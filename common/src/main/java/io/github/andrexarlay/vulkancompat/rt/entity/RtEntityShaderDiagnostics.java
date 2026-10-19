package io.github.andrexarlay.vulkancompat.rt.entity;

import java.util.Locale;

/**
 * Diagnostics and binding contract for the ray-traced entity shader pipeline ({@code rt_common.glsl}).
 *
 * <h2>Java-to-GLSL Descriptor and Buffer Device Address ({@code StatsBuf}) Mapping Table</h2>
 * <p>Entity hits in {@code rt_common.glsl} are identified by bit 23 of {@code instanceCustomIndex}
 * ({@code INSTANCE_TYPE_ENTITY = 0x800000u}) and never index the world chunk table {@code sections.s[customIndex]}.
 * Instead, all per-frame entity geometry, motion vectors, and texture atlas buffers are addressed via
 * {@code GL_EXT_buffer_reference} through the per-frame {@code StatsBuf} pointer in push constants
 * ({@code pc.statsAddr}, 256-byte stride {@code BDA_STATS_256B}):
 *
 * <table border="1">
 *   <caption>StatsBuf Entity Binding Layout (pc.statsAddr)</caption>
 *   <tr>
 *     <th>Byte Offset</th>
 *     <th>GLSL Field</th>
 *     <th>GLSL Type / Buffer Reference</th>
 *     <th>Java Producer (RtFrameRenderer / RtEntityGeometryManager)</th>
 *   </tr>
 *   <tr>
 *     <td>+180</td>
 *     <td>{@code entityTris}</td>
 *     <td>{@code uint} (4B)</td>
 *     <td>Triangle count of this frame's entity BLAS ({@code eg.frameTriangles()}, 0 when absent)</td>
 *   </tr>
 *   <tr>
 *     <td>+184</td>
 *     <td>{@code entPosAddr}</td>
 *     <td>{@code uvec2 -> FloatBuf} (8B)</td>
 *     <td>Camera-relative {@code float3} vertex positions ({@code eg.framePosAddress()})</td>
 *   </tr>
 *   <tr>
 *     <td>+192</td>
 *     <td>{@code entIdxAddr}</td>
 *     <td>{@code uvec2 -> UIntBuf} (8B)</td>
 *     <td>Triangle vertex indices, 3 {@code uint}s per primitive ({@code eg.frameIndexAddress()})</td>
 *   </tr>
 *   <tr>
 *     <td>+200</td>
 *     <td>{@code entAttrAddr}</td>
 *     <td>{@code uvec2 -> UIntBuf} (8B)</td>
 *     <td>Per-vertex attributes, 4 {@code uint}s (16B): ABGR color, {@code blockLight | (slot << 8) | (skyLight << 16)}, floatBits(u), floatBits(v) ({@code eg.frameAttrAddress()})</td>
 *   </tr>
 *   <tr>
 *     <td>+208</td>
 *     <td>{@code entPrevAddr}</td>
 *     <td>{@code uvec2 -> FloatBuf} (8B)</td>
 *     <td>Previous-frame camera-relative {@code float3} positions for motion vectors ({@code eg.framePrevAddress()})</td>
 *   </tr>
 *   <tr>
 *     <td>+216</td>
 *     <td>{@code entAtlasAddr}</td>
 *     <td>{@code uvec2 -> UIntBuf} (8B)</td>
 *     <td>Shelf-packed {@code 2048x2048} RGBA8 (ABGR {@code uint}) entity texture atlas ({@code eg.atlasAddress()})</td>
 *   </tr>
 *   <tr>
 *     <td>+224</td>
 *     <td>{@code entRectAddr}</td>
 *     <td>{@code uvec2 -> UIntBuf} (8B)</td>
 *     <td>Atlas slot rectangle table, {@code uvec4(x, y, w, h)} in texels per slot ({@code eg.rectAddress()})</td>
 *   </tr>
 *   <tr>
 *     <td>+232</td>
 *     <td>{@code motionAddr}</td>
 *     <td>{@code uvec2 -> Vec4Buf} (8B)</td>
 *     <td>Per-pixel primary hit motion vector buffer for DLSS-RR ({@code entityMotionBuf.address})</td>
 *   </tr>
 *   <tr>
 *     <td>+240</td>
 *     <td>{@code entAtlasSize}</td>
 *     <td>{@code uint} (4B)</td>
 *     <td>Atlas dimension in texels ({@link EntityTextureAtlas#SIZE} = 2048 when uploaded, 0 otherwise)</td>
 *   </tr>
 * </table>
 */
public final class RtEntityShaderDiagnostics {

    public enum State {
        MISSING,
        DEBUG_MATERIAL,
        TEXTURED,
        FAILED
    }

    public static final int STATS_BUF_STRIDE = 256;
    public static final int OFFSET_ENTITY_TRIS = 180;
    public static final int OFFSET_ENT_POS_ADDR = 184;
    public static final int OFFSET_ENT_IDX_ADDR = 192;
    public static final int OFFSET_ENT_ATTR_ADDR = 200;
    public static final int OFFSET_ENT_PREV_ADDR = 208;
    public static final int OFFSET_ENT_ATLAS_ADDR = 216;
    public static final int OFFSET_ENT_RECT_ADDR = 224;
    public static final int OFFSET_MOTION_ADDR = 232;
    public static final int OFFSET_ENT_ATLAS_SIZE = 240;

    public static final String LAYOUT = "BDA_STATS_256B";
    public static final String TEXTURE_REGISTRY = "ENTITY_ATLAS_2048";
    public static final String ALPHA_CUTOUT = "ACTIVE";
    public static final float ALPHA_CUTOUT_THRESHOLD = 0.1f;
    public static final String EMISSIVE = "MISSING";
    public static final String UNSUPPORTED_LAYERS = "eyes_emissive,glint,armor_trim";

    private static State state = State.MISSING;
    private static int lastEntityTris = 0;
    private static long lastPosAddr = 0L;
    private static long lastIdxAddr = 0L;
    private static long lastAttrAddr = 0L;
    private static long lastAtlasAddr = 0L;
    private static long lastRectAddr = 0L;
    private static int lastAtlasSize = 0;
    private static int lastAtlasSlotCount = 0;
    private static int lastMaterialCount = 0;
    private static String lastError = "none";

    private RtEntityShaderDiagnostics() {
    }

    /**
     * Updates the entity shader diagnostics state from the current frame's bound BDA addresses and atlas metrics.
     */
    public static synchronized void updateFromFrame(
        int entityTris,
        long posAddr,
        long idxAddr,
        long attrAddr,
        long atlasAddr,
        long rectAddr,
        int atlasSize,
        int atlasSlotCount,
        int materialCount
    ) {
        lastEntityTris = Math.max(0, entityTris);
        lastPosAddr = posAddr;
        lastIdxAddr = idxAddr;
        lastAttrAddr = attrAddr;
        lastAtlasAddr = atlasAddr;
        lastRectAddr = rectAddr;
        lastAtlasSize = Math.max(0, atlasSize);
        lastAtlasSlotCount = Math.max(0, atlasSlotCount);
        lastMaterialCount = Math.max(0, materialCount);
        lastError = "none";

        boolean geometryBound = entityTris > 0 && posAddr != 0L && idxAddr != 0L && attrAddr != 0L;
        boolean atlasReady = atlasAddr != 0L && atlasSize > 0 && atlasSlotCount > 1;

        if (atlasReady) {
            state = State.TEXTURED;
        } else if (geometryBound) {
            state = State.DEBUG_MATERIAL;
        } else {
            state = State.MISSING;
        }
    }

    /**
     * Records a shader or descriptor failure on the entity path.
     */
    public static synchronized void recordFailure(String error) {
        state = State.FAILED;
        lastError = (error != null && !error.isBlank()) ? error : "unknown";
    }

    /**
     * Resets diagnostics state to {@link State#MISSING}.
     */
    public static synchronized void reset() {
        state = State.MISSING;
        lastEntityTris = 0;
        lastPosAddr = 0L;
        lastIdxAddr = 0L;
        lastAttrAddr = 0L;
        lastAtlasAddr = 0L;
        lastRectAddr = 0L;
        lastAtlasSize = 0;
        lastAtlasSlotCount = 0;
        lastMaterialCount = 0;
        lastError = "none";
    }

    public static synchronized State currentState() {
        return state;
    }

    public static synchronized int entityTris() {
        return lastEntityTris;
    }

    public static synchronized long posAddr() {
        return lastPosAddr;
    }

    public static synchronized long idxAddr() {
        return lastIdxAddr;
    }

    public static synchronized long attrAddr() {
        return lastAttrAddr;
    }

    public static synchronized long atlasAddr() {
        return lastAtlasAddr;
    }

    public static synchronized long rectAddr() {
        return lastRectAddr;
    }

    public static synchronized int atlasSize() {
        return lastAtlasSize;
    }

    public static synchronized int atlasSlotCount() {
        return lastAtlasSlotCount;
    }

    public static synchronized int materialCount() {
        return lastMaterialCount;
    }

    public static synchronized String lastError() {
        return lastError;
    }

    /**
     * Formats the canonical entity shader diagnostics line.
     */
    public static synchronized String formatDiagnostics() {
        return String.format(
            Locale.ROOT,
            "[VK-ENTITY-SHADER] state=%s layout=%s materials=%d textures=%d textureRegistry=%s alphaCutout=%s emissive=%s unsupportedLayers=%s lastError=%s",
            state.name(),
            LAYOUT,
            lastMaterialCount,
            lastAtlasSlotCount,
            TEXTURE_REGISTRY,
            ALPHA_CUTOUT,
            EMISSIVE,
            UNSUPPORTED_LAYERS,
            lastError
        );
    }
}
