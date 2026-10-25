#!/usr/bin/env python3
"""Exercise the installed distribution and verify SIGINT completes cleanup promptly."""
import json
import os
from pathlib import Path
import signal
import subprocess
import time
import uuid

ROOT = Path(__file__).resolve().parents[1]
work = ROOT / 'build' / ('cli-smoke-' + uuid.uuid4().hex[:8])
work.mkdir(parents=True)
script = work / 'Jenkinsfile'
script.write_text('''pipeline {
    agent any
    stages { stage('Wait') { steps {
        writeFile file:'started',text:'yes'
        sh 'sleep 20'
    } } }
    post { aborted { writeFile file:'cleanup',text:'yes' } }
}
''')
command = [str(ROOT / 'cli/build/install/jenkinsfile-exec/bin/jenkinsfile-exec'), 'run', str(script), '--workspace', str(work)]
with (work / 'output.log').open('w') as log:
    process = subprocess.Popen(command, stdout=log, stderr=subprocess.STDOUT)
    try:
        deadline = time.monotonic() + 10
        while not (work / 'started').exists():
            if process.poll() is not None or time.monotonic() > deadline:
                raise RuntimeError('CLI did not start; see ' + str(work / 'output.log'))
            time.sleep(0.05)
        time.sleep(0.3)
        start = time.monotonic()
        process.send_signal(signal.SIGINT)
        code = process.wait(timeout=4)
        elapsed = time.monotonic() - start
        assert code == 130, code
        assert (work / 'cleanup').read_text() == 'yes'
        reports = list((work / '.local-jenkinsfile/runs').glob('*.json'))
        assert len(reports) == 1 and json.loads(reports[0].read_text())['status'] == 'ABORTED'
        print(f'PASS: SIGINT exit130, cleanup and report in {elapsed:.2f}s. Evidence: {work}')
    finally:
        if process.poll() is None:
            process.kill()
            process.wait()
