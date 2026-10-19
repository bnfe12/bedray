package io.github.andrexarlay.vulkancompat.api.rt;

/**
 * Vertex buffer layout descriptor for meshes registered with {@link RtSceneApi#registerMesh}.
 *
 * <p>Positions are always 3 IEEE-754 {@code float}s ({@code x, y, z}) at {@code posOffset}.
 * Optional attributes use {@code -1} when absent:
 * <ul>
 *   <li>{@code colorOffset}: 4 unsigned bytes ({@code R, G, B, A} in memory, i.e. packed ABGR little-endian {@code int})</li>
 *   <li>{@code uvOffset}: 2 {@code float}s ({@code u, v})</li>
 *   <li>{@code lightOffset}: 4 bytes packed lightmap ({@code blockLight | (skyLight << 16)})</li>
 *   <li>{@code normalOffset}: 3 {@code float}s ({@code nx, ny, nz}) when {@code !byteNormals}, or 3 signed bytes when {@code byteNormals}</li>
 * </ul>
 */
public record RtVertexFormat(
    int strideBytes,
    int posOffset,
    int colorOffset,
    int uvOffset,
    int lightOffset,
    int normalOffset,
    boolean byteNormals
) {
    /**
     * Canonical 10-float interleaved RT entity vertex format (40 bytes):
     * {@code [x, y, z, colorBits, u, v, lightBits, nx, ny, nz]}.
     */
    public static final RtVertexFormat RT_ENTITY_10F = new RtVertexFormat(40, 0, 12, 16, 24, 28, false);

    /**
     * Blaze3D {@code DefaultVertexFormat.NEW_ENTITY} (36 bytes):
     * {@code Position(3f @ 0), Color(4ub @ 12), UV0(2f @ 16), UV1/Overlay(2s @ 24), UV2/Light(2s @ 28), Normal(3b @ 32)}.
     */
    public static final RtVertexFormat NEW_ENTITY = new RtVertexFormat(36, 0, 12, 16, 28, 32, true);

    /**
     * Blaze3D {@code DefaultVertexFormat.BLOCK} (32 bytes):
     * {@code Position(3f @ 0), Color(4ub @ 12), UV0(2f @ 16), UV2/Light(2s @ 24), Normal(3b @ 28)}.
     */
    public static final RtVertexFormat BLOCK = new RtVertexFormat(32, 0, 12, 16, 24, 28, true);

    /**
     * Compact position + UV + color + float normal format (32 bytes):
     * {@code Position(3f @ 0), UV(2f @ 12), Color(4ub @ 20), Normal(3f @ 24)} (stride 36).
     */
    public static final RtVertexFormat POSITION_TEX_COLOR_NORMAL = new RtVertexFormat(36, 0, 20, 12, -1, 24, false);

    /**
     * Position-only 3-float format (12 bytes): {@code [x, y, z]}.
     */
    public static final RtVertexFormat POSITION_ONLY = new RtVertexFormat(12, 0, -1, -1, -1, -1, false);

    public RtVertexFormat {
        if (strideBytes < 12) {
            throw new IllegalArgumentException("strideBytes must be >= 12, got " + strideBytes);
        }
        if (posOffset < 0 || posOffset + 12 > strideBytes) {
            throw new IllegalArgumentException("posOffset out of bounds: " + posOffset + " for stride " + strideBytes);
        }
    }
}
