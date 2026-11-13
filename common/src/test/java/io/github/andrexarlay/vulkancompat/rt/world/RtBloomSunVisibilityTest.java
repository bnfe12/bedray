package io.github.andrexarlay.vulkancompat.rt.world;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.joml.Matrix4f;
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
    @Test void thePackedCurveCannotChangeTheSourceMode() {
        var previous = RtSettings.tonemapper;
        try {
            for (var mode : RtSettings.Tonemapper.values()) {
                RtSettings.tonemapper = mode;
                for (int source = 0; source <= 2; source++) {
                    var params = new RtBloomPass.Params(new Matrix4f(), new float[]{0, 1, 0}, 0,
                        0, 0, 0, false, source != 0, 2, 0.7f, 0.35f, 0.75f, 0, 0, 0, source == 2 ? 71 : 0, 1);
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
    void theApplyStageNeverReadsTheImageItWrites() throws IOException {
        String bloom = read("bloom/bloom.slang");
        assertTrue(bloom.contains("float bloomSunVisibility(Sampler2D depth, Sampler2D frame, int2 size, BloomPush pc) {"),
            "visibility is measured on a sampled, read-only frame");
        assertFalse(bloom.contains("finalImg[t]"), "no read of the storage image the apply stage writes");
        String apply = read("passes/bloom_apply.slang");
        assertTrue(apply.contains("float vis = srcTex.Load(int3(0, 0, 0)).a;"), "the apply stage takes the prefilter's value");
        assertFalse(apply.contains("bloomSunVisibility("), "and never measures it itself");
    }

    @Test
    void thePrefilterMeasuresItAndTheUpsampleKeepsIt() throws IOException {
        String prefilter = read("passes/bloom_prefilter_down.slang");
        assertTrue(prefilter.contains("bloomSunVisibility(depthTex, srcTex, int2(srcSize), pc)"),
            "measured on the full-resolution frame the prefilter samples");
        assertTrue(prefilter.contains("pc, uv, texel, pc)), sunVisibility);") || prefilter.contains("pc)), sunVisibility);"),
            "stored in the alpha of mip 0");
        String up = read("passes/bloom_up.slang");
        assertTrue(up.contains("dstImg[p] = float4(bloomSanitize(lerp(own, wide, pc.p1.y)), ownTexel.a);"),
            "the in-place upsample into mip 0 does not overwrite it");
    }
}
