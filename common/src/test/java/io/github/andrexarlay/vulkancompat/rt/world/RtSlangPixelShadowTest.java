package io.github.andrexarlay.vulkancompat.rt.world;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The "Pixel Shadows" look option evaluates the sun/moon shadow once per world cell instead of once per pixel: the
 * receiver moves to the centre of its cell, and one whole cell shares one shadow ray, so the edge is the staircase of
 * the block texture the cell is measured in. The deleted {@code rt_common_pixel_shadow.glsl} owned the whole policy and
 * three rules kept it from becoming a cheat, all three of which are pinned here:
 *
 * <ul>
 *   <li>only the receiver moves - never the caster and never the light term;</li>
 *   <li>a cell that crosses geometry keeps the exact shadow, decided by one validation ray;</li>
 *   <li>geometry outside the block grid - a mob, a leaf card - is never snapped.</li>
 * </ul>
 *
 * <p>The second half guards the world-locked disc rotation that keeps the quantised penumbra from filling with per-pixel
 * speckle, and the push-word packing that revives the setting: the host had been writing {@code StatsBuf.shadowPixel}
 * with no Slang reader at all, so the option was dead in the migrated renderer.
 */
class RtSlangPixelShadowTest {

    private static final String TRACER = "src/main/resources/shaders/slang/pathtrace/path_tracer.slang";
    private static final String ENTRY = "src/main/resources/shaders/slang/pathtrace/path_trace_entry.slang";
    private static final String SUN = "src/main/resources/shaders/slang/light/sun.slang";
    private static final String LIGHT_CONTRACT = "src/main/resources/shaders/slang/contracts/light.slang";
    private static final String PASS_HOST =
        "src/main/java/io/github/andrexarlay/vulkancompat/rt/world/RtPathTraceSlangPass.java";
    private static final String SHADER_HOST =
        "src/main/java/io/github/andrexarlay/vulkancompat/rt/world/RtSlangPathPass.java";

    // Mirrors of the shader constants.  Duplicate literals on purpose: if either side drifts, the mirror stops
    // describing what ships and the numeric tests below stop meaning anything.
    private static final int OPTION_PIXEL_SHADOWS = 1 << 7;
    private static final int PIXEL_SHADOW_SIZE_SHIFT = 8;
    private static final int MAX_TEXELS = 16;

    // ---- the snap's arithmetic, mirrored from path_tracer.slang ----

    private static float cellBlocks(int texels) {
        return texels / 16.0f;
    }

    private static float cellCentre(float world, float cell) {
        return (float) ((Math.floor(world / cell) + 0.5) * cell);
    }

    /** mirror of the lateral vector before the plane projection, with y always zero. */
    private static float[] lateral(float x, float z, float cell) {
        return new float[] {cellCentre(x, cell) - x, 0.0f, cellCentre(z, cell) - z};
    }

    /** mirror of {@code lateral -= normal * dot(lateral, normal)}. */
    private static float[] project(float[] lateral, float[] normal) {
        float dot = lateral[0] * normal[0] + lateral[1] * normal[1] + lateral[2] * normal[2];
        return new float[] {lateral[0] - normal[0] * dot, lateral[1] - normal[1] * dot,
            lateral[2] - normal[2] * dot};
    }

    private static float length(float[] v) {
        return (float) Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
    }

    /** mirror of the eligibility predicate in {@code pathTraceDirectTerm}. */
    private static boolean snaps(boolean primary, float cell, float leafBack, boolean inMedium, boolean entity) {
        return primary && cell > 0.0f && leafBack <= 0.0f && !inMedium && !entity;
    }

    // ---- the rotation's arithmetic, mirrored from path_tracer.slang ----

    private static int pcgHash(int value) {
        int state = value * 747796405 + (int) 2891336453L;
        int word = ((state >>> ((state >>> 28) + 4)) ^ state) * 277803737;
        return (word >>> 22) ^ word;
    }

    private static int sunSeed(int cx, int cy, int cz, int frame, int sampleWord) {
        int seed = cx * 1973 + cy * 9277 + cz * 4093;
        return pcgHash(pcgHash(seed + frame * 26699) + sampleWord * 6971);
    }

    private static int shadowCell(double world, float cell) {
        return (int) Math.floor(world / cell);
    }

    @Test
    void oneCellSharesOneShadowOrigin() {
        float cell = cellBlocks(4);   // four texture pixels = a quarter block
        assertEquals(0.25f, cell, 1e-7f);
        // two fragments of the cell [1.0, 1.25) x [1.0, 1.25) snap to the same centre
        float[] a = lateral(1.01f, 1.02f, cell);
        float[] b = lateral(1.20f, 1.21f, cell);
        assertEquals(1.125f, 1.01f + a[0], 1e-6f, "the cell centre of x");
        assertEquals(1.125f, 1.20f + b[0], 1e-6f, "a second fragment of the same cell lands on the same centre");
        assertEquals(1.125f, 1.02f + a[2], 1e-6f);
        // the next cell over is one cell width away, not a fragment width
        float[] c = lateral(1.26f, 1.26f, cell);
        assertEquals(1.125f + cell, 1.26f + c[0], 1e-6f, "the staircase step is the cell, not the pixel");
        assertTrue(Math.abs(a[0]) < 0.5f * cell + 1e-6f, "no fragment moves further than half a cell");
    }

    @Test
    void theSnapNeverLeavesTheHorizontalPlane() {
        float cell = cellBlocks(1);
        float[] lateral = lateral(3.3f, -7.7f, cell);
        assertEquals(0.0f, lateral[1], 0.0f, "the shared origin keeps the fragment's height: a low sun cannot shove "
            + "the receiver into the ground");
        float[] floor = project(lateral, new float[] {0.0f, 1.0f, 0.0f});
        assertEquals(lateral[0], floor[0], 1e-7f);
        assertEquals(lateral[2], floor[2], 1e-7f);
        assertTrue(read(TRACER).contains("lateral -= normal * dot(lateral, normal);"),
            "the displacement is projected into the surface plane; adding the normal component pushes wall "
                + "fragments sideways into the wall");
    }

    @Test
    void aWallWhoseCellCentreLiesAlongItsNormalKeepsTheExactOrigin() {
        // a fragment one texture pixel off the cell centre of a wall facing +x: all of the lateral is normal, so the
        // projected displacement vanishes and the caller must keep the exact origin instead of snapping into the wall
        float cell = cellBlocks(8);
        float[] normal = {1.0f, 0.0f, 0.0f};
        // z sits on a cell centre, so the whole displacement points along the wall's normal
        float[] lateral = project(lateral(1.01f, 1.25f, cell), normal);
        assertTrue(length(lateral) <= 1.0e-5f, "a normal-only displacement is not a snap: " + length(lateral));
        // a displacement along the wall survives the projection and is still a snap
        float[] raw = lateral(1.125f, 1.02f, cell);
        float[] along = project(raw, normal);
        assertEquals(0.0f, along[0], 1e-6f, "the wall keeps its own normal coordinate");
        assertEquals(raw[2], along[2], 1e-6f, "the tangential part still moves");
        assertTrue(Math.abs(along[2]) > 0.1f, "the tangential displacement must not be lost");
    }

    @Test
    void aBlockedCellKeepsTheExactShadow() {
        String tracer = read(TRACER);
        String origin = block(tracer, tracer.indexOf("float3 pathPixelShadowOrigin<T : ISceneTracer>"));
        assertTrue(origin.contains("Ray probe = makeRay(position + normal * bias, lateral / len, 0.0, len + 0.05);"),
            "the validation ray is the short segment from the fragment to the cell centre (legacy length + 0.05)");
        assertTrue(origin.contains("return traversal.traceVisibility(probe).visible ? position + lateral : position;"),
            "a cell that crosses geometry - a step, a slab, a wall corner - must keep the exact origin, or the whole "
                + "cell lifts above its own surface and shadows detach from their casters");
        assertTrue(origin.contains("return position;   // the fragment already sits on the cell centre"),
            "a fragment on the cell centre has nothing to share");
    }

    @Test
    void onlyThePrimaryReceiverPaysTheValidationRay() {
        String tracer = read(TRACER);
        String term = block(tracer, tracer.indexOf("float3 pathTraceDirectTerm<T : ISceneTracer>"));
        assertTrue(term.contains("if (primary && options.pixelShadowCell > 0.0 && leafBack <= 0.0 && !medium.inside"),
            "the quantisation belongs to the pixel's own surface; deeper vertices are 1-3 spp and must stay exact");
        assertTrue(term.contains("&& (surface.hit.flags & HIT_FLAG_ENTITY) == 0u)"),
            "a mob moves inside a world-locked cell, so its snapped origin would stay behind its shadow");
        assertTrue(term.contains("!medium.inside"),
            "an underwater receiver's beam is refracted; the block cell above it means nothing");
        assertTrue(term.contains("leafBack <= 0.0"),
            "a leaf's shadow ray has to start on its own sun side, which a cell centre cannot guarantee");
        for (String call : new String[] {
            "medium, receiver.primary, reusedTraced", "medium, receiver.primary, keyTraced",
            "medium, receiver.primary, lightTraced"}) {
            assertTrue(tracer.contains(call), "every direct-light call must hand over the primary verdict: " + call);
        }
    }

    @Test
    void theEstimatorAndTheRotationUseTheSameCell() {
        String tracer = read(TRACER);
        String snap = block(tracer, tracer.indexOf("float3 pathPixelShadowOrigin<T : ISceneTracer>"));
        assertTrue(snap.contains("float2 w = worldPosition.xz;"));
        assertTrue(snap.contains("(floor(w.x / cell) + 0.5) * cell - w.x"));
        String cell = block(tracer, tracer.indexOf("int3 pathTraceShadowCell(float3 worldPosition, float cell)"));
        assertTrue(cell.contains("int(floor(worldPosition.x / cell))")
                && cell.contains("int(floor(worldPosition.z / cell))"),
            "the disc rotation has to name the same cell the origin snapped to, or the staircase fills with "
                + "neighbouring cells' penumbrae");
        assertTrue(tracer.contains("pathTraceShadowCell(surface.hit.worldPosition, options.pixelShadowCell)"),
            "the receiver rotation reads the pixel-shadow cell on the primary vertex");
        assertTrue(tracer.contains("pathTraceStableSunCell(surface.hit.worldPosition)"),
            "a vertex that keeps its exact origin still gets a world-locked rotation when the option is on");
        String stable = block(tracer, tracer.indexOf("int3 pathTraceStableSunCell(float3 worldPosition)"));
        assertTrue(stable.contains("float2 sheared = (worldPosition.xz + worldPosition.y * 0.5) * 16.0;"),
            "the y shear decorrelates stacked surfaces, so a floor under a roof does not repeat the roof's pattern");
    }

    @Test
    void aCellSharesOneSunDiskPoint() {
        // same cell, frame and sample stratum: identical seed (and so identical disc point) for every pixel of the cell
        // pathTraceShadowCell returns (x, z, 0), so the mirror names the second word y = 0 and the third z
        int a = sunSeed(shadowCell(1.01, 0.25f), 0, shadowCell(1.02, 0.25f), 7, 3);
        int b = sunSeed(shadowCell(1.20, 0.25f), 0, shadowCell(1.21, 0.25f), 7, 3);
        assertEquals(a, b, "every fragment of the cell draws the same disc point; that is what makes one texel one sample");
        assertNotEquals(a, sunSeed(shadowCell(1.26, 0.25f), 0, shadowCell(1.26, 0.25f), 7, 3),
            "the next cell draws its own point");
        assertNotEquals(a, sunSeed(shadowCell(1.01, 0.25f), 0, shadowCell(1.02, 0.25f), 7, 4),
            "the strata still vary across the samples of one frame, so the penumbra still converges");
        assertNotEquals(a, sunSeed(shadowCell(1.01, 0.25f), 0, shadowCell(1.02, 0.25f), 8, 3),
            "the strata still vary across frames");
    }

    @Test
    void aLeafFreezesToItsBlockAndToItsFrame() {
        String tracer = read(TRACER);
        assertTrue(tracer.contains("if (receiver.foliage) {\n"
                + "            receiver.stableSun = true;\n"
                + "            receiver.sunSeed = pathTraceSunSeed(pathTraceFoliageSunCell(surface.hit.worldPosition), 0u,"),
            "a leaf keeps one disc point of its own block with the frame term frozen: its transmission is a "
                + "0.6-per-layer staircase, and a per-frame re-roll flips whole texels");
        String foliage = block(tracer, tracer.indexOf("int3 pathTraceFoliageSunCell(float3 worldPosition)"));
        assertTrue(foliage.contains("int3(floor(worldPosition))"),
            "a leaf card never leaves its own block, so the block is its rotation");
        // every hit point inside one block shares the rotation, and the next block gets its own
        int leafX = (int) Math.floor(2.3), leafY = (int) Math.floor(64.7), leafZ = (int) Math.floor(-3.2);
        int acrossTheBlock = (int) Math.floor(2.97);
        assertEquals(leafX, acrossTheBlock, "the whole block is one cell");
        assertEquals(sunSeed(leafX, leafY, leafZ, 0, 5), sunSeed(acrossTheBlock, leafY, leafZ, 0, 5),
            "same block, same stratum, same disc point");
        assertNotEquals(sunSeed(leafX, leafY, leafZ, 0, 5), sunSeed(leafX + 1, leafY, leafZ, 0, 5),
            "the neighbouring block draws its own point");
    }

    @Test
    void anOrdinaryReceiverKeepsThePerPathStream() {
        String sun = read(SUN);
        assertTrue(sun.contains("uint drawSeed = receiver.stableSun ? receiver.sunSeed : seed;"),
            "without a stable seed the sampler draws from the path's own stream");
        assertEquals(2, count(sun, "if (!receiver.stableSun) seed = drawSeed;"),
            "both draws (the sun/moon pick and the disc point) must advance the caller's stream exactly as before: "
                + "not consuming it would correlate every draw that follows");
        String contract = read(LIGHT_CONTRACT);
        assertTrue(contract.contains("bool   stableSun;") && contract.contains("uint   sunSeed;"),
            "the rotation travels on the receiver, so shading a secondary ray cannot change the primary's sequence");
        assertTrue(contract.contains("receiver.stableSun = false;") && contract.contains("receiver.sunSeed = 0u;"),
            "a caller that does not opt in keeps the exact per-path sample");
    }

    @Test
    void theHostSendsTheCellSizeInTheSameWord() {
        String host = read(PASS_HOST);
        assertTrue(host.contains("static final int OPTION_PIXEL_SHADOWS = 128;"), "mirror of the shader bit 7");
        assertTrue(host.contains("static final int PIXEL_SHADOW_SIZE_SHIFT = 8;"), "the size lives in bits 8..15");
        assertTrue(host.contains("static final int PIXEL_SHADOW_MAX_SIZE = 16;"), "the settings screen's ceiling");
        String path = read(SHADER_HOST);
        assertTrue(path.contains("| pixelShadowFlags()\n"), "the path trace must receive the option, not just the host");
        assertTrue(path.contains("if (!RtSettings.pixelShadows()) {"), "the option is the settings screen's switch");
        assertTrue(path.contains("Math.max(1, RtSettings.pixelShadowSize())"),
            "a stale zero-sized config must not blank the shadows");

        String tracer = read(TRACER);
        assertTrue(tracer.contains("static const uint PATH_TRACE_OPTION_PIXEL_SHADOWS       = 1u << 7u;"),
            "bit 7 of the push word is the option, above the six ray-profile effect bits");
        assertTrue(tracer.contains("static const uint PATH_TRACE_PIXEL_SHADOW_SIZE_SHIFT    = 8u;"),
            "the cell size rides in bits 8..15 of the same word");
        String entry = read(ENTRY);
        assertTrue(entry.contains("(pc.flags >> PATH_TRACE_PIXEL_SHADOW_SIZE_SHIFT) & 0xFFu) / 16.0"),
            "the shader decodes texture pixels back into blocks");
        assertTrue(entry.contains("options.sampleWord = sampleWord;"),
            "the rotation's strata come from the same sample word the path seed hashed");

        // the packed word round-trips for every size the settings screen can send
        for (int texels = 1; texels <= MAX_TEXELS; texels++) {
            int flags = OPTION_PIXEL_SHADOWS | (texels << PIXEL_SHADOW_SIZE_SHIFT);
            int decoded = (flags >>> PIXEL_SHADOW_SIZE_SHIFT) & 0xFF;
            assertEquals(texels, decoded, "texture pixels survive the push word");
            assertEquals(texels / 16.0f, cellBlocks(decoded), 1e-7f);
        }
        assertEquals(0.0625f, cellBlocks(1), 1e-7f, "size 1 is one pixel of a 16x16 block texture");
        assertEquals(1.0f, cellBlocks(16), 1e-7f, "the widest cell is one block");
        assertEquals(1, OPTION_PIXEL_SHADOWS >>> 7, "bit 7 is the flag");
        assertTrue((OPTION_PIXEL_SHADOWS & 0x3F) == 0, "the flag must not collide with the ray-profile effect bits");
        assertTrue((MAX_TEXELS << 8) <= 0xFFFF, "the size never reaches the reserved half of the word");
    }

    // ---- source helpers ----

    private static String read(String relative) {
        try {
            return Files.readString(Path.of(relative), StandardCharsets.UTF_8).replace("\r\n", "\n");
        } catch (IOException e) {
            throw new AssertionError("cannot read " + relative, e);
        }
    }

    private static String block(String source, int at) {
        int depth = 0;
        for (int i = source.indexOf('{', at); i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return source.substring(at, i + 1);
                }
            }
        }
        throw new AssertionError("unbalanced block at offset " + at);
    }

    private static int count(String source, String needle) {
        int at = 0;
        int hits = 0;
        while (true) {
            int found = source.indexOf(needle, at);
            if (found < 0) {
                return hits;
            }
            hits++;
            at = found + needle.length();
        }
    }
}
