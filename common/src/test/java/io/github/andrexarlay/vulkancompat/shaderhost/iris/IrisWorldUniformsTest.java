package io.github.andrexarlay.vulkancompat.shaderhost.iris;

import io.github.andrexarlay.vulkancompat.shaderhost.ShaderHostState;
import io.github.andrexarlay.vulkancompat.shaderhost.glsl.IrisConstDirectives;
import io.github.andrexarlay.vulkancompat.shaderhost.glsl.IrisGlslTranslator;
import io.github.andrexarlay.vulkancompat.shaderhost.glsl.IrisUniforms;
import io.github.andrexarlay.vulkancompat.shaderhost.pack.ScanLimits;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Engine macros, the typed world block, Iris's celestial math and smoothing, and the world example pack. */
class IrisWorldUniformsTest {

    @TempDir
    Path temp;

    @org.junit.jupiter.api.BeforeEach
    @AfterEach
    void restoreMacros() {
        IrisStandardMacros.setForTesting(IrisStandardMacros.create(null, "26.3", "Windows 11"));
    }

    @Test
    void macrosFollowIrisFormatting() throws Exception {
        assertEquals("260300", IrisStandardMacros.formatVersion("26.3"));
        assertEquals("12104", IrisStandardMacros.formatVersion("1.21.4"));
        assertNull(IrisStandardMacros.formatVersion("26"));
        assertEquals("MC_OS_WINDOWS", IrisStandardMacros.osMacro("Windows 11"));
        assertEquals("MC_OS_LINUX", IrisStandardMacros.osMacro("Linux"));
        Files.writeString(temp.resolve("options.txt"), "version:4000\nmipmapLevels:2\n");
        var env = IrisStandardMacros.create(temp, "26.3", "Linux");
        assertEquals("260300", env.macros().get("MC_VERSION"));
        assertEquals("2", env.macros().get("MC_MIPMAP_LEVEL"));
        assertEquals("450", env.macros().get("MC_GL_VERSION"));
        assertEquals("", env.macros().get("IS_IRIS"));
        assertEquals("8", env.macros().get("MC_RENDER_STAGE_TERRAIN_SOLID"));
        assertEquals("23", env.macros().get("MC_RENDER_STAGE_HAND_TRANSLUCENT"));
        assertEquals("12", env.macros().get("DH_BLOCK_WATER"));
        assertTrue(env.macros().containsKey("MC_OS_LINUX"));
        assertFalse(env.macros().containsKey("MC_GL_VENDOR_NVIDIA"), "no device at compile time: vendor macros stay undefined");
        assertTrue(env.unavailable().isEmpty());
        var noVersion = IrisStandardMacros.create(null, null, "Linux");
        assertFalse(noVersion.macros().containsKey("MC_VERSION"));
        assertTrue(noVersion.unavailable().containsKey("MC_VERSION"));
    }

    private Path pack(String name, String finalFsh) throws Exception {
        Path root = temp.resolve(name).resolve("shaders");
        Files.createDirectories(root);
        Files.writeString(root.resolve("final.fsh"), finalFsh, StandardCharsets.UTF_8);
        return root.getParent();
    }

    @Test
    void referencedUnavailableMacroIsRefusedByName() throws Exception {
        IrisStandardMacros.setForTesting(IrisStandardMacros.create(null, null, "Linux"));
        Path p = pack("needs-version", "#version 120\nvoid main(){\n#if MC_VERSION >= 11700\ngl_FragColor=vec4(1);\n#else\ngl_FragColor=vec4(0);\n#endif\n}\n");
        var result = IrisPassGraph.build(p, ScanLimits.DEFAULT);
        assertFalse(result.success());
        assertEquals(ShaderHostState.UNSUPPORTED_PACK_FEATURES, result.status().state());
        assertTrue(result.status().detail().contains("MC_VERSION"), result.status().detail());
    }

    @Test
    void macrosSelectBranchesAndUndefinedFamiliesAreReported() throws Exception {
        Path p = pack("branches", "#version 120\nvoid main(){\n#if MC_VERSION >= 260000 && defined IS_IRIS\ngl_FragColor=vec4(1);\n#else\nthis would not compile;\n#endif\n"
            + "#ifdef MC_GL_VENDOR_NVIDIA\n#endif\n}\n");
        var result = IrisPassGraph.build(p, ScanLimits.DEFAULT);
        assertTrue(result.success(), result.status().detail());
        assertTrue(result.status().detail().contains("MC_GL_VENDOR_* is not defined"), result.status().detail());
    }

    @Test
    void worldUniformsTranslateToTypedBlock() {
        var r = IrisGlslTranslator.translate("#version 120\nuniform int worldTime;\nuniform vec3 sunPosition;\nuniform ivec2 eyeBrightnessSmooth;\n"
            + "void main(){gl_FragColor=vec4(sunPosition*float(worldTime+eyeBrightnessSmooth.x),1);}\n",
            IrisGlslTranslator.Stage.FRAGMENT, Map.of(), "final.fsh");
        assertTrue(r.unsupported().isEmpty(), r.unsupported().toString());
        assertEquals(Set.of("worldTime", "sunPosition", "eyeBrightnessSmooth"), r.worldUsed());
        assertTrue(r.source().contains("binding = " + IrisUniforms.BINDING_WORLD + ") uniform VkbIrisWorld"), r.source());
        assertTrue(r.source().contains("layout(offset = 136) ivec2 eyeBrightnessSmooth"), r.source());
        var wrongType = IrisGlslTranslator.translate("#version 120\nuniform float worldTime;\nvoid main(){gl_FragColor=vec4(worldTime);}\n",
            IrisGlslTranslator.Stage.FRAGMENT, Map.of(), "final.fsh");
        assertTrue(wrongType.unsupported().contains("uniform float worldTime"), wrongType.unsupported().toString());
        var unknown = IrisGlslTranslator.translate("#version 120\nuniform float screenBrightness;\nvoid main(){gl_FragColor=vec4(screenBrightness);}\n",
            IrisGlslTranslator.Stage.FRAGMENT, Map.of(), "final.fsh");
        assertTrue(unknown.unsupported().contains("uniform float screenBrightness"), "uniforms outside the block stay refused by name");
    }

    private static IrisWorldUniforms.Sample sample(long clock, boolean fixed, boolean endOrNether, float sunDeg, float moonDeg, float rain,
                                                   int block, int sky) {
        return new IrisWorldUniforms.Sample(clock, fixed, endOrNether, 3, sunDeg, moonDeg, rain, new float[] {0.1f, 0.2f, 0.3f},
            new float[] {0.4f, 0.5f, 0.6f}, 4f, 96f, 1, block, sky, 0.25f, 0.5f, 0.75f);
    }

    @Test
    void worldBlockLayoutAndIrisRules() {
        var constants = IrisWorldUniforms.Constants.defaults();
        var smoothing = new IrisWorldUniforms.Smoothing(constants);
        ByteBuffer b = ByteBuffer.allocate(IrisUniforms.WORLD_BYTES).order(ByteOrder.nativeOrder());
        IrisWorldUniforms.write(b, sample(50_123, false, false, 0f, 180f, 2f, 7, 15), new Matrix4f(), constants, smoothing, 0.05f);
        assertEquals(2_123, b.getInt(0), "worldTime = clock % 24000");
        assertEquals(2, b.getInt(4), "worldDay = clock / 24000");
        assertEquals(3, b.getInt(8));
        assertEquals(0.25f, b.getFloat(12), 1e-6f, "sunAngle = (attribute + 90) / 360");
        assertEquals(1f, b.getFloat(20), 1e-6f, "rainStrength is clamped to 1");
        assertEquals(1f, b.getFloat(24), 1e-6f, "the first smoothed sample is taken as is");
        assertEquals(1, b.getInt(28));
        assertEquals(0.25f, b.getFloat(44), 1e-6f);
        assertEquals(0.5f, b.getFloat(60), 1e-6f);
        assertEquals(0.75f, b.getFloat(76), 1e-6f);
        assertEquals(4f, b.getFloat(92), 1e-6f);
        assertEquals(0.2f, b.getFloat(100), 1e-6f);
        assertEquals(96f, b.getFloat(108), 1e-6f);
        assertEquals(0.6f, b.getFloat(120), 1e-6f);
        assertEquals(112, b.getInt(128), "eyeBrightness = block light * 16");
        assertEquals(240, b.getInt(132), "eyeBrightness.y = sky light * 16");
        assertEquals(112, b.getInt(136));
        // Day: shadowLightPosition is the sun. With identity model-view and angle 0 the sun is straight up.
        assertEquals(0f, b.getFloat(64), 1e-4f);
        assertEquals(100f, b.getFloat(68), 1e-4f);
        assertEquals(0f, b.getFloat(72), 1e-4f);

        IrisWorldUniforms.write(b, sample(30_000, true, false, 200f, 20f, 0f, 0, 0), new Matrix4f(), constants, smoothing, 0.05f);
        assertEquals(0, b.getInt(0), "a fixed-time dimension reports worldTime 0");
        float sun = IrisWorldUniforms.wrappedAngle(200f); // 290 > 180: night
        assertTrue(sun >= 180f);
        assertEquals(IrisWorldUniforms.wrappedAngle(20f) / 360f, b.getFloat(16), 1e-6f, "at night shadowAngle follows the moon");
        IrisWorldUniforms.write(b, sample(30_000, true, true, 0f, 0f, 0f, 0, 0), new Matrix4f(), constants, smoothing, 0.05f);
        assertEquals(6_000, b.getInt(0), "End/Nether skip the fixed-time rule, as Iris does");
    }

    @Test
    void celestialMatchesIrisRotationOrder() {
        Vector4f quarter = IrisWorldUniforms.celestial(new Matrix4f(), 0f, 90f);
        assertEquals(-100f, quarter.x, 1e-3f);
        assertEquals(0f, quarter.y, 1e-3f);
        assertEquals(0f, quarter.z, 1e-3f);
        Vector4f tilted = IrisWorldUniforms.celestial(new Matrix4f(), 90f, 0f);
        assertEquals(0f, tilted.y, 1e-3f, "sunPathRotation tilts the zenith away from +Y");
        Vector4f up = IrisWorldUniforms.up(new Matrix4f().rotateX(0.3f));
        assertEquals(100f, up.length(), 1e-3f);
    }

    @Test
    void smoothingUsesHalfLivesInTenthsOfASecond() {
        var s = new IrisWorldUniforms.Smoothed(600f, 200f);
        assertEquals(0f, s.update(0f, 0.016f));
        assertEquals(0.5f, s.update(1f, 60f), 1e-4f, "wetness half-life 600 = 60 s rising");
        var d = new IrisWorldUniforms.Smoothed(600f, 200f);
        d.update(1f, 0.016f);
        assertEquals(0.5f, d.update(0f, 20f), 1e-4f, "dryness half-life 200 = 20 s falling");
    }

    @Test
    void packConstantsResolveAndConflictsAreRefused() {
        var one = new IrisRenderTargets.ProgramDirectives("composite",
            IrisConstDirectives.parse("const float sunPathRotation = -40.0;\nconst int eyeBrightnessHalflife = 20;"), false, null);
        var c = IrisWorldUniforms.Constants.resolve(List.of(one));
        assertEquals(-40f, c.sunPathRotation());
        assertEquals(20f, c.eyeBrightnessHalfLife());
        assertEquals(600f, c.wetnessHalfLife());
        var other = new IrisRenderTargets.ProgramDirectives("final", IrisConstDirectives.parse("const float sunPathRotation = 10.0;"), false, null);
        assertFalse(IrisWorldUniforms.Constants.resolve(List.of(one, other)).problems().isEmpty());
    }

    @Test
    void worldExampleCompiles() throws Exception {
        IrisPassGraph graph = IrisExamplePacksTest.build("iris-world-post");
        assertEquals(-30f, graph.worldConstants().sunPathRotation());
        assertEquals(300f, graph.worldConstants().wetnessHalfLife());
        assertTrue(graph.worldUsed().containsAll(Set.of("shadowLightPosition", "wetness", "eyeBrightnessSmooth", "isEyeInWater",
            "fogColor", "worldTime", "moonPhase", "darknessFactor")), graph.worldUsed().toString());
    }
}
