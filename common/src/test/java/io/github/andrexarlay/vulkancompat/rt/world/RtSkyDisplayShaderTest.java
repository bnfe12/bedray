package io.github.andrexarlay.vulkancompat.rt.world;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

class RtSkyDisplayShaderTest {
    private static final Path LIGHT = Path.of("src/main/resources/shaders/slang/light");
    private record Preserved(String file, String function, String hash) {}

    @Test void theDisplayTransferKeepsTheExistingSkyEquations() throws Exception {
        // Hashes of the removed rt_sky.glsl at 1ea94ce^, after language/state
        // syntax conversion only. Remove only the explicit body-ownership gate;
        // body luminance used solely by the deleted specular glint is not copied.
        List<Preserved> functions = List.of(
            new Preserved("sky_celestial.slang", "celestialLocal", "ec794a9b8ba642e9ab6cef5bc924d5aece0aea026cf72da5d9c0c892ac170277"),
            new Preserved("sky_celestial.slang", "celestialQuad", "ac8d926e7ea9b1785bdb6ed7be561c8f69081eb71f1b60c3934f799a69958f39"),
            new Preserved("sky_celestial.slang", "skySunTexel", "082d4ca53731149a8e476a867b45779280e15746669d9e0284c6f739c4e67c87"),
            new Preserved("sky_celestial.slang", "skyMoonTexel", "28547d2d8670d821f310309dad823ea3698c66705630240c055f3fc075d7cbe6"),
            new Preserved("sky_celestial.slang", "skyHash", "8780cd5b538b40f882629c8cbba35108b95069ccc0924b6fe1b15466a7c1b58c"),
            new Preserved("sky_celestial.slang", "skyStars", "7bf0588b88e385e944f16f1b06a8c93eb528bde6a0f1aaa3280867ccfc8a4078"),
            new Preserved("sky_clouds.slang", "skyCloudCell", "4c8bb2be11ec0ba1cc55a9957e530d4b129a740a67193bf2bd4e9561025e48b2"),
            new Preserved("sky_clouds.slang", "skyClouds", "0050ea8630e934378bc3695ac5329b7e4a30ca84b3deeceed6ada680b93d450b"),
            new Preserved("sky_background.slang", "skyFogColor", "db885466b57e673f85914c3d3578c2c40ff33b683acc98f0411e5ec6db45aa94"),
            new Preserved("sky_background.slang", "skySunsetFan", "6e78a6ed3968aac6ccba3b3117b2a10f84a7d650aeb2e4c6eb17d8fc04ff7f96"),
            new Preserved("sky_background.slang", "skyEndCube", "f85c85989d3d744df2c4e950c1bbadf47e7dcf9e7862c9dbb3106b2f0c73201f"),
            new Preserved("sky_background.slang", "skyEndFlashTexel", "68e2cb3d93fdf8cb55149bb4d0678c5abb369086e18af7b6aed88404cad3c846"),
            new Preserved("sky_background.slang", "skyDisplay", "44e7a731d0e38ad97b2621c8e2d87e09ea2fcdf79e33a1415386a677d915c306"));
        for (Preserved function : functions) {
            String source = Files.readString(LIGHT.resolve(function.file));
            String canonical = body(source, function.function)
                .replaceAll("\\b(\\w+)\\(sky,\\s*", "$1(").replaceAll("\\b(\\w+)\\(sky\\)", "$1()")
                .replaceAll("if\\s*\\(includeBodies\\)\\s*", "").replaceAll("\\s+", "");
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
            assertEquals(function.hash, hash, function.function);
        }
    }

    @Test void visibleSpritesDoNotBecomeAnotherDiffuseLightOrASecondNeeBody() throws Exception {
        String adapter = Files.readString(LIGHT.resolve("frame_sky.slang"));
        assertTrue(body(adapter, "radiance").contains("skyBackgroundRadiance(display, direction, true)"));
        assertTrue(body(adapter, "viewRadiance").contains("skyBackgroundRadiance(display, direction, false)"));
        assertTrue(body(adapter, "skyRadiance").contains("return fallback.skyRadiance(direction)"));
        assertTrue(body(adapter, "sample").contains("return fallback.sample(normal, seed)"));
        assertTrue(body(adapter, "pdf").contains("return fallback.pdf(normal, direction)"));
        String background = Files.readString(LIGHT.resolve("sky_background.slang"));
        assertTrue(background.contains("if (includeBodies) c += sun.rgb * rain;"));
        assertTrue(background.contains("if (includeBodies) c += moon.rgb * moon.a * rain;"));
        assertFalse(background.contains("sunDisk("), "the old extra specular glint would pay NEE twice");
        for (String file : List.of("sky_celestial.slang", "sky_clouds.slang", "sky_background.slang", "sky_textures.slang", "frame_sky.slang"))
            assertFalse(Files.readString(LIGHT.resolve(file)).contains("vk::binding"), "the atlas is borrowed through the existing packet");
    }

    @Test void theFinalDisplayExposurePreservesTheSkyInBothOutputModes() throws Exception {
        for (float exposure : new float[] {0.2f, 1.1f, 8f}) {
            float[] display = {0.2f, 0.45f, 0.8f};
            float[] linear = RtTonemap.invert(display[0], display[1], display[2]);
            float[] output = RtTonemap.apply(linear[0] / exposure * exposure, linear[1] / exposure * exposure,
                linear[2] / exposure * exposure);
            assertArrayEquals(display, output, 2e-6f);
            for (float channel : display) assertEquals(channel, channel / exposure * exposure, 1e-6f);
        }
        String conversion = body(Files.readString(LIGHT.resolve("sky_background.slang")), "skyBackgroundRadiance");
        assertTrue(conversion.contains("? tonemapInverse(display) : display) / exposure;"),
            "the typed display applies exposure even when the tone curve is disabled");
    }

    private static String body(String source, String function) {
        source = source.replaceAll("(?s)/\\*.*?\\*/|//[^\\n]*", "");
        var match = Pattern.compile("\\b" + function + "\\s*\\([^;{]*\\)\\s*\\{").matcher(source);
        assertTrue(match.find(), function);
        int start = match.end(), end = start, depth = 1;
        while (end < source.length() && depth > 0) {
            char next = source.charAt(end++);
            if (next == '{') depth++; else if (next == '}') depth--;
        }
        assertEquals(0, depth, function);
        return source.substring(start, end - 1);
    }
}
