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
import java.util.List;

import static io.github.andrexarlay.vulkancompat.rt.rc.RcGeometry.*;
import static io.github.andrexarlay.vulkancompat.rt.rc.RcTransport.*;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Temporal reuse of the RC v2 field (batch 5.6). The lattice is fixed relative to the raw camera and the angular cells
 * to the world axes, so the field of the previous recording is the same lattice displaced by the camera move. Each cell
 * the builder resolves is blended with the previous field at the same level and ray, read at
 * {@code probe + (this raw camera - the history's)} by trilinear weights over the eight lattice neighbours:
 * {@code L = lerp(L_now, L_then, w)}, {@code T = lerp(T_now, T_then, w)}, confidence the minimum. Nothing is blended
 * when the native owner says there is no history ({@code w = 0}: first frame, reset, a skipped frame, another axis),
 * when this frame's cell is unresolved, or when any required history neighbour is unresolved or outside the lattice -
 * missing data is never renormalised away. The previous tails stay in the owner's 192 MiB budget as one extra buffer.
 */
class RtRcFieldHistoryTest {

    private static final Path SHADERS = Path.of("src/main/resources/shaders/slang");
    private static final Path MAPPING = SHADERS.resolve("radiance_cascades/field_mapping.slang");
    private static final Path TRANSPORT = SHADERS.resolve("radiance_cascades/field_transport.slang");
    private static final Path BUILDER = SHADERS.resolve("passes/rc_field_build_scene.slang");

    // ---- model: one level of the lattice, one ray, scalar radiance -----------------------------------------------------

    /** Trilinear history at a displaced probe, or NaN when a required neighbour is missing (outside or unresolved). */
    private static double history(double[][][] field, double first, double spacing, double[] position) {
        int axis = field.length;
        double[] u = new double[3];
        int[] base = new int[3];
        double[] fraction = new double[3];
        for (int i = 0; i < 3; i++) {
            u[i] = (position[i] - first) / spacing;
            base[i] = (int) Math.floor(u[i]);
            fraction[i] = u[i] - base[i];
            if (base[i] < 0 || base[i] >= axis || base[i] + (fraction[i] > 0 ? 1 : 0) >= axis) return Double.NaN;
        }
        double sum = 0.0;
        for (int corner = 0; corner < 8; corner++) {
            double w = 1.0;
            int[] at = new int[3];
            for (int i = 0; i < 3; i++) {
                boolean high = ((corner >> i) & 1) != 0;
                w *= high ? fraction[i] : 1.0 - fraction[i];
                at[i] = base[i] + (high ? 1 : 0);
            }
            if (w == 0.0) continue;
            double value = field[at[0]][at[1]][at[2]];
            if (Double.isNaN(value)) return Double.NaN;
            sum += w * value;
        }
        return sum;
    }

    private static double blend(double now, double then, double weight) {
        if (!(weight > 0.0) || Double.isNaN(now) || Double.isNaN(then)) return now;
        return now + (then - now) * weight;
    }

    @Test
    void aStillCameraBlendsEachCellWithItself() {
        double[][][] previous = lattice(4, 1.0);
        double first = -1.5;
        for (int x = 0; x < 4; x++) {
            double[] probe = {first + x, first + 1, first + 2};
            double then = history(previous, first, 1.0, probe);
            assertEquals(previous[x][1][2], then, 1.0e-12, "no move: the history is the same cell");
            assertEquals(0.2 * 7.0 + 0.8 * then, blend(7.0, then, 0.8), 1.0e-12);
        }
    }

    @Test
    void aMoveOfWholeProbesReadsTheNeighbourAndAFractionInterpolates() {
        double[][][] previous = lattice(4, 1.0);
        double first = -1.5;
        double[] probe = {first + 1, first + 1, first + 1};
        double[] moved = {probe[0] + 1.0, probe[1], probe[2]};
        assertEquals(previous[2][1][1], history(previous, first, 1.0, moved), 1.0e-12,
            "the camera moved one probe: the point now at this probe was the neighbour's");
        double[] half = {probe[0] + 0.5, probe[1], probe[2]};
        assertEquals(0.5 * (previous[1][1][1] + previous[2][1][1]), history(previous, first, 1.0, half), 1.0e-12);
    }

    @Test
    void missingHistoryIsNeverRenormalisedAway() {
        double[][][] previous = lattice(4, 1.0);
        double first = -1.5;
        previous[2][1][1] = Double.NaN;   // an unresolved neighbour
        double[] half = {first + 1.5, first + 1, first + 1};
        assertTrue(Double.isNaN(history(previous, first, 1.0, half)), "a required unresolved neighbour drops the history");
        assertEquals(5.0, blend(5.0, history(previous, first, 1.0, half), 0.8), 0.0, "and the cell keeps this frame's value");
        double[] outside = {first - 0.25, first, first};
        assertTrue(Double.isNaN(history(previous, first, 1.0, outside)), "outside the lattice there is no history");
        assertEquals(5.0, blend(5.0, 9.0, 0.0), 0.0, "weight 0 (first frame, reset) is the field without reuse");
        assertTrue(Double.isNaN(blend(Double.NaN, 9.0, 0.8)), "an unresolved cell is not resurrected by its history");
    }

    /** With independent per-frame noise the blend is an exponential mean: its variance falls to (1-w)/(1+w). */
    @Test
    void theBlendIsAnExponentialMeanOfTheFrames() {
        java.util.SplittableRandom random = new java.util.SplittableRandom(56);
        double w = 0.8;
        double value = 0.0;
        double sum = 0.0;
        double sum2 = 0.0;
        int frames = 400_000;
        for (int i = 0; i < frames; i++) {
            double sample = 1.0 + (random.nextDouble() - 0.5) * Math.sqrt(12.0);   // mean 1, variance 1
            value = i == 0 ? sample : blend(sample, value, w);
            if (i > 100) { sum += value; sum2 += value * value; }
        }
        int n = frames - 101;
        double mean = sum / n;
        double variance = sum2 / n - mean * mean;
        assertEquals(1.0, mean, 0.01, "unbiased for a static field");
        assertEquals((1.0 - w) / (1.0 + w), variance, 0.01, "variance 1 -> 0.111 at w = 0.8");
    }

    private static double[][][] lattice(int axis, double scale) {
        double[][][] field = new double[axis][axis][axis];
        for (int x = 0; x < axis; x++)
            for (int y = 0; y < axis; y++)
                for (int z = 0; z < axis; z++)
                    field[x][y][z] = scale * (x + 10 * y + 100 * z);
        return field;
    }

    // ---- shader and host contract ----------------------------------------------------------------------------------

    @Test
    void theShaderBlendsAsTheModelDoes() throws IOException {
        String mapping = read(MAPPING);
        assertTrue(mapping.contains("    float4 history;     // xyz: this raw camera minus the history field's, w: history weight (0 = none)\n"
                + "};                      // 288 bytes"),
            "the metadata carries the camera move and the weight the native owner decided");
        assertTrue(mapping.contains("&& isFinite(f.history.xyz) && isFinite(f.history.w) && f.history.w >= 0.0 && f.history.w < 1.0;"),
            "a weight of 1 would freeze the field; a non-finite move is no history");
        String transport = read(TRANSPORT);
        assertTrue(transport.contains("float3 p = rcFieldProbe(field, level, probe) + field.history.xyz;")
                && transport.contains("if (!rcFieldNeighbors(field, level, p, base, fraction)) return current;")
                && transport.contains("RCSegment past = rcFieldAverageEnd(average);")
                && transport.contains("if (!rcSegmentValid(past)) return current;")
                && transport.contains("rcFieldAverageAdd(temporal, current, 1.0 - w);")
                && transport.contains("rcFieldAverageAdd(temporal, past, w);")
                && transport.contains("return rcFieldAverageEnd(temporal);"),
            "same level, same ray, trilinear, all-or-nothing, lerp by the owner's weight");
        assertTrue(transport.contains("if (!(w > 0.0) || !rcSegmentValid(current)) return current;"));
        String builder = read(BUILDER);
        assertTrue(builder.contains("[[vk::binding(38,0)]] StructuredBuffer<RCSegment> rcHistory;"));
        assertTrue(builder.contains("rcTails[field.counts[pc.level].w + tid.x] = rcFieldBlendHistory(field, rcHistory, pc.level, probe,\n"
                + "        tid.x % rays, current);"),
            "each level is blended before the next finer level reads it");
    }

    @Test
    void temporalInterpolationNeedsBothContributorsToProveTheSurvivingTail() {
        Segment opaque = interval(new Vec3(5, 3, 1), ZERO, 0.7);
        Segment bounded = finiteMiss();
        Segment opening = average(List.of(new WeightedSample(0.2, opaque), new WeightedSample(0.8, bounded)));
        assertEquals(0.8, opening.transmission().x(), 1e-15);
        assertTrue(opening.valid());
        assertFalse(opening.opaque() || opening.terminalKnown(),
            "a current opaque cell blended with bounded history is no longer a terminal proof");
        Segment closing = average(List.of(new WeightedSample(0.2, bounded), new WeightedSample(0.8, opaque)));
        assertEquals(0.2, closing.transmission().x(), 1e-15);
        assertFalse(closing.opaque() || closing.terminalKnown());
        assertEquals(0.7, opening.confidence(), "confidence is minimum evidence, not the temporal weight");
        Segment bothOpaque = average(List.of(new WeightedSample(0.2, opaque), new WeightedSample(0.8, opaque)));
        assertTrue(bothOpaque.opaque() && bothOpaque.terminalKnown());
        Segment tinyTail = interval(ZERO, new Vec3(1e-30, 0, 0), 1);
        Segment almostOpaque = average(List.of(new WeightedSample(0.2, opaque), new WeightedSample(0.8, tinyTail)));
        assertTrue(almostOpaque.transmission().x() > 0 && !almostOpaque.terminalKnown(),
            "small surviving transport still needs its back boundary");
    }

    @Test
    void temporalMetadataUsesTheSameClassificationAndClampEvidenceAsSpatialReads() throws IOException {
        String transport = read(TRANSPORT);
        int start = transport.indexOf("RCSegment rcFieldBlendHistory(");
        String temporal = transport.substring(start);
        assertTrue(temporal.contains("RCFieldAverage temporal = rcFieldAverageBegin();"));
        assertTrue(temporal.contains("rcFieldAverageAdd(temporal, current, 1.0 - w);"));
        assertTrue(temporal.contains("rcFieldAverageAdd(temporal, past, w);"));
        assertTrue(temporal.contains("return rcFieldAverageEnd(temporal);"));
        assertFalse(temporal.contains("RCSegment blended = current;"),
            "classifications cannot be copied from just the current contributor");
        assertTrue(transport.contains("a.diagnostics |= s.flags & RC_SEGMENT_CLAMPED;"),
            "clamping in either contributing frame remains observable");
        assertTrue(transport.contains("a.terminalKnown = a.terminalKnown &&"));
        assertTrue(transport.contains("if (all(s.transmittance == float3(0.0))) s.flags |= RC_SEGMENT_BLOCKED;"));
    }

    @Test
    void theHostContractNamesTheHistoryBinding() {
        Assumptions.assumeTrue(SlangPassLibrary.available(RtRadianceCascadesContract.BUILD), "RC builder not compiled");
        SlangPassManifest builder = SlangPassLibrary.load(RtRadianceCascadesContract.BUILD).orElseThrow().manifest();
        RtRadianceCascadesContract.requireBuilder(builder);
        assertEquals(17, builder.bindings().size());
        SlangPassManifest.Binding history = builder.binding("rcHistory");
        assertNotNull(history);
        assertEquals(38, history.binding());
        assertEquals(BedrayCoreLayouts.DESCRIPTOR_TYPE_STORAGE_BUFFER, history.descriptorType());
        assertEquals(32, history.elementBytes());
        assertEquals(288, builder.binding("rcField").elementBytes());
    }

    private static String read(Path path) throws IOException {
        return Files.readString(path, StandardCharsets.UTF_8).replace("\r\n", "\n");
    }
}
