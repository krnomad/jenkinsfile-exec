#!/usr/bin/env jenkinsfile-exec
def message = 'Hello from an executable Jenkinsfile'
pipeline {
    agent any
    stages {
        stage('Hello') {
            steps {
                echo message
                sh 'pwd'
            }
        }
    }
}
