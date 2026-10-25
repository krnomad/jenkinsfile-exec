package io.localjenkinsfile.api
interface UserInteraction {
 boolean isAvailable()
 String ask(String prompt, boolean secret)
}
