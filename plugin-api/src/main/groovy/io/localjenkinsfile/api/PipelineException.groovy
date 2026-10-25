package io.localjenkinsfile.api
class PipelineException extends RuntimeException {
 final String code
 PipelineException(String code, String message, Throwable cause=null) { super(message,cause); this.code=code }
}
