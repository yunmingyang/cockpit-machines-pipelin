import java.util.regex.*
import java.net.InetAddress
import org.apache.commons.lang3.RandomStringUtils


def guest
def testSuiteResultPath
def enableVenv = String.format("source %s/cockpit-venv/bin/activate && ",
                               HOME)
def linchpinWorkspace = String.format("%s/linchpin-workspace", HOME)


@NonCPS
String getInvertoriesPath(String log){
    Matcher m = Pattern.compile("\"inventory_path\":\\s\".*\"").matcher(log)
    if(!m.find()){
        throw new Exception("no such string")
    }
    return m.group().split("\\s")[1].trim().replaceAll("\"", "")
}

// TODO: upload results
// TODO: update browsers(chrome)
node('jslave-cockpit-machines'){

    stage("Pre-operations"){
        println("Linchpin Workspace is " + linchpinWorkspace)

        def composeId = COMPOSE_ID ? COMPOSE_ID : readJSON(text: CI_MESSAGE)['msg']['compose_id']
        currentBuild.description = "Compose is " + composeId

        testSuiteResultPath = String.format(WORKSPACE + "/%s_" + RandomStringUtils.random(5, true, true), composeId)
        println("testSuiteResultPath is " + testSuiteResultPath)

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
    }
    
    stage("Clone"){
        deleteDir()
        checkout([
                $class: 'GitSCM',
                branches: [[name: 'rhel-8.3-verify']],
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
        sh(script: "npm install")
    }

    stage("Run test"){
        println("---------------------check browsers versions---------------------")
        sh(script: "google-chrome --version && firefox --version")
        println("-----------------------------------------------------------------")

        print("--------------------run verify-* test on chrome--------------------")
        def runCmd = String.format("test/verify/check-machines --machine=%s | tee %s",
                                   "10.73.131.87",
                                   testSuiteResultPath + "/chrome.log")
        sh(script: runCmd)
        
        print("-------------------run verify-* test on firefox--------------------")
        runCmd = String.format("TEST_BROWSER=firefox test/verify/check-machines --machine=%s | tee %s",
                               "10.73.131.87",
                               testSuiteResultPath + "/firefox.log")
        sh(script: runCmd)
    }

    stage("Upload"){
        println("upload")
    }
}