package io.github.andrexarlay.vulkancompat.rt.world;

import io.github.andrexarlay.vulkancompat.rt.entity.EntityGeometryFrame;
import org.lwjgl.system.MemoryUtil;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

import java.util.ArrayList;
import java.util.List;

/**
 * Captures entity, mob, chest, and dropped item geometry during the frame render
 * and packs the CPU streams consumed by the native scene.
 */
public final class RtEntityGeometryManager {

    public static final class QuadGeometry {
        public final float[] positions = new float[12]; // 4 vertices * 3 (x, y, z)
        public final float[] uvs = new float[8];        // 4 vertices * 2 (u, v)
        public final int[] colors = new int[4];       // 4 vertices ARGB/ABGR
        public final int[] light = new int[4];        // 4 vertices light (block, sky)
    }

    private final List<QuadGeometry> currentFrameQuads = new ArrayList<>();
    private EntityGeometryFrame currentFrame = EntityGeometryFrame.EMPTY;

    private int quadCount = 0;

    public static final RtEntityGeometryManager INSTANCE = new RtEntityGeometryManager();

    public static RtEntityGeometryManager getInstance() {
        return INSTANCE;
    }

    public RtEntityGeometryManager() {
    }

    public synchronized void setCurrentFrame(EntityGeometryFrame frame) {
        setFrame(frame);
    }

    public synchronized void resetFrame() {
        currentFrameQuads.clear();
        currentFrame = EntityGeometryFrame.EMPTY;
        quadCount = 0;
    }

    public synchronized void setFrame(EntityGeometryFrame frame) {
        this.currentFrame = frame != null ? frame : EntityGeometryFrame.EMPTY;
        this.quadCount = this.currentFrame.totalVertices() / 4;
    }

    public synchronized EntityGeometryFrame getCurrentFrame() {
        return currentFrame;
    }

    public synchronized void addQuad(QuadGeometry quad) {
        if (quad != null && currentFrameQuads.size() < 65536) {
            currentFrameQuads.add(quad);
            quadCount++;
        }
    }

    public synchronized int getQuadCount() {
        if (!currentFrameQuads.isEmpty()) {
            return currentFrameQuads.size();
        }
        return currentFrame != null && currentFrame.totalVertices() > 0 ? currentFrame.totalVertices() / 4 : quadCount;
    }

    public synchronized int getVertexCount() {
        if (currentFrame != null && currentFrame.totalVertices() > 0) {
            return currentFrame.totalVertices();
        }
        return currentFrameQuads.size() * 4;
    }

    public synchronized int getIndexCount() {
        if (currentFrame != null && currentFrame.totalIndices() > 0) {
            return currentFrame.totalIndices();
        }
        int vCount = getVertexCount();
        return (vCount / 4) * 6;
    }

    public synchronized int getPrimitiveCount() {
        int idxCount = getIndexCount();
        if (idxCount > 0) {
            return idxCount / 3;
        }
        return getVertexCount() / 2;
    }

    public synchronized int getCapturedEntityCount() {
        if (currentFrame != null && !currentFrame.entities().isEmpty()) {
            return currentFrame.entities().size();
        }
        return currentFrameQuads.size();
    }

    public synchronized boolean hasEntities() {
        return (currentFrame != null && currentFrame.hasEntities()) || !currentFrameQuads.isEmpty() || getPrimitiveCount() > 0;
    }

    public synchronized List<QuadGeometry> getQuads() {
        return currentFrameQuads;
    }

    private double curCamX, curCamY, curCamZ;
    public static final int FLOATS_PER_VERTEX = 10;

    record Packed(MemorySegment positions, MemorySegment indices, MemorySegment attributes,
                  MemorySegment previous, int vertices, int indexCount) {}

    private EntityGeometryFrame prevFrame = EntityGeometryFrame.EMPTY;
    private double prevCamX, prevCamY, prevCamZ;
    private int matchedEntities;
    private int unmatchedEntities;
    /**
     * Scratch for the per-frame entity matching loop (every captured entity against every previous one). Matching is
     * O(entities^2) and ran on the render thread with one fresh {@code Vector3f} per pair, which is hundreds of thousands of
     * short-lived objects per frame in a mob farm. Only touched from the synchronized upload path.
     */
    private final org.joml.Vector3f matchCur = new org.joml.Vector3f();
    private final org.joml.Vector3f matchPrev = new org.joml.Vector3f();
    private final org.joml.Matrix4f matchInv = new org.joml.Matrix4f();
    private final org.joml.Matrix4f matchCurToPrev = new org.joml.Matrix4f();
    private final org.joml.Vector4f matchPoint = new org.joml.Vector4f();
    /** Marks previous-frame entities already claimed this frame, indexed by their position in {@code prevFrame.entities()}. */
    private boolean[] prevUsed = new boolean[0];
    private int frameVertices;
    private int frameTriangles;
    private long packedBytes;

    synchronized Packed pack(Arena arena, double camX, double camY, double camZ) {
        curCamX = camX; curCamY = camY; curCamZ = camZ;
        float[] src = currentFrame.vertexData();
        int[] idx = currentFrame.indexData();
        int verts = src == null ? 0 : Math.min(currentFrame.totalVertices(), src.length / FLOATS_PER_VERTEX);
        int indices = idx == null ? 0 : Math.min(currentFrame.totalIndices(), idx.length) / 3 * 3;
        boolean staged = (verts == 0 || indices == 0) && !currentFrameQuads.isEmpty();
        if (staged) {
            verts = currentFrameQuads.size() * 4;
            indices = currentFrameQuads.size() * 6;
        }
        frameVertices = verts;
        frameTriangles = indices / 3;
        packedBytes = 0;
        if (verts == 0 || indices == 0) {
            prevFrame = EntityGeometryFrame.EMPTY;
            return null;
        }
        var pos = arena.allocate(verts * 12L, 4);
        var index = arena.allocate(indices * 4L, 4);
        var attr = arena.allocate(verts * 16L, 4);
        var prev = arena.allocate(verts * 12L, 4);
        if (staged) {
            EntityQuadIndices.write(index.asByteBuffer().order(java.nio.ByteOrder.nativeOrder()).asIntBuffer(),
                currentFrameQuads.size());
            int v = 0;
            for (var q : currentFrameQuads) {
                for (int k = 0; k < 4; k++, v++) {
                    writeVertex(pos.address(), attr.address(), v, q.positions[k * 3], q.positions[k * 3 + 1],
                        q.positions[k * 3 + 2], q.colors[k], q.light[k], q.uvs[k * 2], q.uvs[k * 2 + 1]);
                }
            }
            prev.copyFrom(pos);
            prevFrame = EntityGeometryFrame.EMPTY;
        } else {
            for (int v = 0; v < verts; v++) {
                int o = v * FLOATS_PER_VERTEX;
                writeVertex(pos.address(), attr.address(), v, src[o], src[o + 1], src[o + 2],
                    Float.floatToRawIntBits(src[o + 3]), Float.floatToRawIntBits(src[o + 6]), src[o + 4], src[o + 5]);
            }
            for (int i = 0; i < indices; i++) {
                int v = idx[i];
                MemoryUtil.memPutInt(index.address() + i * 4L, v >= 0 && v < verts ? v : 0);
            }
            writePrevPositions(src, verts, prev.address());
        }
        packedBytes = verts * 40L + indices * 4L;
        return new Packed(pos, index, attr, prev, verts, indices);
    }

    private static void writeVertex(long pos, long attr, int v, float x, float y, float z,
                                    int color, int light, float u, float texV) {
        long p = pos + v * 12L;
        MemoryUtil.memPutFloat(p, x);
        MemoryUtil.memPutFloat(p + 4, y);
        MemoryUtil.memPutFloat(p + 8, z);
        long a = attr + v * 16L;
        MemoryUtil.memPutInt(a, color);
        MemoryUtil.memPutInt(a + 4, light);
        MemoryUtil.memPutFloat(a + 8, u);
        MemoryUtil.memPutFloat(a + 12, texV);
    }

    /**
     * Previous positions for entity motion vectors: an entity of this frame is matched to the previous frame's entity of the
     * same type and vertex count whose origin (world) is nearest, within 2 blocks (render states carry no stable id in 26.3).
     * Matched: per-vertex previous position (covers rigid motion AND animation); unmatched: no motion (= current position).
     */
    private void writePrevPositions(float[] src, int verts, long dst) {
        for (int v = 0; v < verts; v++) {
            int o = v * FLOATS_PER_VERTEX;
            MemoryUtil.memPutFloat(dst + v * 12L, src[o]);
            MemoryUtil.memPutFloat(dst + v * 12L + 4, src[o + 1]);
            MemoryUtil.memPutFloat(dst + v * 12L + 8, src[o + 2]);
        }
        matchedEntities = 0;
        unmatchedEntities = 0;
        float[] psrc = prevFrame.vertexData();
        float ox = (float) (prevCamX - curCamX);
        float oy = (float) (prevCamY - curCamY);
        float oz = (float) (prevCamZ - curCamZ);
        java.util.List<EntityGeometryFrame.CapturedEntity> prevEntities = prevFrame.entities();
        if (prevUsed.length < prevEntities.size()) {
            prevUsed = new boolean[Math.max(prevEntities.size(), prevUsed.length * 2 + 16)];
        } else {
            java.util.Arrays.fill(prevUsed, 0, prevEntities.size(), false);
        }
        for (EntityGeometryFrame.CapturedEntity e : currentFrame.entities()) {
            EntityGeometryFrame.CapturedEntity best = null;
            int bestIndex = -1;
            double bestD = 4.0;
            if (psrc != null) {
                org.joml.Vector3f c = e.currentTransform().getTranslation(matchCur);
                for (int pi = 0; pi < prevEntities.size(); pi++) {
                    EntityGeometryFrame.CapturedEntity p = prevEntities.get(pi);
                    if (prevUsed[pi] || p.vertexCount() != e.vertexCount() || !p.entityType().equals(e.entityType())) {
                        continue;
                    }
                    if (e.entityId() < -1 && p.entityId() == e.entityId()) {
                        best = p;
                        bestIndex = pi;
                        break;
                    }
                    org.joml.Vector3f q = p.currentTransform().getTranslation(matchPrev);
                    double dx = q.x + ox - c.x;
                    double dy = q.y + oy - c.y;
                    double dz = q.z + oz - c.z;
                    double d = dx * dx + dy * dy + dz * dz;
                    if (d < bestD) {
                        bestD = d;
                        best = p;
                        bestIndex = pi;
                    }
                }
            }
            if (best == null && e.previousTransform() != null && e.currentTransform() != null
                    && !e.previousTransform().equals(e.currentTransform(), 1.0e-6f)) {
                matchInv.set(e.currentTransform());
                if (Math.abs(matchInv.determinant()) > 1.0e-8f) {
                    matchInv.invert();
                    matchCurToPrev.set(e.previousTransform()).mul(matchInv);
                    org.joml.Vector4f tmp = matchPoint;
                    matchedEntities++;
                    for (int k = 0; k < e.vertexCount(); k++) {
                        int cv = e.vertexOffset() + k;
                        int co = cv * FLOATS_PER_VERTEX;
                        if (cv >= verts || co + 2 >= src.length) {
                            break;
                        }
                        tmp.set(src[co], src[co + 1], src[co + 2], 1.0f).mul(matchCurToPrev);
                        MemoryUtil.memPutFloat(dst + cv * 12L, tmp.x + ox);
                        MemoryUtil.memPutFloat(dst + cv * 12L + 4, tmp.y + oy);
                        MemoryUtil.memPutFloat(dst + cv * 12L + 8, tmp.z + oz);
                    }
                    continue;
                }
            }
            if (best == null) {
                unmatchedEntities++;
                // no previous match: sentinel 1e30 -> the shader flags the pixels as disoccluded (DLSS-RR disocclusion mask)
                for (int k = 0; k < e.vertexCount(); k++) {
                    int cv = e.vertexOffset() + k;
                    if (cv >= verts) {
                        break;
                    }
                    MemoryUtil.memPutFloat(dst + cv * 12L, 1.0e30f);
                }
                continue;
            }
            if (bestIndex >= 0) {
                prevUsed[bestIndex] = true;
            }
            matchedEntities++;
            for (int k = 0; k < e.vertexCount(); k++) {
                int cv = e.vertexOffset() + k;
                int pv = (best.vertexOffset() + k) * FLOATS_PER_VERTEX;
                if (cv >= verts || pv + 2 >= psrc.length) {
                    break;
                }
                MemoryUtil.memPutFloat(dst + cv * 12L, psrc[pv] + ox);
                MemoryUtil.memPutFloat(dst + cv * 12L + 4, psrc[pv + 1] + oy);
                MemoryUtil.memPutFloat(dst + cv * 12L + 8, psrc[pv + 2] + oz);
            }
        }
        prevFrame = currentFrame;
        prevCamX = curCamX;
        prevCamY = curCamY;
        prevCamZ = curCamZ;
    }

    synchronized void resetHistory() {
        prevFrame = EntityGeometryFrame.EMPTY;
    }

    public synchronized String formatDiagnostics() {
        return "[VK-ENTITY-CAPTURE] vertices=" + frameVertices + " primitives=" + frameTriangles
            + " packedBytes=" + packedBytes + " matched=" + matchedEntities + " unmatched=" + unmatchedEntities;
    }
}
