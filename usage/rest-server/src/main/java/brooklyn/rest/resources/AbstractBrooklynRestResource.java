package brooklyn.rest.resources;

import io.brooklyn.camp.CampPlatform;
import io.brooklyn.camp.brooklyn.BrooklynCampConstants;

import javax.servlet.ServletContext;
import javax.ws.rs.core.Context;

import org.codehaus.jackson.map.ObjectMapper;

import brooklyn.config.BrooklynServiceAttributes;
import brooklyn.management.ManagementContext;
import brooklyn.rest.util.BrooklynRestResourceUtils;
import brooklyn.rest.util.WebResourceUtils;

import com.google.common.annotations.VisibleForTesting;

public abstract class AbstractBrooklynRestResource {

    @VisibleForTesting
    public static final String DATE_FORMAT = "yyyy-MM-dd'T'HH:mm:ssZ";
    
    // can be injected by jersey when ManagementContext in not injected manually
    // (seems there is no way to make this optional so note it _must_ be injected;
    // most of the time that happens for free, but with test framework it doesn't,
    // so we have set up a NullServletContextProvider in our tests) 
    @Context ServletContext servletContext;
    
    private ManagementContext managementContext;
    private BrooklynRestResourceUtils brooklynRestResourceUtils;
    private final ObjectMapper mapper = new ObjectMapper();

    public synchronized ManagementContext mgmt() {
        if (managementContext!=null) return managementContext;
        managementContext = (ManagementContext) servletContext.getAttribute(BrooklynServiceAttributes.BROOKLYN_MANAGEMENT_CONTEXT);
        if (managementContext!=null) return managementContext;
        
        throw new IllegalStateException("ManagementContext not supplied for Brooklyn Jersey Resource "+this);
    }
    
    public void injectManagementContext(ManagementContext managementContext) {
        if (this.managementContext!=null) {
            if (this.managementContext.equals(managementContext)) return;
            throw new IllegalStateException("ManagementContext cannot be changed: specified twice for Brooklyn Jersey Resource "+this);
        }
        this.managementContext = managementContext;
    }

    public synchronized BrooklynRestResourceUtils brooklyn() {
        if (brooklynRestResourceUtils!=null) return brooklynRestResourceUtils;
        brooklynRestResourceUtils = new BrooklynRestResourceUtils(mgmt());
        return brooklynRestResourceUtils;
    }
    
    protected ObjectMapper mapper() {
        return mapper;
    }

    /** returns an object which jersey will handle nicely, converting to json,
     * sometimes wrapping in quotes if needed (for outermost json return types) */ 
    protected Object getValueForDisplay(Object value, boolean preferJson, boolean isJerseyReturnValue) {
        return WebResourceUtils.getValueForDisplay(value, preferJson, isJerseyReturnValue);
    }

    protected CampPlatform camp() {
        CampPlatform camp = mgmt().getConfig().getConfig(BrooklynCampConstants.CAMP_PLATFORM);
        if (camp!=null) return camp;
        throw new IllegalStateException("CAMP platform server not enabled");
    }
    
}
