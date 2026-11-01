package io.github.andrexarlay.vulkancompat.rt.world;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The primary rays are offset by the jitter the temporal denoiser is told about. DLSS-RR and NRD receive the upscaler's
 * pixel jitter (CameraJitterManager); the legacy GLSL rays read that same value from StatsBuf. The Slang frame used its
 * own Halton sequence instead - and only while accumulating - so a still camera traced one offset and told DLSS-RR
 * another, and a moving camera traced none at all while DLSS-RR was told the upscaler's. The reconstruction then
 * reprojected every sample to the wrong spot: a soft, smeared, washed-out image.
 */
class RtSlangUpscalerJitterTest {
    private static final Path RENDERER = Path.of("src/main/java/io/github/andrexarlay/vulkancompat/rt/world/"
        + "RtFrameRenderer.java");

    @Test
    void anActiveUpscalerJitterIsTheRayJitter() {
        RtPathTraceSlangPass.Frame frame = new RtPathTraceSlangPass.Frame();
        RtFrameParams params = new RtFrameParams();
        params.upscalerJittered = true;
        params.upscalerJitterX = 0.3125f;
        params.upscalerJitterY = -0.21f;
        for (int accumFrames : new int[] {0, 7}) {
            RtSlangFrameData.fill(frame, params, 1280, 720, 11, accumFrames);
            assertEquals(0.3125f, frame.jitter.x, 0f, "the x offset DLSS-RR is told, accumulating or not");
            assertEquals(-0.21f, frame.jitter.y, 0f, "the y offset DLSS-RR is told, accumulating or not");
            assertEquals(1f / 1280f, frame.jitter.z, 0f);
            assertEquals(1f / 720f, frame.jitter.w, 0f);
        }
    }

    @Test
    void withoutAnUpscalerJitterTheAccumulationKeepsItsOwnSequence() {
        RtPathTraceSlangPass.Frame frame = new RtPathTraceSlangPass.Frame();
        RtFrameParams params = new RtFrameParams();
        RtSlangFrameData.fill(frame, params, 1280, 720, 0, 0);
        assertEquals(0f, frame.jitter.x, 0f, "the first frame of a mean is centred");
        assertEquals(0f, frame.jitter.y, 0f);
        RtSlangFrameData.fill(frame, params, 1280, 720, 0, 3);
        assertEquals(0.0f, frame.jitter.x, 0f, "halton(1, 2) - 0.5");
        assertEquals(-1f / 6f, frame.jitter.y, 1e-7f, "halton(1, 3) - 0.5");
    }

    @Test
    void theRendererHandsTheFrameTheJitterItGivesTheDenoiser() throws IOException {
        String renderer = Files.readString(RENDERER).replace("\r\n", "\n");
        assertTrue(renderer.contains("        params.upscalerJittered = upscalerJittered;\n"
            + "        params.upscalerJitterX = jitterPxX;\n"
            + "        params.upscalerJitterY = jitterPxY;"), "one value for StatsBuf, the Slang rays and DLSS-RR");
        assertTrue(renderer.indexOf("params.upscalerJitterX = jitterPxX;") < renderer.indexOf("pass.dispatch(cmd, params, w, h);"),
            "set before the pass fills its frame");
    }
}
