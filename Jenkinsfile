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

        def pinFile = readYaml(file: linchpinWorkspace + "/PinFile")
        pinFile['cockpit-machines']['topology']['resource_groups'][0]['resource_definitions'][0]['recipesets'][0]['distro'] = composeId
        if (fileExists(file: linchpinWorkspace + "/PinFile")){
            sh(script: "rm -f " + linchpinWorkspace + "/PinFile")
        }
        writeYaml(file: linchpinWorkspace + "/PinFile", data: pinFile)
    }
    
    stage("Provision"){
        def linchpinCmd = String.format("%slinchpin -vvv -c %s -w %s up",
                                        enableVenv,
                                        linchpinWorkspace + "/linchpin.conf",
                                        linchpinWorkspace)
        def output = sh(script: linchpinCmd, returnStdout: true)
        def invertoryData = readFile(file: getInvertoriesPath(output), encoding: "UTF-8")
        guest = InetAddress.getByName(invertoryData.split("all")[-1].split("]")[-1].split("=")[-1].trim()).getHostAddress()
        println("Guest ip address is " + guest)
    }
    
    stage("Clone"){
        deleteDir()

        checkout([
                $class: 'GitSCM',
                branches: [[name: AUTO_BRANCH]],
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

        println("c------------------create results directory----------------------")
        testSuiteResultPath = String.format(WORKSPACE + "/%s_" + RandomStringUtils.random(5, true, true), composeId)
        sh(script: String.format("mkdir %s", testSuiteResultPath))
        println("testSuiteResultPath is " + testSuiteResultPath)

        print("--------------------run verify-* test on chrome--------------------")
        def runCmd = String.format("%s/test/verify/check-machines --machine=%s | tee %s",
                                    WORKSPACE,
                                    guest,
                                    testSuiteResultPath + "/chrome.log")
        try{
            sh(script: runCmd)
        } catch(e){
            exceptionList.add(e)
        }

        print("-------------------run verify-* test on firefox--------------------")
        runCmd = String.format("TEST_BROWSER=firefox %s/test/verify/check-machines --machine=%s | tee %s",
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
        if(exceptionList){
            def throwingExc = new Exception('these exception are throwed')
            for(Exception e: exceptionList){
                throwingExc.addSuppressed(e)
            }
            throw throwingExc
        }

        input("upload?")
        def resPath = String.format("%s/%s_%s",
                                    WORKSPACE,
                                    composeId,
                                    RandomStringUtils.random(5, true, true))
        def cmd = String.format("scp -r %s root@%s:%s",
                                testSuiteResultPath,
                                RES_HOST,
                                RES_PATH)
        sh(script: cmd)
    }
}
