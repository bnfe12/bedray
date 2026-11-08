package io.github.andrexarlay.vulkancompat.rtx;

import io.github.andrexarlay.vulkancompat.rtx.upscaler.VelocityPipeline;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * With no denoiser, the temporal upscaler gets the RT pass's per-pixel entity motion (2026-11-08: spinning dropped items
 * reached DLSS-SR with camera-only motion and smeared into dark ovals).
 */
class UpscalerMotionSourceTest {
    private static final int RAY = VelocityPipeline.DEPTH_RAY_DISTANCE;

    @Test
    void entityMotionOnlyWhenTheFrameIsNotDenoiserResolvedAndBothInputsExist() {
        assertEquals(WorldTargetManager.UpscalerMotionSource.RT_ENTITY,
            WorldTargetManager.upscalerMotionSource(false, 1L, 2L, RAY));
        assertEquals(WorldTargetManager.UpscalerMotionSource.RASTER_CAMERA,
            WorldTargetManager.upscalerMotionSource(true, 1L, 2L, RAY), "a denoiser-resolved frame keeps its own path");
        assertEquals(WorldTargetManager.UpscalerMotionSource.RASTER_CAMERA,
            WorldTargetManager.upscalerMotionSource(false, 0L, 2L, RAY), "no RT depth");
        assertEquals(WorldTargetManager.UpscalerMotionSource.RASTER_CAMERA,
            WorldTargetManager.upscalerMotionSource(false, 1L, 0L, RAY), "no entity motion written");
        assertEquals(WorldTargetManager.UpscalerMotionSource.RASTER_CAMERA,
            WorldTargetManager.upscalerMotionSource(false, 1L, 2L, VelocityPipeline.DEPTH_LINEAR_VIEW_Z),
            "only the ray-distance depth matches the RT pass's own motion");
    }

    @Test
    void bothVelocitySitesGoThroughTheSelection() throws IOException {
        String wtm = Files.readString(Path.of("src/main/java/io/github/andrexarlay/vulkancompat/rtx/WorldTargetManager.java"));
        int calls = wtm.split("recordUpscalerMotion\\(velocityPipeline, cmd,", -1).length - 1;
        assertEquals(2, calls, "the upscaler dispatch and the velocity-only path (frame generation)");
        assertTrue(wtm.contains("[VKB-RESOLVE] upscaler_mv_source={}"), "the choice is logged on change");
    }
}
