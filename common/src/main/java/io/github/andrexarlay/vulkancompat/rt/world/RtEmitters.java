package io.github.andrexarlay.vulkancompat.rt.world;

import io.github.andrexarlay.vulkancompat.rt.material.MaterialAtlasBaker;
import io.github.andrexarlay.vulkancompat.rt.material.LabPbr;
import io.github.andrexarlay.vulkancompat.rt.sodium.DecodedSection;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Light list for next event estimation of emissive surfaces (both RT modes). Every quad of a built section whose atlas
 * region is emissive in the material atlas (mean emission over the quad's UV rectangle exceeds MIN_EMISSION) becomes
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
 * changes. Each publication has immutable host-visible storage retained by every recording that reads it;
 * completion/discard proof releases those readers. Per-section records are cached by DecodedSection identity.
 */
public final class RtEmitters {
    private static final Logger LOGGER = LoggerFactory.getLogger("VKB-RT");
    public static final int TINT_OFFSET = 20;
    public static final int FLOATS = TINT_OFFSET + 4;
    public static final int BYTES = FLOATS * 4;
    public static final int MAX_EMITTERS = 65536;
    public static final float MIN_EMISSION = 0.005f;
    /** Texel scan of a quad: at most this many texels per axis (hi-res packs are sampled with a stride). */
    static final int MAX_SCAN = 32;
    /**
     * Minimum luminous power at peak emission 1 (area x mean emission x boost, in block faces). Vanilla puts a
     * torch at light level 14 and glowstone at 15, and a glowstone face carries about one block face of power (area 1 x the mean
     * emission of its texture, around 0.65), so a flame must carry about 14/15 of that: 0.6, not the fifth of a face (0.2) this
     * floor used to be, which left a torch lighting a puddle and a cave with a torch in it effectively black (owner report
     * 2026-10-24). Dim materials scale this floor by their brightest texel. At full strength it is only reachable while
     * {@code area * mean emission >= MIN_POWER / MAX_BOOST = 0.0094}; smaller spots stop at the cap and stay dim.
     */
    public static final float MIN_POWER = 0.6f;
    /** Cap of the power boost (a single emissive texel of a big quad must not become a floodlight). */
    public static final float MAX_BOOST = 64f;
    /**
     * Largest quad area (blocks^2) whose boost may turn a solid-pass quad into an open (two-sided) source. A torch-size quad is
     * smaller than this, a block face (1.0) is not, and neither is a face carrying a small emissive spot: the emitter rectangle
     * shrinks to the spot, but the quad is still an opaque face and light must not leave its back side.
     */
    static final float MAX_OPEN_AREA = 0.05f;

    /**
     * Emitter corners on a 1/8192-block grid (at most 6.1e-5 block of shift). A corner on this grid plus the integer offset
     * section*16 - origin is exact in float while |x| < 2048, so the identity key of EmitterTable (origin + record) is the
     * same for every origin. Off the grid, spot-shrunk corners rounded differently per origin, and every rescan that moved
     * the origin gave about 17% of them a new ID: their DI reservoirs failed and the table revision churned.
     */
    static final float CORNER_GRID = 8192f;

    static float snapCorner(float v) {
        return Math.round(v * CORNER_GRID) / CORNER_GRID;
    }

    /**
     * Membership bonus of a light that was in the last published list, in 1/8-octave importance buckets (4 = x1.41): a
     * newcomer must beat it by more than that to evict it. The hard top-K swapped lights of nearly equal importance at the
     * budget edge on every rescan (testbed 2026-11-07: 97 of 512 per publish); each swapped-out light lost its NEE and its
     * DI reservoirs at once, so the light near it jumped. -Dbedray.rt.disableEmitterHysteresis=true sets 0 (A/B check).
     */
    static final int STICKY_BUCKETS = Boolean.getBoolean("bedray.rt.disableEmitterHysteresis") ? 0 : 4;
    /** Sorted membership keys at the start of the current static scan. */
    private long[] scanMembership = new long[0];

    /**
     * Quantized, totally ordered selection score: the 1/8-octave log2 bucket of the importance (float exponent and top
     * three mantissa bits, monotonic), plus the sticky bonus, plus 12 key bits as a deterministic tie-break. The value stays
     * below 2048, where a float is exact to 1/4096, so equal inputs always order the same way.
     */
    static float selectionScore(float importance, long key, boolean sticky) {
        int bucket = (Float.floatToRawIntBits(Math.max(importance, Float.MIN_NORMAL)) >>> 20) + (sticky ? STICKY_BUCKETS : 0);
        return bucket + (key >>> 52) * (1f / 4096f);
    }

    private static final int[] STICK_WORDS = {4, 5, 6, 8, 9, 10, 11, 12, 13, 15};

    /**
     * World-space identity of a record: its snapped corner in world coordinates plus edges, uv corner, sidedness and slot.
     * It depends neither on the emitter origin nor on the emission, so a re-anchored or flickering light stays sticky.
     */
    static long stickKey(float lx, float ly, float lz, float[] r, int o, int ox, int oy, int oz) {
        long h = 0x9E3779B97F4A7C15L;
        h = mix64(h ^ Math.round(((double) ox + lx) * CORNER_GRID));
        h = mix64(h ^ Math.round(((double) oy + ly) * CORNER_GRID));
        h = mix64(h ^ Math.round(((double) oz + lz) * CORNER_GRID));
        for (int w : STICK_WORDS) {
            h = mix64(h ^ Float.floatToIntBits(r[o + w] + 0f));
        }
        return h;
    }

    private static long mix64(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    private static final float[] EMPTY = new float[0];

    private record Entry(DecodedSection src, float[] records) {
    }

    private final EmitterCaptureBuffers buffers;
    private final EmitterFrameSelection frameSelection = new EmitterFrameSelection();
    private final Long2ObjectOpenHashMap<Entry> cache = new Long2ObjectOpenHashMap<>();
    private final LongOpenHashSet seen = new LongOpenHashSet();
    private float[][] heap = new float[1024][FLOATS + 1];
    private int heapSize;
    private int totalCounter;
    private int inRangeCounter;
    private int targetBudget;
    private float curCx, curCy, curCz;
    private float curRange2;
    private MaterialAtlasBaker.Result currentMat;
    private long lastVersion = -1;
    private Object lastMaterials;
    private int materialRevision;
    private int count;
    private int originX;
    private int originY;
    private int originZ;
    private int publishedOriginX, publishedOriginY, publishedOriginZ;
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
     * Movement that re-selects the list once it is published. With every light in range already selected (in_range <=
     * budget, the usual case at budget 2048) a move only changes the lights that cross the range sphere, so a full rescan
     * every half block (the former MOVE_REBUILD = 0.5) republished the table about once a second while walking for nothing: each publish
     * rebuilds the light tree and shifts the sampling pdfs, so lit areas shimmered while moving (owner 2026-11-07). A
     * budget-limited list keeps a short threshold: the cut follows the camera, and the hysteresis keeps it steady.
     */
    static double moveRebuild(float range, boolean budgetLimited) {
        return budgetLimited ? 2.0 : Math.max(4.0, range / 16.0);
    }

    /**
     * CPU budget per frame of a cold scan: no list yet, or the camera jumped past passAbortDistance (teleport, respawn).
     * The 4 ms warm budget spread a 3000-section cold scan over seconds of unlit frames (testbed: 2.5-7.5 s after a
     * teleport into a torch-lit room, which rendered black until the list arrived).
     */
    static final long COLD_SCAN_BUDGET_NS = 16_000_000L;
    /** True while the pass in flight is a cold scan (see COLD_SCAN_BUDGET_NS). */
    private boolean coldScan;
    private long lastLogNs;
    private long maxPublicationNanos;
    private int publicationsInWindow;
    private int reusesInWindow;
    /** CPU budget of one frame of the light-list scan: a cold scan (pack switch, world change) spans several frames. */
    static final long SCAN_BUDGET_NS = 4_000_000L;

    /**
     * Camera movement (blocks) that invalidates a scan in progress: half the scan range, so a pass only restarts for a teleport
     * or a respawn. The snapshot reaches {@code range} blocks around the camera it was taken with, so after half a range the
     * near field of the new position is still its content; anything that keeps moving slower than that (sprinting, flying, even
     * elytra, over the few frames a pass lasts) keeps its progress. A smaller fixed threshold would restart the pass on every
     * frame of a long flight, and a restart publishes nothing, which is the delay this pass exists to remove.
     */
    static double passAbortDistance(float range) {
        return Math.max(16.0, 0.5 * range);
    }
    /** Non-null while a section scan is in progress (spread over frames); the GPU keeps the previous list meanwhile. */
    private ScanPass<DecodedSection> scan;
    /** Per-section step of the pass, hoisted so a frame of the scan allocates nothing. */
    private final java.util.function.Consumer<DecodedSection> scanStep = this::processSection;
    /**
     * Whether the snapshot behind the published list was superseded while it was being scanned. Only diagnostic: the list is
     * published either way, and a fresh pass replaces it as soon as the scan budget allows.
     */
    private boolean scanStale;
    /** Wall time of that scan, so a republish of the same static selection does not report an ever-growing rebuild time. */
    private double scanMs;
    private long scanStartNs;
    /** Snapshot of the static (section) selection, so a frame where only entity lights changed skips the section scan. */
    private float[][] staticHeap = new float[64][FLOATS + 1];
    private int staticHeapSize = -1;
    private int staticTotal;
    private int staticInRange;
    private int staticOriginX, staticOriginY, staticOriginZ;
    private int staticSections;

    public RtEmitters(RtMemory memory) {
        this.buffers = new EmitterCaptureBuffers(memory);
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

    private void processSection(DecodedSection d) {
        seen.add(d.key);
        Entry e = cache.get(d.key);
        if (e == null || e.src != d) {
            e = new Entry(d, currentMat == null ? EMPTY : extract(d, currentMat.material(), currentMat.width(), currentMat.height()));
            cache.put(d.key, e);
        }
        float[] r = e.records;
        if (r.length == 0) return;
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
            if (targetBudget <= 0) {
                continue;
            }
            float importance = r[o + 14] * r[o + 7] * r[o + 3] / Math.max(d2, 1f);
            // cheap reject first: the best score this light could reach (sticky, highest tie-break) cannot enter the heap
            if (heapSize >= targetBudget && selectionScore(importance, -1L, true) <= heap[0][FLOATS]) {
                continue;
            }
            long key = stickKey(r[o] + dx, r[o + 1] + dy, r[o + 2] + dz, r, o, originX, originY, originZ); // == rec[0..2]
            boolean sticky = java.util.Arrays.binarySearch(scanMembership, key) >= 0;
            float score = selectionScore(importance, key, sticky);
            if (heapSize >= targetBudget && score <= heap[0][FLOATS]) {
                continue;
            }
            addCandidate(r, o, dx, dy, dz, score);
        }
    }

    public int count() {
        return count;
    }

    /** Revision of the selected light content, independent of its importance order. Used only by cached PT suffixes. */
    public long fingerprint() {
        var publication = buffers.publication();
        return publication == null ? 0L : publication.revision();
    }

    /** Device address of the immutable published list, or zero for an empty list. */
    public long address() {
        var buffer = buffers.current();
        return buffer == null ? 0L : buffer.address;
    }

    /** Buffer of the immutable published list, or zero for an empty list. */
    public long handle() {
        var buffer = buffers.current();
        return buffer == null ? 0L : buffer.handle;
    }

    /** Publications occupy their own buffer, so descriptor bindings start at zero. */
    public long segmentOffset() {
        return 0L;
    }

    public int originX() {
        return publishedOriginX;
    }

    public int originY() {
        return publishedOriginY;
    }

    public int originZ() {
        return publishedOriginZ;
    }

    /**
     * Rebuilds the light list when the built sections or the materials changed, or the camera moved. Only emitters
     * within {@code range} blocks of the camera are kept, sorted by importance (emission * area / distance^2, descending)
     * and cut to {@code budget}: the shader samples the front of the list more often (index = n * u^2), so the nearest/brightest
     * lights get most of the samples. {@code mat} may be null (no emitters).
     * Before recording GPU reads, call {@link #retainForRecording(long)} and release only after completion/discard.
     */
    public void update(RtWorld world, MaterialAtlasBaker.Result mat, double camX, double camY, double camZ, float range, int budget) {
        update(world, mat, camX, camY, camZ, range, budget, null, 0);
    }

    /**
     * As above, plus the quad emitters of entities (dropped items, items of entities, the item in the hand): {@code entityQuads}
     * holds {@code entityCount} records of {@link #FLOATS} floats whose positions are relative to the CAMERA (as the entity
     * geometry); they are moved into the publication's origin here. They are merged every frame with the last completed
     * static selection, including while a new section scan is in progress.
     */
    public void update(RtWorld world, MaterialAtlasBaker.Result mat, double camX, double camY, double camZ, float range, int budget,
                       float[] entityQuads, int entityCount) {
        long version = world.structureVersion();
        if (scan != null && scan.usableFor(camX, camY, camZ, mat, range, budget)) {
            // Sections streaming in must not restart the pass: a restart publishes nothing, and while chunks load the version
            // changes every frame, so the list would stay at its pre-cave content and a freshly placed torch would light
            // nothing (owner report 2026-10-24). The pass keeps its progress, publishes its slightly stale result, and the
            // next update starts a fresh pass because the version moved on.
            scan.noteVersion(version);
            continueScan(camX, camY, camZ, range, budget, entityQuads, entityCount, false);
            return;
        }
        scan = null; // no pass in flight, or the camera left the frame its distance selection was computed in (teleport)
        double moved = Double.isNaN(lastCamX) ? Double.MAX_VALUE
            : Math.max(Math.abs(camX - lastCamX), Math.max(Math.abs(camY - lastCamY), Math.abs(camZ - lastCamZ)));
        boolean budgetLimited = inRange > targetBudget;
        if (version == lastVersion && mat == lastMaterials && moved <= moveRebuild(range, budgetLimited)
            && range == lastRange && budget == lastBudget) {
            if (entityCount == 0 && lastEntityCount == 0) {
                return;
            }
            if (staticHeapSize >= 0) {
                // Keep the selection camera fixed: repeated sub-threshold moves must eventually trigger a new scan.
                publishSelection(camX, camY, camZ, range, budget, entityQuads, entityCount);
                return;
            }
        }
        coldScan = staticHeapSize < 0 || mat != lastMaterials || moved > passAbortDistance(range);
        boolean refreshPreviousSelection = coldScan || range != lastRange || budget != lastBudget;
        startScan(world, mat, camX, camY, camZ, range, budget, version);
        continueScan(camX, camY, camZ, range, budget, entityQuads, entityCount, refreshPreviousSelection);
    }

    private void startScan(RtWorld world, MaterialAtlasBaker.Result mat, double camX, double camY, double camZ, float range,
                           int budget, long version) {
        if (mat != lastMaterials) {
            cache.clear();
            materialRevision++;
            // Old UVs cannot be evaluated against a newly installed atlas while its section scan is still incomplete.
            staticHeapSize = staticTotal = staticInRange = staticSections = 0;
        }
        lastVersion = version;
        lastMaterials = mat;
        lastCamX = camX;
        lastCamY = camY;
        lastCamZ = camZ;
        lastRange = range;
        lastBudget = budget;
        scanMembership = frameSelection.publishedKeysSnapshot();
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
        scanStartNs = System.nanoTime();
        java.util.ArrayList<DecodedSection> list = new java.util.ArrayList<>();
        world.forEachBuiltSection(list::add);
        // nearest first: after a cold invalidate (pack switch, world change) the world lights up from the camera outwards
        list.sort(java.util.Comparator.comparingLong(this::sectionDistance2));
        scan = new ScanPass<>(list, version, camX, camY, camZ, passAbortDistance(range),
            new EmitterScanConfiguration(mat, range, budget));
    }

    private long sectionDistance2(DecodedSection d) {
        long dx = (long) d.sectionX * 16 - originX;
        long dy = (long) d.sectionY * 16 - originY;
        long dz = (long) d.sectionZ * 16 - originZ;
        return dx * dx + dy * dy + dz * dz;
    }

    private void continueScan(double camX, double camY, double camZ, float range, int budget,
                              float[] entityQuads, int entityCount, boolean refreshPreviousSelection) {
        boolean complete = scan.advance(System.nanoTime() + (coldScan ? COLD_SCAN_BUDGET_NS : SCAN_BUDGET_NS), scanStep);
        if (complete) {
            scanStale = scan.stale();
            scanMs = (System.nanoTime() - scanStartNs) / 1.0e6;
            scan = null;
            completeStaticSelection();
        }
        // Keep the committed table during a warm scan. A camera jump, atlas or settings change
        // needs an immediate refresh, and entity lights still follow the current frame.
        if (complete || refreshPreviousSelection || entityCount != 0 || lastEntityCount != 0) {
            publishSelection(camX, camY, camZ, range, budget, entityQuads, entityCount);
        }
    }

    /**
     * One walk over a snapshot of the built sections, spread over frames by a time budget. The world keeps changing under it
     * ({@link #noteVersion}), and that only marks the result stale: the snapshot sections are immutable, so the pass finishes,
     * publishes what it saw, and the next frame starts a fresh pass. Restarting on a change would starve the list — sections
     * stream in during the whole load, no pass would ever reach its end, and nothing new would ever be published.
     */
    static final class ScanPass<T> {
        private final java.util.List<T> items;
        private final long version;
        private final double camX;
        private final double camY;
        private final double camZ;
        private final double abortDistance;
        private final EmitterScanConfiguration configuration;
        private int cursor;
        private boolean stale;

        ScanPass(java.util.List<T> items, long version, double camX, double camY, double camZ, double abortDistance) {
            this(items, version, camX, camY, camZ, abortDistance, null);
        }

        ScanPass(java.util.List<T> items, long version, double camX, double camY, double camZ, double abortDistance,
                 EmitterScanConfiguration configuration) {
            this.items = items;
            this.version = version;
            this.camX = camX;
            this.camY = camY;
            this.camZ = camZ;
            this.abortDistance = abortDistance;
            this.configuration = configuration;
        }

        /** World version of this frame: a different one marks the pass stale without throwing its progress away. */
        void noteVersion(long current) {
            if (current != version) {
                stale = true;
            }
        }

        /** True when the world changed after the pass started: its result is published anyway, then superseded. */
        boolean stale() {
            return stale;
        }

        /**
         * False once the camera left the frame the snapshot's distance selection was computed in (a teleport, a respawn): the
         * pass would publish a list weighted around a place the player is no longer at, so the caller starts a new one.
         */
        boolean usableFor(double x, double y, double z) {
            return Math.max(Math.abs(x - camX), Math.max(Math.abs(y - camY), Math.abs(z - camZ))) <= abortDistance;
        }

        boolean usableFor(double x, double y, double z, Object materials, float range, int budget) {
            return configuration != null && configuration.matches(materials, range, budget) && usableFor(x, y, z);
        }

        /**
         * Processes items until the snapshot is exhausted (true) or the deadline passes (false). A frame always processes at
         * least one item, so a snapshot larger than the whole budget cannot stall the pass.
         */
        boolean advance(long deadlineNs, java.util.function.Consumer<T> step) {
            while (cursor < items.size()) {
                step.accept(items.get(cursor));
                cursor++;
                if (cursor < items.size() && System.nanoTime() > deadlineNs) {
                    return false;
                }
            }
            return true;
        }
    }

    private void completeStaticSelection() {
        var cachedKeys = cache.keySet().iterator();
        while (cachedKeys.hasNext()) {
            if (!seen.contains(cachedKeys.nextLong())) cachedKeys.remove();
        }
        // snapshot the static selection: a frame where only entity lights changed skips the whole section scan
        ensureStaticHeapCapacity(Math.max(targetBudget, heapSize));
        for (int i = 0; i < heapSize; i++) {
            System.arraycopy(heap[i], 0, staticHeap[i], 0, FLOATS + 1);
        }
        staticHeapSize = heapSize;
        staticTotal = totalCounter;
        staticInRange = inRangeCounter;
        staticOriginX = originX;
        staticOriginY = originY;
        staticOriginZ = originZ;
        staticSections = seen.size();
    }

    private void publishSelection(double camX, double camY, double camZ, float range, int budget,
                                  float[] entityQuads, int entityCount) {
        long publicationStarted = System.nanoTime();
        var frame = frameSelection.compose(staticHeap, Math.max(0, staticHeapSize), staticOriginX, staticOriginY, staticOriginZ,
            camX, camY, camZ, range, budget, entityQuads, entityCount);
        int n = frame.count();
        boolean published = buffers.publish(frame.records(), n, frame.originX(), frame.originY(), frame.originZ(), materialRevision);
        if (published) {
            frameSelection.recordPublished(frame);
            publicationsInWindow++;
        } else {
            reusesInWindow++;
        }
        publishedOriginX = frame.originX();
        publishedOriginY = frame.originY();
        publishedOriginZ = frame.originZ();
        int entityUsedNow = 0;
        for (int k = 0; k < n; k++) {
            if (frame.records()[k][15] > 0.5f) {
                entityUsedNow++;
            }
        }
        entityUsed = entityUsedNow;
        entityInRange = frame.entityInRange();
        lastEntityCount = entityCount;
        count = n;
        inRange = staticInRange + entityInRange;
        totalEmitters = staticTotal + entityCount;
        long now = System.nanoTime();
        long publicationNanos = now - publicationStarted;
        maxPublicationNanos = Math.max(maxPublicationNanos, publicationNanos);
        if (now - lastLogNs > 5_000_000_000L) {
            lastLogNs = now;
            var identity = buffers.publication();
            LOGGER.info("[VKB-EMITTERS] table=BEM2 revision={} material_revision={} selected={} retained={} added={} removed={} bytes={} max_probe={} global_fraction={} bvh_depth={} topology_reused={} table_reused={} publications={} reuses={} selection_publish_ms={} selection_publish_max_ms={}",
                identity.revision(), materialRevision, n, published ? identity.retained() : n,
                published ? identity.added() : 0, published ? identity.removed() : 0,
                identity.data().remaining(), identity.maxProbe(), identity.globalFraction(), identity.bvhDepth(),
                !published || identity.topologyReused(), !published, publicationsInWindow, reusesInWindow,
                publicationNanos / 1.0e6, maxPublicationNanos / 1.0e6);
            maxPublicationNanos = 0;
            publicationsInWindow = reusesInWindow = 0;
            LOGGER.info(String.format(java.util.Locale.ROOT,
                "[VKB-RT] emitters used=%d in_range=%d total=%d entity_quads=%d/%d budget=%d range=%.0f sections=%d rebuild_ms=%.2f stale=%b cold=%b origin=(%d,%d,%d) scan_pending=%b scan_processed=%d scan_total=%d scan_age_ms=%.2f",
                count, inRange, totalEmitters, entityUsed, entityInRange, targetBudget, Math.sqrt((double) curRange2), staticSections,
                scanMs, scanStale, coldScan, publishedOriginX, publishedOriginY, publishedOriginZ, scan != null,
                scan == null ? 0 : scan.cursor, scan == null ? 0 : scan.items.size(),
                scan == null ? 0.0 : (now - scanStartNs) / 1.0e6));
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
        EmissiveTexelIndex candidates = EmissiveTexelIndex.of(material, atlasW, atlasH,
            io.github.andrexarlay.vulkancompat.rt.material.RtMaterialAtlas.cpuMaterialVersion());
        return extractQuads(d.positions, d.uvs, d.colors, d.light, d.totalQuads(), material, atlasW, atlasH,
            d.passQuadStart(DecodedSection.PASS_CUTOUT), candidates);
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
        return extractQuads(positions, uvs, colors, null, quads, material, atlasW, atlasH, solidQuads,
            EmissiveTexelIndex.build(material, atlasW, atlasH));
    }

    static float[] extractQuads(float[] positions, float[] uvs, int[] colors, int[] light, int quads, int[] material,
                                        int atlasW, int atlasH, int solidQuads, EmissiveTexelIndex candidates) {
        float[] tmp = null;
        int[] levels = null;
        float[] peaks = null;
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
        // Most quads of a section do not glow: reject them in O(1) from the atlas index before the texel scan.
        int[] rect = new int[4];
        for (int q = 0; q < quads; q++) {
            int uvOffset = q * 8;
            EmissiveTexelIndex.texelRect(uvs, uvOffset, atlasW, atlasH, rect);
            if (!candidates.anyCandidate(rect[0], rect[1], rect[2], rect[3])) {
                continue;
            }
            if (tmp == null) {
                tmp = new float[quads * FLOATS];
                levels = new int[quads];
                peaks = new float[quads];
            }
            // cutout pass quads (torches, lanterns, campfires, fire) are open sources: they radiate to both sides
            float redstoneSignal = quadRedstoneSignal(colors, q * 4);
            float peak = buildQuadRecordPeak(tmp, n * FLOATS, positions, q * 12, uvs, uvOffset, tex, q >= solidQuads, redstoneSignal);
            if (peak > 0f) {
                levels[n] = vertexBlockLightLevel(light, q * 4);
                peaks[n] = peak;
                n++;
            }
        }
        if (n == 0) {
            return EMPTY;
        }
        if (light != null) {
            normalizeOpenSourcePower(tmp, levels, peaks, n);
        }
        return java.util.Arrays.copyOf(tmp, n * FLOATS);
    }

    /**
     * Total power (sum of area x mean emission x boost of its open quads) a block cell of open sources carries at vanilla
     * light level 15; a cell at level L gets {@code OPEN_SOURCE_POWER_15 * (L / 15)^2}. The power of an open source used
     * to be per quad - each flame quad lifted to {@link #MIN_POWER} - so a cell's light grew with the number of quads its
     * model has, not with the light it gives in the game: four candles (level 12, a dozen flame quads) out-shone a lantern
     * (level 15) several times (owner screenshot, 2026-11-12: "the candles are very bright"). Squared because the reach
     * of a 1/d^2 source to a fixed brightness grows with the square root of its power, and a vanilla level is a reach in
     * blocks. Calibrated between the torch (level 14, one quad, 0.6 before this rule: now 1.4) and the lantern (level 15, four quads, 2.4 before: now 1.6), autotest J
     * 2026-11-12): it keeps its power, a torch (level 14, one quad, 0.6 before) rises to 2.1, four candles (level 12,
     * twenty quads, 12.0 before) fall to 1.5.
     */
    static final float OPEN_SOURCE_POWER_15 = 1.6f;
    private static final java.util.concurrent.atomic.AtomicInteger LOGGED_CELLS = new java.util.concurrent.atomic.AtomicInteger();
    private static final int MAX_LOGGED_CELLS = 32;

    /** Vanilla block-light level 0..15 of a quad: the brightest of its four vertices (Sodium stores level * 16 + 8). */
    static int vertexBlockLightLevel(int[] light, int offset) {
        if (light == null || offset + 3 >= light.length) {
            return -1;
        }
        int level = 0;
        for (int i = 0; i < 4; i++) {
            level = Math.max(level, (light[offset + i] & 0xFF) >>> 4);
        }
        return Math.min(level, 15);
    }

    /** Rescales the open (two-sided, non-redstone) records of every block cell to the power of the cell's light level. */
    static void normalizeOpenSourcePower(float[] r, int[] levels, float[] peaks, int n) {
        Map<Integer, float[]> cells = new HashMap<>();   // cell -> {power, level, quads}
        int[] cellOf = new int[n];
        for (int i = 0; i < n; i++) {
            int o = i * FLOATS;
            cellOf[i] = Integer.MIN_VALUE;
            // Weak authored emitters (fungi, roots, spores) have no vanilla source light level. Their vertex light
            // comes from neighbours; retain their intrinsic area/mean/boost instead of calibrating it as a lamp.
            if (r[o + 11] != 1.0f || levels[i] < 0 || peaks[i] < 1f) {
                continue;
            }
            int cx = (int) Math.floor(r[o] + 0.5f * (r[o + 4] + r[o + 8]));
            int cy = (int) Math.floor(r[o + 1] + 0.5f * (r[o + 5] + r[o + 9]));
            int cz = (int) Math.floor(r[o + 2] + 0.5f * (r[o + 6] + r[o + 10]));
            int cell = ((cx + 1) & 0x3F) | (((cy + 1) & 0x3F) << 6) | (((cz + 1) & 0x3F) << 12);
            cellOf[i] = cell;
            float[] c = cells.computeIfAbsent(cell, k -> new float[3]);
            c[0] += r[o + 3] * r[o + 14] * r[o + 7];
            c[1] = Math.max(c[1], levels[i]);
            c[2] += 1f;
        }
        for (Map.Entry<Integer, float[]> e : cells.entrySet()) {
            float[] c = e.getValue();
            float level = Math.max(c[1], 1f);
            float target = OPEN_SOURCE_POWER_15 * (level / 15f) * (level / 15f);
            float scale = c[0] > 1.0e-6f ? target / c[0] : 1f;
            c[0] = scale;
            if (LOGGED_CELLS.getAndIncrement() < MAX_LOGGED_CELLS) {
                LOGGER.info("[VKB-RT] emitters open_cell level={} quads={} power={} target={} scale={}",
                    (int) c[1], (int) c[2], String.format(java.util.Locale.ROOT, "%.3f", target / scale),
                    String.format(java.util.Locale.ROOT, "%.3f", target), String.format(java.util.Locale.ROOT, "%.3f", scale));
            }
        }
        for (int i = 0; i < n; i++) {
            if (cellOf[i] == Integer.MIN_VALUE) {
                continue;
            }
            int o = i * FLOATS;
            r[o + 7] = Math.max(Math.min(r[o + 7] * cells.get(cellOf[i])[0], MAX_BOOST * 2f), 1.0e-4f);
        }
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
     * leaves from there, not from the stick. Full-strength lamps receive the small-source power compensation, capped by
     * {@link #MAX_BOOST}; weak authored texels retain their area-integrated emission. Layout: see the class comment; {@code uvc.w} (index
     * {@code o + 15}) is 0 (block atlas); entity callers set it to slot + 1.
     */
    public static boolean buildQuadRecord(float[] dst, int o, float[] positions, int po, float[] uvs, int uo, TexelEmission tex,
                                          boolean twoSidedSource) {
        return buildQuadRecordPeak(dst, o, positions, po, uvs, uo, tex, twoSidedSource, 1f) > 0f;
    }

    private static float buildQuadRecordPeak(float[] dst, int o, float[] positions, int po, float[] uvs, int uo, TexelEmission tex,
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
            return 0f;
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
            return 0f;
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
            return 0f;
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
            return 0f;
        }
        float da = aMax - aMin;
        float db = bMax - bMin;
        float area = areaFull * da * db;
        float boost = redstoneSource ? 1f : powerBoost(area, mean, best);
        float pyOffset = (twoSidedSource && Math.abs(e1y) < 0.02f && Math.abs(e2y) < 0.02f) ? 0.08f : 0.0f;
        dst[o] = snapCorner(px + e1x * aMin + e2x * bMin);
        dst[o + 1] = snapCorner(py + e1y * aMin + e2y * bMin + pyOffset);
        dst[o + 2] = snapCorner(pz + e1z * aMin + e2z * bMin);
        dst[o + 3] = area;
        dst[o + 4] = e1x * da;
        dst[o + 5] = e1y * da;
        dst[o + 6] = e1z * da;
        dst[o + 7] = boost;
        dst[o + 8] = e2x * db;
        dst[o + 9] = e2y * db;
        dst[o + 10] = e2z * db;
        // e2.w: open / small sources (torch, lantern, campfire, fire) radiate to both sides; solid block faces only into their
        // outward hemisphere. A boost alone does not make a quad open: with MIN_POWER at 0.6 a solid face of mean emission 0.4 to
        // 0.6 is lifted as well, and an open flag gives it back-hemisphere emission (cos floor 0.25) and a shadow ray that stops
        // short of the surface, which leaks light through slabs and carpets. The test is the whole quad (a block face is 1.0),
        // not the shrunk emitter rectangle: a small bright spot on an opaque face must not open the face either.
        dst[o + 11] = redstoneSource ? 2.0f
            : ((twoSidedSource || (areaFull < MAX_OPEN_AREA && boost > 1.05f)) ? 1.0f : 0.0f);
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
        return best;
    }

    /**
     * Power compensation for full-strength lamp texels. Weak authored emission retains its integrated area and mean:
     * compensating sparse spores as lamp flames made each canopy face light an entire room. Full-strength torch flames
     * retain {@link #MIN_POWER}; their boosts remain capped by {@link #MAX_BOOST}.
     */
    public static float powerBoost(float area, float meanEmission, float bestEmission) {
        if (bestEmission < 1f) {
            return 1f;
        }
        float boost = Math.max(1f, bestEmission * 0.5f / meanEmission);
        float power = area * meanEmission * boost;
        float minimumPower = MIN_POWER * Math.max(0f, Math.min(bestEmission, 1f));
        if (power < minimumPower) {
            boost *= minimumPower / Math.max(power, 1.0e-9f);
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
        buffers.close();
        count = inRange = totalEmitters = entityUsed = entityInRange = 0;
        cache.clear();
        seen.clear();
        currentMat = null;
        scan = null; // the snapshot would otherwise keep every decoded section of the pass alive
    }

    /** Called before a pass records reads of the currently published list. */
    public void retainForRecording(long commandBuffer) { buffers.retain(commandBuffer); }

    /** Called only by this recording's completion/discard action. */
    public void releaseRecording(long commandBuffer) { buffers.releaseRecording(commandBuffer); }
}
