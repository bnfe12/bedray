package io.github.andrexarlay.vulkancompat.rt.world;

import io.github.andrexarlay.vulkancompat.rt.sodium.DecodedSection;
import io.github.andrexarlay.vulkancompat.rt.sodium.SodiumMeshDecoder;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link SectionVegetation} owns the per-quad foliage classification that the staging path ({@code quadFlags}) and the
 * word-based contract ({@code tagVegetation}) used to duplicate. These tests pin the owner directly: {@code classify}
 * writes one bit-set per cutout quad into a caller-zeroed array, {@code tag} ORs those bits into a copy of the light
 * words, and neither touches the shared decoded section. The bit values are the canonical light-word flags the RT
 * shaders read, so they are asserted as numbers here too.
 */
public class SectionVegetationTest {

    @Test
    public void constantsAreTheCanonicalLightWordBits() {
        assertEquals(0x01000000, SectionVegetation.VEGETATION_BIT, "bit 24 of the light word");
        assertEquals(0x02000000, SectionVegetation.LEAF_CUBE_BIT, "bit 25 of the light word");
        assertEquals(0, SectionVegetation.FOLIAGE_NONE);
        assertEquals(1, SectionVegetation.FOLIAGE_CARD);
        assertEquals(2, SectionVegetation.FOLIAGE_CUBE);
    }

    @Test
    public void classifyWritesOneBitSetPerCutoutQuad() {
        // solid plant-like block stays untagged; cutout card, iron bars and a leaf cube in three different cells
        DecodedSection d = section(
            new float[][] {up(8, 9, 8)},
            new float[][] {up(2, 4, 2), up(5, 7, 5), up(11, 12, 11)});
        int[] bits = new int[d.totalQuads()];
        int tagged = SectionVegetation.classify(d, (x, y, z) -> {
            if (x == 2 && y == 3 && z == 2) {
                return SectionVegetation.FOLIAGE_CARD;
            }
            if (x == 11 && y == 11 && z == 11) {
                return SectionVegetation.FOLIAGE_CUBE;
            }
            return SectionVegetation.FOLIAGE_NONE;
        }, bits);
        assertEquals(2, tagged, "two cutout quads belong to a plant");
        assertEquals(0, bits[0], "a solid quad is never foliage");
        assertEquals(SectionVegetation.VEGETATION_BIT, bits[1], "the plant card");
        assertEquals(0, bits[2], "iron bars are not foliage");
        assertEquals(SectionVegetation.VEGETATION_BIT | SectionVegetation.LEAF_CUBE_BIT, bits[3], "the leaf cube");
    }

    @Test
    public void classifyCachesTheLookupPerCell() {
        // four faces of one leaf cube share a cell: the level lookup must run once, not four times
        DecodedSection d = section(new float[][] {}, new float[][] {
            up(2, 4, 2), down(2, 3, 2), east(3, 3, 2), west(2, 3, 2)});
        int[] calls = new int[1];
        int[] bits = new int[d.totalQuads()];
        int tagged = SectionVegetation.classify(d, (x, y, z) -> {
            calls[0]++;
            return SectionVegetation.FOLIAGE_CUBE;
        }, bits);
        assertEquals(4, tagged, "every face of the leaf is still tagged");
        assertEquals(1, calls[0], "one block state query for the four faces of one cell");
    }

    @Test
    public void classifyCubeBitNeedsAnAxisAlignedFace() {
        // a leaf block drawn as a slanted card: the block class says cube, the geometry says card
        DecodedSection d = section(new float[][] {}, new float[][] {diagonal(2, 4, 2)});
        int[] bits = new int[d.totalQuads()];
        assertEquals(1, SectionVegetation.classify(d, (x, y, z) -> SectionVegetation.FOLIAGE_CUBE, bits));
        assertEquals(SectionVegetation.VEGETATION_BIT, bits[0], "a diagonal leaf quad is foliage but not a full cube");
    }

    @Test
    public void classifyResolvesEachFaceToItsOwnCell() {
        // the 1 cm step against the normal must land in the cell that owns the face, including negative section origins
        DecodedSection d = section(new float[][] {},
            new float[][] {up(2, 4, 2), down(7, 3, 7), east(11, 3, 4), west(4, 3, 9)});
        StringBuilder asked = new StringBuilder();
        int[] bits = new int[d.totalQuads()];
        assertEquals(4, SectionVegetation.classify(d, (x, y, z) -> {
            asked.append(x).append(',').append(y).append(',').append(z).append(';');
            return SectionVegetation.FOLIAGE_CARD;
        }, bits));
        assertEquals("2,3,2;7,3,7;10,3,4;4,3,9;", asked.toString());

        DecodedSection negative = SodiumMeshDecoder.decode(1L, -1, 0, -2, passes(
            new float[][] {}, new float[][] {up(0, 4, 0)}));
        int[] hit = new int[3];
        SectionVegetation.classify(negative, (x, y, z) -> {
            hit[0] = x;
            hit[1] = y;
            hit[2] = z;
            return SectionVegetation.FOLIAGE_CARD;
        }, new int[negative.totalQuads()]);
        assertEquals(-16, hit[0]);
        assertEquals(3, hit[1]);
        assertEquals(-32, hit[2], "the section origin must not wrap around zero");
    }

    @Test
    public void tagCopiesLightAndOrsBitsWithoutTouchingTheSection() {
        DecodedSection d = section(new float[][] {}, new float[][] {up(2, 4, 2)});
        int[] out = new int[d.totalQuads() * 4];
        int tagged = SectionVegetation.tag(d, out, (x, y, z) -> SectionVegetation.FOLIAGE_CARD);
        assertEquals(1, tagged);
        for (int v = 0; v < 4; v++) {
            assertEquals(SectionVegetation.VEGETATION_BIT, out[v] & SectionVegetation.VEGETATION_BIT, "vertex " + v);
            assertEquals(d.light[v] & 0x00FFFFFF, out[v] & 0x00FFFFFF, "the vanilla light bytes stay as decoded");
        }
        for (int v = 0; v < d.totalQuads() * 4; v++) {
            assertEquals(0, d.light[v] & (SectionVegetation.VEGETATION_BIT | SectionVegetation.LEAF_CUBE_BIT),
                "the decoded section is shared with the registry and must not be modified");
        }
    }

    @Test
    public void tagRejectsAnUndersizedOutput() {
        DecodedSection d = section(new float[][] {}, new float[][] {up(2, 4, 2)});
        int[] tooSmall = new int[d.totalQuads() * 4 - 1];
        assertThrows(IllegalArgumentException.class,
            () -> SectionVegetation.tag(d, tooSmall, (x, y, z) -> SectionVegetation.FOLIAGE_CARD));
    }

    // ---- synthetic section builders (compact vertex layout, see SodiumMeshDecoder) ----

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
                b.putInt((int) (p[0] * 16.0f) & 0xFF);
            }
        }
        return b;
    }

    private static int quantize(float position) {
        return (int) (((8.0f + position) / 32.0f) * 1048576.0f) & 1048575;
    }
}