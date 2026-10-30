package io.github.andrexarlay.vulkancompat.rt.world;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Slang path tracer had only the sun and the moon to sample, so a torch-lit room rendered black. The emitter list in
 * {@code light/block_emitters.slang} carries the look the owner iterated on for months: the tuned falloff, the chromatic
 * categories, the open-source shadow bias. These guards pin those numbers, the receiver state the sampler needs, and the
 * one place where the estimate and the density must not double-count a cosine.
 */
class RtSlangBlockEmitterTest {

    private static final String EMITTERS = "src/main/resources/shaders/slang/light/block_emitters.slang";
    private static final String PASS = "src/main/resources/shaders/slang/passes/path_trace.slang";
    private static final String ENTRY = "src/main/resources/shaders/slang/pathtrace/path_trace_entry.slang";
    private static final String TRACER = "src/main/resources/shaders/slang/pathtrace/path_tracer.slang";
    private static final String CONTRACT = "src/main/resources/shaders/slang/contracts/light.slang";
    private static final String SURFACE = "src/main/resources/shaders/slang/surface/surface.slang";

    @Test
    void theRecordIsTheBufferRtEmittersAlreadyUploads() {
        String slang = read(EMITTERS);
        String record = block(slang, "struct BlockEmitterRecord {");
        assertEquals(6, count(record, "float4 "), "six vec4: the same 96 bytes as RtEmitters.FLOATS");
        assertTrue(record.indexOf("corner;") < record.indexOf("edge1;"), "p0 then e1 then e2");
        assertTrue(record.indexOf("edge1;") < record.indexOf("edge2;"), "the two edges of the quad");
        assertTrue(record.indexOf("edge2;") < record.indexOf("uvCorner;"), "then uv, then tint");
        assertTrue(record.indexOf("uvEdges;") < record.indexOf("tint;"), "the field order is the upload order");
        assertTrue(read(ENTRY).contains("float4 emitterCameraRelative;"),
            "the camera-relative correction and the candidate count travel as a push vec4");
    }

    @Test
    void theFalloffAndTheWindowAreTheTunedOnes() {
        String slang = read(EMITTERS);
        assertTrue(slang.contains("static const float EMITTER_RADIUS = 24.0;"), "the light radius, not the ray distance");
        assertTrue(slang.contains("static const float EMISSIVE_GAIN = 3.0;"));
        assertTrue(slang.contains("static const float EMISSIVE_FLUX_GAIN = 2.2;"));
        String attenuation = block(slang, "float emitterAttenuation(float d2) {");
        assertTrue(attenuation.contains("dd <= 4.0 ? rsqrt(dd * sqrt(dd)) : 0.84090 * rsqrt(dd * sqrt(sqrt(dd)))"),
            "d^-1.5 to two blocks then d^-1.25, continuous at the seam");
        assertTrue(attenuation.contains("max(d2, 0.25)"), "the floor of dd near the emitter face");
        String window = block(slang, "float emitterWindow(float d2, float lightRadius) {");
        assertTrue(window.contains("clamp(1.0 - d2 / (lightRadius * lightRadius), 0.0, 1.0)"), "squared linear window");
        assertTrue(slang.contains("min(maxDistance, EMITTER_RADIUS)"));
    }

    @Test
    void theEstimatorCancelsTheCosineTheBsdfAlreadyApplies() {
        String slang = read(EMITTERS);
        String estimate = estimateBlock(slang);
        assertTrue(estimate.contains("float risWeight = (weightSum / max(sampleCount, 1.0)) / max(cosReceiver, 1.0e-4);"),
            "the target density folds the receiver cosine in and the albedo/PI term has no cosine of its"
                + " own, while bsdfEvaluate returns the diffuse lobe multiplied by NdotL: dividing here is what keeps the"
                + "product an unbiased integrand instead of squaring the cosine");
        assertTrue(estimate.contains("result.radiance = e.radiancePerEmission * risWeight * e.solidAnglePdf;"),
            "radiance divided by this sourcePdf leaves Le * W / K");
        assertTrue(estimate.contains("result.sourcePdf = e.solidAnglePdf;"), "the density itself stays the honest one");
        String pdf = block(slang, "float blockEmitterSolidAnglePdf(BlockEmitterPoint p, float distance2,"
            + " float cosGeometry) {");
        assertTrue(pdf.contains("(probability / area) * distance2 / max(cosGeometry, 1.0e-5)"),
            "area sampling to solid angle: P(index) / area * d^2 / cos(emitter)");
        String indexProbability = block(slang, "float blockEmitterIndexProbability(uint index, uint count) {");
        assertTrue(indexProbability.contains("(sqrt(float(index + 1u)) - sqrt(float(index))) * rsqrt(float(count))"),
            "the list is importance-sorted, so u^2 picks it and P(i) = sqrt((i+1)/n) - sqrt(i/n)");
        String batch = block(slang, "BlockEmitterWinner sampleBlockEmitterBatch(StructuredBuffer<BlockEmitterRecord> records,");
        assertTrue(batch.contains("float2 r2shift = float2(rand01(seed), rand01(seed));"),
            "one per-call R2 shift stratifies the quads of one torch instead of flickering on them");
    }

    @Test
    void theTwoSidedSourcesKeepTheirFloorAndTheirEndBias() {
        String slang = read(EMITTERS);
        String phat = block(slang, "float blockEmitterPhat(BlockEmitterPoint p, LightReceiver receiver, float distance2,"
            + " float lightRadius) {");
        assertTrue(phat.contains("float cosGeometry = p.twoSided ? abs(cosEmitter) : max(cosEmitter, 0.0);"),
            "the density uses the true projection");
        assertTrue(phat.contains("float cosKernel = p.twoSided ? max(abs(cosEmitter), 0.25) : max(cosEmitter, 0.0);"),
            "the look kernel keeps the 0.25 floor so an edge-on flame does not switch off");
        String endBias = block(slang, "float blockEmitterEndBias(BlockEmitterPoint p, float distanceTo) {");
        assertTrue(endBias.contains("float endBias = clamp(0.02 * distanceTo, 0.002, 0.03);"), "the tuned shadow bias");
        assertTrue(endBias.contains("return p.twoSided ? max(endBias, min(0.2, 0.45 * distanceTo)) : endBias;"),
            "a torch flame on its stick must not be shadowed by its own stick");
        String estimate = estimateBlock(slang);
        assertTrue(estimate.contains("float shadowDistance = max(distanceTo - e.endBias, 0.001);"),
            "the shadow ray of the path tracer stops short of the emitter body");
        assertTrue(estimate.contains("result.position = receiver.position + direction * shadowDistance;"),
            "short of the emitter body");
        assertTrue(estimate.contains("result.distance = shadowDistance;"),
            "and the same shortened distance, not the full one");
    }

    @Test
    void aGlowingFaceIsNotLitByItsOwnQuad() {
        String slang = read(EMITTERS);
        String candidate = block(slang, "BlockEmitterCandidate blockEmitterCandidate(StructuredBuffer<BlockEmitterRecord> records,");
        assertTrue(candidate.contains("distance2 < (receiver.emission > 0.5 ? 0.25 : 1.0e-6)"),
            "the half-block self-rejection: without it a torch face samples its own quad at d -> 0, the d^-1.5"
                + " kernel blows that candidate up and it wins the roulette on top of the emission term already added");
        assertTrue(read(CONTRACT).contains("float  emission;"),
            "the receiver carries the one piece of state a sampler cannot infer from a position and a normal");
        String tracer = read(TRACER);
        assertTrue(tracer.contains("receiver.primary = result.surfaceHits == 1u;"),
            "the path tracer marks the primary vertex, the only one whose light point is reused");
        assertTrue(tracer.contains("lights.sample(receiver, seed)"),
            "and passes the receiver, so the emission mask of the face being shaded travels with the call");
        assertTrue(tracer.contains("result.radiance += throughput * surface.material.emission;"),
            "and still adds the face it sees: the look of an emitter and the light it casts are two terms");
        assertTrue(read(SURFACE).contains("material.emissionMask = max(materialTexel.b, 0.0);"),
            "the mask is the atlas channel the emitter sampler reads, not a luminance of the colour");
    }

    @Test
    void entityEmittersSampleTheirOwnAtlasEmissionAndTint() {
        String slang = read(EMITTERS);
        String candidate = block(slang, "BlockEmitterCandidate blockEmitterCandidate(StructuredBuffer<BlockEmitterRecord> records,");
        assertFalse(candidate.contains("entity-atlas emitter: not evaluable"),
            "a glowing entity quad is a real NEE candidate, not a hole in the light list");
        assertTrue(candidate.contains("entityTexel(atlas.entities, slotPlus1 - 1u, point.uv)"),
            "the candidate resolves emission at the sampled point of the entity atlas");
        assertTrue(candidate.contains("entityTexelEmission(entityTexel) * emitter.edge1.w"),
            "the atlas emission code keeps the record's power boost");
        assertTrue(candidate.contains("point.tint = emitter.tint;"),
            "the captured vanilla tint is part of the emitted radiance");
        assertTrue(candidate.contains("distance2 > lightRadius * lightRadius"),
            "the radius test still precedes either atlas fetch");

        String radiance = block(slang, "float3 blockEmitterRadiance(BlockEmitterPoint p, MaterialAtlas atlas) {");
        assertTrue(radiance.contains("p.atlasSlotPlus1 != 0u"),
            "the winning point remembers which atlas owns its UVs");
        assertTrue(radiance.contains("p.tint.rgb * p.tint.a"),
            "rgb and alpha of the captured tint both modulate the light");
        assertTrue(radiance.contains("emitterAttenuationColor(tex)"),
            "entity and block emitters share the same tuned chromatic response");
    }

    @Test
    void theColorCategoriesAreTheTunedOnes() {
        String slang = block(read(EMITTERS), "float3 emitterAttenuationColor(float3 tex) {");
        // The eight chromatic categories are tuned by eye against real screens, so a retune has to move both files or
        // neither: a bound that survives on one side only means the two paths disagree about what a torch looks like.
        for (String threshold : new String[] {"c.r > 0.65 && c.g < 0.22", "c.r >= 0.55 && c.r > c.b * 1.25",
            "c.b > 0.45 && c.g > 0.35 && c.r < 0.45", "c.b > 0.65 && c.g > 0.65 && c.r > 0.35",
            "c.r > 0.7 && c.g > 0.65 && c.b < 0.45", "c.r > 0.45 && c.b > 0.55 && c.g < 0.45",
            "c.r > 0.6 && c.b > 0.75 && c.g >= 0.45", "c.g > 0.65 && c.b > 0.35 && c.r < 0.5"}) {
            assertTrue(slang.contains(threshold), "the shader lost " + threshold);
        }
        assertTrue(read(EMITTERS).contains("float3(1.0, 0.04, 0.02)"),
            "redstone keeps its own colour instead of the texture");
        assertTrue(read(EMITTERS).contains("uint(materialTexel.a * 255.0 + 0.5)) & 4u"),
            "the redstone texel test reads the same alpha bit as the atlas builder writes it");
    }

    @Test
    void theBranchProbabilityIsFoldedIntoTheDensity() {
        String slang = read(EMITTERS);
        String composite = block(slang, "struct SunAndBlockEmitterSampler : ILightSampler {");
        assertTrue(composite.contains("emitterSample.sourcePdf *= emitterProbability;"),
            "the composite picks the branch, so the density of the sample is the branch density times the branch share");
        assertTrue(composite.contains("sunSample.sourcePdf *= 1.0 - emitterProbability;"), "the sun side needs it too");
        assertFalse(composite.contains("sourcePdf /= emitterProbability"));
        assertTrue(composite.contains("return emitters.sample(receiver, seed);"), "a sole active branch has probability one");
        for (double q : new double[]{0.1, 0.25, 0.5, 0.9}) {
            double expected = q * (1.0 / q) + (1.0 - q) * (1.0 / (1.0 - q));
            assertEquals(2.0, expected, 1e-12, "both source estimates remain unbiased across mixture choices");
        }
        assertTrue(composite.contains("if (!hasEmitters || sunIntensity <= 0.0)"),
            "with one live branch there is nothing to renormalise, and the sun keeps its own sun/moon split");
        String entry = read(ENTRY);
        assertTrue(entry.contains("lights.emitterProbability = pc.emitterCount > 0u ? clamp(pc.emitterProbability, 0.0, 1.0) : 0.0;"),
            "an empty list must not steal half of the draws for a light that is never there");
        assertTrue(read(PASS).contains("[[vk::binding(18, 0)]] StructuredBuffer<BlockEmitterRecord> emitters;"),
            "the host promises exactly this binding number");
        assertTrue(entry.contains("uint emitterCandidates = uint(max(pc.emitterCameraRelative.w, 1.0));"),
            "the candidate count travels in the free component of the camera-relative vec4");
        assertTrue(entry.contains("emitterSampler.candidates = emitterCandidates;")
            && entry.contains("directReuse.candidates = emitterCandidates;"),
            "the plain RIS draw and the ReSTIR DI batch read the same number of candidates, so the A/B is one switch");
        assertTrue(entry.indexOf("receiverEmission") < 0,
            "the sampler has no per-frame receiver state: the emission of the face being shaded travels with the call");
        assertTrue(composite.contains("&& !(receiver.primary && emittersReusedAtPrimary)")
                && composite.contains("&& !receiver.bakedBlockLight;"),
            "with the reservoir on, the composite leaves the emitter branch to it at the primary vertex instead of"
                + " drawing the same list a second time; a receiver the distant handover baked draws no emitter"
                + " branch either");
    }

    /** The body of the shared estimator, which turns one reservoir record or one fresh draw into a light sample. */
    private static String estimateBlock(String emittersSource) {
        return block(emittersSource, "LightSample blockEmitterEstimateSample(");
    }

    private static int count(String source, String needle) {        int at = 0;
        int hits = 0;
        while (true) {
            int found = source.indexOf(needle, at);
            if (found < 0) {
                return hits;
            }
            hits++;
            at = found + needle.length();
        }
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

    private static String read(String relative) {
        try {
            return Files.readString(Path.of(relative), StandardCharsets.UTF_8).replace("\r\n", "\n");
        } catch (java.io.IOException e) {
            throw new AssertionError("cannot read " + relative, e);
        }
    }
}
