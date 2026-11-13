package io.github.andrexarlay.vulkancompat.rtx.framegen;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** Pins the cross-module producer contract; matrix and failure behavior have separate executable CPU tests. */
class RrFrameGenerationWiringTest {
    private static String source(String path) throws IOException {
        return Files.readString(Path.of("src/main/java/io/github/andrexarlay/vulkancompat/" + path + ".java"));
    }

    @Test void bothVendorsConsumeCurrentRrGuidesAndNeverFallbackToStaleRasterGuides() throws IOException {
        for (String path : new String[]{"rtx/framegen/FrameGenRuntime", "rtx/framegen/fsr3/Fsr3FrameGen"}) {
            String text = source(path);
            assertTrue(text.contains("WorldTargetManager.rrOutputThisFrame()"));
            assertTrue(text.contains("if (rrFrame && guides == null) { worldFail = \"rr_guides_missing_current_frame\"; return; }"));
            assertTrue(text.contains("guides.requireMatches("));
            assertTrue(text.contains("guides.depth().view()"));
            assertTrue(text.contains("guides.motion().view()"));
            assertTrue(text.contains("guides.reads().read() : null"));
        }
        String plan = source("rtx/upscaler/WorldResolutionPlan");
        assertFalse(plan.contains("framegenOff"), "FG no longer selects the old RR followed by SR route");
        String world = source("rtx/WorldTargetManager");
        assertTrue(world.contains("WorldFrames.isCurrent(resolutionStamp)"), "the route survives endWorldPhase with engine frame provenance");
    }

    @Test void disabledFrameGenerationSkipsGuideProductionAndResumingRequiresCurrentData() throws IOException {
        String nvidia = source("rtx/framegen/FrameGenRuntime");
        String amd = source("rtx/framegen/fsr3/Fsr3FrameGen");
        String world = source("rtx/WorldTargetManager");
        String renderer = source("rt/world/RtFrameRenderer");
        assertTrue(renderer.contains("FrameGenRuntime.needsFrameInputs())\n                        pendingFgGuides"));
        int nvidiaWorldReady = nvidia.indexOf("public static void onWorldReady(");
        int amdWorldReady = amd.indexOf("public static void onWorldReady(");
        assertTrue(nvidia.indexOf("if (!needsDlssgInputs())", nvidiaWorldReady)
            < nvidia.indexOf("producerCommands.record(", nvidiaWorldReady));
        assertTrue(amd.indexOf("if (!needsFrameInputs())", amdWorldReady)
            < amd.indexOf("producerCommands.record(", amdWorldReady));
        for (String vendor : new String[]{nvidia, amd}) {
            assertTrue(vendor.contains("!rrFrame && !WorldTargetManager.hasCurrentRasterMotion()"));
        }
        assertTrue(world.contains("WorldFrames.isCurrent(rasterMotionStamp)"));
        assertTrue(world.contains("!reuseDenoiserMotion || FrameGenRuntime.needsFrameInputs() || probeRasterMotion"));
    }

    @Test void rrSdkAndVelocityUseTheActualPrimaryProducerJitter() throws IOException {
        String renderer = source("rt/world/RtFrameRenderer");
        assertTrue(renderer.contains("float rrJitterX = slang.guideFrame().jitter.x"));
        assertTrue(renderer.contains("float rrJitterY = slang.guideFrame().jitter.y"));
        String rr = source("rt/denoise/DlssRrDenoiser");
        assertTrue(rr.contains("new VelocityPipeline.Jitter(jitterPixelsX, jitterPixelsY,"));
        assertTrue(rr.contains("reset ? jitterPixelsX : previousPrimaryJitterX"));
        assertTrue(rr.indexOf("previousPrimaryJitterX = jitterPixelsX") > rr.indexOf("rr.evaluate("));
        // RR has its own measured jitter signs (2026-11-07: +1/+1 made the RR output wobble in a 2 px cycle; +1/-1 is still).
        assertTrue(rr.contains("jitterPixelsX * io.github.andrexarlay.vulkancompat.rtx.upscaler.dlss.DlssConfig.rrJitterSignX()"));
        assertTrue(rr.contains("jitterPixelsY * io.github.andrexarlay.vulkancompat.rtx.upscaler.dlss.DlssConfig.rrJitterSignY()"));
        assertEquals(1.0f, io.github.andrexarlay.vulkancompat.rtx.upscaler.dlss.DlssConfig.rrJitterSignX());
        assertEquals(-1.0f, io.github.andrexarlay.vulkancompat.rtx.upscaler.dlss.DlssConfig.rrJitterSignY());
        String fg = source("rtx/framegen/FrameGenRuntime");
        assertTrue(fg.contains("int VIEWPORT = 1;"));
        assertTrue(fg.contains("int RECONSTRUCTION_VIEWPORT = 0;"));
        assertTrue(fg.contains("commonCameras.publish("));
        assertTrue(fg.contains("fgCameras.publish("));
        assertFalse(fg.contains("FLAG_DYNAMIC_RESOLUTION"), "fixed DLSS input/output scale is not DRS");
    }

    @Test void rrReplacementDrainsFgReadsBeforeDestroyingAnyGuideGeneration() throws IOException {
        String renderer = source("rt/world/RtFrameRenderer");
        int drain = renderer.indexOf("private static void drainHostSubmissions(");
        assertTrue(renderer.indexOf("FrameGenRuntime.drainProducerRecordings()", drain)
            < renderer.indexOf("encoder.submit()", drain));
        String rr = source("rt/denoise/DlssRrDenoiser");
        int destroy = rr.indexOf("public void destroy()");
        assertTrue(rr.indexOf("frameGenReads.requireUnreferenced()", destroy) < rr.indexOf("releaseFeature(\"destroy\")", destroy));
        int release = rr.indexOf("private void releaseFeature(String stage)");
        assertTrue(rr.indexOf("frameGenReads.requireUnreferenced()", release) < rr.indexOf("rr.releaseFeature(", release));
        String host = source("rtx/framegen/FrameGenHostCommands");
        assertTrue(host.indexOf("retirements(owner, source, handle)") < host.indexOf("source.retain(handle)"));
    }
}
