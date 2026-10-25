pipeline {
    agent any
    stages {
        stage('Unstable stage') {
            steps { writeFile file: 'observed.txt', text: 'before'; catchError(buildResult: 'SUCCESS', stageResult: 'UNSTABLE') { error 'caught' } }
            post { unstable { writeFile file: 'observed.txt', text: 'unstable' } }
        }
    }
    post { success { script { writeFile file: 'observed.txt', text: "${readFile('observed.txt')}:success" } } }
}
