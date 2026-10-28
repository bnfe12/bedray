package io.github.andrexarlay.vulkancompat.rt.scene;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_DOUBLE;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * Java 25 FFM binding of {@code native/bedray_scene/include/bedray_scene.h} (ABI v2).
 *
 * <p>The host owns {@code VkInstance}, {@code VkPhysicalDevice}, {@code VkDevice},
 * the queue and the command buffer. This class passes their addresses to the
 * scene and asks it to record BLAS/TLAS builds, uploads and barriers into the
 * caller's command buffer; it never creates a device, submits or waits.
 *
 * <p>ABI v2 is the shader-facing scene: a section record is 64 bytes and carries
 * a stable section identity, the device address of a 64-byte
 * {@code GeometryVertex} stream and three packed pass ranges. The Java host no
 * longer writes that record itself, which is why {@link Section} carries the
 * metadata the native side needs instead of a prebuilt table row.
 *
 * <p>Threading: one instance is bound to one device and is used from the render
 * thread only, like the rest of the RT frame driver.
 */
public final class BedraySceneNative implements AutoCloseable {
    private final Arena arena;
    private final Path libraryPath;
    private final Set<Long> liveScenes = ConcurrentHashMap.newKeySet();
    private final MethodHandle abiVersionHandle;
    private final MethodHandle abiInfoHandle;
    private final MethodHandle createHandle;
    private final MethodHandle updateHandle;
    private final MethodHandle updateTableHandle;
    private final MethodHandle setViewHandle;
    private final MethodHandle prepareFrameHandle;
    private final MethodHandle releaseFrameHandle;
    private final MethodHandle queryResourcesHandle;
    private final MethodHandle destroyHandle;
    private volatile boolean closed;

    /** Values reported by {@code bedray_scene_abi_info}. */
    public record AbiInfo(int abiVersion, int sizeofDesc, int sizeofSectionInput, int sizeofEntityInput,
                          int sizeofUpdate, int sizeofTableDesc, int sizeofFrameDesc, int sizeofFrameInfo,
                          int sizeofResources, int sizeofSectionRecord, int sizeofError) {
    }

    /** Values reported by {@code bedray_scene_prepare_frame}. */
    public record FrameInfo(int abiVersion, int flags, long frameIndex, long tlas, long instanceCount,
                            long blasBuilds, long blasRefits, long deferredUpdates, long uploadBytes,
                            long sectionCount) {
    }

    /** Handles of the most recently prepared frame; the scene owns them and the host must not destroy them. */
    public record Resources(int abiVersion, int sectionCount, int instanceCount, long tlas,
                            long instanceBuffer, long instanceBufferAddress,
                            long sectionTableBuffer, long sectionTableAddress, long sectionTableBytes,
                            long materialTableBuffer, long materialTableAddress, long materialTableBytes,
                            long lightTableBuffer, long lightTableAddress, long lightTableBytes) {
    }

    /**
     * One section update. {@code vertices} must be a native segment holding
     * {@code vertexCount} records of 64-byte {@code GeometryVertex} data (the
     * scene copies it during the call, so it may be freed afterwards).
     *
     * <p>{@code quadStart} is the first quad of each geometry in the stream and
     * the ranges are contiguous in pass order, exactly as the section record
     * publishes them. {@code passBits} stores two bits per geometry.
     */
    public record Section(long id, long geometryRevision, MemorySegment vertices, long vertexCount,
                          MemorySegment indices, long indexCount, int indexType,
                          int originX, int originY, int originZ, int geometryCount,
                          int quadStart0, int quadStart1, int quadStart2, int passBits, int flags) {
        public Section {
            if (vertices == null || vertices.equals(MemorySegment.NULL)) {
                throw new IllegalArgumentException("a section needs a vertex stream");
            }
            if (indices == null || indices.equals(MemorySegment.NULL)) {
                throw new IllegalArgumentException("a section needs an index stream");
            }
            if (geometryCount < 1 || geometryCount > BedraySceneLayouts.MAX_SECTION_GEOMETRIES) {
                throw new IllegalArgumentException("geometryCount " + geometryCount + " is not 1.."
                    + BedraySceneLayouts.MAX_SECTION_GEOMETRIES);
            }
            if (indexType != BedraySceneLayouts.INDEX_UINT16 && indexType != BedraySceneLayouts.INDEX_UINT32) {
                throw new IllegalArgumentException("a section needs a 16- or 32-bit index type");
            }
        }
    }

    /** One entity update; {@code transform} is the row-major 3x4 matrix {@code VkTransformMatrixKHR} takes. */
    public record Entity(long id, long geometryRevision, long topologyRevision, MemorySegment vertices,
                         long vertexCount, MemorySegment indices, long indexCount, int vertexStride,
                         int indexType, int instanceMask, float[] transform) {
        public Entity {
            if (vertices == null || vertices.equals(MemorySegment.NULL)) {
                throw new IllegalArgumentException("an entity needs a vertex stream");
            }
            if (indices == null || indices.equals(MemorySegment.NULL)) {
                throw new IllegalArgumentException("an entity needs an index stream");
            }
            if (transform == null || transform.length != 12) {
                throw new IllegalArgumentException("an entity transform is a 3x4 matrix of 12 floats");
            }
        }
    }

    private BedraySceneNative(Path libraryPath) {
        this.libraryPath = libraryPath;
        this.arena = Arena.ofShared();
        SymbolLookup lookup;
        try {
            lookup = SymbolLookup.libraryLookup(libraryPath, arena);
        } catch (IllegalArgumentException | UnsatisfiedLinkError e) {
            arena.close();
            throw new BedraySceneException("load", -1,
                "cannot load " + libraryPath + " (x64 DLL expected): " + e.getMessage(), e);
        }

        Linker linker = Linker.nativeLinker();
        try {
            abiVersionHandle = bind(linker, lookup, "bedray_scene_abi_version",
                FunctionDescriptor.of(JAVA_INT));
            abiInfoHandle = bind(linker, lookup, "bedray_scene_abi_info",
                FunctionDescriptor.ofVoid(ADDRESS));
            createHandle = bind(linker, lookup, "bedray_scene_create",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
            updateHandle = bind(linker, lookup, "bedray_scene_update",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
            updateTableHandle = bind(linker, lookup, "bedray_scene_update_table",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
            setViewHandle = bind(linker, lookup, "bedray_scene_set_view",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_DOUBLE, JAVA_DOUBLE, JAVA_DOUBLE, ADDRESS));
            prepareFrameHandle = bind(linker, lookup, "bedray_scene_prepare_frame",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
            releaseFrameHandle = bind(linker, lookup, "bedray_scene_release_frame",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, ADDRESS));
            queryResourcesHandle = bind(linker, lookup, "bedray_scene_query_resources",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
            destroyHandle = bind(linker, lookup, "bedray_scene_destroy",
                FunctionDescriptor.of(JAVA_INT, ADDRESS));
        } catch (RuntimeException | Error e) {
            arena.close();
            throw e;
        }

        try {
            int version = (int) abiVersionHandle.invokeExact();
            if (version != BedraySceneLayouts.ABI_VERSION) {
                throw new BedraySceneException("load", BedraySceneLayouts.STATUS_ABI_MISMATCH,
                    "DLL ABI " + version + " != Java ABI " + BedraySceneLayouts.ABI_VERSION, null);
            }
            verifyReportedAbi(abiInfo());
        } catch (Throwable t) {
            arena.close();
            throw rethrow("load", t);
        }
    }

    /**
     * Loads the scene DLL from an explicit path. Never guesses a location; the
     * caller decides where the library lives.
     */
    public static BedraySceneNative load(Path dll) {
        if (dll == null) {
            throw new IllegalArgumentException("dll must not be null");
        }
        BedraySceneLayouts.verify();
        Path path = dll.toAbsolutePath().normalize();
        if (!Files.isRegularFile(path)) {
            throw new BedraySceneException("load", -1, "not a regular file: " + path, null);
        }
        return new BedraySceneNative(path);
    }

    private static MethodHandle bind(Linker linker, SymbolLookup lookup, String name, FunctionDescriptor descriptor) {
        MemorySegment symbol = lookup.find(name)
            .orElseThrow(() -> new BedraySceneException("load", -1, "DLL has no export " + name, null));
        return linker.downcallHandle(symbol, descriptor);
    }

    private static void verifyReportedAbi(AbiInfo info) {
        require("sizeof_desc", info.sizeofDesc(), 88);
        require("sizeof_section_input", info.sizeofSectionInput(), 96);
        require("sizeof_entity_input", info.sizeofEntityInput(), 120);
        require("sizeof_update", info.sizeofUpdate(), 56);
        require("sizeof_table_desc", info.sizeofTableDesc(), 40);
        require("sizeof_frame_desc", info.sizeofFrameDesc(), 24);
        require("sizeof_frame_info", info.sizeofFrameInfo(), 80);
        require("sizeof_resources", info.sizeofResources(), 112);
        require("sizeof_section_record", info.sizeofSectionRecord(), 64);
        require("sizeof_error", info.sizeofError(), 1028);
    }

    private static void require(String name, int actual, int expected) {
        if (actual != expected) {
            throw new BedraySceneException("load", BedraySceneLayouts.STATUS_ABI_MISMATCH,
                name + " is " + actual + " bytes in the DLL, expected " + expected, null);
        }
    }

    public Path libraryPath() {
        return libraryPath;
    }

    public AbiInfo abiInfo() {
        ensureOpen();
        try (Arena a = Arena.ofConfined()) {
            MemorySegment info = a.allocate(BedraySceneLayouts.ABI_INFO);
            abiInfoHandle.invokeExact(info);
            return new AbiInfo(
                info.get(JAVA_INT, BedraySceneLayouts.AI_ABI_VERSION),
                info.get(JAVA_INT, BedraySceneLayouts.AI_SIZEOF_DESC),
                info.get(JAVA_INT, BedraySceneLayouts.AI_SIZEOF_SECTION_INPUT),
                info.get(JAVA_INT, BedraySceneLayouts.AI_SIZEOF_ENTITY_INPUT),
                info.get(JAVA_INT, BedraySceneLayouts.AI_SIZEOF_UPDATE),
                info.get(JAVA_INT, BedraySceneLayouts.AI_SIZEOF_TABLE_DESC),
                info.get(JAVA_INT, BedraySceneLayouts.AI_SIZEOF_FRAME_DESC),
                info.get(JAVA_INT, BedraySceneLayouts.AI_SIZEOF_FRAME_INFO),
                info.get(JAVA_INT, BedraySceneLayouts.AI_SIZEOF_RESOURCES),
                info.get(JAVA_INT, BedraySceneLayouts.AI_SIZEOF_SECTION_RECORD),
                info.get(JAVA_INT, BedraySceneLayouts.AI_SIZEOF_ERROR));
        } catch (Throwable t) {
            throw rethrow("abi_info", t);
        }
    }

    /**
     * Creates a scene bound to the host's Vulkan objects.
     *
     * @param getInstanceProcAddr address of {@code vkGetInstanceProcAddr}
     * @param getDeviceProcAddr   address of {@code vkGetDeviceProcAddr}
     * @param capabilities        {@code BedraySceneCapability} bits the host enabled; the two
     *                            required bits are validated by the DLL
     * @param maxGeometryBytes    upper bound for the vertex+index bytes of one mesh
     * @return opaque scene handle
     */
    public long create(long instance, long physicalDevice, long device, int apiVersion,
                       long getInstanceProcAddr, long getDeviceProcAddr, int capabilities,
                       int framesInFlight, int maxSections, int maxEntities, int maxInstances,
                       int maxTableBytes, long maxGeometryBytes) {
        ensureOpen();
        if (framesInFlight < 1 || framesInFlight > BedraySceneLayouts.MAX_FRAMES_IN_FLIGHT) {
            throw new IllegalArgumentException("framesInFlight " + framesInFlight + " is not 1.."
                + BedraySceneLayouts.MAX_FRAMES_IN_FLIGHT);
        }
        try (Arena a = Arena.ofConfined()) {
            MemorySegment desc = a.allocate(BedraySceneLayouts.DESC);
            desc.set(JAVA_INT, BedraySceneLayouts.CD_ABI_VERSION, BedraySceneLayouts.ABI_VERSION);
            desc.set(JAVA_INT, BedraySceneLayouts.CD_FLAGS, 0);
            desc.set(JAVA_LONG, BedraySceneLayouts.CD_INSTANCE, instance);
            desc.set(JAVA_LONG, BedraySceneLayouts.CD_PHYSICAL_DEVICE, physicalDevice);
            desc.set(JAVA_LONG, BedraySceneLayouts.CD_DEVICE, device);
            desc.set(JAVA_LONG, BedraySceneLayouts.CD_GET_INSTANCE_PROC_ADDR, getInstanceProcAddr);
            desc.set(JAVA_LONG, BedraySceneLayouts.CD_GET_DEVICE_PROC_ADDR, getDeviceProcAddr);
            desc.set(JAVA_INT, BedraySceneLayouts.CD_API_VERSION, apiVersion);
            desc.set(JAVA_INT, BedraySceneLayouts.CD_CAPABILITIES, capabilities);
            desc.set(JAVA_INT, BedraySceneLayouts.CD_FRAMES_IN_FLIGHT, framesInFlight);
            desc.set(JAVA_INT, BedraySceneLayouts.CD_MAX_SECTIONS, maxSections);
            desc.set(JAVA_INT, BedraySceneLayouts.CD_MAX_ENTITIES, maxEntities);
            desc.set(JAVA_INT, BedraySceneLayouts.CD_MAX_INSTANCES, maxInstances);
            desc.set(JAVA_INT, BedraySceneLayouts.CD_MAX_TABLE_BYTES, maxTableBytes);
            desc.set(JAVA_LONG, BedraySceneLayouts.CD_MAX_GEOMETRY_BYTES, maxGeometryBytes);

            MemorySegment out = a.allocate(ADDRESS);
            MemorySegment error = a.allocate(BedraySceneLayouts.ERROR);
            int status = (int) createHandle.invokeExact(desc, out, error);
            check("create", status, error);
            long scene = out.get(JAVA_LONG, 0);
            if (scene == 0) {
                throw new BedraySceneException("create", BedraySceneLayouts.STATUS_INTERNAL,
                    "scene handle is zero", null);
            }
            liveScenes.add(scene);
            return scene;
        } catch (Throwable t) {
            throw rethrow("create", t);
        }
    }

    /**
     * Applies one transactional update: added/changed sections and entities plus
     * the ids removed this frame. The scene copies every vertex and index stream
     * during the call, so the caller may release its staging memory afterwards.
     */
    public void update(long scene, List<Section> sections, long[] removedSections,
                       List<Entity> entities, long[] removedEntities) {
        ensureOpen();
        requireHandle(scene, "scene");
        List<Section> addSections = sections == null ? List.of() : sections;
        List<Entity> addEntities = entities == null ? List.of() : entities;
        long[] dropSections = removedSections == null ? new long[0] : removedSections;
        long[] dropEntities = removedEntities == null ? new long[0] : removedEntities;

        try (Arena a = Arena.ofConfined()) {
            MemorySegment sectionArray = addSections.isEmpty()
                ? MemorySegment.NULL
                : a.allocate(BedraySceneLayouts.SECTION_INPUT.byteSize() * addSections.size());
            for (int i = 0; i < addSections.size(); ++i) {
                writeSection(sectionArray, i, addSections.get(i));
            }
            MemorySegment entityArray = addEntities.isEmpty()
                ? MemorySegment.NULL
                : a.allocate(BedraySceneLayouts.ENTITY_INPUT.byteSize() * addEntities.size());
            for (int i = 0; i < addEntities.size(); ++i) {
                writeEntity(entityArray, i, addEntities.get(i));
            }
            MemorySegment removedSectionArray = dropSections.length == 0
                ? MemorySegment.NULL
                : a.allocate(JAVA_LONG, dropSections.length);
            for (int i = 0; i < dropSections.length; ++i) {
                removedSectionArray.setAtIndex(JAVA_LONG, i, dropSections[i]);
            }
            MemorySegment removedEntityArray = dropEntities.length == 0
                ? MemorySegment.NULL
                : a.allocate(JAVA_LONG, dropEntities.length);
            for (int i = 0; i < dropEntities.length; ++i) {
                removedEntityArray.setAtIndex(JAVA_LONG, i, dropEntities[i]);
            }

            MemorySegment update = a.allocate(BedraySceneLayouts.UPDATE);
            update.set(JAVA_INT, BedraySceneLayouts.U_ABI_VERSION, BedraySceneLayouts.ABI_VERSION);
            update.set(JAVA_INT, BedraySceneLayouts.U_FLAGS, 0);
            update.set(ADDRESS, BedraySceneLayouts.U_SECTIONS, sectionArray);
            update.set(JAVA_INT, BedraySceneLayouts.U_SECTION_COUNT, addSections.size());
            update.set(JAVA_INT, BedraySceneLayouts.U_REMOVED_SECTION_COUNT, dropSections.length);
            update.set(ADDRESS, BedraySceneLayouts.U_REMOVED_SECTIONS, removedSectionArray);
            update.set(ADDRESS, BedraySceneLayouts.U_ENTITIES, entityArray);
            update.set(JAVA_INT, BedraySceneLayouts.U_ENTITY_COUNT, addEntities.size());
            update.set(JAVA_INT, BedraySceneLayouts.U_REMOVED_ENTITY_COUNT, dropEntities.length);
            update.set(ADDRESS, BedraySceneLayouts.U_REMOVED_ENTITIES, removedEntityArray);

            MemorySegment error = a.allocate(BedraySceneLayouts.ERROR);
            int status = (int) updateHandle.invokeExact(handle(scene), update, error);
            check("update", status, error);
        } catch (Throwable t) {
            throw rethrow("update", t);
        }
    }

    /**
     * Uploads one side table (material or light words) or clears it with
     * {@code size == 0}. The scene copies the bytes during the call.
     */
    public void updateTable(long scene, int tableKind, long revision, MemorySegment data, long size) {
        ensureOpen();
        requireHandle(scene, "scene");
        if (size < 0) {
            throw new IllegalArgumentException("table size must not be negative");
        }
        if (size > 0 && (data == null || data.equals(MemorySegment.NULL))) {
            throw new IllegalArgumentException("size > 0 but data is NULL");
        }
        try (Arena a = Arena.ofConfined()) {
            MemorySegment desc = a.allocate(BedraySceneLayouts.TABLE_DESC);
            desc.set(JAVA_INT, BedraySceneLayouts.TD_ABI_VERSION, BedraySceneLayouts.ABI_VERSION);
            desc.set(JAVA_INT, BedraySceneLayouts.TD_TABLE_KIND, tableKind);
            desc.set(JAVA_LONG, BedraySceneLayouts.TD_REVISION, revision);
            desc.set(ADDRESS, BedraySceneLayouts.TD_DATA, size == 0 ? MemorySegment.NULL : data);
            desc.set(JAVA_LONG, BedraySceneLayouts.TD_SIZE, size);
            MemorySegment error = a.allocate(BedraySceneLayouts.ERROR);
            int status = (int) updateTableHandle.invokeExact(handle(scene), desc, error);
            check("update_table", status, error);
        } catch (Throwable t) {
            throw rethrow("update_table", t);
        }
    }

    /**
     * Sets the camera position static sections are placed against. The renderer
     * traces camera-relative rays while a section's vertex stream is
     * section-local, so the scene writes each section instance as
     * {@code translate(origin - eye)} — the placement
     * {@code InstanceMath.sectionTransform} gives the legacy TLAS. A section's
     * own transform is identity, which is correct only for the per-frame entity
     * ring the host re-centres around the camera. Call before every
     * {@link #prepareFrame}; without a view the scene keeps identity placement.
     */
    public void setView(long scene, double eyeX, double eyeY, double eyeZ) {
        ensureOpen();
        requireHandle(scene, "scene");
        if (!Double.isFinite(eyeX) || !Double.isFinite(eyeY) || !Double.isFinite(eyeZ)) {
            throw new IllegalArgumentException("the view position must be finite");
        }
        try (Arena a = Arena.ofConfined()) {
            MemorySegment error = a.allocate(BedraySceneLayouts.ERROR);
            int status = (int) setViewHandle.invokeExact(handle(scene), eyeX, eyeY, eyeZ, error);
            check("set_view", status, error);
        } catch (Throwable t) {
            throw rethrow("set_view", t);
        }
    }

    /**
     * Records this frame's uploads, BLAS/TLAS builds and barriers into
     * {@code commandBuffer} and publishes the resulting resources.
     */
    public FrameInfo prepareFrame(long scene, long frameIndex, long commandBuffer) {
        ensureOpen();
        requireHandle(scene, "scene");
        requireHandle(frameIndex, "frameIndex");
        requireHandle(commandBuffer, "commandBuffer");
        try (Arena a = Arena.ofConfined()) {
            MemorySegment desc = a.allocate(BedraySceneLayouts.FRAME_DESC);
            desc.set(JAVA_INT, BedraySceneLayouts.FD_ABI_VERSION, BedraySceneLayouts.ABI_VERSION);
            desc.set(JAVA_INT, BedraySceneLayouts.FD_FLAGS, 0);
            desc.set(JAVA_LONG, BedraySceneLayouts.FD_FRAME_INDEX, frameIndex);
            desc.set(JAVA_LONG, BedraySceneLayouts.FD_COMMAND_BUFFER, commandBuffer);
            MemorySegment info = a.allocate(BedraySceneLayouts.FRAME_INFO);
            MemorySegment error = a.allocate(BedraySceneLayouts.ERROR);
            int status = (int) prepareFrameHandle.invokeExact(handle(scene), desc, info, error);
            check("prepare_frame", status, error);
            return new FrameInfo(
                info.get(JAVA_INT, BedraySceneLayouts.FI_ABI_VERSION),
                info.get(JAVA_INT, BedraySceneLayouts.FI_FLAGS),
                info.get(JAVA_LONG, BedraySceneLayouts.FI_FRAME_INDEX),
                info.get(JAVA_LONG, BedraySceneLayouts.FI_TLAS),
                info.get(JAVA_LONG, BedraySceneLayouts.FI_INSTANCE_COUNT),
                info.get(JAVA_LONG, BedraySceneLayouts.FI_BLAS_BUILDS),
                info.get(JAVA_LONG, BedraySceneLayouts.FI_BLAS_REFITS),
                info.get(JAVA_LONG, BedraySceneLayouts.FI_DEFERRED_UPDATES),
                info.get(JAVA_LONG, BedraySceneLayouts.FI_UPLOAD_BYTES),
                info.get(JAVA_LONG, BedraySceneLayouts.FI_SECTION_COUNT));
        } catch (Throwable t) {
            throw rethrow("prepare_frame", t);
        }
    }

    /**
     * Releases a prepared frame after the host proved GPU completion (or proved
     * the recording was discarded). Until then the frame's transient upload
     * buffers, scratch memory and BLAS pins stay alive.
     */
    public void releaseFrame(long scene, long frameIndex) {
        ensureOpen();
        requireHandle(scene, "scene");
        requireHandle(frameIndex, "frameIndex");
        try (Arena a = Arena.ofConfined()) {
            MemorySegment error = a.allocate(BedraySceneLayouts.ERROR);
            int status = (int) releaseFrameHandle.invokeExact(handle(scene), frameIndex, error);
            check("release_frame", status, error);
        } catch (Throwable t) {
            throw rethrow("release_frame", t);
        }
    }

    /** Handles of the most recently prepared frame. */
    public Resources queryResources(long scene) {
        ensureOpen();
        requireHandle(scene, "scene");
        try (Arena a = Arena.ofConfined()) {
            MemorySegment resources = a.allocate(BedraySceneLayouts.RESOURCES);
            int status = (int) queryResourcesHandle.invokeExact(handle(scene), resources);
            check("query_resources", status, MemorySegment.NULL);
            return new Resources(
                resources.get(JAVA_INT, BedraySceneLayouts.R_ABI_VERSION),
                resources.get(JAVA_INT, BedraySceneLayouts.R_SECTION_COUNT),
                resources.get(JAVA_INT, BedraySceneLayouts.R_INSTANCE_COUNT),
                resources.get(JAVA_LONG, BedraySceneLayouts.R_TLAS),
                resources.get(JAVA_LONG, BedraySceneLayouts.R_INSTANCE_BUFFER),
                resources.get(JAVA_LONG, BedraySceneLayouts.R_INSTANCE_BUFFER_ADDRESS),
                resources.get(JAVA_LONG, BedraySceneLayouts.R_SECTION_TABLE_BUFFER),
                resources.get(JAVA_LONG, BedraySceneLayouts.R_SECTION_TABLE_ADDRESS),
                resources.get(JAVA_LONG, BedraySceneLayouts.R_SECTION_TABLE_BYTES),
                resources.get(JAVA_LONG, BedraySceneLayouts.R_MATERIAL_TABLE_BUFFER),
                resources.get(JAVA_LONG, BedraySceneLayouts.R_MATERIAL_TABLE_ADDRESS),
                resources.get(JAVA_LONG, BedraySceneLayouts.R_MATERIAL_TABLE_BYTES),
                resources.get(JAVA_LONG, BedraySceneLayouts.R_LIGHT_TABLE_BUFFER),
                resources.get(JAVA_LONG, BedraySceneLayouts.R_LIGHT_TABLE_ADDRESS),
                resources.get(JAVA_LONG, BedraySceneLayouts.R_LIGHT_TABLE_BYTES));
        } catch (Throwable t) {
            throw rethrow("query_resources", t);
        }
    }

    /** Destroys the scene. Refuses while one of its frames is unreleased. */
    public void destroy(long scene) {
        ensureOpen();
        if (scene == 0) {
            return;
        }
        try {
            int status = (int) destroyHandle.invokeExact(handle(scene));
            if (status == BedraySceneLayouts.STATUS_IN_USE) {
                throw new BedraySceneException("destroy", status,
                    "prepared frames still reference the scene; release them after GPU completion or discard", null);
            }
            check("destroy", status, MemorySegment.NULL);
            liveScenes.remove(scene);
        } catch (Throwable t) {
            throw rethrow("destroy", t);
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        if (!liveScenes.isEmpty()) {
            throw new BedraySceneException("close", BedraySceneLayouts.STATUS_IN_USE,
                "native scenes must be destroyed before the DLL is unloaded", null);
        }
        closed = true;
        arena.close();
    }

    /** Writes one {@code BedraySceneSectionInput} into a caller-allocated array. */
    public static void writeSection(MemorySegment array, int index, Section section) {
        long base = index * BedraySceneLayouts.SECTION_INPUT.byteSize();
        array.set(JAVA_LONG, base + BedraySceneLayouts.SI_SECTION_ID, section.id());
        array.set(JAVA_LONG, base + BedraySceneLayouts.SI_GEOMETRY_REVISION, section.geometryRevision());
        array.set(ADDRESS, base + BedraySceneLayouts.SI_VERTICES, section.vertices());
        array.set(JAVA_LONG, base + BedraySceneLayouts.SI_VERTEX_COUNT, section.vertexCount());
        array.set(JAVA_INT, base + BedraySceneLayouts.SI_VERTEX_STRIDE, BedraySceneLayouts.GEOMETRY_VERTEX_BYTES);
        array.set(JAVA_INT, base + BedraySceneLayouts.SI_INDEX_TYPE, section.indexType());
        array.set(ADDRESS, base + BedraySceneLayouts.SI_INDICES, section.indices());
        array.set(JAVA_LONG, base + BedraySceneLayouts.SI_INDEX_COUNT, section.indexCount());
        array.set(JAVA_INT, base + BedraySceneLayouts.SI_ORIGIN, section.originX());
        array.set(JAVA_INT, base + BedraySceneLayouts.SI_ORIGIN + 4, section.originY());
        array.set(JAVA_INT, base + BedraySceneLayouts.SI_ORIGIN + 8, section.originZ());
        array.set(JAVA_INT, base + BedraySceneLayouts.SI_GEOMETRY_COUNT, section.geometryCount());
        array.set(JAVA_INT, base + BedraySceneLayouts.SI_QUAD_START, section.quadStart0());
        array.set(JAVA_INT, base + BedraySceneLayouts.SI_QUAD_START + 4, section.quadStart1());
        array.set(JAVA_INT, base + BedraySceneLayouts.SI_QUAD_START + 8, section.quadStart2());
        array.set(JAVA_INT, base + BedraySceneLayouts.SI_PASS_BITS, section.passBits());
        array.set(JAVA_INT, base + BedraySceneLayouts.SI_FLAGS, section.flags());
        array.set(JAVA_INT, base + BedraySceneLayouts.SI_FLAGS + 4, 0);
    }

    /** Writes one {@code BedraySceneEntityInput} into a caller-allocated array. */
    public static void writeEntity(MemorySegment array, int index, Entity entity) {
        long base = index * BedraySceneLayouts.ENTITY_INPUT.byteSize();
        array.set(JAVA_LONG, base + BedraySceneLayouts.EI_ENTITY_ID, entity.id());
        array.set(JAVA_LONG, base + BedraySceneLayouts.EI_GEOMETRY_REVISION, entity.geometryRevision());
        array.set(JAVA_LONG, base + BedraySceneLayouts.EI_TOPOLOGY_REVISION, entity.topologyRevision());
        array.set(ADDRESS, base + BedraySceneLayouts.EI_VERTICES, entity.vertices());
        array.set(JAVA_LONG, base + BedraySceneLayouts.EI_VERTEX_COUNT, entity.vertexCount());
        array.set(ADDRESS, base + BedraySceneLayouts.EI_INDICES, entity.indices());
        array.set(JAVA_LONG, base + BedraySceneLayouts.EI_INDEX_COUNT, entity.indexCount());
        array.set(JAVA_INT, base + BedraySceneLayouts.EI_VERTEX_STRIDE, entity.vertexStride());
        array.set(JAVA_INT, base + BedraySceneLayouts.EI_INDEX_TYPE, entity.indexType());
        array.set(JAVA_INT, base + BedraySceneLayouts.EI_INSTANCE_MASK, entity.instanceMask());
        array.set(JAVA_INT, base + BedraySceneLayouts.EI_INSTANCE_MASK + 4, 0);
        for (int i = 0; i < 12; ++i) {
            array.set(JAVA_FLOAT, base + BedraySceneLayouts.EI_TRANSFORM + i * 4L, entity.transform()[i]);
        }
    }

    private static MemorySegment handle(long address) {
        return MemorySegment.ofAddress(address);
    }

    private static void requireHandle(long value, String name) {
        if (value == 0) {
            throw new IllegalArgumentException(name + " must not be zero");
        }
    }

    private static void check(String operation, int status, MemorySegment error) {
        if (status == BedraySceneLayouts.STATUS_OK) {
            return;
        }
        String message = "";
        if (error != null && !error.equals(MemorySegment.NULL)
            && error.get(JAVA_INT, BedraySceneLayouts.ERROR_STATUS) == status) {
            message = error.getString(BedraySceneLayouts.ERROR_MESSAGE, StandardCharsets.UTF_8);
        }
        throw new BedraySceneException(operation, status, message, null);
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("bedray_scene is closed");
        }
    }

    private static RuntimeException rethrow(String operation, Throwable t) {
        if (t instanceof BedraySceneException e) {
            return e;
        }
        if (t instanceof RuntimeException e) {
            return e;
        }
        if (t instanceof Error e) {
            throw e;
        }
        return new BedraySceneException(operation, -1, t.toString(), t);
    }
}
