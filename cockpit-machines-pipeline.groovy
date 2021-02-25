import java.util.regex.*
import java.net.InetAddress
import org.apache.commons.lang3.RandomStringUtils


def guest
def composeId
def testSuiteResultPath
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

node('jslave-cockpit-machines'){
    ansiColor('xterm'){
        println('\033[1;31m--------------------enable ansiColor with xterm----------------------\033[0m')
    }

    stage("Pre-operations"){
        println("Linchpin Workspace is " + linchpinWorkspace)

        composeId = COMPOSE_ID ? COMPOSE_ID : readJSON(text: CI_MESSAGE)['compose_id']
        currentBuild.description = "Compose is " + composeId
    }

    stage("Check existence of the distro"){
        def outputCheck = 1
        def count = 0
        def checkCmd = enableVenv + " && bkr distros-list --name=" + composeId
        while(true){
            count++
            println("\033[1;31mThis is the " + count + " time\033[0m")

            outputCheck = sh(script: checkCmd, returnStatus: true)
            if (outputCheck == 0){
                break
            }else{
                sleep(60)
            }

            if (count == 4320){
                error("\033[1;31mNo such distro\033[0m")
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
        println("\033[1;31m--------------------print output after running command----------------------\033[0m")
        println(output)
        println("\033[1;31m--------------------finished.----------------------\033[0m")

        def invertoryData = readFile(file: getInvertoriesPath(output), encoding: "UTF-8")
        guest = InetAddress.getByName(invertoryData.split("all")[-1].split("]")[-1].split("=")[-1].trim()).getHostAddress()
        println("Guest ip address is " + guest)
    }

    stage("Clone"){
        deleteDir()
        def autoBranch = "rhel-" + AUTO_BRANCH_VERSION + "-"
        if (ARCH == "x86_64"){
            autoBranch += "verify"
        }else{
            autoBranch += ARCH
        }
        println("auto branch is :" + autoBranch)

        checkout([
                $class: 'GitSCM',
                branches: [[name: autoBranch]],
                userRemoteConfigs: [[url: 'https://github.com/yunmingyang/cockpit.git']],
                extensions: [
                    [$class: 'CloneOption', shallow: true, noTags: true, depth: 1, timeout: 30]
                ]
            ])

        checkout([
                $class: 'GitSCM',
                branches: [[name: 'master']],
                userRemoteConfigs: [[url: 'https://github.com/cockpit-project/bots.git']],
                extensions: [
                    [$class: 'CloneOption', shallow: true, noTags: true, depth: 1, timeout: 30],
                    [$class: 'RelativeTargetDirectory', relativeTargetDir: WORKSPACE + '/bots']
                ]
            ])
    }

    stage("Npm install"){
        def registry = NPM_REGISTRY ? " --registry " + NPM_REGISTRY : "" 

        sh(script: "npm install --loglevel verbose" + registry)
    }

    stage("Run testsuite"){
        println("--------------------check browsers versions----------------------")
        sh(script: "google-chrome --version && firefox --version")

        println("--------------------create results directory----------------------")
        testSuiteResultPath = WORKSPACE + "/" + composeId + "_" + ARCH + "_" + RandomStringUtils.random(10, true, true)
        sh(script: "mkdir " + testSuiteResultPath)
        println("testSuiteResultPath is " + testSuiteResultPath)

        print("--------------------run verify-* test on chrome--------------------")
        def runCmd = String.format("%s && %s/test/verify/check-machines --machine=%s | tee %s",
                                   enableVenv,
                                   WORKSPACE,
                                   guest,
                                   testSuiteResultPath + "/chrome.log")
        sh(script: runCmd)

        print("-------------------run verify-* test on firefox--------------------")
        runCmd = String.format("%s && TEST_BROWSER=firefox %s/test/verify/check-machines --machine=%s | tee %s",
                               enableVenv,
                               WORKSPACE,
                               guest,
                               testSuiteResultPath + "/firefox.log")
        sh(script: runCmd)
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
        println("please check the log at " + resURL)
    }
}
