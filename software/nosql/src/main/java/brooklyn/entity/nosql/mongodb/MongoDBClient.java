package brooklyn.entity.nosql.mongodb;

import java.util.List;
import java.util.Map;

import brooklyn.config.ConfigKey;
import brooklyn.entity.annotation.Effector;
import brooklyn.entity.annotation.EffectorParam;
import brooklyn.entity.basic.ConfigKeys;
import brooklyn.entity.basic.MethodEffector;
import brooklyn.entity.nosql.mongodb.sharding.MongoDBShardedDeployment;
import brooklyn.entity.proxying.ImplementedBy;
import brooklyn.util.flags.SetFromFlag;

import com.google.common.reflect.TypeToken;

@ImplementedBy(MongoDBClientImpl.class)
public interface MongoDBClient extends AbstractMongoDBServer {
    
    MethodEffector<Void> RUN_SCRIPT = new MethodEffector<Void>(MongoDBClient.class, "runScript");
    
    @SuppressWarnings("serial")
    @SetFromFlag("startupJsScripts")
    ConfigKey<List<String>> STARTUP_JS_SCRIPTS = ConfigKeys.newConfigKey(
            new TypeToken<List<String>>(){}, "mongodb.client.startupJsScripts", 
                "List of scripts defined in mongodb.client.scripts to be run on startup");
    
    @SuppressWarnings("serial")
    @SetFromFlag("scripts")
    ConfigKey<Map<String, String>> JS_SCRIPTS = ConfigKeys.newConfigKey(
            new TypeToken<Map<String, String>>(){}, "mongodb.client.scripts", "List of javascript scripts to be copied "
                    + "to the server. These scripts can be run using the runScript effector");
    
    @SetFromFlag("shardedDeployment")
    ConfigKey<MongoDBShardedDeployment> SHARDED_DEPLOYMENT = ConfigKeys.newConfigKey(MongoDBShardedDeployment.class, 
            "mongodb.client.shardeddeployment", "Sharded deployment that the client will use to run scripts. "
                    + "If both SERVER and SHARDED_DEPLOYMENT are specified, SERVER will be used");
    
    @SetFromFlag("server")
    ConfigKey<AbstractMongoDBServer> SERVER = ConfigKeys.newConfigKey(AbstractMongoDBServer.class, 
            "mongodb.client.server", "MongoDBServer that the client will use to run scripts. "
                    + "If both SERVER and SHARDED_DEPLOYMENT are specified, SERVER will be used");
    
    @Effector(description="Runs one of the scripts defined in mongodb.client.scripts")
    void runScript(@EffectorParam(name="preStart", description="use this to create parameters that can be used by the script, e.g.:<p><code>var loopCount = 10</code>") String preStart,
            @EffectorParam(name="scriptName", description="Name of the script as defined in mongodb.client.scripts") String scriptName);
}
