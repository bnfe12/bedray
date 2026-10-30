package io.github.andrexarlay.vulkancompat.rt.world;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The reservoir normalisation W = wSum / (M * p-hat) is unbiased only while M is the number of attempts the sampler
 * made, independent of what those attempts produced. v3's ptAddPath gated on ptPathReusable, so a sky, emitter,
 * delta or glossy first edge never reached M: the estimate became conditional on a recorded reusable hit and was
 * scaled by 1/P(recorded). In a scene where half the first edges escape to the sky that is a factor of two, and the
 * module could not see it because no test exercised the add path against a closed-form integral.
 *
 * <p>Admission and reuse are different questions, and v4 splits them: ptAddPath admits every valid attempt, while
 * ptMergeReservoir keeps the certified-class gate that refuses to transport a path whose suffix radiance depends on
 * the incoming edge. This test pins the split in the shader source and validates the estimator on the same flatland
 * scene the change-of-measure test uses: the source anchor at x=0 and the receiver at x=0.2 share a floor in front of
 * a diffuse wall at x=2, the wall's radiance is 0.5, and the receiver's cosine-sampled indirect integral over the
 * wall half of the hemisphere is 0.6 * 0.5 * 0.5 = 0.15.
 *
 * <p>Ways this silently breaks again: the add path returns to ptPathReusable and drops the attempts that cannot be
 * reused; the merge loses the attempt count when the incoming record carries no winner; or the resolve normalises by
 * a hit count instead of the attempt count. The model below is the module's own arithmetic, including the target's
 * MIN_PDF floor, which is what keeps a non-contributing attempt from ever winning the reservoir.
 */
class RestirPtReservoirInvariantTest {

    private static final Path SHADER =
        Path.of("src/main/resources/shaders/slang/experimental/restir_pt.slang");

    private static final double SOURCE_X = 0.0;
    private static final double RECEIVER_X = 0.2;
    private static final double WALL_X = 2.0;
    private static final double RECEIVER_ALBEDO = 0.6;
    private static final double WALL_RADIANCE = 0.5;
    private static final double ANALYTIC = RECEIVER_ALBEDO * WALL_RADIANCE * 0.5;

    /** Cosine sampling is symmetric in azimuth, so exactly half of the first edges reach the wall. */
    private static final double WALL_HIT_PROBABILITY = 0.5;

    private static final double MIN_PDF = 1.0e-8;
    private static final double MIN_COSINE = 0.05;
    private static final double MIN_EDGE_COSINE = 1.0e-3;
    private static final double MIN_DISTANCE = 1.0e-4;
    private static final double MAX_CANDIDATE_WEIGHT = 64.0;
    private static final double MAX_RESOLVED_WEIGHT = 1.0e4;
    private static final double MAX_M = 400.0;
    private static final double M_CAP = 20.0;
    private static final double TEMPORAL_RATIO_CAP = 8.0;
    private static final double SKY_DISTANCE = 1.0e3;

    private static final int HISTORY_ATTEMPTS = 4;
    private static final int FRESH_ATTEMPTS = 2;

    /**
     * Admission is not reuse. The add path must count every valid attempt, and the only surviving rejection is the
     * zero-density path the sampler never chose. The merge keeps the certified-class gate, because that is the step
     * that transports a stored density to another receiver.
     */
    @Test
    void admissionCountsEveryAttemptWhileReuseStaysInTheMerge() throws IOException {
        String shader = Files.readString(SHADER, StandardCharsets.UTF_8);
        assertTrue(shader.contains("if (!ptPathValid(path) || path.first.pdf <= 0.0) return reservoir;"),
            "ptAddPath must admit every valid attempt: a sky, emitter, delta or glossy first edge is still a draw"
                + " from the first-edge density, and dropping it makes M conditional on a recorded hit");
        assertFalse(shader.contains("ptPathReusable(path)"),
            "the reusable-class gate does not belong in the add path; it stays in ptMergeReservoir");
        assertTrue(shader.contains("if (!ptPathReusable(incoming.sample)) return current;"),
            "reuse is still restricted to the certified diffuse-first class in the merge");
    }

    /**
     * The sharpest pair: the same fresh reservoir, the same two attempts, only the admission rule differs. Counting
     * every attempt converges to the analytic indirect value; v3's gate keeps only wall hits, so its M is the number
     * of hits and the estimate is conditional on at least one hit, which is exactly 1/P(hit) too large.
     */
    @Test
    void aFreshReservoirIsUnbiasedOnlyWhenEveryAttemptCounts() {
        int trials = 300_000;
        SplittableRandom random = new SplittableRandom(0x5EEDL);
        RunningStatistics counted = new RunningStatistics(trials);
        RunningStatistics gated = new RunningStatistics(trials);
        for (int trial = 0; trial < trials; ++trial) {
            counted.record(resolve(build(RECEIVER_X, FRESH_ATTEMPTS, true, random), RECEIVER_X));
            gated.record(resolve(build(RECEIVER_X, FRESH_ATTEMPTS, false, random), RECEIVER_X));
        }

        assertUnbiased(counted, "a reservoir that counts its sky attempts resolves the analytic indirect term");
        double gatedExpected = (1.0 - Math.pow(1.0 - WALL_HIT_PROBABILITY, FRESH_ATTEMPTS))
            * ANALYTIC / WALL_HIT_PROBABILITY;
        assertEquals(gatedExpected, gated.mean(), Math.max(4.0 * gated.standardError(), gatedExpected * 0.02),
            "the v3 gate makes the estimate conditional on at least one hit (measured " + gated.mean() + ")");
        assertTrue(gated.mean() > ANALYTIC * 1.3,
            "if this stops holding the scene no longer separates attempt accounting from hit counting");
    }

    /**
     * Temporal reuse adds the transported history record to the same normalisation. Both steps must stay unbiased:
     * merging into an empty receiver transports the stored weight, and merging into the fresh receiver combines two
     * independently normalised attempt sets. The history's sky attempts count in its M even though its winner is a
     * wall hit, or the transported estimate is conditional on the same hit.
     */
    @Test
    void temporalReuseStaysUnbiasedWhenEveryAttemptCounts() {
        int trials = 300_000;
        SplittableRandom random = new SplittableRandom(0x5EEDL);
        RunningStatistics transported = new RunningStatistics(trials);
        RunningStatistics merged = new RunningStatistics(trials);
        for (int trial = 0; trial < trials; ++trial) {
            Reservoir history = build(SOURCE_X, HISTORY_ATTEMPTS, true, random);
            Reservoir intoEmpty = merge(new Reservoir(), history,
                target(history.sample, RECEIVER_X), shiftJacobian(history.sample), random);
            transported.record(resolve(intoEmpty, RECEIVER_X));

            Reservoir current = build(RECEIVER_X, FRESH_ATTEMPTS, true, random);
            Reservoir incoming = build(SOURCE_X, HISTORY_ATTEMPTS, true, random);
            Reservoir temporal = merge(current, incoming,
                target(incoming.sample, RECEIVER_X), shiftJacobian(incoming.sample), random);
            merged.record(resolve(temporal, RECEIVER_X));
        }

        assertUnbiased(transported, "a transported history record resolves the analytic indirect term");
        assertUnbiased(merged, "the temporal merge of two attempt sets resolves the analytic indirect term");
    }

    /**
     * W = wSum/(M*p-hat). The merge multiplies the stored weight sum by the target ratio and the change of measure,
     * and then normalises by the receiver target, so a candidate merged into an empty reservoir keeps exactly its
     * resolved weight times J. This is the algebra the Monte Carlo above exercises at scale; here it is exact.
     */
    @Test
    void mergingIntoAnEmptyReservoirTransportsTheResolvedWeightByTheJacobian() {
        Sample vertex = new Sample(WALL_X, 0.2, 0.7, -1.0, 0.0, 0.0, 0.42, 0.3, WALL_RADIANCE);
        Reservoir source = new Reservoir();
        source.add(vertex, target(vertex, SOURCE_X) / vertex.pdf(), target(vertex, SOURCE_X),
            new SplittableRandom(1L));
        double atSource = resolvedWeight(source, SOURCE_X);
        double jacobian = shiftJacobian(vertex);
        assertTrue(Math.abs(jacobian - 1.0) > 0.05,
            "the receiver must sit far enough from the writer for J to differ from one, or a missing correction"
                + " would pass this test");

        Reservoir merged = merge(new Reservoir(), source, target(vertex, RECEIVER_X), jacobian,
            new SplittableRandom(2L));
        assertEquals(atSource * jacobian, resolvedWeight(merged, RECEIVER_X), atSource * 1.0e-12,
            "merging into an empty reservoir transports W by J: the target ratio cancels against the receiver"
                + " normalisation and the change of measure is all that remains");
    }

    // ---- The module's estimator in doubles ----------------------------------------------------------------------

    /** One first edge as ptRecordPath stores it. Sky edges carry bsdfPdf = 0 and zero throughput (DI owns them). */
    private record Sample(double px, double py, double pz, double nx, double ny, double nz,
                          double pdf, double bsdfPdf, double radiance) {
    }

    private static final class Reservoir {
        double m;
        double weightSum;
        double target = -1.0;
        Sample sample;

        /** ptAddPath's WRS step, after the caller decided the attempt is admitted. */
        void add(Sample candidate, double weight, double targetPdf, SplittableRandom random) {
            m += 1.0;
            weightSum += weight;
            if (random.nextDouble() * weightSum < weight) {
                sample = candidate;
                target = targetPdf;
            }
        }
    }

    /** ptAddPath with `admitSky` selecting v4's admission rule or v3's ptPathReusable gate. */
    private static Reservoir build(double anchorX, int attempts, boolean admitSky, SplittableRandom random) {
        Reservoir reservoir = new Reservoir();
        for (int attempt = 0; attempt < attempts; ++attempt) {
            Sample sample = trace(anchorX, random);
            if (!admitSky && sample.radiance <= 0.0) continue;
            double targetPdf = target(sample, anchorX);
            if (targetPdf <= 0.0) continue;
            double weight = Math.min(targetPdf / Math.max(sample.pdf(), MIN_PDF), MAX_CANDIDATE_WEIGHT);
            if (!(weight > 0.0)) continue;
            reservoir.add(sample, weight, targetPdf, random);
        }
        return reservoir;
    }

    /** One cosine-sampled first edge from a floor anchor. A miss is recorded where ptVertexFromHit records it. */
    private static Sample trace(double anchorX, SplittableRandom random) {
        double u1 = random.nextDouble();
        double u2 = random.nextDouble();
        double radius = Math.sqrt(u1);
        double phi = 2.0 * Math.PI * u2;
        double wx = radius * Math.cos(phi);
        double wy = radius * Math.sin(phi);
        double wz = Math.sqrt(1.0 - u1);
        double pdf = wz / Math.PI;
        if (wx <= 1.0e-9) {
            double distance = SKY_DISTANCE;
            return new Sample(anchorX + wx * distance, wy * distance, wz * distance,
                -wx, -wy, -wz, pdf, 0.0, 0.0);
        }
        double distance = (WALL_X - anchorX) / wx;
        double py = distance * wy;
        double pz = distance * wz;
        double dx = WALL_X - anchorX;
        double length = Math.sqrt(dx * dx + py * py + pz * pz);
        return new Sample(WALL_X, py, pz, -1.0, 0.0, 0.0, pdf, (dx / length) / Math.PI, WALL_RADIANCE);
    }

    /** ptTargetPdf at a floor anchor with normal +z. */
    private static double target(Sample sample, double anchorX) {
        if (sample == null) return 0.0;
        double dx = sample.px() - anchorX;
        double dy = sample.py();
        double dz = sample.pz();
        double distanceSquared = dx * dx + dy * dy + dz * dz;
        if (distanceSquared <= MIN_DISTANCE * MIN_DISTANCE) return 0.0;
        double cosine = Math.max(MIN_COSINE, Math.min(1.0, Math.abs(dz) / Math.sqrt(distanceSquared)));
        return Math.max(sample.bsdfPdf(), MIN_PDF) * cosine;
    }

    /** ptFirstEdgeGeometry at a floor anchor: |cos at the vertex| / r^2. */
    private static double geometry(Sample sample, double anchorX) {
        double dx = anchorX - sample.px();
        double dy = -sample.py();
        double dz = -sample.pz();
        double distanceSquared = dx * dx + dy * dy + dz * dz;
        if (distanceSquared <= MIN_DISTANCE * MIN_DISTANCE) return 0.0;
        double distance = Math.sqrt(distanceSquared);
        double cosine = Math.abs(sample.nx() * dx + sample.ny() * dy + sample.nz() * dz) / distance;
        if (cosine < MIN_EDGE_COSINE) return 0.0;
        return cosine / distanceSquared;
    }

    private static double shiftJacobian(Sample sample) {
        double oldGeometry = geometry(sample, SOURCE_X);
        double newGeometry = geometry(sample, RECEIVER_X);
        if (oldGeometry <= 0.0 || newGeometry <= 0.0) return 0.0;
        return newGeometry / oldGeometry;
    }

    /** ptMergeReservoir, including its reset of a current that is not a usable accumulator. */
    private static Reservoir merge(Reservoir current, Reservoir incoming, double incomingTargetPdf,
            double shiftJacobian, SplittableRandom random) {
        if (incoming.m <= 0.0 || incoming.sample == null) return current;
        if (!(incoming.weightSum > 0.0) || !(incoming.target > 0.0)) return current;
        if (!(incomingTargetPdf > 0.0) || !(shiftJacobian > 0.0)) return current;
        if (!(current.m > 0.0 && current.weightSum > 0.0 && current.target > 0.0
                && current.sample != null)) {
            current = new Reservoir();
        }
        if (incoming.m > MAX_M) return current;

        double incomingM = Math.min(incoming.m, M_CAP);
        if (incomingM <= 0.0) return current;
        double ratio = Math.min(Math.max(
            incomingTargetPdf / Math.max(incoming.target, MIN_PDF) * shiftJacobian, 0.0), TEMPORAL_RATIO_CAP);
        double selectionScale = incomingM / Math.max(incoming.m, MIN_PDF);
        double incomingWeight = incoming.weightSum * selectionScale * ratio;
        if (!(incomingWeight > 0.0)) return current;

        double totalWeight = current.weightSum + incomingWeight;
        if (random.nextDouble() * totalWeight < incomingWeight) {
            current.sample = incoming.sample;
            current.target = incomingTargetPdf;
        }
        current.m += incomingM;
        current.weightSum = totalWeight;
        if (current.m > MAX_M) {
            double rescale = MAX_M / current.m;
            current.m *= rescale;
            current.weightSum *= rescale;
        }
        return current;
    }

    /** ptResolveDetailed's normalisation and the receiver-lobe shading, in doubles. */
    private static double resolve(Reservoir reservoir, double anchorX) {
        if (reservoir.sample == null) return 0.0;
        return resolvedWeight(reservoir, anchorX) * receiverLobe(reservoir.sample, anchorX)
            * reservoir.sample.radiance();
    }

    private static double resolvedWeight(Reservoir reservoir, double anchorX) {
        if (reservoir.m <= 0.0 || !(reservoir.weightSum > 0.0) || reservoir.sample == null) return 0.0;
        double targetPdf = target(reservoir.sample, anchorX);
        if (targetPdf <= 0.0) return 0.0;
        return Math.min(reservoir.weightSum / Math.max(reservoir.m * targetPdf, MIN_PDF),
            MAX_RESOLVED_WEIGHT);
    }

    private static double receiverLobe(Sample sample, double anchorX) {
        if (sample == null) return 0.0;
        double dx = sample.px() - anchorX;
        double dy = sample.py();
        double dz = sample.pz();
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        return RECEIVER_ALBEDO / Math.PI * (dz / distance);
    }

    private static void assertUnbiased(RunningStatistics statistics, String message) {
        assertEquals(ANALYTIC, statistics.mean(),
            Math.max(4.0 * statistics.standardError(), ANALYTIC * 0.01), message);
    }

    private static final class RunningStatistics {
        private final int count;
        private double sum;
        private double squares;

        RunningStatistics(int count) {
            this.count = count;
        }

        void record(double value) {
            sum += value;
            squares += value * value;
        }

        double mean() {
            return sum / count;
        }

        double standardError() {
            double mean = mean();
            double variance = Math.max(0.0, squares / count - mean * mean);
            return Math.sqrt(variance / count);
        }
    }
}
