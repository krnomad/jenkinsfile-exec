# Jenkinsfile Exec

<p align="center">
  🌐 <strong>Language / 언어</strong><br />
  <a href="README.md">English</a> &nbsp; | &nbsp; <a href="README.ko.md"><strong>한국어</strong></a>
</p>

**Jenkinsfile을 실행 파일처럼 사용하세요.**

Jenkins controller 없이 실제 Groovy 환경에서 Declarative Jenkins Pipeline의 유용한 문법을 로컬로 실행합니다. Step 플러그인으로 로컬 AI 워크플로를 추가할 수 있습니다. Codex는 기본 포함되며, Claude는 같은 SPI를 사용하는 후속 플러그인으로 제공할 예정입니다.

**현재 상태: 초기 구현 단계입니다.** 호환성은 테스트한 문법과 고정된 Jenkins 비교 사례로 제한됩니다. 로컬 전용 확장 기능은 아래에 명시합니다. 임의의 Jenkins 플러그인, CPS 실행 상태 저장, Jenkins DSL 전체를 지원하지는 않습니다.

## 빠른 시작

JDK 21 이상과 macOS 또는 Linux가 필요합니다. 빌드 기준으로 검증한 버전은 JDK 21입니다. 프로토콜 테스트와 Jenkins 비교 도구에는 Python 3가 필요합니다. `codex` step을 사용할 때만 설치 및 인증을 마친 Codex CLI가 필요하며, 검증한 버전은 `0.163.0`입니다.

```sh
git clone https://github.com/krnomad/jenkinsfile-exec.git
cd jenkinsfile-exec
./gradlew test :cli:installDist
cli/build/install/jenkinsfile-exec/bin/jenkinsfile-exec run examples/Jenkinsfile --workspace build/demo
cli/build/install/jenkinsfile-exec/bin/jenkinsfile-exec check examples/Codex.Jenkinsfile
cli/build/install/jenkinsfile-exec/bin/jenkinsfile-exec plugins list
```

Homebrew로 설치한 Java 21이 기본 Java가 아닌 경우에는 빌드 전에 다음을 설정하세요.

```sh
export JAVA_HOME="$(brew --prefix openjdk@21)/libexec/openjdk.jdk/Contents/Home"
```

`./gradlew :cli:distZip :cli:distTar`로 배포 압축파일을 만들 수 있습니다. 실행 파일과 플러그인 JAR이 포함됩니다. 배포본 실행에는 controller, Docker daemon, 별도의 Groovy 설치가 필요하지 않습니다.

`run [Jenkinsfile]`의 초기 workspace는 Jenkinsfile이 있는 디렉터리입니다. 다른 디렉터리에서 실행해도 같습니다. `--workspace PATH`로 변경할 수 있습니다. CLI에 전달한 상대 경로인 Jenkinsfile 경로와 workspace 경로는 명령을 실행한 디렉터리를 기준으로 해석합니다. 실행할 때마다 초기 workspace의 `.local-jenkinsfile/runs/<run-id>.json`에 결과를 저장하고 stage 로그를 출력합니다. 내부 `ws` 블록은 결과 보고서 위치를 바꾸지 않습니다. 종료 코드는 성공 `0`, 실패/unstable `1`, 설정/정적 검사 오류 `2`, 중단 `130`입니다.

`check`는 최상위 Groovy 코드, annotation transform 컴파일, shell/AI 프로세스, stage 본문을 실행하지 않고 지원하는 Declarative 구조를 파싱하고 검사합니다. `script` 안의 동적 호출과 동적 옵션 값은 실행 시 검사합니다. 정적 검사 통과가 Jenkins 호환성이나 실행 성공을 보장하지는 않습니다.

## Jenkinsfile 직접 실행

설치한 배포본의 `bin` 디렉터리를 PATH에 한 번 등록하세요. 소스 저장소에서 `:cli:installDist`를 실행한 뒤에는 다음과 같이 설정합니다.

```sh
export PATH="$PWD/cli/build/install/jenkinsfile-exec/bin:$PATH"
```

압축파일을 풀어 설치했다면 해당 배포본의 `bin` 절대 경로를 사용하세요. 새 터미널에서도 유지하려면 절대 경로로 작성한 export 구문을 `~/.zshrc` 같은 shell 시작 설정 파일에 추가하세요. `JAVA_HOME` 또는 PATH를 통해 JDK 21 이상도 사용할 수 있어야 합니다.

Jenkinsfile의 정확한 첫 줄에 다음 shebang을 넣으세요.

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

실행 권한을 한 번 부여한 뒤 직접 실행합니다.

```sh
chmod +x Jenkinsfile
./Jenkinsfile
./Jenkinsfile --workspace build/demo
```

다른 디렉터리에서 실행해도 기본 workspace는 Jenkinsfile이 있는 디렉터리입니다. `--workspace`와 `--plugin-dir`도 사용할 수 있으며, CLI 옵션의 상대 경로는 명령을 실행한 디렉터리를 기준으로 합니다. Pipeline 종료 코드와 터미널 입출력을 유지합니다. `jenkinsfile-exec path/to/Jenkinsfile`도 `run`으로 처리하며, 명시적인 `run`, `check`, `plugins` 명령을 우선합니다.

특정 컴퓨터의 `/bin/Jenkinsfile` 경로 대신 `#!/usr/bin/env jenkinsfile-exec`을 사용하세요. `env: jenkinsfile-exec: No such file or directory` 오류가 나면 배포본의 `bin`이 PATH에 등록됐는지 확인하세요. 파일은 Unix LF 줄바꿈으로 저장하세요. [직접 실행 예제](examples/Executable.Jenkinsfile)를 참고할 수 있습니다. 고정된 Jenkins 비교 환경에서도 shebang 헤더를 소스로 받아들이는 것을 확인했습니다. 로컬 전용 step을 Jenkins에서 사용하려면 해당 확장 기능이 필요합니다.

## Groovy와 Pipeline

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

`pipeline` 위에서 선언한 변수는 일반적인 Groovy 어휘적 범위 규칙을 따릅니다. Closure에서 값을 변경할 수 있으며 실행 간에는 공유되지 않습니다. 스크립트의 `def` 변수가 임의의 클래스 메서드에서 접근할 수 있는 필드가 되는 것은 아닙니다. `script` 안에서는 Groovy 조건문, 반복문, 컬렉션, 문자열 보간, 예외 처리를 사용할 수 있습니다.

지원 구조: `pipeline`, `stages`, 순차 `stage`, `steps`, `script`, Pipeline/stage의 `environment`, `agent any/none`, `when`, `post`, `options { timeout(...) }`.

지원 기본 step: `sh`, `dir`, `ws`, `pwd`, `echo`, `fileExists`, `readFile`, `writeFile`, `error`, `withEnv`, `timeout`, `retry`, `catchError`, 등록된 로컬 플러그인을 호출하는 `step`. `sh`는 `returnStatus`, `returnStdout`, `encoding`, `label`, shebang 스크립트를 지원합니다. 기본 shell 실행 방식은 `/bin/sh -xe`입니다.

`when`은 `expression`, `environment`, `allOf`, `anyOf`, `not`을 지원합니다. `post`는 `always`, `aborted`, `failure`, `success`, `unstable`, `cleanup`을 지원합니다. `catchError(stageResult: ...)`로 stage 결과를 처리할 수 있습니다. Stage의 `when`은 stage environment 적용 전에 실행됩니다. Stage 경고와 post 조건 동작은 고정된 Jenkins 비교 사례를 따릅니다. `currentBuild.result`로 빌드 결과를 더 나쁜 상태로 바꿀 수 있으며, `currentBuild.currentResult`로 현재 결과를 확인합니다.

`agent any`는 로컬 workspace를 할당합니다. `agent none`은 할당하지 않으므로 workspace를 사용하는 step에는 stage 수준의 `agent any`가 필요합니다. 어느 것도 Jenkins node에 연결하지 않습니다. Environment와 디렉터리 wrapper는 실행 컨텍스트와 자식 프로세스 환경을 변경하며, JVM 전체의 `System.getenv()`나 프로세스 전체 작업 디렉터리는 변경하지 않습니다. 알 수 없는 directive, step, option은 명시적으로 실패합니다.

Timeout과 Ctrl-C는 작업을 취소하고 소유한 자식 프로세스를 종료합니다. Shell 자식 프로세스는 별도 Unix 프로세스 그룹에 속하며 각 `sh` step 종료 시 정리합니다. 여러 step에 걸쳐 유지하는 백그라운드 daemon은 v1 호환 범위에 포함되지 않습니다. `post`에는 별도 30초 정리 예산이 있습니다. Groovy 반복문과 관리되는 step에는 중단 검사를 적용하지만, 중단에 협조하지 않는 native/Java 호출의 즉각적인 정상 종료는 보장할 수 없습니다. Workspace 작업과 임의의 Groovy/플러그인은 로컬 사용자의 권한으로 실행됩니다.

## Workspace

초기 workspace 우선순위는 **`--workspace` > Jenkinsfile이 있는 디렉터리**입니다. CLI에서 workspace를 별도로 지정하지 않아도 됩니다.

```sh
# 현재 shell 디렉터리와 관계없이 초기 workspace는 /projects/app입니다.
jenkinsfile-exec run /projects/app/Jenkinsfile
# 명시적 변경. 상대 경로는 명령을 실행한 shell 디렉터리를 기준으로 합니다.
jenkinsfile-exec run /projects/app/Jenkinsfile --workspace /tmp/app-build
```

Jenkinsfile 안에서는 다음과 같이 사용합니다.

```groovy
pipeline {
    agent any
    stages {
        stage('Build') {
            steps {
                ws('build/demo') {
                    sh 'pwd'
                    echo "Allocated workspace: ${env.WORKSPACE}"
                    // 파일 step과 codex도 이 workspace를 사용합니다.
                    dir('src') { sh 'pwd' }
                }
                echo "Restored working directory: ${pwd()}"
            }
        }
    }
}
```

`ws('path') { ... }`, `ws(dir: 'path') { ... }`, `ws { ... }`를 지원합니다. 경로에는 Groovy 변수와 GString을 사용할 수 있습니다. 상대 `ws` 경로는 `dir` 또는 다른 `ws` 안에서도 로컬 agent root에 해당하는 **초기 workspace**를 기준으로 해석합니다. 절대 경로는 그대로 사용합니다. 경로를 생략하거나 빈 경로를 지정하면 초기 workspace를 요청합니다. 현재 로컬 agent가 이미 사용 중이므로 `@2` 또는 다음 사용 가능한 접미사를 붙인 경로를 할당합니다.

`ws` 블록은 OS 파일 lease를 획득합니다. 다른 `ws`가 요청한 경로를 사용 중이면 기다리는 대신 사용 가능한 `@2`, `@3`, ... 형제 경로를 선택합니다. 중첩 요청도 같습니다. 블록은 `WORKSPACE`와 `WORKSPACE_TMP`를 설정하고 shell, 파일, 플러그인, AI step에 현재 경로를 전달합니다. `dir`는 작업 디렉터리만 변경하며 `WORKSPACE`는 유지합니다. 블록 종료, 실패, timeout 시 이전 경로와 환경을 복구하고 lease를 해제하며, 파일은 삭제하지 않습니다.

Lease 메타데이터는 canonical path를 키로 `~/.cache/local-jenkinsfile/workspace-locks`에 저장합니다. 따라서 심볼릭 링크 별칭이나 다른 프로젝트의 CLI 프로세스도 lease를 공유합니다. 메타데이터 파일은 해제 후에도 남습니다. 사용 여부는 파일 존재가 아닌 OS 잠금으로 판단하며 프로세스 종료 시 잠금이 해제됩니다. 이 lease는 runner 간 협력 규약이며 임의의 프로그램에 대한 보안 경계는 아닙니다. 초기 workspace와 일반 `dir`에는 전역 lease나 자동 이름 변경을 적용하지 않습니다. 실행 간 workspace 격리가 필요하면 `ws`를 사용하세요. 다른 workspace step과 마찬가지로 `ws`에도 `agent any`가 필요합니다.

[Workspace 예제](examples/Workspace.Jenkinsfile) · [Jenkins workspace 의미](https://www.jenkins.io/doc/pipeline/steps/workflow-durable-task-step/#ws-allocate-workspace)

## Codex 워크플로

배포본의 `bin`을 PATH에 등록한 뒤 번역 예제를 직접 실행할 수 있습니다.

```sh
./examples/CodexHello.Jenkinsfile
./examples/CodexTranslateInput.Jenkinsfile
```

[CodexHello.Jenkinsfile](examples/CodexHello.Jenkinsfile)은 Codex에 `hello`의 한국어와 일본어 표현을 묻습니다. [CodexTranslateInput.Jenkinsfile](examples/CodexTranslateInput.Jenkinsfile)은 터미널에서 한국어 한 줄을 입력받아 영어와 일본어로 번역합니다. 두 예제 모두 다음 stage에서 구조화된 번역 결과를 출력하고, 기존 Codex 모델 설정을 사용하며, `yolo: false`를 명시합니다. 설치 및 인증을 마친 Codex CLI가 필요하며 모델 사용량을 소비합니다. 입력 예제는 터미널이 필요하고(`interactive true`), 빈 입력은 Codex 호출 전에 실패합니다. `script` 안에서 일반 Groovy/Java API인 `System.console().readLine()`으로 읽습니다. 이 입력은 로컬 터미널 동작이며 Jenkins의 `input` step이 아닙니다. Pipeline timeout이 이 blocking console read를 중단한다고 보장할 수는 없습니다. 번역 호출은 도구 실행을 요청하지 않습니다.

```groovy
def review = null
pipeline {
    agent any
    options {
        interactive false       // 기본값
        aiSession 'shared'      // 기본값. 'isolated'도 사용 가능
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

AI 옵션 값에는 변수, Map, GString을 포함한 일반 Groovy 표현식을 사용할 수 있습니다. 지원하는 Codex 옵션은 다음과 같습니다.

| 옵션 | 의미 |
| --- | --- |
| `prompt` | 필수 작업 문자열. 빈 값 불가 |
| `systemPrompt` | Codex `baseInstructions`에 대응하는 기본 지침 교체 값. 모델 API의 system role과 완전히 같은 동작을 보장하는 것은 아님 |
| `model` | CLI 모델 식별자. 생략하면 기존 CLI 설정 사용 |
| `reasoningEffort` | 모델이 지원하는 추론 강도. 설치된 CLI의 모델 목록을 기준으로 검사 |
| `profile` | 기존 CLI 프로필. Runner가 해당 설정을 수정하지 않음 |
| `yolo` | Boolean, **기본값 true**. 정책이 허용하는 범위에서 Codex 명령 승인과 sandbox를 비활성화 |
| `newSession` | Boolean, 기본값 false. 독립적인 일회성 대화 사용 |
| `outputSchema` | 로컬에서 검사하는 JSON Schema Map. 외부 스키마 참조는 거부 |

정상 반환 값은 `text`, 선택적 `data`(파싱된 Map/List/스칼라), `sessionId`, `turnId`입니다. 실패하면 예외를 던지므로 일반적인 Pipeline/Groovy 예외 처리 방식을 사용할 수 있습니다. AI의 암묵적 재시도는 없습니다. 명시적인 `retry`는 부수 효과를 반복할 수 있지만, 결과가 모호한 `RESULT_UNKNOWN`은 기본 retry step에서도 재실행하지 않습니다.

공유 대화는 Pipeline 실행 하나와 provider 하나의 범위로 제한합니다. `newSession: true`는 공유 대화를 교체하지 않습니다. 지침과 프로필은 첫 호출에서 고정되므로 변경하려면 새 세션을 사용하세요. 모델과 추론 강도를 생략하면 공유 세션의 값을 유지합니다. 실패한 공유 세션은 무효화하며 이어서 실행하려면 새 세션이 필요합니다. 다른 실행의 최근 세션을 자동으로 재개하지 않습니다.

각 step에서 설치된 `codex app-server`를 전용 stdio 자식 프로세스로 실행하고, 기록한 thread ID만 재개하며, 성공적인 `turn/completed` 이벤트를 받으면 step을 종료합니다. App-server는 hosted service가 아닌 로컬 자식 프로세스입니다. 모델 통신과 기존 CLI 연동은 Codex 자체 설정을 따릅니다. 인증은 설치된 CLI가 관리합니다. `LOCAL_JENKINSFILE_CODEX`로 다른 실행 파일 경로를 선택할 수 있습니다. 임의의 shell 명령을 지정하는 옵션은 아닙니다.

### 사용자 상호작용과 승인

| Pipeline | AI step | 동작 |
| --- | --- | --- |
| noninteractive(기본값) | YOLO(기본값) | 명령 승인 질문 없이 실행 |
| noninteractive | `yolo: false` | 허용된 작업만 수행. 첫 승인 요청에서 turn을 취소하고 `APPROVAL_REQUIRED`로 실패 |
| `interactive true` | YOLO | 사용자 질문에 터미널에서 응답 가능 |
| `interactive true` | `yolo: false` | 승인 요청과 사용자 질문에 터미널에서 응답 |

사용자 상호작용은 한 작업 중 발생하는 요청을 처리하기 위한 기능입니다. 작업이 완료되면 자동으로 다음 step으로 진행하며 무기한 채팅을 열지 않습니다. Interactive 모드는 터미널에서 설치된 실행 파일을 직접 실행하세요. TTY가 없으면 stage 시작 전에 실패합니다. Noninteractive 모드의 사용자 질문은 `INPUT_REQUIRED`로 실패합니다. 조직 정책에 따라 YOLO가 제한될 수 있으며, 적용된 정책이 요청과 다르면 명시적으로 실패합니다. 이 client에서 구현하지 않은 요청 유형도 명시적으로 실패합니다.

## 플러그인

인자 없는 생성자, SPI 버전 `1`, 고유 ID, `StepDefinition` Map을 제공하는 `io.localjenkinsfile.api.StepPlugin`을 구현하세요. 구현 클래스는 `META-INF/services/io.localjenkinsfile.api.StepPlugin`에 등록합니다.

플러그인 JAR과 추가 의존성을 `.local-jenkinsfile/plugins/`에 넣거나 `--plugin-dir PATH`로 지정하세요. 기본 step이나 다른 플러그인과 같은 이름은 사용할 수 없습니다. Step에는 현재 workspace, 환경, logger, 취소 token, 사용자 상호작용 interface, 실행별 세션 저장소가 제공됩니다. [플러그인 가이드(영문)](docs/plugins.md)를 참고하세요.

Jenkins의 확장 가능한 step 개념을 따르지만 Jenkins HPI/JPI 바이너리 호환성은 구현하지 않았습니다. `step([$class: 'registeredStepName', ...])`은 등록된 로컬 플러그인에만 전달합니다.

## 호환성과 검증

단위/통합 테스트는 모델 통신이나 Jenkins 없이 실행합니다. 실제 shell/파일 작업과 로컬 가짜 Codex 프로토콜 프로세스로 승인, 실패, 취소, 조기 이벤트, 연결 종료 상황을 결정적으로 검증합니다.

```sh
./gradlew test :cli:installDist
python3 scripts/compare-jenkins.py
```

두 번째 명령은 체크섬으로 고정한 Jenkins LTS와 플러그인을 `build/`에 내려받고, loopback에 바인딩한 독립 controller를 시작해 사례를 비교한 뒤 종료합니다. 기존 controller에는 접속하지 않습니다. 테스트용 Jenkins는 실제 실행 의존성이 아닙니다. 비교 대상은 결과 파일과 빌드 결과이며 console 출력의 바이트 단위 일치를 요구하지 않습니다.

`python3 scripts/probe-codex.py`로 설치된 CLI의 스키마와 handshake를 확인할 수 있습니다. `--live`를 추가하면 기존 CLI 인증으로 두 번의 작은 모델 작업을 실행합니다.

실제로 검증한 범위는 [검증 근거와 남은 제한 사항(영문)](docs/verification.md)을 참고하세요. 최상위 Scripted Pipeline, parallel/matrix, 분산 agent, container, credentials 저장소, 자동 checkout, Shared Libraries, CPS 실행 상태 저장/복구, 임의의 Jenkins 플러그인, 제한 없는 Jenkins API 호환성은 지원하지 않습니다. `codex`, `interactive`, `aiSession`, 플러그인 등록은 로컬 확장이므로 Jenkins에서 같은 파일을 실행하려면 해당 확장 기능이 필요합니다.

## 개발

모듈 구조는 `plugin-api` → `runtime` / `codex-plugin` → `cli`입니다. Runtime은 텍스트 치환 대신 Groovy AST 검사와 지연 실행 closure를 사용합니다. 설계와 구현 기록은 `docs/superpowers/`에 있습니다.

라이선스는 [Apache-2.0](LICENSE)입니다.
