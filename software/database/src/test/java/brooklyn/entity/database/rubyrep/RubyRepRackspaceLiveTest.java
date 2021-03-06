package brooklyn.entity.database.rubyrep;

import java.util.Arrays;

import org.testng.annotations.Test;

import brooklyn.entity.database.DatastoreMixins.DatastoreCommon;
import brooklyn.entity.database.postgresql.PostgreSqlIntegrationTest;
import brooklyn.entity.database.postgresql.PostgreSqlNode;
import brooklyn.entity.proxying.EntitySpec;
import brooklyn.location.Location;
import brooklyn.location.basic.PortRanges;
import brooklyn.location.basic.SshMachineLocation;
import brooklyn.util.net.Protocol;
import brooklyn.util.ssh.IptablesCommands;
import brooklyn.util.ssh.IptablesCommands.Chain;
import brooklyn.util.ssh.IptablesCommands.Policy;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

/**
 * The RubyRepRackspaceLiveTest installs RubyRep on various operating systems like Ubuntu, CentOS, Red Hat etc. To make sure that
 * RubyRep and PostgreSql works like expected on these Operating Systems.
 */
public class RubyRepRackspaceLiveTest extends RubyRepIntegrationTest {
    
    @Test(groups = "Live")
    public void test_Debian_6() throws Exception {
        test("Debian 6");
    }

    @Test(groups = "Live")
    public void test_Ubuntu_10_0() throws Exception {
        test("Ubuntu 10.0");
    }

    @Test(groups = "Live")
    public void test_Ubuntu_12_0() throws Exception {
        test("Ubuntu 12.0");
    }

    @Test(groups = "Live")
    public void test_Ubuntu_13() throws Exception {
        test("Ubuntu 13");
    }

    @Test(groups = "Live")
    public void test_CentOS_6() throws Exception {
        test("CentOS 6");
    }

    @Test(groups = "Live")
    public void test_CentOS_5() throws Exception {
        test("CentOS 5");
    }

    @Test(groups = "Live")
    public void test_Fedora() throws Exception {
        test("Fedora ");
    }

    @Test(groups = "Live")
    public void test_Red_Hat_Enterprise_Linux_6() throws Exception {
        test("Red Hat Enterprise Linux 6");
    }

    public void test(String osRegex) throws Exception {
        PostgreSqlNode db1 = tapp.createAndManageChild(EntitySpec.create(PostgreSqlNode.class)
                .configure(DatastoreCommon.CREATION_SCRIPT_CONTENTS, PostgreSqlIntegrationTest.CREATION_SCRIPT)
                .configure(PostgreSqlNode.POSTGRESQL_PORT, PortRanges.fromInteger(9111)));
        PostgreSqlNode db2 = tapp.createAndManageChild(EntitySpec.create(PostgreSqlNode.class)
                .configure(DatastoreCommon.CREATION_SCRIPT_CONTENTS, PostgreSqlIntegrationTest.CREATION_SCRIPT)
                .configure(PostgreSqlNode.POSTGRESQL_PORT, PortRanges.fromInteger(9111)));

        brooklynProperties.put("brooklyn.location.jclouds.rackspace-cloudservers-uk.imageNameRegex", osRegex);
        brooklynProperties.remove("brooklyn.location.jclouds.rackspace-cloudservers-uk.image-id");
        brooklynProperties.remove("brooklyn.location.jclouds.rackspace-cloudservers-uk.imageId");
        brooklynProperties.put("brooklyn.location.jclouds.rackspace-cloudservers-uk.inboundPorts", Arrays.asList(22, 9111));
        Location loc = managementContext.getLocationRegistry().resolve("jclouds:rackspace-cloudservers-uk");
        
        startInLocation(tapp, db1, db2, loc);

        //hack to get the port for mysql open; is the inbounds property not respected on rackspace??
        for (DatastoreCommon node : ImmutableSet.of(db1, db2)) {
            SshMachineLocation l = (SshMachineLocation) node.getLocations().iterator().next();
            l.execCommands("add iptables rule", ImmutableList.of(IptablesCommands.insertIptablesRule(Chain.INPUT, Protocol.TCP, 9111, Policy.ACCEPT)));
        }

        testReplication(db1, db2);
    }
    
    // disable inherited non-live tests
    @Test(enabled = false, groups = "Integration")
    public void test_localhost_mysql() throws Exception {
        super.test_localhost_mysql();
    }

    // disable inherited non-live tests
    @Test(enabled = false, groups = "Integration")
    public void test_localhost_postgres() throws Exception {
        super.test_localhost_postgres();
    }

    // disable inherited non-live tests
    @Test(enabled = false, groups = "Integration")
    public void test_localhost_postgres_mysql() throws Exception {
        super.test_localhost_postgres_mysql();
    }
}
