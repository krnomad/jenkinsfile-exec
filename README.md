# Jenkinsfile Exec

<p align="center">
  🌐 <strong>Language / 언어</strong><br />
  <a href="README.md"><strong>English</strong></a> &nbsp; | &nbsp; <a href="README.ko.md">한국어</a>
</p>

**Run your Jenkinsfile like an executable.**

Run a useful subset of Declarative Jenkins Pipeline locally, using real Groovy, without a Jenkins controller. Add local AI workflows through step plugins. Codex is included; Claude is planned using the same SPI.

**Status: early implementation.** The compatibility claim is limited to tested constructs and pinned Jenkins fixtures. Local extensions are listed below. Arbitrary Jenkins plugins, CPS persistence and the entire Jenkins DSL are not supported.

## Quick start

Requires JDK 21+ (JDK 21 is the tested build baseline), macOS or Linux. Python 3 is required for the protocol tests and Jenkins comparison tool. An installed, authenticated Codex CLI is needed only for `codex` steps; tested with `0.163.0`.

```sh
git clone https://github.com/krnomad/jenkinsfile-exec.git
cd jenkinsfile-exec
./gradlew test :cli:installDist
cli/build/install/jenkinsfile-exec/bin/jenkinsfile-exec run examples/Jenkinsfile --workspace build/demo
cli/build/install/jenkinsfile-exec/bin/jenkinsfile-exec check examples/Codex.Jenkinsfile
cli/build/install/jenkinsfile-exec/bin/jenkinsfile-exec plugins list
```

Before building, on Homebrew installations where Java 21 is not the default:

```sh
export JAVA_HOME="$(brew --prefix openjdk@21)/libexec/openjdk.jdk/Contents/Home"
```

Build archives with `./gradlew :cli:distZip :cli:distTar`. The executable and plugin JARs are in the archive. No controller, Docker daemon or Groovy installation is required to run the distribution.

`run [Jenkinsfile]` defaults the initial workspace to the directory containing the Jenkinsfile, even when invoked from another directory. `--workspace PATH` overrides that default; relative CLI paths (input and workspace override) resolve against the invocation directory. Each run writes `.local-jenkinsfile/runs/<run-id>.json` in its initial workspace and prints stage logs; an inner `ws` does not relocate the run report. Exit codes: success `0`, failure/unstable `1`, configuration/static error `2`, abort `130`.

`check` parses and validates the supported Declarative structure without running top-level Groovy, compiling annotation transforms, starting shell/AI processes, or executing stage bodies. Dynamic calls inside `script` and dynamic option values are validated during execution. A static PASS is not a Jenkins/runtime compatibility certificate.

## Execute Jenkinsfile directly

Put the installed distribution's `bin` directory on PATH once. From the source repository after `:cli:installDist`:

```sh
export PATH="$PWD/cli/build/install/jenkinsfile-exec/bin:$PATH"
```

For an extracted archive, use its absolute `bin` path instead. To keep this across terminal sessions, add the export with that absolute path to your shell startup file (for example `~/.zshrc`). Keep JDK21+ available through `JAVA_HOME` or PATH.

Start your Jenkinsfile with this exact first line:

```groovy
#!/usr/bin/env jenkinsfile-exec
def message = 'Hello'
pipeline {
    agent any
    stages {
        stage('Hello') { steps { echo message } }
    }
}
```

Then grant execution permission once and run it:

```sh
chmod +x Jenkinsfile
./Jenkinsfile
./Jenkinsfile --workspace build/demo
```

The default workspace is the Jenkinsfile directory, including when launched from elsewhere. `--workspace` and `--plugin-dir` remain available; relative CLI option paths use the invocation directory. Pipeline exit codes and terminal input/output are preserved. `jenkinsfile-exec path/to/Jenkinsfile` also implies `run`; explicit `run`, `check`, and `plugins` commands retain priority.

Use `#!/usr/bin/env jenkinsfile-exec` rather than a machine-specific `/bin/Jenkinsfile` path. An `env: jenkinsfile-exec: No such file or directory` error means the distribution's `bin` is missing from PATH. Save the file with Unix LF line endings. See the executable [example](examples/Executable.Jenkinsfile). The shebang is accepted as a source header by the pinned Jenkins comparison; local-only steps still require corresponding extensions on Jenkins.

## Groovy and Pipeline

```groovy
def targets = ['api', 'web']
def tested = []
pipeline {
    agent any
    environment { MODE = 'local' }
    stages {
        stage('Test') {
            steps {
                script {
                    for (def target in targets) {
                        if (fileExists("${target}/package.json")) {
                            dir(target) { sh 'npm test' }
                            tested << target
                        }
                    }
                }
            }
        }
        stage('Report') {
            steps { echo "Tested: ${tested.join(', ')}" }
        }
    }
}
```

Variables declared above `pipeline` follow normal Groovy lexical scope. Closures can update those variables; they are not shared across runs. A script-local `def` does not become a field visible inside arbitrary class methods. Groovy conditionals, loops, collections, interpolation and exception handling work inside `script`.

Supported structures: `pipeline`, `stages`, sequential `stage`, `steps`, `script`, Pipeline/stage `environment`, `agent any/none`, `when`, `post`, `options { timeout(...) }`.

Supported core steps: `sh`, `dir`, `ws`, `pwd`, `echo`, `fileExists`, `readFile`, `writeFile`, `error`, `withEnv`, `timeout`, `retry`, `catchError`, and `step` for registered local plugins. `sh` supports `returnStatus`, `returnStdout`, `encoding`, `label` and shebang scripts. Default shell behavior is `/bin/sh -xe`.

`when` supports `expression`, `environment`, `allOf`, `anyOf`, `not`. `post` supports `always`, `aborted`, `failure`, `success`, `unstable`, `cleanup`. Stage-specific result handling is available through `catchError(stageResult: ...)`. Stage `when` runs before stage environment is applied; stage-warning/post-condition behavior follows the pinned Jenkins comparison fixture. `currentBuild.result` can worsen the build result and `currentBuild.currentResult` reports it.

`agent any` allocates the local workspace. `agent none` does not: workspace steps need a stage-level `agent any`. Neither connects to a Jenkins node. Environment and directory wrappers modify the execution context and child process environment, not JVM-global `System.getenv()` or the process-wide directory. Unknown directives/steps/options fail explicitly.

Timeout and Ctrl-C cancel work and terminate owned child processes. Shell children belong to a private Unix process group and are cleaned up at the end of each `sh` step; persistent background daemons across steps are outside v1 compatibility. `post` has a separate 30-second cleanup budget. Interrupt checks cover Groovy loops and managed steps; uncooperative native/Java calls cannot be promised an immediate graceful interruption. Workspaces and arbitrary Groovy/plugins run with the local user's permissions.

## Workspaces

Initial workspace priority is **`--workspace` > Jenkinsfile directory**. No CLI injection is required:

```sh
# Initial workspace is /projects/app, regardless of the current shell directory.
jenkinsfile-exec run /projects/app/Jenkinsfile
# Explicit override; relative values are relative to the invoking shell directory.
jenkinsfile-exec run /projects/app/Jenkinsfile --workspace /tmp/app-build
```

Inside the Jenkinsfile:

```groovy
pipeline {
    agent any
    stages {
        stage('Build') {
            steps {
                ws('build/demo') {
                    sh 'pwd'
                    echo "Allocated workspace: ${env.WORKSPACE}"
                    // File steps and codex also use this workspace.
                    dir('src') { sh 'pwd' }
                }
                echo "Restored working directory: ${pwd()}"
            }
        }
    }
}
```

`ws('path') { ... }`, `ws(dir: 'path') { ... }`, and `ws { ... }` are supported. Paths can be Groovy variables/GStrings. Relative `ws` paths resolve against the **initial workspace**, which represents the local agent root, even inside an enclosing `dir` or `ws`. Absolute paths are used directly. An empty/omitted path requests the initial workspace; it is already occupied by the current local agent, so an alternate `@2` (or next available suffix) is allocated.

A `ws` block acquires an OS file lease. If another `ws` holds the requested path, a free `@2`, `@3`, ... sibling is chosen rather than waiting. Nested requests behave the same way. The block sets `WORKSPACE` and `WORKSPACE_TMP`, and its current path is passed to shell/file/plugin/AI steps. `dir` changes only the working directory, leaving `WORKSPACE` unchanged. On exit, failure or timeout, `ws` restores the previous path/environment and releases its lease; it does not delete files.

Lease metadata is stored in `~/.cache/local-jenkinsfile/workspace-locks`, keyed by canonical path, so symlink aliases and CLI processes from different projects share leases. Metadata files remain after release; the OS lock, not the file's existence, indicates occupancy and releases on process exit. These are cooperative runner leases, not a security boundary against arbitrary programs. Initial workspaces and plain `dir` are not globally leased/renamed; use `ws` for overlapping runs that need workspace isolation. `ws` requires `agent any` just like other workspace steps.

[Workspace example](examples/Workspace.Jenkinsfile) · [Jenkins workspace semantics](https://www.jenkins.io/doc/pipeline/steps/workflow-durable-task-step/#ws-allocate-workspace)

## Codex workflow

Executable translation examples (after adding the distribution's `bin` to PATH):

```sh
./examples/CodexHello.Jenkinsfile
./examples/CodexTranslateInput.Jenkinsfile
```

[CodexHello.Jenkinsfile](examples/CodexHello.Jenkinsfile) asks Codex how to say `hello` in Korean and Japanese. [CodexTranslateInput.Jenkinsfile](examples/CodexTranslateInput.Jenkinsfile) reads one Korean line from your terminal and translates it into English and Japanese. Both print the structured translation in the following stage, use your existing Codex model configuration, and explicitly set `yolo: false`. They require an installed, authenticated Codex CLI and consume model usage. The input example needs a terminal (`interactive true`); empty input fails before calling Codex. It reads through `System.console().readLine()` inside `script`, using ordinary Groovy/Java APIs. Console reading is local terminal behavior, not a Jenkins `input` step; the Pipeline timeout cannot guarantee interrupting this blocking console read. The translation calls request no tool execution.

```groovy
def review = null
pipeline {
    agent any
    options {
        interactive false       // default
        aiSession 'shared'      // default; or 'isolated'
        timeout(time: 10, unit: 'MINUTES')
    }
    stages {
        stage('Review') {
            steps {
                script {
                    review = codex(
                        systemPrompt: 'Review the code and explain concrete findings.',
                        reasoningEffort: 'high',
                        prompt: 'Review this workspace.',
                        outputSchema: [
                            type: 'object', properties: [summary: [type: 'string']],
                            required: ['summary'], additionalProperties: false
                        ]
                    )
                    echo review.data.summary
                }
            }
        }
        stage('Follow up') {
            steps {
                codex prompt: 'Based on your review, suggest the next action.'
            }
        }
    }
}
```

AI option values are ordinary Groovy expressions, including variables, maps and GStrings. Supported Codex options:

| Option | Meaning |
| --- | --- |
| `prompt` | Required nonempty task string |
| `systemPrompt` | Replacement base instructions, mapped to Codex `baseInstructions`; not a literal model API system-role guarantee |
| `model` | CLI model identifier; omitted uses existing CLI configuration |
| `reasoningEffort` | Model-supported effort, validated against the installed CLI model catalogue |
| `profile` | Existing CLI profile; the runner never edits its configuration |
| `yolo` | Boolean, **defaults to true**: disables Codex command approvals and sandboxing where policy permits |
| `newSession` | Boolean, default false; an independent one-off conversation |
| `outputSchema` | JSON Schema Map, validated locally; external schema references are rejected |

Normal return value: `text`, optional `data` (parsed Map/List/scalar), `sessionId`, `turnId`. Failure throws; standard Pipeline/Groovy error handling applies. There is no implicit AI retry. Explicit `retry` can repeat side effects, but an ambiguous `RESULT_UNKNOWN` is never replayed by the core retry step.

Shared conversation is scoped to one Pipeline run and one provider. `newSession: true` does not replace the shared conversation. Instructions and profile lock at the first call; change them with a new session. Omitted model/effort retain shared-session values. A failed shared session is invalidated; continuation requires a fresh session. It never resumes another run's most recent session.

The runner launches the installed `codex app-server` on private stdio for each step, resumes only its recorded thread ID, and ends the step on a successful `turn/completed` event. The app-server is a local subprocess, not a hosted service. Model traffic and existing CLI integrations still follow Codex's own configuration. Authentication remains owned by the installed CLI. `LOCAL_JENKINSFILE_CODEX` can select a different executable path (not an arbitrary shell command).

### Interaction and approvals

| Pipeline | AI step | Behavior |
| --- | --- | --- |
| noninteractive (default) | YOLO (default) | Runs without command approval prompts |
| noninteractive | `yolo: false` | Performs allowed work; first approval request cancels the turn and fails with `APPROVAL_REQUIRED` |
| `interactive true` | YOLO | User questions can be answered at the terminal |
| `interactive true` | `yolo: false` | User answers approval requests and questions at the terminal |

Interaction is for requests during one task. A completed task automatically advances to the next step; it never opens an indefinite chat. Run the installed executable directly in a terminal for interactive mode. Missing TTY fails before stages begin. Noninteractive questions fail with `INPUT_REQUIRED`. Organizational policies can still restrict YOLO; mismatched effective policies fail explicitly. Request types not implemented by this client fail explicitly.

## Plugins

Implement `io.localjenkinsfile.api.StepPlugin` with a no-argument constructor, SPI version `1`, a unique ID, and a map of `StepDefinition`s. Register the implementation in `META-INF/services/io.localjenkinsfile.api.StepPlugin`.

Put the plugin JAR and its additional dependencies in `.local-jenkinsfile/plugins/`, or use `--plugin-dir PATH`. Names cannot shadow core steps or other plugins. Steps get the current workspace, environment, logger, cancellation token, interaction interface and run-local session storage. See [plugin guide](docs/plugins.md).

This follows Jenkins's extensible step concept. Jenkins HPI/JPI binary compatibility is not implemented. `step([$class: 'registeredStepName', ...])` dispatches only to registered local plugins.

## Compatibility and verification

Unit/integration tests run without model traffic or Jenkins. They use actual shell/file operations and a local fake Codex protocol process for deterministic approval, failure, cancellation, early-event and disconnect cases.

```sh
./gradlew test :cli:installDist
python3 scripts/compare-jenkins.py
```

The second command downloads checksum-pinned Jenkins LTS/plugins into `build/`, starts its own controller bound to loopback, compares fixtures, and shuts it down. It never contacts an existing controller. Test-only Jenkins is not a runtime dependency. Fixtures compare resulting files and build outcomes, not byte-identical console output.

Run `python3 scripts/probe-codex.py` for the installed CLI schema/handshake check, or add `--live` to perform two small model turns using the CLI's existing authentication.

See [verification evidence and remaining limits](docs/verification.md) for the actual tested baseline. Unsupported: top-level Scripted Pipeline, parallel/matrix, distributed agents, containers, credentials storage, automatic checkout, Shared Libraries, CPS persistence/recovery, arbitrary Jenkins plugins and unrestricted Jenkins API compatibility. `codex`, `interactive`, `aiSession`, and plugin registration are local extensions; Jenkins needs corresponding extensions to run a file using them.

## Development

Modules: `plugin-api` → `runtime` / `codex-plugin` → `cli`. The runtime uses Groovy AST validation and deferred closures rather than text substitution. Design and implementation records are under `docs/superpowers/`.

Licensed under [Apache-2.0](LICENSE).
