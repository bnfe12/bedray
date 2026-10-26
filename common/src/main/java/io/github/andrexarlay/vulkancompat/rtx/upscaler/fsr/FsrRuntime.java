package io.github.andrexarlay.vulkancompat.rtx.upscaler.fsr;

import com.mojang.renderpearl.api.device.DeviceInfo;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import io.github.andrexarlay.vulkanbridge.platform.LogPaths;
import io.github.andrexarlay.vulkancompat.rtx.raytracing.VulkanCapabilityChecker;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.UpscalerBackend;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.UpscalerPreregistration;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.UpscalerReconfigure;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.UpscalerRequest;
import net.minecraft.client.Minecraft;
import org.lwjgl.vulkan.VK10;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;

/**
 * Lifecycle of the AMD FidelityFX (ffx-api, Vulkan backend) upscaler context. Same state machine as XessRuntime / DlssRuntime
 * (IDLE, READY, RECONFIGURE_PENDING, FAILED; {@link UpscalerReconfigure}): request-driven ({@link UpscalerRequest}), created on
 * the first world frame, retired {@link UpscalerReconfigure#RETIRE_DELAY_FRAMES} frame heads before destruction on a mode or
 * version change, re-created on a window resize. A failure is logged as {@code [VKB-FSR] fsr=FAILED stage=.. result=..} and
 * keeps the game running on the bilinear path; only a changed request leaves FAILED (no hidden retry).
 * <p>
 * Render thread only; {@link #onFrameBegin} runs at the HEAD of {@code GameRenderer.render()} (no render pass open).
 */
public final class FsrRuntime {
    private static final Logger LOGGER = LoggerFactory.getLogger("Bedray/FsrRuntime");

    public enum State { IDLE, FAILED, READY, RECONFIGURE_PENDING }

    private static State state = State.IDLE;
    private static FfxNative nativeLib;
    private static List<FfxNative.ProviderVersion> versions = List.of();
    private static FfxNative.Context context;
    private static FsrRequest activeRequest;
    private static FfxNative.Size activeInput;
    private static String activeProvider;
    private static int activeFlags;
    private static int outputWidth;
    private static int outputHeight;
    private static int initEpoch;
    private static boolean pendingDestroy;
    private static long frameCounter;
    private static FfxNative.Context retiredContext;
    private static long retireAtFrame;
    private static FsrRequest attempted;
    /** Custom render percent of the READY context / of the last attempt / of the context being created (0 = mode size). */
    private static int activeCustom;
    private static int attemptedCustom;
    private static int pendingCustom;
    private static String reconfigureFrom;

    private FsrRuntime() {}

    public static synchronized State state() {
        return state;
    }

    public static synchronized boolean isReady() {
        return state == State.READY;
    }

    public static synchronized boolean isActive() {
        return state == State.READY;
    }

    public static synchronized FfxNative nativeLib() {
        return nativeLib;
    }

    public static synchronized FfxNative.Context context() {
        return context;
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

    public static synchronized FsrRequest activeRequest() {
        return activeRequest;
    }

    public static synchronized FsrQuality activeMode() {
        return activeRequest == null ? null : activeRequest.quality();
    }

    public static synchronized FfxNative.Size activeInput() {
        return activeInput;
    }

    public static synchronized String activeProvider() {
        return activeProvider;
    }

    public static synchronized int activeFlags() {
        return activeFlags;
    }

    public static synchronized double scale() {
        return activeInput == null || activeInput.width() <= 0 ? 1.0 : (double) outputWidth / activeInput.width();
    }

    /**
     * Upscaler providers of the official DLL (loads it on first use; for the settings page). Empty list when the library is
     * not usable or the query failed (logged).
     */
    public static synchronized List<FfxNative.ProviderVersion> availableVersions() {
        if (nativeLib == null) {
            FsrLibrary.Library lib = FsrLibrary.library();
            if (!lib.usable()) {
                return List.of();
            }
            try {
                nativeLib = FfxNative.load(lib.dll());
            } catch (FsrException e) {
                LOGGER.error("[VKB-FSR] fsr=FAILED stage=load result={}", e.getMessage(), e);
                return List.of();
            }
        }
        if (versions.isEmpty()) {
            FfxNative.QueryResult<List<FfxNative.ProviderVersion>> q = nativeLib.upscalerVersions();
            LOGGER.info("[VKB-FSR] [VKB-AUTOTEST] fsr=versions result={} available={}", q.result(), q.value());
            if (q.result() == FfxNative.RETURN_OK) {
                versions = q.value();
            }
        }
        return versions;
    }

    public static synchronized void onFrameBegin(int winW, int winH) {
        frameCounter++;
        if (pendingDestroy) {
            destroyPendingContext();
        }
        if (state == State.READY && winW > 0 && winH > 0 && (winW != outputWidth || winH != outputHeight)) {
            resizeReinit(winW, winH);
        }
        FsrRequest desired = UpscalerRequest.current().fsrRequest(); // null: FSR is not the selected upscaler
        int desiredPct = UpscalerRequest.current().customPercentFor(UpscalerBackend.FSR);
        for (int guard = 0; guard < 4; guard++) {
            UpscalerReconfigure.Step step = UpscalerReconfigure.plan(UpscalerReconfigure.Phase.valueOf(state.name()),
                UpscalerReconfigure.target(desired, desiredPct), UpscalerReconfigure.target(activeRequest, activeCustom),
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
                    LOGGER.info("[VKB-FSR] [VKB-AUTOTEST] fsr=failed_state_cleared attempted={} requested={} (explicit new request)", attempted, desired);
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

    private static void beginRetire(FsrRequest desired) {
        String from = UpscalerReconfigure.describe("FSR", activeRequest);
        LOGGER.info("[VKB-AUTOTEST] upscaler_reconfigure from={} to={} reason=request phase=retire tech=fsr destroy_at_frame={} frame={}",
            from, UpscalerReconfigure.describe("FSR", desired), frameCounter + UpscalerReconfigure.RETIRE_DELAY_FRAMES, frameCounter);
        reconfigureFrom = from;
        retiredContext = context;
        context = null;
        activeRequest = null;
        activeInput = null;
        activeProvider = null;
        retireAtFrame = frameCounter + UpscalerReconfigure.RETIRE_DELAY_FRAMES;
        state = State.RECONFIGURE_PENDING;
    }

    private static void destroyRetired() {
        if (nativeLib != null && retiredContext != null) {
            int r = nativeLib.destroyContext(retiredContext);
            LOGGER.info("[VKB-FSR] [VKB-AUTOTEST] fsr=context_destroyed result={} reason=reconfigure", r);
        }
        retiredContext = null;
    }

    private static void createWhenPossible(FsrRequest request, int winW, int winH) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.level == null || winW <= 0 || winH <= 0) {
            return;
        }
        VulkanDevice device = VulkanCapabilityChecker.getVulkanDevice();
        if (device == null) {
            return;
        }
        attempted = request;
        attemptedCustom = pendingCustom;
        try {
            create(request, device, winW, winH);
        } catch (FsrException e) {
            LOGGER.error("FSR setup failed\n{}", LogPaths.stack(e));
            fail(e.stage(), e.getMessage());
        } catch (RuntimeException | Error e) {
            // The game keeps running without FSR; the reason is logged with the full stack (not swallowed).
            // Redacted stack: the FFM load failure carries the absolute DLL path inside its own message.
            LOGGER.error("FSR setup threw an exception\n{}", LogPaths.stack(e));
            fail("exception", LogPaths.show(e));
        }
    }

    private static void create(FsrRequest request, VulkanDevice device, int winW, int winH) {
        DeviceInfo info = device.getDeviceInfo();
        FsrLibrary.Library lib = FsrLibrary.library();
        LOGGER.info("[VKB-FSR] FSR requested: request={} gpu='{}' vendor='{}' driver='{}' dll={}",
            request, info.name(), info.vendorName(), info.driverInfo(), io.github.andrexarlay.vulkanbridge.platform.LogPaths.show(lib.dll()));
        if (!lib.usable()) {
            fail("library", lib.reason());
            return;
        }
        if (!UpscalerPreregistration.isRegistered(UpscalerBackend.FSR)) {
            // The FSR VK backend picks shader permutations from what the GPU supports (FP16, storage image formats);
            // without our FeatureSets the device may lack features those shaders use. Refused, not risked.
            fail("device_features", "not_registered (FSR FeatureSets were not added at device creation: restart with the library installed)");
            return;
        }
        // ffxCreateContext calls vkGetBufferMemoryRequirements2KHR through vkGetDeviceProcAddr (NULL without the extension
        // enabled on the device: access violation at pc=0). Refuse instead of crashing (FsrDeviceRequirements).
        java.util.Set<String> underlying = info.underlyingExtensions();
        List<String> missing = new java.util.ArrayList<>();
        for (String ext : FsrDeviceRequirements.BASE_EXTENSIONS) {
            if (!underlying.contains(ext + " (D)")) {
                missing.add(ext);
            }
        }
        LOGGER.info("[VKB-FSR] [VKB-AUTOTEST] fsr=required_device_ext {} enabled={}", FsrDeviceRequirements.BASE_EXTENSIONS, missing.isEmpty());
        if (!missing.isEmpty()) {
            fail("required_device_ext", "missing=" + missing + " (the optional FeatureSet '" + FsrDeviceRequirements.ID + "' was not enabled)");
            return;
        }
        List<FfxNative.ProviderVersion> available = availableVersions();
        if (nativeLib == null || available.isEmpty()) {
            fail("versions", "no upscaler provider reported by " + lib.dll());
            return;
        }
        Optional<FfxNative.ProviderVersion> picked = request.version().pick(available);
        if (request.version() != FsrVersion.AUTO && picked.isEmpty()) {
            fail("version", "not_in_dll requested=" + request.version() + " available=" + available);
            return;
        }
        long overrideId = picked.map(FfxNative.ProviderVersion::id).orElse(0L);
        int quality = request.quality().nativeValue();

        FfxNative.QueryResult<FfxNative.Size> rr = nativeLib.renderResolution(winW, winH, quality, overrideId);
        FfxNative.QueryResult<Float> ratio = nativeLib.upscaleRatio(quality, overrideId);
        LOGGER.info("[VKB-FSR] [VKB-AUTOTEST] fsr=query render_resolution result={} mode={} display={}x{} render={} ratio_result={} ratio={} table_ratio={}",
            rr.result(), request.quality(), winW, winH, rr.value(), ratio.result(), ratio.value(), request.quality().ratio());
        if (rr.result() != FfxNative.RETURN_OK || rr.value().width() <= 0 || rr.value().height() <= 0) {
            fail("query_render_resolution", String.valueOf(rr.result()));
            return;
        }
        if (ratio.result() == FfxNative.RETURN_OK && Math.abs(ratio.value() - request.quality().ratio()) > 1e-3) {
            // The UI table comes from the SDK header; a different DLL answer is reported (the DLL answer is what is used).
            LOGGER.warn("[VKB-FSR] FSR ratio of {} from the DLL is {} but the SDK header table says {}", request.quality(), ratio.value(), request.quality().ratio());
        }
        FfxNative.Size render = rr.value();
        if (pendingCustom > 0) {
            // Custom render scale: the context is created with maxRenderSize = the custom size and every dispatch uses it
            // (ffx_upscale.h: renderSize per dispatch may be anything up to maxRenderSize). The mode query above is only logged.
            int[] s = UpscalerReconfigure.customSize(winW, winH, pendingCustom, null, null);
            render = new FfxNative.Size(s[0], s[1]);
            LOGGER.info("[VKB-FSR] [VKB-AUTOTEST] fsr=custom_render percent={} output={}x{} render={}", pendingCustom, winW, winH, render);
        }

        FsrCamera cam = FsrCamera.fromCurrentProjection();
        int flags = FfxNative.CREATE_DEPTH_INVERTED | FfxNative.CREATE_AUTO_EXPOSURE;
        if (cam.infiniteFar()) {
            flags |= FfxNative.CREATE_DEPTH_INFINITE;
        }
        if (FsrConfig.debugChecking()) {
            flags |= FfxNative.CREATE_DEBUG_CHECKING;
        }
        long gdpa = device.vkDevice().getCapabilities().vkGetDeviceProcAddr;
        if (gdpa == 0L) {
            fail("create_context", "vkGetDeviceProcAddr address is 0");
            return;
        }
        long t0 = System.nanoTime();
        FfxNative.ContextResult created = nativeLib.createUpscaleContext(flags, render.width(), render.height(), winW, winH,
            device.vkDevice().address(), device.vkDevice().getPhysicalDevice().address(), gdpa, overrideId);
        long ms = (System.nanoTime() - t0) / 1_000_000L;
        if (created.result() != FfxNative.RETURN_OK || created.context().handle() == 0L) {
            fail("create_context", String.valueOf(created.result()));
            return;
        }
        context = created.context();
        FfxNative.QueryResult<FfxNative.ProviderVersion> pv = nativeLib.providerVersion(context);
        activeProvider = pv.result() == FfxNative.RETURN_OK ? pv.value().name() : "unknown(result=" + pv.result() + ")";
        LOGGER.info("[VKB-FSR] [VKB-AUTOTEST] fsr=context_created result=0 provider={} requested_version={} override=0x{} flags=0x{} max_render={} output={}x{} camera={} ms={}",
            activeProvider, request.version(), Long.toHexString(overrideId), Integer.toHexString(flags), render, winW, winH, cam, ms);
        if (picked.isPresent() && !picked.get().name().equals(activeProvider)) {
            fail("provider_mismatch", "requested " + picked.get() + " got " + activeProvider);
            return;
        }
        activeRequest = request;
        activeCustom = pendingCustom;
        activeInput = render;
        activeFlags = flags;
        outputWidth = winW;
        outputHeight = winH;
        initEpoch++;
        state = State.READY;
        if (reconfigureFrom != null) {
            LOGGER.info("[VKB-AUTOTEST] upscaler_reconfigure from={} to={} reason=request phase=done tech=fsr input={} output={}x{} frame={}",
                reconfigureFrom, UpscalerReconfigure.describe("FSR", request), render, winW, winH, frameCounter);
            reconfigureFrom = null;
        }
    }

    /** The window size changed: the context is bound to the output size; destroyed after the GPU is idle (rare). */
    private static void resizeReinit(int winW, int winH) {
        LOGGER.info("[VKB-FSR] [VKB-AUTOTEST] fsr=resize_reinit from={}x{} to={}x{}", outputWidth, outputHeight, winW, winH);
        waitIdle("FSR re-init");
        cleanup();
    }

    /** ffxDispatch failed: FSR is disabled for this request; the context is destroyed at the next frame head. */
    public static synchronized void executeFailed(String result) {
        LOGGER.error("[VKB-FSR] [VKB-AUTOTEST] fsr=FAILED stage=execute result={} (falling back to bilinear; FSR disabled until the request changes)", result);
        pendingDestroy = true;
        activeRequest = null;
        activeInput = null;
        state = State.FAILED;
    }

    private static void destroyPendingContext() {
        waitIdle("FSR destroy after failure");
        destroyAfterFailure();
        pendingDestroy = false;
    }

    private static void waitIdle(String why) {
        VulkanDevice device = VulkanCapabilityChecker.getVulkanDevice();
        if (device != null) {
            int idle = VK10.vkDeviceWaitIdle(device.vkDevice());
            if (idle != VK10.VK_SUCCESS) {
                LOGGER.error("vkDeviceWaitIdle before {} returned {}", why, idle);
            }
        }
    }

    private static void fail(String stage, String result) {
        LOGGER.error("[VKB-FSR] [VKB-AUTOTEST] fsr=FAILED stage={} result={}", stage, result);
        destroyAfterFailure();
        activeRequest = null;
        activeInput = null;
        state = State.FAILED;
    }

    /** Releases a context created before a failure; a problem here is logged, the original failure stays the reported one. */
    private static void destroyAfterFailure() {
        if (nativeLib != null && context != null) {
            try {
                int r = nativeLib.destroyContext(context);
                if (r != FfxNative.RETURN_OK) {
                    LOGGER.warn("[VKB-FSR] ffxDestroyContext after failure returned {}", r);
                }
            } catch (RuntimeException e) {
                LOGGER.error("[VKB-FSR] ffxDestroyContext after failure threw", e);
            }
        }
        context = null;
    }

    /**
     * Destroys the context now (teardown and resize paths; the caller guarantees no FSR work is in flight). The DLL stays
     * loaded for the process lifetime.
     */
    public static synchronized void cleanup() {
        if (state == State.RECONFIGURE_PENDING) {
            destroyRetired();
            state = State.IDLE;
            return;
        }
        if (state == State.IDLE || state == State.FAILED) {
            return;
        }
        if (nativeLib != null && context != null) {
            int r = nativeLib.destroyContext(context);
            LOGGER.info("[VKB-FSR] [VKB-AUTOTEST] fsr=context_destroyed result={}", r);
        }
        context = null;
        activeRequest = null;
        activeInput = null;
        state = State.IDLE;
    }
}
