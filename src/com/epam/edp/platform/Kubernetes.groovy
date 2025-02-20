/* Copyright 2018 EPAM Systems.

 Licensed under the Apache License, Version 2.0 (the "License");
 you may not use this file except in compliance with the License.
 You may obtain a copy of the License at
 http://www.apache.org/licenses/LICENSE-2.0

 Unless required by applicable law or agreed to in writing, software
 distributed under the License is distributed on an "AS IS" BASIS,
 WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.

 See the License for the specific language governing permissions and
 limitations under the License.*/

package com.epam.edp.platform
import groovy.json.JsonSlurperClassic

class Kubernetes implements Platform {
    Script script
    def promoteStageName = "Promote-images-ecr"

    def getJsonPathValue(object, name, jsonPath, project = null) {
        def command = "kubectl get ${object} ${name} -o jsonpath='{${jsonPath}}'"
        if (project)
            command = "${command} -n ${project}"
        return script.sh(
                script: command,
                returnStdout: true
        ).trim()
    }

    def getJsonValue(object, name, project = null) {
        def command = "kubectl get ${object} ${name} -o json"
        if (project)
            command = "${command} -n ${project}"
        return script.sh(
                script: command,
                returnStdout: true
        ).trim()
    }

    def getImageStream(imageStreamName, crApiGroup) {
        return script.sh(
                script: "kubectl get cbis.${crApiGroup} ${imageStreamName} --ignore-not-found=true --no-headers | awk '{print \$1}'",
                returnStdout: true
        ).trim()
    }

    def getImageStreamTags(imageStreamName, crApiGroup) {
        script.sh(
                script: "kubectl get cbis.${crApiGroup} ${imageStreamName} -o jsonpath='{range .spec.tags[*]}{.name}{\"\\n\"}{end}'",
                returnStdout: true
        ).trim().tokenize()
    }

    def apply(fileName) {
        script.sh(script: "oc apply -f ${fileName}")
    }

    def deleteObject(objectType, objectName, force = false) {
        def command = "kubectl delete ${objectType} ${objectName}"
        if (force) {
            command = "${command} --force --grace-period=0"
        }
        try {
            script.sh(script: "${command}")
        } catch(Exception ex){}
    }

    def copyToPod(source, destination, podName,podNamespace = null, podContainerName = null) {
        def command = "kubectl cp ${source} "

        if (podNamespace)
            command = "${command}${podNamespace}/"

        command = "${command}${podName}:${destination}"

        if (podContainerName)
            command = "${command} -c ${podContainerName}"
        script.sh(script: "${command}")
    }

    def getObjectStatus(objectType, objectName, project = null) {
        def output = getJsonValue(objectType, objectName, project)
        def parsedInitContainer = new JsonSlurperClassic().parseText(output)
        return parsedInitContainer["status"]
    }

    def getExternalEndpoint(name) {
        return getJsonPathValue("ingress", name, ".spec.rules[0].host")
    }

    def checkObjectExists(objectType, objectName, project = null) {
        def command = "kubectl get ${objectType} ${objectName} --ignore-not-found=true"
        if (project)
            command = "${command} -n ${project}"

        def res = script.sh(
                script: command,
                returnStdout: true
        ).trim()
        if (res == "")
            return false
        return true
    }

    def createProjectIfNotExist(name, edpName) {
        if (!checkObjectExists("ns", name))
            script.sh("kubectl create ns ${name}")
    }

    def getObjectList(objectType) {
        return script.sh(
                script: "kubectl get ${objectType} -o jsonpath='{.items[*].metadata.name}'",
                returnStdout: true
        ).trim().tokenize()
    }

    def copySharedSecrets(sharedSecretName, secretName, project) {
        script.sh("kubectl get --export -o yaml secret ${sharedSecretName} | " +
                "sed -e 's/name: ${sharedSecretName}/name: ${secretName}/' | " +
                "kubectl -n ${project} apply -f -")
    }

    def createRoleBinding(user, project) {
        println("[JENKINS][DEBUG] Security model for kubernetes hasn't defined yet")
    }

    def createConfigMapFromFile(cmName, project, filePath) {
        script.sh("kubectl create configmap ${cmName} -n ${project} --from-file=${filePath} --dry-run -o yaml | oc apply -f -")
    }

    def deployCodebase(project, chartPath, imageName, codebase, dnsWildcard, timeout, isDeployed) {
        def command = isDeployed ? "upgrade --force" : "install -n"
        script.sh("helm ${command} " +
                "${project}-${codebase.name} " +
                "--wait " +
                "--timeout=${timeout} " +
                "--namespace ${project} " +
                "--set name=${codebase.name} " +
                "--set namespace=${project} " +
                "--set image.name=${imageName} " +
                "--set image.version=${codebase.version} " +
                "--set database.required=${codebase.db_kind != "" ? true : false} " +
                "--set database.version=${codebase.db_version} " +
                "--set database.capacity=${codebase.db_capacity} " +
                "--set database.database.storageClass=${codebase.db_storage} " +
                "--set ingress.required=${codebase.route_site != "" ? true : false} " +
                "--set ingress.path=${codebase.route_path} " +
                "--set ingress.site=${codebase.route_site} " +
                "--set dnsWildcard=${dnsWildcard} " +
                "${chartPath}")
    }

    def verifyDeployedCodebase(name, project) {
        def deployedCodebases = script.sh(
                script: "helm ls --namespace=${project} -a -q",
                returnStdout: true
        ).trim().tokenize()
        if (deployedCodebases.contains("${project}-${name}".toString()))
            return true

        return false
    }

    def rollbackDeployedCodebase(name, project) {
        script.sh("helm rollback ${project}-${name} 0")
    }
}