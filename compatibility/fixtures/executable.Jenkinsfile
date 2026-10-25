#!/usr/bin/env jenkinsfile-exec
def message = 'shebang'
pipeline {
    agent any
    stages {
        stage('Execute') {
            steps {
                script { writeFile file:'observed.txt',text:message+':groovy' }
            }
        }
    }
}
