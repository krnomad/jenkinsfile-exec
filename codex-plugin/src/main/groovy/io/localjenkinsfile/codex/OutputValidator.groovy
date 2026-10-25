package io.localjenkinsfile.codex
import com.fasterxml.jackson.databind.ObjectMapper
import com.networknt.schema.*
import io.localjenkinsfile.api.PipelineException
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
class OutputValidator {
 private static final ObjectMapper MAPPER=new ObjectMapper().enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
 static Object validate(Map schema,String text) {
  try {
   // Offline schemas only: never fetch user-controlled remote references.
   rejectReferences(schema)
   def factory=JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012,{builder ->
    builder.schemaLoaders({loaders -> loaders.values({values ->
     values.clear()
     values.add({iri -> throw new IllegalArgumentException('External schema loading is disabled: '+iri)} as com.networknt.schema.resource.SchemaLoader)
    } as java.util.function.Consumer)} as java.util.function.Consumer)
   } as java.util.function.Consumer)
   def compiled=factory.getSchema(MAPPER.readTree(JsonOutput.toJson(schema)))
   def node=MAPPER.readTree(text)
   if (node==null) throw new IllegalArgumentException('Empty JSON output')
   def errors=compiled.validate(node)
   if (errors) throw new IllegalArgumentException(errors.collect {it.message}.join('; '))
   new JsonSlurper().parseText(text)
  } catch(Exception e) {throw new PipelineException('OUTPUT_SCHEMA','AI output does not satisfy outputSchema: '+e.message,e)}
 }
 static void rejectReferences(Object value) {
  if (value instanceof Map) {
   ['$ref','$dynamicRef','$recursiveRef'].each {key ->
    if (value.containsKey(key) && !value[key].toString().startsWith('#')) throw new PipelineException('INVALID_OPTION','Only local outputSchema references are supported')
   }
   value.values().each {rejectReferences(it)}
  } else if (value instanceof List) value.each {rejectReferences(it)}
 }
}
