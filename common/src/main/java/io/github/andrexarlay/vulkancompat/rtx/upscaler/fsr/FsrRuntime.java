package io.github.andrexarlay.vulkancompat.rtx.upscaler.fsr;

import com.mojang.renderpearl.api.device.DeviceInfo;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import io.github.andrexarlay.vulkanbridge.platform.LogPaths;
import io.github.andrexarlay.vulkancompat.rtx.raytracing.VulkanCapabilityChecker;
import io.github.andrexarlay.vulkancompat.rtx.WorldTargetManager;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.UpscalerBackend;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.UpscalerContextRetirement;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.UpscalerPreregistration;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.UpscalerReconfigure;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.UpscalerRequest;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Lifecycle of the AMD FidelityFX (ffx-api, Vulkan backend) upscaler context. Same state machine as XessRuntime / DlssRuntime
 * (IDLE, READY, RECONFIGURE_PENDING, FAILED; {@link UpscalerReconfigure}): request-driven ({@link UpscalerRequest}), created on
 * the first world frame, retired after host submission and fence completion on a mode or version change, and re-created
 * on a window resize. A failure is logged as {@code [VKB-FSR] fsr=FAILED stage=.. result=..} and
 * keeps the game running on the bilinear path; only a changed request leaves FAILED (no hidden retry).
 * <p>
 * Render thread only; {@link #onFrameBegin} runs at the HEAD of {@code GameRenderer.render()} (no render pass open).
 */
public final class FsrRuntime {
    private static final Logger LOGGER = LoggerFactory.getLogger("Bedray/FsrRuntime");

    public enum State { IDLE, FAILED, READY, RECONFIGURE_PENDING }

    private static State state = State.IDLE;
    private static FfxNative nativeLib;
    private static FfxNative.Context context;
    private static VulkanDevice contextDevice;
    private static FsrRequest activeRequest;
    private static FfxNative.Size activeInput;
    private static String activeProvider;
    private static String failureReason;
    private static int activeFlags;
    private static int outputWidth;
    private static int outputHeight;
    private static int initEpoch;
    private static boolean pendingDestroy;
    private static long frameCounter;
    private static FfxNative.Context retiredContext;
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

    public static synchronized String failureReason() {
        return failureReason;
    }

    private static volatile List<FfxNative.ProviderVersion> availableVersions = List.of();
    private static volatile VulkanDevice probedDevice;

    /** Snapshot for UI; settings construction never loads AMD libraries or runs a native query. */
    public static List<FfxNative.ProviderVersion> availableVersions() {
        return availableVersions;
    }

    /** Runs once per device, outside settings callbacks, before any world upscaler is requested. */
    public static void probeProviders() {
        if (probedDevice != null) return;
        VulkanDevice device = VulkanCapabilityChecker.getVulkanDevice();
        if (device == null) return;
        long started = System.nanoTime();
        availableVersions = FsrProviders.available(device);
        probedDevice = device;
        LOGGER.info("[VKB-FSR] provider_snapshot available={} ms={}", availableVersions, (System.nanoTime() - started) / 1_000_000);
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
                UpscalerReconfigure.target(attempted, attemptedCustom));
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
        LOGGER.info("[VKB-AUTOTEST] upscaler_reconfigure from={} to={} reason=request phase=retire tech=fsr proof=host_fence_required frame={}",
            from, UpscalerReconfigure.describe("FSR", desired), frameCounter);
        reconfigureFrom = from;
        retiredContext = context;
        context = null;
        activeRequest = null;
        activeInput = null;
        activeProvider = null;
        state = State.RECONFIGURE_PENDING;
    }

    private static void destroyRetired() {
        retiredContext = releaseContext(retiredContext, "destroy_retired");
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
            fail(e.stage(), LogPaths.show(e.getMessage()));
        } catch (RuntimeException | Error e) {
            // The game keeps running without FSR; the reason is logged with the full stack (not swallowed).
            // Redacted stack: the FFM load failure carries the absolute DLL path inside its own message.
            LOGGER.error("FSR setup threw an exception\n{}", LogPaths.stack(e));
            fail("exception", LogPaths.show(e));
        }
    }

    private static void create(FsrRequest request, VulkanDevice device, int winW, int winH) {
        if (context != null || retiredContext != null)
            throw new IllegalStateException("provider=fsr stage=create retained_context=true");
        DeviceInfo info = device.getDeviceInfo();
        LOGGER.info("[VKB-FSR] request={} gpu='{}' vendor='{}' driver='{}'",
            request, info.name(), info.vendorName(), info.driverInfo());
        if (!UpscalerPreregistration.isRegistered(UpscalerBackend.FSR)) {
            // The FSR VK backend picks shader permutations from what the GPU supports (FP16, storage image formats);
            // without our FeatureSets the device may lack features those shaders use. Refused, not risked.
            fail("device_features", "not_registered (FSR FeatureSets were not added at device creation: restart with the library installed)");
            return;
        }
        FfxNative.VulkanHandles handles = FsrProviders.handles(device);
        if (handles.getDeviceProcAddr() == 0L) {
            throw new FsrException("device", "vkGetDeviceProcAddr is unavailable", null);
        }
        availableVersions = FsrProviders.available(device);
        var candidates = FsrProviders.candidates(device, request.version());
        if (candidates.isEmpty()) {
            throw new FsrException("version", "requested=" + request.version()
                + " available=" + availableVersions, null);
        }
        FsrException lastFailure = null;
        for (var candidate : candidates) {
            try {
                createWithProvider(request, device, handles, candidate, winW, winH);
                return;
            } catch (FsrException failure) {
                lastFailure = failure;
                LOGGER.warn("[VKB-FSR] candidate_failed provider={} dll={} stage={} reason={}",
                    candidate, candidate.library().libraryPath().getFileName(), failure.stage(), LogPaths.show(failure.getMessage()));
                // Setup may already own a context. No fallback while that context is retained.
                context = releaseContext(context, "rejected_provider");
            }
        }
        throw lastFailure;
    }

    private static void createWithProvider(FsrRequest request, VulkanDevice device, FfxNative.VulkanHandles handles,
                                           FsrProviders.Candidate candidate, int winW, int winH) {
        FfxNative selected = candidate.library();
        long overrideId = candidate.versionId();
        if (candidate.allowMl()) {
            var required = selected.requiredInputs(overrideId, handles);
            if (required.result() != FfxNative.RETURN_OK || (required.value() & ~FfxNative.PROVIDED_INPUTS) != 0) {
                throw new FsrException("resource_requirements", "sdk_return=" + required.result()
                    + " required=0x" + Long.toHexString(required.value()), null);
            }
        }
        int quality = request.quality().nativeValue();

        FfxNative.QueryResult<FfxNative.Size> rr = selected.renderResolution(winW, winH, quality, overrideId, handles);
        FfxNative.QueryResult<Float> ratio = selected.upscaleRatio(quality, overrideId, handles);
        LOGGER.info("[VKB-FSR] [VKB-AUTOTEST] fsr=query render_resolution result={} mode={} display={}x{} render={} ratio_result={} ratio={} table_ratio={}",
            rr.result(), request.quality(), winW, winH, rr.value(), ratio.result(), ratio.value(), request.quality().ratio());
        if (rr.result() != FfxNative.RETURN_OK || rr.value().width() <= 0 || rr.value().height() <= 0) {
            throw new FsrException("query_render_resolution", "sdk_return=" + rr.result() + " render=" + rr.value(), null);
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
            if (candidate.allowMl()) {
                var minimum = selected.renderResolution(winW, winH, FsrQuality.ULTRA_PERFORMANCE.nativeValue(), overrideId, handles);
                if (minimum.result() != FfxNative.RETURN_OK || minimum.value().width() <= 0 || minimum.value().height() <= 0) {
                    throw new FsrException("custom_scale_limits", "sdk_return=" + minimum.result(), null);
                }
                s[0] = Math.max(s[0], minimum.value().width());
                s[1] = Math.max(s[1], minimum.value().height());
            }
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
        long t0 = System.nanoTime();
        FfxNative.ContextResult created = selected.createUpscaleContext(flags, render.width(), render.height(), winW, winH,
            handles, overrideId);
        long ms = (System.nanoTime() - t0) / 1_000_000L;
        context = created.context();
        nativeLib = selected;
        contextDevice = device;
        if (created.result() != FfxNative.RETURN_OK || context == null) {
            throw new FsrException("create_context", "sdk_return=" + created.result() + " has_context=" + (context != null), null);
        }
        var provider = selected.providerVersion(context);
        if (provider.result() != FfxNative.RETURN_OK || !candidate.accepts(provider.value())) {
            throw new FsrException("provider_mismatch", "expected=" + candidate + " actual=" + provider, null);
        }
        activeProvider = provider.value().name();
        LOGGER.info("[VKB-FSR] [VKB-AUTOTEST] fsr=context_created result=0 provider={} requested_version={} dll={} override=0x{} flags=0x{} max_render={} output={}x{} camera={} ms={}",
            activeProvider, request.version(), selected.libraryPath().getFileName(), Long.toHexString(overrideId),
            Integer.toHexString(flags), render, winW, winH, cam, ms);
        failureReason = null;
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

    /** Window resize runs before CB recording; context destruction proves host completion first. */
    private static void resizeReinit(int winW, int winH) {
        LOGGER.info("[VKB-FSR] [VKB-AUTOTEST] fsr=resize_reinit from={}x{} to={}x{}", outputWidth, outputHeight, winW, winH);
        cleanup();
    }

    /** ffxDispatch failed: FSR is disabled for this request; the context is destroyed at the next frame head. */
    public static synchronized void executeFailed(String result) {
        LOGGER.error("[VKB-FSR] [VKB-AUTOTEST] provider=fsr stage=execute frame={} context=0x{} output={}x{} retained={} result={}",
            frameCounter, context == null ? "0" : Long.toHexString(context.handle()), outputWidth, outputHeight, context != null, result);
        pendingDestroy = true;
        failureReason = "execute: " + result;
        activeRequest = null;
        activeInput = null;
        state = State.FAILED;
    }

    private static void destroyPendingContext() {
        context = releaseContext(context, "destroy_after_execute_failure");
        pendingDestroy = false;
    }

    private static void fail(String stage, String result) {
        failureReason = stage + ": " + result;
        LOGGER.error("[VKB-FSR] [VKB-AUTOTEST] fsr=FAILED stage={} result={}", stage, result);
        activeRequest = null;
        activeInput = null;
        state = State.FAILED;
        destroyAfterFailure();
    }

    /** Releases a context created before a failure; a problem here is logged, the original failure stays the reported one. */
    private static void destroyAfterFailure() {
        try {
            context = releaseContext(context, "destroy_after_setup_failure");
        } catch (RuntimeException | Error failure) {
            pendingDestroy = true;
            LOGGER.error("[VKB-FSR] provider=fsr stage=setup_cleanup frame={} context=0x{} retained=true\n{}",
                frameCounter, Long.toHexString(context.handle()), LogPaths.stack(failure));
        }
    }

    private static FfxNative.Context releaseContext(FfxNative.Context owned, String stage) {
        return UpscalerContextRetirement.release(owned, () -> {
            try {
                if (contextDevice == null) throw new IllegalStateException("FSR context lost its host device");
                WorldTargetManager.drainTemporalRecordings(contextDevice);
            } catch (RuntimeException | Error failure) {
                LOGGER.error("[VKB-FSR] provider=fsr stage={} frame={} context=0x{} output={}x{} proof=FAILED retained=true\n{}",
                    stage, frameCounter, Long.toHexString(owned.handle()), outputWidth, outputHeight, LogPaths.stack(failure));
                throw failure;
            }
        }, handle -> {
            long address = handle.handle();
            try {
                if (nativeLib == null) throw new IllegalStateException("FSR context lost its native library");
                int result = nativeLib.destroyContext(handle);
                if (result != FfxNative.RETURN_OK) throw new IllegalStateException("sdk_return=" + result);
                LOGGER.info("[VKB-FSR] [VKB-AUTOTEST] fsr=context_destroyed stage={} context=0x{} frame={}",
                    stage, Long.toHexString(address), frameCounter);
            } catch (RuntimeException | Error failure) {
                LOGGER.error("[VKB-FSR] provider=fsr stage={} frame={} context=0x{} output={}x{} sdk=FAILED retained=true\n{}",
                    stage, frameCounter, Long.toHexString(address), outputWidth, outputHeight, LogPaths.stack(failure));
                throw failure;
            }
        });
    }

    /**
     * Proves host completion and releases all contexts, including FAILED state and pending execute failures.
     * The DLL stays loaded for the process lifetime.
     */
    public static synchronized void cleanup() {
        destroyRetired();
        context = releaseContext(context, "cleanup");
        contextDevice = null;
        nativeLib = null;
        activeProvider = null;
        failureReason = null;
        pendingDestroy = false;
        activeRequest = null;
        activeInput = null;
        state = State.IDLE;
    }
}
