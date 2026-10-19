package io.github.andrexarlay.vulkancompat.rtx.upscaler.dlss;

import com.mojang.renderpearl.api.device.DeviceInfo;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import io.github.andrexarlay.vulkancompat.rtx.raytracing.VulkanCapabilityChecker;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.UpscalerBackend;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.UpscalerReconfigure;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.UpscalerRequest;
import net.minecraft.client.Minecraft;
import org.lwjgl.system.FunctionProvider;
import org.lwjgl.vulkan.VK;
import org.lwjgl.vulkan.VK10;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Lifecycle of the NVIDIA DLSS Super Resolution context (NGX through our wrapper {@code vkb_dlss.dll}).
 * <p>
 * Request-driven: nothing happens unless DLSS is the requested upscaler ({@link UpscalerRequest}: -Dvkbridge.dlss, the toml read
 * before the device exists, or the settings page at runtime). A failure never stops the game: it is
 * logged as {@code [VKB-AUTOTEST] dlss=FAILED stage=... result=...} and the runtime stays in {@link State#FAILED} (normal
 * bilinear rendering continues, no hidden retry: only a changed request leaves FAILED).
 * <p>
 * Runtime switch: when the request no longer matches the active mode the runtime goes READY -> RECONFIGURE_PENDING (out of
 * use at once), the feature is released {@link UpscalerReconfigure#RETIRE_DELAY_FRAMES} frame heads later (no
 * vkDeviceWaitIdle), then the new mode is set up (NGX stays initialised for the device) and the init epoch increases, which
 * resets the temporal history.
 * <p>
 * Steps (each logs a {@code [VKB-DLSS] [VKB-AUTOTEST] dlss=<step>} line): {@code lib_loaded}, {@code required_instance_ext},
 * {@code required_device_ext}, {@code ngx_init}, {@code optimal_settings}, {@code ready}; later {@code feature_created} (first
 * evaluate, needs a command buffer) and {@code dispatch ok}.
 * <p>
 * All methods must run on the render thread. {@link #onFrameBegin(int, int)} is called from the HEAD of
 * {@code GameRenderer.render()} (via {@code WorldTargetManager.resetPhase}), when no render pass is open.
 */
public final class DlssRuntime {
    private static final Logger LOGGER = LoggerFactory.getLogger("Bedray/DlssRuntime");

    public enum State { IDLE, INITIALIZING, FAILED, READY, RECONFIGURE_PENDING }

    /** Render size NGX asked for. */
    public record Size(int x, int y) {
        @Override
        public String toString() {
            return x + "x" + y;
        }
    }

    /** Feature creation flags: LDR colour (no IS_HDR), reverse-Z depth, render-resolution jitter-free motion vectors in pixels. */
    public static final int FEATURE_FLAGS = DlssLayouts.FLAG_MV_LOW_RES | DlssLayouts.FLAG_DEPTH_INVERTED | DlssLayouts.FLAG_AUTO_EXPOSURE;

    /** Timeout in nanoseconds for asynchronous NGX background model loading (25 seconds). */
    public static final long MODEL_LOAD_TIMEOUT_NANOS = 25_000_000_000L;
    /** NVSDK_NGX_Result_FAIL_FeatureNotFound; this is terminal, not a model-loading state. */
    private static final int NGX_RESULT_FAIL_FEATURE_NOT_FOUND = 0xBAD00004;

    private static State state = State.IDLE;
    private static long initializingStartNanos;
    private static long lastInitializingPollLogNanos;
    private static DlssNative nativeLib;
    private static boolean ngxInitialised;
    private static DlssQuality activeMode;
    private static Size activeInput;
    private static int outputWidth;
    private static int outputHeight;
    /** Incremented whenever the render/output size or mode changes: temporal history must be reset. */
    private static int initEpoch;
    private static long feature;
    private static int featureEpoch = -1;
    private static boolean pendingDestroy;
    private static String unsupportedReason;
    /** Frame heads seen so far (incremented in {@link #onFrameBegin}). */
    private static long frameCounter;
    /** Feature taken out of use by a reconfiguration, released at frame {@link #retireAtFrame}. */
    private static long retiredFeature;
    private static long retireAtFrame;
    /** Mode of the setup made last: a FAILED state belongs to this request. */
    private static DlssQuality attempted;
    /** Custom render percent of the READY setup / of the last attempt / of the setup being created (0 = NGX's size of the mode). */
    private static int activeCustom;
    private static int attemptedCustom;
    private static int pendingCustom;
    /** PerfQuality value the feature is created with: activeMode, or for CUSTOM the mode whose NGX range holds the custom size. */
    private static DlssQuality featureMode;
    /** "from" text of the reconfiguration in progress (for the done line), null when none. */
    private static String reconfigureFrom;

    private DlssRuntime() {}

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

    /** True while the world is rendered at the NGX input resolution (the temporal consumer). */
    public static synchronized boolean isActive() {
        return state == State.READY;
    }

    /** True when NGX (or the vendor id) said DLSS cannot run here; used by {@link DlssAvailability#isUsable()}. */
    public static synchronized boolean isUnsupportedOnThisSystem() {
        return unsupportedReason != null;
    }

    public static synchronized String unsupportedReason() {
        return unsupportedReason;
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

    public static synchronized DlssQuality activeMode() {
        return activeMode;
    }

    public static synchronized Size activeInput() {
        return activeInput;
    }

    /** output / render width as reported by NGX for the active mode. */
    public static synchronized double scale() {
        return activeInput == null || activeInput.x() <= 0 ? 1.0 : (double) outputWidth / activeInput.x();
    }

    public static Path resolveGameDir() {
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.gameDirectory != null) {
            return mc.gameDirectory.toPath().toAbsolutePath().normalize();
        }
        return Path.of("").toAbsolutePath().normalize();
    }

    /**
     * Called once per frame at the HEAD of {@code GameRenderer.render()}. Initialises NGX on the first frame in which a world
     * is loaded (menu frames are skipped), and re-queries the render size when the window size changes.
     */
    public static synchronized void onFrameBegin(int winW, int winH) {
        frameCounter++;
        if (pendingDestroy) {
            destroyPending();
        }
        if (state == State.READY && winW > 0 && winH > 0 && (winW != outputWidth || winH != outputHeight)) {
            resize(winW, winH);
        }
        DlssQuality desired = UpscalerRequest.current().dlssQuality(); // null: DLSS is not the selected upscaler, zero cost
        if (state == State.INITIALIZING) {
            if (!java.util.Objects.equals(desired, attempted)) {
                LOGGER.info("[VKB-DLSS] [VKB-AUTOTEST] dlss=initializing_canceled attempted={} requested={}", attempted, desired);
                state = State.IDLE;
                attempted = null;
            } else {
                pollInitializing(desired, winW, winH);
                return;
            }
        }
        int desiredPct = UpscalerRequest.current().customPercentFor(UpscalerBackend.DLSS);
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
                    releaseRetired();
                    state = State.IDLE;
                }
                case CLEAR_FAILED -> {
                    LOGGER.info("[VKB-DLSS] [VKB-AUTOTEST] dlss=failed_state_cleared attempted={} requested={} (explicit new request)", attempted, desired);
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

    /** READY setup no longer matches the request: out of use now, the feature is released after the retire delay. */
    private static void beginRetire(DlssQuality desired) {
        String from = UpscalerReconfigure.describe("DLSS", activeMode);
        LOGGER.info("[VKB-DLSS] [VKB-AUTOTEST] upscaler_reconfigure from={} to={} reason=request phase=retire tech=dlss destroy_at_frame={} frame={}",
            from, UpscalerReconfigure.describe("DLSS", desired), frameCounter + UpscalerReconfigure.RETIRE_DELAY_FRAMES, frameCounter);
        reconfigureFrom = from;
        retiredFeature = feature; // 0 when no evaluate happened yet
        feature = 0L;
        featureEpoch = -1;
        activeMode = null;
        activeInput = null;
        retireAtFrame = frameCounter + UpscalerReconfigure.RETIRE_DELAY_FRAMES;
        state = State.RECONFIGURE_PENDING;
    }

    /** Releases the feature retired by {@link #beginRetire}; the GPU work that used it was submitted frames ago. */
    private static void releaseRetired() {
        if (retiredFeature != 0L && nativeLib != null) {
            DlssNative.Status st = nativeLib.releaseFeature(retiredFeature);
            if (!st.ok()) {
                LOGGER.error("[VKB-DLSS] vkb_dlss_release_feature (retired) failed: {}", st);
            } else {
                LOGGER.info("[VKB-DLSS] [VKB-AUTOTEST] dlss=feature_destroyed reason=reconfigure");
            }
        }
        retiredFeature = 0L;
    }

    private static void createWhenPossible(DlssQuality mode, int winW, int winH) {
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
            // The game keeps running without DLSS, but the reason is logged with the full stack (not swallowed).
            LOGGER.error("DLSS setup threw an exception", e);
            LOGGER.error("[VKB-DLSS] [VKB-AUTOTEST] dlss=FAILED stage=exception result=exception message={}", e.toString());
            state = State.FAILED;
        }
    }

    private static void create(DlssQuality mode, VulkanDevice device, int winW, int winH) {
        DeviceInfo info = device.getDeviceInfo();
        LOGGER.info("[VKB-DLSS] requested: mode={} gpu='{}' vendor='{}' driver='{}'", mode, info.name(), info.vendorName(), info.driverInfo());

        DlssAvailability.Library lib = DlssAvailability.library();
        if (!lib.usable()) {
            fail("library", lib.reason());
            return;
        }
        if (DlssAvailability.gpuRulesOutDlss()) {
            unsupportedReason = "GPU vendor is not NVIDIA (" + info.vendorName() + ")";
            fail("unsupported_gpu", unsupportedReason);
            return;
        }
        if (nativeLib == null) {
            try {
                nativeLib = DlssNative.load(lib.wrapperDll());
            } catch (DlssNativeException e) {
                LOGGER.error("DLSS wrapper load failed", e);
                fail("load", "load_error");
                return;
            }
            nativeLib.installLogCallback();
        }
        LOGGER.info("[VKB-DLSS] [VKB-AUTOTEST] dlss=lib_loaded wrapper={} api=0x{} ngx_api=0x{} featureDir={}",
            io.github.andrexarlay.vulkanbridge.platform.LogPaths.show(nativeLib.libraryPath()), Integer.toHexString(nativeLib.apiVersion()), Integer.toHexString(nativeLib.ngxApiVersion()), io.github.andrexarlay.vulkanbridge.platform.LogPaths.show(lib.featureDir()));

        Path logDir = gameLogDir();

        // Instance requirements: the engine instance already exists; Bedray Core hook (requestInstanceExtensions) had to enable them.
        DlssNative.NameList instExt = nativeLib.requiredInstanceExtensions(lib.featureDir(), logDir);
        if (!instExt.status().ok()) {
            fail("required_instance_ext", instExt.status().toString());
            return;
        }
        Set<String> enabledInstance = device.instance().getEnabledExtensions();
        int engineApi = device.instance().vkInstance().getCapabilities().apiVersion;
        List<String> missingInstance = new ArrayList<>();
        for (String n : instExt.names()) {
            if (!enabledInstance.contains(n)) {
                missingInstance.add(n);
            }
        }
        LOGGER.info("[VKB-DLSS] [VKB-AUTOTEST] dlss=required_instance_ext names={} engine_api=0x{} enabled_by_name={} missing={}",
            instExt.names(), Integer.toHexString(engineApi), enabledInstance.containsAll(instExt.names()), missingInstance);
        if (!missingInstance.isEmpty()) {
            fail("required_instance_ext", "missing=" + missingInstance);
            return;
        }

        long vkInstance = device.instance().vkInstance().address();
        long vkPhysical = device.vkDevice().getPhysicalDevice().address();
        long vkDevice = device.vkDevice().address();

        DlssNative.NameList devExt = nativeLib.requiredDeviceExtensions(vkInstance, vkPhysical, lib.featureDir(), logDir);
        if (!devExt.status().ok()) {
            fail("required_device_ext", devExt.status().toString());
            return;
        }
        Set<String> underlying = info.underlyingExtensions();
        List<String> missingDevice = new ArrayList<>();
        for (String n : devExt.names()) {
            if (!underlying.contains(n + " (D)")) {
                missingDevice.add(n);
            }
        }
        LOGGER.info("[VKB-DLSS] [VKB-AUTOTEST] dlss=required_device_ext names={} missing={} (fixed list in DlssDeviceRequirements: {})",
            devExt.names(), missingDevice, DlssDeviceRequirements.DEVICE_EXTENSIONS);
        if (!missingDevice.isEmpty()) {
            fail("required_device_ext", "missing=" + missingDevice);
            return;
        }

        if (!ngxInitialised) {
            FunctionProvider provider = VK.getFunctionProvider();
            long gipa = provider.getFunctionAddress("vkGetInstanceProcAddr");
            long gdpa = provider.getFunctionAddress("vkGetDeviceProcAddr");
            if (gipa == 0L || gdpa == 0L) {
                fail("ngx_init", "no vkGetInstanceProcAddr/vkGetDeviceProcAddr from the LWJGL function provider");
                return;
            }
            DlssNative.Caps[] caps = new DlssNative.Caps[1];
            long t0 = System.nanoTime();
            DlssNative.Status st = nativeLib.init(vkInstance, vkPhysical, vkDevice, gipa, gdpa, lib.featureDir(), logDir,
                null, null, caps);
            long ms = (System.nanoTime() - t0) / 1_000_000L;
            if (caps[0] != null) {
                LOGGER.info("[VKB-DLSS] [VKB-AUTOTEST] dlss=ngx_init code={} sr_available={} needs_updated_driver={} min_driver={}.{} feature_init_result=0x{} ngx_result_init=0x{} ms={}",
                    st.code(), caps[0].superSamplingAvailable(), caps[0].needsUpdatedDriver(), caps[0].minDriverMajor(),
                    caps[0].minDriverMinor(), Integer.toHexString(caps[0].featureInitResult()),
                    Integer.toHexString(caps[0].ngxResultInit()), ms);
            }
            if (st.code() == DlssNative.ERR_UNSUPPORTED) {
                // NGX is initialised but DLSS SR is not available here (no RTX, old driver, ...): honest, permanent for this session.
                unsupportedReason = st.error();
                ngxInitialised = true;
                fail("unsupported", st.error());
                return;
            }
            if (!st.ok() && st.code() != DlssNative.ERR_ALREADY_INIT) {
                fail("ngx_init", st.toString());
                return;
            }
            ngxInitialised = true;

            if (caps[0] != null && !caps[0].superSamplingAvailable()) {
                if (caps[0].needsUpdatedDriver()) {
                    unsupportedReason = "Driver update required: minimum driver " + caps[0].minDriverMajor() + "." + caps[0].minDriverMinor();
                    fail("unsupported", unsupportedReason);
                    return;
                }
                if (caps[0].featureInitResult() == NGX_RESULT_FAIL_FEATURE_NOT_FOUND) {
                    unsupportedReason = "DLSS SR feature is unavailable (NGX FeatureNotFound, result=0x"
                        + Integer.toHexString(caps[0].featureInitResult()) + ")";
                    fail("unsupported", unsupportedReason);
                    return;
                }
                state = State.INITIALIZING;
                initializingStartNanos = System.nanoTime();
                lastInitializingPollLogNanos = initializingStartNanos;
                LOGGER.info("[VKB-DLSS] [VKB-AUTOTEST] dlss=initializing mode={} feature_init_result=0x{} (waiting for NGX model load, timeout 15s)",
                    mode, Integer.toHexString(caps[0].featureInitResult()));
                return;
            }
        }

        completeReady(mode, winW, winH);
    }

    private static void completeReady(DlssQuality mode, int winW, int winH) {
        Size render;
        DlssQuality fm;
        if (pendingCustom > 0) {
            CustomChoice c = chooseCustom(winW, winH, pendingCustom);
            if (c == null) {
                return; // fail() already logged
            }
            fm = c.mode();
            render = c.size();
        } else {
            DlssNative.OptimalSettings[] opt = new DlssNative.OptimalSettings[1];
            DlssNative.Status os = nativeLib.queryOptimalSettings(mode, winW, winH, opt);
            if (!os.ok()) {
                fail("optimal_settings", os.toString());
                return;
            }
            LOGGER.info("[VKB-DLSS] [VKB-AUTOTEST] dlss=optimal_settings mode={} output={}x{} render={}x{} min={}x{} max={}x{} sharpness={}",
                mode, winW, winH, opt[0].renderWidth(), opt[0].renderHeight(), opt[0].minWidth(), opt[0].minHeight(),
                opt[0].maxWidth(), opt[0].maxHeight(), opt[0].sharpness());
            fm = mode;
            render = new Size(opt[0].renderWidth(), opt[0].renderHeight());
        }

        activeMode = mode;
        activeCustom = pendingCustom;
        featureMode = fm;
        activeInput = render;
        outputWidth = winW;
        outputHeight = winH;
        initEpoch++;
        state = State.READY;
        LOGGER.info("[VKB-DLSS] [VKB-AUTOTEST] dlss=ready mode={} render={} output={}x{} flags=0x{}",
            mode, activeInput, winW, winH, Integer.toHexString(FEATURE_FLAGS));
        if (reconfigureFrom != null) {
            LOGGER.info("[VKB-DLSS] [VKB-AUTOTEST] upscaler_reconfigure from={} to={} reason=request phase=done tech=dlss render={} output={}x{} frame={}",
                reconfigureFrom, UpscalerReconfigure.describe("DLSS", mode), activeInput, winW, winH, frameCounter);
            reconfigureFrom = null;
        }
    }

    private static void pollInitializing(DlssQuality mode, int winW, int winH) {
        long now = System.nanoTime();
        long elapsedNanos = now - initializingStartNanos;
        if (elapsedNanos > MODEL_LOAD_TIMEOUT_NANOS) {
            long sec = elapsedNanos / 1_000_000_000L;
            LOGGER.error("[VKB-DLSS] [VKB-AUTOTEST] dlss=FAILED stage=unsupported result=timeout (model did not finish loading after {}s)", sec);
            unsupportedReason = "DLSS SR model loading timed out (" + sec + "s)";
            fail("unsupported", unsupportedReason);
            return;
        }

        DlssNative.Caps[] caps = new DlssNative.Caps[1];
        DlssNative.Status st = nativeLib != null ? nativeLib.queryCaps(caps) : null;
        if (st != null && st.ok() && caps[0] != null) {
            if (caps[0].needsUpdatedDriver()) {
                unsupportedReason = "Driver update required: minimum driver " + caps[0].minDriverMajor() + "." + caps[0].minDriverMinor();
                fail("unsupported", unsupportedReason);
                return;
            }
            if (caps[0].featureInitResult() == NGX_RESULT_FAIL_FEATURE_NOT_FOUND) {
                unsupportedReason = "DLSS SR feature is unavailable (NGX FeatureNotFound, result=0x"
                    + Integer.toHexString(caps[0].featureInitResult()) + ")";
                fail("unsupported", unsupportedReason);
                return;
            }
            if (caps[0].superSamplingAvailable()) {
                long ms = elapsedNanos / 1_000_000L;
                LOGGER.info("[VKB-DLSS] [VKB-AUTOTEST] dlss=model_loaded elapsed_ms={} mode={}", ms, mode);
                completeReady(mode, winW, winH);
                return;
            }
        }

        if (now - lastInitializingPollLogNanos >= 2_000_000_000L) {
            lastInitializingPollLogNanos = now;
            long sec = elapsedNanos / 1_000_000_000L;
            int initRes = (caps[0] != null) ? caps[0].featureInitResult() : 0;
            LOGGER.info("[VKB-DLSS] [VKB-AUTOTEST] dlss=initializing_pending_model mode={} elapsed_s={} feature_init_result=0x{}",
                mode, sec, Integer.toHexString(initRes));
        }
    }

    /** Mode used for a custom render size and the size itself (inside that mode's NGX dynamic range). */
    record CustomChoice(DlssQuality mode, Size size) {}

    /**
     * DLSS custom render scale. NGX reports for every PerfQuality mode an optimal render size and a dynamic range [min, max]
     * (NGX_DLSS_GET_OPTIMAL_SETTINGS); a feature may be created with any render size in that range. No ratio is assumed: the
     * modes are asked, the one whose range contains the custom size and whose optimal size is closest is used, and when no
     * range contains it the size is clamped into the closest range (logged as clamped=true).
     */
    private static CustomChoice chooseCustom(int winW, int winH, int percent) {
        int[] want = UpscalerReconfigure.customSize(winW, winH, percent, null, null);
        CustomChoice best = null;
        long bestScore = Long.MAX_VALUE;
        StringBuilder ranges = new StringBuilder();
        for (DlssQuality q : new DlssQuality[] {DlssQuality.QUALITY, DlssQuality.BALANCED, DlssQuality.PERFORMANCE,
            DlssQuality.ULTRA_PERFORMANCE, DlssQuality.DLAA}) {
            DlssNative.OptimalSettings[] opt = new DlssNative.OptimalSettings[1];
            DlssNative.Status os = nativeLib.queryOptimalSettings(q, winW, winH, opt);
            if (!os.ok()) {
                ranges.append(q).append("=error(").append(os).append(") ");
                continue;
            }
            DlssNative.OptimalSettings o = opt[0];
            ranges.append(q).append('=').append(o.renderWidth()).append('x').append(o.renderHeight()).append('[')
                .append(o.minWidth()).append('x').append(o.minHeight()).append("..").append(o.maxWidth()).append('x')
                .append(o.maxHeight()).append("] ");
            int[] s = UpscalerReconfigure.customSize(winW, winH, percent,
                new int[] {o.minWidth(), o.minHeight()}, new int[] {o.maxWidth(), o.maxHeight()});
            boolean inside = s[0] == want[0] && s[1] == want[1];
            long score = (inside ? 0L : 1_000_000L) + Math.abs((long) o.renderWidth() - want[0]);
            if (score < bestScore) {
                bestScore = score;
                best = new CustomChoice(q, new Size(s[0], s[1]));
            }
        }
        if (best == null) {
            fail("optimal_settings", "custom: no mode answered: " + ranges.toString().trim());
            return null;
        }
        LOGGER.info("[VKB-DLSS] [VKB-AUTOTEST] dlss=custom_render percent={} output={}x{} wanted={}x{} used={} feature_mode={} clamped={} ranges={}",
            percent, winW, winH, want[0], want[1], best.size(), best.mode(), bestScore >= 1_000_000L, ranges.toString().trim());
        return best;
    }

    private static Path gameLogDir() {
        Path dir = resolveGameDir().resolve("logs").resolve("vkb_dlss");
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot create the NGX log directory " + dir, e);
        }
        return dir;
    }

    /**
     * The window size changed: the render size must be asked from NGX again and the feature is re-created on the next
     * evaluate. The GPU is idle first (resizes are rare); NGX itself stays initialised (it is bound to the device).
     */
    private static void resize(int winW, int winH) {
        LOGGER.info("[VKB-DLSS] [VKB-AUTOTEST] dlss=resize from={}x{} to={}x{}", outputWidth, outputHeight, winW, winH);
        waitIdle();
        releaseFeature();
        if (activeCustom > 0) {
            CustomChoice c = chooseCustom(winW, winH, activeCustom);
            if (c == null) {
                return;
            }
            featureMode = c.mode();
            activeInput = c.size();
        } else {
            DlssNative.OptimalSettings[] opt = new DlssNative.OptimalSettings[1];
            DlssNative.Status os = nativeLib.queryOptimalSettings(activeMode, winW, winH, opt);
            if (!os.ok()) {
                fail("optimal_settings", os.toString());
                return;
            }
            activeInput = new Size(opt[0].renderWidth(), opt[0].renderHeight());
        }
        outputWidth = winW;
        outputHeight = winH;
        initEpoch++;
        LOGGER.info("[VKB-DLSS] [VKB-AUTOTEST] dlss=resized render={} output={}x{}", activeInput, winW, winH);
    }

    /** Makes sure a feature exists for the active size; records its creation into {@code commandBuffer}. */
    public static synchronized boolean ensureFeature(long commandBuffer) {
        if (feature != 0L && featureEpoch == initEpoch) {
            return true;
        }
        if (feature != 0L) {
            // Stale feature of an older size: the previous frame's commands may still use it.
            waitIdle();
            releaseFeature();
        }
        long[] out = new long[1];
        long t0 = System.nanoTime();
        DlssNative.Status st = nativeLib.createFeature(commandBuffer, activeInput.x(), activeInput.y(), outputWidth, outputHeight,
            featureMode != null ? featureMode : activeMode, FEATURE_FLAGS, out);
        if (!st.ok()) {
            executeFailed("create_feature:" + st);
            return false;
        }
        feature = out[0];
        featureEpoch = initEpoch;
        LOGGER.info("[VKB-DLSS] [VKB-AUTOTEST] dlss=feature_created mode={} render={} output={}x{} flags=0x{} ms={}",
            activeMode, activeInput, outputWidth, outputHeight, Integer.toHexString(FEATURE_FLAGS), (System.nanoTime() - t0) / 1_000_000L);
        return true;
    }

    public static synchronized long feature() {
        return feature;
    }

    public static synchronized DlssNative nativeLib() {
        return nativeLib;
    }

    /**
     * Called when creating the feature or {@code vkb_dlss_evaluate} reported an error: logs the stage, disables DLSS for
     * the rest of the session (the caller falls back to the bilinear path); the feature is released after the GPU is idle,
     * at the head of the next frame (the failed command buffer may still be submitted this frame). NGX itself is shared
     * with Streamline in-process and must remain initialized for Streamline's DLSS-G feature.
     */
    public static synchronized void executeFailed(String result) {
        LOGGER.error("[VKB-DLSS] [VKB-AUTOTEST] dlss=FAILED stage=execute result={} (falling back to bilinear; DLSS disabled for this session)", result);
        pendingDestroy = true;
        activeMode = null;
        activeInput = null;
        state = State.FAILED;
    }

    private static void destroyPending() {
        waitIdle();
        releaseFeature();
        pendingDestroy = false;
    }

    private static void waitIdle() {
        VulkanDevice device = VulkanCapabilityChecker.getVulkanDevice();
        if (device != null) {
            int idle = VK10.vkDeviceWaitIdle(device.vkDevice());
            if (idle != VK10.VK_SUCCESS) {
                LOGGER.error("vkDeviceWaitIdle for DLSS returned {}", idle);
            }
        }
    }

    private static void releaseFeature() {
        if (feature != 0L && nativeLib != null) {
            DlssNative.Status st = nativeLib.releaseFeature(feature);
            if (!st.ok()) {
                LOGGER.error("[VKB-DLSS] vkb_dlss_release_feature failed: {}", st);
            }
        }
        feature = 0L;
        featureEpoch = -1;
    }

    /**
     * Frees the feature (render thread). The runtime returns to IDLE and sets up again on the next world frame if DLSS is
     * still requested. NGX stays initialised for the device; the wrapper DLL stays loaded for the process lifetime.
     */
    public static synchronized void cleanup() {
        if (state == State.RECONFIGURE_PENDING) {
            waitIdle(); // teardown path only, never the frame path
            releaseRetired();
            state = State.IDLE;
            return;
        }
        if (state == State.INITIALIZING) {
            state = State.IDLE;
            return;
        }
        if (state == State.IDLE || state == State.FAILED) {
            return;
        }
        waitIdle();
        releaseFeature();
        LOGGER.info("[VKB-DLSS] [VKB-AUTOTEST] dlss=feature_destroyed");
        activeMode = null;
        activeInput = null;
        state = State.IDLE;
    }

    private static void fail(String stage, String result) {
        LOGGER.error("[VKB-DLSS] [VKB-AUTOTEST] dlss=FAILED stage={} result={}", stage, result);
        lastFailure = "stage=" + stage + " result=" + result;
        state = State.FAILED;
    }

    /** Test hook: back to the initial state (no native calls). */
    static synchronized void resetForTests() {
        state = State.IDLE;
        nativeLib = null;
        ngxInitialised = false;
        activeMode = null;
        activeInput = null;
        outputWidth = 0;
        outputHeight = 0;
        initEpoch = 0;
        feature = 0L;
        featureEpoch = -1;
        pendingDestroy = false;
        unsupportedReason = null;
        frameCounter = 0L;
        retiredFeature = 0L;
        retireAtFrame = 0L;
        attempted = null;
        reconfigureFrom = null;
        initializingStartNanos = 0L;
        lastInitializingPollLogNanos = 0L;
    }
}
