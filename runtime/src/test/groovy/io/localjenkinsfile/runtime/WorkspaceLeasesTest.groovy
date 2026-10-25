package io.localjenkinsfile.runtime
import io.localjenkinsfile.api.Cancellation
import org.junit.jupiter.api.*
import org.junit.jupiter.api.io.TempDir
import java.nio.file.*
import static org.junit.jupiter.api.Assertions.*
class WorkspaceLeasesTest {
 @TempDir Path root
 @Test void independentManagersExcludeSameWorkspaceAndReuseAfterRelease() {
  def one=new WorkspaceLeases(root.resolve('locks'))
  def two=new WorkspaceLeases(root.resolve('locks'))
  def first=one.acquire(root.resolve('target'),new Cancellation())
  def second=two.acquire(root.resolve('target'),new Cancellation())
  try {
   assertEquals(root.resolve('target'),first.path)
   assertEquals(root.resolve('target@2'),second.path)
  } finally {first.close();second.close()}
  def reused=two.acquire(root.resolve('target'),new Cancellation())
  try {assertEquals(root.resolve('target'),reused.path)} finally {reused.close()}
 }
 @Test void symlinkAliasesShareLockIdentity() {
  Path actual=Files.createDirectories(root.resolve('real'))
  Path alias=Files.createSymbolicLink(root.resolve('alias'),actual)
  def manager=new WorkspaceLeases(root.resolve('locks'))
  def first=manager.acquire(actual,new Cancellation())
  def second=manager.acquire(alias,new Cancellation())
  try {assertEquals(root.resolve('alias@2'),second.path)} finally {first.close();second.close()}
 }
 @Test void cancellationDoesNotAcquireWorkspace() {
  def manager=new WorkspaceLeases(root.resolve('locks'))
  def cancelled=new Cancellation(cancelled:true)
  assertThrows(io.localjenkinsfile.api.PipelineAborted) {manager.acquire(root.resolve('target'),cancelled)}
 }
}
