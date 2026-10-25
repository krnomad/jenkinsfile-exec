# Verification — 2026-10-25

This is an initial implementation, not a full Jenkins replacement. Results below distinguish deterministic tests, actual Codex runs, actual Jenkins comparison, and configured but unexecuted CI.

## Automated suite and distribution

macOS arm64, JDK21, Gradle9.4.1, Groovy4.0.29:

```sh
./gradlew test :cli:installDist :cli:distZip :cli:distTar
python3 scripts/smoke-cli.py
python3 scripts/smoke-workspaces.py
python3 scripts/smoke-shebang.py
```

**54 tests, zero failures:** plugin-api3, runtime25 (22 execution +3 lease tests), Codex15, CLI11. XML reports are under each module's `build/test-results/test/`; latest test/build log is `build/publish-clean-build.log`.

The suite exercises real files/shell processes and a fake local JSON-RPC process. It covers lexical variables/deferred expressions, scopes, conditional short circuiting, stage ordering, agent allocation, shell statuses/stdout, retry/catchError/post, child-process cleanup after the parent shell exits, pure Groovy post-loop timeout, plugin option validation, shared/isolated sessions, approval/input handling, cancellation while waiting for input or blocked writes, early completion events, unknown outcomes, strict JSON and offline schema references.

`check` is also tested with top-level side effects and an invalid `@Grab`: it does not execute the script or resolve dependencies.

The installed CLI SIGINT smoke test verifies exit130, executed `post { aborted }`, and a persisted ABORTED run report. It completed in0.09s after SIGINT in this run (`build/cli-smoke-8b7658c7`). The test uses its own temporary workspace and process.

Archives: `cli/build/distributions/jenkinsfile-exec-0.1.0-SNAPSHOT.zip` and `.tar`. These contain the executable, runtime and Codex plugin dependencies. They require an installed JDK21+ and, for AI calls, an authenticated Codex CLI.

## Workspace extension

Authorized follow-up: `ws` blocks plus Jenkinsfile-parent default and retained CLI injection. Initial workspace priority is explicit `--workspace` (relative to invocation cwd) over input Jenkinsfile parent. Reports and default plugin discovery remain at this initial root.

Tests cover positional/named/no-argument ws, absolute and initial-root-relative paths, Groovy variables, nested `@2` allocation, initial-root reservation, environment/file/shell/plugin context changes, exception/timeout restoration, lease reuse and symlink alias identity. `check` recognizes ws without creating workspace/lease directories. The initial root remains explicit and is not globally locked or renamed; only ws blocks participate in allocation leases.

A real multi-process smoke test (`scripts/smoke-workspaces.py`) ran without `--workspace` from a different cwd, verified results/reports in each Jenkinsfile's parent, allocated `shared@2` for an overlapping second process, interrupted the first process, then reacquired `shared` without a suffix. Evidence: `build/workspace-smoke-73ef7e56`.

An independent focused review found no important defect in this change. The Jenkins workspace fixture confirms environment, nested lease suffix, exception release, no-argument ws and path restoration for absolute paths. Relative paths intentionally map Jenkins's agent root to the initial local workspace; they are not claimed relative to the same physical directory as a controller's agent root.

## Executable Jenkinsfiles

`#!/usr/bin/env jenkinsfile-exec` plus executable permission allows `./Jenkinsfile`. An existing file as the first CLI argument implies `run`, while explicit commands retain priority. Put the distribution's `bin` directory on PATH once; shell startup files and system directories are not changed automatically.

The OS-level smoke test executes the shebang without a shell, including a path with spaces, top-level Groovy variables, `ws`, Jenkinsfile-parent defaults, `./Jenkinsfile --workspace`, static checking and failure exit propagation. Evidence: `build/shebang smoke d5674d5b`. The three new CLI regressions passed; implicit-run tests failed before implementation.

The ZIP was extracted to a path with spaces, executable modes restored from archive metadata, and its executable example was launched from another directory; output and SUCCESS report were verified. Both ZIP and TAR store executable permission for the example. Evidence: `build/shebang archive smoke 9e103b91`. An independent focused read-only review found no correctness or security defect.

## Initial public name

The repository, Gradle project, CLI launcher and distribution are named `jenkinsfile-exec`; the display name is Jenkinsfile Exec. README commands, executable examples, smoke scripts, pinned Jenkins fixture and CI use that name. CLI name/version/help have a regression test that failed before the rename and passed afterward.

Internal `io.localjenkinsfile` packages, `.local-jenkinsfile` report/plugin storage, `LOCAL_JENKINSFILE_CODEX` and the existing workspace lease directory retain their identifiers. Keeping the same lease directory preserves exclusion between runner processes using the same workspace.

A clean CLI build removed stale launch scripts from the previous project name. Final ZIP/TAR contents were checked for the new executable names, both README languages, example executable permissions and absence of generated run/output directories. Actual CLI SIGINT, cross-process workspace and OS shebang smoke tests passed after the rename. Linux/GitHub Actions results remain separate from these macOS checks.

## Actual Codex CLI

Tested installed version: **0.163.0**. Existing authentication/configuration was used without editing it. No direct model API integration or new API key was created.

- Schema export confirms thread/start/resume, baseInstructions, approvals, turn/start/complete, structured output and effort fields.
- Private stdio initialize/initialized handshake passed.
- Two real model turns produced schema-validated JSON and recalled the first turn's token using the same explicit thread ID. Both automatically returned on task completion. Latest evidence: `build/codex-live-a7aa642d`, `build/codex-live-final.log`.
- With a temporary executable wrapper selecting read-only sandbox, noninteractive `yolo:false` returned `APPROVAL_REQUIRED`, created neither the requested marker nor the following-stage marker. Evidence: `build/approval-probe`.
- In a real PTY, `interactive true` and `yolo:false` displayed a command approval request. Answering yes created only the test marker and automatically ran the next stage. Evidence: `build/interactive-probe` (one SUCCESS report plus earlier cancelled trials). This tests the runner's terminal approval UI, not Codex's full TUI.

Reproduce schema/handshake with `python3 scripts/probe-codex.py`. `--live` additionally runs two small model turns and consumes CLI usage. Approval tests use only test workspaces; deterministic approval/cancellation cases are in the ordinary test suite.

These observations do not prove every model, every account policy, every Codex release, MCP elicitation type, or native blocking call. Unsupported requests/policy mismatches fail explicitly. Claude is not implemented.

## Actual Jenkins comparison

Pinned **Jenkins LTS2.580.1 +48 checksum-pinned plugins**, specified in `compatibility/jenkins.lock.json`. Test-only controller was created in a fresh build directory, bound to loopback, then stopped. Existing Jenkins controllers were not contacted or changed.

`python3 scripts/compare-jenkins.py` ran the identical fixtures in the local executable and Jenkins, comparing final build status and `observed.txt`:

| Fixture | Outcome | Observed value |
| --- | --- | --- |
| executable (shebang) | SUCCESS | `shebang:groovy` |
| variables | SUCCESS | `new:1,3:pipeline-stage:pipeline:wrapped` |
| when-environment | SUCCESS | `0` |
| retry | SUCCESS | `3:9:hello` |
| catch-error | SUCCESS | `before:success` |
| short-circuit | SUCCESS | `selected` |
| failure | FAILURE | `stage-failure:always:failure:cleanup` |
| post-failure | FAILURE | `start:failure:cleanup` |
| stage-timeout | ABORTED | `aborted` |
| workspace | SUCCESS | `scoped:leased:restored` |

**10/10 match.** Evidence: `build/jenkins-comparison-9080a84b`; aggregate log `build/publish-jenkins-comparison.log`. The harness retains each controller build log, local log, case data and result JSON for inspection.

The comparison corrected two assumptions: `when` precedes stage environment, and a stage warning alone does not enter the pinned Jenkins stage-post guard when only an unstable condition is present. Tests now preserve the observed behavior.

Ten fixtures establish those scenarios only. No percentage or blanket compatibility guarantee is inferred for the rest of Jenkins. Remote agents, CPS restart/recovery, arbitrary HPI/JPI plugins, parallel/matrix and background daemons spanning `sh` calls are outside v1. Local AI/interaction/session options require corresponding Jenkins extensions to run on a controller.

## Review and remaining validation

An independent read-only reviewer reproduced six defects. Each gained a failing regression before the fix and passed afterward: shell-child leakage, unenforced post budget, uninterruptible protocol writes, remote dynamic schema references, eager conditions and trailing JSON acceptance. A later SIGINT smoke test also found and fixed a shutdown/report race.

Linux/macOS CI and an isolated Jenkins comparison job are configured in `.github/workflows/ci.yml`. **CI has not run on GitHub, and Linux was not executed in this session.** Broader Jenkins versions, additional provider/CLI versions, and more exotic Groovy/CPS patterns remain release validation work.
