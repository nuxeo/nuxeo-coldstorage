/*
* (C) Copyright 2020 Nuxeo (http://nuxeo.com/) and others.
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
*     http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*
* Contributors:
*     Abdoul BA <aba@nuxeo.com>
*/

/* Using a version specifier, such as branch, tag, etc */
library "nuxeo-napps-tools@0.0.7"

def appName = 'nuxeo-coldstorage'
def containerLabel = 'maven'
def stageOpt

pipeline {
  agent {
    label 'builder-maven-nuxeo'
  }
  options {
    disableConcurrentBuilds()
    buildDiscarder(logRotator(daysToKeepStr: '15', numToKeepStr: '10', artifactNumToKeepStr: '5'))
  }
  parameters {
    string(name: 'rcVersion', description: 'Version to be promoted')
    string(name: 'reference', description: 'Reference branch to be bumped after releasing')
    booleanParam(
      name: 'dryRun', defaultValue: true,
      description: 'if true all steps will be run without publishing the artifact'
    )
  }
  environment {
    APP_NAME = "${appName}"
    AWS_CREDENTIAL_SECRET_NAME = 'aws-iam-user-credentials'
    AWS_BUCKET_SECRET_NAME = 'aws-config-napps'
    AWS_SECRET_NAMESPACE = 'napps'
    BACKEND_FOLDER = "${WORKSPACE}/${APP_NAME}"
    BRANCH_NAME = GIT_BRANCH.replace('origin/', '')
    BRANCH_LC = "${BRANCH_NAME.toLowerCase().replace('.', '-')}"
    BOOLEAN_TRUE = 'true'
    BOOLEAN_FALSE = 'false'
    BUCKET_PREFIX = "${APP_NAME}-${BRANCH_LC}-${BUILD_NUMBER}"
    CONFIG_FILE = 'workflow.yaml'
    CONNECT_PROD_URL = 'https://connect.nuxeo.com/nuxeo'
    CHART_DIR = 'ci/helm/preview'
    FRONTEND_FOLDER = "${WORKSPACE}/${APP_NAME}-web"
    JENKINS_HOME = '/root'
    MAVEN_DEBUG = '-e'
    MAVEN_OPTS = "${MAVEN_OPTS} -Xms512m -Xmx3072m"
    NUXEO_BASE_IMAGE = 'docker-private.packages.nuxeo.com/nuxeo/nuxeo:10.10-HF48'
    ORG = 'nuxeo'
    PREVIEW_NAMESPACE = "${APP_NAME}-${BRANCH_LC}-release"
    PREVIEW_URL = "https://preview-${PREVIEW_NAMESPACE}.napps.dev.nuxeo.com"
    UNIT_TEST_NAMESPACE_SUFFIX = "${APP_NAME}-${BRANCH_LC}".toLowerCase()
  }
  stages {
    stage('Check parameters') {
      steps {
        script {
          params.each { entry, value ->
            if (!value || value == '') {
              currentBuild.result = 'ABORTED'
              currentBuild.description = "Missing required ${entry} parameter, aborting the build."
              error(currentBuild.description)
            }
          }

          env.RC_VERSION = params.rcVersion
          env.REFERENCE_BRANCH = params.reference

          if (params.dryRun && params.dryRun != '') {
            env.DRY_RUN_RELEASE = params.dryRun
            if (env.DRY_RUN_RELEASE == BOOLEAN_TRUE) {
              env.SLACK_CHANNEL = 'infra-napps'
            }
          } else {
            env.SLACK_CHANNEL = 'napps-notifs'
            env.DRY_RUN_RELEASE = BOOLEAN_FALSE
          }
        }
      }
    }
    stage('Init') {
      steps {
        script {
          if (fileExists(CONFIG_FILE)) {
            stageOpt = readYaml file: CONFIG_FILE
          } else {
            stageOpt =
              readYaml text: nxNapps.getDefaultWorkflowConfig()
          }
        }
      }
    }
    stage('Set Labels') {
      steps {
        container(containerLabel) {
          echo '''
            ----------------------------------------
            Set Kubernetes resource labels
            ----------------------------------------
          '''
          echo "Set label 'branch: ${REFERENCE_BRANCH}' on pod ${NODE_NAME}"
          sh "kubectl label pods ${NODE_NAME} branch=${REFERENCE_BRANCH}"
          // output pod description
          echo "Describe pod ${NODE_NAME}"
          sh "kubectl describe pod ${NODE_NAME}"
        }
      }
    }
    stage('Setup') {
      steps {
        container(containerLabel) {
          script {
            nxNapps.setup()
            env.CURRENT_VERSION = nxNapps.currentVersion()
            env.VERSION = nxNapps.getReleaseVersion(CURRENT_VERSION)
            env.PACKAGE_BASE_NAME = "${APP_NAME}-package-${VERSION}"
            env.PACKAGE_FILENAME = "${APP_NAME}-package/target/${PACKAGE_BASE_NAME}.zip"
            echo """
              -----------------------------------------------------------
              Release ${APP_NAME}
              -----------------------------------------------------------
              ----------------------------------------
              Package name:     ${PACKAGE_BASE_NAME}
              Build version:    ${RC_VERSION}
              Current version:  ${CURRENT_VERSION}
              Release version:  ${VERSION}
              Reference branch: ${REFERENCE_BRANCH}
              ----------------------------------------
            """
          }
        }
      }
    }
    stage('Fetch Release Candidate') {
      steps {
        container(containerLabel) {
          sh "git fetch origin 'refs/tags/v${RC_VERSION}*:refs/tags/v${RC_VERSION}*'"
        }
      }
    }
    stage('Checkout') {
      steps {
        container(containerLabel) {
          script {
            nxNapps.gitCheckout("v${RC_VERSION}")
          }
        }
      }
    }
    stage('Notify promotion start on slack') {
      steps {
        script {
          String message = "Starting release ${VERSION} from build ${env.RC_VERSION}: ${BUILD_URL}"
          slackBuildStatus("${SLACK_CHANNEL}", "${message}", 'gray')
        }
      }
    }
    stage('Update Version') {
      steps {
        container(containerLabel) {
          script {
            nxNapps.updateVersion("${VERSION}")
          }
        }
      }
    }
    stage('Compile') {
      steps {
        container(containerLabel) {
          script {
            nxNapps.mavenCompile()
          }
        }
      }
    }
    stage('Linting') {
      when {
        expression { stageOpt.lint.enable }
      }
      steps {
        container('maven') {
          script {
            try {
              nxNapps.lint("${FRONTEND_FOLDER}")
            } catch (err) {
              //Allow lint to fail
              if (stageOpt.lint.allowFailure) {
                echo hudson.Functions.printThrowable(err)
              } else {
                throw err
              }
            }
          }
        }
      }
    }
    stage('Run Frontend UTests') {
      when {
        expression { stageOpt.runUtest.frontend }
      }
      steps {
        container(containerLabel) {
          script {
            def stages = [:]
            boolean allowFailure = REFERENCE_BRANCH == '10.10' //FIXME
            stages['frontend'] = nxNapps.runFrontendUnitTests(allowFailure)
            parallel stages
          }
        }
      }
    }
    stage('Run Runtime UTests') {
      when {
        expression { stageOpt.runUtest.backend }
      }
      steps {
        container(containerLabel) {
          script {
            def stages = [:]
            def envVars = nxNapps.getEnvironmentVariables("${APP_NAME}",
              "${AWS_BUCKET_SECRET_NAME}", "${AWS_CREDENTIAL_SECRET_NAME}",
              "${AWS_SECRET_NAMESPACE}",  "${BUCKET_PREFIX}-utests"
            )
            stages['backend'] = nxNapps.runBackendUnitTests(envVars)
            parallel stages
          }
        }
      }
    }
    stage('Deploy Env') {
      when {
        expression { stageOpt.runUtest.multipleEnv }
      }
      steps {
        script {
          def stages = [:]
          stageOpt.targetTestEnvs.each { env ->
            String containerName = stageOpt.mcontainers["${env}"]
            stages["Deploy - ${env}"] =
              nxKube.helmDeployUnitTestEnvStage(
                "${containerName}", "${env}", "${UNIT_TEST_NAMESPACE_SUFFIX}-${env}", 'ci/helm/utests'
              )
          }
          parallel stages
        }
      }
    }
    stage('Unit Tests') {
      when {
        expression { stageOpt.runUtest.multipleEnv }
      }
      steps {
        container(containerLabel) {
          script {
            def stages = [:]
            def parameters = [:]
            stageOpt.targetTestEnvs.each { env ->
              String containerName = stageOpt.mcontainers["${env}"]
              String namespace  = "${UNIT_TEST_NAMESPACE_SUFFIX}-${env}"
              parameters["${env}"] = nxNapps.getEnvironmentVariables("${namespace}")
              stages["JUnit - ${env}"] =
                nxNapps.runUnitTestStageLegacy(
                  "${containerName}", "${env}", "${WORKSPACE}",
                  "${namespace}", parameters["${env}"]
                )
            }
            parallel stages
          }
        }
      }
      post {
        always {
          script {
            try {
              targetTestEnvs.each { env ->
                container('maven') {
                  nxKube.helmDestroyUnitTestsEnv(
                    "${UNIT_TEST_NAMESPACE_SUFFIX}-${env}"
                  )
                }
              }
            } catch(err) {
              throw err
            }
          }
        }
      }
    }
    stage('Package') {
      steps {
        container(containerLabel) {
          script {
            nxNapps.mavenPackage()
          }
        }
      }
    }
    stage('Build Docker Image') {
      when {
        expression { stageOpt.preview.enable }
      }
      steps {
        container(containerLabel) {
          script {
            nxNapps.dockerBuild(
              "${WORKSPACE}/${APP_NAME}-package/target/${APP_NAME}-package-*.zip",
              "${WORKSPACE}/ci/docker", "${WORKSPACE}/ci/docker/skaffold.yaml"
            )
          }
        }
      }
    }
    stage('Buid Helm Chart') {
      when {
        expression { stageOpt.preview.enable }
      }
      steps {
        container(containerLabel) {
          script {
            nxKube.helmBuildChart("${CHART_DIR}", 'values.yaml')
            nxNapps.gitCheckout("${CHART_DIR}/requirements.yaml")
          }
        }
      }
    }
    stage('Deploy Preview') {
      when {
        expression { stageOpt.preview.enable }
      }
      steps {
        container(containerLabel) {
          script {
            nxKube.helmDeployPreview(
              "${PREVIEW_NAMESPACE}", "${CHART_DIR}", "${GIT_URL}", "${BOOLEAN_FALSE}"
            )
          }
        }
      }
    }
    stage('Run FTests') {
      when {
        expression { stageOpt.runFtest.enable }
      }
      steps {
        container(containerLabel) {
          script {
            try {
              retry(2) {
                nxNapps.runFunctionalTests(
                  "${FRONTEND_FOLDER}", "--nuxeoUrl=http://preview.${PREVIEW_NAMESPACE}.svc.cluster.local/nuxeo"
                )
              }
            } catch(err) {
              throw err
            } finally {
              //retrieve preview logs
              nxKube.helmGetPreviewLogs("${PREVIEW_NAMESPACE}")
              cucumber (
                fileIncludePattern: '**/*.json',
                jsonReportDirectory: "${FRONTEND_FOLDER}/target/cucumber-reports/",
                sortingMethod: 'NATURAL'
              )
              archiveArtifacts (
                allowEmptyArchive: true,
                //we can't use full path when archiving artifacts
                artifacts: "${FRONTEND_FOLDER}/**/target/**, logs/*.log"
              )
            }
          }
        }
      }
      post {
        always {
          container(containerLabel) {
            script {
              //cleanup the preview
              nxKube.helmDeleteNamespace("${PREVIEW_NAMESPACE}")
            }
          }
        }
      }
    }
    stage('Publish') {
      when {
        allOf {
          not {
            branch 'PR-*'
          }
          not {
            environment name: 'DRY_RUN', value: BOOLEAN_TRUE
          }
        }
      }
      environment {
        MESSAGE = "Release ${VERSION}"
        TAG = "v${VERSION}"
      }
      stages {
        stage('Git Commit and Tag') {
          steps {
            container(containerLabel) {
              script {
                nxNapps.gitCommit("${MESSAGE}", '-a')
                nxNapps.gitTag("${TAG}", "${MESSAGE}")
              }
            }
          }
        }
        stage('Publish Package') {
          when {
            expression { stageOpt.publish.connect }
          }
          steps {
            container(containerLabel) {
              script {
                echo """
                  -------------------------------------------------------------
                  Upload ${APP_NAME} Package ${VERSION} to ${CONNECT_PROD_URL}
                  -------------------------------------------------------------
                """
                if (DRY_RUN_RELEASE == BOOLEAN_FALSE) {
                  String packageFile = "${APP_NAME}-package/target/${APP_NAME}-package-${VERSION}.zip"
                  connectUploadPackage("${packageFile}", 'connect-prod', "${CONNECT_PREPROD_URL}")
                }
              }
            }
          }
          post {
            always {
              archiveArtifacts (
                allowEmptyArchive: true,
                artifacts: "${APP_NAME}-package/target/${APP_NAME}-package-*.zip"
              )
            }
          }
        }
        stage('Git Push') {
          when {
            expression { stageOpt.publish.github }
          }
          steps {
            container(containerLabel) {
              echo """
                --------------------------
                Git push ${TAG}
                --------------------------
              """
              script {
                if (DRY_RUN_RELEASE == BOOLEAN_FALSE) {
                  nxNapps.gitPush("${TAG}")
                }
              }
            }
          }
        }
      }
    }
    stage('Release and Bump reference branch') {
      when {
        allOf {
          expression { stageOpt.publish.github }
          not {
            environment name: 'DRY_RUN', value: BOOLEAN_TRUE
          }
        }
      }
      steps {
        container(containerLabel) {
          script {
            //cleanup files updated by other stages
            sh 'git reset --hard'
            // increment minor version
            String nextVersion =
              sh(returnStdout: true, script: "perl -pe 's/\\b(\\d+)(?=\\D*\$)/\$1+1/e' <<< ${CURRENT_VERSION}").trim()
            echo """
              -----------------------------------------------
              Update ${REFERENCE_BRANCH} version from ${CURRENT_VERSION} to ${nextVersion}
              -----------------------------------------------
            """
            nxNapps.gitCheckout("${REFERENCE_BRANCH}")
            nxNapps.updateVersion("${nextVersion}")
            nxNapps.gitCommit("Release ${VERSION}, update ${CURRENT_VERSION} to ${nextVersion}", '-a')
            if (DRY_RUN_RELEASE == BOOLEAN_FALSE) {
              nxNapps.gitPush("${REFERENCE_BRANCH}")
            }
          }
        }
      }
    }
  }
  post {
    always {
      script {
        currentBuild.description = "Release ${VERSION} from build ${RC_VERSION}"
      }
    }
    success {
      script {
        // update Slack Channel
        String message = "Successfully released ${VERSION} from build ${env.RC_VERSION}: ${BUILD_URL} :tada:"
        slackBuildStatus("${SLACK_CHANNEL}", "${message}", 'good')
      }
    }
    failure {
      script {
        // update Slack Channel
        String message = "Failed to release ${VERSION} from build ${env.RC_VERSION}: ${BUILD_URL}"
        slackBuildStatus("${SLACK_CHANNEL}", "${message}", 'danger')
      }
    }
  }
}
