package io.localjenkinsfile.runtime

import io.localjenkinsfile.api.Cancellation
import io.localjenkinsfile.api.PipelineException
import java.nio.channels.*
import java.nio.file.*
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/** Scoped workspace leases, shared by CLI processes belonging to the same OS user. */
class WorkspaceLeases {
 private static final Map<Path,Object> JVM_CLAIMS=new ConcurrentHashMap<>()
 final Path lockDirectory
 WorkspaceLeases(Path lockDirectory=Path.of(System.getProperty('user.home'),'.cache','local-jenkinsfile','workspace-locks')) {
  this.lockDirectory=lockDirectory.toAbsolutePath().normalize()
 }
 WorkspaceLease acquire(Path requested,Cancellation cancellation,Collection<Path> reserved=[]) {
  cancellation.check()
  Path base=requested.toAbsolutePath().normalize()
  Set<Path> occupied=reserved.collect {canonical(it)} as Set
  Files.createDirectories(lockDirectory)
  for(int number=1;;number++) {
   cancellation.check()
   Path candidate=number==1 ? base : Path.of(base.toString()+'@'+number)
   Path key=canonical(candidate)
   if(occupied.contains(key)) continue
   Object claim=new Object()
   if(JVM_CLAIMS.putIfAbsent(key,claim)!=null) continue
   FileChannel channel
   boolean retained=false
   try {
    String digest=MessageDigest.getInstance('SHA-256').digest(key.toString().getBytes(StandardCharsets.UTF_8)).encodeHex().toString()
    channel=FileChannel.open(lockDirectory.resolve(digest+'.lock'),StandardOpenOption.CREATE,StandardOpenOption.WRITE)
    FileLock lock
    try {lock=channel.tryLock()} catch(OverlappingFileLockException ignored) {lock=null}
    if(lock==null) continue
    def lease=new WorkspaceLease(candidate,channel,lock,{JVM_CLAIMS.remove(key,claim)})
    retained=true
    return lease
   } catch(IOException e) {throw new PipelineException('WORKSPACE_LOCK','Cannot allocate workspace '+candidate+': '+e.message,e)}
   finally {
    if(!retained) {
     try {channel?.close()} finally {JVM_CLAIMS.remove(key,claim)}
    }
   }
  }
 }
 private static Path canonical(Path path) {path.toFile().canonicalFile.toPath()}
}

class WorkspaceLease implements Closeable {
 final Path path
 private final FileChannel channel
 private final FileLock lock
 private final Closure releaseClaim
 private boolean closed
 WorkspaceLease(Path path,FileChannel channel,FileLock lock,Closure releaseClaim) {
  this.path=path;this.channel=channel;this.lock=lock;this.releaseClaim=releaseClaim
 }
 synchronized void close() {
  if(closed) return
  closed=true
  try {if(lock.valid) lock.release()}
  finally {try {channel.close()} finally {releaseClaim.call()}}
 }
}
