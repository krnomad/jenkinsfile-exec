package io.localjenkinsfile.cli
import io.localjenkinsfile.api.*
class ConsoleInteraction implements UserInteraction {
 boolean isAvailable() {System.console()!=null}
 String ask(String prompt,boolean secret) {
  def console=System.console()
  if(console==null) throw new PipelineException('TTY_REQUIRED','A terminal is required for interactive input')
  if(secret) {char[] chars=console.readPassword('%s: ',prompt);return chars==null ? null : new String(chars)}
  console.readLine('%s: ',prompt)
 }
}
