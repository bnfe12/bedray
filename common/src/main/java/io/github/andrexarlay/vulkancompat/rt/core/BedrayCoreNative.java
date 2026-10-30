package io.github.andrexarlay.vulkancompat.rt.core;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * Java 25 FFM binding of {@code native/bedray_core/include/bedray_core.h} (ABI v2).
 *
 * <p>The host owns {@code VkInstance}, {@code VkDevice}, the queue and the command
 * buffer. This class only passes their addresses to the native core and asks it to
 * record compute work into the caller's command buffer; it never submits or waits.
 *
 * <p>Threading: one instance is bound to one device and is used from the render
 * thread only, like the rest of the RT frame driver.
 */
public final class BedrayCoreNative implements AutoCloseable {
    private static final AtomicLong RESOURCE_GENERATIONS = new AtomicLong(1);
    private final Arena arena;
    private final Path libraryPath;
    private final Set<Long> liveCores = ConcurrentHashMap.newKeySet();
    private final Set<Long> liveResourceOwners = ConcurrentHashMap.newKeySet();
    private final SymbolLookup symbols;
    private volatile boolean closed;

    private final MethodHandle abiVersionHandle;
    private final MethodHandle abiInfoHandle;
    private final MethodHandle createHandle;
    private final MethodHandle destroyHandle;
    private final MethodHandle queryCapabilitiesHandle;
    private final MethodHandle registerProgramHandle;
    private final MethodHandle destroyProgramHandle;
    private final MethodHandle renderFrameHandle;
    private final MethodHandle releaseRecordingHandle;
    private final MethodHandle discardRecordingHandle;
    private final MethodHandle atlasSamplerHandle;
    private final MethodHandle linearSamplerHandle;
    private final MethodHandle renderPhasesHandle;
    private final MethodHandle configurePathTraceHandle;
    private final MethodHandle renderPathTraceHandle;
    private final MethodHandle renderPathTraceGuidesHandle;
    private final MethodHandle renderPathTraceMotionHandle;
    private final MethodHandle destroyPathTraceHandle;
    private final MethodHandle pathTraceFeatureMaskHandle;

    /** Values reported by {@code bedray_core_abi_info}. */
    public record AbiInfo(int abiVersion, int sizeofCoreDesc, int sizeofCapabilities, int sizeofProgramDesc,
                          int sizeofFrameDesc, int sizeofResource, int sizeofError) {
    }

    /** Values reported by {@code bedray_core_query_capabilities}. */
    public record Capabilities(int abiVersion, int flags, int maxPushConstants,
                               int maxComputeWorkGroupCountX, int maxComputeWorkGroupCountY, int maxComputeWorkGroupCountZ,
                               int maxComputeWorkGroupInvocations, long maxStorageBufferRange,
                               long maxUniformBufferRange, long deviceMemoryBytes) {
        public boolean has(int capability) {
            return (flags & capability) == capability;
        }
    }

    private BedrayCoreNative(Path libraryPath) {
        this.libraryPath = libraryPath;
        this.arena = Arena.ofShared();
        SymbolLookup lookup;
        try {
            lookup = SymbolLookup.libraryLookup(libraryPath, arena);
        } catch (IllegalArgumentException | UnsatisfiedLinkError e) {
            arena.close();
            throw new BedrayCoreException("load", -1,
                "cannot load " + libraryPath + " (x64 DLL expected): " + e.getMessage(), e);
        }
        symbols = lookup;

        Linker linker = Linker.nativeLinker();
        try {
            abiVersionHandle = bind(linker, lookup, "bedray_core_abi_version", FunctionDescriptor.of(JAVA_INT));
            abiInfoHandle = bind(linker, lookup, "bedray_core_abi_info", FunctionDescriptor.ofVoid(ADDRESS));
            createHandle = bind(linker, lookup, "bedray_core_create",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
            destroyHandle = bind(linker, lookup, "bedray_core_destroy", FunctionDescriptor.of(JAVA_INT, ADDRESS));
            queryCapabilitiesHandle = bind(linker, lookup, "bedray_core_query_capabilities",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
            registerProgramHandle = bind(linker, lookup, "bedray_core_register_program",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
            destroyProgramHandle = bind(linker, lookup, "bedray_core_destroy_program",
                FunctionDescriptor.ofVoid(ADDRESS, JAVA_LONG));
            renderFrameHandle = bind(linker, lookup, "bedray_core_render_frame",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
            releaseRecordingHandle = bind(linker, lookup, "bedray_core_release_recording",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG));
            discardRecordingHandle = bindOptional(linker, lookup, "bedray_core_discard_recording",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG));
            atlasSamplerHandle = bind(linker, lookup, "bedray_core_atlas_sampler",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
            linearSamplerHandle = bind(linker, lookup, "bedray_core_linear_sampler",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
            renderPhasesHandle = bind(linker, lookup, "bedray_core_render_phases",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS));
            // The path-trace pass ABI is additive: a DLL built before it simply
            // has no export, and the Java path keeps running unchanged.
            configurePathTraceHandle = bindOptional(linker, lookup, "bedray_core_configure_path_trace",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
            renderPathTraceHandle = bindOptional(linker, lookup, "bedray_core_render_path_trace",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
            renderPathTraceGuidesHandle = bindOptional(linker, lookup, "bedray_core_render_path_trace_guides",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
            renderPathTraceMotionHandle = bindOptional(linker, lookup, "bedray_core_render_path_trace_motion",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
            destroyPathTraceHandle = bindOptional(linker, lookup, "bedray_core_destroy_path_trace",
                FunctionDescriptor.of(JAVA_INT, ADDRESS));
            pathTraceFeatureMaskHandle = bindOptional(linker, lookup, "bedray_core_path_trace_feature_mask",
                FunctionDescriptor.of(JAVA_INT));
        } catch (RuntimeException | Error e) {
            arena.close();
            throw e;
        }

        try {
            int version = (int) abiVersionHandle.invokeExact();
            if (version != BedrayCoreLayouts.ABI_VERSION) {
                throw new BedrayCoreException("load", BedrayCoreLayouts.STATUS_ABI_MISMATCH,
                    "DLL ABI " + version + " != Java ABI " + BedrayCoreLayouts.ABI_VERSION, null);
            }
            verifyReportedAbi(abiInfo());
        } catch (Throwable t) {
            arena.close();
            throw rethrow("load", t);
        }
    }

    /**
     * Loads the core DLL from an explicit path. Never guesses a location; the
     * caller decides where the library lives.
     */
    public static BedrayCoreNative load(Path dll) {
        if (dll == null) {
            throw new IllegalArgumentException("dll must not be null");
        }
        BedrayCoreLayouts.verify();
        Path path = dll.toAbsolutePath().normalize();
        if (!Files.isRegularFile(path)) {
            throw new BedrayCoreException("load", -1, "not a regular file: " + path, null);
        }
        return new BedrayCoreNative(path);
    }

    private static MethodHandle bind(Linker linker, SymbolLookup lookup, String name, FunctionDescriptor descriptor) {
        MemorySegment symbol = lookup.find(name)
            .orElseThrow(() -> new BedrayCoreException("load", -1, "DLL has no export " + name, null));
        return linker.downcallHandle(symbol, descriptor);
    }

    /** Like {@link #bind} but returns null when the DLL predates the export. */
    private static MethodHandle bindOptional(Linker linker, SymbolLookup lookup, String name, FunctionDescriptor descriptor) {
        return lookup.find(name).map(symbol -> linker.downcallHandle(symbol, descriptor)).orElse(null);
    }

    MethodHandle resourceFunction(String name, FunctionDescriptor descriptor) {
        ensureOpen();
        return bind(Linker.nativeLinker(), symbols, name, descriptor);
    }

    static long nextResourceGeneration() {
        for (;;) {
            long value = RESOURCE_GENERATIONS.get();
            if (value <= 0 || value == Long.MAX_VALUE) throw new IllegalStateException("native resource generations exhausted");
            if (RESOURCE_GENERATIONS.compareAndSet(value, value + 1)) return value;
        }
    }

    void retainResourceOwner(long owner) {
        ensureOpen();
        if (owner == 0 || !liveResourceOwners.add(owner)) {
            throw new IllegalStateException("invalid or duplicate native resource owner");
        }
    }

    void releaseResourceOwner(long owner) {
        if (!liveResourceOwners.remove(owner)) throw new IllegalStateException("unknown native resource owner");
    }

    public void requireResourceOwnersReleased() {
        ensureOpen();
        if (!liveResourceOwners.isEmpty()) {
            throw new BedrayCoreException("close", BedrayCoreLayouts.STATUS_IN_USE,
                "native resource owners must be closed before unloading bedray_core", null);
        }
    }

    private static void verifyReportedAbi(AbiInfo info) {
        require("sizeof_core_desc", info.sizeofCoreDesc(), 72);
        require("sizeof_capabilities", info.sizeofCapabilities(), 56);
        require("sizeof_program_desc", info.sizeofProgramDesc(), 56);
        require("sizeof_frame_desc", info.sizeofFrameDesc(), 64);
        require("sizeof_resource", info.sizeofResource(), 48);
        require("sizeof_error", info.sizeofError(), 1028);
    }

    private static void require(String name, int actual, int expected) {
        if (actual != expected) {
            throw new BedrayCoreException("load", BedrayCoreLayouts.STATUS_ABI_MISMATCH,
                name + " is " + actual + " bytes in the DLL, expected " + expected, null);
        }
    }

    public Path libraryPath() {
        return libraryPath;
    }

    public AbiInfo abiInfo() {
        ensureOpen();
        try (Arena a = Arena.ofConfined()) {
            MemorySegment info = a.allocate(BedrayCoreLayouts.ABI_INFO);
            abiInfoHandle.invokeExact(info);
            return new AbiInfo(
                info.get(JAVA_INT, BedrayCoreLayouts.AI_ABI_VERSION),
                info.get(JAVA_INT, BedrayCoreLayouts.AI_SIZEOF_CORE_DESC),
                info.get(JAVA_INT, BedrayCoreLayouts.AI_SIZEOF_CAPABILITIES),
                info.get(JAVA_INT, BedrayCoreLayouts.AI_SIZEOF_PROGRAM_DESC),
                info.get(JAVA_INT, BedrayCoreLayouts.AI_SIZEOF_FRAME_DESC),
                info.get(JAVA_INT, BedrayCoreLayouts.AI_SIZEOF_RESOURCE),
                info.get(JAVA_INT, BedrayCoreLayouts.AI_SIZEOF_ERROR));
        } catch (Throwable t) {
            throw rethrow("abi_info", t);
        }
    }

    /**
     * Creates a core bound to the host's Vulkan objects.
     *
     * @param getInstanceProcAddr address of {@code vkGetInstanceProcAddr}
     * @param getDeviceProcAddr   address of {@code vkGetDeviceProcAddr}
     * @param capabilities        requested {@code BedrayCapability} bits, already checked by the host
     * @return opaque core handle
     */
    public long create(long instance, long physicalDevice, long device, long queue, int queueFamily, int apiVersion,
                       long getInstanceProcAddr, long getDeviceProcAddr, int capabilities) {
        ensureOpen();
        try (Arena a = Arena.ofConfined()) {
            MemorySegment desc = a.allocate(BedrayCoreLayouts.CORE_DESC);
            desc.set(JAVA_INT, BedrayCoreLayouts.CD_ABI_VERSION, BedrayCoreLayouts.ABI_VERSION);
            desc.set(JAVA_LONG, BedrayCoreLayouts.CD_INSTANCE, instance);
            desc.set(JAVA_LONG, BedrayCoreLayouts.CD_PHYSICAL_DEVICE, physicalDevice);
            desc.set(JAVA_LONG, BedrayCoreLayouts.CD_DEVICE, device);
            desc.set(JAVA_LONG, BedrayCoreLayouts.CD_QUEUE, queue);
            desc.set(JAVA_INT, BedrayCoreLayouts.CD_QUEUE_FAMILY, queueFamily);
            desc.set(JAVA_INT, BedrayCoreLayouts.CD_API_VERSION, apiVersion);
            desc.set(JAVA_LONG, BedrayCoreLayouts.CD_GET_INSTANCE_PROC_ADDR, getInstanceProcAddr);
            desc.set(JAVA_LONG, BedrayCoreLayouts.CD_GET_DEVICE_PROC_ADDR, getDeviceProcAddr);
            desc.set(JAVA_INT, BedrayCoreLayouts.CD_CAPABILITIES, capabilities);

            MemorySegment out = a.allocate(ADDRESS);
            MemorySegment error = a.allocate(BedrayCoreLayouts.ERROR);
            int status = (int) createHandle.invokeExact(desc, out, error);
            check("create", status, error);
            long core = out.get(JAVA_LONG, 0);
            if (core == 0) {
                throw new BedrayCoreException("create", BedrayCoreLayouts.STATUS_INTERNAL, "core handle is zero", null);
            }
            liveCores.add(core);
            return core;
        } catch (Throwable t) {
            throw rethrow("create", t);
        }
    }

    /** Borrows the core-owned nearest/clamp atlas sampler; the caller must not destroy it. */
    public long atlasSampler(long core) {
        return borrowSampler(core, atlasSamplerHandle);
    }

    public long linearSampler(long core) {
        return borrowSampler(core, linearSamplerHandle);
    }

    private long borrowSampler(long core, MethodHandle function) {
        ensureOpen();
        requireHandle(core, "core");
        try (Arena a = Arena.ofConfined()) {
            MemorySegment out = a.allocate(JAVA_LONG);
            MemorySegment error = a.allocate(BedrayCoreLayouts.ERROR);
            int status = (int) function.invokeExact(handle(core), out, error);
            check("atlas_sampler", status, error);
            long sampler = out.get(JAVA_LONG, 0);
            requireHandle(sampler, "atlas sampler");
            return sampler;
        } catch (Throwable t) {
            throw rethrow("atlas_sampler", t);
        }
    }

    public Capabilities queryCapabilities(long core) {
        ensureOpen();
        requireHandle(core, "core");
        try (Arena a = Arena.ofConfined()) {
            MemorySegment caps = a.allocate(BedrayCoreLayouts.CAPABILITIES);
            int status = (int) queryCapabilitiesHandle.invokeExact(handle(core), caps);
            if (status != BedrayCoreLayouts.STATUS_OK) {
                throw new BedrayCoreException("query_capabilities", status, "", null);
            }
            return new Capabilities(
                caps.get(JAVA_INT, BedrayCoreLayouts.CAP_ABI_VERSION),
                caps.get(JAVA_INT, BedrayCoreLayouts.CAP_FLAGS),
                caps.get(JAVA_INT, BedrayCoreLayouts.CAP_MAX_PUSH_CONSTANTS),
                caps.get(JAVA_INT, BedrayCoreLayouts.CAP_MAX_GROUP_COUNT_X),
                caps.get(JAVA_INT, BedrayCoreLayouts.CAP_MAX_GROUP_COUNT_Y),
                caps.get(JAVA_INT, BedrayCoreLayouts.CAP_MAX_GROUP_COUNT_Z),
                caps.get(JAVA_INT, BedrayCoreLayouts.CAP_MAX_INVOCATIONS),
                caps.get(JAVA_LONG, BedrayCoreLayouts.CAP_MAX_STORAGE_RANGE),
                caps.get(JAVA_LONG, BedrayCoreLayouts.CAP_MAX_UNIFORM_RANGE),
                caps.get(JAVA_LONG, BedrayCoreLayouts.CAP_DEVICE_MEMORY));
        } catch (Throwable t) {
            throw rethrow("query_capabilities", t);
        }
    }

    /**
     * Registers a compute program from SPIR-V produced ahead of time by {@code slangc}.
     *
     * @param bindings       array of {@code BedrayDescriptorBinding}, or {@link MemorySegment#NULL}
     * @param spirvWordCount number of 32-bit words, not bytes
     * @return opaque program handle
     */
    public long registerProgram(long core, String name, String entryPoint, MemorySegment spirv, long spirvWordCount,
                                MemorySegment bindings, int bindingCount, int pushConstantBytes) {
        ensureOpen();
        requireHandle(core, "core");
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        if (entryPoint == null || entryPoint.isBlank()) {
            throw new IllegalArgumentException("entryPoint must not be blank");
        }
        if (spirv == null || spirv.equals(MemorySegment.NULL) || spirvWordCount <= 0) {
            throw new IllegalArgumentException("SPIR-V words must not be empty");
        }
        if (bindingCount > 0 && (bindings == null || bindings.equals(MemorySegment.NULL))) {
            throw new IllegalArgumentException("bindingCount > 0 but bindings is NULL");
        }
        if (pushConstantBytes < 0) {
            throw new IllegalArgumentException("pushConstantBytes must not be negative");
        }

        try (Arena a = Arena.ofConfined()) {
            MemorySegment desc = a.allocate(BedrayCoreLayouts.PROGRAM_DESC);
            desc.set(JAVA_INT, BedrayCoreLayouts.PD_ABI_VERSION, BedrayCoreLayouts.ABI_VERSION);
            desc.set(ADDRESS, BedrayCoreLayouts.PD_NAME, a.allocateFrom(name, StandardCharsets.UTF_8));
            desc.set(ADDRESS, BedrayCoreLayouts.PD_ENTRY_POINT, a.allocateFrom(entryPoint, StandardCharsets.UTF_8));
            desc.set(ADDRESS, BedrayCoreLayouts.PD_SPIRV, spirv);
            desc.set(JAVA_LONG, BedrayCoreLayouts.PD_SPIRV_WORD_COUNT, spirvWordCount);
            desc.set(ADDRESS, BedrayCoreLayouts.PD_BINDINGS, bindingCount > 0 ? bindings : MemorySegment.NULL);
            desc.set(JAVA_INT, BedrayCoreLayouts.PD_BINDING_COUNT, bindingCount);
            desc.set(JAVA_INT, BedrayCoreLayouts.PD_PUSH_CONSTANT_BYTES, pushConstantBytes);

            MemorySegment out = a.allocate(ADDRESS);
            MemorySegment error = a.allocate(BedrayCoreLayouts.ERROR);
            int status = (int) registerProgramHandle.invokeExact(handle(core), desc, out, error);
            check("register_program", status, error);
            long program = out.get(JAVA_LONG, 0);
            if (program == 0) {
                throw new BedrayCoreException("register_program", BedrayCoreLayouts.STATUS_INTERNAL,
                    "program handle is zero", null);
            }
            return program;
        } catch (Throwable t) {
            throw rethrow("register_program", t);
        }
    }

    /**
     * Records one compute dispatch into {@code commandBuffer}. The core inserts no
     * barriers: the host owns the surrounding synchronization.
     *
     * @param resources array of {@code BedrayResource}, or {@link MemorySegment#NULL} when empty
     */
    public void renderFrame(long core, long commandBuffer, long program, int groupCountX, int groupCountY, int groupCountZ,
                            MemorySegment resources, int resourceCount, MemorySegment pushConstants, int pushConstantBytes) {
        renderPhases(core, commandBuffer, program, groupCountX, groupCountY, groupCountZ,
            resources, resourceCount, pushConstants, pushConstantBytes, -1, 1);
    }

    public void renderPhases(long core, long commandBuffer, long program, int groupCountX, int groupCountY, int groupCountZ,
                             MemorySegment resources, int resourceCount, MemorySegment pushConstants, int pushConstantBytes,
                             int phaseOffset, int phaseCount) {
        ensureOpen();
        requireHandle(core, "core");
        if (commandBuffer == 0) {
            throw new IllegalArgumentException("commandBuffer must not be zero");
        }
        requireHandle(program, "program");
        if (groupCountX <= 0 || groupCountY <= 0 || groupCountZ <= 0) {
            throw new IllegalArgumentException("dispatch group counts must be positive");
        }
        if (resourceCount < 0) {
            throw new IllegalArgumentException("resourceCount must not be negative");
        }
        if (resourceCount > 0 && (resources == null || resources.equals(MemorySegment.NULL))) {
            throw new IllegalArgumentException("resourceCount > 0 but resources is NULL");
        }
        if (pushConstantBytes < 0) {
            throw new IllegalArgumentException("pushConstantBytes must not be negative");
        }
        if (pushConstantBytes > 0 && (pushConstants == null || pushConstants.equals(MemorySegment.NULL))) {
            throw new IllegalArgumentException("pushConstantBytes > 0 but pushConstants is NULL");
        }

        try (Arena a = Arena.ofConfined()) {
            MemorySegment desc = a.allocate(BedrayCoreLayouts.FRAME_DESC);
            desc.set(JAVA_INT, BedrayCoreLayouts.FD_ABI_VERSION, BedrayCoreLayouts.ABI_VERSION);
            desc.set(JAVA_LONG, BedrayCoreLayouts.FD_COMMAND_BUFFER, commandBuffer);
            desc.set(JAVA_LONG, BedrayCoreLayouts.FD_PROGRAM, program);
            desc.set(JAVA_INT, BedrayCoreLayouts.FD_GROUP_COUNT_X, groupCountX);
            desc.set(JAVA_INT, BedrayCoreLayouts.FD_GROUP_COUNT_Y, groupCountY);
            desc.set(JAVA_INT, BedrayCoreLayouts.FD_GROUP_COUNT_Z, groupCountZ);
            desc.set(JAVA_INT, BedrayCoreLayouts.FD_RESOURCE_COUNT, resourceCount);
            desc.set(ADDRESS, BedrayCoreLayouts.FD_RESOURCES, resourceCount > 0 ? resources : MemorySegment.NULL);
            desc.set(ADDRESS, BedrayCoreLayouts.FD_PUSH_CONSTANTS, pushConstantBytes > 0 ? pushConstants : MemorySegment.NULL);
            desc.set(JAVA_INT, BedrayCoreLayouts.FD_PUSH_CONSTANT_BYTES, pushConstantBytes);

            MemorySegment error = a.allocate(BedrayCoreLayouts.ERROR);
            int status = phaseOffset < 0
                ? (int) renderFrameHandle.invokeExact(handle(core), desc, error)
                : (int) renderPhasesHandle.invokeExact(handle(core), desc, phaseOffset, phaseCount, error);
            check("render_frame", status, error);
        } catch (Throwable t) {
            throw rethrow("render_frame", t);
        }
    }

    /**
     * Invalidates temporal state and releases a recording after proven discard,
     * or after host completion with unknown execution and no future resubmission.
     */
    public void discardRecording(long core, long commandBuffer) {
        ensureOpen();
        requireHandle(core, "core");
        if (discardRecordingHandle == null) {
            releaseRecording(core, commandBuffer); // Older cores are torn down after host failure.
            return;
        }
        try {
            int status = (int) discardRecordingHandle.invokeExact(handle(core), commandBuffer);
            check("discard_recording", status, MemorySegment.NULL);
        } catch (Throwable t) {
            throw rethrow("discard_recording", t);
        }
    }

    /** Releases a completed recording that cannot be submitted again. */
    public void releaseRecording(long core, long commandBuffer) {
        ensureOpen();
        requireHandle(core, "core");
        requireHandle(commandBuffer, "commandBuffer");
        try {
            int status = (int) releaseRecordingHandle.invokeExact(handle(core), commandBuffer);
            check("release_recording", status, MemorySegment.NULL);
        } catch (Throwable t) {
            throw rethrow("release_recording", t);
        }
    }

    /**
     * Legacy-signature overload: keeps existing callers on the legacy section
     * table until they explicitly pass {@code program_trace_scene}. The native
     * descriptor still carries the new field at offset 64; zero selects the
     * legacy path in the C++ pass.
     */
    public void configurePathTrace(long core, long programTrace, long programSpatial, long programDisplay,
                                   long imageBudgetBytes, long bufferBudgetBytes, int width, int height) {
        configurePathTrace(core, programTrace, 0L, programSpatial, programDisplay,
            imageBudgetBytes, bufferBudgetBytes, width, height);
    }

    /**
     * Configures the native pass with registered program handles. Scene/spatial programs may be zero.
     * The pass owns private images, uploads and dummies and only records into host command buffers.
     */
    public void configurePathTrace(long core, long programTrace, long programTraceScene, long programSpatial,
                                   long programDisplay, long imageBudgetBytes, long bufferBudgetBytes,
                                   int width, int height) {
        ensureOpen();
        requireHandle(core, "core");
        requirePathTrace();
        requireHandle(programTrace, "programTrace");
        requireHandle(programDisplay, "programDisplay");
        if (imageBudgetBytes < 0 || bufferBudgetBytes < 0) {
            throw new IllegalArgumentException("path-trace budgets must not be negative");
        }
        if (width < 0 || height < 0) {
            throw new IllegalArgumentException("path-trace extent hint must not be negative");
        }
        try (Arena a = Arena.ofConfined()) {
            MemorySegment desc = a.allocate(BedrayCoreLayouts.PT_DESC);
            desc.set(JAVA_INT, BedrayCoreLayouts.PTD_ABI_VERSION, BedrayCoreLayouts.ABI_VERSION);
            desc.set(JAVA_LONG, BedrayCoreLayouts.PTD_PROGRAM_TRACE, programTrace);
            desc.set(JAVA_LONG, BedrayCoreLayouts.PTD_PROGRAM_SPATIAL, programSpatial);
            desc.set(JAVA_LONG, BedrayCoreLayouts.PTD_PROGRAM_DISPLAY, programDisplay);
            desc.set(JAVA_LONG, BedrayCoreLayouts.PTD_IMAGE_BUDGET_BYTES, imageBudgetBytes);
            desc.set(JAVA_LONG, BedrayCoreLayouts.PTD_BUFFER_BUDGET_BYTES, bufferBudgetBytes);
            desc.set(JAVA_INT, BedrayCoreLayouts.PTD_WIDTH, width);
            desc.set(JAVA_INT, BedrayCoreLayouts.PTD_HEIGHT, height);
            // Zero keeps every frame on the legacy section table.
            desc.set(JAVA_LONG, BedrayCoreLayouts.PTD_PROGRAM_TRACE_SCENE, programTraceScene);
            MemorySegment error = a.allocate(BedrayCoreLayouts.ERROR);
            int status = (int) configurePathTraceHandle.invokeExact(handle(core), desc, error);
            check("configure_path_trace", status, error);
        } catch (Throwable t) {
            throw rethrow("configure_path_trace", t);
        }
    }

    /**
     * Records trace → optional spatial → display for one frame. {@code frameDesc}
     * must be a native segment holding a fully initialised
     * {@code BedrayPathTraceFrameDesc}; the caller keeps every borrowed resource
     * alive and in GENERAL until {@link #releaseRecording} for the same command
     * buffer.
     */
    public void renderPathTrace(long core, MemorySegment frameDesc) {
        ensureOpen();
        requireHandle(core, "core");
        requirePathTrace();
        if (frameDesc == null || frameDesc.equals(MemorySegment.NULL)
            || frameDesc.byteSize() < BedrayCoreLayouts.PT_FRAME_DESC.byteSize()) {
            throw new IllegalArgumentException("frameDesc must hold a BedrayPathTraceFrameDesc");
        }
        try (Arena a = Arena.ofConfined()) {
            MemorySegment error = a.allocate(BedrayCoreLayouts.ERROR);
            int status = (int) renderPathTraceHandle.invokeExact(handle(core), frameDesc, error);
            check("render_path_trace", status, error);
        } catch (Throwable t) {
            throw rethrow("render_path_trace", t);
        }
    }

    /** Same frame recorder with seven borrowed host guide views. Input POD is consumed synchronously. */
    public void renderPathTraceGuides(long core, MemorySegment frameDesc, MemorySegment guides) {
        ensureOpen();
        requireHandle(core, "core");
        requirePathTrace();
        if (!hasNativeGuideOutputs())
            throw new BedrayCoreException("render_path_trace_guides", BedrayCoreLayouts.STATUS_UNSUPPORTED,
                "matching native guide-output exports are required", null);
        if (frameDesc == null || !frameDesc.isNative() || frameDesc.byteSize() < BedrayCoreLayouts.PT_FRAME_DESC.byteSize()
            || guides == null || !guides.isNative() || guides.byteSize() < BedrayCoreLayouts.PT_GUIDES_DESC.byteSize())
            throw new IllegalArgumentException("native frame and guide descriptors are required");
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment error = arena.allocate(BedrayCoreLayouts.ERROR);
            int status = (int) renderPathTraceGuidesHandle.invokeExact(handle(core), frameDesc, guides, error);
            check("render_path_trace_guides", status, error);
        } catch (Throwable failure) { throw rethrow("render_path_trace_guides", failure); }
    }

    /** Destroys the native pass. Refuses while one of its recordings is unreleased. */
    public void destroyPathTrace(long core) {
        ensureOpen();
        if (core == 0) {
            return;
        }
        requirePathTrace();
        try {
            int status = (int) destroyPathTraceHandle.invokeExact(handle(core));
            if (status == BedrayCoreLayouts.STATUS_IN_USE) {
                throw new BedrayCoreException("destroy_path_trace", status,
                    "path-trace recordings still reference the pass; release them after GPU completion or discard", null);
            }
            check("destroy_path_trace", status, MemorySegment.NULL);
        } catch (Throwable t) {
            throw rethrow("destroy_path_trace", t);
        }
    }

    /** True when the loaded core advertises ownership of DI allocation, history and parity. */
    public boolean hasNativeReservoirs() {
        return hasPathTraceFeature(BedrayCoreLayouts.PATH_TRACE_FEATURE_NATIVE_DI);
    }

    /** Accepts current 25-binding programs with inactive native guide stand-ins; does not enable guide writes. */
    public boolean hasGuideBindings() {
        return hasPathTraceFeature(BedrayCoreLayouts.PATH_TRACE_FEATURE_GUIDE_BINDINGS);
    }

    public boolean hasNativeGuideOutputs() {
        return hasPathTraceFeature(BedrayCoreLayouts.PATH_TRACE_FEATURE_GUIDE_OUTPUTS) && renderPathTraceGuidesHandle != null;
    }

    public boolean hasNativeMotion() {
        return renderPathTraceMotionHandle != null && hasPathTraceFeature(BedrayCoreLayouts.PATH_TRACE_FEATURE_NRD_MOTION);
    }

    /** True when the loaded core accepts and maps the camera-medium frame bit; an older core would reject the frame. */
    public boolean hasCameraInWaterFlag() {
        return hasPathTraceFeature(BedrayCoreLayouts.PATH_TRACE_FEATURE_CAMERA_IN_WATER);
    }

    /** True when the loaded core accepts and maps both water switches; an older core would reject the frame. */
    public boolean hasWaterModelFlags() {
        return hasPathTraceFeature(BedrayCoreLayouts.PATH_TRACE_FEATURE_WATER_MODEL);
    }

    public void renderPathTraceMotion(long core, MemorySegment frame, MemorySegment guides, MemorySegment motion) {
        ensureOpen(); requireHandle(core, "core"); requirePathTrace();
        if (!hasNativeMotion()) throw new BedrayCoreException("render_path_trace_motion", BedrayCoreLayouts.STATUS_UNSUPPORTED,
            "matching NRD_MOTION native exports are required", null);
        if (frame == null || !frame.isNative() || frame.byteSize() < BedrayCoreLayouts.PT_FRAME_DESC.byteSize()
            || guides == null || !guides.isNative() || guides.byteSize() < BedrayCoreLayouts.PT_GUIDES_DESC.byteSize()
            || motion == null || !motion.isNative() || motion.byteSize() < BedrayCoreLayouts.PT_BUFFER.byteSize())
            throw new IllegalArgumentException("native frame, guides and motion range are required");
        try (Arena arena = Arena.ofConfined()) {
            var error = arena.allocate(BedrayCoreLayouts.ERROR);
            check("render_path_trace_motion", (int) renderPathTraceMotionHandle.invokeExact(handle(core), frame, guides, motion, error), error);
        } catch (Throwable failure) { throw rethrow("render_path_trace_motion", failure); }
    }

    private boolean hasPathTraceFeature(int feature) {
        ensureOpen();
        if (pathTraceFeatureMaskHandle == null) return false;
        try {
            int features = (int) pathTraceFeatureMaskHandle.invokeExact();
            return (features & feature) == feature;
        } catch (Throwable t) {
            throw rethrow("path_trace_feature_mask", t);
        }
    }

    /** True when the loaded DLL exports the additive path-trace pass ABI. */
    public boolean hasPathTrace() {
        return configurePathTraceHandle != null
            && renderPathTraceHandle != null
            && destroyPathTraceHandle != null;
    }

    private void requirePathTrace() {
        if (!hasPathTrace()) {
            throw new BedrayCoreException("path_trace", BedrayCoreLayouts.STATUS_UNSUPPORTED,
                "the loaded bedray_core has no path-trace pass exports", null);
        }
    }

    public void destroyProgram(long core, long program) {
        ensureOpen();
        if (core == 0 || program == 0) {
            return;
        }
        try {
            destroyProgramHandle.invokeExact(handle(core), program);
        } catch (Throwable t) {
            throw rethrow("destroy_program", t);
        }
    }

    public void destroy(long core) {
        if (core == 0) {
            return;
        }
        ensureOpen();
        try {
            int status = (int) destroyHandle.invokeExact(handle(core));
            if (status == BedrayCoreLayouts.STATUS_IN_USE) {
                throw new BedrayCoreException("destroy", status,
                    "recordings still reference the core; release them after GPU completion or discard", null);
            }
            check("destroy", status, MemorySegment.NULL);
            liveCores.remove(core);
        } catch (Throwable t) {
            throw rethrow("destroy", t);
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        requireResourceOwnersReleased();
        for (long core : Set.copyOf(liveCores)) {
            destroy(core);
        }
        closed = true;
        arena.close();
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
        if (status == BedrayCoreLayouts.STATUS_OK) {
            return;
        }
        String message = "";
        if (!error.equals(MemorySegment.NULL) && error.get(JAVA_INT, BedrayCoreLayouts.ERROR_STATUS) == status) {
            message = error.getString(BedrayCoreLayouts.ERROR_MESSAGE, StandardCharsets.UTF_8);
        }
        throw new BedrayCoreException(operation, status, message, null);
    }

    void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("bedray_core is closed");
        }
    }

    private static RuntimeException rethrow(String operation, Throwable t) {
        if (t instanceof BedrayCoreException e) {
            return e;
        }
        if (t instanceof RuntimeException e) {
            return e;
        }
        if (t instanceof Error e) {
            throw e;
        }
        return new BedrayCoreException(operation, -1, t.toString(), t);
    }

    // ---- helpers for callers building the two arrays by hand

    public static void writeDescriptorBinding(MemorySegment at, int index, int binding, int descriptorType,
                                              int descriptorCount, int stageFlags) {
        long base = index * BedrayCoreLayouts.DESCRIPTOR_BINDING.byteSize();
        at.set(JAVA_INT, base + BedrayCoreLayouts.DB_BINDING, binding);
        at.set(JAVA_INT, base + BedrayCoreLayouts.DB_DESCRIPTOR_TYPE, descriptorType);
        at.set(JAVA_INT, base + BedrayCoreLayouts.DB_DESCRIPTOR_COUNT, descriptorCount);
        at.set(JAVA_INT, base + BedrayCoreLayouts.DB_STAGE_FLAGS, stageFlags);
    }

    public static void writeResource(MemorySegment at, int index, int binding, int descriptorType, int imageLayout,
                                     long imageViewOrBuffer, long sampler, long offset, long range) {
        long base = index * BedrayCoreLayouts.RESOURCE.byteSize();
        at.set(JAVA_INT, base + BedrayCoreLayouts.R_BINDING, binding);
        at.set(JAVA_INT, base + BedrayCoreLayouts.R_DESCRIPTOR_TYPE, descriptorType);
        at.set(JAVA_INT, base + BedrayCoreLayouts.R_IMAGE_LAYOUT, imageLayout);
        at.set(JAVA_LONG, base + BedrayCoreLayouts.R_HANDLE, imageViewOrBuffer);
        at.set(JAVA_LONG, base + BedrayCoreLayouts.R_SAMPLER, sampler);
        at.set(JAVA_LONG, base + BedrayCoreLayouts.R_OFFSET, offset);
        at.set(JAVA_LONG, base + BedrayCoreLayouts.R_RANGE, range);
    }
}
