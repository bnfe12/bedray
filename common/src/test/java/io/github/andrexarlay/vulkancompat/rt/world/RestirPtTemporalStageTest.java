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
 * The live temporal stage of ReSTIR PT (path_trace_entry + restir/pt_temporal.slang). Three invariants decide whether
 * switching it on can change the picture in a way nobody asked for:
 *
 * <ol>
 *   <li><b>Every attempt is counted.</b> The first sample of a pixel is one draw from the first-edge density whatever it
 *   produced: a specular lobe, a truncation, a sky or emitter first edge, a glossy first vertex. Each of them has to
 *   add 1 to {@code M} - as a zero-contribution attempt - or {@code W = wSum / (M * p-hat)} is conditioned on a
 *   recorded candidate and scaled by {@code 1/P(recorded)}, the exact defect v4 closed in the module. That includes the
 *   merge: an accumulator holding only attempts ({@code M > 0}, {@code wSum = 0}) must not be reset by the history it
 *   merges.</li>
 *   <li><b>Ownership.</b> The reservoir owns exactly the diffuse continuation of the primary vertex whose first scattered
 *   vertex is certified diffuse and does not emit. Everything else stays in the path that traced it, so the stage never
 *   counts a term twice and never drops one.</li>
 *   <li><b>First-frame identity.</b> With no history, {@code M = 1} and the resolved {@code f * L * W} equals the suffix
 *   the stage took out of the path, so PT on and PT off are the same image.</li>
 * </ol>
 *
 * <p>The model below is the module's arithmetic in double precision (target, candidate cap, resolve) plus the diffuse
 * branch of {@code bsdfSample}; the source guards pin the shader lines the model stands for.
 */
class RestirPtTemporalStageTest {

    private static final Path SHADERS = Path.of("src/main/resources/shaders/slang");
    private static final Path MODULE = SHADERS.resolve("experimental/restir_pt.slang");
    private static final Path STAGE = SHADERS.resolve("restir/pt_temporal.slang");
    private static final Path TRACER = SHADERS.resolve("pathtrace/path_tracer.slang");
    private static final Path ENTRY = SHADERS.resolve("pathtrace/path_trace_entry.slang");
    private static final Path BSDF = SHADERS.resolve("shading/bsdf.slang");

    private static final double MIN_PDF = 1.0e-8;
    private static final double MIN_COSINE = 0.05;
    private static final double MAX_CANDIDATE_WEIGHT = 64.0;
    private static final double MAX_RESOLVED_WEIGHT = 1.0e4;
    private static final double M_CAP = 20.0;
    private static final double TEMPORAL_RATIO_CAP = 8.0;
    private static final double MAX_M = 400.0;

    // ---- source guards ---------------------------------------------------------------------------------------------

    @Test
    void theTracerRecordsTheFirstVertexBehindItsOwnOptionBit() throws IOException {
        String tracer = read(TRACER);
        assertTrue(tracer.contains("static const uint PATH_TRACE_OPTION_RESTIR_PT        = 1u << 17u;"),
            "bit 17 is the first free bit: 8..15 carry the pixel-shadow cell and 16 the block-emitter switch");
        assertTrue(tracer.contains("result.pt.radianceBefore = result.radiance;"),
            "the suffix is what the path gathered after the continuation left the primary vertex");
        assertTrue(tracer.contains("result.pt.suffixContribution = radianceSuffix;"),
            "and the stage subtracts exactly that, not a reconstruction of it");
        assertTrue(tracer.contains("ptTerminalEmitsMaterial(surface.material)"),
            "the emitter decision is the decoded material's, never HIT_FLAG_EMISSIVE");
        String entry = read(ENTRY);
        assertTrue(entry.contains("options.flags = s == 0u ? pc.flags : (pc.flags & ~PATH_TRACE_OPTION_RESTIR_PT);"),
            "only the first ray of a pixel owns the record: the others keep their suffixes and trace as before");
    }

    @Test
    void everyRootBindsTheReservoirRingAtThirtySix() throws IOException {
        for (String root : new String[] {"passes/path_trace.slang", "passes/path_trace_scene.slang",
                "passes/rc_path_trace_scene.slang"}) {
            assertTrue(read(SHADERS.resolve(root)).contains(
                    "[[vk::binding(36, 0)]] RWStructuredBuffer<RestirPtReservoir> ptReservoirs;"),
                root + " shares the entry body, so it has to declare the PT ring (or the native dummy) too");
        }
    }

    /** Invariant 1 in the shader: every branch that does not admit a candidate still adds the attempt. */
    @Test
    void everyNonCandidateIsStillAnAttempt() throws IOException {
        String stage = read(STAGE);
        assertTrue(stage.contains("current = ptAddEmptyAttempt(current);"),
            "a specular lobe, a truncation, a terminal or uncertified first vertex is an attempt with no contribution");
        assertEquals(2, count(stage, "current = ptAddEmptyAttempt(current);"),
            "both the non-candidate branch and a candidate the add refused count the attempt");
        String module = read(MODULE);
        assertTrue(module.contains("if (!ptReservoirIsAccumulator(current)) current = ptEmptyReservoir();"),
            "the merge resets only a corrupt accumulator: M-only attempts are legitimate and must survive the merge");
        assertFalse(module.contains("if (!ptReservoirHasSample(current)) current = ptEmptyReservoir();"),
            "the v4 reset would throw away every zero-contribution attempt the moment history arrives");
    }

    /** Invariant 2 in the shader: the candidate class is the certified, non-emitting, non-sky first vertex. */
    @Test
    void theReservoirOwnsOnlyTheCertifiedDiffuseContinuation() throws IOException {
        String stage = read(STAGE);
        assertTrue(stage.contains("bool candidate = record.status == PATH_PT_RECORD_VERTEX\n"
                + "        && (record.vertexFlags & PATH_PT_VERTEX_CERTIFIED) != 0u\n"
                + "        && (record.vertexFlags & (PATH_PT_VERTEX_ENVIRONMENT | PATH_PT_VERTEX_EMISSIVE)) == 0u\n"
                + "        && !pathTraceSampleRejected(flags);"),
            "sky, emitter and uncertified first vertices stay in the path that traced them");
        assertTrue(stage.contains("radiance -= record.suffixContribution;"),
            "an admitted candidate leaves the path, so it is counted once - by the reservoir");
        assertTrue(stage.contains("if (record.status == PATH_PT_RECORD_NONE) {"),
            "an ineligible receiver (sky pixel, water, RC field, ray profile) publishes an empty record and adds nothing");
    }

    /** Invariant 3 in the shader: the resolved term is the diffuse lobe's f*cos times L*W, with no second 1/pdf. */
    @Test
    void theResolvedTermIsTheDiffuseLobeOfTheReceiver() throws IOException {
        String stage = read(STAGE);
        assertTrue(stage.contains("radiance += record.diffuseReflectance\n"
                + "            * (INV_PI * max(dot(record.anchorNormal, shadeDirection), 0.0))\n"
                + "            * resolved.radiance * resolved.weight;"),
            "f_d * cos * L * W: the receiver's own diffuse lobe, evaluated in the reused direction");
        assertTrue(stage.contains("float3 shadeDirection = fresh ? record.direction : resolved.direction;"),
            "a fresh winner is shaded along its sampled direction, so f * cos * W is exactly the weight it replaced; the"
                + " anchor-to-vertex edge starts one ray bias off the face and would differ at a short first edge");
        String bsdf = read(BSDF);
        assertTrue(bsdf.contains("albedo = bsdfDiffuseReflectanceAt(material, NdotV);"),
            "the sampler's diffuse weight and the stage's reflectance are one formula, or the identity drifts");
        String tracer = read(TRACER);
        assertTrue(tracer.contains("result.pt.diffuseReflectance = bsdfDiffuseReflectanceAt(surface.material,"),
            "the reflectance is captured at the primary vertex with the view the sampler used");
    }

    /** History is keyed on the unwrapped camera-relative receiver, never on the 1024-wrapped anchor (P1-6). */
    @Test
    void historyIsKeyedOnTheReprojectedReceiver() throws IOException {
        String module = read(MODULE);
        assertTrue(module.contains("HistoryStatus status = historyValidate(storedReceiver, receiver.historyPosition,"),
            "the key is the receiver in the writer frame's camera-relative space");
        assertFalse(module.contains("receiver.worldPosition"),
            "SurfaceHit.worldPosition wraps at 1024 blocks and must not key a history");
        String stage = read(STAGE);
        assertTrue(stage.contains("diReprojectReceiver(record.anchorPosition + motion, cameraDelta,"),
            "the same reprojection DI uses: receiver plus its own motion plus the camera delta");
    }

    // ---- CPU model -------------------------------------------------------------------------------------------------

    /**
     * Invariant 3, numerically: a fresh certified candidate with no history resolves to exactly the suffix the stage
     * subtracted, for random materials, lobe probabilities, directions and suffixes. The candidate cap and the resolved
     * weight cap are firefly guards, so the cases that would hit them are counted and must stay rare.
     */
    @Test
    void anEmptyHistoryReproducesThePathItReplaced() {
        SplittableRandom random = new SplittableRandom(0x5eed_17L);
        int capped = 0;
        int checked = 0;
        for (int trial = 0; trial < 200_000; trial++) {
            double branchProbability = 0.05 + 0.95 * random.nextDouble();
            double[] reflectance = {random.nextDouble(), random.nextDouble(), random.nextDouble()};
            double cosine = Math.sqrt(random.nextDouble());          // cosine-weighted direction
            if (cosine <= 0.0) {
                continue;
            }
            double reversePdf = random.nextDouble() / Math.PI;
            double[] suffix = {10 * random.nextDouble(), random.nextDouble(), 0.0};

            double sourcePdf = branchProbability * cosine / Math.PI;           // bsdfSample: pdf
            double[] weight = new double[3];                                  // bsdfSample: weight
            double[] removed = new double[3];
            for (int c = 0; c < 3; c++) {
                weight[c] = reflectance[c] / branchProbability;
                removed[c] = weight[c] * suffix[c];                            // what the path gathered
            }

            double target = targetPdf(reversePdf, cosine);
            double candidate = target / Math.max(sourcePdf, MIN_PDF);
            if (candidate > MAX_CANDIDATE_WEIGHT) {
                capped++;
                continue;
            }
            Reservoir reservoir = new Reservoir().add(candidate, target);   // the candidate IS the attempt
            double resolvedWeight = resolveWeight(reservoir, target);
            if (resolvedWeight >= MAX_RESOLVED_WEIGHT) {
                capped++;
                continue;
            }
            for (int c = 0; c < 3; c++) {
                double added = reflectance[c] * (Math.max(cosine, 0.0) / Math.PI) * suffix[c] * resolvedWeight;
                assertEquals(removed[c], added, 1.0e-9 * Math.max(1.0, removed[c]),
                    "trial " + trial + " channel " + c + ": PT on must equal PT off while the history is empty");
            }
            checked++;
        }
        assertTrue(checked > 150_000, "the identity has to hold on the bulk of the hemisphere, checked " + checked);
        assertTrue(capped < 2_000, "the firefly caps are guards, not the common case: " + capped + " capped");
    }

    /**
     * Invariant 1, numerically: a pixel whose attempt this frame produced no candidate (M = 1, wSum = 0) merges a
     * history record with M_h attempts. The result must carry M = 1 + M_h, so the history's resolved weight is diluted
     * by the attempt this frame really made. The v4 reset discarded the attempt and kept M = M_h.
     */
    @Test
    void aZeroContributionAttemptSurvivesTheHistoryMerge() {
        Reservoir history = new Reservoir();
        history.m = 6.0;
        history.weightSum = 3.0;
        history.target = 0.25;
        Reservoir current = addEmptyAttempt(new Reservoir(), false);
        assertEquals(1.0, current.m, 0.0, "the attempt is counted");
        assertEquals(0.0, current.weightSum, 0.0, "and contributes no weight");

        Reservoir merged = merge(current, history, 0.25, 1.0, false);
        assertEquals(7.0, merged.m, 1e-12, "M = this frame's attempt + the history's attempts");
        assertEquals(3.0, merged.weightSum, 1e-12, "the history's weight moves unchanged at J = 1, ratio 1");
        assertEquals(3.0 / (7.0 * 0.25), resolveWeight(merged, 0.25), 1e-12,
            "W is normalised by every attempt, not only by the ones that recorded");

        Reservoir v4 = merge(current, history, 0.25, 1.0, true);
        assertEquals(6.0, v4.m, 1e-12, "the old reset drops this frame's attempt - the 1/P(recorded) defect again");
    }

    /**
     * Ownership, numerically: over many frames of a fixed receiver, the expectation of (path - removed + added) equals
     * the expectation of the untouched path when the reservoir is fresh every frame. A sky first edge is never removed
     * and never added; a certified hit is removed and resolved back.
     */
    @Test
    void ownershipNeitherDoubleCountsNorDropsATerm() {
        SplittableRandom random = new SplittableRandom(42L);
        double off = 0.0;
        double on = 0.0;
        int frames = 400_000;
        double branchProbability = 0.7;
        double reflectance = 0.6;
        for (int frame = 0; frame < frames; frame++) {
            boolean diffuse = random.nextDouble() < branchProbability;
            double specular = diffuse ? 0.0 : 0.3;                    // the specular continuation's own gather
            double path = specular;
            double result = specular;
            if (diffuse) {
                double cosine = Math.sqrt(random.nextDouble());
                boolean sky = random.nextDouble() < 0.5;
                double suffix = sky ? 1.2 : 0.4 + 0.2 * random.nextDouble();
                double weight = reflectance / branchProbability;
                path += weight * suffix;
                result += weight * suffix;
                if (!sky) {
                    double target = targetPdf(0.3 / Math.PI, cosine);
                    double candidate = target / (branchProbability * cosine / Math.PI);
                    Reservoir reservoir = new Reservoir()
                        .add(Math.min(candidate, MAX_CANDIDATE_WEIGHT), target);
                    result -= weight * suffix;
                    result += reflectance * (cosine / Math.PI) * suffix * resolveWeight(reservoir, target);
                }
            }
            off += path;
            on += result;
        }
        assertEquals(off / frames, on / frames, 1.0e-3,
            "with a fresh reservoir every frame the stage is an identity in expectation as well as per sample");
    }

    @Test
    void theRingIsTwoHalvesOf144BytesPerPixel() {
        assertEquals(144, RtPathTraceSlangPass.PT_RESERVOIR_BYTES, "RestirPtReservoir: 112 B path + stats + source");
        assertEquals(597_196_800L, RtPathTraceSlangPass.ptReservoirBytes(1920, 1080),
            "2 * 1920 * 1080 * 144 B: the cost the settings row has to name");
        assertEquals(2L * 2560 * 1440 * 144, RtPathTraceSlangPass.ptReservoirBytes(2560, 1440));
    }

    /**
     * The record layout as the compiler laid it out, read from the generated reflection: first vertex 64 B, path 112 B,
     * reservoir 144 B. Up to v4 the module claimed 52/80/112 B - the packed payload, never measured - and a host that
     * believed it would have addressed every pixel but the first at the wrong stride. The offsets below are the ones the
     * shader reads and writes, so a field reorder fails here before it can reach a ring.
     */
    @Test
    void theRecordLayoutIsTheMeasuredStd430One() throws IOException {
        Path reflection = Path.of("build/generated/slang/reflection/path_trace.reflection.json");
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.isRegularFile(reflection),
            "path_trace.slang was not compiled into this build");
        com.google.gson.JsonObject root = com.google.gson.JsonParser.parseString(
            Files.readString(reflection, StandardCharsets.UTF_8)).getAsJsonObject();
        com.google.gson.JsonObject reservoir = null;
        for (var parameter : root.getAsJsonArray("parameters")) {
            var object = parameter.getAsJsonObject();
            if (object.get("name").getAsString().equals("ptReservoirs")) {
                reservoir = object.getAsJsonObject("type").getAsJsonObject("resultType");
            }
        }
        assertTrue(reservoir != null, "path_trace declares ptReservoirs");
        assertEquals(144, size(reservoir), "RestirPtReservoir");
        assertEquals(0, offset(reservoir, "sample"));
        assertEquals(112, offset(reservoir, "stats"));
        assertEquals(128, offset(reservoir, "source"));
        com.google.gson.JsonObject path = field(reservoir, "sample");
        assertEquals(0, offset(path, "anchorPosition"));
        assertEquals(16, offset(path, "anchorNormal"));
        assertEquals(32, offset(path, "first"));
        assertEquals(96, offset(path, "flags"));
        com.google.gson.JsonObject first = field(path, "first");
        assertEquals(0, offset(first, "position"));
        assertEquals(16, offset(first, "normal"));
        assertEquals(32, offset(first, "throughput"));
        assertEquals(44, offset(first, "pdf"));
        assertEquals(48, offset(first, "bsdfPdf"));
        assertEquals(52, offset(first, "flags"));
        assertEquals(56, offset(first, "sampleId"));
        assertTrue(read(MODULE).contains("//   RestirPtReservoir   144 B  (sample@0, stats@112, source@128)"),
            "the module's own layout comment states the measured record");
    }

    private static com.google.gson.JsonObject field(com.google.gson.JsonObject struct, String name) {
        for (var entry : struct.getAsJsonArray("fields")) {
            var object = entry.getAsJsonObject();
            if (object.get("name").getAsString().equals(name)) {
                return object.getAsJsonObject("type");
            }
        }
        throw new AssertionError("no field " + name);
    }

    private static int offset(com.google.gson.JsonObject struct, String name) {
        for (var entry : struct.getAsJsonArray("fields")) {
            var object = entry.getAsJsonObject();
            if (object.get("name").getAsString().equals(name)) {
                return object.getAsJsonObject("binding").get("offset").getAsInt();
            }
        }
        throw new AssertionError("no field " + name);
    }

    private static int size(com.google.gson.JsonObject type) {
        for (var entry : type.getAsJsonArray("sizes")) {
            var object = entry.getAsJsonObject();
            if (object.get("kind").getAsString().equals("uniform")) {
                return object.get("value").getAsInt();
            }
        }
        throw new AssertionError("no uniform size");
    }

    // ---- model ------------------------------------------------------------------------------------------------------

    private static final class Reservoir {
        double m;
        double weightSum;
        double target;

        Reservoir add(double weight, double targetPdf) {
            m += 1.0;
            weightSum += weight;
            target = targetPdf;     // a single candidate always wins its own reservoir
            return this;
        }
    }

    /** ptTargetPdf for a receiver cosine: the reverse density floor times the clamped cosine. */
    private static double targetPdf(double reversePdf, double cosine) {
        return Math.max(reversePdf, MIN_PDF) * Math.min(Math.max(Math.abs(cosine), MIN_COSINE), 1.0);
    }

    private static Reservoir addEmptyAttempt(Reservoir reservoir, boolean ignored) {
        reservoir.m += 1.0;
        return reservoir;
    }

    /** ptResolveDetailed's normalisation, including the resolved-weight guard. */
    private static double resolveWeight(Reservoir reservoir, double targetPdf) {
        if (!(reservoir.weightSum > 0.0)) {
            return 0.0;
        }
        double weight = reservoir.weightSum / Math.max(reservoir.m * targetPdf, MIN_PDF);
        return Math.min(weight, MAX_RESOLVED_WEIGHT);
    }

    /** ptMergeReservoir at a fixed roulette outcome; {@code v4Reset} restores the reset this batch replaced. */
    private static Reservoir merge(Reservoir current, Reservoir incoming, double incomingTarget, double jacobian,
                                   boolean v4Reset) {
        Reservoir out = new Reservoir();
        out.m = current.m;
        out.weightSum = current.weightSum;
        out.target = current.target;
        boolean hasSample = out.m > 0 && out.weightSum > 0 && out.target > 0;
        if (v4Reset ? !hasSample : !(out.m >= 0 && out.weightSum >= 0 && out.m <= MAX_M)) {
            out = new Reservoir();
        }
        double incomingM = Math.min(incoming.m, M_CAP);
        double ratio = Math.min(Math.max(incomingTarget / Math.max(incoming.target, MIN_PDF) * jacobian, 0.0),
            TEMPORAL_RATIO_CAP);
        double incomingWeight = incoming.weightSum * (incomingM / Math.max(incoming.m, MIN_PDF)) * ratio;
        double total = out.weightSum + incomingWeight;
        if (out.weightSum == 0.0) {
            out.target = incomingTarget;    // the roulette always picks the only weighted record
        }
        out.m += incomingM;
        out.weightSum = total;
        return out;
    }

    private static int count(String text, String needle) {
        int count = 0;
        for (int at = text.indexOf(needle); at >= 0; at = text.indexOf(needle, at + needle.length())) {
            count++;
        }
        return count;
    }

    private static String read(Path path) throws IOException {
        return Files.readString(path, StandardCharsets.UTF_8).replace("\r\n", "\n");
    }
}
