package io.localjenkinsfile.runtime

import io.localjenkinsfile.api.*
import org.codehaus.groovy.control.*
import org.codehaus.groovy.control.customizers.ASTTransformationCustomizer
import groovy.transform.ThreadInterrupt
import java.nio.file.*

class PipelineRunner {
 final StepRegistry registry
 PipelineRunner(StepRegistry registry=new StepRegistry()) {
  this.registry=registry
  def collision=registry.names().intersect(StepsRuntime.NAMES)
  if (collision) throw new IllegalArgumentException('Plugin shadows core steps: '+collision)
 }
 private CompilerConfiguration configuration() {
  def c=new CompilerConfiguration(scriptBaseClass:RuntimeScript.name)
  c.addCompilationCustomizers(new DeclarativeCompiler((StepsRuntime.NAMES+registry.names()) as Set))
  c.addCompilationCustomizers(new ASTTransformationCustomizer(ThreadInterrupt))
  c
 }
 void check(String text,String name='Jenkinsfile') {
  // Stop before local annotation transforms or script class initialization can run.
  def source=SourceUnit.create(name,text)
  source.parse();source.completePhase();source.nextPhase();source.convert()
  def validator=new DeclarativeCompiler((StepsRuntime.NAMES+registry.names()) as Set)
  source.AST.classes.findAll {it.script}.each {validator.call(source,null,it)}
  if(source.errorCollector.hasErrors()) throw new MultipleCompilationErrorsException(source.errorCollector)
 }
 RunResult run(String text,Path workspace,Map settings=[:]) {
  def ctx=new ExecutionContext(workspace:workspace.toAbsolutePath().normalize())
  ctx.baseWorkspace=ctx.workspace
  if(settings.postTimeoutMillis!=null) ctx.postTimeoutMillis=settings.postTimeoutMillis as long
  if (settings.cancellation) ctx.cancellation=settings.cancellation
  if (settings.log) ctx.log=settings.log
  if (settings.interaction) ctx.interaction=settings.interaction
  if (settings.env) ctx.env.putAll(settings.env)
  def result=new RunResult(runId:ctx.runId)
  def loader=new GroovyClassLoader(getClass().classLoader,configuration())
  try {
   Files.createDirectories(ctx.workspace)
   check(text)
   def script=(RuntimeScript)loader.parseClass(text,'Jenkinsfile').getDeclaredConstructor().newInstance()
   script.context=ctx;script.runner=this;script.result=result
   script.stepsRuntime=new StepsRuntime(script,registry)
   script.run()
  } catch (Throwable e) { recordError(result,unwrap(e)) }
  finally { loader.close(); Thread.interrupted() }
  result
 }
 Object execute(PipelineModel m,RuntimeScript s) {
  def c=s.context
  c.workspaceAvailable=m.agent=='any'
  if(c.workspaceAvailable) setWorkspaceEnvironment(c)
  c.interactive=m.options.get('interactive',false)
  c.aiSession=m.options.get('aiSession','shared')
  if (c.interactive && !c.interaction?.available) throw new PipelineException('TTY_REQUIRED','interactive true requires an attached terminal')
  try {
   withEnvironment(s,m.environment) {
    try {
     withTimeout(s,m.options.timeout) {
      m.stages.each { st ->
       if (s.result.status in ['FAILURE','ABORTED']) {s.result.stages << [name:st.name,status:'SKIPPED'];return}
       executeStage(st,s)
      }
     }
    } catch(Throwable e) {recordError(s.result,unwrap(e))}
    finally {runPost(m.post,s,s.result.status)}
   }
  } catch(Throwable e) {recordError(s.result,unwrap(e))}
  s.result
 }
 private void executeStage(StageModel st,RuntimeScript s) {
  def entry=[name:st.name,status:'SUCCESS'];s.result.stages << entry
  def c=s.context; boolean oldAgent=c.workspaceAvailable
  def oldEnv=new LinkedHashMap(c.env)
  c.workspaceAvailable=st.agent==null ? oldAgent : st.agent=='any'
  if(c.workspaceAvailable && !oldAgent) setWorkspaceEnvironment(c)
  c.log.println("[stage] ${st.name}")
  s.stepsRuntime.stageEntry=entry
  try {
   withTimeout(s,st.options.timeout) {
    if (st.when && !WhenBuilder.evaluate(s,st.when)) {entry.status='SKIPPED';return}
    applyEnvironment(s,st.environment)
    DslSupport.invokeClosure(st.steps,s)
   }
  } catch(Throwable e) {entry.status=isAborted(e)?'ABORTED':'FAILURE';recordError(s.result,unwrap(e))}
  finally {
   // Jenkins' stage post guard evaluates build/error result before its per-condition warning result.
   boolean postEligible=st.post.keySet().any {it in ['always','cleanup'] || it.toUpperCase()==s.result.status}
   if(entry.status!='SKIPPED' && postEligible) {
    def order=['SUCCESS':0,'UNSTABLE':1,'FAILURE':2,'ABORTED':3]
    runPost(st.post,s,order[entry.status]>order[s.result.status] ? entry.status : s.result.status)
   }
   c.env.clear();c.env.putAll(oldEnv)
   c.workspaceAvailable=oldAgent;s.stepsRuntime.stageEntry=null
  }
 }
 private static void setWorkspaceEnvironment(ExecutionContext context) {
  context.env.WORKSPACE=context.baseWorkspace.toString()
  context.env.WORKSPACE_TMP=context.baseWorkspace.toString()+'@tmp'
 }
 private static void applyEnvironment(RuntimeScript s,Map<String,Closure> values) {
  values.each {name,value ->
   def v=DslSupport.invokeClosure(value,s)
   if (v==null) throw new PipelineException('INVALID_ENVIRONMENT','Null environment value for '+name)
   s.context.env[name]=v.toString()
  }
 }
 void withEnvironment(RuntimeScript s,Map<String,Closure> values,Closure work) {
  def old=new LinkedHashMap(s.context.env)
  try {
   applyEnvironment(s,values)
   work.call()
  } finally {s.context.env.clear();s.context.env.putAll(old)}
 }
 static Object withTimeout(RuntimeScript s,Map options,Closure work) {
  options ? s.stepsRuntime.withTimeout(options,work) : work.call()
 }
 static void runPost(Map<String,Closure> post,RuntimeScript s,String status) {
  Thread.interrupted()
  def original=s.context.cancellation
  s.context.cancellation=new Cancellation()
  String effectiveStatus=status
  try {
   s.stepsRuntime.withTimeout([time:s.context.postTimeoutMillis,unit:'MILLISECONDS']) {
   ['always','aborted','failure','success','unstable','cleanup'].each {name ->
    if (post[name] && (name in ['always','cleanup'] || name.toUpperCase()==effectiveStatus)) {
     try {DslSupport.invokeClosure(post[name],s)} catch(Throwable e) {
      recordError(s.result,unwrap(e))
      effectiveStatus=isAborted(e)?'ABORTED':'FAILURE'
      if(s.stepsRuntime.stageEntry) s.stepsRuntime.stageEntry.status=effectiveStatus
     }
    }
   }
   }
  } catch(Throwable e) {recordError(s.result,unwrap(e))}
  finally {s.context.cancellation=original;Thread.interrupted()}
 }
 static boolean isAborted(Throwable e) {e instanceof PipelineAborted || e instanceof InterruptedException}
 static Throwable unwrap(Throwable e) {
  while ((e instanceof java.lang.reflect.InvocationTargetException || e instanceof java.util.concurrent.ExecutionException) && e.cause) e=e.cause
  e
 }
 static void recordError(RunResult r,Throwable e) {
  r.worsen(isAborted(e)?'ABORTED':'FAILURE')
  if (!r.error) r.error=e
  else if (!r.error.is(e)) r.additionalErrors << e
 }
}
