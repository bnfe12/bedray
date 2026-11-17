package io.github.andrexarlay.vulkancompat.rt.world;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Cloud shadows on the key light: the shader reads the very cells the sky draws, both the sun sample and its re-evaluation
 * apply the same factor, no pass that has no sky packet can read memory through it, and the strength travels in free bits of
 * the sky flags without touching the bits the sky already uses.
 */
class RtSlangCloudShadowTest {
    private static final String ROOT = "src/main/resources/shaders/slang/";

    @Test void theShadowReadsTheCellsTheSkyDraws() {
        String shadow = read("light/cloud_shadow.slang");
        String sky = read("light/sky_clouds.slang");
        // the same cell function, the same texture slot, the same alpha threshold and the same 12-block cell
        assertTrue(shadow.contains("skyCloudCell(sky, cell, size).a >= 10.0 / 255.0"));
        assertTrue(sky.contains("if (c.a >= 10.0 / 255.0) {"));
        assertTrue(shadow.contains("skyTexSize(sky, 9u)"));
        assertTrue(sky.contains("skyTexSize(sky, 9u)"));
        assertTrue(shadow.contains("int2(floor(q.xz / 12.0))"));
        // the same layer geometry: FANCY slab 0..4 above the cloud bottom, FAST the plane at the bottom
        assertTrue(shadow.contains("float top = status == 2u ? 4.0 : 0.0;"));
        assertTrue(sky.contains("float top = status == 2u ? 4.0 : 0.0;"));
        // the same camera-in-cloud-space origin, shifted by the receiver's camera-relative position
        assertTrue(shadow.contains("float3 o = source.position.xyz + position;"));
        // fade with distance like the picture
        assertTrue(shadow.contains("source.position.w"));
    }

    @Test void theSunSamplerAppliesTheSameFactorWhenItDrawsAndWhenItReevaluates() {
        String sun = read("light/sun.slang");
        String call = "radiance *= cloudShadowTransmittance(clouds, receiver.position, direction);";
        assertEquals(2, sun.split(java.util.regex.Pattern.quote(call), -1).length - 1,
            "sample() and evaluate() must both scale the radiance, or a reused sample is re-weighed with another value");
        assertTrue(sun.contains("CloudShadowSource clouds;"));
        assertTrue(sun.contains("import \"light/cloud_shadow.slang\";"));
        // the target density of a fresh draw is taken after the factor, like evaluate() takes it
        int draw = sun.indexOf(call);
        int target = sun.indexOf("result.targetPdf = luminance(radiance) * cosSurface;");
        assertTrue(draw > 0 && target > draw, "the draw's target density must see the shadowed radiance");
    }

    @Test void everyPassThatBuildsTheSamplerStatesWhatItDoesWithTheClouds() {
        // a sampler local is not zero-initialised: a pass that does not read a sky packet must say so
        String rc = read("passes/rc_field_build_scene.slang");
        assertTrue(rc.contains("key.clouds = (CloudShadowSource)0;"));
        String entry = read("pathtrace/path_trace_entry.slang");
        assertTrue(entry.contains("keyLight.clouds = (CloudShadowSource)0;"));
        assertTrue(entry.contains("lights.keyLight.clouds = cloudShadowSource(environment.display);"));
        int zero = entry.indexOf("keyLight.clouds = (CloudShadowSource)0;");
        int fill = entry.indexOf("lights.keyLight.clouds = cloudShadowSource(environment.display);");
        int copy = entry.indexOf("lights.keyLight = keyLight;");
        assertTrue(zero < copy && copy < fill, "the sampler is copied into the light list first and filled once the packet is read");
    }

    @Test void aZeroedSourceOrAnOffStrengthReadsNothing() {
        String shadow = read("light/cloud_shadow.slang");
        String guard = shadow.substring(shadow.indexOf("float cloudShadowTransmittance("), shadow.indexOf("SkyDisplayState sky = (SkyDisplayState)0;"));
        assertTrue(guard.contains("percent == 0u"));
        assertTrue(guard.contains("status == 0u"));
        assertTrue(guard.contains("source.textureAddress == 0u"));
        assertTrue(guard.contains("direction.y > CLOUD_SHADOW_MIN_ELEVATION"));
        assertTrue(shadow.contains("if (!sky.valid) return source;"), "a packet that failed its checks gives the zero source");
        assertTrue(shadow.contains("return isFinite(transmittance) ? clamp(transmittance, 0.0, 1.0) : 1.0;"));
    }

    @Test void theStrengthRidesInFreeBitsOfTheSkyFlags() {
        // bits the sky already owns: moon phase 0..2, has-sky 4, flip 5, end 6, nether 7, cloud status 8..9, tonemapped 10
        int used = 0x7 | (1 << 4) | (1 << 5) | (1 << 6) | (1 << 7) | (3 << 8) | RtSkyDisplayLayout.TONEMAPPED;
        int strength = RtSkyDisplayLayout.CLOUD_SHADOW_MASK << RtSkyDisplayLayout.CLOUD_SHADOW_SHIFT;
        assertEquals(0, used & strength);
        assertEquals(0, strength & RtSkyDisplayLayout.VERSION_MASK);
        assertTrue(read("light/cloud_shadow.slang").contains("CLOUD_SHADOW_PERCENT_SHIFT = " + RtSkyDisplayLayout.CLOUD_SHADOW_SHIFT + "u;"));
        assertTrue(read("light/cloud_shadow.slang").contains("(source.flags >> CLOUD_SHADOW_PERCENT_SHIFT) & 0xFFu"));
    }

    @Test void theSettingIsAPercentThatFitsItsEightBits() {
        float before = RtAtmosphereSettings.cloudShadows();
        try {
            RtAtmosphereSettings.set("cloud_shadows", 1.0);
            assertEquals(100, RtAtmosphereSettings.cloudShadowPercent());
            RtAtmosphereSettings.set("cloud_shadows", 0.0);
            assertEquals(0, RtAtmosphereSettings.cloudShadowPercent());
            RtAtmosphereSettings.set("cloud_shadows", 2.0);
            assertEquals(200, RtAtmosphereSettings.cloudShadowPercent());
            RtAtmosphereSettings.set("cloud_shadows", 99.0);
            assertEquals(200, RtAtmosphereSettings.cloudShadowPercent(), "the setting clamps at 200 percent, below the 255 the bits hold");
            RtAtmosphereSettings.set("cloud_shadows", -3.0);
            assertEquals(0, RtAtmosphereSettings.cloudShadowPercent());
            assertTrue(RtAtmosphereSettings.options().stream().anyMatch(o -> o.key().equals("cloud_shadows")));
        } finally {
            RtAtmosphereSettings.set("cloud_shadows", before);
        }
    }

    private static String read(String relative) {
        try { return Files.readString(Path.of(ROOT + relative)).replace("\r\n", "\n"); }
        catch (IOException failure) { throw new AssertionError("cannot read " + relative, failure); }
    }
}
