def buildWorkspace = 'build/demo'
pipeline {
    agent any
    stages {
        stage('Workspace') {
            steps {
                ws(buildWorkspace) {
                    echo "Workspace: ${env.WORKSPACE}"
                    sh 'pwd'
                    writeFile file: 'result.txt', text: 'Written inside the allocated workspace.'
                }
                echo "Restored directory: ${pwd()}"
            }
        }
    }
}
