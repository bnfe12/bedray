package io.github.andrexarlay.vulkancompat.rt.world;

import io.github.andrexarlay.vulkancompat.rt.material.LabPbr;
import io.github.andrexarlay.vulkancompat.rtx.config.RtSettings;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * L2 JUnit tests verifying:
 * 1. Energy-conserving reflection split: metals have no diffuse exitance, dielectrics keep (1 - F).
 * 2. GGX VNDF sampling: the white-furnace estimator F(V.H) * G2/G1 matches the brute-force single-scattering BRDF integral.
 * 3. Power-based emitter boost of torches and small cutout emitters in RtEmitters.
 * 4. Voxel edge bleeding prevention (bounded shadowBias) and normal map terminator smoothing.
 */
public class RtShadingFixesTest {

    private static final float EPSILON = 1e-4f;

    @Test
    public void testReflectionEnergySplit() {
        runReflectionEnergyChecks();
    }

    @Test
    public void testGgxVndfEnergy() {
        runGgxVndfChecks();
    }

    @Test
    public void testTorchAndEmitterBoost() {
        runTorchBoostChecks();
    }

    @Test
    public void testVoxelEdgeBleedingAndNormalSmoothing() {
        runVoxelEdgeBleedingChecks();
    }

    @Test
    public void testPixelShadows() {
        runPixelShadowChecks();
    }

    @Test
    public void testRtPresetLighting() {
        runRtPresetChecks();
    }

    @Test
    public void testDenoisedSpecularContract() {
        runDenoisedSpecularChecks();
    }

    @Test
    public void testRoughSurfaceEnergySplit() {
        runRoughEnergySplitChecks();
    }

    @Test
    public void testBiomeAmbientVariation() {
        runBiomeAmbientChecks();
    }

    @Test
    public void testEmissiveSurfacesShowTheirLight() {
        runEmissiveSurfaceChecks();
    }

    public static void runAll() {
        RtShadingFixesTest test = new RtShadingFixesTest();
        test.testReflectionEnergySplit();
        test.testGgxVndfEnergy();
        test.testTorchAndEmitterBoost();
        test.testVoxelEdgeBleedingAndNormalSmoothing();
        test.testPixelShadows();
        test.testRtPresetLighting();
        test.testDenoisedSpecularContract();
        test.testRoughSurfaceEnergySplit();
        test.testBiomeAmbientVariation();
        test.testEmissiveSurfacesShowTheirLight();
    }

    /**
     * A glowing block must LOOK like it emits: its visible face carries the same scene-linear radiance the emitter list casts
     * into the room, and that radiance survives the denoiser input. Both halves were broken (owner report 2026-10-25 "blocks
     * give light but do not look like the texture emits"): the emission was anchored to a capped display colour (dimmer than
     * the light it casts, and below the bloom threshold), and the DLSS-RR pack never read the channel it is written to.
     */
    private static void runEmissiveSurfaceChecks() {
        String surface = RtWorldChecks.rtCommon();
        int emittedStart = surface.indexOf("vec3 emitted(Surface s) {");
        int emittedEnd = surface.indexOf("\n}", emittedStart);
        assertTrue(emittedStart > 0 && emittedEnd > emittedStart, "emitted() is declared in the common modules");
        String body = surface.substring(emittedStart, emittedEnd);

        assertTrue(surface.contains("float flux = EMISSIVE_GAIN * EMISSIVE_FLUX_GAIN;"),
            "the visible emission uses the same gain as the emitter list");
        assertTrue(body.contains("return emitterLightColor(s.albedoTex) * (s.emission * flux);"),
            "a glowing texel radiates its classified colour at its own emission strength");
        assertTrue(body.contains("return redColor * (s.emission * 1.2 * flux);"), "redstone keeps its saturated red glow");
        assertTrue(body.contains("return fieryColor * (1.15 * pulse * texelHotspot * max(s.emission, 1.0) * flux);"),
            "lava keeps its pulse and hotspot");
        assertFalse(body.contains("displayToRadiance"),
            "the emission is scene-linear: a display-anchored value cannot follow the exposure nor reach the bloom threshold");
        assertFalse(surface.contains("vec3 emissionDisplay("), "the capped display helper is gone, not kept as a second path");

        assertTrue(surface.contains("leNoTrans = texRgb * chosenEm * EMISSIVE_GAIN * EMISSIVE_FLUX_GAIN;"),
            "the cast light and the visible face are the same expression");

        String path = RtShaderCompiler.loadSource("/shaders/rt_path.comp");
        assertTrue(path.contains("res.direct0 += e;"), "the first-vertex emission goes to the analytic direct channel");
        assertTrue(path.contains("gbRestSum += r.radiance - r.direct0;"),
            "and is subtracted from the denoised diffuse channel, so nothing is counted twice");
    }

    private static float fresnelSchlick(float f0, float cosTheta) {
        float c = Math.max(0.0f, Math.min(1.0f, cosTheta));
        float inv = 1.0f - c;
        float inv5 = inv * inv * inv * inv * inv;
        return f0 + (1.0f - f0) * inv5;
    }

    private static float smoothstep(float edge0, float edge1, float x) {
        float t = Math.max(0.0f, Math.min(1.0f, (x - edge0) / (edge1 - edge0)));
        return t * t * (3.0f - 2.0f * t);
    }

    private static void runReflectionEnergyChecks() {
        // Dielectric: the diffuse share falls monotonically from 1 - f0 at normal incidence as the Fresnel reflectance
        // rises towards grazing; the two shares stay within [0,1] (no negative or over-unity energy).
        for (float f0 : new float[] {0.02f, 0.04f}) {
            float previous = 2.0f;
            for (float cosV : new float[] {1.0f, 0.7f, 0.3f, 0.05f, 0.0f}) {
                float f = fresnelSchlick(f0, cosV);
                float kD = 1.0f - f;
                assertTrue(kD >= 0.0f && kD <= 1.0f, "kD in [0,1] at cosV=" + cosV);
                assertTrue(kD <= previous + EPSILON, "kD does not rise towards grazing at cosV=" + cosV);
                previous = kD;
                assertTrue(f >= f0 - EPSILON, "reflectance never falls below F0 at cosV=" + cosV);
            }
            assertTrue(Math.abs(fresnelSchlick(f0, 0.0f) - 1.0f) < 1.0e-4f, "grazing reflectance is 1");
            assertTrue(Math.abs(fresnelSchlick(f0, 1.0f) - f0) < 1.0e-6f, "normal reflectance is F0");
        }

        // The old non-physical shortcuts (15 % metal diffuse, Fresnel scaled by gloss) must stay gone, the diffuse share
        // must apply to metals even with reflections off, and the sampled reflection must be the VNDF estimator.
        String rays = RtShaderCompiler.loadSource("/shaders/rt_rays.comp");
        String common = RtWorldChecks.rtCommon();
        assertFalse(rays.contains("diffuse * 0.15"), "no 15% metal diffuse in rt_rays.comp");
        assertFalse(common.contains("diffuse * 0.15"), "no 15% metal diffuse in rt_common.glsl");
        assertFalse(rays.contains("* F * gloss"), "reflection is no longer scaled by gloss");
        assertFalse(rays.contains("coneAround(rdir, spread"),
            "the VNDF reflection path is not cone-jittered (the water far-sheen keeps its own coneAround)");
        assertTrue(rays.contains("vec3 kD = s.metal > 0.5 ? vec3(0.0) : vec3(1.0) - fresnelSchlick(f0, cosV);"),
            "kD is computed before the reflections gate");
        assertTrue(rays.contains("imageStore(gbDiffuse, p, vec4((indirect + emissiveLight) * diffK * thr * atmT, gbHitDist));"),
            "the denoiser diffuse channel carries the raw kD lighting (metals have no diffuse), fog included");
        assertTrue(rays.contains("sampleGgxVndf(s.ns, V, alpha, u, vdotH)"), "rt_rays.comp samples the GGX VNDF");
        assertTrue(rays.contains("smithG2OverG1(cosV, dot(rdir, s.ns), alphaSq)"),
            "rt_rays.comp weights VNDF samples by F(V.H) * G2/G1");
        assertTrue(rays.contains("skyEnvRadiance(rdir, s.roughness)"),
            "rough reflections keep clouds and stars through skyEnvRadiance");
        assertTrue(common.contains("vec3 sampleGgxVndf(vec3 n, vec3 v, float alpha, vec2 u, out float vdotH)"),
            "rt_common.glsl defines the VNDF sampler");
        assertTrue(common.contains("vec3 skyEnvRadiance(vec3 dir, float roughness)"),
            "rt_common.glsl defines the cloud-aware environment radiance");
    }

    /**
     * The owner-visible radial darkening (report 2026-10-21: "if you stand close to a wall the far part of the wall is
     * darkened, same for the blocks you stand on"). Above ROUGH_SPLIT no specular lobe is sampled, so splitting the diffuse
     * by the grazing Schlick reflectance deleted energy that nothing reflected back: at 84 degrees a stone-like F0 of 0.04
     * keeps only 39% of the diffuse, which is a vignette around the point of the surface nearest the camera. A rough
     * dielectric must split with the constant F0 instead.
     */
    private static void runRoughEnergySplitChecks() {
        float f0 = 0.04f;
        float split = 1.0f - f0;
        for (float cosV : new float[] {1.0f, 0.5f, 0.25f, 0.1f, 0.0f}) {
            float full = 1.0f - fresnelSchlick(f0, cosV);
            assertTrue(split >= full - EPSILON, "the rough split never removes more energy than the full curve, cosV=" + cosV);
        }
        assertTrue(1.0f - fresnelSchlick(f0, 0.1f) < 0.45f,
            "the guarded bug: the full Schlick curve keeps under 45% of the diffuse at 84 degrees");

        String common = RtWorldChecks.rtCommon();
        String path = RtShaderCompiler.loadSource("/shaders/rt_path.comp");
        assertTrue(common.contains("const float ROUGH_SPLIT = 0.6;"),
            "one roughness threshold shared by the energy split and the specular lobe probability");
        assertTrue(common.contains("vec3 fresnelEnergySplit(vec3 f0, float cosTheta, float roughness, bool metal)"),
            "rt_common.glsl defines the split");
        assertTrue(common.contains("(metal || roughness < ROUGH_SPLIT) ? fresnelSchlick(f0, cosTheta) : f0;"),
            "traced-specular surfaces keep the Fresnel curve, rough dielectrics keep F0");
        assertEquals(2, count(common, "vec3 F = fresnelEnergySplit(f0, cosV, s.roughness, s.metal > 0.5);"),
            "both secondary-hit shading paths use the split");
        assertTrue(path.contains("vec3 F = fresnelEnergySplit(f0, cosV, s.roughness, s.metal > 0.5);"),
            "the path tracer primary hit uses the split");
        assertEquals(0, count(path, "vec3 F = fresnelSchlick(f0, cosV);"),
            "no primary-hit path keeps the full grazing Fresnel on the diffuse share");
        assertTrue(path.contains("s.roughness < ROUGH_SPLIT ? clamp(max(Fsc, 0.45 * gloss), 0.05, 0.95) : 0.0"),
            "the specular lobe is sampled only below the same threshold");
        // The sun / emitter NEE, the foliage back scatter, the canopy fill and the diffuse weight all read the split value.
        assertEquals(5, count(path.substring(path.indexOf("vec3 F = fresnelEnergySplit")), "(1.0 - Fsc)"),
            "sun NEE, foliage back scatter, emitter NEE, canopy fill and the diffuse continuation share the split");
    }

    // ---- GGX VNDF white-furnace model (double precision port of rt_common.glsl sampleGgxVndf) ----------------------------
    private static double dot(double[] a, double[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    private static double[] cross(double[] a, double[] b) {
        return new double[] {a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
    }

    private static double[] normalize(double[] v) {
        double len = Math.sqrt(dot(v, v));
        return new double[] {v[0] / len, v[1] / len, v[2] / len};
    }

    private static double smithLambda(double cosTheta, double alphaSq) {
        double c2 = Math.max(cosTheta * cosTheta, 1.0e-8);
        double tan2 = Math.max(1.0 - c2, 0.0) / c2;
        return 0.5 * (Math.sqrt(1.0 + alphaSq * tan2) - 1.0);
    }

    private static double smithG2OverG1(double cosV, double cosL, double alphaSq) {
        double lv = smithLambda(Math.max(cosV, 0.0), alphaSq);
        double ll = smithLambda(Math.max(cosL, 0.0), alphaSq);
        return (1.0 + lv) / (1.0 + lv + ll);
    }

    /** Mean, min and max of the white-metal (F = 1) VNDF estimator over an n x n stratified sample grid. */
    private static double[] vndfEstimator(double cosV, double roughness, int n) {
        double alpha = Math.max(roughness * roughness, 1.0e-3);
        double alphaSq = alpha * alpha;
        double sinV = Math.sqrt(Math.max(0.0, 1.0 - cosV * cosV));
        double[] v = {sinV, 0.0, cosV};   // N = +Z; basisAround(N) gives t = +X, b = +Y
        double[] vh = normalize(new double[] {alpha * v[0], alpha * v[1], Math.max(v[2], 1.0e-6)});
        double lensq = vh[0] * vh[0] + vh[1] * vh[1];
        double[] t1 = lensq > 1.0e-12 ? normalize(new double[] {-vh[1], vh[0], 0.0}) : new double[] {1.0, 0.0, 0.0};
        double[] t2 = cross(vh, t1);
        double sum = 0.0;
        double minW = Double.MAX_VALUE;
        double maxW = -Double.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                double r = Math.sqrt((i + 0.5) / n);
                double phi = 2.0 * Math.PI * ((j + 0.5) / n);
                double p1 = r * Math.cos(phi);
                double p2 = r * Math.sin(phi);
                double s = 0.5 * (1.0 + vh[2]);
                p2 = (1.0 - s) * Math.sqrt(Math.max(0.0, 1.0 - p1 * p1)) + s * p2;
                double pz = Math.sqrt(Math.max(0.0, 1.0 - p1 * p1 - p2 * p2));
                double[] nh = {
                    p1 * t1[0] + p2 * t2[0] + pz * vh[0],
                    p1 * t1[1] + p2 * t2[1] + pz * vh[1],
                    p1 * t1[2] + p2 * t2[2] + pz * vh[2]
                };
                double[] h = normalize(new double[] {alpha * nh[0], alpha * nh[1], Math.max(nh[2], 1.0e-6)});
                double vdotH = dot(v, h);
                double cosL = 2.0 * vdotH * h[2] - v[2];   // L = reflect(-V, H) = 2 (V.H) H - V
                double w = smithG2OverG1(cosV, cosL, alphaSq);
                sum += w;
                minW = Math.min(minW, w);
                maxW = Math.max(maxW, w);
            }
        }
        return new double[] {sum / (double) (n * n), minW, maxW};
    }

    /**
     * Directional albedo of the single-scattering GGX BRDF (F = 1) by quadrature in the half-vector domain:
     * integral of D(h) * G2(V,L) * (V.H) / (N.V) over the hemisphere of h, with L = reflect(-V, h). The change of
     * variables dω_L = 4 (V.H) dω_h removes the 1/(V.H) of the BRDF, and the tan-warped theta keeps the samples inside
     * the D lobe, so the reference stays accurate at grazing view angles where a uniform L-domain grid under-resolves it.
     */
    private static double bruteForceAlbedo(double cosV, double roughness, int nTheta, int nPhi) {
        double alpha = Math.max(roughness * roughness, 1.0e-3);
        double alphaSq = alpha * alpha;
        double sinV = Math.sqrt(Math.max(0.0, 1.0 - cosV * cosV));
        double dPhi = (2.0 * Math.PI) / nPhi;
        double lambdaV = smithLambda(cosV, alphaSq);
        double sum = 0.0;
        for (int i = 0; i < nTheta; i++) {
            double u = (i + 0.5) / nTheta;
            double tanT = Math.tan(Math.PI * 0.5 * u);
            double theta = Math.atan(alpha * tanT);
            double dTheta = alpha * (Math.PI * 0.5) * (1.0 + tanT * tanT) / (1.0 + alpha * alpha * tanT * tanT) / nTheta;
            double sinT = Math.sin(theta);
            double cosT = Math.cos(theta);
            for (int j = 0; j < nPhi; j++) {
                double phi = (j + 0.5) * dPhi;
                double hx = sinT * Math.cos(phi);
                double hy = sinT * Math.sin(phi);
                double vdotH = sinV * hx + cosV * cosT;
                if (vdotH <= 0.0) {
                    continue;
                }
                double cosL = 2.0 * vdotH * cosT - cosV;
                if (cosL <= 0.0) {
                    continue;
                }
                double g2 = 1.0 / (1.0 + lambdaV + smithLambda(cosL, alphaSq));
                double d2 = cosT * cosT * (alphaSq - 1.0) + 1.0;
                double d = alphaSq / (Math.PI * d2 * d2);
                sum += d * g2 * vdotH / cosV * sinT * dTheta * dPhi;
            }
        }
        return sum;
    }

    private static void runGgxVndfChecks() {
        for (double roughness : new double[] {0.2, 0.5, 0.8}) {
            for (double cosV : new double[] {1.0, 0.5, 0.1}) {
                double[] est = vndfEstimator(cosV, roughness, 192);
                double ref = bruteForceAlbedo(cosV, roughness, 256, 512);
                assertEquals(ref, est[0], 0.008, String.format(java.util.Locale.ROOT,
                    "white furnace roughness=%.2f cosV=%.2f: estimator %.4f, reference %.4f", roughness, cosV, est[0], ref));
                assertTrue(est[1] >= 0.0, "VNDF weight is never negative");
                assertTrue(est[2] <= 1.0 + 1.0e-9, "VNDF weight never exceeds 1 (G2/G1 <= 1), was " + est[2]);
            }
        }
    }

    private static void runTorchBoostChecks() {
        int[] mat = new int[4 * 4];
        java.util.Arrays.fill(mat, LabPbr.pack(255, 0, 0, LabPbr.SOURCE_AUTO));
        mat[0] = LabPbr.pack(255, 0, 255, LabPbr.SOURCE_AUTO); // texel (0,0) emission 1.0

        // Quad 0: 1x1 flat quad on y=0 (solid pass: ceiling lamp / glowstone)
        // Quad 1: 0.2x0.8 vertical quad (cutout pass: torch flame)
        float[] pos = {
            // quad 0 (solid face): [0,1] on x,z at y=0
            0, 0, 0,  1, 0, 0,  1, 0, 1,  0, 0, 1,
            // quad 1 (cutout torch): [0.4, 0.6] on x at y=[0.2, 1.0]
            0.4f, 0.2f, 0.5f,  0.6f, 0.2f, 0.5f,  0.6f, 1.0f, 0.5f,  0.4f, 1.0f, 0.5f
        };

        float[] uv = {
            // quad 0 UVs mapping to texel 0
            0.0f, 0.0f,  0.25f, 0.0f,  0.25f, 0.25f,  0.0f, 0.25f,
            // quad 1 UVs mapping to texel 0
            0.0f, 0.0f,  0.25f, 0.0f,  0.25f, 0.25f,  0.0f, 0.25f
        };

        // solidQuads = 1: quad 0 is solid, quad 1 is cutout
        float[] records = RtEmitters.extractQuads(pos, uv, 2, mat, 4, 4, 1);
        assertEquals(2 * RtEmitters.FLOATS, records.length, "Extracted 2 quads");

        // Quad 0 (solid): boost at offset 7
        float quad0Boost = records[7];
        assertEquals(1.0f, quad0Boost, EPSILON, "Solid block emitter must keep 1.0x baseline power");

        // Quad 1 (cutout torch): boost at offset FLOATS + 7
        float quad1Boost = records[RtEmitters.FLOATS + 7];
        // the boost is power based now (RtEmitters.MIN_POWER = area x mean emission x boost), not a flat 2.8x of every cutout quad:
        // this 0.2 x 0.8 quad (area 0.16, fully emissive) is lifted to the minimum power
        float quad1Area = records[RtEmitters.FLOATS + 3];
        float quad1Mean = records[RtEmitters.FLOATS + 14];
        assertEquals(0.16f, quad1Area, 1e-4f, "area of the torch quad");
        assertEquals(RtEmitters.MIN_POWER, quad1Area * quad1Mean * quad1Boost, 1e-4f,
            "small cutout emitter must be lifted to MIN_POWER, boost was " + quad1Boost);
    }

    private static void runVoxelEdgeBleedingChecks() {
        // Shadow bias bounds: prevents ray origin punching through walls or thin slabs
        float biasClose = RtLighting.shadowBias(0.0f);
        float biasMid = RtLighting.shadowBias(50.0f);
        float biasFar = RtLighting.shadowBias(1000.0f);

        assertTrue(biasClose >= 0.003f, "Shadow bias at t=0 must clear float precision (>= 3mm)");
        assertTrue(biasMid > biasClose, "Shadow bias must grow with distance");
        assertTrue(biasFar <= 0.015f, "Shadow bias must be clamped to <= 15mm to avoid wall edge bleeding at distance");

        // Smooth shadow terminator: prevents jagged normal terminator artifacts near geometric horizon
        float tNegative = smoothstep(0.0f, 0.08f, -0.01f);
        assertEquals(0.0f, tNegative, EPSILON, "Facing away from light must have 0 terminator");

        float tZero = smoothstep(0.0f, 0.08f, 0.0f);
        assertEquals(0.0f, tZero, EPSILON, "At geometric horizon, terminator must be 0");

        float tMid = smoothstep(0.0f, 0.08f, 0.04f);
        assertTrue(tMid > 0.4f && tMid < 0.6f, "Halfway into terminator range smoothly ramps (0.5)");

        float tFull = smoothstep(0.0f, 0.08f, 0.10f);
        assertEquals(1.0f, tFull, EPSILON, "Above terminator threshold, full lighting is preserved");
    }

    private static void runRtPresetChecks() {
        RtSettings.Preset savedPreset = RtSettings.preset;
        boolean savedGi = RtSettings.raysGi;
        int savedQuality = RtSettings.raysGiQuality;
        int savedDistance = RtSettings.raysGiDistance;
        boolean savedReflections = RtSettings.raysReflections;
        try {
            RtSettings.applyPreset(RtSettings.Preset.LOW);
            assertFalse(RtSettings.gi(), "LOW stays a shadow/contact/emissive preset without a GI bounce");
            RtSettings.applyPreset(RtSettings.Preset.MEDIUM);
            assertTrue(RtSettings.gi(), "MEDIUM traces one GI bounce instead of flat ambient-only interiors");
            assertEquals(1, RtSettings.giQuality(), "MEDIUM uses one GI ray");
            assertEquals(48, RtSettings.giDistance(), "MEDIUM keeps the cheap GI reach");
            assertTrue(RtSettings.reflections(), "MEDIUM keeps reflections");
            RtSettings.applyPreset(RtSettings.Preset.HIGH);
            assertTrue(RtSettings.gi(), "HIGH keeps GI");
            assertEquals(64, RtSettings.giDistance(), "HIGH reaches farther than MEDIUM");
            RtSettings.applyPreset(RtSettings.Preset.ULTRA);
            assertTrue(RtSettings.gi(), "ULTRA keeps GI");
            assertTrue(RtSettings.giQuality() >= 2, "ULTRA traces more than one GI ray");
        } finally {
            RtSettings.raysGi = savedGi;
            RtSettings.raysGiQuality = savedQuality;
            RtSettings.raysGiDistance = savedDistance;
            RtSettings.raysReflections = savedReflections;
            RtSettings.preset = savedPreset;
        }
    }

    private static void runPixelShadowChecks() {
        // The shader side of the contract (the snap formula, one module, no normal lift, entity/foliage gate, no hidden
        // sample state, and the numeric cell behaviour) is RtPixelShadowSamplingTest's job; a Java mirror here would only
        // test itself. Only the StatsBuf plumbing and the settings stay in this class.
        String common = RtWorldChecks.rtCommon();
        assertTrue(common.contains("float shadowPixel;"), "StatsBuf carries the pixel-shadow cell size");

        // Settings: registered, off by default, clamped, and reset by a preset.
        RtSettings.Preset savedPreset = RtSettings.preset;
        boolean savedOn = RtSettings.raysPixelShadows;
        int savedSize = RtSettings.raysPixelShadowSize;
        try {
            RtSettings.applyPreset(RtSettings.Preset.MEDIUM);
            assertFalse(RtSettings.pixelShadows(), "pixel shadows are off in the default preset");
            assertEquals(1, RtSettings.pixelShadowSize(), "default cell is 1 texture pixel");
            assertEquals(0.0f, RtFrameRenderer.pixelShadowStatsValue(), EPSILON, "off writes 0 into StatsBuf.shadowPixel");
            assertTrue(RtSettings.applyKeyValue("rays_pixel_shadows", "true"), "the option is a known key");
            assertTrue(RtSettings.pixelShadows(), "the option turns on");
            RtSettings.applyKeyValue("rays_pixel_shadow_size", "16");
            assertEquals(16, RtSettings.pixelShadowSize(), "the cell size is settable");
            assertEquals(16.0f, RtFrameRenderer.pixelShadowStatsValue(), EPSILON, "StatsBuf gets the size in texture pixels (shader divides by 16)");
            RtSettings.applyKeyValue("rays_pixel_shadow_size", "64");
            assertEquals(16, RtSettings.pixelShadowSize(), "out-of-range cells are rejected, not clamped silently");
            RtSettings.applyPreset(RtSettings.Preset.MEDIUM);
            assertFalse(RtSettings.pixelShadows(), "a preset reset turns pixel shadows off");
            assertEquals(1, RtSettings.pixelShadowSize(), "a preset reset restores the default cell");
        } finally {
            RtSettings.raysPixelShadows = savedOn;
            RtSettings.raysPixelShadowSize = savedSize;
            RtSettings.preset = savedPreset;
        }
    }

    /**
     * Stage C contract: the G-buffer carries the specular radiance and its F0 guide, NRD gets a real specular lane and
     * world-space motion, and DLSS-RR gets one combined raw colour (no direct-light double add).
     */
    private static void runDenoisedSpecularChecks() {
        assertEquals(7, RtPipeline.GBUFFER_VIEWS, "gbuffer = viewZ, normal, diffuse, albedo, direct, specular, spec albedo");
        assertEquals(13, RtPipeline.BINDING_GBUFFER_SPECULAR, "specular radiance is binding 13");
        assertEquals(14, RtPipeline.BINDING_GBUFFER_SPEC_ALBEDO, "F0 guide is binding 14");

        // Both denoisers must hand the pipeline exactly GBUFFER_VIEWS images. DLSS-RR kept returning the old five after the
        // G-buffer gained the specular radiance and F0 lanes, so the first gbuffer bind threw and RT stayed off all session.
        assertEquals(RtPipeline.GBUFFER_VIEWS, denoiserGbufferViews("DlssRrDenoiser.java"),
            "DLSS-RR must expose every G-buffer image the shader writes");
        assertEquals(RtPipeline.GBUFFER_VIEWS, denoiserGbufferViews("NrdDenoiser.java"),
            "NRD must expose every G-buffer image the shader writes");

        String common = RtWorldChecks.rtCommon();
        String rays = RtShaderCompiler.loadSource("/shaders/rt_rays.comp");
        String path = RtShaderCompiler.loadSource("/shaders/rt_path.comp");
        String pack = RtShaderCompiler.loadSource("/shaders/rt_pack_nrd.comp");

        assertTrue(common.contains("binding = 13, rgba16f) uniform writeonly image2D gbSpecular"), "binding 13 declared");
        assertTrue(common.contains("binding = 14, rgba8) uniform writeonly image2D gbSpecAlbedo"), "binding 14 declared");

        // Ray mode: the traced radiance is raw (denoised later), the analytic GGX sun highlight is noise-free and goes into gbDirect.
        assertTrue(rays.contains("imageStore(gbSpecular, p, vec4(refl * thr * atmT, reflDist));"),
            "rt_rays.comp stores the raw traced reflection radiance with its hit distance");
        assertTrue(rays.contains("imageStore(gbSpecAlbedo, p, vec4(clamp(wantsRefl ? f0 : vec3(0.0), 0.0, 1.0), 1.0));"),
            "the F0 guide is zero when there is no specular lobe");
        assertTrue(rays.contains("specSun = pc.keyColor.rgb * vis * specGGX * ndotl;"), "sun highlight is analytic");
        assertTrue(rays.contains("directOut = (s.albedoLit * direct * directScale + specSun + selfEmission) * thr + glassRefl;"),
            "the noise-free sun highlight is composed after the denoiser (gbDirect)");
        assertTrue(count(rays, "vec4(s.motion,") == 1 && rays.contains("dot(s.motion, s.motion) > 1.0e-8"),
            "entity/world motion is written for the NRD motion input");
        assertFalse(rays.contains("gbAlbedo, p, vec4((indirect"), "the diffuse channel is no longer pre-demodulated in the ray shader");

        // Path mode: raw radiance, zero specular radiance (no PT specular lane yet), F0 guide for RR.
        assertTrue(path.contains("imageStore(gbSpecular, p, vec4(0.0));"), "path tracing keeps an empty specular lane");
        assertTrue(path.contains("imageStore(gbSpecAlbedo, p, vec4(clamp(specAlb, 0.0, 1.0), 1.0));"),
            "path tracing writes the F0/albedo specular guide for DLSS-RR");

        // Pack: NRD motion, demodulation, separate specular; RR early-returns without touching IN_MV.
        assertTrue(pack.contains("Vec4Buf(pc.motionAddr).v[p.y * pc.size.x + p.x]"), "pack reads the world-space motion buffer");
        assertTrue(pack.contains("imageStore(nrdInMotion, p, vec4(motion, 0.0));"), "pack writes the NRD motion input");
        assertTrue(pack.contains("vec3 ambient = radiance / albedo;"), "diffuse radiance is demodulated for NRD");
        assertTrue(pack.contains("imageStore(nrdInSpecular, p, vec4(linearToYCoCg(specRadiance), normHitSpec));"),
            "REBLUR gets a real specular lane");
        assertTrue(pack.contains("imageStore(nrdInSpecular, p, vec4(specRadiance, min(specDist, FP16_MAX)));"),
            "RELAX gets a real specular lane");
        assertTrue(pack.contains("if (pc.denoiser != 2u) {") && pack.contains("imageStore(nrdInMotion, p, vec4(0.0));"),
            "DLSS-RR leaves IN_MV to the velocity pass");
        // The analytic direct channel carries the first-vertex emission (the visible face of a torch, glowstone, lava), the
        // first camera-facing interface mirror, the GGX sun highlight and the fog in-scatter, and is subtracted from
        // gbDiffuse in the ray shaders. Without it in the RR input those pixels were lit by their own light and stayed dark.
        assertTrue(pack.contains("vec3 direct = imageLoad(gbDirect, p).rgb;")
                && pack.contains("vec4(clamp(radiance + specRadiance + direct, 0.0, FP16_MAX), 1.0)"),
            "DLSS-RR receives one combined raw colour, direct channel included (emission is visible, not only cast)");
        assertTrue(pack.contains("if (invalid3(direct)) {"), "a non-finite direct term is dropped before the denoiser history");

        // Compose: RR output is final; NRD is remodulated and the specular lane added.
        assertTrue(pack.contains("color = max(den.xyz, 0.0);"), "RR compose adds no direct light twice");
        assertTrue(pack.contains("color = albedo * ambient + specular + direct;"), "NRD compose remodulates and adds both lanes");
        assertTrue(pack.contains("vec3 specular = pc.denoiser == 0u ? yCoCgToLinear(denSpec.xyz) : max(denSpec.xyz, 0.0);"),
            "RELAX specular is read in linear RGB, REBLUR in YCoCg");
    }

    /**
     * Stage E contract: the ray pass reuses one emitter sample per pixel through a two-parity reservoir, while the path
     * tracer keeps plain RIS. The history is validated before reuse and the world state invalidates it.
     */
    @Test
    public void testRestirTemporalReuse() {
        String common = RtWorldChecks.rtCommon();
        String rays = RtShaderCompiler.loadSource("/shaders/rt_rays.comp");
        String path = RtShaderCompiler.loadSource("/shaders/rt_path.comp");

        assertTrue(common.contains("uvec2 reservoirAddr;"), "StatsBuf publishes the reservoir buffer address");
        assertTrue(common.contains("struct Reservoir {"), "one packed reservoir record");
        assertTrue(common.contains("buffer ReservoirBuf { Reservoir r[]; }"), "the reservoir is a device-address buffer");
        int resStart = common.indexOf("struct Reservoir {");
        assertEquals(4, count(common.substring(resStart, common.indexOf("};", resStart)), "vec4 "),
            "Reservoir is 4 x vec4 = 64 B, matching RESERVOIR_BYTES in RtFrameRenderer");
        assertTrue(common.contains("float emitterPhat(vec3 y, float d2, float texEm, vec3 en, bool openSource, Surface s, float maxDist)"),
            "the RIS target is shared by candidates and reused history");
        assertTrue(common.contains("prev.leWsum.w * (mPrev / max(prev.meta.x, 1.0))"),
            "the stored weight is scaled to the clamped sample count that is added to M (no unbounded ramp)");
        assertTrue(common.contains("(phatHist / max(prev.enPhat.w, 1.0e-8))"),
            "the stored RIS weight is rescaled to the current receiver");
        assertTrue(common.contains("float mPrev = min(prev.meta.x, 20.0);"), "the history sample count is clamped");
        assertTrue(common.contains("normalDot > (isStatic ? 0.8 : 0.6)"), "a normal mismatch rejects the history");
        assertTrue(common.contains("abs(prev.meta.w - prevDist) < tol"), "a depth mismatch rejects the history");
        assertTrue(common.contains("prev.yTexEm.xyz - (isStatic ? vec3(0.0) : fb.camDelta.xyz)"),
            "a stored camera-relative sample follows the camera: camDelta = current - previous");
        assertTrue(common.contains("(uint(prev.meta.y) & 2u)") && common.contains("(recvN.z < 0.0 ? 2 : 0)"),
            "the sign of the receiver normal z survives the snorm packing");
        assertTrue(count(common, "storeEmptyReservoir(writeSlot);") == 3, "no candidate, a degenerate point and no emitters all clear it");
        assertTrue(common.contains("uint((pc.frame & 1u) ^ 1u) * pixels"), "the previous parity half is read");
        assertTrue(common.contains("ReservoirBuf(fb.reservoirAddr).r[writeSlot] = outR;"), "the current parity half is written");
        assertTrue(common.contains("traceTransmittance(shadowOrigin, rayDir / rayDist, rayDist)"),
            "the reused sample still gets exactly one visibility ray");

        assertTrue(rays.contains("sampleEmittersTemporal(s, vec3(1.0), reach, seed, p, size, n * NEE_CANDIDATES)"),
            "the ray pass merges the history once per pixel");
        assertEquals(0, count(path, "sampleEmittersTemporal"), "the path tracer keeps plain RIS");
        assertEquals(0, count(path, "sampleEmittersImpl"), "the path tracer keeps plain RIS");
    }

    /**
     * Biome lighting: the Overworld ambient mixed the biome sky colour in at 0.25 and then diluted it again by half, so
     * two biomes under the same sun produced nearly the same ambient. The check pins the new weights and proves that a
     * biome-tinted sky and fog still move the result by a visible amount.
     */
    private static void runBiomeAmbientChecks() {
        String common = RtWorldChecks.rtCommon();
        assertTrue(common.contains("vec3 skyTint = 0.45 * pc.skyColor.rgb + 0.55;"),
            "the biome sky colour must keep close to half of the ambient tint");
        assertTrue(common.contains("vec3 horizonDay = mix(vec3(1.00, 0.97, 0.92), sb.skyFogColor.rgb * 1.15, 0.30);"),
            "the horizon band must take the biome fog colour");
        assertTrue(common.contains("if (skyIsNether()) {") && common.contains("if (skyIsEnd()) {"),
            "the per-dimension ambient stays intact");

        float[][] skies = {{0.47f, 0.65f, 1.00f}, {0.55f, 0.62f, 0.48f}};   // plains vs a murky, green-shifted biome
        float oldDelta = relativeDelta(zenithAmbient(skies[0], 0.25f, 0.75f), zenithAmbient(skies[1], 0.25f, 0.75f));
        float newDelta = relativeDelta(zenithAmbient(skies[0], 0.45f, 0.55f), zenithAmbient(skies[1], 0.45f, 0.55f));
        assertTrue(newDelta > oldDelta * 1.5f, "the biome share must grow: old=" + oldDelta + " new=" + newDelta);
        assertTrue(newDelta > 0.04f, "two biomes must differ visibly, got " + newDelta);

        float plainsHorizon = relativeDelta(horizonAmbient(new float[] {0.75f, 0.85f, 1.00f}),
            horizonAmbient(new float[] {0.45f, 0.50f, 0.32f}));
        assertTrue(plainsHorizon > 0.08f, "the biome fog colour must move the horizon haze, got " + plainsHorizon);
    }

    /** rt_common.glsl skyLight(): daylight dome zenith, day factor omitted because it scales both biomes equally. */
    private static float[] zenithAmbient(float[] skyColor, float biomeWeight, float baseWeight) {
        float[] neutral = {0.90f, 0.94f, 1.00f};
        float[] day = new float[3];
        for (int i = 0; i < 3; i++) {
            float tint = biomeWeight * skyColor[i] + baseWeight;
            day[i] = 0.5f * neutral[i] + 0.5f * tint;
        }
        return day;
    }

    /** rt_common.glsl skyLight(): horizon haze after the biome fog mix. */
    private static float[] horizonAmbient(float[] fogColor) {
        float[] haze = {1.00f, 0.97f, 0.92f};
        float[] out = new float[3];
        for (int i = 0; i < 3; i++) {
            out[i] = 0.70f * haze[i] + 0.30f * (fogColor[i] * 1.15f);
        }
        return out;
    }

    private static float relativeDelta(float[] a, float[] b) {
        return Math.max(Math.abs(a[0] - b[0]), Math.max(Math.abs(a[1] - b[1]), Math.abs(a[2] - b[2])));
    }

    private static int count(String haystack, String needle) {
        int n = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + 1)) {
            n++;
        }
        return n;
    }

    /** Length of the array returned by {@code gbufferViews()}: it only holds .view fields, so they can be counted. */
    private static int denoiserGbufferViews(String file) {
        java.nio.file.Path relative = java.nio.file.Path.of("src", "main", "java",
            "io", "github", "andrexarlay", "vulkancompat", "rt", "denoise", file);
        // Gradle forwards the launcher's user.dir, which is the repository root, while the sources live in the common module.
        java.nio.file.Path path = java.nio.file.Files.isRegularFile(relative)
            ? relative : java.nio.file.Path.of("common").resolve(relative);
        String src;
        try {
            src = java.nio.file.Files.readString(path);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("cannot read " + file + " at " + path.toAbsolutePath(), e);
        }
        int start = src.indexOf("long[] gbufferViews()");
        int end = src.indexOf(';', start);
        assertTrue(start > 0 && end > start, file + " must expose gbufferViews()");
        return count(src.substring(start, end), ".view");
    }
}
