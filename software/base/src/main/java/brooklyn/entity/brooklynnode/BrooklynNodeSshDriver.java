package brooklyn.entity.brooklynnode;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.lang.String.format;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import brooklyn.entity.basic.Entities;
import brooklyn.entity.drivers.downloads.DownloadResolver;
import brooklyn.entity.java.JavaSoftwareProcessSshDriver;
import brooklyn.location.basic.SshMachineLocation;
import brooklyn.util.collections.MutableMap;
import brooklyn.util.file.ArchiveBuilder;
import brooklyn.util.file.ArchiveUtils;
import brooklyn.util.net.Networking;
import brooklyn.util.net.Urls;
import brooklyn.util.os.Os;
import brooklyn.util.ssh.BashCommands;
import brooklyn.util.text.Strings;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;

public class BrooklynNodeSshDriver extends JavaSoftwareProcessSshDriver implements BrooklynNodeDriver {
    
    public BrooklynNodeSshDriver(BrooklynNodeImpl entity, SshMachineLocation machine) {
        super(entity, machine);
    }

    @Override
    public BrooklynNodeImpl getEntity() {
        return (BrooklynNodeImpl) super.getEntity();
    }

    public String getBrooklynHome() {
        return getInstallDir()+"/brooklyn-"+getVersion();
    }

    @Override
    protected String getLogFileLocation() {
        return format("%s/console", getRunDir());
    }
    
    private String getPidFile() {
        return "pid_java";
    }
    
    @Override
    public void install() {
        String uploadUrl = entity.getConfig(BrooklynNode.DISTRO_UPLOAD_URL);
        
        // Need to explicitly give file, because for snapshot URLs you don't get a clean filename from the URL.
        // This filename is used to generate the first URL to try: 
        // file://$HOME/.brooklyn/repository/BrooklynNode/0.6.0-SNAPSHOT/brooklyn-dist-0.6.0-SNAPSHOT-dist.tar.gz
        DownloadResolver resolver = Entities.newDownloader(this, ImmutableMap.of("filename", "brooklyn-dist-"+getVersion()+"-dist.tar.gz"));
        List<String> urls = resolver.getTargets();
        String saveAs = resolver.getFilename();
        setExpandedInstallDir(getInstallDir()+"/"+resolver.getUnpackedDirectoryName(format("brooklyn-%s", getVersion())));
        
        newScript("createInstallDir")
                .body.append("mkdir -p "+getInstallDir())
                .failOnNonZeroResultCode()
                .execute();

        List<String> commands = Lists.newArrayList();
        if (uploadUrl != null) {
            // Only upload if not already installed
            boolean exists = newScript("checkIfInstalled")
                    .body.append("cd "+getInstallDir(), "test -f BROOKLYN")
                    .execute() == 0;
            if (!exists) {
                InputStream distroStream = resource.getResourceFromUrl(uploadUrl);
                getMachine().copyTo(distroStream, getInstallDir()+"/"+saveAs);
            }
        } else {
            commands.addAll(BashCommands.commandsToDownloadUrlsAs(urls, saveAs));
        }
        commands.add(BashCommands.INSTALL_TAR);
        commands.add("tar xzfv " + saveAs);
        
        newScript(INSTALLING).
                failOnNonZeroResultCode().
                body.append(commands).execute();
    }

    @Override
    public void customize() {
        newScript(CUSTOMIZING)
                .failOnNonZeroResultCode()
                .body.append(
                        // workaround for AMP distribution placing everything in the root of this archive, but
                        // brooklyn distribution placing everything in a subdirectory: check to see if subdirectory
                        // with expect name exists; symlink to same directory if it doesn't
                        format("[ -d %1$s/brooklyn-%2$s ] || ln -s . %1$s/brooklyn-%2$s", getInstallDir(), getVersion()),
                        format("cp -R %s/brooklyn-%s/{bin,conf} .", getInstallDir(), getVersion()),
                        "mkdir -p ./lib/")
                .execute();
        
        SshMachineLocation machine = getMachine();
        BrooklynNode entity = getEntity();
        
        String brooklynGlobalPropertiesRemotePath = entity.getConfig(BrooklynNode.BROOKLYN_GLOBAL_PROPERTIES_REMOTE_PATH);
        String brooklynGlobalPropertiesContents = entity.getConfig(BrooklynNode.BROOKLYN_GLOBAL_PROPERTIES_CONTENTS);
        String brooklynGlobalPropertiesUri = entity.getConfig(BrooklynNode.BROOKLYN_GLOBAL_PROPERTIES_URI);

        String brooklynLocalPropertiesRemotePath = processTemplateContents(entity.getConfig(BrooklynNode.BROOKLYN_LOCAL_PROPERTIES_REMOTE_PATH));
        String brooklynLocalPropertiesContents = entity.getConfig(BrooklynNode.BROOKLYN_LOCAL_PROPERTIES_CONTENTS);
        String brooklynLocalPropertiesUri = entity.getConfig(BrooklynNode.BROOKLYN_LOCAL_PROPERTIES_URI);

        String brooklynCatalogRemotePath = entity.getConfig(BrooklynNode.BROOKLYN_CATALOG_REMOTE_PATH);
        String brooklynCatalogContents = entity.getConfig(BrooklynNode.BROOKLYN_CATALOG_CONTENTS);
        String brooklynCatalogUri = entity.getConfig(BrooklynNode.BROOKLYN_CATALOG_URI);

        // Override the ~/.brooklyn/brooklyn.properties if required
        if (brooklynGlobalPropertiesContents != null || brooklynGlobalPropertiesUri != null) {
            uploadFileContents(brooklynGlobalPropertiesContents, brooklynGlobalPropertiesUri, brooklynGlobalPropertiesRemotePath);
        }
        
        // Upload a local-brooklyn.properties if required
        if (brooklynLocalPropertiesContents != null || brooklynLocalPropertiesUri != null) {
            uploadFileContents(brooklynLocalPropertiesContents, brooklynLocalPropertiesUri, brooklynLocalPropertiesRemotePath);
        }

        // Override the ~/.brooklyn/catalog.xml if required
        if (brooklynCatalogContents != null || brooklynCatalogUri != null) {
            uploadFileContents(brooklynCatalogContents, brooklynCatalogUri, brooklynCatalogRemotePath);
        }

        // Copy additional resources to the server
        for (Map.Entry<String,String> entry : getEntity().getAttribute(BrooklynNode.COPY_TO_RUNDIR).entrySet()) {
            Map<String, String> substitutions = ImmutableMap.of("RUN", getRunDir());
            String localResource = entry.getKey();
            String remotePath = entry.getValue();
            String resolvedRemotePath = remotePath;
            for (Map.Entry<String,String> substitution : substitutions.entrySet()) {
                String key = substitution.getKey();
                String val = substitution.getValue();
                resolvedRemotePath = resolvedRemotePath.replace("${"+key+"}", val).replace("$"+key, val);
            }
            machine.copyTo(MutableMap.of("permissions", "0600"), resource.getResourceFromUrl(localResource), resolvedRemotePath);
        }

        for (String entry : getEntity().getClasspath()) {
            // If a local folder, then create archive from contents first
            if (Urls.isDirectory(entry)) {
                File jarFile = ArchiveBuilder.jar().add(entry).create();
                entry = jarFile.getAbsolutePath();
            }

            // Determine filename
            String destFile = entry.contains("?") ? entry.substring(0, entry.indexOf('?')) : entry;
            destFile = destFile.substring(destFile.lastIndexOf('/') + 1);

            ArchiveUtils.deploy(MutableMap.<String, Object>of(), entry, machine, getRunDir(), Os.mergePaths(getRunDir(), "lib"), destFile);
        }
    }

    @Override
    public void launch() {
        String app = getEntity().getAttribute(BrooklynNode.APP);
        String locations = getEntity().getAttribute(BrooklynNode.LOCATIONS);
        Integer httpPort = getEntity().getAttribute(BrooklynNode.HTTP_PORT);
        boolean hasLocalBrooklynProperties = getEntity().getConfig(BrooklynNode.BROOKLYN_LOCAL_PROPERTIES_CONTENTS) != null || getEntity().getConfig(BrooklynNode.BROOKLYN_LOCAL_PROPERTIES_URI) != null;
        String localBrooklynPropertiesPath = processTemplateContents(getEntity().getConfig(BrooklynNode.BROOKLYN_LOCAL_PROPERTIES_REMOTE_PATH));
        String bindAddress = getEntity().getAttribute(BrooklynNode.WEB_CONSOLE_BIND_ADDRESS);

        String cmd = "./bin/brooklyn launch";
        if (app != null) {
            cmd += " --app "+app;
        }
        if (locations != null) {
            cmd += " --locations "+locations;
        }
        if (hasLocalBrooklynProperties) {
            cmd += " --localBrooklynProperties "+localBrooklynPropertiesPath;
        }
        if (getEntity().isHttpProtocolEnabled("http")) {
            Networking.checkPortsValid(ImmutableMap.of("httpPort", httpPort));
            cmd += " --port "+httpPort;
        } else if (getEntity().getEnabledHttpProtocols().isEmpty()) {
            cmd += " --noConsole";
        } else {
            throw new IllegalStateException("Unsupported http protocol: "+getEntity().getEnabledHttpProtocols());
        }
        if (Strings.isNonEmpty(bindAddress)) {
            cmd += " --bindAddress "+bindAddress;
        }
        if (getEntity().getAttribute(BrooklynNode.NO_WEB_CONSOLE_AUTHENTICATION)) {
            cmd += " --noConsoleSecurity";
        }
        if (getEntity().getConfig(BrooklynNode.NO_SHUTDOWN_ON_EXIT)) {
            cmd += " --noShutdownOnExit ";
        }
        cmd += format(" >> %s/console 2>&1 </dev/null &", getRunDir());
        
        log.info("Starting brooklyn on {} using command {}", getMachine(), cmd);
        
        // relies on brooklyn script creating pid file
        newScript(ImmutableMap.of("usePidFile", false), LAUNCHING).
            body.append(
                format("export BROOKLYN_CLASSPATH=%s", getRunDir()+"/lib/\"*\""),
                format("export BROOKLYN_HOME=%s", getBrooklynHome()),
                format(cmd)
        ).execute();
    }

    @Override
    public boolean isRunning() {
        Map<String,String> flags = ImmutableMap.of("usePidFile", getPidFile());
        int result = newScript(flags, CHECK_RUNNING).execute();
        return result == 0;
    }

    @Override
    public void stop() {
        Map<String,String> flags = ImmutableMap.of("usePidFile", getPidFile());
        newScript(flags, STOPPING).execute();
    }

    @Override
    public void kill() {
        Map<String,String> flags = ImmutableMap.of("usePidFile", getPidFile());
        newScript(flags, KILLING).execute();
    }
    
    @Override
    public Map<String, String> getShellEnvironment() {
        Map<String, String> orig = super.getShellEnvironment();
        String origClasspath = orig.get("CLASSPATH");
        String newClasspath = (origClasspath == null ? "" : origClasspath+":") + 
                getRunDir()+"/conf/" + ":" +
                getRunDir()+"/lib/\"*\"";
        Map<String,String> results = new LinkedHashMap<String,String>();
        results.putAll(orig);
        results.put("BROOKLYN_CLASSPATH", newClasspath);
        results.put("BROOKLYN_HOME", getBrooklynHome());
        results.put("RUN", getRunDir());
        return results;
    }
    
    private void uploadFileContents(String contents, String alternativeUri, String remotePath) {
        checkNotNull(remotePath, "remotePath");
        SshMachineLocation machine = getMachine();
        String tempRemotePath = String.format("%s/upload.tmp", getRunDir());

        if (contents != null) {
            machine.copyTo(new ByteArrayInputStream(contents.getBytes()), tempRemotePath);
        } else if (alternativeUri != null) {
            InputStream propertiesStream = resource.getResourceFromUrl(alternativeUri);
            machine.copyTo(propertiesStream, tempRemotePath);
        } else {
            throw new IllegalStateException("No contents supplied for file "+remotePath);
        }
        newScript(CUSTOMIZING)
                .failOnNonZeroResultCode()
                .body.append(
                        format("mkdir -p %s", remotePath.subSequence(0, remotePath.lastIndexOf("/"))),
                        format("cp -p %s %s", tempRemotePath, remotePath),
                        format("rm -f %s", tempRemotePath))
                .execute();
    }
}
