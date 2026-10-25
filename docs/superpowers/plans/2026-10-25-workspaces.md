# Workspace DSL Implementation Plan

> **For agentic workers:** Use inline execution and test-driven-development. This is an explicitly requested extension of the approved runtime design.

**Goal:** Add scoped Jenkins-style ws allocation, default the initial workspace to the Jenkinsfile parent, and retain CLI override.
**Architecture:** A fixed local agent root (the initial workspace) plus mutable current working path. ws obtains a cross-process file lease, updates WORKSPACE/WORKSPACE_TMP, and restores/releases in finally; dir only changes the working path.
**Tech Stack:** Existing Groovy/JDK21 runtime, FileChannel locks.
**Spec:** User request on 2026-10-25; ../specs/2026-10-25-local-jenkinsfile-design.md

## Decisions
- Priority: --workspace (relative to invocation cwd) > Jenkinsfile parent. Resolve input path before workspace and plugin lookup. plugins list without a Jenkinsfile still uses cwd.
- ws accepts a path, named dir, or no arguments. Relative paths resolve against the initial local agent root, not the enclosing dir/ws.
- Default ws requests the initial workspace; it is already logically occupied by agent any, so it selects @2 or the next free suffix. Concurrent/nested ws requests use @2/@3 rather than waiting.
- OS locks are stored in the per-user .cache/local-jenkinsfile/workspace-locks directory, keyed by canonical target path. Keep metadata files after release to avoid unlink races; OS locks release on process death. A JVM-wide claim map prevents closing another descriptor from releasing a same-JVM POSIX lock.
- This does not introduce locking/suffix changes to the initial CLI/default workspace. Only ws blocks participate in cross-process leases. Unmanaged writers/dir are not isolated by these leases.
- Require an allocated local agent; restore path and only WORKSPACE/WORKSPACE_TMP on exceptions and cancellation. Report files remain in initial workspace. Preserve session storage between ws blocks.

## Tests and tasks
- [x] RED: CLI default/override/static check, ws nested/absolute/map/default/scopes, workspace lease collision/alias/release/cancel tests.
- [x] GREEN: lease manager, ws dispatch, initial root/environment, CLI resolution.
- [x] Verify distribution from a different cwd and overlapping CLI processes with a shared ws; retry after interruption must reclaim the original path.
- [x] Add actual Jenkins ws fixture (absolute paths for portable comparison), refresh docs/examples/distribution and run the full suite.

## Results
- Workspace suite and CLI defaults/overrides pass, including soft timeout release and static check with no side effects.
- Real CLI processes: shared@2 on collision and shared after SIGINT release; evidence build/workspace-smoke-73ef7e56.
- Actual Jenkins LTS2.580.1 fixture: workspace SUCCESS, scoped:leased:restored; all nine comparisons match (build/jenkins-comparison-25ec2b2b).
- Independent focused review: no important defect found. Updated distribution and documentation.
