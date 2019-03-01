def target_cluster_flags = ""
def docker_registry = "docker-registry.default.svc"
def rolloutNewVersion
def approve_message
def currentState = 'green'
def newState = 'blue'
def pom_file = ''
def version = ''

//PREREQUISITES
//oc new-build --strategy docker --binary  --name docker-release
//oc start-build docker-release --from-dir . --follow

pipeline {
    agent any
    stages{
        stage('Prepare') {
            steps {
                script {
                    if (!"${BUILD_TAG}"?.trim()) {
                        currentBuild.result = 'ABORTED'
                        error('Tag to build is empty')
                    }
                    echo "Releasing tag ${BUILD_TAG}"
                    target_cluster_flags = "--server=${OCP_CLUSTER_URL} --insecure-skip-tls-verify"
                    target_cluster_flags = "$target_cluster_flags   --namespace=${OCP_PRJ_BASE_NAMESPACE}-prod"
                }
            }
        }
        stage('Verify Active Service'){
            steps{
                script{
                    withCredentials([string(credentialsId: "${OCP_SERVICE_TOKEN}", variable: 'OCP_SERVICE_TOKEN')]) {
                        def activeService = {
                            sh(
                                script: "oc get route ${OCP_BUILD_NAME} -o jsonpath='{.spec.to.name}' --token=${OCP_SERVICE_TOKEN}  $target_cluster_flags |grep ${currentState}",
                                returnStdout: true
                            )
                        }
                        echo "Active Service:" + activeService
//                        if (activeService == "${OCP_BUILD_NAME}-blue") {
                        if (activeService >= 1 ) {
                            newState = 'green'
                            currentState = 'blue'
                        }
                        echo "Curret Service ${OCP_BUILD_NAME}-${currentState} will be replaced with ${OCP_BUILD_NAME}-${newState}"
                    }
                }
            }
        }
        stage('Tag Image') {
            steps{
                script{
                    withCredentials([string(credentialsId: "${OCP_SERVICE_TOKEN}", variable: 'OCP_SERVICE_TOKEN')]) {
                        def checkImageStream =
                            sh(
                                script:"oc get is ${OCP_BUILD_NAME}-${newState} -o yaml --ignore-not-found=true --token=${OCP_SERVICE_TOKEN}  $target_cluster_flags|grep ${BUILD_TAG}",
                                returnStatus:true
                            )
                        if (checkImageStream >= 1) {
                            def tagImageStrem =
                                sh(
                                    script:"oc tag ${OCP_PRJ_BASE_NAMESPACE}/${OCP_BUILD_NAME}:${BUILD_TAG} ${OCP_PRJ_BASE_NAMESPACE}-prod/${OCP_BUILD_NAME}-${newState}:${BUILD_TAG} $target_cluster_flags --token=${OCP_SERVICE_TOKEN}",
                                    returnStatus:true
                                )
                        }else{
                            echo "Image with version ${BUILD_TAG} already present, will be restored"
                        }
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
                    withCredentials([string(credentialsId: "${OCP_SERVICE_TOKEN}", variable: 'OCP_SERVICE_TOKEN')]) {
                        def patchImageStream =
                            sh(
                                script: "oc set image dc/${OCP_BUILD_NAME}-${newState} ${OCP_BUILD_NAME}-${newState}=$docker_registry:5000/${OCP_PRJ_BASE_NAMESPACE}-prod/${OCP_BUILD_NAME}-${newState}:${BUILD_TAG}  --token=${OCP_SERVICE_TOKEN}  $target_cluster_flags",
                                returnStdout: true
                            )
                        def rollout =
                            sh(
                                script: "oc rollout latest ${OCP_BUILD_NAME}-${newState} --token=${OCP_SERVICE_TOKEN}  $target_cluster_flags",
                                returnStdout: true
                            )
                        if (!rollout?.trim()) {
                            currentBuild.result = 'ERROR'
                            error('Rollout finished with errors')
                        }else{
                            sh(
                                script: "oc label dc ${OCP_BUILD_NAME}-${newState} $target_cluster_flags img_version=${BUILD_TAG} --token=${OCP_SERVICE_TOKEN} --overwrite=true",
                                returnStdout: true
                            )
                        }
                        echo "Rollout result: $rollout"
                    }
                }
            }
        }
        stage('Patch Route'){
            steps {
                script {
                    withCredentials([string(credentialsId: "${OCP_SERVICE_TOKEN}", variable: 'OCP_SERVICE_TOKEN')]) {
                        def patchRoute =
                            sh(
                                script: "oc patch route/${OCP_BUILD_NAME}  --patch='{\"spec\":{\"to\":{\"name\":\"${OCP_BUILD_NAME}-${newState}\"}}}' --token=${OCP_SERVICE_TOKEN}  $target_cluster_flags \
                                        |oc replace ${OCP_BUILD_NAME} --token=${OCP_SERVICE_TOKEN} $target_cluster_flags -f -",
                                returnStdout: true
                            )
                        echo "Patched route: $patchRoute"
                    }
                }
            }
        }
    }
}