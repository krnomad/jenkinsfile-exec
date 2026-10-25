package io.localjenkinsfile.api
class PipelineAborted extends PipelineException {
 PipelineAborted(String message='Execution interrupted') { super('ABORTED', message) }
}
