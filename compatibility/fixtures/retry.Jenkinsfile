def attempts = 0
pipeline {
    agent any
    stages {
        stage('retry') {
            steps {
                script {
                    retry(3) {
                        attempts++
                        if (attempts < 3) { error 'retry' }
                    }
                    def status = sh(script: 'exit 9', returnStatus: true)
                    def text = sh(script: 'printf hello', returnStdout: true)
                    writeFile file: 'observed.txt', text: "${attempts}:${status}:${text}"
                }
            }
        }
    }
}
