package io.github.andrexarlay.vulkancompat.rt.hand;

import net.minecraft.util.LightCoordsUtil;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Step 1 of RT hand lighting: the lightmap redirect is exact and narrow. L2 only; the hook is proven by screenshots. */
class HandRtLightTest {

    private static int blockTexel(int packed) { return HandRtLight.sampledTexel((packed >> 4) & 0xF); }
    private static int skyTexel(int packed) { return HandRtLight.sampledTexel((packed >> 20) & 0xF); }

    @Test
    void theSentinelSamplesTexelZeroZero() {
        assertEquals(0, blockTexel(HandRtLight.SENTINEL_LIGHT));
        assertEquals(0, skyTexel(HandRtLight.SENTINEL_LIGHT));
    }

    @Test
    void fullBrightAndFullSkyLandWhereTheFillExpects() {
        assertEquals(15, blockTexel(LightCoordsUtil.FULL_BRIGHT));
        assertEquals(15, skyTexel(LightCoordsUtil.FULL_BRIGHT));
        int fullSky = LightCoordsUtil.pack(0, 15);
        assertEquals(0, blockTexel(fullSky));
        assertEquals(15, skyTexel(fullSky));
    }

    @Test
    void fullBrightStaysWhiteSoEmissivePixelsKeepGlowing() {
        int tint = 0xFF336699;
        assertEquals(0xFFFFFFFF, HandRtLight.texelColor(15, 15, tint));
        assertEquals(tint, HandRtLight.texelColor(0, 0, tint));
        assertEquals(tint, HandRtLight.texelColor(15, 0, tint));
        assertEquals(tint, HandRtLight.texelColor(0, 15, tint));
    }

    @Test
    void debugColourParsesSixHexDigitsOnly() {
        assertEquals(0xFFFF00FF, HandRtLight.parseRgb("FF00FF"));
        assertEquals(0xFF102030, HandRtLight.parseRgb("#102030"));
        assertNull(HandRtLight.parseRgb("F0F"));
        assertNull(HandRtLight.parseRgb("GG0000"));
        assertNull(HandRtLight.parseRgb(""));
        assertNull(HandRtLight.parseRgb(null));
    }

    @Test
    void withoutASourceNothingIsArmed() {
        assertFalse(HandRtLight.armed());
        assertEquals(1234, HandRtLight.handLight(1234), "unarmed, the vanilla packed light passes through");
    }

    @Test
    void theOverrideIsLimitedToPrepareFrameAndTheReplayCannotLeakIt() throws IOException {
        String mixin = Files.readString(Path.of(
            "src/main/java/io/github/andrexarlay/vulkancompat/mixin/rt/MixinGameRendererHand.java")).replace("\r\n", "\n");
        assertTrue(mixin.contains("FeatureRenderDispatcher;prepareFrame(") && mixin.contains("shift = At.Shift.AFTER"),
            "lightmap() is swapped only around the hand pass's prepareFrame");
        assertTrue(mixin.contains("if (HandRtLight.overriding()) {\n            cir.setReturnValue(HandRtLight.view());"),
            "the swap keys on the narrow window, not on the whole pass");
        assertTrue(mixin.contains("} finally {") && mixin.contains("HandRtLight.endPass();\n        }"),
            "an exception in the deferred replay still ends the pass (and still propagates)");
        String json = Files.readString(Path.of("src/main/resources/mixins.bedray.json"));
        assertTrue(json.contains("\"rt.MixinFirstPersonHandsAndItemsRenderer\""), "the packed-light redirect is registered");
    }
}
