pipeline {
    agent any
    stages {
        stage('Selected') {
            when { anyOf { expression { true }; expression { error 'must not evaluate' } } }
            steps { writeFile file: 'observed.txt', text: 'selected' }
        }
        stage('Skipped') {
            when { allOf { expression { false }; expression { error 'must not evaluate' } } }
            steps { error 'must not execute' }
        }
    }
}
