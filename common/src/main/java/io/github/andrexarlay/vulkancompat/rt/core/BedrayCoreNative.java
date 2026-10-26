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
    private final Arena arena;
    private final Path libraryPath;
    private final Set<Long> liveCores = ConcurrentHashMap.newKeySet();
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
            int status = (int) renderFrameHandle.invokeExact(handle(core), desc, error);
            check("render_frame", status, error);
        } catch (Throwable t) {
            throw rethrow("render_frame", t);
        }
    }

    /**
     * Releases resources for a recording after all its GPU submissions have
     * completed and it cannot be submitted again, or after discarding an
     * unsubmitted recording. Call before re-recording this command buffer.
     */
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

    private void ensureOpen() {
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
