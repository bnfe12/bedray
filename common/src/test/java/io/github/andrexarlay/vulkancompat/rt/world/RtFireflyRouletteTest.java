package io.github.andrexarlay.vulkancompat.rt.world;

import io.github.andrexarlay.vulkancompat.rtx.config.RtSettings;
import org.junit.jupiter.api.Test;

import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Two path-transport choices for the player: a firefly-clamp ceiling (the radiance a single sample may reach before the
 * tracer truncates it and the display mean clips it) and a Russian-roulette switch (random early termination of dim paths).
 * The clamp must drive the tracer dispatch and the live native display coherently; the roulette toggle rides spare option bit 24, outside the ReSTIR
 * PT mask, so the native core forwards it verbatim. Defaults preserve the shipped transport. String assertions are
 * mutation-proved.
 */
class RtFireflyRouletteTest {
    private static String shader(String relative) throws Exception {
        return Files.readString(Path.of("src/main/resources/shaders/slang", relative)).replace("\r\n", "\n");
    }
    private static String java(String relative) throws Exception {
        return Files.readString(Path.of("src/main/java/io/github/andrexarlay/vulkancompat", relative)).replace("\r\n", "\n");
    }
    private static String cpp(String relative) throws Exception {
        return Files.readString(Path.of("../native/bedray_core", relative)).replace("\r\n", "\n");
    }

    @Test void fireflyClampSliderDrivesTheTracerAndLiveDisplayPath() throws Exception {
        String pass = java("rt/world/RtSlangPathPass.java");
        assertTrue(pass.contains("Math.max(1f, params.rayDistance), (float) RtSettings.pathFireflyClamp(), 0.5f"),
            "the tracer dispatch must take the firefly ceiling from the player's slider");
        assertFalse(pass.contains("static final float MAX_RADIANCE"),
            "the fixed ceiling must be gone once the slider owns it");
        String nativeBackend = java("rt/world/NativePathTraceBackend.java");
        assertTrue(nativeBackend.contains("(float) RtSettings.pathFireflyClamp(), RtSlangPathPass.REPROJECTION_TOLERANCE"),
            "the live native display clamp must follow the same slider");
    }

    @Test void fireflyClampIsRegisteredRangeCheckedAndPersisted() throws Exception {
        int saved = RtSettings.pathFireflyClamp;
        try {
            assertEquals(64, RtSettings.pathFireflyClamp(), "the shipped ceiling is the default");
            assertTrue(RtSettings.applyKeyValue("path_firefly_clamp", "128"));
            assertEquals(128, RtSettings.pathFireflyClamp());
            assertTrue(RtSettings.applyKeyValue("path_firefly_clamp", "7"));
            assertEquals(128, RtSettings.pathFireflyClamp(), "below the floor must keep the previous value");
            assertTrue(RtSettings.applyKeyValue("path_firefly_clamp", "257"));
            assertEquals(128, RtSettings.pathFireflyClamp(), "above the ceiling must keep the previous value");
            StringWriter w = new StringWriter();
            RtSettings.write(w);
            assertTrue(w.toString().contains("path_firefly_clamp = 128"), "the slider must persist to bedray.toml");
            String settings = java("rtx/config/RtSettings.java");
            assertTrue(settings.contains("integer(\"path_firefly_clamp\", () -> pathFireflyClamp, v -> pathFireflyClamp = v, 8, 256)"),
                "the slider must be registered with the 8..256 range");
        } finally {
            RtSettings.pathFireflyClamp = saved;
        }
    }

    @Test void russianRouletteUsesSpareBit24OutsideThePtMask() throws Exception {
        assertEquals(1 << 24, RtPathTraceSlangPass.OPTION_NO_RUSSIAN_ROULETTE, "the toggle owns spare option bit 24");
        assertEquals(1 << 23, RtPathTraceSlangPass.OPTION_RR_PT_DECORRELATE, "bit 23 was the last used option bit");
        assertEquals(0, RtPathTraceSlangPass.OPTION_NO_RUSSIAN_ROULETTE & RtPathTraceSlangPass.PT_STATE_MASK,
            "bit 24 must stay outside the ReSTIR PT mask the native core replaces");
        String tracer = shader("pathtrace/path_tracer.slang");
        assertTrue(tracer.contains("static const uint PATH_TRACE_OPTION_NO_RUSSIAN_ROULETTE = 1u << 24u;"),
            "the shader must define the same bit 24");
    }

    @Test void tracerGatesRussianRouletteOnTheToggle() throws Exception {
        String tracer = shader("pathtrace/path_tracer.slang");
        assertTrue(tracer.contains(
            "if ((options.flags & PATH_TRACE_OPTION_NO_RUSSIAN_ROULETTE) == 0u && result.depth >= options.minRussianDepth) {"),
            "roulette must run only while the toggle is off (bit clear), preserving the default transport");
    }

    @Test void hostOptionFlagsCarryTheRouletteToggle() throws Exception {
        String pass = java("rt/world/RtSlangPathPass.java");
        assertTrue(pass.contains("(RtSettings.pathRussianRoulette() ? 0 : RtPathTraceSlangPass.OPTION_NO_RUSSIAN_ROULETTE)"),
            "the option word must set bit 24 only when the player turned roulette off");
    }

    @Test void rouletteDefaultsOnParsesAndPersists() throws Exception {
        boolean saved = RtSettings.pathRussianRoulette;
        try {
            assertTrue(RtSettings.pathRussianRoulette(), "roulette is on by default, as shipped");
            assertTrue(RtSettings.applyKeyValue("path_russian_roulette", "false"));
            assertFalse(RtSettings.pathRussianRoulette());
            assertTrue(RtSettings.applyKeyValue("path_russian_roulette", "true"));
            assertTrue(RtSettings.pathRussianRoulette());
            StringWriter w = new StringWriter();
            RtSettings.write(w);
            assertTrue(w.toString().contains("path_russian_roulette = true"), "the toggle must persist to bedray.toml");
        } finally {
            RtSettings.pathRussianRoulette = saved;
        }
    }

    @Test void menuAndCatalogOfferBothControls() throws Exception {
        String menu = java("sodium/VulkanSodiumConfig.java");
        assertTrue(menu.contains("intOpt(builder, \"path_firefly_clamp\", 64, 8, 256, 1,"),
            "the firefly clamp must be a path slider row");
        assertTrue(menu.contains("boolOpt(builder, \"path_russian_roulette\", true, v -> RtSettings.pathRussianRoulette = v,"),
            "roulette must be a path toggle row bound to RtSettings");
        assertTrue(menu.contains("() -> RtSettings.pathFireflyClamp, pathOn, dMode, Mark.PATH"),
            "the clamp is a path-mode detail");
        assertTrue(menu.contains("() -> RtSettings.pathRussianRoulette, pathOn, dMode, Mark.PATH"),
            "roulette is a path-mode detail");
        String catalog = java("sodium/SettingsCatalog.java");
        assertTrue(catalog.contains("putNoImage(\"path_firefly_clamp\", Tier.ADVANCED, \"Firefly Clamp\","));
        assertTrue(catalog.contains("putNoImage(\"path_russian_roulette\", Tier.ADVANCED, \"Russian Roulette\","));
    }

    @Test void nativeForwardsTheRouletteBitAndRadianceVerbatim() throws Exception {
        String pass = cpp("src/path_trace_pass.cpp");
        assertTrue(pass.contains("constexpr std::uint32_t kPathOptionRestirPtMask = 0x1Fu << 17u;"),
            "the PT mask covers bits 17..21 only, so bit 24 survives");
        assertTrue(pass.contains("trace_push.flags = (desc.path.flags & ~kPathOptionRestirPtMask) | pt_flags;"),
            "the native core forwards the option word verbatim outside the PT mask");
        assertTrue(pass.contains("trace_push.max_radiance = desc.path.max_radiance;"),
            "the native tracer takes the firefly ceiling from the descriptor the slider fills");
    }
}