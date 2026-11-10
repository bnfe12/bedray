package io.github.andrexarlay.vulkancompat.rt.world;

import io.github.andrexarlay.vulkancompat.rt.scene.BedraySceneLayouts;
import io.github.andrexarlay.vulkancompat.rt.scene.BedraySceneNative;
import io.github.andrexarlay.vulkancompat.rt.scene.SceneFrameRecordings;
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
import java.util.List;
import java.util.Map;

/**
 * Host facade over the scene exports in {@code bedray_core.dll}: owner of the section BLAS,
 * the TLAS and the 64-byte {@code GeometrySection} table.
 *
 * <p>The scene never creates a device, submits or waits.  It records uploads,
 * BLAS/TLAS builds and barriers into the command buffer {@link RtWorld} already
 * has, and publishes the TLAS plus the section-table buffer this class hands to
 * the path trace pass as a borrowed {@link RtFrameBindings.SectionTable}.
 *
 * <p>Java paces CPU captures and serializes POD input. C++ is the sole owner of
 * AS builds and scene GPU resources. Update failures remain queued
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

    /** AOT program that decodes the native scene record. */
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
    /** {@code HARDWARE_INSTANCE_MASK_ENTITIES}. */
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
    /** Tracking is reserved before native preparation can accept a frame. */
    private final SceneFrameRecordings recordings;
    private final EntityTopologyIdentity entityTopology = new EntityTopologyIdentity();
    private final SceneBuildTelemetry buildTelemetry = new SceneBuildTelemetry();
    private long nextSectionId = 1;
    private long nextRevision = 1;
    private boolean entityPresent;
    private long tlas;
    private RtFrameBindings.SectionTable table;
    private int sectionCount;
    private long lastLogNanos;
    private long lastInstances;
    private int lastBlasBuilds;
    private int entityTriangles;
    private double lastViewX;
    private double lastViewY;
    private double lastViewZ;
    private boolean sceneDestroyed;
    private boolean closed;

    private NativeSceneBridge(BedraySceneNative library, long scene) {
        this.library = library;
        this.scene = scene;
        this.recordings = new SceneFrameRecordings(scene);
    }

    /** Loads the required scene exports from the selected core DLL. */
    static NativeSceneBridge create(SlangRuntime runtime) {
        if (runtime == null) {
            throw new RtFailure("scene_init", "Slang runtime is unavailable");
        }
        if (!SlangPassLibrary.available(SCENE_PASS)) {
            throw new RtFailure("scene_init", "required AOT pass " + SCENE_PASS + " is missing from the JAR");
        }
        if (!runtime.library().hasNativeSceneOnlyPathTrace()) {
            throw new RtFailure("scene_init", "replace bedray_core.dll with the version bundled for this Bedray build");
        }
        BedraySceneNative library = null;
        long scene = 0;
        try {
            library = BedraySceneNative.load(runtime.library().libraryPath());
            int features = library.features();
            int capabilities = sceneCapabilities(features, Boolean.getBoolean("bedray.rt.disableAsUpdates"));
            scene = library.create(runtime.instance().address(), runtime.physicalDevice().address(),
                runtime.device().address(), runtime.apiVersion(), runtime.getInstanceProcAddr(),
                runtime.getDeviceProcAddr(), capabilities, FRAMES_IN_FLIGHT, RtConfig.MAX_INSTANCES,
                MAX_ENTITIES, RtConfig.MAX_INSTANCES, TABLE_BYTES, MAX_GEOMETRY_BYTES);
            runtime.attachSceneMemory(scene);
            LOGGER.info("[VKB-RT] native scene ready: dll={} abi={} frames={} max_sections={} max_geometry_mb={} features=0x{} as_updates={}",
                library.libraryPath().getFileName(), BedraySceneLayouts.ABI_VERSION, FRAMES_IN_FLIGHT,
                RtConfig.MAX_INSTANCES, MAX_GEOMETRY_BYTES >> 20, Integer.toHexString(features),
                (capabilities & BedraySceneLayouts.CAP_AS_UPDATE) != 0);
            return new NativeSceneBridge(library, scene);
        } catch (RuntimeException | LinkageError e) {
            if (library != null) {
                if (scene != 0) {
                    try { library.destroy(scene); }
                    catch (RuntimeException | LinkageError cleanup) { e.addSuppressed(cleanup); }
                }
                try {
                    library.close();
                } catch (RuntimeException | LinkageError closeFailure) {
                    e.addSuppressed(closeFailure);
                }
            }
            throw new RtFailure("scene_init", e.getMessage(), e);
        }
    }

    static int sceneCapabilities(int features, boolean disabled) {
        int required = BedraySceneLayouts.CAP_ACCELERATION_STRUCTURE | BedraySceneLayouts.CAP_DEVICE_ADDRESS;
        return required | (!disabled && (features & BedraySceneLayouts.FEATURE_SAFE_AS_UPDATE) != 0
            ? BedraySceneLayouts.CAP_AS_UPDATE : 0);
    }

    /** True while the native scene owns the TLAS and the table the path tracer binds. */
    boolean activeThisFrame() {
        return tlas != 0L;
    }

    long tlas() {
        return tlas;
    }

    RtFrameBindings.SectionTable sectionTable() {
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
     * and rebuilt every frame. Geometry follows that frame; topology changes only
     * when the shape/index bytes change. Native owns safe refit/COW selection.
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
        MemorySegment indexStream = MemorySegment.ofAddress(indices.buffer().mapped + indices.offset()).reinterpret(indices.bytes());
        long topologyRevision = entityTopology.update(vertexCount, indexCount, 12, BedraySceneLayouts.INDEX_UINT32, indexStream);
        BedraySceneNative.Entity entity = new BedraySceneNative.Entity(ENTITY_ID, frameIndex, topologyRevision,
            vertexStream, vertexCount, indexStream, indexCount, 12, BedraySceneLayouts.INDEX_UINT32,
            ENTITY_INSTANCE_MASK, IDENTITY_TRANSFORM);
        library.update(scene, null, null, List.of(entity), null);
        entityPresent = true;
        entityTriangles = Math.toIntExact(indexCount / 3);
    }

    private void removeEntity() {
        entityTopology.clear();
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
     * <p>The renderer traces camera-relative rays. The native scene places each
     * static section at its world origin minus the raw camera position.
     */
    void prepareFrame(long commandBuffer, long frameIndex, double camX, double camY, double camZ) {
        recordings.prepare(commandBuffer, frameIndex, recording -> {
            library.setView(scene, camX, camY, camZ);
            flushUpdates();
            BedraySceneNative.FrameInfo info = library.prepareFrame(scene, recording);
            // RtFrameRenderer attaches one completion callback AFTER execute accepts
            // this command buffer. Scene and compute resources share that proof.
            BedraySceneNative.Resources resources = library.queryResources(scene);
            long handle = resources.sectionTableBuffer();
            long bytes = resources.sectionTableBytes();
            if (info.tlas() != resources.tlas() || (info.tlas() != 0L && (handle == 0L || bytes <= 0L))) {
                throw new RtFailure("scene_prepare", "native TLAS and section table do not describe the same frame");
            }
            if (info.tlas() == 0L) {
                table = null;
            } else if (table == null || table.handle() != handle || table.bytes() != bytes) {
                // Borrowed: the scene retires the buffer only after referencing frames
                // complete or are discarded.
                table = new RtFrameBindings.SectionTable(handle, bytes);
            }
            tlas = info.tlas();
            sectionCount = resources.sectionCount();
            lastViewX = camX;
            lastViewY = camY;
            lastViewZ = camZ;
            lastInstances = info.instanceCount();
            lastBlasBuilds = Math.toIntExact(info.blasBuilds());
            buildTelemetry.add(info.flags(), info.blasBuilds(), info.blasRefits(), info.deferredUpdates());
            logActivePeriodically();
        });
    }

    /**
     * One line every couple of seconds while the scene has a TLAS.
     */
    private void logActivePeriodically() {
        if (!activeThisFrame()) {
            return;
        }
        long now = System.nanoTime();
        if (now - lastLogNanos < LOG_ACTIVE_NANOS) {
            return;
        }
        lastLogNanos = now;
        var work = buildTelemetry.drain();
        LOGGER.info("[VKB-RT] native_scene active sections={} instances={} tlas=0x{} table={}B view=({}, {}, {}) "
                + "window_frames={} blas_builds={} blas_refits={} tlas_builds={} tlas_updates={} "
                + "cow_frames={} deferred_updates={} entity_topology={} entity_triangles={}",
            sectionCount, lastInstances, Long.toHexString(tlas), table == null ? 0L : table.bytes(),
            lastViewX, lastViewY, lastViewZ, work.frames(), work.blasBuilds(), work.blasRefits(),
            work.tlasBuilds(), work.tlasUpdates(), work.copyOnWriteFrames(), work.deferredUpdates(),
            entityTopology.revision(), entityTriangles);
    }

    /**
     * Runs from the host encoder's destruction queue once the submission that
     * consumed this frame has completed. A late callback (after close) must not
     * touch the destroyed scene, and a failed release stays tracked so drain or
     * close can retry it.
     */
    void releaseRecording(long commandBuffer) {
        recordings.release("release_frame", commandBuffer, frame -> library.releaseFrame(scene, frame));
    }

    void discardRecording(long commandBuffer) {
        if (!recordings.release("discard_frame", commandBuffer, frame -> library.discardFrame(scene, frame))) return;
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
        recordings.releaseAll(frame -> library.releaseFrame(scene, frame));
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        if (!sceneDestroyed) {
            releaseAllFrames();
            library.destroy(scene);
            sceneDestroyed = true;
            tlas = 0L;
            table = null;
            sectionCount = 0;
        }
        library.close();
        closed = true;
    }

    @Override
    public String toString() {
        return "NativeSceneBridge[sections=" + sectionIds.size() + ", frames=" + recordings.pendingCount()
            + (activeThisFrame() ? ", active" : ", idle") + "]";
    }
}
