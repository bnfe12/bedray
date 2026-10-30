package io.github.andrexarlay.vulkancompat.rt.world;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * "Exposure compensation" and "Adaptive exposure" reach the picture. The display step of the typed path is the one place
 * that multiplies the running mean before the tone curve, and it takes that multiplier from the frame. Before this batch
 * the display push hardcoded 1.0, the volumetric composite hardcoded 1, and the auto-exposure bit was read in three call
 * sites but never set - so the EV slider moved a number in the denoiser's pack and left the traced image alone.
 */
class RtSlangExposureTest {
    private static final Path RENDERER = Path.of("src/main/java/io/github/andrexarlay/vulkancompat/rt/world/"
        + "RtFrameRenderer.java");
    private static final Path FRAME_DATA = Path.of("src/main/java/io/github/andrexarlay/vulkancompat/rt/world/"
        + "RtSlangFrameData.java");
    private static final Path VOLUMETRIC = Path.of("src/main/java/io/github/andrexarlay/vulkancompat/rt/world/"
        + "RtSlangVolumetricPass.java");
    private static final Path REFERENCE_BACKEND = Path.of("src/main/java/io/github/andrexarlay/vulkancompat/rt/world/"
        + "ReferencePathTraceBackend.java");
    private static final Path NATIVE_BRIDGE = Path.of("src/main/java/io/github/andrexarlay/vulkancompat/rt/world/"
        + "BedrayPathTraceNative.java");
    private static final Path DISPLAY = Path.of("src/main/resources/shaders/slang/passes/path_display.slang");
    private static final Path ENTRY = Path.of("src/main/resources/shaders/slang/pathtrace/path_trace_entry.slang");

    /** The compensation is a linear factor on the exposure, clamped to the four stops the slider can reach. */
    @Test
    void evCompensationIsAPowerOfTwo() {
        assertEquals(1.0f, RtExposure.evScale(0), 1.0e-6f, "no compensation");
        assertEquals(2.0f, RtExposure.evScale(10), 1.0e-6f, "one stop brighter");
        assertEquals(0.5f, RtExposure.evScale(-10), 1.0e-6f, "one stop darker");
        assertEquals(16.0f, RtExposure.evScale(400), 1.0e-6f, "a request past the slider clamps to its ceiling");
        assertEquals(1.0f / 16.0f, RtExposure.evScale(-400), 1.0e-6f, "and to its floor");
    }

    /** The switch has a mechanism: a meter that has measured a dark scene lifts the exposure the fixed mode cannot. */
    @Test
    void aMeasuredSceneMovesTheAutoValueAndNotTheFixedOne() {
        RtExposure meter = new RtExposure();
        int samples = 1000;
        long logSumOfADarkScene = (long) ((Math.log(0.004) / Math.log(2.0) + 16.0) * 64.0) * samples;
        for (int frame = 0; frame < 600; frame++) {
            meter.observe(logSumOfADarkScene, samples, 1.0f / 60.0f);
        }
        assertTrue(meter.applied(true, 0) > meter.applied(false, 0),
            "with the same compensation the adapted value must differ from the fixed one, or the switch is a lie");
        assertTrue(meter.applied(true, 10) > meter.applied(true, 0), "the compensation still rides on top of the meter");
    }

    /** One number per frame: the renderer computes it once and every consumer reads that field. */
    @Test
    void theFrameComputesExposureOnce() throws IOException {
        String src = read(RENDERER);
        assertTrue(src.contains("params.exposure = exposure.applied("
            + "io.github.andrexarlay.vulkancompat.rtx.config.RtSettings.autoExposure(),"),
            "the switch is read from the setting the player toggled, not from a flag bit that nothing ever set");
        assertEquals(1, occurrences(src, "exposure.applied("),
            "one call site decides the frame's exposure; a second one can only disagree with the first");
        assertEquals(2, occurrences(src, "float composeExposure = params.exposure;"),
            "both denoiser packs read the frame's number instead of recomputing it");
        assertTrue(src.contains("MemoryUtil.memPutFloat(hdr + RtStatsLayout.EXPOSURE, params.exposure);"),
            "the per-frame slot the shaders used to read carries the same number as the picture");
    }

    /** The auto bit is now actually set, so a legacy or future reader of the flag sees the player's choice. */
    @Test
    void theSwitchReachesTheFrameFlags() throws IOException {
        String src = read(RENDERER);
        assertTrue(src.contains("(io.github.andrexarlay.vulkancompat.rtx.config.RtSettings.autoExposure() "
            + "? RtFrameParams.FLAG_AUTO_EXPOSURE : 0)"),
            "the flag the renderer used to test must be the flag the renderer sets");
        assertFalse(src.contains("exposure.applied((params.flags & RtFrameParams.FLAG_AUTO_EXPOSURE)"),
            "reading the bit before the flags are composed would use last frame's switch");
    }

    /** The frame word is the carrier for both backends, and nothing pins it to one. */
    @Test
    void oneLaneFeedsBothBackends() throws IOException {
        RtPathTraceSlangPass.Frame frame = new RtPathTraceSlangPass.Frame();
        RtFrameParams params = new RtFrameParams();
        params.exposure = 2.5f;
        RtSlangFrameData.fill(frame, params, 1920, 1080, 0, 1);
        assertEquals(2.5f, frame.exposure.x, "the host writes the frame's number into the exposure lane");
        assertEquals(0f, frame.exposure.w, "the frame time stays out of this lane; the Java path keeps it in origin.w");
        String data = read(FRAME_DATA);
        assertTrue(data.contains("frame.exposure.set(p.exposure, 0f, 0f, 0f);"), "no constant may return here");
        String nativeBridge = read(NATIVE_BRIDGE);
        assertTrue(nativeBridge.contains("PTL_EXPOSURE, frame.exposure.x)"),
            "the native core takes its descriptor's exposure from the same lane");
        String reference = read(REFERENCE_BACKEND);
        assertTrue(reference.contains(".putFloat(8, frame.exposure.x)"),
            "the Java display dispatch takes the same lane, at the offset the shader calls pc.exposure");
        assertFalse(reference.contains(".putFloat(8, 1.0f)"), "the display push must not pin the multiplier");
    }

    /** The shafts are composited onto the tone-mapped picture, so they are scaled by that picture's exposure. */
    @Test
    void theVolumetricCompositeSharesThePictureExposure() throws IOException {
        String src = read(VOLUMETRIC);
        assertTrue(src.contains("float exposure = p.exposure;"),
            "the march and the apply read the frame's number, and each other");
        assertFalse(src.contains("float exposure = 1f;"), "a pinned one would dim the shafts relative to the world");
    }

    /** The tracer must stay scene-referred: only the display step spends the exposure, so accumulation cannot be re-exposed. */
    @Test
    void onlyTheDisplayStepSpendsIt() throws IOException {
        String display = read(DISPLAY);
        assertTrue(display.contains("float3 exposed = mean * max(pc.exposure, 0.0);"),
            "the multiplier is applied to the running mean, before the tone curve");
        assertTrue(display.contains("float  exposure;"), "the push lane the host writes");
        String entry = read(ENTRY);
        assertFalse(entry.contains("pc.exposure"),
            "the tracer writes scene-referred radiance; exposing it here would expose the accumulated history twice");
    }

    private static String read(Path path) throws IOException {
        return Files.readString(path).replace("\r\n", "\n");
    }

    private static int occurrences(String haystack, String needle) {
        int count = 0;
        for (int at = haystack.indexOf(needle); at >= 0; at = haystack.indexOf(needle, at + needle.length())) {
            count++;
        }
        return count;
    }
}
