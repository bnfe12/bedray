package io.github.andrexarlay.vulkancompat.rt.world;

import io.github.andrexarlay.vulkancompat.rt.entity.EntityGeometryFrame;
import io.github.andrexarlay.vulkancompat.rt.entity.EntityGpuFrame;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Captures entity, mob, chest, and dropped item geometry during the frame render
 * and stages it into GPU buffers for dynamic entity BLAS acceleration structures.
 */
public final class RtEntityGeometryManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("VKB-RT-ENTITY");

    public static final class QuadGeometry {
        public final float[] positions = new float[12]; // 4 vertices * 3 (x, y, z)
        public final float[] uvs = new float[8];        // 4 vertices * 2 (u, v)
        public final int[] colors = new int[4];       // 4 vertices ARGB/ABGR
        public final int[] light = new int[4];        // 4 vertices light (block, sky)
    }

    private final List<QuadGeometry> currentFrameQuads = new ArrayList<>();
    private EntityGeometryFrame currentFrame = EntityGeometryFrame.EMPTY;

    private RtMemory.Buf vertexBuf;
    private RtMemory.Buf indexBuf;
    private RtMemory.Buf uvBuf;
    private RtMemory.Buf colBuf;
    private RtMemory.Buf lightBuf;
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

    @Deprecated(forRemoval = true)
    public synchronized void upload(EntityGeometryFrame frame, RtMemory mem) {
        setFrame(frame);
        requireRecordingAwareUpload(mem);
    }

    public synchronized void upload(EntityGeometryFrame frame, RtMemory mem, long commandBuffer) {
        setFrame(frame);
        uploadToGpu(mem, 0, 0, 0, commandBuffer);
    }

    private double curCamX, curCamY, curCamZ;

    private EntityCaptureBuffers captureBuffers;

    /** Legacy callers must provide the exact recording through the new overload. */
    @Deprecated(forRemoval = true)
    public synchronized void uploadToGpu(RtMemory mem, double camX, double camY, double camZ) {
        requireRecordingAwareUpload(mem);
    }

    @Deprecated(forRemoval = true)
    public synchronized void uploadToGpu(RtMemory mem, RtArena ignored) {
        requireRecordingAwareUpload(mem);
    }

    private static void requireRecordingAwareUpload(RtMemory mem) {
        if (mem != null) throw new IllegalStateException(
            "entity GPU upload requires uploadToGpu(memory, camX, camY, camZ, commandBuffer) and completion/discard release");
    }

    /** Begins an exact recording before allocating/writing capture buffers; failures retain ownership for discard. */
    public synchronized void uploadToGpu(RtMemory mem, double camX, double camY, double camZ, long commandBuffer) {
        if (mem == null || commandBuffer == 0) throw new IllegalArgumentException("memory and recording are required");
        if (captureBuffers == null) captureBuffers = new EntityCaptureBuffers(mem);
        if (!captureBuffers.belongsTo(mem)) throw new IllegalStateException("previous device capture owner must be drained/closed");
        captureBuffers.begin(commandBuffer);
        curCamX = camX;
        curCamY = camY;
        curCamZ = camZ;
        uploadGeometry(commandBuffer);
        uploadAtlas(commandBuffer);
    }

    private void uploadGeometry(long commandBuffer) {
        // Case 1: captured frame (EntityCaptureManager): interleaved 10 floats per vertex
        // [x, y, z, colour(ABGR bits), u, v, light(bits), nx, ny, nz], camera-relative positions
        if (currentFrame != null && currentFrame.vertexData() != null && currentFrame.totalVertices() > 0
                && currentFrame.indexData() != null && currentFrame.totalIndices() >= 3) {
            uploadInterleaved(commandBuffer);
            return;
        }
        frameVertices = 0;
        frameTriangles = 0;

        // Case 2: Staged QuadGeometry
        if (!currentFrameQuads.isEmpty()) {
            int qCount = currentFrameQuads.size();
            RtMemory.Buf[] buffers = captureBuffers.quads(commandBuffer, qCount);
            vertexBuf = buffers[0];
            indexBuf = buffers[1];
            uvBuf = buffers[2];
            colBuf = buffers[3];
            lightBuf = buffers[4];
            EntityQuadIndices.write(MemoryUtil.memIntBuffer(indexBuf.mapped, qCount * 6), qCount);

            FloatBuffer vb = MemoryUtil.memFloatBuffer(vertexBuf.mapped, qCount * 12);
            FloatBuffer ub = MemoryUtil.memFloatBuffer(uvBuf.mapped, qCount * 8);
            IntBuffer cb = MemoryUtil.memIntBuffer(colBuf.mapped, qCount * 4);
            IntBuffer lb = MemoryUtil.memIntBuffer(lightBuf.mapped, qCount * 4);
            vb.position(0);
            ub.position(0);
            cb.position(0);
            lb.position(0);

            for (QuadGeometry q : currentFrameQuads) {
                for (int v = 0; v < 4; v++) {
                    vb.put(q.positions[v * 3]);
                    vb.put(q.positions[v * 3 + 1]);
                    vb.put(q.positions[v * 3 + 2]);
                    ub.put(q.uvs[v * 2]);
                    ub.put(q.uvs[v * 2 + 1]);
                    cb.put(q.colors[v]);
                    lb.put(q.light[v]);
                }
            }

            quadCount = qCount;
            if (currentFrame == null || currentFrame == EntityGeometryFrame.EMPTY) {
                EntityGeometryFrame.Builder b = EntityGeometryFrame.builder(0L, 0L);
                b.addEntity(new EntityGeometryFrame.CapturedEntity(0, "quads", new Matrix4f(), new Matrix4f(),
                    0, qCount * 4, 0, qCount * 6, 0, 0, false));
                currentFrame = b.build();
            }
            return;
        }

        quadCount = 0;
    }

    /** Writable slots per buffer generation; exact leases, not this count, decide when a slot is reusable. */
    public static final int RING = 3;
    public static final int FLOATS_PER_VERTEX = 10;
    private RtMemory.Buf ringPos;
    private RtMemory.Buf ringIdx;
    private RtMemory.Buf ringAttr;
    /** Per vertex: the same model vertex in the previous frame, relative to the CURRENT camera (float3); = position if unmatched. */
    private RtMemory.Buf ringPrev;
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
    private RtMemory.Buf atlasBuf;
    private RtMemory.Buf rectBuf;
    private long atlasVersion = -1;
    private long ringVertexCap;
    private long ringIndexCap;
    private int ringSlot;
    private int frameVertices;
    private int frameTriangles;
    private long uploadedBytes;

    private void uploadInterleaved(long commandBuffer) {
        float[] src = currentFrame.vertexData();
        int[] idx = currentFrame.indexData();
        int verts = Math.min(currentFrame.totalVertices(), src.length / FLOATS_PER_VERTEX);
        int indices = Math.min(currentFrame.totalIndices(), idx.length) / 3 * 3;
        if (verts <= 0 || indices <= 0) {
            frameVertices = 0;
            frameTriangles = 0;
            return;
        }
        var geometry = captureBuffers.geometry(commandBuffer, verts, indices);
        ringPos = geometry.positions();
        ringIdx = geometry.indices();
        ringAttr = geometry.attributes();
        ringPrev = geometry.previous();
        ringVertexCap = geometry.vertexCapacity();
        ringIndexCap = geometry.indexCapacity();
        ringSlot = geometry.slot();
        long pos = ringPos.mapped + ringSlot * ringVertexCap * 12L;
        long attr = ringAttr.mapped + ringSlot * ringVertexCap * 16L;
        for (int v = 0; v < verts; v++) {
            int o = v * FLOATS_PER_VERTEX;
            MemoryUtil.memPutFloat(pos + v * 12L, src[o]);
            MemoryUtil.memPutFloat(pos + v * 12L + 4, src[o + 1]);
            MemoryUtil.memPutFloat(pos + v * 12L + 8, src[o + 2]);
            MemoryUtil.memPutInt(attr + v * 16L, Float.floatToRawIntBits(src[o + 3]));
            MemoryUtil.memPutInt(attr + v * 16L + 4, Float.floatToRawIntBits(src[o + 6]));
            MemoryUtil.memPutFloat(attr + v * 16L + 8, src[o + 4]);
            MemoryUtil.memPutFloat(attr + v * 16L + 12, src[o + 5]);
        }
        long ib = ringIdx.mapped + ringSlot * ringIndexCap * 4L;
        for (int i = 0; i < indices; i++) {
            int x = idx[i];
            MemoryUtil.memPutInt(ib + i * 4L, x >= 0 && x < verts ? x : 0);
        }
        writePrevPositions(src, verts, ringPrev.mapped + ringSlot * ringVertexCap * 12L);
        frameVertices = verts;
        frameTriangles = indices / 3;
        uploadedBytes = verts * 40L + indices * 4L;
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

    private long atlasSwaps;

    private void uploadAtlas(long commandBuffer) {
        var source = io.github.andrexarlay.vulkancompat.rt.entity.EntityTextureAtlas.INSTANCE;
        var atlas = captureBuffers.atlas(commandBuffer, source);
        atlasBuf = atlas.texels();
        rectBuf = atlas.rects();
        if (atlasVersion != atlas.version()) {
            atlasVersion = atlas.version();
            atlasSwaps++;
            LOGGER.info("[VK-ENTITY-ATLAS] uploaded version={} slots={} bytes={} addr=0x{}", atlasVersion,
                source.slotCount(), atlasBuf.size, Long.toHexString(atlasBuf.address));
        }
    }

    /** Exact completion/discard proof is supplied by RtFrameRenderer's generation-specific host ticket. */
    public synchronized void releaseRecording(RtMemory memory, long commandBuffer) {
        if (captureBuffers != null && captureBuffers.belongsTo(memory)) captureBuffers.releaseRecording(commandBuffer);
    }

    public synchronized long framePrevAddress() {
        return ringPrev != null && frameTriangles > 0 ? ringPrev.address + ringSlot * ringVertexCap * 12L : 0L;
    }

    public synchronized long atlasAddress() {
        return atlasBuf != null ? atlasBuf.address : 0L;
    }

    public synchronized long rectAddress() {
        return rectBuf != null ? rectBuf.address : 0L;
    }

    /** Upload telemetry for [VK-ENTITY-AS]. */
    public synchronized String uploadTelemetry() {
        return String.format(java.util.Locale.ROOT,
            "atlasSwaps=%d retired=%d uploadBytes=%d posAddr=0x%x idxAddr=0x%x attrAddr=0x%x prevAddr=0x%x atlasSlots=%d atlasAddr=0x%x matched=%d unmatched=%d",
            atlasSwaps, captureBuffers == null ? 0 : captureBuffers.retiredCount(), uploadedBytes, framePosAddress(), frameIndexAddress(), frameAttrAddress(), framePrevAddress(),
            io.github.andrexarlay.vulkancompat.rt.entity.EntityTextureAtlas.INSTANCE.slotCount(), atlasAddress(), matchedEntities, unmatchedEntities);
    }

    /** Device address of this frame's float3 positions (camera-relative), 0 if nothing was uploaded. */
    public synchronized long framePosAddress() {
        return ringPos != null && frameTriangles > 0 ? ringPos.address + ringSlot * ringVertexCap * 12L : 0L;
    }

    public synchronized long frameIndexAddress() {
        return ringIdx != null && frameTriangles > 0 ? ringIdx.address + ringSlot * ringIndexCap * 4L : 0L;
    }

    public synchronized long frameAttrAddress() {
        return ringAttr != null && frameTriangles > 0 ? ringAttr.address + ringSlot * ringVertexCap * 16L : 0L;
    }

    /**
     * A byte range of a device buffer, ready to bind as one Slang {@code StructuredBuffer}. The legacy GLSL path reaches
     * the same records through device addresses in the statistics buffer; a Slang pass names them as descriptors instead,
     * so it needs the buffer and the offset of the slot in play rather than the summed address.
     */
    public record GpuRange(RtMemory.Buf buffer, long offset, long bytes) {
        static final GpuRange NONE = new GpuRange(null, 0L, 0L);

        public boolean present() {
            return buffer != null && buffer.handle != 0L && bytes > 0L;
        }
    }

    /** This frame's camera-relative positions: the ring slice the entity BLAS was built from. */
    public synchronized GpuRange framePositions() {
        return ringPos != null && frameTriangles > 0
            ? new GpuRange(ringPos, ringSlot * ringVertexCap * 12L, (long) frameVertices * 12L)
            : GpuRange.NONE;
    }

    /**
     * Where each vertex of this frame's models stood one frame earlier, still relative to the CURRENT camera, so
     * subtracting {@link #framePositions()} from it is the part's own motion and nothing else. Bound at the same ring
     * slot offset and the same length as the positions, because a motion vector that outlives its model is a lie.
     */
    public synchronized GpuRange framePreviousPositions() {
        return ringPrev != null && frameTriangles > 0
            ? new GpuRange(ringPrev, ringSlot * ringVertexCap * 12L, (long) frameVertices * 12L)
            : GpuRange.NONE;
    }

    /** Three indices per triangle of this frame, bound at the ring slot's own offset. */
    public synchronized GpuRange frameIndices() {
        return ringIdx != null && frameTriangles > 0
            ? new GpuRange(ringIdx, ringSlot * ringIndexCap * 4L, (long) frameTriangles * 12L)
            : GpuRange.NONE;
    }

    /** Colour, light and uv per vertex of this frame. */
    public synchronized GpuRange frameAttributes() {
        return ringAttr != null && frameTriangles > 0
            ? new GpuRange(ringAttr, ringSlot * ringVertexCap * 16L, (long) frameVertices * 16L)
            : GpuRange.NONE;
    }

    /** The RT entity atlas. A version swap builds fresh buffers and retires the old pair, so a bound range stays valid. */
    public synchronized GpuRange atlasTexels() {
        return atlasBuf != null ? new GpuRange(atlasBuf, 0L, atlasBuf.size) : GpuRange.NONE;
    }

    public synchronized GpuRange atlasRects() {
        return rectBuf != null ? new GpuRange(rectBuf, 0L, rectBuf.size) : GpuRange.NONE;
    }

    /** Texels per side of the bound atlas, 0 while nothing has been uploaded (the shader then reads the fallback texel). */
    public synchronized int atlasTexelsPerSide() {
        return atlasBuf != null
            ? io.github.andrexarlay.vulkancompat.rt.entity.EntityTextureAtlas.SIZE : 0;
    }

    public synchronized int frameVertices() {
        return frameVertices;
    }

    public synchronized int frameTriangles() {
        return frameTriangles;
    }

    public synchronized long uploadedBytes() {
        return uploadedBytes;
    }

    public RtMemory.Buf getVertexBuf() {
        return vertexBuf;
    }

    public RtMemory.Buf getIndexBuf() {
        return indexBuf;
    }

    public long getUvAddress() {
        return uvBuf != null ? uvBuf.address : (vertexBuf != null ? vertexBuf.address : 0L);
    }

    public long getColorAddress() {
        return colBuf != null ? colBuf.address : (vertexBuf != null ? vertexBuf.address : 0L);
    }

    public long getLightAddress() {
        return lightBuf != null ? lightBuf.address : (vertexBuf != null ? vertexBuf.address : 0L);
    }

    /** Host drained submissions and excluded resubmission; outstanding capture tickets still refuse close. */
    public synchronized void closeAll(RtMemory mem) { close(mem); }

    public synchronized void close(RtMemory mem) {
        if (captureBuffers != null) {
            if (!captureBuffers.belongsTo(mem)) throw new IllegalStateException("capture owner belongs to another device");
            if (captureBuffers.pendingCount() != 0) throw new IllegalStateException("capture recordings require completion/discard");
            invalidateGpuViews();
            captureBuffers.close();
            captureBuffers = null;
        }
        invalidateGpuViews();
        currentFrame = prevFrame = EntityGeometryFrame.EMPTY;
        currentFrameQuads.clear();
        quadCount = 0;
    }

    private void invalidateGpuViews() {
        ringPos = ringIdx = ringAttr = ringPrev = null;
        atlasBuf = rectBuf = null;
        vertexBuf = indexBuf = uvBuf = colBuf = lightBuf = null;
        atlasVersion = -1;
        frameVertices = frameTriangles = 0;
        lastGpuFrame = EntityGpuFrame.EMPTY;
    }
    /**
     * Returns the last EntityGpuFrame built from uploadToGpu (may be EMPTY if no geometry this frame).
     * The frame's buffer addresses reflect the ring-buffer segment used for the current frame.
     */
    public synchronized EntityGpuFrame getLastGpuFrame() {
        return lastGpuFrame;
    }

    private EntityGpuFrame lastGpuFrame = EntityGpuFrame.EMPTY;

    /** Compatibility no-op for null memory; GPU callers must use the recording-aware overload. */
    @Deprecated(forRemoval = true)
    public synchronized EntityGpuFrame uploadAndGetGpuFrame(RtMemory mem, double camX, double camY, double camZ) {
        requireRecordingAwareUpload(mem);
        return EntityGpuFrame.EMPTY;
    }

    /** Uploads capture storage leased to this recording and returns its borrowed device addresses. */
    public synchronized EntityGpuFrame uploadAndGetGpuFrame(RtMemory mem, double camX, double camY, double camZ,
                                                           long commandBuffer) {
        uploadToGpu(mem, camX, camY, camZ, commandBuffer);
        if (ringPos == null || frameVertices <= 0) {
            lastGpuFrame = EntityGpuFrame.EMPTY;
            return lastGpuFrame;
        }
        long slotOffset = ringSlot * ringVertexCap;
        long posAddr  = ringPos  != null ? (ringPos.address  + slotOffset * 12L) : 0L;
        long idxAddr  = ringIdx  != null ? (ringIdx.address  + (long) ringSlot * ringIndexCap * 4L) : 0L;
        long attrAddr = ringAttr != null ? (ringAttr.address + slotOffset * 16L) : 0L;
        lastGpuFrame = new EntityGpuFrame(
            currentFrame != null ? currentFrame.frameId() : 0L,
            posAddr, idxAddr, attrAddr,
            0L,                     // sceneRecordBufferAddress — not yet implemented
            frameVertices, frameTriangles * 3, frameTriangles,
            getCapturedEntityCount(), 0, currentFrame != null ? currentFrame.geometryGeneration() : 0L, true
        );
        return lastGpuFrame;
    }

    public synchronized String formatDiagnostics() {
        boolean hasAtlas = atlasBuf != null && atlasVersion >= 0;
        return String.format(
            "[VK-ENTITY-UPLOAD] vertices=%d primitives=%d ringSlot=%d uploadedBytes=%d" +
            " atlasReady=%s atlasVersion=%d frameId=%d",
            frameVertices, frameTriangles,
            ringSlot,
            uploadedBytes,
            hasAtlas ? "true" : "false",
            atlasVersion,
            currentFrame != null ? currentFrame.frameId() : -1L
        );
    }
}
