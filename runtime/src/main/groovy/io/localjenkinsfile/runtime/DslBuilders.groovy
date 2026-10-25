package io.localjenkinsfile.runtime
import io.localjenkinsfile.api.PipelineException

class DslSupport extends GroovyObjectSupport {
 RuntimeScript script
 DslSupport(RuntimeScript script) { this.script=script }
 static Object invokeClosure(Closure c,Object delegate) {
  def cloned=c.rehydrate(delegate,c.owner,c.thisObject)
  cloned.resolveStrategy=Closure.DELEGATE_FIRST
  cloned.call()
 }
 Object propertyMissing(String name) { script.getProperty(name) }
 Object methodMissing(String name,Object a) { script.invokeMethod(name,a) }
}
class PipelineBuilder extends DslSupport {
 PipelineModel model=new PipelineModel()
 PipelineBuilder(RuntimeScript s) { super(s) }
 void agent(Object a) { model.agent=a.toString() }
 void environment(Closure c) { invokeClosure(c,new EnvironmentBuilder(script,model.environment)) }
 void options(Closure c) { invokeClosure(c,new OptionsBuilder(script,model.options,false)) }
 void post(Closure c) { invokeClosure(c,new PostBuilder(script,model.post)) }
 void stages(Closure c) { invokeClosure(c,new StagesBuilder(script,model.stages)) }
}
class StagesBuilder extends DslSupport {
 List<StageModel> stages
 StagesBuilder(RuntimeScript s,List stages) { super(s);this.stages=stages }
 void stage(String name,Closure c) {
  if (stages.any {it.name==name}) throw new PipelineException('INVALID_PIPELINE','Duplicate stage: '+name)
  def b=new StageBuilder(script,name);invokeClosure(c,b);stages.add(b.model)
 }
}
class StageBuilder extends DslSupport {
 StageModel model
 StageBuilder(RuntimeScript s,String name) { super(s);model=new StageModel(name:name) }
 void agent(Object a) {model.agent=a.toString()}
 void environment(Closure c) {invokeClosure(c,new EnvironmentBuilder(script,model.environment))}
 void options(Closure c) {invokeClosure(c,new OptionsBuilder(script,model.options,true))}
 void when(Closure c) {model.when=c}
 void steps(Closure c) {model.steps=c}
 void post(Closure c) {invokeClosure(c,new PostBuilder(script,model.post))}
}
class EnvironmentBuilder extends DslSupport {
 Map target
 EnvironmentBuilder(RuntimeScript s,Map target) {super(s);this.target=target}
 void __environment(String name,Closure value) { target[name]=value }
}
class OptionsBuilder extends DslSupport {
 Map target
 boolean stage
 OptionsBuilder(RuntimeScript s,Map target,boolean stage) {super(s);this.target=target;this.stage=stage}
 void interactive(Object value) {
  if (stage || !(value instanceof Boolean)) throw new PipelineException('INVALID_OPTION','interactive requires boolean at pipeline scope')
  target.interactive=value
 }
 void aiSession(Object value) {
  if (stage || !(value?.toString() in ['shared','isolated'])) throw new PipelineException('INVALID_OPTION','aiSession requires shared or isolated at pipeline scope')
  target.aiSession=value.toString()
 }
 void timeout(Map options) { target.timeout=options }
}
class PostBuilder extends DslSupport {
 Map target
 PostBuilder(RuntimeScript s,Map target) {super(s);this.target=target}
 Object methodMissing(String name,Object a) {
  if (!(name in DeclarativeCompiler.POST)) throw new PipelineException('INVALID_POST','Unsupported post condition '+name)
  target[name]=((Object[])a)[0];null
 }
}
class WhenBuilder extends DslSupport {
 List<Closure> conditions=[]
 WhenBuilder(RuntimeScript s) {super(s)}
 void expression(Closure c) {conditions.add({!!invokeClosure(c,script)})}
 void environment(Map args) {
  if (!(args.keySet() as Set).every {it in ['name','value','ignoreCase']} || !args.containsKey('name') || !args.containsKey('value')) throw new PipelineException('INVALID_WHEN','environment requires name/value')
  String actual=script.context.env[args.name.toString()]
  conditions.add({args.ignoreCase ? actual?.equalsIgnoreCase(args.value.toString()) ?: false : actual==args.value.toString()})
 }
 void allOf(Closure c) {conditions.add({evaluate(script,c,'all')})}
 void anyOf(Closure c) {conditions.add({evaluate(script,c,'any')})}
 void not(Closure c) {conditions.add({!evaluate(script,c,'all')})}
 static boolean evaluate(RuntimeScript s,Closure c,String mode='all') {
  def b=new WhenBuilder(s);invokeClosure(c,b)
  if (!b.conditions) throw new PipelineException('INVALID_WHEN','Empty when condition')
  mode=='any' ? b.conditions.any {it.call()} : b.conditions.every {it.call()}
 }
}
