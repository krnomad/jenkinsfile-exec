pipeline {
    agent any
    stages {
        stage('Timeout') {
            options { timeout(time: 1, unit: 'SECONDS') }
            steps { sh 'sleep 10' }
            post { aborted { writeFile file: 'observed.txt', text: 'aborted' } }
        }
    }
}
