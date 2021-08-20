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
@Library('nuxeo-napps-tools@0.0.8') _

def appName = 'nuxeo-coldstorage'
def containerLabel = 'maven'
def mcontainers = [
  'dev': 'maven',
  'mongodb': 'maven-mongodb',
  'pgsql': 'maven-pgsql',
]
def targetTestEnvs = ['dev', 'mongodb', 'pgsql',]

def runFrontEndUnitTests() {
  return {
    stage('frontend') {
      container('maven') {
        script {
          echo '''
            ----------------------------------------
            Run FrontEnd Unit tests
            ----------------------------------------
          '''
          try {
            String sauceAccessKey = ''
            String sauceUsername = ''
            if (nxNapps.needsSaucelabs()) {
              String saucelabesSecretName = 'saucelabs-coldstorage'
              sauceAccessKey =
                sh(script: "jx step credential -s ${saucelabesSecretName} -k key", returnStdout: true).trim()
              sauceUsername =
                sh(script: "jx step credential -s ${saucelabesSecretName} -k username", returnStdout: true).trim()
            }
            withEnv(["SAUCE_USERNAME=${sauceUsername}", "SAUCE_ACCESS_KEY=${sauceAccessKey}"]) {
              container('playwright') {
                retry(2) {
                  sh """
                    cd ${FRONTEND_FOLDER}
                    npm install playwright
                    npm install
                    npm run test
                  """
                }
              }
            }
          } catch (err) {
            throw err
          }
        }
      }
    }
  }
}

pipeline {
  agent {
    label 'builder-maven-nuxeo-11'
  }
  options {
    disableConcurrentBuilds()
    buildDiscarder(logRotator(daysToKeepStr: '15', numToKeepStr: '10', artifactNumToKeepStr: '5'))
  }
  environment {
    APP_NAME = "${appName}"
    AWS_CREDENTIAL_SECRET_NAME = 'aws-iam-user-credentials'
    AWS_BUCKET_SECRET_NAME = 'aws-config-napps'
    AWS_SECRET_NAMESPACE = 'napps'
    BACKEND_FOLDER = "${WORKSPACE}/nuxeo-coldstorage"
    BRANCH_LC = "${BRANCH_NAME.toLowerCase()}"
    BUCKET_PREFIX = "${appName}-${BRANCH_LC}-${BUILD_NUMBER}"
    CHANGE_BRANCH = "${env.CHANGE_BRANCH != null ? env.CHANGE_BRANCH : BRANCH_NAME}"
    CHANGE_TARGET = "${env.CHANGE_TARGET != null ? env.CHANGE_TARGET : BRANCH_NAME}"
    CHART_DIR = 'ci/helm/preview'
    CONNECT_PREPROD_URL = 'https://nos-preprod-connect.nuxeocloud.com/nuxeo'
    ENABLE_GITHUB_STATUS = 'true'
    FRONTEND_FOLDER = "${WORKSPACE}/nuxeo-coldstorage-web"
    JENKINS_HOME = '/root'
    MAVEN_DEBUG = '-e'
    MAVEN_OPTS = "${MAVEN_OPTS} -Xms512m -Xmx3072m"
    NUXEO_BASE_IMAGE = 'docker-private.packages.nuxeo.com/nuxeo/nuxeo:2021.6'
    ORG = 'nuxeo'
    PREVIEW_NAMESPACE = "coldstorage-${BRANCH_LC}"
    REFERENCE_BRANCH = 'lts-2021'
    IS_REFERENCE_BRANCH = "${BRANCH_NAME == REFERENCE_BRANCH}"
    SLACK_CHANNEL = "${env.DRY_RUN == 'true' ? 'infra-napps' : 'napps-notifs'}"
    UNIT_TEST_NAMESPACE_SUFFIX = "${APP_NAME}-${BRANCH_LC}".toLowerCase()
  }
  stages {
    stage('Set Labels') {
      steps {
        container('maven') {
          script {
            nxNapps.setLabels()
          }
        }
      }
    }
    stage('Setup') {
      steps {
        container('maven') {
          script {
            nxNapps.setup()
            env.VERSION = nxNapps.getRCVersion()
            env.PACKAGE_FILENAME = "nuxeo-coldstorage-package/target/nuxeo-coldstorage-package-${VERSION}.zip"
            sh 'env'
          }
        }
      }
    }
    stage('Update Version') {
      steps {
        container('maven') {
          script {
            nxNapps.updateVersion("${VERSION}")
          }
        }
      }
    }
    stage('Compile') {
      steps {
        container('maven') {
          script {
            gitHubBuildStatus('compile')
            nxNapps.mavenCompile()
          }
        }
      }
      post {
        always {
          gitHubBuildStatus('compile')
        }
      }
    }
    stage('Linting') {
      steps {
        container('maven') {
          script {
            gitHubBuildStatus('lint')
            nxNapps.lint("${FRONTEND_FOLDER}")
          }
        }
      }
      post {
        always {
          gitHubBuildStatus('lint')
        }
      }
    }
    stage('Run runtime unit tests') {
      steps {
        container(containerLabel) {
          script {
            def stages = [:]
            def envVars = nxNapps.getEnvironmentVariables(
                "${APP_NAME}", "${AWS_BUCKET_SECRET_NAME}", "${AWS_CREDENTIAL_SECRET_NAME}",
                "${AWS_SECRET_NAMESPACE}", "${UNIT_TEST_NAMESPACE_SUFFIX}-${env}"
              )
            stages['backend'] = nxNapps.runBackendUnitTests(envVars)
            stages['frontend'] = runFrontEndUnitTests()
            gitHubBuildStatus('utests/backend')
            gitHubBuildStatus('utests/frontend')
            parallel stages
          }
        }
      }
      post {
        always {
          gitHubBuildStatus('utests/backend')
          gitHubBuildStatus('utests/frontend')
        }
      }
    }
    stage('Deploy Env') {
      steps {
        script {
          def stages = [:]
          targetTestEnvs.each { env ->
            String containerName = mcontainers["${env}"]
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
      steps {
        container(containerLabel) {
          script {
            gitHubBuildStatus('utests')
            def stages = [:]
            def parameters = [:]
            targetTestEnvs.each { env ->
              String containerName = mcontainers["${env}"]
              String namespace  = "${UNIT_TEST_NAMESPACE_SUFFIX}-${env}"
              parameters["${env}"] = nxNapps.getEnvironmentVariables(
                "${APP_NAME}", "${AWS_BUCKET_SECRET_NAME}", "${AWS_CREDENTIAL_SECRET_NAME}",
                "${AWS_SECRET_NAMESPACE}",  "${namespace}"
              )
              stages["JUnit - ${env}"] = nxNapps.runUnitTestStage("${containerName}", "${env}", "${WORKSPACE}", "${namespace}", parameters["${env}"])
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
                container(containerLabel) {
                  nxKube.helmDestroyUnitTestsEnv(
                    "${UNIT_TEST_NAMESPACE_SUFFIX}-${env}"
                  )
                }
              }
            } finally {
              gitHubBuildStatus('utests')
            }
          }
        }
      }
    }
    stage('Package') {
      steps {
        container('maven') {
          script {
            gitHubBuildStatus('package')
            nxNapps.mavenPackage()
          }
        }
      }
      post {
        always {
          script {
            gitHubBuildStatus('package')
          }
        }
      }
    }
    stage('Build Docker Image') {
      steps {
        container('maven') {
          script {
            gitHubBuildStatus('docker/build')
            nxNapps.dockerBuild(
              "${PACKAGE_FILENAME}", "${WORKSPACE}/ci/docker", "${WORKSPACE}/ci/docker/skaffold.yaml"
            )
          }
        }
      }
      post {
        always {
          gitHubBuildStatus('docker/build')
        }
      }
    }
    stage('Buid Helm Chart') {
      steps {
        container('maven') {
          script {
            gitHubBuildStatus('helm/chart/build')
            nxKube.helmBuildChart("${CHART_DIR}", 'values.yaml')
            nxNapps.gitCheckout("${CHART_DIR}/requirements.yaml")
          }
        }
      }
      post {
        always {
          gitHubBuildStatus('helm/chart/build')
        }
      }
    }
    stage('Deploy Preview') {
      steps {
        container('maven') {
          script {
            nxKube.helmDeployPreview(
              "${PREVIEW_NAMESPACE}", "${CHART_DIR}", "${GIT_URL}", "${IS_REFERENCE_BRANCH}"
            )
          }
        }
      }
    }
    stage('Run Functional Tests') {
      steps {
        container('maven') {
          script {
            gitHubBuildStatus('ftests')
            try {
              retry(3) {
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
                jsonReportDirectory: "${FRONTEND_FOLDER}/ftest/target/cucumber-reports/",
                sortingMethod: 'NATURAL'
              )
              archiveArtifacts (
                allowEmptyArchive: true,
                artifacts: 'nuxeo-coldstorage-web/target/**, logs/*.log' //we can't use full path when archiving artifacts
              )
            }
          }
        }
      }
      post {
        always {
          container('maven') {
            script {
              //cleanup the preview
              try {
                if (nxNapps.needsPreviewCleanup() == 'true') {
                  nxKube.helmDeleteNamespace("${PREVIEW_NAMESPACE}")
                }
              } finally {
                gitHubBuildStatus('ftests')
              }
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
            environment name: 'DRY_RUN', value: 'true'
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
            container('maven') {
              script {
                nxNapps.gitCommit("${MESSAGE}", '-a')
                nxNapps.gitTag("${TAG}", "${MESSAGE}")
              }
            }
          }
        }
        stage('Package') {
          steps {
            container('maven') {
              script {
                gitHubBuildStatus('publish/package')
                echo """
                  -------------------------------------------------------------
                  Upload Coldstorage Package ${VERSION} to ${CONNECT_PREPROD_URL}
                  -------------------------------------------------------------
                """
                connectUploadPackage("${PACKAGE_FILENAME}", 'connect-preprod', "${CONNECT_PREPROD_URL}")
              }
            }
          }
          post {
            always {
              archiveArtifacts (
                allowEmptyArchive: true,
                artifacts: 'nuxeo-coldstorage-package/target/nuxeo-coldstorage-package-*.zip'
              )
              gitHubBuildStatus('publish/package')
            }
          }
        }
        stage('Git Push') {
          steps {
            container('maven') {
              echo """
                --------------------------
                Git push ${TAG}
                --------------------------
              """
              script {
                nxNapps.gitPush("${TAG}")
              }
            }
          }
        }
      }
    }
  }
  post {
    always {
      script {
        if (!nxNapps.isPullRequest() && env.DRY_RUN != 'true') {
          // update JIRA issue
          step([$class: 'JiraIssueUpdater', issueSelector: [$class: 'DefaultIssueSelector'], scm: scm])
          currentBuild.description = "Build ${VERSION}"
        }
      }
    }
    success {
      script {
        // update Slack Channel
        String message = "${JOB_NAME} - #${BUILD_NUMBER} ${currentBuild.currentResult} (<${BUILD_URL}|Open>)"
        slackBuildStatus("${SLACK_CHANNEL}", "${message}", 'good')
      }
    }
    unsuccessful {
      script {
        // update Slack Channel
        String message = "${JOB_NAME} - #${BUILD_NUMBER} ${currentBuild.currentResult} (<${BUILD_URL}|Open>)"
        slackBuildStatus("${SLACK_CHANNEL}", "${message}", 'danger')
      }
    }
  }
}
