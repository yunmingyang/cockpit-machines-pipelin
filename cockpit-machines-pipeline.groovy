import java.util.regex.*
import java.net.InetAddress
import org.apache.commons.lang3.RandomStringUtils


def guest
def composeId
def testSuiteResultPath
def RES_HOST
def RES_PATH
def TOOLS_HOME = "/root"
def OS = JOB_NAME.split("pipeline-")[-1].toLowerCase()
def enableVenv = String.format("source %s/cockpit-venv/bin/activate",
                               TOOLS_HOME)
def linchpinWorkspace = String.format("%s/linchpin-workspace", TOOLS_HOME)


@NonCPS
String getInvertoriesPath(String log){
    Matcher m = Pattern.compile("\"inventory_path\":\\s\".*\"").matcher(log)
    if(!m.find()){
        throw new Exception("can not find invertory path")
    }
    return m.group().split("\\s")[1].trim().replaceAll("\"", "")
}

String strWColor(String str){
    return "\033[1;31m" + str + "\033[0m")
}

properties([
    parameters([
        string(name: 'COMPOSE_ID', description: "Compose id for manual"),
        string(name: 'BRANCH', description: "Select branch for manual"),
        choice(name: 'ARCH', choices: ['x86_64', 's390x', 'ppc64le'], description: "ARCH for provision")
    ])
    //TODO: pipelineTrigger
])

node('jslave-cockpit-machines'){
    ansiColor('xterm'){
        println(strWColor("--------------------enable ansiColor with xterm----------------------"))
    }

    stage("Read config"){
        def config = readJSON(file: "/root/job_config.json")
        RES_HOST = config["res_host"]
        RES_PATH = config["res_path"]

        println(strWColor("TOOLS_HOME is: " + TOOLS_HOME))
        println(strWColor("RES_HOST is: " + RES_HOST))
        println(strWColor("RES_PATH is: " + RES_PATH))
        println(strWColor("OS is: " + OS))
        println(strWColor("ARCH is: " + ARCH))
    }

    stage("Pre-operations"){
        println(strWColor("Linchpin Workspace is " + linchpinWorkspace))

        composeId = COMPOSE_ID ? COMPOSE_ID : readJSON(text: CI_MESSAGE)['compose_id']
        currentBuild.description = "Compose is " + composeId
    }

    stage("Check existence of the distro"){
        def outputCheck = 1
        def count = 0
        def checkCmd = enableVenv + " && bkr distros-list --name=" + composeId
        while(true){
            count++
            println(strWColor("This is the " + count + " time"))

            outputCheck = sh(script: checkCmd, returnStatus: true)
            if (outputCheck == 0){
                break
            }else{
                sleep(60)
            }

            if (count == 4320){
                error(strWColor("No such distro"))
            }
        }
    }

    stage("Provision"){
        def linchpinCmd = String.format("%s && linchpin -vvvv -c %s -w %s --template-data '{ \"distro\": \"%s\", \"arch\": \"%s\" }' up",
                                        enableVenv,
                                        linchpinWorkspace + "/linchpin.conf",
                                        linchpinWorkspace,
                                        composeId,
                                        ARCH)
        def output = sh(script: linchpinCmd, returnStdout: true)
        println(strWColor("--------------------print output after running command----------------------"))
        println(output)
        println(strWColor("--------------------finished.----------------------"))

        def invertoryData = readFile(file: getInvertoriesPath(output), encoding: "UTF-8")
        guest = InetAddress.getByName(invertoryData.split("all")[-1].split("]")[-1].split("=")[-1].trim()).getHostAddress()
        println(strWColor("--------------------Guest ip address is " + guest + "----------------------"))
    }

    stage("Clone"){
        deleteDir()
        // TODO: the variable for multiarch
        def autoBranch = "main"
        println(strWColor("--------------------auto branch is :" + autoBranch + "----------------------"))
        //Use local repositories as we can control the automation version
        checkout([
            $class: 'GitSCM',
            branches: [[name: autoBranch]],
            userRemoteConfigs: [[url: 'https://github.com/yunmingyang/cockpit-machines.git']],
            extensions: [
                [$class: 'CloneOption', shallow: true, noTags: true, depth: 1, timeout: 30]
            ]
        ])
    }

    stage("make"){
        sh(script: "make test/common && " + 
                   "make src/lib/cockpit-po-plugin.js && " +
                   "make bots && " +
                   "make node_modules/.bin/webpack")
    }

    stage("Run testsuite"){
        println(strWColor("--------------------check browsers versions----------------------"))
        sh(script: "google-chrome --version && firefox --version")

        println(strWColor("--------------------create results directory----------------------"))
        testSuiteResultPath = WORKSPACE + "/" + composeId + "_" + ARCH + "_" + RandomStringUtils.random(10, true, true)
        sh(script: "mkdir " + testSuiteResultPath)
        println(strWColor("testSuiteResultPath is " + testSuiteResultPath)

        for(def i in findFils(glob: "test/check-machines-*")){
            println(strWColor("--------------------run " + i.name + " on chrome--------------------"))
            sh(script: String.format("%s && TEST_OS=%s %s/%s --machine=%s | tee %s",
                                     enableVenv,
                                     OS,
                                     WORKSPACE,
                                     i.toString(),
                                     guest,
                                     testSuiteResultPath + "/" + i.name))

            println(strWColor("-------------------run" + i.name + " on firefox--------------------"))
            sh(script: String.format("%s && TEST_OS=%s TEST_BROWSER=firefox %s/%s --machine=%s | tee %s",
                                     enableVenv,
                                     OS,
                                     WORKSPACE,
                                     i.toString(),
                                     guest,
                                     testSuiteResultPath + "/" + i.name))
        }
    }

    stage("Upload"){
        def cmd = String.format("scp -r %s root@%s:%s",
                                testSuiteResultPath,
                                RES_HOST,
                                RES_PATH)
        sh(script: cmd)

        def resURL = String.format("http://%s/results/iscsi/cockpit-machines/%s",
                                   RES_HOST,
                                   testSuiteResultPath.split("/")[-1])
        println(strWColor("please check the log at " + resURL))
    }
}
