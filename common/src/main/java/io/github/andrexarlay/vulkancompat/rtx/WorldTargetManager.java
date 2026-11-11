package io.github.andrexarlay.vulkancompat.rtx;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTexture;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTextureView;
import io.github.andrexarlay.vulkanbridge.api.VulkanBridge;
import io.github.andrexarlay.vulkancompat.rt.lifetime.RecordingCompletion;
import io.github.andrexarlay.vulkancompat.rt.world.RtFrameRenderer;
import io.github.andrexarlay.vulkancompat.rt.lifetime.RecordingCompletion.Retirement;
import io.github.andrexarlay.vulkancompat.rtx.config.VulkanRtxConfig;
import io.github.andrexarlay.vulkancompat.rtx.raytracing.VulkanCapabilityChecker;
import io.github.andrexarlay.vulkancompat.rtx.framegen.FrameGenRuntime;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.CameraJitterManager;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.FrameContext;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.IUpscalerBackend;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.UpscalerBackend;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.UpscalerLibraryLoader;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.VelocityPipeline;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.VkBarriers;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.TemporalUpscaler;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.spatial.SpatialUpscaler;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.dlss.DlssNeuralRendering;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.xess.XessRuntime;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.SkyRenderer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.Optional;

/**
 * Manages dedicated low-resolution 3D world render targets, motion vectors target,
 * camera tracking, sub-pixel jitter, and full-screen upscale blitting into the native-resolution
 * main render target.
 * <p>
 * Eliminates scissor bounds crashes (known pitfall: scissor bounds) under resolution scaling by isolating
 * the 3D world phase to separate low-res targets while preserving 100% native resolution
 * for window, mainRenderTarget, and GUI rendering.
 */
public final class WorldTargetManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("Bedray/WorldTargetManager");

    private static TextureTarget worldTarget;
    private static TextureTarget worldHud3d;
    private static TextureTarget motionVectorsTarget;
    /** Copy of the world depth taken before the hand pass (XeSS depth input, velocity input). */
    private static TextureTarget depthCopy;
    private static boolean depthCopiedThisFrame = false;
    private static int lastXessEpoch = -1;

    private static RenderTarget savedMainRenderTarget;
    private static RenderTarget savedHud3DTarget;

    private static final io.github.andrexarlay.vulkancompat.rtx.upscaler.VelocityRecordingOwner velocityRecordings =
        new io.github.andrexarlay.vulkancompat.rtx.upscaler.VelocityRecordingOwner(
            failure -> LOGGER.warn("[WorldTargetManager] temporal retirement failed; resources retained", failure));
    private static final DlssNeuralRendering dlssNeural = new DlssNeuralRendering(velocityRecordings);
    private static int lastDlssNeuralIntensity;
    private static final RecordingCompletion spatialCompletion = new RecordingCompletion(
        failure -> LOGGER.warn("[WorldTargetManager] spatial retirement failed; resources retained", failure));
    private static boolean recordingSpatial, drainingSpatial;

    /**
     * The dedicated worldTarget colour texture is ALWAYS created with STORAGE usage (see {@link TargetLifecyclePolicy}), so
     * toggling Hardware RT only changes the execution policy and never replaces the published Java object.
     */
    private static boolean worldTargetHasStorage = false;
    /** Last Hardware RT state seen at the frame start; a change is logged and invalidates the RT binding. */
    private static Boolean lastRtEnabled = null;
    /** identityHashCode of the SkyRenderer target last logged (-1 = nothing logged yet). */
    private static int lastLoggedSkyTargetId = -1;
    /** Read by MixinVulkanConst: the format whose texture, created right now, must get STORAGE usage (null = none). */
    private static GpuFormat storageUsageFormat = null;

    /** XeSS output image (window size, RGBA8, STORAGE) that is copied into the full-size main target before the GUI. */
    private static TextureTarget xessOutput;

    // Temporal camera & projection tracking
    private static Matrix4f currProjMatrix;
    private static Matrix4f prevProjMatrix;
    private static Matrix4f currViewRotationMatrix;
    private static Matrix4f prevViewRotationMatrix;
    private static Vec3 currCameraPos;
    private static Vec3 prevCameraPos;

    /** A/B diagnostic: {@code -Dvkrtx.velocity.legacyRtDepth=true} reads the RT depth as hardware depth (pre-fix behaviour). */
    private static final boolean RT_DEPTH_LEGACY = Boolean.getBoolean("vkrtx.velocity.legacyRtDepth");
    private static final Matrix4f invViewProj = new Matrix4f();
    private static final Matrix4f prevViewProj = new Matrix4f();

    private static boolean resetHistory = true;
    private static boolean lastColorProducerRt;
    private static boolean colorProducerChanged;
    /** True only when {@link #computeMatrices()} accepted this frame's camera; history advances only then. */
    private static boolean cameraMatricesValidThisFrame = false;
    private static long lastDegenerateCameraLogNanos = 0L;
    private static boolean inWorldPhase = false;
    /**
     * The hand is drawn into the low-resolution world target without motion vectors and with a depth buffer of its own,
     * so a temporal upscaler consumes it as background (stretched arm). It is deferred to full resolution instead.
     */
    private static boolean handPassDeferred = false;
    private static Boolean lastLoggedHandDeferral = null;

    private static int lastLogW = -1;
    private static int lastLogH = -1;
    private static int lastLogWinW = -1;
    private static int lastLogWinH = -1;

    private WorldTargetManager() {}
    private static io.github.andrexarlay.vulkancompat.rtx.upscaler.WorldResolutionPlan resolutionPlan;

    private static io.github.andrexarlay.vulkancompat.rtx.frame.WorldFrame.Stamp resolutionStamp;
    public static boolean rrOutputThisFrame() {
        return resolutionStamp != null && io.github.andrexarlay.vulkancompat.rtx.frame.WorldFrames.isCurrent(resolutionStamp)
            && resolutionPlan != null && resolutionPlan.rayReconstructionOutput();
    }

    public static boolean temporalGuidesRequested() {
        return TemporalUpscaler.isActive() || FrameGenRuntime.usable();
    }

    public static String dlssNeuralFailure() { return dlssNeural.failureReason(); }

    public static boolean rrResolvesOutput() { return inWorldPhase && resolutionPlan != null && resolutionPlan.rayReconstructionOutput(); }
    public static int traceWidth(int fallback) { return rrResolvesOutput() ? resolutionPlan.traceWidth() : fallback; }
    public static int traceHeight(int fallback) { return rrResolvesOutput() ? resolutionPlan.traceHeight() : fallback; }
    public static int presentationWidth(int fallback) { return rrResolvesOutput() ? resolutionPlan.worldWidth() : fallback; }
    public static int presentationHeight(int fallback) { return rrResolvesOutput() ? resolutionPlan.worldHeight() : fallback; }
    private static io.github.andrexarlay.vulkancompat.rtx.upscaler.WorldResolutionPlan lastLoggedResolutionPlan;
    /** Change-triggered log state: the upscaler last ran on a DLSS-RR-resolved image (the blurry two-stage chain). */
    private static boolean lastTwoStageRr;

    /**
     * Checks whether 3D resolution scaling or hardware ray tracing isolation is active.
     */
    public static boolean isScalingActive() {
        if (!VulkanBridge.isVulkan()) {
            return false;
        }
        // The RT path renders into the isolated world target; the gate (RtActivation) is off while RT is blocked or failed,
        // so a blocked RT no longer forces the extra world target + blit at 100%.
        if (io.github.andrexarlay.vulkancompat.rt.sodium.RtGeometryGate.enabled()) {
            return true;
        }
        // An initialised XeSS/DLSS context renders the world at its input resolution (even for a 100% preset / AA mode).
        if (TemporalUpscaler.isActive()) {
            return true;
        }
        // A built-in spatial upscaler (FSR 1 / NIS) owns the world target at its render scale (also at 100%: sharpening only).
        if (SpatialUpscaler.isSelected()) {
            return true;
        }
        return isResolutionScaleReduced();
    }

    /**
     * True when the user selected the (only implemented) BILINEAR upscaler with a 3D scale below 100%.
     * Reads the public config fields directly: {@code VulkanRtxConfig.isScalingEnabled()} is hard-wired to
     * false ("crash protection" from before the separate 3D target existed) and must not gate this.
     */
    public static boolean isResolutionScaleReduced() {
        return VulkanRtxConfig.upscalerType == VulkanRtxConfig.UpscalerType.BILINEAR
                && VulkanRtxConfig.qualityPreset.getScaleFactor() < 1.0f;
    }

    /**
     * Checks whether the dedicated 3D world phase is active (alias for {@link #isScalingActive()}).
     */
    public static boolean isWorldPhaseActive() {
        return isScalingActive();
    }

    public static int getTargetWidth(int winW) {
        if (TemporalUpscaler.isActive() && TemporalUpscaler.outputWidth() == winW) {
            return TemporalUpscaler.inputWidth();
        }
        if (!TemporalUpscaler.isActive() && SpatialUpscaler.isSelected()) {
            return SpatialUpscaler.renderSize(winW);
        }
        float scale = VulkanRtxConfig.qualityPreset.getScaleFactor();
        return VulkanRtxManager.calculateScaledWidth(winW, scale);
    }

    public static int getTargetHeight(int winH) {
        if (TemporalUpscaler.isActive() && TemporalUpscaler.outputHeight() == winH) {
            return TemporalUpscaler.inputHeight();
        }
        if (!TemporalUpscaler.isActive() && SpatialUpscaler.isSelected()) {
            return SpatialUpscaler.renderSize(winH);
        }
        float scale = VulkanRtxConfig.qualityPreset.getScaleFactor();
        return VulkanRtxManager.calculateScaledHeight(winH, scale);
    }

    /** Copy of the last unjittered world projection (null before the first world frame). Read by FSR for near/far/fov. */
    public static Matrix4f currentProjection() {
        return currProjMatrix == null ? null : new Matrix4f(currProjMatrix);
    }

    private static final Matrix4f levelProjection = new Matrix4f();
    private static final org.joml.Vector3f levelCameraOrigin = new org.joml.Vector3f();
    private static boolean haveLevelProjection;
    private static final boolean BOB_PROJECTION = !"off".equalsIgnoreCase(System.getProperty("vkrtx.rt.bobProjection", "on"));

    /**
     * Records the projection matrix the engine really uses for the world this frame (unjittered) and derives the
     * corresponding camera-origin offset in raw-camera-relative world coordinates. GameRenderer.renderLevel
     * multiplies the camera projection by the view-bobbing / hurt-tilt / portal-wobble pose ({@code projectionMatrix.mul(bobStack)},
     * decompiled GameRenderer lines 679-702) before it reaches the projection buffer, so it differs from
     * {@code CameraRenderState.projectionMatrix}. Called for every Vulkan frame, also when no render-scale phase is active.
     */
    public static boolean noteLevelProjection(Matrix4f projMatrix, Matrix4f baseProjection, Matrix4f viewRotation) {
        // renderLevel also passes the hand (hud3d) projection through ProjectionMatrixBuffer.getBuffer later in the frame
        // (decompiled GameRenderer line 726): only the first call after beginLevelProjectionFrame() is the world's one
        if (projMatrix != null && awaitingLevelProjection) {
            awaitingLevelProjection = false;
            levelProjection.set(projMatrix);
            io.github.andrexarlay.vulkancompat.rt.world.CameraBobPose.originOffset(
                projMatrix, baseProjection, viewRotation, levelCameraOrigin);
            haveLevelProjection = true;
            return true;
        }
        return false;
    }

    /** Arms {@link #noteLevelProjection}: called before {@code renderLevel} of every Vulkan frame. */
    public static void beginLevelProjectionFrame() {
        haveLevelProjection = false;
        levelCameraOrigin.zero();
        awaitingLevelProjection = true;
    }

    private static boolean awaitingLevelProjection;

    /** True only after the world projection hook has run in the current renderLevel invocation. */
    public static boolean hasCurrentLevelProjection() {
        return haveLevelProjection;
    }

    /** The current frame's full, unjittered world projection including bobbing, or {@code fallback}. */
    public static Matrix4f levelProjectionOr(Matrix4f fallback) {
        return BOB_PROJECTION && haveLevelProjection ? new Matrix4f(levelProjection) : fallback;
    }

    /** Exact raster projection, irrespective of the optional RT/compute bob approximation setting. */
    public static Matrix4f currentLevelProjection() {
        return haveLevelProjection ? new Matrix4f(levelProjection) : null;
    }

    /** Effective origin of the exact raster camera, independent of hardware RT diagnostic settings. */
    public static org.joml.Vector3f currentLevelCameraOrigin() {
        return haveLevelProjection ? new org.joml.Vector3f(levelCameraOrigin) : null;
    }

    /** Camera origin offset caused by the view-bob transform, in coordinates relative to the base camera. */
    public static org.joml.Vector3f levelCameraOriginOr(org.joml.Vector3f fallback) {
        return BOB_PROJECTION && haveLevelProjection ? new org.joml.Vector3f(levelCameraOrigin)
            : (fallback != null ? new org.joml.Vector3f(fallback) : new org.joml.Vector3f());
    }

    public static boolean bobProjectionEnabled() {
        return BOB_PROJECTION;
    }

    /**
     * Updates current projection matrix (unjittered) from ProjectionMatrixBuffer.
     */
    public static void setCurrentProjectionMatrix(Matrix4f projMatrix) {
        if (projMatrix != null) {
            if (currProjMatrix == null) {
                currProjMatrix = new Matrix4f(projMatrix);
            } else {
                currProjMatrix.set(projMatrix);
            }
        }
    }

    /**
     * Updates current camera state (view rotation matrix and camera world position).
     */
    public static void setCameraState(Matrix4f viewRotationMatrix, Vec3 pos) {
        if (viewRotationMatrix != null) {
            if (currViewRotationMatrix == null) {
                currViewRotationMatrix = new Matrix4f(viewRotationMatrix);
            } else {
                currViewRotationMatrix.set(viewRotationMatrix);
            }
        }
        if (pos != null) {
            if (prevCameraPos != null && pos.distanceToSqr(prevCameraPos) > 64.0 * 64.0) {
                resetHistory = true;
            }
            currCameraPos = pos;
        }
    }

    /**
     * Alias for {@link #setCameraState(Matrix4f, Vec3)}.
     */
    public static void setCameraView(Matrix4f viewRotationMatrix, Vec3 pos) {
        setCameraState(viewRotationMatrix, pos);
    }

    /**
     * Computes invViewProj = (currProj * currViewRotation)^-1 and prevViewProj = prevProj * prevViewRotation.
     * <p>
     * A degenerate camera (the zero-quaternion first RT frame, a zero-size projection, a non-finite position) is not
     * inverted: the last usable matrices stay in place, history is reset and the caller must skip this temporal frame.
     * Feeding the NaN inverse to the native velocity contract disabled DLSS for the whole session (26.3/latest.log
     * 2026-11-02 23:20:41: {@code non-finite invViewProj at byte 0 = NaN}).
     *
     * @return false when this frame's camera cannot produce a finite inverse view-projection
     */
    public static boolean computeMatrices() {
        cameraMatricesValidThisFrame = false;
        if (currCameraPos != null && !isFinite(currCameraPos)) {
            resetHistory = true;
            logDegenerateCamera("camera_position=" + currCameraPos);
            return false;
        }
        CameraMatrixGuard.Update update = CameraMatrixGuard.update(
            currProjMatrix, currViewRotationMatrix, prevProjMatrix, prevViewRotationMatrix,
            resetHistory, invViewProj, prevViewProj);
        if (!update.usable()) {
            resetHistory = true;
            Matrix4f combined = currProjMatrix != null && currViewRotationMatrix != null
                ? new Matrix4f(currProjMatrix).mul(currViewRotationMatrix) : null;
            logDegenerateCamera(CameraMatrixGuard.describe(currProjMatrix, currViewRotationMatrix, combined));
            return false;
        }
        if (update.resetHistory()) {
            resetHistory = true;
        }
        cameraMatricesValidThisFrame = true;
        return true;
    }

    private static void logDegenerateCamera(String detail) {
        long now = System.nanoTime();
        if (now - lastDegenerateCameraLogNanos < 1_000_000_000L) {
            return;
        }
        lastDegenerateCameraLogNanos = now;
        LOGGER.warn("[VKB-AUTOTEST] camera_matrices=degenerate {} (temporal frame skipped, history reset, upscaler kept)",
            detail);
    }

    private static boolean isFinite(Vec3 value) {
        return Double.isFinite(value.x) && Double.isFinite(value.y) && Double.isFinite(value.z);
    }

    /** Camera translation since the last submitted temporal frame; zero whenever the history was reset. */
    private static Vec3 cameraDelta() {
        if (currCameraPos == null || prevCameraPos == null || resetHistory
            || !isFinite(currCameraPos) || !isFinite(prevCameraPos)) {
            return Vec3.ZERO;
        }
        return currCameraPos.subtract(prevCameraPos);
    }

    /**
     * Called right before {@link GameRenderer#renderLevel()}.
     * If scaling is active, saves original full-resolution targets, substitutes
     * low-res {@code worldTarget} and {@code worldHud3d}, and advances the sub-pixel jitter frame.
     */
    public static void beginWorldPhase(GameRenderer renderer, int winW, int winH) {
        resolutionPlan = null;
        resolutionStamp = null;
        // Always start from "unresolved": only this frame's denoiser record may claim the colour is already resolved.
        io.github.andrexarlay.vulkancompat.rt.world.RtFrameRenderer.resetFrameColorTemporallyResolved();
        if (!isScalingActive()) {
            return;
        }
        if (!(renderer instanceof GameRendererTargetAccessor accessor)) {
            return;
        }

        int targetW = getTargetWidth(winW);
        int targetH = getTargetHeight(winH);

        if (targetW <= 0 || targetH <= 0) {
            return;
        }

        // 100% resolution or invalid dimensions: zero overhead vanilla path when hardware RT is disabled
        final boolean rtNow = io.github.andrexarlay.vulkancompat.rt.sodium.RtGeometryGate.enabled();
        if (!rtNow && !TemporalUpscaler.isActive() && !FrameGenRuntime.usable() && !SpatialUpscaler.isSelected()
            && targetW == winW && targetH == winH) {
            return;
        }

        resolutionPlan = chooseResolutionPlan(targetW, targetH, winW, winH);
        resolutionStamp = io.github.andrexarlay.vulkancompat.rtx.frame.WorldFrames.stampOrNull();
        ensureTargets(resolutionPlan.worldWidth(), resolutionPlan.worldHeight());
        if (!resolutionPlan.equals(lastLoggedResolutionPlan)) {
            lastLoggedResolutionPlan = resolutionPlan;
            resetHistory = true;
            LOGGER.info("[VKB-RESOLVE] rr_output={} trace={}x{} raster={}x{} output={}x{} framegen_off={}",
                resolutionPlan.rayReconstructionOutput(), targetW, targetH, resolutionPlan.worldWidth(),
                resolutionPlan.worldHeight(), winW, winH,
                io.github.andrexarlay.vulkancompat.rtx.framegen.FrameGenSettings.mode()
                    == io.github.andrexarlay.vulkancompat.rtx.framegen.FrameGenSettings.Mode.OFF);
        }

        if (lastRtEnabled == null || lastRtEnabled != rtNow) {
            boolean first = lastRtEnabled == null;
            lastRtEnabled = rtNow;
            if (!first) {
                // RT toggle only changes the execution policy; the target object is kept. Drop the RT binding and history.
                resetHistory = true;
                logSkyIdentity("rt_toggle rt=" + rtNow);
            }
        }
        final SkyRenderer skyNow = currentSkyRenderer();
        if (skyNow instanceof SkyRenderTargetAccessor skyAcc
            && System.identityHashCode(skyAcc.vkb$getRenderTarget()) != lastLoggedSkyTargetId) {
            logSkyIdentity("first_capture");
        }

        inWorldPhase = true;
        depthCopiedThisFrame = false;
        handPassDeferred = handMustBeDeferred(inWorldPhase, CameraJitterManager.temporalConsumerActive());
        if (lastLoggedHandDeferral == null || lastLoggedHandDeferral != handPassDeferred) {
            lastLoggedHandDeferral = handPassDeferred;
            LOGGER.info("[VKB-AUTOTEST] hand_defer={} temporal_consumer={}", handPassDeferred,
                TemporalUpscaler.isActive() ? TemporalUpscaler.active()
                    : CameraJitterManager.temporalConsumerActive() ? "dlss_rr" : "off");
        }
        if (CameraJitterManager.temporalConsumerActive()) {
            if (TemporalUpscaler.initEpoch() != lastXessEpoch) {
                lastXessEpoch = TemporalUpscaler.initEpoch();
                resetHistory = true;
            }
            CameraJitterManager.advanceFrame(targetW, targetH,
                CameraJitterManager.phaseCountFor(TemporalUpscaler.isActive() ? TemporalUpscaler.scale() : 1.0));
        }

        if (lastLogW != targetW || lastLogH != targetH || lastLogWinW != winW || lastLogWinH != winH) {
            LOGGER.info("[VKB-AUTOTEST] world_target={}x{} main={}x{}", targetW, targetH, winW, winH);
            lastLogW = targetW;
            lastLogH = targetH;
            lastLogWinW = winW;
            lastLogWinH = winH;
        }

        savedMainRenderTarget = accessor.vkb$getMainRenderTarget();
        savedHud3DTarget = accessor.vkb$getHud3DTarget();

        accessor.vkb$setMainRenderTarget(worldTarget);
        accessor.vkb$setHud3DTarget(worldHud3d);
    }

    /**
     * Called right after {@link GameRenderer#applyPostEffects()}.
     * Restores original full-size render targets, executes VelocityPipeline for motion vectors,
     * dispatches hardware ray tracing and temporal upscalers via FrameContext, and falls back to bilinear upscale.
     */
    public static void endWorldPhase(GameRenderer renderer) {
        if (!inWorldPhase) {
            return;
        }
        boolean reconstructedOutput = rrResolvesOutput();
        inWorldPhase = false;

        RenderTarget fullMain = savedMainRenderTarget;

        if (renderer instanceof GameRendererTargetAccessor accessor) {
            if (savedMainRenderTarget != null) {
                accessor.vkb$setMainRenderTarget(savedMainRenderTarget);
            }
            if (savedHud3DTarget != null) {
                accessor.vkb$setHud3DTarget(savedHud3DTarget);
            }
        }

        savedMainRenderTarget = null;
        savedHud3DTarget = null;

        if (worldTarget != null && fullMain != null) {
            boolean currentRt = RtFrameRenderer.displayedThisFrame(worldTarget);
            colorProducerChanged = currentRt != lastColorProducerRt;
            if (colorProducerChanged) {
                resetHistory = true;
                lastColorProducerRt = currentRt;
            }
            boolean dispatched = false;
            try {
                // 1. Hardware Ray Tracing: done by RtFrameRenderer at the head of render3dHud (MixinGameRenderer).
                // The PoC overlay that used to run here (test geometry, rt_test) is deleted with the legacy renderer.

                // 2. Temporal upscaler: XeSS (-Dvkbridge.xess) or DLSS (-Dvkbridge.dlss). STUB: FSR has no implementation.
                if (reconstructedOutput) {
                    // RR already produced the world at output resolution, including the raw fallback of a skipped RR frame.
                    // Post effects/UI have run on that image. Do not feed it through SR a second time.
                    if (worldTarget.width != fullMain.width || worldTarget.height != fullMain.height) {
                        throw new IllegalStateException("RR presentation extent differs from main: "
                            + worldTarget.width + "x" + worldTarget.height + " vs "
                            + fullMain.width + "x" + fullMain.height);
                    }
                    RenderSystem.getDevice().createCommandEncoder().copyTextureToTexture(
                        worldTarget.getColorTexture(), fullMain.getColorTexture(), 0, 0, 0, 0, 0,
                        fullMain.width, fullMain.height);
                    dispatched = true;
                } else if (TemporalUpscaler.isActive()) {
                    dispatched = runXessPass(fullMain);
                } else if (FrameGenRuntime.usable()) {
                    runMotionVectorsOnly();
                }

                // 2b. Built-in spatial upscaler (FSR 1 / NIS compute in this mod)
                if (!dispatched && !TemporalUpscaler.isActive() && SpatialUpscaler.isSelected()) {
                    dispatched = runSpatialPass(fullMain);
                }

                // 3. Default: bilinear upscale of the world target into the full-size main target
                if (!dispatched) {
                    upscaleWorld(worldTarget, fullMain);
                }
            } catch (Throwable t) {
                LOGGER.error("Failed to upscale world target to full main target", t);
                try {
                    upscaleWorld(worldTarget, fullMain);
                } catch (Throwable fallbackErr) {
                    LOGGER.error("Fallback bilinear blit also failed", fallbackErr);
                }
            } finally {
                advanceFrameHistory();
            }
        }

        // Full-resolution replay: the hand has no motion vectors and uses its own depth buffer, so it must not be fed to
        // the temporal upscaler with the world. Drawn after the upscale and before the GUI.
        if (renderer instanceof GameRendererHandAccessor handAccessor) {
            try {
                handAccessor.vkb$replayDeferredHand();
            } catch (Throwable t) {
                LOGGER.error("Failed to replay the deferred first-person hand pass", t);
            }
        }
    }

    private static void advanceFrameHistory() {
        // A skipped or degenerate frame must not become the previous frame: the next submitted temporal frame has to
        // reproject against the last camera that actually produced motion vectors.
        if (cameraMatricesValidThisFrame) {
            if (currProjMatrix != null) {
                if (prevProjMatrix == null) {
                    prevProjMatrix = new Matrix4f(currProjMatrix);
                } else {
                    prevProjMatrix.set(currProjMatrix);
                }
            }
            if (currViewRotationMatrix != null) {
                if (prevViewRotationMatrix == null) {
                    prevViewRotationMatrix = new Matrix4f(currViewRotationMatrix);
                } else {
                    prevViewRotationMatrix.set(currViewRotationMatrix);
                }
            }
            if (currCameraPos != null && isFinite(currCameraPos)) {
                prevCameraPos = currCameraPos;
            }
            resetHistory = false;
        }
        cameraMatricesValidThisFrame = false;
    }

    /**
     * Safely resizes the dedicated world targets without touching GameRenderer.resize
     * (avoiding invalidation of sectionOcclusionGraph).
     */
    public static void ensureTargets(int w, int h) {
        RenderSystem.assertOnRenderThread();
        if (w <= 0 || h <= 0) {
            return;
        }

        // STORAGE usage is always requested for the dedicated world target and resize is in place (RenderTarget.resize =
        // destroyBuffers + createBuffers on the SAME object), so SkyRenderer.renderTarget / frame-graph consumers that
        // captured this object never see a destroyed target.
        if (worldTarget == null) {
            storageUsageFormat = TargetLifecyclePolicy.resolveStorageScope(true);
            try {
                worldTarget = new TextureTarget("vkb_world", w, h, GpuFormat.RGBA8_UNORM, GpuFormat.D32_FLOAT);
            } finally {
                storageUsageFormat = null;
            }
            worldTargetHasStorage = true;
            resetHistory = true;
            logSkyIdentity("create");
        } else if (worldTarget.width != w || worldTarget.height != h) {
            drainBorrowedWorldTargetRecordings();
            final RenderTarget identityBefore = worldTarget;
            storageUsageFormat = TargetLifecyclePolicy.resolveStorageScope(true);
            try {
                worldTarget.resize(w, h);
            } finally {
                storageUsageFormat = null;
            }
            if (!TargetLifecyclePolicy.hasStableIdentity(identityBefore, worldTarget)
                || worldTarget.getColorTextureView() == null) {
                throw new IllegalStateException("world target lost its colour view after in-place resize to " + w + "x" + h);
            }
            resetHistory = true; // XeSS/DLSS history of the old resolution is invalid (FrameContext reset=true)
            logSkyIdentity("resize " + w + "x" + h);
        }

        if (worldHud3d == null) {
            worldHud3d = new TextureTarget("vkb_world_hud3d", w, h, null, GpuFormat.D32_FLOAT);
        } else if (worldHud3d.width != w || worldHud3d.height != h) {
            worldHud3d.resize(w, h);
        }

        if ((resolutionPlan == null || !resolutionPlan.rayReconstructionOutput())
                && (TemporalUpscaler.isActive() || FrameGenRuntime.usable())) {
            ensureTemporalTargets(w, h);
        }
    }

    /**
     * Depth copy (taken at the head of render3dHud, before the hand pass clears the depth) and motion vector image of
     * the XeSS path, both at render (input) resolution. The motion vector image gets STORAGE usage (compute writes it).
     */
    private static void ensureTemporalTargets(int w, int h) {
        if ((depthCopy != null && (depthCopy.width != w || depthCopy.height != h))
            || (motionVectorsTarget != null && (motionVectorsTarget.width != w || motionVectorsTarget.height != h))) {
            FrameGenRuntime.drainProducerRecordings();
            drainVelocityRecordings();
        }
        if (depthCopy == null) {
            depthCopy = new TextureTarget("vkb_world_depth_copy", w, h, null, GpuFormat.D32_FLOAT);
            resetHistory = true;
        } else if (depthCopy.width != w || depthCopy.height != h) {
            depthCopy.resize(w, h);
            resetHistory = true;
        }
        if (motionVectorsTarget == null || motionVectorsTarget.width != w || motionVectorsTarget.height != h) {
            storageUsageFormat = GpuFormat.RG16_FLOAT;
            try {
                if (motionVectorsTarget == null) {
                    motionVectorsTarget = new TextureTarget("vkb_motion_vectors", w, h, GpuFormat.RG16_FLOAT, null);
                } else {
                    motionVectorsTarget.resize(w, h);
                }
            } finally {
                storageUsageFormat = null;
            }
            resetHistory = true;
        }
    }

    /**
     * Head of {@code GameRenderer.render3dHud}: the hand pass clears the world depth, so the depth that XeSS and the
     * velocity pass need is copied here (engine copy, no render pass is open at this point).
     */
    public static void copyWorldDepth() {
        // Output-grid raster depth is already available to UI; RR owns its input-grid motion/depth guides.
        if (rrResolvesOutput()) return;
        if (!inWorldPhase || (!TemporalUpscaler.isActive() && !FrameGenRuntime.usable()) || worldTarget == null || depthCopy == null) {
            return;
        }
        if (depthCopy.width != worldTarget.width || depthCopy.height != worldTarget.height) {
            throw new IllegalStateException("depth copy " + depthCopy.width + "x" + depthCopy.height
                + " does not match world target " + worldTarget.width + "x" + worldTarget.height);
        }
        depthCopy.copyDepthFrom(worldTarget);
        depthCopiedThisFrame = true;
    }

    /**
     * True only while a texture of {@code format} is being created that needs STORAGE usage (world colour for the RT
     * compute pass, XeSS output, motion vectors). Read by MixinVulkanConst (render thread only).
     */
    public static boolean isStorageUsageRequestedFor(GpuFormat format) {
        return storageUsageFormat != null && storageUsageFormat == format;
    }

    /**
     * Creates or resizes the XeSS output image (window size). STORAGE usage is requested for exactly this texture.
     */
    private static void ensureXessOutput(int w, int h) {
        if (xessOutput != null && xessOutput.width == w && xessOutput.height == h) {
            return;
        }
        if (xessOutput != null) drainSpatialRecordings();
        storageUsageFormat = GpuFormat.RGBA8_UNORM;
        try {
            if (xessOutput == null) {
                xessOutput = new TextureTarget("vkb_xess_output", w, h, GpuFormat.RGBA8_UNORM, null);
            } else {
                xessOutput.resize(w, h);
            }
        } finally {
            storageUsageFormat = null;
        }
        resetHistory = true;
    }

    private static boolean xessSmokeLogged = false;

    /**
     * XeSS path of the end of the world phase. Step X3: smoke mode only. With
     * {@code -Dvkbridge.xess.smoke=true} the XeSS output image is cleared to a known colour (magenta) and copied into
     * the full-size main target; without it nothing is executed yet and the caller uses the bilinear blit.
     *
     * @return true if the full-resolution image was produced by this path
     */
    private static boolean runXessPass(RenderTarget fullMain) {
        if (TemporalUpscaler.active() == UpscalerBackend.DLSS || !XessRuntime.smokeRequested()) {
            return runXessTemporal(fullMain);
        }
        ensureXessOutput(fullMain.width, fullMain.height);
        VulkanDevice vulkanDevice = VulkanCapabilityChecker.getVulkanDevice();
        if (vulkanDevice == null) {
            throw new IllegalStateException("XeSS smoke: no Vulkan device");
        }
        long image = extractVkImage(xessOutput);
        if (image == 0L) {
            throw new IllegalStateException("XeSS smoke: cannot get the VkImage of the XeSS output");
        }
        VulkanCommandEncoder encoder = vulkanDevice.createCommandEncoder();
        VkCommandBuffer cmd = encoder.allocateAndBeginTransientCommandBuffer();
        VkBarriers.fullMemoryBarrier(cmd);
        try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
            org.lwjgl.vulkan.VkClearColorValue color = org.lwjgl.vulkan.VkClearColorValue.calloc(stack);
            color.float32(0, 1.0f).float32(1, 0.0f).float32(2, 1.0f).float32(3, 1.0f); // magenta
            org.lwjgl.vulkan.VkImageSubresourceRange.Buffer range = org.lwjgl.vulkan.VkImageSubresourceRange.calloc(1, stack);
            range.get(0).aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
            VK10.vkCmdClearColorImage(cmd, image, VK10.VK_IMAGE_LAYOUT_GENERAL, color, range);
        }
        VkBarriers.fullMemoryBarrier(cmd);
        int endErr = VK10.vkEndCommandBuffer(cmd);
        if (endErr != VK10.VK_SUCCESS) {
            throw new IllegalStateException("XeSS smoke: vkEndCommandBuffer failed: " + endErr);
        }
        encoder.execute(cmd);
        copyXessOutputToMain(fullMain);
        if (!xessSmokeLogged) {
            xessSmokeLogged = true;
            LOGGER.info("[VKB-AUTOTEST] xess=smoke color=magenta output={}x{} copied_to_main={}x{}",
                xessOutput.width, xessOutput.height, fullMain.width, fullMain.height);
        }
        return true;
    }

    private static int xessFrames = 0;

    /**
     * Steps X4/X5: velocity compute on the depth copy, then {@code xessVKExecute} into the XeSS output image, then the
     * engine copy of that output into the full-size main target. Any failure disables XeSS for the session with an
     * explicit {@code stage=execute} log line and the caller falls back to the bilinear blit.
     *
     * @return true if XeSS produced the full-resolution image
     */
    private static boolean runXessTemporal(RenderTarget fullMain) {
        final UpscalerBackend kind = TemporalUpscaler.active();
        try {
            return recordXess(fullMain, kind);
        } catch (RuntimeException | Error e) {
            LOGGER.error("{} frame failed", kind, e);
            TemporalUpscaler.executeFailed(kind, "exception:" + e.getClass().getSimpleName());
            return false;
        }
    }

    /** Records velocity + the temporal upscaler of {@code kind} (XESS or DLSS) and copies its output into the main target. */
    private static boolean recordXess(RenderTarget fullMain, UpscalerBackend kind) {
        if (!depthCopiedThisFrame) {
            throw new IllegalStateException("XeSS: the world depth copy of this frame is missing (render3dHud hook did not run)");
        }
        depthCopiedThisFrame = false;
        ensureXessOutput(fullMain.width, fullMain.height);
        if (!computeMatrices()) {
            return false; // degenerate camera: skip this temporal frame instead of failing the upscaler for the session
        }
        final Vec3 camDelta = cameraDelta();
        VulkanDevice vulkanDevice = VulkanCapabilityChecker.getVulkanDevice();
        if (vulkanDevice == null) {
            throw new IllegalStateException("XeSS: no Vulkan device");
        }
        IUpscalerBackend backend = UpscalerLibraryLoader.getBackend(kind);
        long rasterDepthView = extractVkImageView(depthCopy.getDepthTextureView());
        long rasterDepthImg = extractDepthVkImage(depthCopy);
        long rtDepth = io.github.andrexarlay.vulkancompat.rt.world.RtFrameRenderer.getActiveDepthView();
        long mvView = extractVkImageView(motionVectorsTarget.getColorTextureView());
        long colorView = extractVkImageView(worldTarget.getColorTextureView());
        long outView = extractVkImageView(xessOutput.getColorTextureView());
        long colorImg = extractVkImage(worldTarget);
        long mvImg = extractVkImage(motionVectorsTarget);
        long outImg = extractVkImage(xessOutput);
        final boolean colorResolved = RtFrameRenderer.frameColorTemporallyResolved();
        // SR and FG consume the same raster depth and velocity pair. The velocity pass merges current RT object
        // motion only at pixels where the traced surface matches the D32 depth that the SDK will see.
        if (rasterDepthView == 0L || mvView == 0L || colorView == 0L || outView == 0L
            || colorImg == 0L || rasterDepthImg == 0L || mvImg == 0L || outImg == 0L) {
            throw new IllegalStateException("XeSS: missing Vulkan handle (rasterDepthView=" + rasterDepthView
                + " mvView=" + mvView + " colorView=" + colorView + " outView=" + outView + " color=" + colorImg
                + " depth=" + rasterDepthImg + " mv=" + mvImg + " out=" + outImg + ")");
        }
        VelocityPipeline velocityPipeline = velocityRecordings.ensure(vulkanDevice);

        final int inW = worldTarget.width;
        final int inH = worldTarget.height;
        final int outW = xessOutput.width;
        final int outH = xessOutput.height;
        // VkFormat numbers via the engine's own mapping (VulkanConst.toVk)
        final int fmtColor = com.mojang.renderpearl.backend.vulkan.VulkanConst.toVk(GpuFormat.RGBA8_UNORM);
        final int fmtDepth = com.mojang.renderpearl.backend.vulkan.VulkanConst.toVk(GpuFormat.D32_FLOAT);
        final int fmtMv = com.mojang.renderpearl.backend.vulkan.VulkanConst.toVk(GpuFormat.RG16_FLOAT);

        final int neuralIntensity = kind == UpscalerBackend.DLSS && !colorResolved && dlssNeural.ready(inW, inH)
            && (worldTarget.getColorTexture().usage() & GpuTexture.USAGE_COPY_SRC) != 0
            ? VulkanRtxConfig.dlssNeuralIntensity : 0;
        final boolean reset = resetHistory || neuralIntensity != lastDlssNeuralIntensity;
        // A denoiser-resolved image is already back on the pixel grid: it must reach DLSS-SR with zero jitter.
        final float srJitterX = upscalerJitterComponent(colorResolved, CameraJitterManager.getCurrentPixelJitterX());
        final float srJitterY = upscalerJitterComponent(colorResolved, CameraJitterManager.getCurrentPixelJitterY());
        boolean ok = recordVelocityCommands(vulkanDevice, cmd -> {
            VkBarriers.fullMemoryBarrier(cmd);
            // Motion vectors must match the exact same hardware Reversed-Z depth buffer (rasterDepthView) passed to the upscaler
            long worldMotion = resetHistory ? 0L : RtFrameRenderer.displayedWorldMotion(worldTarget);
            velocityPipeline.dispatchWorld(cmd, rasterDepthView, mvView, inW, inH, invViewProj, prevViewProj, camDelta, worldMotion);
            VkBarriers.fullMemoryBarrier(cmd);
            if (rtDepth != 0L && io.github.andrexarlay.vulkancompat.rt.denoise.MotionProbe.armed()
                && io.github.andrexarlay.vulkancompat.rt.world.RtFrameRenderer.probeMemory() != null) {
                io.github.andrexarlay.vulkancompat.rt.denoise.MotionProbe.record(cmd,
                    io.github.andrexarlay.vulkancompat.rt.world.RtFrameRenderer.probeMemory(),
                    io.github.andrexarlay.vulkancompat.rt.world.RtFrameRenderer.activeDepthImage(), mvImg, 0L, inW, inH,
                    invViewProj, prevViewProj, camDelta, resetHistory,
                    io.github.andrexarlay.vulkancompat.rt.world.RtFrameRenderer.activeDepthConvention() == VelocityPipeline.DEPTH_RAY_DISTANCE);
                VkBarriers.fullMemoryBarrier(cmd);
            }
            io.github.andrexarlay.vulkancompat.rt.denoise.MotionProbe.tick();

            var color = new FrameContext.ImageRef(colorImg, colorView, fmtColor, VK10.VK_IMAGE_ASPECT_COLOR_BIT, inW, inH);
            var depth = new FrameContext.ImageRef(rasterDepthImg, rasterDepthView, fmtDepth, VK10.VK_IMAGE_ASPECT_DEPTH_BIT, inW, inH);
            var motion = new FrameContext.ImageRef(mvImg, mvView, fmtMv, VK10.VK_IMAGE_ASPECT_COLOR_BIT, inW, inH);
            if (neuralIntensity > 0) {
                color = dlssNeural.record(cmd, color, depth, motion, reset, neuralIntensity);
                if (color == null) return false;
            }
            FrameContext ctx = new FrameContext(
                cmd, color, depth, motion,
                new FrameContext.ImageRef(outImg, outView, fmtColor, VK10.VK_IMAGE_ASPECT_COLOR_BIT, outW, outH),
                srJitterX, srJitterY,
                inW, inH, outW, outH,
                (float) TemporalUpscaler.scale(),
                TemporalUpscaler.modeName(),
                1.0f,
                reset,
                RtFrameRenderer.displayedCoverageMask(worldTarget),
                RtFrameRenderer.displayedReactiveMask(worldTarget));
            return backend.dispatch(ctx);
        });
        if (!ok) {
            return false; // The recording was discarded. The caller uses the original world color.
        }
        if (neuralIntensity > 0) dlssNeural.onRecorded();
        lastDlssNeuralIntensity = neuralIntensity;
        copyXessOutputToMain(fullMain);
        boolean twoStageRr = colorResolved
            && io.github.andrexarlay.vulkancompat.rtx.config.RtSettings.denoiser()
                == io.github.andrexarlay.vulkancompat.rtx.config.RtSettings.PathDenoiser.DLSS_RR;
        if (twoStageRr != lastTwoStageRr) {
            lastTwoStageRr = twoStageRr;
            if (twoStageRr) {
                // RR already resolved this image at render size; the upscaler can only stretch it (zero jitter): blur.
                LOGGER.warn("[VKB-RESOLVE] two_stage_rr=true upscaler={} input={}x{} output={}x{} (RR did not own the output this frame)",
                    kind, inW, inH, outW, outH);
            } else {
                LOGGER.info("[VKB-RESOLVE] two_stage_rr=false");
            }
        }
        if (xessFrames == 0 || xessFrames % 120 == 0) {
            LOGGER.info("{}[VKB-AUTOTEST] {}=dispatch ok frames={} result=0 input={}x{} output={}x{} jitter=({},{}) jitter_source={} reset={} phase={}",
                kind == UpscalerBackend.FSR ? "[VKB-FSR] " : "", kind.name().toLowerCase(java.util.Locale.ROOT), xessFrames + 1, inW, inH, outW, outH,
                srJitterX, srJitterY, colorResolved ? "denoiser_resolved" : "camera",
                reset, CameraJitterManager.getPhaseIndex());
        }
        xessFrames++;
        return true;
    }

    /**
     * Dispatches the velocity compute pass without a temporal upscaler, providing depthCopy and motionVectorsTarget
     * for standalone Frame Generation (Streamline DLSS-G / FSR 3 FG) in 100% native resolution.
     */
    private static void runMotionVectorsOnly() {
        if (!depthCopiedThisFrame) {
            return;
        }
        depthCopiedThisFrame = false;
        if (worldTarget == null || depthCopy == null || motionVectorsTarget == null) {
            return;
        }
        if (!computeMatrices()) {
            return;
        }
        final Vec3 camDelta = cameraDelta();
        VulkanDevice vulkanDevice = VulkanCapabilityChecker.getVulkanDevice();
        if (vulkanDevice == null) {
            return;
        }
        long rasterDepthView = extractVkImageView(depthCopy.getDepthTextureView());
        long rasterDepthImg = extractDepthVkImage(depthCopy);
        long rtDepth = io.github.andrexarlay.vulkancompat.rt.world.RtFrameRenderer.getActiveDepthView();
        long mvView = extractVkImageView(motionVectorsTarget.getColorTextureView());
        long mvImg = extractVkImage(motionVectorsTarget);
        if (rasterDepthView == 0L || mvView == 0L || rasterDepthImg == 0L || mvImg == 0L) {
            return;
        }
        VelocityPipeline velocityPipeline = velocityRecordings.ensure(vulkanDevice);

        final int inW = worldTarget.width;
        final int inH = worldTarget.height;

        recordVelocityCommands(vulkanDevice, cmd -> {
            VkBarriers.fullMemoryBarrier(cmd);
            long worldMotion = resetHistory ? 0L : RtFrameRenderer.displayedWorldMotion(worldTarget);
            velocityPipeline.dispatchWorld(cmd, rasterDepthView, mvView, inW, inH, invViewProj, prevViewProj, camDelta, worldMotion);
            VkBarriers.fullMemoryBarrier(cmd);
            if (rtDepth != 0L && io.github.andrexarlay.vulkancompat.rt.denoise.MotionProbe.armed()
                && io.github.andrexarlay.vulkancompat.rt.world.RtFrameRenderer.probeMemory() != null) {
                io.github.andrexarlay.vulkancompat.rt.denoise.MotionProbe.record(cmd,
                    io.github.andrexarlay.vulkancompat.rt.world.RtFrameRenderer.probeMemory(),
                    io.github.andrexarlay.vulkancompat.rt.world.RtFrameRenderer.activeDepthImage(), mvImg, 0L, inW, inH,
                    invViewProj, prevViewProj, camDelta, resetHistory,
                    io.github.andrexarlay.vulkancompat.rt.world.RtFrameRenderer.activeDepthConvention() == VelocityPipeline.DEPTH_RAY_DISTANCE);
                VkBarriers.fullMemoryBarrier(cmd);
            }
            io.github.andrexarlay.vulkancompat.rt.denoise.MotionProbe.tick();

            return true;
        });
    }

    private static boolean recordVelocityCommands(VulkanDevice device, java.util.function.Function<VkCommandBuffer, Boolean> commands) {
        return velocityRecordings.record(device, commands);
    }

    /** Completion proof for the velocity and temporal SDK commands recorded together. */
    public static void drainTemporalRecordings(VulkanDevice expectedDevice) {
        velocityRecordings.drain(expectedDevice);
    }

    private static void drainVelocityRecordings() {
        velocityRecordings.drain();
    }

    /** Display-size RGBA8 STORAGE image between FSR 1 EASU and RCAS (created only when RCAS runs). */
    private static TextureTarget spatialTmp;

    /**
     * Built-in spatial upscaler (FSR 1 / NIS): compute from the world colour into the display-size output image (the same image
     * the temporal path uses), then the engine copy into the full-size main target. A failure is logged by SpatialUpscaler and
     * the caller falls back to the bilinear blit.
     *
     * @return true if the full-resolution image was produced by this path
     */
    private static boolean runSpatialPass(RenderTarget fullMain) {
        VulkanDevice vulkanDevice = VulkanCapabilityChecker.getVulkanDevice();
        if (vulkanDevice == null) {
            throw new IllegalStateException("spatial upscaler: no Vulkan device");
        }
        ensureXessOutput(fullMain.width, fullMain.height);
        boolean needTmp = SpatialUpscaler.selected() == UpscalerBackend.FSR1 && VulkanRtxConfig.fsr1Sharpness > 0.0f;
        if (needTmp && (spatialTmp == null || spatialTmp.width != fullMain.width || spatialTmp.height != fullMain.height)) {
            if (spatialTmp != null) drainSpatialRecordings();
            storageUsageFormat = GpuFormat.RGBA8_UNORM;
            try {
                if (spatialTmp == null) {
                    spatialTmp = new TextureTarget("vkb_spatial_tmp", fullMain.width, fullMain.height, GpuFormat.RGBA8_UNORM, null);
                } else {
                    spatialTmp.resize(fullMain.width, fullMain.height);
                }
            } finally {
                storageUsageFormat = null;
            }
        }
        long inView = extractVkImageView(worldTarget.getColorTextureView());
        long outView = extractVkImageView(xessOutput.getColorTextureView());
        long tmpView = needTmp ? extractVkImageView(spatialTmp.getColorTextureView()) : 0L;
        if (inView == 0L || outView == 0L || (needTmp && tmpView == 0L)) {
            throw new IllegalStateException("spatial upscaler: missing image view (in=" + inView + " out=" + outView + " tmp=" + tmpView + ")");
        }
        if (recordingSpatial || drainingSpatial) throw new IllegalStateException("recursive spatial recording/drain");
        VulkanCommandEncoder encoder = vulkanDevice.createCommandEncoder();
        VkCommandBuffer cmd = encoder.allocateAndBeginTransientCommandBuffer();
        var ticket = spatialCompletion.beginWithDiscard(cmd.address(), new Retirement(
            () -> SpatialUpscaler.releaseRecording(cmd.address()), () -> SpatialUpscaler.discardRecording(cmd.address())));
        boolean executeAttempted = false;
        recordingSpatial = true;
        try {
            boolean ok = SpatialUpscaler.record(vulkanDevice.vkDevice(), cmd, inView, worldTarget.width, worldTarget.height,
                tmpView, outView, xessOutput.width, xessOutput.height);
            if (!ok) {
                spatialCompletion.discard(ticket);
                return false;
            }
            int status = VK10.vkEndCommandBuffer(cmd);
            if (status != VK10.VK_SUCCESS) throw new IllegalStateException("spatial vkEndCommandBuffer failed: " + status);
            spatialCompletion.submissionAttempted(ticket);
            executeAttempted = true;
            encoder.execute(cmd);
            spatialCompletion.submitted(ticket, action -> encoder.queueForDestroy(action::run));
        } catch (RuntimeException | Error failure) {
            if (!executeAttempted) {
                // The complete chain is abandoned, including a successful EASU before a failed RCAS.
                try { spatialCompletion.discard(ticket); }
                catch (RuntimeException | Error cleanup) { failure.addSuppressed(cleanup); }
            }
            throw failure;
        } finally {
            recordingSpatial = false;
        }
        copyXessOutputToMain(fullMain);
        return true;
    }

    private static void drainSpatialRecordings() {
        if (spatialCompletion.pendingCount() == 0) return;
        if (recordingSpatial || drainingSpatial) throw new IllegalStateException("spatial drain during an active recording");
        VulkanDevice device = SpatialUpscaler.hostDevice();
        if (device == null || VulkanCapabilityChecker.getVulkanDevice() != device)
            throw new IllegalStateException("spatial host device unavailable or changed; resources retained");
        drainingSpatial = true;
        try {
            VulkanCommandEncoder encoder = device.createCommandEncoder();
            try (var completion = encoder.createFence()) {
                encoder.submit();
                if (!completion.awaitCompletion(5_000_000_000L))
                    throw new IllegalStateException("spatial host submission completion timed out; resources retained");
                spatialCompletion.afterHostDrain();
            }
            if (spatialCompletion.pendingCount() != 0)
                throw new IllegalStateException("spatial recording proof unavailable; resources retained");
        } finally {
            drainingSpatial = false;
        }
    }

    /** A target's Java identity survives resize; its borrowed Vulkan views do not. */
    private static void drainBorrowedWorldTargetRecordings() {
        io.github.andrexarlay.vulkancompat.rt.world.RtFrameRenderer.drainTargetRecordings();
        FrameGenRuntime.drainProducerRecordings();
        drainVelocityRecordings();
        drainSpatialRecordings();
    }

    /** Engine-side 1:1 copy (vkCmdCopyImage) of the XeSS output into the full-size main target; no render pass may be open. */
    private static void copyXessOutputToMain(RenderTarget fullMain) {
        int w = Math.min(xessOutput.width, fullMain.width);
        int h = Math.min(xessOutput.height, fullMain.height);
        RenderSystem.getDevice().createCommandEncoder()
            .copyTextureToTexture(xessOutput.getColorTexture(), fullMain.getColorTexture(), 0, 0, 0, 0, 0, w, h);
    }

    private static io.github.andrexarlay.vulkancompat.rtx.upscaler.WorldResolutionPlan chooseResolutionPlan(
            int targetW, int targetH, int winW, int winH) {
        return io.github.andrexarlay.vulkancompat.rtx.upscaler.WorldResolutionPlan.choose(
            targetW, targetH, winW, winH,
            io.github.andrexarlay.vulkancompat.rt.world.RtFrameRenderer.rrOutputReady()
                && !Boolean.getBoolean("bedray.rt.disableRrOutput"),
            TemporalUpscaler.active(), SpatialUpscaler.isSelected());
    }

    /**
     * Safety reset hook at HEAD of {@link GameRenderer#render()}.
     * Restores original targets if a previous frame was interrupted by an exception between
     * beginWorldPhase and endWorldPhase.
     */
    public static void resetPhase(GameRenderer renderer) {
        colorProducerChanged = false;
        // Experimental XeSS / DLSS contexts (request-only via -Dvkbridge.xess / -Dvkbridge.dlss; no-op otherwise). Here no render pass is open.
        net.minecraft.client.Minecraft mcForXess = net.minecraft.client.Minecraft.getInstance();
        if (mcForXess != null && mcForXess.getWindow() != null) {
            TemporalUpscaler.onFrameBegin(
                mcForXess.getWindow().getWidth(), mcForXess.getWindow().getHeight());
            io.github.andrexarlay.vulkancompat.rtx.config.SamplerBiasSync.onFrameBegin();
            int winW = mcForXess.getWindow().getWidth();
            int winH = mcForXess.getWindow().getHeight();
            int targetW = getTargetWidth(winW), targetH = getTargetHeight(winH);
            boolean validExtent = winW > 0 && winH > 0 && targetW > 0 && targetH > 0;
            boolean rrOutput = validExtent && chooseResolutionPlan(targetW, targetH, winW, winH).rayReconstructionOutput();
            if (rrOutput) {
                io.github.andrexarlay.vulkancompat.rtx.upscaler.dlss.DlssRuntime.suspendSrFeatureForRr();
            }
            var host = VulkanCapabilityChecker.getVulkanDevice();
            dlssNeural.prepare(host, host != null && mcForXess.level != null && validExtent && !rrOutput
                    && TemporalUpscaler.active() == UpscalerBackend.DLSS
                    && !io.github.andrexarlay.vulkancompat.rtx.framegen.StreamlineBootstrap.dlssViaStreamline(),
                VulkanRtxConfig.dlssNeuralIntensity > 0, targetW, targetH);
        }
        if (inWorldPhase) {
            LOGGER.warn("World phase was not properly ended in previous frame; resetting render targets");
            inWorldPhase = false;
            handPassDeferred = false;
            if (renderer instanceof GameRendererTargetAccessor accessor) {
                if (savedMainRenderTarget != null) {
                    accessor.vkb$setMainRenderTarget(savedMainRenderTarget);
                }
                if (savedHud3DTarget != null) {
                    accessor.vkb$setHud3DTarget(savedHud3DTarget);
                }
            }
            savedMainRenderTarget = null;
            savedHud3DTarget = null;
            CameraJitterManager.reset();
            resetHistory = true;
        }

        // The published world target is NOT destroyed when scaling/RT/upscaler turns off: SkyRenderer and the frame graph
        // keep the Java object across frames. It is released only by destroyTargetsForShutdown().
    }

    private static SkyRenderer currentSkyRenderer() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.levelRenderer == null) {
            return null;
        }
        return mc.levelRenderer.skyRenderer();
    }

    /**
     * Publication state of {@link #worldTarget} with respect to {@code SkyRenderer.renderTarget}. UNKNOWN (accessor mixin
     * not applied, renderer not created yet) blocks retirement (fail-closed).
     */
    public static TargetLifecyclePolicy.PublicationState getSkyPublicationState() {
        if (worldTarget == null) {
            return TargetLifecyclePolicy.PublicationState.NOT_PUBLISHED;
        }
        return TargetLifecyclePolicy.evaluatePublicationState(currentSkyRenderer(), worldTarget);
    }

    /** Logs SkyRenderer.renderTarget vs worldTarget identity. Only called on first capture, create, resize and RT toggle. */
    private static void logSkyIdentity(String reason) {
        SkyRenderer sky = currentSkyRenderer();
        if (!(sky instanceof SkyRenderTargetAccessor acc)) {
            LOGGER.info("[VKB-AUTOTEST] sky_target_id=unknown world_target_id=0x{} same=unknown reason={} (SkyRenderer missing or accessor mixin not applied)",
                Integer.toHexString(System.identityHashCode(worldTarget)), reason);
            return;
        }
        RenderTarget skyTarget = acc.vkb$getRenderTarget();
        int skyId = System.identityHashCode(skyTarget);
        lastLoggedSkyTargetId = skyId;
        LOGGER.info("[VKB-AUTOTEST] sky_target_id=0x{} world_target_id=0x{} same={} sky_color_view_present={} reason={}",
            Integer.toHexString(skyId), Integer.toHexString(System.identityHashCode(worldTarget)),
            skyTarget == worldTarget, skyTarget != null && skyTarget.getColorTextureView() != null, reason);
    }

    /**
     * Releases dedicated world render targets. Refused (fail-closed, with a warning) during the world phase and whenever
     * the world target is published to SkyRenderer or the publication state is UNKNOWN. Runtime configuration changes must
     * not call this; use {@link #destroyTargetsForShutdown()} for the real teardown.
     */
    public static void destroyTargets() {
        TargetLifecyclePolicy.PublicationState state = getSkyPublicationState();
        if (!TargetLifecyclePolicy.isTargetRetirementAllowed(inWorldPhase, state)) {
            LOGGER.warn("[VKB-AUTOTEST] target_retire refused inWorldPhase={} sky_publication={}", inWorldPhase, state);
            return;
        }
        releaseTargets();
    }

    /** Explicit shutdown point: the level renderer and frame graph no longer submit work with the published target. */
    public static void destroyTargetsForShutdown() {
        releaseTargets();
    }

    private static void releaseTargets() {
        RenderSystem.assertOnRenderThread();
        drainBorrowedWorldTargetRecordings();
        dlssNeural.close();
        lastDlssNeuralIntensity = 0;
        TemporalUpscaler.cleanup();
        UpscalerBackend active = VulkanRtxConfig.activeBackend;
        if (active != null && active != UpscalerBackend.BILINEAR) {
            IUpscalerBackend backend = UpscalerLibraryLoader.getBackend(active);
            if (backend != null) {
                backend.cleanup();
            }
        }
        velocityRecordings.close();
        {
            VulkanDevice idleDevice = VulkanCapabilityChecker.getVulkanDevice();
            if (idleDevice != null) {
                VK10.vkDeviceWaitIdle(idleDevice.vkDevice());
            }
            SpatialUpscaler.cleanup();
        }
        if (spatialTmp != null) {
            spatialTmp.destroyBuffers();
            spatialTmp = null;
        }
        if (worldTarget != null) {
            worldTarget.destroyBuffers();
            worldTarget = null;
        }
        if (worldHud3d != null) {
            worldHud3d.destroyBuffers();
            worldHud3d = null;
        }
        if (motionVectorsTarget != null) {
            motionVectorsTarget.destroyBuffers();
            motionVectorsTarget = null;
        }
        if (depthCopy != null) {
            depthCopy.destroyBuffers();
            depthCopy = null;
        }
        if (xessOutput != null) {
            xessOutput.destroyBuffers();
            xessOutput = null;
        }
        worldTargetHasStorage = false;
        lastRtEnabled = null;
        lastLoggedSkyTargetId = -1;
        CameraJitterManager.reset();
        haveLevelProjection = false;
        awaitingLevelProjection = false;
        levelProjection.identity();
        levelCameraOrigin.zero();
        resetHistory = true;
        cameraMatricesValidThisFrame = false;
        lastDegenerateCameraLogNanos = 0L;
        invViewProj.identity();
        prevViewProj.identity();
        currProjMatrix = null;
        prevProjMatrix = null;
        currViewRotationMatrix = null;
        prevViewRotationMatrix = null;
        currCameraPos = null;
        prevCameraPos = null;
        lastLogW = -1;
        lastLogH = -1;
        lastLogWinW = -1;
        lastLogWinH = -1;
    }

    /**
     * Performs a full-screen bilinear blit/upscale from low-res world target to native fullMain target.
     */
    private static void upscaleWorld(RenderTarget world, RenderTarget fullMain) {
        GpuTextureView srcView = world.getColorTextureView();
        GpuTextureView dstView = fullMain.getColorTextureView();
        if (srcView == null || dstView == null) {
            return;
        }

        CommandEncoder enc = RenderSystem.getDevice().createCommandEncoder();
        try (RenderPass p = enc.createRenderPass(() -> "VKB upscale", dstView, Optional.empty())) {
            p.setPipeline(RenderSystem.getCompiledPipeline(RenderPipelines.TRACY_BLIT));
            RenderSystem.bindDefaultUniforms(p);
            p.setUniform("InSampler", srcView, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
            p.draw(3, 1, 0, 0);
        }
    }

    public static long extractVkImage(RenderTarget target) {
        if (target == null || target.getColorTexture() == null) {
            return 0L;
        }
        if (target.getColorTexture() instanceof VulkanGpuTexture vulkanTex) {
            return vulkanTex.vkImage();
        }
        try {
            Method m = target.getColorTexture().getClass().getMethod("vkImage");
            Object res = m.invoke(target.getColorTexture());
            if (res instanceof Long l) {
                return l;
            }
        } catch (Throwable ignored) {}
        return 0L;
    }

    public static long extractDepthVkImage(RenderTarget target) {
        if (target == null || target.getDepthTexture() == null) {
            return 0L;
        }
        if (target.getDepthTexture() instanceof VulkanGpuTexture vulkanTex) {
            return vulkanTex.vkImage();
        }
        try {
            Method m = target.getDepthTexture().getClass().getMethod("vkImage");
            Object res = m.invoke(target.getDepthTexture());
            if (res instanceof Long l) {
                return l;
            }
        } catch (Throwable ignored) {}
        return 0L;
    }

    public static long extractVkImageView(GpuTextureView view) {
        if (view == null) {
            return 0L;
        }
        if (view instanceof VulkanGpuTextureView vulkanView) {
            return vulkanView.vkImageView();
        }
        try {
            Method m = view.getClass().getMethod("vkImageView");
            Object res = m.invoke(view);
            if (res instanceof Long l) {
                return l;
            }
        } catch (Throwable ignored) {}
        return 0L;
    }

    public static TextureTarget getWorldTarget() {
        return worldTarget;
    }

    public static TextureTarget getWorldHud3d() {
        return worldHud3d;
    }

    /** World depth copy of the temporal-upscaler path (D32, render size), null while no temporal upscaler ran. Read by frame generation. */
    public static TextureTarget getDepthCopy() {
        return depthCopy;
    }

    public static TextureTarget getMotionVectorsTarget() {
        return motionVectorsTarget;
    }

    public static VelocityPipeline getVelocityPipeline() {
        return velocityRecordings.pipeline();
    }

    public static boolean isInWorldPhase() {
        return inWorldPhase;
    }

    /**
     * The hand must leave the world phase exactly when a temporal upscaler would reproject it: it is rendered without
     * motion vectors, so feeding it to the temporal pass stretches it across the frame.
     */
    public static boolean handMustBeDeferred(boolean inWorldPhase, boolean temporalUpscalerActive) {
        return inWorldPhase && temporalUpscalerActive;
    }

    /** True when the hand pass belongs to the full-resolution replay after the temporal upscale (see {@link GameRendererHandAccessor}). */
    public static boolean shouldDeferHand() {
        return inWorldPhase && handPassDeferred;
    }

    /**
     * Sub-pixel jitter handed to a temporal upscaler for the world colour. NRD and DLSS-RR already resolve their own
     * sub-pixel history into a render-resolution image sitting on the pixel grid, so passing the camera jitter on to a
     * following DLSS-SR pass would offset it a second time and make the whole frame crawl.
     */
    public static float upscalerJitterComponent(boolean colorTemporallyResolved, float cameraJitter) {
        return colorTemporallyResolved ? 0.0f : cameraJitter;
    }

    public static RenderTarget getSavedMainRenderTarget() {
        return savedMainRenderTarget;
    }

    public static RenderTarget getSavedHud3DTarget() {
        return savedHud3DTarget;
    }

    public static Matrix4f getCurrProjMatrix() {
        return currProjMatrix;
    }

    public static Matrix4f getPrevProjMatrix() {
        return prevProjMatrix;
    }

    public static Matrix4f getCurrViewRotationMatrix() {
        return currViewRotationMatrix;
    }

    public static Matrix4f getPrevViewRotationMatrix() {
        return prevViewRotationMatrix;
    }

    public static Vec3 getCurrCameraPos() {
        return currCameraPos;
    }

    public static Vec3 getPrevCameraPos() {
        return prevCameraPos;
    }

    public static Matrix4f getInvViewProj() {
        return invViewProj;
    }

    public static Matrix4f getPrevViewProj() {
        return prevViewProj;
    }

    public static boolean colorProducerChangedThisFrame() { return colorProducerChanged; }

    public static boolean isResetHistory() {
        return resetHistory;
    }

    public static void setResetHistory(boolean reset) {
        resetHistory = reset;
    }

    public static void requestResetHistory() {
        resetHistory = true;
    }
}
