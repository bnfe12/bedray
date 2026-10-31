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
 * Light that reaches an underwater receiver from above the sheet, other than the sun and the moon. Until this batch the
 * key light alone was bent at the face ({@code waterBeamToLight}); a block emitter above the water was shadowed and shaded
 * along the straight sight line, as if the sheet were not there, and a camera ray that left the body without meeting a
 * face looked up the sky along its unbent direction. Now:
 *
 * <ul>
 *   <li>{@code waterBeamToPoint} finds where a point light's beam crosses the flat sheet (Snell, solved by bisection),
 *   walks the two legs through {@code traceShadowBeam} and weights the sample by the ray Jacobian of the crossing -
 *   which in the limit of a far light is exactly the key light's {@code cos(air) / cos(water)};</li>
 *   <li>an escaped ray inside the body is refracted out of the plane with the sheet's own transmission weight, or sees
 *   no sky at all past the critical angle (total internal reflection).</li>
 * </ul>
 *
 * The model is the shader's arithmetic in double precision; the Jacobian is checked against a finite-difference
 * ray-geometry measurement, not against itself.
 */
class RtSlangWaterPointBeamTest {

    private static final Path SHADERS = Path.of("src/main/resources/shaders/slang");
    private static final Path WATER = SHADERS.resolve("surface/water.slang");
    private static final Path VISIBILITY = SHADERS.resolve("surface/visibility.slang");
    private static final Path TRACER = SHADERS.resolve("pathtrace/path_tracer.slang");
    private static final double IOR = 1.333;
    private static final int STEPS = 32;
    private static final String NL = String.valueOf((char) 10);

    /** The shader's solution: horizontal offset of the crossing from the receiver, 0..D. */
    private static double crossing(double depth, double rise, double across, double ior) {
        double lo = 0.0;
        double hi = across;
        for (int i = 0; i < STEPS; i++) {
            double mid = 0.5 * (lo + hi);
            double g = ior * mid / Math.sqrt(mid * mid + depth * depth)
                - (across - mid) / Math.sqrt((across - mid) * (across - mid) + rise * rise);
            if (g > 0.0) {
                hi = mid;
            } else {
                lo = mid;
            }
        }
        return 0.5 * (lo + hi);
    }

    /** The shader's weight: ior^2 * straight^2 * d(omega water)/d(A perpendicular at the light). */
    private static double weight(double depth, double rise, double across, double ior) {
        double x = crossing(depth, rise, across, ior);
        double column = Math.hypot(x, depth);
        double airDistance = Math.hypot(across - x, rise);
        double cosWater = depth / column;
        double cosAir = rise / airDistance;
        double rOverSin = depth / cosWater + rise * ior / cosAir;
        double rPrime = depth / (cosWater * cosWater) + rise * ior * cosWater / (cosAir * cosAir * cosAir);
        double straight2 = across * across + (depth + rise) * (depth + rise);
        return ior * ior * straight2 / (rOverSin * rPrime * cosAir);
    }

    /** Where a water-side ray at polar angle theta (from vertical) and azimuth phi lands on the light's height plane. */
    private static double[] landing(double theta, double phi, double depth, double rise, double ior) {
        double sinAir = ior * Math.sin(theta);
        double tanAir = sinAir / Math.sqrt(1.0 - sinAir * sinAir);
        double r = depth * Math.tan(theta) + rise * tanAir;
        return new double[] {r * Math.cos(phi), r * Math.sin(phi)};
    }

    private static double[] derivative(double theta, double depth, double rise, boolean polar) {
        double[] coarse = central(theta, depth, rise, polar, 2.0e-6);
        double[] fine = central(theta, depth, rise, polar, 1.0e-6);
        return new double[] {(4.0 * fine[0] - coarse[0]) / 3.0, (4.0 * fine[1] - coarse[1]) / 3.0};
    }

    private static double[] central(double theta, double depth, double rise, boolean polar, double h) {
        double[] plus = polar ? landing(theta + h, 0.0, depth, rise, IOR) : landing(theta, h, depth, rise, IOR);
        double[] minus = polar ? landing(theta - h, 0.0, depth, rise, IOR) : landing(theta, -h, depth, rise, IOR);
        return new double[] {(plus[0] - minus[0]) / (2.0 * h), (plus[1] - minus[1]) / (2.0 * h)};
    }

    @Test
    void theCrossingObeysSnellAndLiesOnTheSheet() {
        SplittableRandom random = new SplittableRandom(53);
        for (int i = 0; i < 100_000; i++) {
            double depth = 0.05 + random.nextDouble() * 20.0;
            double rise = 0.05 + random.nextDouble() * 20.0;
            double across = random.nextDouble() * 30.0;
            double x = crossing(depth, rise, across, IOR);
            assertTrue(x >= 0.0 && x <= across, "the crossing lies between the two feet");
            double sinWater = x / Math.hypot(x, depth);
            double sinAir = (across - x) / Math.hypot(across - x, rise);
            assertEquals(sinAir, IOR * sinWater, 1.0e-7, "n_water sin = n_air sin at the crossing");
        }
    }

    @Test
    void theWeightIsTheMeasuredRayJacobianOfTheCrossing() {
        SplittableRandom random = new SplittableRandom(54);
        for (int i = 0; i < 2_000; i++) {
            double depth = 0.2 + random.nextDouble() * 10.0;
            double rise = 0.2 + random.nextDouble() * 10.0;
            double across = 0.05 + random.nextDouble() * 12.0;
            double x = crossing(depth, rise, across, IOR);
            double theta = Math.atan2(x, depth);
            double sinAir = IOR * Math.sin(theta);
            double cosAir = Math.sqrt(1.0 - sinAir * sinAir);
            // Finite differences of the ray map (Richardson-extrapolated central differences, so a grazing air leg,
            // whose derivatives grow like 1/cos^3, is measured and not rounded): solid angle on the water side against
            // area on the light's height plane.
            double[] dTheta = derivative(theta, depth, rise, true);
            double[] dPhi = derivative(theta, depth, rise, false);
            double areaPerParam = Math.abs(dTheta[0] * dPhi[1] - dTheta[1] * dPhi[0]);
            double omegaPerParam = Math.sin(theta);
            double perpendicular = omegaPerParam / (areaPerParam * cosAir);
            double straight2 = across * across + (depth + rise) * (depth + rise);
            double measured = IOR * IOR * straight2 * perpendicular;
            assertEquals(measured, weight(depth, rise, across, IOR), 1.0e-4 * measured,
                "depth " + depth + " rise " + rise + " across " + across);
        }
    }

    @Test
    void theWeightReducesToNoSheetAndToTheKeyLightBeam() {
        for (double across : new double[] {0.0, 0.5, 3.0, 9.0}) {
            assertEquals(1.0, weight(2.0, 3.0, across, 1.0), 1.0e-9, "no index change, no weight");
        }
        // A light very far above: the beam is the key light's, whose weight is cos(air) / cos(water).
        double depth = 2.0;
        double rise = 1.0e6;
        double across = 0.7e6;
        double x = crossing(depth, rise, across, IOR);
        double cosWater = depth / Math.hypot(x, depth);
        double cosAir = rise / Math.hypot(across - x, rise);
        assertEquals(cosAir / cosWater, weight(depth, rise, across, IOR), 1.0e-3,
            "the far limit is waterBeamToLight's solidAngle");
    }

    /** Before and after for one torch: two blocks under the sheet, three above it and four across. */
    @Test
    void aTorchAboveTheSheetIsSeenSteeperAndBrighterPerSolidAngle() {
        double depth = 2.0;
        double rise = 3.0;
        double across = 4.0;
        double straightCos = (depth + rise) / Math.hypot(across, depth + rise);
        double x = crossing(depth, rise, across, IOR);
        double bentCos = depth / Math.hypot(x, depth);
        double w = weight(depth, rise, across, IOR);
        System.out.printf("[water point beam] before: arrival cos %.4f weight 1.0000; after: arrival cos %.4f"
            + " crossing %.4f weight %.4f%n", straightCos, bentCos, x, w);
        assertTrue(bentCos > straightCos, "the bent beam arrives closer to vertical than the sight line");
        assertTrue(x < across * depth / (depth + rise), "and crosses the sheet nearer the receiver than the sight line does");
        assertTrue(w > 0.0 && Double.isFinite(w));
    }

    // ---- source guards ---------------------------------------------------------------------------------------------

    @Test
    void theShaderSolvesAndWeightsTheCrossingAsTheModelDoes() throws IOException {
        String water = read(WATER);
        assertTrue(water.contains("static const uint WATER_POINT_BEAM_STEPS = " + STEPS + "u;"));
        assertTrue(water.contains("float g = WATER_IOR * mid * rsqrt(mid * mid + depth * depth)\n"
                + "            - (across - mid) * rsqrt((across - mid) * (across - mid) + rise * rise);"),
            "the bisection function of the model");
        assertTrue(water.contains("        if (g > 0.0) {" + NL + "            hi = mid;" + NL + "        } else {" + NL
                + "            lo = mid;"),
            "g rises with x, so a positive g means the crossing lies nearer the receiver: the model's step");
        assertTrue(water.contains("float rOverSin = depth / cosWater + rise * WATER_IOR / cosAir;")
                && water.contains("float rPrime = depth / (cosWater * cosWater) + rise * WATER_IOR * cosWater"
                    + " / (cosAir * cosAir * cosAir);")
                && water.contains("beam.solidAngle = WATER_IOR * WATER_IOR * straight * straight"
                    + " / (rOverSin * rPrime * cosAir);"),
            "the weight of the model");
    }

    @Test
    void theKeyLightBeamIsUnchangedAndBothShareTheTwoLegWalk() throws IOException {
        String water = read(WATER);
        assertTrue(water.contains("    beam.air = lightDirection;\n    beam.airDistance = distance - column;"),
            "the key light's air leg is exactly what traceShadowBeam walked before");
        String visibility = read(VISIBILITY);
        assertTrue(visibility.contains("makeRay(entry, beam.air, 0.0, max(beam.airDistance, bias))"),
            "the sky leg follows the beam's own air direction and length");
        assertTrue(visibility.contains("waterBeamTransmittance(beam.air.y)"), "and the Fresnel share of its air cosine");
        String tracer = read(TRACER);
        assertTrue(tracer.contains("} else if (medium.inside && light.kind == LightKind.Emitter) {\n"
                + "        beam = waterBeamToPoint(light.position, position, medium);"),
            "a block emitter above the sheet is reached through the crossing");
        assertTrue(tracer.contains("waterBeamFocus(beam, position, beam.air, materialOptions.portalTime,\n"
                + "                materialOptions.waterWaveMix)"),
            "wave parity: the caustics switch and the wave style act on the emitter beam exactly as on the key light");
    }

    @Test
    void anEscapedRayInsideTheBodyLeavesThroughThePlane() throws IOException {
        String tracer = read(TRACER);
        assertTrue(tracer.contains("float3 bent = refract(ray.direction, float3(0.0, -1.0, 0.0), WATER_IOR);"),
            "out of the dense side, against the downward face normal");
        assertTrue(tracer.contains("escapeWeight = float3(0.0);   // total internal reflection: this ray sees no sky"),
            "past the critical angle the sky is not what the ray sees");
        assertTrue(tracer.contains("escapeWeight = waterBeamTransmittance(escape.y) * (WATER_IOR * WATER_IOR);"),
            "inside the window: the sheet's transmission times eta^2, as the optical lobe weights a crossing");
        assertTrue(tracer.contains("result.radiance += throughput * escapeWeight * sky;"));
    }

    private static String read(Path path) throws IOException {
        return Files.readString(path, StandardCharsets.UTF_8).replace("\r\n", "\n");
    }
}
