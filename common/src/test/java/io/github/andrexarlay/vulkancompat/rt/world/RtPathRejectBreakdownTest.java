package io.github.andrexarlay.vulkancompat.rt.world;

import io.github.andrexarlay.vulkancompat.rt.core.BedrayCoreLayouts;
import io.github.andrexarlay.vulkancompat.rt.slang.SlangPassLibrary;
import io.github.andrexarlay.vulkancompat.rt.slang.SlangPassManifest;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The F3 split of the path tracer's bad endings. {@code reject_pct} says how many metered pixels the tracer refused;
 * this block says why and what else went short: a non-finite fault ({@code PATH_TRACE_FLAG_INVALID_SAMPLE} on a refused
 * pixel), a truncated path ({@code PATH_TRACE_FLAG_TRUNCATED}, kept in the mean), and a shadow walk that ran out of its
 * filter budget ({@code SURFACE_VISIBILITY_FLAG_OVERFLOW}, reported as occluded). The trace pass writes the four words
 * itself, over the same 1-in-16 tiles {@code frame_stats} meters, into the last sixteen bytes of the frame's stats slot
 * (binding 37). The host reads them back and prints them only when the metered word equals its own count of the mask:
 * a native core that binds a stand-in, or a host whose mirror drifted, reads as n/a, never as 0 %.
 */
class RtPathRejectBreakdownTest {

    private static final Path SHADERS = Path.of("src/main/resources/shaders/slang");
    private static final Path ENTRY = SHADERS.resolve("pathtrace/path_trace_entry.slang");
    private static final Path TRACER = SHADERS.resolve("pathtrace/path_tracer.slang");
    private static final Path FRAME_STATS = SHADERS.resolve("passes/frame_stats.slang");
    private static final Path JAVA = Path.of("src/main/java/io/github/andrexarlay/vulkancompat");
    private static final Path RENDERER = JAVA.resolve("rt/world/RtFrameRenderer.java");
    private static final Path TRACE_HOST = JAVA.resolve("rt/world/RtPathTraceSlangPass.java");
    private static final Path CONTROLLER = JAVA.resolve("rt/world/RtSlangPathPass.java");
    private static final Path NATIVE_HOST = JAVA.resolve("rt/world/BedrayPathTraceNative.java");

    // ---- layout: host == shader ------------------------------------------------------------------------------------

    @Test
    void theBlockIsTheLastSixteenBytesOfTheSlotAndTheShaderAddressesTheSameWord() throws IOException {
        assertEquals(560, RtStatsLayout.PATH_REJECTS, "a uvec4 after transIndirectShadows, 16-byte aligned");
        assertEquals(RtStatsLayout.SLOT_STRIDE, RtStatsLayout.SIZE, "the block fills the slot exactly");
        String entry = read(ENTRY);
        assertTrue(entry.contains("static const uint PATH_REJECT_WORD = " + RtStatsLayout.PATH_REJECTS / 4 + "u;"),
            "the shader indexes the word the host reads: offset / 4");
        assertTrue(entry.contains("static const uint PATH_REJECT_SLOT_WORDS = " + RtStatsLayout.SLOT_STRIDE / 4 + "u;"),
            "and refuses a binding shorter than the whole slot");
    }

    @Test
    void theTraceMetersTheTilesFrameStatsMeters() throws IOException {
        String entry = read(ENTRY);
        String stats = read(FRAME_STATS);
        assertTrue(stats.contains("uint site = (uint(p.x) >> 2u) + (uint(p.y) >> 2u) * tilesPerRow + pc.frameIndex;"));
        assertTrue(entry.contains("uint site = (uint(p.x) >> 2u) + (uint(p.y) >> 2u) * tilesPerRow + frameIndex;"),
            "the same tile walk, so metered can be compared with RtStatsMetering and with reject_pct");
        assertTrue(entry.contains("static const uint PATH_REJECT_TILES_PER_SAMPLE = " + RtStatsMetering.TILES_PER_SAMPLE + "u;"));
        assertTrue(entry.contains("pathTraceCountRejects(outPathStats, p, size, f.frame.x, sampleTrusted, total.flags);"),
            "the frame word is the index frame_stats is pushed (RtSlangFrameData writes frame.frameIndex to both)");
    }

    // ---- shader semantics ------------------------------------------------------------------------------------------

    @Test
    void theFourWordsCountWhatTheirNamesSay() throws IOException {
        String entry = read(ENTRY);
        String body = block(entry, "void pathTraceCountRejects(");
        assertTrue(body.contains("InterlockedAdd(stats[PATH_REJECT_WORD + 0u], 1u);")
                && body.contains("if (!trusted && (flags & PATH_TRACE_FLAG_INVALID_SAMPLE) != 0u)"),
            "word 0: a refused pixel with a non-finite fault (RC_UNRESOLVED is the rest of reject_pct)");
        assertTrue(body.contains("InterlockedAdd(stats[PATH_REJECT_WORD + 1u], 1u);")
                && body.contains("if ((flags & PATH_TRACE_FLAG_TRUNCATED) != 0u)"),
            "word 1: a pixel with at least one truncated path");
        assertTrue(body.contains("InterlockedAdd(stats[PATH_REJECT_WORD + 2u], 1u);")
                && body.contains("if ((flags & PATH_TRACE_FLAG_VISIBILITY_OVERFLOW) != 0u)"),
            "word 2: a pixel with at least one shadow walk past its filter budget");
        assertTrue(body.contains("InterlockedAdd(stats[PATH_REJECT_WORD + 3u], 1u);"), "word 3: every metered pixel");
        assertTrue(body.contains("if (count < PATH_REJECT_SLOT_WORDS) return;"),
            "a stand-in shorter than the slot is never written: the host then reads n/a");
        assertTrue(entry.contains("mean.flags |= result.flags;"), "the union over every sample of the pixel");
    }

    @Test
    void theTracerRaisesTheOverflowFlagWhereTheShadowWalkGaveUp() throws IOException {
        String tracer = read(TRACER);
        assertTrue(tracer.contains("static const uint PATH_TRACE_FLAG_VISIBILITY_OVERFLOW = 1u << 13u;"),
            "bit 13 is the first free sample flag after RC_UNRESOLVED");
        assertTrue(tracer.contains("if ((shadow.flags & SURFACE_VISIBILITY_FLAG_OVERFLOW) != 0u) {\n"
                + "        pathFlags |= PATH_TRACE_FLAG_VISIBILITY_OVERFLOW;"),
            "the direct term reports the walk that proved nothing");
        assertEquals(3, count(tracer, "Occluded, result.flags)"), "each of the three direct-term call sites hands the path's own flag word in");
    }

    @Test
    void everyTraceRootDeclaresTheSlotAtThirtySeven() throws IOException {
        for (String root : new String[] {"passes/path_trace.slang", "passes/path_trace_scene.slang",
                "passes/rc_path_trace_scene.slang"}) {
            assertTrue(read(SHADERS.resolve(root)).contains(
                    "[[vk::binding(37, 0)]] RWStructuredBuffer<uint> outPathStats;"),
                root + " shares the entry body, so it declares the stats slot (or the native stand-in) too");
        }
        Assumptions.assumeTrue(SlangPassLibrary.available("path_trace"), "path_trace.slang was not compiled");
        SlangPassManifest manifest = SlangPassLibrary.load("path_trace").orElseThrow().manifest();
        SlangPassManifest.Binding binding = manifest.binding("outPathStats");
        assertNotNull(binding);
        assertEquals(37, binding.binding());
        assertEquals(BedrayCoreLayouts.DESCRIPTOR_TYPE_STORAGE_BUFFER, binding.descriptorType());
        assertEquals(4, binding.elementBytes());
    }

    // ---- host -------------------------------------------------------------------------------------------------------

    @Test
    void theHostPrintsTheSplitOnlyForAMeasurementItsOwnMaskAgreesWith() {
        long metered = RtStatsMetering.meteredPixels(256, 128, 3);
        RtStatsMetering.Breakdown split = RtStatsMetering.breakdown(256, 128, 3, 16, 64, 32, (int) metered);
        assertNotNull(split);
        assertEquals(100.0 * 16 / metered, split.invalidPct(), 1.0e-12);
        assertEquals(100.0 * 64 / metered, split.truncatedPct(), 1.0e-12);
        assertEquals(100.0 * 32 / metered, split.overflowPct(), 1.0e-12);
        assertNull(RtStatsMetering.breakdown(256, 128, 3, 0, 0, 0, 0),
            "a zero metered word is a stand-in or an unwritten slot, not a clean frame");
        assertNull(RtStatsMetering.breakdown(256, 128, 3, 0, 0, 0, (int) metered + 16),
            "a metered word the host mask disagrees with means the two masks drifted");
        assertNull(RtStatsMetering.breakdown(256, 128, 3, (int) metered + 1, 0, 0, (int) metered),
            "a count above the metered pixels is corrupt");
    }

    @Test
    void theHostBindsTheWholeSlotZeroesTheBlockAndReadsItBack() throws IOException {
        String host = read(TRACE_HOST);
        assertTrue(host.contains("new Expected(\"outPathStats\", BedrayCoreLayouts.DESCRIPTOR_TYPE_STORAGE_BUFFER)"));
        assertTrue(host.contains("pathStatsBuffer(pathStats, pathStatsDummy)"),
            "the frame's slot, or a one-word stand-in the shader refuses to write");
        String controller = read(CONTROLLER);
        assertTrue(controller.contains("new RtPathTraceSlangPass.BufferView(params.statsBuffer, params.statsOffset,\n"
                + "                RtStatsLayout.SLOT_STRIDE)"),
            "the frame hands the backend the whole slot: the counter head and the trace block");
        String nativeHost = read(NATIVE_HOST);
        assertTrue(nativeHost.contains("stats.offset(), RtFrameStatsSlangPass.COUNTER_BYTES, frame.frameIndex);"),
            "frame_stats still binds exactly its sixteen bytes on the native core");
        String renderer = read(RENDERER);
        assertTrue(renderer.contains("VK10.vkCmdFillBuffer(cmd, statsBuf.handle, writeOffset + RtStatsLayout.PATH_REJECTS, 16, 0);"),
            "a slot reused three frames later must not report the old frame's counts");
        assertTrue(renderer.contains("lastRejectSplit = RtStatsMetering.breakdown(metadata.width(), metadata.height(),"),
            "read back with the slot's own extent and rotation");
        assertTrue(renderer.contains("invalid_pct=%s trunc_pct=%s vis_overflow_pct=%s"), "and printed on the F3 line");
    }

    private static int count(String source, String needle) {
        int count = 0;
        for (int at = source.indexOf(needle); at >= 0; at = source.indexOf(needle, at + 1)) {
            count++;
        }
        return count;
    }

    private static String block(String source, String header) {
        int at = source.indexOf(header);
        assertTrue(at >= 0, "missing block: " + header);
        int depth = 0;
        for (int i = source.indexOf('{', at); i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return source.substring(at, i + 1);
                }
            }
        }
        throw new AssertionError("unbalanced block: " + header);
    }

    private static String read(Path path) throws IOException {
        return Files.readString(path, StandardCharsets.UTF_8).replace("\r\n", "\n");
    }
}
