package brooklyn.policy.loadbalancing;

import static org.testng.Assert.assertEquals;

import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import brooklyn.entity.Entity;
import brooklyn.entity.Group;
import brooklyn.entity.basic.ApplicationBuilder;
import brooklyn.entity.basic.DynamicGroup;
import brooklyn.entity.basic.Entities;
import brooklyn.entity.proxying.EntitySpec;
import brooklyn.location.basic.SimulatedLocation;
import brooklyn.test.Asserts;
import brooklyn.test.entity.TestApplication;
import brooklyn.util.collections.MutableMap;

import com.google.common.base.Predicate;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

public class ItemsInContainersGroupTest {

    // all tests are 20ms or less, but use a big timeout just in case very slow machine!
    private static final long TIMEOUT_MS = 15000;
    
    private TestApplication app;
    private SimulatedLocation loc;
    private Group containerGroup;
    private ItemsInContainersGroup itemGroup;

    @BeforeMethod(alwaysRun=true)
    public void setUp() throws Exception {
        loc = new SimulatedLocation(MutableMap.of("name", "loc"));
        
        app = ApplicationBuilder.newManagedApp(TestApplication.class);
        containerGroup = app.createAndManageChild(EntitySpec.create(DynamicGroup.class)
                .displayName("containerGroup")
                .configure(DynamicGroup.ENTITY_FILTER, new Predicate<Entity>() {
                    public boolean apply(Entity input) {
                        return input instanceof MockContainerEntity && 
                                input.getConfig(MockContainerEntity.MOCK_MEMBERSHIP) == "ingroup";
                    }}));
        itemGroup = app.createAndManageChild(EntitySpec.create(ItemsInContainersGroup.class)
                .displayName("itemGroup"));
        itemGroup.setContainers(containerGroup);
        
        app.start(ImmutableList.of(loc));
    }

    @AfterMethod(alwaysRun=true)
    public void tearDown() throws Exception {
        if (app != null) Entities.destroyAll(app.getManagementContext());
    }

    @Test
    public void testSimpleMembership() throws Exception {
        MockContainerEntity containerIn = newContainer(app, "A", "ingroup");
        MockItemEntity item1 = newItem(app, containerIn, "1");
        MockItemEntity item2 = newItem(app, containerIn, "2");
        
        assertItemsEventually(item1, item2);
    }

    @Test
    public void testFilterIsAppliedToItems() throws Exception {
        itemGroup.stop();
        Entities.unmanage(itemGroup);
        
        itemGroup = app.createAndManageChild(EntitySpec.create(ItemsInContainersGroup.class)
                .displayName("itemGroupWithDispName2")
                .configure(ItemsInContainersGroup.ITEM_FILTER, new Predicate<Entity>() {
                    public boolean apply(Entity input) {
                        return "2".equals(input.getDisplayName());
                    }}));
        itemGroup.setContainers(containerGroup);
        
        MockContainerEntity containerIn = newContainer(app, "A", "ingroup");
        MockItemEntity item1 = newItem(app, containerIn, "1");
        MockItemEntity item2 = newItem(app, containerIn, "2");
        
        assertItemsEventually(item2); // does not include item1
    }

    @Test
    public void testItemsInOtherContainersIgnored() throws Exception {
        MockContainerEntity containerOut = newContainer(app, "A", "outgroup");
        MockItemEntity item1 = newItem(app, containerOut, "1");
        
        assertItemsEventually();
    }
    
    @Test
    public void testItemMovedInIsAdded() throws Exception {
        MockContainerEntity containerIn = newContainer(app, "A", "ingroup");
        MockContainerEntity containerOut = newContainer(app, "A", "outgroup");
        MockItemEntity item1 = newItem(app, containerOut, "1");
        item1.move(containerIn);
        
        assertItemsEventually(item1);
    }

    @Test
    public void testItemMovedOutIsRemoved() throws Exception {
        MockContainerEntity containerIn = newContainer(app, "A", "ingroup");
        MockContainerEntity containerOut = newContainer(app, "A", "outgroup");
        MockItemEntity item1 = newItem(app, containerIn, "1");
        assertItemsEventually(item1);
        
        item1.move(containerOut);
        assertItemsEventually();
    }

    // Failed in build #2197 on cloudbees; 
    // possible just a timeout, time was set at 5s there, ran for 19 iterations;
    // since it only takes 5 *millis* locally I'm (Alex) slightly concerned there may
    // be a real problem of racing on startup, but see if we see it again, with timeout 15s...
    @Test
    public void testItemUnmanagedIsRemoved() throws Exception {
        MockContainerEntity containerIn = newContainer(app, "A", "ingroup");
        MockItemEntity item1 = newItem(app, containerIn, "1");
        assertItemsEventually(item1);
        
        Entities.unmanage(item1);
        assertItemsEventually();
    }

    // TODO How to test this? Will it be used?
    // Adding a new container then adding items to it is tested in many other methods.
    @Test(enabled=false)
    public void testContainerAddedWillAddItsItems() throws Exception {
    }

    @Test
    public void testContainerRemovedWillRemoveItsItems() throws Exception {
        MockContainerEntity containerA = newContainer(app, "A", "ingroup");
        MockItemEntity item1 = newItem(app, containerA, "1");
        assertItemsEventually(item1);
        
        Entities.unmanage(containerA);
        assertItemsEventually();
    }

    private void assertItemsEventually(final MockItemEntity... expected) {
        Asserts.succeedsEventually(MutableMap.of("timeout", TIMEOUT_MS), new Runnable() {
            public void run() {
                assertEquals(ImmutableSet.copyOf(itemGroup.getMembers()), ImmutableSet.copyOf(expected));
            }});
    }   
     
    private MockContainerEntity newContainer(TestApplication app, String name, String membership) {
        MockContainerEntity container = app.createAndManageChild(EntitySpec.create(MockContainerEntity.class)
                        .displayName(name)
                        .configure(MockContainerEntity.MOCK_MEMBERSHIP, membership));
        container.start(ImmutableList.of(loc));
        return container;
    }
    
    private static MockItemEntity newItem(TestApplication app, MockContainerEntity container, String name) {
        MockItemEntity item = app.createAndManageChild(EntitySpec.create(MockItemEntity.class)
                .displayName(name));
        item.move(container);
        return item;
    }
}
