package io.localjenkinsfile.cli

import io.localjenkinsfile.api.*
import io.localjenkinsfile.runtime.*
import groovy.json.JsonOutput
import java.nio.file.*
import java.net.URLClassLoader

class Main {
 static void main(String[] args) {System.exit(execute(args.toList()))}
 static int execute(List<String> arguments,PrintStream out=System.out,PrintStream err=System.err,UserInteraction interaction=new ConsoleInteraction()) {
  List<String> args=new ArrayList<>(arguments)
  URLClassLoader loader
  try {
   if(args.empty || args[0] in ['--help','-h','help']) {usage(out);return 0}
   if(args[0]=='--version') {out.println('jenkinsfile-exec 0.1.0-SNAPSHOT');return 0}
   // An OS shebang passes the script path first, without a `run` subcommand.
   // Explicit commands keep priority even if a same-named file exists.
   String command=args[0] in ['run','check','plugins'] ? args.remove(0) :
    Files.isRegularFile(Path.of(args[0])) ? 'run' : args.remove(0)
   if(!(command in ['run','check','plugins'])) throw new IllegalArgumentException('Unknown command: '+command)
   Map opts=[:];List<String> positional=[]
   while(!args.empty) {
    String value=args.remove(0)
    if(value.startsWith('--')) {
     if(!(value in ['--workspace','--plugin-dir']) || args.empty) throw new IllegalArgumentException('Unknown or incomplete option: '+value)
     opts[value]=args.remove(0)
    } else positional << value
   }
   if(command!='plugins' && positional.size()>1) throw new IllegalArgumentException('Expected one Jenkinsfile path')
   Path input=command=='plugins' ? null : Path.of(positional ? positional[0] : 'Jenkinsfile').toAbsolutePath().normalize()
   Path workspace=opts.containsKey('--workspace') ? Path.of(opts['--workspace']).toAbsolutePath().normalize() : input?.parent ?: Path.of('.').toAbsolutePath().normalize()
   Path pluginDir=Path.of(opts['--plugin-dir'] ?: workspace.resolve('.local-jenkinsfile/plugins').toString()).toAbsolutePath()
   List<URL> urls=[]
   if(Files.isDirectory(pluginDir)) Files.list(pluginDir).withCloseable {stream ->
    stream.filter {it.toString().endsWith('.jar')}.sorted().forEach {urls << it.toUri().toURL()}
   }
   loader=new URLClassLoader(urls as URL[],Main.class.classLoader)
   def plugins=ServiceLoader.load(StepPlugin,loader).toList()
   def registry=new StepRegistry(plugins)
   def runner=new PipelineRunner(registry)
   if(command=='plugins') {
    if(positional!=['list']) throw new IllegalArgumentException('Usage: plugins list')
    out.println('core: '+StepsRuntime.NAMES.sort().join(', '))
    plugins.each {out.println("${it.id()} (SPI ${it.apiVersion()}): ${it.steps().keySet().sort().join(', ')}")}
    return 0
   }
   String source=Files.readString(input)
   if(command=='check') {
    runner.check(source,input.fileName.toString());out.println('Static check passed. Runtime/Jenkins compatibility is not implied.');return 0
   }
   def cancellation=new Cancellation()
   Thread executing=Thread.currentThread()
   def finished=new java.util.concurrent.CountDownLatch(1)
   Thread hook=new Thread({cancellation.cancelled=true;executing.interrupt();try {finished.await(35,java.util.concurrent.TimeUnit.SECONDS)} catch(InterruptedException ignored) { }} as Runnable,'pipeline-shutdown')
   Runtime.runtime.addShutdownHook(hook)
   RunResult result
   try {
   result=runner.run(source,workspace,[log:out,interaction:interaction,cancellation:cancellation])
   Path reportDir=workspace.resolve('.local-jenkinsfile/runs');Files.createDirectories(reportDir)
   Map report=[runId:result.runId,status:result.status,stages:result.stages,error:result.error ? [code:result.error instanceof PipelineException ? result.error.code : 'EXECUTION_ERROR',message:result.error.message] : null,additionalErrors:result.additionalErrors.collect {it.message}]
   Files.writeString(reportDir.resolve(result.runId+'.json'),JsonOutput.prettyPrint(JsonOutput.toJson(report)))
   out.println('[pipeline] '+result.status)
   if(result.error) err.println((result.error instanceof PipelineException ? result.error.code : 'EXECUTION_ERROR')+': '+result.error.message)
   return result.status=='SUCCESS' ? 0 : result.status=='ABORTED' ? 130 : 1
   } finally {finished.countDown();try {Runtime.runtime.removeShutdownHook(hook)} catch(IllegalStateException ignored) { }}
  } catch(IllegalArgumentException e) {err.println(e.message);usage(err);return 2}
  catch(Exception | ServiceConfigurationError e) {err.println(e.class.simpleName+': '+e.message);return 2}
  finally {loader?.close()}
 }
 static void usage(PrintStream out) {
  out.println('Usage: jenkinsfile-exec run|check [Jenkinsfile] [--workspace PATH] [--plugin-dir PATH]')
  out.println('       jenkinsfile-exec FILE [--workspace PATH] [--plugin-dir PATH]')
  out.println('       jenkinsfile-exec plugins list [--plugin-dir PATH]')
  out.println('Executable Jenkinsfile: #!/usr/bin/env jenkinsfile-exec, then chmod +x Jenkinsfile and ./Jenkinsfile')
  out.println('Default workspace: Jenkinsfile directory. --workspace overrides it; relative overrides use the current directory.')
 }
}
