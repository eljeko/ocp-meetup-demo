def target_cluster_flags = ""
def docker_registry = "docker-registry.default.svc"
def rolloutNewVersion
def approve_message
//PREREQUISITES
//oc new-build --strategy docker --binary  --name docker-release
//oc start-build docker-release --from-dir . --follow

pipeline {
    agent any
    stages{
        stage('Prepare') {
            steps {
                sh 'echo "Moving to target project:${OCP_PRJ_BASE_NAMESPACE}-collaudo"'
                sh 'oc project ${OCP_PRJ_BASE_NAMESPACE}-collaudo'
                script {
                    if (!"${BUILD_TAG}"?.trim()) {
                        currentBuild.result = 'ABORTED'
                        error('Tag to build is empty')
                    }
                    echo "Releasing tag ${BUILD_TAG}"
                    target_cluster_flags = "--insecure-skip-tls-verify"
                    target_cluster_flags = "$target_cluster_flags --namespace=${OCP_PRJ_BASE_NAMESPACE}-collaudo"
                }
            }
        }
        stage('Tag Image') {
            steps{
                script{
                    def checkImageStream =
                        sh(
                            script:"oc get is ${OCP_BUILD_NAME} -o yaml --ignore-not-found=true $target_cluster_flags|grep ${BUILD_TAG}",
                            returnStatus:true
                        )
                    if (checkImageStream >= 1) {
                        sh 'oc tag ${OCP_PRJ_BASE_NAMESPACE}-test/${OCP_BUILD_NAME}:latest ${OCP_PRJ_BASE_NAMESPACE}-collaudo/${OCP_BUILD_NAME}:${BUILD_TAG}'
                    }else{
                        echo "Image with version ${BUILD_TAG} already present, will be restored"
                    }
                }
            }
        }
        stage("Confirm Rollout") {
            steps {
                script {
                    rolloutNewVersion =
                        input(  message: "Si desidere effettuare il rollout?",
                                ok: 'Procedi',
                                    parameters: [booleanParam(defaultValue: true,
                                            description: '',
                                            name: 'Procedi?')])
                    echo "Rollout:" + rolloutNewVersion
                }
            }
        }
        stage('Rollout') {
            when {
                expression { rolloutNewVersion ==~ /(?i)(Y|YES|T|TRUE|ON|RUN)/ }
            }
            steps {
                script {
                    def patchImageStream =
                        sh(
                            script: "oc set image dc/${OCP_BUILD_NAME} ${OCP_BUILD_NAME}=$docker_registry:5000/${OCP_PRJ_BASE_NAMESPACE}-collaudo/${OCP_BUILD_NAME}:${BUILD_TAG}  $target_cluster_flags",
                            returnStdout: true
                        )
                    if (!patchImageStream?.trim()) {
                        def currentImageStreamVersion =
                            sh(
                                script: "oc get dc ${OCP_BUILD_NAME} -o jsonpath='{.spec.template.spec.containers[0].image}'  $target_cluster_flags",
                                returnStdout: true
                            )
                        if (!currentImageStreamVersion.equalsIgnoreCase("$docker_registry:5000/${OCP_PRJ_BASE_NAMESPACE}-collaudo/${OCP_BUILD_NAME}:${BUILD_TAG}")) {
                            echo "DeploymentConfig image tag version is: $currentImageStreamVersion but expected tag is ${BUILD_TAG}"
                            currentBuild.result = 'ERROR'
                            error('Rollout finished with errors: DeploymentConfig image tag version is wrong')
                        }

                    }
                    def rollout =
                        sh(
                            script: "oc rollout latest ${OCP_BUILD_NAME} $target_cluster_flags",
                            returnStdout: true
                        )
                    if (!rollout?.trim()) {
                        currentBuild.result = 'ERROR'
                        error('Rollout finished with errors')
                    }else{
                        sh(
                            script: "oc label dc ${OCP_BUILD_NAME} $target_cluster_flags img_version=${BUILD_TAG} --overwrite=true",
                            returnStdout: true
                        )
                    }
                    echo "Rollout result: $rollout"
                }
            }
        }
    }
    post {
        always {
            sh 'oc project jenkins'
        }
    }
}