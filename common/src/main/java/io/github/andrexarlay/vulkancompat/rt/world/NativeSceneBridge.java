package io.github.andrexarlay.vulkancompat.rt.world;

import io.github.andrexarlay.vulkancompat.rt.scene.BedraySceneLayouts;
import io.github.andrexarlay.vulkancompat.rt.scene.BedraySceneLocator;
import io.github.andrexarlay.vulkancompat.rt.scene.BedraySceneNative;
import io.github.andrexarlay.vulkancompat.rt.slang.SlangPassLibrary;
import io.github.andrexarlay.vulkancompat.rt.slang.SlangRuntime;
import io.github.andrexarlay.vulkancompat.rt.sodium.DecodedSection;
import io.github.andrexarlay.vulkanbridge.api.NativeLibraries;
import io.github.andrexarlay.vulkanbridge.api.VulkanBridge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Host facade over {@code bedray_scene.dll}: the native owner of the section BLAS,
 * the TLAS and the 64-byte {@code GeometrySection} table.
 *
 * <p>The scene never creates a device, submits or waits.  It records uploads,
 * BLAS/TLAS builds and barriers into the command buffer {@link RtWorld} already
 * has, and publishes the TLAS plus the section-table buffer this class hands to
 * the path trace pass as a borrowed {@link RtMemory.Buf}.
 *
 * <p>Geometry is fed in the same paced order the legacy world builds it: a section
 * enters the scene when {@link RtWorld} succeeds in staging it, and leaves when the
 * rescan evicts it.  The bridge therefore keeps both scenes in lockstep, which is
 * what makes the native table a drop-in replacement for the legacy one.
 *
 * <p>Lifetime: a prepared frame is released when the same transient command-buffer
 * address comes back from the pool (proof that its previous submission completed)
 * or, for every remaining frame, after the host drained submissions.  The scene
 * refuses destruction while one of its frames is unreleased.
 */
final class NativeSceneBridge implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger("VKB-RT");

    /** AOT program that decodes the native record; the bridge is pointless without it. */
    static final String SCENE_PASS = "path_trace_scene";

    private static final int FRAMES_IN_FLIGHT = 3;
    private static final int MAX_ENTITIES = 1;
    private static final int TABLE_BYTES = 4096;
    private static final long MAX_GEOMETRY_BYTES = 48L << 20;
    private static final long ENTITY_ID = 1L;
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
    private final Map<Long, Long> frames = new LinkedHashMap<>();
    private long nextSectionId = 1;
    private long nextRevision = 1;
    private long frameIndex;
    private boolean entityPresent;
    private long tlas;
    private RtMemory.Buf table;
    private int sectionCount;
    private boolean activeThisFrame;
    private boolean closed;

    private NativeSceneBridge(BedraySceneNative library, long scene) {
        this.library = library;
        this.scene = scene;
    }

    /**
     * Loads the optional scene DLL and creates a scene on the host's Vulkan objects.
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
        NativeLibraries libraries;
        try {
            libraries = VulkanBridge.libraries();
        } catch (RuntimeException e) {
            LOGGER.warn("[VKB-RT] native scene unavailable: Bedray NativeLibraries are not available: {}",
                e.getMessage());
            return null;
        }
        BedraySceneLocator.Location location = BedraySceneLocator.locate(libraries);
        if (!location.usable()) {
            LOGGER.warn("[VKB-RT] native scene unavailable: {} is not installed in {} ({})",
                BedraySceneLocator.DLL, libraries.nativesDirectory(), location.detail());
            return null;
        }
        BedraySceneNative library = null;
        try {
            library = BedraySceneNative.load(location.dll());
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

    /**
     * Queues a section the legacy world just staged.  The bridge keeps the same
     * id for the section across rebuilds, so the native record's identity (and
     * therefore the path tracer's history) survives a re-mesh.
     */
    void sectionBuilt(DecodedSection section, int[] tagged) {
        Long removed = removals.remove(section.key);
        long id;
        if (removed != null) {
            id = removed;
            sectionIds.put(section.key, id);
        } else {
            Long existing = sectionIds.get(section.key);
            if (existing == null) {
                id = nextSectionId++;
                sectionIds.put(section.key, id);
            } else {
                id = existing;
            }
        }
        // RtWorld reuses one per-quad scratch array for every section of the frame,
        // so the queue has to keep its own copy until the scene copies it.
        int[] flags = tagged == null ? null : Arrays.copyOf(tagged, section.totalQuads());
        updates.put(section.key, new PendingSection(section, flags));
    }

    /** Queues the removal of a section the rescan evicted or that Sodium dropped. */
    void sectionRemoved(long key) {
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
        this.frameIndex = frameIndex;
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
    }

    private void removeEntity() {
        if (!entityPresent) {
            return;
        }
        library.update(scene, null, null, null, new long[] {ENTITY_ID});
        entityPresent = false;
    }

    /**
     * Applies the queued section updates, records this frame's native builds into
     * {@code commandBuffer} and republishes the TLAS/table handles.
     */
    void prepareFrame(long commandBuffer, long frameIndex) {
        flushUpdates();
        Long previous = frames.remove(commandBuffer);
        if (previous != null) {
            // The transient handle is back from the pool: its previous submission
            // completed, so the scene may reuse that frame's upload buffers.
            library.releaseFrame(scene, previous);
        }
        BedraySceneNative.FrameInfo info = library.prepareFrame(scene, frameIndex, commandBuffer);
        // Track the frame before reading the resources: once the scene accepted it,
        // only releaseFrame may free its transient uploads. A query failure below
        // must not leave an untracked outstanding frame that blocks close().
        frames.put(commandBuffer, frameIndex);
        BedraySceneNative.Resources resources = library.queryResources(scene);
        tlas = resources.tlas();
        sectionCount = resources.sectionCount();
        long handle = resources.sectionTableBuffer();
        long bytes = resources.sectionTableBytes();
        if (handle == 0L || bytes <= 0L) {
            table = null;
        } else if (table == null || table.handle != handle || table.size != bytes) {
            // Borrowed: the scene owns the buffer and keeps it alive until the next
            // table rebuild; the host must never destroy it through RtMemory. The
            // flag is also what tells the Slang path which program to dispatch.
            table = new RtMemory.Buf(handle, 0L, bytes, resources.sectionTableAddress(), 0L, false, true);
        }
        activeThisFrame = info.tlas() != 0L;
    }

    private void flushUpdates() {
        for (Map.Entry<Long, Long> removal : removals.entrySet()) {
            try {
                library.update(scene, null, new long[] {removal.getValue()}, null, null);
            } catch (RuntimeException e) {
                LOGGER.warn("[VKB-RT] native_scene remove section key={} failed: {}", removal.getKey(), e.getMessage());
            }
        }
        removals.clear();
        for (Map.Entry<Long, PendingSection> update : updates.entrySet()) {
            Long id = sectionIds.get(update.getKey());
            if (id == null) {
                continue;
            }
            PendingSection pending = update.getValue();
            try (Arena arena = Arena.ofConfined()) {
                BedraySceneNative.Section section = BedraySceneGeometry.build(pending.section(), pending.tagged(),
                    arena, id, nextRevision++);
                library.update(scene, List.of(section), null, null, null);
            } catch (RuntimeException e) {
                // A section the native scene rejects (more than 65535 quads, a
                // validation failure) must not retry every frame: the legacy world
                // keeps it, the native scene logs it once and goes without it.
                LOGGER.error("[VKB-RT] native_scene section key={} id={} rejected: {}",
                    update.getKey(), id, e.getMessage());
            }
        }
        updates.clear();
    }

    /** Releases every prepared frame after the host proved completion or discard. */
    void releaseAllFrames() {
        RuntimeException failure = null;
        for (Long frame : new LinkedHashSet<>(frames.values())) {
            try {
                library.releaseFrame(scene, frame);
            } catch (RuntimeException e) {
                if (failure == null) {
                    failure = e;
                } else {
                    failure.addSuppressed(e);
                }
            }
        }
        frames.clear();
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
