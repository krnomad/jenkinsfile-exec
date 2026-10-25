package io.localjenkinsfile.api
import org.junit.jupiter.api.Test
import static org.junit.jupiter.api.Assertions.*
class RegistryTest {
 @Test void rejectsCollisionsAndUnknownSteps() {
   def p = [id: { 'sample' }, apiVersion: { 1 }, steps: { [hello: new StepDefinition('hello', false, { c, a -> 'ok' })] }] as StepPlugin
   def registry = new StepRegistry([p])
   assertEquals('sample', registry.ownerOf('hello'))
   assertThrows(IllegalArgumentException) { new StepRegistry([p,p]) }
   assertThrows(PipelineException) { registry.require('missing') }
 }
 @Test void enforcesWorkspaceRequirement() {
   def c = new ExecutionContext(workspace: java.nio.file.Files.createTempDirectory('spi'), workspaceAvailable: false)
   def d = new StepDefinition('file', true, { ctx, args -> 'should not run' })
   assertEquals('WORKSPACE_REQUIRED', assertThrows(PipelineException) { d.call(c, [:]) }.code)
 }
 @Test void pluginOptionSchemasRejectInvalidValuesBeforeExecution() {
  def context=new ExecutionContext(workspace:java.nio.file.Files.createTempDirectory('options'))
  def step=new StepDefinition('sample',false,[prompt:[type:'string',required:true],enabled:[type:'boolean']],{c,a -> a.prompt.toUpperCase()})
  assertEquals('HELLO',step.call(context,[prompt:'hello',enabled:true]))
  assertEquals('INVALID_OPTION',assertThrows(PipelineException) {step.call(context,[enabled:true])}.code)
  assertEquals('INVALID_OPTION',assertThrows(PipelineException) {step.call(context,[prompt:'hello',enabled:'true'])}.code)
  assertEquals('INVALID_OPTION',assertThrows(PipelineException) {step.call(context,[prompt:'hello',typo:true])}.code)
 }

}
