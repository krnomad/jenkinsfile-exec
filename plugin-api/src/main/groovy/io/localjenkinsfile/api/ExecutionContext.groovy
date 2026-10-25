package io.localjenkinsfile.api
import java.nio.file.Path
class ExecutionContext {
 Path workspace
 Path baseWorkspace
 boolean workspaceAvailable=true
 Map<String,String> env=new LinkedHashMap<>(System.getenv())
 PrintStream log=System.out
 long postTimeoutMillis=30000L
 boolean interactive=false
 String aiSession='shared'
 UserInteraction interaction
 Cancellation cancellation=new Cancellation()
 Map<String,Object> sessions=[:]
 final String runId=UUID.randomUUID().toString()
 void requireWorkspace() {
  if (!workspaceAvailable) throw new PipelineException('WORKSPACE_REQUIRED','This step requires agent any (locally allocated workspace)')
 }
}
