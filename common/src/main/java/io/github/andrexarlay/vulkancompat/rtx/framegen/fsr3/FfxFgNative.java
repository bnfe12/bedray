package io.github.andrexarlay.vulkancompat.rtx.framegen.fsr3;

import io.github.andrexarlay.vulkancompat.rtx.upscaler.fsr.FfxLayouts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.file.Files;
import java.nio.file.Path;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * FFM binding of the frame generation part of ffx-api in {@code amd_fidelityfx_vk.dll} (FidelityFX SDK v1.1.4, the same DLL the FSR
 * upscaler uses). Every struct below is written from {@code ffx_framegeneration.h} and {@code vk/ffx_api_vk.h} (field order, types);
 * offsets are checked by {@link #verifyLayouts()}. Contract and call order: docs/framegen/FSR3_FINDINGS.md.
 * <p>
 * Threading: all calls on the render thread, except the frame generation callback, which the swapchain calls from vkQueuePresentKHR
 * (also the render thread) and which only forwards to ffxDispatch.
 */
public final class FfxFgNative {
    private static final Logger LOGGER = LoggerFactory.getLogger("Bedray/FfxFg");

    // descriptor types
    static final long CREATE_BACKEND_VK = 0x3L;
    static final long CREATE_FGSC_VK = 0x40001L;
    static final long CONFIGURE_FGSC_UI_VK = 0x40002L;
    static final long QUERY_FGSC_FUNCTIONS_VK = 0x40005L;
    static final long DISPATCH_FGSC_WAIT_VK = 0x40007L;
    static final long CREATE_FG = 0x20001L;
    static final long CONFIGURE_FG = 0x20002L;
    static final long DISPATCH_FG_PREPARE = 0x20004L;
    static final long CREATE_FG_HUDLESS = 0x20009L;
    static final long DISPATCH_FG_PREPARE_CAMERA = 0x2000aL;

    // FfxApiCreateContextFramegenerationFlags
    public static final int FG_DEPTH_INVERTED = 1 << 3;
    public static final int FG_DEPTH_INFINITE = 1 << 4;
    public static final int FG_HDR = 1 << 5;
    public static final int FG_DEBUG_CHECKING = 1 << 6;
    // FfxApiSurfaceFormat (enum order of ffx_api_types.h)
    public static final int FORMAT_R8G8B8A8_UNORM = 10;
    public static final int FORMAT_B8G8R8A8_UNORM = 14;
    public static final int FORMAT_R16G16_FLOAT = 18;
    public static final int FORMAT_R32_FLOAT = 28;
    public static final int USAGE_READ_ONLY = 0;
    public static final int USAGE_DEPTHTARGET = 1 << 2;
    public static final int STATE_COMMON = 1;

    private static final StructLayout QUEUE_INFO = MemoryLayout.structLayout(
        ADDRESS.withName("queue"), JAVA_INT.withName("familyIndex"), MemoryLayout.paddingLayout(4), ADDRESS.withName("submitFunc"));
    /** sizeof(VkSwapchainCreateInfoKHR) on x64 = 104 (checked against LWJGL by the runtime). */
    public static final int SWAPCHAIN_CREATE_INFO_SIZE = 104;
    private static final StructLayout CREATE_FGSC = MemoryLayout.structLayout(
        FfxLayouts.HEADER.withName("header"), ADDRESS.withName("physicalDevice"), ADDRESS.withName("device"),
        ADDRESS.withName("swapchain"), ADDRESS.withName("allocator"),
        MemoryLayout.sequenceLayout(SWAPCHAIN_CREATE_INFO_SIZE, JAVA_BYTE).withName("createInfo"),
        QUEUE_INFO.withName("gameQueue"), QUEUE_INFO.withName("asyncComputeQueue"), QUEUE_INFO.withName("presentQueue"),
        QUEUE_INFO.withName("imageAcquireQueue"));
    private static final StructLayout QUERY_REPLACEMENT = MemoryLayout.structLayout(
        FfxLayouts.HEADER.withName("header"), ADDRESS.withName("createSwapchain"), ADDRESS.withName("destroySwapchain"),
        ADDRESS.withName("getSwapchainImages"), ADDRESS.withName("acquireNextImage"), ADDRESS.withName("queuePresent"),
        ADDRESS.withName("setHdrMetadata"), ADDRESS.withName("getLastPresentCount"));
    private static final StructLayout REGISTER_UI = MemoryLayout.structLayout(
        FfxLayouts.HEADER.withName("header"), FfxLayouts.RESOURCE.withName("uiResource"), JAVA_INT.withName("flags"),
        MemoryLayout.paddingLayout(4));
    private static final StructLayout CREATE_FG_DESC = MemoryLayout.structLayout(
        FfxLayouts.HEADER.withName("header"), JAVA_INT.withName("flags"), FfxLayouts.DIMENSIONS_2D.withName("displaySize"),
        FfxLayouts.DIMENSIONS_2D.withName("maxRenderSize"), JAVA_INT.withName("backBufferFormat"));
    private static final StructLayout CREATE_HUDLESS = MemoryLayout.structLayout(
        FfxLayouts.HEADER.withName("header"), JAVA_INT.withName("hudlessBackBufferFormat"), MemoryLayout.paddingLayout(4));
    private static final StructLayout CONFIGURE_FG_DESC = MemoryLayout.structLayout(
        FfxLayouts.HEADER.withName("header"), ADDRESS.withName("swapChain"), ADDRESS.withName("presentCallback"),
        ADDRESS.withName("presentCallbackUserContext"), ADDRESS.withName("frameGenerationCallback"),
        ADDRESS.withName("frameGenerationCallbackUserContext"), JAVA_BYTE.withName("frameGenerationEnabled"),
        JAVA_BYTE.withName("allowAsyncWorkloads"), MemoryLayout.paddingLayout(6), FfxLayouts.RESOURCE.withName("HUDLessColor"),
        JAVA_INT.withName("flags"), JAVA_BYTE.withName("onlyPresentGenerated"), MemoryLayout.paddingLayout(3),
        MemoryLayout.sequenceLayout(4, JAVA_INT).withName("generationRect"), JAVA_LONG.withName("frameID"));
    private static final StructLayout PREPARE = MemoryLayout.structLayout(
        FfxLayouts.HEADER.withName("header"), JAVA_LONG.withName("frameID"), JAVA_INT.withName("flags"), MemoryLayout.paddingLayout(4),
        ADDRESS.withName("commandList"), FfxLayouts.DIMENSIONS_2D.withName("renderSize"),
        FfxLayouts.FLOAT_COORDS_2D.withName("jitterOffset"), FfxLayouts.FLOAT_COORDS_2D.withName("motionVectorScale"),
        JAVA_FLOAT.withName("frameTimeDelta"), JAVA_BYTE.withName("unused_reset"), MemoryLayout.paddingLayout(3),
        JAVA_FLOAT.withName("cameraNear"), JAVA_FLOAT.withName("cameraFar"), JAVA_FLOAT.withName("cameraFovAngleVertical"),
        JAVA_FLOAT.withName("viewSpaceToMetersFactor"), FfxLayouts.RESOURCE.withName("depth"),
        FfxLayouts.RESOURCE.withName("motionVectors"));
    private static final StructLayout CAMERA_INFO = MemoryLayout.structLayout(
        FfxLayouts.HEADER.withName("header"), MemoryLayout.sequenceLayout(12, JAVA_FLOAT).withName("vectors"));

    /** Checks every hand-written layout against the C rules; throws on a mismatch before any native call. */
    public static void verifyLayouts() {
        expect(CREATE_FGSC.byteSize(), 248, "sizeof(ffxCreateContextDescFrameGenerationSwapChainVK)");
        expect(FfxLayouts.offset(CREATE_FGSC, "createInfo"), 48, "FGSC.createInfo");
        expect(FfxLayouts.offset(CREATE_FGSC, "gameQueue"), 152, "FGSC.gameQueue");
        expect(FfxLayouts.offset(CREATE_FGSC, "imageAcquireQueue"), 224, "FGSC.imageAcquireQueue");
        expect(QUERY_REPLACEMENT.byteSize(), 72, "sizeof(ffxQueryDescSwapchainReplacementFunctionsVK)");
        expect(REGISTER_UI.byteSize(), 72, "sizeof(RegisterUiResourceVK)");
        expect(CREATE_FG_DESC.byteSize(), 40, "sizeof(ffxCreateContextDescFrameGeneration)");
        expect(FfxLayouts.offset(CREATE_FG_DESC, "backBufferFormat"), 36, "CreateFG.backBufferFormat");
        expect(CREATE_HUDLESS.byteSize(), 24, "sizeof(Hudless)");
        expect(CONFIGURE_FG_DESC.byteSize(), 144, "sizeof(ffxConfigureDescFrameGeneration)");
        expect(FfxLayouts.offset(CONFIGURE_FG_DESC, "frameGenerationEnabled"), 56, "ConfigureFG.frameGenerationEnabled");
        expect(FfxLayouts.offset(CONFIGURE_FG_DESC, "HUDLessColor"), 64, "ConfigureFG.HUDLessColor");
        expect(FfxLayouts.offset(CONFIGURE_FG_DESC, "flags"), 112, "ConfigureFG.flags");
        expect(FfxLayouts.offset(CONFIGURE_FG_DESC, "generationRect"), 120, "ConfigureFG.generationRect");
        expect(FfxLayouts.offset(CONFIGURE_FG_DESC, "frameID"), 136, "ConfigureFG.frameID");
        expect(PREPARE.byteSize(), 184, "sizeof(ffxDispatchDescFrameGenerationPrepare)");
        expect(FfxLayouts.offset(PREPARE, "commandList"), 32, "Prepare.commandList");
        expect(FfxLayouts.offset(PREPARE, "frameTimeDelta"), 64, "Prepare.frameTimeDelta");
        expect(FfxLayouts.offset(PREPARE, "cameraNear"), 72, "Prepare.cameraNear");
        expect(FfxLayouts.offset(PREPARE, "depth"), 88, "Prepare.depth");
        expect(FfxLayouts.offset(PREPARE, "motionVectors"), 136, "Prepare.motionVectors");
        expect(CAMERA_INFO.byteSize(), 64, "sizeof(PrepareCameraInfo)");
    }

    private static void expect(long actual, long expected, String what) {
        if (actual != expected) {
            throw new IllegalStateException("ffx-api framegen layout mismatch: " + what + " is " + actual + ", C layout says " + expected);
        }
    }

    /** One image handed to ffx (raw VkImage plus the description the VK backend builds views from). */
    public record Image(long vkImage, int ffxFormat, int width, int height, int usage, int state) {
        public static final Image NONE = new Image(0L, 0, 0, 0, 0, 0);
    }

    /** Replacement entry points of the frame interpolation swapchain (addresses of native functions). */
    public record Replacement(long createSwapchain, long destroySwapchain, long getSwapchainImages, long acquireNextImage,
                              long queuePresent, long getLastPresentCount) {}

    private final Arena arena = Arena.ofShared();
    private final Path libraryPath;
    private final MethodHandle createContext;
    private final MethodHandle destroyContext;
    private final MethodHandle configure;
    private final MethodHandle query;
    private final MethodHandle dispatch;
    private final MemorySegment dispatchCallbackStub;
    private MethodHandle hGetImages, hAcquire, hPresent, hCreate, hDestroy, hCount;
    private long boundGetImages, boundAcquire, boundPresent, boundCreate, boundDestroy, boundCount;

    private FfxFgNative(Path dll) {
        verifyLayouts();
        this.libraryPath = dll;
        SymbolLookup lookup;
        try {
            lookup = SymbolLookup.libraryLookup(dll, arena);
        } catch (IllegalArgumentException | UnsatisfiedLinkError e) {
            arena.close();
            throw new IllegalStateException("cannot load " + dll + ": " + e, e);
        }
        Linker linker = Linker.nativeLinker();
        createContext = bind(linker, lookup, "ffxCreateContext", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
        destroyContext = bind(linker, lookup, "ffxDestroyContext", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
        configure = bind(linker, lookup, "ffxConfigure", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
        query = bind(linker, lookup, "ffxQuery", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
        dispatch = bind(linker, lookup, "ffxDispatch", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
        try {
            MethodHandle target = MethodHandles.lookup().findVirtual(FfxFgNative.class, "onFrameGenerationCallback",
                MethodType.methodType(int.class, MemorySegment.class, MemorySegment.class)).bindTo(this);
            dispatchCallbackStub = linker.upcallStub(target, FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS), arena);
        } catch (NoSuchMethodException | IllegalAccessException e) {
            arena.close();
            throw new IllegalStateException("cannot create the frame generation callback", e);
        }
    }

    public static FfxFgNative load(Path dll) {
        if (!Files.isRegularFile(dll)) {
            throw new IllegalStateException("not found: " + dll);
        }
        // the developer override property reaches this loader straight from the command line, with no natives manager in between
        String reject = io.github.andrexarlay.vulkancompat.rtx.NativeDllGate.reject(dll);
        if (reject != null) {
            throw new IllegalStateException(dll + " " + reject);
        }
        FfxFgNative n = new FfxFgNative(dll.toAbsolutePath().normalize());
        LOGGER.info("[VKB-FG] fsr3 loaded {}", n.libraryPath);
        return n;
    }

    private static MethodHandle bind(Linker linker, SymbolLookup lookup, String name, FunctionDescriptor fd) {
        MemorySegment symbol = lookup.find(name).orElseThrow(() -> new IllegalStateException("the DLL has no export '" + name + "'"));
        return linker.downcallHandle(symbol, fd);
    }

    public Path libraryPath() {
        return libraryPath;
    }

    // ------------------------------------------------------------------------------------------------ swapchain context

    /** A created ffx context: {@code holder} is the 8 byte {@code ffxContext} variable. */
    public record Context(MemorySegment holder) {
        public long handle() {
            return holder.get(JAVA_LONG, 0);
        }
    }

    public record Created(int result, Context context, long swapchain) {}

    /**
     * {@code ffxCreateContext(ffxCreateContextDescFrameGenerationSwapChainVK)}: destroys {@code oldSwapchain} (may be 0), creates the
     * frame interpolation swapchain from {@code createInfoAddress} (a VkSwapchainCreateInfoKHR, copied) and returns its handle.
     * The async compute queue is passed as NULL (optional, FSR3_FINDINGS.md).
     */
    public Created createSwapchainContext(long physicalDevice, long device, long oldSwapchain, long createInfoAddress,
                                          long gameQueue, int gameFamily, long presentQueue, int presentFamily,
                                          long acquireQueue, int acquireFamily) {
        MemorySegment holder = arena.allocate(JAVA_LONG);
        holder.set(JAVA_LONG, 0, 0L);
        try (Arena a = Arena.ofConfined()) {
            MemorySegment sc = a.allocate(JAVA_LONG);
            sc.set(JAVA_LONG, 0, oldSwapchain);
            MemorySegment d = a.allocate(CREATE_FGSC);
            d.set(JAVA_LONG, 0, CREATE_FGSC_VK);
            d.set(ADDRESS, FfxLayouts.offset(CREATE_FGSC, "physicalDevice"), MemorySegment.ofAddress(physicalDevice));
            d.set(ADDRESS, FfxLayouts.offset(CREATE_FGSC, "device"), MemorySegment.ofAddress(device));
            d.set(ADDRESS, FfxLayouts.offset(CREATE_FGSC, "swapchain"), sc);
            MemorySegment.copy(MemorySegment.ofAddress(createInfoAddress).reinterpret(SWAPCHAIN_CREATE_INFO_SIZE), 0, d,
                FfxLayouts.offset(CREATE_FGSC, "createInfo"), SWAPCHAIN_CREATE_INFO_SIZE);
            writeQueue(d, "gameQueue", gameQueue, gameFamily);
            writeQueue(d, "asyncComputeQueue", 0L, 0);
            writeQueue(d, "presentQueue", presentQueue, presentFamily);
            writeQueue(d, "imageAcquireQueue", acquireQueue, acquireFamily);
            int r = invokeInt(createContext, holder, d, MemorySegment.NULL);
            return new Created(r, new Context(holder), sc.get(JAVA_LONG, 0));
        }
    }

    private static void writeQueue(MemorySegment d, String field, long queue, int family) {
        long o = FfxLayouts.offset(CREATE_FGSC, field);
        d.set(ADDRESS, o, MemorySegment.ofAddress(queue));
        d.set(JAVA_INT, o + 8, family);
    }

    public Replacement queryReplacement(Context sc) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment d = a.allocate(QUERY_REPLACEMENT);
            d.set(JAVA_LONG, 0, QUERY_FGSC_FUNCTIONS_VK);
            int r = invokeInt(query, sc.holder(), d);
            if (r != 0) {
                throw new IllegalStateException("ffxQuery(replacement functions) returned " + r);
            }
            return new Replacement(addr(d, "createSwapchain"), addr(d, "destroySwapchain"), addr(d, "getSwapchainImages"),
                addr(d, "acquireNextImage"), addr(d, "queuePresent"), addr(d, "getLastPresentCount"));
        }
    }

    private static long addr(MemorySegment d, String f) {
        return d.get(ADDRESS, FfxLayouts.offset(QUERY_REPLACEMENT, f)).address();
    }

    public int waitForPresents(Context sc) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment d = a.allocate(FfxLayouts.HEADER);
            d.set(JAVA_LONG, 0, DISPATCH_FGSC_WAIT_VK);
            return invokeInt(dispatch, sc.holder(), d);
        }
    }

    /** Registers the UI resource of the swapchain; {@link Image#NONE} clears it (hudless mode composes no separate UI). */
    public int registerUi(Context sc, Image ui) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment d = a.allocate(REGISTER_UI);
            d.set(JAVA_LONG, 0, CONFIGURE_FGSC_UI_VK);
            writeResource(d, FfxLayouts.offset(REGISTER_UI, "uiResource"), ui);
            return invokeInt(configure, sc.holder(), d);
        }
    }

    public int destroy(Context ctx) {
        return invokeInt(destroyContext, ctx.holder(), MemorySegment.NULL);
    }

    // ------------------------------------------------------------------------------------------------ FG context

    public record FgCreated(int result, Context context) {}

    public FgCreated createFgContext(int flags, int displayW, int displayH, int maxRenderW, int maxRenderH, int backBufferFormat,
                                     int hudlessFormat, long vkDevice, long vkPhysicalDevice, long vkGetDeviceProcAddr) {
        MemorySegment holder = arena.allocate(JAVA_LONG);
        holder.set(JAVA_LONG, 0, 0L);
        try (Arena a = Arena.ofConfined()) {
            MemorySegment hudless = a.allocate(CREATE_HUDLESS);
            hudless.set(JAVA_LONG, 0, CREATE_FG_HUDLESS);
            hudless.set(JAVA_INT, FfxLayouts.offset(CREATE_HUDLESS, "hudlessBackBufferFormat"), hudlessFormat);

            MemorySegment backend = a.allocate(FfxLayouts.CREATE_BACKEND_VK);
            backend.set(JAVA_LONG, 0, CREATE_BACKEND_VK);
            backend.set(ADDRESS, 8, hudless);
            backend.set(JAVA_LONG, FfxLayouts.offset(FfxLayouts.CREATE_BACKEND_VK, "vkDevice"), vkDevice);
            backend.set(JAVA_LONG, FfxLayouts.offset(FfxLayouts.CREATE_BACKEND_VK, "vkPhysicalDevice"), vkPhysicalDevice);
            backend.set(ADDRESS, FfxLayouts.offset(FfxLayouts.CREATE_BACKEND_VK, "vkDeviceProcAddr"), MemorySegment.ofAddress(vkGetDeviceProcAddr));

            MemorySegment fg = a.allocate(CREATE_FG_DESC);
            fg.set(JAVA_LONG, 0, CREATE_FG);
            fg.set(ADDRESS, 8, backend);
            fg.set(JAVA_INT, FfxLayouts.offset(CREATE_FG_DESC, "flags"), flags);
            long ds = FfxLayouts.offset(CREATE_FG_DESC, "displaySize");
            fg.set(JAVA_INT, ds, displayW);
            fg.set(JAVA_INT, ds + 4, displayH);
            long mr = FfxLayouts.offset(CREATE_FG_DESC, "maxRenderSize");
            fg.set(JAVA_INT, mr, maxRenderW);
            fg.set(JAVA_INT, mr + 4, maxRenderH);
            fg.set(JAVA_INT, FfxLayouts.offset(CREATE_FG_DESC, "backBufferFormat"), backBufferFormat);
            int r = invokeInt(createContext, holder, fg, MemorySegment.NULL);
            return new FgCreated(r, new Context(holder));
        }
    }

    /**
     * {@code ffxConfigure(ffxConfigureDescFrameGeneration)} for the frame {@code frameId}: callbacks, enable switch, hudless image.
     * {@code swapchain} is the handle returned by the swapchain context.
     */
    public int configureFg(Context fg, long swapchain, boolean enabled, Image hudless, long frameId) {
        if (fg == null || fg.handle() == 0L || swapchain == 0L) {
            return 0;
        }
        try (Arena a = Arena.ofConfined()) {
            MemorySegment d = a.allocate(CONFIGURE_FG_DESC);
            d.set(JAVA_LONG, 0, CONFIGURE_FG);
            d.set(ADDRESS, FfxLayouts.offset(CONFIGURE_FG_DESC, "swapChain"), MemorySegment.ofAddress(swapchain));
            d.set(ADDRESS, FfxLayouts.offset(CONFIGURE_FG_DESC, "frameGenerationCallback"), dispatchCallbackStub);
            // user context = the address of the ffxContext variable (the callback calls ffxDispatch(ctx*, ...))
            d.set(ADDRESS, FfxLayouts.offset(CONFIGURE_FG_DESC, "frameGenerationCallbackUserContext"), fg.holder());
            d.set(JAVA_BYTE, FfxLayouts.offset(CONFIGURE_FG_DESC, "frameGenerationEnabled"), (byte) (enabled ? 1 : 0));
            d.set(JAVA_BYTE, FfxLayouts.offset(CONFIGURE_FG_DESC, "allowAsyncWorkloads"), (byte) 0);
            writeResource(d, FfxLayouts.offset(CONFIGURE_FG_DESC, "HUDLessColor"), hudless);
            d.set(JAVA_LONG, FfxLayouts.offset(CONFIGURE_FG_DESC, "frameID"), frameId);
            return invokeInt(configure, fg.holder(), d);
        }
    }

    public record PrepareParams(long vkCommandBuffer, Image depth, Image motionVectors, int renderWidth, int renderHeight,
                                float jitterX, float jitterY, float mvScaleX, float mvScaleY, float frameTimeMs,
                                float cameraNear, float cameraFar, float fovY, float[] cameraVectors, long frameId) {}

    public int dispatchPrepare(Context fg, PrepareParams p) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment cam = a.allocate(CAMERA_INFO);
            cam.set(JAVA_LONG, 0, DISPATCH_FG_PREPARE_CAMERA);
            for (int i = 0; i < 12; i++) {
                cam.set(JAVA_FLOAT, 16 + 4L * i, p.cameraVectors()[i]);
            }
            MemorySegment d = a.allocate(PREPARE);
            d.set(JAVA_LONG, 0, DISPATCH_FG_PREPARE);
            d.set(ADDRESS, 8, cam);
            d.set(JAVA_LONG, FfxLayouts.offset(PREPARE, "frameID"), p.frameId());
            d.set(ADDRESS, FfxLayouts.offset(PREPARE, "commandList"), MemorySegment.ofAddress(p.vkCommandBuffer()));
            long rs = FfxLayouts.offset(PREPARE, "renderSize");
            d.set(JAVA_INT, rs, p.renderWidth());
            d.set(JAVA_INT, rs + 4, p.renderHeight());
            long j = FfxLayouts.offset(PREPARE, "jitterOffset");
            d.set(JAVA_FLOAT, j, p.jitterX());
            d.set(JAVA_FLOAT, j + 4, p.jitterY());
            long mv = FfxLayouts.offset(PREPARE, "motionVectorScale");
            d.set(JAVA_FLOAT, mv, p.mvScaleX());
            d.set(JAVA_FLOAT, mv + 4, p.mvScaleY());
            d.set(JAVA_FLOAT, FfxLayouts.offset(PREPARE, "frameTimeDelta"), p.frameTimeMs());
            d.set(JAVA_FLOAT, FfxLayouts.offset(PREPARE, "cameraNear"), p.cameraNear());
            d.set(JAVA_FLOAT, FfxLayouts.offset(PREPARE, "cameraFar"), p.cameraFar());
            d.set(JAVA_FLOAT, FfxLayouts.offset(PREPARE, "cameraFovAngleVertical"), p.fovY());
            d.set(JAVA_FLOAT, FfxLayouts.offset(PREPARE, "viewSpaceToMetersFactor"), 0.0f);
            writeResource(d, FfxLayouts.offset(PREPARE, "depth"), p.depth());
            writeResource(d, FfxLayouts.offset(PREPARE, "motionVectors"), p.motionVectors());
            return invokeInt(dispatch, fg.holder(), d);
        }
    }

    private static void writeResource(MemorySegment base, long o, Image img) {
        if (img.vkImage() == 0L) {
            return;
        }
        base.set(JAVA_LONG, o, img.vkImage());
        long dsc = o + 8;
        base.set(JAVA_INT, dsc, 2);            // FFX_API_RESOURCE_TYPE_TEXTURE2D
        base.set(JAVA_INT, dsc + 4, img.ffxFormat());
        base.set(JAVA_INT, dsc + 8, img.width());
        base.set(JAVA_INT, dsc + 12, img.height());
        base.set(JAVA_INT, dsc + 16, 1);
        base.set(JAVA_INT, dsc + 20, 1);       // mipCount (view levelCount, must not be 0)
        base.set(JAVA_INT, dsc + 24, 0);
        base.set(JAVA_INT, dsc + 28, img.usage());
        base.set(JAVA_INT, o + 40, img.state());
    }

    /**
     * Callback the swapchain calls inside vkQueuePresentKHR to record the interpolation: ffxDispatch(userCtx, params). An exception must not
     * leave an upcall (it would abort the JVM), so failures are logged and reported as an error code.
     */
    @SuppressWarnings("unused")
    private int onFrameGenerationCallback(MemorySegment params, MemorySegment userCtx) {
        try {
            return (int) dispatch.invokeExact(userCtx, params);
        } catch (Throwable t) {
            LOGGER.error("[VKB-FG] fsr3 frame generation callback failed", t);
            return 1;
        }
    }

    // ------------------------------------------------------------------------------------------------ replacement entry points

    private static MethodHandle fn(long address, FunctionDescriptor fd) {
        return Linker.nativeLinker().downcallHandle(MemorySegment.ofAddress(address), fd);
    }

    public int getSwapchainImages(long fnAddr, long device, long swapchain, long countPtr, long imagesPtr) {
        if (hGetImages == null || boundGetImages != fnAddr) {
            hGetImages = fn(fnAddr, FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_LONG, ADDRESS, ADDRESS));
            boundGetImages = fnAddr;
        }
        try {
            return (int) hGetImages.invokeExact(device, swapchain, MemorySegment.ofAddress(countPtr), MemorySegment.ofAddress(imagesPtr));
        } catch (Throwable t) {
            throw new IllegalStateException("replacement vkGetSwapchainImagesKHR failed", t);
        }
    }

    public int acquireNextImage(long fnAddr, long device, long swapchain, long timeout, long semaphore, long fence, long indexPtr) {
        if (hAcquire == null || boundAcquire != fnAddr) {
            hAcquire = fn(fnAddr, FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, ADDRESS));
            boundAcquire = fnAddr;
        }
        try {
            return (int) hAcquire.invokeExact(device, swapchain, timeout, semaphore, fence, MemorySegment.ofAddress(indexPtr));
        } catch (Throwable t) {
            throw new IllegalStateException("replacement vkAcquireNextImageKHR failed", t);
        }
    }

    /** getLastPresentCountFFXAPI(VkSwapchainKHR): real presents (rendered + generated) since the swapchain was created. */
    public long lastPresentCount(long fnAddr, long swapchain) {
        if (hCount == null || boundCount != fnAddr) {
            hCount = fn(fnAddr, FunctionDescriptor.of(JAVA_LONG, JAVA_LONG));
            boundCount = fnAddr;
        }
        try {
            return (long) hCount.invokeExact(swapchain);
        } catch (Throwable t) {
            throw new IllegalStateException("getLastPresentCountFFXAPI failed", t);
        }
    }

    public int queuePresent(long fnAddr, long queue, long presentInfoPtr) {
        if (hPresent == null || boundPresent != fnAddr) {
            hPresent = fn(fnAddr, FunctionDescriptor.of(JAVA_INT, JAVA_LONG, ADDRESS));
            boundPresent = fnAddr;
        }
        try {
            return (int) hPresent.invokeExact(queue, MemorySegment.ofAddress(presentInfoPtr));
        } catch (Throwable t) {
            throw new IllegalStateException("replacement vkQueuePresentKHR failed", t);
        }
    }

    /** vkCreateSwapchainFFXAPI(device, createInfo, allocator, out*, context); {@code contextHandle} is the swapchain context handle value. */
    public int createSwapchain(long fnAddr, long device, long createInfoPtr, long outPtr, long contextHandle) {
        if (hCreate == null || boundCreate != fnAddr) {
            hCreate = fn(fnAddr, FunctionDescriptor.of(JAVA_INT, JAVA_LONG, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
            boundCreate = fnAddr;
        }
        try {
            return (int) hCreate.invokeExact(device, MemorySegment.ofAddress(createInfoPtr), MemorySegment.NULL,
                MemorySegment.ofAddress(outPtr), MemorySegment.ofAddress(contextHandle));
        } catch (Throwable t) {
            throw new IllegalStateException("replacement vkCreateSwapchainKHR failed", t);
        }
    }

    public void destroySwapchain(long fnAddr, long device, long swapchain, long contextHandle) {
        if (hDestroy == null || boundDestroy != fnAddr) {
            hDestroy = fn(fnAddr, FunctionDescriptor.ofVoid(JAVA_LONG, JAVA_LONG, ADDRESS, ADDRESS));
            boundDestroy = fnAddr;
        }
        try {
            hDestroy.invokeExact(device, swapchain, MemorySegment.NULL, MemorySegment.ofAddress(contextHandle));
        } catch (Throwable t) {
            throw new IllegalStateException("replacement vkDestroySwapchainKHR failed", t);
        }
    }

    private static int invokeInt(MethodHandle h, Object... args) {
        try {
            return (int) h.invokeWithArguments(args);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException("FidelityFX downcall failed", t);
        }
    }
}
