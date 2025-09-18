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
import java.time.LocalDate
import java.time.format.DateTimeFormatter

library identifier: "platform-ci-shared-library@v0.0.75"

pipeline {
  agent {
    label 'jenkins-nuxeo-package-lts-2021-nodejs18'
  }
  options {
    buildDiscarder(logRotator(daysToKeepStr: '60', numToKeepStr: '60', artifactNumToKeepStr: '5'))
    disableConcurrentBuilds()
    githubProjectProperty(projectUrlStr: 'https://github.com/nuxeo/nuxeo-coldstorage')
  }
  environment {
    BRANCH_NAME = "${params.BRANCH}"
    BUILD_VERSION = "${params.BUILD_VERSION}"
    JIRA_PROJECT = 'NXP'
    JIRA_MOVING_VERSION = 'coldstorage-2021.x'
    JIRA_RELEASED_VERSION = "coldstorage-${VERSION}"
    VERSION = "${nxUtils.getMajorDotMinorVersion(version: env.BUILD_VERSION)}"
  }
  stages {
    stage('Set labels') {
      steps {
        container('maven') {
          script {
            nxK8s.setPodLabels(branch: env.BRANCH_NAME)
          }
        }
      }
    }
    stage('Release') {
      steps {
        container('maven') {
          script {
            sh "git checkout v${BUILD_VERSION}"
            nxGit.tagPush()
          }
        }
      }
    }
    stage('Upload Nuxeo Packages') {
      steps {
        container('maven') {
          script {
            echo """
            ----------------------------------------
            Upload Nuxeo Package to ${CONNECT_PROD_SITE_URL}
            ----------------------------------------"""
            // fetch Nuxeo Package with Maven
            nxMvn.download(artifact: "org.nuxeo.coldstorage:nuxeo-coldstorage-package:${BUILD_VERSION}:zip")
            nxUtils.postForm(credentialsId: 'connect-prod', url: "${CONNECT_PROD_SITE_URL}marketplace/upload?batch=true",
                form: ["package=@target/nuxeo-coldstorage-package-${BUILD_VERSION}.zip"])
          }
        }
      }
    }
    stage('Bump branch') {
      steps {
        container('maven') {
          script {
            sh 'git checkout ${BRANCH_NAME}'
            def currentVersion = readMavenPom().getVersion()
            // increment minor version
            def nextVersion = nxUtils.getNextMajorDotMinorVersion() + '-SNAPSHOT'
            echo """
            -----------------------------------------------
            Update ${BRANCH_NAME} version from ${currentVersion} to ${nextVersion}
            -----------------------------------------------
            """
            nxMvn.updateVersion(version: nextVersion)
            nxGit.commitPush(message: "Release ${VERSION}, update ${currentVersion} to ${nextVersion}")
          }
        }
      }
    }
    stage('Release Jira version') {
      steps {
        container('maven') {
          script {
            nxProject.release(
              jql                  : "project = ${JIRA_PROJECT} and fixVersion = ${JIRA_MOVING_VERSION}",
              newJiraVersion       : [
                project    : env.JIRA_PROJECT,
                name       : env.JIRA_RELEASED_VERSION,
                description: "Cold Storage Addon ${VERSION}",
                releaseDate: LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE),
                released   : true,
              ],
              jiraMovingVersionName: env.JIRA_MOVING_VERSION,
            )
          }
        }
      }
    }
  }

  post {
    always {
      script {
        nxUtils.setReleaseDescription()
        nxUtils.notifyReleaseStatusIfNecessary()
      }
    }
  }
}
