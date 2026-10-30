package io.github.andrexarlay.vulkancompat.rt.world;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * "Rays per Pixel" reaches the tracer. The player's value goes through the adaptive governor into the frame word
 * {@code FrameConstants.frame.z}, and the entry loop traces that many primary paths per pixel and averages them.
 * Before this the word was written as a constant 1 and no shader read it at all, so the slider changed a number in a
 * log and nothing in the picture.
 */
class RtSlangSamplePerPixelTest {
    private static final Path SHARED = Path.of("src/main/resources/shaders/slang/pathtrace/path_trace_entry.slang");
    private static final Path FRAME_DATA = Path.of("src/main/java/io/github/andrexarlay/vulkancompat/rt/world/"
        + "RtSlangFrameData.java");
    private static final Path PATH_PASS = Path.of("src/main/java/io/github/andrexarlay/vulkancompat/rt/world/"
        + "RtSlangPathPass.java");

    /** The host side: the frame word carries the governor's value, clamped to the ceiling the shader honours. */
    @Test
    void frameWordCarriesTheSampleCount() {
        RtPathTraceSlangPass.Frame frame = new RtPathTraceSlangPass.Frame();
        RtFrameParams params = new RtFrameParams();
        for (int[] request : new int[][]{{0}, {1}, {4}, {8}, {99}}) {
            params.spp = request[0];
            RtSlangFrameData.fill(frame, params, 1920, 1080, 3, 5);
            int expected = Math.min(RtPathTraceSlangPass.MAX_SAMPLES_PER_PIXEL, Math.max(1, request[0]));
            assertEquals(expected, frame.samplesPerFrame, "frame word for a request of " + request[0]);
        }
    }

    /** The shader reads the word the host writes, inside its own ceiling: neither side may ignore the other. */
    @Test
    void entryLoopsOverTheFrameWord() throws IOException {
        String shared = read(SHARED);
        assertTrue(shared.contains("static const uint PATH_TRACE_MAX_SPP = 8u;"),
            "the entry names the ceiling the host clamps to as well");
        assertTrue(shared.contains("return clamp(f.frame.z, 1u, PATH_TRACE_MAX_SPP);"),
            "the sample count comes from FrameConstants.frame.z, not from a constant");
        assertTrue(shared.contains("uint sampleCount = pathTraceSampleCount(f);"),
            "the entry asks the frame how many rays this pixel owns");
        assertTrue(shared.contains("for (uint s = 0u; s < sampleCount; ++s)"),
            "the integrator runs once per requested sample");
        assertTrue(shared.contains("accumulatePathTraceSample(total, tracePathWithField("),
            "each sample feeds the running mean instead of overwriting the pixel");
        assertTrue(shared.contains("PathTraceResult result = pathTraceMeanResult(total, sampleTrusted);"),
            "the writers read the mean of the samples, not the last one");
    }

    /**
     * One ray per pixel has to be the frame that ran before the loop existed: the host's own jitter, the host's own
     * sample index, no strata. Anything else would move the deployed default's pixels under a change that promised only
     * to add rays when the player asked for them.
     */
    @Test
    void oneRayPerPixelIsTheFrameThatRanBefore() throws IOException {
        String shared = read(SHARED);
        assertTrue(shared.contains("if (n <= 1u) {\n        return f.camera.jitter.xy;"),
            "a single ray keeps the host's own sub-pixel jitter");
        assertTrue(shared.contains("uint sampleWord = pc.sampleIndex * sampleCount + s;\n"
                + "        uint seed = pathTraceSeed(p, f.frame.x, sampleWord);"),
            "with one sample the seed is the host's sample index, exactly as before the loop; the same word also keys "
                + "the world-locked sun rotation of a pixel-shadow cell");
    }

    /**
     * The reservoir is one record per pixel per frame. A second ray that merged into it again would count the same
     * frame twice in the temporal weight, and one that published an empty record would delete what the first ray wrote.
     */
    @Test
    void onlyTheFirstRayTouchesTheReservoir() throws IOException {
        String shared = read(SHARED);
        assertTrue(shared.contains("directReuse.enabled = s == 0u ? diRequested : 0u;"),
            "reuse runs for the first sample of the pixel only");
        assertTrue(shared.contains("lights.emittersReusedAtPrimary = directReuse.enabled != 0u;"),
            "a ray that did not draw on the reservoir pays for the emissive branch itself");
    }

    /** A rejected path is the fault the tracer refused: its radiance may not enter the mean the accumulator stores. */
    @Test
    void refusedPathsStayOutOfTheAcceptedMean() throws IOException {
        String shared = read(SHARED);
        assertTrue(shared.contains("if (pathTraceSampleRejected(result.flags)) {\n"
            + "        mean.refusedRadiance += result.radiance;\n"
            + "        return;\n"
            + "    }\n"
            + "    mean.accepted += 1u;\n"
            + "    mean.acceptedRadiance += result.radiance;"),
            "the reject filter splits the sums before either is averaged");
        assertTrue(shared.contains("result.radiance = mean.acceptedRadiance / float(mean.accepted);"),
            "the pixel's colour is the mean of the believed samples");
    }

    /** The Java side may not hand back the constant that made the slider a no-op. */
    @Test
    void theHostStopsHardcodingOneSamplePerPixel() throws IOException {
        String frameData = read(FRAME_DATA);
        String pathPass = read(PATH_PASS);
        assertTrue(frameData.contains("frame.samplesPerFrame = Math.min(RtPathTraceSlangPass.MAX_SAMPLES_PER_PIXEL,"
            + " Math.max(1, p.spp));"), "the frame word is written from the frame params");
        assertTrue(pathPass.contains("params.spp = RtAdaptiveQuality.spp(RtSettings.spp());"),
            "the pass reads the player's value through the adaptive governor");
        assertTrue(pathPass.contains("\",spp=\" + p.spp + \",dist=\""),
            "the describe line reports the count this frame actually traces");
        assertFalse(frameData.contains("frame.samplesPerFrame = 1;"), "the frame word is not a constant");
        assertFalse(pathPass.contains("params.spp = 1;"), "the pass does not pin one ray per pixel");
        assertFalse(pathPass.contains(",spp=1"), "the pass does not describe a sample count it did not trace");
    }

    private static String read(Path relativeToCommon) throws IOException {
        return Files.readString(relativeToCommon).replace("\r\n", "\n");
    }
}
