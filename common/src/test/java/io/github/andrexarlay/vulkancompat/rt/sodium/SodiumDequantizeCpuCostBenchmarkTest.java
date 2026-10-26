package io.github.andrexarlay.vulkancompat.rt.sodium;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.Arrays;
import java.util.Locale;
import java.util.Random;

/**
 * OPT-IN CPU cost benchmark for the GPU dequantize refactor (module 2, commit 8a86ab9): measures the CPU work the
 * change removed from the section build and the bytes per quad it stopped copying, on synthetic Sodium vertices.
 *
 * <p>Not part of the normal suite: runs only with {@code -Dvkrtx.perf.bench=true} (forwarded by runShimTests) or the
 * environment variable {@code VKRTX_PERF_BENCH=1} (inherited by the forked Gradle test JVM):
 * <pre>
 *   VKRTX_PERF_BENCH=1 ./gradlew :common:test --tests "*SodiumDequantizeCpuCostBenchmarkTest" ...
 * </pre>
 *
 * <p>Paths measured, ns per quad, median over {@link #MEASURED_ROUNDS} rounds after {@link #WARMUP_ROUNDS} warmup:
 * <ol>
 *   <li>{@code decode}: the REAL {@link SodiumMeshDecoder#decode} on all three passes (worker-thread capture;
 *       byte-identical before and after the refactor - it is what a capture-only future would partially remove),</li>
 *   <li>{@code capture_only}: raw-word extraction only, the decoder's inner raw fill reconstructed verbatim (5 int
 *       reads + mask + store per vertex, no positions/uvs/normals). Production has NO standalone capture-only entry
 *       point (decode always computes everything), so this is the upper bound of any worker-side saving - a
 *       reconstructed loop, clearly separated from the real-decoder measurements;</li>
 *   <li>{@code stage_decoded_old}: the removed render-thread staging write (RtWorld at 8a86ab9^): positions+uvs as
 *       floats, colours+light as ints, into a direct buffer standing in for the mapped staging (112 B/quad);</li>
 *   <li>{@code stage_raw_new}: the current staging write: one raw-word memcpy (80 B/quad written, 84 B/quad
 *       reserved by the fixed flag plane, head 13c71b7).</li>
 * </ol>
 *
 * <p>A JVM microbenchmark cannot show the GPU dispatch cost of sodium_dequantize.comp or end-to-end chunk-generation
 * time; only CPU-side deltas are claimed. Assertions are loose structural bounds that fire only on catastrophic
 * regression, never tight perf thresholds (CI must stay non-flaky).
 */
public final class SodiumDequantizeCpuCostBenchmarkTest {

    /** 96 sections x 2500 quads (solid 2200 + cutout 240 + translucent 60): the dense-section scale of a real view. */
    static final int SECTIONS = 96;
    static final int SOLID_QUADS = 2200;
    static final int CUTOUT_QUADS = 240;
    static final int TRANSLUCENT_QUADS = 60;
    static final int QUADS_PER_SECTION = SOLID_QUADS + CUTOUT_QUADS + TRANSLUCENT_QUADS;

    static final int WARMUP_ROUNDS = 3;
    static final int MEASURED_ROUNDS = 15;

    /** Bytes per quad crossing the CPU: old staged-and-copied geometry vs new raw capture, exactly. */
    static final long OLD_BYTES_PER_QUAD = 112L;         // 12 pos + 8 uv floats + 4 colour + 4 light ints
    static final long NEW_BYTES_RESERVED_PER_QUAD = 84L; // 20 raw words + 4 B fixed flag plane
    static final long NEW_BYTES_WRITTEN_UNTAGGED = 80L;  // flag plane written only when foliage is tagged
    static final long SOURCE_BYTES_PER_QUAD = 80L;       // the Sodium vertex data both paths read

    static boolean benchEnabled() {
        return "true".equals(System.getProperty("vkrtx.perf.bench")) || "1".equals(System.getenv("VKRTX_PERF_BENCH"));
    }

    // ---- synthetic input (encoder mirrors CompactChunkVertex, as in SodiumMeshDecoderChecks) ----

    private static int quantizePosition(float position) {
        return (int) (((8.0f + position) / 32.0f) * 1048576.0f) & 1048575;
    }

    private static void putVertex(ByteBuffer b, float x, float y, float z, int color, int w3, int w4) {
        int qx = quantizePosition(x);
        int qy = quantizePosition(y);
        int qz = quantizePosition(z);
        b.putInt((qx >>> 10 & 1023) | (qy >>> 10 & 1023) << 10 | (qz >>> 10 & 1023) << 20);
        b.putInt((qx & 1023) | (qy & 1023) << 10 | (qz & 1023) << 20);
        b.putInt(color);
        b.putInt(w3);
        b.putInt(w4);
    }

    /** Face-like axis-aligned quad at a quantised grid position, UVs strictly inside a 16x16 sprite cell. */
    private static void putQuad(ByteBuffer b, Random rnd) {
        int axis = rnd.nextInt(3);
        float base = Math.round(rnd.nextFloat() * 160f) / 10f; // 0.0 .. 16.0
        int bu = rnd.nextInt(32) * 1024;                        // sprite cell origin (1/32 of a half-atlas axis)
        int cell = 512;                                         // sprite cell size
        int color = 0xFF000000 | rnd.nextInt(1 << 24);
        int w4 = rnd.nextInt(256) | (rnd.nextInt(256) << 8) | (rnd.nextInt(256) << 16) | (rnd.nextInt(256) << 24);
        int a1 = (axis + 1) % 3; // in-plane axes of the face perpendicular to `axis`
        int a2 = (axis + 2) % 3;
        int[][] corners = { { 0, 0 }, { 1, 0 }, { 1, 1 }, { 0, 1 } };
        float spanX = 0.5f + rnd.nextFloat(); // face side lengths, 0.5..1.5 blocks
        float spanY = 0.5f + rnd.nextFloat();
        float originA1 = rnd.nextFloat() * (16f - spanX);
        float originA2 = rnd.nextFloat() * (16f - spanY);
        for (int k = 0; k < 4; k++) {
            float[] p = new float[3];
            p[axis] = base;
            p[a1] = originA1 + corners[k][0] * spanX;
            p[a2] = originA2 + corners[k][1] * spanY;
            int uu = bu + (int) (corners[k][0] * (cell - 1));
            int vv = bu + (int) (corners[k][1] * (cell - 1));
            int w3 = (rnd.nextBoolean() ? 0x8000 : 0) | uu | (rnd.nextBoolean() ? 0x80000000 : 0) | (vv << 16);
            putVertex(b, p[0], p[1], p[2], color, w3, w4);
        }
    }

    private static ByteBuffer quadBuffer(int quads, Random rnd) {
        ByteBuffer b = ByteBuffer.allocate(quads * SodiumMeshDecoder.QUAD_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        for (int q = 0; q < quads; q++) {
            putQuad(b, rnd);
        }
        return b;
    }

    private static ByteBuffer[] buildSection(Random rnd) {
        return new ByteBuffer[] { quadBuffer(SOLID_QUADS, rnd), quadBuffer(CUTOUT_QUADS, rnd),
            quadBuffer(TRANSLUCENT_QUADS, rnd) };
    }

    // ---- the measured paths ----

    /** The decoder's raw fill verbatim: 5 int reads + section-index mask + store per vertex, nothing else. */
    private static void captureInto(ByteBuffer s, int[] dst, int quadBase) {
        s.position(0);
        int quads = s.limit() / SodiumMeshDecoder.QUAD_BYTES;
        for (int i = 0; i < quads; i++) {
            int o = i * SodiumMeshDecoder.QUAD_BYTES;
            int ro = (quadBase + i) * 20;
            for (int v = 0; v < 4; v++, o += SodiumMeshDecoder.STRIDE) {
                dst[ro] = s.getInt(o);
                dst[ro + 1] = s.getInt(o + 4);
                dst[ro + 2] = s.getInt(o + 8);
                dst[ro + 3] = s.getInt(o + 12);
                dst[ro + 4] = s.getInt(o + 16) & 0x00FFFFFF;
            }
        }
        dst[dst.length - 1] = 0x00FFFFFF & dst[19]; // keep the sink non-trivial against dead-code elimination
    }

    /** The removed CPU staging write (RtWorld at 8a86ab9^): decoded arrays into mapped staging, 112 B/quad. */
    private static void stageDecoded(ByteBuffer staging, DecodedSection d, int quads) {
        int off = staging.position();
        FloatBuffer fb = staging.slice(off, staging.capacity() - off).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer();
        fb.put(d.positions, 0, quads * 12);
        fb.put(d.uvs, 0, quads * 8);
        IntBuffer ib = staging.slice(off + (int) (80L * quads), staging.capacity() - off - (int) (80L * quads))
            .order(ByteOrder.LITTLE_ENDIAN).asIntBuffer();
        ib.put(d.colors, 0, quads * 4);
        ib.put(d.light, 0, quads * 4); // blockLookup is null in the bench: untagged light, exactly the old fast path
        staging.position(off + (int) (OLD_BYTES_PER_QUAD * quads));
    }

    /** The current CPU staging write (RtWorld at head): raw-word memcpy; 80 B/quad written, 84 B/quad consumed. */
    private static void stageRawWords(ByteBuffer staging, DecodedSection d, int quads) {
        int off = staging.position();
        IntBuffer sb = staging.slice(off, staging.capacity() - off).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer();
        sb.put(d.rawWords, 0, quads * 20);
        staging.position(off + (int) (NEW_BYTES_RESERVED_PER_QUAD * quads)); // fixed flag plane reserved, untagged
    }

    private static double median(double[] v) {
        double[] s = v.clone();
        Arrays.sort(s);
        return s[s.length / 2];
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.1f", v);
    }

    private static void printStat(String name, double[] samples) {
        double[] s = samples.clone();
        Arrays.sort(s);
        System.out.println("[VKB-PERF-BENCH] " + name + " median=" + fmt(median(s)) + " min=" + fmt(s[0])
            + " max=" + fmt(s[s.length - 1]) + " p25=" + fmt(s[s.length / 4]) + " p75=" + fmt(s[s.length * 3 / 4]));
    }

    private static double mbPerS(long bytesPerQuad, double nsPerQuad) {
        return bytesPerQuad / nsPerQuad * 1e9 / 1e6;
    }

    @Test
    public void measureCpuCostOfDequantizeRefactor() {
        Assumptions.assumeTrue(benchEnabled(), "set -Dvkrtx.perf.bench=true or VKRTX_PERF_BENCH=1 to run the benchmark");

        long totalQuads = (long) SECTIONS * QUADS_PER_SECTION;
        Random rnd = new Random(20261026L);
        ByteBuffer[][] sources = new ByteBuffer[SECTIONS][];
        for (int s = 0; s < SECTIONS; s++) {
            sources[s] = buildSection(rnd);
        }

        // staging stand-in: the whole mapped staging buffer of RtWorld (3 x 16 MiB segments); one round writes
        // 96 x 2500 x 112 B = 26.9 MiB, which fits
        ByteBuffer staging = ByteBuffer.allocateDirect(3 * (16 << 20)).order(ByteOrder.LITTLE_ENDIAN);
        int[] captureSink = new int[QUADS_PER_SECTION * 20 + 1];

        DecodedSection[] pool = new DecodedSection[SECTIONS];
        double[] decodeNs = new double[MEASURED_ROUNDS];
        double[] captureNs = new double[MEASURED_ROUNDS];
        double[] stageOldNs = new double[MEASURED_ROUNDS];
        double[] stageNewNs = new double[MEASURED_ROUNDS];

        for (int r = 0; r < WARMUP_ROUNDS + MEASURED_ROUNDS; r++) {
            boolean timed = r >= WARMUP_ROUNDS;
            int mi = r - WARMUP_ROUNDS;

            long t0 = System.nanoTime();
            for (int s = 0; s < SECTIONS; s++) {
                pool[s] = SodiumMeshDecoder.decode(s, 0, s / 16 - 4, 0, sources[s]);
            }
            long t1 = System.nanoTime();
            if (timed) {
                decodeNs[mi] = (double) (t1 - t0) / totalQuads;
            }

            t0 = System.nanoTime();
            for (int s = 0; s < SECTIONS; s++) {
                int done = 0;
                for (int p = 0; p < DecodedSection.PASS_COUNT; p++) {
                    ByteBuffer dup = sources[s][p].duplicate().order(ByteOrder.LITTLE_ENDIAN);
                    captureInto(dup, captureSink, done);
                    done += dup.limit() / SodiumMeshDecoder.QUAD_BYTES;
                }
            }
            t1 = System.nanoTime();
            if (timed) {
                captureNs[mi] = (double) (t1 - t0) / totalQuads;
            }

            staging.clear();
            t0 = System.nanoTime();
            for (int s = 0; s < SECTIONS; s++) {
                stageDecoded(staging, pool[s], pool[s].totalQuads());
            }
            t1 = System.nanoTime();
            if (timed) {
                stageOldNs[mi] = (double) (t1 - t0) / totalQuads;
            }

            staging.clear();
            t0 = System.nanoTime();
            for (int s = 0; s < SECTIONS; s++) {
                stageRawWords(staging, pool[s], pool[s].totalQuads());
            }
            t1 = System.nanoTime();
            if (timed) {
                stageNewNs[mi] = (double) (t1 - t0) / totalQuads;
            }
        }

        double medDecode = median(decodeNs);
        double medCapture = median(captureNs);
        double medStageOld = median(stageOldNs);
        double medStageNew = median(stageNewNs);

        // structural bounds (correctness guards, not perf targets)
        Assertions.assertEquals(QUADS_PER_SECTION, pool[0].totalQuads(), "synthetic section decodes to the expected quads");
        Assertions.assertEquals(QUADS_PER_SECTION * 20, pool[0].rawWords.length, "raw words: 20 ints per quad retained");
        Assertions.assertTrue(medCapture < medDecode, "capture-only must stay far cheaper than the full decode");
        Assertions.assertTrue(medStageNew <= medStageOld * 1.5, "raw staging must not blow up past decoded staging");
        Assertions.assertTrue(medDecode + medStageOld < 50_000, "catastrophic-regression bound: old pipeline < 50 us/quad");

        System.out.println("[VKB-PERF-BENCH] sections=" + SECTIONS + " quads_per_section=" + QUADS_PER_SECTION
            + " warmup_rounds=" + WARMUP_ROUNDS + " measured_rounds=" + MEASURED_ROUNDS + " total_quads=" + totalQuads);
        printStat("decode_ns_per_quad", decodeNs);
        printStat("capture_only_ns_per_quad", captureNs);
        printStat("stage_decoded_old_ns_per_quad", stageOldNs);
        printStat("stage_raw_new_ns_per_quad", stageNewNs);
        System.out.println("[VKB-PERF-BENCH] cpu_delta_staging_ns_per_quad median=" + fmt(medStageOld - medStageNew)
            + " pct_of_staging=" + fmt(100.0 * (medStageOld - medStageNew) / medStageOld)
            + " pct_of_capture_plus_staging=" + fmt(100.0 * (medStageOld - medStageNew) / (medCapture + medStageOld))
            + " pct_of_full_old_pipeline=" + fmt(100.0 * (medStageOld - medStageNew) / (medDecode + medStageOld)));
        System.out.println("[VKB-PERF-BENCH] hypothetical_worker_saving_pct (capture_only vs full decode, upper bound)="
            + fmt(100.0 * (1.0 - medCapture / medDecode)));
        System.out.println("[VKB-PERF-BENCH] bytes_per_quad staged_old=" + OLD_BYTES_PER_QUAD
            + " staged_new_written_untagged=" + NEW_BYTES_WRITTEN_UNTAGGED
            + " staged_new_reserved=" + NEW_BYTES_RESERVED_PER_QUAD
            + " upload_reduction_pct=" + fmt(100.0 * (1.0 - (double) NEW_BYTES_RESERVED_PER_QUAD / OLD_BYTES_PER_QUAD))
            + " staging_to_arena_copy_old=112B_per_quad new=0B_geometry_only_8B_per_cutout_quad_micromap_tail");
        System.out.println("[VKB-PERF-BENCH] mb_per_s decode=" + fmt(mbPerS(SOURCE_BYTES_PER_QUAD, medDecode))
            + " capture_only=" + fmt(mbPerS(SOURCE_BYTES_PER_QUAD, medCapture))
            + " stage_old=" + fmt(mbPerS(OLD_BYTES_PER_QUAD, medStageOld))
            + " stage_new=" + fmt(mbPerS(NEW_BYTES_WRITTEN_UNTAGGED, medStageNew)));
        System.out.println("[VKB-PERF-BENCH] scope_limits gpu_dispatch_cost=not_measurable_in_jvm"
            + " end_to_end_chunk_generation=not_measurable_without_running_game headless_vulkan_device=absent_in_tests");
    }
}
