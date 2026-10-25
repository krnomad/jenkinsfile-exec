package io.localjenkinsfile.api
class Cancellation {
 volatile boolean cancelled
 final List<Long> deadlines=Collections.synchronizedList(new ArrayList<Long>())
 void check() {
  if (cancelled || Thread.currentThread().isInterrupted()) throw new PipelineAborted()
  List<Long> snapshot
  synchronized(deadlines) {snapshot=new ArrayList<>(deadlines)}
  if (snapshot.any { System.nanoTime() >= it }) throw new PipelineAborted('Timeout exceeded')
 }
}
