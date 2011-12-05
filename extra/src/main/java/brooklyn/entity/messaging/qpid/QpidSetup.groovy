package brooklyn.entity.messaging.qpid

import java.util.List
import java.util.Map

import brooklyn.entity.basic.Attributes
import brooklyn.entity.basic.lifecycle.legacy.SshBasedJavaAppSetup
import brooklyn.location.basic.SshMachineLocation

/**
 * Start a {@link QpidBroker} in a {@link brooklyn.location.Location} accessible over ssh.
 */
public class QpidSetup extends SshBasedJavaAppSetup {
    public static final String DEFAULT_VERSION = "0.14"
    public static final String DEFAULT_INSTALL_DIR = DEFAULT_INSTALL_BASEDIR+"/"+"qpid"
    public static final int DEFAULT_FIRST_AMQP_PORT = 5672

    private int amqpPort
    private int rmiPort

    public static QpidSetup newInstance(QpidBroker entity, SshMachineLocation machine) {
        String suggestedVersion = entity.getConfig(QpidBroker.SUGGESTED_VERSION)
        String suggestedInstallDir = entity.getConfig(QpidBroker.SUGGESTED_INSTALL_DIR)
        String suggestedRunDir = entity.getConfig(QpidBroker.SUGGESTED_RUN_DIR)
        Integer suggestedJmxPort = entity.getConfig(QpidBroker.JMX_PORT.configKey)
        Integer suggestedAmqpPort = entity.getConfig(QpidBroker.AMQP_PORT.configKey)

        String version = suggestedVersion ?: DEFAULT_VERSION
        String installDir = suggestedInstallDir ?: "$DEFAULT_INSTALL_DIR/${version}/qpid-broker-${version}"
        String runDir = suggestedRunDir ?: "$BROOKLYN_HOME_DIR/${entity.application.id}/qpid-${entity.id}"
        String logFileLocation = "$runDir/log/qpid.log"

        int jmxPort = machine.obtainPort(toDesiredPortRange(suggestedJmxPort))
        int rmiPort = machine.obtainPort(toDesiredPortRange(jmxPort - 100))
        int amqpPort = machine.obtainPort(toDesiredPortRange(suggestedAmqpPort))

        QpidSetup result = new QpidSetup(entity, machine)
        result.setRmiPort(rmiPort)
        result.setJmxPort(jmxPort)
        result.setAmqpPort(amqpPort)
        result.setVersion(version)
        result.setInstallDir(installDir)
        result.setRunDir(runDir)
        entity.setAttribute(Attributes.LOG_FILE_LOCATION, logFileLocation)

        return result
    }

    public QpidSetup(QpidBroker entity, SshMachineLocation machine) {
        super(entity, machine)
    }

    public void setAmqpPort(int val) {
        amqpPort = val
    }

    public void setRmiPort(int val) {
        rmiPort = val
    }

    /** JMX is configured using command line switch. */
    @Override
    protected Map getJmxJavaSystemProperties() { [:] }

    @Override
    protected void setEntityAttributes() {
		super.setEntityAttributes()
        entity.setAttribute(Attributes.AMQP_PORT, amqpPort)
    }

    /**
     * Configure the broker.
     */
    @Override
    public void config() {
        super.config()
        copyFilesForRuntime()
    }

    public void copyFilesForRuntime() {
        entity.getConfig(QpidBroker.RUNTIME_FILES).each {
            String dest, File source ->
            int result = machine.copyTo source, "${runDir}/${dest}"
            log.info("copied ${source.path} to ${runDir}/${dest} - ${result}")
        }
    }

    @Override
    public List<String> getInstallScript() {
        makeInstallScript([
                // TODO change back after ASF 0.14 release
                // "wget http://download.nextag.com/apache/qpid/${version}/qpid-java-broker-${version}.tar.gz",
                // "tar xvzf qpid-java-broker-${version}.tar.gz",
                "wget http://developers.cloudsoftcorp.com/download/qpid/qpid-broker-${version}.tgz",
                "tar xzvf qpid-broker-${version}.tgz"
            ])
    }

    /**
     * Creates the directories Qpid needs to run in a different location from where it is installed.
     */
    public List<String> getRunScript() {
        List<String> script = [
            "cd ${runDir}",
			"nohup ./bin/qpid-server -m ${jmxPort} -p ${amqpPort} --exclude-0-8 ${amqpPort} --exclude-0-9 ${amqpPort} --exclude-0-9-1 ${amqpPort} &",
        ]
        return script
    }

    public Map<String, String> getShellEnvironment() {
        Map result = super.getShellEnvironment()
		result << [
			"QPID_HOME" : "${runDir}",
			"QPID_WORK" : "${runDir}",
			"QPID_OPTS" : result.JAVA_OPTS
        ]
    }

    /** @see SshBasedJavaAppSetup#getCheckRunningScript() */
    public List<String> getCheckRunningScript() {
       return makeCheckRunningScript("qpid", "qpid-server.pid")
    }

    @Override
    public List<String> getConfigScript() {
        List<String> script = [
            "mkdir -p ${runDir}",
            "cd ${runDir}",
            "cp -R ${installDir}/{bin,etc,lib} .",
                // JE and BDBstore
        ]
        return script
    }

    @Override
    public List<String> getRestartScript() {
       return makeRestartScript("qpid", "qpid-server.pid")
    }

    @Override
    public List<String> getShutdownScript() {
       return makeShutdownScript("qpid", "qpid-server.pid")
    }

    @Override
    protected void postShutdown() {
        machine.releasePort(rmiPort)
        machine.releasePort(jmxPort)
        machine.releasePort(amqpPort)
    }
}
