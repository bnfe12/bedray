package io.github.andrexarlay.vulkancompat.rt.world;

import io.github.andrexarlay.vulkancompat.rtx.upscaler.UpscalerBackend;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.WorldResolutionPlan;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * With DLSS + DLSS-RR, RR must reconstruct straight to the output. The two-stage chain (RR at render size, then DLSS-SR
 * stretching the already resolved image with zero jitter) is the blur the owner reported (2026-11-07 blur audit).
 */
class RtRrOutputPlanTest {
    private static final String BASE = "src/main/java/io/github/andrexarlay/vulkancompat/";

    private static String read(String path) throws IOException {
        return Files.readString(Path.of(BASE + path)).replace("\r\n", "\n");
    }

    @Test
    void readinessIsIntentNotTheExistenceOfTheRrObject() throws IOException {
        String renderer = read("rt/world/RtFrameRenderer.java");
        int start = renderer.indexOf("public static boolean rrOutputReady() {");
        int end = renderer.indexOf("}", start);
        String ready = renderer.substring(start, end);
        assertTrue(ready.contains("rrIntended()"), "the plan follows the denoiser the frame will use");
        assertFalse(ready.contains("dlssRrDenoiser != null"),
            "the plan is frozen before RR is created: requiring the object sent frame 0 and every retry through the blurry chain");
        assertFalse(ready.contains("dropDlssRrDenoiserNext"), "a one-frame drop must not flip the whole chain and reset history");
        int intended = renderer.indexOf("static boolean rrIntended() {");
        String body = renderer.substring(intended, renderer.indexOf("\n    }", intended));
        assertTrue(body.contains("dlssRrFailed"), "a permanent failure still hands the output back to the upscaler");
        assertTrue(body.contains("lib.usable()") && body.contains("frameGenOwnsNgx"),
            "same conditions as DenoiserResolution's DLSS_RR branch");
    }

    @Test
    void combinedPlanPresentsAtOutputSizeWithDlss() {
        WorldResolutionPlan plan = WorldResolutionPlan.choose(1707, 960, 2560, 1440, true, UpscalerBackend.DLSS, false);
        assertTrue(plan.rayReconstructionOutput(), "DLSS + RR ready: RR owns the output");
        assertEquals(2560, plan.worldWidth());
        assertEquals(1707, plan.traceWidth(), "rays stay at the DLSS input size");
    }

    @Test
    void theTwoStageChainIsReportedWhenItRuns() throws IOException {
        String wtm = read("rtx/WorldTargetManager.java");
        assertTrue(wtm.contains("[VKB-RESOLVE] two_stage_rr=true"), "a regression back to the blurry chain is visible in latest.log");
        assertTrue(wtm.contains("if (twoStageRr != lastTwoStageRr) {"), "change-triggered, never per frame");
    }
}
