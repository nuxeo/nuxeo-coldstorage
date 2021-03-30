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
@Library('nuxeo-napps-tools@0.0.4') _

def appName = 'nuxeo-coldstorage'
def repositoryUrl = 'https://github.com/nuxeo/nuxeo-coldstorage/'

String currentVersion() {
  return readMavenPom().getVersion()
}

String getReleaseVersion(String version) {
  return version.replace('-SNAPSHOT', '')
}

def runBackEndUnitTests() {
  return {
    stage('backend') {
      container('maven-default') {
        script {
          try {
            echo '''
              ----------------------------------------
              Run BackEnd Unit tests
              ----------------------------------------
            '''
            sh """
              cd ${BACKEND_FOLDER}
              mvn ${MAVEN_ARGS} -V -T0.8C test
            """
          } catch (err) {
            throw err
          } finally {
            junit allowEmptyResults: true, testResults: "**/target/surefire-reports/*.xml"
          }
        }
      }
    }
  }
}

def runFrontEndUnitTests() {
  return {
    stage('frontend') {
      container('maven-mongodb') {
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
                sh """
                  cd ${FRONTEND_FOLDER}
                  npm install
                  npm run test
                """
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
  parameters {
    string(name: 'BUILD_VERSION', description: 'Version to be promoted')
    string(name: 'reference', description: 'Reference branch to be bumped after releasing')
    booleanParam(
      name: 'dryRun', defaultValue: true,
      description: 'if true all steps will be run without publishing the artifact'
    )
  }
  options {
    disableConcurrentBuilds()
    buildDiscarder(logRotator(daysToKeepStr: '15', numToKeepStr: '10', artifactNumToKeepStr: '5'))
  }
  environment {
    APP_NAME = "${appName}"
    BACKEND_FOLDER = "${WORKSPACE}/nuxeo-coldstorage"
    BRANCH_NAME = GIT_BRANCH.replace('origin/', '')
    BRANCH_LC = "${BRANCH_NAME.toLowerCase().replace('.', '-')}"
    CONNECT_PROD_URL = 'https://connect.nuxeo.com/nuxeo'
    CHART_DIR = 'ci/helm/preview'
    CURRENT_VERSION = currentVersion()
    ENABLE_GITHUB_STATUS = 'false'
    FRONTEND_FOLDER = "${WORKSPACE}/nuxeo-coldstorage-web"
    JENKINS_HOME = '/root'
    MAVEN_DEBUG = '-e'
    MAVEN_OPTS = "${MAVEN_OPTS} -Xms512m -Xmx3072m"
    ORG = 'nuxeo'
    VERSION = getReleaseVersion(CURRENT_VERSION)
  }
  stages {
    stage('Check parameters') {
      steps {
        script {
          if (!params.BUILD_VERSION || params.BUILD_VERSION == '') {
            currentBuild.result = 'ABORTED'
            currentBuild.description = 'Missing required version parameter, aborting the build.'
            error(currentBuild.description)
          }

          if (!params.reference || params.reference == '') {
            currentBuild.result = 'ABORTED'
            currentBuild.description = 'Missing required reference parameter, aborting the build.'
            error(currentBuild.description)
          } else {
            echo '''
              ----------------------------------------
              Update Reference Branch
              ----------------------------------------
            '''
            env.REFERENCE_BRANCH = params.reference
          }

          if (params.dryRun && params.dryRun != '') {
            env.DRY_RUN_RELEASE = params.dryRun
            if (env.DRY_RUN_RELEASE == 'true') {
              env.SLACK_CHANNEL = 'infra-napps'
            }
          } else {
            env.SLACK_CHANNEL = 'napps-notifs'
            env.DRY_RUN_RELEASE = 'false'
          }

          env.PACKAGE_BASE_NAME = "nuxeo-coldstorage-package-${VERSION}"
          env.PACKAGE_FILENAME = "nuxeo-coldstorage-package/target/${PACKAGE_BASE_NAME}.zip"
          echo """
            -----------------------------------------------------------
            Release nuxeo coldstorage connector
            -----------------------------------------------------------
            ----------------------------------------
            Coldstorage package:   ${PACKAGE_BASE_NAME}
            Build version:    ${params.BUILD_VERSION}
            Current version:  ${CURRENT_VERSION}
            Release version:  ${VERSION}
            Reference branch: ${REFERENCE_BRANCH}
            ----------------------------------------
          """
        }
      }
    }
    stage('Notify promotion start on slack') {
      steps {
        script {
          String message = "Starting release ${VERSION} from build ${params.BUILD_VERSION}: ${BUILD_URL}"
          slackBuildStatus.set("${SLACK_CHANNEL}", "${message}", 'gray')
        }
      }
    }
    stage('Set Labels') {
      steps {
        container('maven') {
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
        container('maven') {
          script {
            nxNapps.setup()
            sh 'env'
          }
        }
      }
    }
    stage('Fetch Release Candidate') {
      steps {
        container('maven') {
          sh "git fetch origin 'refs/tags/v${params.BUILD_VERSION}*:refs/tags/v${params.BUILD_VERSION}*'"
        }
      }
    }
    stage('Checkout') {
      steps {
        container('maven') {
          script {
            nxNapps.gitCheckout("v${RC_VERSION}")
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
            nxNapps.mavenCompile()
          }
        }
      }
    }
    stage('Linting') {
      steps {
        container('maven') {
          script {
            nxNapps.lint("${FRONTEND_FOLDER}")
          }
        }
      }
    }
    stage('Run Unit Tests') {
      steps {
        script {
          def stages = [:]
          stages['backend'] = runBackEndUnitTests()
          stages['frontend'] = runFrontEndUnitTests()
          parallel stages
        }
      }
    }
    stage('Deploy ColdStorage Preview') {
      steps {
        container('maven') {
          script {
            nxKube.helmDeployPreview(
              "${PREVIEW_NAMESPACE}", "${CHART_DIR}", "${repositoryUrl}", "${IS_REFERENCE_BRANCH}"
            )
          }
        }
      }
    }
    stage('Run Functional Tests') {
      steps {
        container('maven') {
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
          container('maven') {
            script {
              //cleanup the preview
              try {
                if (nxNapps.needsPreviewCleanup() == 'true') {
                  nxKube.helmDeleteNamespace("${PREVIEW_NAMESPACE}")
                }
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
        stage('Publish') {
          steps {
            container('maven') {
              script {
                echo """
                  ------------------------------------------------------------
                  Upload Coldstorage Package ${VERSION} to ${CONNECT_PROD_URL}
                  ------------------------------------------------------------
                """
                if (env.DRY_RUN_RELEASE == 'false') {
                  String packageFile = "nuxeo-coldstorage-package/target/nuxeo-coldstorage-package-${VERSION}.zip"
                  connectUploadPackage.set("${packageFile}", 'connect-preprod', "${CONNECT_PREPROD_URL}")
                }
              }
            }
          }
          post {
            always {
              archiveArtifacts (
                allowEmptyArchive: true,
                artifacts: 'nuxeo-coldstorage-package/target/nuxeo-coldstorage-package-*.zip'
              )
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
                if (env.DRY_RUN_RELEASE == 'false') {
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
          not {
            environment name: 'DRY_RUN', value: 'true'
          }
        }
      }
      steps {
        container('maven') {
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
            if (env.DRY_RUN_RELEASE == 'false') {
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
        currentBuild.description = "Release ${VERSION} from build ${params.BUILD_VERSION}"
      }
    }
    success {
      script {
        // update Slack Channel
        String message = "Successfully released ${VERSION} from build ${params.BUILD_VERSION}: ${BUILD_URL} :tada:"
        slackBuildStatus.set("${SLACK_CHANNEL}", "${message}", 'good')
      }
    }
    unsuccessful {
      script {
        // update Slack Channel
        String message = "Failed to release ${VERSION} from build ${params.BUILD_VERSION}: ${BUILD_URL}"
        slackBuildStatus.set("${SLACK_CHANNEL}", "${message}", 'danger')
      }
    }
  }
}
