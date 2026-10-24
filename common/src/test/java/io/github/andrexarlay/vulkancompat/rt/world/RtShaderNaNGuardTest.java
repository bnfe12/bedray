package io.github.andrexarlay.vulkancompat.rt.world;

import org.junit.jupiter.api.Test;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import static org.junit.jupiter.api.Assertions.*;

/**
 * L2 guards against the NaN/Inf that the owner has already seen as a full black screen and as lingering speckles.
 * <p>
 * GLSL {@code normalize()} of a zero vector is NaN and {@code clamp(NaN)} is undefined, so one bad normal can travel: a voxel
 * mesh can carry a zero-area triangle (a quad stored as {@code (A,B,C,C)} makes the second triangle {@code (C,C,A)}), and a
 * half vector {@code V + L} cancels when the view looks exactly along the key light. Two of these are worse than a speck:
 * the water-boundary normal decides whether the whole subgroup is under water (its medium reaches every path), and the camera
 * ray builder divides by the homogeneous {@code w}.
 * <p>
 * The numeric part ports the two helpers ({@code normalizeOr}, {@code safeW}) and rebuilds the exact inputs that the old code
 * turned into NaN; the source checks keep every call site on a helper.
 */
public class RtShaderNaNGuardTest {

    @Test
    public void testZeroVectorHelperExists() {
        String src = RtWorldChecks.rtCommon();
        assertNotNull(src, "rt_common.glsl must be on the classpath");
        assertTrue(src.contains("vec3 normalizeOr(vec3 v, vec3 fallback)"),
            "a shared guarded normalize is what keeps the geometric call sites consistent");
        assertTrue(src.contains("return len2 > 1.0e-12 ? v * inversesqrt(len2) : fallback;"),
            "the gate itself is load-bearing: 1e-6 of length is a sliver, and a looser value would start discarding real faces");
    }

    @Test
    public void testNoBareNormalizeOfAZeroCrossProduct() {
        String src = RtWorldChecks.rtCommon();
        assertNotNull(src);
        assertEquals(0, count(src, "normalize(cross("),
            "a cross product of coincident vertices is zero: normalize() of it is NaN, which then poisons the normal, the ray "
                + "offset and the albedo divide");
        assertEquals(1, count(src, "vec3 n = normalizeOr(cross(p1 - p0, p2 - p0), -dir);"),
            "the block-triangle normal must fall back to the direction towards the camera");
    }

    @Test
    public void testWaterBoundaryRejectsDegenerateTriangles() {
        String src = RtWorldChecks.rtCommon();
        assertNotNull(src);
        // The material test below the normal treats NaN as "solid water face" (abs(NaN) < 0.5 is false), so the degenerate
        // triangle has to be rejected before the classification, not just given a fallback normal.
        int areaTest = src.indexOf("if (g2 < 1.0e-12) {");
        assertTrue(areaTest > 0, "the degenerate test is an area test of the cross product");
        int degenerate = src.indexOf("return 3u;", areaTest);
        assertTrue(degenerate > areaTest, "traceWaterBoundary must pass a zero-area triangle through as a translucent layer");
        int normal = src.indexOf("vec3 n = g * inversesqrt(g2);");
        assertTrue(normal > degenerate, "the normal must be built only after the degenerate test");
    }

    @Test
    public void testGgxHalfVectorIsGuarded() {
        String src = RtWorldChecks.rtCommon();
        assertNotNull(src);
        assertFalse(src.contains("normalize(V + L)"),
            "V + L is zero when the eye looks along the key light: the same 0/0 already fixed on the water glint");
        assertTrue(src.contains("vec3 H = normalizeOr(V + L, N);"),
            "the GGX half vector must fall back to the shading normal at exact opposition");
    }

    @Test
    public void testCameraRayBuilderNeverDividesByZeroW() {
        String src = RtWorldChecks.rtCommon();
        assertNotNull(src);
        assertTrue(src.contains("return abs(w) < 1.0e-6 ? (w < 0.0 ? -1.0e-6 : 1.0e-6) : w;"),
            "safeW() must keep the sign of a genuine negative w instead of flipping the ray");
        assertEquals(0, count(src, "/ w.w - cameraOrigin()") + count(src, "/ c0.w)") + count(src, "/ c1.w)"),
            "no unguarded homogeneous division may remain: a zero w makes the direction Inf and then NaN for the whole frame");
        // Guarding only the divisor leaves 0/1e-6 - cameraOrigin(), and cameraOrigin() is exactly zero when the player does not
        // bob: a fully degenerate inverse projection still normalises a zero vector. Every quotient must end in normalizeOr().
        assertTrue(src.contains("return normalizeOr(centerW.xyz / safeW(centerW.w) - cameraOrigin(), rawCameraForward());"));
        assertTrue(src.contains("return normalizeOr(w.xyz / safeW(w.w) - cameraOrigin(), rawCameraForward());"));
        assertTrue(src.contains("normalizeOr(c1.xyz / safeW(c1.w), rawCameraForward())"));
        assertTrue(src.contains("normalizeOr(c0.xyz / safeW(c0.w), rawCameraForward())"));
    }

    @Test
    public void testSkyMotionVectorUsesTheSameGuards() {
        // RtPathCompiler expansion: the sky branch writes a motion vector the upscaler reprojects from, and a NaN there survives
        // far longer than one frame. This file is outside the common expansion the other tests read.
        String path = RtShaderCompiler.resolveIncludes(
            RtShaderCompiler.loadSource("/shaders/rt_path.comp"), "/shaders/rt_path.comp");
        assertNotNull(path);
        assertFalse(path.contains("normalize(prevClipDir.xyz / prevClipDir.w)"),
            "an Inf quotient divided into a unit vector is NaN (Inf * inversesqrt(Inf)), so the divisor needs safeW() as well");
        assertTrue(path.contains("vec3 skyDir = normalizeOr(prevClipDir.xyz / safeW(prevClipDir.w), rawCameraForward());"));
    }

    @Test
    public void testCameraForwardFallbackIsFinite() {
        // The shader fallbacks pass rawCameraForward(), so it must never be NaN or non-unit: a singular rotation inverts to a
        // zero matrix, whose components are finite and would slip past a plain isFinite() check into normalize().
        Vector3f dest = new Vector3f();
        assertEquals(new Vector3f(0f, 0f, -1f), CameraBobPose.rawForward(null, dest), "no rotation: the fixed back axis");
        Vector3f degenerate = CameraBobPose.rawForward(new Matrix4f(), dest);
        assertEquals(new Vector3f(0f, 0f, -1f), degenerate, "a zero matrix must not reach normalize()");
        assertTrue(Float.isFinite(degenerate.lengthSquared()));
        Matrix4f rotation = new Matrix4f().rotateY((float) Math.toRadians(90.0));
        Vector3f real = CameraBobPose.rawForward(rotation, new Vector3f());
        assertEquals(1f, real.length(), 1e-5f, "a genuine rotation still yields a unit axis");
        // Sign-independent statement of the contract (the same one RtWorldChecks pins): the axis reproduces the view-space z of
        // the rotation it came from, so a re-signing of the fallback or of JOML's convention cannot slip through silently.
        Vector3f world = new Vector3f(0.6f, -0.2f, 0.8f);
        Vector3f viewPoint = new Vector3f(world).mulDirection(rotation);
        assertEquals(-world.dot(real), viewPoint.z, 2e-4f, "the raw axis must match the rotation it was derived from");
    }

    @Test
    public void testSafeWSemantics() {
        assertEquals(1.0e-6, safeW(0.0), 0.0, "an exact zero divisor is replaced, not divided by");
        assertEquals(1.0e-6, safeW(-0.0), 0.0, "IEEE -0.0 < 0.0 is false, so a negative zero takes the positive branch");
        assertEquals(1.0e-6, safeW(5.0e-7), 0.0, "a sub-gate positive w is replaced");
        assertEquals(-1.0e-6, safeW(-5.0e-7), 0.0, "the sign of a tiny negative w is preserved, so the ray is not flipped");
        assertEquals(-1.0e-6, safeW(-1.0e-7), 0.0);
        assertEquals(-1.0, safeW(-1.0), 0.0, "a genuine w must pass through untouched");
        assertEquals(2.5, safeW(2.5), 0.0);
        assertTrue(Double.isFinite(1.0 / safeW(0.0)), "the point of the guard: the quotient stays finite");
    }

    @Test
    public void testTangentFrameIsGuardedWhenAnEdgeCollapses() {
        String src = RtWorldChecks.rtCommon();
        assertNotNull(src);
        assertFalse(src.contains("vec3 T = normalize((e1 * d2.y - e2 * d1.y) * r);")
                || src.contains("vec3 B = normalize((e2 * d1.x - e1 * d2.x) * r);"),
            "det != 0 only says the UVs are not collinear: a collapsed edge still cancels the tangent, and a NaN tangent "
                + "reaches s.ns and then every shading term (the entity twin uses rDet behind its own area gate and is safe)");
        assertTrue(src.contains("vec3 T = normalizeOr((e1 * d2.y - e2 * d1.y) * r, n);"));
        assertTrue(src.contains("vec3 B = normalizeOr((e2 * d1.x - e1 * d2.x) * r, n);"));
        assertTrue(src.contains("vec3 nsObj = normalizeOr(T * xy.x + B * xy.y + n * z, n);"),
            "the tangent-space vector itself needs the guard too: |xy| can reach 1, which leaves x + y + z == 0");

        // The reachable case: a zero-area (collinear) triangle whose corner UVs are still three distinct atlas coordinates, so
        // det != 0 while the tangent numerator cancels exactly. A collapsed edge does NOT do this: p1 == p0 implies d1 == 0
        // (uv1 is fetched with the same vertex index as p1), which zeroes det and the old gate already caught it.
        double[] e1 = {1.0, 0.0, 0.0};
        double[] e2 = {2.0, 0.0, 0.0};
        double[] d1 = {1.0, 1.0};
        double[] d2 = {0.0, 2.0};
        double det = d1[0] * d2[1] - d2[0] * d1[1];
        assertEquals(2.0, det, 1e-12, "the UV pair is not collinear, so the existing det gate is open");
        double[] t = {
            e1[0] * d2[1] - e2[0] * d1[1],
            e1[1] * d2[1] - e2[1] * d1[1],
            e1[2] * d2[1] - e2[2] * d1[1]
        };
        assertEquals(0.0, Math.sqrt(t[0] * t[0] + t[1] * t[1] + t[2] * t[2]), 0.0, "the unguarded tangent is exactly zero");
        double len = Math.sqrt(t[0] * t[0] + t[1] * t[1] + t[2] * t[2]);
        assertTrue(Double.isNaN(t[0] / len), "and normalize() of it is NaN");
        double[] n = {0.0, 1.0, 0.0};
        assertArrayEquals(n, normalizeOr(t, n, shaderGate()), 1e-12, "the guard collapses the perturbation onto the real normal");
    }

    @Test
    public void testSkyHorizonNormalizeIsGuarded() {
        String src = RtWorldChecks.rtCommon();
        assertNotNull(src);
        assertFalse(src.contains("normalize(vec2(d.x, d.z) + vec2(1.0e-6, 0.0))"),
            "the old bias moved only x, so a direction of (-1e-6, 0) in the horizontal plane still normalized a zero vector");
        assertTrue(src.contains("hz = hz2 > 1.0e-12 ? hz * inversesqrt(hz2) : vec2(1.0, 0.0);"),
            "a straight-down view has no horizon direction; any unit vector is a valid stand-in");
    }

    @Test
    public void testRayModeSanitisesTheFrameBeforeWritingIt() {
        String rays = RtShaderCompiler.resolveIncludes(
            RtShaderCompiler.loadSource("/shaders/rt_rays.comp"), "/shaders/rt_rays.comp");
        int write = rays.indexOf("imageStore(outImage, p, vec4(clamp(tonemapped ? finishColor(mean) : mean, 0.0, 1.0), 1.0));");
        for (String channel : new String[] {"r", "g", "b"}) {
            int at = rays.indexOf("if (isnan(mean." + channel + ") || isinf(mean." + channel + ")) mean." + channel + " = 0.0;");
            assertTrue(at > 0, "ray mode must replace a non-finite " + channel + " (the path tracer already does)");
            assertTrue(write > at, "the sanitise of " + channel + " must run before the frame is written");
        }
        // The demodulated direct channel feeds NRD / DLSS-RR, whose history keeps a bad value alive far longer than one frame.
        int gbWrite = rays.indexOf("vec4(directOut * atmT + atmA, 0.0)");
        assertTrue(gbWrite > 0, "the gbuffer variant must still write the direct channel");
        for (String channel : new String[] {"r", "g", "b"}) {
            int at = rays.indexOf("if (isnan(directOut." + channel + ") || isinf(directOut." + channel + ")) directOut." + channel + " = 0.0;");
            assertTrue(at > 0 && at < gbWrite, "the direct G-buffer channel must be sanitised in " + channel + " before it is stored");
        }
    }

    @Test
    public void testNormalizeOrSemantics() {
        double gate = shaderGate();
        assertEquals(1.0e-12, gate, 0.0, "the gate read out of the shader is the one the model below uses");

        double[] up = {0.0, 2.0, 0.0};
        double[] unit = normalizeOr(up, new double[] {1.0, 0.0, 0.0}, gate);
        assertEquals(0.0, unit[0], 1e-12);
        assertEquals(1.0, unit[1], 1e-12, "a normal input must still be normalized to unit length");

        double[] degenerate = normalizeOr(new double[] {0.0, 0.0, 0.0}, new double[] {0.0, 0.0, -1.0}, gate);
        assertArrayEquals(new double[] {0.0, 0.0, -1.0}, degenerate, 1e-12,
            "a zero vector must return the fallback instead of NaN");

        double sliverLength = Math.sqrt(gate) * 0.5;
        double[] sliver = normalizeOr(new double[] {sliverLength, 0.0, 0.0}, new double[] {0.0, 1.0, 0.0}, gate);
        assertArrayEquals(new double[] {0.0, 1.0, 0.0}, sliver, 1e-12,
            "a length just under sqrt(gate) is float noise: normalizing it would invent a random direction");

        double above = Math.sqrt(gate) * 2.0;
        double[] kept = normalizeOr(new double[] {above, 0.0, 0.0}, new double[] {0.0, 1.0, 0.0}, gate);
        assertArrayEquals(new double[] {1.0, 0.0, 0.0}, kept, 1e-12,
            "just above the gate the real direction must survive: the fallback is not a blanket replacement");

        double[] n = {0.0, 1.0, 0.0};
        double[] half = normalizeOr(new double[] {0.0, 0.0, 0.0}, n, gate);
        assertEquals(1.0, half[1], 1e-12, "looking along the sun must leave the half vector at the shading normal (peak glint)");
    }

    /** The gate literal of normalizeOr, read from the shader so the numeric port cannot drift from it. */
    private static double shaderGate() {
        String src = RtWorldChecks.rtCommon();
        assertNotNull(src);
        int body = src.indexOf("vec3 normalizeOr(vec3 v, vec3 fallback)");
        assertTrue(body > 0, "normalizeOr must be declared in the expanded common file");
        int from = src.indexOf("len2 > ", body) + "len2 > ".length();
        int to = src.indexOf(" ?", from);
        assertTrue(from > 0 && to > from, "normalizeOr must compare len2 against a literal");
        return Double.parseDouble(src.substring(from, to));
    }

    /** Port of safeW: the divisor is clamped away from zero, keeping the sign of a genuine negative w. */
    private static double safeW(double w) {
        return Math.abs(w) < 1.0e-6 ? (w < 0.0 ? -1.0e-6 : 1.0e-6) : w;
    }

    /** Port of normalizeOr: the fallback is returned when the squared length is at or below the gate. */
    private static double[] normalizeOr(double[] v, double[] fallback, double gate) {
        double len2 = v[0] * v[0] + v[1] * v[1] + v[2] * v[2];
        if (!(len2 > gate)) {
            return fallback.clone();
        }
        double inv = 1.0 / Math.sqrt(len2);
        return new double[] {v[0] * inv, v[1] * inv, v[2] * inv};
    }

    private static int count(String haystack, String needle) {
        int n = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + needle.length())) {
            n++;
        }
        return n;
    }

    public static void runAll() {
        RtShaderNaNGuardTest t = new RtShaderNaNGuardTest();
        t.testZeroVectorHelperExists();
        t.testNoBareNormalizeOfAZeroCrossProduct();
        t.testWaterBoundaryRejectsDegenerateTriangles();
        t.testGgxHalfVectorIsGuarded();
        t.testCameraRayBuilderNeverDividesByZeroW();
        t.testSkyMotionVectorUsesTheSameGuards();
        t.testCameraForwardFallbackIsFinite();
        t.testSafeWSemantics();
        t.testTangentFrameIsGuardedWhenAnEdgeCollapses();
        t.testSkyHorizonNormalizeIsGuarded();
        t.testRayModeSanitisesTheFrameBeforeWritingIt();
        t.testNormalizeOrSemantics();
    }
}
