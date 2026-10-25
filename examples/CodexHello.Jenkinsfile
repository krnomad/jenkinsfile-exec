#!/usr/bin/env jenkinsfile-exec
// Requires an installed, authenticated Codex CLI.
def word = 'hello'
def translated = null
pipeline {
    agent any
    options { timeout(time: 2, unit: 'MINUTES') }
    stages {
        stage('Translate hello') {
            steps {
                script {
                    translated = codex(
                        yolo: false,
                        systemPrompt: 'You are a translator. Translate the supplied greeting into natural Korean and Japanese. Do not use tools, execute commands, or change files. Return only the requested structured result.',
                        prompt: "How do you say '${word}' in Korean and Japanese?",
                        outputSchema: [
                            type: 'object',
                            properties: [korean: [type: 'string'], japanese: [type: 'string']],
                            required: ['korean', 'japanese'],
                            additionalProperties: false
                        ]
                    )
                }
            }
        }
        stage('Show translations') {
            steps {
                echo "원문: ${word}"
                echo "한국어: ${translated.data.korean}"
                echo "일본어: ${translated.data.japanese}"
            }
        }
    }
}
