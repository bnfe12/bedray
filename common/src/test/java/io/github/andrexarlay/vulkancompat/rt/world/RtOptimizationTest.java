package io.github.andrexarlay.vulkancompat.rt.world;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * L2 guards for the cost reductions of the RT frame. None of these changes the image: each removes work whose result was
 * discarded (sky on a hit, AO visibility with traced GI, alpha fetch on solid geometry, environment on a reflection hit,
 * repeated emission classification) or replaces a per-section ReSTIR reset with a light-list fingerprint.
 */
public class RtOptimizationTest {

    @Test
    public void testSkyModelOnlyRunsOnMiss() {
        String rays = RtShaderCompiler.loadSource("/shaders/rt_rays.comp");
        assertFalse(rays.contains("vec3 color = skyDisplay(rayDir);"),
            "the sky model must not run before the trace: a hit overwrites the colour");
        int sky = rays.indexOf("color = skyDisplay(rayDir);");
        int miss = rays.indexOf("imageStore(gbViewZ, p, vec4(1.0e6");
        assertTrue(sky > 0, "the miss branch must still produce the sky");
        assertTrue(miss > 0 && sky < miss, "the sky must be evaluated at the start of the miss branch");
    }

    @Test
    public void testAoVisibilityIsGatedByTracedGi() {
        String rays = RtShaderCompiler.loadSource("/shaders/rt_rays.comp");
        assertTrue(rays.contains("min(AO_STEPS_WITH_GI, aoSamples)"),
            "with GI only the ray that feeds the denoiser guide may run, and none when AO is off");
        assertTrue(rays.contains("const uint AO_STEPS_WITH_GI = 1u;"), "the guide ray exists in the G-buffer pass");
        assertTrue(rays.contains("const uint AO_STEPS_WITH_GI = 0u;"), "without a G-buffer there is nothing to guide");
        assertTrue(rays.contains("ao = open / float(aoSteps);"), "the remaining AO samples must be normalised by their count");
    }

    @Test
    public void testReflectionEnvironmentIsDeferredToMiss() {
        String rays = RtShaderCompiler.loadSource("/shaders/rt_rays.comp");
        assertFalse(rays.contains("vec3 env = mirror"),
            "the environment must not be evaluated before the reflection ray");
        int trace = rays.indexOf("bool hitRefl = traceSecondary(");
        int env = rays.indexOf("skyEnvRadiance(rdir, s.roughness)");
        assertTrue(env > trace, "the environment must sit in the miss branch of the hit test");
        assertTrue(rays.contains("hitRefl ? fCol * shadeReflectionHit"), "a reflection hit takes the hit colour");
    }

    @Test
    public void testLeafFilterWalkIsBounded() {
        String surface = RtShaderCompiler.loadSource("/shaders/rt_common_surface.glsl");
        assertNotNull(surface, "rt_common_surface.glsl missing");
        assertTrue(surface.contains("const uint LEAF_LAYER_LIMIT = 6u;"),
            "a leaf filter does not end a shadow ray, so one ray crossing a canopy needs a bound on the layers it shades");
        int fn = surface.indexOf("vec3 traceTransmittance(vec3 origin, vec3 d, float tmax)");
        assertTrue(fn > 0, "traceTransmittance moved");
        int end = surface.indexOf("\n}", fn);
        String body = surface.substring(fn, end < 0 ? surface.length() : end).replace("\r\n", "\n");
        assertEquals(3, body.split("leafLayers", -1).length - 1,
            "the counter is declared once, advanced in the foliage branch and read by the cap");
        String cap = "if (leafLayers >= LEAF_LAYER_LIMIT) {";
        assertTrue(body.contains(cap), "the cap is missing: a canopy ray pays a fetchSurface per layer with no limit");
        assertTrue(body.indexOf(cap) > body.indexOf("candidateAccepted("),
            "the blocker test has to run for every candidate, including those past the cap");
        String capped = body.substring(body.indexOf(cap) + cap.length(), body.indexOf("Surface s;"));
        // The cap must not return early: candidates behind it are still visited, so an opaque blocker ends the ray and the
        // product stays order independent. Returning the partial product leaked up to 4.7 % of the sun through geometry
        // that stands behind the canopy.
        assertFalse(capped.contains("return T;"), "the cap must keep walking the rest of the ray, not return the product");
        assertTrue(capped.contains("T *= 0.60;"), "a layer past the cap is folded in as a neutral leaf");
        assertTrue(capped.contains("continue;"), "the walk continues past the cap instead of fetching the surface");
        String foliage = body.substring(body.indexOf("if (s.isFoliage) {"));
        assertFalse(foliage.substring(0, foliage.indexOf("} else {")).contains("return vec3(0.0)"),
            "the leaf branch filters the light, it never blocks it");
    }

    @Test
    public void testEmissionEvaluatedOncePerLitPixel() {
        String rays = RtShaderCompiler.loadSource("/shaders/rt_rays.comp");
        assertTrue(rays.contains("vec3 selfEmission = emitted(s);"), "the emissive exitance is hoisted out of three uses");
        assertFalse(rays.contains("+ emitted(s)"), "no lit path may re-evaluate the emission");
    }

    @Test
    public void testSolidGeometryHasNoAlphaFetch() {
        String common = RtWorldChecks.rtCommon();
        int fn = common.indexOf("bool candidateAccepted(");
        int end = common.indexOf("\n}", fn);
        assertTrue(fn > 0 && end > fn, "candidateAccepted must be defined");
        String body = common.substring(fn, end);
        int solid = body.indexOf("if (pass == 0u) {");
        int dust = body.indexOf("dustCoverage(uv, gHitT)");
        int material = body.indexOf("textureLod(matAtlas");
        assertTrue(solid > 0, "solid candidates must be accepted before the UV / atlas alpha fetch");
        assertTrue(dust > solid, "cutout alpha comes from dustCoverage(), after the solid accept");
        assertTrue(material > solid, "no material fetch may happen on a solid candidate");
        assertTrue(body.indexOf("textureLod(atlas") == -1,
            "the cutout alpha is fetched inside dustCoverage(), not inline in candidateAccepted");
        // The solid layer has no alpha test: the camera ray treats it as opaque, so shadow rays must too (otherwise a solid
        // texel with atlas alpha < 1 casts no shadow).
        assertFalse(body.contains("pass == 0u && !skipTranslucent"), "the solid pass is opaque for shadow rays too");
        assertTrue(common.contains("if (pass == 2u && hasFlag(FLAG_MATERIALS))"),
            "the translucent / redstone handling stays in place");
    }

    @Test
    public void testSkyEnvRadianceShortCircuits() {
        String common = RtWorldChecks.rtCommon();
        // The shader sources use CRLF, so match single lines only.
        int dense = common.indexOf("if (w <= 0.0) {");
        int rough = common.indexOf("if (w >= 1.0) {");
        assertTrue(dense > 0, "a smooth lobe needs only the gradient dome");
        assertTrue(rough > 0, "a rough lobe needs only the real sky");
    }

    @Test
    public void testReservoirResetKeysOffLightFingerprint() throws IOException {
        String renderer = Files.readString(Path.of("src", "main", "java", "io", "github", "andrexarlay",
            "vulkancompat", "rt", "world", "RtFrameRenderer.java"));
        assertTrue(renderer.contains("emitters.fingerprint()"),
            "the reservoir must be reset by the light list, not by any section build");
        assertFalse(renderer.contains("world.structureVersion()"),
            "streaming sections and camera re-anchoring must not invalidate the ReSTIR history");
    }
}
