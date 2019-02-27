def docker_registry = "docker-registry.default.svc"
pipeline {
    agent any
    stages{
        stage('prepare') {
            steps {
                sh 'printenv |sort'
                script {
                    if (!"${BUILD_TAG}"?.trim()) {
                        currentBuild.result = 'ABORTED'
                        error('Tag to build is empty')
                    }
                    echo "Releasing tag ${BUILD_TAG}"
                    target_cluster_flags = "--server=${OCP_CLUSTER_URL} --insecure-skip-tls-verify"
                    target_cluster_flags = "$target_cluster_flags   --namespace=${OCP_PRJ_NAMESPACE}"
                }
            }
        }
        stage('Source checkout') {
            steps {
                checkout(
                    [$class                           : 'GitSCM', branches: [[name: "refs/tags/${BUILD_TAG}"]],
                        doGenerateSubmoduleConfigurations: false,
                        extensions                       : [],
                        submoduleCfg                     : [],
                        userRemoteConfigs                : [[credentialsId: "${GIT_CREDENTIAL_ID}", url: "${GIT_URL}"]]]
                )
            }
        }
        stage('Maven') {
            steps {
                withMaven(mavenSettingsFilePath: "${MVN_SETTINGS}") {
                    sh "mvn -f ${POM_FILE} versions:set -DnewVersion=${BUILD_TAG}"
                }
            }
        }
        stage('QA') {
            parallel {
                stage('SonarQube analysis') {
                    steps {
                        script{
                            if(Boolean.parseBoolean(env.SONAR)){
                                withSonarQubeEnv('Sonar-MacLocalhost') {
                                    withMaven(mavenSettingsFilePath: "${MVN_SETTINGS}") {
                                        sh "mvn -f ${POM_FILE} sonar:sonar"
                                    }
                                }
                            }else{
                                echo "SonarQube analysis skipped"
                            }
                        }
                    }
                }
                stage('Run Maven Tests') {
                    steps {
                        withMaven(mavenSettingsFilePath: "${MVN_SETTINGS}") {
                            sh "mvn -f ${POM_FILE} test"
                        }
                    }
                }
            }
        }
        stage('Publish on nexus') {
            steps {
                script{
                    if(Boolean.parseBoolean(env.DEPLOY_ON_NEXUS)){
                        echo "DEPLOY ON NEXUS"
                        withMaven(mavenSettingsFilePath: "${MVN_SETTINGS}") {
                            sh "mvn -f ${POM_FILE} clean deploy -Dmaven.javadoc.skip=true -DskipTests "
                        }
                    }else{
                        echo "PACKAGE"
                        withMaven(mavenSettingsFilePath: "${MVN_SETTINGS}") {
                            sh "mvn -f ${POM_FILE} clean package -Dappversion=${BUILD_TAG} -Dmaven.javadoc.skip=true -DskipTests "
                        }
                    }
                }
            }
        }
        stage('OCP'){
            stages {
                stage('Prepare') {
                    steps {
                        sh """
                            rm -rf ${WORKSPACE}/s2i-binary
                            mkdir -p ${WORKSPACE}/s2i-binary
                            cp ${WORKSPACE}/web-app/target/ROOT.war ${WORKSPACE}/s2i-binary
                        """
//                            mkdir -p ${WORKSPACE}/s2i-binary/configuration
//                            cp ${WORKSPACE}/runtime-configuration/ocp/standalone-openshift.xml ${WORKSPACE}/s2i-binary/configuration/
                    }
                }
                stage('UpdateBuild') {
                    steps {
                        script {
                            openshift.withCluster() {
                                def buildconfigUpdateResult =
                                    sh(
                                        script: "oc patch bc ${OCP_BUILD_NAME}  -p '{\"spec\":{\"output\":{\"to\":{\"kind\":\"ImageStreamTag\",\"name\":\"${OCP_BUILD_NAME}:${BUILD_TAG}\"}}}}' -o json $target_cluster_flags \
                                                |oc replace ${OCP_BUILD_NAME}  $target_cluster_flags -f -",
                                        returnStdout: true
                                    )
                                if (!buildconfigUpdateResult?.trim()) {
                                    currentBuild.result = 'ERROR'
                                    error('BuildConfig update finished with errors')
                                }
                                echo "Patch BuildConfig result: $buildconfigUpdateResult"
                            }
                        }

                    }
                }
                stage('StartBuild') {
                    steps {
                        script {
                            openshift.withCluster() {
                                def startBuildResult =
                                    sh(
                                        script: "oc start-build ${OCP_BUILD_NAME} --from-dir=${WORKSPACE}/s2i-binary $target_cluster_flags --follow",
                                        returnStdout: true
                                    )
                                if (!startBuildResult?.trim()) {
                                    currentBuild.result = 'ERROR'
                                    error('Start build update finished with errors')
                                }
                                echo "Start build result: $startBuildResult"
                            }
                        }
                    }
                }
                stage('Deploy') {
                    steps {
                        script {
                            openshift.withCluster() {
                                def patchIamgeStream =
                                    sh(
                                        script: "oc set image dc/${OCP_BUILD_NAME} ${OCP_BUILD_NAME}=$docker_registry:5000/${OCP_PRJ_NAMESPACE}/${OCP_BUILD_NAME}:${BUILD_TAG}  $target_cluster_flags",
                                        returnStdout: true
                                    )
                                //If the output is true the image was the same, so we check if current image is really the desired version
                                if (!patchIamgeStream?.trim()) {
                                    def currentImageStreamVersion =
                                        sh(
                                            script: "oc get dc ${OCP_BUILD_NAME} -o jsonpath='{.spec.template.spec.containers[0].image}' $target_cluster_flags",
                                            returnStdout: true
                                        )
                                    //if current DeploymentConfig image tag version it's different form BUIL_TAG we end the pipeline with an error
                                    if (!currentImageStreamVersion.equalsIgnoreCase("$docker_registry:5000/${OCP_PRJ_NAMESPACE}/${OCP_BUILD_NAME}:${BUILD_TAG}")) {
                                        echo "DeploymentConfig image tag version is: $currentImageStreamVersion but expected tag is ${BUILD_TAG}"
                                        currentBuild.result = 'ERROR'
                                        error('Rollout finished with errors: DeploymentConfig image tag version is wrong')
                                    }

                                }
                                echo "Patch imageStream result: $patchIamgeStream"
                            }
                        }
                    }
                }
                stage('Rollout') {
                    steps {
                        script {
                            openshift.withCluster() {
                                def rollout =
                                    sh(
                                        script: "oc rollout latest ${OCP_BUILD_NAME} $target_cluster_flags",
                                        returnStdout: true
                                    )
                                if (!rollout?.trim()) {
                                    currentBuild.result = 'ERROR'
                                    error('Rollout finished with errors')
                                }
                                echo "Rollout result: $rollout"
                            }
                        }
                    }
                }
            }
        }
    }
}