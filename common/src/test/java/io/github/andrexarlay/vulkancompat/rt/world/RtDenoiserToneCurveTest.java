package io.github.andrexarlay.vulkancompat.rt.world;

import io.github.andrexarlay.vulkancompat.rt.core.BedrayNrdPackNative;
import io.github.andrexarlay.vulkancompat.rtx.config.RtSettings;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The denoiser compose pass overwrites the path display image, so the player's tone curve AND post-cinema looks must ride
 * through the NRD pack flags and be re-applied by compose — otherwise the choices only work with the denoiser off. Flags:
 * bit 0 tone enable, bit 1 split, bits 2..4 curve ordinal, bit 5 vignette, bit 6 grain, bit 7 reserved. Guards the shader
 * decode, the Java packing/validation, both denoiser call sites and the native mask. String assertions are mutation-proved.
 */
class RtDenoiserToneCurveTest {
    private static String shader(String relative) throws Exception {
        return Files.readString(Path.of("src/main/resources/shaders/slang", relative)).replace("\r\n", "\n");
    }
    private static String java(String relative) throws Exception {
        return Files.readString(Path.of("src/main/java/io/github/andrexarlay/vulkancompat", relative)).replace("\r\n", "\n");
    }
    private static String cpp(String relative) throws Exception {
        return Files.readString(Path.of("../native/bedray_core", relative)).replace("\r\n", "\n");
    }
    private static BedrayNrdPackNative.Settings settings(int flags) {
        return new BedrayNrdPackNative.Settings(2, 1, 0, flags, 3, .1f, 20, 100, 1.1f, 4096);
    }

    @Test void composeAppliesTheChosenCurveDecodedFromFlagsBits2To4() throws Exception {
        String pack = shader("denoise/nrd_pack.slang");
        int compose = pack.indexOf("float3 nrdComposeColor(");
        assertTrue(compose >= 0, "nrdComposeColor must exist");
        String body = pack.substring(compose);
        assertTrue(body.contains("if ((pc.flags & 1u) != 0u) color = tonemapColorMode(color, (pc.flags >> 2u) & 7u);"),
            "compose must tone map with the player's curve from flags bits 2..4, not the fixed tonemapColor");
        assertFalse(body.contains("color = tonemapColor(color);"),
            "the fixed curve would discard the player's choice on denoised geometry");
        int exposure = body.indexOf("color *= max(pc.exposure, 0.0);");
        assertTrue(exposure >= 0 && exposure < body.indexOf("tonemapColorMode(color, (pc.flags >> 2u) & 7u)"),
            "exposure must still be applied exactly once, before the curve");
    }

    @Test void composeReAppliesThePostCinemaLooksOnEveryWrittenPixel() throws Exception {
        String pack = shader("denoise/nrd_pack.slang");
        assertTrue(pack.contains("static const uint NRD_FLAG_VIGNETTE = 1u << 5u;"));
        assertTrue(pack.contains("static const uint NRD_FLAG_GRAIN = 1u << 6u;"));
        int helper = pack.indexOf("float3 nrdPostCinema(");
        assertTrue(helper >= 0, "compose needs a shared post-cinema helper");
        String body = pack.substring(helper);
        assertTrue(body.contains("postVignette(color, uv, 0.40)"), "vignette strength must match path_display");
        assertTrue(body.contains("postFilmGrain(color, int2(pixel), 0u, 0.06)"), "grain strength must match path_display");
        String entry = shader("passes/nrd_compose.slang");
        // Both the RR (every pixel) and the NRD (denoised pixels) write sites must run the looks.
        assertEquals(2, entry.split("nrdPostCinema\\(", -1).length - 1,
            "both compose write sites must re-apply post-cinema");
        assertTrue(entry.contains("import \"denoise/nrd_pack.slang\";"));
    }

    @Test void javaPackFlagsEncodeEnableSplitOrdinalAndLooksWithoutLeaking() {
        assertEquals(1, BedrayNrdPackNative.packFlags(true, false, 0, false, false));
        assertEquals(2, BedrayNrdPackNative.packFlags(false, true, 0, false, false));
        assertEquals(1 | 2 | (2 << 2), BedrayNrdPackNative.packFlags(true, true, 2, false, false));
        assertEquals(1 | (4 << 2), BedrayNrdPackNative.packFlags(true, false, 4, false, false));
        assertEquals(1 | 0x20 | 0x40, BedrayNrdPackNative.packFlags(true, false, 0, true, true));
        // The ordinal is masked to three bits so a bad value can never touch the look bits or bit 7.
        assertEquals(1 | (7 << 2), BedrayNrdPackNative.packFlags(true, false, 7, false, false));
        assertEquals(1, BedrayNrdPackNative.packFlags(true, false, 8, false, false));
        for (RtSettings.Tonemapper m : RtSettings.Tonemapper.values()) {
            int flags = BedrayNrdPackNative.packFlags(true, false, m.ordinal(), false, false);
            assertEquals(m.ordinal(), (flags >> 2) & 7, "packed ordinal must equal the enum for " + m);
            assertEquals(1, flags & 1, "tone enable must survive for " + m);
            assertEquals(0, flags & ~0x7F, "no bit above 6 may be set for " + m);
        }
    }

    @Test void settingsAcceptTheLookBitsButStillRejectBit7() {
        assertDoesNotThrow(() -> settings(0x7F));
        assertDoesNotThrow(() -> settings(1 | (4 << 2) | 0x20 | 0x40));
        assertThrows(IllegalArgumentException.class, () -> settings(0x80));
        assertThrows(IllegalArgumentException.class, () -> settings(-1));
    }

    @Test void bothDenoisersForwardThePlayerCurveAndLooks() throws Exception {
        String nrd = java("rt/denoise/NrdDenoiser.java");
        assertTrue(nrd.contains("BedrayNrdPackNative.packFlags(tonemap"),
            "NRD must build flags through the shared packer");
        assertTrue(nrd.contains("RtSettings.tonemapper().ordinal()"), "NRD must forward the player's chosen curve");
        assertTrue(nrd.contains("RtSettings.vignette(), RtSettings.filmGrain()"), "NRD must forward the post-cinema looks");
        String rr = java("rt/denoise/DlssRrDenoiser.java");
        assertTrue(rr.contains("BedrayNrdPackNative.packFlags(tonemap"),
            "DLSS-RR compose must build flags through the shared packer");
        assertTrue(rr.contains("RtSettings.tonemapper().ordinal()"), "DLSS-RR must forward the player's chosen curve");
        assertTrue(rr.contains("RtSettings.vignette(), RtSettings.filmGrain()"), "DLSS-RR must forward the post-cinema looks");
    }

    @Test void nativeValidationAllowsTheCurveAndLookBits() throws Exception {
        String pass = cpp("src/nrd_pack_pass.cpp");
        assertTrue(pass.contains("(frame.flags & ~0x7Fu)"),
            "the native mask must allow bits 0..6 (enable, split, ordinal, vignette, grain)");
        assertFalse(pass.contains("(frame.flags & ~0x1Fu)"), "the tone-only mask would reject the post-cinema looks");
        assertFalse(pass.contains("(frame.flags & ~3u)"), "the old mask would reject every non-peak curve");
    }

    @Test void javaSlangAndNativeAgreeOnTheFlagsLayout() throws Exception {
        assertEquals(2, BedrayNrdPackNative.TONE_MODE_SHIFT);
        assertEquals(0x20, BedrayNrdPackNative.VIGNETTE_BIT);
        assertEquals(0x40, BedrayNrdPackNative.GRAIN_BIT);
        assertEquals(0x7F, BedrayNrdPackNative.FLAGS_MASK);
        assertTrue(shader("denoise/nrd_pack.slang").contains("(pc.flags >> 2u) & 7u"));
        assertTrue(cpp("src/nrd_pack_pass.cpp").contains("(frame.flags & ~0x7Fu)"));
        String test = cpp("tests/nrd_pack_pass_test.cpp");
        assertTrue(test.contains("f.flags = 0x80;"), "bit 7 must remain the rejected sentinel");
        assertTrue(test.contains("1u | (4u << 2) | 0x20u | 0x40u"), "a valid AGX + vignette + grain word must be exercised");
    }
}