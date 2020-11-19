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

void buildDockerImage () {
  echo """
    ----------------------------------------
    Build Docker image
    ----------------------------------------
    Image tag: ${VERSION}
  """
  sh """
    echo "Build and push Docker image to internal Docker registry ${DOCKER_REGISTRY}"
    cp -r ${WORKSPACE}/nuxeo-coldstorage-package/target/nuxeo-coldstorage-package-*.zip ${WORKSPACE}/ci/docker
  """
  skaffoldBuild("${WORKSPACE}/ci/docker/skaffold.yaml")
}

void buildHelmChart(String charDir) {
  echo """
    ----------------------------------------
    Building Helm Chart ${charDir}
    ----------------------------------------
  """
  // first substitute environment variables in chart values
  sh """
    cd ${charDir}
    mv values.yaml values.yaml.tosubst
    envsubst < values.yaml.tosubst > values.yaml
    #build and deploy the chart
    #To avoid jx gc cron job,
    #reference branch previews are deployed by calling jx step helm install instead of jx preview
    jx step helm build
    mkdir target && helm template . --output-dir target
  """
  // the requirements.yaml file has been changed by the step before
  this.cleanupChart(charDir)
}

void cleanupChart(String charDir) {
  sh """
    cd ${charDir}
    git checkout requirements.yaml
  """
}

void cleanupPreview(String namespace) {
  String deploymentName = this.getResourceName('deployment', "${namespace}")
  String statefulsetName = this.getResourceName('statefulset', "${namespace}")
  try {
    this.scaleResource("${namespace}", 'deployment', "${deploymentName}", '0')
    this.scaleResource("${namespace}", 'statefulset', "${statefulsetName}", '0')
  } catch (err) {
    echo hudson.Functions.printThrowable(err)
  } finally {
    sh "jx step helm delete preview --namespace=${namespace} --purge"
    // clean up the preview namespace
    sh "kubectl delete namespace ${namespace} --ignore-not-found=true"
  }
}

void compile() {
  echo '''
    ----------------------------------------
    Compile
    ----------------------------------------
  '''
  sh "mvn ${MAVEN_ARGS} -V -T0.8C -DskipTests clean install"
}

void deployPreview(String namespace, String charDir, String isCleanupPreview, String gitRepo, String isReferenceBranch) {
  //The notification will only be printed if the PR has "preview" tag on GitHub
  String previewOption = isCleanupPreview == 'true' ? '--no-comment=false' : ''
  String deploymentName = ''
  String statefulsetName = ''
  String timeout = '11m'
  dir(charDir) {
    echo """
      ----------------------------------------
      Deploy Preview environment in ${namespace}
      ----------------------------------------
    """
    boolean nsExists = this.namespaceExists(namespace)
    if (nsExists) {
      // Previous preview deployment needs to be scaled to 0 to be replaced correctly
      deploymentName = this.getResourceName('deployment', "${namespace}")
      statefulsetName = this.getResourceName('statefulset', "${namespace}")
      this.scaleResource("${namespace}", 'deployment', "${deploymentName}", '0')
      this.scaleResource("${namespace}", 'statefulset', "${statefulsetName}", '0')
    }
    String traceCmd = """
      mkdir -p ${WORKSPACE}/logs
      ( sleep 80 ; kubectl logs -f --selector branch=${BRANCH_NAME} -n ${namespace} 2>&1 | tee ${WORKSPACE}/logs/preview.log  ) &
    """
    String previewCmd = isReferenceBranch == 'true' ?
            // To avoid jx gc cron job,
            //reference branch previews are deployed by calling jx step helm install instead of jx preview
            "jx step helm install  --namespace ${namespace} --name ${namespace} --verbose ."
            // When deploying a pr preview, we use jx preview which gc the merged pull requests
            : "jx preview --namespace ${namespace}  --name ${namespace}  ${previewOption} --source-url=${gitRepo} --preview-health-timeout ${timeout}"

    try {
      sh """
        ${traceCmd}
        ${previewCmd}
      """
    } catch (err){
      if (isCleanupPreview == 'true'){
        this.cleanupPreview("${namespace}")
      }
      throw err
    }

    //statefulsetName deploymentName are empty if the first time the preview is enabled
    if (!nsExists) {
      deploymentName = this.getResourceName('deployment', "${namespace}")
      statefulsetName = this.getResourceName('statefulset', "${namespace}")
    }
    // check deployment status, exits if not OK
    rolloutStatus('statefulset', "${statefulsetName}", '1m', "${namespace}")
    rolloutStatus('deployment', "${deploymentName}", "${timeout}", "${namespace}")
    // We need to expose the nuxeo url by hand
    previewUrl =
            sh(returnStdout: true, script: "jx get urls -n ${namespace} | grep -oP https://.* | tr -d '\\n'")
    echo """
      ----------------------------------------
      Preview available at: ${previewUrl}
      ----------------------------------------
    """
  }
}

String getResourceName(String resource, String namespace) {
  return sh(
          script: "kubectl get ${resource} -n ${namespace} -o custom-columns=:metadata.name |tr '\\n' ' ' |  awk  -F' ' '{print \$1}' | sed '/^\$/d'",
          returnStdout: true
  ).trim()
}

void getPreviewLogs(String namespace) {
  try {
    String deployName = getResourceName('deployment', "${namespace}")
    String kubcetlCmd =
            "kubectl get pods -n ${namespace} --selector \"app=${deployName}\" -o custom-columns=:metadata.name |tr '\\n' ' ' | awk -F' ' '{print \$1}'"
    String podName = sh(returnStdout: true, script: kubcetlCmd)
    podName = podName.replaceAll('\\s', '')
    sh """
      kubectl -n ${namespace} cp ${podName}:/var/log/nuxeo ${WORKSPACE}/logs
      kubectl logs ${podName} -n ${namespace} > ${WORKSPACE}/logs/${podName}.log
    """
  } catch (err) {
    echo hudson.Functions.printThrowable(err)
    echo 'NUXEO::ColdStorage Cannot archive preview logs'
  }
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
  String args = '-fae -B -nsu'
  return args
}

boolean namespaceExists(String namespace) {
  return sh(returnStatus: true, script: "kubectl get namespace ${namespace}") == 0
}

boolean needsPreviewCleanup() {
  return this.isPullRequest() && !pullRequest.labels.contains('preview')
}

boolean needsSaucelabs() {
  return this.isPullRequest() && pullRequest.labels.contains('saucelabs')
}

void rolloutStatus(String kind, String name, String timeout, String namespace) {
  sh "kubectl rollout status ${kind} ${name} --timeout=${timeout} --namespace=${namespace}"
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

void scaleResource(String namespace, String resource, String podName, String replicas) {
  sh "kubectl -n ${namespace} scale ${resource} ${podName} --replicas=${replicas}"
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
  """
}

/**
 * Replaces environment variables present in the given yaml file and then runs skaffold build on it.
 * Needed environment variables are generally:
 * - DOCKER_REGISTRY
 * - VERSION
 */
void skaffoldBuild(String skaffoldFile) {
  sh """
    envsubst < ${skaffoldFile} > ${skaffoldFile}~gen
    skaffold build -f ${skaffoldFile}~gen
  """
}

return this;
