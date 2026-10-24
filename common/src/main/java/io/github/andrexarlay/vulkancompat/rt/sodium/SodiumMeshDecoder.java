package io.github.andrexarlay.vulkancompat.rt.sodium;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Pure CPU decoder of Sodium 0.9.2 compact chunk vertices (stride 20 bytes, 4 vertices per quad, no index data).
 * No GL/Vulkan/Minecraft/Sodium classes are referenced here, so it is unit-testable.
 *
 * <p>Layout (little-endian words, source: Sodium 0.9.2 decompilation
 * {@code net/caffeinemc/mods/sodium/client/render/chunk/vertex/format/impl/CompactChunkVertex.java}):
 * <pre>
 *  +0  w0  packPositionHi: (x&gt;&gt;&gt;10 &amp; 1023) | (y&gt;&gt;&gt;10 &amp; 1023) &lt;&lt; 10 | (z&gt;&gt;&gt;10 &amp; 1023) &lt;&lt; 20
 *  +4  w1  packPositionLo: (x &amp; 1023) | (y &amp; 1023) &lt;&lt; 10 | (z &amp; 1023) &lt;&lt; 20
 *  +8  w2  color: ColorARGB.mulRGB(vertex.color, ao); vertex.color = ColorARGB.toABGR(...) (BlockRenderer.java:141)
 *  +12 w3  packTexture: (u &amp; 65535) | (v &amp; 65535) &lt;&lt; 16
 *  +16 w4  packLightAndData: (light &amp; 65535) | material &lt;&lt; 16 | sectionIndex &lt;&lt; 24
 * </pre>
 * Reference decoder (Sodium GLSL {@code assets/sodium/shaders/include/chunk_vertex.glsl}):
 * <ul>
 *   <li>{@code _deinterleave_u20x3}: {@code q = (hi << 10) | lo} per axis, hi = 10 bits of w0, lo = 10 bits of w1;</li>
 *   <li>{@code _vert_position = q * (32 / 2^20) + (-8)} (VERTEX_SCALE, VERTEX_OFFSET); the encoder is
 *       {@code (int)((8 + v) / 32 * 1048576) & 0xFFFFF} (quantizePosition), so v = q*32/2^20 - 8; coordinates are
 *       section-local blocks;</li>
 *   <li>{@code _get_texcoord}: {@code (a_TexCoord & 0x7FFF) / 32768}; bit 15 is the shrink bias sign
 *       ({@code mix(-1,1,bool(a_TexCoord >> 15))}, applied in block_layer_opaque.vsh:71 as bias * u_TexCoordShrink).
 *       The bias is applied here exactly like Sodium's vertex shader: without it a far-edge vertex sits exactly ON the
 *       sprite boundary and NEAREST sampling pulls the first texel of the NEIGHBOUR sprite (a glowing frame around faces
 *       next to an emissive sprite, or holes where the neighbour is transparent); one quantization step inward is safe
 *       for NEAREST and invisible for the atlas texture.</li>
 *   <li>{@code _vert_tex_light_coord = a_LightAndData.xy / 256}: byte0 = block light, byte1 = sky light, both encoded as
 *       {@code clamp(level*16 + 8, 8, 248)} (encodeLight); the decoder keeps the raw bytes in {@code light};</li>
 *   <li>{@code _material_params = a_LightAndData[2]}, {@code _draw_id = a_LightAndData[3]} (section index inside the
 *       region; NOT a world coordinate, dropped).</li>
 * </ul>
 * Color byte order: {@code ColorARGB.toABGR(argb) = Integer.reverseBytes(Integer.rotateLeft(argb, 8))} gives the int
 * {@code A<<24 | B<<16 | G<<8 | R}; stored little-endian, byte 0 = R. {@code mulRGB} keeps the alpha byte.
 *
 * <p>Quad to triangles: Sodium draws quads with the shared index template (v+0, v+1, v+2, v+2, v+3, v+0)
 * (SharedQuadIndexBuffer.java:65-84); this class uses the same template.
 *
 * <p>Normal: geometric, {@code n = normalize(cross(p2 - p0, p3 - p1))} (diagonals); with the Minecraft counter-clockwise
 * winding (UP face p0=(0,1,0) p1=(0,1,1) p2=(1,1,1) p3=(1,1,0)) this yields +Y, checked in the unit tests.
 */
public final class SodiumMeshDecoder {
    public static final int STRIDE = 20;
    public static final int QUAD_BYTES = STRIDE * 4;
    public static final int POSITION_MAX_VALUE = 1 << 20;
    public static final int TEXTURE_MAX_VALUE = 1 << 15;
    public static final float VERTEX_SCALE = 32.0f / POSITION_MAX_VALUE;
    public static final float VERTEX_OFFSET = -8.0f;

    private SodiumMeshDecoder() {
    }

    /** 20-bit quantized coordinate of axis {@code axis} (0=x, 1=y, 2=z) from the two position words. */
    public static int quantizedPosition(int w0, int w1, int axis) {
        int shift = axis * 10;
        return (((w0 >>> shift) & 1023) << 10) | ((w1 >>> shift) & 1023);
    }

    public static float decodePosition(int w0, int w1, int axis) {
        return quantizedPosition(w0, w1, axis) * VERTEX_SCALE + VERTEX_OFFSET;
    }

    public static float decodeU(int w3) {
        return decodeTex((w3 & 0xFFFF), w3 & (1 << 15));
    }

    public static float decodeV(int w3) {
        return decodeTex((w3 >>> 16) & 0xFFFF, w3 & (1 << 31));
    }

    /** One quantization step inward from the sprite edge, mirroring Sodium's {@code bias * u_TexCoordShrink}. */
    public static final float TEX_COORD_SHRINK = 1.0f / TEXTURE_MAX_VALUE;

    private static float decodeTex(int half, int biasBit) {
        float c = (half & (TEXTURE_MAX_VALUE - 1)) / (float) TEXTURE_MAX_VALUE;
        return c + (biasBit != 0 ? TEX_COORD_SHRINK : -TEX_COORD_SHRINK);
    }

    /** Triangle indices for {@code quadCount} quads: (0,1,2, 2,3,0) per quad, offset by {@code baseVertex}. */
    public static void fillTriangleIndices(int[] dst, int quadCount, int baseVertex) {
        if (dst.length < quadCount * 6) {
            throw new IllegalArgumentException("dst too small: " + dst.length + " < " + quadCount * 6);
        }
        for (int q = 0; q < quadCount; q++) {
            int v = baseVertex + q * 4;
            int o = q * 6;
            dst[o] = v;
            dst[o + 1] = v + 1;
            dst[o + 2] = v + 2;
            dst[o + 3] = v + 2;
            dst[o + 4] = v + 3;
            dst[o + 5] = v;
        }
    }

    /**
     * Decodes one section.
     *
     * @param passData per pass (0=SOLID, 1=CUTOUT, 2=TRANSLUCENT) a buffer holding vertices from position 0 to
     *                 {@code limit()}; may be {@code null} (pass absent). Position/limit/order of the arguments are not changed.
     * @throws IllegalArgumentException when a buffer length is not a multiple of one quad (80 bytes)
     */
    public static DecodedSection decode(long key, int sectionX, int sectionY, int sectionZ, ByteBuffer[] passData) {
        if (passData.length != DecodedSection.PASS_COUNT) {
            throw new IllegalArgumentException("passData must have " + DecodedSection.PASS_COUNT + " entries");
        }
        int[] passQuads = new int[DecodedSection.PASS_COUNT];
        int total = 0;
        ByteBuffer[] src = new ByteBuffer[DecodedSection.PASS_COUNT];
        for (int p = 0; p < DecodedSection.PASS_COUNT; p++) {
            ByteBuffer b = passData[p];
            if (b == null) {
                continue;
            }
            ByteBuffer d = b.duplicate().order(ByteOrder.LITTLE_ENDIAN);
            d.position(0);
            int len = d.limit();
            if (len % QUAD_BYTES != 0) {
                throw new IllegalArgumentException("pass " + p + " length " + len + " is not a multiple of " + QUAD_BYTES);
            }
            src[p] = d;
            passQuads[p] = len / QUAD_BYTES;
            total += passQuads[p];
        }

        float[] positions = new float[total * 12];
        float[] uvs = new float[total * 8];
        int[] colors = new int[total * 4];
        int[] light = new int[total * 4];
        float[] normals = new float[total * 3];
        float[] min = {Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY};
        float[] max = {Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY};

        int quad = 0;
        for (int p = 0; p < DecodedSection.PASS_COUNT; p++) {
            ByteBuffer d = src[p];
            for (int i = 0; i < passQuads[p]; i++, quad++) {
                int base = i * QUAD_BYTES;
                for (int v = 0; v < 4; v++) {
                    int o = base + v * STRIDE;
                    int w0 = d.getInt(o);
                    int w1 = d.getInt(o + 4);
                    int w2 = d.getInt(o + 8);
                    int w3 = d.getInt(o + 12);
                    int w4 = d.getInt(o + 16);
                    int vi = quad * 4 + v;
                    for (int axis = 0; axis < 3; axis++) {
                        float c = decodePosition(w0, w1, axis);
                        positions[vi * 3 + axis] = c;
                        if (c < min[axis]) min[axis] = c;
                        if (c > max[axis]) max[axis] = c;
                    }
                    uvs[vi * 2] = decodeU(w3);
                    uvs[vi * 2 + 1] = decodeV(w3);
                    colors[vi] = w2;
                    light[vi] = w4 & 0x00FFFFFF; // drop the region-local section index (byte 3)
                }
                computeNormal(positions, quad * 12, normals, quad * 3);
            }
        }
        if (total == 0) {
            min = new float[3];
            max = new float[3];
        }
        return new DecodedSection(key, sectionX, sectionY, sectionZ, passQuads, positions, uvs, colors, light, normals, min, max);
    }

    /** n = normalize(cross(p2 - p0, p3 - p1)). A zero-area quad has no normal: fixed (0,1,0), such quads draw nothing. */
    static void computeNormal(float[] pos, int po, float[] out, int oo) {
        float d1x = pos[po + 6] - pos[po];
        float d1y = pos[po + 7] - pos[po + 1];
        float d1z = pos[po + 8] - pos[po + 2];
        float d2x = pos[po + 9] - pos[po + 3];
        float d2y = pos[po + 10] - pos[po + 4];
        float d2z = pos[po + 11] - pos[po + 5];
        float nx = d1y * d2z - d1z * d2y;
        float ny = d1z * d2x - d1x * d2z;
        float nz = d1x * d2y - d1y * d2x;
        float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (len < 1.0e-12f) {
            out[oo] = 0f;
            out[oo + 1] = 1f;
            out[oo + 2] = 0f;
            return;
        }
        out[oo] = nx / len;
        out[oo + 1] = ny / len;
        out[oo + 2] = nz / len;
    }
}
