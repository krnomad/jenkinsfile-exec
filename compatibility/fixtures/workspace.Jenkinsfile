pipeline {
    agent any
    stages {
        stage('Workspace') {
            steps {
                script {
                    def base = pwd()
                    def target = "${base}/allocated"
                    assert env.WORKSPACE == base
                    dir('sub') {
                        ws(dir: target) {
                            assert pwd() == target
                            assert env.WORKSPACE == target
                            assert env.WORKSPACE_TMP == "${target}@tmp"
                            writeFile file: 'inside', text: 'scoped'
                            dir('child') { assert env.WORKSPACE == target }
                            ws(target) { assert pwd() == "${target}@2" }
                            assert pwd() == target
                        }
                        assert pwd() == "${base}/sub"
                        assert env.WORKSPACE == base
                    }
                    try {
                        ws(target) { error 'expected' }
                    } catch (Exception error) {
                        assert error.message == 'expected'
                    }
                    ws(target) { assert pwd() == target }
                    ws { assert pwd() == "${base}@2" }
                    assert pwd() == base
                    assert env.WORKSPACE == base
                    writeFile file: 'observed.txt', text: "${readFile('allocated/inside')}:leased:restored"
                }
            }
        }
    }
}
