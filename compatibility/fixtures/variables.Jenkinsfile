def value = 'old'
def selected = []
pipeline {
    agent any
    environment { OUTER = 'pipeline' }
    stages {
        stage('prepare') {
            steps {
                script {
                    for (def n in [1, 2, 3]) {
                        if (n % 2 == 1) { selected << n }
                    }
                    value = 'new'
                }
            }
        }
        stage('scoped') {
            environment { INNER = "${env.OUTER}-stage" }
            when { environment name: 'OUTER', value: 'pipeline' }
            steps {
                dir('child') {
                    withEnv(['OUTER=wrapped']) {
                        sh 'printf "%s" "$OUTER" > value'
                    }
                }
                writeFile file: 'observed.txt', text: "${value}:${selected.join(',')}:${env.INNER}"
            }
        }
        stage('restore') {
            steps {
                script {
                    def content = readFile('observed.txt')
                    writeFile file: 'observed.txt', text: "${content}:${env.OUTER}:${readFile('child/value')}"
                }
            }
        }
    }
}
