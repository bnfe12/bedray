package io.github.andrexarlay.vulkancompat.rt.world;

import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** Lens ghosts of the sun: strength in bits 16..23 of the bloom push word, drawn in the apply stage of the sun glare. */
class RtSlangLensFlareTest {
    private static final String ROOT = "src/main/resources/shaders/slang/";

    @Test void theStrengthTravelsInFreeBitsAndLeavesEveryOtherLaneAlone() {
        double before = RtAtmosphereSettings.get("lens_flare");
        try {
            RtAtmosphereSettings.set("lens_flare", 0.0);
            int off = flags();
            assertEquals(0, off >>> 16, "the default is the picture of every earlier build");
            RtAtmosphereSettings.set("lens_flare", 0.75);
            int on = flags();
            assertEquals(75, on >>> 16 & 0xFF);
            assertEquals(off & 0xFFFF, on & 0xFFFF, "source, tone, sky, shape and halo size are untouched");
            RtAtmosphereSettings.set("lens_flare", 2.0);
            assertEquals(200, flags() >>> 16 & 0xFF);
            // the whole word has to survive the trip through a float, which holds integers up to 2^24 exactly
            int word = flags();
            assertTrue(word < (1 << 24));
            assertEquals(word, (int) (float) word);
            RtAtmosphereSettings.set("lens_flare", 50.0);
            assertEquals(200, RtAtmosphereSettings.lensFlarePercent(), "clamped to 200 percent");
        } finally {
            RtAtmosphereSettings.set("lens_flare", before);
        }
    }

    @Test void theShaderReadsTheSameBitsTheHostWritesAndIgnoresThemInTheOtherLanes() {
        String bloom = read("bloom/bloom.slang");
        assertTrue(bloom.contains("(uint(max(pc.p1.x, 0.0)) >> 16u) & 255u"));
        // every other decode of the packed word masks its own bits, so bits 16+ cannot leak into them
        assertTrue(bloom.contains("uint(max(pc.p1.x, 0.0)) & 3u"));
        assertTrue(bloom.contains("(uint(max(pc.p1.x, 0.0)) >> 2u) & 7u"));
        assertTrue(bloom.contains("(uint(pc.p1.x) >> 6u) & 3u"));
        assertTrue(bloom.contains("(uint(pc.p1.x) >> 8u) & 255u"));
    }

    @Test void theGhostsFollowTheSunVisibilityAndTheGlareSwitch() {
        String apply = read("passes/bloom_apply.slang");
        assertTrue(apply.contains("bloomLensFlare(pixelNdc, pc.p1.zw, float(size.x) / float(size.y), sunVisibility * pc.sunDir.w, flare)"),
            "visibility (edge-faded, occlusion-aware) times the glare strength, the same gates as the halo");
        // the pixel position uses the sun's own convention: no Y flip, unlike the world-direction decode
        assertTrue(apply.contains("float2 pixelNdc = (float2(p) + 0.5) / float2(size) * 2.0 - 1.0;"));
        assertTrue(read("bloom/bloom.slang").contains("float2 center = clamp((ndc * 0.5 + 0.5) * float2(size)"));
        String bloom = read("bloom/bloom.slang");
        assertTrue(bloom.contains("if (strength <= 0.0 || visibility <= 0.0) return float3(0.0);"));
        // ghosts sit on the sun-centre axis, so a sun in the middle draws them on itself unless they are faded in
        assertTrue(bloom.contains("float2 centre = sunNdc * BLOOM_FLARE_AXIS[i];"));
        assertTrue(bloom.contains("smoothstep(0.0, 0.8, length(sunNdc))"));
    }

    private static int flags() {
        var params = new RtBloomPass.Params(new Matrix4f(), new float[]{0, 1, 0}, 0,
            0, 0, 0, false, true, 2, 0.7f, 0.35f, 0.75f, 0.3f, -0.2f, 71, 1);
        var bytes = ByteBuffer.allocateDirect(128).order(ByteOrder.nativeOrder());
        RtBloomPass.fillPushConstants(bytes, params, true);
        return (int) bytes.getFloat(112);
    }

    private static String read(String relative) {
        try { return Files.readString(Path.of(ROOT + relative)).replace("\r\n", "\n"); }
        catch (IOException failure) { throw new AssertionError("cannot read " + relative, failure); }
    }
}
