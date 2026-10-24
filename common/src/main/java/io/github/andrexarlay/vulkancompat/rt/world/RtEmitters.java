package io.github.andrexarlay.vulkancompat.rt.world;

import io.github.andrexarlay.vulkancompat.rt.material.MaterialAtlasBaker;
import io.github.andrexarlay.vulkancompat.rt.material.LabPbr;
import io.github.andrexarlay.vulkancompat.rt.sodium.DecodedSection;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VK12;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.FloatBuffer;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Light list for next event estimation of emissive surfaces (both RT modes). Every quad of a built section whose atlas
 * region is emissive in the material atlas (mean emission of a 4x4 texel grid over the quad's UV rectangle > 0.02) becomes
 * an emitter record of 96 bytes:
 * <pre>
 *   vec4 p0   corner (blocks, relative to the emitter origin), w = area (blocks^2)
 *   vec4 e1   edge p1 - p0
 *   vec4 e2   edge p3 - p0; w = sidedness (0/1), 2 = redstone source
 *   vec4 uvc  xy = emitter UV corner, z = mean emission 0..1, w = entity atlas slot + 1 (0 = block atlas)
 *   vec4 duv  xy = UV edge p1 - p0, zw = UV edge p3 - p0
 *   vec4 tint RGB multiplier for entity emitters (block emitters use white)
 * </pre>
 * The origin is the camera block position at the time of the last rebuild; the shader gets the camera position relative to
 * it every frame (frame header, see RtFrameRenderer). The list is rebuilt when the set of built sections or the material atlas
 * changes, into the next of {@link #SEGMENTS} segments of a host-visible buffer (the engine keeps at most two frames in
 * flight, so a segment is never rewritten while a frame may read it). Per-section records are cached by DecodedSection identity.
 */
public final class RtEmitters {
    private static final Logger LOGGER = LoggerFactory.getLogger("VKB-RT");
    public static final int TINT_OFFSET = 20;
    public static final int FLOATS = TINT_OFFSET + 4;
    public static final int BYTES = FLOATS * 4;
    public static final int MAX_EMITTERS = 65536;
    public static final int SEGMENTS = 3;
    public static final float MIN_EMISSION = 0.005f;
    /** Texel scan of a quad: at most this many texels per axis (hi-res packs are sampled with a stride). */
    static final int MAX_SCAN = 32;
    /**
     * Minimum luminous power (area x mean emission x boost, in block faces) of a quad that has any emissive texel: a torch flame
     * (a few texels of a 2 x 10 texel quad), a candle or a lantern bulb shines like a part of a glowstone face, as the
     * vanilla light level 14 suggests; without it they light only a few blocks around.
     */
    public static final float MIN_POWER = 0.2f;
    /** Cap of the power boost (a single emissive texel of a big quad must not become a floodlight). */
    public static final float MAX_BOOST = 64f;
    private static final float[] EMPTY = new float[0];
    private static final long FINGERPRINT_SEED = 0x9E3779B97F4A7C15L;
    private static final long FINGERPRINT_PRIME = 0x100000001B3L;

    private record Entry(DecodedSection src, float[] records) {
    }

    private final RtMemory memory;
    private final RtMemory.Buf buffer;
    private final Map<Long, Entry> cache = new HashMap<>();
    private final Set<Long> seen = new HashSet<>();
    private float[][] heap = new float[1024][FLOATS + 1];
    private int heapSize;
    private int totalCounter;
    private int inRangeCounter;
    private int targetBudget;
    private float curCx, curCy, curCz;
    private float curRange2;
    private MaterialAtlasBaker.Result currentMat;
    private long lastVersion = -1;
    /** Hash of the in-range block emitters in world space; ReSTIR history may only survive while it is unchanged. */
    private long fingerprint = FINGERPRINT_SEED;
    private Object lastMaterials;
    private int segment = -1;
    private int count;
    private int originX;
    private int originY;
    private int originZ;
    private final int[] segOriginX = new int[SEGMENTS];
    private final int[] segOriginY = new int[SEGMENTS];
    private final int[] segOriginZ = new int[SEGMENTS];
    private double lastCamX = Double.NaN;
    private double lastCamY;
    private double lastCamZ;
    private float lastRange = -1f;
    private int lastBudget = -1;
    private int lastEntityCount;
    private int entityInRange;
    private int entityUsed;
    private int inRange;
    private int totalEmitters;
    /**
     * Camera movement (blocks) that triggers a re-selection of the light list. Records are stored relative to the emitter
     * origin and the shader receives the camera position relative to that same origin every frame, so positions stay exact
     * between rebuilds; the threshold only delays the distance-based re-selection, which does not need sub-block precision.
     */
    public static final double MOVE_REBUILD = 0.5;
    private long lastLogNs;
    /** CPU budget of one frame of the light-list scan: a cold scan (pack switch, world change) spans several frames. */
    static final long SCAN_BUDGET_NS = 4_000_000L;
    /** Non-null while a section scan is in progress (spread over frames); the GPU keeps the previous list meanwhile. */
    private java.util.ArrayList<DecodedSection> scanList;
    private int scanCursor;
    private long scanVersion = -1;
    private long scanStartNs;
    /** Snapshot of the static (section) selection, so a frame where only entity lights changed skips the section scan. */
    private float[][] staticHeap = new float[64][FLOATS + 1];
    private int staticHeapSize = -1;
    private int staticTotal;
    private int staticInRange;
    private long staticFingerprint;

    public RtEmitters(RtMemory memory) {
        this.memory = memory;
        this.buffer = memory.createBuffer("rt_emitters", (long) MAX_EMITTERS * BYTES * SEGMENTS,
            VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT, true);
        for (int i = 0; i < heap.length; i++) {
            heap[i] = new float[FLOATS + 1];
        }
        for (int i = 0; i < staticHeap.length; i++) {
            staticHeap[i] = new float[FLOATS + 1];
        }
    }

    private void ensureHeapCapacity(int capacity) {
        if (heap.length < capacity) {
            int newCap = Math.max(capacity, heap.length * 2);
            float[][] newHeap = new float[newCap][];
            System.arraycopy(heap, 0, newHeap, 0, heap.length);
            for (int i = heap.length; i < newCap; i++) {
                newHeap[i] = new float[FLOATS + 1];
            }
            heap = newHeap;
        }
    }

    private void ensureStaticHeapCapacity(int capacity) {
        if (staticHeap.length < capacity) {
            int newCap = Math.max(capacity, staticHeap.length * 2);
            float[][] newHeap = new float[newCap][];
            System.arraycopy(staticHeap, 0, newHeap, 0, staticHeap.length);
            for (int i = staticHeap.length; i < newCap; i++) {
                newHeap[i] = new float[FLOATS + 1];
            }
            staticHeap = newHeap;
        }
    }

    private void addCandidate(float[] r, int o, float dx, float dy, float dz, float importance) {
        float[] rec;
        if (heapSize < targetBudget) {
            rec = heap[heapSize];
            System.arraycopy(r, o, rec, 0, FLOATS);
            rec[0] += dx;
            rec[1] += dy;
            rec[2] += dz;
            rec[FLOATS] = importance;
            siftUp(heapSize);
            heapSize++;
        } else {
            rec = heap[0];
            System.arraycopy(r, o, rec, 0, FLOATS);
            rec[0] += dx;
            rec[1] += dy;
            rec[2] += dz;
            rec[FLOATS] = importance;
            siftDown(0, heapSize);
        }
    }

    static void siftUp(float[][] heap, int index) {
        int cur = index;
        while (cur > 0) {
            int parent = (cur - 1) >>> 1;
            if (heap[cur][FLOATS] < heap[parent][FLOATS]) {
                float[] tmp = heap[cur];
                heap[cur] = heap[parent];
                heap[parent] = tmp;
                cur = parent;
            } else {
                break;
            }
        }
    }

    static void siftDown(float[][] heap, int index, int size) {
        int cur = index;
        while (true) {
            int left = (cur << 1) + 1;
            if (left >= size) {
                break;
            }
            int right = left + 1;
            int smallest = (right < size && heap[right][FLOATS] < heap[left][FLOATS]) ? right : left;
            if (heap[smallest][FLOATS] < heap[cur][FLOATS]) {
                float[] tmp = heap[cur];
                heap[cur] = heap[smallest];
                heap[smallest] = tmp;
                cur = smallest;
            } else {
                break;
            }
        }
    }

    static void sortHeapDescending(float[][] heap, int size) {
        int k = size;
        while (k > 1) {
            float[] tmp = heap[0];
            heap[0] = heap[k - 1];
            heap[k - 1] = tmp;
            k--;
            siftDown(heap, 0, k);
        }
    }

    private void siftUp(int index) {
        siftUp(heap, index);
    }

    private void siftDown(int index, int size) {
        siftDown(heap, index, size);
    }

    private void sortHeapDescending() {
        sortHeapDescending(heap, heapSize);
    }

    /**
     * Mixes one emitter record into the light-list fingerprint. The position is hashed in world space (origin offset added),
     * so a re-anchored list with the same lights hashes identically and its ReSTIR history can be kept.
     */
    static long mixEmitter(long h, float[] r, int o, float ox, float oy, float oz) {
        h = mix(h, Float.floatToIntBits(r[o] + ox));
        h = mix(h, Float.floatToIntBits(r[o + 1] + oy));
        h = mix(h, Float.floatToIntBits(r[o + 2] + oz));
        for (int i = 3; i <= 19; i++) {
            h = mix(h, Float.floatToIntBits(r[o + i]));
        }
        return h;
    }

    private static long mix(long h, int v) {
        return (h ^ v) * FINGERPRINT_PRIME;
    }

    private void processSection(DecodedSection d) {
        seen.add(d.key);
        Entry e = cache.get(d.key);
        if (e == null || e.src != d) {
            e = new Entry(d, currentMat == null ? EMPTY : extract(d, currentMat.material(), currentMat.width(), currentMat.height()));
            cache.put(d.key, e);
        }
        float[] r = e.records;
        int quads = r.length / FLOATS;
        totalCounter += quads;
        float dx = d.sectionX * 16 - originX;
        float dy = d.sectionY * 16 - originY;
        float dz = d.sectionZ * 16 - originZ;
        for (int q = 0; q < quads; q++) {
            int o = q * FLOATS;
            float px = r[o] + dx + 0.5f * (r[o + 4] + r[o + 8]) - curCx;
            float py = r[o + 1] + dy + 0.5f * (r[o + 5] + r[o + 9]) - curCy;
            float pz = r[o + 2] + dz + 0.5f * (r[o + 6] + r[o + 10]) - curCz;
            float d2 = px * px + py * py + pz * pz;
            if (d2 > curRange2) {
                continue;
            }
            inRangeCounter++;
            fingerprint = mixEmitter(fingerprint, r, o, dx + originX, dy + originY, dz + originZ);
            if (targetBudget <= 0) {
                continue;
            }
            float importance = r[o + 14] * r[o + 7] * r[o + 3] / Math.max(d2, 1f);
            if (heapSize >= targetBudget && importance <= heap[0][FLOATS]) {
                continue;
            }
            addCandidate(r, o, dx, dy, dz, importance);
        }
    }

    public int count() {
        return count;
    }

    /**
     * Hash of the block emitters within range of the last rebuild, in world space. Changes only when the lights themselves
     * change (a torch placed or broken, a material or a section edit), not when a distant section streams in or the camera
     * re-anchors the list: the ReSTIR reservoir reset keys off this instead of the world structure version.
     */
    public long fingerprint() {
        return fingerprint;
    }

    /** Device address of the current segment (valid when {@link #count()} > 0). */
    public long address() {
        return buffer.address + (long) Math.max(segment, 0) * MAX_EMITTERS * BYTES;
    }

    public int originX() {
        return segment >= 0 ? segOriginX[segment] : originX;
    }

    public int originY() {
        return segment >= 0 ? segOriginY[segment] : originY;
    }

    public int originZ() {
        return segment >= 0 ? segOriginZ[segment] : originZ;
    }

    /**
     * Rebuilds the light list when the built sections or the materials changed, or the camera moved. Only emitters
     * within {@code range} blocks of the camera are kept, sorted by importance (emission * area / distance^2, descending)
     * and cut to {@code budget}: the shader samples the front of the list more often (index = n * u^2), so the nearest/brightest
     * lights get most of the samples. {@code mat} may be null (no emitters).
     */
    public void update(RtWorld world, MaterialAtlasBaker.Result mat, double camX, double camY, double camZ, float range, int budget) {
        update(world, mat, camX, camY, camZ, range, budget, null, 0);
    }

    /**
     * As above, plus the quad emitters of entities (dropped items, items of entities, the item in the hand): {@code entityQuads}
     * holds {@code entityCount} records of {@link #FLOATS} floats whose positions are relative to the CAMERA (as the entity
     * geometry); they are moved into the frame of the emitter origin here. They change every frame, so a frame with entity
     * emitters always rebuilds the list.
     */
    public void update(RtWorld world, MaterialAtlasBaker.Result mat, double camX, double camY, double camZ, float range, int budget,
                       float[] entityQuads, int entityCount) {
        long version = world.structureVersion();
        if (scanList != null) {
            if (version == scanVersion) {
                continueScan(entityQuads, entityCount);
            } else {
                scanList = null; // sections changed mid-scan: restart from scratch on this frame
                scanCursor = 0;
                startScan(world, mat, camX, camY, camZ, range, budget, version);
                continueScan(entityQuads, entityCount);
            }
            return; // the previous light list stays on the GPU until the scan finishes
        }
        double moved = Double.isNaN(lastCamX) ? Double.MAX_VALUE
            : Math.max(Math.abs(camX - lastCamX), Math.max(Math.abs(camY - lastCamY), Math.abs(camZ - lastCamZ)));
        if (version == lastVersion && mat == lastMaterials && moved <= MOVE_REBUILD && range == lastRange && budget == lastBudget) {
            if (entityCount == 0 && lastEntityCount == 0) {
                return;
            }
            if (staticHeapSize >= 0) {
                // only the entity lights can have changed: restore the static selection and merge them again
                lastCamX = camX;
                lastCamY = camY;
                lastCamZ = camZ;
                lastEntityCount = entityCount;
                curCx = (float) (camX - originX);
                curCy = (float) (camY - originY);
                curCz = (float) (camZ - originZ);
                restoreStatic();
                processEntities(entityQuads, entityCount);
                writeSegment();
                return;
            }
        }
        startScan(world, mat, camX, camY, camZ, range, budget, version);
        continueScan(entityQuads, entityCount);
    }

    private void startScan(RtWorld world, MaterialAtlasBaker.Result mat, double camX, double camY, double camZ, float range,
                           int budget, long version) {
        if (mat != lastMaterials) {
            cache.clear();
        }
        lastVersion = version;
        lastMaterials = mat;
        lastCamX = camX;
        lastCamY = camY;
        lastCamZ = camZ;
        lastRange = range;
        lastBudget = budget;
        lastEntityCount = -1; // scan in progress: publish decides the next-step state
        originX = (int) Math.floor(camX);
        originY = (int) Math.floor(camY);
        originZ = (int) Math.floor(camZ);
        curCx = (float) (camX - originX);
        curCy = (float) (camY - originY);
        curCz = (float) (camZ - originZ);
        curRange2 = range * range;
        currentMat = mat;
        targetBudget = Math.min(Math.max(budget, 0), MAX_EMITTERS);
        if (targetBudget > 0) {
            ensureHeapCapacity(targetBudget);
        }
        heapSize = 0;
        totalCounter = 0;
        inRangeCounter = 0;
        entityInRange = 0;
        seen.clear();
        fingerprint = FINGERPRINT_SEED;
        staticHeapSize = -1;
        scanStartNs = System.nanoTime();
        scanVersion = version;
        scanList = new java.util.ArrayList<>();
        world.forEachBuiltSection(scanList::add);
        // nearest first: after a cold invalidate (pack switch, world change) the world lights up from the camera outwards
        scanList.sort(java.util.Comparator.comparingLong(this::sectionDistance2));
    }

    private long sectionDistance2(DecodedSection d) {
        long dx = (long) d.sectionX * 16 - originX;
        long dy = (long) d.sectionY * 16 - originY;
        long dz = (long) d.sectionZ * 16 - originZ;
        return dx * dx + dy * dy + dz * dz;
    }

    private void continueScan(float[] entityQuads, int entityCount) {
        long deadline = System.nanoTime() + SCAN_BUDGET_NS;
        while (scanCursor < scanList.size()) {
            processSection(scanList.get(scanCursor));
            scanCursor++;
            if (System.nanoTime() > deadline) {
                return;
            }
        }
        scanList = null;
        scanCursor = 0;
        lastEntityCount = entityCount;
        publish(entityQuads, entityCount);
    }

    private void publish(float[] entityQuads, int entityCount) {
        fingerprint = mix(fingerprint, inRangeCounter);
        cache.keySet().retainAll(seen);
        // snapshot the static selection: a frame where only entity lights changed skips the whole section scan
        ensureStaticHeapCapacity(Math.max(targetBudget, heapSize));
        for (int i = 0; i < heapSize; i++) {
            System.arraycopy(heap[i], 0, staticHeap[i], 0, FLOATS + 1);
        }
        staticHeapSize = heapSize;
        staticTotal = totalCounter;
        staticInRange = inRangeCounter;
        staticFingerprint = fingerprint;
        processEntities(entityQuads, entityCount);
        writeSegment();
    }

    private void processEntities(float[] entityQuads, int entityCount) {
        for (int i = 0; i < entityCount; i++) {
            int o = i * FLOATS;
            totalCounter++;
            // camera-relative corner + (camera - origin) = emitter-origin frame; distance from the camera uses the quad centre
            float cx = entityQuads[o] + 0.5f * (entityQuads[o + 4] + entityQuads[o + 8]);
            float cy = entityQuads[o + 1] + 0.5f * (entityQuads[o + 5] + entityQuads[o + 9]);
            float cz = entityQuads[o + 2] + 0.5f * (entityQuads[o + 6] + entityQuads[o + 10]);
            float d2 = cx * cx + cy * cy + cz * cz;
            if (d2 > curRange2) {
                continue;
            }
            inRangeCounter++;
            entityInRange++;
            if (targetBudget <= 0) {
                continue;
            }
            float importance = entityQuads[o + 14] * entityQuads[o + 7] * entityQuads[o + 3] / Math.max(d2, 1f);
            if (heapSize >= targetBudget && importance <= heap[0][FLOATS]) {
                continue;
            }
            addCandidate(entityQuads, o, curCx, curCy, curCz, importance);
        }
    }

    private void restoreStatic() {
        heapSize = staticHeapSize;
        for (int i = 0; i < heapSize; i++) {
            System.arraycopy(staticHeap[i], 0, heap[i], 0, FLOATS + 1);
        }
        totalCounter = staticTotal;
        inRangeCounter = staticInRange;
        fingerprint = staticFingerprint;
    }

    private void writeSegment() {
        sortHeapDescending();
        int n = Math.min(heapSize, MAX_EMITTERS);
        segment = (segment + 1) % SEGMENTS;
        segOriginX[segment] = originX;
        segOriginY[segment] = originY;
        segOriginZ[segment] = originZ;
        long base = buffer.mapped + (long) segment * MAX_EMITTERS * BYTES;
        FloatBuffer out = MemoryUtil.memFloatBuffer(base, MAX_EMITTERS * FLOATS);
        int entityUsedNow = 0;
        for (int k = 0; k < n; k++) {
            out.put(k * FLOATS, heap[k], 0, FLOATS);
            if (heap[k][15] > 0.5f) {
                entityUsedNow++;
            }
        }
        entityUsed = entityUsedNow;
        count = n;
        inRange = inRangeCounter;
        totalEmitters = totalCounter;
        long now = System.nanoTime();
        if (now - lastLogNs > 5_000_000_000L) {
            lastLogNs = now;
            LOGGER.info(String.format(java.util.Locale.ROOT,
                "[VKB-RT] emitters used=%d in_range=%d total=%d entity_quads=%d/%d budget=%d range=%.0f sections=%d rebuild_ms=%.2f origin=(%d,%d,%d)",
                count, inRange, totalEmitters, entityUsed, entityInRange, targetBudget, Math.sqrt((double) curRange2), seen.size(),
                (now - scanStartNs) / 1.0e6, originX, originY, originZ));
        }
    }

    /** Emitters within range of the camera at the last rebuild, and in all built sections. */
    public int inRange() {
        return inRange;
    }

    public int totalEmitters() {
        return totalEmitters;
    }

    /** Entity quad emitters that made it into the light list of the last rebuild (they compete for the budget with the blocks). */
    public int entityUsed() {
        return entityUsed;
    }

    /** Entity quad emitters (items) within range at the last rebuild. */
    public int entityInRange() {
        return entityInRange;
    }

    /**
     * Emitter records of one section, positions section-local (pure, unit-tested through {@link #extractQuads}).
     */
    static float[] extract(DecodedSection d, int[] material, int atlasW, int atlasH) {
        return extractQuads(d.positions, d.uvs, d.colors, d.totalQuads(), material, atlasW, atlasH,
            d.passQuadStart(DecodedSection.PASS_CUTOUT));
    }

    /** {@code positions}: 12 floats per quad, {@code uvs}: 8 floats per quad (DecodedSection layout). */
    public static float[] extractQuads(float[] positions, float[] uvs, int quads, int[] material, int atlasW, int atlasH) {
        return extractQuads(positions, uvs, quads, material, atlasW, atlasH, quads);
    }

    public static float[] extractQuads(float[] positions, float[] uvs, int quads, int[] material, int atlasW, int atlasH, int solidQuads) {
        return extractQuads(positions, uvs, null, quads, material, atlasW, atlasH, solidQuads);
    }

    /** Extracts emitter quads with vertex colors so vertex-tinted redstone uses its actual signal strength. */
    public static float[] extractQuads(float[] positions, float[] uvs, int[] colors, int quads, int[] material, int atlasW, int atlasH,
                                       int solidQuads) {
        float[] tmp = null;
        int n = 0;
        TexelEmission tex = new TexelEmission() {
            @Override
            public int width() {
                return atlasW;
            }

            @Override
            public int height() {
                return atlasH;
            }

            @Override
            public float at(int x, int y) {
                return ((material[y * atlasW + x] >>> 16) & 255) / 255f;
            }

            @Override
            public boolean isRedstoneAt(int x, int y) {
                int m = material[y * atlasW + x];
                return LabPbr.hasSourceFlag(m, LabPbr.SOURCE_REDSTONE);
            }
        };
        for (int q = 0; q < quads; q++) {
            if (tmp == null) {
                tmp = new float[quads * FLOATS];
            }
            // cutout pass quads (torches, lanterns, campfires, fire) are open sources: they radiate to both sides
            int uvOffset = q * 8;
            float redstoneSignal = quadRedstoneSignal(colors, q * 4);
            if (buildQuadRecord(tmp, n * FLOATS, positions, q * 12, uvs, uvOffset, tex, q >= solidQuads, redstoneSignal)) {
                n++;
            }
        }
        if (n == 0) {
            return EMPTY;
        }
        return java.util.Arrays.copyOf(tmp, n * FLOATS);
    }

    /** Emission 0..1 of the texels of one texture region (block atlas material map, or the region of a slot of the entity atlas). */
    public interface TexelEmission {
        int width();

        int height();

        /** Emission of texel (x, y), {@code 0 <= x < width()}, {@code 0 <= y < height()}. */
        float at(int x, int y);

        /** True for material atlas texels tagged as vertex-tinted redstone. */
        default boolean isRedstoneAt(int x, int y) {
            return false;
        }
    }

    private static float quadRedstoneSignal(int[] colors, int offset) {
        if (colors == null || offset + 3 >= colors.length) {
            return 0f; // no vertex signal means we cannot safely treat tagged redstone as powered
        }
        float signal = 0f;
        for (int i = 0; i < 4; i++) {
            int color = colors[offset + i];
            float red = (color & 255) / 255f;
            float green = ((color >>> 8) & 255) / 255f;
            float blue = ((color >>> 16) & 255) / 255f;
            float vertexSignal = red <= 0.30f || red <= green * 1.8f || red <= blue * 1.8f
                ? 0f : Math.max(0f, Math.min(1f, (red - 0.30f) / 0.70f));
            signal += vertexSignal;
        }
        return signal * 0.25f;
    }

    /**
     * Writes the emitter record of one quad (positions: 4 corners x 3 floats at {@code po}, uvs: 4 x 2 at {@code uo}, texture
     * coordinates 0..1 over {@code tex}) to {@code dst[o ..]}; false when the quad has no emissive texel. The texels of the
     * UV rectangle of the quad are scanned (at most {@link #MAX_SCAN} per axis); the emitter is shrunk to the bounding rectangle of
     * the emissive texels, so a torch flame (a 2x2 texel spot on a 2x10 texel quad) is a small quad AT the flame and the light
     * leaves from there, not from the stick. Its power (area x mean emission over that rectangle x boost) is raised to
     * {@link #MIN_POWER} when smaller, capped by {@link #MAX_BOOST}. Layout: see the class comment; {@code uvc.w} (index
     * {@code o + 15}) is 0 (block atlas); entity callers set it to slot + 1.
     */
    public static boolean buildQuadRecord(float[] dst, int o, float[] positions, int po, float[] uvs, int uo, TexelEmission tex,
                                          boolean twoSidedSource) {
        return buildQuadRecord(dst, o, positions, po, uvs, uo, tex, twoSidedSource, 1f);
    }

    private static boolean buildQuadRecord(float[] dst, int o, float[] positions, int po, float[] uvs, int uo, TexelEmission tex,
                                           boolean twoSidedSource, float redstoneSignal) {
        int w = tex.width();
        int h = tex.height();
        float uMin = Float.MAX_VALUE;
        float vMin = Float.MAX_VALUE;
        float uMax = -Float.MAX_VALUE;
        float vMax = -Float.MAX_VALUE;
        for (int v = 0; v < 4; v++) {
            float u = uvs[uo + v * 2];
            float t = uvs[uo + v * 2 + 1];
            uMin = Math.min(uMin, u);
            uMax = Math.max(uMax, u);
            vMin = Math.min(vMin, t);
            vMax = Math.max(vMax, t);
        }
        int x0 = Math.max(0, Math.min(w - 1, (int) Math.floor(uMin * w)));
        int y0 = Math.max(0, Math.min(h - 1, (int) Math.floor(vMin * h)));
        int x1 = Math.max(x0 + 1, Math.min(w, (int) Math.ceil(uMax * w)));
        int y1 = Math.max(y0 + 1, Math.min(h, (int) Math.ceil(vMax * h)));
        int stepX = Math.max(1, (x1 - x0 + MAX_SCAN - 1) / MAX_SCAN);
        int stepY = Math.max(1, (y1 - y0 + MAX_SCAN - 1) / MAX_SCAN);
        // quad geometry: p0, edges to p1 and p3
        float px = positions[po];
        float py = positions[po + 1];
        float pz = positions[po + 2];
        float e1x = positions[po + 3] - px;
        float e1y = positions[po + 4] - py;
        float e1z = positions[po + 5] - pz;
        float e2x = positions[po + 9] - px;
        float e2y = positions[po + 10] - py;
        float e2z = positions[po + 11] - pz;
        float areaFull = cross(e1x, e1y, e1z, e2x, e2y, e2z);
        if (areaFull < 1.0e-6f) {
            return false;
        }
        // uv of p0 and of its two edges, to map a texel centre back to the quad coordinates (a along e1, b along e2):
        // uv = uv0 + A a + B b
        float u0 = uvs[uo];
        float v0 = uvs[uo + 1];
        float ax = uvs[uo + 2] - u0;
        float ay = uvs[uo + 3] - v0;
        float bx = uvs[uo + 6] - u0;
        float by = uvs[uo + 7] - v0;
        float det = ax * by - bx * ay;
        boolean invertible = Math.abs(det) > 1.0e-12f;
        int cols = (x1 - x0 + stepX - 1) / stepX;
        int rows = (y1 - y0 + stepY - 1) / stepY;
        float[] se = new float[cols * rows];
        float[] sa = new float[cols * rows];
        float[] sb = new float[cols * rows];
        float aMin = 1f;
        float aMax = 0f;
        float bMin = 1f;
        float bMax = 0f;
        float sum = 0f;
        int emissive = 0;
        boolean redstoneSource = false;
        float halfU = 0.5f * stepX / w;
        float halfV = 0.5f * stepY / h;
        int k = 0;
        for (int y = y0; y < y1; y += stepY) {
            for (int x = x0; x < x1; x += stepX) {
                boolean redstoneTexel = tex.isRedstoneAt(x, y);
                float e = redstoneTexel ? redstoneSignal : tex.at(x, y);
                redstoneSource |= redstoneTexel;
                se[k] = e;
                float cu = (x + 0.5f * stepX) / w;
                float cv = (y + 0.5f * stepY) / h;
                float a = 0f;
                float b = 0f;
                if (invertible) {
                    a = ((cu - u0) * by - (cv - v0) * bx) / det;
                    b = (ax * (cv - v0) - ay * (cu - u0)) / det;
                }
                sa[k] = a;
                sb[k] = b;
                k++;
                sum += e;
                if (e > MIN_EMISSION) {
                    emissive++;
                    if (invertible) {
                        // the texel footprint (half extents in uv) mapped into quad coordinates
                        float ha = (Math.abs(by) * halfU + Math.abs(bx) * halfV) / Math.abs(det);
                        float hb = (Math.abs(ay) * halfU + Math.abs(ax) * halfV) / Math.abs(det);
                        aMin = Math.min(aMin, a - ha);
                        aMax = Math.max(aMax, a + ha);
                        bMin = Math.min(bMin, b - hb);
                        bMax = Math.max(bMax, b + hb);
                    }
                }
            }
        }
        if (emissive == 0 || sum / k <= MIN_EMISSION) {
            return false;
        }
        if (!invertible) {
            aMin = 0f;
            aMax = 1f;
            bMin = 0f;
            bMax = 1f;
        }
        aMin = Math.max(0f, aMin);
        bMin = Math.max(0f, bMin);
        aMax = Math.min(1f, aMax);
        bMax = Math.min(1f, bMax);
        if (aMax <= aMin || bMax <= bMin) {
            return false;
        }
        // mean emission and brightest texel inside the emitter rectangle
        float inSum = 0f;
        int inCount = 0;
        float best = 0f;
        for (int i = 0; i < k; i++) {
            if (!invertible || (sa[i] >= aMin && sa[i] <= aMax && sb[i] >= bMin && sb[i] <= bMax)) {
                inSum += se[i];
                inCount++;
                best = Math.max(best, se[i]);
            }
        }
        float mean = inCount > 0 ? inSum / inCount : 0f;
        if (mean <= MIN_EMISSION) {
            return false;
        }
        float da = aMax - aMin;
        float db = bMax - bMin;
        float area = areaFull * da * db;
        float boost = redstoneSource ? 1f : powerBoost(area, mean, best);
        float pyOffset = (twoSidedSource && Math.abs(e1y) < 0.02f && Math.abs(e2y) < 0.02f) ? 0.08f : 0.0f;
        dst[o] = px + e1x * aMin + e2x * bMin;
        dst[o + 1] = py + e1y * aMin + e2y * bMin + pyOffset;
        dst[o + 2] = pz + e1z * aMin + e2z * bMin;
        dst[o + 3] = area;
        dst[o + 4] = e1x * da;
        dst[o + 5] = e1y * da;
        dst[o + 6] = e1z * da;
        dst[o + 7] = boost;
        dst[o + 8] = e2x * db;
        dst[o + 9] = e2y * db;
        dst[o + 10] = e2z * db;
        // e2.w: open / small sources (torch, lantern, campfire, fire, any boosted quad) radiate to both sides; solid block faces
        // into their outward hemisphere
        dst[o + 11] = redstoneSource ? 2.0f : ((twoSidedSource || boost > 1.05f) ? 1.0f : 0.0f);
        dst[o + 12] = u0 + ax * aMin + bx * bMin;       // uv of the emitter corner
        dst[o + 13] = v0 + ay * aMin + by * bMin;
        dst[o + 14] = mean;                             // mean texel emission of the emitter rectangle
        dst[o + 15] = 0f;
        dst[o + 16] = ax * da;                          // uv(p1') - uv(p0')
        dst[o + 17] = ay * da;
        dst[o + 18] = bx * db;                          // uv(p3') - uv(p0')
        dst[o + 19] = by * db;
        dst[o + 20] = 1f;                               // tint.rgb defaults to white for block emitters
        dst[o + 21] = 1f;
        dst[o + 22] = 1f;
        dst[o + 23] = 1f;
        return true;
    }

    /**
     * Power boost of an emitter rectangle: the power of half its brightest texel spread over the rectangle (small bright spots
     * get a lift), then at least {@link #MIN_POWER} for area x mean emission x boost; at most {@link #MAX_BOOST}.
     */
    public static float powerBoost(float area, float meanEmission, float bestEmission) {
        float boost = Math.max(1f, bestEmission * 0.5f / meanEmission);
        float power = area * meanEmission * boost;
        if (power < MIN_POWER) {
            boost *= MIN_POWER / Math.max(power, 1.0e-9f);
        }
        return Math.min(boost, MAX_BOOST);
    }

    private static float cross(float ax, float ay, float az, float bx, float by, float bz) {
        float cx = ay * bz - az * by;
        float cy = az * bx - ax * bz;
        float cz = ax * by - ay * bx;
        return (float) Math.sqrt(cx * cx + cy * cy + cz * cz);
    }

    public void destroy() {
        memory.destroy(buffer);
        cache.clear();
        seen.clear();
        currentMat = null;
    }
}
