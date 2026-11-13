package io.github.andrexarlay.vulkancompat.shaderhost.pack;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IrisPackSettingsTest {

    @TempDir
    Path tmp;

    private static final String PROPS = """
        profile.MINIMUM=!AO !SHADOW shadowMapResolution=512
        profile.LOW=profile.MINIMUM SHADOW shadowMapResolution=1024
        program.world0/shadow.enabled=SHADOW
        program.world0/shadowcomp.enabled=SHADOW && MULTICOLORED_BLOCKLIGHT
        texture.noise=tex/noise.png
        texture.composite.colortex7=tex/dirt.png
        colortex1.format=RGBA16F
        uniform.float.shadowFade=clamp(1.0 - abs(sunAngle - 0.5), 0.0, 1.0)
        """;

    private static IrisPackSettings settings(String profile) {
        String prev = System.getProperty("vkrtx.shaderhost.profile");
        if (profile == null) System.clearProperty("vkrtx.shaderhost.profile");
        else System.setProperty("vkrtx.shaderhost.profile", profile);
        try {
            return IrisPackSettings.parse(PackProperties.parse(PROPS), Set.of("world0", "world-1", "world1"));
        } finally {
            if (prev == null) System.clearProperty("vkrtx.shaderhost.profile");
            else System.setProperty("vkrtx.shaderhost.profile", prev);
        }
    }

    @Test
    void noProfileAppliedWhenNoDefault() {
        assertEquals("", settings(null).profile());
    }

    @Test
    void profileTokensSetOptions() {
        IrisPackSettings min = settings(null);
        assertFalse(min.optionBool("SHADOW"));
        assertFalse(min.optionBool("AO"));
        assertNull(min.options().get("shadowMapResolution"));
        IrisPackSettings low = settings("LOW");
        assertTrue(low.optionBool("SHADOW"));
        assertEquals("1024", low.options().get("shadowMapResolution"));
    }

    @Test
    void programEnabledFollowsProfile() {
        assertFalse(settings(null).isProgramEnabled("world0", "shadow"));
        assertTrue(settings("LOW").isProgramEnabled("world0", "shadow"));
        assertFalse(settings("LOW").isProgramEnabled("world0", "shadowcomp"));
        assertTrue(settings(null).isProgramEnabled("world0", "gbuffers_terrain"));
    }

    @Test
    void dimensionFolderMapping() {
        IrisPackSettings s = settings(null);
        assertEquals("world0", s.dimensionFolder("minecraft:overworld"));
        assertEquals("world-1", s.dimensionFolder("minecraft:the_nether"));
        assertEquals("world1", s.dimensionFolder("minecraft:the_end"));
        assertEquals("world0", s.dimensionFolder("unknown:dim"));
    }

    @Test
    void dimensionFolderFallsBackToRootWhenAbsent() {
        IrisPackSettings s = IrisPackSettings.parse(PackProperties.parse(PROPS), Set.of());
        assertEquals("", s.dimensionFolder("minecraft:overworld"));
    }

    @Test
    void customTexturesResolved() {
        IrisPackSettings s = settings(null);
        assertEquals("tex/noise.png", s.customTexture("noise"));
        assertEquals("tex/dirt.png", s.customTexture("colortex7"));
        assertNull(s.customTexture("missing"));
    }

    @Test
    void colortexFormatDefaultAndExplicit() {
        IrisPackSettings s = settings(null);
        assertEquals("RGBA8", s.colortexFormat(0));
        assertEquals("RGBA16F", s.colortexFormat(1));
    }

    @Test
    void defineMacrosReflectOptions() {
        Map<String, String> min = settings(null).defineMacros();
        assertFalse(min.containsKey("SHADOW"));
        Map<String, String> low = settings("LOW").defineMacros();
        assertTrue(low.containsKey("SHADOW"));
    }

    @Test
    void uniformExprReturned() {
        assertEquals("clamp(1.0 - abs(sunAngle - 0.5), 0.0, 1.0)", settings(null).uniformExpr("shadowFade"));
        assertNull(settings(null).uniformExpr("nope"));
    }

    @Test
    void sourceDefineDefaultsGovernWhenNoProfile() throws IOException {
        Path p = tmp.resolve("Src.zip");
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(p))) {
            put(z, "shaders/lib/settings.glsl", "#define SHADOW\n//#define AO\nconst int shadowMapResolution = 2048;\n");
            put(z, "shaders/shaders.properties",
                "screen.LIGHTING=SHADOW AO shadowMapResolution\nprogram.world0/shadow.enabled=SHADOW\nprogram.world0/deferred.enabled=AO\n");
            put(z, "shaders/final.fsh", "#version 120\nvoid main(){gl_FragColor=vec4(1.0);}\n");
        }
        try (PackSource src = PackSource.open(p, ScanLimits.DEFAULT)) {
            PackReport report = ShaderpackScanner.scan(src);
            IrisPackSettings s = IrisPackSettings.parse(src, report);
            assertEquals("", s.profile());
            assertTrue(s.optionBool("SHADOW"), "uncommented #define SHADOW must default on");
            assertFalse(s.optionBool("AO"), "commented //#define AO must default off");
            assertEquals("2048", s.options().get("shadowMapResolution"));
            assertTrue(s.isProgramEnabled("world0", "shadow"));
            assertFalse(s.isProgramEnabled("world0", "deferred"));
        }
    }

    @Test
    void profileDefaultOverlaysSourceDefaults() throws IOException {
        Path p = tmp.resolve("Overlay.zip");
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(p))) {
            put(z, "shaders/lib/settings.glsl", "#define SHADOW\nconst int shadowMapResolution = 2048;\n");
            put(z, "shaders/shaders.properties",
                "screen.LIGHTING=SHADOW shadowMapResolution\nprofile.DEFAULT=!SHADOW shadowMapResolution=512\nprogram.world0/shadow.enabled=SHADOW\n");
            put(z, "shaders/final.fsh", "#version 120\nvoid main(){gl_FragColor=vec4(1.0);}\n");
        }
        try (PackSource src = PackSource.open(p, ScanLimits.DEFAULT)) {
            PackReport report = ShaderpackScanner.scan(src);
            IrisPackSettings s = IrisPackSettings.parse(src, report);
            assertEquals("DEFAULT", s.profile());
            assertFalse(s.optionBool("SHADOW"), "profile.DEFAULT !SHADOW overrides source default");
            assertEquals("512", s.options().get("shadowMapResolution"));
            assertFalse(s.isProgramEnabled("world0", "shadow"));
        }
    }

    private static void put(ZipOutputStream z, String name, String text) throws IOException {
        z.putNextEntry(new ZipEntry(name));
        z.write(text.getBytes(StandardCharsets.UTF_8));
        z.closeEntry();
    }
}