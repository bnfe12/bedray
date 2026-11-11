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

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
    private static final Map<Path, FfxNative> LOADED_LIBRARIES = new HashMap<>();
    private static VulkanDevice enumeratedDevice;
    private static FfxNative enumeratedLibrary;
    private static List<FfxNative.ProviderVersion> versions = List.of();
    private static String failureReason;
    private static FfxNative.Context context;
    private static VulkanDevice contextDevice;
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

    public static synchronized String failureReason() { return failureReason; }

    private static FsrLibrary.Library libraryForUpscaling() {
        return FsrDeviceRequirements.modernRegistered() ? FsrLibrary.upscalerLibrary() : FsrLibrary.library();
    }

    private static FfxNative.VulkanHandles handles(VulkanDevice device) {
        var vk = device.vkDevice();
        return new FfxNative.VulkanHandles(vk.address(), vk.getPhysicalDevice().address(),
            vk.getCapabilities().vkGetDeviceProcAddr);
    }

    private static FfxNative loadLibrary(FsrLibrary.Library library) {
        return LOADED_LIBRARIES.computeIfAbsent(library.dll(), FfxNative::load);
    }

    private static List<FfxNative.ProviderVersion> enumerate(FfxNative lib, VulkanDevice device) {
        if (enumeratedDevice != device || enumeratedLibrary != lib) {
            var handles = handles(device);
            if (handles.getDeviceProcAddr() == 0L) return List.of();
            var result = lib.upscalerVersions(handles);
            if (result.result() != FfxNative.RETURN_OK) {
                enumeratedDevice = device;
                enumeratedLibrary = lib;
                versions = List.of();
                LOGGER.warn("[VKB-FSR] provider query failed sdk_return={}", result.result());
                return versions;
            }
            boolean mlEnabled = FsrDeviceRequirements.mlDeviceEnabled(device);
            versions = result.value().stream().filter(version -> !FsrVersion.FSR_4.matches(version.name())
                || (lib.modern() && mlEnabled)).toList();
            enumeratedDevice = device;
            enumeratedLibrary = lib;
            LOGGER.info("[VKB-FSR] providers reported={} eligible={} ml_device_features={}", result.value(), versions, mlEnabled);
        }
        return versions;
    }

    /** UI inspection never changes the DLL that owns a live or retiring context. */
    public static synchronized List<FfxNative.ProviderVersion> availableVersions() {
        VulkanDevice device = VulkanCapabilityChecker.getVulkanDevice();
        FsrLibrary.Library library = libraryForUpscaling();
        if (device == null || !library.usable()) return List.of();
        try {
            return enumerate(loadLibrary(library), device);
        } catch (FsrException e) {
            LOGGER.warn("[VKB-FSR] provider lookup failed {}", LogPaths.show(e.getMessage()));
            return List.of();
        }
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
        FsrLibrary.Library lib = libraryForUpscaling();
        if (request.version() == FsrVersion.FSR_4) {
            var modern = FsrLibrary.modernLibrary();
            if (!modern.usable()) {
                fail("fsr4_library", modern.reason());
                return;
            }
            if (!FsrDeviceRequirements.modernRegistered()) {
                fail("fsr4_restart", "restart to enable the SDK 2.4 Vulkan device features");
                return;
            }
            if (!FsrDeviceRequirements.mlDeviceEnabled(device)) {
                fail("fsr4_device_features", "the logical Vulkan device lacks the FSR 4 ML feature group");
                return;
            }
        }
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
        FfxNative selectedLibrary = loadLibrary(lib);
        List<FfxNative.ProviderVersion> available = enumerate(selectedLibrary, device);
        if (available.isEmpty()) {
            fail("versions", "no eligible upscaler provider reported by " + LogPaths.show(lib.dll()));
            return;
        }
        Optional<FfxNative.ProviderVersion> picked = request.version().pick(available);
        if (request.version() == FsrVersion.AUTO && selectedLibrary.modern()) {
            // Pin an eligible provider to the features Minecraft enabled on this device.
            for (FsrVersion family : List.of(FsrVersion.FSR_4, FsrVersion.FSR_3_1, FsrVersion.FSR_2)) {
                picked = family.pick(available);
                if (picked.isPresent()) break;
            }
        }
        if ((request.version() != FsrVersion.AUTO || selectedLibrary.modern()) && picked.isEmpty()) {
            fail("version", "requested=" + request.version() + " eligible=" + available);
            return;
        }
        long overrideId = picked.map(FfxNative.ProviderVersion::id).orElse(0L);
        var vk = handles(device);
        int quality = request.quality().nativeValue();

        FfxNative.QueryResult<FfxNative.Size> rr = selectedLibrary.renderResolution(winW, winH, quality, overrideId, vk);
        FfxNative.QueryResult<Float> ratio = selectedLibrary.upscaleRatio(quality, overrideId, vk);
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
            if (picked.filter(provider -> FsrVersion.FSR_4.matches(provider.name())).isPresent()) {
                var minimum = selectedLibrary.renderResolution(winW, winH, FsrQuality.ULTRA_PERFORMANCE.nativeValue(), overrideId, vk);
                if (minimum.result() != FfxNative.RETURN_OK || minimum.value().width() <= 0 || minimum.value().height() <= 0) {
                    fail("custom_scale_limits", "sdk_return=" + minimum.result());
                    return;
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
        if (vk.getDeviceProcAddr() == 0L) {
            fail("create_context", "vkGetDeviceProcAddr address is 0");
            return;
        }
        long t0 = System.nanoTime();
        FfxNative.ContextResult created = selectedLibrary.createUpscaleContext(flags, render.width(), render.height(), winW, winH,
            vk.device(), vk.physicalDevice(), vk.getDeviceProcAddr(), overrideId);
        long ms = (System.nanoTime() - t0) / 1_000_000L;
        if (created.result() != FfxNative.RETURN_OK || created.context().handle() == 0L) {
            fail("create_context", String.valueOf(created.result()));
            return;
        }
        nativeLib = selectedLibrary;
        context = created.context();
        contextDevice = device;
        FfxNative.QueryResult<FfxNative.ProviderVersion> pv = nativeLib.providerVersion(context);
        activeProvider = pv.result() == FfxNative.RETURN_OK ? pv.value().name() : "unknown(result=" + pv.result() + ")";
        LOGGER.info("[VKB-FSR] [VKB-AUTOTEST] fsr=context_created result=0 provider={} requested_version={} override=0x{} flags=0x{} max_render={} output={}x{} camera={} ms={}",
            activeProvider, request.version(), Long.toHexString(overrideId), Integer.toHexString(flags), render, winW, winH, cam, ms);
        if (picked.isPresent() && !picked.get().name().equals(activeProvider)) {
            fail("provider_mismatch", "requested " + picked.get() + " got " + activeProvider);
            return;
        }
        if (FsrVersion.FSR_4.matches(activeProvider)) {
            var required = nativeLib.requiredInputs(context);
            if (required.result() != FfxNative.RETURN_OK || (required.value() & ~FfxNative.PROVIDED_INPUTS) != 0L) {
                fail("resource_requirements", "sdk_return=" + required.result() + " required=0x" + Long.toHexString(required.value()));
                return;
            }
        }
        activeRequest = request;
        activeCustom = pendingCustom;
        activeInput = render;
        activeFlags = flags;
        outputWidth = winW;
        outputHeight = winH;
        initEpoch++;
        failureReason = null;
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
        failureReason = "dispatch: " + result;
        activeProvider = null;
        activeRequest = null;
        activeInput = null;
        state = State.FAILED;
    }

    private static void destroyPendingContext() {
        context = releaseContext(context, "destroy_after_execute_failure");
        pendingDestroy = false;
    }

    private static void fail(String stage, String result) {
        LOGGER.error("[VKB-FSR] [VKB-AUTOTEST] fsr=FAILED stage={} result={}", stage, result);
        failureReason = stage + ": " + result;
        activeProvider = null;
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
        pendingDestroy = false;
        activeRequest = null;
        activeInput = null;
        activeProvider = null;
        failureReason = null;
        state = State.IDLE;
    }
}
