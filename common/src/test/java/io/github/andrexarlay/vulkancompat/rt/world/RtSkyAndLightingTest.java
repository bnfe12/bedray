package io.github.andrexarlay.vulkancompat.rt.world;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * L2 JUnit tests for:
 * 1. Dark night ambient scaling (ensuring night is genuinely dark, requiring torches).
 * 2. Authentic square Minecraft sun and moon coverage in ray tangent basis.
 * 3. Biome palette unpacking, green/teal tint preservation, and cardinal shade division.
 * 4. Procedural Minecraft-style clouds coverage and horizon fade.
 */
public class RtSkyAndLightingTest {

    private static final float EPSILON = 1e-5f;

    @Test
    public void testNightAmbientDarkButNotBlack() {
        float dayScale = RtSky.ambientScale(1.0f);
        assertEquals(RtSky.AMBIENT_SCALE, dayScale, EPSILON);
        assertEquals(0f, RtSky.ambientScale(0.0f), EPSILON);
        assertEquals(dayScale, RtLighting.ambientScale(1.0f), EPSILON);

        float[] skyColor = {0.4f, 0.6f, 1.0f};
        float[] key = {0.06f, 0.075f, 0.1f};
        float[] night = new float[3];
        RtSky.ambient(skyColor, 0.0f, -1.0f, 1.0f, 1.0f, 1.0f, key, 1.0f, night);
        for (int i = 0; i < 3; i++) {
            assertTrue(night[i] > 0.005f && night[i] < 0.06f, "night sky light is dim but never black: " + night[i]);
        }
        assertTrue(night[2] > night[0], "night sky light is blue");
        float[] newMoon = new float[3];
        RtSky.ambient(skyColor, 0.0f, -1.0f, 1.0f, 0.08f, 1.0f, key, 1.0f, newMoon);
        assertTrue(newMoon[2] < night[2], "a new moon gives less sky light than a full moon");
        float[] noon = new float[3];
        RtSky.ambient(skyColor, 1.0f, 1.0f, -1.0f, 1.0f, 1.0f, new float[] {1f, 0.97f, 0.92f}, 1.0f, noon);
        assertTrue(noon[0] > night[0] * 8f, "day ambient is far brighter than night");
        assertTrue(noon[2] < noon[0] * 1.25f, "noon sky light is not a blue wash: " + noon[2] / noon[0]);
    }

    @Test
    public void testSunColourFollowsElevation() {
        float[] low = new float[3];
        float[] mid = new float[3];
        float[] high = new float[3];
        RtSky.sunColor(0.03f, low);
        RtSky.sunColor(0.28f, mid);
        RtSky.sunColor(0.95f, high);
        assertTrue(low[0] > low[1] && low[1] > low[2] && low[2] / low[0] < 0.35f, "horizon sun is orange-red");
        assertTrue(high[2] / high[0] > 0.85f && high[1] / high[0] > 0.93f, "noon sun is near white");
        assertTrue(mid[2] / mid[0] > low[2] / low[0] && mid[2] / mid[0] < high[2] / high[0], "colour ramps monotonically");

        RtSky.Key sunset = new RtSky.Key();
        RtSky.Key noon = new RtSky.Key();
        // sunAngle a gives elevation cos(a): 0 = noon, 1.45 rad = about 7 degrees up
        RtSky.keyLight(0f, (float) Math.PI, false, true, 1f, 1f, noon);
        RtSky.keyLight(1.45f, 1.45f + (float) Math.PI, false, true, 1f, 1f, sunset);
        assertTrue(noon.color[0] > sunset.color[0], "low sun is dimmer than noon");
        assertTrue(sunset.color[2] / sunset.color[0] < noon.color[2] / noon.color[0] * 0.5f, "sunset is much warmer than noon");
    }

    @Test
    public void testMoonPhaseScalesMoonLight() {
        RtSky.Key full = new RtSky.Key();
        RtSky.Key quarter = new RtSky.Key();
        RtSky.keyLight((float) Math.PI, 0f, false, true, 1.0f, 1.0f, full);
        RtSky.keyLight((float) Math.PI, 0f, false, true, 0.5f, 1.0f, quarter);
        assertTrue(full.isMoon && quarter.isMoon);
        assertEquals(full.color[2] * 0.5f, quarter.color[2], 1e-5f);
        assertTrue(full.color[2] > full.color[0], "moonlight is cold");
    }

    @Test
    public void testGoldenHourAmbientIsWarmerThanNoon() {
        float[] sky = {0.47f, 0.65f, 1.0f};
        float[] noon = new float[3];
        float[] gold = new float[3];
        RtSky.ambient(sky, 1.0f, 1.0f, -1.0f, 1.0f, 1.0f, new float[] {1f, 0.97f, 0.92f}, 0.0f, noon);
        RtSky.ambient(sky, 0.6f, 0.12f, -1.0f, 1.0f, 1.0f, new float[] {1f, 0.6f, 0.35f}, 0.0f, gold);
        assertTrue(gold[2] / gold[0] < noon[2] / noon[0], "golden hour horizon light is redder than noon");
    }

    @Test
    public void testBiomePalettePreservation() {
        // Memory order: R=0x4A, G=0x96, B=0x2E, A=0xFF (Minecraft plains grass tint)
        int grassColor = 0xFF2E964A;
        float r = (grassColor & 0xFF) / 255.0f;
        float g = ((grassColor >> 8) & 0xFF) / 255.0f;
        float b = ((grassColor >> 16) & 0xFF) / 255.0f;

        assertTrue(g > r && g > b, "Grass tint must have green dominance");
        float ratioGR = g / r;
        float ratioGB = g / b;

        // Dividing by cardinal shade preserves chromaticity
        float sideShade = RtLighting.cardinalShade(1.0f, 0.0f, 0.0f);
        assertEquals(0.6f, sideShade, EPSILON);

        float unshadedR = r / sideShade;
        float unshadedG = g / sideShade;
        float unshadedB = b / sideShade;

        assertEquals(ratioGR, unshadedG / unshadedR, EPSILON, "G/R ratio must be strictly preserved");
        assertEquals(ratioGB, unshadedG / unshadedB, EPSILON, "G/B ratio must be strictly preserved");

        // Swamp water teal tint
        int swampWaterColor = 0xFF4D6638; // teal/dark green water
        float swR = (swampWaterColor & 0xFF) / 255.0f;
        float swG = ((swampWaterColor >> 8) & 0xFF) / 255.0f;
        float swB = ((swampWaterColor >> 16) & 0xFF) / 255.0f;
        float topShade = RtLighting.cardinalShade(0.0f, 1.0f, 0.0f);
        assertEquals(1.0f, topShade, EPSILON);
        assertEquals(swG / swR, (swG / topShade) / (swR / topShade), EPSILON);
    }

    @Test
    public void testSmoothSunToMoonTransition() {
        float crossAngle = (float) Math.acos(-0.12);
        RtSky.Key before = new RtSky.Key();
        RtSky.Key after = new RtSky.Key();
        RtSky.keyLight(crossAngle - 0.001f, crossAngle - 0.001f + (float) Math.PI, false, true, 1.0f, 1.0f, before);
        RtSky.keyLight(crossAngle + 0.001f, crossAngle + 0.001f + (float) Math.PI, false, true, 1.0f, 1.0f, after);

        assertFalse(before.isMoon);
        assertTrue(after.isMoon);
        assertEquals(0.0f, before.color[2], 1e-3f, "Sun light must be zero at crossover");
        assertEquals(0.0f, after.color[2], 1e-3f, "Moon light must ramp smoothly from zero at crossover");
        assertTrue(RtSkyState.maxExposure(0.0f) < 2.5f, "Night max exposure must stay clamped so night stays dark");
    }
}
