package io.github.andrexarlay.vulkancompat.rt.sodium;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Random;

/**
 * L2 checks of the CPU decoder and the registry on synthetic vertices. No GPU, no Sodium classes. Called from ShimTestRunner.
 * The encoder below is a copy of the formulas of CompactChunkVertex (Sodium 0.9.2), used only to build test input.
 */
public final class SodiumMeshDecoderChecks {
    private SodiumMeshDecoderChecks() {
    }

    public static void runAll() {
        knownVertexHandBuilt();
        uvColorLightWords();
        quadToTriangles();
        normalsOfAxisQuads();
        extremeQuantization();
        randomRoundTrip();
        passLayoutAndErrors();
        registryAccounting();
    }

    // ---- test encoder (mirror of Sodium's CompactChunkVertex) ----

    private static int quantizePosition(float position) {
        return (int) (((8.0f + position) / 32.0f) * 1048576.0f) & 1048575;
    }

    private static void putVertex(ByteBuffer b, float x, float y, float z, int color, int w3, int w4) {
        int qx = quantizePosition(x);
        int qy = quantizePosition(y);
        int qz = quantizePosition(z);
        b.putInt((qx >>> 10 & 1023) | (qy >>> 10 & 1023) << 10 | (qz >>> 10 & 1023) << 20);
        b.putInt((qx & 1023) | (qy & 1023) << 10 | (qz & 1023) << 20);
        b.putInt(color);
        b.putInt(w3);
        b.putInt(w4);
    }

    private static ByteBuffer quadBuffer(int quads) {
        return ByteBuffer.allocate(quads * SodiumMeshDecoder.QUAD_BYTES).order(ByteOrder.LITTLE_ENDIAN);
    }

    private static ByteBuffer[] single(int pass, ByteBuffer data) {
        ByteBuffer[] a = new ByteBuffer[DecodedSection.PASS_COUNT];
        a[pass] = data;
        return a;
    }

    private static void putQuad(ByteBuffer b, float[] p) {
        for (int v = 0; v < 4; v++) {
            putVertex(b, p[v * 3], p[v * 3 + 1], p[v * 3 + 2], 0xFFFFFFFF, 0, 0x00000000);
        }
    }

    private static void check(boolean cond, String msg) {
        if (!cond) {
            throw new AssertionError(msg);
        }
    }

    private static void near(float expected, float actual, float eps, String msg) {
        if (Math.abs(expected - actual) > eps) {
            throw new AssertionError(msg + ": expected " + expected + " actual " + actual);
        }
    }

    // ---- checks ----

    /** Words built by hand from the documented layout, independent of the test encoder. */
    private static void knownVertexHandBuilt() {
        // x = 3.5  -> q = (3.5+8)/32*2^20 = 376832 = hi 368, lo 0
        // y = 0.0  -> q = 262144 = hi 256, lo 0
        // z = 16.0 -> q = 786432 = hi 768, lo 0
        int w0 = 368 | (256 << 10) | (768 << 20);
        int w1 = 0;
        near(3.5f, SodiumMeshDecoder.decodePosition(w0, w1, 0), 0f, "x");
        near(0.0f, SodiumMeshDecoder.decodePosition(w0, w1, 1), 0f, "y");
        near(16.0f, SodiumMeshDecoder.decodePosition(w0, w1, 2), 0f, "z");
        // lo bits: add 5 to x lo, 7 to y lo, 9 to z lo -> +q of 5,7,9
        int w1b = 5 | (7 << 10) | (9 << 20);
        near(3.5f + 5 * SodiumMeshDecoder.VERTEX_SCALE, SodiumMeshDecoder.decodePosition(w0, w1b, 0), 1e-6f, "x lo");
        near(0.0f + 7 * SodiumMeshDecoder.VERTEX_SCALE, SodiumMeshDecoder.decodePosition(w0, w1b, 1), 1e-6f, "y lo");
        near(16.0f + 9 * SodiumMeshDecoder.VERTEX_SCALE, SodiumMeshDecoder.decodePosition(w0, w1b, 2), 1e-6f, "z lo");
    }

    private static void uvColorLightWords() {
        ByteBuffer b = quadBuffer(1);
        int w3a = 0xC000;                 // u15 = 0x4000 (0.5), bias bit set; v = 0
        int w3b = 0x7FFF0000 | 0x0001;    // v15 = 32767, u15 = 1
        int color = 0x44332211;           // memory order: R=0x11 G=0x22 B=0x33 A=0x44
        int w4 = 0xAA073010;              // block 0x10, sky 0x30, material 0x07, section index 0xAA (dropped)
        putVertex(b, 0, 0, 0, color, w3a, w4);
        putVertex(b, 1, 0, 0, color, w3b, w4);
        putVertex(b, 1, 1, 0, color, w3a, w4);
        putVertex(b, 0, 1, 0, color, w3b, w4);
        DecodedSection s = SodiumMeshDecoder.decode(1L, 0, 0, 0, single(0, b));
        near(0.5f, s.uvs[0], 0f, "u bias bit ignored");
        near(0.0f, s.uvs[1], 0f, "v");
        near(1.0f / 32768.0f, s.uvs[2], 0f, "u15=1");
        near(32767.0f / 32768.0f, s.uvs[3], 0f, "v15=32767");
        check(s.colors[0] == color, "color word kept");
        check((s.colors[0] & 0xFF) == 0x11, "R is byte 0");
        check(s.light[0] == 0x073010, "light word without section index: " + Integer.toHexString(s.light[0]));
        check((s.light[0] & 0xFF) == 0x10 && ((s.light[0] >> 8) & 0xFF) == 0x30, "block then sky");
    }

    private static void quadToTriangles() {
        int[] idx = new int[12];
        SodiumMeshDecoder.fillTriangleIndices(idx, 2, 0);
        int[] expected = {0, 1, 2, 2, 3, 0, 4, 5, 6, 6, 7, 4};
        check(java.util.Arrays.equals(expected, idx), "template (0,1,2,2,3,0): " + java.util.Arrays.toString(idx));
        int[] idx2 = new int[6];
        SodiumMeshDecoder.fillTriangleIndices(idx2, 1, 100);
        check(java.util.Arrays.equals(new int[]{100, 101, 102, 102, 103, 100}, idx2), "base vertex offset");
        boolean threw = false;
        try {
            SodiumMeshDecoder.fillTriangleIndices(new int[5], 1, 0);
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        check(threw, "too small dst rejected");
    }

    private static void normalsOfAxisQuads() {
        // Counter-clockwise when looking at the face from outside (Minecraft winding).
        float[][] quads = {
                {0, 1, 0, 0, 1, 1, 1, 1, 1, 1, 1, 0},   // UP
                {0, 0, 1, 0, 0, 0, 1, 0, 0, 1, 0, 1},   // DOWN
                {1, 0, 0, 1, 1, 0, 1, 1, 1, 1, 0, 1},   // +X
                {0, 0, 0, 0, 0, 1, 0, 1, 1, 0, 1, 0},   // -X
                {0, 0, 1, 1, 0, 1, 1, 1, 1, 0, 1, 1},   // +Z
                {1, 0, 0, 0, 0, 0, 0, 1, 0, 1, 1, 0},   // -Z
        };
        float[][] expected = {{0, 1, 0}, {0, -1, 0}, {1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}};
        String[] names = {"UP", "DOWN", "+X", "-X", "+Z", "-Z"};
        ByteBuffer b = quadBuffer(quads.length);
        for (float[] q : quads) {
            putQuad(b, q);
        }
        DecodedSection s = SodiumMeshDecoder.decode(2L, 0, 0, 0, single(1, b));
        check(s.totalQuads() == 6 && s.quadsInPass(1) == 6, "6 cutout quads");
        for (int i = 0; i < 6; i++) {
            for (int a = 0; a < 3; a++) {
                near(expected[i][a], s.normals[i * 3 + a], 1e-6f, "normal " + names[i] + " axis " + a);
            }
        }
        // Block-top quad of a full block in the section: positions stay in [0,16]
        ByteBuffer top = quadBuffer(1);
        putQuad(top, new float[]{0, 16, 0, 0, 16, 16, 16, 16, 16, 16, 16, 0});
        DecodedSection t = SodiumMeshDecoder.decode(3L, 5, 6, 7, single(0, top));
        near(0f, t.posMin[0], 0f, "min x");
        near(16f, t.posMax[1], 0f, "max y");
        near(1f, t.normals[1], 1e-6f, "top normal +Y");
        check(t.sectionX == 5 && t.sectionY == 6 && t.sectionZ == 7, "section coords");
    }

    private static void extremeQuantization() {
        near(-8.0f, SodiumMeshDecoder.decodePosition(0, 0, 0), 0f, "q=0");
        int allOnes = 0x3FFFFFFF;
        float max = SodiumMeshDecoder.decodePosition(allOnes, allOnes, 2);
        near(24.0f - 32.0f / (1 << 20), max, 1e-5f, "q=2^20-1");
        check(max < 24.0f, "below 24");
        check(SodiumMeshDecoder.quantizedPosition(allOnes, allOnes, 1) == (1 << 20) - 1, "20 bits");
        near(32767.0f / 32768.0f, SodiumMeshDecoder.decodeU(0xFFFF), 0f, "u max, bias bit dropped");
        near(32767.0f / 32768.0f, SodiumMeshDecoder.decodeV(0xFFFF0000), 0f, "v max, bias bit dropped");
    }

    private static void randomRoundTrip() {
        Random r = new Random(12345);
        ByteBuffer b = quadBuffer(64);
        float[] expect = new float[64 * 12];
        for (int q = 0; q < 64; q++) {
            for (int v = 0; v < 4; v++) {
                float x = r.nextFloat() * 16f;
                float y = r.nextFloat() * 16f;
                float z = r.nextFloat() * 16f;
                expect[q * 12 + v * 3] = x;
                expect[q * 12 + v * 3 + 1] = y;
                expect[q * 12 + v * 3 + 2] = z;
                putVertex(b, x, y, z, 0, 0, 0);
            }
        }
        DecodedSection s = SodiumMeshDecoder.decode(4L, 0, 0, 0, single(2, b));
        float tol = SodiumMeshDecoder.VERTEX_SCALE * 1.01f; // one quantization step (truncation in the encoder)
        for (int i = 0; i < expect.length; i++) {
            check(Math.abs(expect[i] - s.positions[i]) <= tol, "round trip index " + i + ": " + expect[i] + " vs " + s.positions[i]);
        }
        check(s.quadsInPass(2) == 64 && s.passQuadStart(2) == 0, "translucent pass");
    }

    private static void passLayoutAndErrors() {
        ByteBuffer solid = quadBuffer(1);
        putQuad(solid, new float[]{0, 0, 0, 1, 0, 0, 1, 1, 0, 0, 1, 0});
        ByteBuffer cutout = quadBuffer(2);
        putQuad(cutout, new float[]{0, 0, 0, 1, 0, 0, 1, 1, 0, 0, 1, 0});
        putQuad(cutout, new float[]{0, 0, 0, 1, 0, 0, 1, 1, 0, 0, 1, 0});
        ByteBuffer[] in = {solid, cutout, null};
        int posBefore = solid.position();
        DecodedSection s = SodiumMeshDecoder.decode(5L, 1, 2, 3, in);
        check(solid.position() == posBefore, "input buffer position untouched");
        check(s.quadsInPass(0) == 1 && s.quadsInPass(1) == 2 && s.quadsInPass(2) == 0, "per pass counts");
        check(s.passQuadStart(0) == 0 && s.passQuadStart(1) == 1 && s.passQuadStart(2) == 3, "pass starts");
        check(s.totalQuads() == 3 && s.positions.length == 36 && s.uvs.length == 24 && s.normals.length == 9, "array sizes");

        DecodedSection empty = SodiumMeshDecoder.decode(6L, 0, 0, 0, new ByteBuffer[3]);
        check(empty.isEmpty() && empty.positions.length == 0, "empty section");

        boolean threw = false;
        try {
            SodiumMeshDecoder.decode(7L, 0, 0, 0, single(0, ByteBuffer.allocate(81)));
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        check(threw, "length not a multiple of 80 rejected");
    }

    private static void registryAccounting() {
        SectionGeometryRegistry reg = new SectionGeometryRegistry();
        ByteBuffer one = quadBuffer(1);
        putQuad(one, new float[]{0, 0, 0, 1, 0, 0, 1, 1, 0, 0, 1, 0});
        ByteBuffer three = quadBuffer(3);
        for (int i = 0; i < 3; i++) {
            putQuad(three, new float[]{0, 0, 0, 1, 0, 0, 1, 1, 0, 0, 1, 0});
        }
        final int[] added = {0};
        final int[] removed = {0};
        reg.addListener(new SectionGeometryRegistry.Listener() {
            @Override
            public void onSectionAdded(DecodedSection section, DecodedSection previous) {
                added[0]++;
            }

            @Override
            public void onSectionRemoved(long key, DecodedSection old) {
                removed[0]++;
            }
        });
        reg.submit(SodiumMeshDecoder.decode(10L, 0, 0, 0, single(0, one)));
        reg.submit(SodiumMeshDecoder.decode(11L, 1, 0, 0, single(1, three)));
        check(reg.sectionCount() == 2 && reg.totalQuads() == 4, "two sections, 4 quads");
        check(reg.passQuads(0) == 1 && reg.passQuads(1) == 3 && reg.passQuads(2) == 0, "per pass totals");
        // replace section 10 with 3 translucent quads
        reg.submit(SodiumMeshDecoder.decode(10L, 0, 0, 0, single(2, three)));
        check(reg.sectionCount() == 2 && reg.totalQuads() == 6 && reg.passQuads(0) == 0 && reg.passQuads(2) == 3, "replace");
        check(reg.addedCount() == 2 && reg.replacedCount() == 1, "added/replaced counters");
        // empty result removes
        reg.submit(SodiumMeshDecoder.decode(11L, 1, 0, 0, new ByteBuffer[3]));
        check(reg.sectionCount() == 1 && reg.removedCount() == 1 && reg.totalQuads() == 3, "empty submit removes");
        reg.remove(999L);
        check(reg.removedCount() == 1, "unknown key is a no-op");
        reg.remove(10L);
        check(reg.sectionCount() == 0 && reg.totalQuads() == 0 && reg.removedCount() == 2, "remove");
        check(added[0] == 3 && removed[0] == 2, "listener events: " + added[0] + "/" + removed[0]);
        reg.recordFailure();
        String line = reg.statsLine();
        check(line.equals("[VKB-RT] sections=0 quads=0 solid=0 cutout=0 translucent=0 added=2 removed=2 replaced=1 failed=1"),
                "stats line: " + line);
    }
}
