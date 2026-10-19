package io.github.andrexarlay.vulkancompat.shaderhost.dirtrt;

import org.joml.Vector3f;
import org.joml.Vector4f;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Adapter and packer for Vulkanite quad geometry buffers (Set 1, Binding 0).
 * <p>
 * Vulkanite hit shaders access quads via:
 * {@code geometryBuffers[gl_InstanceCustomIndexEXT + gl_GeometryIndexEXT].quads[gl_PrimitiveID >> 1]}
 * <p>
 * Each Quad is exactly 160 bytes (4 vertices * 40 bytes).
 * <p>
 * Each Vertex is exactly 40 bytes:
 * <ul>
 *   <li>Offset 0..11 (12B): {@code vec3 position} (float x, y, z)</li>
 *   <li>Offset 12..19 (8B): {@code vec2 texcoord} (float u, v)</li>
 *   <li>Offset 20..35 (16B): {@code vec4 normal_tangent} (float nx, ny, nz, tangent)</li>
 *   <li>Offset 36..39 (4B): {@code uint blockId} (uint block identifier)</li>
 * </ul>
 */
public final class DirtRtQuadAdapter {

    public static final int VERTEX_SIZE = 40;
    public static final int VERTICES_PER_QUAD = 4;
    public static final int QUAD_SIZE = VERTEX_SIZE * VERTICES_PER_QUAD; // 160 bytes

    public static final int OFFSET_POSITION = 0;
    public static final int SIZE_POSITION = 12; // 3 * 4B

    public static final int OFFSET_POS_X = 0;
    public static final int OFFSET_POS_Y = 4;
    public static final int OFFSET_POS_Z = 8;

    public static final int OFFSET_TEXCOORD = 12;
    public static final int SIZE_TEXCOORD = 8; // 2 * 4B

    public static final int OFFSET_TEX_U = 12;
    public static final int OFFSET_TEX_V = 16;

    public static final int OFFSET_NORMAL_TANGENT = 20;
    public static final int SIZE_NORMAL_TANGENT = 16; // 4 * 4B

    public static final int OFFSET_NORM_X = 20;
    public static final int OFFSET_NORM_Y = 24;
    public static final int OFFSET_NORM_Z = 28;
    public static final int OFFSET_TANGENT = 32;

    public static final int OFFSET_BLOCK_ID = 36;
    public static final int SIZE_BLOCK_ID = 4;

    private DirtRtQuadAdapter() {}

    /**
     * Immutable 40-byte vertex representation.
     */
    public record Vertex(
            float x, float y, float z,
            float u, float v,
            float nx, float ny, float nz, float tangent,
            int blockId
    ) {
        public Vertex {
            // blockId is treated as uint32
        }
    }

    /**
     * Immutable 160-byte quad representation consisting of 4 vertices.
     */
    public record Quad(Vertex v0, Vertex v1, Vertex v2, Vertex v3) {
        public Quad {
            Objects.requireNonNull(v0, "v0 must not be null");
            Objects.requireNonNull(v1, "v1 must not be null");
            Objects.requireNonNull(v2, "v2 must not be null");
            Objects.requireNonNull(v3, "v3 must not be null");
        }

        public Vertex getVertex(int index) {
            return switch (index) {
                case 0 -> v0;
                case 1 -> v1;
                case 2 -> v2;
                case 3 -> v3;
                default -> throw new IndexOutOfBoundsException("Vertex index out of range (0..3): " + index);
            };
        }
    }

    /**
     * Writes a single 40-byte vertex into the buffer at the given byte offset.
     */
    public static void writeVertex(ByteBuffer buffer, int offset,
                                   float x, float y, float z,
                                   float u, float v,
                                   float nx, float ny, float nz, float tangent,
                                   int blockId) {
        Objects.requireNonNull(buffer, "buffer must not be null");
        if (offset < 0 || buffer.capacity() - offset < VERTEX_SIZE) {
            throw new IllegalArgumentException("Insufficient buffer capacity for 40-byte vertex at offset " + offset);
        }

        ByteOrder originalOrder = buffer.order();
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        try {
            buffer.putFloat(offset + OFFSET_POS_X, x);
            buffer.putFloat(offset + OFFSET_POS_Y, y);
            buffer.putFloat(offset + OFFSET_POS_Z, z);

            buffer.putFloat(offset + OFFSET_TEX_U, u);
            buffer.putFloat(offset + OFFSET_TEX_V, v);

            buffer.putFloat(offset + OFFSET_NORM_X, nx);
            buffer.putFloat(offset + OFFSET_NORM_Y, ny);
            buffer.putFloat(offset + OFFSET_NORM_Z, nz);
            buffer.putFloat(offset + OFFSET_TANGENT, tangent);

            buffer.putInt(offset + OFFSET_BLOCK_ID, blockId);
        } finally {
            buffer.order(originalOrder);
        }
    }

    /**
     * Writes a {@link Vertex} into the buffer at the given byte offset.
     */
    public static void writeVertex(ByteBuffer buffer, int offset, Vertex vertex) {
        Objects.requireNonNull(vertex, "vertex must not be null");
        writeVertex(buffer, offset,
                vertex.x(), vertex.y(), vertex.z(),
                vertex.u(), vertex.v(),
                vertex.nx(), vertex.ny(), vertex.nz(), vertex.tangent(),
                vertex.blockId());
    }

    /**
     * Reads a {@link Vertex} from the buffer at the given byte offset.
     */
    public static Vertex readVertex(ByteBuffer buffer, int offset) {
        Objects.requireNonNull(buffer, "buffer must not be null");
        if (offset < 0 || buffer.capacity() - offset < VERTEX_SIZE) {
            throw new IllegalArgumentException("Insufficient buffer capacity for 40-byte vertex at offset " + offset);
        }

        ByteOrder originalOrder = buffer.order();
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        try {
            float x = buffer.getFloat(offset + OFFSET_POS_X);
            float y = buffer.getFloat(offset + OFFSET_POS_Y);
            float z = buffer.getFloat(offset + OFFSET_POS_Z);

            float u = buffer.getFloat(offset + OFFSET_TEX_U);
            float v = buffer.getFloat(offset + OFFSET_TEX_V);

            float nx = buffer.getFloat(offset + OFFSET_NORM_X);
            float ny = buffer.getFloat(offset + OFFSET_NORM_Y);
            float nz = buffer.getFloat(offset + OFFSET_NORM_Z);
            float tangent = buffer.getFloat(offset + OFFSET_TANGENT);

            int blockId = buffer.getInt(offset + OFFSET_BLOCK_ID);

            return new Vertex(x, y, z, u, v, nx, ny, nz, tangent, blockId);
        } finally {
            buffer.order(originalOrder);
        }
    }

    /**
     * Writes a complete 160-byte {@link Quad} into the buffer at the given byte offset.
     */
    public static void writeQuad(ByteBuffer buffer, int offset, Quad quad) {
        Objects.requireNonNull(quad, "quad must not be null");
        if (offset < 0 || buffer.capacity() - offset < QUAD_SIZE) {
            throw new IllegalArgumentException("Insufficient buffer capacity for 160-byte quad at offset " + offset);
        }

        writeVertex(buffer, offset, quad.v0());
        writeVertex(buffer, offset + VERTEX_SIZE, quad.v1());
        writeVertex(buffer, offset + 2 * VERTEX_SIZE, quad.v2());
        writeVertex(buffer, offset + 3 * VERTEX_SIZE, quad.v3());
    }

    /**
     * Writes a {@link Quad} at the current buffer position, advancing position by 160 bytes.
     */
    public static void writeQuad(ByteBuffer buffer, Quad quad) {
        int pos = buffer.position();
        writeQuad(buffer, pos, quad);
        buffer.position(pos + QUAD_SIZE);
    }

    /**
     * Reads a {@link Quad} from the buffer at the given byte offset.
     */
    public static Quad readQuad(ByteBuffer buffer, int offset) {
        Vertex v0 = readVertex(buffer, offset);
        Vertex v1 = readVertex(buffer, offset + VERTEX_SIZE);
        Vertex v2 = readVertex(buffer, offset + 2 * VERTEX_SIZE);
        Vertex v3 = readVertex(buffer, offset + 3 * VERTEX_SIZE);
        return new Quad(v0, v1, v2, v3);
    }

    /**
     * Writes multiple quads starting at the specified offset.
     */
    public static void writeQuads(ByteBuffer buffer, int offset, List<Quad> quads) {
        Objects.requireNonNull(quads, "quads must not be null");
        long requiredSize = (long) quads.size() * QUAD_SIZE;
        if (offset < 0 || (long) buffer.capacity() - offset < requiredSize) {
            throw new IllegalArgumentException("Buffer capacity too small for " + quads.size() + " quads (" + requiredSize + " bytes) at offset " + offset);
        }

        for (int i = 0; i < quads.size(); i++) {
            writeQuad(buffer, offset + i * QUAD_SIZE, quads.get(i));
        }
    }

    /**
     * Writes multiple quads at the current buffer position, advancing position by {@code quads.size() * 160}.
     */
    public static void writeQuads(ByteBuffer buffer, List<Quad> quads) {
        int pos = buffer.position();
        writeQuads(buffer, pos, quads);
        buffer.position(pos + quads.size() * QUAD_SIZE);
    }

    /**
     * Reads a list of quads from the buffer.
     */
    public static List<Quad> readQuads(ByteBuffer buffer, int offset, int count) {
        if (count < 0) {
            throw new IllegalArgumentException("Count must not be negative: " + count);
        }
        long requiredSize = (long) count * QUAD_SIZE;
        if (offset < 0 || (long) buffer.capacity() - offset < requiredSize) {
            throw new IllegalArgumentException("Buffer capacity too small to read " + count + " quads from offset " + offset);
        }

        List<Quad> result = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            result.add(readQuad(buffer, offset + i * QUAD_SIZE));
        }
        return result;
    }

    /**
     * Computes the normalized geometric face normal from the first three quad vertices.
     * <p>
     * {@code normal = normalize((v1 - v0) x (v2 - v0))}
     */
    public static Vector3f computeFaceNormal(float x0, float y0, float z0,
                                            float x1, float y1, float z1,
                                            float x2, float y2, float z2,
                                            Vector3f dest) {
        if (dest == null) dest = new Vector3f();

        float e1x = x1 - x0;
        float e1y = y1 - y0;
        float e1z = z1 - z0;

        float e2x = x2 - x0;
        float e2y = y2 - y0;
        float e2z = z2 - z0;

        // Cross product e1 x e2
        float nx = e1y * e2z - e1z * e2y;
        float ny = e1z * e2x - e1x * e2z;
        float nz = e1x * e2y - e1y * e2x;

        float lenSq = nx * nx + ny * ny + nz * nz;
        if (lenSq > 1e-12f) {
            float invLen = (float) (1.0 / Math.sqrt(lenSq));
            dest.set(nx * invLen, ny * invLen, nz * invLen);
        } else {
            dest.set(0.0f, 1.0f, 0.0f); // Default up normal
        }
        return dest;
    }

    /**
     * Computes tangent vector and handedness from triangle positions, UVs, and geometric normal.
     * Returns a {@code Vector4f} containing (tx, ty, tz, handednessSign).
     */
    public static Vector4f computeTangent(float nx, float ny, float nz,
                                         float x0, float y0, float z0, float u0, float v0,
                                         float x1, float y1, float z1, float u1, float v1,
                                         float x2, float y2, float z2, float u2, float v2,
                                         Vector4f dest) {
        if (dest == null) dest = new Vector4f();

        float e1x = x1 - x0;
        float e1y = y1 - y0;
        float e1z = z1 - z0;
        float e2x = x2 - x0;
        float e2y = y2 - y0;
        float e2z = z2 - z0;

        float du1 = u1 - u0;
        float dv1 = v1 - v0;
        float du2 = u2 - u0;
        float dv2 = v2 - v0;

        float det = du1 * dv2 - du2 * dv1;
        float tx, ty, tz;
        if (Math.abs(det) > 1e-8f) {
            float invDet = 1.0f / det;
            tx = (e1x * dv2 - e2x * dv1) * invDet;
            ty = (e1y * dv2 - e2y * dv1) * invDet;
            tz = (e1z * dv2 - e2z * dv1) * invDet;
        } else {
            // Degenerate UVs: fallback tangent perpendicular to normal
            if (Math.abs(nx) < 0.9f) {
                tx = 1.0f;
                ty = 0.0f;
                tz = 0.0f;
            } else {
                tx = 0.0f;
                ty = 1.0f;
                tz = 0.0f;
            }
        }

        // Gram-Schmidt orthogonalize: t = normalize(t - n * dot(n, t))
        float dotNT = nx * tx + ny * ty + nz * tz;
        tx -= nx * dotNT;
        ty -= ny * dotNT;
        tz -= nz * dotNT;

        float tLenSq = tx * tx + ty * ty + tz * tz;
        if (tLenSq > 1e-12f) {
            float invTLen = (float) (1.0 / Math.sqrt(tLenSq));
            tx *= invTLen;
            ty *= invTLen;
            tz *= invTLen;
        }

        // Bitangent and handedness
        // bitangent from geometry: (n x t)
        float bGeomX = ny * tz - nz * ty;
        float bGeomY = nz * tx - nx * tz;
        float bGeomZ = nx * ty - ny * tx;

        // bitangent from UV
        float bUvX = (e2x * du1 - e1x * du2);
        float bUvY = (e2y * du1 - e1y * du2);
        float bUvZ = (e2z * du1 - e1z * du2);

        float dotBB = bGeomX * bUvX + bGeomY * bUvY + bGeomZ * bUvZ;
        float handedness = dotBB < 0.0f ? -1.0f : 1.0f;

        dest.set(tx, ty, tz, handedness);
        return dest;
    }

    /**
     * Constructs a complete {@link Quad} from 4 corner positions and UVs, computing the normal and tangent.
     *
     * @param positions array of 12 floats (x0, y0, z0, x1, y1, z1, x2, y2, z2, x3, y3, z3)
     * @param uvs       array of 8 floats (u0, v0, u1, v1, u2, v2, u3, v3)
     * @param blockId   block identifier
     */
    public static Quad createQuad(float[] positions, float[] uvs, int blockId) {
        Objects.requireNonNull(positions, "positions must not be null");
        Objects.requireNonNull(uvs, "uvs must not be null");
        if (positions.length < 12) throw new IllegalArgumentException("positions length must be >= 12");
        if (uvs.length < 8) throw new IllegalArgumentException("uvs length must be >= 8");

        Vector3f normal = computeFaceNormal(
                positions[0], positions[1], positions[2],
                positions[3], positions[4], positions[5],
                positions[6], positions[7], positions[8],
                new Vector3f());

        Vector4f tangent = computeTangent(
                normal.x, normal.y, normal.z,
                positions[0], positions[1], positions[2], uvs[0], uvs[1],
                positions[3], positions[4], positions[5], uvs[2], uvs[3],
                positions[6], positions[7], positions[8], uvs[4], uvs[5],
                new Vector4f());

        Vertex v0 = new Vertex(positions[0], positions[1], positions[2], uvs[0], uvs[1],
                normal.x, normal.y, normal.z, tangent.w, blockId);
        Vertex v1 = new Vertex(positions[3], positions[4], positions[5], uvs[2], uvs[3],
                normal.x, normal.y, normal.z, tangent.w, blockId);
        Vertex v2 = new Vertex(positions[6], positions[7], positions[8], uvs[4], uvs[5],
                normal.x, normal.y, normal.z, tangent.w, blockId);
        Vertex v3 = new Vertex(positions[9], positions[10], positions[11], uvs[6], uvs[7],
                normal.x, normal.y, normal.z, tangent.w, blockId);

        return new Quad(v0, v1, v2, v3);
    }
}
