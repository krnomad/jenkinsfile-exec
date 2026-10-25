package io.localjenkinsfile.runtime
import org.junit.jupiter.api.*
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.nio.file.Files
import static org.junit.jupiter.api.Assertions.*
class RuntimeTest {
 @TempDir Path root
 def runText(String text) { new PipelineRunner().run(text, root) }
 @Test void executesGroovyWithSharedLexicalVariablesAndDeferredSteps() {
  def r = runText("""
   def prefix = 'old'
   def values = []
   pipeline {
    agent any
    stages {
     stage('first') { steps { script {
      for (def n in [1, 2, 3]) { if (n % 2 == 1) { values << n } }
      prefix = 'new'
     } } }
     stage('second') { steps { writeFile file: 'result', text: "\${prefix}:\${values.join(',')}" } }
    }
   }
  """)
  assertEquals('SUCCESS', r.status)
  assertEquals('new:1,3', root.resolve('result').toFile().text)
 }
 @Test void checkNeverExecutesTopLevelCodeAndRejectsUnsupportedStructure() {
  new PipelineRunner().check("new File('${root}/never').text='bad'; pipeline { agent any; stages { stage('x') { steps { echo 'x' } } } }")
  assertFalse(root.resolve('never').toFile().exists())
  assertThrows(Exception) { new PipelineRunner().check("pipeline { agent any; stages { stage('x') { parallel { } } } }") }
  assertThrows(Exception) { new PipelineRunner().check("pipeline { agent any; stages { stage('x') { steps { madeUpStep 'x' } } } }") }
 }
 @Test void scopesEnvironmentAndDirectoriesEvenAfterFailure() {
  def r = runText('''
   pipeline {
    agent any
    environment { OUTER = 'pipeline' }
    stages {
     stage('scoped') {
      environment { OUTER = 'stage'; INNER = "${env.OUTER}-inner" }
      steps { script {
       try { dir('child') { withEnv(['OUTER=wrapped']) { sh 'printf "%s" "$OUTER" > value'; error 'expected' } } } catch (Exception ex) { }
       writeFile file: 'stage', text: "${env.OUTER}:${env.INNER}"
      } }
     }
     stage('restored') { steps { writeFile file: 'outer', text: "${env.OUTER}:${env.INNER ?: 'absent'}" } }
    }
   }
  ''')
  assertEquals('SUCCESS', r.status)
  assertEquals('wrapped',root.resolve('child/value').toFile().text)
  assertEquals('stage:stage-inner',root.resolve('stage').toFile().text)
  assertEquals('pipeline:absent',root.resolve('outer').toFile().text)
 }
 @Test void agentNoneRequiresStageAllocation() {
  def r=runText("pipeline { agent none; stages { stage('x') { steps { sh 'true' } } } }")
  assertEquals('FAILURE', r.status)
  assertEquals('WORKSPACE_REQUIRED',r.error.code)
  assertEquals('SUCCESS',runText("pipeline { agent none; stages { stage('x') { agent any; steps { sh 'true' } } } }").status)
 }
 @Test void conditionsAndPostFollowResult() {
  def r=runText('''
  pipeline { agent any; stages {
    stage('skip') { when { expression { false } }; steps { writeFile file:'bad',text:'bad' } }
    stage('fail') { steps { sh 'exit 7' }; post { failure { writeFile file:'stage-post',text:'failed' } } }
    stage('never') { steps { writeFile file:'never',text:'bad' } }
   }; post { always { writeFile file:'always',text:'yes' }; failure { writeFile file:'failure',text:'yes' }; cleanup { writeFile file:'cleanup',text:'yes' } }
  }
  ''')
  assertEquals('FAILURE',r.status)
  assertEquals(['SKIPPED','FAILURE','SKIPPED'],r.stages*.status)
  assertFalse(root.resolve('bad').toFile().exists())
  assertTrue(root.resolve('stage-post').toFile().exists())
  assertTrue(root.resolve('cleanup').toFile().exists())
 }
 @Test void supportsReturnStatusStdoutRetryAndCatchError() {
  def r=runText('''
  def tries=0
  pipeline { agent any; stages { stage('x') { steps { script {
   assert sh(script:'exit 9',returnStatus:true)==9
   assert sh(script:'printf hello',returnStdout:true)=='hello'
   retry(3) { tries++; if (tries<3) { error 'again' } }
   assert tries==3
   catchError(buildResult:'UNSTABLE') { error 'caught' }
   writeFile file:'continued',text:'yes'
  } } } }; post { unstable { writeFile file:'unstable',text:'yes' } } }
  ''')
  assertEquals('UNSTABLE',r.status)
  assertTrue(root.resolve('continued').toFile().exists())
  assertTrue(root.resolve('unstable').toFile().exists())
 }
 @Test void timeoutTerminatesShellAndRunsPost() {
  long start=System.nanoTime()
  def r=runText("pipeline { agent any; stages { stage('x') { steps { timeout(time:150,unit:'MILLISECONDS') { sh 'sleep 20' } } } }; post { aborted { writeFile file:'aborted',text:'yes' } } }")
  assertEquals('ABORTED',r.status)
  assertTrue((System.nanoTime()-start)/1e9 < 6)
  assertTrue(root.resolve('aborted').toFile().exists())
 }
 @Test void malformedEnvironmentAndDuplicateDirectivesAreRejected() {
  assertThrows(Exception) { new PipelineRunner().check("pipeline { agent any; agent none; stages { stage('x') { steps { echo 'x' } } } }") }
  assertThrows(Exception) { new PipelineRunner().check("pipeline { agent any; environment { sh 'touch bad' }; stages { stage('x') { steps { echo 'x' } } } }") }
 }
 @Test void stageTimeoutRunsStagePostExactlyOnce() {
  def r=runText("pipeline { agent any; stages { stage('x') { options { timeout(time:100,unit:'MILLISECONDS') }; steps { sh 'sleep 5' }; post { aborted { writeFile file:'stage-aborted',text:'yes' } } } } }")
  assertEquals('ABORTED',r.status)
  assertTrue(Files.exists(root.resolve('stage-aborted')))
  assertEquals(0,r.additionalErrors.size())
 }
 @Test void stageWarningAloneDoesNotEnterPostAsVerifiedOnJenkins() {
  def r=runText("pipeline { agent any; stages { stage('x') { steps { catchError(buildResult:'SUCCESS',stageResult:'UNSTABLE') { error 'caught' } }; post { unstable { writeFile file:'stage-unstable',text:'yes' } } } } }")
  assertEquals('SUCCESS',r.status)
  assertEquals('UNSTABLE',r.stages[0].status)
  assertFalse(Files.exists(root.resolve('stage-unstable')))
 }
 @Test void whenRunsBeforeStageEnvironmentAsVerifiedOnJenkins() {
  def r=runText('''
    def sideEffects=0
    pipeline { agent any; environment { OUTER='yes' }; stages {
      stage('skipped') { environment { TARGET='yes' }; when { environment name:'TARGET',value:'yes' }; steps { echo "${++sideEffects}" } }
      stage('pipeline-environment') { when { environment name:'OUTER',value:'yes' }; steps { writeFile file:'selected',text:"${sideEffects}" } }
    } }
  ''')
  assertEquals('SUCCESS',r.status)
  assertEquals('SKIPPED',r.stages[0].status)
  assertEquals('0',root.resolve('selected').toFile().text)
 }
 @Test void checkDoesNotResolveGrabOrExecuteAnnotationTransforms() {
  new PipelineRunner().check("@Grab('invalid-local-jenkinsfile:never-download:0') def x=1; pipeline { agent any; stages { stage('x') { steps { echo 'x' } } } }")
 }

 @Test void failureInAlwaysActivatesFailurePostAndPreservesOriginalError() {
  def r=runText("pipeline { agent any; stages { stage('x') { steps { echo 'ok' } } }; post { always { error 'original-post-failure' }; failure { writeFile file:'failed-post',text:'yes' }; cleanup { error 'cleanup-failure' } } }")
  assertEquals('FAILURE',r.status)
  assertTrue(Files.exists(root.resolve('failed-post')))
  assertEquals('original-post-failure',r.error.message)
  assertEquals('cleanup-failure',r.additionalErrors[0].message)
 }

 @Test void backgroundChildrenAreTerminatedEvenAfterShellHasExited() {
  Long pid=null
  try {
   def r=runText("pipeline { agent any; stages { stage('x') { steps { timeout(time:1,unit:'SECONDS') { sh 'sleep 8 & echo \$! > child.pid; sleep 0.1' } } } } }")
   pid=Long.parseLong(root.resolve('child.pid').toFile().text.trim())
   Thread.sleep(100)
   assertFalse(ProcessHandle.of(pid).map {it.alive}.orElse(false))
  } finally {if(pid) ProcessHandle.of(pid).ifPresent {it.destroyForcibly()}}
 }
 @Test @Timeout(3) void postBudgetInterruptsPureGroovyLoops() {
  long start=System.nanoTime()
  def r=new PipelineRunner().run("pipeline { agent any; stages { stage('x') { steps { echo 'ok' } } }; post { always { script { while(true) { def x=1+1 } } } } }",root,[postTimeoutMillis:100])
  assertEquals('ABORTED',r.status)
  assertTrue((System.nanoTime()-start)/1e9<1)
 }
 @Test void whenConditionsShortCircuit() {
  def r=runText("pipeline { agent any; stages { stage('yes') { when { anyOf { expression { true }; expression { error 'must-not-run' } } }; steps { writeFile file:'selected',text:'yes' } }; stage('no') { when { allOf { expression { false }; expression { error 'must-not-run' } } }; steps { error 'must-not-run' } } } }")
  assertEquals('SUCCESS',r.status)
  assertEquals('yes',root.resolve('selected').toFile().text)
  assertEquals('SKIPPED',r.stages[1].status)
 }

 @Test void wsScopesFilesShellAndEnvironmentAndRestoresAfterFailure() {
  def r=runText('''
   def target='selected'
   pipeline { agent any; stages { stage('ws') { steps { script {
    def base=pwd()
    assert env.WORKSPACE==base
    dir('sub') {
     try {
      ws(target) {
       assert pwd()=="${base}/selected"
       assert env.WORKSPACE==pwd()
       assert env.WORKSPACE_TMP=="${pwd()}@tmp"
       sh 'printf "%s" "$WORKSPACE" > shell-workspace'
       dir('child') {
        assert env.WORKSPACE=="${base}/selected"
        writeFile file:'file',text:'inside'
       }
       error 'expected'
      }
     } catch(Exception ex) { assert ex.message=='expected' }
     assert pwd()=="${base}/sub"
     assert env.WORKSPACE==base
    }
    assert pwd()==base
    writeFile file:'restored',text:pwd()
   } } } } }
  ''')
  assertEquals('SUCCESS',r.status, r.error?.toString())
  assertEquals(root.resolve('selected').toString(),root.resolve('selected/shell-workspace').toFile().text)
  assertEquals('inside',root.resolve('selected/child/file').toFile().text)
  assertEquals(root.toString(),root.resolve('restored').toFile().text)
 }
 @Test void nestedWsAllocatesSuffixAndReleasesItAfterBlock() {
  def r=runText('''
   pipeline { agent any; stages { stage('ws') { steps { script {
    def base=pwd()
    ws('shared') {
     assert pwd()=="${base}/shared"
     ws(dir:'shared') { assert pwd()=="${base}/shared@2"; writeFile file:'nested',text:'yes' }
     assert pwd()=="${base}/shared"
    }
    ws('shared') {assert pwd()=="${base}/shared"}
    ws {assert pwd()=="${base}@2"}
    assert pwd()==base
   } } } } }
  ''')
  assertEquals('SUCCESS',r.status,r.error?.toString())
  assertEquals('yes',root.resolve('shared@2/nested').toFile().text)
 }
 @Test void wsSupportsAbsolutePathsAndRequiresAgent() {
  Path outside=root.resolve('absolute')
  def r=runText("pipeline { agent any; stages { stage('x') { steps { ws(dir:'${outside}') { writeFile file:'actual',text:pwd() } } } } }")
  assertEquals('SUCCESS',r.status,r.error?.toString())
  assertEquals(outside.toString(),outside.resolve('actual').toFile().text)
  def noAgent=runText("pipeline { agent none; stages { stage('x') { steps { ws('blocked') { echo 'never' } } } } }")
  assertEquals('WORKSPACE_REQUIRED',noAgent.error.code)
  assertFalse(Files.exists(root.resolve('blocked')))
 }
 @Test void wsRejectsInvalidOptionsBeforeRunningBody() {
  ["ws(123) { echo 'never' }", "ws(path:'wrong') { echo 'never' }", "ws('no-body')"].each {step ->
   def r=runText("pipeline { agent any; stages { stage('x') { steps { ${step} } } } }")
   assertEquals('FAILURE',r.status)
   assertEquals('INVALID_ARGUMENT',r.error.code)
  }
 }
 @Test void wsChangesPluginContextWithoutResettingRunSessionStorage() {
  def plugin=[id:{'workspace-observer'},apiVersion:{1},steps:{[observe:new io.localjenkinsfile.api.StepDefinition('observe',true,{c,a ->
   c.sessions.saved='preserved'
   [directory:c.workspace.toString(),environment:c.env.WORKSPACE]
  })]}] as io.localjenkinsfile.api.StepPlugin
  def runner=new PipelineRunner(new io.localjenkinsfile.api.StepRegistry([plugin]))
  def r=runner.run('''
   pipeline { agent any; stages { stage('plugin') { steps { script {
    ws('ai-work') {
     def observed=observe([:])
     assert observed.directory==pwd()
     assert observed.environment==pwd()
     writeFile file:'plugin',text:observed.directory
    }
   } } } } }
  ''',root)
  assertEquals('SUCCESS',r.status,r.error?.toString())
  assertEquals(root.resolve('ai-work').toString(),root.resolve('ai-work/plugin').toFile().text)
 }

 @Test void wsTimeoutReleasesLeaseBeforeAnotherBlockInSameRun() {
  def r=runText('''
   pipeline { agent any; stages { stage('ws') { steps { script {
    def base=pwd()
    try { timeout(time:100,unit:'MILLISECONDS') { ws('timed') { Thread.sleep(2000) } } }
    catch(Exception expected) { }
    assert pwd()==base
    ws('timed') { assert pwd()=="${base}/timed"; writeFile file:'reused',text:'yes' }
   } } } } }
  ''')
  assertEquals('SUCCESS',r.status,r.error?.toString())
  assertTrue(Files.exists(root.resolve('timed/reused')))
 }

}
