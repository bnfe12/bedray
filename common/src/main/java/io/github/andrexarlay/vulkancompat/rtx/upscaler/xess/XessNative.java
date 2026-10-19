package io.github.andrexarlay.vulkancompat.rtx.upscaler.xess;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BOOLEAN;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;

/**
 * FFM (java.lang.foreign, Java 25) binding of the Intel XeSS C API used for Vulkan super resolution.
 * Signatures are written by hand from {@code xess.h} / {@code xess_vk.h} (XeSS SDK 3.0.2, lines noted per method);
 * the headers are not part of this repository and {@code libxess.dll} is never packaged (installed by the user into
 * {@code <gameDir>/bedray/natives/}).
 * <p>
 * Threading: all calls must be made from the render thread (the one that calls {@code xessVKInit}).
 * The logging callback is the only code that can run on another thread; it touches nothing except the logger.
 * <p>
 * The DLL is loaded from an explicit path through {@link SymbolLookup#libraryLookup(Path, Arena)}; no
 * {@code System.load}, no PATH search, no other game's directory.
 * <p>
 */
public final class XessNative implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger("Bedray/XessNative");
    private static final Logger XESS_LOGGER = LoggerFactory.getLogger("XeSS");

    // xess_result_t (xess.h:147-182)
    public static final int RESULT_SUCCESS = 0;
    public static final int RESULT_WARNING_OLD_DRIVER = 2;

    // xess_init_flags_t (xess.h:106-127)
    public static final int INIT_FLAG_INVERTED_DEPTH = 1 << 1;
    public static final int INIT_FLAG_LDR_INPUT_COLOR = 1 << 6;

    // xess_logging_level_t (xess.h:187-193)
    public static final int LOG_LEVEL_INFO = 1;

    private final Arena arena = Arena.ofShared();
    private final Path libraryPath;

    private final MethodHandle getVersion;
    private final MethodHandle getOptimalInputResolution;
    private final MethodHandle setLoggingCallback;
    private final MethodHandle isOptimalDriver;
    private final MethodHandle destroyContext;
    private final MethodHandle getPipelineBuildStatus;
    private final MethodHandle vkGetRequiredInstanceExtensions;
    private final MethodHandle vkGetRequiredDeviceExtensions;
    private final MethodHandle vkGetRequiredDeviceFeatures;
    private final MethodHandle vkCreateContext;
    private final MethodHandle vkBuildPipelines;
    private final MethodHandle vkInit;
    private final MethodHandle vkGetInitParams;
    private final MethodHandle vkExecuteHandle;

    private MemorySegment loggingStub;

    /** Result of an "extensions" query: names are {@code const char*} owned by XeSS, copied here. */
    public record NameList(int result, List<String> names, int minVkApiVersion) {}

    public record Resolution(int x, int y) {
        @Override
        public String toString() {
            return x + "x" + y;
        }
    }

    public record OptimalInput(int result, Resolution optimal, Resolution min, Resolution max) {}

    /** sType and first VkBool32 (offset 16, right after pNext) of every structure in the returned pNext chain. */
    public record FeatureChain(int result, List<Integer> sTypes, List<Integer> firstBool32) {}

    public record ContextResult(int result, MemorySegment handle) {}

    /** One {@code xess_vk_image_view_info}: single-mip, single-layer view. */
    public record ViewInfo(long imageView, long image, int aspectMask, int format, int width, int height) {
        public static final ViewInfo NONE = new ViewInfo(0L, 0L, 0, 0, 0, 0);
    }

    /** Values read back by {@code xessVKGetInitParams}. */
    public record InitReadback(int result, int outW, int outH, int quality, int flags) {}

    private XessNative(Path libraryPath) {
        XessLayouts.verify();
        this.libraryPath = libraryPath;
        SymbolLookup lookup;
        try {
            lookup = SymbolLookup.libraryLookup(libraryPath, arena);
        } catch (IllegalArgumentException | UnsatisfiedLinkError e) {
            arena.close();
            throw new XessNativeException("Cannot load " + libraryPath
                + " (usual causes: missing Visual C++ 2015-2022 x64 Redistributable 14.40+, missing d3d12.dll/dxgi.dll, "
                + "or a non-x64 DLL): " + e, e);
        }
        Linker linker = Linker.nativeLinker();
        try {
            // xess_result_t xessGetVersion(xess_version_t*)                                      xess.h:213
            getVersion = bind(linker, lookup, "xessGetVersion", FunctionDescriptor.of(JAVA_INT, ADDRESS));
            // xessGetOptimalInputResolution(ctx, const xess_2d_t* out, quality, opt*, min*, max*)  xess.h:269
            getOptimalInputResolution = bind(linker, lookup, "xessGetOptimalInputResolution",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
            // xessSetLoggingCallback(ctx, level, callback)                                        xess.h:368
            setLoggingCallback = bind(linker, lookup, "xessSetLoggingCallback",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS));
            // xessIsOptimalDriver(ctx)                                                            xess.h:380
            isOptimalDriver = bind(linker, lookup, "xessIsOptimalDriver", FunctionDescriptor.of(JAVA_INT, ADDRESS));
            // xessDestroyContext(ctx)                                                             xess.h:297
            destroyContext = bind(linker, lookup, "xessDestroyContext", FunctionDescriptor.of(JAVA_INT, ADDRESS));
            // xessGetPipelineBuildStatus(ctx)                                                     xess.h:417
            getPipelineBuildStatus = bind(linker, lookup, "xessGetPipelineBuildStatus", FunctionDescriptor.of(JAVA_INT, ADDRESS));
            // xessVKGetRequiredInstanceExtensions(uint32_t* count, const char* const** names, uint32_t* minVk)  xess_vk.h:142
            vkGetRequiredInstanceExtensions = bind(linker, lookup, "xessVKGetRequiredInstanceExtensions",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
            // xessVKGetRequiredDeviceExtensions(VkInstance, VkPhysicalDevice, uint32_t*, const char* const**)  xess_vk.h:157
            vkGetRequiredDeviceExtensions = bind(linker, lookup, "xessVKGetRequiredDeviceExtensions",
                FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_LONG, ADDRESS, ADDRESS));
            // xessVKGetRequiredDeviceFeatures(VkInstance, VkPhysicalDevice, void** features)       xess_vk.h:182
            vkGetRequiredDeviceFeatures = bind(linker, lookup, "xessVKGetRequiredDeviceFeatures",
                FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_LONG, ADDRESS));
            // xessVKCreateContext(VkInstance, VkPhysicalDevice, VkDevice, xess_context_handle_t*)  xess_vk.h:193
            vkCreateContext = bind(linker, lookup, "xessVKCreateContext",
                FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_LONG, ADDRESS));
            // xessVKBuildPipelines(ctx, VkPipelineCache, bool blocking, uint32_t initFlags)         xess_vk.h:212
            vkBuildPipelines = bind(linker, lookup, "xessVKBuildPipelines",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, JAVA_BOOLEAN, JAVA_INT));
            // xessVKInit(ctx, const xess_vk_init_params_t*)                                         xess_vk.h:230
            vkInit = bind(linker, lookup, "xessVKInit", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
            // xessVKGetInitParams(ctx, xess_vk_init_params_t*)                                      xess_vk.h:242
            vkGetInitParams = bind(linker, lookup, "xessVKGetInitParams", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
            // xessVKExecute(ctx, VkCommandBuffer, const xess_vk_execute_params_t*)                  xess_vk.h:253
            vkExecuteHandle = bind(linker, lookup, "xessVKExecute", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, ADDRESS));
        } catch (RuntimeException e) {
            arena.close();
            throw e;
        }
    }

    /**
     * Loads {@code libxess.dll} from an explicit file.
     *
     * @throws XessNativeException if the file is missing, cannot be loaded (cause attached) or lacks an export
     */
    public static XessNative load(Path dll) {
        if (!Files.isRegularFile(dll)) {
            throw new XessNativeException("libxess.dll not found at " + dll
                + " (XeSS is unavailable; install the DLL from the Intel XeSS SDK there)");
        }
        XessNative n = new XessNative(dll.toAbsolutePath().normalize());
        LOGGER.info("Loaded {} ({} bytes)", n.libraryPath, fileSize(dll));
        return n;
    }

    private static long fileSize(Path p) {
        try {
            return Files.size(p);
        } catch (IOException e) {
            throw new XessNativeException("Cannot stat " + p, e);
        }
    }

    private static MethodHandle bind(Linker linker, SymbolLookup lookup, String name, FunctionDescriptor fd) {
        MemorySegment symbol = lookup.find(name)
            .orElseThrow(() -> new XessNativeException("libxess.dll has no export '" + name + "'"));
        return linker.downcallHandle(symbol, fd);
    }

    public Path libraryPath() {
        return libraryPath;
    }

    // ------------------------------------------------------------------ calls

    /** {@code xessGetVersion}: returns {@code major.minor.patch}; throws on a non-success result. */
    public String getVersionString() {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment v = a.allocate(XessLayouts.XESS_VERSION);
            int r = invokeInt(getVersion, v);
            if (r != RESULT_SUCCESS) {
                throw new XessNativeException("xessGetVersion failed, result=" + r);
            }
            return (v.get(JAVA_SHORT, 0) & 0xFFFF) + "." + (v.get(JAVA_SHORT, 2) & 0xFFFF) + "." + (v.get(JAVA_SHORT, 4) & 0xFFFF);
        }
    }

    /** {@code xessVKGetRequiredInstanceExtensions}. Needs no Vulkan handles. */
    public NameList getRequiredInstanceExtensions() {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment count = a.allocate(JAVA_INT);
            MemorySegment names = a.allocate(ADDRESS);
            MemorySegment minVk = a.allocate(JAVA_INT);
            int r = invokeInt(vkGetRequiredInstanceExtensions, count, names, minVk);
            return new NameList(r, r == RESULT_SUCCESS ? readStrings(count.get(JAVA_INT, 0), names) : List.of(), minVk.get(JAVA_INT, 0));
        }
    }

    /** {@code xessVKGetRequiredDeviceExtensions}; handles are native addresses of VkInstance / VkPhysicalDevice. */
    public NameList getRequiredDeviceExtensions(long vkInstance, long vkPhysicalDevice) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment count = a.allocate(JAVA_INT);
            MemorySegment names = a.allocate(ADDRESS);
            int r = invokeInt(vkGetRequiredDeviceExtensions, vkInstance, vkPhysicalDevice, count, names);
            return new NameList(r, r == RESULT_SUCCESS ? readStrings(count.get(JAVA_INT, 0), names) : List.of(), 0);
        }
    }

    /**
     * {@code xessVKGetRequiredDeviceFeatures}: the library returns its own pNext chain (memory owned by XeSS).
     * Only the chain is walked (VkBaseOutStructure: sType @0, pNext @8); every Vulkan feature structure is at least 24 bytes.
     */
    public FeatureChain getRequiredDeviceFeatures(long vkInstance, long vkPhysicalDevice) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment chainPtr = a.allocate(ADDRESS);
            chainPtr.set(ADDRESS, 0, MemorySegment.NULL);
            int r = invokeInt(vkGetRequiredDeviceFeatures, vkInstance, vkPhysicalDevice, chainPtr);
            List<Integer> sTypes = new ArrayList<>();
            List<Integer> firstBools = new ArrayList<>();
            if (r == RESULT_SUCCESS) {
                MemorySegment node = chainPtr.get(ADDRESS, 0);
                int guard = 0;
                while (node.address() != 0L) {
                    if (guard++ >= 64) {
                        throw new XessNativeException("xessVKGetRequiredDeviceFeatures returned a pNext chain longer than 64 structures");
                    }
                    MemorySegment s = node.reinterpret(24);
                    sTypes.add(s.get(JAVA_INT, 0));
                    firstBools.add(s.get(JAVA_INT, 16));
                    node = s.get(ADDRESS, 8);
                }
            }
            return new FeatureChain(r, List.copyOf(sTypes), List.copyOf(firstBools));
        }
    }

    /** {@code xessVKCreateContext}; on failure the handle is the NULL segment. */
    public ContextResult vkCreateContext(long vkInstance, long vkPhysicalDevice, long vkDevice) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment out = a.allocate(ADDRESS);
            out.set(ADDRESS, 0, MemorySegment.NULL);
            int r = invokeInt(vkCreateContext, vkInstance, vkPhysicalDevice, vkDevice, out);
            return new ContextResult(r, out.get(ADDRESS, 0));
        }
    }

    public int destroyContext(MemorySegment ctx) {
        return invokeInt(destroyContext, ctx);
    }

    public int isOptimalDriver(MemorySegment ctx) {
        return invokeInt(isOptimalDriver, ctx);
    }

    public int getPipelineBuildStatus(MemorySegment ctx) {
        return invokeInt(getPipelineBuildStatus, ctx);
    }

    public OptimalInput getOptimalInputResolution(MemorySegment ctx, int outW, int outH, XessQuality quality) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment out = a.allocate(XessLayouts.XESS_2D);
            out.set(JAVA_INT, 0, outW);
            out.set(JAVA_INT, 4, outH);
            MemorySegment opt = a.allocate(XessLayouts.XESS_2D);
            MemorySegment min = a.allocate(XessLayouts.XESS_2D);
            MemorySegment max = a.allocate(XessLayouts.XESS_2D);
            int r = invokeInt(getOptimalInputResolution, ctx, out, quality.nativeValue(), opt, min, max);
            return new OptimalInput(r, res(opt), res(min), res(max));
        }
    }

    public int vkBuildPipelines(MemorySegment ctx, long vkPipelineCache, boolean blocking, int initFlags) {
        return invokeInt(vkBuildPipelines, ctx, vkPipelineCache, blocking, initFlags);
    }

    /** {@code xessVKInit} with no external heaps (XeSS allocates internally). Blocking (kernel JIT). */
    public int vkInit(MemorySegment ctx, int outW, int outH, XessQuality quality, int initFlags, long vkPipelineCache) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment p = a.allocate(XessLayouts.XESS_VK_INIT_PARAMS); // zero-initialised
            p.set(JAVA_INT, XessLayouts.INIT_OFF_OUTPUT, outW);
            p.set(JAVA_INT, XessLayouts.INIT_OFF_OUTPUT + 4, outH);
            p.set(JAVA_INT, XessLayouts.INIT_OFF_QUALITY, quality.nativeValue());
            p.set(JAVA_INT, XessLayouts.INIT_OFF_FLAGS, initFlags);
            p.set(JAVA_LONG, XessLayouts.INIT_OFF_PIPELINE_CACHE, vkPipelineCache);
            return invokeInt(vkInit, ctx, p);
        }
    }

    /** {@code xessVKGetInitParams}: reads back (output, quality, flags) to cross-check the init struct layout. */
    public InitReadback vkGetInitParams(MemorySegment ctx) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment p = a.allocate(XessLayouts.XESS_VK_INIT_PARAMS);
            int r = invokeInt(vkGetInitParams, ctx, p);
            return new InitReadback(r,
                p.get(JAVA_INT, XessLayouts.INIT_OFF_OUTPUT), p.get(JAVA_INT, XessLayouts.INIT_OFF_OUTPUT + 4),
                p.get(JAVA_INT, XessLayouts.INIT_OFF_QUALITY), p.get(JAVA_INT, XessLayouts.INIT_OFF_FLAGS));
        }
    }

    /**
     * {@code xessVKExecute}: records the XeSS commands into {@code vkCommandBuffer} (native address of the VkCommandBuffer).
     * Inputs must already be in SHADER_READ_ONLY_OPTIMAL and the output in GENERAL; XeSS inserts no barriers.
     * Unused textures (exposure scale, responsive mask) are passed as zero handles (their init flags are not set).
     */
    public int vkExecute(MemorySegment ctx, long vkCommandBuffer, ViewInfo color, ViewInfo velocity, ViewInfo depth,
                         ViewInfo output, float jitterX, float jitterY, float exposureScale, boolean resetHistory,
                         int inputWidth, int inputHeight) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment p = a.allocate(XessLayouts.XESS_VK_EXECUTE_PARAMS); // zero-initialised
            writeView(p, "colorTexture", color);
            writeView(p, "velocityTexture", velocity);
            writeView(p, "depthTexture", depth);
            writeView(p, "outputTexture", output);
            p.set(JAVA_FLOAT, XessLayouts.EXEC_OFF_JITTER_X, jitterX);
            p.set(JAVA_FLOAT, XessLayouts.EXEC_OFF_JITTER_X + 4, jitterY);
            p.set(JAVA_FLOAT, XessLayouts.EXEC_OFF_JITTER_X + 8, exposureScale);
            p.set(JAVA_INT, XessLayouts.EXEC_OFF_JITTER_X + 12, resetHistory ? 1 : 0);
            p.set(JAVA_INT, XessLayouts.EXEC_OFF_JITTER_X + 16, inputWidth);
            p.set(JAVA_INT, XessLayouts.EXEC_OFF_JITTER_X + 20, inputHeight);
            try {
                return (int) vkExecuteHandle.invokeExact(ctx, vkCommandBuffer, p);
            } catch (RuntimeException | Error e) {
                throw e;
            } catch (Throwable t) {
                throw new XessNativeException("xessVKExecute failed", t);
            }
        }
    }

    private static void writeView(MemorySegment params, String field, ViewInfo v) {
        long o = XessLayouts.XESS_VK_EXECUTE_PARAMS.byteOffset(java.lang.foreign.MemoryLayout.PathElement.groupElement(field));
        params.set(JAVA_LONG, o, v.imageView());
        params.set(JAVA_LONG, o + 8, v.image());
        params.set(JAVA_INT, o + 16, v.aspectMask());
        params.set(JAVA_INT, o + 20, 0);                         // baseMipLevel
        params.set(JAVA_INT, o + 24, v.imageView() == 0L ? 0 : 1); // levelCount
        params.set(JAVA_INT, o + 28, 0);                         // baseArrayLayer
        params.set(JAVA_INT, o + 32, v.imageView() == 0L ? 0 : 1); // layerCount
        params.set(JAVA_INT, o + 36, v.format());
        params.set(JAVA_INT, o + 40, v.width());
        params.set(JAVA_INT, o + 44, v.height());
    }

    /** Installs an upcall that forwards XeSS log lines to SLF4J logger "XeSS". */
    public int installLoggingCallback(MemorySegment ctx, int minLevel) {
        if (loggingStub == null) {
            try {
                MethodHandle target = MethodHandles.lookup().findStatic(XessNative.class, "onNativeLog",
                    MethodType.methodType(void.class, MemorySegment.class, int.class));
                loggingStub = Linker.nativeLinker().upcallStub(target, FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT), arena);
            } catch (NoSuchMethodException | IllegalAccessException e) {
                throw new XessNativeException("Cannot create the XeSS logging upcall", e);
            }
        }
        return invokeInt(setLoggingCallback, ctx, minLevel, loggingStub);
    }

    /**
     * Upcall target, may run on any thread. An exception escaping an upcall aborts the JVM, so everything here is
     * caught and reported through the logger (reporting, not suppression).
     */
    @SuppressWarnings("unused")
    private static void onNativeLog(MemorySegment message, int level) {
        try {
            String text = message.reinterpret(Long.MAX_VALUE).getString(0);
            switch (level) {
                case 3 -> XESS_LOGGER.error("{}", text);
                case 2 -> XESS_LOGGER.warn("{}", text);
                default -> XESS_LOGGER.info("{}", text);
            }
        } catch (Throwable t) {
            XESS_LOGGER.error("Failed to read a XeSS log message", t);
        }
    }

    /** Unloads the DLL. Only after every context created from it has been destroyed. */
    @Override
    public void close() {
        arena.close();
    }

    // ------------------------------------------------------------------ helpers

    private static Resolution res(MemorySegment s) {
        return new Resolution(s.get(JAVA_INT, 0), s.get(JAVA_INT, 4));
    }

    private static List<String> readStrings(int count, MemorySegment namesPtr) {
        List<String> out = new ArrayList<>(count);
        if (count <= 0) {
            return out;
        }
        MemorySegment array = namesPtr.get(ADDRESS, 0).reinterpret((long) count * ADDRESS.byteSize());
        for (int i = 0; i < count; i++) {
            out.add(array.getAtIndex(ADDRESS, i).reinterpret(Long.MAX_VALUE).getString(0));
        }
        return out;
    }

    private static int invokeInt(MethodHandle h, Object... args) {
        try {
            return (int) h.invokeWithArguments(args);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable t) {
            throw new XessNativeException("XeSS downcall failed", t);
        }
    }
}
