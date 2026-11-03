package io.github.andrexarlay.vulkancompat.rtx.upscaler;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WorldResolutionPlanTest {
    @Test void balancedRrTracesLowButKeepsPostUiAndDepthAtOutputSize() {
        var plan = WorldResolutionPlan.choose(1485, 835, 2560, 1440, true, true, true);
        assertTrue(plan.rayReconstructionOutput());
        assertEquals(1485, plan.traceWidth()); assertEquals(835, plan.traceHeight());
        assertEquals(2560, plan.worldWidth()); assertEquals(1440, plan.worldHeight());
    }
    @Test void bootstrapOtherBackendsAndFgRetainTheirExistingInputContract() {
        for (var plan : new WorldResolutionPlan[]{
                WorldResolutionPlan.choose(1485, 835, 2560, 1440, false, true, true),
                WorldResolutionPlan.choose(1485, 835, 2560, 1440, true, false, true),
                WorldResolutionPlan.choose(1485, 835, 2560, 1440, true, true, false)}) {
            assertFalse(plan.rayReconstructionOutput());
            assertEquals(1485, plan.worldWidth()); assertEquals(835, plan.worldHeight());
        }
    }
    @Test void dlaaStillUsesOneReconstructionWithoutReducingResolution() {
        var plan = WorldResolutionPlan.choose(1920, 1080, 1920, 1080, true, true, true);
        assertTrue(plan.rayReconstructionOutput());
        assertEquals(plan.traceWidth(), plan.worldWidth());
        assertEquals(plan.traceHeight(), plan.worldHeight());
    }
}
