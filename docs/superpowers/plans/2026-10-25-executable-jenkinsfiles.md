# Executable Jenkinsfiles

Authorized scope: execute `./Jenkinsfile` with a portable `#!/usr/bin/env jenkinsfile-exec` header, retaining explicit CLI commands, workspace defaults and overrides. The installed distribution's bin directory must be on PATH; no system directory or shell configuration is modified automatically.

1. Add failing CLI regression tests for implicit file execution, shebang parsing, normal Groovy variables, workspace forwarding and failure exit.
2. Interpret an existing regular file argument as implicit `run`; explicit commands retain priority. Groovy handles the shebang as source syntax.
3. Supply an executable example, preserve its archive executable mode, document the one-time PATH setup, and add an OS-level smoke test to CI.
4. Run all tests, executable/archive smoke checks and the pinned Jenkins comparison including a shebang fixture. Record concrete results before completion.

Completed: 53 tests passed; real OS shebang and ZIP/TAR executable-mode checks passed; 10/10 pinned Jenkins comparisons matched, including the shebang fixture. Independent focused review reported no defects. Detailed evidence is in docs/verification.md.
