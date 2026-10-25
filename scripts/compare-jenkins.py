#!/usr/bin/env python3
"""Compare fixtures against an isolated, disposable Jenkins pinned by checksum.
Downloads only on first use. Binds Jenkins to loopback; never touches existing controllers.
Requires Java 21+, Python 3.10+, and an installed CLI distribution.
"""
import argparse
import base64
import concurrent.futures
import hashlib
import json
import os
from pathlib import Path
import shutil
import socket
import subprocess
import time
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parents[1]

def download(entry, target):
    digest = base64.b64decode(entry['sha256'])
    if target.exists() and hashlib.sha256(target.read_bytes()).digest() == digest:
        return target
    target.parent.mkdir(parents=True, exist_ok=True)
    with urllib.request.urlopen(entry['url'], timeout=90) as response:
        content = response.read()
    if hashlib.sha256(content).digest() != digest:
        raise RuntimeError(f'Checksum mismatch: {target.name}')
    temporary = target.with_suffix('.download')
    temporary.write_bytes(content)
    temporary.replace(target)
    return target


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--timeout', type=int, default=240)
    opts = parser.parse_args()
    lock = json.loads((ROOT / 'compatibility/jenkins.lock.json').read_text())
    cache = ROOT / 'build/jenkins-cache'
    work = ROOT / 'build' / ('jenkins-comparison-' + uuid.uuid4().hex[:8])
    work.mkdir(parents=True)
    home = work / 'home'
    (home / 'plugins').mkdir(parents=True)
    war = download(lock['core'], cache / ('jenkins-' + lock['core']['version'] + '.war'))
    def plugin(item):
        name, entry = item
        path = download(entry, cache / (name + '-' + entry['version'] + '.jpi'))
        shutil.copyfile(path, home / 'plugins' / (name + '.jpi'))
    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
        list(pool.map(plugin, lock['plugins'].items()))
    print(f"Downloaded and verified Jenkins {lock['core']['version']}, {len(lock['plugins'])} plugins", flush=True)
    cases = []
    cli = ROOT / 'cli/build/install/jenkinsfile-exec/bin/jenkinsfile-exec'
    for fixture in sorted((ROOT / 'compatibility/fixtures').glob('*.Jenkinsfile')):
        workspace = work / ('local-' + fixture.stem)
        run = subprocess.run([str(cli), 'run', str(fixture), '--workspace', str(workspace)],
                             stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, timeout=60)
        (work / (fixture.stem + '-local.log')).write_text(run.stdout)
        records = list((workspace / '.local-jenkinsfile/runs').glob('*.json'))
        record = json.loads(records[0].read_text())
        observed = workspace / 'observed.txt'
        cases.append({'name': fixture.stem, 'script': fixture.read_text(),
                      'local': {'status': record['status'], 'observed': observed.read_text() if observed.exists() else None}})
    case_file = work / 'cases.json'
    case_file.write_text(json.dumps(cases))
    result_file = work / 'jenkins-results.json'
    init = home / 'init.groovy.d'
    init.mkdir()
    # Use a JSON file rather than interpolating executable fixture text into the init script.
    (init / 'compare.groovy').write_text('''
import jenkins.model.Jenkins
import hudson.init.InitMilestone
import org.jenkinsci.plugins.workflow.job.WorkflowJob
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition
import groovy.json.JsonSlurper
import groovy.json.JsonOutput
import java.util.concurrent.TimeUnit
Jenkins.get().setNumExecutors(2)
Thread.startDaemon('local-jenkinsfile-compatibility') {
    def results=[]
    try {
        while(Jenkins.get().initLevel != InitMilestone.COMPLETED) { Thread.sleep(100) }
        def cases=new JsonSlurper().parse(new File(System.getProperty('comparison.cases')))
        cases.each { test ->
            def job=Jenkins.get().createProject(WorkflowJob.class, test.name)
            job.definition=new CpsFlowDefinition(test.script, false)
            def build=job.scheduleBuild2(0).get(90,TimeUnit.SECONDS)
            def observed=Jenkins.get().getWorkspaceFor(job)?.child('observed.txt')
            results << [name:test.name,status:build.result.toString(),observed:observed?.exists()?observed.readToString():null]
            new File(System.getProperty('comparison.output')+'.'+test.name+'.log').text=build.getLog(3000).join('\\n')
        }
    } catch(Throwable error) { results << [error:error.toString()] }
    finally { new File(System.getProperty('comparison.output')).text=JsonOutput.prettyPrint(JsonOutput.toJson(results)) }
}
''')
    with socket.socket() as sock:
        sock.bind(('127.0.0.1', 0))
        port = sock.getsockname()[1]
    java = str(Path(os.environ['JAVA_HOME']) / 'bin/java') if os.environ.get('JAVA_HOME') else 'java'
    env = dict(os.environ, JENKINS_HOME=str(home))
    command = [java, '-Xmx768m', '-Djenkins.install.runSetupWizard=false',
               '-Dhudson.model.UpdateCenter.never=true', '-Djenkins.model.DownloadService.never=true',
               '-Dcomparison.cases=' + str(case_file), '-Dcomparison.output=' + str(result_file),
               '-jar', str(war), '--httpListenAddress=127.0.0.1', '--httpPort=' + str(port),
               '--webroot=' + str(work / 'webroot')]
    with (work / 'controller.log').open('w') as log:
        process = subprocess.Popen(command, env=env, stdout=log, stderr=subprocess.STDOUT)
        try:
            deadline = time.monotonic() + opts.timeout
            while not result_file.exists():
                if process.poll() is not None:
                    raise RuntimeError(f'Jenkins exited {process.returncode}; see {work}/controller.log')
                if time.monotonic() > deadline:
                    raise TimeoutError(f'Jenkins comparison timed out; see {work}/controller.log')
                time.sleep(0.5)
            # Atomic publication is not guaranteed by Jenkins init Groovy, wait for complete JSON.
            for _ in range(20):
                try:
                    results = json.loads(result_file.read_text())
                    break
                except json.JSONDecodeError:
                    time.sleep(0.1)
            else:
                raise RuntimeError('Incomplete comparison result')
        finally:
            process.terminate()
            try:
                process.wait(timeout=15)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait()
    failed = False
    for case in cases:
        actual = next((x for x in results if x.get('name') == case['name']), None)
        if actual is None:
            print('Jenkins errors: ' + json.dumps(results))
        same = actual is not None and case['local'] == {k: actual[k] for k in ['status', 'observed']}
        print(('PASS' if same else 'FAIL') + ' ' + case['name'] + ': ' + json.dumps({'local': case['local'], 'jenkins': actual}))
        failed |= not same
    print('Evidence: ' + str(work))
    return int(failed)

if __name__ == '__main__':
    raise SystemExit(main())
