# Jenkinsfile Exec v1

Approved in conversation on 2026-10-25; implementation authorized.

Independent controller-free JVM CLI, Java 21 / Groovy 4, macOS and Linux. Support Declarative pipeline plus normal Groovy inside script, lexical variables above pipeline, sequential stages, scoped env/dir, agent any/none, shell and file steps, when/post/timeout/retry/catchError. Unsupported directives fail explicitly. Basic compatibility is tested against Jenkins; local extensions and arbitrary Java/CPS semantics are not claimed portable.

Codex first, Claude later via the same ServiceLoader SPI. Spawn the installed Codex CLI's app-server on stdio, inherit existing authentication/configuration. Synchronous AI step returns text/data/sessionId/turnId, fails on errors, automatically continues after one completed turn. Options: prompt, systemPrompt, model, reasoningEffort, profile, yolo (default true), newSession, outputSchema. Option values are Groovy expressions.

Pipeline options interactive false (default), aiSession shared (default; alternative isolated). Shared sessions are per run/provider only. One-off newSession does not replace shared pointer. Failed shared sessions are unusable. Instructions/profile locked per shared thread; model and effort per turn.

Noninteractive + non-YOLO performs allowed work then fails/cancels at first approval request; never hangs or upgrades permissions. Interactive allows terminal approval/question replies, ends at turn/completed rather than waiting for another user prompt. No TTY => interactive preflight error. Approval-required/input-required/schema/CLI failures are FAILURE; timeout/cancel ABORTED; ambiguous disconnect RESULT_UNKNOWN and no automatic replay.

Technical gates: actual Codex protocol/approvals/session/output probes; Groovy lexical/deferred expressions; Jenkins differential tests. Runtime compatibility must not be inferred from unit tests alone. Do not change global CLI config, auth, or other sessions.

## Workspace extension (authorized 2026-10-25)

The default initial workspace is the Jenkinsfile parent, overridden by --workspace relative to invocation cwd. ws supports optional positional/named dir, absolute/initial-root-relative paths, nested/suffixed OS leases, WORKSPACE/TMP, and restoration on exit/cancel. dir remains path-only. Initial workspace is not globally locked/renamed; explicit ws blocks share canonical-path leases between CLI processes. See ../plans/2026-10-25-workspaces.md for decisions and verification.
