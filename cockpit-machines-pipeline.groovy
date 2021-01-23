import java.util.regex.*
import java.net.InetAddress
import org.apache.commons.lang3.RandomStringUtils


def guest
def composeId
def testSuiteResultPath
def enableVenv = String.format("source %s/cockpit-venv/bin/activate && ",
                               HOME)
def linchpinWorkspace = String.format("%s/linchpin-workspace", HOME)
def exceptionList = new LinkedList<Exception>()


@NonCPS
String getInvertoriesPath(String log){
    Matcher m = Pattern.compile("\"inventory_path\":\\s\".*\"").matcher(log)
    if(!m.find()){
        throw new Exception("no such string")
    }
    return m.group().split("\\s")[1].trim().replaceAll("\"", "")
}

node('jslave-cockpit-machines'){

    stage("Pre-operations"){
        println("Linchpin Workspace is " + linchpinWorkspace)

        composeId = COMPOSE_ID ? COMPOSE_ID : readJSON(text: CI_MESSAGE)['compose_id']
        currentBuild.description = "Compose is " + composeId
    }

    stage("Provision"){
        def linchpinCmd = String.format("%slinchpin -vvvv -c %s -w %s --template-data '{ \"distro\": \"%s\", \"arch\": \"%s\"}' up",
                                        enableVenv,
                                        linchpinWorkspace + "/linchpin.conf",
                                        linchpinWorkspace,
                                        composeId,
                                        ARCH)
        def output = sh(script: linchpinCmd, returnStdout: true)
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

        sh(script: "npm cache clean --force && npm install" + registry)
    }

    stage("Run testsuite"){
        println("--------------------check browsers versions----------------------")
        sh(script: "google-chrome --version && firefox --version")

        println("--------------------create results directory----------------------")
        testSuiteResultPath = WORKSPACE + "/" + composeId + "_" + RandomStringUtils.random(5, true, true) + "_" + ARCH
        sh(script: "mkdir " + testSuiteResultPath)
        println("testSuiteResultPath is " + testSuiteResultPath)

        print("--------------------run verify-* test on chrome--------------------")
        def runCmd = String.format("%s && %s/test/verify/check-machines --machine=%s | tee %s",
                                   enableVenv,
                                   WORKSPACE,
                                   guest,
                                   testSuiteResultPath + "/chrome.log")
        try{
            sh(script: runCmd)
        } catch(e){
            exceptionList.add(e)
        }

        print("-------------------run verify-* test on firefox--------------------")
        runCmd = String.format("%s && TEST_BROWSER=firefox %s/test/verify/check-machines --machine=%s | tee %s",
                               enableVenv,
                               WORKSPACE,
                               guest,
                               testSuiteResultPath + "/firefox.log")
        try{
            sh(script: runCmd)
        } catch(e){
            exceptionList.add(e)
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
        println("-------------------please check the log at " + resURL + "--------------------")

        if(exceptionList){
            def throwingExc = new Exception('these exception are throwed')
            for(Exception e: exceptionList){
                throwingExc.addSuppressed(e)
            }
            throw throwingExc
        }
    }
}
