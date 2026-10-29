package io.github.andrexarlay.vulkancompat.rt.world;

import io.github.andrexarlay.vulkancompat.rt.scene.BedraySceneLayouts;
import io.github.andrexarlay.vulkancompat.rt.scene.BedraySceneNative;
import io.github.andrexarlay.vulkancompat.rt.slang.SlangPassLibrary;
import io.github.andrexarlay.vulkancompat.rt.slang.SlangRuntime;
import io.github.andrexarlay.vulkancompat.rt.sodium.DecodedSection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Host facade over the scene exports in {@code bedray_core.dll}: owner of the section BLAS,
 * the TLAS and the 64-byte {@code GeometrySection} table.
 *
 * <p>The scene never creates a device, submits or waits.  It records uploads,
 * BLAS/TLAS builds and barriers into the command buffer {@link RtWorld} already
 * has, and publishes the TLAS plus the section-table buffer this class hands to
 * the path trace pass as a borrowed {@link RtMemory.Buf}.
 *
 * <p>Java paces CPU captures and serializes POD input. C++ is the sole owner of
 * AS builds and scene GPU resources in native mode. Update failures remain queued
 * and propagate to the host; geometry is never silently omitted.
 *
 * <p>Lifetime: every prepared frame is queued on the host encoder's destruction
 * queue, which flushes an entry only after the submission that consumed it has
 * been waited for. That wait is the GPU-completion proof the scene requires
 * before it recycles the frame's transient uploads. Reusing a command-buffer
 * address is not a usable proof here: the shared encoder interleaves many
 * allocations per frame, so an address rarely comes back, and a 3-slot ring
 * fills within a few frames. Remaining frames are released after the host
 * drained submissions. The scene refuses destruction while a frame is
 * unreleased.
 */
final class NativeSceneBridge implements AutoCloseable, NativeSectionCapture.Sink {
    private static final Logger LOGGER = LoggerFactory.getLogger("VKB-RT");

    /** AOT program that decodes the native record; the bridge is pointless without it. */
    static final String SCENE_PASS = "path_trace_scene";

    /**
     * The scene's frame ring. The backend keeps two command pools and hands out a
     * fresh buffer whenever every buffer of the current pool is still in use, so
     * warm-up can present more distinct command-buffer addresses than a three-deep
     * ring accepts; the ABI maximum keeps the scene alive instead of disabling it.
     */
    private static final int FRAMES_IN_FLIGHT = BedraySceneLayouts.MAX_FRAMES_IN_FLIGHT;
    private static final int MAX_ENTITIES = 1;
    private static final int TABLE_BYTES = 4096;
    private static final long MAX_GEOMETRY_BYTES = 48L << 20;
    private static final long ENTITY_ID = 1L;
    private static final long LOG_ACTIVE_NANOS = 2_000_000_000L;
    /** {@code HARDWARE_INSTANCE_MASK_ENTITIES}: the same bit the legacy entity TLAS instance carries. */
    private static final int ENTITY_INSTANCE_MASK = 0x02;
    private static final float[] IDENTITY_TRANSFORM = {
        1f, 0f, 0f, 0f,
        0f, 1f, 0f, 0f,
        0f, 0f, 1f, 0f
    };

    private record PendingSection(DecodedSection section, int[] tagged) {
    }

    private final BedraySceneNative library;
    private final long scene;
    private final Map<Long, Long> sectionIds = new LinkedHashMap<>();
    private final Map<Long, PendingSection> updates = new LinkedHashMap<>();
    private final Map<Long, Long> removals = new LinkedHashMap<>();
    /** Frame indices the scene accepted and has not released yet. */
    private final Set<Long> frames = new LinkedHashSet<>();
    private final Map<Long, Long> recordingFrames = new LinkedHashMap<>();
    private long nextSectionId = 1;
    private long nextRevision = 1;
    private boolean entityPresent;
    private long tlas;
    private RtMemory.Buf table;
    private int sectionCount;
    private boolean activeThisFrame;
    private long lastLogNanos;
    private long lastInstances;
    private int lastBlasBuilds;
    private int entityTriangles;
    private double lastViewX;
    private double lastViewY;
    private double lastViewZ;
    private boolean closed;

    private NativeSceneBridge(BedraySceneNative library, long scene) {
        this.library = library;
        this.scene = scene;
    }

    /**
     * Loads the optional scene exports from the already validated core DLL.
     * Returns null (with one log line) when the feature is unavailable; the caller
     * keeps the legacy scene in that case.
     */
    static NativeSceneBridge create(SlangRuntime runtime) {
        if (runtime == null) {
            return null;
        }
        if (!SlangPassLibrary.available(SCENE_PASS)) {
            LOGGER.warn("[VKB-RT] vkbridge.rt.nativeScene=true but AOT pass {} is missing from the jar;"
                + " keeping the Java scene", SCENE_PASS);
            return null;
        }
        BedraySceneNative library = null;
        try {
            library = BedraySceneNative.load(runtime.library().libraryPath());
            int capabilities = BedraySceneLayouts.CAP_ACCELERATION_STRUCTURE | BedraySceneLayouts.CAP_DEVICE_ADDRESS;
            long scene = library.create(runtime.instance().address(), runtime.physicalDevice().address(),
                runtime.device().address(), runtime.apiVersion(), runtime.getInstanceProcAddr(),
                runtime.getDeviceProcAddr(), capabilities, FRAMES_IN_FLIGHT, RtConfig.MAX_INSTANCES,
                MAX_ENTITIES, RtConfig.MAX_INSTANCES, TABLE_BYTES, MAX_GEOMETRY_BYTES);
            LOGGER.info("[VKB-RT] native scene ready: dll={} abi={} frames={} max_sections={} max_geometry_mb={}",
                library.libraryPath().getFileName(), BedraySceneLayouts.ABI_VERSION, FRAMES_IN_FLIGHT,
                RtConfig.MAX_INSTANCES, MAX_GEOMETRY_BYTES >> 20);
            return new NativeSceneBridge(library, scene);
        } catch (RuntimeException e) {
            LOGGER.error("[VKB-RT] native scene unavailable: {}; keeping the Java scene", e.getMessage(), e);
            if (library != null) {
                try {
                    library.close();
                } catch (RuntimeException closeFailure) {
                    e.addSuppressed(closeFailure);
                }
            }
            return null;
        }
    }

    /** True while the native scene owns the TLAS and the table the path tracer binds. */
    boolean activeThisFrame() {
        return activeThisFrame;
    }

    long tlas() {
        return tlas;
    }

    RtMemory.Buf sectionTable() {
        return table;
    }

    int sectionCount() {
        return sectionCount;
    }

    int instanceCount() { return Math.toIntExact(lastInstances); }
    int blasBuilds() { return lastBlasBuilds; }
    int entityTriangles() { return entityTriangles; }
    long residentBytes() { return library.residentBytes(scene); }

    /**
     * Queues a paced CPU section capture. The bridge keeps the same
     * id for the section across rebuilds, so the native record's identity (and
     * therefore the path tracer's history) survives a re-mesh.
     */
    @Override public void sectionBuilt(DecodedSection section, int[] tagged) {
        Long removed = removals.remove(section.key);
        if (removed != null) {
            sectionIds.put(section.key, removed);
        } else if (!sectionIds.containsKey(section.key)) {
            sectionIds.put(section.key, nextSectionId++);
        }
        // RtWorld reuses one per-quad scratch array for every section of the frame,
        // so the queue has to keep its own copy until the scene copies it.
        int[] flags = tagged == null ? null : Arrays.copyOf(tagged, section.totalQuads());
        updates.put(section.key, new PendingSection(section, flags));
    }

    /** Queues the removal of a section the rescan evicted or that Sodium dropped. */
    @Override public void sectionRemoved(long key) {
        Long id = sectionIds.remove(key);
        if (id == null) {
            return;
        }
        updates.remove(key);
        removals.put(key, id);
    }

    /**
     * Feeds this frame's captured models as one entity.  The ring is host-visible
     * and rebuilt every frame, so both revisions advance with the frame index and
     * the scene rebuilds the entity BLAS exactly as the legacy path does.
     */
    void updateEntities(long frameIndex) {
        RtEntityGeometryManager manager = RtEntityGeometryManager.INSTANCE;
        RtEntityGeometryManager.GpuRange positions = manager.framePositions();
        RtEntityGeometryManager.GpuRange indices = manager.frameIndices();
        if (!positions.present() || !indices.present()
            || positions.buffer().mapped == 0L || indices.buffer().mapped == 0L) {
            removeEntity();
            return;
        }
        long vertexCount = positions.bytes() / 12L;
        long indexCount = indices.bytes() / 4L;
        if (vertexCount <= 0L || indexCount <= 0L) {
            removeEntity();
            return;
        }
        MemorySegment vertexStream = MemorySegment.ofAddress(positions.buffer().mapped + positions.offset());
        MemorySegment indexStream = MemorySegment.ofAddress(indices.buffer().mapped + indices.offset());
        BedraySceneNative.Entity entity = new BedraySceneNative.Entity(ENTITY_ID, frameIndex, frameIndex,
            vertexStream, vertexCount, indexStream, indexCount, 12, BedraySceneLayouts.INDEX_UINT32,
            ENTITY_INSTANCE_MASK, IDENTITY_TRANSFORM);
        library.update(scene, null, null, List.of(entity), null);
        entityPresent = true;
        entityTriangles = Math.toIntExact(indexCount / 3);
    }

    private void removeEntity() {
        if (!entityPresent) {
            return;
        }
        library.update(scene, null, null, null, new long[] {ENTITY_ID});
        entityPresent = false;
        entityTriangles = 0;
    }

    /**
     * Applies the queued section updates, records this frame's native builds into
     * {@code commandBuffer} and republishes the TLAS/table handles.
     *
     * <p>{@code camX/Y/Z} is the raw camera position the legacy TLAS anchors on.
     * The renderer traces camera-relative rays, so the scene has to place every
     * static section at {@code origin - camera}; a frame that skips this call
     * piles all section-local geometry at the origin instead.
     */
    void prepareFrame(long commandBuffer, long frameIndex, double camX, double camY, double camZ) {
        if (recordingFrames.containsKey(commandBuffer)) {
            throw new IllegalStateException("native scene recording still requires completion/discard");
        }
        library.setView(scene, camX, camY, camZ);
        flushUpdates();
        BedraySceneNative.FrameInfo info = library.prepareFrame(scene, frameIndex, commandBuffer);
        // Track the frame before reading the resources: once the scene accepted it,
        // only releaseFrame may free its transient uploads. A query failure below
        // must not leave an untracked outstanding frame that blocks close().
        frames.add(frameIndex);
        recordingFrames.put(commandBuffer, frameIndex);
        // RtFrameRenderer attaches one completion callback AFTER execute accepts
        // this command buffer. Scene and compute resources share that proof.
        BedraySceneNative.Resources resources = library.queryResources(scene);
        tlas = resources.tlas();
        sectionCount = resources.sectionCount();
        long handle = resources.sectionTableBuffer();
        long bytes = resources.sectionTableBytes();
        if (handle == 0L || bytes <= 0L) {
            table = null;
        } else if (table == null || table.handle != handle || table.size != bytes) {
            // Borrowed: the scene retires the buffer only after referencing frames
            // complete or are discarded; the host must not destroy it through RtMemory. The
            // flag is also what tells the Slang path which program to dispatch.
            table = new RtMemory.Buf(handle, 0L, bytes, resources.sectionTableAddress(), 0L, false, true);
        }
        activeThisFrame = info.tlas() != 0L;
        lastViewX = camX;
        lastViewY = camY;
        lastViewZ = camZ;
        lastInstances = info.instanceCount();
        lastBlasBuilds = Math.toIntExact(info.blasBuilds());
        logActivePeriodically();
    }

    /**
     * One line every couple of seconds while the scene owns the frame, so a run
     * log shows the native TLAS carrying sections rather than the Java fallback.
     */
    private void logActivePeriodically() {
        if (!activeThisFrame) {
            return;
        }
        long now = System.nanoTime();
        if (now - lastLogNanos < LOG_ACTIVE_NANOS) {
            return;
        }
        lastLogNanos = now;
        LOGGER.info("[VKB-RT] native_scene active sections={} instances={} tlas=0x{} table={}B view=({}, {}, {})",
            sectionCount, lastInstances, Long.toHexString(tlas), table == null ? 0L : table.size,
            lastViewX, lastViewY, lastViewZ);
    }

    /**
     * Runs from the host encoder's destruction queue once the submission that
     * consumed this frame has completed. A late callback (after close) must not
     * touch the destroyed scene, and a failed release stays tracked so drain or
     * close can retry it.
     */
    void releaseRecording(long commandBuffer) {
        Long frame = recordingFrames.get(commandBuffer);
        if (frame == null) return;
        library.releaseFrame(scene, frame);
        frames.remove(frame);
        recordingFrames.remove(commandBuffer);
    }

    void discardRecording(long commandBuffer) {
        Long frame = recordingFrames.get(commandBuffer);
        if (frame == null) return;
        library.discardFrame(scene, frame);
        frames.remove(frame);
        recordingFrames.remove(commandBuffer);
        activeThisFrame = false;
        tlas = 0;
        table = null;
    }

    private void flushUpdates() {
        for (Iterator<Map.Entry<Long, Long>> it = removals.entrySet().iterator(); it.hasNext();) {
            Map.Entry<Long, Long> removal = it.next();
            library.update(scene, null, new long[] {removal.getValue()}, null, null);
            it.remove();
        }
        for (Iterator<Map.Entry<Long, PendingSection>> it = updates.entrySet().iterator(); it.hasNext();) {
            Map.Entry<Long, PendingSection> update = it.next();
            Long id = sectionIds.get(update.getKey());
            if (id == null) {
                it.remove();
                continue;
            }
            PendingSection pending = update.getValue();
            try (Arena arena = Arena.ofConfined()) {
                BedraySceneNative.Section section = BedraySceneGeometry.build(pending.section(), pending.tagged(),
                    arena, id, nextRevision++);
                library.update(scene, List.of(section), null, null, null);
            }
            it.remove();
        }
    }

    /** Releases every prepared frame after the host proved completion or discard. */
    void releaseAllFrames() {
        RuntimeException failure = null;
        for (Iterator<Long> it = frames.iterator(); it.hasNext(); ) {
            long frame = it.next();
            try {
                library.releaseFrame(scene, frame);
                it.remove();
                recordingFrames.values().removeIf(value -> value == frame);
            } catch (RuntimeException e) {
                if (failure == null) {
                    failure = e;
                } else {
                    failure.addSuppressed(e);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        releaseAllFrames();
        library.destroy(scene);
        library.close();
        closed = true;
        tlas = 0L;
        table = null;
        sectionCount = 0;
        activeThisFrame = false;
    }

    @Override
    public String toString() {
        return "NativeSceneBridge[sections=" + sectionIds.size() + ", frames=" + frames.size()
            + (activeThisFrame ? ", active" : ", idle") + "]";
    }
}
