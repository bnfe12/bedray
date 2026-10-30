package io.github.andrexarlay.vulkancompat.rt.world;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Typed counterpart of the deleted {@code RtShaderNaNGuardTest}, which pinned the NaN/Inf guards of the GLSL common
 * file. The owner has already seen a NaN as a full black screen and as lingering speckles: a zero-length vector
 * normalized to NaN travels through the normal, the ray offset and the albedo divide, and NEE is where it is born - the
 * direction to an emitter that coincides with the receiver, the half vector of a view that looks exactly along the
 * light, the tangent frame of a collinear UV island.
 *
 * <p>The guards are structural, so most of this file reads source: every zero-possible normalize goes through
 * {@code safeNormalize} with a documented fallback, or sits behind an explicit length gate. The numeric half ports
 * {@code safeNormalize} and rebuilds the degenerate inputs the unguarded code turned into NaN.
 */
class RtSlangNaNGuardTest {

    private static final String MATH = "src/main/resources/shaders/slang/contracts/math.slang";
    private static final String BSDF = "src/main/resources/shaders/slang/shading/bsdf.slang";
    private static final String MICROFACET = "src/main/resources/shaders/slang/shading/microfacet.slang";
    private static final String EMITTERS = "src/main/resources/shaders/slang/light/block_emitters.slang";
    private static final String SUN = "src/main/resources/shaders/slang/light/sun.slang";
    private static final String ENTRY = "src/main/resources/shaders/slang/pathtrace/path_trace_entry.slang";
    private static final String VISIBILITY_PASS = "src/main/resources/shaders/slang/passes/velocity_reproject.slang";

    /** Mirror of {@code MATH_EPSILON}: the gate compares a squared length, so the length gate is its square root. */
    private static final double GATE = 1.0e-6;

    @Test
    void safeNormalizeFallsBackInsteadOfDividingByZero() {
        String math = read(MATH);
        assertTrue(math.contains("static const float MATH_EPSILON = 1.0e-6;"),
            "the gate is a named constant, so every fallback in the tree shares one threshold");
        assertTrue(math.contains("float3 safeNormalize(float3 value, float3 fallback) {"));
        assertTrue(math.contains("return lengthSquared > MATH_EPSILON ? value * rsqrt(lengthSquared) : fallback;"),
            "the comparison is on the squared length: at or below the gate the fallback wins and no division runs");

        double[] up = {0.0, 2.0, 0.0};
        assertArrayEquals(new double[] {0.0, 1.0, 0.0}, normalize(up, new double[] {1.0, 0.0, 0.0}),
            1e-12, "a real vector is still normalized to unit length");
        double[] zero = normalize(new double[] {0.0, 0.0, 0.0}, new double[] {0.0, 0.0, -1.0});
        assertArrayEquals(new double[] {0.0, 0.0, -1.0}, zero, 1e-12,
            "a zero vector must return the fallback instead of NaN");
        double sliverLength = Math.sqrt(GATE) * 0.5;
        double[] sliver = normalize(new double[] {sliverLength, 0.0, 0.0}, new double[] {0.0, 1.0, 0.0});
        assertArrayEquals(new double[] {0.0, 1.0, 0.0}, sliver, 1e-12,
            "a length under a millimetre is float noise: normalizing it would invent a random direction");
        double above = Math.sqrt(GATE) * 2.0;
        double[] kept = normalize(new double[] {above, 0.0, 0.0}, new double[] {0.0, 1.0, 0.0});
        assertArrayEquals(new double[] {1.0, 0.0, 0.0}, kept, 1e-12,
            "just above the gate the real direction survives: the fallback is not a blanket replacement");
        // the NEE half-vector case: the eye looks exactly along the key light, so V + L is zero
        double[] n = {0.0, 1.0, 0.0};
        assertArrayEquals(n, normalize(new double[] {0.0, 0.0, 0.0}, n), 1e-12,
            "an opposition must leave the half vector at the shading normal (peak glint), not NaN");
    }

    @Test
    void theGgxHalfVectorNeverNormalisesZero() {
        for (String file : new String[] {BSDF, MICROFACET}) {
            String source = read(file);
            assertTrue(source.contains("float3 H = safeNormalize(V + L, N);"),
                file + " must fall back to the shading normal when the view looks along the light");
            assertFalse(source.contains("normalize(V + L)"), file + " must not normalize an opposition to NaN");
        }
    }

    @Test
    void aCoincidentEmitterIsRejectedBeforeAnyDivision() {
        String emitters = read(EMITTERS);
        String sample = block(emitters, emitters.indexOf("LightSample blockEmitterEstimateSample("));
        assertTrue(sample.contains("if (e.solidAnglePdf <= 0.0 || e.phat <= 0.0) {"),
            "a zero density has no weight: dividing by it is the NaN, so the sample is invalidated first");
        assertTrue(sample.contains("direction = safeNormalize(direction, float3(0.0, 1.0, 0.0));"),
            "an emitter that coincides with the receiver leaves a zero vector; the up axis is the stand-in");
        assertTrue(sample.contains("if (cosReceiver <= 0.0) {"),
            "a light behind the receiver is rejected, not clamped into a divide by zero");
        assertTrue(sample.contains("/ max(cosReceiver, 1.0e-4)"), "the weight division keeps a floor");
        assertTrue(sample.contains("max(distanceTo - e.endBias, 0.001)"), "the shadow ray keeps a minimum length");
        assertFalse(sample.contains("normalize(direction)"),
            "the direction must not be normalized bare: a coincident emitter is exactly the zero case");
    }

    @Test
    void aKeyLightBehindTheReceiverIsRejectedNotDivided() {
        String sun = read(SUN);
        assertTrue(sun.contains("float3 axis = pickSun ? safeNormalize(sun.direction.xyz, float3(0.0, 1.0, 0.0))"));
        assertTrue(sun.contains(": safeNormalize(sun.moonDirection.xyz, float3(0.0, -1.0, 0.0));"),
            "a zero direction takes the axis it came from; a zero light vector would normalize to NaN");
        assertTrue(sun.contains("if (cosSurface <= 0.0) return result;"),
            "the receiver's cosine gates the sample before the source PDF divides by the solid angle");
        int divide = sun.indexOf("float sourcePdf = selectionPdf / sunSolidAngle(radius);");
        int reject = sun.indexOf("if (sourcePdf <= 0.0) return result;");
        assertTrue(divide > 0 && reject > divide,
            "a zero source PDF is a rejected sample, not an infinite weight");
    }

    @Test
    void theTangentFrameFallsBackToTheFaceWhenTheUvIslandCollapses() {
        String math = read(MATH);
        String frame = block(math, math.indexOf("void triangleTangentFrame("));
        assertTrue(frame.contains("if (abs(det) > 1.0e-12) {"),
            "a degenerate UV island is caught by the determinant gate");
        assertTrue(frame.contains("tangent = safeNormalize((e1 * d2.y - e2 * d1.y) / det, normal);"),
            "det != 0 does not prove the numerator is non-zero: collinear edges zero it while the UVs stay distinct");
        assertTrue(frame.contains("bitangent = safeNormalize((e2 * d1.x - e1 * d2.x) / det, normal);"));
        assertTrue(frame.contains("faceTangentFrame(normal, tangent, bitangent);"),
            "the axis frame is the honest answer for a collapsed island");

        // The reachable case the legacy test rebuilt: three distinct UVs and collinear positions. det is not zero,
        // the tangent numerator is exactly zero, and an unguarded normalize() turns it into NaN.
        double[] e1 = {1.0, 0.0, 0.0};
        double[] e2 = {2.0, 0.0, 0.0};
        double[] d1 = {1.0, 1.0};
        double[] d2 = {0.0, 2.0};
        double det = d1[0] * d2[1] - d2[0] * d1[1];
        assertEquals(2.0, det, 1e-12, "the UV pair is not collinear, so the determinant gate is open");
        double[] t = {
            e1[0] * d2[1] - e2[0] * d1[1],
            e1[1] * d2[1] - e2[1] * d1[1],
            e1[2] * d2[1] - e2[2] * d1[1]
        };
        double length = Math.sqrt(t[0] * t[0] + t[1] * t[1] + t[2] * t[2]);
        assertEquals(0.0, length, 0.0, "the unguarded tangent is exactly zero");
        assertTrue(Double.isNaN(t[0] / length), "and normalize() of it is NaN");
        assertArrayEquals(new double[] {0.0, 1.0, 0.0}, normalize(t, new double[] {0.0, 1.0, 0.0}), 1e-12,
            "the guard collapses the perturbation onto the real normal");
    }

    @Test
    void thePrimaryNormalIsGatedWhereItEntersThePath() {
        String entry = read(ENTRY);
        assertTrue(entry.contains("n = length(n) > 1.0e-4 ? normalize(n) : float3(0.0, 0.0, 1.0);"),
            "a zero-length primary normal would poison every term of the path; the entry replaces it");
        assertTrue(entry.contains(
                "result.primary.normal = length(normal) > 1.0e-4 ? normalize(normal) : float3(0.0, 0.0, 1.0);"),
            "the recorded vertex normal is gated the same way, or the reprojection and guides read the NaN");
    }

    @Test
    void theNeeCoreHasNoUnguardedNormalize() {
        // The camera basis is unit by construction and the two entry lines above are behind their length gates;
        // everything that can be zero-length - emitter direction, half vector, tangent, hit normal - goes through
        // safeNormalize.
        String slang = "src/main/resources/shaders/slang/";
        for (String file : new String[] {slang + "pathtrace/path_tracer.slang", MATH, EMITTERS, SUN, BSDF,
            slang + "contracts/light.slang", slang + "surface/surface.slang", slang + "surface/water.slang"}) {
            assertEquals(0, countLowercaseNormalize(read(file)), file + " normalizes an unguarded vector");
        }
        assertEquals(3, countLowercaseNormalize(read(ENTRY)),
            "the camera basis plus the two gated lines: a fourth bare call would be a new unguarded vector");
    }

    @Test
    void theCameraRayBuilderNeverDividesByAHomogeneousW() {
        // The deleted guard protected a builder that projected the far-plane point and divided by its clip w. The
        // typed primary ray does not do that: it composes the direction from the camera basis, where the forward
        // coefficient is one, so the vector can neither be zero nor infinite.
        String entry = read(ENTRY);
        String builder = block(entry, entry.indexOf("Ray pathTracePrimaryRay("));
        assertTrue(builder.contains("float3 direction = normalize(f.camera.forward.xyz"),
            "the primary ray is the explicit camera basis, not an inverse-projection quotient");
        assertFalse(builder.contains("mul("),
            "a matrix multiply plus a divide is exactly the unprojection whose zero w the deleted guard stopped");
        assertFalse(builder.contains("/"),
            "the builder has no division at all: that is stronger than guarding the divisor");
    }

    @Test
    void theVelocityPassSanitisesItsOwnHomogeneousDivide() {
        // velocity.slang and velocity_live.slang are ports of velocity_reproject.comp, which divided by w bare and
        // relied on its final non-finite sanitise. Both ports keep that protection, so a degenerate inverse
        // projection costs one disoccluded pixel instead of a poisoned motion vector; the pass gates the clip-w
        // divide on a finite value, which NaN fails.
        String velocity = read("src/main/resources/shaders/slang/history/velocity.slang");
        assertTrue(velocity.contains("if (!isFinite(result.motionPixels.x) || !isFinite(result.motionPixels.y) || "
                + "!isFinite(result.dynamicMask) ||"),
            "the typed walk owns the sanitise of its own motion, dynamic and disocclusion outputs");
        assertTrue(velocity.contains("result.motionPixels = float2(0.0);\n        result.dynamicMask = 0.0;\n"
                + "        result.disocclusionMask = 1.0;"),
            "a non-finite reprojection becomes no motion plus a full disocclusion, never a NaN history tap");

        String live = read("src/main/resources/shaders/slang/history/velocity_live.slang");
        assertTrue(live.contains("// Protection against NaN, Inf"),
            "the live port keeps the legacy sanitise that made its bare w divide safe");
        assertTrue(live.contains("motionVector = float2(0.0);\n        disocclusionMask = 1.0;"),
            "the live sanitise must clear the vector and force the disocclusion");

        String pass = read(VISIBILITY_PASS);
        assertTrue(pass.contains("params.hasPrevDepth && abs(result.previousClipW) > 1.0e-7) {"),
            "the linear-viewZ depth test is gated on a finite clip w: NaN fails the comparison and skips it");
        assertTrue(pass.contains("params.hasPrevDepth && result.previousClipW > 1.0e-6) {"));
    }

    // ---- helpers ----

    private static String read(String relative) {
        try {
            return Files.readString(Path.of(relative), StandardCharsets.UTF_8).replace("\r\n", "\n");
        } catch (IOException e) {
            throw new AssertionError("cannot read " + relative, e);
        }
    }

    private static String block(String source, int at) {
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
        throw new AssertionError("unbalanced block at offset " + at);
    }

    /** Occurrences of bare {@code normalize(} - {@code safeNormalize(} carries a capital N and does not match. */
    private static int countLowercaseNormalize(String source) {
        int at = 0;
        int hits = 0;
        while (true) {
            int found = source.indexOf("normalize(", at);
            if (found < 0) {
                return hits;
            }
            hits++;
            at = found + "normalize(".length();
        }
    }

    /** Port of safeNormalize: the fallback is returned when the squared length is at or below the gate. */
    private static double[] normalize(double[] v, double[] fallback) {
        double lengthSquared = v[0] * v[0] + v[1] * v[1] + v[2] * v[2];
        if (!(lengthSquared > GATE)) {
            return fallback.clone();
        }
        double inv = 1.0 / Math.sqrt(lengthSquared);
        return new double[] {v[0] * inv, v[1] * inv, v[2] * inv};
    }
}
