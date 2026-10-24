package io.github.andrexarlay.vulkancompat.rt.world;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Source check for pixel shadows ({@code RtSettings.raysPixelShadows}): one world cell must produce one shadow result.
 *
 * <p>{@code pixelShadowOrigin} snaps the shadow-ray origin to the centre of the cell, and {@code pixelShadowCell} exists
 * so that every pixel of a cell shares the same penumbra sample: the sun-disk point comes from
 * {@code pixelOffset(gSunPixel)}, so the cell index has to replace the pixel index there too. {@code rt_rays.comp} does
 * that; {@code rt_path.comp} set {@code gSunPixel = p} once per pixel and never replaced it, which left the path tracer
 * casting a different part of the sun disk from every pixel of a snapped cell: a blocky penumbra filled with per-pixel
 * speckle instead of clean blocks.
 */
public class RtPixelShadowSamplingTest {

    private static final String PATH_SHADER = "/shaders/rt_path.comp";
    private static final String RAYS_SHADER = "/shaders/rt_rays.comp";
    private static final String SAMPLING_MODULE = "/shaders/rt_common_sampling.glsl";
    private static final String CELL_SNAP = "gSunPixel = ivec2(pixelShadowCell(s.worldPos)) & ivec2(1023);";
    private static final String DISK_SAMPLE = "sunShadowDirection(pc.keyDir.xyz, sunRadius(), gSunSub, gSunSpp)";

    @Test
    public void pathTracerSamplesTheSunPerCell() {
        String src = RtShaderCompiler.loadSource(PATH_SHADER);
        int snap = src.indexOf(CELL_SNAP);
        assertTrue(snap >= 0, "the path tracer must take its sun-disk sample from the shadow cell, like rt_rays.comp");
        int guard = src.lastIndexOf("if (b == 0u && !med.inside && shadowPixelSize() > 0.0) {", snap);
        assertTrue(guard >= 0 && snap - guard < 200,
            "the snap must stay gated on the option (shadowPixelSize) and on the ray not being refracted under water, "
                + "where the origin is not snapped either");
        int sample = src.indexOf(DISK_SAMPLE);
        assertTrue(sample > snap, "the cell index must be in place before the first sun-disk sample is drawn from it");
        assertEquals(1, occurrences(src, CELL_SNAP), "one snap per path vertex, not a second one per bounce");
        assertTrue(src.contains("gSunPixel = p;"),
            "the per-pixel default stays: with pixel shadows off the option must not move a single sample");
    }

    @Test
    public void rayPassStillSamplesTheSunPerCell() {
        String src = RtShaderCompiler.loadSource(RAYS_SHADER);
        int snap = src.indexOf(CELL_SNAP);
        assertTrue(snap >= 0, "rt_rays.comp is the pass this pairing was written for; it must keep its cell sample");
        int guard = src.lastIndexOf("if (shadowPixelSize() > 0.0) {", snap);
        assertTrue(guard >= 0 && snap - guard < 120,
            "the cell sample must stay directly behind the option gate: an unconditional snap would move samples with "
                + "pixel shadows off, and a snap inside the camJitter branch below would never run");
    }

    @Test
    public void diskSampleComesFromTheCellIndex() {
        String src = RtShaderCompiler.loadSource(SAMPLING_MODULE);
        String marker = "vec3 sunShadowDirection(vec3 d, float radius, uint i, uint perFrame) {";
        int at = src.indexOf(marker);
        assertTrue(at >= 0, "sunShadowDirection not found, the test would pass vacuously");
        String body = src.substring(at + marker.length(), src.indexOf('}', at));
        assertTrue(body.contains("pixelOffset(gSunPixel)"),
            "the disk point is a function of gSunPixel: that is what makes a cell index give the whole cell one sample");
        assertTrue(body.contains("gSunIndex * perFrame + i"),
            "the sample index must keep advancing per frame and per spp, otherwise the cell never averages the cone");
    }

    private static int occurrences(String source, String needle) {
        int n = 0;
        for (int at = source.indexOf(needle); at >= 0; at = source.indexOf(needle, at + 1)) {
            n++;
        }
        return n;
    }
}
