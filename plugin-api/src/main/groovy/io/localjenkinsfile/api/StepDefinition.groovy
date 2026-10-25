package io.localjenkinsfile.api
class StepDefinition {
 final String name
 final boolean workspaceRequired
 final Map<String,Map> options
 final Closure implementation
 StepDefinition(String name,boolean workspaceRequired,Closure implementation) {this(name,workspaceRequired,[:],implementation)}
 StepDefinition(String name,boolean workspaceRequired,Map<String,Map> options,Closure implementation) {
  this.name=name;this.workspaceRequired=workspaceRequired;this.options=options.asImmutable();this.implementation=implementation
 }
 Object call(ExecutionContext context,Map arguments) {
  context.cancellation.check()
  if(workspaceRequired) context.requireWorkspace()
  Map values=[:];arguments.each {k,v -> values[k.toString()]=v instanceof GString ? v.toString() : v}
  if(!options.isEmpty()) {
   def extra=values.keySet()-options.keySet()
   if(extra) throw new PipelineException('INVALID_OPTION',"Unsupported ${name} options: ${extra}")
   options.each {key,schema ->
    if(schema.required && !values.containsKey(key)) throw new PipelineException('INVALID_OPTION',"${name} requires ${key}")
    if(values.containsKey(key)) {
     Object value=values[key]
     boolean valid=switch(schema.type) {
      case 'string' -> value instanceof CharSequence
      case 'boolean' -> value instanceof Boolean
      case 'object' -> value instanceof Map
      case 'array' -> value instanceof List
      case 'number' -> value instanceof Number
      default -> false
     }
     if(!valid) throw new PipelineException('INVALID_OPTION',"${name}.${key} requires ${schema.type}")
    }
   }
  }
  implementation.call(context,values)
 }
}
