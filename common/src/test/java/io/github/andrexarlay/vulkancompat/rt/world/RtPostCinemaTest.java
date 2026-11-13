package io.github.andrexarlay.vulkancompat.rt.world;

import io.github.andrexarlay.vulkancompat.rtx.config.RtSettings;
import org.junit.jupiter.api.Test;

import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class RtPostCinemaTest {
    private static String shader(String relative) throws Exception {
        return Files.readString(Path.of("src/main/resources/shaders/slang", relative)).replace("\r\n", "\n");
    }

    private static String java(String relative) throws Exception {
        return Files.readString(Path.of("src/main/java/io/github/andrexarlay/vulkancompat", relative)).replace("\r\n", "\n");
    }

    @Test void postCinemaBitsPackIntoTheSpareDisplayFlags() {
        boolean v = RtSettings.vignette;
        boolean g = RtSettings.filmGrain;
        try {
            RtSettings.vignette = false;
            RtSettings.filmGrain = false;
            assertEquals(0, RtSlangPathPass.postCinemaBits(), "both off must add no bits");
            RtSettings.vignette = true;
            assertEquals(1 << 8, RtSlangPathPass.postCinemaBits(), "vignette must own bit 8");
            RtSettings.vignette = false;
            RtSettings.filmGrain = true;
            assertEquals(1 << 9, RtSlangPathPass.postCinemaBits(), "grain must own bit 9");
            RtSettings.vignette = true;
            RtSettings.filmGrain = true;
            assertEquals((1 << 8) | (1 << 9), RtSlangPathPass.postCinemaBits());
            // The toggles must never disturb the five low display flags or the tonemapper bits 5..7.
            assertEquals(0, RtSlangPathPass.postCinemaBits() & 0xFF, "post-cinema bits leaked into bits 0..7");
        } finally {
            RtSettings.vignette = v;
            RtSettings.filmGrain = g;
        }
    }

    @Test void javaAndSlangAgreeOnThePostCinemaBitLayout() throws Exception {
        assertEquals(1 << 8, RtSlangPathPass.DISPLAY_FLAG_VIGNETTE);
        assertEquals(1 << 9, RtSlangPathPass.DISPLAY_FLAG_GRAIN);
        String display = shader("passes/path_display.slang");
        assertTrue(display.contains("static const uint PATH_DISPLAY_FLAG_VIGNETTE = 1u << 8u;"),
            "the shader vignette bit must match Java bit 8");
        assertTrue(display.contains("static const uint PATH_DISPLAY_FLAG_GRAIN = 1u << 9u;"),
            "the shader grain bit must match Java bit 9");
    }

    @Test void displayPassAppliesTheEffectsPerPixelAfterTonemap() throws Exception {
        String display = shader("passes/path_display.slang");
        assertTrue(display.contains("import post_cinema;"), "the display pass must import the post-cinema module");
        assertTrue(display.contains("(pc.flags & PATH_DISPLAY_FLAG_VIGNETTE) != 0u"), "vignette must be gated on its flag");
        assertTrue(display.contains("(pc.flags & PATH_DISPLAY_FLAG_GRAIN) != 0u"), "grain must be gated on its flag");
        assertTrue(display.contains("postFilmGrain(display, p, frame.frame.x"),
            "grain must animate from the frame index, not a frozen seed");
        // The effects must run on the tone-mapped display colour, before the final saturate write.
        int tm = display.indexOf("tonemapColorMode(exposed, tmMode)");
        int vig = display.indexOf("postVignette(");
        int write = display.indexOf("outImage[p] = float4(saturate(display)");
        assertTrue(tm >= 0 && vig > tm && write > vig, "vignette must sit between the tone curve and the output write");
    }

    @Test void postCinemaModuleIsPerPixelAndReadsNoNeighbour() throws Exception {
        String mod = shader("post_cinema.slang");
        assertTrue(mod.contains("float3 postVignette("), "the module must define the vignette helper");
        assertTrue(mod.contains("float3 postFilmGrain("), "the module must define the grain helper");
        assertTrue(mod.contains("pcgHash("), "grain must hash the pixel and frame for animated noise");
        // A cross-pixel read of a write target is the hazard this module must avoid: it stays strictly per-pixel.
        assertFalse(mod.contains("accum["), "post-cinema must never read the accum write target");
        assertFalse(mod.contains("outImage["), "post-cinema must never read the output write target");
        assertFalse(mod.contains("radiance["), "post-cinema must not sample the radiance image cross-pixel");
    }

    @Test void bothBackendsSendThePostCinemaBits() throws Exception {
        String nativeBackend = java("rt/world/NativePathTraceBackend.java");
        assertTrue(nativeBackend.contains("RtSlangPathPass.postCinemaBits()"),
            "the live native backend must forward the post-cinema toggles into display_flags");
        boolean v = RtSettings.vignette;
        boolean g = RtSettings.filmGrain;
        try {
            RtSettings.vignette = true;
            RtSettings.filmGrain = true;
            int flags = ReferencePathTraceBackend.displayFlags(0);
            assertEquals((1 << 8) | (1 << 9), flags & ((1 << 8) | (1 << 9)),
                "the reference display push must carry both post-cinema bits");
            assertEquals(RtSlangPathPass.DISPLAY_FLAG_TONEMAP, flags & RtSlangPathPass.DISPLAY_FLAG_TONEMAP,
                "the tonemap enable bit must survive the post-cinema packing");
        } finally {
            RtSettings.vignette = v;
            RtSettings.filmGrain = g;
        }
    }

    @Test void menuBindsAndGatesAndCatalogDescribesTheToggles() throws Exception {
        String menu = java("sodium/VulkanSodiumConfig.java");
        assertTrue(menu.contains("boolOpt(builder, \"vignette\", false, v -> RtSettings.vignette = v,"),
            "vignette must be a row bound to RtSettings.vignette");
        assertTrue(menu.contains("boolOpt(builder, \"film_grain\", false, v -> RtSettings.filmGrain = v,"),
            "film_grain must be a row bound to RtSettings.filmGrain");
        // The display pass runs in both RT modes, so the rows are live whenever RT is on (not path-only).
        assertTrue(menu.contains("() -> RtSettings.vignette, anyRt, dMode, Mark.NONE"),
            "vignette must be gated to anyRt");
        assertTrue(menu.contains("() -> RtSettings.filmGrain, anyRt, dMode, Mark.NONE"),
            "film_grain must be gated to anyRt");
        String catalog = java("sodium/SettingsCatalog.java");
        assertTrue(catalog.contains("putNoImage(\"vignette\", Tier.ADVANCED, \"Vignette\","));
        assertTrue(catalog.contains("putNoImage(\"film_grain\", Tier.ADVANCED, \"Film Grain\","));
    }

    @Test void configParsesAndPersistsTheToggles() throws Exception {
        boolean v = RtSettings.vignette;
        boolean g = RtSettings.filmGrain;
        try {
            assertTrue(RtSettings.applyKeyValue("vignette", "true"));
            assertTrue(RtSettings.vignette());
            assertTrue(RtSettings.applyKeyValue("film_grain", "true"));
            assertTrue(RtSettings.filmGrain());
            assertTrue(RtSettings.applyKeyValue("vignette", "false"));
            assertFalse(RtSettings.vignette());
            StringWriter w = new StringWriter();
            RtSettings.write(w);
            String toml = w.toString();
            assertTrue(toml.contains("vignette = false"), "vignette must persist to bedray.toml");
            assertTrue(toml.contains("film_grain = true"), "film_grain must persist to bedray.toml");
        } finally {
            RtSettings.vignette = v;
            RtSettings.filmGrain = g;
        }
    }
}