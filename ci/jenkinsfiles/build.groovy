/*
* (C) Copyright 2019-2021 Nuxeo (http://nuxeo.com/) and others.
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
library identifier: 'nuxeo-napps-tools@0.0.14', retriever: modernSCM(
        [$class       : 'GitSCMSource',
         credentialsId: 'jx-pipeline-git-github',
         remote       : 'https://github.com/nuxeo/nuxeo-napps-tools.git'])

def appName = 'nuxeo-coldstorage'
def configFile = 'ci/workflow.yaml'
def defaultContainer = 'maven'
def nxVersion = '10.10-HF58'
def referenceBranch = '10.10'
def podLabel = 'builder-maven-nuxeo'

buildMaven(appName, podLabel, defaultContainer, nxVersion, referenceBranch, configFile)
