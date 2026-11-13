package io.github.andrexarlay.vulkancompat.rt.world;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class RtDimensionLightTest {
    @Test
    void netherFogCannotTurnTheWholeLightDomeIntoPureRed() {
        var light = new RtSky.SkyLight();
        RtSky.dimensionSkyLight(false, true, new float[] {1f, 0f, 0f}, light);
        assertTrue(light.horizon[0] >= light.horizon[1]);
        assertTrue(light.horizon[1] >= light.horizon[0] * 0.8f, "retain neutral fill for cyan/blue materials");
        assertTrue(light.horizon[2] >= light.horizon[0] * 0.8f);
        for (float channel : light.horizon) assertTrue(channel > 0f && channel < 0.2f);
    }

    @Test
    void endKeepsItsOwnCoolPalette() {
        var light = new RtSky.SkyLight();
        RtSky.dimensionSkyLight(true, false, new float[] {1f, 0f, 0f}, light);
        assertTrue(light.zenith[2] > light.zenith[0]);
        assertTrue(light.zenith[0] > light.zenith[1]);
        assertTrue(light.ground[2] < light.zenith[2]);
    }

    @Test
    void overworldModelIsUntouchedAndMalformedFogCannotPoisonAFrame() {
        var light = new RtSky.SkyLight();
        light.zenith[0] = 0.71f;
        light.horizon[2] = 0.32f;
        RtSky.dimensionSkyLight(false, false, new float[3], light);
        assertEquals(0.71f, light.zenith[0]);
        assertEquals(0.32f, light.horizon[2]);
        RtSky.dimensionSkyLight(false, true, new float[] {Float.NaN, -1f, Float.POSITIVE_INFINITY}, light);
        for (float channel : light.zenith) assertTrue(Float.isFinite(channel) && channel > 0f);
    }

    @Test
    void capturedDimensionStateReachesThePublishedLightDome() throws Exception {
        String host = Files.readString(Path.of("src/main/java/io/github/andrexarlay/vulkancompat/rt/world/RtFrameRenderer.java"));
        int palette = host.indexOf("RtSky.dimensionSkyLight(skyState.isEnd, skyState.isNether, skyState.fogColor, skyLight)");
        assertTrue(palette > host.indexOf("RtSky.skyLight(params.skyColor"));
        assertTrue(palette < host.indexOf("System.arraycopy(skyLight.zenith"));
    }
}
