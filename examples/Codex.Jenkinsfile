def instructions = 'Review the code and explain concrete findings. Do not modify files.'
def review = null
pipeline {
    agent any
    options {
        interactive false
        aiSession 'shared'
        timeout(time: 10, unit: 'MINUTES')
    }
    stages {
        stage('Review') {
            steps {
                script {
                    review = codex(
                        systemPrompt: instructions,
                        reasoningEffort: 'high',
                        prompt: 'Review the project in the current workspace. Return a short summary.',
                        outputSchema: [
                            type: 'object',
                            properties: [summary: [type: 'string']],
                            required: ['summary'],
                            additionalProperties: false
                        ]
                    )
                    echo review.data.summary
                }
            }
        }
        stage('Follow up') {
            steps {
                script {
                    def followup = codex(prompt: 'From your review, name the most important next action.')
                    echo followup.text
                }
            }
        }
    }
}
