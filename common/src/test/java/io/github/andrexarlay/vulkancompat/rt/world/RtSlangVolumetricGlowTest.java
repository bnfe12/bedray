package io.github.andrexarlay.vulkancompat.rt.world;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Source pins of the block-light glow of the volumetric march. The glow must keep estimating what the surface lighting
 * estimates (same emitter list, same kernel, same texel rules), otherwise a lamp would shine in the air with a different
 * colour or reach than on the wall next to it. The kernel fingerprint in {@link RtSlangVolumetricSceneTest} cuts this block
 * out; this test pins the block itself.
 */
class RtSlangVolumetricGlowTest {
    private static final String ROOT = "src/main/resources/shaders/slang/";

    @Test void theGlowDrawsFromTheSameEmitterListAndKernelAsTheSurfaceLighting() {
        String glow = glowFunction();
        // one emitter per step from the light tree the path tracer reads, keyed in the list's own frame
        assertTrue(glow.contains("blockEmitterCount(table)"));
        assertTrue(glow.contains("blockEmitterSelect(table, p + cameraRelative,"));
        assertTrue(glow.contains("blockEmitterRecord(table, chosen.index)"));
        // the same distance kernel, window and gains as blockEmitterCandidate / blockEmitterRadiance
        assertTrue(glow.contains("emitterWindow(d2, EMITTER_RADIUS)"));
        assertTrue(glow.contains("emitterAttenuation(d2)"));
        assertTrue(glow.contains("EMISSIVE_GAIN * EMISSIVE_FLUX_GAIN"));
        assertTrue(glow.contains("emitterAttenuationColor("));
        // area over draw probability: the quad was sampled by area, the list by probability
        assertTrue(glow.contains("max(emitter.corner.w, 1.0e-6) / max(chosen.probability, 1.0e-6)"));
        // the cosine rules of the emitter side, including the floor of open sources
        assertTrue(glow.contains("twoSided ? abs(cosEmitter) : max(cosEmitter, 0.0)"));
        assertTrue(glow.contains("twoSided ? max(cosGeometry, 0.25) : cosGeometry"));
    }

    @Test void theEmissionOfTheDrawnTexelDecidesLikeInTheSurfaceLighting() {
        String glow = glowFunction();
        String surface = read("light/block_emitters.slang");
        // surface rule: material texel blue times the record strength; lit redstone texels use the fixed red
        assertTrue(surface.contains("point.texelEmission = point.redstone ? emitter.uvCorner.z : materialTexel.b * emitter.edge1.w;"));
        assertTrue(surface.contains("point.redstone = emitter.edge2.w > 1.5 && ((255u - uint(materialTexel.a * 255.0 + 0.5)) & 4u) != 0u;"));
        assertTrue(glow.contains("materialAtlas.SampleLevel(uv, 0.0)"));
        assertTrue(glow.contains("emitter.edge2.w > 1.5 && ((255u - uint(materialTexel.a * 255.0 + 0.5)) & 4u) != 0u"));
        assertTrue(glow.contains("hue = float3(1.0, 0.04, 0.02);"));
        assertTrue(glow.contains("emission = materialTexel.b * emission;"));
        assertTrue(glow.contains("if (emission <= 0.0) continue;"), "a texel that emits nothing must not light the air");
    }

    @Test void everySampleIsGatedByVisibilityAndEverythingStaysFinite() {
        String glow = glowFunction();
        assertTrue(glow.contains("traversal.traceVisibility(shadowRay).visible"), "no light through walls");
        assertTrue(glow.contains("if (d2 > EMITTER_RADIUS * EMITTER_RADIUS || d2 < 1.0e-4) continue;"));
        assertTrue(glow.contains("EMITTER_GLOW_MAX_STEPS"), "the step count has a ceiling");
        assertTrue(glow.contains("EMITTER_GLOW_MAX_ENERGY"), "one sample next to a tiny quad cannot blow the pixel up");
        assertTrue(glow.contains("return isFinite(glow) ? glow : float3(0.0);"));
        assertTrue(glow.contains("if (gain <= 0.0 || density <= 0.0 || tEnd <= 0.5) return float3(0.0);"));
        assertTrue(glow.contains("if (count == 0u) return float3(0.0);"), "an empty or missing list draws nothing");
    }

    @Test void theKernelCallsTheGlowOnlyWhenTheHostSwitchesItOnAndFeedsItTheRightWords() {
        String entry = read("light/volumetric_march_entry.slang");
        assertTrue(entry.contains("if (pc.emitterGlow > 0.0) {"));
        assertTrue(entry.contains("marchEmitterGlow(traversal, emitters, albedoAtlas, materialAtlas, pc.emitterCameraRelative,"));
        assertTrue(entry.contains("pc.emitterGlowDensity"));
        assertTrue(entry.contains("scatter += marchEmitterGlow("), "the glow is added to the shaft scattering, same history and apply");
    }

    private static String glowFunction() {
        String source = read("light/volumetric.slang");
        int start = source.indexOf("float3 marchEmitterGlow<");
        assertTrue(start >= 0, "marchEmitterGlow is declared");
        int begin = source.indexOf('{', source.indexOf(")", source.indexOf("float gain, float rayBias", start)));
        int depth = 0;
        for (int i = begin; i < source.length(); i++) {
            if (source.charAt(i) == '{') depth++;
            else if (source.charAt(i) == '}' && --depth == 0) return source.substring(start, i + 1);
        }
        throw new AssertionError("unclosed marchEmitterGlow");
    }

    private static String read(String relative) {
        try { return Files.readString(Path.of(ROOT + relative)).replace("\r\n", "\n"); }
        catch (IOException failure) { throw new AssertionError("cannot read " + relative, failure); }
    }
}
