package io.github.andrexarlay.vulkancompat.rt.world;

import io.github.andrexarlay.vulkancompat.rt.sodium.DecodedSection;
import io.github.andrexarlay.vulkancompat.rt.sodium.SodiumMeshDecoder;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Cutout foliage classification and the native vertex flag contract. */
public class RtVegetationTagTest {


    @Test
    public void tagsOnlyVegetationCutoutQuads() {
        // solid pass: a plant-like block that must stay untagged (only the cutout pass can be foliage)
        // cutout pass: a plant card at (2,3,2), iron bars at (5,6,5), a leaf cube at (11,11,11)
        DecodedSection d = section(
            new float[][] {up(8, 9, 8)},
            new float[][] {up(2, 4, 2), up(5, 7, 5), up(11, 12, 11)});
        int[] out = new int[d.totalQuads()];
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
    }

    @Test
    public void cubeBitNeverComesWithoutTheVegetationBit() {
        // the shader gates the cube step on the foliage flag, but a stray bit 25 must not survive on its own either
        DecodedSection d = section(new float[][] {}, new float[][] {up(2, 4, 2)});
        int[] out = new int[d.totalQuads()];
        assertTrue(RtWorld.tagVegetation(d, out, (x, y, z) -> RtWorld.FOLIAGE_CUBE) > 0);
        assertEquals(BedraySceneGeometry.VERTEX_FLAG_VEGETATION | BedraySceneGeometry.VERTEX_FLAG_LEAF_CUBE, out[0] & (BedraySceneGeometry.VERTEX_FLAG_VEGETATION | BedraySceneGeometry.VERTEX_FLAG_LEAF_CUBE));
        assertEquals(0, RtWorld.tagVegetation(d, new int[d.totalQuads()], (x, y, z) -> RtWorld.FOLIAGE_NONE));
    }

    @Test
    public void taggingLeavesTheDecodedLightUnchanged() {
        DecodedSection d = section(new float[][] {}, new float[][] {up(2, 4, 2)});
        int[] originalLight = d.light.clone();
        int[] out = new int[d.totalQuads()];
        assertEquals(1, RtWorld.tagVegetation(d, out, (x, y, z) -> RtWorld.FOLIAGE_CARD));
        assertBit(out, 0, "native foliage flag");
        assertArrayEquals(originalLight, d.light, "decoded light is shared with the registry");
    }

    @Test
    public void faceCentreResolvesToItsOwnCell() {
        // the centre of a full-block top face lies exactly on the cell boundary (y = 4): a plain floor() would pick the
        // cell above the block, where the block state is the neighbour's, and leaves would lose their tag
        DecodedSection d = section(new float[][] {}, new float[][] {up(2, 4, 2)});
        int[] hits = new int[3];
        RtWorld.tagVegetation(d, new int[d.totalQuads()], (x, y, z) -> {
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
        int[] flags = {BedraySceneGeometry.VERTEX_FLAG_VEGETATION};
        assertEquals(0, RtWorld.tagVegetation(cutout, flags, null));
        assertNoBit(flags, 0, "no block lookup");
    }

    @Test
    public void scratchArrayDoesNotLeakBetweenSections() {
        DecodedSection a = section(new float[][] {}, new float[][] {up(2, 4, 2)});
        DecodedSection b = section(new float[][] {}, new float[][] {up(5, 7, 5)});
        int[] out = new int[a.totalQuads()];
        RtWorld.BlockLookup onlyFirst = (x, y, z) -> x == 2 ? RtWorld.FOLIAGE_CARD : RtWorld.FOLIAGE_NONE;
        assertEquals(1, RtWorld.tagVegetation(a, out, onlyFirst));
        assertBit(out, 0, "first section's foliage");
        assertEquals(0, RtWorld.tagVegetation(b, out, onlyFirst));
        assertNoBit(out, 0, "second section has no foliage");
    }

    @Test
    public void everyFaceResolvesToItsOwnBlock() {
        // top, bottom and both x faces, in four different cells, plus a section in negative coordinates: the 1 cm step against
        // the normal must always land in the cell that owns the face and never in the neighbour
        DecodedSection d = section(new float[][] {},
            new float[][] {up(2, 4, 2), down(7, 3, 7), east(11, 3, 4), west(4, 3, 9)});
        StringBuilder asked = new StringBuilder();
        int tagged = RtWorld.tagVegetation(d, new int[d.totalQuads()], (x, y, z) -> {
            asked.append(x).append(',').append(y).append(',').append(z).append(';');
            return RtWorld.FOLIAGE_CARD;
        });
        assertEquals(4, tagged);
        assertEquals("2,3,2;7,3,7;10,3,4;4,3,9;", asked.toString());

        DecodedSection negative = SodiumMeshDecoder.decode(1L, -1, 0, -2, passes(
            new float[][] {}, new float[][] {up(0, 4, 0)}));
        int[] hit = new int[3];
        RtWorld.tagVegetation(negative, new int[negative.totalQuads()], (x, y, z) -> {
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
        int tagged = RtWorld.tagVegetation(d, new int[d.totalQuads()], (x, y, z) -> {
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
        int[] out = new int[d.totalQuads()];
        assertEquals(1, RtWorld.tagVegetation(d, out, (x, y, z) -> RtWorld.FOLIAGE_CUBE));
        assertBit(out, 0, "a diagonal leaf quad is still foliage");
        assertCubeBit(out, 0, false, "no one-block caster step for a diagonal quad");
    }



    @Test
    public void theTraversalReadsTheNativeVertexFlags() throws java.io.IOException {
        assertEquals(1, BedraySceneGeometry.VERTEX_FLAG_VEGETATION);
        assertEquals(2, BedraySceneGeometry.VERTEX_FLAG_LEAF_CUBE);
        java.nio.file.Path shaders = java.nio.file.Path.of("src/main/resources/shaders/slang");
        String geometry = java.nio.file.Files.readString(shaders.resolve("contracts/geometry.slang"));
        String traversal = java.nio.file.Files.readString(shaders.resolve("traversal/hardware_traversal.slang"));
        assertTrue(geometry.contains("GEOMETRY_VERTEX_FLAG_VEGETATION = 1u << 0u"));
        assertTrue(geometry.contains("GEOMETRY_VERTEX_FLAG_LEAF_CUBE  = 1u << 1u"));
        assertTrue(traversal.contains("(vertexFlags & GEOMETRY_VERTEX_FLAG_VEGETATION) != 0u) result.flags |= HIT_FLAG_FOLIAGE;"));
        assertTrue(traversal.contains("(vertexFlags & GEOMETRY_VERTEX_FLAG_LEAF_CUBE) != 0u) result.flags |= HIT_FLAG_LEAF_CUBE;"));
    }

    private static void assertBit(int[] flags, int quad, String message) {
        assertEquals(BedraySceneGeometry.VERTEX_FLAG_VEGETATION,
            flags[quad] & BedraySceneGeometry.VERTEX_FLAG_VEGETATION, message);
    }

    private static void assertNoBit(int[] flags, int quad, String message) {
        assertEquals(0, flags[quad] & BedraySceneGeometry.VERTEX_FLAG_VEGETATION, message);
    }

    private static void assertCubeBit(int[] flags, int quad, boolean expected, String message) {
        assertEquals(expected ? BedraySceneGeometry.VERTEX_FLAG_LEAF_CUBE : 0,
            flags[quad] & BedraySceneGeometry.VERTEX_FLAG_LEAF_CUBE, message);
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
