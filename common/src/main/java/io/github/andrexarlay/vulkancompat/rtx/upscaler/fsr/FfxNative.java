package io.github.andrexarlay.vulkancompat.rtx.upscaler.fsr;

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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * FFM (java.lang.foreign) binding of the AMD FidelityFX API ("ffx-api") as exported by the official signed
 * {@code amd_fidelityfx_vk.dll} of FidelityFX SDK v1.1.4 ({@code PrebuiltSignedDLL}). Exports (ffx_api.h l.130-154):
 * {@code ffxCreateContext, ffxDestroyContext, ffxConfigure, ffxQuery, ffxDispatch}; all return {@code uint32 ffxReturnCode_t}
 * and take {@code ffxContext*} (a pointer to the opaque context handle) as first argument.
 * <p>
 * Only the upscaler effect is bound (FSR 3.1.x / FSR 2.x providers, selected by {@code ffxOverrideVersion}). The DLL is
 * loaded from the explicit path returned by the Bedray Core {@code NativeLibraries.resolve("fsr")}; never from PATH.
 * All calls on the render thread. The message callback may run on any thread and only logs.
 */
public final class FfxNative {
    private static final Logger LOGGER = LoggerFactory.getLogger("Bedray/FfxNative");
    private static final Logger FFX_LOGGER = LoggerFactory.getLogger("FidelityFX");

    // ffx_api.h l.32-41
    public static final int RETURN_OK = 0;
    // ffx_api.h l.85/96/103, ffx_upscale.h l.70-136, vk/ffx_api_vk.h l.39
    public static final long DESC_QUERY_GET_VERSIONS = 4L;
    public static final long DESC_OVERRIDE_VERSION = 5L;
    public static final long DESC_QUERY_GET_PROVIDER_VERSION = 6L;
    public static final long DESC_CREATE_BACKEND_VK = 0x3L;
    public static final long DESC_CREATE_UPSCALE = 0x00010000L;
    public static final long DESC_DISPATCH_UPSCALE = 0x00010001L;
    public static final long DESC_QUERY_UPSCALE_RATIO = 0x00010002L;
    public static final long DESC_QUERY_RENDER_RESOLUTION = 0x00010003L;
    public static final long DESC_QUERY_JITTER_PHASE_COUNT = 0x00010004L;

    // FfxApiCreateContextUpscaleFlags (ffx_upscale.h l.40-51)
    public static final int CREATE_HDR = 1 << 0;
    public static final int CREATE_DISPLAY_RES_MV = 1 << 1;
    public static final int CREATE_MV_JITTER_CANCELLATION = 1 << 2;
    public static final int CREATE_DEPTH_INVERTED = 1 << 3;
    public static final int CREATE_DEPTH_INFINITE = 1 << 4;
    public static final int CREATE_AUTO_EXPOSURE = 1 << 5;
    public static final int CREATE_DYNAMIC_RESOLUTION = 1 << 6;
    public static final int CREATE_DEBUG_CHECKING = 1 << 7;
    public static final int CREATE_NON_LINEAR_COLORSPACE = 1 << 8;

    // ffx_api_types.h enums
    public static final int RESOURCE_TYPE_TEXTURE2D = 2;          // FfxApiResourceType l.117
    public static final int FORMAT_R16G16_FLOAT = 18;             // FfxApiSurfaceFormat l.29 (0-based enum order)
    public static final int FORMAT_R8G8B8A8_UNORM = 10;
    public static final int FORMAT_R32_FLOAT = 28;
    public static final int USAGE_READ_ONLY = 0;                  // FfxApiResourceUsage l.73
    public static final int USAGE_UAV = 1 << 1;
    public static final int USAGE_DEPTHTARGET = 1 << 2;
    public static final int STATE_COMMON = 1 << 0;                // FfxApiResourceState l.86 (VK backend: GENERAL layout)
    public static final int STATE_UNORDERED_ACCESS = 1 << 1;      // (VK backend: GENERAL layout)

    private final Arena arena = Arena.ofShared();
    private final Path libraryPath;
    private final MethodHandle createContext;
    private final MethodHandle destroyContext;
    private final MethodHandle query;
    private final MethodHandle dispatch;
    private MemorySegment messageStub;

    /** One upscaler provider found in the DLL: id for {@code ffxOverrideVersion} and the display name ("3.1.4"). */
    public record ProviderVersion(long id, String name) {
        @Override
        public String toString() {
            return name + "(0x" + Long.toHexString(id) + ")";
        }
    }

    public record Size(int width, int height) {
        @Override
        public String toString() {
            return width + "x" + height;
        }
    }

    public record QueryResult<T>(int result, T value) {}

    /** A created context: {@code holder} is the 8-byte {@code ffxContext} variable whose address is the {@code ffxContext*}. */
    public record Context(MemorySegment holder) {
        public long handle() {
            return holder.get(JAVA_LONG, 0);
        }
    }

    public record ContextResult(int result, Context context) {}

    /** One image handed to ffxDispatch: raw {@code VkImage} plus the description the VK backend builds its views from. */
    public record Image(long vkImage, int ffxFormat, int width, int height, int usage, int state) {
        public static final Image NONE = new Image(0L, 0, 0, 0, 0, 0);
    }

    /** Per-frame parameters of {@code ffxDispatchDescUpscale} (ffx_upscale.h l.81-106). */
    public record DispatchParams(long vkCommandBuffer, Image color, Image depth, Image motionVectors, Image output,
                                 float jitterX, float jitterY, float mvScaleX, float mvScaleY,
                                 int renderWidth, int renderHeight, int upscaleWidth, int upscaleHeight,
                                 boolean enableSharpening, float sharpness, float frameTimeDeltaMs, float preExposure,
                                 boolean reset, float cameraNear, float cameraFar, float cameraFovY, float viewSpaceToMeters) {}

    private FfxNative(Path libraryPath) {
        FfxLayouts.verify();
        this.libraryPath = libraryPath;
        SymbolLookup lookup;
        try {
            lookup = SymbolLookup.libraryLookup(libraryPath, arena);
        } catch (IllegalArgumentException | UnsatisfiedLinkError e) {
            arena.close();
            throw new FsrException("load", "cannot load " + libraryPath + ": " + e, e);
        }
        Linker linker = Linker.nativeLinker();
        try {
            // ffxReturnCode_t ffxCreateContext(ffxContext*, ffxCreateContextDescHeader*, const ffxAllocationCallbacks*)
            createContext = bind(linker, lookup, "ffxCreateContext", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
            // ffxReturnCode_t ffxDestroyContext(ffxContext*, const ffxAllocationCallbacks*)
            destroyContext = bind(linker, lookup, "ffxDestroyContext", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
            // ffxReturnCode_t ffxQuery(ffxContext*, ffxQueryDescHeader*)
            query = bind(linker, lookup, "ffxQuery", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
            // ffxReturnCode_t ffxDispatch(ffxContext*, const ffxDispatchDescHeader*)
            dispatch = bind(linker, lookup, "ffxDispatch", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
            // ffxConfigure exists too (not used); its presence is part of the export check of the official DLL.
            lookup.find("ffxConfigure").orElseThrow(() -> new FsrException("load", libraryPath.getFileName() + " has no export 'ffxConfigure'", null));
        } catch (RuntimeException e) {
            arena.close();
            throw e;
        }
    }

    /** Loads the DLL from an explicit file. @throws FsrException (stage {@code load}) on any problem. */
    public static FfxNative load(Path dll) {
        if (!Files.isRegularFile(dll)) {
            throw new FsrException("load", "not found: " + dll, null);
        }
        String reject = io.github.andrexarlay.vulkancompat.rtx.NativeDllGate.reject(dll);
        if (reject != null) {
            throw new FsrException("load", dll + " " + reject, null);
        }
        FfxNative n = new FfxNative(dll.toAbsolutePath().normalize());
        LOGGER.info("[VKB-FSR] loaded {}", n.libraryPath);
        return n;
    }

    private static MethodHandle bind(Linker linker, SymbolLookup lookup, String name, FunctionDescriptor fd) {
        MemorySegment symbol = lookup.find(name)
            .orElseThrow(() -> new FsrException("load", "the DLL has no export '" + name + "' (not an ffx-api DLL)", null));
        return linker.downcallHandle(symbol, fd);
    }

    public Path libraryPath() {
        return libraryPath;
    }

    // ------------------------------------------------------------------ queries without a context

    /**
     * {@code ffxQueryDescGetVersions} for the upscaler effect (ffx_api.cpp: with a NULL context the call enumerates the
     * internal providers; {@code device} is the DX12 device only, NULL here). Two calls: count, then ids and names.
     */
    public QueryResult<List<ProviderVersion>> upscalerVersions() {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment count = a.allocate(JAVA_LONG);
            MemorySegment d = a.allocate(FfxLayouts.QUERY_GET_VERSIONS);
            d.set(JAVA_LONG, 0, DESC_QUERY_GET_VERSIONS);
            d.set(JAVA_LONG, FfxLayouts.offset(FfxLayouts.QUERY_GET_VERSIONS, "createDescType"), DESC_CREATE_UPSCALE);
            d.set(ADDRESS, FfxLayouts.offset(FfxLayouts.QUERY_GET_VERSIONS, "outputCount"), count);
            int r = invokeInt(query, MemorySegment.NULL, d);
            long n = count.get(JAVA_LONG, 0);
            if (r != RETURN_OK || n <= 0) {
                return new QueryResult<>(r, List.of());
            }
            if (n > 16) {
                throw new FsrException("versions", "ffxQueryDescGetVersions reported " + n + " providers (more than 16 is not plausible)", null);
            }
            MemorySegment ids = a.allocate(JAVA_LONG, n);
            MemorySegment names = a.allocate(ADDRESS, n);
            d.set(ADDRESS, FfxLayouts.offset(FfxLayouts.QUERY_GET_VERSIONS, "versionIds"), ids);
            d.set(ADDRESS, FfxLayouts.offset(FfxLayouts.QUERY_GET_VERSIONS, "versionNames"), names);
            r = invokeInt(query, MemorySegment.NULL, d);
            long got = count.get(JAVA_LONG, 0);
            List<ProviderVersion> out = new ArrayList<>();
            for (long i = 0; i < Math.min(got, n); i++) {
                MemorySegment name = names.getAtIndex(ADDRESS, i);
                out.add(new ProviderVersion(ids.getAtIndex(JAVA_LONG, i),
                    name.address() == 0L ? "?" : name.reinterpret(256).getString(0)));
            }
            return new QueryResult<>(r, List.copyOf(out));
        }
    }

    /** {@code ffxQueryDescUpscaleGetRenderResolutionFromQualityMode} answered by the provider {@code versionId} (0 = default). */
    public QueryResult<Size> renderResolution(int displayW, int displayH, int qualityMode, long versionId) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment outW = a.allocate(JAVA_INT);
            MemorySegment outH = a.allocate(JAVA_INT);
            MemorySegment d = a.allocate(FfxLayouts.QUERY_RENDER_RESOLUTION);
            d.set(JAVA_LONG, 0, DESC_QUERY_RENDER_RESOLUTION);
            chainOverride(a, d, versionId);
            d.set(JAVA_INT, FfxLayouts.offset(FfxLayouts.QUERY_RENDER_RESOLUTION, "displayWidth"), displayW);
            d.set(JAVA_INT, FfxLayouts.offset(FfxLayouts.QUERY_RENDER_RESOLUTION, "displayHeight"), displayH);
            d.set(JAVA_INT, FfxLayouts.offset(FfxLayouts.QUERY_RENDER_RESOLUTION, "qualityMode"), qualityMode);
            d.set(ADDRESS, FfxLayouts.offset(FfxLayouts.QUERY_RENDER_RESOLUTION, "pOutRenderWidth"), outW);
            d.set(ADDRESS, FfxLayouts.offset(FfxLayouts.QUERY_RENDER_RESOLUTION, "pOutRenderHeight"), outH);
            int r = invokeInt(query, MemorySegment.NULL, d);
            return new QueryResult<>(r, new Size(outW.get(JAVA_INT, 0), outH.get(JAVA_INT, 0)));
        }
    }

    /** {@code ffxQueryDescUpscaleGetUpscaleRatioFromQualityMode}. */
    public QueryResult<Float> upscaleRatio(int qualityMode, long versionId) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment out = a.allocate(JAVA_FLOAT);
            MemorySegment d = a.allocate(FfxLayouts.QUERY_UPSCALE_RATIO);
            d.set(JAVA_LONG, 0, DESC_QUERY_UPSCALE_RATIO);
            chainOverride(a, d, versionId);
            d.set(JAVA_INT, FfxLayouts.offset(FfxLayouts.QUERY_UPSCALE_RATIO, "qualityMode"), qualityMode);
            d.set(ADDRESS, FfxLayouts.offset(FfxLayouts.QUERY_UPSCALE_RATIO, "pOutUpscaleRatio"), out);
            int r = invokeInt(query, MemorySegment.NULL, d);
            return new QueryResult<>(r, out.get(JAVA_FLOAT, 0));
        }
    }

    /** {@code ffxQueryDescUpscaleGetJitterPhaseCount}. */
    public QueryResult<Integer> jitterPhaseCount(int renderWidth, int displayWidth, long versionId) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment out = a.allocate(JAVA_INT);
            MemorySegment d = a.allocate(FfxLayouts.QUERY_JITTER_PHASE_COUNT);
            d.set(JAVA_LONG, 0, DESC_QUERY_JITTER_PHASE_COUNT);
            chainOverride(a, d, versionId);
            d.set(JAVA_INT, FfxLayouts.offset(FfxLayouts.QUERY_JITTER_PHASE_COUNT, "renderWidth"), renderWidth);
            d.set(JAVA_INT, FfxLayouts.offset(FfxLayouts.QUERY_JITTER_PHASE_COUNT, "displayWidth"), displayWidth);
            d.set(ADDRESS, FfxLayouts.offset(FfxLayouts.QUERY_JITTER_PHASE_COUNT, "pOutPhaseCount"), out);
            int r = invokeInt(query, MemorySegment.NULL, d);
            return new QueryResult<>(r, out.get(JAVA_INT, 0));
        }
    }

    /** Puts an {@code ffxOverrideVersion} (allocated in {@code a}) into {@code desc.pNext} when {@code versionId != 0}. */
    private static void chainOverride(Arena a, MemorySegment desc, long versionId) {
        if (versionId == 0L) {
            return;
        }
        MemorySegment o = a.allocate(FfxLayouts.OVERRIDE_VERSION);
        o.set(JAVA_LONG, 0, DESC_OVERRIDE_VERSION);
        o.set(JAVA_LONG, 16, versionId);
        desc.set(ADDRESS, 8, o);
    }

    // ------------------------------------------------------------------ context

    /**
     * {@code ffxCreateContext} with the chain {@code ffxCreateContextDescUpscale -> ffxCreateBackendVKDesc -> [ffxOverrideVersion]}.
     * The descriptors only need to live during the call except pointers the API keeps (ffx_api.h l.128: "Pointers passed in
     * desc must remain live until ffxDestroyContext"): the message callback stub lives in this object's arena.
     */
    public ContextResult createUpscaleContext(int flags, int maxRenderW, int maxRenderH, int maxUpscaleW, int maxUpscaleH,
                                              long vkDevice, long vkPhysicalDevice, long vkGetDeviceProcAddr, long versionId) {
        MemorySegment holder = arena.allocate(JAVA_LONG);
        holder.set(JAVA_LONG, 0, 0L);
        try (Arena a = Arena.ofConfined()) {
            MemorySegment backend = a.allocate(FfxLayouts.CREATE_BACKEND_VK);
            backend.set(JAVA_LONG, 0, DESC_CREATE_BACKEND_VK);
            backend.set(JAVA_LONG, FfxLayouts.offset(FfxLayouts.CREATE_BACKEND_VK, "vkDevice"), vkDevice);
            backend.set(JAVA_LONG, FfxLayouts.offset(FfxLayouts.CREATE_BACKEND_VK, "vkPhysicalDevice"), vkPhysicalDevice);
            backend.set(ADDRESS, FfxLayouts.offset(FfxLayouts.CREATE_BACKEND_VK, "vkDeviceProcAddr"), MemorySegment.ofAddress(vkGetDeviceProcAddr));
            chainOverride(a, backend, versionId);

            MemorySegment up = a.allocate(FfxLayouts.CREATE_UPSCALE);
            up.set(JAVA_LONG, 0, DESC_CREATE_UPSCALE);
            up.set(ADDRESS, 8, backend);
            up.set(JAVA_INT, FfxLayouts.offset(FfxLayouts.CREATE_UPSCALE, "flags"), flags);
            long mr = FfxLayouts.offset(FfxLayouts.CREATE_UPSCALE, "maxRenderSize");
            up.set(JAVA_INT, mr, maxRenderW);
            up.set(JAVA_INT, mr + 4, maxRenderH);
            long mu = FfxLayouts.offset(FfxLayouts.CREATE_UPSCALE, "maxUpscaleSize");
            up.set(JAVA_INT, mu, maxUpscaleW);
            up.set(JAVA_INT, mu + 4, maxUpscaleH);
            up.set(ADDRESS, FfxLayouts.offset(FfxLayouts.CREATE_UPSCALE, "fpMessage"), messageStub());
            int r = invokeInt(createContext, holder, up, MemorySegment.NULL);
            return new ContextResult(r, new Context(holder));
        }
    }

    /** {@code ffxQueryGetProviderVersion} on a live context: which provider really serves it. */
    public QueryResult<ProviderVersion> providerVersion(Context ctx) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment d = a.allocate(FfxLayouts.QUERY_PROVIDER_VERSION);
            d.set(JAVA_LONG, 0, DESC_QUERY_GET_PROVIDER_VERSION);
            int r = invokeInt(query, ctx.holder(), d);
            MemorySegment name = d.get(ADDRESS, 24);
            return new QueryResult<>(r, new ProviderVersion(d.get(JAVA_LONG, 16),
                name.address() == 0L ? "?" : name.reinterpret(256).getString(0)));
        }
    }

    public int destroyContext(Context ctx) {
        return invokeInt(destroyContext, ctx.holder(), MemorySegment.NULL);
    }

    /** {@code ffxDispatch} with an {@code ffxDispatchDescUpscale}: records the FSR passes into the command buffer. */
    public int dispatchUpscale(Context ctx, DispatchParams p) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment d = a.allocate(FfxLayouts.DISPATCH_UPSCALE); // zero-initialised: unused resources are NULL
            d.set(JAVA_LONG, 0, DESC_DISPATCH_UPSCALE);
            d.set(JAVA_LONG, FfxLayouts.offset(FfxLayouts.DISPATCH_UPSCALE, "commandList"), p.vkCommandBuffer());
            writeResource(d, "color", p.color());
            writeResource(d, "depth", p.depth());
            writeResource(d, "motionVectors", p.motionVectors());
            writeResource(d, "output", p.output());
            long j = FfxLayouts.offset(FfxLayouts.DISPATCH_UPSCALE, "jitterOffset");
            d.set(JAVA_FLOAT, j, p.jitterX());
            d.set(JAVA_FLOAT, j + 4, p.jitterY());
            long mv = FfxLayouts.offset(FfxLayouts.DISPATCH_UPSCALE, "motionVectorScale");
            d.set(JAVA_FLOAT, mv, p.mvScaleX());
            d.set(JAVA_FLOAT, mv + 4, p.mvScaleY());
            long rs = FfxLayouts.offset(FfxLayouts.DISPATCH_UPSCALE, "renderSize");
            d.set(JAVA_INT, rs, p.renderWidth());
            d.set(JAVA_INT, rs + 4, p.renderHeight());
            long us = FfxLayouts.offset(FfxLayouts.DISPATCH_UPSCALE, "upscaleSize");
            d.set(JAVA_INT, us, p.upscaleWidth());
            d.set(JAVA_INT, us + 4, p.upscaleHeight());
            d.set(JAVA_BYTE, FfxLayouts.offset(FfxLayouts.DISPATCH_UPSCALE, "enableSharpening"), (byte) (p.enableSharpening() ? 1 : 0));
            d.set(JAVA_FLOAT, FfxLayouts.offset(FfxLayouts.DISPATCH_UPSCALE, "sharpness"), p.sharpness());
            d.set(JAVA_FLOAT, FfxLayouts.offset(FfxLayouts.DISPATCH_UPSCALE, "frameTimeDelta"), p.frameTimeDeltaMs());
            d.set(JAVA_FLOAT, FfxLayouts.offset(FfxLayouts.DISPATCH_UPSCALE, "preExposure"), p.preExposure());
            d.set(JAVA_BYTE, FfxLayouts.offset(FfxLayouts.DISPATCH_UPSCALE, "reset"), (byte) (p.reset() ? 1 : 0));
            d.set(JAVA_FLOAT, FfxLayouts.offset(FfxLayouts.DISPATCH_UPSCALE, "cameraNear"), p.cameraNear());
            d.set(JAVA_FLOAT, FfxLayouts.offset(FfxLayouts.DISPATCH_UPSCALE, "cameraFar"), p.cameraFar());
            d.set(JAVA_FLOAT, FfxLayouts.offset(FfxLayouts.DISPATCH_UPSCALE, "cameraFovAngleVertical"), p.cameraFovY());
            d.set(JAVA_FLOAT, FfxLayouts.offset(FfxLayouts.DISPATCH_UPSCALE, "viewSpaceToMetersFactor"), p.viewSpaceToMeters());
            d.set(JAVA_INT, FfxLayouts.offset(FfxLayouts.DISPATCH_UPSCALE, "flags"), 0);
            return invokeInt(dispatch, ctx.holder(), d);
        }
    }

    /** FfxApiResource as {@code ffxApiGetResourceVK(image, ffxApiGetImageResourceDescriptionVK(...), state)} builds it. */
    private static void writeResource(MemorySegment desc, String field, Image img) {
        long o = FfxLayouts.offset(FfxLayouts.DISPATCH_UPSCALE, field);
        if (img.vkImage() == 0L) {
            return; // NULL resource: the backend maps it to the effect's NULL resource (RegisterResourceVK)
        }
        desc.set(JAVA_LONG, o, img.vkImage());
        long dsc = o + 8;
        desc.set(JAVA_INT, dsc, RESOURCE_TYPE_TEXTURE2D);
        desc.set(JAVA_INT, dsc + 4, img.ffxFormat());
        desc.set(JAVA_INT, dsc + 8, img.width());
        desc.set(JAVA_INT, dsc + 12, img.height());
        desc.set(JAVA_INT, dsc + 16, 1);  // depth = arrayLayers (1)
        desc.set(JAVA_INT, dsc + 20, 1);  // mipCount: used as the view levelCount by the VK backend, must not be 0
        desc.set(JAVA_INT, dsc + 24, 0);  // flags NONE
        desc.set(JAVA_INT, dsc + 28, img.usage());
        desc.set(JAVA_INT, o + 40, img.state());
    }

    // ------------------------------------------------------------------ message callback

    /** {@code void (*ffxApiMessage)(uint32_t type, const wchar_t* message)} (ffx_api.h l.75); wchar_t is UTF-16 on Windows. */
    private synchronized MemorySegment messageStub() {
        if (messageStub == null) {
            try {
                MethodHandle target = MethodHandles.lookup().findStatic(FfxNative.class, "onMessage",
                    MethodType.methodType(void.class, int.class, MemorySegment.class));
                messageStub = Linker.nativeLinker().upcallStub(target, FunctionDescriptor.ofVoid(JAVA_INT, ADDRESS), arena);
            } catch (NoSuchMethodException | IllegalAccessException e) {
                throw new FsrException("create_context", "cannot create the FidelityFX message upcall", e);
            }
        }
        return messageStub;
    }

    /** Upcall target (any thread). An exception escaping an upcall aborts the JVM, so failures are reported, not thrown. */
    @SuppressWarnings("unused")
    private static void onMessage(int type, MemorySegment message) {
        try {
            String text = message.address() == 0L ? "(null)"
                : message.reinterpret(8192).getString(0, StandardCharsets.UTF_16LE);
            if (type == 0) {
                FFX_LOGGER.error("[VKB-FSR] ffx message: {}", text);
            } else {
                FFX_LOGGER.warn("[VKB-FSR] ffx message type={}: {}", type, text);
            }
        } catch (Throwable t) {
            FFX_LOGGER.error("[VKB-FSR] failed to read a FidelityFX message", t);
        }
    }

    private static int invokeInt(MethodHandle h, Object... args) {
        try {
            return (int) h.invokeWithArguments(args);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable t) {
            throw new FsrException("downcall", "FidelityFX downcall failed", t);
        }
    }
}
