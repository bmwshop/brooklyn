package brooklyn.entity.rebind;

import static org.testng.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import brooklyn.entity.Entity;
import brooklyn.entity.basic.ApplicationBuilder;
import brooklyn.entity.basic.Entities;
import brooklyn.entity.proxying.EntitySpec;
import brooklyn.entity.rebind.dto.MementosGenerators;
import brooklyn.entity.trait.Identifiable;
import brooklyn.management.ManagementContext;
import brooklyn.mementos.EntityMemento;
import brooklyn.mementos.TreeNode;
import brooklyn.test.entity.TestApplication;
import brooklyn.test.entity.TestEntity;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Maps;

public class RebindManagerSorterTest {

    private TestApplication app;
    private ManagementContext managementContext;
    private RebindManagerImpl rebindManager;
    
    @BeforeMethod(alwaysRun=true)
    public void setUp() throws Exception {
        app = ApplicationBuilder.newManagedApp(TestApplication.class);
        managementContext = app.getManagementContext();
        rebindManager = (RebindManagerImpl) managementContext.getRebindManager();
    }
    
    @AfterMethod(alwaysRun=true)
    public void tearDown() throws Exception {
        if (app != null) Entities.destroyAll(app.getManagementContext());
    }

    @Test
    public void testSortOrder() throws Exception {
        TestEntity e1a = app.createAndManageChild(EntitySpec.create(TestEntity.class));
        TestEntity e1b = e1a.createAndManageChild(EntitySpec.create(TestEntity.class));
        
        // In reverse order
        Map<String, EntityMemento> nodes = toMementos(ImmutableList.of(e1b, e1a, app));
        Map<String, EntityMemento> sortedNodes = rebindManager.sortParentFirst(nodes);
        assertOrder(sortedNodes, ImmutableList.of(app, e1a, e1b));

        // already in correct order
        Map<String, EntityMemento> nodes2 = toMementos(ImmutableList.of(app, e1a, e1b));
        Map<String, EntityMemento> sortedNodes2 = rebindManager.sortParentFirst(nodes);
        assertOrder(sortedNodes2, ImmutableList.of(app, e1a, e1b));
    }
    
    @Test
    public void testSortOrderMultipleBranches() throws Exception {
        TestEntity e1a = app.createAndManageChild(EntitySpec.create(TestEntity.class));
        TestEntity e1b = e1a.createAndManageChild(EntitySpec.create(TestEntity.class));
        TestEntity e2a = app.createAndManageChild(EntitySpec.create(TestEntity.class));
        TestEntity e2b = e2a.createAndManageChild(EntitySpec.create(TestEntity.class));
        
        Map<String, EntityMemento> nodes = toMementos(ImmutableList.of(e2b, e1b, e2a, e1a, app));
        Map<String, EntityMemento> sortedNodes = rebindManager.sortParentFirst(nodes);
        assertOrder(sortedNodes, ImmutableList.of(app, e1a, e1b), ImmutableList.of(app, e2a, e2b));
    }
    
    @Test
    public void testSortOrderMultipleApps() throws Exception {
        TestApplication app2 = ApplicationBuilder.newManagedApp(TestApplication.class);

        TestEntity e1a = app.createAndManageChild(EntitySpec.create(TestEntity.class));
        TestEntity e1b = e1a.createAndManageChild(EntitySpec.create(TestEntity.class));
        TestEntity e2a = app2.createAndManageChild(EntitySpec.create(TestEntity.class));
        TestEntity e2b = e2a.createAndManageChild(EntitySpec.create(TestEntity.class));
        
        Map<String, EntityMemento> nodes = toMementos(ImmutableList.of(e2b, e1b, e2a, e1a, app, app2));
        Map<String, EntityMemento> sortedNodes = rebindManager.sortParentFirst(nodes);
        assertOrder(sortedNodes, ImmutableList.of(app, e1a, e1b), ImmutableList.of(app2, e2a, e2b));
    }

    @Test
    public void testSortOrderWhenNodesMissing() throws Exception {
        TestEntity e1a = app.createAndManageChild(EntitySpec.create(TestEntity.class));
        TestEntity e1b = e1a.createAndManageChild(EntitySpec.create(TestEntity.class));
        
        Map<String, EntityMemento> nodes = toMementos(ImmutableList.of(e1b, e1a));
        Map<String, EntityMemento> sortedNodes = rebindManager.sortParentFirst(nodes);
        assertOrder(sortedNodes, ImmutableList.of(e1a, e1b));
    }
    
    private void assertOrder(Map<String, ? extends TreeNode> nodes, Iterable<? extends Identifiable>... orders) {
        List<String> actualOrder = ImmutableList.copyOf(nodes.keySet());
        String errmsg = "actualOrder="+actualOrder+"; requiredSubOrderings="+Arrays.toString(orders);
        for (Iterable<? extends Identifiable> order : orders) {
            int prevIndex = -1;
            for (Identifiable o : order) {
                int index = actualOrder.indexOf(o.getId());
                assertTrue(index > prevIndex, errmsg);
                prevIndex = index;
            }
        }
    }

    private Map<String, EntityMemento> toMementos(Iterable<? extends Entity> entities) {
        Map<String, EntityMemento> result = Maps.newLinkedHashMap();
        for (Entity entity : entities) {
            result.put(entity.getId(), MementosGenerators.newEntityMemento(entity));
        }
        return result;
    }
}
