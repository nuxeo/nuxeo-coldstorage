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

def appName = 'nuxeo-coldstorage'
def pipelineLib
def repositoryUrl = 'https://github.com/nuxeo/nuxeo-coldstorage/'

properties([
  [
    $class: 'BuildDiscarderProperty',
    strategy: [
      $class: 'LogRotator',
      daysToKeepStr: '15', numToKeepStr: '10',
      artifactNumToKeepStr: '5'
    ]
  ],
  [
    $class: 'GithubProjectProperty', projectUrlStr: repositoryUrl
  ],
  disableConcurrentBuilds(),
])

void setGitHubBuildStatus(String context, String message, String state, String gitRepo) {
  if ( env.DRY_RUN != 'true' && ENABLE_GITHUB_STATUS == 'true') {
    step([
      $class: 'GitHubCommitStatusSetter',
      reposSource: [$class: 'ManuallyEnteredRepositorySource', url: gitRepo],
      contextSource: [$class: 'ManuallyEnteredCommitContextSource', context: context],
      statusResultSource: [
        $class: 'ConditionalStatusResultSource', results: [[$class: 'AnyBuildResult', message: message, state: state]]
      ],
    ])
  }
}

pipeline {
  agent {
    label 'builder-maven-nuxeo-11'
  }
  environment {
    APP_NAME = "${appName}"
    BACKEND_FOLDER = "${WORKSPACE}/nuxeo-coldstorage"
    CHANGE_BRANCH = "${env.CHANGE_BRANCH != null ? env.CHANGE_BRANCH : BRANCH_NAME}"
    CHANGE_TARGET = "${env.CHANGE_TARGET != null ? env.CHANGE_TARGET : BRANCH_NAME}"
    CONNECT_PREPROD_URL = 'https://nos-preprod-connect.nuxeocloud.com/nuxeo'
    ENABLE_GITHUB_STATUS = 'true'
    FRONTEND_FOLDER = "${WORKSPACE}/nuxeo-coldstorage-web"
    JENKINS_HOME = '/root'
    MAVEN_DEBUG = '-e'
    MAVEN_OPTS = "${MAVEN_OPTS} -Xms512m -Xmx3072m"
    ORG = 'nuxeo'
  }
  stages {
    stage('Load Common Library') {
      steps {
        container('maven') {
          script {
            pipelineLib = load 'ci/jenkinsfiles/common-lib.groovy'
            if (env.DRY_RUN == 'true') {
              env.SLACK_CHANNEL = 'infra-napps'
            } else {
              env.SLACK_CHANNEL = 'pr-napps'
            }
          }
        }
      }
    }
    stage('Set Labels') {
      steps {
        container('maven') {
          script {
            pipelineLib.setLabels()
          }
        }
      }
    }
    stage('Setup') {
      steps {
        container('maven') {
          script {
            pipelineLib.setup()
            env.VERSION = pipelineLib.getVersion()
            sh 'env'
          }
        }
      }
    }
    stage('Update Version') {
      steps {
        container('maven') {
          script {
            pipelineLib.updateVersion("${VERSION}")
          }
        }
      }
    }
    stage('Compile') {
      steps {
        setGitHubBuildStatus('coldstorage/compile', 'Compile', 'PENDING', "${repositoryUrl}")
        container('maven') {
          script {
            pipelineLib.compile()
          }
        }
      }
      post {
        success {
          setGitHubBuildStatus('coldstorage/compile', 'Compile', 'SUCCESS', "${repositoryUrl}")
        }
        unsuccessful {
          setGitHubBuildStatus('coldstorage/compile', 'Compile', 'FAILURE', "${repositoryUrl}")
        }
      }
    }
    stage('Linting') {
      steps {
        setGitHubBuildStatus('coldstorage/lint', 'Run Linting Validations', 'PENDING', "${repositoryUrl}")
        container('maven') {
          script {
            pipelineLib.lint()
          }
        }
      }
      post {
        success {
          setGitHubBuildStatus('coldstorage/lint', 'Run Linting Validations', 'SUCCESS', "${repositoryUrl}")
        }
        unsuccessful {
          setGitHubBuildStatus('coldstorage/lint', 'Run Linting Validations', 'FAILURE', "${repositoryUrl}")
        }
      }
    }
    stage('Run Unit Tests') {
      steps {
        script {
          def stages = [:]
          stages['backend'] = pipelineLib.runBackEndUnitTests()
          stages['frontend'] = pipelineLib.runFrontEndUnitTests()
          parallel stages
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
                pipelineLib.gitCommit("${MESSAGE}", '-a')
                pipelineLib.gitTag("${TAG}", "${MESSAGE}")
              }
            }
          }
        }
        stage('Publish ColdStorage Package') {
          steps {
            setGitHubBuildStatus('coldstorage/publish/package', 'Upload ColdStorage Package', 'PENDING', "${repositoryUrl}")
            container('maven') {
              script {
                echo """
                  -------------------------------------------------
                  Upload Aspera Package ${VERSION} to ${CONNECT_PREPROD_URL}
                  -------------------------------------------------
                """
                pipelineLib.uploadPackage("${VERSION}", 'connect-preprod', "${CONNECT_PREPROD_URL}")
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
            success {
              setGitHubBuildStatus('coldstorage/publish/package', 'Upload ColdStorage Package', 'SUCCESS', "${repositoryUrl}")
            }
            unsuccessful {
              setGitHubBuildStatus('coldstorage/publish/package', 'Upload ColdStorage Package', 'FAILURE', "${repositoryUrl}")
            }
          }
        }
      }
    }
  }
  post {
    always {
      script {
        if (!pipelineLib.isPullRequest() && env.DRY_RUN != 'true') {
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
        pipelineLib.setSlackBuildStatus("${SLACK_CHANNEL}", "${message}", 'good')
      }
    }
    failure {
      script {
        // update Slack Channel
        String message = "${JOB_NAME} - #${BUILD_NUMBER} ${currentBuild.currentResult} (<${BUILD_URL}|Open>)"
        pipelineLib.setSlackBuildStatus("${SLACK_CHANNEL}", "${message}", 'danger')
      }
    }
  }
}
