#!/usr/bin/env python3
"""Execute a Jenkinsfile through its OS shebang, including paths with spaces."""
import json
import os
from pathlib import Path
import subprocess
import uuid

ROOT = Path(__file__).resolve().parents[1]
CLI = ROOT / 'cli/build/install/jenkinsfile-exec/bin/jenkinsfile-exec'
work = ROOT / 'build' / ('shebang smoke ' + uuid.uuid4().hex[:8])
project = work / 'project with spaces'
caller = work / 'caller'
project.mkdir(parents=True)
caller.mkdir()
script = project / 'Jenkinsfile'
script.write_text('''#!/usr/bin/env jenkinsfile-exec
def message = 'direct'
pipeline {
    agent any
    stages {
        stage('Execute') {
            steps {
                script {
                    writeFile file:'where', text:message+':'+pwd()
                    ws('nested') { sh 'printf nested > marker' }
                }
            }
        }
    }
}
''')
script.chmod(0o755)
env = dict(os.environ, PATH=str(CLI.parent) + os.pathsep + os.environ.get('PATH', ''))


def run(args, name, expected=0, cwd=caller):
    # No shell: the kernel must find and execute the shebang interpreter.
    result = subprocess.run(args, cwd=cwd, env=env, stdout=subprocess.PIPE,
                            stderr=subprocess.STDOUT, text=True, timeout=30)
    (work / (name + '.log')).write_text(result.stdout)
    assert result.returncode == expected, result.stdout


def verify(directory):
    assert (directory / 'where').read_text() == 'direct:' + str(directory)
    assert (directory / 'nested/marker').read_text() == 'nested'
    records = list((directory / '.local-jenkinsfile/runs').glob('*.json'))
    assert len(records) == 1
    assert json.loads(records[0].read_text())['status'] == 'SUCCESS'


run([str(CLI), 'check', str(script)], 'static-check')
assert not (project / 'where').exists()
run([str(script)], 'default')
verify(project)
override = work / 'override with spaces'
# Exercise ./Jenkinsfile as well as invocation by absolute path.
run(['./Jenkinsfile', '--workspace', str(override)], 'override', cwd=project)
verify(override)
assert not (caller / '.local-jenkinsfile').exists()
script.write_text("#!/usr/bin/env jenkinsfile-exec\npipeline { agent any; stages { stage('Fail') { steps { sh 'exit 7' } } } }\n")
run([str(script)], 'failure', expected=1)
assert any(json.loads(p.read_text())['status'] == 'FAILURE'
           for p in (project / '.local-jenkinsfile/runs').glob('*.json'))
print('PASS: OS shebang, spaces, Groovy variables, default workspace, ws, CLI override, static check and failure exit. Evidence: ' + str(work))
