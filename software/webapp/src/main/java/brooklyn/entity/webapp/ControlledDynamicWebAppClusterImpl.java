package brooklyn.entity.webapp;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import brooklyn.enricher.Enrichers;
import brooklyn.entity.Entity;
import brooklyn.entity.annotation.Effector;
import brooklyn.entity.annotation.EffectorParam;
import brooklyn.entity.basic.Attributes;
import brooklyn.entity.basic.ConfigurableEntityFactory;
import brooklyn.entity.basic.DynamicGroupImpl;
import brooklyn.entity.basic.Entities;
import brooklyn.entity.basic.EntityPredicates;
import brooklyn.entity.basic.Lifecycle;
import brooklyn.entity.proxy.LoadBalancer;
import brooklyn.entity.proxy.nginx.NginxController;
import brooklyn.entity.proxying.EntitySpec;
import brooklyn.entity.trait.Startable;
import brooklyn.entity.trait.StartableMethods;
import brooklyn.entity.webapp.jboss.JBoss7Server;
import brooklyn.event.SensorEvent;
import brooklyn.event.SensorEventListener;
import brooklyn.event.feed.ConfigToAttributes;
import brooklyn.location.Location;
import brooklyn.util.collections.MutableList;
import brooklyn.util.collections.MutableMap;
import brooklyn.util.exceptions.Exceptions;

import com.google.common.base.Predicate;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;

import brooklyn.entity.webapp.FilenameToWebContextMapper;
import static com.google.common.base.Preconditions.checkNotNull;

public class ControlledDynamicWebAppClusterImpl extends DynamicGroupImpl implements ControlledDynamicWebAppCluster {

    public static final Logger log = LoggerFactory.getLogger(ControlledDynamicWebAppClusterImpl.class);
    private FilenameToWebContextMapper filenameToWebContextMapper = new FilenameToWebContextMapper();

    public ControlledDynamicWebAppClusterImpl() {
        this(MutableMap.of(), null);
    }
    
    public ControlledDynamicWebAppClusterImpl(Map flags) {
        this(flags, null);
    }
    
    public ControlledDynamicWebAppClusterImpl(Entity parent) {
        this(MutableMap.of(), parent);
    }
    
    public ControlledDynamicWebAppClusterImpl(Map flags, Entity parent) {
        super(flags, parent);
        setAttribute(SERVICE_UP, false);
    }

    @Override
    public void init() {
        super.init();
        
        ConfigToAttributes.apply(this, FACTORY);
        ConfigToAttributes.apply(this, MEMBER_SPEC);
        ConfigToAttributes.apply(this, CONTROLLER);
        ConfigToAttributes.apply(this, CONTROLLER_SPEC);
        ConfigToAttributes.apply(this, WEB_CLUSTER_SPEC);
        
        ConfigurableEntityFactory<? extends WebAppService> webServerFactory = getAttribute(FACTORY);
        EntitySpec<? extends WebAppService> webServerSpec = getAttribute(MEMBER_SPEC);
        if (webServerFactory == null && webServerSpec == null) {
            log.debug("creating default web server spec for {}", this);
            webServerSpec = EntitySpec.create(JBoss7Server.class);
            setAttribute(MEMBER_SPEC, webServerSpec);
        }
        
        log.debug("creating cluster child for {}", this);
        // Note relies on initial_size being inherited by DynamicWebAppCluster, because key id is identical
        EntitySpec<? extends DynamicWebAppCluster> webClusterSpec = getAttribute(WEB_CLUSTER_SPEC);
        Map<String,Object> webClusterFlags;
        if (webServerSpec != null) {
            webClusterFlags = MutableMap.<String,Object>of("memberSpec", webServerSpec);
        } else {
            webClusterFlags = MutableMap.<String,Object>of("factory", webServerFactory);
        }
        if (webClusterSpec == null) {
            log.debug("creating default web cluster spec for {}", this);
            webClusterSpec = EntitySpec.create(DynamicWebAppCluster.class);
        }
        boolean hasMemberSpec = webClusterSpec.getConfig().containsKey(DynamicWebAppCluster.MEMBER_SPEC) || webClusterSpec.getFlags().containsKey("memberSpec");
        boolean hasMemberFactory = webClusterSpec.getConfig().containsKey(DynamicWebAppCluster.FACTORY) || webClusterSpec.getFlags().containsKey("factory");
        if (!(hasMemberSpec || hasMemberFactory)) {
            webClusterSpec.configure(webClusterFlags);
        } else {
            log.warn("In {}, not setting cluster's {} because already set on webClusterSpec", new Object[] {this, webClusterFlags.keySet()});
        }
        setAttribute(WEB_CLUSTER_SPEC, webClusterSpec);
        
        DynamicWebAppCluster cluster = addChild(webClusterSpec);
        if (Entities.isManaged(this)) Entities.manage(cluster);
        setAttribute(CLUSTER, cluster);
        setEntityFilter(EntityPredicates.isMemberOf(cluster));
        subscribe(cluster, DynamicWebAppCluster.GROUP_MEMBERS, new SensorEventListener<Object>() {
            @Override public void onEvent(SensorEvent<Object> event) {
                // TODO inefficient impl; also worth extracting this into a mixin of some sort.
                rescanEntities();
            }});
        
        LoadBalancer controller = getAttribute(CONTROLLER);
        if (controller == null) {
            EntitySpec<? extends LoadBalancer> controllerSpec = getAttribute(CONTROLLER_SPEC);
            if (controllerSpec == null) {
                log.debug("creating controller using default spec for {}", this);
                controllerSpec = EntitySpec.create(NginxController.class);
                setAttribute(CONTROLLER_SPEC, controllerSpec);
            } else {
                log.debug("creating controller using custom spec for {}", this);
            }
            controller = addChild(controllerSpec);
            if (Entities.isManaged(this)) Entities.manage(controller);
            setAttribute(CONTROLLER, controller);
        }
    }
    
    public LoadBalancer getController() {
        return getAttribute(CONTROLLER);
    }

    public synchronized ConfigurableEntityFactory<WebAppService> getFactory() {
        return (ConfigurableEntityFactory<WebAppService>) getAttribute(FACTORY);
    }
    
    // TODO convert to an entity reference which is serializable
    public synchronized DynamicWebAppCluster getCluster() {
        return getAttribute(CLUSTER);
    }
    
    public void start(Collection<? extends Location> locations) {
        setAttribute(Attributes.SERVICE_STATE, Lifecycle.STARTING);
        try {
            if (isLegacyConstruction()) {
                init();
            }
            
            if (locations.isEmpty()) locations = this.getLocations();
            addLocations(locations);
            
            LoadBalancer loadBalancer = getController();
            loadBalancer.bind(MutableMap.of("serverPool", getCluster()));
    
            List<Entity> childrenToStart = MutableList.<Entity>of(getCluster());
            // Set controller as child of cluster, if it does not already have a parent
            if (getController().getParent() == null) {
                addChild(getController());
            }
            
            // And only start controller if we are parent
            if (this.equals(getController().getParent())) childrenToStart.add(getController());
        
            Entities.invokeEffectorList(this, childrenToStart, Startable.START, ImmutableMap.of("locations", locations))
                .get();
            
            // wait for everything to start, then update controller, to ensure it is up to date
            // (will happen asynchronously as members come online, but we want to force it to happen)
            getController().update();
            
            connectSensors();
            
        } catch (InterruptedException e) {
            setAttribute(Attributes.SERVICE_STATE, Lifecycle.ON_FIRE);
            throw Exceptions.propagate(e);
        } catch (ExecutionException e) {
            setAttribute(Attributes.SERVICE_STATE, Lifecycle.ON_FIRE);
            throw Exceptions.propagate(e);
        }

        setAttribute(SERVICE_UP, getCluster().getAttribute(SERVICE_UP));
        setAttribute(SERVICE_STATE, Lifecycle.RUNNING);
    }

    @Override
    public void stop() {
        setAttribute(SERVICE_STATE, Lifecycle.STOPPING);
        try {
            List<Startable> tostop = Lists.newArrayList();
            if (this.equals(getController().getParent())) tostop.add(getController());
            tostop.add(getCluster());
            
            StartableMethods.stopSequentially(tostop);
    
            clearLocations();
            setAttribute(SERVICE_UP, false);

            setAttribute(SERVICE_STATE, Lifecycle.STOPPED);
            setAttribute(SERVICE_UP, false);
        } catch (Exception e) {
            setAttribute(SERVICE_STATE, Lifecycle.ON_FIRE);
            throw Exceptions.propagate(e);
        }
    }

    @Override
    public void restart() {
        // TODO prod the entities themselves to restart, instead?
        Collection<Location> locations = Lists.newArrayList(getLocations());

        stop();
        start(locations);
    }
    
    void connectSensors() {
        addEnricher(Enrichers.builder()
                .propagatingAllBut(SERVICE_STATE, SERVICE_UP, ROOT_URL, GROUP_MEMBERS, GROUP_SIZE)
                .from(getCluster())
                .build());
        addEnricher(Enrichers.builder()
                // include hostname and address of controller (need both in case hostname only resolves to internal/private ip)
                .propagating(LoadBalancer.HOSTNAME, Attributes.ADDRESS, SERVICE_UP, ROOT_URL)
                .from(getController())
                .build());
    }

    public Integer resize(Integer desiredSize) {
        return getCluster().resize(desiredSize);
    }

    @Override
    public String replaceMember(String memberId) {
        return getCluster().replaceMember(memberId);
    }
    
    /**
     * @return the current size of the group.
     */
    @Override
    public Integer getCurrentSize() {
        return getCluster().getCurrentSize();
    }

    private Entity findChildOrNull(Predicate<? super Entity> predicate) {
        for (Entity contender : getChildren()) {
            if (predicate.apply(contender)) return contender;
        }
        return null;
    }
    /**
     * Deploys the given artifact, from a source URL, to all members of the cluster
     * See {@link FileNameToContextMappingTest} for definitive examples!
     * 
     * @param url  where to get the war, as a URL, either classpath://xxx or file:///home/xxx or http(s)...
     * @param targetName  where to tell the server to serve the WAR, see above
     */
    @Effector(description="Deploys the given artifact, from a source URL, to a given deployment filename/context")
    public void deploy(
            @EffectorParam(name="url", description="URL of WAR file") String url, 
            @EffectorParam(name="targetName", description="context path where WAR should be deployed (/ for ROOT)") String targetName) {
        try {
            checkNotNull(url, "url");
            checkNotNull(targetName, "targetName");
            // actually deploy
            List <Entity> cluster_members = Lists.newArrayList();
//            cluster_members.addAll((Collection<? extends Entity>) Iterables.filter(getCluster().getChildren(), JavaWebAppSoftwareProcess.class)); 
            for (JavaWebAppSoftwareProcess member : Iterables.filter(getCluster().getChildren(), JavaWebAppSoftwareProcess.class)) {
            	Lifecycle serviceState = member.getAttribute(SERVICE_STATE);
            	if (serviceState == Lifecycle.RUNNING) {
            	  cluster_members.add(member);
            	}
            }            
            Entities.invokeEffectorListWithArgs(this, cluster_members, DEPLOY, url, targetName).get();            
            
            // Update attribute
            Set<String> deployedWars = getAttribute(DEPLOYED_WARS);
            if (deployedWars == null) {
                deployedWars = Sets.newLinkedHashSet();
            }
            deployedWars.add(targetName);
            setAttribute(DEPLOYED_WARS, deployedWars);
        } catch (RuntimeException e) {
            // Log and propagate, so that log says which entity had problems...
            log.warn("Error deploying '"+url+"' to "+targetName+" on "+toString()+"; rethrowing...", e);
            throw Throwables.propagate(e);
        } catch (Throwable th) {
            // Log and propagate, so that log says which entity had problems...
            log.warn("Error undeploying '"+targetName+"' on "+toString()+"; rethrowing...", th);
            throw Throwables.propagate(th);
        }
    }
    /** For the DEPLOYED_WARS to be updated, the input must match the result of the call to deploy */
    @Effector(description="Undeploys the given context/artifact")
    public void undeploy(
            @EffectorParam(name="targetName") String targetName) {
    	
        try {

            List <Entity> cluster_members = Lists.newArrayList();
//            cluster_members.addAll((Collection<? extends Entity>) Iterables.filter(getCluster().getChildren(), JavaWebAppSoftwareProcess.class));
            for (JavaWebAppSoftwareProcess member : Iterables.filter(getCluster().getChildren(), JavaWebAppSoftwareProcess.class)) {
            	Lifecycle serviceState = member.getAttribute(SERVICE_STATE);
            	if (serviceState == Lifecycle.RUNNING) {
            	  cluster_members.add(member);
            	}
            }             
            Entities.invokeEffectorListWithArgs(this, cluster_members, UNDEPLOY, targetName).get(); 
            
       	
            // Update attribute
            Set<String> deployedWars = getAttribute(DEPLOYED_WARS);
            if (deployedWars == null) {
                deployedWars = Sets.newLinkedHashSet();
            }
            deployedWars.remove( filenameToWebContextMapper.convertDeploymentTargetNameToContext(targetName) );
            setAttribute(DEPLOYED_WARS, deployedWars);
        } catch (RuntimeException e) {
            // Log and propagate, so that log says which entity had problems...
            log.warn("Error undeploying '"+targetName+"' on "+toString()+"; rethrowing...", e);
            throw Throwables.propagate(e);
        } catch (Throwable th) {
            // Log and propagate, so that log says which entity had problems...
            log.warn("Error undeploying '"+targetName+"' on "+toString()+"; rethrowing...", th);
            throw Throwables.propagate(th);
        }
    }  
    @Effector(description="Updates the given context/artifact")
    public void update(
    		@EffectorParam(name="url", description="URL of NEW WAR file") String url,
            @EffectorParam(name="targetName") String targetName) {
    	// simple for now
    	undeploy(targetName);
    	deploy(url, targetName);
    
    }    
}
