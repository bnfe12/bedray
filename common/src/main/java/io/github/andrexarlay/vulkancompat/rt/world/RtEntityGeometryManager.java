package io.github.andrexarlay.vulkancompat.rt.world;

import io.github.andrexarlay.vulkancompat.rt.entity.EntityGeometryFrame;
import io.github.andrexarlay.vulkancompat.rt.entity.EntityGpuFrame;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK10;
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

    public synchronized void upload(EntityGeometryFrame frame, RtMemory mem) {
        setFrame(frame);
        uploadToGpu(mem, null);
    }

    private double curCamX, curCamY, curCamZ;

    /** As {@link #uploadToGpu(RtMemory, RtArena)} with the camera of the frame (previous-position matching of moving entities). */
    public synchronized void uploadToGpu(RtMemory mem, double camX, double camY, double camZ) {
        curCamX = camX;
        curCamY = camY;
        curCamZ = camZ;
        uploadToGpu(mem, null);
        uploadAtlas(mem);
    }

    public synchronized void uploadToGpu(RtMemory mem, RtArena geomArena) {
        if (mem == null) {
            return;
        }

        // Case 1: captured frame (EntityCaptureManager): interleaved 10 floats per vertex
        // [x, y, z, colour(ABGR bits), u, v, light(bits), nx, ny, nz], camera-relative positions
        if (currentFrame != null && currentFrame.vertexData() != null && currentFrame.totalVertices() > 0
                && currentFrame.indexData() != null && currentFrame.totalIndices() >= 3) {
            uploadInterleaved(mem);
            return;
        }
        frameVertices = 0;
        frameTriangles = 0;

        // Case 2: Staged QuadGeometry
        if (!currentFrameQuads.isEmpty()) {
            int qCount = currentFrameQuads.size();
            long vertexBytes = (long) qCount * 4L * 12L;
            long indexBytes = (long) qCount * 6L * 4L;
            long uvBytes = (long) qCount * 4L * 8L;
            long colBytes = (long) qCount * 4L * 4L;
            long lightBytes = (long) qCount * 4L * 4L;

            if (vertexBuf == null || vertexBuf.size < vertexBytes) {
                if (vertexBuf != null) {
                    mem.destroy(vertexBuf);
                }
                vertexBuf = mem.createBuffer("entity_vertex", Math.max(vertexBytes, 1L << 20),
                    VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT | RtMemory.USAGE_AS_INPUT, true);
            }

            if (indexBuf == null || indexBuf.size < indexBytes) {
                if (indexBuf != null) {
                    mem.destroy(indexBuf);
                }
                indexBuf = mem.createBuffer("entity_index", Math.max(indexBytes, 256L << 10),
                    VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT | RtMemory.USAGE_AS_INPUT, true);
                IntBuffer ib = MemoryUtil.memIntBuffer(indexBuf.mapped, 65536 * 6);
                ib.position(0);
                for (int i = 0; i < 65536; i++) {
                    int base = i * 4;
                    ib.put(base);
                    ib.put(base + 1);
                    ib.put(base + 2);
                    ib.put(base + 2);
                    ib.put(base + 3);
                    ib.put(base);
                }
            }

            if (uvBuf == null || uvBuf.size < uvBytes) {
                if (uvBuf != null) {
                    mem.destroy(uvBuf);
                }
                uvBuf = mem.createBuffer("entity_uv", Math.max(uvBytes, 256L << 10),
                    VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT, true);
            }

            if (colBuf == null || colBuf.size < colBytes) {
                if (colBuf != null) {
                    mem.destroy(colBuf);
                }
                colBuf = mem.createBuffer("entity_col", Math.max(colBytes, 256L << 10),
                    VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT, true);
            }

            if (lightBuf == null || lightBuf.size < lightBytes) {
                if (lightBuf != null) {
                    mem.destroy(lightBuf);
                }
                lightBuf = mem.createBuffer("entity_light", Math.max(lightBytes, 256L << 10),
                    VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT, true);
            }

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

    /** Ring of {@link #RING} slices so that the CPU never overwrites data a frame still in flight reads (BLAS build, shader). */
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
    private RtMemory.Buf atlasBuf;
    private RtMemory.Buf rectBuf;
    private long atlasVersion = -1;
    private long ringVertexCap;
    private long ringIndexCap;
    private int ringSlot;
    private int frameVertices;
    private int frameTriangles;
    private long uploadedBytes;

    private void uploadInterleaved(RtMemory mem) {
        float[] src = currentFrame.vertexData();
        int[] idx = currentFrame.indexData();
        int verts = Math.min(currentFrame.totalVertices(), src.length / FLOATS_PER_VERTEX);
        int indices = Math.min(currentFrame.totalIndices(), idx.length) / 3 * 3;
        if (verts <= 0 || indices <= 0) {
            frameVertices = 0;
            frameTriangles = 0;
            return;
        }
        if (ringPos == null || verts > ringVertexCap || indices > ringIndexCap) {
            close(mem);
            ringVertexCap = Math.max(65536L, Long.highestOneBit((long) verts) << 1);
            ringIndexCap = Math.max(98304L, Long.highestOneBit((long) indices) << 1);
            int addr = VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | org.lwjgl.vulkan.VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT;
            ringPos = mem.createBuffer("entity_pos_ring", RING * ringVertexCap * 12L, addr | RtMemory.USAGE_AS_INPUT, true);
            ringIdx = mem.createBuffer("entity_idx_ring", RING * ringIndexCap * 4L, addr | RtMemory.USAGE_AS_INPUT, true);
            // per vertex: colour (uint ABGR) + light (uint, block | sky << 16) + uv (2 floats)
            ringAttr = mem.createBuffer("entity_attr_ring", RING * ringVertexCap * 16L, addr, true);
            ringPrev = mem.createBuffer("entity_prev_ring", RING * ringVertexCap * 12L, addr, true);
        }
        ringSlot = (ringSlot + 1) % RING;
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
        java.util.Set<EntityGeometryFrame.CapturedEntity> used = new java.util.HashSet<>();
        for (EntityGeometryFrame.CapturedEntity e : currentFrame.entities()) {
            EntityGeometryFrame.CapturedEntity best = null;
            double bestD = 4.0;
            if (psrc != null) {
                org.joml.Vector3f c = e.currentTransform().getTranslation(new org.joml.Vector3f());
                for (EntityGeometryFrame.CapturedEntity p : prevFrame.entities()) {
                    if (used.contains(p) || p.vertexCount() != e.vertexCount() || !p.entityType().equals(e.entityType())) {
                        continue;
                    }
                    if (e.entityId() < -1 && p.entityId() == e.entityId()) {
                        best = p;
                        break;
                    }
                    org.joml.Vector3f q = p.currentTransform().getTranslation(new org.joml.Vector3f());
                    double dx = q.x + ox - c.x;
                    double dy = q.y + oy - c.y;
                    double dz = q.z + oz - c.z;
                    double d = dx * dx + dy * dy + dz * dz;
                    if (d < bestD) {
                        bestD = d;
                        best = p;
                    }
                }
            }
            if (best == null && e.previousTransform() != null && e.currentTransform() != null
                    && !e.previousTransform().equals(e.currentTransform(), 1.0e-6f)) {
                org.joml.Matrix4f invCur = new org.joml.Matrix4f(e.currentTransform());
                if (Math.abs(invCur.determinant()) > 1.0e-8f) {
                    invCur.invert();
                    org.joml.Matrix4f curToPrev = new org.joml.Matrix4f(e.previousTransform()).mul(invCur);
                    org.joml.Vector4f tmp = new org.joml.Vector4f();
                    matchedEntities++;
                    for (int k = 0; k < e.vertexCount(); k++) {
                        int cv = e.vertexOffset() + k;
                        int co = cv * FLOATS_PER_VERTEX;
                        if (cv >= verts || co + 2 >= src.length) {
                            break;
                        }
                        tmp.set(src[co], src[co + 1], src[co + 2], 1.0f).mul(curToPrev);
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
            used.add(best);
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

    /** Atlas buffers replaced by a newer version, destroyed once no frame in flight can read them. */
    private final java.util.ArrayDeque<long[]> retiredAtlas = new java.util.ArrayDeque<>();
    private final java.util.ArrayDeque<RtMemory.Buf> retiredBufs = new java.util.ArrayDeque<>();
    private long uploadFrame;
    /** Frames that may still be in flight when a buffer is retired (ring of 3 + margin). */
    private static final int RETIRE_FRAMES = RING + 2;
    private long atlasSwaps;

    private void uploadAtlas(RtMemory mem) {
        if (mem == null) {
            return;
        }
        io.github.andrexarlay.vulkancompat.rt.entity.EntityTextureAtlas atlas = io.github.andrexarlay.vulkancompat.rt.entity.EntityTextureAtlas.INSTANCE;
        uploadFrame++;
        while (!retiredAtlas.isEmpty() && uploadFrame - retiredAtlas.peekFirst()[0] > RETIRE_FRAMES) {
            retiredAtlas.pollFirst();
            mem.destroy(retiredBufs.pollFirst());
            mem.destroy(retiredBufs.pollFirst());
        }
        long v = atlas.version();
        if (v != atlasVersion) {
            // versioned double buffering: the new version goes into FRESH buffers; the old ones stay untouched (frames in
            // flight keep reading them through the StatsBuf addresses of their slot) and are destroyed RETIRE_FRAMES later
            int addr = VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | org.lwjgl.vulkan.VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT;
            int n = io.github.andrexarlay.vulkancompat.rt.entity.EntityTextureAtlas.SIZE;
            RtMemory.Buf na = mem.createBuffer("entity_tex_atlas", (long) n * n * 4L, addr, true);
            RtMemory.Buf nr = mem.createBuffer("entity_tex_rects", io.github.andrexarlay.vulkancompat.rt.entity.EntityTextureAtlas.MAX_SLOTS * 16L, addr, true);
            atlas.copyTo(na.mapped, nr.mapped);
            if (atlasBuf != null) {
                retiredAtlas.addLast(new long[] {uploadFrame});
                retiredBufs.addLast(atlasBuf);
                retiredBufs.addLast(rectBuf);
            }
            atlasBuf = na;
            rectBuf = nr;
            atlasSwaps++;
            atlasVersion = v;
            LOGGER.info("[VK-ENTITY-ATLAS] uploaded version={} slots={} bytes={} addr=0x{}", v, atlas.slotCount(),
                atlasBuf.size, Long.toHexString(atlasBuf.address));
        }
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
            atlasSwaps, retiredAtlas.size(), uploadedBytes, framePosAddress(), frameIndexAddress(), frameAttrAddress(), framePrevAddress(),
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

    /** World shutdown: GPU idle (RtWorld.destroy waits), everything incl. the atlas versions can go. */
    public synchronized void closeAll(RtMemory mem) {
        close(mem);
        for (RtMemory.Buf b : retiredBufs) {
            mem.destroy(b);
        }
        retiredBufs.clear();
        retiredAtlas.clear();
        if (atlasBuf != null) {
            mem.destroy(atlasBuf);
            mem.destroy(rectBuf);
        }
        atlasBuf = rectBuf = null;
        atlasVersion = -1;
    }

    public void close(RtMemory mem) {
        for (RtMemory.Buf b : new RtMemory.Buf[] {ringPos, ringIdx, ringAttr, ringPrev}) {
            if (b != null) {
                mem.destroy(b);
            }
        }
        ringPos = ringIdx = ringAttr = ringPrev = null;
        frameVertices = 0;
        frameTriangles = 0;
        if (vertexBuf != null) {
            mem.destroy(vertexBuf);
            vertexBuf = null;
        }
        if (indexBuf != null) {
            mem.destroy(indexBuf);
            indexBuf = null;
        }
        if (uvBuf != null) {
            mem.destroy(uvBuf);
            uvBuf = null;
        }
        if (colBuf != null) {
            mem.destroy(colBuf);
            colBuf = null;
        }
        if (lightBuf != null) {
            mem.destroy(lightBuf);
            lightBuf = null;
        }
        currentFrame = EntityGeometryFrame.EMPTY;
        currentFrameQuads.clear();
        quadCount = 0;
    }
    /**
     * Returns the last EntityGpuFrame built from uploadToGpu (may be EMPTY if no geometry this frame).
     * The frame's buffer addresses reflect the ring-buffer segment used for the current frame.
     */
    public synchronized EntityGpuFrame getLastGpuFrame() {
        return lastGpuFrame;
    }

    private EntityGpuFrame lastGpuFrame = EntityGpuFrame.EMPTY;

    /**
     * Uploads the current frame geometry to GPU ring-buffer and returns an EntityGpuFrame
     * with real buffer device addresses. This is the single canonical upload path.
     */
    public synchronized EntityGpuFrame uploadAndGetGpuFrame(RtMemory mem, double camX, double camY, double camZ) {
        uploadToGpu(mem, camX, camY, camZ);
        if (ringPos == null || frameVertices <= 0) {
            lastGpuFrame = EntityGpuFrame.EMPTY;
            return lastGpuFrame;
        }
        long slotOffset = ringSlot * ringVertexCap;
        long posAddr  = ringPos  != null ? (ringPos.address  + slotOffset * 12L) : 0L;
        long idxAddr  = ringIdx  != null ? (ringIdx.address  + (long) ringSlot * ringIndexCap * 4L) : 0L;
        long attrAddr = ringAttr != null ? (ringAttr.address + slotOffset * 16L) : 0L;
        long prevAddr = ringPrev != null ? (ringPrev.address + slotOffset * 12L) : 0L;
        long atlAddr  = atlasBuf != null ? atlasBuf.address : 0L;
        long rectAddr = rectBuf  != null ? rectBuf.address  : 0L;
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
