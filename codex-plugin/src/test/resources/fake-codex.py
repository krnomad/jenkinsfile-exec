import sys,json,uuid,os
thread_id=None
turn_id='turn-1'
mode=''
def send(v):
 print(json.dumps(v),flush=True)
def response(i,result): send({'id':i,'result':result})
def complete(status='completed',text='done'):
 if status=='completed': send({'method':'item/completed','params':{'threadId':thread_id,'turnId':turn_id,'item':{'id':'msg-1','type':'agentMessage','text':text,'phase':'final_answer'}}})
 send({'method':'turn/completed','params':{'threadId':thread_id,'turn':{'id':turn_id,'status':status,'items':[]}}})
for line in sys.stdin:
 v=json.loads(line)
 if 'method' not in v:
  if mode=='approval' and v.get('id')==900:
   decision=v.get('result',{}).get('decision')
   if decision=='accept': complete(text='approved')
  elif mode=='input' and v.get('id')==901:
   complete(text='answered')
  continue
 m=v['method'];i=v.get('id');p=v.get('params',{})
 trace=os.environ.get('FAKE_TRACE')
 if trace:
  with open(trace,'a') as f:f.write(json.dumps(v)+'\n')
 if m=='initialize': response(i,{'userAgent':'fake/1','platformFamily':'unix','platformOs':'linux'})
 elif m=='initialized': pass
 elif m=='model/list': response(i,{'data':[{'id':'test-model','model':'test-model','supportedReasoningEfforts':[{'reasoningEffort':'high','description':'High'}]}],'nextCursor':None})
 elif m=='thread/start':
  thread_id=str(uuid.uuid4());response(i,{'thread':{'id':thread_id,'turns':[]},'model':p.get('model','test-model'),'reasoningEffort':'high','approvalPolicy':p.get('approvalPolicy'),'approvalsReviewer':'user','cwd':p.get('cwd'),'sandbox':{'type':'readOnly' if os.environ.get('FAKE_COERCE') else 'dangerFullAccess' if p.get('sandbox')=='danger-full-access' else 'workspaceWrite'}})
  if os.environ.get('FAKE_BLOCK_READ'):
   import time;time.sleep(30)
 elif m=='thread/resume':
  thread_id=p['threadId'];response(i,{'thread':{'id':thread_id,'turns':[]},'model':p.get('model','test-model'),'reasoningEffort':'high','approvalPolicy':p.get('approvalPolicy'),'approvalsReviewer':'user','cwd':p.get('cwd'),'sandbox':{'type':'readOnly' if os.environ.get('FAKE_COERCE') else 'dangerFullAccess' if p.get('sandbox')=='danger-full-access' else 'workspaceWrite'}})
 elif m=='turn/start':
  mode=p['input'][0]['text']
  if mode=='disconnect': sys.exit(0)
  if mode=='early': complete(text='early result')
  response(i,{'turn':{'id':turn_id,'status':'inProgress','items':[]}})
  if mode=='early':continue
  if mode=='approval': send({'id':900,'method':'item/commandExecution/requestApproval','params':{'threadId':thread_id,'turnId':turn_id,'itemId':'cmd-1','command':'touch blocked','reason':'Need permission'}})
  elif mode=='input': send({'id':901,'method':'item/tool/requestUserInput','params':{'threadId':thread_id,'turnId':turn_id,'itemId':'tool-1','questions':[{'id':'q','header':'choice','question':'Pick one','options':[{'label':'yes','description':'Proceed'}]}]}})
  elif mode=='failed': complete('failed')
  elif mode=='wait': pass
  elif p.get('outputSchema'): complete(text='not json' if mode=='invalid' else '{"answer":"yes"}')
  else: complete(text=os.environ.get('SCOPED_VALUE','done'))
 elif m=='turn/interrupt':
  response(i,{});complete('interrupted')
 else: send({'id':i,'error':{'code':-32601,'message':'Unknown method '+m}})
