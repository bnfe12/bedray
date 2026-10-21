package io.github.andrexarlay.vulkancompat.rt.world;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import io.github.andrexarlay.vulkanbridge.api.VulkanBridge;
import io.github.andrexarlay.vulkancompat.rt.denoise.BridgeNrdLocator;
import io.github.andrexarlay.vulkancompat.rt.denoise.DenoiserConfig;
import io.github.andrexarlay.vulkancompat.rt.denoise.DenoiserMode;
import io.github.andrexarlay.vulkancompat.rt.denoise.DlssRrDenoiser;
import io.github.andrexarlay.vulkancompat.rt.denoise.NrdAvailability;
import io.github.andrexarlay.vulkancompat.rt.denoise.NrdDenoiser;
import io.github.andrexarlay.vulkancompat.rt.denoise.NrdException;
import io.github.andrexarlay.vulkancompat.rt.material.MaterialAtlasBaker;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.dlss.DlssAvailability;
import io.github.andrexarlay.vulkancompat.rt.material.RtMaterialAtlas;
import io.github.andrexarlay.vulkancompat.rt.sodium.RtGeometryGate;
import io.github.andrexarlay.vulkancompat.rtx.config.RtSettings;
import io.github.andrexarlay.vulkancompat.rtx.WorldTargetManager;
import io.github.andrexarlay.vulkancompat.rtx.raytracing.VulkanCapabilityChecker;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.VkBarriers;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.TemporalUpscaler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.client.renderer.state.level.SkyRenderState;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.world.level.dimension.DimensionType;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VKCapabilitiesDevice;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkImageCopy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;

/**
 * Per-frame driver of the world ray tracing path, called from the head of {@code GameRenderer.render3dHud}
 * (world depth still alive, hand not drawn yet, no render pass open). Everything is recorded into one transient command
 * buffer that is spliced into the engine submission with {@code VulkanCommandEncoder.execute}.
 *
 * <p>Order inside the buffer: BLAS builds + TLAS build ({@link RtWorld#update}), primary-ray compute into an own RGBA8
 * image, and, only with {@code -Dvkbridge.rt.debug=<view>}, a {@code vkCmdCopyImage} of that image over the engine
 * colour image (REPLACE). Without a debug view the rays are traced (statistics) but nothing is shown.
 *
 * <p>Off (gate false): returns before touching any GPU object. Any failure disables the path for the session with
 * {@code [VKB-RT] FAILED stage=.. result=..}; the game continues with the plain raster image.
 */
public final class RtFrameRenderer {
    private static final Logger LOGGER = LoggerFactory.getLogger("VKB-RT");
    private static final int STATS_SLOTS = 3;
    /**
     * Per-frame slot (host visible, read by the shaders through pc.statsAddr): 0 hits, 4 samples, 8 log-luminance sum (zeroed
     * on the GPU), 16 vec3 camera position relative to the emitter origin, 32 uint64 emitter buffer address, 40 emitter count,
     * 44 float auto exposure, 48 GI distance, 52 emissive distance, 56 AO radius (ray mode, blocks), 60 auto-normal strength,
     * 64 sun angular radius, 68 history cap, 80 mat4 previous view-projection, 144 vec3 camera move since the previous frame,
     * 160..247 fog / entity fields, 256..383 the vanilla sky block (RtSkyState, StatsBuf sky* in rt_common.glsl),
     * 384..399 the bobbed camera origin relative to the raw camera position; 400..415 the raw camera-forward axis for NRD's
     * view-Z guide (StatsBuf cameraBobOrigin/cameraForward).
     */
    private static final int STATS_STRIDE = 512;
    /** Rebuilt sections closer than this (squared section distance, ~2 sections) reset the path tracing accumulation. */
    static final long SCENE_CHANGE_RADIUS_SQ = 8;

    private static RtWorld world;
    private static RtPass pass;
    private static RtMaterialAtlas materials;
    private static RtEmitters emitters;
    private static int lastRadiusXz;
    private static long lastStatusNs;
    private static boolean lastUpscaled;
    /** NRD was chosen automatically (no -Dvkrtx.denoiser): only the path tracer uses it. */
    private static boolean nrdPathOnly;
    private static final org.joml.Matrix4f curViewProj = new org.joml.Matrix4f();
    private static final org.joml.Matrix4f prevViewProj = new org.joml.Matrix4f();
    private static double prevCamX;
    private static double prevCamY;
    private static double prevCamZ;
    private static boolean havePrevCamera;
    private static int lastRadiusY;
    private static final RtExposure exposure = new RtExposure();
    private static long lastExposureNanos;
    private static volatile RtFrameParams lastParams;
    private static volatile RtMode lastPassMode;
    private static String lastPassLog = "";
    /** Last per-frame statistics of {@link RtWorld#update} (F3 / PerfMonitor), null before the first RT frame. */
    private static volatile RtWorld.FrameStats lastStats;
    private static long blasBuiltTotal = 0;

    private static RtMemory.Img output;
    private static RtMemory.Img outputDepth;
    private static RtMemory.Img compositeOutput;
    private static boolean outputReady = false;
    private static RtMemory.Buf statsBuf;
    /** God rays, bloom and sun glare post passes (Bedray atmosphere), run on the final image after the denoiser. */
    private static final RtAtmosphere atmosphere = new RtAtmosphere();
    private static final RtFogState fogState = new RtFogState();
    /** Per-pixel entity motion of rt_path for the DLSS-RR motion vectors (vec4 per pixel). */
    private static RtMemory.Buf entityMotionBuf;
    private static final boolean[] statsWritten = new boolean[STATS_SLOTS];
    private static long dispatchCounter = 0;
    private static long passFrames = 0;
    private static double lastHitPct = -1.0;
    private static double passMsInWindow = 0.0;
    private static int passFramesInWindow = 0;
    private static final RtFrameParams params = new RtFrameParams();
    /** Vanilla sky parameters of the frame (RtSkyState) and the resource pack sun / moon / cloud textures. */
    private static final RtSkyState skyState = new RtSkyState();
    private static RtSkyTextures skyTextures;
    private static long lastShadingLogNs = 0L;
    private static volatile float lastSunAngle = Float.NaN;

    /** Sun angle (radians) of the last RT frame, NaN before the first one (used by the autotest sun scenario). */
    public static float lastSunAngle() {
        return lastSunAngle;
    }
    private static int lastAtlasW = -1;

    // NRD denoiser (experimental, off by default): the gbuffer pipeline variant is used only while denoiseMode is an NRD mode.
    private static DenoiserMode denoiseMode = DenoiserMode.OFF;
    private static NrdAvailability denoiseAvailability;
    private static NrdDenoiser denoiser;
    private static boolean dropDenoiserNext = false;
    private static boolean dropDenoiserByError = false; // true = NrdException, false = user toggled OFF
    // [DLSS-RR] DLSS Ray Reconstruction denoiser (experimental)
    private static DlssRrDenoiser dlssRrDenoiser;
    private static boolean dropDlssRrDenoiserNext = false;
    private static boolean dropDlssRrByError = false;
    // A denoiser that failed to create or record is off for the rest of the session. The user's setting
    // (RtSettings.pathDenoiser, saved to bedray.toml) is never overwritten by a failure or by a startup flag.
    private static boolean dlssRrFailed = false;
    private static boolean nrdFailed = false;
    private static String lastDenoiserLog = "";
    /** Last resolver outcome, including a reason when the request fell back or could not be selected. */
    private static volatile String denoiserResolutionReason = "not_resolved";
    /** Last native create/record failure for the current world; retained for F3 after fallback/drop. */
    private static volatile String denoiserRuntimeFailure;

    private static long windowStartNs = 0L;
    private static int builtInWindow = 0;
    private static double buildMsInWindow = 0.0;
    private static int lastLoggedTotal = -1;
    private static int lastLoggedInstances = -1;

    private static RtSettings.Preset lastFramePreset;
    private static RtSettings.PathPreset lastFramePathPreset;
    private static RtSettings.PathDenoiser lastFramePathDenoiser;
    private static DenoiserMode lastFrameDenoiseMode = DenoiserMode.OFF;
    private static int lastFrameBounces = -1;
    private static int lastFrameSpp = -1;
    private static boolean lastFrameAutoExposure = true;

    /**
     * Screen-space depth composite pipeline for legacy raster entity compositing.
     * When native RT is active, this pipeline is permanently disabled (OFF): dynamic entities
     * are fully integrated into the RT TLAS, so pure RT radiance (finalSource=RT_OUTPUT) is copied
     * directly into the final render target without raster depth composite mixing.
     */
    private static RtCompositePipeline compositePipeline = null;
    private static final boolean LEGACY_RASTER_ENTITY_COMPOSITE = false;
    private static final String FINAL_OUTPUT_SOURCE = "RT_OUTPUT";
    private static final int REFLECTION_MASK = 0x03;
    private static final int SHADOW_MASK = 0x03;

    public static boolean isLegacyRasterEntityCompositeEnabled() {
        return LEGACY_RASTER_ENTITY_COMPOSITE && compositePipeline != null;
    }

    public static String getFinalSource() {
        return FINAL_OUTPUT_SOURCE;
    }

    public static int getReflectionMask() {
        return REFLECTION_MASK;
    }

    public static int getShadowMask() {
        return SHADOW_MASK;
    }

    public static io.github.andrexarlay.vulkancompat.rt.entity.RuntimeFeatureStatus getEntityInstanceStatus() {
        if (world != null && world.entityBlas() != null && world.entityBlas().getBlasAddress() != 0L && world.entityBlas().getPrimitiveCount() > 0) {
            return io.github.andrexarlay.vulkancompat.rt.entity.RuntimeFeatureStatus.READY;
        }
        return io.github.andrexarlay.vulkancompat.rt.entity.RuntimeFeatureStatus.EMPTY;
    }

    public static boolean hasActiveLocalLights() {
        return emitters != null && emitters.count() > 0;
    }

    public static String getHermeticTelemetry() {
        var entityStatus = getEntityInstanceStatus();
        String worldStatus = world == null ? "UNAVAILABLE" : (lastStats == null ? "INITIALIZING" : "READY");
        return String.format(Locale.ROOT,
            "[RT-HERMETIC] worldInstance=%s entityInstance=%s legacyRasterEntityComposite=%s finalSource=%s reflectionMask=0x%02X shadowMask=0x%02X",
            worldStatus,
            entityStatus,
            isLegacyRasterEntityCompositeEnabled() ? "ON" : "OFF",
            FINAL_OUTPUT_SOURCE,
            REFLECTION_MASK,
            SHADOW_MASK);
    }

    private RtFrameRenderer() {
    }

    private static volatile float lastExposureValue = 1.1f;
    private static volatile double lastAvgLum;

    /** Auto exposure of the last readback (autotest telemetry): value (EV multiplier) and the log average scene luminance before exposure. */
    public static float lastExposure() {
        return lastExposureValue;
    }

    public static double lastAverageLuminance() {
        return lastAvgLum;
    }

    /** Light list of the last frame for the autotest log: used / in range / total emitters (RtEmitters). */
    public static String lightTelemetry() {
        RtEmitters e = emitters;
        return e == null ? "none" : String.format(Locale.ROOT, "used=%d,in_range=%d,total=%d,entity_quads=%d/%d,capture=%s", e.count(),
            e.inRange(), e.totalEmitters(), e.entityUsed(), e.entityInRange(),
            io.github.andrexarlay.vulkancompat.rt.entity.EntityCaptureManager.getInstance().emitterTelemetry());
    }

    /** Statistics of the last RT frame (null before the first one or after a shutdown). */
    public static RtWorld.FrameStats lastStats() {
        return lastStats;
    }

    /** BLAS built since the game started (monotonic, for a per-second rate). */
    public static long blasBuiltTotal() {
        return blasBuiltTotal;
    }

    /** Active depth view from DLSS-RR or RT output depth, fallback for velocity reproject. */
    public static long getActiveDepthView() {
        if (dlssRrDenoiser != null && dlssRrDenoiser.gbViewZ() != null) {
            return dlssRrDenoiser.gbViewZ().view;
        }
        if (outputDepth != null) {
            return outputDepth.view;
        }
        return 0L;
    }

    /**
     * Convention of {@link #getActiveDepthView()} for the velocity pass: {@link io.github.andrexarlay.vulkancompat.rtx.upscaler.VelocityPipeline#DEPTH_LINEAR_VIEW_Z}
     * (DLSS-RR G-buffer view Z), {@link io.github.andrexarlay.vulkancompat.rtx.upscaler.VelocityPipeline#DEPTH_RAY_DISTANCE}
     * (RT outDepth), or -1 when there is no RT depth (raster depth copy in hardware convention).
     */
    public static int activeDepthConvention() {
        if (dlssRrDenoiser != null && dlssRrDenoiser.gbViewZ() != null) {
            return io.github.andrexarlay.vulkancompat.rtx.upscaler.VelocityPipeline.DEPTH_LINEAR_VIEW_Z;
        }
        return outputDepth != null ? io.github.andrexarlay.vulkancompat.rtx.upscaler.VelocityPipeline.DEPTH_RAY_DISTANCE : -1;
    }

    /** VkImage of {@link #getActiveDepthView()} (GENERAL layout), 0 = none; MotionProbe readback only. */
    public static long activeDepthImage() {
        if (dlssRrDenoiser != null && dlssRrDenoiser.gbViewZ() != null) {
            return dlssRrDenoiser.gbViewZ().image;
        }
        return outputDepth != null ? outputDepth.image : 0L;
    }

    /** Memory allocator of the RT world (null without a world); MotionProbe readback only. */
    public static RtMemory probeMemory() {
        return world != null ? world.memory() : null;
    }

    /**
     * Full unjittered projection of this frame as the engine renders the world, including view-bobbing, hurt and portal pose.
     * Primary rays use the matching camera-origin offset from the same pose.
     * {@code -Dvkrtx.rt.bobProjection=off} restores the old behaviour for A/B runs.
     */
    private static org.joml.Matrix4f frameProjection;
    private static final boolean BOB_PROJECTION = io.github.andrexarlay.vulkancompat.rtx.WorldTargetManager.bobProjectionEnabled();

    /** Albedo demodulation of the temporal history; {@code -Dvkrtx.rt.demodulate=off} restores the old behaviour for A/B runs. */
    private static final boolean DEMODULATE = !"off".equalsIgnoreCase(System.getProperty("vkrtx.rt.demodulate", "on"));

    /** Experimental NRD direct-sun path for A/B only; defaults to pass-through to preserve the existing shadow edges. */
    private static final boolean NRD_DENOISE_SUN = Boolean.getBoolean("vkrtx.nrd.denoiseSun");

    private static final boolean LOW_DISC = !"off".equalsIgnoreCase(System.getProperty("vkrtx.rt.lowDisc", "on"));

    private static volatile double lastHistPct = -1.0;

    /**
     * Temporal accumulation (both RT modes): percent of the counted hit pixels of a recent frame (2 frames old) whose
     * reprojected history passed the distance/normal test; -1 before the first readback.
     */
    public static double lastHistoryAcceptedPct() {
        return lastHistPct;
    }

    /** True while the RT world (BLAS/TLAS, pipeline) exists. */
    public static boolean worldReady() {
        return world != null;
    }

    /**
     * Resets temporal accumulation history of the active pass and re-arms auto-exposure.
     * Called whenever preset, denoiser (DLSS-RR / NRD / OFF), bounces, or SPP change.
     */
    public static void resetHistory() {
        if (pass != null) {
            pass.resetHistory();
        }
        exposure.reset();
        atmosphere.resetHistory();
        lastHistPct = -1.0;
    }

    /** True when this path owns the Hardware RT toggle, so the old PoC overlay must not run. */
    public static boolean supersedesPoc() {
        return RtGeometryGate.enabled();
    }

    /** The compute diagnostic owns this frame; close capture and retire hardware-only resources. */
    public static void suspendForComputeDiagnostic() {
        io.github.andrexarlay.vulkancompat.rt.entity.EntityCaptureManager.getInstance().endFrame();
        if (world != null) {
            shutdown("compute_diagnostic_scene");
        }
    }

    public static void render(CameraRenderState camera, RenderTarget target, LevelRenderState levelState) {
        // publish this frame's captured entity geometry (also closes the capture: the hand pass that follows is not world geometry)
        if (io.github.andrexarlay.vulkancompat.rt.entity.EntityCaptureManager.getInstance().isOpen()) {
            io.github.andrexarlay.vulkancompat.rt.entity.OffscreenBlockEntities.captureMissing(camera);
        }
        io.github.andrexarlay.vulkancompat.rt.entity.EntityCaptureManager.getInstance().endFrame();
        if (RtGeometryGate.failure() != null) {
            return; // failed this session (RtGeometryGate.markFailed), retried only after the user toggles RT
        }
        if (!RtGeometryGate.enabled()) {
            if (world != null) {
                shutdown("gate_off");
            }
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.level == null || camera == null || camera.pos == null) {
            return; // known pitfall: nothing to trace in the menu
        }
        // XeSS/DLSS: the RT image is traced into the world target at the render resolution with the same sub-pixel jitter as the
        // raster projection (CameraJitterManager); the upscaler then reconstructs it to the window (depth / motion vectors from
        // the raster pass, which draws the same chunk geometry).
        boolean upscaled = TemporalUpscaler.isActive();
        if (upscaled != lastUpscaled) {
            lastUpscaled = upscaled;
            LOGGER.info("[VKB-RT] temporal_upscaler active={} backend={} input={}x{} output={}x{} (RT traced at the input size, jittered)",
                upscaled, TemporalUpscaler.active(), upscaled ? TemporalUpscaler.inputWidth() : 0,
                upscaled ? TemporalUpscaler.inputHeight() : 0, upscaled ? TemporalUpscaler.outputWidth() : 0,
                upscaled ? TemporalUpscaler.outputHeight() : 0);
        }
        try {
            frame(camera, target, levelState);
        } catch (RtFailure f) {
            fail(f.stage(), f.result(), f.getCause() != null ? f.getCause() : f);
        } catch (RuntimeException | LinkageError e) {
            fail("frame", e.getClass().getSimpleName() + ": " + e.getMessage(), e);
        }
    }

    private static void frame(CameraRenderState camera, RenderTarget target, LevelRenderState levelState) {
        VulkanDevice vulkanDevice = VulkanCapabilityChecker.getVulkanDevice();
        if (vulkanDevice == null) {
            return;
        }
        VkDevice device = vulkanDevice.vkDevice();
        if (world == null) {
            requireRayTracingDevice(device);
            world = new RtWorld(device);
            selectDenoiser();
            pass = createPass(device, passModeFor(RtConfig.mode()), wantGbuffer(passModeFor(RtConfig.mode())));
            LOGGER.info("[VKB-RT] world_ready debug_mode={}", RtConfig.DEBUG_NAME.isEmpty() ? "none" : RtConfig.DEBUG_NAME);
            if (RtConfig.isUnknownMode(RtConfig.DEBUG_NAME)) {
                LOGGER.warn("[VKB-RT] unknown -Dvkbridge.rt.debug={} (use normals|depth|uv|section), no debug view", RtConfig.DEBUG_NAME);
            }
        }

        if (dropDenoiserNext) {
            dropDenoiser(device);
        }
        // [DLSS-RR]
        if (dropDlssRrDenoiserNext) {
            dropDlssRrDenoiser(device);
        }

        if (denoiseAvailability == null) {
            try {
                denoiseAvailability = BridgeNrdLocator.locate(VulkanBridge.libraries());
            } catch (Throwable t) {
                denoiseAvailability = NrdAvailability.unavailable("Bedray NativeLibraries not available: " + t.getMessage());
            }
        }

        denoiseMode = resolveDenoiseMode();

        RtSettings.Preset curPreset = RtSettings.preset;
        RtSettings.PathPreset curPathPreset = RtSettings.pathPreset;
        RtSettings.PathDenoiser curPathDenoiser = RtSettings.denoiser();
        int curBounces = RtSettings.bounces();
        int curSpp = RtSettings.spp();
        boolean curAutoExposure = RtSettings.autoExposure();

        boolean presetOrDenoiserChanged = (lastFrameBounces != -1) && (
            curPreset != lastFramePreset
            || curPathPreset != lastFramePathPreset
            || curPathDenoiser != lastFramePathDenoiser
            || denoiseMode != lastFrameDenoiseMode
            || curBounces != lastFrameBounces
            || curSpp != lastFrameSpp
            || curAutoExposure != lastFrameAutoExposure
        );

        if (presetOrDenoiserChanged) {
            LOGGER.info("[VKB-RT] resetHistory: preset({}->{}) pt_preset({}->{}) denoiser({}->{}) denoiseMode({}->{}) bounces({}->{}) spp({}->{}) autoExp({}->{})",
                lastFramePreset, curPreset, lastFramePathPreset, curPathPreset, lastFramePathDenoiser, curPathDenoiser,
                lastFrameDenoiseMode, denoiseMode, lastFrameBounces, curBounces, lastFrameSpp, curSpp, lastFrameAutoExposure, curAutoExposure);
            resetHistory();
        }

        lastFramePreset = curPreset;
        lastFramePathPreset = curPathPreset;
        lastFramePathDenoiser = curPathDenoiser;
        lastFrameDenoiseMode = denoiseMode;
        lastFrameBounces = curBounces;
        lastFrameSpp = curSpp;
        lastFrameAutoExposure = curAutoExposure;

        if (denoiseMode != DenoiserMode.DLSS_RR && dlssRrDenoiser != null) {
            dlssRrDenoiser.destroy();
            dlssRrDenoiser = null;
        }
        if ((!denoiseMode.isNrd() || (denoiser != null && denoiser.mode() != denoiseMode)) && denoiser != null) {
            denoiser.destroy();
            denoiser = null;
        }

        RtMode want = passModeFor(RtConfig.mode());
        boolean wantGb = wantGbuffer(want);
        if (pass != null && (pass.mode() != want || pass.usesGbuffer() != wantGb)) {
            LOGGER.info("[VKB-RT] mode_switch from={} to={} nrd_gbuffer={}", pass.mode().logName(), want.logName(), wantGb);
            pass.destroy();
            pass = createPass(device, want, wantGb);
            resetHistory();
            lastPassLog = "";
        }
        long frameStart = System.nanoTime();
        VulkanCommandEncoder encoder = vulkanDevice.createCommandEncoder();
        VkCommandBuffer cmd = encoder.allocateAndBeginTransientCommandBuffer();
        VkBarriers.fullMemoryBarrier(cmd);
        int radiusXz = RtConfig.radiusXz(Minecraft.getInstance().options.getEffectiveRenderDistance());
        int radiusY = RtConfig.radiusY(radiusXz);
        lastRadiusXz = radiusXz;
        lastRadiusY = radiusY;
        RtWorld.FrameStats stats = world.update(cmd, camera.pos.x, camera.pos.y, camera.pos.z, radiusXz, radiusY);
        lastStats = stats;
        blasBuiltTotal += stats.builtThisFrame();
        boolean traced = false;
        if (stats.instances() > 0 && camera.projectionMatrix != null && camera.viewRotationMatrix != null && target != null) {
            recordPrimaryRays(cmd, device, camera, target, levelState,
                world.nearestBuiltSq() <= SCENE_CHANGE_RADIUS_SQ);
            traced = true;
        }
        VkBarriers.fullMemoryBarrier(cmd);
        RtFailure.check(VK10.vkEndCommandBuffer(cmd), "end_command_buffer");
        encoder.execute(cmd);
        if (traced) {
            passFrames++;
            passFramesInWindow++;
            passMsInWindow += (System.nanoTime() - frameStart) / 1.0e6;
        }
        logWindow(stats, target);
    }

    /** Output image, dispatch and (debug view only) the copy into the engine colour image, inside the open command buffer. */
    private static void recordPrimaryRays(VkCommandBuffer cmd, VkDevice device, CameraRenderState camera, RenderTarget target,
                                         LevelRenderState levelState, boolean sceneChanged) {
        SkyRenderState sky = levelState.skyRenderState;
        frameProjection = BOB_PROJECTION ? io.github.andrexarlay.vulkancompat.rtx.WorldTargetManager.levelProjectionOr(camera.projectionMatrix) : camera.projectionMatrix;
        org.joml.Vector3f bobOrigin = BOB_PROJECTION
            ? io.github.andrexarlay.vulkancompat.rtx.WorldTargetManager.levelCameraOriginOr(null)
            : new org.joml.Vector3f();
        if (BOB_PROJECTION && (dispatchCounter % 30) == 0 && frameProjection != camera.projectionMatrix) {
            float md = 0f;
            for (int c = 0; c < 4; c++) {
                for (int r = 0; r < 4; r++) {
                    md = Math.max(md, Math.abs(frameProjection.get(c, r) - camera.projectionMatrix.get(c, r)));
                }
            }
            LOGGER.info("[VKB-RT] bob_projection max_abs_diff={} m11={} cam_m11={} m21={} m20={} yRot={} xRot={}", String.format(Locale.ROOT, "%.5f", md),
                frameProjection.m11(), camera.projectionMatrix.m11(), frameProjection.m21(), frameProjection.m20(), camera.yRot, camera.xRot);
        }
        int w = target.width;
        int h = target.height;
        if (output == null || output.width != w || output.height != h) {
            if (output != null) {
                VK10.vkDeviceWaitIdle(device); // frames in flight may still write or copy the old image
                world.memory().destroy(output);
                if (outputDepth != null) {
                    world.memory().destroy(outputDepth);
                    outputDepth = null;
                }
                if (compositeOutput != null) {
                    world.memory().destroy(compositeOutput);
                    compositeOutput = null;
                }
            }
            output = world.memory().createImage("rt_output", w, h, VK10.VK_FORMAT_R8G8B8A8_UNORM,
                VK10.VK_IMAGE_USAGE_STORAGE_BIT | VK10.VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK10.VK_IMAGE_USAGE_SAMPLED_BIT);
            outputDepth = world.memory().createImage("rt_depth", w, h, VK10.VK_FORMAT_R32_SFLOAT,
                VK10.VK_IMAGE_USAGE_STORAGE_BIT | VK10.VK_IMAGE_USAGE_SAMPLED_BIT);
            compositeOutput = world.memory().createImage("rt_composite", w, h, VK10.VK_FORMAT_R8G8B8A8_UNORM,
                VK10.VK_IMAGE_USAGE_STORAGE_BIT | VK10.VK_IMAGE_USAGE_TRANSFER_SRC_BIT);
            outputReady = false;
            LOGGER.info("[VKB-RT] rt_output {}x{} RGBA8, rt_depth R32F, rt_composite RGBA8", w, h);
        }
        if (statsBuf == null) {
            statsBuf = world.memory().createBuffer("rt_stats", (long) STATS_SLOTS * STATS_STRIDE,
                VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT
                    | VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT, true);
        }
        if (!outputReady) {
            VkBarriers.transition(cmd, output.image, VK10.VK_IMAGE_ASPECT_COLOR_BIT,
                VK10.VK_IMAGE_LAYOUT_UNDEFINED, VK10.VK_IMAGE_LAYOUT_GENERAL);
            VkBarriers.transition(cmd, outputDepth.image, VK10.VK_IMAGE_ASPECT_COLOR_BIT,
                VK10.VK_IMAGE_LAYOUT_UNDEFINED, VK10.VK_IMAGE_LAYOUT_GENERAL);
            VkBarriers.transition(cmd, compositeOutput.image, VK10.VK_IMAGE_ASPECT_COLOR_BIT,
                VK10.VK_IMAGE_LAYOUT_UNDEFINED, VK10.VK_IMAGE_LAYOUT_GENERAL);
            outputReady = true;
        }
        AbstractTexture atlasTexture = Minecraft.getInstance().getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS);
        long atlasView = atlasTexture != null ? WorldTargetManager.extractVkImageView(atlasTexture.getTextureView()) : 0L;
        if (atlasView == 0L) {
            throw new RtFailure("atlas", "block atlas VkImageView unavailable");
        }
        int atlasW = atlasTexture.getTexture().getWidth(0);
        int atlasH = atlasTexture.getTexture().getHeight(0);
        if (denoiseMode == DenoiserMode.DLSS_RR) {
            // [DLSS-RR]
            if (pass.usesGbuffer() && dlssRrDenoiser == null) {
                createDlssRrDenoiser(device, w, h);
            }
            if (dlssRrDenoiser != null) {
                dlssRrDenoiser.ensure(cmd, w, h, output);
            }
        }
        if (denoiseMode.isNrd()) {
            if (pass.usesGbuffer() && denoiser == null) {
                createDenoiser(device, w, h);
            } else if (denoiser != null) {
                denoiser.ensure(w, h, output.view);
            }
        }
        boolean useDenoiser = pass.usesGbuffer() && (denoiser != null || dlssRrDenoiser != null);
        if (materials == null) {
            materials = new RtMaterialAtlas(world.memory());
        }
        materials.ensure(cmd, (TextureAtlas) atlasTexture, atlasW, atlasH, Minecraft.getInstance().getResourceManager());
        pass.prepare(cmd, world.memory(), w, h);
        long[] gbViews = null;
        if (useDenoiser) {
            if (dlssRrDenoiser != null) {
                gbViews = dlssRrDenoiser.gbufferViews();
            } else if (denoiser != null) {
                gbViews = denoiser.gbufferViews();
            }
        }
        pass.bind(new RtPipeline.Views(world.tlasHandle(), world.sectionTable(), output.view, atlasView, materials.materialView(),
            materials.normalView(), 0L, gbViews, outputDepth != null ? outputDepth.view : 0L));

        // The slot read now was written by the dispatch two frames ago (the engine keeps at most 2 submissions in flight).
        int writeSlot = (int) (dispatchCounter % STATS_SLOTS);
        int readSlot = (writeSlot + 1) % STATS_SLOTS;
        if (statsWritten[readSlot]) {
            long base = statsBuf.mapped + (long) readSlot * STATS_STRIDE;
            int hits = MemoryUtil.memGetInt(base);
            int samples = MemoryUtil.memGetInt(base + 4);
            if (hits > 0) {
                lastHistPct = 100.0 * MemoryUtil.memGetInt(base + 12) / hits;
            }
            if (samples > 0) {
                lastHitPct = 100.0 * hits / samples;
                exposure.setMax(RtSkyState.maxExposure(params.key.day));
                long expNow = System.nanoTime();
                exposure.observe(Integer.toUnsignedLong(MemoryUtil.memGetInt(base + 8)), samples,
                    lastExposureNanos == 0L ? 1.0f / 60.0f : (float) ((expNow - lastExposureNanos) / 1.0e9));
                lastExposureNanos = expNow;
                lastExposureValue = exposure.value();
                long sumLog = Integer.toUnsignedLong(MemoryUtil.memGetInt(base + 8));
                lastAvgLum = sumLog == 0L ? 0.0 : Math.pow(2.0, sumLog / 64.0 / samples - 16.0);
            }
            statsWritten[readSlot] = false;
        }
        long writeOffset = (long) writeSlot * STATS_STRIDE;
        VK10.vkCmdFillBuffer(cmd, statsBuf.handle, writeOffset, 16, 0);
        if (emitters == null) {
            emitters = new RtEmitters(world.memory());
        }
        float lightRange = RtConfig.mode() == RtMode.PATH_TRACED
            ? Math.min(io.github.andrexarlay.vulkancompat.rtx.config.RtSettings.pathMaxDistance(), 128)
            : io.github.andrexarlay.vulkancompat.rtx.config.RtSettings.emissiveDistance() + 8f;
        // Light of items: the glowing PIXELS of emissive items are quad emitters (EntityCaptureManager): dropped items and items
        // of entities of this frame, and the item in the first-person hand (captured by the hand pass of the previous frame).
        // There is no point light "at the player".
        float[] entityEmitters = io.github.andrexarlay.vulkancompat.rt.entity.EntityCaptureManager.getInstance()
            .emitterQuadsForFrame(camera.viewRotationMatrix);
        emitters.update(world, materials.lastBake(), camera.pos.x, camera.pos.y, camera.pos.z, lightRange,
            io.github.andrexarlay.vulkancompat.rtx.config.RtSettings.lightBudget(), entityEmitters,
            entityEmitters.length / RtEmitters.FLOATS);
        long hdr = statsBuf.mapped + writeOffset;
        MemoryUtil.memPutInt(hdr + 40, emitters.count());
        MemoryUtil.memPutFloat(hdr + 44, exposure.applied((params.flags & RtFrameParams.FLAG_AUTO_EXPOSURE) != 0,
            io.github.andrexarlay.vulkancompat.rtx.config.RtSettings.exposureEvTenths()));
        MemoryUtil.memPutFloat(hdr + 16, (float) (camera.pos.x - emitters.originX()));
        MemoryUtil.memPutFloat(hdr + 20, (float) (camera.pos.y - emitters.originY()));
        MemoryUtil.memPutFloat(hdr + 24, (float) (camera.pos.z - emitters.originZ()));
        MemoryUtil.memPutLong(hdr + 32, emitters.address());
        VkBarriers.fullMemoryBarrier(cmd);

        // stable (unjittered) matrix for both rays and accumulation decision:
        // camera rays apply subpixel jitter explicitly via cameraJitter offset in StatsBuf;
        // invViewProj must stay strictly unjittered so forward, viewZ, and motion vectors are jitter-free.
        InstanceMath.inverseViewProjection(frameProjection, camera.viewRotationMatrix, params.invViewProjStable);
        params.invViewProj.set(params.invViewProjStable);
        params.statsAddress = statsBuf.address + writeOffset;
        params.flipY = RtConfig.FLIP_Y;
        params.tonemap = RtConfig.TONEMAP_ACES;
        params.frame = (int) dispatchCounter;
        params.cameraX = camera.pos.x;
        params.cameraY = camera.pos.y;
        params.cameraZ = camera.pos.z;
        params.sceneChanged = sceneChanged;
        lastSunAngle = sky.sunAngle;
        boolean hasSky = sky.skybox == DimensionType.Skybox.OVERWORLD;
        if (skyTextures == null) {
            skyTextures = new RtSkyTextures(world.memory());
        }
        skyTextures.ensure(device);
        skyState.collect(levelState, camera.pos.x + bobOrigin.x, camera.pos.y + bobOrigin.y, camera.pos.z + bobOrigin.z, RtConfig.SUN_FLIP_X,
            skyTextures.cloudWidth(), skyTextures.cloudHeight());
        RtSky.keyLight(sky.sunAngle, sky.moonAngle, RtConfig.SUN_FLIP_X, hasSky, skyState.moonLight, skyState.rainBrightness,
            params.key);
        skyState.write(hdr, skyTextures.address(), RtConfig.SUN_FLIP_X);
        // vanilla fog of the game (render distance edge, biome / weather / water) + aerial perspective: rt_common.glsl fogTransmittanceHit
        fogState.collect(Minecraft.getInstance(), levelState.worldPartialTicks, lastRadiusXz, skyState.rainBrightness);
        fogState.write(hdr, RtAtmosphereSettings.fogVanilla(), RtAtmosphereSettings.fogAerial(), RtAtmosphereSettings.chunkFadeSeconds());
        if (sky.skyColor != null) {
            params.skyColor[0] = sky.skyColor.x();
            params.skyColor[1] = sky.skyColor.y();
            params.skyColor[2] = sky.skyColor.z();
        }
        params.volumetricFogEnabled = io.github.andrexarlay.vulkancompat.rtx.config.RtSettings.volumetricFogEnabled();
        params.fogDensity = io.github.andrexarlay.vulkancompat.rtx.config.RtSettings.fogDensity();
        params.fogInScattering = io.github.andrexarlay.vulkancompat.rtx.config.RtSettings.fogInScattering();
        params.fogDistanceScale = io.github.andrexarlay.vulkancompat.rtx.config.RtSettings.fogDistanceScale();
        MaterialAtlasBaker.Result bake = materials.lastBake();
        params.flags = RtFrameParams.FLAG_MATERIALS | (bake != null && bake.labN() > 0 ? RtFrameParams.FLAG_NORMAL_MAPS : 0)
            | (io.github.andrexarlay.vulkancompat.rtx.config.RtSettings.autoNormalsOn() ? RtFrameParams.FLAG_AUTO_NORMALS : 0)
            | (params.volumetricFogEnabled ? RtFrameParams.FLAG_VOLUMETRIC_FOG : 0)
            | (io.github.andrexarlay.vulkancompat.rtx.config.RtSettings.waterStyle() == 1 ? RtFrameParams.FLAG_WATER_REALISTIC : 0)
            | (io.github.andrexarlay.vulkancompat.rtx.config.RtSettings.vibrantVisuals() ? RtFrameParams.FLAG_VIBRANT_VISUALS : 0)
            | (io.github.andrexarlay.vulkancompat.rtx.config.RtSettings.caustics() ? RtFrameParams.FLAG_CAUSTICS : 0)
            | ((denoiseMode == DenoiserMode.DLSS_RR && dlssRrDenoiser != null
                || denoiseMode.isNrd() && NRD_DENOISE_SUN) ? RtFrameParams.FLAG_SUN_DENOISED : 0)
            | (DEMODULATE ? RtFrameParams.FLAG_DEMODULATE : 0)
            | (LOW_DISC ? RtFrameParams.FLAG_LOWDISC : 0);
        pass.configure(params, useDenoiser);
        MemoryUtil.memPutFloat(hdr + 48, params.giDistance);
        MemoryUtil.memPutFloat(hdr + 52, params.emissiveDistance);
        MemoryUtil.memPutFloat(hdr + 56, params.aoRadius);
        MemoryUtil.memPutFloat(hdr + 60, io.github.andrexarlay.vulkancompat.rtx.config.RtSettings.autoNormalStrengthPercent() / 100f);
        MemoryUtil.memPutFloat(hdr + 64, io.github.andrexarlay.vulkancompat.rtx.config.RtSettings.sunRadiusRad());
        MemoryUtil.memPutFloat(hdr + 68, params.historyCap);
        // Temporal reprojection uses the full pose in both projections. Hit positions include the current bob origin, while
        // camDelta remains the raw camera-position delta; the prior full projection already carries the previous bob origin.
        curViewProj.set(frameProjection).mul(camera.viewRotationMatrix);
        if (!havePrevCamera) {
            prevViewProj.set(curViewProj);
            prevCamX = camera.pos.x;
            prevCamY = camera.pos.y;
            prevCamZ = camera.pos.z;
            havePrevCamera = true;
        }
        prevViewProj.get(80, MemoryUtil.memByteBuffer(hdr, STATS_STRIDE));
        float dx = (float) (camera.pos.x - prevCamX);
        float dy = (float) (camera.pos.y - prevCamY);
        float dz = (float) (camera.pos.z - prevCamZ);
        boolean camMoved = Math.abs(dx) > 1.0e-7f || Math.abs(dy) > 1.0e-7f || Math.abs(dz) > 1.0e-7f
            || !prevViewProj.equals(curViewProj, 1.0e-7f);
        MemoryUtil.memPutFloat(hdr + 144, dx);
        MemoryUtil.memPutFloat(hdr + 148, dy);
        MemoryUtil.memPutFloat(hdr + 152, dz);
        MemoryUtil.memPutFloat(hdr + 156, camMoved ? 1.0f : 0.0f);
        float baseDistance = (float) Math.max(32, params.rayDistance);
        float fogDistance = baseDistance * params.fogDistanceScale;
        MemoryUtil.memPutFloat(hdr + 160, params.fogDensity);
        MemoryUtil.memPutFloat(hdr + 164, params.fogInScattering);
        MemoryUtil.memPutFloat(hdr + 168, fogDistance);
        // 2 = distance fog + the former in-shader god rays, 1 = distance fog only (the light shafts come from RtAtmosphere)
        MemoryUtil.memPutInt(hdr + 172, params.volumetricFogEnabled
            ? (RtAtmosphereSettings.godRays() == RtAtmosphereSettings.GodRays.INLINE ? 2 : 1) : 0);
        MemoryUtil.memPutFloat(hdr + 176, params.fogDistanceScale);
        // entity geometry of this frame (RtEntityGeometryManager ring slice; the same slice the entity BLAS was built from)
        RtEntityGeometryManager eg = RtEntityGeometryManager.INSTANCE;
        boolean entOk = world.entityBlas().getPrimitiveCount() > 0;
        MemoryUtil.memPutInt(hdr + 180, entOk ? eg.frameTriangles() : 0);
        MemoryUtil.memPutLong(hdr + 184, entOk ? eg.framePosAddress() : 0L);
        MemoryUtil.memPutLong(hdr + 192, entOk ? eg.frameIndexAddress() : 0L);
        MemoryUtil.memPutLong(hdr + 200, entOk ? eg.frameAttrAddress() : 0L);
        MemoryUtil.memPutLong(hdr + 208, entOk ? eg.framePrevAddress() : 0L);
        MemoryUtil.memPutLong(hdr + 216, eg.atlasAddress());
        MemoryUtil.memPutLong(hdr + 224, eg.rectAddress());
        long motionAddr = 0L;
        if (dlssRrDenoiser != null && useDenoiser && !Boolean.getBoolean("vkrtx.entityMotion.off")) {
            long need = (long) w * h * 16L;
            if (entityMotionBuf == null || entityMotionBuf.size < need) {
                if (entityMotionBuf != null) {
                    VK10.vkDeviceWaitIdle(device);
                    world.memory().destroy(entityMotionBuf);
                }
                entityMotionBuf = world.memory().createBuffer("entity_motion_px", need,
                    VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | org.lwjgl.vulkan.VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT, false);
            }
            motionAddr = entityMotionBuf.address;
            dlssRrDenoiser.entityMotionAddress = motionAddr;
        }
        MemoryUtil.memPutLong(hdr + 232, motionAddr);
        MemoryUtil.memPutInt(hdr + 240, eg.atlasAddress() != 0L ? io.github.andrexarlay.vulkancompat.rt.entity.EntityTextureAtlas.SIZE : 0);
        float jitterPxX = 0.0f;
        float jitterPxY = 0.0f;
        if (io.github.andrexarlay.vulkancompat.rtx.upscaler.CameraJitterManager.isJitterActive()
            && io.github.andrexarlay.vulkancompat.rtx.upscaler.CameraJitterManager.isActive()
            && io.github.andrexarlay.vulkancompat.rtx.upscaler.CameraJitterManager.isScalingActive()) {
            jitterPxX = io.github.andrexarlay.vulkancompat.rtx.upscaler.CameraJitterManager.getCurrentPixelJitterX();
            jitterPxY = io.github.andrexarlay.vulkancompat.rtx.upscaler.CameraJitterManager.getCurrentPixelJitterY();
        }
        MemoryUtil.memPutFloat(hdr + 248, jitterPxX);
        MemoryUtil.memPutFloat(hdr + 252, jitterPxY);
        MemoryUtil.memPutFloat(hdr + 384, bobOrigin.x);
        MemoryUtil.memPutFloat(hdr + 388, bobOrigin.y);
        MemoryUtil.memPutFloat(hdr + 392, bobOrigin.z);
        MemoryUtil.memPutFloat(hdr + 396, 0.0f);
        org.joml.Vector3f rawForward = io.github.andrexarlay.vulkancompat.rt.world.CameraBobPose.rawForward(
            camera.viewRotationMatrix, new org.joml.Vector3f());
        MemoryUtil.memPutFloat(hdr + 400, rawForward.x);
        MemoryUtil.memPutFloat(hdr + 404, rawForward.y);
        MemoryUtil.memPutFloat(hdr + 408, rawForward.z);
        // StatsBuf.cameraForward.w: strength of the optional baked vanilla light fill (RtSettings.bakedLightFill, rays mode)
        MemoryUtil.memPutFloat(hdr + 412, io.github.andrexarlay.vulkancompat.rtx.config.RtSettings.bakedLightFill());
        org.joml.Matrix4f probePrev = io.github.andrexarlay.vulkancompat.rt.denoise.StabilityProbe.recording() ? new org.joml.Matrix4f(prevViewProj) : null;
        prevViewProj.set(curViewProj);
        prevCamX = camera.pos.x;
        prevCamY = camera.pos.y;
        prevCamZ = camera.pos.z;
        lastParams = params;
        lastPassMode = pass.mode();
        logShading(atlasW, atlasH, hasSky, sky);
        skyState.log(params.key, exposure.value(), skyTextures.loadedMask());
        logPass(pass.describe(params));
        pass.dispatch(cmd, params, w, h);
        statsWritten[writeSlot] = true;
        dispatchCounter++;
        VkBarriers.fullMemoryBarrier(cmd);
        if (probePrev != null && outputDepth != null) {
            io.github.andrexarlay.vulkancompat.rt.denoise.StabilityProbe.record(cmd, world.memory(), outputDepth.image, w, h,
                new org.joml.Matrix4f(params.invViewProjStable), probePrev, dx, dy, dz);
            VkBarriers.fullMemoryBarrier(cmd);
        }
        io.github.andrexarlay.vulkancompat.rt.denoise.StabilityProbe.tick();
        if (useDenoiser) {
            if (dlssRrDenoiser != null) {
                // [DLSS-RR] Record DLSS Ray Reconstruction
                try {
                    float composeExposure = exposure.applied((params.flags & RtFrameParams.FLAG_AUTO_EXPOSURE) != 0,
                        io.github.andrexarlay.vulkancompat.rtx.config.RtSettings.exposureEvTenths());
                    long depthView = (dlssRrDenoiser != null && dlssRrDenoiser.gbViewZ() != null)
                        ? dlssRrDenoiser.gbViewZ().view
                        : (target != null ? WorldTargetManager.extractVkImageView(target.getDepthTextureView()) : 0L);
                    boolean jittered = io.github.andrexarlay.vulkancompat.rtx.upscaler.CameraJitterManager.isJitterActive()
                        && io.github.andrexarlay.vulkancompat.rtx.upscaler.CameraJitterManager.isActive()
                        && io.github.andrexarlay.vulkancompat.rtx.upscaler.CameraJitterManager.isScalingActive();
                    float rrJitterX = jittered
                        ? io.github.andrexarlay.vulkancompat.rtx.upscaler.CameraJitterManager.getCurrentPixelJitterX()
                            * io.github.andrexarlay.vulkancompat.rtx.upscaler.dlss.DlssConfig.jitterSignX()
                        : 0.0f;
                    float rrJitterY = jittered
                        ? io.github.andrexarlay.vulkancompat.rtx.upscaler.CameraJitterManager.getCurrentPixelJitterY()
                            * io.github.andrexarlay.vulkancompat.rtx.upscaler.dlss.DlssConfig.jitterSignY()
                        : 0.0f;
                    dlssRrDenoiser.record(cmd, frameProjection, camera.viewRotationMatrix, camera.pos.x, camera.pos.y, camera.pos.z,
                        params.tonemap, composeExposure, depthView, rrJitterX, rrJitterY);
                    VkBarriers.fullMemoryBarrier(cmd);
                } catch (Exception e) {
                    denoiserRuntimeFailure = "dlss_rr_record: " + String.valueOf(e.getMessage());
                    LOGGER.error("[DLSS-RR] FAILED record: {} (denoiser disabled for this session, raw RT image is shown)",
                        e.getMessage(), e);
                    dropDlssRrDenoiserNext = true;
                    dropDlssRrByError = true;
                }
            } else if (denoiser != null) {
                try {
                    float composeExposure = exposure.applied((params.flags & RtFrameParams.FLAG_AUTO_EXPOSURE) != 0,
                        io.github.andrexarlay.vulkancompat.rtx.config.RtSettings.exposureEvTenths());
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
                    denoiser.record(cmd, camera.projectionMatrix, frameProjection, camera.viewRotationMatrix,
                        camera.pos.x, camera.pos.y, camera.pos.z, params.tonemap, composeExposure,
                        nrdJitterX, nrdJitterY, nrdJitterPrevX, nrdJitterPrevY);
                    VkBarriers.fullMemoryBarrier(cmd);
                } catch (NrdException e) {
                    denoiserRuntimeFailure = "nrd_record " + e.stage() + ": " + String.valueOf(e.getMessage());
                    // The command buffer stays valid (vkb_nrd validates before recording); the raw image is shown this frame and the
                    // denoiser is dropped at the start of the next one.
                    LOGGER.error("[VKB-NRD] FAILED stage={} {} (denoiser disabled for this session, raw RT image is shown)",
                        e.stage(), e.getMessage(), e);
                    dropDenoiserNext = true;
                    dropDenoiserByError = true;
                }
            }
        }

        // particles of the game (drawn into their own layer by RtParticleLayer.replay) over the denoised image, before bloom / god rays
        io.github.andrexarlay.vulkancompat.rt.particles.RtParticleLayer.composite(cmd, device, output.view, w, h);

        // god rays + bloom + sun glare on the denoised final image (reads the ray distance, writes only rt_output)
        atmosphere.record(cmd, device, world.memory(), params, curViewProj,
            new RtPipeline.Views(world.tlasHandle(), world.sectionTable(), output.view, atlasView, materials.materialView(),
                materials.normalView(), 0L, null, outputDepth.view),
            w, h, bobOrigin.x, bobOrigin.y, bobOrigin.z);

        if (RtConfig.displayed()) {
            long dst = WorldTargetManager.extractVkImage(target);
            if (dst == 0L) {
                throw new RtFailure("copy_to_target", "engine VkImage of the main/world target is 0");
            }
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkImageCopy.Buffer region = VkImageCopy.calloc(1, stack);
                region.get(0).srcSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
                region.get(0).dstSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
                region.get(0).extent().width(w).height(h).depth(1);
                VK10.vkCmdCopyImage(cmd, output.image, VK10.VK_IMAGE_LAYOUT_GENERAL, dst, VK10.VK_IMAGE_LAYOUT_GENERAL, region);
            }
        }
    }

    /** Once per world: locates the NRD natives and logs the denoiser that will be used. */
    private static void selectDenoiser() {
        denoiserRuntimeFailure = null;
        denoiserResolutionReason = "not_resolved";
        try {
            denoiseAvailability = BridgeNrdLocator.locate(VulkanBridge.libraries());
        } catch (Throwable e) {
            denoiseAvailability = NrdAvailability.unavailable("Bedray NativeLibraries not available: " + e.getMessage());
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
     * Path tracing denoiser requested for this frame: reads the setting (RtSettings.denoiser()).
     * Falls back to OFF when the library is missing or the denoiser failed earlier in this session;
     * every change of the outcome is logged once (rule 4).
     */
    private static DenoiserMode resolveDenoiseMode() {
        RtSettings.PathDenoiser want = RtSettings.denoiser();
        DenoiserMode mode = DenoiserMode.OFF;
        String reason = "setting";
        switch (want) {
            case OFF -> { }
            case DLSS_RR -> {
                DlssAvailability.Library lib = DlssAvailability.library();
                if (dlssRrFailed) {
                    reason = "dlss_rr_failed_earlier_this_session";
                } else if (io.github.andrexarlay.vulkancompat.rtx.framegen.StreamlineBootstrap.isReady()
                    && !io.github.andrexarlay.vulkancompat.rtx.framegen.StreamlineBootstrap.rrViaStreamline()) {
                    // Frame generation owns NGX: raw NGX cannot create Ray Reconstruction next to it (0xBAD0000B in the log of the
                    // owner's configuration), only the Streamline plugin can. Say so instead of trying and failing.
                    reason = "frame_generation_needs_sl.dlss_d.dll+nvngx_dlssd.dll_in_the_streamline_folder";
                } else if (lib == null || !lib.usable()) {
                    reason = "dlss_library_unusable " + (lib == null ? "null" : lib.reason());
                } else {
                    mode = DenoiserMode.DLSS_RR;
                }
                if (mode == DenoiserMode.OFF && !nrdFailed && denoiseAvailability != null && denoiseAvailability.usable()) {
                    mode = DenoiserMode.NRD_REBLUR;
                    reason += " -> fallback_to_nrd";
                }
            }
            case NRD -> {
                if (nrdFailed) {
                    reason = "nrd_failed_earlier_this_session";
                } else if (denoiseAvailability == null || !denoiseAvailability.usable()) {
                    reason = "nrd_library_unusable " + (denoiseAvailability == null ? "null" : denoiseAvailability.reason());
                } else {
                    DenoiserMode flag = DenoiserConfig.requested();
                    mode = flag.isNrd() ? flag : DenoiserMode.NRD_REBLUR;
                }
            }
        }
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
    private static void createDlssRrDenoiser(VkDevice device, int w, int h) {
        try {
            dlssRrDenoiser = new DlssRrDenoiser(device, world.memory(), w, h, output);
        } catch (Exception e) {
            denoiserRuntimeFailure = "dlss_rr_init: " + String.valueOf(e.getMessage());
            LOGGER.error("[DLSS-RR] status=disabled reason=init {} (falling back to NRD if available)", e.getMessage(), e);
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
    private static void dropDlssRrDenoiser(VkDevice device) {
        dropDlssRrDenoiserNext = false;
        if (dlssRrDenoiser != null) {
            dlssRrDenoiser.destroy();
            dlssRrDenoiser = null;
        }
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

    private static void createDenoiser(VkDevice device, int w, int h) {
        try {
            denoiser = new NrdDenoiser(device, world.memory(), denoiseMode, denoiseAvailability, w, h, output.view);
        } catch (NrdException | RtFailure e) {
            denoiserRuntimeFailure = "nrd_init: " + String.valueOf(e.getMessage());
            LOGGER.error("[VKB-NRD] status=disabled reason=init {} (off for this session, setting kept)", e.getMessage(), e);
            nrdFailed = true;
            denoiseMode = DenoiserMode.OFF;
            rebuildPlainPipeline(device);
        }
    }

    private static void dropDenoiser(VkDevice device) {
        dropDenoiserNext = false;
        if (denoiser != null) {
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

    private static void rebuildPlainPipeline(VkDevice device) {
        if (pass != null) {
            pass.destroy();   // waits for the device to go idle
        }
        pass = createPass(device, passModeFor(RtConfig.mode()), false);
    }

    /** Ray Tracing unless Path Tracing is selected (also the pass of the legacy debug-view-only runs, mode off). */
    static RtMode passModeFor(RtMode mode) {
        return mode == RtMode.PATH_TRACED ? RtMode.PATH_TRACED : RtMode.RAY_TRACED;
    }

    /**
     * Whether the pass of {@code mode} is built with the G-buffer: false for Ray Tracing, denoiseMode != OFF for Path Tracing.
     */
    private static boolean wantGbuffer(RtMode mode) {
        if (mode != RtMode.PATH_TRACED) {
            return false;
        }
        return denoiseMode != DenoiserMode.OFF;
    }

    private static RtPass createPass(VkDevice device, RtMode mode, boolean gbuffer) {
        if (mode == RtMode.PATH_TRACED) {
            if (!gbuffer && RtSettings.denoiser() == RtSettings.PathDenoiser.NRD) {
                LOGGER.warn("[VKB-NRD] path_tracing denoiser=nrd requested but NRD is not active ({}): accumulation only",
                    denoiseMode.isNrd() ? "?" : "start the game with -Dvkrtx.denoiser=nrd and the NRD natives");
            }
            return new RtPathTracerPass(device, gbuffer);
        }
        return new RtRayEffectsPass(device, gbuffer);
    }

    /** True when the NRD path is active for this session (selected at world creation from -Dvkrtx.denoiser). */
    public static boolean nrdActive() {
        return denoiseMode.isNrd();
    }

    /** True only while an NRD instance is present and has not been scheduled for failure shutdown. */
    public static boolean nrdRuntimeActive() {
        return denoiseMode.isNrd() && denoiser != null && !dropDenoiserNext;
    }

    // [DLSS-RR] True when DLSS Ray Reconstruction is active for this session
    public static boolean dlssRrActive() {
        return denoiseMode == DenoiserMode.DLSS_RR;
    }

    /** True only while the DLSS-RR instance is present and has not been scheduled for failure shutdown. */
    public static boolean dlssRrRuntimeActive() {
        return denoiseMode == DenoiserMode.DLSS_RR && dlssRrDenoiser != null && !dropDlssRrDenoiserNext;
    }

    /** User/config request, distinct from the resolved native mode and its runtime instance. */
    public static RtSettings.PathDenoiser denoiserRequested() {
        return RtSettings.denoiser();
    }

    /** Selected native mode after resolving availability and fallback rules. */
    public static DenoiserMode denoiserSelected() {
        return denoiseMode;
    }

    /** Resolution explanation; "setting" means the requested mode resolved directly. */
    public static String denoiserResolutionReason() {
        return denoiserResolutionReason;
    }

    /** Last NRD/DLSS-RR instance creation or record failure in this world, if any. */
    public static String denoiserRuntimeFailure() {
        return denoiserRuntimeFailure;
    }

    /** Current instance state for diagnostics. pending-drop is kept separate from active. */
    public static String denoiserRuntimeState() {
        if (dropDenoiserNext || dropDlssRrDenoiserNext) {
            return "pending-drop";
        }
        if (nrdRuntimeActive() || dlssRrRuntimeActive()) {
            return "active";
        }
        return "inactive";
    }

    // [DLSS-RR] Returns the active DLSS-RR denoiser, or null if inactive
    public static DlssRrDenoiser dlssRrDenoiser() {
        return dlssRrDenoiser;
    }

    /** Mode of the pass that rendered the last RT frame (null before the first one); autotests. */
    public static RtMode lastPassMode() {
        return lastPassMode;
    }

    /** Frames averaged into the last path traced image (1 for the ray mode or a reset frame); autotests. */
    public static int lastAccumulatedFrames() {
        RtFrameParams p = lastParams;
        return p == null ? 0 : p.accumCount + 1;
    }

    /** Logs {@code [VKB-RT] mode=.. passes=..} on the first frame and whenever it changes. */
    private static void logPass(String passes) {
        String line = RtConfig.mode().logName() + "|" + passes;
        if (!line.equals(lastPassLog)) {
            lastPassLog = line;
            LOGGER.info("[VKB-RT] mode={} passes={}", RtConfig.mode().logName(), passes);
        }
    }

    /** Logs the shading setup on the first frame, when the atlas size changes, and every 10 s (the sun moves). */
    private static void logShading(int atlasW, int atlasH, boolean hasSky, SkyRenderState sky) {
        long now = System.nanoTime();
        if (lastAtlasW == atlasW && now - lastShadingLogNs < 10_000_000_000L) {
            return;
        }
        lastAtlasW = atlasW;
        lastShadingLogNs = now;
        float[] sun = new float[3];
        RtSky.sunDirection(sky.sunAngle, RtConfig.SUN_FLIP_X, sun);
        float[] moon = new float[3];
        RtSky.sunDirection(sky.moonAngle, RtConfig.SUN_FLIP_X, moon);
        long clock = Minecraft.getInstance().level.getOverworldClockTime();
        LOGGER.info(String.format(Locale.ROOT,
            "[VKB-RT] shading mode=%s sun_dir=(%.3f,%.3f,%.3f) atlas=%dx%d day=%.2f sky=%s sky_color=(%.2f,%.2f,%.2f) sun_flip_x=%s ao=%d"
                + " sun_angle_raw=%.4f moon_dir=(%.3f,%.3f,%.3f) clock_ticks=%d tonemap=%s key_dir=(%.3f,%.3f,%.3f)",
            RtConfig.effectiveModeName(), sun[0], sun[1], sun[2], atlasW, atlasH,
            params.key.day, hasSky, params.skyColor[0], params.skyColor[1], params.skyColor[2], RtConfig.SUN_FLIP_X,
            params.aoSamples, sky.sunAngle, moon[0], moon[1], moon[2], clock, params.tonemap,
            params.key.dir[0], params.key.dir[1], params.key.dir[2]));
    }

    private static void requireRayTracingDevice(VkDevice device) {
        VKCapabilitiesDevice caps = device.getCapabilities();
        if (!caps.VK_KHR_acceleration_structure || !caps.VK_KHR_ray_query
            || caps.vkCmdBuildAccelerationStructuresKHR == 0L) {
            throw new RtFailure("init", "device_without_ray_tracing (VK_KHR_acceleration_structure="
                + caps.VK_KHR_acceleration_structure + " VK_KHR_ray_query=" + caps.VK_KHR_ray_query + ")");
        }
    }

    private static void logWindow(RtWorld.FrameStats s, RenderTarget target) {
        builtInWindow += s.builtThisFrame();
        buildMsInWindow += s.buildMs();
        long now = System.nanoTime();
        if (windowStartNs == 0L) {
            windowStartNs = now;
        }
        if (now - windowStartNs < 1_000_000_000L) {
            return;
        }
        boolean changed = builtInWindow > 0 || s.totalSections() != lastLoggedTotal || s.instances() != lastLoggedInstances;
        if (now - lastStatusNs >= 5_000_000_000L) {
            lastStatusNs = now;
            // F3-style status line (one line, every 5 s)
            LOGGER.info(String.format(Locale.ROOT,
                "[VKB-RT] status mode=%s lights=%d sun=(%.2f,%.2f,%.2f)x%.2f day=%.2f key=%s radius=%d/%d sections=%d blas_mb=%d budget_mb=%d sun_size_deg=%.2f",
                RtConfig.mode().logName(), emitters != null ? emitters.count() : 0, params.key.dir[0], params.key.dir[1],
                params.key.dir[2], params.key.color[0], params.key.day, params.key.isMoon ? "moon" : "sun", lastRadiusXz, lastRadiusY,
                s.instances(), s.vramBytes() >> 20, RtConfig.vramBudgetBytes() >> 20,
                io.github.andrexarlay.vulkancompat.rtx.config.RtSettings.sunSizeTenths / 10.0));
        }
        if (changed) {
            LOGGER.info(String.format(Locale.ROOT,
                "[VKB-RT] blas built=%d total=%d build_ms=%.2f tlas instances=%d tlas_ms=%.2f vram_mb=%d pending=%d failed=%d identical_skipped=%d",
                builtInWindow, s.totalSections(), buildMsInWindow, s.instances(), s.tlasMs(), s.vramBytes() >> 20,
                s.pending(), s.failedSections(), world != null ? world.identicalSkipped() : 0L));
        }
        if (passFramesInWindow > 0) {
            LOGGER.info(String.format(Locale.ROOT,
                "[VKB-RT] rt_pass ok frames=%d rays=%d hit_pct=%s hist_pct=%s ms=%.2f displayed=%s mode=%s",
                passFrames, target != null ? (long) target.width * target.height : 0L,
                lastHitPct < 0 ? "n/a" : String.format(Locale.ROOT, "%.1f", lastHitPct),
                lastHistPct < 0 ? "n/a" : String.format(Locale.ROOT, "%.1f", lastHistPct),
                passMsInWindow / passFramesInWindow, RtConfig.displayed(), RtConfig.effectiveModeName()));
        } else if (s.instances() == 0 && s.pending() > 0) {
            LOGGER.info("[VKB-RT] rt_pass waiting instances=0 pending={}", s.pending());
        }
        passMsInWindow = 0.0;
        passFramesInWindow = 0;
        lastLoggedTotal = s.totalSections();
        lastLoggedInstances = s.instances();
        builtInWindow = 0;
        buildMsInWindow = 0.0;
        windowStartNs = now;
    }

    private static void fail(String stage, String result, Throwable cause) {
        RtGeometryGate.markFailed(stage, result);
        if (cause != null) {
            LOGGER.error("[VKB-RT] FAILED stage={} result={} (RT disabled for this session)", stage, result, cause);
        } else {
            LOGGER.error("[VKB-RT] FAILED stage={} result={} (RT disabled for this session)", stage, result);
        }
        shutdown("failed");
    }

    private static void shutdown(String reason) {
        io.github.andrexarlay.vulkancompat.rt.particles.RtParticleLayer.release(reason);
        RtPass pl = pass;
        RtWorld w = world;
        pass = null;
        lastPassLog = "";
        world = null;
        lastStats = null;
        if (w == null) {
            return;
        }
        try {
            if (pl != null) {
                pl.destroy(); // waits for the device to go idle first
            }
            atmosphere.destroy();
            if (denoiser != null) {
                denoiser.destroy();
            }
            // [DLSS-RR]
            if (dlssRrDenoiser != null) {
                dlssRrDenoiser.destroy();
            }
            if (materials != null) {
                materials.destroy();
            }
            if (emitters != null) {
                emitters.destroy();
            }
            if (output != null) {
                w.memory().destroy(output);
            }
            if (outputDepth != null) {
                w.memory().destroy(outputDepth);
            }
            if (compositeOutput != null) {
                w.memory().destroy(compositeOutput);
            }
            if (compositePipeline != null) {
                compositePipeline.destroy();
            }
            if (statsBuf != null) {
                w.memory().destroy(statsBuf);
            }
            if (skyTextures != null) {
                skyTextures.destroy();
            }
            if (entityMotionBuf != null) {
                w.memory().destroy(entityMotionBuf);
            }
            w.destroy();
            LOGGER.info("[VKB-RT] world_destroyed reason={}", reason);
        } catch (RuntimeException | LinkageError e) {
            LOGGER.error("[VKB-RT] FAILED stage=destroy result={} reason={}", e.getMessage(), reason, e);
        } finally {
            denoiser = null;
            // [DLSS-RR]
            dlssRrDenoiser = null;
            dropDlssRrDenoiserNext = false;
            compositePipeline = null;
            materials = null;
            emitters = null;
            lastPassMode = null;
            dropDenoiserNext = false;
            output = null;
            outputDepth = null;
            compositeOutput = null;
            entityMotionBuf = null;
            statsBuf = null;
            skyTextures = null;
            outputReady = false;
            java.util.Arrays.fill(statsWritten, false);
        }
    }
}
