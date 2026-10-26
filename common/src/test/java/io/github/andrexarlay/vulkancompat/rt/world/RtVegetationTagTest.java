package io.github.andrexarlay.vulkancompat.rt.world;

import io.github.andrexarlay.vulkancompat.rt.sodium.DecodedSection;
import io.github.andrexarlay.vulkancompat.rt.sodium.SodiumMeshDecoder;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The leaf transmission of {@code rt_common_surface.glsl} is gated by {@link RtWorld#VEGETATION_BIT}, a per-quad tag the
 * renderer writes from the block state while a section is staged. Before it, every cutout quad (bars, fences, panes,
 * ladders, rails, doors) took the leaf term: each back-lit one glowed warm, and the one-block shadow start of a leaf
 * block could step past a thin pane into a lit cell. The bit must therefore mark exactly the plants and leaves, and it
 * must live in the free byte of the light word without touching the vanilla light.
 *
 * <p>{@link RtWorld#LEAF_CUBE_BIT} then separates a full leaf cube (one block of caster, the shadow ray starts on its
 * sun-side face) from a thin card (1 cm along the sun is enough). An axis-aligned normal cannot make that call: a wall
 * vine has one too.
 */
public class RtVegetationTagTest {

    private static final String CORE = "/shaders/rt_common_core.glsl";
    private static final String SURFACE = "/shaders/rt_common_surface.glsl";

    @Test
    public void tagsOnlyVegetationCutoutQuads() {
        // solid pass: a plant-like block that must stay untagged (only the cutout pass can be foliage)
        // cutout pass: a plant card at (2,3,2), iron bars at (5,6,5), a leaf cube at (11,11,11)
        DecodedSection d = section(
            new float[][] {up(8, 9, 8)},
            new float[][] {up(2, 4, 2), up(5, 7, 5), up(11, 12, 11)});
        int[] out = new int[d.totalQuads() * 4];
        int tagged = RtWorld.tagVegetation(d, out, (x, y, z) -> {
            if (x == 2 && y == 3 && z == 2) {
                return RtWorld.FOLIAGE_CARD;
            }
            if (x == 11 && y == 11 && z == 11) {
                return RtWorld.FOLIAGE_CUBE;
            }
            return x == 8 && y == 8 && z == 8 ? RtWorld.FOLIAGE_CARD : RtWorld.FOLIAGE_NONE;
        });
        assertEquals(2, tagged, "two cutout quads belong to a plant");
        assertBit(out, 1, "cutout quad of the plant");
        assertCubeBit(out, 1, false, "a cross quad is a card, not a full cube");
        assertNoBit(out, 0, "solid quad, even of a plant block");
        assertNoBit(out, 2, "cutout quad of iron bars");
        assertBit(out, 3, "cutout quad of the leaf block");
        assertCubeBit(out, 3, true, "a leaf block is a full cube");
        for (int v = 0; v < d.totalQuads() * 4; v++) {
            assertEquals(0, d.light[v] & (RtWorld.VEGETATION_BIT | RtWorld.LEAF_CUBE_BIT),
                "the decoded section is shared with the registry and must not be modified");
        }
    }

    @Test
    public void cubeBitNeverComesWithoutTheVegetationBit() {
        // the shader gates the cube step on the foliage flag, but a stray bit 25 must not survive on its own either
        DecodedSection d = section(new float[][] {}, new float[][] {up(2, 4, 2)});
        int[] out = new int[d.totalQuads() * 4];
        assertTrue(RtWorld.tagVegetation(d, out, (x, y, z) -> RtWorld.FOLIAGE_CUBE) > 0);
        assertEquals(RtWorld.VEGETATION_BIT | RtWorld.LEAF_CUBE_BIT, out[0] & (RtWorld.VEGETATION_BIT | RtWorld.LEAF_CUBE_BIT));
        assertEquals(0, RtWorld.tagVegetation(d, new int[d.totalQuads() * 4], (x, y, z) -> RtWorld.FOLIAGE_NONE));
    }

    @Test
    public void bitSurvivesTheStagingCopy() {
        DecodedSection d = section(new float[][] {}, new float[][] {up(2, 4, 2)});
        int[] out = new int[d.totalQuads() * 4];
        assertTrue(RtWorld.tagVegetation(d, out, (x, y, z) -> RtWorld.FOLIAGE_CARD) > 0);
        assertEquals(RtWorld.VEGETATION_BIT, out[0] & RtWorld.VEGETATION_BIT);
        assertEquals(d.light[0] & 0x00FFFFFF, out[0] & 0x00FFFFFF,
            "the vanilla light bytes stay as the decoder wrote them");
    }

    @Test
    public void faceCentreResolvesToItsOwnCell() {
        // the centre of a full-block top face lies exactly on the cell boundary (y = 4): a plain floor() would pick the
        // cell above the block, where the block state is the neighbour's, and leaves would lose their tag
        DecodedSection d = section(new float[][] {}, new float[][] {up(2, 4, 2)});
        int[] hits = new int[3];
        RtWorld.tagVegetation(d, new int[d.totalQuads() * 4], (x, y, z) -> {
            hits[0] = x;
            hits[1] = y;
            hits[2] = z;
            return RtWorld.FOLIAGE_CARD;
        });
        assertArrayEquals(new int[] {2, 3, 2}, hits, "the face asking for the block below it");
    }

    @Test
    public void noLookupLeavesEveryQuadUntagged() {
        DecodedSection cutout = section(new float[][] {}, new float[][] {up(2, 4, 2)});
        assertSame(cutout.light, stagedLight(cutout, null),
            "without a lookup no copy is made and the shader sees no foliage at all (the pre-tag behaviour)");
        DecodedSection empty = section(new float[][] {}, new float[][] {});
        assertNull(tagged(empty, (x, y, z) -> RtWorld.FOLIAGE_CARD),
            "a section without cutout quads stages the decoded words");
    }

    @Test
    public void scratchArrayDoesNotLeakBetweenSections() {
        // both sections share the staging scratch array; the second must be a fresh copy of its own light words
        DecodedSection a = section(new float[][] {}, new float[][] {up(2, 4, 2)});
        DecodedSection b = section(new float[][] {}, new float[][] {up(5, 7, 5)});
        int[] out = new int[a.totalQuads() * 4];
        assertTrue(RtWorld.tagVegetation(a, out, (x, y, z) -> x == 2 ? RtWorld.FOLIAGE_CARD : RtWorld.FOLIAGE_NONE) > 0);
        assertEquals(32, out[0] & 0xFF, "block light byte of the quad at x = 2");
        assertTrue(RtWorld.tagVegetation(b, out, (x, y, z) -> x == 5 ? RtWorld.FOLIAGE_CARD : RtWorld.FOLIAGE_NONE) > 0);
        assertEquals(80, out[0] & 0xFF, "the quad at x = 5 must bring its own light, not the previous section's");
        assertEquals(RtWorld.VEGETATION_BIT, out[0] & RtWorld.VEGETATION_BIT);
    }

    @Test
    public void everyFaceResolvesToItsOwnBlock() {
        // top, bottom and both x faces, in four different cells, plus a section in negative coordinates: the 1 cm step against
        // the normal must always land in the cell that owns the face and never in the neighbour
        DecodedSection d = section(new float[][] {},
            new float[][] {up(2, 4, 2), down(7, 3, 7), east(11, 3, 4), west(4, 3, 9)});
        StringBuilder asked = new StringBuilder();
        int tagged = RtWorld.tagVegetation(d, new int[d.totalQuads() * 4], (x, y, z) -> {
            asked.append(x).append(',').append(y).append(',').append(z).append(';');
            return RtWorld.FOLIAGE_CARD;
        });
        assertEquals(4, tagged);
        assertEquals("2,3,2;7,3,7;10,3,4;4,3,9;", asked.toString());

        DecodedSection negative = SodiumMeshDecoder.decode(1L, -1, 0, -2, passes(
            new float[][] {}, new float[][] {up(0, 4, 0)}));
        int[] hit = new int[3];
        RtWorld.tagVegetation(negative, new int[negative.totalQuads() * 4], (x, y, z) -> {
            hit[0] = x;
            hit[1] = y;
            hit[2] = z;
            return RtWorld.FOLIAGE_CARD;
        });
        assertArrayEquals(new int[] {-16, 3, -32}, hit, "the section origin must not wrap around zero");
    }

    @Test
    public void facesOfOneBlockAskOnce() {
        // a leaf cube shows up to six faces in the same cell: the level lookup must run once for them, not six times
        DecodedSection d = section(new float[][] {}, new float[][] {
            up(2, 4, 2), down(2, 3, 2), east(3, 3, 2), west(2, 3, 2)});
        int[] calls = new int[1];
        int tagged = RtWorld.tagVegetation(d, new int[d.totalQuads() * 4], (x, y, z) -> {
            calls[0]++;
            return RtWorld.FOLIAGE_CUBE;
        });
        assertEquals(4, tagged, "every face of the leaf is still tagged");
        assertEquals(1, calls[0], "one block state query for the four faces of one cell");
    }

    @Test
    public void cubeBitNeedsAnAxisAlignedFace() {
        // a resource pack that draws LeavesBlock as a cross: the block class says "cube", the quad geometry says "card", and a
        // one-block step along the diagonal normal would leave the caster cell on the wrong side
        DecodedSection d = section(new float[][] {}, new float[][] {diagonal(2, 4, 2)});
        int[] out = new int[d.totalQuads() * 4];
        assertEquals(1, RtWorld.tagVegetation(d, out, (x, y, z) -> RtWorld.FOLIAGE_CUBE));
        assertBit(out, 0, "a diagonal leaf quad is still foliage");
        assertCubeBit(out, 0, false, "no one-block caster step for a diagonal quad");
    }

    @Test
    public void shaderUsesTheSameBitAsJava() {
        assertEquals(0x01000000, RtWorld.VEGETATION_BIT, "bit 24 of the light word");
        assertEquals(0x02000000, RtWorld.LEAF_CUBE_BIT, "bit 25 of the light word");
        String core = RtShaderCompiler.loadSource(CORE);
        assertTrue(core.contains("const uint VERT_FLAG_VEGETATION = 0x01000000u;"),
            "the GLSL constant must equal RtWorld.VEGETATION_BIT");
        assertTrue(core.contains("const uint VERT_FLAG_LEAF_CUBE = 0x02000000u;"),
            "the GLSL constant must equal RtWorld.LEAF_CUBE_BIT");
        String surface = RtShaderCompiler.loadSource(SURFACE);
        assertTrue(surface.contains("s.isFoliage = (rawLight0 & VERT_FLAG_VEGETATION) != 0u && geomPass == 1u"),
            "foliage must require the per-quad tag, not just the cutout pass");
        assertFalse(surface.contains("s.isFoliage = (geomPass == 1u)"),
            "the render pass alone made bars, panes, ladders and rails glow warm when back-lit");
        assertTrue(surface.contains("s.leafCube = s.isFoliage && (rawLight0 & VERT_FLAG_LEAF_CUBE) != 0u;"),
            "the one-block caster step belongs to full leaf cubes only");
    }

    @Test
    public void casterStepComesFromTheTagNotTheNormal() {
        String surface = RtShaderCompiler.loadSource(SURFACE);
        assertTrue(surface.contains("return s.leafCube && !s.backFace ? s.pos - s.ng * 1.01 : s.pos + L * 0.01;"),
            "a card is cleared by 1 cm; a leaf cube starts on its sun-side face");
        assertFalse(surface.contains("axis < 0.98"),
            "an axis-aligned normal is not a full cube: a wall vine has one too, and the step started inside the wall");
    }

    // ---- synthetic section builders (compact vertex layout, see SodiumMeshDecoder) ----

    private static int[] stagedLight(DecodedSection d, RtWorld.BlockLookup lookup) {
        if (lookup == null) {
            return d.light;
        }
        int[] out = new int[d.totalQuads() * 4];
        return RtWorld.tagVegetation(d, out, lookup) > 0 ? out : d.light;
    }

    private static int[] tagged(DecodedSection d, RtWorld.BlockLookup lookup) {
        int[] out = new int[d.totalQuads() * 4];
        return RtWorld.tagVegetation(d, out, lookup) > 0 ? out : null;
    }

    private static void assertBit(int[] words, int quad, String what) {
        for (int v = 0; v < 4; v++) {
            assertEquals(RtWorld.VEGETATION_BIT, words[quad * 4 + v] & RtWorld.VEGETATION_BIT, what + " vertex " + v);
        }
    }

    private static void assertNoBit(int[] words, int quad, String what) {
        for (int v = 0; v < 4; v++) {
            assertEquals(0, words[quad * 4 + v] & RtWorld.VEGETATION_BIT, what + " vertex " + v);
        }
    }

    private static void assertCubeBit(int[] words, int quad, boolean expected, String what) {
        for (int v = 0; v < 4; v++) {
            int want = expected ? RtWorld.LEAF_CUBE_BIT : 0;
            assertEquals(want, words[quad * 4 + v] & RtWorld.LEAF_CUBE_BIT, what + " vertex " + v);
        }
    }

    /** Upward face of the block below {@code (x, y, z)}: the quad plane is at {@code y}, over that block. */
    private static float[] up(float x, float y, float z) {
        return new float[] {
            x, y, z, x, y, z + 1, x + 1, y, z + 1, x + 1, y, z
        };
    }

    /** Downward face of the block above the plane at {@code y}: its cell is {@code (x, y, z)}. */
    private static float[] down(float x, float y, float z) {
        return new float[] {
            x, y, z, x + 1, y, z, x + 1, y, z + 1, x, y, z + 1
        };
    }

    /** +X face of the block west of the plane at {@code x}: its cell is {@code (x - 1, y, z)}. */
    private static float[] east(float x, float y, float z) {
        return new float[] {
            x, y, z, x, y + 1, z, x, y + 1, z + 1, x, y, z + 1
        };
    }

    /** -X face of the block east of the plane at {@code x}: its cell is {@code (x, y, z)}. */
    private static float[] west(float x, float y, float z) {
        return new float[] {
            x, y, z, x, y, z + 1, x, y + 1, z + 1, x, y + 1, z
        };
    }

    /** Quad with the diagonal normal (-1, 1, 0)/sqrt(2): what a leaf model drawn as a slanted card looks like. */
    private static float[] diagonal(float x, float y, float z) {
        return new float[] {
            x, y, z, x, y, z + 1, x + 1, y + 1, z + 1, x + 1, y + 1, z
        };
    }

    private static DecodedSection section(float[][] solid, float[][] cutout) {
        return SodiumMeshDecoder.decode(1L, 0, 0, 0, passes(solid, cutout));
    }

    private static ByteBuffer[] passes(float[][] solid, float[][] cutout) {
        ByteBuffer[] passes = new ByteBuffer[DecodedSection.PASS_COUNT];
        passes[DecodedSection.PASS_SOLID] = quadBuffer(solid);
        passes[DecodedSection.PASS_CUTOUT] = quadBuffer(cutout);
        return passes;
    }

    private static ByteBuffer quadBuffer(float[][] quads) {
        ByteBuffer b = ByteBuffer.allocate(quads.length * SodiumMeshDecoder.QUAD_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        for (float[] p : quads) {
            for (int v = 0; v < 4; v++) {
                int qx = quantize(p[v * 3]);
                int qy = quantize(p[v * 3 + 1]);
                int qz = quantize(p[v * 3 + 2]);
                b.putInt((qx >>> 10 & 1023) | (qy >>> 10 & 1023) << 10 | (qz >>> 10 & 1023) << 20);
                b.putInt((qx & 1023) | (qy & 1023) << 10 | (qz & 1023) << 20);
                b.putInt(0xFFFFFFFF);
                b.putInt(0);
                // block light byte derived from the first corner x, so a stale copy is visible in the assertions
                b.putInt((int) (p[0] * 16.0f) & 0xFF);
            }
        }
        return b;
    }

    private static int quantize(float position) {
        return (int) (((8.0f + position) / 32.0f) * 1048576.0f) & 1048575;
    }
}
