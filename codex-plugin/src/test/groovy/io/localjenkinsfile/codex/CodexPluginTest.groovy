package io.localjenkinsfile.codex
import io.localjenkinsfile.api.*
import groovy.json.JsonSlurper
import org.junit.jupiter.api.*
import org.junit.jupiter.api.io.TempDir
import java.nio.file.*
import static org.junit.jupiter.api.Assertions.*
class CodexPluginTest {
 @TempDir Path root
 CodexPlugin plugin
 ExecutionContext ctx
 @BeforeEach void setup() {
  plugin=new CodexPlugin(['python3',Path.of(getClass().getResource('/fake-codex.py').toURI()).toString()])
  ctx=new ExecutionContext(workspace:root,log:new PrintStream(new ByteArrayOutputStream()))
  ctx.env.FAKE_TRACE=root.resolve('trace.jsonl').toString()
 }
 def invokeStep(Map opts) { plugin.steps().codex.call(ctx,opts) }
 List trace() { root.resolve('trace.jsonl').toFile().readLines().collect {new JsonSlurper().parseText(it)} }
 @Test void structuredResultAndSharedSessionRetainExplicitThreadId() {
  def schema=[type:'object',properties:[answer:[type:'string']],required:['answer'],additionalProperties:false]
  def a=invokeStep([prompt:'structured',model:'test-model',reasoningEffort:'high',systemPrompt:'instructions',outputSchema:schema])
  assertEquals('yes',a.data.answer)
  ctx.env.SCOPED_VALUE='second'
  def b=invokeStep([prompt:'next'])
  assertEquals(a.sessionId,b.sessionId)
  assertEquals('second',b.text)
  def t=trace()
  assertEquals('danger-full-access',t.find {it.method=='thread/start'}.params.sandbox)
  assertEquals('never',t.find {it.method=='thread/start'}.params.approvalPolicy)
  assertEquals('instructions',t.find {it.method=='thread/start'}.params.baseInstructions)
  assertEquals(a.sessionId,t.find {it.method=='thread/resume'}.params.threadId)
 }
 @Test void noninteractiveApprovalCancelsTurnAndFails() {
  def ex=assertThrows(PipelineException) {invokeStep([prompt:'approval',yolo:false])}
  assertEquals('APPROVAL_REQUIRED',ex.code)
  assertTrue(trace().any {it.method=='turn/interrupt'})
  assertEquals('on-request',trace().find {it.method=='thread/start'}.params.approvalPolicy)
  assertEquals('SESSION_INVALID',assertThrows(PipelineException) {invokeStep([prompt:'next'])}.code)
 }
 @Test void interactiveApprovalAndInputReturnAtTurnCompletion() {
  ctx.interactive=true
  ctx.interaction=[isAvailable:{true},ask:{String prompt,boolean secret -> 'yes'}] as UserInteraction
  assertEquals('approved',invokeStep([prompt:'approval',yolo:false]).text)
  assertEquals('answered',invokeStep([prompt:'input',yolo:false]).text)
 }
 @Test void inputDoesNotHangNoninteractively() {
  assertEquals('INPUT_REQUIRED',assertThrows(PipelineException) {invokeStep([prompt:'input'])}.code)
 }
 @Test void independentCallDoesNotReplaceSharedSession() {
  def a=invokeStep([prompt:'first']);def b=invokeStep([prompt:'second',newSession:true]);def c=invokeStep([prompt:'third'])
  assertNotEquals(a.sessionId,b.sessionId);assertEquals(a.sessionId,c.sessionId)
  ctx.aiSession='isolated'
  assertNotEquals(invokeStep([prompt:'four']).sessionId,invokeStep([prompt:'five']).sessionId)
 }
 @Test void unknownOutcomeDoesNotReplay() {
  assertEquals('RESULT_UNKNOWN',assertThrows(PipelineException) {invokeStep([prompt:'disconnect'])}.code)
  assertEquals(1,trace().count {it.method=='turn/start'})
 }
 @Test void rejectsUnknownOptionsBadSchemaAndUnsupportedEffort() {
  assertEquals('INVALID_OPTION',assertThrows(PipelineException) {invokeStep([prompt:'x',typo:true])}.code)
  assertFalse(root.resolve('trace.jsonl').toFile().exists())
  assertEquals('UNSUPPORTED_MODEL_OPTION',assertThrows(PipelineException) {invokeStep([prompt:'x',model:'test-model',reasoningEffort:'extreme'])}.code)
  assertEquals('OUTPUT_SCHEMA',assertThrows(PipelineException) {invokeStep([prompt:'invalid',newSession:true,outputSchema:[type:'object']])}.code)
 }
 @Test void preservesEarlyCompletionAndLocksSharedInstructions() {
  def a=invokeStep([prompt:'early',systemPrompt:'one']);assertEquals('early result',a.text)
  assertEquals('SESSION_OPTIONS_CHANGED',assertThrows(PipelineException) {invokeStep([prompt:'x',systemPrompt:'two'])}.code)
 }
 @Test void cancellationInterruptsTheActiveTurn() {
  ctx.cancellation.deadlines << System.nanoTime()+500_000_000L
  assertThrows(PipelineAborted) {invokeStep([prompt:'wait'])}
  assertTrue(trace().any {it.method=='turn/interrupt'})
 }
 @Test void failsWhenYoloPolicyIsNotApplied() {
  ctx.env.FAKE_COERCE='1'
  assertEquals('POLICY_MISMATCH',assertThrows(PipelineException) {invokeStep([prompt:'first'])}.code)
  assertFalse(trace().any {it.method=='turn/start'})
 }
 @Test void schemaMismatchAndFailedTurnAreFailures() {
  assertEquals('OUTPUT_SCHEMA',assertThrows(PipelineException) {invokeStep([prompt:'structured',outputSchema:[type:'object',required:['missing']]])}.code)
  assertEquals('AI_FAILED',assertThrows(PipelineException) {invokeStep([prompt:'failed',newSession:true])}.code)
 }

 @Test void timeoutWhileWaitingForApprovalDoesNotBlockOrApproveLate() {
  ctx.interactive=true
  ctx.interaction=[isAvailable:{true},ask:{String prompt,boolean secret ->
   ctx.cancellation.deadlines << System.nanoTime()+100_000_000L
   Thread.sleep(1000)
   'yes'
  }] as UserInteraction
  long start=System.nanoTime()
  assertThrows(PipelineAborted) {invokeStep([prompt:'approval',yolo:false])}
  assertTrue((System.nanoTime()-start)/1e9 < 0.8)
 }

 @Test void rejectsRemoteDynamicReferencesBeforeStartingCli() {
  assertEquals('INVALID_OPTION',assertThrows(PipelineException) {invokeStep([prompt:'x',outputSchema:['$dynamicRef':'http://127.0.0.1:1/schema']])}.code)
  assertFalse(Files.exists(root.resolve('trace.jsonl')))
 }
 @Test void structuredOutputMustContainExactlyOneJsonDocument() {
  ['{} garbage','{} {}'].each {text ->
   assertEquals('OUTPUT_SCHEMA',assertThrows(PipelineException) {OutputValidator.validate([type:'object'],text)}.code)
  }
 }
 @Test void cancellationUnblocksAWriterWhenServerStopsReading() {
  ctx.env.FAKE_BLOCK_READ='1'
  def rpc=new JsonRpcClient(['python3',Path.of(getClass().getResource('/fake-codex.py').toURI()).toString()],ctx)
  def executor=java.util.concurrent.Executors.newSingleThreadExecutor(Thread.ofVirtual().factory())
  try {
   rpc.call('initialize',[:]);rpc.call('thread/start',[cwd:root.toString()])
   ctx.cancellation.deadlines << System.nanoTime()+100_000_000L
   def future=executor.submit({
    assertThrows(PipelineAborted) {rpc.send([method:'turn/start',params:[prompt:'x'*1000000]])}
   } as java.util.concurrent.Callable)
   future.get(2,java.util.concurrent.TimeUnit.SECONDS)
  } finally {rpc.process.destroyForcibly();rpc.close();executor.shutdownNow()}
 }

}
