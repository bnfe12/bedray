package io.github.andrexarlay.vulkancompat.rt.denoise;

import io.github.andrexarlay.vulkancompat.rtx.config.RtSettings;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The denoiser setting must resolve to the same table whether or not a trace mode is active: the Slang pass writes the
 * seven DLSS-RR guides and NRD's motion, so "the path has no G-buffer" is no longer a reason to refuse. The table is
 * pure, so each library/session combination is checked without NGX or NRD.
 */
class DenoiserResolutionTest {

    private static final String RENDERER = "src/main/java/io/github/andrexarlay/vulkancompat/rt/world/RtFrameRenderer.java";

    private static DenoiserResolution.Result resolve(RtSettings.PathDenoiser want, boolean nrdOk) {
        return DenoiserResolution.resolve(want, false, false, false, "null", false, nrdOk,
            nrdOk ? "" : "NRD.dll not found", DenoiserMode.NRD_REBLUR);
    }

    @Test
    void nrdIsSelectedWhenItsLibrariesAreUsable() {
        assertEquals(DenoiserMode.NRD_REBLUR, resolve(RtSettings.PathDenoiser.NRD, true).mode());
        assertEquals("setting", resolve(RtSettings.PathDenoiser.NRD, true).reason());
    }

    @Test
    void theStartupFlagChoosesTheNrdVariant() {
        DenoiserResolution.Result relax = DenoiserResolution.resolve(RtSettings.PathDenoiser.NRD, false, false,
            false, "null", false, true, "", DenoiserMode.NRD_RELAX);
        assertEquals(DenoiserMode.NRD_RELAX, relax.mode());
        DenoiserResolution.Result reblur = DenoiserResolution.resolve(RtSettings.PathDenoiser.NRD, false, false,
            false, "null", false, true, "", DenoiserMode.OFF);
        assertEquals(DenoiserMode.NRD_REBLUR, reblur.mode(), "a non-NRD flag keeps the default variant");
    }

    @Test
    void aSessionFailureStopsRetrying() {
        DenoiserResolution.Result failed = DenoiserResolution.resolve(RtSettings.PathDenoiser.NRD, false, true,
            false, "null", false, true, "", DenoiserMode.NRD_REBLUR);
        assertEquals(DenoiserMode.OFF, failed.mode());
        assertEquals("nrd_failed_earlier_this_session", failed.reason());
    }

    @Test
    void aMissingNrdLibrarySaysWhy() {
        DenoiserResolution.Result missing = resolve(RtSettings.PathDenoiser.NRD, false);
        assertEquals(DenoiserMode.OFF, missing.mode());
        assertEquals("nrd_library_unusable NRD.dll not found", missing.reason());
    }

    @Test
    void dlssRrIsSelectedWhenItsLibraryIsUsable() {
        DenoiserResolution.Result ok = DenoiserResolution.resolve(RtSettings.PathDenoiser.DLSS_RR, false, false,
            true, "", false, false, "null", DenoiserMode.NRD_REBLUR);
        assertEquals(DenoiserMode.DLSS_RR, ok.mode());
    }

    @Test
    void frameGenerationWithoutTheStreamlinePluginExplainsTheLimitation() {
        DenoiserResolution.Result blocked = DenoiserResolution.resolve(RtSettings.PathDenoiser.DLSS_RR, false, false,
            true, "", true, false, "null", DenoiserMode.NRD_REBLUR);
        assertEquals(DenoiserMode.OFF, blocked.mode());
        assertEquals("frame_generation_needs_sl.dlss_d.dll+nvngx_dlssd.dll_in_the_streamline_folder", blocked.reason());
    }

    @Test
    void anUnusableDlssRrFallsBackToNrd() {
        DenoiserResolution.Result fallback = DenoiserResolution.resolve(RtSettings.PathDenoiser.DLSS_RR, false, false,
            false, "vkb_dlss.dll missing", false, true, "", DenoiserMode.NRD_REBLUR);
        assertEquals(DenoiserMode.NRD_REBLUR, fallback.mode());
        assertEquals("dlss_library_unusable vkb_dlss.dll missing -> fallback_to_nrd", fallback.reason());
    }

    @Test
    void offStaysOff() {
        assertEquals(DenoiserMode.OFF, resolve(RtSettings.PathDenoiser.OFF, true).mode());
    }

    /** The refusal that predates the guides must be gone, and the pass must still ask for them. */
    @Test
    void anActiveTraceModeNoLongerRefusesTheDenoiser() throws IOException {
        String renderer = Files.readString(Path.of(RENDERER), StandardCharsets.UTF_8);
        assertFalse(renderer.contains("slang_denoiser_not_wired"), "the blanket refusal is gone");
        assertFalse(renderer.contains("slang_path_has_no_gbuffer"), "the old refusal is gone too");
        String resolve = block(renderer, "private static DenoiserMode resolveDenoiseMode()");
        assertFalse(resolve.contains("RtConfig.mode()"), "the setting is resolved independently of the trace mode");
        assertTrue(resolve.contains("DenoiserResolution.resolve("), "the resolution table decides");
        assertTrue(block(renderer, "private static boolean wantGbuffer(RtMode mode)")
            .contains("return denoiseMode != DenoiserMode.OFF;"), "an enabled denoiser still builds the guide variant");
        assertTrue(renderer.contains("!runtime.library().hasNativeMotion())"),
            "native NRD still refuses a core without the motion export");
    }

    private static String block(String source, String header) {
        int start = source.indexOf(header);
        assertTrue(start >= 0, "missing: " + header);
        int open = source.indexOf('{', start);
        int depth = 0;
        for (int i = open; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return source.substring(start, i + 1);
        }
        throw new AssertionError("unbalanced block: " + header);
    }
}
