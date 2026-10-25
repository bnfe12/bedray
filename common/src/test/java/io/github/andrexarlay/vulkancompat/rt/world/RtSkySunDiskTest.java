package io.github.andrexarlay.vulkancompat.rt.world;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The sun sprite and its two consumers.
 *
 * <p>Owner report 2026-10-24: a large flat white square in a blue sky with no glow around it. Vanilla 26.3
 * {@code textures/environment/celestial/sun.png} is 32x32, palette encoded, alpha 255 in every texel, black in the corners:
 * a round halo of (36,36,14) around an 8x8 core of (255,255,217). The shader's {@code core} mask dropped everything below
 * peak 0.42, which is the whole halo, so only the square core survived; the old comment blaming the halo for a "giant solid
 * beige box" described the missing-texture fallback, whose colour is exactly (1.0, 0.95, 0.78).</p>
 *
 * <p>The mask is ported here so the numbers are checked and not just the source text: the halo texel must reach the frame,
 * the corners must stay black (the sprite is opaque, so anything but black there would draw the quad), and the body mask
 * that the specular path uses must stay compact. The sprite values above come from decoding the vanilla asset; no image is
 * read by this test, so what is pinned here is the shading math and the shader text, not the PNG.</p>
 */
public class RtSkySunDiskTest {

    private static final String SKY_MODULE = "/shaders/rt_sky.glsl";

    /** Halo texel of sun.png, x = 16, y = 6: the outer ring of the disc. */
    private static final float[] HALO = {36 / 255f, 36 / 255f, 14 / 255f, 1f};
    /** Core texel, x = 16, y = 16. */
    private static final float[] CORE = {1f, 1f, 217 / 255f, 1f};
    private static final float[] CORNER = {0f, 0f, 0f, 1f};

    @Test
    public void haloIsPartOfTheSun() {
        // box: the quad coordinate of the sampled point, -1..1 from the corners to the centre of an edge
        float[] halo = shadeAt(HALO, 0.55f);
        assertTrue(halo[0] > 0.1f, "the round halo must survive the mask, it is the glow: " + halo[0]);
        assertEquals(HALO[0], halo[0], 1e-4f, "inside the quad the sprite colour is passed through unchanged");
        assertEquals(0.0f, halo[3], 0f, "but the halo is not the sun body: the specular disk must stay as compact as before");

        float[] core = shadeAt(CORE, 0.25f);
        assertEquals(1.0f, core[0], 1e-4f, "the core keeps its full brightness");
        assertEquals(1.0f, core[3], 1e-4f, "the core is the body");
    }

    @Test
    public void theQuadHasNoSquareEdge() {
        // sun.png is opaque edge to edge, so a square would appear wherever the sprite is not black. Its corners are, and this
        // keeps a future re-encode of the texture from turning the quad into a box without anyone noticing.
        float[] corner = shadeAt(CORNER, 0.95f);
        assertEquals(0.0f, corner[0], 0f, "the corner texel is black: it adds nothing and leaves no edge");
        assertEquals(0.0f, corner[3], 0f);
        // the border is feathered by hand because the alpha channel cannot do it
        assertTrue(shadeAt(CORE, 0.995f)[0] < 0.5f, "the outermost texels fade out instead of cutting the quad");
        assertEquals(1.0f, shadeAt(CORE, 0.80f)[0], 1e-6f, "the feather starts at the border only");
    }

    @Test
    public void fallbackWithoutTextureIsRound() {
        // A resource pack without the file (or a server-side pack override) falls back to a plain disc. The old fallback was a
        // box mask, which is the "giant solid beige box" the removed comment was actually about.
        assertEquals(1.0f, fallbackMask(0f, 0f), 1e-6f, "the centre is lit");
        assertEquals(1.0f, fallbackMask(0.15f, 0f), 1e-6f, "a disc has no corner geometry");
        assertEquals(0.0f, fallbackMask(0.75f, 0.75f), 1e-6f, "the diagonal is outside the disc of radius 0.95");
        assertTrue(fallbackMask(0.65f, 0f) > 0f, "but the horizontal edge still is not");
    }

    @Test
    public void shaderReturnsTheSpriteAndKeepsABodyMask() {
        String src = RtShaderCompiler.loadSource(SKY_MODULE);
        assertFalse(src.contains("smoothstep(0.42, 0.85, peak) * (1.0 - smoothstep(0.55, 0.98, box))"),
            "the old mask multiplied the whole sprite by the core: peak 0.42 is above the halo's 0.14, so the glow was erased");
        assertTrue(src.contains("return vec4(t.rgb * opacity, core * opacity);"),
            "rgb carries the sprite with its texture opacity (halo included), alpha carries the body mask");
        // the halo is round: a box (max of the two axes) metric would fade the disc into a square while the radius does not
        assertFalse(src.contains("float box = max(abs(q.x), abs(q.y));"), "the feather must follow the round halo, not the quad");
        // the display composite is additive like vanilla: multiplying by the body mask would erase the halo again
        assertTrue(src.contains("c += sun.rgb * rain;"), "the sun sprite is composited by its own colour");
        assertFalse(src.contains("c += sun.rgb * sun.a * rain;"), "the body mask must not multiply the premultiplied sprite");
        assertTrue(src.contains("c += vec3(skyStars(dir) * sb2 * (1.0 - sun.a));"),
            "the sun body attenuates stars by its opacity; the added halo does not occlude them");
        assertTrue(src.contains("return pc.keyColor.rgb * (bodyLuminance * 6.0);"),
            "sunDisk keeps one compact highlight with texture opacity applied once");
    }

    // ---- ports of the shader math ------------------------------------------------------------------------------------------
    // The edges are read out of rt_sky.glsl, so these tests check the shipped numbers and not a copy of them that can drift.

    private static final float[] FEATHER_EDGES = smoothstepEdges("float feather = 1.0 - smoothstep(");
    private static final float[] CORE_EDGES = smoothstepEdges("float core = smoothstep(");
    private static final float[] DISC_EDGES = smoothstepEdges("float m = (1.0 - smoothstep(");

    private static float smoothstep(float edge0, float edge1, float x) {
        float t = Math.max(0f, Math.min(1f, (x - edge0) / (edge1 - edge0)));
        return t * t * (3f - 2f * t);
    }

    /** The two numeric edges of the smoothstep that follows {@code marker} in the sky module. */
    private static float[] smoothstepEdges(String marker) {
        String src = RtShaderCompiler.loadSource(SKY_MODULE);
        int at = src.indexOf(marker);
        if (at < 0) {
            throw new IllegalStateException("rt_sky.glsl no longer contains \"" + marker + "\"");
        }
        java.util.regex.Matcher m = java.util.regex.Pattern
            .compile("^\\s*([0-9]*\\.?[0-9]+)\\s*,\\s*([0-9]*\\.?[0-9]+)\\s*,")
            .matcher(src.substring(at + marker.length()));
        if (!m.find()) {
            throw new IllegalStateException("no two smoothstep edges after \"" + marker + "\"");
        }
        return new float[] {Float.parseFloat(m.group(1)), Float.parseFloat(m.group(2))};
    }

    /** rgb (index 0..2) and body mask (index 3) of {@code skySunTexel} for a sprite texel sampled at quad radius. */
    private static float[] shadeAt(float[] texel, float radius) {
        float feather = 1f - smoothstep(FEATHER_EDGES[0], FEATHER_EDGES[1], radius);
        float peak = Math.max(texel[0], Math.max(texel[1], texel[2]));
        float core = smoothstep(CORE_EDGES[0], CORE_EDGES[1], peak);
        float opacity = texel[3] * feather;
        return new float[] {texel[0] * opacity, texel[1] * opacity, texel[2] * opacity, core * opacity};
    }

    private static float fallbackMask(float qx, float qy) {
        float radius = (float) Math.hypot(qx, qy);
        float feather = 1f - smoothstep(FEATHER_EDGES[0], FEATHER_EDGES[1], radius);
        return (1f - smoothstep(DISC_EDGES[0], DISC_EDGES[1], radius)) * feather;
    }
}
