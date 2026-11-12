package io.github.andrexarlay.vulkancompat.rt.world;

import io.github.andrexarlay.vulkancompat.rtx.config.RtSettings;
import org.junit.jupiter.api.Test;

import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Guards the Vibrant Visuals grade end to end: the CPU mirror of postVibrant, the spare display-flag bit that carries the
 * toggle to the live native core (no ABI field), the removal of the old dead frame bit, and the config round-trip. The
 * string assertions are mutation-proved; the numeric ones pin the grade maths.
 */
class RtVibrantTest {
    private static String shader(String relative) throws Exception {
        return Files.readString(Path.of("src/main/resources/shaders/slang", relative)).replace("\r\n", "\n");
    }

    private static String java(String relative) throws Exception {
        return Files.readString(Path.of("src/main/java/io/github/andrexarlay/vulkancompat", relative)).replace("\r\n", "\n");
    }

    @Test void neutralParametersAreTheIdentity() {
        for (float[] c : new float[][] {{0f, 0f, 0f}, {0.5f, 0.5f, 0.5f}, {0.6f, 0.3f, 0.2f}, {1f, 1f, 1f}}) {
            float[] out = RtPostCinema.vibrant(c[0], c[1], c[2], 1f, 1f);
            assertEquals(c[0], out[0], 1e-6f);
            assertEquals(c[1], out[1], 1e-6f);
            assertEquals(c[2], out[2], 1e-6f);
        }
    }

    @Test void greyIsUnchangedByTheGrade() {
        // Saturation scales chroma about the luma (zero for grey); contrast pivots about 0.5, so mid grey is a fixed point.
        float[] out = RtPostCinema.vibrant(0.5f, 0.5f, 0.5f, RtPostCinema.VIBRANT_SATURATION, RtPostCinema.VIBRANT_CONTRAST);
        assertEquals(0.5f, out[0], 1e-6f);
        assertEquals(0.5f, out[1], 1e-6f);
        assertEquals(0.5f, out[2], 1e-6f);
    }

    @Test void gradeRaisesSaturationAndContrastOfAColourfulPixel() {
        float r = 0.6f, g = 0.3f, b = 0.2f;
        float[] out = RtPostCinema.vibrant(r, g, b, RtPostCinema.VIBRANT_SATURATION, RtPostCinema.VIBRANT_CONTRAST);
        float spreadIn = Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b));
        float spreadOut = Math.max(out[0], Math.max(out[1], out[2])) - Math.min(out[0], Math.min(out[1], out[2]));
        assertTrue(spreadOut > spreadIn, "the grade must widen the chroma spread (richer saturation)");
        // The bright channel lifts and the dark channels drop: contrast about mid grey.
        assertTrue(out[0] > r, "the peak channel must rise");
        assertTrue(out[2] < b, "the darkest channel must fall");
        for (float v : out) {
            assertTrue(v >= 0f && v <= 1f, "the grade must stay in 0..1");
        }
    }

    @Test void mirrorMatchesTheShaderConstants() throws Exception {
        assertEquals(1.30f, RtPostCinema.VIBRANT_SATURATION, 1e-6f);
        assertEquals(1.12f, RtPostCinema.VIBRANT_CONTRAST, 1e-6f);
        String display = shader("passes/path_display.slang");
        assertTrue(display.contains("postVibrant(display, 1.30, 1.12)"),
            "the display pass must call the grade with the same fixed strengths as the CPU mirror");
        String mod = shader("post_cinema.slang");
        assertTrue(mod.contains("float3 postVibrant(float3 color, float saturation, float contrast)"),
            "the post-cinema module must define the vibrant helper");
        assertTrue(mod.contains("float3(0.2126, 0.7152, 0.0722)"), "the luma weights must match the CPU mirror");
        assertTrue(mod.contains("saturate((sat - 0.5) * contrast + 0.5)"), "the contrast pivot must be mid grey");
    }

    @Test void javaAndSlangAgreeOnTheVibrantBitLayout() throws Exception {
        assertEquals(1 << 10, RtSlangPathPass.DISPLAY_FLAG_VIBRANT);
        String display = shader("passes/path_display.slang");
        assertTrue(display.contains("static const uint PATH_DISPLAY_FLAG_VIBRANT = 1u << 10u;"),
            "the shader vibrant bit must match Java bit 10");
        assertTrue(display.contains("(pc.flags & PATH_DISPLAY_FLAG_VIBRANT) != 0u"), "the grade must be gated on its flag");
        // The grade runs on the tone-mapped colour, before the corner falloff and the final write.
        int tm = display.indexOf("tonemapColorMode(exposed, tmMode)");
        int vib = display.indexOf("postVibrant(display,");
        int vig = display.indexOf("postVignette(");
        int write = display.indexOf("outImage[p] = float4(saturate(display)");
        assertTrue(tm >= 0 && vib > tm && vig > vib && write > vig,
            "vibrant must sit between the tone curve and vignette, before the output write");
    }

    @Test void vibrantBitPacksIntoTheSpareDisplayFlagOnly() {
        boolean saved = RtSettings.vibrantVisuals;
        try {
            RtSettings.vibrantVisuals = false;
            assertEquals(0, RtSlangPathPass.vibrantBit(), "off must add no bit");
            RtSettings.vibrantVisuals = true;
            assertEquals(1 << 10, RtSlangPathPass.vibrantBit(), "vibrant must own bit 10");
            // It must never disturb the low display flags, the tonemapper bits 5..7 or the post-cinema bits 8..9.
            assertEquals(0, RtSlangPathPass.vibrantBit() & 0x3FF, "vibrant bit leaked into bits 0..9");
        } finally {
            RtSettings.vibrantVisuals = saved;
        }
    }

    @Test void liveBackendSendsTheVibrantBit() throws Exception {
        String backend = java("rt/world/NativePathTraceBackend.java");
        assertTrue(backend.contains("RtSlangPathPass.vibrantBit()"),
            "the live native backend must forward the vibrant toggle into display_flags");
        boolean saved = RtSettings.vibrantVisuals;
        try {
            RtSettings.vibrantVisuals = true;
            int flags = RtSlangPathPass.DISPLAY_FLAG_TONEMAP | RtSlangPathPass.tonemapperBits(RtSettings.tonemapper())
                | RtSlangPathPass.postCinemaBits() | RtSlangPathPass.vibrantBit();
            assertEquals(1 << 10, flags & (1 << 10), "the live display push must carry the vibrant bit");
            assertEquals(RtSlangPathPass.DISPLAY_FLAG_TONEMAP, flags & RtSlangPathPass.DISPLAY_FLAG_TONEMAP,
                "the tonemap enable bit must survive the vibrant packing");
        } finally {
            RtSettings.vibrantVisuals = saved;
        }
    }

    @Test void theOldDeadFrameBitIsGone() throws Exception {
        // The grade now rides in display_flags; the frame-flag bit 14 was set but never read by any shader.
        assertFalse(java("rt/world/RtFrameParams.java").contains("FLAG_VIBRANT_VISUALS"),
            "the dead frame flag must be removed");
        assertFalse(java("rt/world/RtFrameRenderer.java").contains("FLAG_VIBRANT_VISUALS"),
            "the dead frame flag setter must be removed");
    }

    @Test void configParsesAndPersistsTheToggle() throws Exception {
        boolean saved = RtSettings.vibrantVisuals;
        try {
            assertTrue(RtSettings.applyKeyValue("vibrant_visuals", "true"));
            assertTrue(RtSettings.vibrantVisuals());
            assertTrue(RtSettings.applyKeyValue("vibrant_visuals", "false"));
            assertFalse(RtSettings.vibrantVisuals());
            RtSettings.vibrantVisuals = true;
            StringWriter w = new StringWriter();
            RtSettings.write(w);
            assertTrue(w.toString().contains("vibrant_visuals = true"), "the toggle must persist to bedray.toml");
        } finally {
            RtSettings.vibrantVisuals = saved;
        }
    }
}