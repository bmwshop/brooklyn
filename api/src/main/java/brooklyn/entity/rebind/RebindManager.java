package brooklyn.entity.rebind;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import brooklyn.entity.Application;
import brooklyn.mementos.BrooklynMementoPersister;

import com.google.common.annotations.VisibleForTesting;

/**
 * Manages the persisting of brooklyn's state, and recreating that state, e.g. on
 * brooklyn restart.
 * 
 * Users are not expected to implement this class, or to call methods on it directly.
 */
public interface RebindManager {
    
    // FIXME Should we be calling managementContext.getRebindManager().rebind, using a
    // new empty instance of managementContext?
    //
    // Or is that a risky API because you could call it on a non-empty managementContext?
    
    public void setPersister(BrooklynMementoPersister persister);

    @VisibleForTesting
    public BrooklynMementoPersister getPersister();

    public List<Application> rebind() throws IOException;
    
    public List<Application> rebind(ClassLoader classLoader) throws IOException;

    public ChangeListener getChangeListener();

    /**
     * Starts the persisting of state (if persister is set; otherwise will start persisting as soon as
     * persister is set). Until {@link #start()} is called, no data will be persisted but entities can 
     * rebind.
     */
    public void start();

    public void stop();

    @VisibleForTesting
    public void waitForPendingComplete(long timeout, TimeUnit unit) throws InterruptedException, TimeoutException;
}
