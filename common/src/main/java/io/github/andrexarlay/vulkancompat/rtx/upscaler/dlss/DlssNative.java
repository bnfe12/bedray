package io.github.andrexarlay.vulkancompat.rtx.upscaler.dlss;

import io.github.andrexarlay.vulkanbridge.platform.LogPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_CHAR;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * FFM binding of our own wrapper {@code vkb_dlss.dll} ({@code native/dlss_bridge/include/vkb_dlss.h}, API 0.1.0).
 * The wrapper links the NVIDIA NGX SDK statically; the actual DLSS model library {@code nvngx_dlss.dll} is loaded by NGX
 * from {@code featurePath}. Neither DLL is packaged: the caller gets both paths from Bedray Core's
 * {@code NativeLibraries.resolve("dlss")} (see {@link DlssAvailability}); this class never searches for files itself.
 * <p>
 * Threading: all calls must be made from the render thread (NGX records into command buffers of that thread). The NGX log
 * callback is the only code that may run elsewhere; it touches nothing except the logger.
 */
public final class DlssNative implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger("Bedray/DlssNative");
    private static final Logger NGX_LOGGER = LoggerFactory.getLogger("NGX");

    /** {@code VKB_DLSS_API_VERSION} major this binding was written for (vkb_dlss.h: 0.1.0). */
    public static final int EXPECTED_API_MAJOR = 0;
    public static final int EXPECTED_API_MINOR = 1;

    public static final int OK = 0;
    public static final int ERR_ALREADY_INIT = 3;
    public static final int ERR_UNSUPPORTED = 5;

    /** Result of a wrapper call: code (VkbDlssResult), the wrapper's thread-local message and the last NGX result. */
    public record Status(int code, String error, int ngxResult) {
        public boolean ok() {
            return code == OK;
        }

        @Override
        public String toString() {
            return "code=" + code + " ngx=0x" + Integer.toHexString(ngxResult) + " error=" + error;
        }
    }

    /** Filled by {@code vkb_dlss_init}. */
    public record Caps(boolean superSamplingAvailable, boolean needsUpdatedDriver, int minDriverMajor, int minDriverMinor,
                       int featureInitResult, int ngxResultInit) {}

    /** Filled by {@code vkb_dlss_query_optimal_settings}: sizes as NGX reports them. */
    public record OptimalSettings(int renderWidth, int renderHeight, int minWidth, int minHeight,
                                  int maxWidth, int maxHeight, float sharpness) {}

    /** Names of a required-extension query; {@code names} are copied out of wrapper memory. */
    public record NameList(Status status, List<String> names) {}

    /** One image of an evaluate call (a {@code VkbDlssImage}). */
    public record Image(long image, long view, int format, int width, int height, int aspectMask, boolean readWrite) {
        public static final Image NONE = new Image(0L, 0L, 0, 0, 0, 0, false);
    }

    /** Everything of a {@code VkbDlssEvalInfo}; {@code exposure.view == 0} means "not provided". */
    public record EvalInfo(boolean reset, Image color, Image output, Image depth, Image motionVectors, Image exposure,
                           float jitterX, float jitterY, float mvScaleX, float mvScaleY, float sharpness,
                           float preExposure, float exposureScale, int renderSubrectWidth, int renderSubrectHeight) {}

    private final Arena arena = Arena.ofShared();
    private final Path libraryPath;
    private MemorySegment logStub;

    private final MethodHandle getApiVersion;
    private final MethodHandle getNgxApiVersion;
    private final MethodHandle getLastErrorString;
    private final MethodHandle getLastNgxResult;
    private final MethodHandle setLogCallback;
    private final MethodHandle getRequiredInstanceExtensions;
    private final MethodHandle getRequiredDeviceExtensions;
    private final MethodHandle init;
    private final MethodHandle shutdown;
    private final MethodHandle queryOptimalSettings;
    private final MethodHandle createFeature;
    private final MethodHandle evaluate;
    private final MethodHandle releaseFeature;
    private final MethodHandle queryCaps;

    private DlssNative(Path libraryPath) {
        DlssLayouts.verify();
        this.libraryPath = libraryPath;
        SymbolLookup lookup;
        try {
            lookup = SymbolLookup.libraryLookup(libraryPath, arena);
        } catch (IllegalArgumentException | UnsatisfiedLinkError e) {
            arena.close();
            throw new DlssNativeException("Cannot load " + LogPaths.show(libraryPath) + " (is it an x64 DLL built by native/dlss_bridge/build.bat?): " + LogPaths.show(e), e);
        }
        Linker linker = Linker.nativeLinker();
        try {
            getApiVersion = bind(linker, lookup, "vkb_dlss_get_api_version", FunctionDescriptor.of(JAVA_INT));
            getNgxApiVersion = bind(linker, lookup, "vkb_dlss_get_ngx_api_version", FunctionDescriptor.of(JAVA_INT));
            getLastErrorString = bind(linker, lookup, "vkb_dlss_get_last_error_string", FunctionDescriptor.of(ADDRESS));
            getLastNgxResult = bind(linker, lookup, "vkb_dlss_get_last_ngx_result", FunctionDescriptor.of(JAVA_INT));
            setLogCallback = bind(linker, lookup, "vkb_dlss_set_log_callback", FunctionDescriptor.ofVoid(ADDRESS));
            // int32_t (const wchar_t* featurePath, const wchar_t* dataPath, uint32_t* count, const char* const** names)
            getRequiredInstanceExtensions = bind(linker, lookup, "vkb_dlss_get_required_instance_extensions",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
            // int32_t (uint64 instance, uint64 physicalDevice, const wchar_t*, const wchar_t*, uint32_t*, const char* const**)
            getRequiredDeviceExtensions = bind(linker, lookup, "vkb_dlss_get_required_device_extensions",
                FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_LONG, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
            // int32_t (instance, physicalDevice, device, void* gipa, void* gdpa, featurePath, logPath, projectId, engineVersion, VkbDlssCaps*)
            init = bind(linker, lookup, "vkb_dlss_init", FunctionDescriptor.of(JAVA_INT,
                JAVA_LONG, JAVA_LONG, JAVA_LONG, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
            shutdown = bind(linker, lookup, "vkb_dlss_shutdown", FunctionDescriptor.ofVoid());
            // int32_t (int32_t qualityMode, uint32_t outWidth, uint32_t outHeight, VkbDlssOptimalSettings*)
            queryOptimalSettings = bind(linker, lookup, "vkb_dlss_query_optimal_settings",
                FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS));
            // int32_t (uint64 commandBuffer, const VkbDlssCreateInfo*, uint64_t* outFeature)
            createFeature = bind(linker, lookup, "vkb_dlss_create_feature",
                FunctionDescriptor.of(JAVA_INT, JAVA_LONG, ADDRESS, ADDRESS));
            // int32_t (uint64 commandBuffer, uint64 feature, const VkbDlssEvalInfo*)
            evaluate = bind(linker, lookup, "vkb_dlss_evaluate", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_LONG, ADDRESS));
            releaseFeature = bind(linker, lookup, "vkb_dlss_release_feature", FunctionDescriptor.of(JAVA_INT, JAVA_LONG));
            queryCaps = lookup.find("vkb_dlss_query_caps")
                .map(sym -> linker.downcallHandle(sym, FunctionDescriptor.of(JAVA_INT, ADDRESS)))
                .orElse(null);
        } catch (RuntimeException e) {
            arena.close();
            throw e;
        }
    }

    /**
     * Loads the wrapper from {@code vkb_dlss.dll} (a path that {@code NativeLibraries.resolve} returned) and checks its API version.
     *
     * @throws DlssNativeException when the DLL cannot be loaded, a symbol is missing or the API version does not match
     */
    public static DlssNative load(Path vkbDlssDll) {
        DlssNative lib = new DlssNative(vkbDlssDll);
        int version = lib.apiVersion();
        int major = version >>> 16;
        int minor = (version >>> 8) & 0xFF;
        if (major != EXPECTED_API_MAJOR || minor < EXPECTED_API_MINOR) {
            lib.arena.close();
            throw new DlssNativeException("vkb_dlss.dll API 0x" + Integer.toHexString(version) + " does not match the Java binding "
                + EXPECTED_API_MAJOR + "." + EXPECTED_API_MINOR + " (rebuild native/dlss_bridge/build.bat)", null);
        }
        return lib;
    }

    private static MethodHandle bind(Linker linker, SymbolLookup lookup, String name, FunctionDescriptor fd) {
        MemorySegment symbol = lookup.find(name).orElseThrow(() -> new DlssNativeException("vkb_dlss.dll has no export " + name, null));
        return linker.downcallHandle(symbol, fd);
    }

    public Path libraryPath() {
        return libraryPath;
    }

    public int apiVersion() {
        return invokeInt(getApiVersion);
    }

    /** {@code NVSDK_NGX_Version_API} the wrapper was built against. */
    public int ngxApiVersion() {
        return invokeInt(getNgxApiVersion);
    }

    /** UTF-16 NUL-terminated copy for a {@code const wchar_t*} parameter (wchar_t is 16 bit on Windows). */
    private static MemorySegment wide(Arena a, Path path) {
        return a.allocateFrom(JAVA_CHAR, (path.toString() + "\0").toCharArray());
    }

    private Status lastStatus(int code) {
        try {
            MemorySegment msg = ((MemorySegment) getLastErrorString.invokeExact()).reinterpret(Long.MAX_VALUE);
            int ngx = (int) getLastNgxResult.invokeExact();
            return new Status(code, code == OK ? "" : msg.getString(0), ngx);
        } catch (Throwable t) {
            throw new DlssNativeException("vkb_dlss_get_last_error_string failed", t);
        }
    }

    /** Instance extensions NGX needs (call before the instance exists); names copied out of wrapper memory. */
    public NameList requiredInstanceExtensions(Path featureDir, Path dataDir) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment count = a.allocate(JAVA_INT);
            MemorySegment names = a.allocate(ADDRESS);
            int r = invokeInt(getRequiredInstanceExtensions, wide(a, featureDir), wide(a, dataDir), count, names);
            return new NameList(lastStatus(r), r == OK ? readStrings(count.get(JAVA_INT, 0), names) : List.of());
        }
    }

    /** Device extensions NGX needs for this physical device; handles are native addresses of VkInstance / VkPhysicalDevice. */
    public NameList requiredDeviceExtensions(long vkInstance, long vkPhysicalDevice, Path featureDir, Path dataDir) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment count = a.allocate(JAVA_INT);
            MemorySegment names = a.allocate(ADDRESS);
            int r = invokeInt(getRequiredDeviceExtensions, vkInstance, vkPhysicalDevice,
                wide(a, featureDir), wide(a, dataDir), count, names);
            return new NameList(lastStatus(r), r == OK ? readStrings(count.get(JAVA_INT, 0), names) : List.of());
        }
    }

    /**
     * {@code vkb_dlss_init}: NGX init for the engine device. On {@link #ERR_UNSUPPORTED} NGX may stay initialised and
     * {@code outCaps[0]} is filled. Only call {@link #shutdown()} when this process exclusively owns NGX; Streamline
     * shares the process-level NGX runtime for DLSS-G.
     */
    public Status init(long vkInstance, long vkPhysicalDevice, long vkDevice, long getInstanceProcAddr, long getDeviceProcAddr,
                       Path featureDir, Path logDir, String projectId, String engineVersion, Caps[] outCaps) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment caps = a.allocate(DlssLayouts.CAPS);
            caps.set(JAVA_INT, 0, DlssLayouts.SIZE_CAPS);
            int r = invokeInt(init, vkInstance, vkPhysicalDevice, vkDevice,
                MemorySegment.ofAddress(getInstanceProcAddr), MemorySegment.ofAddress(getDeviceProcAddr),
                wide(a, featureDir), wide(a, logDir),
                projectId == null ? MemorySegment.NULL : a.allocateFrom(projectId),
                engineVersion == null ? MemorySegment.NULL : a.allocateFrom(engineVersion), caps);
            outCaps[0] = new Caps(caps.get(JAVA_INT, 4) != 0, caps.get(JAVA_INT, 8) != 0, caps.get(JAVA_INT, 12),
                caps.get(JAVA_INT, 16), caps.get(JAVA_INT, 20), caps.get(JAVA_INT, 24));
            return lastStatus(r);
        }
    }

    /** Re-queries capability parameters for the already-initialised NGX session (e.g. while model is loading). */
    public Status queryCaps(Caps[] outCaps) {
        if (queryCaps != null) {
            try (Arena a = Arena.ofConfined()) {
                MemorySegment caps = a.allocate(DlssLayouts.CAPS);
                caps.set(JAVA_INT, 0, DlssLayouts.SIZE_CAPS);
                int r = invokeInt(queryCaps, caps);
                if (outCaps != null && outCaps.length > 0) {
                    outCaps[0] = new Caps(caps.get(JAVA_INT, 4) != 0, caps.get(JAVA_INT, 8) != 0, caps.get(JAVA_INT, 12),
                        caps.get(JAVA_INT, 16), caps.get(JAVA_INT, 20), caps.get(JAVA_INT, 24));
                }
                return lastStatus(r);
            } catch (Throwable t) {
                throw new DlssNativeException("vkb_dlss_query_caps failed", t);
            }
        }
        return new Status(ERR_UNSUPPORTED, "vkb_dlss_query_caps not found", 0);
    }

    /** Releases all features and the process-level NGX runtime; the GPU must be idle and NGX must not be shared with Streamline. */
    public void shutdown() {
        try {
            shutdown.invokeExact();
        } catch (Throwable t) {
            throw new DlssNativeException("vkb_dlss_shutdown failed", t);
        }
    }

    /** NGX optimal render resolution for a mode and output size (never hard-coded ratios). */
    public Status queryOptimalSettings(DlssQuality quality, int outWidth, int outHeight, OptimalSettings[] out) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment s = a.allocate(DlssLayouts.OPTIMAL_SETTINGS);
            s.set(JAVA_INT, 0, DlssLayouts.SIZE_OPTIMAL_SETTINGS);
            int r = invokeInt(queryOptimalSettings, quality.nativeValue(), outWidth, outHeight, s);
            if (r == OK) {
                out[0] = new OptimalSettings(s.get(JAVA_INT, 4), s.get(JAVA_INT, 8), s.get(JAVA_INT, 12), s.get(JAVA_INT, 16),
                    s.get(JAVA_INT, 20), s.get(JAVA_INT, 24), s.get(JAVA_FLOAT, 28));
            }
            return lastStatus(r);
        }
    }

    /** Records feature creation into {@code commandBuffer}; the new handle (opaque, not an NGX pointer) goes to {@code outFeature[0]}. */
    public Status createFeature(long commandBuffer, int renderW, int renderH, int outW, int outH, DlssQuality quality,
                                int flags, long[] outFeature) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment info = a.allocate(DlssLayouts.CREATE_INFO);
            info.set(JAVA_INT, 0, DlssLayouts.SIZE_CREATE_INFO);
            info.set(JAVA_INT, 4, renderW);
            info.set(JAVA_INT, 8, renderH);
            info.set(JAVA_INT, 12, outW);
            info.set(JAVA_INT, 16, outH);
            info.set(JAVA_INT, 20, quality.nativeValue());
            info.set(JAVA_INT, 24, flags);
            info.set(JAVA_INT, 28, 0);
            MemorySegment handle = a.allocate(JAVA_LONG);
            int r = invokeInt(createFeature, commandBuffer, info, handle);
            if (r == OK) {
                outFeature[0] = handle.get(JAVA_LONG, 0);
            }
            return lastStatus(r);
        }
    }

    /** Records the DLSS evaluate into {@code commandBuffer}. NGX binds its own pipelines: the caller re-binds its state. */
    public Status evaluate(long commandBuffer, long feature, EvalInfo e) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment s = a.allocate(DlssLayouts.EVAL_INFO);
            s.set(JAVA_INT, DlssLayouts.offset(DlssLayouts.EVAL_INFO, "struct_size"), DlssLayouts.SIZE_EVAL_INFO);
            s.set(JAVA_INT, DlssLayouts.offset(DlssLayouts.EVAL_INFO, "reset"), e.reset() ? 1 : 0);
            writeImage(s, "color", e.color());
            writeImage(s, "output", e.output());
            writeImage(s, "depth", e.depth());
            writeImage(s, "motion_vectors", e.motionVectors());
            writeImage(s, "exposure", e.exposure());
            s.set(JAVA_FLOAT, DlssLayouts.offset(DlssLayouts.EVAL_INFO, "jitter_x"), e.jitterX());
            s.set(JAVA_FLOAT, DlssLayouts.offset(DlssLayouts.EVAL_INFO, "jitter_y"), e.jitterY());
            s.set(JAVA_FLOAT, DlssLayouts.offset(DlssLayouts.EVAL_INFO, "mv_scale_x"), e.mvScaleX());
            s.set(JAVA_FLOAT, DlssLayouts.offset(DlssLayouts.EVAL_INFO, "mv_scale_y"), e.mvScaleY());
            s.set(JAVA_FLOAT, DlssLayouts.offset(DlssLayouts.EVAL_INFO, "sharpness"), e.sharpness());
            s.set(JAVA_FLOAT, DlssLayouts.offset(DlssLayouts.EVAL_INFO, "pre_exposure"), e.preExposure());
            s.set(JAVA_FLOAT, DlssLayouts.offset(DlssLayouts.EVAL_INFO, "exposure_scale"), e.exposureScale());
            s.set(JAVA_INT, DlssLayouts.offset(DlssLayouts.EVAL_INFO, "render_subrect_width"), e.renderSubrectWidth());
            s.set(JAVA_INT, DlssLayouts.offset(DlssLayouts.EVAL_INFO, "render_subrect_height"), e.renderSubrectHeight());
            return lastStatus(invokeInt(evaluate, commandBuffer, feature, s));
        }
    }

    private static void writeImage(MemorySegment evalInfo, String member, Image img) {
        long base = DlssLayouts.offset(DlssLayouts.EVAL_INFO, member);
        evalInfo.set(JAVA_LONG, base + DlssLayouts.offset(DlssLayouts.IMAGE, "image"), img.image());
        evalInfo.set(JAVA_LONG, base + DlssLayouts.offset(DlssLayouts.IMAGE, "view"), img.view());
        evalInfo.set(JAVA_INT, base + DlssLayouts.offset(DlssLayouts.IMAGE, "format"), img.format());
        evalInfo.set(JAVA_INT, base + DlssLayouts.offset(DlssLayouts.IMAGE, "width"), img.width());
        evalInfo.set(JAVA_INT, base + DlssLayouts.offset(DlssLayouts.IMAGE, "height"), img.height());
        evalInfo.set(JAVA_INT, base + DlssLayouts.offset(DlssLayouts.IMAGE, "aspect_mask"), img.aspectMask());
        evalInfo.set(JAVA_INT, base + DlssLayouts.offset(DlssLayouts.IMAGE, "base_mip_level"), 0);
        evalInfo.set(JAVA_INT, base + DlssLayouts.offset(DlssLayouts.IMAGE, "level_count"), 1);
        evalInfo.set(JAVA_INT, base + DlssLayouts.offset(DlssLayouts.IMAGE, "base_array_layer"), 0);
        evalInfo.set(JAVA_INT, base + DlssLayouts.offset(DlssLayouts.IMAGE, "layer_count"), 1);
        evalInfo.set(JAVA_INT, base + DlssLayouts.offset(DlssLayouts.IMAGE, "read_write"), img.readWrite() ? 1 : 0);
    }

    /** The GPU must no longer use the feature. */
    public Status releaseFeature(long feature) {
        return lastStatus(invokeInt(releaseFeature, feature));
    }

    /** Routes NGX log lines (level 0 off, 1 on, 2 verbose) into the {@code NGX} logger. */
    public void installLogCallback() {
        if (logStub != null) {
            return;
        }
        try {
            MethodHandle target = MethodHandles.lookup().findStatic(DlssNative.class, "onNativeLog",
                MethodType.methodType(void.class, MemorySegment.class, int.class));
            logStub = Linker.nativeLinker().upcallStub(target, FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT), arena);
            setLogCallback.invokeExact(logStub);
        } catch (Throwable t) {
            throw new DlssNativeException("Cannot install the NGX log callback", t);
        }
    }

    /**
     * Upcall target, may run on any thread. An exception escaping an upcall aborts the JVM, so everything here is
     * caught and reported through the logger (reporting, not suppression).
     */
    @SuppressWarnings("unused")
    private static void onNativeLog(MemorySegment message, int level) {
        try {
            String text = message.reinterpret(Long.MAX_VALUE).getString(0).stripTrailing();
            NGX_LOGGER.info("{}", text);
        } catch (Throwable t) {
            LOGGER.error("NGX log callback failed", t);
        }
    }

    private static List<String> readStrings(int count, MemorySegment namesPtr) {
        List<String> out = new ArrayList<>(count);
        if (count == 0) {
            return out;
        }
        MemorySegment array = namesPtr.get(ADDRESS, 0).reinterpret((long) count * ADDRESS.byteSize());
        for (int i = 0; i < count; i++) {
            out.add(array.getAtIndex(ADDRESS, i).reinterpret(Long.MAX_VALUE).getString(0));
        }
        return out;
    }

    private static int invokeInt(MethodHandle handle, Object... args) {
        try {
            return (int) handle.invokeWithArguments(args);
        } catch (Throwable t) {
            throw new DlssNativeException("native call failed: " + handle.type(), t);
        }
    }

    @Override
    public void close() {
        arena.close();
    }
}
