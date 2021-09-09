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
library identifier: 'nuxeo-napps-tools@0.0.10', retriever: modernSCM(
        [$class       : 'GitSCMSource',
         credentialsId: 'jx-pipeline-git-github',
         remote       : 'https://github.com/nuxeo/nuxeo-napps-tools.git'])

def appName = 'nuxeo-coldstorage'
def containerLabel = 'maven'

pipeline {
  agent {
    label 'builder-maven-nuxeo'
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
    BRANCH_LC = "${BRANCH_NAME.toLowerCase().replace('.', '-')}"
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
    // To reduce the startup time, we are using a specific docker tag but Nuxeo will install all available HFs)
    NUXEO_BASE_IMAGE = 'docker-private.packages.nuxeo.com/nuxeo/nuxeo:10.10-HF52'
    ORG = 'nuxeo'
    PREVIEW_NAMESPACE = "coldstorage-${BRANCH_LC}"
    REFERENCE_BRANCH = '10.10'
    IS_REFERENCE_BRANCH = "${BRANCH_NAME == REFERENCE_BRANCH}"
    SLACK_CHANNEL = "${env.DRY_RUN == 'true' ? 'infra-napps' : 'napps-notifs'}"
  }
  stages {
    stage('Set Labels') {
      steps {
        container(containerLabel) {
          script {
            nxNapps.setLabels()
          }
        }
      }
    }
    stage('Setup') {
      steps {
        container(containerLabel) {
          script {
            nxNapps.setup()
            env.VERSION = nxNapps.getRCVersion()
          }
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
    stage('Install') {
      steps {
        container(containerLabel) {
          gitHubBuildStatus('npm/install')
          script {
            dir(FRONTEND_FOLDER) {
              sh 'npm install'
            }
          }
        }
      }
      post {
        always {
          gitHubBuildStatus('npm/install')
        }
      }
    }
    stage('Linting') {
      steps {
        container(containerLabel) {
          gitHubBuildStatus('npm/lint')
          script {
            boolean allowFailure = REFERENCE_BRANCH == '10.10' //FIXME
            try {
              dir(FRONTEND_FOLDER) {
                sh 'npm run lint'
              }
            } catch (err) {
              //Allow lint to fail
              if (allowFailure) {
                echo hudson.Functions.printThrowable(err)
              } else {
                throw err
              }
            }
          }
        }
      }
      post {
        always {
          gitHubBuildStatus('npm/lint')
        }
      }
    }
    stage('Run Unit Tests') {
      steps {
        container(containerLabel) {
          script {
            def stages = [:]
            boolean allowFailure = REFERENCE_BRANCH == '10.10' //FIXME
            def envVars = nxNapps.getEnvironmentVariables(
              "${APP_NAME}", "${AWS_BUCKET_SECRET_NAME}", "${AWS_CREDENTIAL_SECRET_NAME}",
              "${AWS_SECRET_NAMESPACE}",  "${BUCKET_PREFIX}-utests"
            )
            stages['backend'] = nxNapps.runBackendUnitTests(envVars)
            stages['frontend'] = nxNapps.runFrontendUnitTests(allowFailure)
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
    stage('Package') {
      steps {
        container(containerLabel) {
          gitHubBuildStatus('package')
          script {
            nxNapps.mavenPackage()
          }
        }
      }
      post {
        always {
          gitHubBuildStatus('package')
        }
      }
    }
    stage('Build Docker Image') {
      steps {
        container(containerLabel) {
          gitHubBuildStatus('docker/build')
          script {
            nxNapps.dockerBuild(
                    "${WORKSPACE}/nuxeo-coldstorage-package/target/nuxeo-coldstorage-package-*.zip",
                    "${WORKSPACE}/ci/docker","${WORKSPACE}/ci/docker/skaffold.yaml"
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
        container(containerLabel) {
          gitHubBuildStatus('helm/chart/build')
          script {
            env.JSF_ENABLED = 'nuxeo-jsf-ui'
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
        container(containerLabel) {
          gitHubBuildStatus('helm/chart/deploy')
          script {
            nxKube.helmDeployPreview(
                    "${PREVIEW_NAMESPACE}", "${CHART_DIR}", "${GIT_URL}", "${IS_REFERENCE_BRANCH}"
            )
          }
        }
      }
      post {
        always {
          gitHubBuildStatus('helm/chart/deploy')
        }
      }
    }
    stage('Run Functional Tests') {
      steps {
        container(containerLabel) {
          gitHubBuildStatus('ftests')
          script {
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
                jsonReportDirectory: "${FRONTEND_FOLDER}/target/cucumber-reports/",
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
          container(containerLabel) {
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
            container(containerLabel) {
              script {
                nxNapps.gitCommit("${MESSAGE}", '-a')
                nxNapps.gitTag("${TAG}", "${MESSAGE}")
              }
            }
          }
        }
        stage('Publish Package') {
          steps {
            container(containerLabel) {
              gitHubBuildStatus('publish/package')
              script {
                echo """
                  -------------------------------------------------------------
                  Upload Coldstorage Package ${VERSION} to ${CONNECT_PREPROD_URL}
                  -------------------------------------------------------------
                """
                String packageFile = "nuxeo-coldstorage-package/target/nuxeo-coldstorage-package-${VERSION}.zip"
                connectUploadPackage("${packageFile}", 'connect-preprod', "${CONNECT_PREPROD_URL}")
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
            container(containerLabel) {
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
