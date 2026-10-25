package io.localjenkinsfile.runtime
import io.localjenkinsfile.api.*

abstract class RuntimeScript extends Script {
 ExecutionContext context
 PipelineRunner runner
 StepsRuntime stepsRuntime
 RunResult result
 private boolean executed
 Object getProperty(String name) {
  switch(name) {
   case 'any':return 'any'
   case 'none':return 'none'
   case 'env':return context.env
   case 'currentBuild':return new BuildView(result)
   default:return super.getProperty(name)
  }
 }
 Object pipeline(Closure c) {
  if (executed) throw new PipelineException('INVALID_PIPELINE','Only one pipeline may execute')
  executed=true
  def b=new PipelineBuilder(this);DslSupport.invokeClosure(c,b)
  runner.execute(b.model,this)
 }
 Object methodMissing(String name,Object args) { stepsRuntime.invoke(name,args as Object[]) }
}
class BuildView {
 RunResult result
 BuildView(RunResult r) { result=r }
 String getCurrentResult() {result.status}
 String getResult() {result.status=='SUCCESS' ? null : result.status}
 void setResult(String s) {result.worsen(s)}
}
