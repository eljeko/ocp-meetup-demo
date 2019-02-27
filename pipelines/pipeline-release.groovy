def target_cluster_flags = ""

pipeline {
    agent any
    stages {
        stage('prepare') {
            steps {
                script {
                    if (!"${BUILD_TAG}"?.trim()) {
                        currentBuild.result = 'ABORTED'
                        error('Tag to build is empty')
                    }
                    echo "Releasing tag ${BUILD_TAG}"

                    target_cluster_flags = "--server=$ocp_cluster_url --insecure-skip-tls-verify"
                }
            }
        }

        stage('Build') {
            stages {
                stage('Source checkout') {
                    steps {
                        checkout(
                                [$class                           : 'GitSCM', branches: [[name: "refs/tags/${BUILD_TAG}"]],
                                 doGenerateSubmoduleConfigurations: false,
                                 extensions                       : [],
                                 submoduleCfg                     : [],
                                 userRemoteConfigs                : [[credentialsId: "${GIT_CREDENTIAL_ID}", url: "${APP_GIT_URL}"]]]
                        )
                    }
                }

                stage('Maven setup') {
                    steps {
                        withMaven(mavenSettingsFilePath: "${MVN_SETTINGS}") {
                            sh "mvn -f ${POM_FILE} versions:set -DnewVersion=${BUILD_TAG}"
                        }
                    }

                }
                stage('SonarQube analysis') {
                    steps {
                        withSonarQubeEnv('Sonarqube') {
                            withMaven(mavenSettingsFilePath: "${MVN_SETTINGS}") {
                                sh "mvn -f ${POM_FILE} sonar:sonar"
                            }
                        }
                    }
                }
            }
        }

        stage('Bake') {
            stages {
                stage('Configuration checkout') {
                    steps {
                        checkout(
                                [$class           : 'GitSCM', branches: [[name: "refs/tags/${APP_CONF_BUILD_TAG}"]], doGenerateSubmoduleConfigurations: false,
                                 extensions       : [[$class: 'RelativeTargetDirectory', relativeTargetDir: 'runtime-configuration']],
                                 submoduleCfg     : [],
                                 userRemoteConfigs: [[credentialsId: "${GIT_CREDENTIAL_ID}", url: "${APP_CONF_GIT_URL}"]]]
                        )
                    }
                }

                stage('Prepare') {
                    steps {
                        sh """
rm -rf ${WORKSPACE}/s2i-binary
mkdir -p ${WORKSPACE}/s2i-binary/configuration
curl http://sdpsvalmbf.rete.testposte/nexus/repository/semplificazione-applicativa/it/poste/sa/nautilus-app/${BUILD_TAG}/nautilus-app-${BUILD_TAG}.war -o ${WORKSPACE}/s2i-binary/nau.war
cp ${WORKSPACE}/runtime-configuration/ocp/standalone-openshift.xml ${WORKSPACE}/s2i-binary/configuration/
"""
                        script {
                            withCredentials([string(credentialsId: 'jenkins-token-semplificazione', variable: 'SECRET_OCP_SERVICE_TOKEN')]) {
                                def buildconfigUpdateResult =
                                        sh(script: "oc patch bc eap71-nautilus  -p '{\"spec\":{\"output\":{\"to\":{\"kind\":\"DockerImage\",\"name\":\"sdpsvalmbf.rete.testposte:5000/eap71-nautilus:${BUILD_TAG}\"}}}}' --namespace=semplificazione -o json --token=$SECRET_OCP_SERVICE_TOKEN $target_cluster_flags |oc replace eap71-nautilus --namespace=semplificazione $target_cluster_flags --token=$SECRET_OCP_SERVICE_TOKEN -f -",
                                                returnStdout: true)
                                if (!buildconfigUpdateResult?.trim()) {
                                    currentBuild.result = 'ERROR'
                                    error('BuildConfig update finished with errors')
                                }
                                echo "Patch BuildConfig result: $buildconfigUpdateResult"
                            }
                        }
                    }
                }
                stage('s2i binary deploy') {
                    steps {
                        script {
                            withCredentials([string(credentialsId: 'jenkins-token-semplificazione', variable: 'SECRET_OCP_SERVICE_TOKEN')]) {
                                def binaryDeployResult = sh(script: "oc start-build eap71-nautilus  --from-dir=${WORKSPACE}/s2i-binary/ --namespace=semplificazione --token=$SECRET_OCP_SERVICE_TOKEN $target_cluster_flags --follow",
                                        returnStdout: true)
                                if (!binaryDeployResult?.trim()) {
                                    currentBuild.result = 'ERROR'
                                    error('Binary deploy finished with errors')
                                }
                                echo "s2i binary deploy result: $binaryDeployResult"
                            }
                        }
                    }
                }
            }
        }
        stage('deploy') {
            steps {
                script {
                    withCredentials([string(credentialsId: 'jenkins-token-semplificazione', variable: 'SECRET_OCP_SERVICE_TOKEN')]) {
                        def patchIamgeStream = sh(script: "oc set image dc/eap71-nautilus eap71-nautilus=sdpsvalmbf.rete.testposte:5000/eap71-nautilus:${BUILD_TAG} --namespace=semplificazione --token=$SECRET_OCP_SERVICE_TOKEN $target_cluster_flags",
                                returnStdout: true)
                        //If the output is true the image was the same, so we check if current image is really the desired version
                        if (!patchIamgeStream?.trim()) {

                            def currentImageStreamVersion = sh(script: "oc get dc  eap71-nautilus -o jsonpath='{.spec.template.spec.containers[0].image}' --namespace=semplificazione --token=$SECRET_OCP_SERVICE_TOKEN $target_cluster_flags",
                                    returnStdout: true)

                            //if current DeploymentConfig image tag version it's different form BUIL_TAG we end the pipeline with an error
                            if (!currentImageStreamVersion.equalsIgnoreCase("sdpsvalmbf.rete.testposte:5000/eap71-nautilus:${BUILD_TAG}")) {
                                echo "DeploymentConfig image tag version is: $currentImageStreamVersion but expected tag is ${BUILD_TAG}"
                                currentBuild.result = 'ERROR'
                                error('Rollout finished with errors: DeploymentConfig image tag version is wrong')
                            }
                        }
                        echo "Patch imageStream result: $patchIamgeStream"
                    }
                    withCredentials([string(credentialsId: 'jenkins-token-semplificazione', variable: 'SECRET_OCP_SERVICE_TOKEN')]) {
                        def rollout = sh(script: "oc rollout latest eap71-nautilus --namespace=semplificazione --token=$SECRET_OCP_SERVICE_TOKEN $target_cluster_flags",
                                returnStdout: true)
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
