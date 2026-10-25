package io.localjenkinsfile.cli
import org.junit.jupiter.api.*
import org.junit.jupiter.api.io.TempDir
import java.nio.file.*
import groovy.json.JsonSlurper
import static org.junit.jupiter.api.Assertions.*
class MainTest {
 @TempDir Path root
 @Test void cliNameMatchesProject() {
  def output=new ByteArrayOutputStream();def stream=new PrintStream(output)
  assertEquals(0,Main.execute(['--version'],stream,stream))
  assertTrue(output.toString().startsWith('jenkinsfile-exec '))
  output.reset()
  assertEquals(0,Main.execute(['--help'],stream,stream))
  assertTrue(output.toString().contains('Usage: jenkinsfile-exec '))
  assertTrue(output.toString().contains('#!/usr/bin/env jenkinsfile-exec'))
 }
 @Test void runWritesFilesAndMachineReadableSummary() {
  def f=root.resolve('Jenkinsfile');Files.writeString(f,"pipeline { agent any; stages { stage('x') { steps { writeFile file:'hello',text:'world' } } } }")
  def output=new ByteArrayOutputStream();def stream=new PrintStream(output)
  assertEquals(0,Main.execute(['run',f.toString(),'--workspace',root.toString()],stream,stream))
  assertEquals('world',root.resolve('hello').toFile().text)
  def records=Files.list(root.resolve('.local-jenkinsfile/runs')).withCloseable {it.toList()}
  assertEquals(1,records.size())
  assertEquals('SUCCESS',new JsonSlurper().parse(records[0].toFile()).status)
 }
 @Test void checkDoesNotRunAndListDiscoversBundledPlugin() {
  def f=root.resolve('Jenkinsfile');Files.writeString(f,"new File('${root}/never').text='bad'; pipeline { agent any; stages { stage('x') { steps { codex prompt:'hello' } } } }")
  def output=new ByteArrayOutputStream();def stream=new PrintStream(output)
  assertEquals(0,Main.execute(['check',f.toString()],stream,stream))
  assertFalse(Files.exists(root.resolve('never')))
  assertEquals(0,Main.execute(['plugins','list'],stream,stream))
  assertTrue(output.toString().contains('codex'))
 }
 @Test void unknownOptionsAndInvalidScriptsHaveNonzeroExit() {
  def output=new PrintStream(new ByteArrayOutputStream())
  assertEquals(2,Main.execute(['run','--bogus'],output,output))
  def f=root.resolve('Jenkinsfile');Files.writeString(f,"pipeline { agent any; stages { stage('x') { steps { sh 'exit 4' } } } }")
  assertEquals(1,Main.execute(['run',f.toString(),'--workspace',root.toString()],output,output))
 }
 @Test void defaultWorkspaceIsJenkinsfileParentRatherThanInvocationDirectory() {
  Path project=Files.createDirectories(root.resolve('project'))
  Path f=project.resolve('custom.Jenkinsfile')
  Files.writeString(f,"pipeline { agent any; stages { stage('x') { steps { writeFile file:'default-result',text:pwd() } } } }")
  def stream=new PrintStream(new ByteArrayOutputStream())
  assertEquals(0,Main.execute(['run',f.toString()],stream,stream))
  assertEquals(project.toString(),project.resolve('default-result').toFile().text)
  assertTrue(Files.isDirectory(project.resolve('.local-jenkinsfile/runs')))
 }
 @Test void explicitWorkspaceOverridesJenkinsfileLocationAndAnchorsWs() {
  Path project=Files.createDirectories(root.resolve('source'))
  Path injected=root.resolve('injected')
  Path f=project.resolve('Jenkinsfile')
  Files.writeString(f,"pipeline { agent any; stages { stage('x') { steps { ws('selected') { writeFile file:'inside',text:pwd() }; writeFile file:'outside',text:pwd() } } } }")
  def stream=new PrintStream(new ByteArrayOutputStream())
  assertEquals(0,Main.execute(['run',f.toString(),'--workspace',injected.toString()],stream,stream))
  assertEquals(injected.resolve('selected').toString(),injected.resolve('selected/inside').toFile().text)
  assertEquals(injected.toString(),injected.resolve('outside').toFile().text)
  assertFalse(Files.exists(project.resolve('selected')))
  assertTrue(Files.isDirectory(injected.resolve('.local-jenkinsfile/runs')))
 }
 @Test void checkAcceptsWsWithoutCreatingDirectories() {
  Path project=Files.createDirectories(root.resolve('checking'))
  Path f=project.resolve('Jenkinsfile')
  Files.writeString(f,"pipeline { agent any; stages { stage('x') { steps { ws('never') { sh 'true' } } } } }")
  def stream=new PrintStream(new ByteArrayOutputStream())
  assertEquals(0,Main.execute(['check',f.toString()],stream,stream))
  assertFalse(Files.exists(project.resolve('never')))
  assertFalse(Files.exists(project.resolve('.local-jenkinsfile')))
 }

 @Test void relativeCliWorkspaceIsRelativeToInvocationDirectory() {
  Path f=root.resolve('Jenkinsfile')
  Path expected=root.resolve('relative-override')
  Path cwd=Path.of('').toAbsolutePath().normalize()
  Files.writeString(f,"pipeline { agent any; stages { stage('x') { steps { writeFile file:'where',text:pwd() } } } }")
  def stream=new PrintStream(new ByteArrayOutputStream())
  assertEquals(0,Main.execute(['run',f.toString(),'--workspace',cwd.relativize(expected).toString()],stream,stream))
  assertEquals(expected.toString(),expected.resolve('where').toFile().text)
 }

 @Test void implicitRunAcceptsShebangAndGroovyVariables() {
  Path project=Files.createDirectories(root.resolve('executable project'))
  Path f=project.resolve('Jenkinsfile')
  Files.writeString(f,"#!/usr/bin/env jenkinsfile-exec\ndef message='direct'\npipeline { agent any; stages { stage('x') { steps { writeFile file:'result',text:message+':'+pwd() } } } }")
  def stream=new PrintStream(new ByteArrayOutputStream())
  assertEquals(0,Main.execute([f.toString()],stream,stream))
  assertEquals('direct:'+project,project.resolve('result').toFile().text)
 }
 @Test void implicitRunForwardsWorkspaceAndFailureExit() {
  Path f=root.resolve('Jenkinsfile')
  Path injected=root.resolve('override')
  Files.writeString(f,"#!/usr/bin/env jenkinsfile-exec\npipeline { agent any; stages { stage('x') { steps { writeFile file:'where',text:pwd(); sh 'exit 7' } } } }")
  def stream=new PrintStream(new ByteArrayOutputStream())
  assertEquals(1,Main.execute([f.toString(),'--workspace',injected.toString()],stream,stream))
  assertEquals(injected.toString(),injected.resolve('where').toFile().text)
  assertFalse(Files.exists(root.resolve('where')))
 }
 @Test void shebangCheckIsStaticAndUnknownCommandsRemainErrors() {
  Path f=root.resolve('Jenkinsfile')
  Files.writeString(f,"#!/usr/bin/env jenkinsfile-exec\nnew File('${root}/never').text='bad'\npipeline { agent any; stages { stage('x') { steps { sh 'true' } } } }")
  def stream=new PrintStream(new ByteArrayOutputStream())
  assertEquals(0,Main.execute(['check',f.toString()],stream,stream))
  assertFalse(Files.exists(root.resolve('never')))
  assertEquals(2,Main.execute(['unknown-command'],stream,stream))
  assertEquals(2,Main.execute([root.toString()],stream,stream))
 }
}
