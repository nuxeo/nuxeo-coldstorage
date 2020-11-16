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

void compile() {
  echo '''
    ----------------------------------------
    Compile
    ----------------------------------------
  '''
  sh "mvn ${MAVEN_ARGS} -V -T0.8C -DskipTests clean install"
}

String getVersion() {
  return this.isPullRequest() ? this.getPullRequestVersion() : this.getReleaseVersion()
}

String getReleaseVersion() {
  String preid = 'rc'
  String nextPromotion = readMavenPom().getVersion().replace('-SNAPSHOT', '')
  String version = "${nextPromotion}-${preid}.0" // first version ever

  // find the latest tag if any
  sh "git fetch origin 'refs/tags/v${nextPromotion}*:refs/tags/v${nextPromotion}*'"
  String tag = sh(returnStdout: true, script: "git tag --sort=taggerdate --list 'v${nextPromotion}*' | tail -1 | tr -d '\n'")
  if (tag) {
    container('maven') {
      version = sh(returnStdout: true, script: "npx --ignore-existing semver -i prerelease --preid ${preid} ${tag} | tr -d '\n'")
    }
  }
  return version
}

String getPullRequestVersion() {
  return "${readMavenPom().getVersion()}-${BRANCH_NAME}"
}

void gitCheckout(String reference) {
  sh "git checkout ${reference}"
}

void gitCommit(String message, String option) {
  echo '''
    ----------------------------------------
    Git Commit
    ----------------------------------------
  '''
  sh """
    git commit ${option} -m "${message}"
  """
}

void gitPush(String reference) {
  sh "git push origin ${reference}"
}

void gitTag(String tagname, String message) {
  sh """
    git tag -a ${tagname} -m "${message}"
  """
}

def isPullRequest() {
  return "${BRANCH_NAME}" =~ /PR-.*/
}

void lint() {
  echo '''
    ----------------------------------------
    Run Linting Validations
    ----------------------------------------
  '''
  sh """
    cd ${FRONTEND_FOLDER}
    npm run lint
  """
}

String mavenArgs() {
  String args = '-DskipITs=true -fae -B -nsu'
  return args
}

boolean needsSaucelabs() {
  return this.isPullRequest() && pullRequest.labels.contains('saucelabs')
}

def runFrontEndUnitTests() {
  return {
    stage('Run Unit tests: FrontEnd') {
      container('maven') {
        String context = 'coldstorage/utests/frontend'
        String message = 'Unit tests - FrontEnd'
        echo '''
          ----------------------------------------
          Run FrontEnd Unit tests
          ----------------------------------------
        '''
        script {
          this.setGitHubBuildStatus("${context}", "${message}", 'PENDING', "${GIT_URL}")
          String sauceAccessKey = ''
          String sauceUsername = ''
          if (this.needsSaucelabs()) {
            String saucelabesSecretName = 'saucelabs-coldstorage'
            sauceAccessKey =
              sh(script: "jx step credential -s ${saucelabesSecretName} -k key", returnStdout: true).trim()
            sauceUsername =
              sh(script: "jx step credential -s ${saucelabesSecretName} -k username", returnStdout: true).trim()
          }
          try {
            withEnv(["SAUCE_USERNAME=${sauceUsername}", "SAUCE_ACCESS_KEY=${sauceAccessKey}"]) {
              container('playwright') {
                sh """
                  cd ${FRONTEND_FOLDER}
                  npm clean-install
                  npm run test
                """
              }
            }
            this.setGitHubBuildStatus("${context}", "${message}", 'SUCCESS', "${GIT_URL}")
          } catch (err) {
            this.setGitHubBuildStatus("${context}", "${message}", 'FAILURE', "${GIT_URL}")
            throw err
          }
        }
      }
    }
  }
}

def runBackEndUnitTests() {
  return {
    stage('Run Unit tests: BackEnd') {
      container('maven') {
        String context = 'coldstorage/utests/backend'
        String message = 'Unit tests - BackEnd'
        script {
          this.setGitHubBuildStatus("${context}", "${message}", 'PENDING', "${GIT_URL}")
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
            this.setGitHubBuildStatus("${context}", "${message}", 'SUCCESS', "${GIT_URL}")
          } catch (err) {
            this.setGitHubBuildStatus("${context}", "${message}", 'FAILURE', "${GIT_URL}")
            throw err
          }
        }
      }
    }
  }
}

void setup() {
  sh '''
    # create the Git credentials
    jx step git credentials
    git config credential.helper store
  '''
  env.MAVEN_ARGS = this.mavenArgs()
}

void setSlackBuildStatus(String channel, String message, String color) {
  if ( env.DRY_RUN != 'true' ) {
    slackSend(channel: channel, color: color, message: message)
  }
}

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

void setLabels() {
  echo '''
    ----------------------------------------
    Set Kubernetes resource labels
    ----------------------------------------
  '''
  echo "Set label 'branch: ${BRANCH_NAME}' on pod ${NODE_NAME}"
  sh "kubectl label pods ${NODE_NAME} branch=${BRANCH_NAME}"
  // output pod description
  echo "Describe pod ${NODE_NAME}"
  sh "kubectl describe pod ${NODE_NAME}"
}

void uploadPackage(String version, String credential,  String connectUrl) {
  withCredentials(
    [usernameColonPassword(credentialsId: "${credential}", variable: 'CONNECT_PASS')]
  ) {
    sh """
      PACKAGE="nuxeo-coldstorage-package/target/nuxeo-coldstorage-package-${version}.zip"
      curl -i -u "$CONNECT_PASS" -F package=@\$PACKAGE "$connectUrl"/site/marketplace/upload?batch=true
    """
  }
}

void updateVersion(String version) {
  echo """
    ----------------------------------------
    Update version
    ----------------------------------------
    New version: ${version}
  """
  sh """
    mvn ${MAVEN_ARGS} versions:set -DnewVersion=${version} -DgenerateBackupPoms=false
    cd ${FRONTEND_FOLDER}
    npm version ${version} --no-git-tag-version
  """
}

return this;
