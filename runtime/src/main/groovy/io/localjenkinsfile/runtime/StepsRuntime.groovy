package io.localjenkinsfile.runtime

import io.localjenkinsfile.api.*
import java.nio.file.*
import java.util.concurrent.TimeUnit
import io.localjenkinsfile.runtime.process.UnixProcessGroup
import io.localjenkinsfile.runtime.process.ProcessGroupMain

class StepsRuntime {
 static final Set<String> NAMES=['script','sh','dir','ws','pwd','echo','fileExists','readFile','writeFile','error','withEnv','timeout','retry','catchError','step'] as Set
 private final WorkspaceLeases workspaceLeases=new WorkspaceLeases()
 final RuntimeScript script
 Map stageEntry
 final StepRegistry registry
 StepsRuntime(RuntimeScript s,StepRegistry r) {script=s;registry=r}
 ExecutionContext getContext() {script.context}
 Object invoke(String name,Object[] args) {
  context.cancellation.check()
  switch(name) {
   case 'script': requireCount(name,args,1);return DslSupport.invokeClosure(args[0] as Closure,script)
   case 'echo': requireCount(name,args,1);context.log.println(args[0]);return null
   case 'error': requireCount(name,args,1);throw new PipelineException('STEP_FAILED',args[0].toString())
   case 'sh': requireCount(name,args,1);return shell(args[0] instanceof Map ? args[0] as Map : [script:args[0]])
   case 'pwd':requireCount(name,args,0);context.requireWorkspace();return context.workspace.toString()
   case 'dir':
    requireCount(name,args,2);context.requireWorkspace()
    Path old=context.workspace
    try {context.workspace=resolve(args[0]);Files.createDirectories(context.workspace);return DslSupport.invokeClosure(args[1] as Closure,script)}
    finally {context.workspace=old}
   case 'ws':return workspace(args)
   case 'withEnv':
    requireCount(name,args,2)
    def old=new LinkedHashMap(context.env)
    try {
     (args[0] as List).each {value ->
      String text=value.toString();int pos=text.indexOf('=')
      if (pos<1) throw new PipelineException('INVALID_ARGUMENT','withEnv expects NAME=value')
      def key=text.substring(0,pos); def v=text.substring(pos+1)
      if (key.contains('+')) {key=key.substring(0,key.indexOf('+')); v=v+File.pathSeparator+(context.env[key] ?: '')}
      if (v=='') context.env.remove(key) else context.env[key]=v
     }
     return DslSupport.invokeClosure(args[1] as Closure,script)
    } finally {context.env.clear();context.env.putAll(old)}
   case 'fileExists':
    requireCount(name,args,1);context.requireWorkspace();return Files.exists(resolve(args[0] instanceof Map ? args[0].file : args[0]))
   case 'readFile':
    requireCount(name,args,1);context.requireWorkspace()
    def readArgs=args[0] instanceof Map ? args[0] as Map : [file:args[0]]
    allowed(readArgs,['file','encoding']);return Files.readString(resolve(readArgs.file),java.nio.charset.Charset.forName(readArgs.encoding ?: 'UTF-8'))
   case 'writeFile':
    requireCount(name,args,1);context.requireWorkspace();Map w=args[0] as Map;allowed(w,['file','text','encoding'])
    Path target=resolve(w.file);Files.createDirectories(target.parent);Files.writeString(target,w.text?.toString() ?: '',java.nio.charset.Charset.forName(w.encoding ?: 'UTF-8'));return null
   case 'timeout':requireCount(name,args,2);return withTimeout(args[0] as Map,{DslSupport.invokeClosure(args[1] as Closure,script)})
   case 'retry':
    requireCount(name,args,2)
    def opt=args[0] instanceof Map ? args[0] : [count:args[0]];allowed(opt,['count']);int count=opt.count as int
    if(count<1) throw new PipelineException('INVALID_ARGUMENT','retry count must be positive')
    for(int i=0;i<count;i++) {
     try {return DslSupport.invokeClosure(args[1] as Closure,script)}
     catch(Throwable e) {if (PipelineRunner.isAborted(e) || (e instanceof PipelineException && e.code=='RESULT_UNKNOWN') || i==count-1) throw e}
    }
    return null
   case 'catchError':
    Map options=args[0] instanceof Map ? args[0] : [:];allowed(options,['buildResult','stageResult','catchInterruptions','message'])
    Closure body=args[-1] as Closure
    try {return DslSupport.invokeClosure(body,script)}
    catch(Throwable e) {
     if (e instanceof VirtualMachineError || (PipelineRunner.isAborted(e) && options.catchInterruptions==false)) throw e
     script.result.worsen(PipelineRunner.isAborted(e)?'ABORTED':(options.buildResult ?: 'FAILURE').toString())
     if(stageEntry && options.stageResult) {
      def order=['SUCCESS':0,'UNSTABLE':1,'FAILURE':2,'ABORTED':3]
      String desired=options.stageResult.toString()
      if(!order.containsKey(desired)) throw new PipelineException('INVALID_ARGUMENT','Invalid stageResult')
      if(order[desired]>order[stageEntry.status]) stageEntry.status=desired
     }
     if (options.message) context.log.println(options.message)
     context.log.println('[caught] '+e.message);return null
    }
   case 'step':
    requireCount(name,args,1);Map options=new LinkedHashMap(args[0] as Map);String type=options.remove('$class')?.toString()
    if (!type) throw new PipelineException('INVALID_ARGUMENT','step requires registered $class (the step name)')
    return registry.require(type).call(context,options)
   default:
    if (args.length!=1 || !(args[0] instanceof Map)) throw new PipelineException('INVALID_ARGUMENT',"${name} expects named options")
    return registry.require(name).call(context,args[0] as Map)
  }
 }
 private Object workspace(Object[] args) {
  if(!(args.length in [1,2]) || !(args[-1] instanceof Closure)) throw new PipelineException('INVALID_ARGUMENT','ws expects an optional path/dir and a closure')
  Object path=args.length==2 ? args[0] : null
  if(path instanceof Map) {allowed(path as Map,['dir']);path=path.dir}
  if(path!=null && !(path instanceof CharSequence)) throw new PipelineException('INVALID_ARGUMENT','ws dir requires a string')
  context.requireWorkspace()
  Path base=context.baseWorkspace
  Path requested=path==null || path.toString().isEmpty() ? base : base.resolve(path.toString()).normalize().toAbsolutePath()
  def lease=workspaceLeases.acquire(requested,context.cancellation,[base])
  Path previous=context.workspace
  Map<String,String> oldValues=[:]
  Set<String> present=[]
  ['WORKSPACE','WORKSPACE_TMP'].each {name ->
   if(context.env.containsKey(name)) {present.add(name);oldValues[name]=context.env[name]}
  }
  try {
   Files.createDirectories(lease.path)
   context.workspace=lease.path
   context.env.WORKSPACE=lease.path.toString()
   context.env.WORKSPACE_TMP=lease.path.toString()+'@tmp'
   context.log.println('[ws] '+lease.path)
   return DslSupport.invokeClosure(args[-1] as Closure,script)
  } finally {
   context.workspace=previous
   ['WORKSPACE','WORKSPACE_TMP'].each {name ->if(present.contains(name)) context.env[name]=oldValues[name] else context.env.remove(name)}
   lease.close()
  }
 }
 Path resolve(Object value) {
  if (value==null) throw new PipelineException('INVALID_ARGUMENT','Missing file/path')
  context.workspace.resolve(value.toString()).normalize().toAbsolutePath()
 }
 Object withTimeout(Map options,Closure body) {
  allowed(options,['time','unit'])
  long amount=options.time as long
  if (amount<=0) throw new PipelineException('INVALID_ARGUMENT','timeout time must be positive')
  TimeUnit unit
  try {unit=TimeUnit.valueOf((options.unit ?: 'MINUTES').toString().toUpperCase())}
  catch(IllegalArgumentException e) {throw new PipelineException('INVALID_ARGUMENT','Unsupported timeout unit')}
  long deadline=System.nanoTime()+unit.toNanos(amount)
  context.cancellation.deadlines << deadline
  Thread current=Thread.currentThread()
  def timer=java.util.concurrent.Executors.newSingleThreadScheduledExecutor({Runnable r -> Thread.ofPlatform().daemon().unstarted(r)} as java.util.concurrent.ThreadFactory)
  def future=timer.schedule({current.interrupt()} as Runnable,amount,unit)
  try {
   def v=body.call();context.cancellation.check();return v
  } catch(InterruptedException e) {throw new PipelineAborted('Timeout exceeded')}
  finally {future.cancel(false);timer.shutdownNow();context.cancellation.deadlines.remove(deadline);Thread.interrupted()}
 }
 private Object shell(Map options) {
  allowed(options,['script','returnStatus','returnStdout','encoding','label'])
  context.requireWorkspace()
  if (!(options.script instanceof CharSequence)) throw new PipelineException('INVALID_ARGUMENT','sh requires script text')
  if (options.returnStatus && options.returnStdout) throw new PipelineException('INVALID_ARGUMENT','returnStatus and returnStdout cannot both be true')
  Path tmp=Files.createTempFile('local-jenkinsfile-','.sh')
  Process process
  Thread outThread,errThread
  def stdout=new ByteArrayOutputStream()
  try {
   String content=options.script.toString();Files.writeString(tmp,content)
   List<String> command
   if (content.startsWith('#!')) {tmp.toFile().setExecutable(true,true);command=[tmp.toString()]}
   else command=['/bin/sh','-xe',tmp.toString()]
   List<String> grouped=[Path.of(System.getProperty('java.home'),'bin','java').toString(),'-cp',UnixProcessGroup.helperClasspath(),ProcessGroupMain.name]
   grouped.addAll(command)
   def pb=new ProcessBuilder(grouped).directory(context.workspace.toFile())
   pb.environment().clear();pb.environment().putAll(context.env)
   if (context.interactive) pb.redirectInput(ProcessBuilder.Redirect.INHERIT)
   process=pb.start()
   if (!context.interactive) process.outputStream.close()
   // Consume only the helper handshake; the remaining bytes are the shell's actual stdout.
   def ready=new ByteArrayOutputStream()
   while(true) {
    context.cancellation.check()
    if(process.inputStream.available()>0) {
     int b=process.inputStream.read()
     if(b==10) break
     if(b<0 || ready.size()>128) throw new PipelineException('PROCESS_GROUP','Invalid process-group startup')
     ready.write(b)
    } else {
     if(!process.alive) throw new PipelineException('PROCESS_GROUP','Process-group helper failed to start')
     Thread.sleep(5)
    }
   }
   if(ready.toString('UTF-8')!='LOCAL_JENKINSFILE_GROUP_READY') throw new PipelineException('PROCESS_GROUP','Invalid process-group handshake')
   def output=options.returnStdout ? stdout : context.log
   Process child=process
   outThread=Thread.startDaemon('shell-stdout') {try {child.inputStream.transferTo(output)} catch(IOException ignored) { }}
   errThread=Thread.startDaemon('shell-stderr') {try {child.errorStream.transferTo(context.log)} catch(IOException ignored) { }}
   while (!process.waitFor(50,TimeUnit.MILLISECONDS)) context.cancellation.check()
   UnixProcessGroup.terminate(process.pid())
   outThread.join(1000);errThread.join(1000);context.cancellation.check()
   int code=process.exitValue()
   if (options.returnStatus) return code
   if (code!=0) throw new PipelineException('SHELL_EXIT',"sh exited with status ${code}")
   options.returnStdout ? stdout.toString(options.encoding ?: 'UTF-8') : null
  } catch(InterruptedException e) {throw new PipelineAborted()}
  finally {
   if (process) {
    UnixProcessGroup.terminate(process.pid())
    def descendants=process.toHandle().descendants().toList()
    descendants.reverseEach {it.destroy()};process.destroy()
    descendants.findAll {it.alive}.each {it.destroyForcibly()}
    if (process.alive) process.destroyForcibly()
   }
   Files.deleteIfExists(tmp)
  }
 }
 static void allowed(Map options,Collection<String> names) {
  def extra=options.keySet().findAll {!(it.toString() in names)}
  if (extra) throw new PipelineException('INVALID_ARGUMENT','Unsupported options: '+extra.join(', '))
 }
 static void requireCount(String name,Object[] args,int n) {
  if (args.length!=n) throw new PipelineException('INVALID_ARGUMENT',"${name} expects ${n} arguments")
 }
}
