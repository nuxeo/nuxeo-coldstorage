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

library identifier: "platform-ci-shared-library@v0.0.25"

pipeline {
  agent {
    label 'jenkins-nuxeo-package-10'
  }
  options {
    buildDiscarder(logRotator(daysToKeepStr: '60', numToKeepStr: '60', artifactNumToKeepStr: '5'))
    disableConcurrentBuilds()
    githubProjectProperty(projectUrlStr: 'https://github.com/nuxeo/nuxeo-coldstorage')
  }
  environment {
    BRANCH_NAME = "${params.BRANCH}"
    BUILD_VERSION = "${params.BUILD_VERSION}"
    JIRA_NUXEO_ADDON_MOVING_VERSION = 'coldstorage-10.x'
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
            def jiraVersionName = "coldstorage-${VERSION}"
            // create a new released version in Jira
            def jiraVersion = [
                project: 'NXP',
                name: jiraVersionName,
                description: "Cold Storage Addon ${VERSION}",
                releaseDate: LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE),
                released: true,
            ]
            nxJira.newVersion(version: jiraVersion)
            // find Jira tickets included in this release and update them
            def jiraTickets = nxJira.jqlSearch(jql: "project = NXP and fixVersion = ${JIRA_NUXEO_ADDON_MOVING_VERSION}")
            def previousVersion = nxUtils.getPreviousMajorDotMinorVersion()
            def changelog = nxGit.getChangeLog(previousVersion: previousVersion, version: env.VERSION)
            def committedIssues = jiraTickets.data.issues.findAll { changelog.contains(it.key) }
            committedIssues.each {
              nxJira.editIssueFixVersion(idOrKey: it.key, fixVersionToRemove: env.JIRA_NUXEO_ADDON_MOVING_VERSION, fixVersionToAdd: jiraVersionName)
            }
          }
        }
      }
    }
  }

  post {
    success {
      script {
        currentBuild.description = "Release ${VERSION}"
        nxSlack.success(message: "Successfully released nuxeo/nuxeo-coldstorage ${VERSION}: ${BUILD_URL}")
      }
    }
    unsuccessful {
      script {
        nxSlack.error(message: "Failed to release nuxeo/nuxeo-coldstorage ${VERSION}: ${BUILD_URL}")
      }
    }
  }
}
