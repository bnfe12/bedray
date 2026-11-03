package io.github.andrexarlay.vulkancompat.rt.world;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ReSTIR PT used to reuse a stored first edge as if the anchor had not moved: the merge applied only the target
 * ratio, so a candidate generated at the old receiver was weighted by the old solid-angle density. The change of
 * measure for the fixed-first-vertex shift is a geometry ratio, and it is only derivable for one certified class of
 * paths — a diffuse first vertex, whose suffix radiance and sampling distribution are independent of the incoming
 * edge. This test pins the derivation into the module and validates it numerically.
 *
 * <p>Ways this silently breaks: the merge divides by the new geometry instead of multiplying (the sign of the
 * correction inverts and reuse darkens exactly the neighbours it should brighten); the diffuse certificate is
 * dropped and a rough or sky first vertex is reused as if its radiance were direction-independent; or the duplicate
 * test returns to comparing vertex positions and drops distinct samples that happen to share a vertex. The CPU model
 * below is the estimator the derivation claims, not the shader: it omits the module's firefly caps and its grazing
 * rejection, both documented approximations, so it validates the change of measure in isolation.
 */
class RestirPtChangeOfMeasureTest {

    private static final Path SHADER =
        Path.of("src/main/resources/shaders/slang/experimental/restir_pt.slang");

    /**
     * Flatland with a real distance and angle change: the source anchor at x=0 and the receiver at x=1 both sit on a
     * floor (z=0, normal +z) in front of a diffuse wall at x=2 (normal -x). The environment is uniform radiance 1 on
     * the wall's side, so the wall's radiance is its albedo and the receiver's indirect light is its own albedo times
     * that radiance over the visible half of the hemisphere.
     */
    private static final double SOURCE_X = 0.0;
    private static final double RECEIVER_X = 1.0;
    private static final double WALL_X = 2.0;
    private static final double RECEIVER_ALBEDO = 0.6;
    private static final double WALL_ALBEDO = 0.5;
    private static final double ENVIRONMENT_RADIANCE = 1.0;
    private static final double MIN_COSINE = 0.05;
    private static final double MIN_PDF = 1.0e-8;

    @Test
    void theShaderImplementsTheDerivedShiftJacobian() throws IOException {
        String shader = Files.readString(SHADER, StandardCharsets.UTF_8);
        assertTrue(shader.contains("RESTIR_PT_PATH_FLAG_DIFFUSE_FIRST"), "the certified class needs its own flag");
        assertTrue(shader.contains("float ptFirstEdgeGeometry(float3 firstPosition, float3 firstNormal, float3 anchor) {"),
            "the solid-angle-to-area factor at the first vertex is the term the shift changes");
        assertTrue(shader.contains("float ptFirstEdgeShiftJacobian(RestirPtFirstVertex first, float3 storedAnchor,"),
            "the change of measure belongs in one named function");
        assertTrue(shader.contains(
                "float oldGeometry = ptFirstEdgeGeometry(first.position, first.normal, storedAnchor);"),
            "the old factor is measured at the writer's anchor");
        assertTrue(shader.contains(
                "float newGeometry = ptFirstEdgeGeometry(first.position + originDelta, first.normal, surfacePosition);"),
            "the new factor is measured at the receiver that now owns the path, in the current camera-relative space");
        assertTrue(shader.contains("newGeometry / oldGeometry"),
            "the transported density is q_old * G_old / G_new, so the weight correction is G_new / G_old");
        assertTrue(shader.contains(
                "incomingTargetPdf / max(incoming.stats.z, RESTIR_PT_MIN_PDF) * shiftJacobian"),
            "the merge has to fold the change of measure into the candidate ratio");
        assertTrue(shader.contains("float shiftJacobian"), "and the merge has to take it as a parameter");
        assertTrue(shader.contains("ptFirstEdgeShiftJacobian(history.sample.first,")
                && shader.contains("history.sample.anchorPosition, originDelta, receiver.position);"),
            "temporal reuse must measure G_old at the writer's own anchor and rebase the vertex by the frame"
                + " delta; a zero delta would silently drop the change of measure whenever the camera moves");
        assertTrue(shader.contains("ptFirstEdgeShiftJacobian(neighbor.sample.first,")
                && shader.contains("neighbor.sample.anchorPosition, float3(0.0), receiver.position);"),
            "a same-frame neighbour is already in this frame's camera-relative space, so its shift must not"
                + " apply an origin delta");
    }

    /** The derivation covers only a diffuse first vertex that is not a direct term; everything else must be refused. */
    @Test
    void reuseRejectsEverythingOutsideTheDerivedClass() throws IOException {
        String shader = Files.readString(SHADER, StandardCharsets.UTF_8);
        assertTrue(shader.contains("if ((path.flags & RESTIR_PT_PATH_FLAG_DIFFUSE_FIRST) == 0u) return false;"),
            "a rough, specular or delta first vertex has a direction-dependent suffix and must stay local");
        assertTrue(shader.contains(
                "uint terminalMask = RESTIR_PT_PATH_FLAG_ENVIRONMENT | RESTIR_PT_PATH_FLAG_EMISSIVE_END;"),
            "the direct first-edge terms are DI's");
        assertTrue(shader.contains("if ((path.flags & terminalMask) != 0u) return false;"),
            "a sky or emitter first vertex carries radiance for one direction, not for every anchor");
        assertTrue(shader.contains("uint   sampleId;"),
            "the duplicate test needs a stable identity, which the record did not have");
        assertTrue(shader.contains("current.sample.first.sampleId != 0u &&"),
            "an unknown id never proves uniqueness, and the position heuristic must be gone");
    }

    /**
     * The whole algebra on one candidate: the resolved weight must be 1/q_new, where q_new is the stored density
     * transported to the receiver, and the target function must cancel out of it (it is a variance control only).
     */
    @Test
    void aReusedCandidateIsWeightedByTheDensityAtItsNewAnchor() {
        Candidate candidate = new Candidate(WALL_X, 0.3, 0.8, 0.8 / Math.PI, 0.9 / Math.PI);
        double transported = 1.0 / transportedSourcePdf(candidate);

        assertEquals(transported, resolvedWeight(candidate), transported * 1.0e-12,
            "a reused candidate is importance-sampled with the density the shift transported into this pixel");
        assertEquals(transported, resolvedWeightWithTargetScale(candidate, 7.25), transported * 1.0e-12,
            "scaling the target must not move the resolved weight: it cancels between weight and normalisation");
        assertTrue(Math.abs(resolvedWeightWithoutShift(candidate) - transported) > transported * 0.5,
            "without the change of measure the same candidate is weighted by the writer's density, which is the old"
                + " bias this derivation removes");
    }

    /**
     * Monte Carlo against a closed-form indirect integral. The source pixel cosine-samples the wall; the candidate is
     * reused at the receiver with the derived change of measure. The corrected mean has to converge to the analytic
     * value, and the uncorrected one — the merge as it shipped in v2 — has to sit measurably away from it. In this
     * scene the writer's density over-weights the near wall (G_old &lt; G_new there), so the v2 merge converges roughly
     * 14% high; the point of the control is the gap, not its sign.
     */
    @Test
    void theDerivedShiftMakesNeighbourReuseUnbiased() {
        double wallRadiance = WALL_ALBEDO * ENVIRONMENT_RADIANCE;
        double analytic = RECEIVER_ALBEDO * wallRadiance / 2.0;
        int samples = 2_000_000;
        SplittableRandom random = new SplittableRandom(0x5EEDL);
        double correctedSum = 0.0;
        double uncorrectedSum = 0.0;
        double correctedSquares = 0.0;
        for (int i = 0; i < samples; ++i) {
            double u1 = random.nextDouble();
            double u2 = random.nextDouble();
            double radius = Math.sqrt(u1);
            double phi = 2.0 * Math.PI * u2;
            double wx = radius * Math.cos(phi);
            double wy = radius * Math.sin(phi);
            double wz = Math.sqrt(1.0 - u1);
            if (wx <= 1.0e-9) {
                continue; // the ray never reaches the wall; a terminal sky edge is not a GI candidate
            }
            double distance = WALL_X / wx;
            double vertexY = distance * wy;
            double vertexZ = distance * wz;
            double oldDistance = Math.sqrt(WALL_X * WALL_X + vertexY * vertexY + vertexZ * vertexZ);
            double newDistance = Math.sqrt(
                (WALL_X - RECEIVER_X) * (WALL_X - RECEIVER_X) + vertexY * vertexY + vertexZ * vertexZ);
            double oldCosine = WALL_X / oldDistance;
            Candidate candidate = new Candidate(WALL_X, vertexY, vertexZ, wz / Math.PI, oldCosine / Math.PI);
            double receiverCosine = vertexZ / newDistance;
            double brdfTimesCosine = RECEIVER_ALBEDO / Math.PI * receiverCosine;
            double corrected = brdfTimesCosine * wallRadiance * resolvedWeight(candidate);
            correctedSum += corrected;
            correctedSquares += corrected * corrected;
            uncorrectedSum += brdfTimesCosine * wallRadiance * resolvedWeightWithoutShift(candidate);
        }
        double correctedMean = correctedSum / samples;
        double uncorrectedMean = uncorrectedSum / samples;
        double variance = Math.max(0.0, correctedSquares / samples - correctedMean * correctedMean);
        double standardError = Math.sqrt(variance / samples);
        assertEquals(analytic, correctedMean, Math.max(3.0 * standardError, analytic * 0.01),
            "the transported estimator must converge to the analytic indirect integral");
        assertTrue(Math.abs(uncorrectedMean - analytic) > analytic * 0.05,
            "the writer's density misweights the shifted path (corrected=" + correctedMean + ", uncorrected="
                + uncorrectedMean + ", analytic=" + analytic + "); if this stops holding the test scene no longer"
                + " separates the two estimators");
    }

    // ---- The estimator as the derivation states it, in doubles ------------------------------------------------

    /** One wall vertex as the writer stored it: position, sampled density at the writer, reverse BSDF density. */
    private record Candidate(double x, double y, double z, double sourcePdf, double reverseBsdfPdf) {
    }

    /** |cos| at the first vertex between its normal (-x) and the edge, over the squared edge length. */
    private static double firstEdgeGeometry(Candidate candidate, double anchorX) {
        double dx = anchorX - candidate.x;
        double distanceSquared = dx * dx + candidate.y * candidate.y + candidate.z * candidate.z;
        double cosine = Math.abs(dx) / Math.sqrt(distanceSquared);
        return cosine / distanceSquared;
    }

    /** The module's target: reverse lobe density at the vertex times the |cosine| floor at the anchor. */
    private static double target(Candidate candidate, double anchorX) {
        double dx = candidate.x - anchorX;
        double distance = Math.sqrt(dx * dx + candidate.y * candidate.y + candidate.z * candidate.z);
        double cosine = Math.max(MIN_COSINE, Math.min(1.0, Math.abs(candidate.z) / distance));
        return Math.max(candidate.reverseBsdfPdf, MIN_PDF) * cosine;
    }

    /** q_new = q_old * G_old / G_new, so its reciprocal is the weight the shifted path must receive. */
    private static double transportedSourcePdf(Candidate candidate) {
        return candidate.sourcePdf() * firstEdgeGeometry(candidate, SOURCE_X)
            / firstEdgeGeometry(candidate, RECEIVER_X);
    }

    /**
     * add -> merge -> resolve for one candidate. The stored weight is the target ratio at the writer; the merge
     * applies the target ratio to the receiver and the change of measure; the resolve divides by the receiver target.
     * A pure change-of-measure implementation therefore resolves to 1/q_new regardless of the target.
     */
    private static double resolvedWeight(Candidate candidate) {
        return resolvedWeightWithTargetScale(candidate, 1.0);
    }

    private static double resolvedWeightWithTargetScale(Candidate candidate, double targetScale) {
        double sourceTarget = target(candidate, SOURCE_X) * targetScale;
        double receiverTarget = target(candidate, RECEIVER_X) * targetScale;
        double storedWeight = sourceTarget / candidate.sourcePdf();
        double jacobian = firstEdgeGeometry(candidate, RECEIVER_X) / firstEdgeGeometry(candidate, SOURCE_X);
        double merged = storedWeight * (receiverTarget / sourceTarget) * jacobian;
        return merged / receiverTarget;
    }

    /** The v2 merge: the target ratio alone, with the writer's density standing in for the shifted one. */
    private static double resolvedWeightWithoutShift(Candidate candidate) {
        double sourceTarget = target(candidate, SOURCE_X);
        double receiverTarget = target(candidate, RECEIVER_X);
        double storedWeight = sourceTarget / candidate.sourcePdf();
        double merged = storedWeight * (receiverTarget / sourceTarget);
        return merged / receiverTarget;
    }
}
