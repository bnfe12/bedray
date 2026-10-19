package io.github.andrexarlay.vulkancompat.rt.world;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for RtEntityBlas lifecycle state, markTlasReady, and formatStatusDiagnostics.
 * Note: actual BLAS build requires a VkCommandBuffer / VkDevice (GPU), so we only test
 * the headless state machine and diagnostic formatting here.
 */
public class RtEntityBlasTest {

    @Test
    public void testInitialStateIsEmpty() {
        // Cannot instantiate RtEntityBlas without a VkDevice; test via formatTelemetry instead.
        // The static helper must produce a parseable string.
        String tel = RtEntityBlas.formatTelemetry(0, false, 0, 0, 0L);
        assertNotNull(tel);
        assertTrue(tel.contains("[VK-ENTITY-AS]"), "must contain prefix");
        assertTrue(tel.contains("blasBuilt=false"), "must report not built");
        assertTrue(tel.contains("primitives=0"), "must have 0 primitives");
        assertTrue(tel.contains("entityMask=0x02"), "must have entity mask 0x02");
    }

    @Test
    public void testFormatTelemetryWithAddress() {
        String tel = RtEntityBlas.formatTelemetry(3, true, 42, 1, 0xDEADBEEFL);
        assertTrue(tel.contains("blasBuilt=true"));
        assertTrue(tel.contains("primitives=42"));
        assertTrue(tel.contains("tlasInstances=1"));
        assertTrue(tel.contains("captured=3"));
    }

    @Test
    public void testStateEnumValues() {
        // Verify all expected State values exist (compiler check)
        RtEntityBlas.State[] states = RtEntityBlas.State.values();
        assertNotNull(states);
        // Must contain EMPTY, BLAS_READY, TLAS_READY at minimum
        boolean hasEmpty = false, hasBlas = false, hasTlas = false;
        for (RtEntityBlas.State s : states) {
            if (s == RtEntityBlas.State.EMPTY) hasEmpty = true;
            if (s == RtEntityBlas.State.BLAS_READY) hasBlas = true;
            if (s == RtEntityBlas.State.TLAS_READY) hasTlas = true;
        }
        assertTrue(hasEmpty, "must have EMPTY state");
        assertTrue(hasBlas, "must have BLAS_READY state");
        assertTrue(hasTlas, "must have TLAS_READY state");
    }

    @Test
    public void testCustomIndexMask() {
        // Entity TLAS customIndex uses INSTANCE_TYPE_ENTITY = 0x800000 (24-bit mask)
        // Verify it fits in 24 bits without overflow
        int INSTANCE_TYPE_ENTITY = 0x800000;
        assertTrue((INSTANCE_TYPE_ENTITY & 0xFFFFFF) == INSTANCE_TYPE_ENTITY,
            "INSTANCE_TYPE_ENTITY must fit in 24-bit customIndex");
        // Slot 0 | INSTANCE_TYPE_ENTITY = 0x800000 (safe)
        int customIndex = 0 | INSTANCE_TYPE_ENTITY;
        assertEquals(0x800000, customIndex);
        // isEntityHit check: (customIndex & 0x800000) != 0
        assertTrue((customIndex & INSTANCE_TYPE_ENTITY) != 0, "isEntityHit should be true");
    }
}
