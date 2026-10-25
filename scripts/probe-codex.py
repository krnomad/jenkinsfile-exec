#!/usr/bin/env python3
"""Validate the installed CLI protocol; --live additionally performs two small model turns.
Uses the installed CLI's existing authentication; never changes its configuration.
"""
import argparse
import json
import os
from pathlib import Path
import select
import shutil
import subprocess
import tempfile
import uuid

ROOT = Path(__file__).resolve().parents[1]

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--live', action='store_true', help='Perform two read-only model calls; consumes CLI usage')
    opts = parser.parse_args()
    cli = os.environ.get('LOCAL_JENKINSFILE_CODEX') or shutil.which('codex')
    if not cli:
        raise RuntimeError('Install and authenticate Codex CLI first')
    version = subprocess.check_output([cli, '--version'], text=True, timeout=10).strip()
    with tempfile.TemporaryDirectory(prefix='local-jenkinsfile-protocol-') as tmp:
        subprocess.run([cli, 'app-server', 'generate-json-schema', '--out', tmp], check=True, timeout=30)
        expectations = {
            'ThreadStartParams': ['baseInstructions', 'approvalPolicy', 'approvalsReviewer', 'cwd', 'sandbox'],
            'ThreadResumeParams': ['threadId', 'baseInstructions', 'cwd'],
            'TurnStartParams': ['threadId', 'input', 'outputSchema', 'effort'],
            'TurnCompletedNotification': ['threadId', 'turn'],
        }
        for name, fields in expectations.items():
            candidates = list(Path(tmp).rglob(name + '.json'))
            if not candidates:
                raise RuntimeError('Missing schema: ' + name)
            data = json.loads(candidates[0].read_text())
            if set(fields) - set(data.get('properties', {})):
                raise RuntimeError('Incompatible schema: ' + name)
    process = subprocess.Popen([cli, 'app-server', '--listen', 'stdio://'], stdin=subprocess.PIPE,
                               stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True)
    try:
        request = {'id': 1, 'method': 'initialize', 'params': {
            'clientInfo': {'name': 'jenkinsfile_exec_probe', 'version': '0.1.0'},
            'capabilities': {'experimentalApi': True}}}
        process.stdin.write(json.dumps(request) + '\n')
        process.stdin.flush()
        if not select.select([process.stdout], [], [], 10)[0]:
            raise TimeoutError('Codex initialize timed out')
        response = json.loads(process.stdout.readline())
        if response.get('id') != 1 or 'result' not in response:
            raise RuntimeError('Codex initialize failed')
        process.stdin.write(json.dumps({'method': 'initialized', 'params': {}}) + '\n')
        process.stdin.flush()
    finally:
        process.stdin.close()
        try:
            process.wait(timeout=3)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait()
    print(version + ': schema and stdio initialize PASS')
    if not opts.live:
        return 0
    run_dir = ROOT / 'build' / ('codex-live-' + uuid.uuid4().hex[:8])
    run_dir.mkdir(parents=True)
    script = run_dir / 'Jenkinsfile'
    script.write_text('''
def first = null
pipeline {
    agent any
    options { timeout(time: 90, unit: 'SECONDS') }
    stages {
        stage('First turn') { steps { script {
            first = codex(yolo: false,
                systemPrompt: 'This is a protocol smoke test. Do not use tools or edit files. Return the requested answer using the output schema.',
                prompt: 'Remember token LJ-73K9. Return it as answer.',
                outputSchema: [type:'object',properties:[answer:[type:'string']],required:['answer'],additionalProperties:false])
            assert first.data.answer == 'LJ-73K9'
        } } }
        stage('Shared context') { steps { script {
            def next = codex(yolo: false,
                prompt: 'What token did I ask you to remember? Return it as answer.',
                outputSchema: [type:'object',properties:[answer:[type:'string']],required:['answer'],additionalProperties:false])
            assert next.sessionId == first.sessionId
            assert next.data.answer == 'LJ-73K9'
        } } }
    }
}
''')
    executable = ROOT / 'cli/build/install/jenkinsfile-exec/bin/jenkinsfile-exec'
    result = subprocess.run([str(executable), 'run', str(script), '--workspace', str(run_dir)], timeout=120)
    print('Live evidence: ' + str(run_dir))
    return result.returncode

if __name__ == '__main__':
    raise SystemExit(main())
