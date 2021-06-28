/*
* (C) Copyright 2021 Nuxeo (http://nuxeo.com/) and others.
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
library identifier: 'nuxeo-napps-tools@0.0.6'

def appName = 'nuxeo-coldstorage'

def getAwsEnvironnmentVariables(String bucketSecretName, String credentialSecret, String namespace, String bucketPrefix) {
  def envVars = [
    "AWS_ACCESS_KEY=${getEnvironmentVariable('access_key_id', credentialSecret, namespace)}",
    "AWS_SECRET_ACCESS_KEY=${getEnvironmentVariable('secret_access_key', credentialSecret, namespace)}",
    "COLDSTORAGE_AWS_MAIN_BUCKET_NAME=${getEnvironmentVariable('coldstorage.bucket', bucketSecretName, namespace)}",
    "COLDSTORAGE_AWS_GLACIER_BUCKET_NAME=${getEnvironmentVariable('coldstorage.bucket.glacier', bucketSecretName, namespace)}",
    "COLDSTORAGE_AWS_REGION=${getEnvironmentVariable('region', bucketSecretName, namespace)}",
    "COLDSTORAGE_AWS_BUCKET_PREFIX=${bucketPrefix}"
  ]
  return envVars
}

String getEnvironmentVariable(String secretKey, String secretName, String namespace) {
  return sh(script: "jx step credential -s ${secretName} -n ${namespace} -k ${secretKey}", returnStdout: true)
}

String currentVersion() {
  return readMavenPom().getVersion()
}

String getReleaseVersion(String version) {
  return version.replace('-SNAPSHOT', '')
}

def runBackEndUnitTests() {
  return {
    stage('backend') {
      container('maven') {
        script {
          try {
            echo '''
              ----------------------------------------
              Run BackEnd Unit tests
              ----------------------------------------
            '''
            def envVars = getAwsEnvironnmentVariables(
              "${AWS_BUCKET_SECRET_NAME}", "${AWS_CREDENTIAL_SECRET_NAME}",
              "${AWS_SECRET_NAMESPACE}",  "${BUCKET_PREFIX}-utests"
            )
            withEnv(envVars) {
              sh """
                mvn ${MAVEN_ARGS} -V -T0.8C test
              """
            }
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
    AWS_CREDENTIAL_SECRET_NAME = 'aws-iam-user-credentials'
    AWS_BUCKET_SECRET_NAME = 'aws-config-napps'
    AWS_SECRET_NAMESPACE = 'napps'
    BACKEND_FOLDER = "${WORKSPACE}/nuxeo-coldstorage"
    BRANCH_LC = "${BRANCH_NAME.toLowerCase()}"
    BUCKET_PREFIX = "${appName}-${BRANCH_LC}-${BUILD_NUMBER}"
    CHANGE_BRANCH = "${env.CHANGE_BRANCH != null ? env.CHANGE_BRANCH : BRANCH_NAME}"
    CHANGE_TARGET = "${env.CHANGE_TARGET != null ? env.CHANGE_TARGET : BRANCH_NAME}"
    CHART_DIR = 'ci/helm/preview'
    CONNECT_PROD_URL = 'https://connect.nuxeo.com/nuxeo'
    CURRENT_VERSION = currentVersion()
    FRONTEND_FOLDER = "${WORKSPACE}/nuxeo-coldstorage-web"
    JENKINS_HOME = '/root'
    MAVEN_DEBUG = '-e'
    MAVEN_OPTS = "${MAVEN_OPTS} -Xms512m -Xmx3072m"
    NUXEO_BASE_IMAGE = 'docker-private.packages.nuxeo.com/nuxeo/nuxeo:11.5.128'
    ORG = 'nuxeo'
    PREVIEW_NAMESPACE = "coldstorage-${BRANCH_LC}"
    REPOSITORY_URL = 'https://github.com/nuxeo/nuxeo-coldstorage/'
    VERSION = getReleaseVersion(CURRENT_VERSION)
  }
  stages {
    stage('Check parameters') {
      steps {
        script {
          if (!params.rcVersion || params.rcVersion == '') {
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

          env.RC_VERSION = params.rcVersion
          env.PACKAGE_BASE_NAME = "nuxeo-coldstorage-package-${VERSION}"
          env.PACKAGE_FILENAME = "nuxeo-coldstorage-package/target/${PACKAGE_BASE_NAME}.zip"
          echo """
            -----------------------------------------------------------
            Release nuxeo coldstorage connector
            -----------------------------------------------------------
            ----------------------------------------
            Coldstorage package:   ${PACKAGE_BASE_NAME}
            Build version:    ${RC_VERSION}
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
          String message = "Starting release ${VERSION} from build ${RC_VERSION}: ${BUILD_URL}"
          slackBuildStatus("${SLACK_CHANNEL}", "${message}", 'gray')
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
          sh "git fetch origin 'refs/tags/v${RC_VERSION}*:refs/tags/v${RC_VERSION}*'"
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
    stage('Build Docker Image') {
      steps {
        container('maven') {
          script {
            nxNapps.dockerBuild(
              "${WORKSPACE}/nuxeo-coldstorage-package/target/nuxeo-coldstorage-package-*.zip",
              "${WORKSPACE}/ci/docker","${WORKSPACE}/ci/docker/skaffold.yaml"
            )
          }
        }
      }
    }
    stage('Buid Helm Chart') {
      steps {
        container('maven') {
          script {
            nxKube.helmBuildChart("${CHART_DIR}", 'values.yaml')
          }
        }
      }
    }
    stage('Deploy Preview') {
      steps {
        container('maven') {
          script {
            nxKube.helmDeployPreview(
              "${PREVIEW_NAMESPACE}", "${CHART_DIR}", "${REPOSITORY_URL}", 'false'
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
                jsonReportDirectory: "${FRONTEND_FOLDER}/ftest/target/cucumber-reports/",
                sortingMethod: 'NATURAL'
              )
              archiveArtifacts (
                allowEmptyArchive: true,
                //we can't use full path when archiving artifacts
                artifacts: 'nuxeo-coldstorage-web/target/**, logs/*.log'
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
                nxKube.helmDeleteNamespace("${PREVIEW_NAMESPACE}")
              } catch (err) {
                echo hudson.Functions.printThrowable(err)
              }
            }
          }
        }
      }
    }
    stage('Publish Package') {
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
                  connectUploadPackage("${packageFile}", 'connect-prod', "${CONNECT_PROD_URL}")
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
        currentBuild.description = "Release ${VERSION} from build ${RC_VERSION}"
      }
    }
    success {
      script {
        // update Slack Channel
        String message = "Successfully released ${VERSION} from build ${RC_VERSION}: ${BUILD_URL} :tada:"
        slackBuildStatus("${SLACK_CHANNEL}", "${message}", 'good')
      }
    }
    unsuccessful {
      script {
        // update Slack Channel
        String message = "Failed to release ${VERSION} from build ${RC_VERSION}: ${BUILD_URL}"
        slackBuildStatus("${SLACK_CHANNEL}", "${message}", 'danger')
      }
    }
  }

}
