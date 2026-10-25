pipeline {
    agent any
    stages { stage('Start') { steps { writeFile file: 'observed.txt', text: 'start' } } }
    post {
        always { error 'post failure' }
        failure { script { writeFile file: 'observed.txt', text: "${readFile('observed.txt')}:failure" } }
        cleanup { script { writeFile file: 'observed.txt', text: "${readFile('observed.txt')}:cleanup" } }
    }
}
