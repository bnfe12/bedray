package io.github.andrexarlay.vulkancompat.rtx.upscaler;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WorldResolutionPlanTest {
    @Test void balancedRrTracesLowButKeepsPostUiAndDepthAtOutputSize() {
        var plan = WorldResolutionPlan.choose(1485, 835, 2560, 1440, true, UpscalerBackend.DLSS, false);
        assertTrue(plan.rayReconstructionOutput());
        assertEquals(1485, plan.traceWidth()); assertEquals(835, plan.traceHeight());
        assertEquals(2560, plan.worldWidth()); assertEquals(1440, plan.worldHeight());
    }
    @Test void bootstrapAndOtherBackendsRetainTheirExistingInputContract() {
        for (var plan : new WorldResolutionPlan[]{
                WorldResolutionPlan.choose(1485, 835, 2560, 1440, false, UpscalerBackend.DLSS, false),
                WorldResolutionPlan.choose(1485, 835, 2560, 1440, true, UpscalerBackend.XESS, false)}) {
            assertFalse(plan.rayReconstructionOutput());
            assertEquals(1485, plan.worldWidth()); assertEquals(835, plan.worldHeight());
        }
    }
    @Test void dlaaStillUsesOneReconstructionWithoutReducingResolution() {
        var plan = WorldResolutionPlan.choose(1920, 1080, 1920, 1080, true, UpscalerBackend.DLSS, false);
        assertTrue(plan.rayReconstructionOutput());
        assertEquals(plan.traceWidth(), plan.worldWidth());
        assertEquals(plan.traceHeight(), plan.worldHeight());
    }
    @Test void nativeRrWithoutUpscalerUsesTheSameOutputGrid() {
        for (UpscalerBackend backend : new UpscalerBackend[]{null, UpscalerBackend.BILINEAR}) {
            var plan = WorldResolutionPlan.choose(1920, 1080, 1920, 1080, true, backend, false);
            assertTrue(plan.rayReconstructionOutput());
            assertEquals(1920, plan.traceWidth()); assertEquals(1080, plan.traceHeight());
            assertEquals(1920, plan.worldWidth()); assertEquals(1080, plan.worldHeight());
        }
    }
    @Test void reducedBilinearSpatialAndOtherTemporalKeepTheirExistingRoute() {
        for (UpscalerBackend backend : new UpscalerBackend[]{null, UpscalerBackend.BILINEAR}) {
            assertFalse(WorldResolutionPlan.choose(1280, 720, 1920, 1080,
                true, backend, false).rayReconstructionOutput());
            assertFalse(WorldResolutionPlan.choose(1920, 1080, 1920, 1080,
                true, backend, true).rayReconstructionOutput());
        }
        for (UpscalerBackend backend : new UpscalerBackend[]{UpscalerBackend.XESS, UpscalerBackend.FSR,
                UpscalerBackend.FSR1, UpscalerBackend.NIS}) {
            assertFalse(WorldResolutionPlan.choose(1920, 1080, 1920, 1080,
                true, backend, false).rayReconstructionOutput());
        }
    }
}
