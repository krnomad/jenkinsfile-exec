pipeline {
    agent any
    stages {
        stage('fail') {
            steps { sh 'exit 7' }
            post { failure { writeFile file: 'observed.txt', text: 'stage-failure' } }
        }
        stage('skip') { steps { error 'must never run' } }
    }
    post {
        always {
            script { writeFile file: 'observed.txt', text: "${readFile('observed.txt')}:always" }
        }
        failure {
            script { writeFile file: 'observed.txt', text: "${readFile('observed.txt')}:failure" }
        }
        cleanup {
            script { writeFile file: 'observed.txt', text: "${readFile('observed.txt')}:cleanup" }
        }
    }
}
