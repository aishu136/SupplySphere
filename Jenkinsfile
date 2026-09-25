// Jenkins pipeline mirroring .github/workflows/ci.yml.
//
// Agent requirements: a node labelled "docker" with Docker Engine, the Compose plugin and Buildx
// (plus QEMU/binfmt for the ARM64 AgentCore image), and the Docker Pipeline and JUnit plugins.
// Builds and tests run inside tool containers, so the node needs no JDK, Python or Node.
pipeline {
    agent none

    options {
        disableConcurrentBuilds(abortPrevious: true)
        buildDiscarder(logRotator(numToKeepStr: '20'))
        timeout(time: 90, unit: 'MINUTES')
    }

    stages {
        stage('Build and test') {
            parallel {
                stage('Microservices (Spring Boot)') {
                    agent { docker { image 'eclipse-temurin:21-jdk'; label 'docker' } }
                    environment { GRADLE_USER_HOME = "${WORKSPACE}/.gradle-home" }
                    steps {
                        dir('scm-platform') {
                            sh './gradlew --no-daemon build'
                        }
                    }
                    post {
                        always { junit allowEmptyResults: true, testResults: 'scm-platform/*/build/test-results/test/*.xml' }
                    }
                }

                stage('Stream processor (Flink)') {
                    agent { docker { image 'eclipse-temurin:17-jdk'; label 'docker' } }
                    environment { GRADLE_USER_HOME = "${WORKSPACE}/.gradle-home" }
                    steps {
                        dir('scm-stream-processor') {
                            sh './gradlew --no-daemon build'
                        }
                        archiveArtifacts artifacts: 'scm-stream-processor/build/libs/scm-stream-processor-*.jar', fingerprint: true
                        stash name: 'flink-job', includes: 'scm-stream-processor/build/libs/scm-stream-processor-*.jar'
                    }
                    post {
                        always { junit allowEmptyResults: true, testResults: 'scm-stream-processor/build/test-results/test/*.xml' }
                    }
                }

                stage('AI service (Python)') {
                    // Root so apt can install OpenCV's system libraries; the workspace is handed back afterwards.
                    agent { docker { image 'python:3.12-slim'; label 'docker'; args '-u root -e HOME=/tmp' } }
                    steps {
                        dir('scm-ai-service') {
                            sh '''
                                apt-get update && apt-get install -y --no-install-recommends libgl1 libglib2.0-0
                                python -m venv /tmp/venv
                                . /tmp/venv/bin/activate
                                pip install --extra-index-url https://download.pytorch.org/whl/cpu -r requirements.txt
                                # Tests stub YOLO, embeddings and the core API, so no AWS credentials or model downloads are needed.
                                pytest -q -p no:cacheprovider --junitxml=build/test-results/pytest.xml
                            '''
                        }
                    }
                    post {
                        always {
                            junit allowEmptyResults: true, testResults: 'scm-ai-service/build/test-results/*.xml'
                            sh 'chown -R "$(stat -c %u:%g .)" scm-ai-service'
                        }
                    }
                }

                stage('UI (Angular)') {
                    agent { docker { image 'node:22'; label 'docker' } }
                    environment { npm_config_cache = "${WORKSPACE}/.npm" }
                    steps {
                        dir('scm-ui') {
                            sh 'npm ci --no-audit --no-fund'
                            // Strict template type-check.
                            sh 'npx ng build'
                        }
                    }
                }
            }
        }

        stage('Docker images') {
            agent { label 'docker' }
            steps {
                script {
                    def services = ['catalog-service', 'inventory-service', 'order-service', 'shipment-service', 'alert-service', 'api-gateway']
                    services.each { svc ->
                        sh "docker build --build-arg SERVICE=${svc} -t ${svc}:ci scm-platform"
                    }
                }
                sh 'docker build -t scm-ai:ci scm-ai-service'
                // AgentCore Runtime runs ARM64 containers.
                sh 'docker buildx build --platform linux/arm64 -f scm-ai-service/Dockerfile.agentcore -t scm-agentcore:ci scm-ai-service'
                sh 'docker build -t scm-ui:ci scm-ui'
                sh 'docker compose config --quiet'
            }
        }

        stage('End-to-end (Docker Compose)') {
            agent { label 'docker' }
            steps {
                unstash 'flink-job'
                sh '''
                    docker compose up -d --build --wait --wait-timeout 600 \
                      kafka postgres jaeger \
                      catalog-service inventory-service order-service shipment-service alert-service api-gateway \
                      flink-jobmanager flink-taskmanager \
                      scm-ai
                '''
                sh '''
                    for attempt in 1 2 3 4 5; do
                      docker compose run --rm flink-job-submitter && exit 0
                      echo "Submission attempt $attempt failed; retrying in 10s"
                      sleep 10
                    done
                    echo "Flink job submission failed after 5 attempts"
                    exit 1
                '''
                sh 'bash scripts/e2e-smoke.sh'
            }
            post {
                failure {
                    sh 'docker compose ps -a || true'
                    sh 'docker compose logs --no-color --tail=200 || true'
                }
                always {
                    sh 'docker compose down -v'
                }
            }
        }
    }
}
