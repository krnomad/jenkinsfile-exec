# Jenkinsfile Exec Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans for inline implementation. Tests precede behavior changes.

**Goal:** Deliver a usable local Declarative Groovy runner and Codex step plugin.
**Architecture:** AST validation plus deferred Groovy closures; scoped execution context and ServiceLoader step registry; local Codex app-server transport.
**Tech Stack:** Java 21, Groovy 4.0.29, Gradle 9.4.1, JUnit 5, JSON Schema validator.
**Spec:** ../specs/2026-10-25-local-jenkinsfile-design.md

## Global Constraints
- Never execute user code during check.
- Local extensions are explicitly excluded from Jenkins portability.
- Do not modify user authentication/configuration or control existing Codex processes.
- No implicit AI retry after unknown outcomes.

## Review Focus
- Lexical variables and deferred interpolation after earlier stage changes.
- Environment restoration after exceptions and nested dirs.
- Timeout and cancellation of child process trees.
- Approval request races with turn completion or disconnect.
- Shared sessions after failure and scoped environment between calls.

## Tasks
- [x] 1. Build/SPI: create Gradle modules and wrapper; test plugin collisions and context/result contracts before implementation.
- [x] 2. Groovy runtime: test then implement AST checks, top-level variables, sequential execution, deferred environment/when, script flow, agent allocation.
- [x] 3. Core steps: test actual shell/files/scopes; implement timeout/retry/catchError/post and cancellation.
- [x] 4. Codex plugin: test protocol fixtures before transport implementation; verify session/permissions/structured result and cancellation. Run installed CLI handshake/schema probe; live model tests explicitly opt-in.
- [x] 5. CLI/distribution: test run/check/plugins and nonexecuting check; ship examples/docs, CI, Jenkins comparison harness.
- [x] 6. Run full tests, distribution smoke tests, technical probes and independent review; document proven vs pending acceptance.

## Execution record
- Fresh empty workspace initialized on feat/local-runtime; no shared branch or existing source to isolate. Inline execution authorized by user request, no additional approval gate.
- JDK 21 already installed via Homebrew (discovered after java_home only showed 13); use it locally without changing global Java settings.

- Tasks 1-6 complete: 37 automated tests pass; eight real Jenkins LTS comparisons agree; installed Codex schema/handshake, two-turn structured context, noninteractive approval refusal and interactive approval/auto-advance verified on macOS arm64.
- Independent review identified six defects. All reproduced RED then fixed GREEN: orphaned shell children, post-loop deadline, blocked RPC writes, remote dynamic references, eager when evaluation, trailing JSON. Added a CLI SIGINT regression smoke test; fixed report/shutdown race.
- Ruling: use a bundled JVM/JNA helper to establish a Unix process group before each shell — macOS lacks a portable setsid executable — persistent background daemons across sh steps are excluded from v1 compatibility.
- Ruling: check uses parse/convert only, before annotation transforms — checking must not resolve @Grab or execute transforms.
- Ruling: stage when precedes stage environment, and stage-warning post eligibility follows observed Jenkins2.580.1 behavior — actual comparison superseded earlier assumptions.
- Ruling: cleanup budget defaults30s and is adjustable only for embedded PipelineRunner via settings.postTimeoutMillis; CLI retains30s.
- Ruling: no base branch/remote exists in this fresh repository, so integration/publishing is outside this implementation request. Working files remain on feat/local-runtime.
- Remaining release gates: Linux CI execution (configured, not run here), broader Jenkins version/fixture coverage, more Codex versions and external provider policies; these are not claimed verified.
