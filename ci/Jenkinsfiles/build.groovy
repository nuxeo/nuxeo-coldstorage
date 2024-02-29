/*
* (C) Copyright 2023 Nuxeo (http://nuxeo.com/) and others.
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
*     Kevin Leturc <kevin.leturc@hyland.com>
*/
library identifier: "platform-ci-shared-library@v0.0.25"

Closure buildUnitTestStage(env) {
  return {
    container('maven') {
      nxWithGitHubStatus(context: "utests/backend/${env}") {
        script {
          def testNamespace = "${CURRENT_NAMESPACE}-coldstorage-${BRANCH_NAME}-${BUILD_NUMBER}-${env}".replaceAll('\\.', '-').toLowerCase()
          nxWithHelmfileDeployment(namespace: testNamespace, environment: "${env}UnitTests") {
            try {
              def bucketPrefix = "utests-$BRANCH_NAME-$BUILD_NUMBER-${env}/".toLowerCase()
              sh """
                cat ci/mvn/nuxeo-test-${env}.properties \
                  ci/mvn/nuxeo-test-s3.properties \
                | AWS_BUCKET_PREFIX=${bucketPrefix} envsubst > /root/nuxeo-test-${env}.properties
              """
              retry(3) {
                sh """
                  mvn -B -nsu -pl :nuxeo-coldstorage \
                    -Dcustom.environment=${env} \
                    -Dcustom.environment.log.dir=target-${env} \
                    -Dnuxeo.test.core=${env == 'mongodb' ? 'mongodb' : 'vcs'} \
                    test
                """
              }
            } finally {
              archiveArtifacts artifacts: "**/target-${env}/**/*.log"
              junit allowEmptyResults: true, testResults: "**/target-${env}/surefire-reports/*.xml"
            }
          }
        }
      }
    }
  }
}

pipeline {
  agent {
    label 'jenkins-nuxeo-package-lts-2021-node-18'
  }
  options {
    buildDiscarder(logRotator(daysToKeepStr: '60', numToKeepStr: '60', artifactNumToKeepStr: '5'))
    disableConcurrentBuilds(abortPrevious: true)
    githubProjectProperty(projectUrlStr: 'https://github.com/nuxeo/nuxeo-coldstorage')
  }
  environment {
    AWS_ACCESS_KEY_ID = nxK8s.getSecretData(namespace: 'platform', name: 'aws-credentials', key: 'access_key_id')
    AWS_SECRET_ACCESS_KEY = nxK8s.getSecretData(namespace: 'platform', name: 'aws-credentials', key: 'secret_access_key')
    AWS_REGION = 'us-east-1'
    AWS_BUCKET = "nuxeo-platform-coldstorage"
    CURRENT_NAMESPACE = nxK8s.getCurrentNamespace()
    MAVEN_OPTS = "$MAVEN_OPTS -Xms512m -Xmx3072m"
    VERSION = nxUtils.getVersion()
    NUXEO_COLDSTORAGE_PACKAGE_PATH = "nuxeo-coldstorage-package/target/nuxeo-coldstorage-package-${VERSION}.zip"
  }
  stages {
    stage('Set labels') {
      steps {
        container('maven') {
          script {
            nxK8s.setPodLabels()
          }
        }
      }
    }
    stage('Update version') {
      steps {
        container('maven') {
          script {
            nxMvn.updateVersion()
          }
        }
      }
    }
    stage('Compile') {
      steps {
        container('maven') {
          nxWithGitHubStatus(context: 'compile') {
            echo """
            ----------------------------------------
            Compile
            ----------------------------------------"""
            echo "MAVEN_OPTS=$MAVEN_OPTS"
            sh 'mvn -B -nsu -T4C install -DskipTests'
          }
        }
      }
      post {
        always {
          archiveArtifacts artifacts: '**/target/*.jar, **/target/nuxeo-*-package-*.zip'
          junit testResults: '**/target/surefire-reports/*.xml, **/target/failsafe-reports/*.xml', allowEmptyResults: true
        }
      }
    }
    stage('Run unit tests') {
      steps {
        script {
          def stages = [:]
          stages['Backend - dev'] = {
            container('maven') {
              nxWithGitHubStatus(context: 'utests/backend/dev') {
                try {
                  // empty file required by the read-project-properties goal of the properties-maven-plugin with the
                  // customEnvironment profile
                  sh 'touch /root/nuxeo-test-dev.properties'
                  retry(3) {
                    sh 'mvn -B -nsu -pl :nuxeo-coldstorage -Dcustom.environment=dev -Dcustom.environment.log.dir=target-dev test'
                  }
                } finally {
                  archiveArtifacts artifacts: '**/target-dev/**/*.log'
                  junit allowEmptyResults: true, testResults: "**/target-dev/surefire-reports/*.xml"
                }
              }
            }
          }
          parallel stages
        }
      }
    }
    stage('Run functional tests') {
      environment {
        AWS_BUCKET_PREFIX = "ftests-$BRANCH_NAME-$BUILD_NUMBER/".toLowerCase()
      }
      steps {
        container('maven') {
          nxWithGitHubStatus(context: 'docker/build') {
            script {
              sh "mkdir -p ci/docker/target && cp ${NUXEO_COLDSTORAGE_PACKAGE_PATH} ci/docker/target"
              def nuxeoVersion = sh(returnStdout: true,
                script: 'mvn org.apache.maven.plugins:maven-help-plugin:3.3.0:evaluate -Dexpression=nuxeo.platform.version -q -DforceStdout')
              nxDocker.build(skaffoldFile: 'ci/docker/skaffold.yaml', envVars: ["NUXEO_VERSION=${nuxeoVersion}"])
            }
          }
          nxWithGitHubStatus(context: 'ftests') {
            script {
              def testNamespace = "${CURRENT_NAMESPACE}-coldstorage-${BRANCH_NAME}-${BUILD_NUMBER}-ftests".replaceAll('\\.', '-').toLowerCase()
              def nuxeoParentVersion = readMavenPom().getParent().getVersion()
              // target connect preprod if nuxeo-parent is a snapshot version or a build version
              def clidSecret = nuxeoParentVersion.matches("^\\d+\\.\\d+(-SNAPSHOT|\\.\\d+)\$") ? 'instance-clid-preprod' : 'instance-clid'
              nxWithHelmfileDeployment(namespace: testNamespace, environment: "functionalTests", envVars: ["CONNECT_CLID_SECRET=${clidSecret}"],
                secrets: [[name: clidSecret, namespace: 'platform']]) {
                dir('nuxeo-coldstorage-web') {
                  retry(3) {
                    sh "npm run ftest -- --nuxeoUrl=http://nuxeo.${NAMESPACE}.svc.cluster.local/nuxeo"
                  }
                }
              }
            }
          }
        }
      }
      post {
        always {
          archiveArtifacts artifacts: 'nuxeo-coldstorage-web/ftest/target/screenshots/**', allowEmptyArchive: true
          cucumber(fileIncludePattern: '**/*.json', jsonReportDirectory: 'nuxeo-coldstorage-web/ftest/target/cucumber-reports/',
            sortingMethod: 'NATURAL')
        }
      }
    }
    stage('Git commit, tag and push') {
      when {
        expression { !nxUtils.isPullRequest() }
      }
      steps {
        container('maven') {
          script {
            echo """
            ----------------------------------------
            Git commit, tag and push
            ----------------------------------------
            """
            nxGit.commitTagPush()
          }
        }
      }
    }
    stage('Deploy Maven artifacts') {
      when {
        expression { !nxUtils.isPullRequest() }
      }
      steps {
        container('maven') {
          nxWithGitHubStatus(context: 'maven/deploy', message: 'Deploy Maven artifacts') {
            script {
              echo """
              ----------------------------------------
              Deploy Maven artifacts
              ----------------------------------------"""
              nxMvn.deploy()
            }
          }
        }
      }
    }
    stage('Deploy Nuxeo package') {
      when {
        expression { !nxUtils.isPullRequest() }
      }
      steps {
        container('maven') {
          nxWithGitHubStatus(context: 'package/deploy', message: 'Deploy Nuxeo packages') {
            script {
              echo """
              ----------------------------------------
              Upload Nuxeo Package to ${CONNECT_PREPROD_SITE_URL}
              ----------------------------------------"""
              nxUtils.postForm(credentialsId: 'connect-preprod', url: "${CONNECT_PREPROD_SITE_URL}marketplace/upload?batch=true",
                  form: ["package=@${NUXEO_COLDSTORAGE_PACKAGE_PATH}"])
            }
          }
        }
      }
    }
  }
  post {
    always {
      script {
        currentBuild.description = "Build ${VERSION}"
        nxJira.updateIssues()
      }
    }
  }
}
