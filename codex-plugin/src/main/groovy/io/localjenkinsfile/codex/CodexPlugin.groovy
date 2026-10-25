package io.localjenkinsfile.codex

import io.localjenkinsfile.api.*
import groovy.json.JsonOutput

class CodexPlugin implements StepPlugin {
 private final List<String> executable
 static final Set<String> OPTIONS=['prompt','systemPrompt','model','reasoningEffort','profile','yolo','newSession','outputSchema'] as Set
 CodexPlugin() {this([System.getenv('LOCAL_JENKINSFILE_CODEX') ?: 'codex'])}
 CodexPlugin(List<String> executable) {this.executable=new ArrayList<>(executable)}
 String id() {'codex'}
 int apiVersion() {1}
 Map<String,StepDefinition> steps() {[codex:new StepDefinition('codex',true,[prompt:[type:'string',required:true],systemPrompt:[type:'string'],model:[type:'string'],reasoningEffort:[type:'string'],profile:[type:'string'],yolo:[type:'boolean'],newSession:[type:'boolean'],outputSchema:[type:'object']],{ExecutionContext c,Map options -> execute(c,options)})]}
 AiResult execute(ExecutionContext c,Map raw) {
  Map options=normalize(raw)
  if(c.interactive && !c.interaction?.available) throw new PipelineException('TTY_REQUIRED','interactive true requires terminal input')
  boolean isolated=c.aiSession=='isolated' || options.newSession==true
  Map session=isolated ? null : c.sessions.codex as Map
  if (session?.invalid) throw new PipelineException('SESSION_INVALID','Previous Codex step failed; use newSession: true')
  ['systemPrompt','profile'].each {key ->
   if(session && options.containsKey(key) && options[key]!=session[key]) throw new PipelineException('SESSION_OPTIONS_CHANGED',"${key} is locked for the shared session; use newSession: true")
  }
  Map effective=[:]
  if(session) effective.putAll(session.findAll {k,v -> k in ['systemPrompt','profile','model','reasoningEffort']})
  effective.putAll(options)
  boolean yolo=effective.get('yolo',true)
  List<String> command=new ArrayList<>(executable)
  if (effective.profile) command.addAll(['-p',effective.profile.toString()])
  command.addAll(['app-server','--listen','stdio://'])
  JsonRpcClient rpc
  String threadId=session?.threadId
  String turnId
  boolean turnSubmitted=false
  boolean turnFinished=false
  try {
   rpc=new JsonRpcClient(command,c)
   rpc.call('initialize',[clientInfo:[name:'jenkinsfile_exec',version:'0.1.0'],capabilities:[experimentalApi:true]])
   rpc.send([method:'initialized',params:[:]])
   def models
   if (effective.model || effective.reasoningEffort) {
    models=rpc.call('model/list',[limit:100,includeHidden:true]).data
    def selected=models?.find {it.model==effective.model || it.id==effective.model}
    if (!selected && effective.model) throw new PipelineException('UNSUPPORTED_MODEL_OPTION','Model is not advertised by installed Codex: '+effective.model)
    if (selected && effective.reasoningEffort && !selected.supportedReasoningEfforts?.any {it.reasoningEffort==effective.reasoningEffort}) throw new PipelineException('UNSUPPORTED_MODEL_OPTION','Reasoning effort not supported by selected model')
   }
   Map threadArgs=[cwd:c.workspace.toString(),approvalPolicy:yolo?'never':'on-request',approvalsReviewer:'user']
   if (yolo) threadArgs.sandbox='danger-full-access'
   if (effective.model) threadArgs.model=effective.model
   // baseInstructions is the documented, schema-confirmed replacement instruction field.
   if (effective.containsKey('systemPrompt')) threadArgs.baseInstructions=effective.systemPrompt
   Map threadResult
   if(threadId) {threadArgs.threadId=threadId;threadResult=rpc.call('thread/resume',threadArgs)}
   else threadResult=rpc.call('thread/start',threadArgs)
   if(threadResult.approvalPolicy!=threadArgs.approvalPolicy || threadResult.approvalsReviewer!='user' || (yolo && threadResult.sandbox?.type!='dangerFullAccess'))
    throw new PipelineException('POLICY_MISMATCH','Codex did not apply requested approval/sandbox policy')
   threadId=threadResult.thread?.id
   if(!threadId) throw new PipelineException('PROTOCOL_ERROR','Codex omitted thread id')
   if(effective.reasoningEffort && !effective.model && threadResult.model) {
    def selected=models?.find {it.model==threadResult.model || it.id==threadResult.model}
    if (selected && !selected.supportedReasoningEfforts?.any {it.reasoningEffort==effective.reasoningEffort}) throw new PipelineException('UNSUPPORTED_MODEL_OPTION','Reasoning effort not supported by effective model')
   }
   session=[threadId:threadId,systemPrompt:effective.systemPrompt,profile:effective.profile,model:effective.model,reasoningEffort:effective.reasoningEffort,invalid:false]
   if(!isolated) c.sessions.codex=session
   Map turnArgs=[threadId:threadId,cwd:c.workspace.toString(),approvalPolicy:yolo?'never':'on-request',approvalsReviewer:'user',input:[[type:'text',text:effective.prompt,text_elements:[]]]]
   if(yolo) turnArgs.sandboxPolicy=[type:'dangerFullAccess']
   if(effective.model) turnArgs.model=effective.model
   if(effective.reasoningEffort) turnArgs.effort=effective.reasoningEffort
   if(effective.outputSchema) turnArgs.outputSchema=effective.outputSchema
   turnSubmitted=true
   def started=rpc.call('turn/start',turnArgs)
   turnId=started.turn?.id
   if (!turnId) throw new PipelineException('PROTOCOL_ERROR','Codex omitted turn id')
   Map<String,Map> messages=[:]
   while(true) {
    c.cancellation.check()
    Map event=rpc.nextEvent()
    Map p=event.params ?: [:]
    if (event.containsKey('id') && event.method) {
     handleRequest(rpc,event,c,threadId,turnId)
     continue
    }
    if(p.threadId!=threadId) continue
    if(event.method=='item/completed' && p.turnId==turnId && p.item?.type=='agentMessage') {
     messages[p.item.id.toString()]=p.item as Map
     if(p.item.text) c.log.println('[codex] '+p.item.text)
    }
    if(event.method=='turn/completed' && p.turn?.id==turnId) {
     turnFinished=true
     if(p.turn.status!='completed') throw new PipelineException('AI_FAILED','Codex turn '+p.turn.status+': '+(p.turn.error?.message ?: 'not completed'))
     def finals=messages.values().findAll {it.phase=='final_answer'}
     String text=(finals ?: messages.values()).collect {it.text ?: ''}.join('\n')
     Object data=effective.outputSchema ? OutputValidator.validate(effective.outputSchema as Map,text) : null
     return new AiResult(text,data,threadId,turnId)
    }
   }
  } catch(Throwable e) {
   if(session && !isolated) session.invalid=true
   boolean confirmed=false
   if(rpc && threadId && turnId && !turnFinished) confirmed=rpc.interruptTurn(threadId,turnId)
   if(rpc && threadId && turnId && !turnFinished && !confirmed) c.log.println('[codex] Cancellation not confirmed; terminating owned process. No automatic replay.')
   if(e instanceof PipelineException) throw e
   if(e instanceof InterruptedException) throw new PipelineAborted()
   if(e instanceof VirtualMachineError) throw e
   throw new PipelineException(turnSubmitted?'RESULT_UNKNOWN':'CLI_ERROR','Codex step failed: '+e.message,e)
  } finally {rpc?.close()}
 }
 static Map normalize(Map raw) {
  def extra=raw.keySet().findAll {!(it.toString() in OPTIONS)}
  if(extra) throw new PipelineException('INVALID_OPTION','Unsupported Codex options: '+extra.join(', '))
  Map o=[:]
  raw.each {k,v -> o[k.toString()]=v instanceof GString ? v.toString() : v}
  if(!(o.prompt instanceof CharSequence) || !o.prompt.toString().trim()) throw new PipelineException('INVALID_OPTION','codex requires a nonempty prompt')
  ['systemPrompt','model','reasoningEffort','profile'].each {k -> if(o.containsKey(k) && !(o[k] instanceof CharSequence)) throw new PipelineException('INVALID_OPTION',k+' requires a string')}
  ['yolo','newSession'].each {k -> if(o.containsKey(k) && !(o[k] instanceof Boolean)) throw new PipelineException('INVALID_OPTION',k+' requires a boolean')}
  if(o.containsKey('outputSchema')) {
   if(!(o.outputSchema instanceof Map) || o.outputSchema.isEmpty()) throw new PipelineException('INVALID_OPTION','outputSchema requires a nonempty Map')
   OutputValidator.rejectReferences(o.outputSchema)
  }
  o
 }
 private static String ask(ExecutionContext c,String prompt,boolean secret) {
  def executor=java.util.concurrent.Executors.newSingleThreadExecutor(Thread.ofVirtual().factory())
  def answer=executor.submit({c.interaction.ask(prompt,secret)} as java.util.concurrent.Callable<String>)
  try {
   while(true) {
    c.cancellation.check()
    try {
     String value=answer.get(50,java.util.concurrent.TimeUnit.MILLISECONDS)
     c.cancellation.check()
     if(value==null) throw new PipelineAborted('Terminal input closed')
     return value
    } catch(java.util.concurrent.TimeoutException ignored) { }
    catch(InterruptedException e) {throw new PipelineAborted()}
    catch(java.util.concurrent.ExecutionException e) {throw e.cause}
   }
  } finally {answer.cancel(true);executor.shutdownNow()}
 }
 private static void handleRequest(JsonRpcClient rpc,Map event,ExecutionContext c,String threadId,String turnId) {
  String method=event.method
  Map p=event.params ?: [:]
  if(p.threadId!=null && (p.threadId!=threadId || (p.turnId!=null && p.turnId!=turnId))) {
   rpc.send([id:event.id,error:[code:-32602,message:'Request does not belong to active turn']]);return
  }
  boolean approval=method.endsWith('/requestApproval') || method=='mcpServer/elicitation/request'
  boolean input=method in ['item/tool/requestUserInput','tool/requestUserInput']
  if(!c.interactive) {
   if(method in ['item/commandExecution/requestApproval','item/fileChange/requestApproval']) rpc.send([id:event.id,result:[decision:'cancel']])
   else if(method=='item/permissions/requestApproval') rpc.send([id:event.id,result:[permissions:[:],scope:'turn']])
   else if(method=='mcpServer/elicitation/request') rpc.send([id:event.id,result:[action:'cancel',content:null]])
   else rpc.send([id:event.id,error:[code:-32000,message:'Noninteractive input is unavailable']])
   throw new PipelineException(approval?'APPROVAL_REQUIRED':input?'INPUT_REQUIRED':'UNSUPPORTED_REQUEST','Codex requires '+method+' during noninteractive execution')
  }
  if(method in ['item/commandExecution/requestApproval','item/fileChange/requestApproval']) {
   String answer=ask(c,"${p.command ?: p.reason ?: 'Approve file changes?'} [yes/no/cancel]",false)
   String decision=answer?.toLowerCase() in ['y','yes'] ? 'accept' : answer?.toLowerCase() in ['n','no'] ? 'decline' : 'cancel'
   rpc.send([id:event.id,result:[decision:decision]])
   if(decision=='cancel') throw new PipelineAborted('Approval cancelled by user')
  } else if(method=='item/permissions/requestApproval') {
   String answer=ask(c,'Grant requested permissions '+JsonOutput.toJson(p.permissions)+'? [yes/no]',false)
   rpc.send([id:event.id,result:[permissions:answer?.toLowerCase() in ['y','yes'] ? p.permissions : [:],scope:'turn']])
  } else if(input) {
   Map answers=[:]
   p.questions.each {q -> answers[q.id]=[answers:[ask(c,q.question.toString()+' '+JsonOutput.toJson(q.options ?: []),q.isSecret==true)]]}
   rpc.send([id:event.id,result:[answers:answers]])
  } else {
   rpc.send([id:event.id,error:[code:-32601,message:'Unsupported interactive request']])
   throw new PipelineException('UNSUPPORTED_REQUEST','Unsupported interactive Codex request: '+method)
  }
 }
}
