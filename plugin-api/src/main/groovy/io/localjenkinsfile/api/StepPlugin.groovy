package io.localjenkinsfile.api
interface StepPlugin {
 String id()
 int apiVersion()
 Map<String,StepDefinition> steps()
}
