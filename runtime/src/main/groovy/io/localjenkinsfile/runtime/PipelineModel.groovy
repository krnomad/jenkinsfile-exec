package io.localjenkinsfile.runtime

class PipelineModel {
 String agent
 Map<String,Closure> environment=[:]
 Map options=[:]
 List<StageModel> stages=[]
 Map<String,Closure> post=[:]
}
class StageModel {
 String name
 String agent
 Map<String,Closure> environment=[:]
 Map options=[:]
 Closure when
 Closure steps
 Map<String,Closure> post=[:]
}
class RunResult {
 String status='SUCCESS'
 Throwable error
 List<Throwable> additionalErrors=[]
 List<Map> stages=[]
 String runId
 void worsen(String value) {
  def order=['SUCCESS':0,'UNSTABLE':1,'FAILURE':2,'ABORTED':3]
  if (!order.containsKey(value)) throw new IllegalArgumentException('Invalid build result: '+value)
  if (order[value]>order[status]) status=value
 }
}
