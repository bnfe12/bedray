package io.github.andrexarlay.vulkancompat.rt.world;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import io.github.andrexarlay.vulkancompat.rtx.config.RtSettings;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The sun glare reads the sun's visibility from the frame before any glow is added. bloom_apply used to sample the final
 * image around the sun disc in the same dispatch that adds the halo to those very pixels: whether a neighbouring
 * workgroup had already brightened them depended on scheduling, so the glare flickered from frame to frame. The
 * prefilter now measures it on the untouched frame and parks it in the alpha of mip 0; the upsample keeps that alpha and
 * the apply stage reads it.
 */
class RtBloomSunVisibilityTest {
    @Test
    void localToneControlsRetainIndependentStrengths() throws IOException {
        float highlights = RtAtmosphereSettings.localHighlights();
        float shadows = RtAtmosphereSettings.localShadows();
        try {
            RtLocalTonemap.setStrength(0f, 1.5f);
            assertTrue(RtLocalTonemap.enabled());
            var config = new java.io.StringWriter();
            io.github.andrexarlay.vulkancompat.rtx.config.VulkanRtxConfig.writeAll(config);
            assertTrue(config.toString().lines().anyMatch("atm_local_highlights = 0.0"::equals));
            assertTrue(config.toString().lines().anyMatch("atm_local_shadows = 1.5"::equals));
            RtLocalTonemap.setStrength(0f, 0f);
            assertFalse(RtLocalTonemap.enabled());
        } finally {
            RtLocalTonemap.setStrength(highlights, shadows);
        }
    }

    @Test
    void skyBloomIsPersistedAndUsesAnIndependentShaderFlag() throws IOException {
        double previous = RtAtmosphereSettings.get("bloom_sky");
        try {
            assertTrue(RtAtmosphereSettings.set("bloom_sky", 1));
            var config = new java.io.StringWriter();
            io.github.andrexarlay.vulkancompat.rtx.config.VulkanRtxConfig.writeAll(config);
            assertTrue(config.toString().lines().anyMatch("atm_bloom_sky = 1.0"::equals));
            RtAtmosphereSettings.set("bloom_sky", 0);
            io.github.andrexarlay.vulkancompat.rtx.config.VulkanRtxConfig.applyKeyValue("atm_bloom_sky", "1.0");
            assertTrue(RtAtmosphereSettings.bloomSky());
            String shader = Files.readString(Path.of("src/main/resources/shaders/slang/bloom/bloom.slang"));
            assertTrue(shader.contains("!bloomIncludesSky(pc) && bloomIsSky(depth, dp)"));
            assertTrue(shader.contains("& (1u << 5u)"));
        } finally {
            RtAtmosphereSettings.set("bloom_sky", previous);
        }
    }

    @Test
    void bloomShapeAndHaloSizeSurviveConfigAndHaveSeparateShaderFields() throws IOException {
        double shape = RtAtmosphereSettings.get("bloom_shape");
        double size = RtAtmosphereSettings.get("sunglare_size");
        try {
            io.github.andrexarlay.vulkancompat.rtx.config.VulkanRtxConfig.applyKeyValue("atm_bloom_shape", "2.0");
            io.github.andrexarlay.vulkancompat.rtx.config.VulkanRtxConfig.applyKeyValue("atm_sunglare_size", "0.65");
            var config = new java.io.StringWriter();
            io.github.andrexarlay.vulkancompat.rtx.config.VulkanRtxConfig.writeAll(config);
            assertTrue(config.toString().lines().anyMatch("atm_bloom_shape = 2.0"::equals));
            assertTrue(config.toString().lines().anyMatch("atm_sunglare_size = 0.65"::equals));
            var params = new RtBloomPass.Params(new Matrix4f(), new float[]{0, 1, 0}, 1,
                0, 0, 0, false, true, 2, 0.7f, 0.35f, 0.75f, 0, 0, 71, 1);
            var bytes = ByteBuffer.allocateDirect(128).order(ByteOrder.nativeOrder());
            RtBloomPass.fillPushConstants(bytes, params, true);
            int flags = (int) bytes.getFloat(112);
            assertEquals(2, flags & 3);
            assertEquals(2, flags >>> 6 & 3);
            assertEquals(-35, (byte) (flags >>> 8));
            assertTrue(read("passes/bloom_up.slang").contains("bloomShapeScale(pc)"));
            assertTrue(read("passes/bloom_apply.slang").contains("bloomGlareSize(pc)"));
        } finally {
            RtAtmosphereSettings.set("bloom_shape", shape);
            RtAtmosphereSettings.set("sunglare_size", size);
        }
    }

    @Test void thePackedCurveCannotChangeTheSourceMode() {
        var previous = RtSettings.tonemapper;
        try {
            for (var mode : RtSettings.Tonemapper.values()) {
                RtSettings.tonemapper = mode;
                for (int source = 0; source <= 2; source++) {
                    var params = new RtBloomPass.Params(new Matrix4f(), new float[]{0, 1, 0}, 0,
                        0, 0, 0, false, source != 0, 2, 0.7f, 0.35f, 0.75f, 0, 0, source == 2 ? 71 : 0, 1);
                    var bytes = ByteBuffer.allocateDirect(128).order(ByteOrder.nativeOrder());
                    if (source == 0 && mode == RtSettings.Tonemapper.PEAK) {
                        assertThrows(IllegalArgumentException.class,
                            () -> RtBloomPass.fillPushConstants(ByteBuffer.allocate(128), params, true));
                    }
                    RtBloomPass.fillPushConstants(bytes, params, true);
                    int flags = (int) bytes.getFloat(112);
                    assertEquals(source, flags & 3, "shader source-mode lane");
                    assertEquals(mode.ordinal(), flags >>> 2 & 7, "shader tone-mode lane");
                }
            }
        } finally {
            RtSettings.tonemapper = previous;
        }
    }

    private static final Path SHADERS = Path.of("src/main/resources/shaders/slang");

    private static String read(String relative) throws IOException {
        return Files.readString(SHADERS.resolve(relative)).replace("\r\n", "\n");
    }

    @Test
    void glareKeepsTheFadeMarginAndRejectsInvalidProjections() {
        assertTrue(RtAtmosphere.glareOnScreen(new Vector4f(1.24f, 0f, 0f, 1f)));
        assertTrue(RtAtmosphere.glareOnScreen(new Vector4f(0f, -2.48f, 0f, 2f)));
        assertFalse(RtAtmosphere.glareOnScreen(new Vector4f(1.25f, 0f, 0f, 1f)));
        assertFalse(RtAtmosphere.glareOnScreen(new Vector4f(0f, -2.5f, 0f, 2f)));
        assertFalse(RtAtmosphere.glareOnScreen(new Vector4f(0f, 0f, 0f, 0.001f)));
        assertFalse(RtAtmosphere.glareOnScreen(new Vector4f(0f, 0f, 0f, -1f)));
        assertFalse(RtAtmosphere.glareOnScreen(new Vector4f(Float.NaN, 0f, 0f, 1f)));
    }

    @Test
    void theApplyStageNeverReadsTheImageItWrites() throws IOException {
        String bloom = read("bloom/bloom.slang");
        assertTrue(bloom.contains("float bloomSunVisibility(Sampler2D depth, Sampler2D frame, int2 size, BloomPush pc) {"),
            "visibility is measured on a sampled, read-only frame");
        assertFalse(bloom.contains("finalImg[t]"), "no read of the storage image the apply stage writes");
        String apply = read("passes/bloom_apply.slang");
        assertTrue(apply.contains("pc.sunDir.w > 0.0 ? srcTex.Load(int3(0, 0, 0)).a : 0.0"),
            "the apply stage reads the first texel's alpha");
        assertFalse(apply.contains("bloomSunVisibility("), "and never measures it itself");
    }

    @Test
    void thePrefilterMeasuresItAndTheUpsampleKeepsIt() throws IOException {
        String prefilter = read("passes/bloom_prefilter_down.slang");
        assertTrue(prefilter.contains("bloomSunVisibility(depthTex, srcTex, int2(srcSize), pc)"),
            "measured on the full-resolution frame the prefilter samples");
        assertTrue(prefilter.contains("if (all(p == int2(0)) && pc.sunDir.w > 0.0)"),
            "visibility is measured just for the texel the apply stage reads");
        assertTrue(prefilter.contains("dstImg[p] = float4(glow, sunVisibility);"),
            "stored in the alpha of mip 0");
        String up = read("passes/bloom_up.slang");
        assertTrue(up.contains("dstImg[p] = float4(bloomSanitize(lerp(own, wide, pc.p1.y)), ownTexel.a);"),
            "the in-place upsample into mip 0 does not overwrite it");
    }
}
