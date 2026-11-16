package io.github.andrexarlay.vulkancompat.rt.world;

import io.github.andrexarlay.vulkanbridge.api.VulkanBridge;
import io.github.andrexarlay.vulkanbridge.api.VulkanBridge;
import io.github.andrexarlay.vulkanbridge.platform.LogPaths;
import io.github.andrexarlay.vulkancompat.rt.denoise.BridgeNrdLocator;
import io.github.andrexarlay.vulkancompat.rt.denoise.DenoiserConfig;
import io.github.andrexarlay.vulkancompat.rt.denoise.DenoiserMode;
import io.github.andrexarlay.vulkancompat.rt.denoise.DenoiserResolution;
import io.github.andrexarlay.vulkancompat.rt.denoise.DlssRrDenoiser;
import io.github.andrexarlay.vulkancompat.rt.denoise.NrdAvailability;
import io.github.andrexarlay.vulkancompat.rt.denoise.NrdDenoiser;
import io.github.andrexarlay.vulkancompat.rt.denoise.NrdException;
import io.github.andrexarlay.vulkancompat.rt.sodium.RtGeometryGate;
import io.github.andrexarlay.vulkancompat.rtx.WorldTargetManager;
import io.github.andrexarlay.vulkancompat.rtx.config.RtSettings;
import io.github.andrexarlay.vulkancompat.rtx.framegen.StreamlineBootstrap;
import io.github.andrexarlay.vulkancompat.rtx.raytracing.VulkanCapabilityChecker;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.dlss.DlssAvailability;
import org.lwjgl.vulkan.VkDevice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The denoiser half of {@link RtFrameRenderer}: which denoiser the session resolves to, the NRD and DLSS-RR instances,
 * their failure and drop flags, and the create/drop steps the frame runs at its boundaries. The frame renderer keeps
 * the order of those steps; this class owns the state they change.
 */
final class RtDenoiserLifecycle {
    private static final Logger LOGGER = LoggerFactory.getLogger("VKB-RT");

    // NRD denoiser (experimental, off by default): the gbuffer pipeline variant is used only while denoiseMode is an NRD mode.
    static DenoiserMode denoiseMode = DenoiserMode.OFF;
    static NrdAvailability denoiseAvailability;
    static NrdDenoiser denoiser;
    static boolean dropDenoiserNext = false;
    static boolean dropDenoiserByError = false; // true = NrdException, false = user toggled OFF
    // [DLSS-RR] DLSS Ray Reconstruction denoiser (experimental)
    static DlssRrDenoiser dlssRrDenoiser;
    static boolean dropDlssRrDenoiserNext = false;
    static boolean dropDlssRrByError = false;
    // A denoiser that failed to create or record is off for the rest of the session. The user's setting
    // (RtSettings.pathDenoiser, saved to bedray.toml) is never overwritten by a failure or by a startup flag.
    static boolean dlssRrFailed = false;
    static boolean nrdFailed = false;
    static String lastDenoiserLog = "";
    /** Last resolver outcome, including a reason when the request fell back or could not be selected. */
    static volatile String denoiserResolutionReason = "not_resolved";
    /** Last native create/record failure for the current world; retained for F3 after fallback/drop. */
    static volatile String denoiserRuntimeFailure;

    private RtDenoiserLifecycle() {
    }

    /**
     * RR owns output reconstruction this frame. Decided from intent (setting, library, session failure), not from whether
     * the RR object exists yet: the plan is frozen in beginWorldPhase, before prepareFrameResources creates RR, so the old
     * `dlssRrDenoiser != null` test made the first world frame, and every frame after a drop or creation retry, take the
     * two-stage chain - RR denoising at render size, then DLSS-SR stretching that resolved image again with zero jitter,
     * the blur the owner saw - and each flip resized the targets and reset RR's history. A frame on the combined plan
     * whose RR did not record presents the raw trace through RtOutputResolve instead. A permanent failure
     * (dlssRrFailed) switches the plan back to the upscaler once.
     */
    static boolean rrOutputReady() {
        return RtGeometryGate.enabled() && RtConfig.displayed() && RtConfig.effectiveMode() == RtConfig.MODE_LIT
            && RtConfig.mode() == RtMode.PATH_TRACED && rrIntended();
    }

    /** DLSS-RR will be the denoiser: same conditions as DenoiserResolution's DLSS_RR branch, without side effects. */
    static boolean rrIntended() {
        if (RtSettings.denoiser() != RtSettings.PathDenoiser.DLSS_RR || dlssRrFailed) return false;
        DlssAvailability.Library lib = DlssAvailability.library();
        if (lib == null || !lib.usable()) return false;
        boolean frameGenOwnsNgx = StreamlineBootstrap.isReady() && !StreamlineBootstrap.rrViaStreamline();
        return !frameGenOwnsNgx;
    }

    /** True when the NRD path is active for this session (selected at world creation from -Dvkrtx.denoiser). */
    static boolean nrdActive() {
        return denoiseMode.isNrd();
    }

    /** True only while an NRD instance is present and has not been scheduled for failure shutdown. */
    static boolean nrdRuntimeActive() {
        return denoiseMode.isNrd() && denoiser != null && !dropDenoiserNext;
    }

    // [DLSS-RR] True when DLSS Ray Reconstruction is active for this session
    static boolean dlssRrActive() {
        return denoiseMode == DenoiserMode.DLSS_RR;
    }

    /** True only while the DLSS-RR instance is present and has not been scheduled for failure shutdown. */
    static boolean dlssRrRuntimeActive() {
        return denoiseMode == DenoiserMode.DLSS_RR && dlssRrDenoiser != null && !dropDlssRrDenoiserNext;
    }

    /** Current instance state for diagnostics. pending-drop is kept separate from active. */
    static String denoiserRuntimeState() {
        if (dropDenoiserNext || dropDlssRrDenoiserNext) {
            return "pending-drop";
        }
        if (nrdRuntimeActive() || dlssRrRuntimeActive()) {
            return "active";
        }
        return "inactive";
    }

    /** Locates the NRD natives; an unreachable library set is an unavailable denoiser, not a failed frame. */
    private static void locateNrd() {
        try {
            denoiseAvailability = BridgeNrdLocator.locate(VulkanBridge.libraries());
        } catch (Throwable e) {
            denoiseAvailability = NrdAvailability.unavailable("Bedray NativeLibraries not available: " + LogPaths.show(e.getMessage()));
        }
    }

    /** Frame boundary: applies the drops the last frame scheduled, then resolves the denoiser this frame will run. */
    static void beginFrame(VkDevice device) {
        if (dropDenoiserNext) {
            dropDenoiser(device);
        }
        // [DLSS-RR]
        if (dropDlssRrDenoiserNext) {
            dropDlssRrDenoiser(device);
        }
        if (denoiseAvailability == null) {
            locateNrd();
        }
        denoiseMode = resolveDenoiseMode();
    }

    /** Once per world: locates the NRD natives and logs the denoiser that will be used. */
    static void selectDenoiser() {
        denoiserRuntimeFailure = null;
        denoiserResolutionReason = "not_resolved";
        locateNrd();
        lastDenoiserLog = "";
        // Apply the JVM startup flag (-Dvkrtx.denoiser / -Dvkrtx.dlss.rr) once at world load into the
        // persisted UI setting so that subsequent in-session UI changes are respected without re-applying
        // the flag every frame and locking the user out of the setting.
        if (!DenoiserConfig.automatic() && DenoiserConfig.configError() == null) {
            DenoiserMode flag = DenoiserConfig.requested();
            RtSettings.pathDenoiser = flag == DenoiserMode.DLSS_RR ? RtSettings.PathDenoiser.DLSS_RR
                : flag.isNrd() ? RtSettings.PathDenoiser.NRD : RtSettings.PathDenoiser.OFF;
        }
        denoiseMode = resolveDenoiseMode();
    }

    /**
     * Denoiser requested for this frame: reads the setting (RtSettings.denoiser()) and resolves it against the
     * libraries and session state through {@link DenoiserResolution}. The Slang pass supplies the seven DLSS-RR guide
     * channels and NRD's motion, so an active trace mode no longer disables the setting. Every change of the outcome
     * is logged once (rule 4).
     */
    static DenoiserMode resolveDenoiseMode() {
        RtSettings.PathDenoiser want = RtSettings.denoiser();
        boolean nrdUsable = denoiseAvailability != null && denoiseAvailability.usable();
        String nrdReason = denoiseAvailability == null ? "null" : denoiseAvailability.reason();
        boolean dlssUsable = false;
        String dlssReason = "null";
        boolean frameGenOwnsNgx = false;
        if (want == RtSettings.PathDenoiser.DLSS_RR) {
            DlssAvailability.Library lib = DlssAvailability.library();
            dlssUsable = lib != null && lib.usable();
            dlssReason = lib == null ? "null" : lib.reason();
            frameGenOwnsNgx = StreamlineBootstrap.isReady() && !StreamlineBootstrap.rrViaStreamline();
        }
        boolean rrOutputAllowed = !WorldTargetManager.isInWorldPhase() || WorldTargetManager.rrResolvesOutput()
            || Boolean.getBoolean("bedray.rt.disableRrOutput");
        DenoiserResolution.Result resolved = DenoiserResolution.resolve(want, dlssRrFailed, nrdFailed,
            dlssUsable, dlssReason, frameGenOwnsNgx, nrdUsable, nrdReason, DenoiserConfig.requested(), rrOutputAllowed);
        DenoiserMode mode = resolved.mode();
        String reason = resolved.reason();
        String line = "requested=" + want + " active=" + mode.logName() + ("setting".equals(reason) ? "" : " reason=" + reason);
        denoiserResolutionReason = reason;
        if (!line.equals(lastDenoiserLog)) {
            lastDenoiserLog = line;
            if (mode == DenoiserMode.OFF && want != RtSettings.PathDenoiser.OFF) {
                LOGGER.warn("[VKB-RT] denoiser {}", line);
            } else {
                LOGGER.info("[VKB-RT] denoiser {}", line);
            }
        }
        return mode;
    }

    // [DLSS-RR] Creates the DLSS-RR denoiser
    static void createDlssRrDenoiser(VkDevice device, int w, int h) {
        try {
            dlssRrDenoiser = new DlssRrDenoiser(device, RtFrameRenderer.worldMemory(), w, h, RtFrameRenderer.rrOutput(),
                RtFrameRenderer.pathSlangRuntime(VulkanCapabilityChecker.getVulkanDevice()));
        } catch (Exception e) {
            denoiserRuntimeFailure = "dlss_rr_init: " + LogPaths.show(e.getMessage());
            RtMemory.Img output = RtFrameRenderer.frameOutput();
            LOGGER.error("[DLSS-RR] status=disabled stage=init profile=dlss_rr extent={}x{} outputView=0x{} reason={} (falling back to NRD if available)\n{}",
                w, h, Long.toUnsignedString(output != null ? output.view : 0L, 16), LogPaths.show(e.getMessage()), LogPaths.stack(e));
            dlssRrFailed = true;
            if (!nrdFailed && denoiseAvailability != null && denoiseAvailability.usable()) {
                LOGGER.warn("[DLSS-RR] Falling back to NRD_REBLUR (keeping G-Buffer pipeline)");
                denoiseMode = DenoiserMode.NRD_REBLUR;
                createDenoiser(device, w, h);
            } else {
                denoiseMode = DenoiserMode.OFF;
                RtFrameRenderer.requestPassRebuild();
            }
        }
    }

    // [DLSS-RR] Drops the DLSS-RR denoiser
    static void dropDlssRrDenoiser(VkDevice device) {
        if (dlssRrDenoiser != null) {
            RtFrameRenderer.drainHostSubmissions(device, "drop_rr_drain");
            dlssRrDenoiser.destroy();
            dlssRrDenoiser = null;
        }
        dropDlssRrDenoiserNext = false;
        if (dropDlssRrByError) {
            dlssRrFailed = true;
        }
        dropDlssRrByError = false;
        if (!nrdFailed && denoiseAvailability != null && denoiseAvailability.usable()) {
            LOGGER.warn("[DLSS-RR] Dropping DLSS-RR, falling back to NRD_REBLUR (keeping G-Buffer pipeline)");
            denoiseMode = DenoiserMode.NRD_REBLUR;
        } else {
            denoiseMode = DenoiserMode.OFF;
            RtFrameRenderer.requestPassRebuild();
        }
    }

    static void createDenoiser(VkDevice device, int w, int h) {
        try {
            denoiser = new NrdDenoiser(device, RtFrameRenderer.worldMemory(), denoiseMode, denoiseAvailability, w, h,
                RtFrameRenderer.frameOutput().view, RtFrameRenderer.pathSlangRuntime(VulkanCapabilityChecker.getVulkanDevice()));
        } catch (NrdException | RtFailure e) {
            denoiserRuntimeFailure = "nrd_init: " + LogPaths.show(e.getMessage());
            RtMemory.Img output = RtFrameRenderer.frameOutput();
            LOGGER.error("[VKB-NRD] status=disabled stage=init profile={} extent={}x{} outputView=0x{} reason={} (off for this session, setting kept)\n{}",
                denoiseMode.logName(), w, h, Long.toUnsignedString(output != null ? output.view : 0L, 16), LogPaths.show(e.getMessage()), LogPaths.stack(e));
            nrdFailed = true;
            denoiseMode = DenoiserMode.OFF;
            RtFrameRenderer.requestPassRebuild();
        }
    }

    static void dropDenoiser(VkDevice device) {
        dropDenoiserNext = false;
        if (denoiser != null) {
            RtFrameRenderer.drainHostSubmissions(device, "drop_nrd_drain");
            denoiser.destroy();
            denoiser = null;
        }
        if (dropDenoiserByError) {
            nrdFailed = true;
        }
        dropDenoiserByError = false;
        denoiseMode = DenoiserMode.OFF;
        RtFrameRenderer.requestPassRebuild();
    }
}
