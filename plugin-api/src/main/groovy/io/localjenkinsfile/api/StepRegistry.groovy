package io.localjenkinsfile.api
class StepRegistry {
 private final Map<String,StepDefinition> definitions=[:]
 private final Map<String,String> owners=[:]
 StepRegistry(Iterable<StepPlugin> plugins=[]) {
  Set<String> ids=[]
  plugins.each { p ->
   if (p.apiVersion()!=1) throw new IllegalArgumentException("Unsupported plugin API: ${p.id()}")
   if (!ids.add(p.id())) throw new IllegalArgumentException("Duplicate plugin: ${p.id()}")
   p.steps().each { name, definition ->
    if (definitions.containsKey(name)) throw new IllegalArgumentException("Duplicate step: ${name}")
    if (name!=definition.name) throw new IllegalArgumentException("Step name mismatch: ${name}")
    definitions[name]=definition; owners[name]=p.id()
   }
  }
 }
 StepDefinition require(String name) {
  if (!definitions.containsKey(name)) throw new PipelineException('UNKNOWN_STEP',"Unsupported step '${name}' (plugin not installed or step not supported)")
  definitions[name]
 }
 String ownerOf(String name) { owners[name] }
 Set<String> names() { Collections.unmodifiableSet(definitions.keySet()) }
}
