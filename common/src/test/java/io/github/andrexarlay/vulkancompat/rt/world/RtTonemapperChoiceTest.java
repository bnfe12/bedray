package io.github.andrexarlay.vulkancompat.rt.world;

import io.github.andrexarlay.vulkancompat.rtx.config.RtSettings;
import org.junit.jupiter.api.Test;

import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Guards the tonemapper-choice contract end to end: the shader mode index, the spare display-flag bits that carry it to
 * the live native core (no ABI field), the CPU mirror and the config round-trip. The string assertions are mutation-proved.
 */
class RtTonemapperChoiceTest {
    private static String shader(String relative) throws Exception {
        return Files.readString(Path.of("src/main/resources/shaders/slang", relative));
    }

    @Test void displayPassDecodesTheModeFromTheSpareFlagBits() throws Exception {
        String display = shader("passes/path_display.slang");
        assertTrue(display.contains("static const uint PATH_DISPLAY_TONEMAPPER_SHIFT = 5u;"),
            "the mode shift must stay 5 (bits 0..4 are the existing display flags)");
        assertTrue(display.contains("static const uint PATH_DISPLAY_TONEMAPPER_MASK = 7u;"),
            "three bits hold up to eight curves");
        assertTrue(display.contains("(pc.flags >> PATH_DISPLAY_TONEMAPPER_SHIFT) & PATH_DISPLAY_TONEMAPPER_MASK"),
            "the display pass must decode the mode from the push flags");
        assertTrue(display.contains("tonemapColorMode(exposed, tmMode)"),
            "the display pass must call the mode-aware curve, not the fixed tonemapColor");
    }

    @Test void javaAndSlangAgreeOnTheModeBitLayout() {
        assertEquals(5, RtSlangPathPass.DISPLAY_TONEMAPPER_SHIFT, "Java shift must match path_display.slang");
        assertEquals(0x7 << 5, RtSlangPathPass.DISPLAY_TONEMAPPER_MASK);
        // The packing must never disturb the five low display-flag bits.
        for (RtSettings.Tonemapper m : RtSettings.Tonemapper.values()) {
            int bits = RtSlangPathPass.tonemapperBits(m);
            assertEquals(0, bits & 0x1F, "tonemapper bits leaked into the display flags for " + m);
            assertEquals(m.ordinal(), bits >>> 5, "the packed mode must equal the enum ordinal for " + m);
        }
    }

    @Test void enumOrdinalIsTheShaderModeIndex() throws Exception {
        // RtTonemap.applyMode and tonemap_curve.slang dispatch on this exact order.
        assertEquals(0, RtSettings.Tonemapper.PEAK.ordinal());
        assertEquals(1, RtSettings.Tonemapper.ACES.ordinal());
        assertEquals(2, RtSettings.Tonemapper.PBR_NEUTRAL.ordinal());
        assertEquals(3, RtSettings.Tonemapper.GT.ordinal());
        assertEquals(4, RtSettings.Tonemapper.AGX.ordinal());
        String curve = shader("tonemap_curve.slang");
        assertTrue(curve.contains("static const uint TM_MODE_ACES = 1u;"));
        assertTrue(curve.contains("static const uint TM_MODE_PBR_NEUTRAL = 2u;"));
        assertTrue(curve.contains("static const uint TM_MODE_GT = 3u;"));
        assertTrue(curve.contains("static const uint TM_MODE_AGX = 4u;"));
        assertTrue(curve.contains("float3 tonemapColorMode(float3 c, uint mode)"));
    }

    @Test void agxInsetMatrixMatchesBetweenCpuAndShader() throws Exception {
        // First row of the inset matrix (transpose of the column-major GLSL inset); a typo here desyncs the look.
        String curve = shader("tonemap_curve.slang");
        assertTrue(curve.contains("0.842479062253094, 0.0784335999999992, 0.0792237451477643"),
            "AgX inset row 0 must match RtTonemap.AGX_INSET");
        assertTrue(curve.contains("15.5 * x4 * x2 - 40.14 * x4 * x + 31.96 * x4"),
            "AgX contrast polynomial must match the CPU mirror");
        assertTrue(curve.contains("float minEv = -12.47393;") && curve.contains("float maxEv = 4.026069;"),
            "AgX log range must match the CPU mirror");
    }

    @Test void bothBackendsSendTheTonemapperBits() throws Exception {
        // The live native backend must not pass a hard zero for displayFlags anymore.
        String nativeBackend = Files.readString(Path.of(
            "src/main/java/io/github/andrexarlay/vulkancompat/rt/world/NativePathTraceBackend.java"));
        assertTrue(nativeBackend.contains("RtSlangPathPass.tonemapperBits(RtSettings.tonemapper())"),
            "the native backend must forward the player's tonemapper into display_flags");
        // The reference backend folds the same bits into its own push.
        RtSettings.Tonemapper saved = RtSettings.tonemapper;
        try {
            RtSettings.tonemapper = RtSettings.Tonemapper.AGX;
            int flags = ReferencePathTraceBackend.displayFlags(0);
            assertEquals(4 << 5, flags & RtSlangPathPass.DISPLAY_TONEMAPPER_MASK,
                "the reference display push must carry the AGX mode bits");
            assertEquals(RtSlangPathPass.DISPLAY_FLAG_TONEMAP, flags & RtSlangPathPass.DISPLAY_FLAG_TONEMAP,
                "the tonemap enable bit must survive the mode packing");
        } finally {
            RtSettings.tonemapper = saved;
        }
    }

    @Test void configParsesAndPersistsTheChoice() throws Exception {
        RtSettings.Tonemapper saved = RtSettings.tonemapper;
        try {
            assertTrue(RtSettings.applyKeyValue("tonemapper", "agx"));
            assertEquals(RtSettings.Tonemapper.AGX, RtSettings.tonemapper);
            assertTrue(RtSettings.applyKeyValue("tonemapper", "pbr-neutral"));
            assertEquals(RtSettings.Tonemapper.PBR_NEUTRAL, RtSettings.tonemapper);
            // An invalid value keeps the previous one.
            assertTrue(RtSettings.applyKeyValue("tonemapper", "bogus"));
            assertEquals(RtSettings.Tonemapper.PBR_NEUTRAL, RtSettings.tonemapper);
            StringWriter w = new StringWriter();
            RtSettings.write(w);
            assertTrue(w.toString().contains("tonemapper = \"PBR_NEUTRAL\""), "the choice must persist to bedray.toml");
        } finally {
            RtSettings.tonemapper = saved;
        }
    }
}