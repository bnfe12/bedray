package io.github.andrexarlay.vulkancompat.rtx.upscaler.xess;

import com.mojang.renderpearl.api.device.DeviceInfo;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import io.github.andrexarlay.vulkancompat.rtx.raytracing.VulkanCapabilityChecker;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.UpscalerBackend;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.UpscalerReconfigure;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.UpscalerRequest;
import net.minecraft.client.Minecraft;
import org.lwjgl.vulkan.VK10;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.foreign.MemorySegment;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Lifecycle of the Intel XeSS Vulkan context (steps X0-X2: library, device checks, context, init).
 * <p>
 * Request-driven: nothing happens unless XeSS is the requested upscaler ({@link UpscalerRequest}: -Dvkbridge.xess, the toml
 * read before the device exists, or the settings page at runtime). A failure never stops the game:
 * it is logged as {@code [VKB-AUTOTEST] xess=FAILED stage=... result=...} and the runtime stays in {@link State#FAILED}
 * (normal rendering continues; there is no hidden retry: only a changed request leaves FAILED).
 * <p>
 * Runtime switch: when the request no longer matches the active mode the context goes READY -> RECONFIGURE_PENDING (out of
 * use at once, the frame falls back to the other path), is destroyed {@link UpscalerReconfigure#RETIRE_DELAY_FRAMES} frame
 * heads later (no vkDeviceWaitIdle) and a new context is created for the new request; the init epoch increases, which
 * resets the temporal history.
 * <p>
 * All methods must run on the render thread. {@link #onFrameBegin(int, int)} is called from the HEAD of
 * {@code GameRenderer.render()} via {@code WorldTargetManager.resetPhase}, i.e. when no render pass is open.
 */
public final class XessRuntime {
    private static final Logger LOGGER = LoggerFactory.getLogger("Bedray/XessRuntime");

    /** Init flags for the current Minecraft color/depth: reverse-Z depth, LDR (non-HDR, non-linear) RGBA8 color. */
    public static final int INIT_FLAGS = XessNative.INIT_FLAG_INVERTED_DEPTH | XessNative.INIT_FLAG_LDR_INPUT_COLOR;

    /** Experiment switch: continue although XeSS asks for a newer Vulkan API than the engine instance uses. */
    public static final String ALLOW_LOW_API_PROPERTY = "vkbridge.xess.allowLowApi";
    public static final String SMOKE_PROPERTY = "vkbridge.xess.smoke";

    public enum State { IDLE, FAILED, READY, RECONFIGURE_PENDING }

    private static State state = State.IDLE;
    private static XessNative nativeLib;
    private static MemorySegment context;
    private static XessQuality activeMode;
    private static XessNative.Resolution activeInput;
    private static int outputWidth;
    private static int outputHeight;
    /** Incremented on every successful xessVKInit: temporal history must be reset when it changes. */
    private static int initEpoch;
    private static boolean pendingDestroy;
    /** Frame heads seen so far (incremented in {@link #onFrameBegin}). */
    private static long frameCounter;
    /** Context taken out of use by a reconfiguration, destroyed at frame {@link #retireAtFrame}. */
    private static MemorySegment retiredContext;
    private static long retireAtFrame;
    /** Mode of the context created last: a FAILED state belongs to this request. */
    private static XessQuality attempted;
    /** Custom render percent of the READY context / of the last attempt / of the context being created (0 = mode size). */
    private static int activeCustom;
    private static int attemptedCustom;
    private static int pendingCustom;
    /** "from" text of the reconfiguration in progress (for the done line), null when none. */
    private static String reconfigureFrom;

    private XessRuntime() {}

    /** {@code stage=.. result=..} of the last failure (F3), null when none happened. */
    private static String lastFailure;

    public static synchronized String lastFailure() {
        return lastFailure;
    }

    public static synchronized State state() {
        return state;
    }

    public static synchronized boolean isReady() {
        return state == State.READY;
    }

    /** True while an initialised XeSS context exists: the world is rendered at XeSS input resolution (the temporal consumer). */
    public static synchronized boolean isActive() {
        return state == State.READY;
    }

    /** {@code -Dvkbridge.xess.smoke=true}: fill the XeSS output with a known colour instead of executing XeSS (step X3). */
    public static boolean smokeRequested() {
        return Boolean.getBoolean(SMOKE_PROPERTY);
    }

    public static synchronized int outputWidth() {
        return outputWidth;
    }

    public static synchronized int outputHeight() {
        return outputHeight;
    }

    public static synchronized int initEpoch() {
        return initEpoch;
    }

    public static synchronized XessNative nativeLib() {
        return nativeLib;
    }

    public static synchronized MemorySegment context() {
        return context;
    }

    /** output / input width: the super-resolution scale of the active mode. */
    public static synchronized double scale() {
        return activeInput == null || activeInput.x() <= 0 ? 1.0 : (double) outputWidth / activeInput.x();
    }

    /** Directory used to find {@code bedray/natives/libxess.dll}. Falls back to the working directory early in startup. */
    public static Path resolveGameDir() {
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.gameDirectory != null) {
            return mc.gameDirectory.toPath().toAbsolutePath().normalize();
        }
        return Path.of("").toAbsolutePath().normalize();
    }

    /**
     * Called once per frame at the HEAD of {@code GameRenderer.render()}. Creates the context on the first frame in
     * which a world is loaded (menu frames are skipped, known pitfall).
     */
    public static synchronized void onFrameBegin(int winW, int winH) {
        frameCounter++;
        if (pendingDestroy) {
            destroyPendingContext();
        }
        if (state == State.READY && winW > 0 && winH > 0 && (winW != outputWidth || winH != outputHeight)) {
            resizeReinit(winW, winH);
        }
        XessQuality desired = UpscalerRequest.current().xessQuality(); // null: XeSS is not the selected upscaler, zero cost
        int desiredPct = UpscalerRequest.current().customPercentFor(UpscalerBackend.XESS);
        for (int guard = 0; guard < 4; guard++) {
            UpscalerReconfigure.Step step = UpscalerReconfigure.plan(UpscalerReconfigure.Phase.valueOf(state.name()),
                UpscalerReconfigure.target(desired, desiredPct), UpscalerReconfigure.target(activeMode, activeCustom),
                UpscalerReconfigure.target(attempted, attemptedCustom), frameCounter, retireAtFrame);
            switch (step) {
                case NONE -> {
                    return;
                }
                case BEGIN_RETIRE -> beginRetire(desired);
                case DESTROY_RETIRED -> {
                    destroyRetired();
                    state = State.IDLE;
                }
                case CLEAR_FAILED -> {
                    LOGGER.info("[VKB-AUTOTEST] xess=failed_state_cleared attempted={} requested={} (explicit new request)", attempted, desired);
                    state = State.IDLE;
                }
                case CREATE -> {
                    pendingCustom = desiredPct;
                    createWhenPossible(desired, winW, winH);
                    return;
                }
            }
        }
    }

    /** READY context no longer matches the request: out of use now, destroyed after the retire delay. */
    private static void beginRetire(XessQuality desired) {
        String from = UpscalerReconfigure.describe("XESS", activeMode);
        LOGGER.info("[VKB-AUTOTEST] upscaler_reconfigure from={} to={} reason=request phase=retire tech=xess destroy_at_frame={} frame={}",
            from, UpscalerReconfigure.describe("XESS", desired), frameCounter + UpscalerReconfigure.RETIRE_DELAY_FRAMES, frameCounter);
        reconfigureFrom = from;
        retiredContext = context;
        context = null;
        activeMode = null;
        activeInput = null;
        retireAtFrame = frameCounter + UpscalerReconfigure.RETIRE_DELAY_FRAMES;
        state = State.RECONFIGURE_PENDING;
    }

    /** Destroys the context retired by {@link #beginRetire}; the GPU work that used it was submitted frames ago. */
    private static void destroyRetired() {
        if (nativeLib != null && retiredContext != null) {
            int r = nativeLib.destroyContext(retiredContext);
            LOGGER.info("[VKB-AUTOTEST] xess=context_destroyed result={} reason=reconfigure", r);
        }
        retiredContext = null;
    }

    private static void createWhenPossible(XessQuality mode, int winW, int winH) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.level == null || winW <= 0 || winH <= 0) {
            return;
        }
        VulkanDevice device = VulkanCapabilityChecker.getVulkanDevice();
        if (device == null) {
            return; // not the Vulkan backend (yet)
        }
        attempted = mode;
        attemptedCustom = pendingCustom;
        try {
            create(mode, device, winW, winH);
        } catch (RuntimeException | Error e) {
            // The game must keep running without XeSS, but the reason is logged with the full stack (not swallowed).
            LOGGER.error("XeSS setup threw an exception", e);
            LOGGER.error("[VKB-AUTOTEST] xess=FAILED stage=exception result=exception message={}", e.toString());
            destroyQuietlyAfterFailure();
            state = State.FAILED;
        }
    }

    private static void create(XessQuality mode, VulkanDevice device, int winW, int winH) {
        Path gameDir = resolveGameDir();
        Path dll = XessConfig.libraryPath(gameDir);
        DeviceInfo info = device.getDeviceInfo();
        LOGGER.info("XeSS requested: mode={} gpu='{}' vendor='{}' driver='{}' dll={}", mode, info.name(), info.vendorName(), info.driverInfo(), io.github.andrexarlay.vulkanbridge.platform.LogPaths.show(dll));

        if (!XessConfig.libraryExists(gameDir)) {
            fail("library", "dll_missing");
            return;
        }
        if (nativeLib == null) {
            try {
                nativeLib = XessNative.load(dll);
            } catch (XessNativeException e) {
                LOGGER.error("XeSS library load failed", e);
                fail("load", "load_error");
                return;
            }
        }
        String version = nativeLib.getVersionString();
        LOGGER.info("[VKB-AUTOTEST] xess=lib_loaded version={}", version);

        // Instance requirements (X0 probe; needs no handles). The engine instance is already created: it cannot be changed here.
        XessNative.NameList instExt = nativeLib.getRequiredInstanceExtensions();
        if (instExt.result() != XessNative.RESULT_SUCCESS) {
            fail("required_instance_ext", instExt.result());
            return;
        }
        Set<String> enabledInstance = device.instance().getEnabledExtensions();
        List<String> missingInstance = new ArrayList<>();
        for (String n : instExt.names()) {
            if (!enabledInstance.contains(n)) {
                missingInstance.add(n);
            }
        }
        // Real API version of the engine instance (LWJGL derives it from VkApplicationInfo.apiVersion; raised to 1.3 by MixinVulkanInstance).
        int engineApi = device.instance().vkInstance().getCapabilities().apiVersion;
        boolean apiOk = (instExt.minVkApiVersion() & ~0xFFF) <= engineApi;
        // Normal path: MixinVulkanInstance raised the instance to 1.3 for XeSS (min API 1.3 in libxess 2.0.2).
        // -Dvkbridge.xess.allowLowApi=true is a debug-only switch to continue when the instance is still older.
        boolean allowLowApi = Boolean.getBoolean(ALLOW_LOW_API_PROPERTY);
        if (!apiOk && allowLowApi) {
            LOGGER.warn("XeSS reports min Vulkan API 0x{} but the engine instance is 0x{}; continuing because -D{}=true",
                Integer.toHexString(instExt.minVkApiVersion()), Integer.toHexString(engineApi), ALLOW_LOW_API_PROPERTY);
        }
        boolean instanceOk = missingInstance.isEmpty() && (apiOk || allowLowApi);
        LOGGER.info("[VKB-AUTOTEST] xess=required_instance_ext count={} names={} min_vk_api=0x{} engine_api=0x{} enabled={}",
            instExt.names().size(), instExt.names(), Integer.toHexString(instExt.minVkApiVersion()),
            Integer.toHexString(engineApi), instanceOk);
        if (!instanceOk) {
            LOGGER.error("XeSS needs instance extensions {} / API 0x{} that the engine instance does not have (instance cannot be changed here)",
                missingInstance, Integer.toHexString(instExt.minVkApiVersion()));
            fail("required_instance_ext", "missing=" + missingInstance + ",apiOk=" + apiOk);
            return;
        }

        long vkInstance = device.instance().vkInstance().address();
        long vkPhysical = device.vkDevice().getPhysicalDevice().address();
        long vkDevice = device.vkDevice().address();

        // Device requirements versus what the engine device really enabled (DeviceInfo lists "<name> (D)" per enabled extension).
        XessNative.NameList devExt = nativeLib.getRequiredDeviceExtensions(vkInstance, vkPhysical);
        if (devExt.result() != XessNative.RESULT_SUCCESS) {
            fail("required_device_ext", devExt.result());
            return;
        }
        Set<String> underlying = info.underlyingExtensions();
        List<String> missingDevice = new ArrayList<>();
        for (String n : devExt.names()) {
            if (!underlying.contains(n + " (D)")) {
                missingDevice.add(n);
            }
        }
        boolean devOk = missingDevice.isEmpty();
        LOGGER.info("[VKB-AUTOTEST] xess=required_device_ext {} enabled={}", devExt.names(), devOk);
        if (!devOk) {
            LOGGER.warn("XeSS requires device extensions that the engine device did not enable: {} "
                + "(is -Dvkbridge.xess set before startup so that the 'VKB XeSS' FeatureSets were added? is the GPU missing them?)", missingDevice);
        }

        XessNative.FeatureChain features = nativeLib.getRequiredDeviceFeatures(vkInstance, vkPhysical);
        if (features.result() != XessNative.RESULT_SUCCESS) {
            fail("required_device_features", features.result());
            return;
        }
        LOGGER.info("[VKB-AUTOTEST] xess=required_device_features sTypes={} firstBool32={}", features.sTypes(), features.firstBool32());

        if (!devOk) {
            fail("required_device_ext", "missing=" + missingDevice);
            return;
        }

        XessNative.ContextResult ctx = nativeLib.vkCreateContext(vkInstance, vkPhysical, vkDevice);
        if (ctx.result() != XessNative.RESULT_SUCCESS || ctx.handle().address() == 0L) {
            fail("create_context", ctx.result());
            return;
        }
        context = ctx.handle();
        LOGGER.info("[VKB-AUTOTEST] xess=context_created result={}", ctx.result());

        int logR = nativeLib.installLoggingCallback(context, XessNative.LOG_LEVEL_INFO);
        if (logR != XessNative.RESULT_SUCCESS) {
            LOGGER.warn("xessSetLoggingCallback returned {} (XeSS messages will not be forwarded)", logR);
        }

        int driverR = nativeLib.isOptimalDriver(context);
        LOGGER.info("[VKB-AUTOTEST] xess=optimal_driver result={}", driverR);
        if (driverR < 0) {
            fail("optimal_driver", driverR);
            return;
        }
        if (driverR != XessNative.RESULT_SUCCESS) {
            LOGGER.warn("xessIsOptimalDriver result={} (XeSS WARNING_OLD_DRIVER: degraded speed or quality is possible)", driverR);
        }

        XessNative.OptimalInput opt = nativeLib.getOptimalInputResolution(context, winW, winH, mode);
        if (opt.result() != XessNative.RESULT_SUCCESS) {
            fail("optimal_input", opt.result());
            return;
        }
        LOGGER.info("[VKB-AUTOTEST] xess=optimal_input mode={} output={}x{} optimal={} min={} max={}",
            mode, winW, winH, opt.optimal(), opt.min(), opt.max());

        long t0 = System.nanoTime();
        int buildR = nativeLib.vkBuildPipelines(context, 0L, true, INIT_FLAGS);
        LOGGER.info("[VKB-AUTOTEST] xess=build_pipelines result={} ms={}", buildR, (System.nanoTime() - t0) / 1_000_000L);
        if (buildR != XessNative.RESULT_SUCCESS) {
            fail("build_pipelines", buildR);
            return;
        }

        t0 = System.nanoTime();
        int initR = nativeLib.vkInit(context, winW, winH, mode, INIT_FLAGS, 0L);
        long initMs = (System.nanoTime() - t0) / 1_000_000L;
        if (initR != XessNative.RESULT_SUCCESS) {
            fail("init", initR);
            return;
        }
        LOGGER.info("[VKB-AUTOTEST] xess=init result={} mode={} input={} output={}x{} flags=0x{} ms={}",
            initR, mode, opt.optimal(), winW, winH, Integer.toHexString(INIT_FLAGS), initMs);

        XessNative.InitReadback rb = nativeLib.vkGetInitParams(context);
        // Only output resolution and quality are compared: the library normalizes initFlags (sent 0x42, read back 0x2).
        boolean rbOk = rb.result() == XessNative.RESULT_SUCCESS && rb.outW() == winW && rb.outH() == winH
            && rb.quality() == mode.nativeValue();
        LOGGER.info("[VKB-AUTOTEST] xess=init_params_readback ok={} result={} output={}x{} quality={} flags=0x{}",
            rbOk, rb.result(), rb.outW(), rb.outH(), rb.quality(), Integer.toHexString(rb.flags()));
        if (rb.flags() != INIT_FLAGS) {
            LOGGER.info("XeSS normalized initFlags: sent 0x{} read back 0x{} (informational)",
                Integer.toHexString(INIT_FLAGS), Integer.toHexString(rb.flags()));
        }
        if (!rbOk) {
            fail("init_params_layout", "readback_mismatch");
            return;
        }

        activeMode = mode;
        activeInput = opt.optimal();
        activeCustom = pendingCustom;
        if (pendingCustom > 0) {
            // Dynamic input resolution (XeSS SDK 3.0.2 guide "Dynamic Input Resolution"): any input in [min; max] of
            // xessGetOptimalInputResolution, passed per execute (inputWidth/inputHeight = the world target size).
            int[] s = UpscalerReconfigure.customSize(winW, winH, pendingCustom,
                new int[] {opt.min().x(), opt.min().y()}, new int[] {opt.max().x(), opt.max().y()});
            activeInput = new XessNative.Resolution(s[0], s[1]);
            LOGGER.info("[VKB-AUTOTEST] xess=custom_input percent={} output={}x{} min={} max={} used={} clamped={}",
                pendingCustom, winW, winH, opt.min(), opt.max(), activeInput,
                s[0] != Math.round(winW * pendingCustom / 100.0) || s[1] != Math.round(winH * pendingCustom / 100.0));
        }
        outputWidth = winW;
        outputHeight = winH;
        initEpoch++;
        state = State.READY;
        if (reconfigureFrom != null) {
            LOGGER.info("[VKB-AUTOTEST] upscaler_reconfigure from={} to={} reason=request phase=done tech=xess input={} output={}x{} frame={}",
                reconfigureFrom, UpscalerReconfigure.describe("XESS", mode), opt.optimal(), winW, winH, frameCounter);
            reconfigureFrom = null;
        }
    }

    /**
     * The window size changed: the context is bound to the output resolution, so it is destroyed and re-created.
     * No XeSS work of earlier frames may be in flight (vkDeviceWaitIdle on the render thread; resizes are rare).
     */
    private static void resizeReinit(int winW, int winH) {
        LOGGER.info("[VKB-AUTOTEST] xess=resize_reinit from={}x{} to={}x{}", outputWidth, outputHeight, winW, winH);
        VulkanDevice device = VulkanCapabilityChecker.getVulkanDevice();
        if (device != null) {
            int idle = VK10.vkDeviceWaitIdle(device.vkDevice());
            if (idle != VK10.VK_SUCCESS) {
                LOGGER.error("vkDeviceWaitIdle before XeSS re-init returned {}", idle);
            }
        }
        cleanup();
    }

    /**
     * Called when {@code xessVKExecute} reported an error: logs the stage, disables XeSS for the rest of the session
     * (the caller falls back to the plain bilinear path) and releases the context after the GPU is idle.
     */
    public static synchronized void executeFailed(String result) {
        LOGGER.error("[VKB-AUTOTEST] xess=FAILED stage=execute result={} (falling back to bilinear; XeSS disabled for this session)", result);
        pendingDestroy = true;
        activeMode = null;
        activeInput = null;
        state = State.FAILED;
    }

    /**
     * The command buffer that recorded the failed execute may still be submitted this frame, so the context is only
     * destroyed at the head of the next frame (after the GPU is idle).
     */
    private static void destroyPendingContext() {
        VulkanDevice device = VulkanCapabilityChecker.getVulkanDevice();
        if (device != null) {
            VK10.vkDeviceWaitIdle(device.vkDevice());
        }
        destroyQuietlyAfterFailure();
        pendingDestroy = false;
    }

    /** Jitter sign experiment switches (verifier tunes them on a static scene): {@code -Dvkbridge.xess.jitterSignX/Y=1|-1}. */
    public static float jitterSignX() {
        return signProperty("vkbridge.xess.jitterSignX", -1.0f);
    }

    public static float jitterSignY() {
        return signProperty("vkbridge.xess.jitterSignY", -1.0f);
    }

    private static float signProperty(String name, float def) {
        String v = System.getProperty(name);
        if (v == null || v.isBlank()) {
            return def;
        }
        float f = Float.parseFloat(v.trim());
        if (f != 1.0f && f != -1.0f) {
            throw new IllegalArgumentException("-D" + name + " must be 1 or -1, got " + v);
        }
        return f;
    }

    private static void fail(String stage, int result) {
        fail(stage, String.valueOf(result));
    }

    /** {@code result} is an xess_result_t number or a short reason token. */
    private static void fail(String stage, String result) {
        LOGGER.error("[VKB-AUTOTEST] xess=FAILED stage={} result={}", stage, result);
        lastFailure = "stage=" + stage + " result=" + result;
        destroyQuietlyAfterFailure();
        state = State.FAILED;
    }

    /** Releases whatever was created before a failure; problems here are logged, the original failure stays the reported one. */
    private static void destroyQuietlyAfterFailure() {
        if (nativeLib != null && context != null) {
            try {
                int r = nativeLib.destroyContext(context);
                if (r != XessNative.RESULT_SUCCESS) {
                    LOGGER.warn("xessDestroyContext after failure returned {}", r);
                }
            } catch (RuntimeException e) {
                LOGGER.error("xessDestroyContext after failure threw", e);
            }
        }
        context = null;
    }

    /**
     * Destroys the context immediately (render thread; teardown and resize paths only: the caller guarantees that no XeSS
     * work is in flight). Runtime mode switches do not use this, they retire the context ({@link #beginRetire}).
     * The runtime returns to IDLE and creates a new context on the next frame if XeSS is still requested.
     * The DLL stays loaded for the process lifetime (unloading is unsafe while XeSS worker threads may still exist).
     */
    public static synchronized void cleanup() {
        if (state == State.RECONFIGURE_PENDING) {
            destroyRetired(); // teardown path: the caller guarantees nothing is in flight
            state = State.IDLE;
            return;
        }
        if (state == State.IDLE || state == State.FAILED) {
            return;
        }
        if (nativeLib != null && context != null) {
            int r = nativeLib.destroyContext(context);
            LOGGER.info("[VKB-AUTOTEST] xess=context_destroyed result={}", r);
        }
        context = null;
        activeMode = null;
        activeInput = null;
        state = State.IDLE;
    }

    public static synchronized XessQuality activeMode() {
        return activeMode;
    }

    public static synchronized XessNative.Resolution activeInput() {
        return activeInput;
    }
}
