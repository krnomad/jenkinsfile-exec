# Plugin SPI v1

A plugin is an ordinary JVM JAR. Compile against `plugin-api` with Groovy 4 and Java 21, and include a no-argument implementation of `StepPlugin` in `META-INF/services/io.localjenkinsfile.api.StepPlugin`.

```groovy
package example
import io.localjenkinsfile.api.*

class GreetingPlugin implements StepPlugin {
    String id() { 'greeting' }
    int apiVersion() { 1 }
    Map<String,StepDefinition> steps() {
        [greet: new StepDefinition('greet', false,
            [name: [type: 'string', required: true]],
            { ExecutionContext context, Map options ->
                context.cancellation.check()
                context.log.println("Hello ${options.name}")
                return options.name
            })]
    }
}
```

The declaration's boolean specifies whether an allocated workspace is required. The optional option schema maps names to `type` (`string`, `boolean`, `object`, `array`, `number`) and `required`. With a schema, unknown options and wrong types fail before implementation. Without a schema, the implementation owns option validation. Groovy GStrings are normalized at the call boundary. Providers can add semantic/capability validation, as Codex does for model reasoning support.

`ExecutionContext` supplies:

- `workspace`: current absolute path, including enclosing `dir`/`ws` changes.
- `baseWorkspace`: fixed initial local agent root (CLI override or Jenkinsfile parent), used to resolve relative ws paths.
- `workspaceAvailable`, `requireWorkspace()`: local agent allocation.
- `env`: current scoped process environment, not JVM-global environment.
- `log`: run output stream.
- `cancellation.check()`: cooperative abort/timeout checkpoint. Poll while waiting for children; terminate all owned child processes on interruption.
- `interactive`, `interaction`: availability and questions. Never ask when interaction is disabled. Input handling must remain cancellable.
- `aiSession`: shared/isolated preference, `sessions`: per-run provider-keyed state, `runId`.

Do not store run-specific values in plugin instance fields: a registry may serve multiple runs. Do not mutate process-wide cwd/env or user authentication/configuration. If a connection drops after submitting an action, report an unknown result rather than retrying it.

Use `PipelineException(code, message)` for actionable failures and `PipelineAborted` for interruption. Core recognizes `RESULT_UNKNOWN` and refuses to retry it. AI providers should use `AiResult(text, data, sessionId, turnId)` and implement the same single-task completion semantics. A provider must reject unsupported options/capabilities rather than silently omit them.

Put the JAR and runtime dependencies in `.local-jenkinsfile/plugins` (or `--plugin-dir`). Plugin/SPI mismatch and duplicate IDs/step names fail at startup; core names cannot be replaced. Plugin JARs execute trusted local code. This is not the Jenkins HPI/JPI API.

Direct call: `greet name: 'World'`. Generic call: `step([$class: 'greet', name: 'World'])`. `$class` identifies the registered local step name, not an arbitrary class to instantiate.

Claude is a future provider, not a stub that pretends to execute. Implement it using this SPI, its CLI's actual approval/completion protocol and the same provider contract tests. Provider-specific model/effort/instruction semantics must be documented and tested.
