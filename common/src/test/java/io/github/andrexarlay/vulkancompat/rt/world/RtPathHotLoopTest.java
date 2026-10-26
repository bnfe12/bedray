package io.github.andrexarlay.vulkancompat.rt.world;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cost invariants of the path tracing hot loop: {@code rt_path.comp} runs {@code spp} full paths per pixel, so every
 * register and every rejected candidate is paid for at screen resolution.
 *
 * <p>These are not style checks. Each one pins a property that is easy to lose in a later edit and expensive when lost:
 * <ol>
 *   <li>one {@code Surface} copy satisfies both the temporal accumulation and the denoiser G-buffer, instead of three
 *       live copies (a 26-field struct held across the sample loop is what pushes a ray generation shader into spills);</li>
 *   <li>the sun setup (disk sample, cell rotation, shadow origin) runs only for a diffuse vertex under a switched-on key
 *       light;</li>
 *   <li>an emitter candidate that the sampler would reject anyway is rejected before its texture fetch;</li>
 *   <li>a transmittance walk does not build a shading normal it never reads.</li>
 * </ol>
 * All four are behaviour preserving: the numbers the shader produces stay the same, only the work between them shrinks.
 */
public class RtPathHotLoopTest {
    private static final String PATH = "/shaders/glsl/rt_path.comp";
    private static final String SURFACE = "/shaders/glsl/rt_common_surface.glsl";
    private static final String EMITTERS = "/shaders/glsl/rt_common_emitters.glsl";

    private static final String TRACE_SIG = "PathResult tracePath(vec3 origin, vec3 dir, WaterMedium med";
    private static final String TRANSMITTANCE_SIG = "vec3 traceTransmittance(vec3 origin, vec3 d, float tmax)";
    private static final String FETCH_SIG =
        "void fetchSurface(uint slot, uint geom, uint prim, vec2 bary, float t, vec3 origin, vec3 dir, bool wantNormal, out Surface s)";

    @Test
    public void oneSurfaceCopyFeedsAccumulationAndDenoiser() {
        String path = load(PATH);
        assertEquals(1, count(path, "Surface accBest;"), "one accumulated surface, not one per consumer");
        assertFalse(path.contains("Surface accFirst;"), "the accumulation copy was merged into accBest");
        assertFalse(path.contains("accFirst."), "nothing reads a separate accumulation surface");
        assertFalse(path.contains("gbFirst"), "the G-buffer copy was merged into accBest");
        assertFalse(path.contains("Surface visible;"), "the first visible hit is written straight into the out parameter");
        // Both consumers must read the same filled struct: an unused copy would be the old duplication in disguise.
        assertTrue(path.contains("temporalAccumulate(p, size, eAcc, vec3(0.0), accumHit, accBest.pos"),
            "the accumulation reprojects the shared surface");
        assertTrue(path.contains("imageStore(gbViewZ, p, vec4(viewZ, 0.0, 0.0, 0.0));"), "the G-buffer still writes depth");
        assertTrue(count(path, "accBest.") >= 8, "the G-buffer block reads accBest, not a re-selected copy");
    }

    @Test
    public void firstVisibleIsCapturedWhereItIsHit() {
        String trace = functionBody(load(PATH), TRACE_SIG);
        assertTrue(trace.contains("firstVisible = s;"), "the first hit is stored when it happens");
        assertFalse(trace.contains("firstVisible = visible"), "no copy deferred to the end of the path");
        // One distance, one place: the denoiser depth used to be routed through a local that shadowed the struct field.
        // Without the local check the count below would still pass on `res.firstDist = firstDist;`.
        assertFalse(trace.contains("float firstDist"), "no local mirror of the first-hit distance");
        assertEquals(2, count(trace, "res.firstDist ="), "initial value plus the single first-hit assignment");
    }

    @Test
    public void sunSetupRunsOnlyForALitDiffuseVertex() {
        String path = load(PATH);
        int gate = path.indexOf("if (s.metal < 0.5 && keyLightOn()) {");
        assertTrue(gate > 0, "the diffuse key-light gate moved or vanished");
        for (String work : new String[]{"sunShadowDirectionAt(", "sunShadowRotation(", "pixelShadowApplies(s, med.inside)"}) {
            int at = path.indexOf(work);
            assertTrue(at > gate, work + " must be paid for inside the gate, not before it");
        }
        // The gate must still gate: the sun term is inside it and the specular path never reaches it.
        int gateEnd = path.indexOf("\n        // NEE of the emissive surfaces", gate);
        assertTrue(gateEnd > gate, "the diffuse sun block must still end before the emitter NEE");
        assertTrue(path.substring(gate, gateEnd).contains("traceTransmittance(shOrigin, shDir, 2048.0)"),
            "the one visibility ray of the vertex stays inside the block");
    }

    @Test
    public void emitterCandidatesAreRejectedBeforeTheirTextureFetch() {
        String emitters = load(EMITTERS);
        String sampler = functionBody(emitters, "float emitterPhat(");
        String loop = functionBody(emitters, "vec3 sampleEmittersImpl(");
        // The gate is a copy of the sampler's own zero tests, so it can only skip candidates that would add no weight.
        assertTrue(sampler.contains("float lightR = min(maxDist, EMITTER_RADIUS);"), "the reach of an emitter lives in one place");
        assertTrue(sampler.contains("s.emission > 0.5 && d2 < 0.25"), "the self-shadow test of the sampler");
        assertTrue(loop.contains("float lightR = min(maxDist, EMITTER_RADIUS);"), "the gate uses the same reach");
        assertTrue(loop.contains("candD2 > lightR * lightR || candD2 < (s.emission > 0.5 ? 0.25 : 1.0e-6)"),
            "the gate mirrors both zero tests of emitterPhat");
        int gate = loop.indexOf("candD2");
        int fetch = loop.indexOf("textureLod(matAtlas, yuv");
        assertTrue(gate > 0 && fetch > gate, "the distance gate must precede the material-atlas fetch of the candidate");
        // The stratified index probability, written with one square root pair instead of two quotients.
        assertTrue(loop.contains("float pIdx = (sqrt(float(idx + 1u)) - sqrt(float(idx))) * inversesqrt(float(n));"),
            "P(i) keeps its shape");
    }

    @Test
    public void stratifiedIndexProbabilityIsUnchanged() {
        // sqrt((i+1)/n) - sqrt(i/n) == (sqrt(i+1) - sqrt(i)) / sqrt(n): the rewrite must be exact, not approximate.
        for (int n : new int[]{1, 2, 7, 64, 512}) {
            for (int i = 0; i < n; i++) {
                double before = Math.sqrt((i + 1.0) / n) - Math.sqrt((double) i / n);
                double after = (Math.sqrt(i + 1.0) - Math.sqrt(i)) * (1.0 / Math.sqrt(n));
                assertEquals(before, after, 1.0e-12, "n=" + n + " i=" + i);
            }
        }
    }

    @Test
    public void transmittanceWalkSkipsTheShadingNormal() {
        String surface = load(SURFACE);
        assertTrue(surface.contains(FETCH_SIG), "fetchSurface must expose the wantNormal switch");
        String fetch = functionBody(surface, FETCH_SIG);
        int gate = fetch.indexOf("if (wantNormal && ((labN && hasFlag(FLAG_NORMAL_MAPS))");
        assertTrue(gate > 0, "the normal-atlas fetch is gated");
        assertTrue(fetch.indexOf("textureLod(nrmAtlas, uv, 0.0)") > gate, "no normal fetch outside the gate");
        assertTrue(fetch.indexOf("if (wantNormal) {") > 0, "the water wave normal is gated too");
        assertTrue(fetch.indexOf("waterWaveNormal(") > fetch.indexOf("if (wantNormal) {"),
            "an unshaded hit does not build a wave normal");
        // Transmittance reads tint, alpha, f0 and the geometric normal; it must not pay for the rest.
        String walk = functionBody(surface, TRANSMITTANCE_SIG);
        assertTrue(walk.contains("origin, d, false, s)"), "the transmittance walk fetches without a shading normal");
        assertTrue(functionBody(surface, "bool traceSurface(vec3 origin, vec3 dir, float tmin, float tmax, bool skipTranslucent, bool excludeSelfAvatar, out Surface s)")
            .contains("origin, dir, true, s)"), "a real hit is shaded with the full surface");
    }

    private static String load(String resource) {
        String src = RtShaderCompiler.loadSource(resource);
        assertNotNull(src, "shader source missing: " + resource);
        return src;
    }

    /** Body of a GLSL function (from the opening brace to its matching close). */
    private static String functionBody(String source, String signature) {
        int at = source.indexOf(signature);
        assertTrue(at >= 0, "not found, the check would pass on nothing: " + signature);
        int open = source.indexOf('{', at);
        assertTrue(open > 0, "no body for " + signature);
        int depth = 0;
        for (int i = open; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return source.substring(open, i + 1);
            }
        }
        throw new IllegalStateException("unbalanced body for " + signature);
    }

    private static int count(String haystack, String needle) {
        int n = 0;
        for (int at = haystack.indexOf(needle); at >= 0; at = haystack.indexOf(needle, at + needle.length())) {
            n++;
        }
        return n;
    }
}
