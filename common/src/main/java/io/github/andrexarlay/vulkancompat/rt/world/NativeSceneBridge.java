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
final class NativeSceneBridge implements AutoCloseable {
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
    private static final int TABLE_BYTES = 16 << 20;
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

    private final BedraySceneNative library;
    private final long scene;
    private long sectionEpoch;

    /** Tracking is reserved before native preparation can accept a frame. */
    private final SceneFrameRecordings recordings;
    private final EntityTopologyIdentity entityTopology = new EntityTopologyIdentity();
    private final SceneBuildTelemetry buildTelemetry = new SceneBuildTelemetry();
    private boolean entityPresent;
    private long entityAtlasVersion = -1L;
    private RtPathTraceSlangPass.Entities entityBindings = RtPathTraceSlangPass.Entities.NONE;
    private long tlas;
    private RtMemory.Buf table;
    private int sectionCount;
    private boolean activeThisFrame;
    private long preparedFrame;
    private final long[] frameTableRevisions = new long[BedraySceneLayouts.TABLE_SKY + 1];
    private final BedraySceneNative.TableView[] frameTables = new BedraySceneNative.TableView[frameTableRevisions.length];
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
        this.recordings = new SceneFrameRecordings(scene);
    }

    /** Loads the scene exports from the already validated core DLL. */
    static NativeSceneBridge create(SlangRuntime runtime) {
        if (runtime == null) {
            throw new IllegalStateException("the native scene requires an initialized Slang runtime");
        }
        if (!SlangPassLibrary.available(SCENE_PASS)) {
            throw new IllegalStateException("the native scene requires the AOT pass " + SCENE_PASS);
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
        } catch (RuntimeException | Error e) {
            if (library != null) {
                if (scene != 0) {
                    try { library.destroy(scene); }
                    catch (RuntimeException cleanup) { e.addSuppressed(cleanup); }
                }
                try {
                    library.close();
                } catch (RuntimeException closeFailure) {
                    e.addSuppressed(closeFailure);
                }
            }
            throw e;
        }
    }

    static int sceneCapabilities(int features, boolean disabled) {
        int required = BedraySceneLayouts.CAP_ACCELERATION_STRUCTURE | BedraySceneLayouts.CAP_DEVICE_ADDRESS;
        return required | (!disabled && (features & BedraySceneLayouts.FEATURE_SAFE_AS_UPDATE) != 0
            ? BedraySceneLayouts.CAP_AS_UPDATE : 0);
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

    BedraySceneNative.Selection selectSections(List<BedraySceneNative.Candidate> candidates,
                                               int x, int y, int z, int radiusXz, int radiusY, int limit) {
        return library.selectSections(scene, candidates, x, y, z, radiusXz, radiusY, RtConfig.EVICT_MARGIN,
            limit, RtConfig.vramBudgetBytes());
    }

    void submitSection(long id, long revision, DecodedSection section, int[] tags) {
        try (Arena arena = Arena.ofConfined()) {
            var input = BedraySceneGeometry.build(section, tags, arena, id, revision);
            library.update(scene, List.of(input), null, null, null);
        }
    }

    long sectionEpoch() { return sectionEpoch; }

    long[] sectionRevisions() { return library.sectionRevisions(scene, sectionCount); }

    /** Copies this frame's CPU capture into the scene before recording. */
    void updateEntities(long frameIndex, double camX, double camY, double camZ) {
        if (!RtConfig.SLANG_ENTITIES) return;
        try (Arena arena = Arena.ofConfined()) {
            var packed = RtEntityGeometryManager.INSTANCE.pack(arena, camX, camY, camZ);
            if (packed == null) {
                removeEntity();
                return;
            }
            long topologyRevision = entityTopology.update(packed.vertices(), packed.indexCount(), 12,
                BedraySceneLayouts.INDEX_UINT32, packed.indices());
            BedraySceneNative.Entity entity = new BedraySceneNative.Entity(ENTITY_ID, frameIndex, topologyRevision,
                packed.positions(), packed.vertices(), packed.indices(), packed.indexCount(), 12, BedraySceneLayouts.INDEX_UINT32,
                ENTITY_INSTANCE_MASK, IDENTITY_TRANSFORM, packed.previous(), packed.attributes());
            library.update(scene, null, null, List.of(entity), null);
            entityPresent = true;
            entityTriangles = packed.indexCount() / 3;
            var atlas = io.github.andrexarlay.vulkancompat.rt.entity.EntityTextureAtlas.INSTANCE;
            // Atlas texels and UV rectangles must come from the same capture revision.
            synchronized (atlas) {
                long version = atlas.version();
                if (version != entityAtlasVersion) {
                    var texels = arena.allocate(TABLE_BYTES, 4);
                    var rects = arena.allocate(io.github.andrexarlay.vulkancompat.rt.entity.EntityTextureAtlas.MAX_SLOTS * 16L, 4);
                    atlas.copyTo(texels.address(), rects.address());
                    library.updateTable(scene, BedraySceneLayouts.TABLE_ENTITY_RECTS, version, rects, rects.byteSize());
                    library.updateTable(scene, BedraySceneLayouts.TABLE_ENTITY_TEXELS, version, texels, texels.byteSize());
                    entityAtlasVersion = version;
                }
            }
        }
    }

    RtPathTraceSlangPass.Entities entityBindings() { return entityBindings; }

    private static RtPathTraceSlangPass.Entities entityBindings(BedraySceneNative.Resources resources) {
        long buffer = resources.entityBuffer();
        if (buffer == 0) return RtPathTraceSlangPass.Entities.NONE;
        long pos = resources.entityPositionsBytes();
        var capture = io.github.andrexarlay.vulkancompat.rt.entity.EntityCaptureManager.getInstance();
        return new RtPathTraceSlangPass.Entities(
            new RtPathTraceSlangPass.BufferView(buffer, 0L, pos),
            new RtPathTraceSlangPass.BufferView(buffer, resources.entityPreviousOffset(), pos),
            new RtPathTraceSlangPass.BufferView(buffer, resources.entityIndicesOffset(), resources.entityIndicesBytes()),
            new RtPathTraceSlangPass.BufferView(buffer, resources.entityAttributesOffset(), pos / 12L * 16L),
            new RtPathTraceSlangPass.BufferView(resources.entityRectBuffer(), 0, resources.entityRectBytes()),
            new RtPathTraceSlangPass.BufferView(resources.entityTexelBuffer(), 0, resources.entityTexelBytes()),
            resources.entityTexelBuffer() == 0 ? 0 : io.github.andrexarlay.vulkancompat.rt.entity.EntityTextureAtlas.SIZE,
            capture.selfAvatarTriStart(), capture.selfAvatarTriCount());
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
     * <p>{@code camX/Y/Z} is the raw camera position the legacy TLAS anchors on.
     * The renderer traces camera-relative rays, so the scene has to place every
     * static section at {@code origin - camera}; a frame that skips this call
     * piles all section-local geometry at the origin instead.
     */
    BedraySceneNative.TableView frameTable(int kind, long revision, java.nio.ByteBuffer bytes) {
        if (!activeThisFrame) throw new IllegalStateException("no current native RT scene");
        if (revision != 0 && frameTableRevisions[kind] != revision) {
            frameTables[kind] = library.publishFrameTable(scene, preparedFrame, kind, revision, bytes);
            frameTableRevisions[kind] = revision;
        }
        return frameTables[kind] == null ? BedraySceneNative.TableView.EMPTY : frameTables[kind];
    }

    BedraySceneNative.MaterialViews uploadMaterials(int width, int height, int count,
        java.lang.foreign.MemorySegment regions, java.lang.foreign.MemorySegment bytes, long planeBytes) {
        if (!activeThisFrame) throw new IllegalStateException("no current native RT scene");
        return library.uploadMaterials(scene, preparedFrame, width, height, count, regions, bytes, planeBytes);
    }

    void prepareFrame(long commandBuffer, long frameIndex, double camX, double camY, double camZ) {
        recordings.prepare(commandBuffer, frameIndex, recording -> {
            library.setView(scene, camX, camY, camZ);
            BedraySceneNative.FrameInfo info = library.prepareFrame(scene, recording);
            // RtFrameRenderer attaches one completion callback AFTER execute accepts
            // this command buffer. Scene and compute resources share that proof.
            BedraySceneNative.Resources resources = library.queryResources(scene);
            entityBindings = entityBindings(resources);
            tlas = resources.tlas();
            sectionCount = resources.sectionCount();
            sectionEpoch = resources.sectionEpoch();
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
            preparedFrame = frameIndex;
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
        var work = buildTelemetry.drain();
        LOGGER.info("[VKB-RT] native_scene active sections={} instances={} tlas=0x{} table={}B view=({}, {}, {}) "
                + "window_frames={} blas_builds={} blas_refits={} tlas_builds={} tlas_updates={} "
                + "cow_frames={} deferred_updates={} entity_topology={} entity_triangles={}",
            sectionCount, lastInstances, Long.toHexString(tlas), table == null ? 0L : table.size,
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
        recordings.completeProducer(commandBuffer, frame -> library.retireProducer(scene, frame),
            frame -> library.releaseFrame(scene, frame));
    }

    Runnable retainRead(long commandBuffer) {
        return recordings.retainRead(commandBuffer, frame -> library.releaseFrame(scene, frame));
    }

    boolean discardRecording(long commandBuffer) {
        if (!recordings.release("discard_frame", commandBuffer, frame -> library.discardFrame(scene, frame))) return false;
        activeThisFrame = false;
        tlas = 0;
        table = null;
        return true;
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
        return "NativeSceneBridge[sections=" + sectionCount + ", frames=" + recordings.pendingCount()
            + (activeThisFrame ? ", active" : ", idle") + "]";
    }
}
