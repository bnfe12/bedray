package io.github.andrexarlay.vulkancompat.rt.world;

import io.github.andrexarlay.vulkanbridge.platform.LogPaths;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import io.github.andrexarlay.vulkanbridge.api.VulkanBridge;
import io.github.andrexarlay.vulkancompat.rt.denoise.BridgeNrdLocator;
import io.github.andrexarlay.vulkancompat.rt.denoise.DenoiserConfig;
import io.github.andrexarlay.vulkancompat.rt.denoise.DenoiserMode;
import io.github.andrexarlay.vulkancompat.rt.denoise.DenoiserResolution;
import io.github.andrexarlay.vulkancompat.rt.denoise.DlssRrDenoiser;
import io.github.andrexarlay.vulkancompat.rt.denoise.NrdAvailability;
import io.github.andrexarlay.vulkancompat.rt.denoise.NrdDenoiser;
import io.github.andrexarlay.vulkancompat.rt.denoise.NrdException;
import io.github.andrexarlay.vulkancompat.rt.lifetime.RecordingCompletion.Retirement;
import io.github.andrexarlay.vulkancompat.rt.slang.SlangRuntime;
import io.github.andrexarlay.vulkancompat.rt.sodium.RtGeometryGate;
import io.github.andrexarlay.vulkancompat.rtx.config.RtSettings;
import io.github.andrexarlay.vulkancompat.rtx.WorldTargetManager;
import io.github.andrexarlay.vulkancompat.rtx.framegen.FrameGenGuides;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.VkBarriers;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.dlss.DlssAvailability;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDevice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Owns the temporal denoiser cluster: the resolved mode, the NRD and DLSS-RR instances and their session-failure /
 * pending-drop flags, the selection and resolution table, creation and fallback, and the per-recording prepare / proof /
 * record of whichever denoiser is active. The renderer borrows the instances for the G-buffer views and the depth /
 * motion accessors; everything that creates, drops or records a denoiser lives here.
 */
final class RtDenoiserLifecycle {
    private static final Logger LOGGER = LoggerFactory.getLogger("VKB-RT");

    /** The frame state and host proofs a denoiser needs; the renderer is the single source of each. */
    interface Context {
        RtMemory memory();
        RtMemory.Img frameOutput();
        RtMemory.Img rrOutput();
        SlangRuntime pathSlangRuntime();
        void requestPlainPipelineRebuild();
        void drainHostSubmissions(VkDevice device, String stage);
    }

    /** Outcome of recording the active denoiser: whether RR resolved the colour, and the FG guides it published. */
    record Recording(boolean rrColorResolved, FrameGenGuides pendingFgGuides) {}

    private final Context context;
    private DenoiserMode denoiseMode = DenoiserMode.OFF;
    private NrdAvailability denoiseAvailability;
    private NrdDenoiser denoiser;
    private boolean dropDenoiserNext = false;
    private boolean dropDenoiserByError = false; // true = NrdException, false = user toggled OFF
    // [DLSS-RR] DLSS Ray Reconstruction denoiser (experimental)
    private DlssRrDenoiser dlssRrDenoiser;
    private boolean dropDlssRrDenoiserNext = false;
    private boolean dropDlssRrByError = false;
    // A denoiser that failed to create or record is off for the rest of the session. The user's setting
    // (RtSettings.pathDenoiser, saved to bedray.toml) is never overwritten by a failure or by a startup flag.
    private boolean dlssRrFailed = false;
    private boolean nrdFailed = false;
    private String lastDenoiserLog = "";
    /** Last resolver outcome, including a reason when the request fell back or could not be selected. */
    private volatile String denoiserResolutionReason = "not_resolved";
    /** Last native create/record failure for the current world; retained for F3 after fallback/drop. */
    private volatile String denoiserRuntimeFailure;
    /** Set when this frame's RT colour was resolved by a same-resolution temporal denoiser (NRD / DLSS-RR). */
    private volatile boolean frameColorTemporallyResolved = false;
    /** NRD was chosen automatically (no -Dvkrtx.denoiser): only the path tracer uses it. */
    private boolean nrdPathOnly;

    RtDenoiserLifecycle(Context context) { this.context = context; }

    DenoiserMode mode() { return denoiseMode; }
    NrdDenoiser nrd() { return denoiser; }
    DlssRrDenoiser rr() { return dlssRrDenoiser; }
    boolean nrdNeedsEnsure(int w, int h, long outputView) { return denoiser != null && denoiser.needsEnsure(w, h, outputView); }
    boolean rrNeedsEnsure(int w, int h, int outW, int outH, long outputImage, long outputView) {
        return dlssRrDenoiser != null && dlssRrDenoiser.needsEnsure(w, h, outW, outH, outputImage, outputView);
    }

    /** A live RR instance can own output reconstruction next frame. FG keeps its existing producer path. */
    boolean rrOutputReady() {
        return RtGeometryGate.enabled() && RtConfig.displayed() && RtConfig.effectiveMode() == RtConfig.MODE_LIT
            && RtConfig.mode() == RtMode.PATH_TRACED && RtSettings.denoiser() == RtSettings.PathDenoiser.DLSS_RR
            && dlssRrDenoiser != null && !dropDlssRrDenoiserNext && !dlssRrFailed;
    }

    boolean nrdActive() { return denoiseMode.isNrd(); }
    boolean nrdRuntimeActive() { return denoiseMode.isNrd() && denoiser != null && !dropDenoiserNext; }
    boolean dlssRrActive() { return denoiseMode == DenoiserMode.DLSS_RR; }
    boolean dlssRrRuntimeActive() { return denoiseMode == DenoiserMode.DLSS_RR && dlssRrDenoiser != null && !dropDlssRrDenoiserNext; }
    String denoiserResolutionReason() { return denoiserResolutionReason; }
    String denoiserRuntimeFailure() { return denoiserRuntimeFailure; }
    String denoiserRuntimeState() {
        if (dropDenoiserNext || dropDlssRrDenoiserNext) {
            return "pending-drop";
        }
        if (nrdRuntimeActive() || dlssRrRuntimeActive()) {
            return "active";
        }
        return "inactive";
    }
    boolean frameColorTemporallyResolved() { return frameColorTemporallyResolved; }
    void resetFrameColorTemporallyResolved() { frameColorTemporallyResolved = false; }

    void dropPending(VkDevice device) {
        if (dropDenoiserNext) {
            dropDenoiser(device);
        }
        // [DLSS-RR]
        if (dropDlssRrDenoiserNext) {
            dropDlssRrDenoiser(device);
        }
    }

    /** Per-frame availability locate (once) and resolution of the requested denoiser against the libraries. */
    void resolveFrame() {
        if (denoiseAvailability == null) {
            try {
                denoiseAvailability = BridgeNrdLocator.locate(VulkanBridge.libraries());
            } catch (Throwable t) {
                denoiseAvailability = NrdAvailability.unavailable("Bedray NativeLibraries not available: " + LogPaths.show(t.getMessage()));
            }
        }
        denoiseMode = resolveDenoiseMode();
    }

    /** Once per world: locates the NRD natives and logs the denoiser that will be used. */
    void selectDenoiser() {
        denoiserRuntimeFailure = null;
        denoiserResolutionReason = "not_resolved";
        try {
            denoiseAvailability = BridgeNrdLocator.locate(VulkanBridge.libraries());
        } catch (Throwable e) {
            denoiseAvailability = NrdAvailability.unavailable("Bedray NativeLibraries not available: " + LogPaths.show(e.getMessage()));
        }
        nrdPathOnly = false;
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
    private DenoiserMode resolveDenoiseMode() {
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
            frameGenOwnsNgx = io.github.andrexarlay.vulkancompat.rtx.framegen.StreamlineBootstrap.isReady()
                && !io.github.andrexarlay.vulkancompat.rtx.framegen.StreamlineBootstrap.rrViaStreamline();
        }
        DenoiserResolution.Result resolved = DenoiserResolution.resolve(want, dlssRrFailed, nrdFailed,
            dlssUsable, dlssReason, frameGenOwnsNgx, nrdUsable, nrdReason, DenoiserConfig.requested());
        DenoiserMode mode = resolved.mode();
        String reason = resolved.reason();
        String line = "requested=" + want + " active=" + mode.logName() + (mode == DenoiserMode.OFF && want != RtSettings.PathDenoiser.OFF ? " reason=" + reason : "");
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
    void createDlssRrDenoiser(VkDevice device, int w, int h) {
        try {
            dlssRrDenoiser = new DlssRrDenoiser(device, context.memory(), w, h, context.rrOutput(),
                context.pathSlangRuntime());
        } catch (Exception e) {
            denoiserRuntimeFailure = "dlss_rr_init: " + LogPaths.show(e.getMessage());
            LOGGER.error("[DLSS-RR] status=disabled stage=init profile=dlss_rr extent={}x{} outputView=0x{} reason={} (falling back to NRD if available)\n{}",
                w, h, Long.toUnsignedString(context.frameOutput() != null ? context.frameOutput().view : 0L, 16), LogPaths.show(e.getMessage()), LogPaths.stack(e));
            dlssRrFailed = true;
            if (!nrdFailed && denoiseAvailability != null && denoiseAvailability.usable()) {
                LOGGER.warn("[DLSS-RR] Falling back to NRD_REBLUR (keeping G-Buffer pipeline)");
                denoiseMode = DenoiserMode.NRD_REBLUR;
                createDenoiser(device, w, h);
            } else {
                denoiseMode = DenoiserMode.OFF;
                rebuildPlainPipeline(device);
            }
        }
    }

    // [DLSS-RR] Drops the DLSS-RR denoiser
    void dropDlssRrDenoiser(VkDevice device) {
        if (dlssRrDenoiser != null) {
            context.drainHostSubmissions(device, "drop_rr_drain");
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
            rebuildPlainPipeline(device);
        }
    }

    void createDenoiser(VkDevice device, int w, int h) {
        try {
            denoiser = new NrdDenoiser(device, context.memory(), denoiseMode, denoiseAvailability, w, h, context.frameOutput().view,
                context.pathSlangRuntime());
        } catch (NrdException | RtFailure e) {
            denoiserRuntimeFailure = "nrd_init: " + LogPaths.show(e.getMessage());
            LOGGER.error("[VKB-NRD] status=disabled stage=init profile={} extent={}x{} outputView=0x{} reason={} (off for this session, setting kept)\n{}",
                denoiseMode.logName(), w, h, Long.toUnsignedString(context.frameOutput() != null ? context.frameOutput().view : 0L, 16), LogPaths.show(e.getMessage()), LogPaths.stack(e));
            nrdFailed = true;
            denoiseMode = DenoiserMode.OFF;
            rebuildPlainPipeline(device);
        }
    }

    void dropDenoiser(VkDevice device) {
        dropDenoiserNext = false;
        if (denoiser != null) {
            context.drainHostSubmissions(device, "drop_nrd_drain");
            denoiser.destroy();
            denoiser = null;
        }
        if (dropDenoiserByError) {
            nrdFailed = true;
        }
        dropDenoiserByError = false;
        denoiseMode = DenoiserMode.OFF;
        rebuildPlainPipeline(device);
    }

    private void rebuildPlainPipeline(VkDevice device) {
        // May be requested while a local command buffer is being recorded.
        // Rebuild only at the next frame boundary, before allocating that buffer.
        context.requestPlainPipelineRebuild();
    }

    /** Retires the instance the resolved mode no longer selects; a switch proves host completion before destruction. */
    void retireForMode(VkDevice device, DenoiserMode denoiseMode) {
        boolean retireRr = denoiseMode != DenoiserMode.DLSS_RR && dlssRrDenoiser != null;
        boolean retireNrd = (!denoiseMode.isNrd() || (denoiser != null && denoiser.mode() != denoiseMode)) && denoiser != null;
        if (retireRr || retireNrd) context.drainHostSubmissions(device, "denoiser_switch_drain");
        if (retireRr) {
            dlssRrDenoiser.destroy();
            dlssRrDenoiser = null;
        }
        if (retireNrd) {
            denoiser.destroy();
            denoiser = null;
        }
    }

    /** Captures the active instance and pins its guide-layout initialization to this recording before it can emit commands. */
    void prepareRecording(VkCommandBuffer cmd, Retirement[] denoiserProof) {
        if (denoiseMode == DenoiserMode.DLSS_RR && dlssRrDenoiser != null) {
            DlssRrDenoiser owner = dlssRrDenoiser;
            denoiserProof[0] = new Retirement(() -> owner.releaseRecording(cmd.address()),
                () -> owner.discardRecording(cmd.address()));
            owner.prepare(cmd);
        }
        if (denoiseMode.isNrd() && denoiser != null) {
            NrdDenoiser owner = denoiser;
            denoiserProof[0] = new Retirement(() -> owner.releaseRecording(cmd.address()),
                () -> owner.discardRecording(cmd.address()));
            owner.prepare(cmd);
        }
    }

    /** Records the active denoiser after the primary dispatch; a failed RR recording discards the whole command buffer. */
    Recording record(VkCommandBuffer cmd, VulkanDevice vulkanDevice, CameraRenderState camera, RenderTarget target,
                     RtPass pass, RtFrameParams params, org.joml.Matrix4f frameProjection,
                     RrHardwareDepth rrHardwareDepth, boolean useDenoiser) {
        boolean rrColorResolved = false;
        FrameGenGuides pendingFgGuides = null;
        if (useDenoiser) {
            if (dlssRrDenoiser != null) {
                // [DLSS-RR] Record DLSS Ray Reconstruction
                try {
                    // The frame's own exposure, decided once above: the pack's tonemap and the path display cannot diverge.
                    float composeExposure = params.exposure;
                    long depthView = (dlssRrDenoiser != null && dlssRrDenoiser.gbViewZ() != null)
                        ? dlssRrDenoiser.gbViewZ().view
                        : (target != null ? WorldTargetManager.extractVkImageView(target.getDepthTextureView()) : 0L);
                    DlssRrDenoiser owner = dlssRrDenoiser;
                    if (!(pass instanceof RtSlangPathPass slang) || rrHardwareDepth == null)
                        throw new IllegalStateException("RR hardware depth requires the current Slang primary producer");
                    // The published primary sample is authoritative, including non-upscaler fallback sequences.
                    float rrJitterX = slang.guideFrame().jitter.x;
                    float rrJitterY = slang.guideFrame().jitter.y;
                    rrHardwareDepth.record(cmd, slang.guideFrame(), owner.gbViewZ(), owner.hardwareDepth());
                    owner.hardwareDepthRecorded(cmd.address());
                    VkBarriers.fullMemoryBarrier(cmd);
                    frameColorTemporallyResolved = owner.record(cmd, frameProjection, camera.viewRotationMatrix, camera.pos.x, camera.pos.y, camera.pos.z,
                        params.tonemap, composeExposure, depthView, rrJitterX, rrJitterY, camera.projectionMatrix);
                    rrColorResolved = frameColorTemporallyResolved;
                    if (rrColorResolved && WorldTargetManager.rrResolvesOutput())
                        pendingFgGuides = owner.frameGenGuides(
                            io.github.andrexarlay.vulkancompat.rtx.frame.WorldFrames.stampOrNull(), vulkanDevice,
                            slang.guideFrame().jitter.x, slang.guideFrame().jitter.y);
                    VkBarriers.fullMemoryBarrier(cmd);
                } catch (Exception e) {
                    if (e instanceof io.github.andrexarlay.vulkancompat.rt.core.BedrayCoreException nativeFailure)
                        throw nativeFailure; // partial coarse recording requires whole-command discard
                    if (e instanceof io.github.andrexarlay.vulkancompat.rt.denoise.RrFeatureCreationRetry) {
                        LOGGER.warn("[DLSS-RR] creation_retry whole_recording=discarded next_frame=quality_fallback reason={}", LogPaths.show(e.getMessage()));
                        throw new RtDenoiserFrameDiscard("DLSS-RR creation retry requires a fresh recording", e);
                    }
                    denoiserRuntimeFailure = "dlss_rr_record: " + LogPaths.show(e.getMessage());
                    LOGGER.error("[DLSS-RR] FAILED record: {} (whole recording discarded; denoiser fallback next frame)\n{}",
                        LogPaths.show(e.getMessage()), LogPaths.stack(e));
                    dropDlssRrDenoiserNext = true;
                    dropDlssRrByError = true;
                    throw new RtDenoiserFrameDiscard("DLSS-RR recording failed before submission", e);
                }
            } else if (denoiser != null) {
                try {
                    // The frame's own exposure, decided once above: the pack's tonemap and the path display cannot diverge.
                    float composeExposure = params.exposure;
                    boolean nrdJittered = io.github.andrexarlay.vulkancompat.rtx.upscaler.CameraJitterManager.isJitterActive()
                        && io.github.andrexarlay.vulkancompat.rtx.upscaler.CameraJitterManager.isActive()
                        && io.github.andrexarlay.vulkancompat.rtx.upscaler.CameraJitterManager.isScalingActive();
                    float nrdJitterX = nrdJittered
                        ? io.github.andrexarlay.vulkancompat.rtx.upscaler.CameraJitterManager.getCurrentPixelJitterX() : 0.0f;
                    float nrdJitterY = nrdJittered
                        ? io.github.andrexarlay.vulkancompat.rtx.upscaler.CameraJitterManager.getCurrentPixelJitterY() : 0.0f;
                    float nrdJitterPrevX = nrdJittered
                        ? io.github.andrexarlay.vulkancompat.rtx.upscaler.CameraJitterManager.getPrevPixelJitterX() : 0.0f;
                    float nrdJitterPrevY = nrdJittered
                        ? io.github.andrexarlay.vulkancompat.rtx.upscaler.CameraJitterManager.getPrevPixelJitterY() : 0.0f;
                    NrdDenoiser owner = denoiser;
                    owner.record(cmd, camera.projectionMatrix, frameProjection, camera.viewRotationMatrix,
                        camera.pos.x, camera.pos.y, camera.pos.z, params.tonemap, composeExposure,
                        nrdJitterX, nrdJitterY, nrdJitterPrevX, nrdJitterPrevY);
                    frameColorTemporallyResolved = true;
                    VkBarriers.fullMemoryBarrier(cmd);
                } catch (NrdException e) {
                    denoiserRuntimeFailure = "nrd_record " + e.stage() + ": " + LogPaths.show(e.getMessage());
                    // The command buffer stays valid (vkb_nrd validates before recording); the raw image is shown this frame and the
                    // denoiser is dropped at the start of the next one.
                    LOGGER.error("[VKB-NRD] FAILED stage={} {} (denoiser disabled for this session, raw RT image is shown)\n{}",
                        e.stage(), LogPaths.show(e.getMessage()), LogPaths.stack(e));
                    dropDenoiserNext = true;
                    dropDenoiserByError = true;
                }
            }
        }
        return new Recording(rrColorResolved, pendingFgGuides);
    }

    void resetHistory() {
        if (denoiser != null) {
            denoiser.resetHistory();
        }
        if (dlssRrDenoiser != null) {
            dlssRrDenoiser.resetHistory();
        }
    }

    void destroyDenoisers() {
        if (denoiser != null) {
            denoiser.destroy();
            denoiser = null;
        }
        if (dlssRrDenoiser != null) {
            dlssRrDenoiser.destroy();
            dlssRrDenoiser = null;
        }
    }

    void resetAfterShutdown() {
        dropDlssRrDenoiserNext = false;
        dropDenoiserNext = false;
    }
}