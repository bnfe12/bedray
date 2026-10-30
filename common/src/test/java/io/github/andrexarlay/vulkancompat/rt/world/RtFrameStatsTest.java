package io.github.andrexarlay.vulkancompat.rt.world;

import io.github.andrexarlay.vulkancompat.rt.core.BedrayCoreLayouts;
import io.github.andrexarlay.vulkancompat.rt.slang.SlangPass;
import io.github.andrexarlay.vulkancompat.rt.slang.SlangPassLibrary;
import io.github.andrexarlay.vulkancompat.rt.slang.SlangPassManifest;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The frame's counters have a writer again. The GLSL ray pass filled {@code hits}, {@code samples}, {@code lumLogSum}
 * and {@code histAccepted} inline; when the legacy pipeline was deleted the four words stayed zero, which muted
 * adaptive exposure ({@link RtExposure} never saw a sample) and turned the F3 hit and history shares into a silent
 * "nothing measured". {@code passes/frame_stats.slang} now derives them from what the path already produces.
 *
 * <p>What is pinned here is the arithmetic on both sides of the readback - the luminance fixed point, the slot's four
 * offsets, the 1-in-16 mask - because none of it is checkable from a unit test of the shader, and a mismatch is a
 * wrong exposure rather than a crash.
 */
class RtFrameStatsTest {
    private static final Path SHADER = Path.of("src/main/resources/shaders/slang/passes/frame_stats.slang");
    private static final Path HOST = Path.of("src/main/java/io/github/andrexarlay/vulkancompat/rt/world/"
        + "RtFrameStatsSlangPass.java");
    private static final Path BACKEND = Path.of("src/main/java/io/github/andrexarlay/vulkancompat/rt/world/"
        + "ReferencePathTraceBackend.java");
    private static final Path NATIVE_BACKEND = Path.of("src/main/java/io/github/andrexarlay/vulkancompat/rt/world/"
        + "NativePathTraceBackend.java");
    private static final Path BRIDGE = Path.of("src/main/java/io/github/andrexarlay/vulkancompat/rt/world/"
        + "BedrayPathTraceNative.java");
    private static final Path LAYOUTS = Path.of("src/main/java/io/github/andrexarlay/vulkancompat/rt/core/"
        + "BedrayCoreLayouts.java");
    private static final Path CORE_NATIVE = Path.of("src/main/java/io/github/andrexarlay/vulkancompat/rt/core/"
        + "BedrayCoreNative.java");
    private static final Path CORE_HEADER = Path.of("../native/bedray_core/include/bedray_core.h");
    private static final Path CORE_SOURCE = Path.of("../native/bedray_core/src/core.cpp");
    private static final Path RENDERER = Path.of("src/main/java/io/github/andrexarlay/vulkancompat/rt/world/"
        + "RtFrameRenderer.java");
    private static final Path EXPOSURE = Path.of("src/main/java/io/github/andrexarlay/vulkancompat/rt/world/"
        + "RtExposure.java");
    private static final Path BINDINGS = Path.of("src/main/resources/shaders/slang/bindings.slang");

    /** The range the shader clamps to, read from its own source so the literals below cannot drift. */
    private static double shaderFloor() throws IOException {
        return Double.parseDouble(needle(read(SHADER), "PATH_STATS_LUM_FLOOR = ", ";").replace("_", ""));
    }

    private static double shaderCeiling() throws IOException {
        return Double.parseDouble(needle(read(SHADER), "PATH_STATS_LUM_CEIL = ", ";").replace("_", ""));
    }

    /** What one metered pixel contributes: the shader's own expression, in doubles. */
    private static long encode(double luminance, double floor, double ceiling) {
        return Math.round((Math.log(Math.max(floor, Math.min(ceiling, luminance))) / Math.log(2.0) + 16.0) * 64.0);
    }

    /** What the renderer's readback makes of a sum of those words. */
    private static double decode(long lumLogSum, long samples) {
        return Math.pow(2.0, lumLogSum / 64.0 / samples - 16.0);
    }

    /** A day-lit frame and a night frame both come back as themselves, to the fixed point's own resolution. */
    @Test
    void theLuminanceWordRoundTripsThroughTheReadback() throws IOException {
        double floor = shaderFloor();
        double ceiling = shaderCeiling();
        assertEquals(1.0 / 65536.0, floor, 1.0e-12, "the word is 64*(log2 l + 16), so the floor is 2^-16");
        assertEquals(65536.0, ceiling, 1.0e-6);
        for (double luminance : new double[]{2.0e-4, 0.002, 0.05, 0.18, 1.0, 8.0, 60.0}) {
            double back = decode(encode(luminance, floor, ceiling), 1L);
            assertEquals(luminance, back, luminance * 0.006, "one stop's 1/64 is the resolution of the word");
        }
        // Averaging over the metered pixels keeps the mean: thirty thousand samples of the same value decode back
        // to that value, which is what RtExposure divides by.
        long sum = 0L;
        for (int i = 0; i < 30000; i++) {
            sum += encode(0.18, floor, ceiling);
        }
        assertEquals(0.18, decode(sum, 30000L), 0.18 * 0.006);
        // Outside the range the word clamps instead of wrapping, and a pitch-dark frame sums to zero - the value
        // RtExposure already treats as "no measurement".
        assertEquals(0L, encode(0.0, floor, ceiling));
        assertEquals(2048L, encode(1.0e9, floor, ceiling));
        assertEquals(encode(floor, floor, ceiling), encode(1.0e-30, floor, ceiling));
    }

    /** Three files hold one formula; the test reads all three, because a one-sided edit is the failure it prevents. */
    @Test
    void theShaderEncodesWhatTheHostDecodes() throws IOException {
        String shader = read(SHADER);
        assertTrue(shader.contains("uint lumWord = uint(round((log2(lit) + 16.0) * 64.0));"),
            "the word the shader writes is the one the readback decodes");
        String expectedDecode = "lumLogSum / 64.0 / samples - 16.0";
        assertTrue(read(EXPOSURE).contains(expectedDecode), "RtExposure decodes the same fixed point");
        assertTrue(read(RENDERER).contains("sumLog / 64.0 / samples - 16.0"), "and so does the F3 average luminance");
    }

    /** stats[0..3] are the four head words of the slot, in the order the readback reads them. */
    @Test
    void theCountersLandOnTheHeadOfTheFrameSlot() throws IOException {
        assertEquals(0, RtStatsLayout.HITS);
        assertEquals(4, RtStatsLayout.SAMPLES);
        assertEquals(8, RtStatsLayout.LUM_LOG_SUM);
        assertEquals(12, RtStatsLayout.HIST_ACCEPTED);
        String shader = read(SHADER);
        int order = shader.indexOf("InterlockedAdd(stats[0], hit ? 1u : 0u);");
        assertTrue(order > 0, "stats[0] is the hit count");
        assertTrue(order < shader.indexOf("InterlockedAdd(stats[1], 1u);"), "stats[1] counts the samples");
        assertTrue(shader.indexOf("InterlockedAdd(stats[1], 1u);")
            < shader.indexOf("InterlockedAdd(stats[2], lumWord);"), "stats[2] sums the luminance words");
        assertTrue(shader.indexOf("InterlockedAdd(stats[2], lumWord);")
            < shader.indexOf("InterlockedAdd(stats[3], historyKept);"), "stats[3] counts the history kept");
    }

    /**
     * The counter pass reaches the slot as four raw words, so its index {@code i} has to be the field the host reads at
     * offset {@code 4*i} and the field a shader reads by name through {@code FrameStats.load()}. The test that compared
     * the whole slot parsed the GLSL declaration and went with the legacy pipeline; this one pins the head, which is
     * the part the meter writes. The rest of the slot is unpinned again.
     */
    @Test
    void theMeteredWordsAreTheHeadOfBothMirrors() throws IOException {
        String slang = read(BINDINGS);
        int struct = slang.indexOf("struct FrameStats {");
        assertTrue(struct >= 0, "the Slang mirror of the frame slot is gone");
        String body = slang.substring(slang.indexOf('{', struct) + 1, slang.indexOf("};", struct));
        List<String> declared = new ArrayList<>();
        for (String line : body.split("\n")) {
            String field = line.trim();
            if (field.isEmpty() || field.startsWith("//")) {
                continue;
            }
            declared.add(field.replaceAll("\\s+", " "));
        }
        assertEquals(List.of("uint hits;", "uint samples;", "uint lumLogSum;", "uint histAccepted;"),
            declared.subList(0, 4), "the words the counter pass adds into are the words the shader names");
        List<RtStatsLayout.Member> head = RtStatsLayout.members().subList(0, 4);
        assertEquals(List.of("hits", "samples", "lumLogSum", "histAccepted"),
            head.stream().map(RtStatsLayout.Member::name).toList(), "and the Java mirror agrees, in order");
        for (int i = 0; i < head.size(); i++) {
            assertEquals(i * Integer.BYTES, head.get(i).offset(), "no padding between the four words");
            assertEquals("uint", head.get(i).glslType(), "each is one word, which is what the buffer element size says");
        }
    }

    /**
     * The mask the host's cost claim rests on: whole 4x4 tiles, one in sixteen, and a different set every frame. A
     * fixed subset would meter the same pixels for the whole session, and a static camera's history makes their noise
     * correlated. The rate is read off the shader rather than assumed, because a one-sided change to the constant is
     * exactly the edit that quietly doubles or halves the meter's cost and its sample count.
     */
    @Test
    void oneTileInSixteenIsMeteredAndTheFrameRotatesIt() throws IOException {
        int rate = (int) Double.parseDouble(needle(read(SHADER), "PATH_STATS_TILES_PER_SAMPLE = ", "u;"));
        assertEquals(16, rate, "the legacy counters weighed one tile in sixteen, and so does the host's cost claim");
        int width = 256;
        int height = 128;
        int tilesPerRow = (width + 3) / 4;
        int tiles = tilesPerRow * ((height + 3) / 4);
        java.util.Set<Integer> coveredBySixteenFrames = new java.util.HashSet<>();
        for (int frameIndex = 0; frameIndex < rate; frameIndex++) {
            int selected = 0;
            for (int y = 0; y < height; y += 4) {
                for (int x = 0; x < width; x += 4) {
                    int tile = (x / 4) + (y / 4) * tilesPerRow;
                    if (((tile + frameIndex) % rate) == 0) {
                        selected++;
                        coveredBySixteenFrames.add(tile);
                    }
                }
            }
            assertEquals(tiles / rate, selected, "one tile in " + rate + " for frame " + frameIndex);
        }
        assertEquals(tiles, coveredBySixteenFrames.size(), "sixteen frames weigh every tile exactly once");
        String shader = read(SHADER).replace("\r\n", "\n");
        assertTrue(shader.contains("uint tilesPerRow = (uint(size.x) + 3u) >> 2u;"), "the same tile index in the shader");
        assertTrue(shader.contains("if (site % PATH_STATS_TILES_PER_SAMPLE != 0u)"), "and the same rate");
    }

    /** A rejected pixel is no sample of the integrand, and the history share may only count pixels that hit. */
    @Test
    void anUntrustedSampleIsNotASampleAndOnlyAHitKeepsHistory() throws IOException {
        String shader = read(SHADER);
        int reject = shader.indexOf("if (!(sample.a > 0.0) || any(isnan(sample.rgb)) || any(isinf(sample.rgb)))");
        assertTrue(reject > 0, "the tracer's own verdict gates the counters, as it gates the running mean");
        assertTrue(reject < shader.indexOf("InterlockedAdd"), "before anything is added");
        assertTrue(shader.contains("uint historyKept = hit && accum[p].a > 1.0 ? 1u : 0u;"),
            "the host divides the share by hits, so only a hit may join the numerator");
    }

    /** The host binds sixteen bytes of one slot: the pass cannot reach the emitter address that lives behind them. */
    @Test
    void theHostBindsOnlyTheCounterHead() throws IOException {
        String host = read(HOST);
        assertEquals(RtFrameStatsSlangPass.COUNTER_BYTES,
            RtStatsLayout.HIST_ACCEPTED + Integer.BYTES, "the head is the four counters and nothing more");
        assertEquals(16, RtFrameStatsSlangPass.COUNTER_BYTES);
        assertEquals(8, RtFrameStatsSlangPass.PUSH_BYTES);
        assertTrue(host.contains("SlangProgram.Resource.storageBuffer(\"stats\", stats.buffer(), stats.offset(),"
            + " COUNTER_BYTES)"), "the descriptor is the slot's head, at the slot's own offset");
        assertTrue(host.contains("if (stats == null || stats.buffer() == 0L || stats.bytes() < COUNTER_BYTES)"),
            "a frame without a slot is refused, not bound at zero");
    }

    /** The generated manifest satisfies the host's promise, and a one-sided shader edit is refused at creation. */
    @Test
    void theGeneratedManifestMatchesTheHost() throws IOException {
        Assumptions.assumeTrue(SlangPassLibrary.available(RtFrameStatsSlangPass.PASS_NAME),
            "the manifest is generated by compileSlang");
        SlangPassManifest manifest = SlangPassLibrary.load(RtFrameStatsSlangPass.PASS_NAME)
            .map(SlangPass::manifest).orElseThrow();
        RtFrameStatsSlangPass.requireManifest(manifest);
        assertEquals(4, manifest.bindings().size());
        assertEquals(8, manifest.pushConstantBytes());
        assertEquals(0, manifest.requiredCapabilities(), "a counter pass needs no negotiated capability");
        assertThrows(IllegalStateException.class, () -> RtFrameStatsSlangPass.requireManifest(copy(manifest,
            manifest.pushConstantBytes() + 4, manifest.bindings())), "a wider push block than the host writes");
        List<SlangPassManifest.Binding> oneMore = new ArrayList<>(manifest.bindings());
        oneMore.add(new SlangPassManifest.Binding("extra", 4, oneMore.get(0).descriptorType(), 1, 0));
        assertThrows(IllegalStateException.class,
            () -> RtFrameStatsSlangPass.requireManifest(copy(manifest, manifest.pushConstantBytes(), oneMore)),
            "a binding the host never supplies");
        List<SlangPassManifest.Binding> wrongElement = new ArrayList<>();
        for (SlangPassManifest.Binding binding : manifest.bindings()) {
            wrongElement.add("stats".equals(binding.name())
                ? new SlangPassManifest.Binding(binding.name(), binding.binding(), binding.descriptorType(), 1, 8)
                : binding);
        }
        assertThrows(IllegalStateException.class,
            () -> RtFrameStatsSlangPass.requireManifest(copy(manifest, manifest.pushConstantBytes(), wrongElement)),
            "the counters are four-byte words");
    }

    /** It reads what the display step wrote a moment earlier, and rides the barrier the picture already needs. */
    @Test
    void theCountersAreReadAfterTheDisplayStepWroteThem() throws IOException {
        String backend = read(BACKEND);
        int barrier = backend.indexOf("VkBarriers.fullMemoryBarrier(cmd); // rt_output and rt_depth are read");
        int counters = backend.indexOf("statistics.record(cmd");
        assertTrue(barrier > 0, "the backend still ends its recording with the barrier the composite needs");
        assertTrue(counters > barrier, "the counters come after it, so accum and depth are the ones just written");
        assertTrue(backend.contains("statistics.record(cmd, trace.radianceView(), accum[write].view(),"
            + " views.depth(), input.stats(),"), "the accumulation image is the one this frame wrote, not the other half");
        assertTrue(backend.contains("if (statistics != null) statistics.releaseRecording(commandBuffer);"),
            "every dispatch holds a descriptor pool until the recording is proven");
    }

    /** The slot the pass writes is the slot the renderer leases and zeroes, and the native core is handed it too. */
    @Test
    void theRendererHandsOverTheSlotItReadsBack() throws IOException {
        String renderer = read(RENDERER);
        int zeroed = renderer.indexOf("VK10.vkCmdFillBuffer(cmd, statsBuf.handle, writeOffset, 16, 0);");
        int handed = renderer.indexOf("params.statsOffset = writeOffset;");
        assertTrue(zeroed > 0, "the head is cleared before anything is added to it");
        assertTrue(handed > zeroed, "and the pass gets the same offset the clear used");
        assertTrue(renderer.contains("params.statsBuffer = statsBuf.handle;"));
        // The counters reach the host through the barrier that already exists; a second one would be a cost paid twice.
        assertTrue(renderer.contains("if (traced) recordStatsHostVisibility(cmd, statsLease.slot());"));
        String nativeBackend = read(NATIVE_BACKEND);
        assertTrue(nativeBackend.contains("frame.entities(), frame.stats(),"),
            "the native backend hands over the same sixteen-byte head the reference backend writes itself");
        assertTrue(nativeBackend.contains("RtFrameStatsSlangPass.registerProgram(runtime)"),
            "the core runs the AOT counter program from this jar, not a second copy of the maths");
        String bridge = read(BRIDGE);
        assertTrue(bridge.indexOf("library.renderPathTrace(core, desc);") < bridge.indexOf(
                "library.recordPathTraceStats(core, input.commandBuffer()"),
            "the counters are read from the images the display step just wrote, so they must come after it");
        assertTrue(bridge.contains("this.frameStats = programStats != 0L && library.hasFrameStats();"),
            "an older core, or a jar without the artifact, writes nothing and the host then says n/a instead of zero");
    }

    /**
     * The native route is two sources that have to agree on numbers: the header plus the static asserts the C++ pass
     * compiles, and the layout the host packs. A bit that drifts is a stage that never runs; a slot that drifts is a
     * program handle the pass reads as something else. Both come back as a frame whose counters stay zero, which is
     * the failure this batch exists to remove, so the numbers are pinned rather than described.
     */
    @Test
    void theNativeCounterStageIsNamedTheSameByBothSides() throws IOException {
        String header = read(CORE_HEADER);
        String core = read(CORE_SOURCE);
        assertEquals(abiBit(header, "BEDRAY_PATH_TRACE_FEATURE_FRAME_STATS"),
            abiBit(read(LAYOUTS), "PATH_TRACE_FEATURE_FRAME_STATS"),
            "the host probes the mask bit the core advertises, or the stage is silently absent on every frame");
        assertEquals(offsetOf(core, "BedrayPathTraceDesc, program_stats"), BedrayCoreLayouts.PTD_PROGRAM_STATS,
            "the counter program is handed over in the slot the C++ static assert reads");
        assertTrue(core.contains("static_assert(sizeof(BedrayPathTraceDesc) == 80"),
            "the core keeps pinning the configure desc an older host packed");
        assertEquals(80L, BedrayCoreLayouts.PT_DESC.byteSize(), "and the Java mirror is that same size");
        assertTrue(read(CORE_NATIVE).contains("\"bedray_core_record_path_trace_stats\""),
            "the host binds the export under the name the header declares it");
    }

    /**
     * leafBacklit is the one head counter the Slang renderer cannot produce, and a permanent zero reads as a measured
     * "no leaf is backlit". The line has to keep saying n/a while nothing writes it.
     */
    @Test
    void aCounterWithNoWriterIsNotReportedAsZero() throws IOException {
        String renderer = read(RENDERER);
        assertTrue(renderer.contains("lastLeafPct = leafBacklit > 0 ? 100.0 * leafBacklit / hits : -1.0;"),
            "-1 is the value the F3 line prints as n/a");
        assertFalse(renderer.contains("lastLeafPct = 100.0 * MemoryUtil.memGetInt(base + RtStatsLayout.LEAF_BACKLIT)"),
            "an unwritten counter is not a measurement of zero");
    }

    private static SlangPassManifest copy(SlangPassManifest manifest, int pushBytes,
                                          List<SlangPassManifest.Binding> bindings) {
        return new SlangPassManifest(manifest.schema(), manifest.name(), manifest.source(), manifest.sourceSha256(),
            manifest.entryPoint(), manifest.stage(), manifest.profile(), manifest.descriptorSet(),
            manifest.threadGroupSize(), pushBytes, manifest.spirvWordCount(), manifest.requiredCapabilities(),
            manifest.spirvCapabilities(), bindings);
    }

    /** {@code NAME = (1u << N)} in the C header, or {@code NAME = 1 << N} in the Java mirror, as a mask value. */
    private static int abiBit(String source, String name) {
        Matcher m = Pattern.compile(name + "\\s*=?\\s*\\(?1u?\\s*<<\\s*(\\d+)\\)?").matcher(source);
        assertTrue(m.find(), name + " is not declared as a single bit");
        return 1 << Integer.parseInt(m.group(1));
    }

    /** A {@code static_assert(offsetof(struct, field) == N)} the C++ pass compiles, as a byte offset. */
    private static long offsetOf(String source, String structAndField) {
        Matcher m = Pattern.compile("offsetof\\(\\s*" + Pattern.quote(structAndField)
            + "\\s*\\)\\s*==\\s*(\\d+)").matcher(source);
        assertTrue(m.find(), "the core no longer pins the offset of " + structAndField);
        return Long.parseLong(m.group(1));
    }

    private static String needle(String text, String before, String after) {
        int at = text.indexOf(before);
        assertTrue(at >= 0, "the source no longer says " + before);
        int end = text.indexOf(after, at);
        return text.substring(at + before.length(), end);
    }

    private static String read(Path path) throws IOException {
        return Files.readString(path).replace("\r\n", "\n");
    }
}
