package io.github.andrexarlay.vulkancompat.rt.world;

import io.github.andrexarlay.vulkancompat.rt.entity.RtSceneInstances;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class RtEntityTlasIntegrationTest {

    @Test
    public void testEntityGeometryManagerAndBlasClassesExist() {
        assertNotNull(RtEntityGeometryManager.class, "RtEntityGeometryManager should exist");
        assertNotNull(RtEntityBlas.class, "RtEntityBlas should exist");
    }

    @Test
    public void testQuadGeometryCreation() {
        RtEntityGeometryManager.QuadGeometry q = new RtEntityGeometryManager.QuadGeometry();
        q.positions[0] = 0.0f;
        q.positions[1] = 0.0f;
        q.positions[2] = 0.0f;
        assertEquals(0.0f, q.positions[0], 1e-5f);
    }

    @Test
    public void testManagerAddAndReset() {
        RtEntityGeometryManager mgr = new RtEntityGeometryManager();
        assertEquals(0, mgr.getQuadCount());
        mgr.addQuad(new RtEntityGeometryManager.QuadGeometry());
        assertEquals(1, mgr.getQuadCount());
        mgr.resetFrame();
        assertEquals(0, mgr.getQuadCount());
    }

    @Test
    public void testEntityCustomIndexEncoding() {
        int customIndex = RtSceneInstances.encodeEntityCustomIndex(0);
        assertTrue(RtSceneInstances.isEntityInstance(customIndex), "Should be recognized as entity instance");
        assertEquals(0, RtSceneInstances.getRecordIndex(customIndex), "Record index should be 0");
        assertEquals(0x800000, customIndex, "Custom index should have bit 23 set (0x800000)");
    }

    @Test
    public void testEntityMaskAndSkipConditions() {
        int entityMask = RtSceneInstances.MASK_ENTITIES;
        assertEquals(0x02, entityMask, "Entity mask should be 0x02");

        RtEntityGeometryManager mgr = new RtEntityGeometryManager();
        assertEquals(0, mgr.getPrimitiveCount(), "Zero primitives when empty");
        assertFalse(mgr.hasEntities(), "Should not have entities when empty");
    }
}
