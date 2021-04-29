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
 *     Nuno Cunha <ncunha@nuxeo.com>
 */
library identifier: "nuxeo-napps-tools@0.0.6"

appName = 'nuxeo-coldstorage'
repositoryUrl = 'https://github.com/nuxeo/nuxeo-coldstorage/'

pipeline {
  agent {
    label 'jenkins-python37'
  }
  options {
    disableConcurrentBuilds()
    buildDiscarder(logRotator(daysToKeepStr: '15', numToKeepStr: '10', artifactNumToKeepStr: '5'))
  }
  parameters {
    string(name: 'PROJECT', defaultValue: 'nuxeo-web-ui', description: 'The name of the Crowdin project to impact.')
    string(name: 'CROWDIN_TOOL_VERSION', defaultValue: 'master')
    string(
      name: 'CROWDIN_PARENT_FOLDER', defaultValue: 'addons/cold-storage',
      description: 'Folder inside Crowdin\'s project from which download or upload of message files take place. Leave empty for root.'
    )
    string(name: 'FORMAT', defaultValue: 'json', description: 'The format of the Crowdin project.')
    string(name: 'BRANCH', defaultValue: 'master', description: '')
    string(name: 'JIRA', defaultValue: '', description: 'JIRA issue to be used in automated commit message.')
    booleanParam(
      name: 'UPDATE_CROWDIN_FROM_NUXEO', defaultValue: true,
      description: 'If checked, the reference file messages.json will be sent to Crowdin.'
    )
    booleanParam(
      name: 'UPDATE_NUXEO_FROM_CROWDIN', defaultValue: true,
      description: 'If checked, external translations managed in Crowdin will be commited to the Nuxeo Cold Storage repo.'
    )
    booleanParam(
      name: 'DRYRUN', defaultValue: false,
      description: 'Changes will not be pushed to GitHub nor Crowdin.'
    )
  }
  environment {
    APP_NAME = "${appName}"
    BRANCH_NAME = GIT_BRANCH.replace('origin/', '')
    CROWDIN_TOOL_FOLDER = "${WORKSPACE}/tools-nuxeo-crowdin"
    NAMESPACE = 'napps'
    PROJECT_PATH = "${WORKSPACE}"
    INPUT_FILE = "${PROJECT_PATH}/nuxeo-coldstorage-web/i18n/messages.json"
    OUTPUT_FOLDER = "${PROJECT_PATH}/nuxeo-coldstorage-web/i18n"
    ORG = 'nuxeo'
  }
  stages {
    stage('Check parameters') {
      steps {
        script {
          ['PROJECT', 'BRANCH'].each {
            if (params.it == '') {
              currentBuild.result = 'ABORTED'
              currentBuild.description = "Missing required parameter $it, aborting build."
              error(currentBuild.description)
            }
          }
        }
      }
    }
    stage('Set Kubernetes labels') {
      steps {
        container('python') {
          echo '''
            ----------------------------------------
            Set Kubernetes labels
            ----------------------------------------
          '''
          echo "Set label 'branch: ${BRANCH_NAME}' on pod ${NODE_NAME}"
          sh """
            kubectl label pods ${NODE_NAME} branch=${BRANCH_NAME}
          """
        }
      }
    }
    stage('Setup') {
      steps {
        container('python') {
          echo '''
            ----------------------------------------
            Setup
            ----------------------------------------
          '''
          withCredentials([usernamePassword(credentialsId: 'jx-pipeline-git-github', usernameVariable: 'username', passwordVariable: 'password')]) {
            withEnv(["USERNAME=${username}", "PASSWORD=${password}"]) {
              sh '''
                # create the Git credentials
                jx step git credentials
                git config credential.helper store
                git clone -b $CROWDIN_TOOL_VERSION \
                  https://$USERNAME:$PASSWORD@github.com/nuxeo/tools-nuxeo-crowdin.git $CROWDIN_TOOL_FOLDER
                python -m pip install --user -r $CROWDIN_TOOL_FOLDER/requirements.txt
                chmod +x $CROWDIN_TOOL_FOLDER/jenkins/*.sh
              '''
            }
          }
        }
      }
    }
    stage('Translate') {
      steps {
        container('python') {
          script {
            def crowdinToken = sh(script: 'kubectl get secret jenkins-secrets -n ${NAMESPACE} -o=jsonpath=\'{.data.CROWDIN_NUXEO_TOKEN}\' | base64 --decode', returnStdout: true)
            def crowdinUser = sh(script: 'kubectl get secret jenkins-secrets -n ${NAMESPACE} -o=jsonpath=\'{.data.CROWDIN_NUXEO_USER}\' | base64 --decode', returnStdout: true)
            withEnv([
                "CROWDIN_API_KEY=${crowdinToken}",
                "CROWDIN_USER=${crowdinUser}"
              ]) {
              echo '''
                ----------------------------------------
                Run Translation
                ----------------------------------------
              '''
              sh """
                echo "Start synchronization"
                bash -xe ${WORKSPACE}/tools-nuxeo-crowdin/jenkins/sync_nuxeo_web_ui_crowdin.sh
              """
            }
          }
        }
        findText regexp: 'Spotted new languages', alsoCheckConsoleOutput: true , unstableIfFound: true
      }
    }
  }
  post {
    failure {
      script {
        // update Slack Channel
        String message = "${JOB_NAME} - #${BUILD_NUMBER} ${currentBuild.currentResult} (<${BUILD_URL}|Open>)"
        slackBuildStatus("${SLACK_CHANNEL}", "${message}", 'danger')
      }
    }
    unstable {
      // update Slack Channel
      String message = "${JOB_NAME} - #${BUILD_NUMBER} ${currentBuild.currentResult} (<${BUILD_URL}|Open>)"
      slackBuildStatus("${SLACK_CHANNEL}", "${message}", 'gray')
    }
  }
}
