/* Copyright 2019 EPAM Systems.

 Licensed under the Apache License, Version 2.0 (the "License");
 you may not use this file except in compliance with the License.
 You may obtain a copy of the License at
 http://www.apache.org/licenses/LICENSE-2.0

 Unless required by applicable law or agreed to in writing, software
 distributed under the License is distributed on an "AS IS" BASIS,
 WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.

 See the License for the specific language governing permissions and
 limitations under the License.*/

package com.epam.edp

import groovy.json.JsonSlurperClassic
import com.epam.edp.platform.Platform
import org.apache.maven.artifact.versioning.*


class Job {
    def type
    Script script
    Platform platform
    def LATEST_TAG = "latest"
    def STABLE_TAG = "stable"
    def stages = [:]
    def deployTemplatesDirectory
    def edpName
    def stageName
    def runStageName
    def metaProject
    def deployProject
    def stageWithoutPrefixName
    def buildCause
    def buildUser
    def buildUrl
    def jenkinsUrl
    def codebasesList = []
    def servicesList = []
    def userInputImagesToDeploy
    def inputProjectPrefix
    def promotion = [:]
    def releaseName
    def releaseFromCommitId
    def adminConsoleUrl
    def sharedSecretsMask = "edp-shared-"
    def pipelineName
    def qualityGates = [:]
    def applicationsToPromote
    def deployJobParameters = []
    def sortedVersions = []
    def autotestName
    def gitProjectPath
    def credentialsId
    def autouser
    def host
    def sshPort
    def testReportFramework
    def autotestBranch
    def maxOfParallelDeployApps
    def maxOfParallelDeployServices

    Job(type, platform, script) {
        this.type = type
        this.script = script
        this.platform = platform
    }

    def getParameterValue(parameter, defaultValue = null) {
        def parameterValue = script.env["${parameter}"] ? script.env["${parameter}"] : defaultValue
        return parameterValue
    }

    def init() {
        this.deployTemplatesDirectory = getParameterValue("DEPLOY_TEMPLATES_DIRECTORY", "deploy-templates")
        this.buildUrl = getParameterValue("BUILD_URL")
        this.jenkinsUrl = getParameterValue("JENKINS_URL")
        this.edpName = platform.getJsonPathValue("cm", "user-settings", ".data.edp_name")
        this.adminConsoleUrl = platform.getJsonPathValue("cm", "user-settings", ".data.admin_console_url")
        this.codebasesList = getCodebaseFromAdminConsole()
        this.buildUser = getBuildUser()
        switch (type) {
            case JobType.CREATERELEASE.value:
                this.releaseName = getParameterValue("RELEASE_NAME").toLowerCase()
                if (!this.releaseName) {
                    script.error("[JENKINS][ERROR] Parameter RELEASE_NAME is mandatory to be specified, please check configuration of job")
                }
                this.releaseFromCommitId = getParameterValue("COMMIT_ID", "")
            case [JobType.BUILD.value, JobType.CODEREVIEW.value, JobType.CREATERELEASE.value]:
                def stagesConfig = getParameterValue("STAGES")
                if (!stagesConfig?.trim())
                    script.error("[JENKINS][ERROR] Parameter STAGES is mandatory to be specified, please check configuration of job")
                try {
                    this.stages = new JsonSlurperClassic().parseText(stagesConfig)
                }
                catch (Exception ex) {
                    script.error("[JENKINS][ERROR] Couldn't parse stages configuration from parameter STAGE - not valid JSON formate.\r\nException - ${ex}")
                }
            case JobType.DEPLOY.value:
                this.maxOfParallelDeployApps = getParameterValue("MAX_PARALLEL_APPS", 5)
                this.maxOfParallelDeployServices = getParameterValue("MAX_PARALLEL_SERVICES", 3)
        }
    }

    def initDeployJob() {
        this.pipelineName = script.JOB_NAME.split("-cd-pipeline")[0]
        this.stageName = script.JOB_NAME.split('/')[1]
        this.metaProject = "${this.edpName}-edp-cicd"
        def stageCodebasesList = []
        def codebaseBranchList = [:]
        def stageContent = getStageFromAdminConsole(this.pipelineName, stageName, "cd-pipeline")
        def pipelineContent = getPipelineFromAdminConsole(this.pipelineName, "cd-pipeline")
        this.applicationsToPromote = pipelineContent.applicationsToPromote
        this.servicesList = pipelineContent.services
        this.qualityGates = stageContent.qualityGates
        this.stageWithoutPrefixName = "${this.pipelineName}-${stageName}"
        this.deployProject = "${this.edpName}-${this.pipelineName}-${stageName}"
        stageContent.applications.each() { item ->
            stageCodebasesList.add(item.name)
            codebaseBranchList["${item.name}"] = ["branch"   : item.branchName,
                                                  "inputIs" : item.inputIs,
                                                  "outputIs": item.outputIs]
        }

        def iterator = codebasesList.listIterator()
        while (iterator.hasNext()) {
            if (!stageCodebasesList.contains(iterator.next().name)) {
                iterator.remove()
            }
        }

        codebasesList.each() { item ->
            item.branch = codebaseBranchList["${item.name}"].branch
            item.normalizedName = "${item.name}-${item.branch.replaceAll("[^\\p{L}\\p{Nd}]+", "-")}"
            item.inputIs = codebaseBranchList["${item.name}"].inputIs.replaceAll("[^\\p{L}\\p{Nd}]+", "-")
            item.outputIs = codebaseBranchList["${item.name}"].outputIs.replaceAll("[^\\p{L}\\p{Nd}]+", "-")

        }

        setCodebaseTags()
    }

    def setGitServerDataToJobContext(gitServerName) {
        def gitServerCrApiGroup = "gitservers.${getParameterValue("GIT_SERVER_CR_VERSION")}.edp.epam.com"
        this.credentialsId = platform.getJsonPathValue(gitServerCrApiGroup, gitServerName, ".spec.nameSshKeySecret")
        this.autouser = platform.getJsonPathValue(gitServerCrApiGroup, gitServerName, ".spec.gitUser")
        this.host = platform.getJsonPathValue(gitServerCrApiGroup, gitServerName, ".spec.gitHost")
        this.sshPort = platform.getJsonPathValue(gitServerCrApiGroup, gitServerName, ".spec.sshPort")

        println("[JENKINS][DEBUG] GitServer data is set up: credId - ${this.credentialsId}," +
                " autouser - ${this.autouser}, host - ${this.host}, sshPort - ${this.sshPort}")
    }

    def setCodebaseTags() {
        codebasesList.each() { codebase ->
            def codebaseTags = getCodebaseTags(codebase,codebase.inputIs)

            if (!codebaseTags.contains(LATEST_TAG))
                codebaseTags += [LATEST_TAG]

            if (!codebaseTags.contains(STABLE_TAG))
                codebaseTags += [STABLE_TAG]

            codebase.sortedTags = sortTags(codebaseTags)

            def outputIsVersions = getCodebaseTags(codebase, codebase.outputIs)
            def sortedOutputIsVersions = sortTags(outputIsVersions)

            codebase.latest = getFirstTag(codebase.sortedTags)
            codebase.stable = getFirstTag(sortedOutputIsVersions)

            if (codebase.stable == "noImageExists") {
                codebase.sortedTags -= [STABLE_TAG]
            }

            if (codebase.latest == "noImageExists") {
                codebase.sortedTags -= [LATEST_TAG]
            }

            script.println("Latest tag: ${codebase.latest}")
            script.println("Stable tag: ${codebase.stable}")

            codebase.sortedTags = codebase.sortedTags
                    .collect{tag -> tag.replaceAll(/^latest/, "${LATEST_TAG} (${codebase.latest})") }
                    .collect{tag -> tag.replaceAll(/^stable/, "${STABLE_TAG} (${codebase.stable})") }

            script.println("sorted Params: ${codebase.sortedTags}")
        }
    }

    def getCodebaseTags(codebase, imageStream) {
        def tags = ['noImageExists']
        def imageStreamExists = script.sh(
                script: "oc -n ${metaProject} get is ${imageStream} --no-headers | awk '{print \$1}'",
                returnStdout: true
        ).trim()
        if (imageStreamExists != "")
            tags = script.sh(
                    script: "oc -n ${metaProject} get is ${imageStream} -o jsonpath='{range .spec.tags[*]}{.name}{\"\\n\"}{end}'",
                    returnStdout: true
            ).trim().tokenize()
        def latestTag = tags.find { it == 'latest' }
        if (latestTag) {
            tags = tags.minus(latestTag)
            tags.add(0, latestTag)
        }
        if (tags != ['noImageExists']) {
            tags.add(0, "No deploy")
        }

        return tags
    }

    @NonCPS
    private def getFirstTag(tags) {
        def tag = tags.stream()
                .filter { it != "latest" }
                .filter { it != "stable" }
                .filter { it != "No deploy" }
                .findFirst()
                .get()

        return tag
    }

    @NonCPS
    private def sortTags(tags) {
        def map = ["latest": 2, "stable": 1, "No deploy": 3]

        return tags
                .collect { new ComparableVersion(it) }
                .sort { e1, e2 ->
            def res = map.getOrDefault(e1.toString(), 0) - map.getOrDefault(e2.toString(), 0)
            if (res == 0){ e1.compareTo(e2) }
            res
        }
        .collect { item -> item.toString() }
                .reverse()
    }

    def generateInputDataForDeployJob() {
        codebasesList.each() { codebase ->
            deployJobParameters.add(script.choice(choices: "${codebase.sortedTags.join('\n')}", description: '', name: "${codebase.name.toUpperCase().replaceAll("-", "_")}_VERSION"))
        }

        userInputImagesToDeploy = script.input id: 'userInput', message: 'Provide the following information', parameters: deployJobParameters
        script.println("USERS_INPUT_IMAGES_TO_DEPLOY: ${userInputImagesToDeploy}")

        codebasesList.each() { codebase ->
            if (userInputImagesToDeploy instanceof java.lang.String) {
                codebase.version = userInputImagesToDeploy
                if (codebase.version.startsWith(LATEST_TAG))
                    codebase.version = LATEST_TAG
                if (codebase.version.startsWith(STABLE_TAG))
                    codebase.version = STABLE_TAG
            }
            else {
                userInputImagesToDeploy.each() { item ->
                    if (item.value.startsWith(LATEST_TAG)) {
                        userInputImagesToDeploy.put(item.key, LATEST_TAG)
                    }
                    if (item.value.startsWith(STABLE_TAG)) {
                        userInputImagesToDeploy.put(item.key, STABLE_TAG)
                    }
                }
                codebase.version = userInputImagesToDeploy["${codebase.name.toUpperCase().replaceAll("-", "_")}_VERSION"]
            }
            codebase.version = codebase.version ? codebase.version : LATEST_TAG
        }
    }

    def getBuildUser() {
        script.wrap([$class: 'BuildUser']) {
            def userId = getParameterValue("BUILD_USER_ID")
            return userId
        }
    }

    def setBuildResult(result) {
        script.currentBuild.result = result
    }

    def setDisplayName(displayName) {
        script.currentBuild.displayName = displayName
    }

    def setDescription(description, addDescription = false) {
        if (addDescription)
            script.currentBuild.description = "${script.currentBuild.description}\r\n${description}"
        else
            script.currentBuild.description = description
    }

    void printDebugInfo(context) {
        def debugOutput = ""
        context.keySet().each { key ->
            debugOutput = debugOutput + "${key}=${context["${key}"]}\n"
        }
        script.println("[JENKINS][DEBUG] Pipeline's context:\n${debugOutput}")
    }

    def runStage(stageName, context, runStageName = null) {
        script.stage(runStageName ? runStageName : stageName) {
            if (context.codebase)
                context.factory.getStage(stageName.toLowerCase(),
                        context.codebase.config.build_tool.toLowerCase(),
                        context.codebase.config.type).run(context)
            else
                context.factory.getStage(stageName.toLowerCase()).run(context)
        }
    }

    private def getBuildCause() {
        return platform.getJsonPathValue("build", "${this.deployProject}-deploy-pipeline-${script.BUILD_NUMBER}", ".spec.triggeredBy[0].message")
    }

    def getTokenFromAdminConsole() {
        def userCredentials = getCredentialsFromSecret("admin-console-reader")
        def clientCredentials = getCredentialsFromSecret("admin-console-client")

        def dnsWildcard = platform.getJsonPathValue("cm", "user-settings", ".data.dns_wildcard")

        def response = script.httpRequest url: "https://keycloak-security.${dnsWildcard}/auth/realms/${this.edpName}-edp/protocol/openid-connect/token",
                httpMode: 'POST',
                contentType: 'APPLICATION_FORM',
                requestBody: "grant_type=password&username=${userCredentials.username}&password=${userCredentials.password}" +
                        "&client_id=${clientCredentials.username}&client_secret=${clientCredentials.password}",
                consoleLogResponseBody: true

        return new JsonSlurperClassic()
                .parseText(response.content)
                .access_token
    }

    def getCodebaseFromAdminConsole(codebaseName = null) {
        def accessToken = getTokenFromAdminConsole()

        def url = "${adminConsoleUrl}/api/v1/edp/codebase${codebaseName ? "/${codebaseName}" : ""}"
        def response = script.httpRequest url: "${url}",
                httpMode: 'GET',
                customHeaders: [[name: 'Authorization', value: "Bearer ${accessToken}"]],
                consoleLogResponseBody: true

        return new JsonSlurperClassic().parseText(response.content.toLowerCase())
    }

    def getStageFromAdminConsole(pipelineName, stageName, pipelineType) {
        def accessToken = getTokenFromAdminConsole()

        def url = "${adminConsoleUrl}" + "/api/v1/edp/${pipelineType}/${pipelineName}/stage/${stageName}"
        def response = script.httpRequest url: "${url}",
                httpMode: 'GET',
                customHeaders: [[name: 'Authorization', value: "Bearer ${accessToken}"]],
                consoleLogResponseBody: true

        return new JsonSlurperClassic().parseText(response.content)
    }

    def getPipelineFromAdminConsole(pipelineName, pipelineType) {
        def accessToken = getTokenFromAdminConsole()

        def url = "${adminConsoleUrl}" + "/api/v1/edp/${pipelineType}/${pipelineName}"
        def response = script.httpRequest url: "${url}",
                httpMode: 'GET',
                customHeaders: [[name: 'Authorization', value: "Bearer ${accessToken}"]],
                consoleLogResponseBody: true

        return new JsonSlurperClassic().parseText(response.content)
    }

    private def getCredentialsFromSecret(name) {
        def credentials = [:]
        credentials['username'] = getSecretField(name, 'username')
        credentials['password'] = getSecretField(name, 'password')
        return credentials
    }

    private def getSecretField(name, field) {
        return new String(platform.getJsonPathValue("secret", name, ".data.\\\\${field}").decodeBase64())
    }


}
