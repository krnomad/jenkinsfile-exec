def count = 0
pipeline {
    agent any
    environment { OUTER = 'yes' }
    stages {
        stage('Skipped') {
            environment { TARGET = 'yes' }
            when { environment name: 'TARGET', value: 'yes' }
            steps { script { count++ } }
        }
        stage('Selected') {
            when { environment name: 'OUTER', value: 'yes' }
            steps { writeFile file: 'observed.txt', text: "${count}" }
        }
    }
}
