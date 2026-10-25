package io.localjenkinsfile.codex

import groovy.json.*
import io.localjenkinsfile.api.*
import java.util.concurrent.*

/** Owns a dedicated stdio process. Never connects to an existing daemon or remote endpoint. */
class JsonRpcClient implements Closeable {
 final ExecutionContext context
 final Process process
 private final BufferedWriter writer
 private final BlockingQueue<Map> incoming=new LinkedBlockingQueue<>()
 private final Deque<Map> backlog=new ArrayDeque<>()
 private final ExecutorService writes=Executors.newSingleThreadExecutor(Thread.ofVirtual().factory())
 private long counter=0
 JsonRpcClient(List<String> command,ExecutionContext context) {
  this.context=context
  def pb=new ProcessBuilder(command).directory(context.workspace.toFile())
  pb.environment().clear();pb.environment().putAll(context.env)
  try {process=pb.start()} catch(IOException e) {throw new PipelineException('CLI_UNAVAILABLE','Cannot start installed Codex CLI: '+command[0],e)}
  writer=new BufferedWriter(new OutputStreamWriter(process.outputStream,java.nio.charset.StandardCharsets.UTF_8))
  Thread.startDaemon('codex-json') {
   try {
    def reader=new BufferedReader(new InputStreamReader(process.inputStream,java.nio.charset.StandardCharsets.UTF_8))
    String line
    while ((line=reader.readLine())!=null) {
     try {
      def v=new JsonSlurper().parseText(line)
      if (!(v instanceof Map)) throw new IllegalArgumentException('Expected JSON object')
      incoming.put(v as Map)
     } catch(Exception e) {incoming.put([_transportError:'Malformed Codex protocol output'])}
    }
   } catch(IOException ignored) { }
   finally {incoming.offer([_eof:true])}
  }
  Thread.startDaemon('codex-stderr') {
   try {process.errorStream.transferTo(context.log)} catch(IOException ignored) { }
  }
 }
 void send(Map value,boolean cancellable=true,long timeoutMs=30000L) {
  String json=JsonOutput.toJson(value)
  def pending=writes.submit({
   writer.write(json);writer.newLine();writer.flush()
  } as Callable)
  long end=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(timeoutMs)
  try {
   while(true) {
    if(cancellable) context.cancellation.check()
    if(System.nanoTime()>end) throw new PipelineException('RESULT_UNKNOWN','Codex protocol write timed out')
    try {pending.get(50,TimeUnit.MILLISECONDS);return}
    catch(TimeoutException ignored) { }
    catch(InterruptedException e) {throw new PipelineAborted()}
    catch(ExecutionException e) {throw new PipelineException('RESULT_UNKNOWN','Codex connection closed before outcome was confirmed',e.cause)}
   }
  } finally {
   if(!pending.done) {
    // Killing the pipe owner releases a blocked native write; interrupt alone cannot.
    process.destroyForcibly();pending.cancel(true)
   }
  }
 }
 long request(String method,Map params) {long id=++counter;send([id:id,method:method,params:params]);id}
 Map call(String method,Map params,long timeoutMs=30000L) {
  long id=request(method,params)
  long end=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(timeoutMs)
  while (true) {
   Map event=readIncoming(end)
   if (event.id==id && !event.containsKey('method')) {
    if (event.error) throw new PipelineException('CLI_ERROR',"Codex ${method}: ${event.error.message}")
    return (event.result ?: [:]) as Map
   }
   backlog.add(event)
  }
 }
 Map nextEvent() {backlog.isEmpty() ? readIncoming(Long.MAX_VALUE) : backlog.removeFirst()}
 private Map readIncoming(long end) {
  while (true) {
   context.cancellation.check()
   if (System.nanoTime()>end) throw new PipelineException('CLI_TIMEOUT','Codex protocol response timed out')
   Map event
   try {event=incoming.poll(50,TimeUnit.MILLISECONDS)} catch(InterruptedException e) {throw new PipelineAborted()}
   if (event==null) continue
   if (event._eof) throw new PipelineException('RESULT_UNKNOWN','Codex disconnected before a confirmed result')
   if (event._transportError) throw new PipelineException('PROTOCOL_ERROR',event._transportError.toString())
   return event
  }
 }
 /** Best effort cancellation confirmation ignores the expired parent deadline. */
 boolean interruptTurn(String threadId,String turnId) {
  Thread.interrupted()
  try {
   long id=++counter
   send([id:id,method:'turn/interrupt',params:[threadId:threadId,turnId:turnId]],false,3000)
   long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(3)
   while(System.nanoTime()<end) {
    Map event=backlog.pollFirst() ?: incoming.poll(50,TimeUnit.MILLISECONDS)
    if (event?._eof) return false
    if (event?.method=='turn/completed' && event.params?.threadId==threadId && event.params?.turn?.id==turnId) return true
   }
  } catch(Exception ignored) { }
  false
 }
 void close() {
  def descendants=process.toHandle().descendants().toList()
  process.destroy()
  try {
   if (!process.waitFor(300,TimeUnit.MILLISECONDS)) process.destroyForcibly()
   process.waitFor(300,TimeUnit.MILLISECONDS)
  } catch(InterruptedException ignored) {process.destroyForcibly();Thread.interrupted()}
  descendants.each { if(it.alive) it.destroyForcibly() }
  writes.shutdownNow()
  // Do not block on a writer's monitor; its native syscall may still be unwinding.
  Thread.startDaemon('codex-writer-close') {try {writer.close()} catch(IOException ignored) { }}
 }
}
