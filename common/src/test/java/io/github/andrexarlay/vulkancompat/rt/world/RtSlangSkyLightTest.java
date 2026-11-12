package io.github.andrexarlay.vulkancompat.rt.world;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The sky lights the scene with the legacy hemisphere model, not with the colour the eye sees. The Slang dome was the
 * display sky colour run back through the tone map: at noon (0.48, 0.64, 1.00) becomes about (2.3, 3.1, 4.8), because
 * the blue channel sits on the shoulder. Every diffuse bounce that escaped received that, several times the sun and
 * saturated blue: white chickens turned blue, grass mint, the whole picture washed out (owner screenshots 2026-11-01
 * 02:29). Bounces and sky samples now receive {@link RtSky#skyLight}; camera rays and mirrors still see the visible sky.
 */
class RtSlangSkyLightTest {
    private static final Path SKY = Path.of("src/main/resources/shaders/slang/light/sky.slang");
    private static final float[] NOON_SKY = {0.482f, 0.643f, 1.0f};
    private static final float[] NOON_KEY = {1.45f, 1.405f, 1.283f};

    private static float luminance(float[] c) {
        return 0.2126f * c[0] + 0.7152f * c[1] + 0.0722f * c[2];
    }

    @Test
    void theVisibleSkyIsNoLightSource() {
        float[] visible = RtTonemap.invert(NOON_SKY[0], NOON_SKY[1], NOON_SKY[2]);
        assertTrue(luminance(visible) > 2f * luminance(NOON_KEY), "the display sky inverted outshines the sun itself");
        assertTrue(visible[2] / visible[0] > 1.9f, "and it is saturated blue");

        RtSky.SkyLight light = new RtSky.SkyLight();
        RtSky.skyLight(NOON_SKY, 1f, 0.63f, -0.63f, 1f, 1f, NOON_KEY, true, light);
        assertTrue(luminance(light.zenith) < 0.35f * luminance(NOON_KEY), "the sky light is a fraction of the sun");
        assertTrue(light.zenith[2] / light.zenith[0] < 1.5f, "and only mildly blue (a shadow under a clear sky)");
    }

    @Test
    void theFrameCarriesTheSkyLight() {
        RtFrameParams params = new RtFrameParams();
        params.skyLightZenith[0] = 0.25f; params.skyLightZenith[1] = 0.27f; params.skyLightZenith[2] = 0.31f;
        params.skyLightHorizon[0] = 0.29f; params.skyLightHorizon[1] = 0.28f; params.skyLightHorizon[2] = 0.27f;
        RtPathTraceSlangPass.Frame frame = new RtPathTraceSlangPass.Frame();
        RtSlangFrameData.fill(frame, params, 16, 9, 0, 0);
        assertEquals(0.31f, frame.skyLightZenith.z, 0f);
        assertEquals(1f, frame.skyLightZenith.w, 0f);
        assertEquals(0.29f, frame.skyLightHorizon.x, 0f);
        assertTrue(frame.zenithColor.z > 3f, "the visible zenith stays the display colour for the eye");
    }

    @Test
    void bouncesAndSkySamplesReceiveTheLightDome() throws IOException {
        String sky = Files.readString(SKY).replace("\r\n", "\n");
        assertTrue(sky.contains("    float3 skyRadiance(float3 direction) {\n        return lightRadiance(direction);\n    }"),
            "a non-delta escape receives the light dome");
        assertTrue(sky.contains("    float3 skyRadiance(float3 direction) {\n        return dome.skyRadiance(direction);\n    }"),
            "and so does the sun-and-moon sky the tracer actually uses, not the visible dome");
        assertTrue(sky.contains("result.radiance = lightRadiance(direction);"), "so does a cosine sky sample");
        assertTrue(sky.contains("float3 zenith = atmosphere.skyLightZenith.rgb * max(atmosphere.skyLightZenith.w, 0.0);"));
        assertTrue(sky.contains("float3 zenith = atmosphere.zenithColor.rgb * max(atmosphere.zenithColor.w, 0.0);"),
            "the visible dome is still there for the eye and for mirrors");
    }
}
