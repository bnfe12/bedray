package io.github.andrexarlay.vulkancompat.rt.world;

import io.github.andrexarlay.vulkanbridge.platform.LogPaths;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import io.github.andrexarlay.vulkancompat.rt.lifetime.RecordingCompletion;
import io.github.andrexarlay.vulkancompat.rt.lifetime.RecordingReadbackSlots;
import io.github.andrexarlay.vulkancompat.rt.lifetime.RecordingCompletion.Retirement;
import io.github.andrexarlay.vulkancompat.rt.denoise.DenoiserMode;
import io.github.andrexarlay.vulkancompat.rt.denoise.DlssRrDenoiser;
import io.github.andrexarlay.vulkancompat.rt.denoise.NrdDenoiser;
import io.github.andrexarlay.vulkancompat.rt.material.MaterialAtlasBaker;
import io.github.andrexarlay.vulkancompat.rt.material.RtMaterialAtlas;
import io.github.andrexarlay.vulkancompat.rt.slang.SlangRuntime;
import io.github.andrexarlay.vulkancompat.rt.sodium.RtGeometryGate;
import io.github.andrexarlay.vulkancompat.rt.world.RtFrameStatsTracker.StatsMetadata;
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
 *
 * <p>This class is the facade: it keeps the frame orchestration, the recording ticket, the host drain and the shutdown,
 * and delegates the focused state to package-private owners ({@link RtFrameStatsTracker}, {@link RtCameraHistory},
 * {@link RtDisplayProvenance}, {@link RtPassFactory}, {@link RtRenderLog}, {@link RtDenoiserLifecycle}).
 */
public final class RtFrameRenderer {
    private static final Logger LOGGER = LoggerFactory.getLogger("VKB-RT");
    // GPU spans of the RT command buffer, logged by RtGpuTimer (-Dvkrtx.rt.gpuTimers=false disables it)
    private static final int SPAN_WORLD = 0;
    private static final int SPAN_RAYS = 1;
    private static final int SPAN_DENOISE = 2;
    private static final int SPAN_ATMO = 3;
    /**
     * Per-frame slot layout is {@link RtStatsLayout} (offsets shared with buffer StatsBuf in rt_common.glsl and verified
     * against the GLSL declaration by RtStatsLayoutTest); this renderer writes hits/samples/camera/emitter/exposure fields,
     * RtSkyState the sky block, RtFogState the fog block.
     */
    private static final int STATS_STRIDE = RtStatsLayout.SLOT_STRIDE;
    /** Rebuilt sections closer than this (squared section distance, ~2 sections) reset the path tracing accumulation. */
    static final long SCENE_CHANGE_RADIUS_SQ = 8;

    private static RtWorld world;
    private static RtPass pass;
    private static RtMaterialAtlas materials;
    private static RtEmitters emitters;
    private static int lastRadiusXz;
    private static boolean lastUpscaled;
    private static int lastRadiusY;
    private static volatile RtFrameParams lastParams;
    private static volatile RtMode lastPassMode;

    private static final RtFrameStatsTracker statsTracker = new RtFrameStatsTracker();
    private static final RtCameraHistory cameraHistory = new RtCameraHistory();
    private static final RtDisplayProvenance provenance = new RtDisplayProvenance();
    private static final RtPassFactory passFactory = new RtPassFactory();
    private static final RtRenderLog renderLog = new RtRenderLog(new RtRenderLog.Context() {
        @Override public RtWorld world() { return world; }
        @Override public RtEmitters emitters() { return emitters; }
        @Override public RtFrameParams params() { return params; }
        @Override public int lastRadiusXz() { return lastRadiusXz; }
        @Override public int lastRadiusY() { return lastRadiusY; }
        @Override public RtGpuTimer gpuTimer() { return gpuTimer; }
        @Override public long passFrames() { return passFrames; }
        @Override public double lastHitPct() { return statsTracker.lastHitPct(); }
        @Override public double lastRejectedPct() { return statsTracker.lastRejectedPct(); }
        @Override public RtStatsMetering.Breakdown lastRejectSplit() { return statsTracker.lastRejectSplit(); }
        @Override public double lastHistPct() { return statsTracker.lastHistPct(); }
        @Override public double lastLeafPct() { return statsTracker.lastLeafPct(); }
    });
    private static final RtDenoiserLifecycle denoisers = new RtDenoiserLifecycle(new RtDenoiserLifecycle.Context() {
        @Override public RtMemory memory() { return world.memory(); }
        @Override public RtMemory.Img frameOutput() { return RtFrameRenderer.frameOutput(); }
        @Override public RtMemory.Img rrOutput() { return RtFrameRenderer.rrOutput(); }
        @Override public SlangRuntime pathSlangRuntime() { return passFactory.pathSlangRuntime(VulkanCapabilityChecker.getVulkanDevice()); }
        @Override public void requestPlainPipelineRebuild() { rebuildPassNext = true; }
        @Override public void drainHostSubmissions(VkDevice device, String stage) { RtFrameRenderer.drainHostSubmissions(device, stage); }
    });

    /** GPU timestamp spans of the RT frame; on by default, disabled with -Dvkrtx.rt.gpuTimers=false. */
    private static RtGpuTimer gpuTimer;
    /** God rays, bloom and sun glare post passes (Bedray atmosphere), run on the final image after the denoiser. */
    private static final RtAtmosphere atmosphere = new RtAtmosphere();
    private static final RecordingCompletion recordingCompletion =
        new RecordingCompletion(
            failure -> LOGGER.error("[VKB-RT] native recording release failed; retaining it for host drain\n{}", LogPaths.stack(failure)));
    private static final RtFogState fogState = new RtFogState();
    /** Per-pixel entity motion of rt_path for the DLSS-RR motion vectors (vec4 per pixel). */
    private static RtFrameResources frameResources;
    private static long passFrames = 0;
    /** Wall clock of the previous traced frame: the time base of {@link RtAdaptiveQuality} (frame-to-frame, not per span). */
    private static long lastTracedNs = 0L;
    private static final RtFrameParams params = new RtFrameParams();
    /** Vanilla sky parameters of the frame (RtSkyState) and the resource pack sun / moon / cloud textures. */
    private static final RtSkyState skyState = new RtSkyState();
    private static RtSkyTextures skyTextures;
    private static volatile float lastSunAngle = Float.NaN;

    /** Sun angle (radians) of the last RT frame, NaN before the first one (used by the autotest sun scenario). */
    public static float lastSunAngle() {
        return lastSunAngle;
    }

    private static RtOutputResolve outputResolve;
    private static RrHardwareDepth rrHardwareDepth;

    /** A live RR instance can own output reconstruction next frame. FG keeps its existing producer path. */
    public static boolean rrOutputReady() {
        return denoisers.rrOutputReady();
    }

    private static RtSettings.Preset lastFramePreset;
    private static RtSettings.PathPreset lastFramePathPreset;
    private static RtSettings.PathDenoiser lastFramePathDenoiser;
    private static DenoiserMode lastFrameDenoiseMode = DenoiserMode.OFF;
    private static int lastFrameBounces = -1;
    private static int lastFrameSpp = -1;
    private static boolean lastFrameAutoExposure = true;
    private static int lastFrameTransparencyPolicy = -1;
    /** The ReSTIR technique switches of this frame, packed; a change of any of them invalidates every reservoir. */
    private static int lastFrameReusePolicy = -1;

    private static final String FINAL_OUTPUT_SOURCE = "RT_OUTPUT";
    private static final int REFLECTION_MASK = 0x03;
    private static final int SHADOW_MASK = 0x03;

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
        if (world != null && world.entityPrimitiveCount() > 0) {
            return io.github.andrexarlay.vulkancompat.rt.entity.RuntimeFeatureStatus.READY;
        }
        return io.github.andrexarlay.vulkancompat.rt.entity.RuntimeFeatureStatus.EMPTY;
    }

    public static boolean hasActiveLocalLights() {
        return emitters != null && emitters.count() > 0;
    }

    public static String getHermeticTelemetry() {
        var entityStatus = getEntityInstanceStatus();
        String worldStatus = world == null ? "UNAVAILABLE" : (statsTracker.lastStats() == null ? "INITIALIZING" : "READY");
        return String.format(Locale.ROOT,
            "[RT-HERMETIC] worldInstance=%s entityInstance=%s finalSource=%s reflectionMask=0x%02X shadowMask=0x%02X",
            worldStatus,
            entityStatus,
            FINAL_OUTPUT_SOURCE,
            REFLECTION_MASK,
            SHADOW_MASK);
    }

    private RtFrameRenderer() {
    }

    /** Auto exposure of the last readback (autotest telemetry): value (EV multiplier) and the log average scene luminance before exposure. */
    public static float lastExposure() {
        return statsTracker.lastExposureValue();
    }

    public static double lastAverageLuminance() {
        return statsTracker.lastAvgLum();
    }

    public static String transparencyTelemetry() {
        var settings = io.github.andrexarlay.vulkancompat.rtx.config.RtSettings.transparencyStats();
        return (settings ? statsTracker.lastTransparency() : "[RT-TRANSPARENCY] counters=OFF")
            + " glass_faces_examined=" + io.github.andrexarlay.vulkancompat.rt.sodium.GlassFaceFilter.examinedFaces()
            + " glass_faces_removed=" + io.github.andrexarlay.vulkancompat.rt.sodium.GlassFaceFilter.removedFaces()
            + " optical_palette_profiles=" + (materials == null ? 0 : materials.opticalProfileCount());
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
        return statsTracker.lastStats();
    }

    /** BLAS built since the game started (monotonic, for a per-second rate). */
    public static long blasBuiltTotal() {
        return statsTracker.blasBuiltTotal();
    }

    /** Active depth view from DLSS-RR or RT output depth, fallback for velocity reproject. */
    public static long getActiveDepthView() {
        DlssRrDenoiser dlssRrDenoiser = denoisers.rr();
        if (dlssRrDenoiser != null && dlssRrDenoiser.gbViewZ() != null) {
            return dlssRrDenoiser.gbViewZ().view;
        }
        if (frameDepth() != null) {
            return frameDepth().view;
        }
        return 0L;
    }

    /**
     * Convention of {@link #getActiveDepthView()} for the velocity pass: {@link io.github.andrexarlay.vulkancompat.rtx.upscaler.VelocityPipeline#DEPTH_LINEAR_VIEW_Z}
     * (DLSS-RR G-buffer view Z), {@link io.github.andrexarlay.vulkancompat.rtx.upscaler.VelocityPipeline#DEPTH_RAY_DISTANCE}
     * (RT outDepth), or -1 when there is no RT depth (raster depth copy in hardware convention).
     */
    public static int activeDepthConvention() {
        DlssRrDenoiser dlssRrDenoiser = denoisers.rr();
        if (dlssRrDenoiser != null && dlssRrDenoiser.gbViewZ() != null) {
            return io.github.andrexarlay.vulkancompat.rtx.upscaler.VelocityPipeline.DEPTH_LINEAR_VIEW_Z;
        }
        return frameDepth() != null ? io.github.andrexarlay.vulkancompat.rtx.upscaler.VelocityPipeline.DEPTH_RAY_DISTANCE : -1;
    }

    /** VkImage of {@link #getActiveDepthView()} (GENERAL layout), 0 = none; MotionProbe readback only. */
    public static long activeDepthImage() {
        DlssRrDenoiser dlssRrDenoiser = denoisers.rr();
        if (dlssRrDenoiser != null && dlssRrDenoiser.gbViewZ() != null) {
            return dlssRrDenoiser.gbViewZ().image;
        }
        return frameDepth() != null ? frameDepth().image : 0L;
    }

    /**
     * Motion vectors the denoiser itself reprojected with (RG16F, render resolution, GENERAL layout), 0 = none.
     * DLSS-RR derives them from the RT linear view Z with per-pixel entity motion, so a following DLSS-SR pass must reuse
     * them instead of a raster-depth pass that only knows camera motion.
     */
    public static long activeMotionVectorView() {
        DlssRrDenoiser dlssRrDenoiser = denoisers.rr();
        return dlssRrDenoiser != null && dlssRrDenoiser.inMotion() != null ? dlssRrDenoiser.inMotion().view : 0L;
    }

    public static long activeMotionVectorImage() {
        DlssRrDenoiser dlssRrDenoiser = denoisers.rr();
        return dlssRrDenoiser != null && dlssRrDenoiser.inMotion() != null ? dlssRrDenoiser.inMotion().image : 0L;
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

    public static io.github.andrexarlay.vulkancompat.rtx.framegen.FrameGenGuides frameGenGuides() {
        return provenance.frameGenGuides();
    }

    /** Current-frame color provenance; old output allocations are not evidence that RT replaced this frame. */
    public static boolean displayedThisFrame(RenderTarget target) {
        return provenance.displayedThisFrame(target);
    }

    /** Diagnostic proof independent of the target temporarily swapped during world rendering. */
    public static boolean displayedThisFrame() {
        return provenance.displayedThisFrame();
    }

    public static org.joml.Matrix4f displayedProjection(RenderTarget target) {
        return provenance.displayedProjection(target);
    }

    /**
     * Scene TLAS of the frame RT displayed into {@code target} (instances are camera-relative: origin = camera position;
     * section instances mask 0x01, the entity instance 0x02), or 0 when RT did not replace this frame. Read-only consumers record
     * after this pass on the same host queue in the same frame; they never keep the handle across frames.
     */
    public static long sceneTlas(RenderTarget target) {
        return displayedThisFrame(target) && world != null ? world.tlasHandle() : 0L;
    }

    /** RT primary-ray distance image (R32F, GENERAL, 1e6 = no hit) of the frame RT displayed into {@code target}, or null. */
    public static RtMemory.Img rayDistance(RenderTarget target) {
        return displayedThisFrame(target) && frameResources != null ? frameResources.displayDepth() : null;
    }
    private static final boolean BOB_PROJECTION = io.github.andrexarlay.vulkancompat.rtx.WorldTargetManager.bobProjectionEnabled();

    /** Albedo demodulation of the temporal history; {@code -Dvkrtx.rt.demodulate=off} restores the old behaviour for A/B runs. */
    private static final boolean DEMODULATE = !"off".equalsIgnoreCase(System.getProperty("vkrtx.rt.demodulate", "on"));

    /** Experimental NRD direct-sun path for A/B only; defaults to pass-through to preserve the existing shadow edges. */
    private static final boolean NRD_DENOISE_SUN = Boolean.getBoolean("vkrtx.nrd.denoiseSun");

    private static final boolean LOW_DISC = !"off".equalsIgnoreCase(System.getProperty("vkrtx.rt.lowDisc", "on"));

    /**
     * Temporal accumulation (both RT modes): percent of the counted hit pixels of a recent frame (2 frames old) whose
     * reprojected history passed the distance/normal test; -1 before the first readback.
     */
    public static double lastHistoryAcceptedPct() {
        return statsTracker.lastHistPct();
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
        denoisers.resetHistory();
        statsTracker.exposure().reset();
        atmosphere.resetHistory();
        statsTracker.resetHistoryShares();
        lastTracedNs = 0L;   // the gap before the next traced frame describes the old setup, not a slow one
        RtAdaptiveQuality.reset();
    }

    /** True when this path owns the Hardware RT toggle, so the old PoC overlay must not run. */
    public static boolean supersedesPoc() {
        return RtGeometryGate.enabled();
    }

    /** The compute diagnostic owns this frame; close capture and retire hardware-only resources. */
    public static void suspendForComputeDiagnostic() {
        if (drainingHostSubmissions) return;
        io.github.andrexarlay.vulkancompat.rt.entity.EntityCaptureManager.getInstance().endFrame();
        if (world != null) {
            shutdown("compute_diagnostic_scene");
        }
    }

    public static void render(CameraRenderState camera, RenderTarget target, LevelRenderState levelState) {
        if (drainingHostSubmissions) return;
        if (teardownPending) {
            // A settings toggle cannot turn an unknown submission outcome into
            // reusable state. Only a successful teardown removes quarantine.
            if (!RtGeometryGate.enabled()) shutdown("retry_gate_off");
            return;
        }
        provenance.beginFrame();
        // publish this frame's captured entity geometry (also closes the capture: the hand pass that follows is not world geometry)
        if (io.github.andrexarlay.vulkancompat.rt.entity.EntityCaptureManager.getInstance().isOpen()) {
            io.github.andrexarlay.vulkancompat.rt.entity.OffscreenBlockEntities.captureMissing(camera);
            // the first-person camera never draws the player's own model; without it a reflection has nothing to show
            io.github.andrexarlay.vulkancompat.rt.entity.EntityCaptureManager.getInstance().captureSelfAvatar(camera,
                levelState != null ? levelState.playerRenderState : null);
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
        } catch (RtDenoiserFrameDiscard discarded) {
            if (discarded.getSuppressed().length != 0) fail("rr_discard", "retirement_failed", discarded);
            else LOGGER.warn("[VKB-RT] frame_skipped reason=rr_recording_discarded recovery=next_frame_preflight");
        } catch (RtFailure f) {
            fail(f.stage(), f.result(), f.getCause() != null ? f.getCause() : f);
        } catch (RuntimeException | LinkageError e) {
            fail("frame", e.getClass().getSimpleName() + ": " + LogPaths.show(e.getMessage()), e);
        }
    }

    private static void frame(CameraRenderState camera, RenderTarget target, LevelRenderState levelState) {
        if (drainingHostSubmissions) return;
        VulkanDevice vulkanDevice = VulkanCapabilityChecker.getVulkanDevice();
        if (vulkanDevice == null) {
            return;
        }
        VkDevice device = vulkanDevice.vkDevice();
        if (io.github.andrexarlay.vulkancompat.rt.particles.RtParticleLayer.needsHostDrain()) {
            drainHostSubmissions(device, "particle_resources_drain");
            io.github.andrexarlay.vulkancompat.rt.particles.RtParticleLayer.collectAfterHostDrain(false);
        }
        if (world == null) {
            requireRayTracingDevice(device);
            world = new RtWorld(device, pathSlangRuntime(vulkanDevice));
            // The leaf transmission is a plant property, not a render-pass one: tag the cutout quads while they are staged.
            world.setBlockLookup(VegetationBlocks::foliageKindAt);
            if (RtGpuTimer.ENABLED) {
                try {
                    gpuTimer = new RtGpuTimer(device, "VKB-RT-GPU", "world", "rays", "denoise", "atmo",
                        "trace", "pt_spatial", "di_spatial", "display");
                    RtGpuTimer.active = gpuTimer;
                } catch (RuntimeException e) {
                    // A device without timestamp queries must lose the timings, not the whole RT frame.
                    LOGGER.warn("[VKB-RT] gpu timers unavailable: {}", LogPaths.show(e.getMessage()));
                }
            }
            denoisers.selectDenoiser();
            pass = createPass(device, passModeFor(RtConfig.mode()), wantGbuffer(passModeFor(RtConfig.mode())));
            LOGGER.info("[VKB-RT] world_ready debug_mode={}", RtConfig.DEBUG_NAME.isEmpty() ? "none" : RtConfig.DEBUG_NAME);
            if (RtConfig.isUnknownMode(RtConfig.DEBUG_NAME)) {
                LOGGER.warn("[VKB-RT] unknown -Dvkbridge.rt.debug={} (use normals|depth|uv|section), no debug view", RtConfig.DEBUG_NAME);
            }
        }

        denoisers.dropPending(device);
        denoisers.resolveFrame();
        DenoiserMode denoiseMode = denoisers.mode();

        RtSettings.Preset curPreset = RtSettings.preset;
        RtSettings.PathPreset curPathPreset = RtSettings.pathPreset;
        RtSettings.PathDenoiser curPathDenoiser = RtSettings.denoiser();
        int curBounces = RtSettings.bounces();
        int curSpp = RtSettings.spp();
        boolean curAutoExposure = RtSettings.autoExposure();
        int curTransparencyPolicy = (RtSettings.opticalMaterials() ? 1 : 0)
            | (RtSettings.directTranslucentShadows() ? 2 : 0)
            | (RtSettings.indirectTranslucentShadows() ? 4 : 0);
        // ReSTIR is a per-technique switch, and every one of them changes what a stored record means: turning reuse on or
        // off, or pooling neighbours on top of it, must not continue an accumulation that was built without it.
        int curReusePolicy = (RtSettings.restirTemporal() ? 1 : 0) | (RtSettings.restirSpatial() ? 2 : 0)
            | (RtSettings.pathRestirDi() ? 4 : 0) | (RtSettings.pathRestirSpatial() ? 8 : 0)
            | (RtSettings.pathRestirPt() ? 16 : 0) | (RtSettings.pathRestirPtSpatial() ? 32 : 0);

        boolean presetOrDenoiserChanged = (lastFrameBounces != -1) && (
            curPreset != lastFramePreset
            || curPathPreset != lastFramePathPreset
            || curPathDenoiser != lastFramePathDenoiser
            || denoiseMode != lastFrameDenoiseMode
            || curBounces != lastFrameBounces
            || curSpp != lastFrameSpp
            || curAutoExposure != lastFrameAutoExposure
            || curTransparencyPolicy != lastFrameTransparencyPolicy
            || curReusePolicy != lastFrameReusePolicy
        );

        if (presetOrDenoiserChanged) {
            LOGGER.info("[VKB-RT] resetHistory: preset({}->{}) pt_preset({}->{}) denoiser({}->{}) denoiseMode({}->{}) bounces({}->{}) spp({}->{}) autoExp({}->{}) transparency({}->{}) restir({}->{})",
                lastFramePreset, curPreset, lastFramePathPreset, curPathPreset, lastFramePathDenoiser, curPathDenoiser,
                lastFrameDenoiseMode, denoiseMode, lastFrameBounces, curBounces, lastFrameSpp, curSpp, lastFrameAutoExposure, curAutoExposure,
                lastFrameTransparencyPolicy, curTransparencyPolicy, lastFrameReusePolicy, curReusePolicy);
            resetHistory();
        }

        lastFramePreset = curPreset;
        lastFramePathPreset = curPathPreset;
        lastFramePathDenoiser = curPathDenoiser;
        lastFrameDenoiseMode = denoiseMode;
        lastFrameBounces = curBounces;
        lastFrameSpp = curSpp;
        lastFrameAutoExposure = curAutoExposure;
        lastFrameTransparencyPolicy = curTransparencyPolicy;
        lastFrameReusePolicy = curReusePolicy;

        denoisers.retireForMode(device, denoiseMode);

        RtMode want = passModeFor(RtConfig.mode());
        boolean wantGb = wantGbuffer(want);
        if (pass != null && (rebuildPassNext || pass.mode() != want || !pass.builtFor(wantGb))) {
            LOGGER.info("[VKB-RT] mode_switch from={} to={} nrd_gbuffer={}", pass.mode().logName(), want.logName(), wantGb);
            drainHostSubmissions(device, "mode_switch_drain");
            world.releaseComputeRecordingsAfterHostDrain();
            pass.releaseRecordingsAfterHostDrain();
            pass.destroy();
            pass = createPass(device, want, wantGb);
            rebuildPassNext = false;
            resetHistory();
            renderLog.resetPassLog();
        }
        statsTracker.consumeCompletedStats();
        if (!statsTracker.slots().hasFreeSlot()) {
            drainHostSubmissions(device, "stats_slots_drain");
            statsTracker.consumeCompletedStats();
            if (!statsTracker.slots().hasFreeSlot()) throw new RtFailure("stats_slots", "completion proof unavailable; stats slots retained");
        }
        if (world.requiresLegacyHostDrain()) {
            drainHostSubmissions(device, "legacy_world_inputs_drain");
            world.prepareLegacyFrameAfterHostDrain();
        }
        if (target != null && target.width > 0 && target.height > 0
            && camera.projectionMatrix != null && camera.viewRotationMatrix != null) {
            prepareFrameResources(device, WorldTargetManager.traceWidth(target.width), WorldTargetManager.traceHeight(target.height));
        }
        long frameStart = System.nanoTime();
        if (emitters == null) emitters = new RtEmitters(world.memory());
        RtEmitters recordingEmitters = emitters;
        VulkanCommandEncoder encoder = vulkanDevice.createCommandEncoder();
        VkCommandBuffer cmd = encoder.allocateAndBeginTransientCommandBuffer();
        var statsOwner = statsTracker.slots();
        var statsLease = statsOwner.reserve(cmd.address());
        if (statsLease == null) throw new RtFailure("stats_slots", "stats reservation unavailable");
        RtWorld recordingWorld = world;
        RtFrameResources recordingResources = frameResources;
        RtPass recordingPass = pass;
        RtOutputResolve recordingOutputResolve = outputResolve;
        RrHardwareDepth recordingRrDepth = rrHardwareDepth;
        Runnable[] particleRelease = {() -> {}};
        Retirement[] denoiserProof = {new Retirement(() -> {}, () -> {})};
        RecordingCompletion.Ticket recording;
        try {
            recording = recordingCompletion.beginWithDiscard(cmd.address(),
            new Retirement(
                () -> { if (recordingRrDepth != null) recordingRrDepth.release(cmd.address(), false); },
                () -> { if (recordingRrDepth != null) recordingRrDepth.release(cmd.address(), true); }),
            new Retirement(
                () -> { if (recordingOutputResolve != null) recordingOutputResolve.release(cmd.address(), false); },
                () -> { if (recordingOutputResolve != null) recordingOutputResolve.release(cmd.address(), true); }),
            new Retirement(() -> statsOwner.completed(statsLease), () -> statsOwner.discarded(statsLease)),
            new Retirement(
                () -> atmosphere.releaseRecording(cmd.address()), () -> atmosphere.discardRecording(cmd.address())),
            new Retirement(
                () -> { if (recordingResources != null) recordingResources.releaseRecording(cmd.address()); },
                () -> { if (recordingResources != null) recordingResources.discardRecording(cmd.address()); }),
            new Retirement(
                () -> recordingPass.releaseRecording(cmd.address()), () -> recordingPass.discardRecording(cmd.address())),
            new Retirement(
                () -> recordingWorld.releaseRecording(cmd.address()), () -> recordingWorld.discardRecording(cmd.address())),
            new Retirement(
                () -> recordingEmitters.releaseRecording(cmd.address()), () -> recordingEmitters.releaseRecording(cmd.address())),
            new Retirement(
                () -> particleRelease[0].run(), () -> particleRelease[0].run()),
            new Retirement(
                () -> denoiserProof[0].completed().run(), () -> denoiserProof[0].discarded().run()));
        } catch (RuntimeException | Error failure) {
            statsOwner.discarded(statsLease); // no commands or submission can reference it yet
            throw failure;
        }
        boolean executeAttempted = false;
        RtWorld.FrameStats stats;
        boolean traced = false;
        try {
            VkBarriers.fullMemoryBarrier(cmd);
            if (gpuTimer != null) {
                gpuTimer.beginFrame(cmd);
            }
            int radiusXz = RtConfig.radiusXz(Minecraft.getInstance().options.getEffectiveRenderDistance());
            int radiusY = RtConfig.radiusY(radiusXz);
            lastRadiusXz = radiusXz;
            lastRadiusY = radiusY;
            if (gpuTimer != null) {
                gpuTimer.stamp(cmd, SPAN_WORLD, false);
            }
            stats = recordingWorld.update(cmd, camera.pos.x, camera.pos.y, camera.pos.z, radiusXz, radiusY);
            if (gpuTimer != null) {
                gpuTimer.stamp(cmd, SPAN_WORLD, true);
            }
            statsTracker.recordWorldStats(stats);
            if (stats.instances() > 0 && camera.projectionMatrix != null && camera.viewRotationMatrix != null && target != null) {
                recordPrimaryRays(cmd, vulkanDevice, camera, target, levelState,
                    world.nearestBuiltSq() <= SCENE_CHANGE_RADIUS_SQ, statsLease, denoiserProof);
                traced = true;
            }
            VkBarriers.fullMemoryBarrier(cmd);
            if (traced) recordStatsHostVisibility(cmd, statsLease.slot());
            RtFailure.check(VK10.vkEndCommandBuffer(cmd), "end_command_buffer");
            particleRelease[0] = io.github.andrexarlay.vulkancompat.rt.particles.RtParticleLayer.recordingRelease(cmd.address());
            recordingCompletion.submissionAttempted(recording);
            executeAttempted = true;
            encoder.execute(cmd);
            recordingCompletion.submitted(recording, action -> encoder.queueForDestroy(action::run));
        } catch (RuntimeException | Error failure) {
            if (!executeAttempted) {
                // This local command buffer was never handed to execute and is
                // deliberately abandoned; the host will not submit it later.
                particleRelease[0] = io.github.andrexarlay.vulkancompat.rt.particles.RtParticleLayer.recordingRelease(cmd.address());
                try { recordingCompletion.discard(recording); }
                catch (RuntimeException | Error cleanup) { failure.addSuppressed(cleanup); }
            }
            // An exception during execute/enqueue is UNKNOWN: keep resources
            // pinned until the existing host drain proves completion.
            throw failure;
        }
        provenance.publish(target, frameProjection);
        if (traced) {
            long tracedNs = System.nanoTime();
            double dtMs = lastTracedNs == 0L ? 0.0 : (tracedNs - lastTracedNs) / 1.0e6;
            lastTracedNs = tracedNs;
            passFrames++;
            renderLog.addTracedFrame((tracedNs - frameStart) / 1.0e6);
            if (pass.mode() == RtMode.PATH_TRACED) {
                // what the ladder can actually move: the traced rays and the denoiser (the chunk build behind SPAN_WORLD does
                // not get cheaper with fewer rays, and would only pin the scene at one ray while the world streams)
                RtAdaptiveQuality.observe(gpuTimer == null ? Double.NaN : gpuTimer.recentMs(SPAN_RAYS, SPAN_DENOISE), dtMs);
            }
        }
        renderLog.logWindow(stats, target);
    }

    /** GPU writes become visible to coherent mapped host memory after this recording completes. */
    private static void recordStatsHostVisibility(VkCommandBuffer cmd, int slot) {
        statsTracker.recordStatsHostVisibility(cmd, slot);
    }

    private static RtMemory.Img frameOutput() { return frameResources != null ? frameResources.output() : null; }
    private static RtMemory.Img frameDepth() { return frameResources != null ? frameResources.depth() : null; }
    private static RtMemory.Img frameComposite() { return frameResources != null ? frameResources.composite() : null; }
    private static RtMemory.Img rrOutput() { return WorldTargetManager.rrResolvesOutput() ? frameComposite() : frameOutput(); }
    private static RtMemory.Buf frameMotion() { return frameResources != null ? frameResources.motion() : null; }

    /** Resource replacement happens before any command buffer can capture the previous generation. */
    private static void prepareFrameResources(VkDevice device, int w, int h) {
        if (frameResources == null) frameResources = new RtFrameResources(world.memory());
        int previousWidth = frameOutput() != null ? frameOutput().width : 0;
        int previousHeight = frameOutput() != null ? frameOutput().height : 0;
        DenoiserMode previousMode = denoisers.mode();
        boolean combinedRr = WorldTargetManager.rrResolvesOutput();
        int outW = WorldTargetManager.presentationWidth(w), outH = WorldTargetManager.presentationHeight(h);
        boolean outputChanged = combinedRr ? frameResources.needsOutput(w, h, outW, outH) : frameResources.needsOutput(w, h);
        long outputView = rrOutput() != null ? rrOutput().view : 0L;
        long outputImage = rrOutput() != null ? rrOutput().image : 0L;
        boolean denoiserChanged = denoisers.nrdNeedsEnsure(w, h, outputView);
        boolean rrChanged = denoisers.rrNeedsEnsure(w, h, outW, outH, outputImage, outputView);
        boolean motionChanged = wantsEntityMotion() && frameResources.needsMotion(w, h);
        String stage = "drain";
        try {
            if (frameResources.initializationPending() || (outputChanged && frameOutput() != null) || denoiserChanged || rrChanged || motionChanged)
                drainHostSubmissions(device, "frame_resources_drain");
            stage = "output_replace";
            if (outputChanged) {
                if (combinedRr) frameResources.ensureOutput(w, h, outW, outH); else frameResources.ensureOutput(w, h);
            }
            if (combinedRr && outputResolve == null) outputResolve = new RtOutputResolve(pathSlangRuntime(VulkanCapabilityChecker.getVulkanDevice()));
            if (denoisers.mode() == DenoiserMode.DLSS_RR) {
                stage = "dlss_rr_ensure";
                if (pass.usesGbuffer() && denoisers.rr() == null) denoisers.createDlssRrDenoiser(device, w, h);
                DlssRrDenoiser dlssRrDenoiser = denoisers.rr();
                if (dlssRrDenoiser != null) dlssRrDenoiser.ensure(w, h, rrOutput());
                if (dlssRrDenoiser != null && rrHardwareDepth == null)
                    rrHardwareDepth = new RrHardwareDepth(pathSlangRuntime(VulkanCapabilityChecker.getVulkanDevice()));
            }
            if (denoisers.mode().isNrd()) {
                stage = "nrd_ensure";
                if (pass.usesGbuffer() && denoisers.nrd() == null) denoisers.createDenoiser(device, w, h);
                NrdDenoiser denoiser = denoisers.nrd();
                if (denoiser != null) denoiser.ensure(w, h, frameOutput().view);
            }
            stage = "entity_motion_ensure";
            if (pass.usesGbuffer() && wantsEntityMotion()) frameResources.ensureMotion(w, h);
            stage = "sky_atlas_prepare";
            if (skyTextures == null) skyTextures = new RtSkyTextures(world.memory());
            skyTextures.prepare(() -> drainHostSubmissions(device, "sky_atlas_reload_drain"));
        } catch (RuntimeException | Error failure) {
            throw new RtFailure("frame_resources_" + stage,
                "profile=" + previousMode.logName() + " previousExtent=" + previousWidth + "x" + previousHeight
                    + " requestedExtent=" + w + "x" + h + " previousOutputView=0x" + Long.toUnsignedString(outputView, 16)
                    + " currentOutputView=0x" + Long.toUnsignedString(frameOutput() != null ? frameOutput().view : 0L, 16)
                    + " outputChanged=" + outputChanged + " denoiserChanged=" + denoiserChanged
                    + " rrChanged=" + rrChanged + " motionChanged=" + motionChanged,
                failure);
        }
    }

    /** Output image, dispatch and (debug view only) the copy into the engine colour image, inside the open command buffer. */
    private static void recordPrimaryRays(VkCommandBuffer cmd, VulkanDevice vulkanDevice, CameraRenderState camera,
                                         RenderTarget target, LevelRenderState levelState, boolean sceneChanged,
                                         RecordingReadbackSlots.Lease<StatsMetadata> statsLease, Retirement[] denoiserProof) {
        VkDevice device = vulkanDevice.vkDevice();
        SkyRenderState sky = levelState.skyRenderState;
        frameProjection = BOB_PROJECTION ? io.github.andrexarlay.vulkancompat.rtx.WorldTargetManager.levelProjectionOr(camera.projectionMatrix) : camera.projectionMatrix;
        org.joml.Vector3f bobOrigin = BOB_PROJECTION
            ? io.github.andrexarlay.vulkancompat.rtx.WorldTargetManager.levelCameraOriginOr(null)
            : new org.joml.Vector3f();
        long dispatchCounter = statsTracker.dispatchCounter();
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
        int w = WorldTargetManager.traceWidth(target.width);
        int h = WorldTargetManager.traceHeight(target.height);
        if (frameOutput() == null || frameOutput().width != w || frameOutput().height != h)
            throw new RtFailure("frame_resources", "output generation was not prepared before recording");
        statsTracker.ensureStatsBuffer(world.memory());
        RtMemory.Buf statsBuf = statsTracker.statsBuf();
        frameResources.prepare(cmd.address(), image -> VkBarriers.transition(cmd, image.image,
            VK10.VK_IMAGE_ASPECT_COLOR_BIT, VK10.VK_IMAGE_LAYOUT_UNDEFINED, VK10.VK_IMAGE_LAYOUT_GENERAL));
        AbstractTexture atlasTexture = Minecraft.getInstance().getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS);
        long atlasView = atlasTexture != null ? WorldTargetManager.extractVkImageView(atlasTexture.getTextureView()) : 0L;
        if (atlasView == 0L) {
            throw new RtFailure("atlas", "block atlas VkImageView unavailable");
        }
        int atlasW = atlasTexture.getTexture().getWidth(0);
        int atlasH = atlasTexture.getTexture().getHeight(0);
        // The vanilla lightmap (16x16, block light x sky light) colours the baked light fill and the vanilla water sheet
        // with the real tint of the pack, dimension and effects; 0 binds the atlas as a benign fallback.
        long lightmapView = 0L;
        try {
            lightmapView = WorldTargetManager.extractVkImageView(Minecraft.getInstance().gameRenderer.lightmap());
        } catch (RuntimeException | LinkageError e) {
            LOGGER.debug("[VKB-RT] lightmap view unavailable: {}", LogPaths.show(e.toString()));
        }
        denoisers.prepareRecording(cmd, denoiserProof);
        DenoiserMode denoiseMode = denoisers.mode();
        NrdDenoiser denoiser = denoisers.nrd();
        DlssRrDenoiser dlssRrDenoiser = denoisers.rr();
        boolean useDenoiser = pass.usesGbuffer() && (denoiser != null || dlssRrDenoiser != null);
        // NRD reads this frame's per-pixel world motion through its own binding, so the buffer has to exist before the
        // pass binds its views: the handle travels with them, and the pass writes exactly that range.
        long motionHandle = 0L;
        if (useDenoiser && wantsEntityMotion()) {
            frameResources.requireMotion(w, h);
            motionHandle = frameMotion().handle;
        }
        if (materials == null) {
            materials = new RtMaterialAtlas(world.memory());
            // Without this the atlas keeps no texel states, intern() returns ST_RETRY forever and the micromaps are
            // inert: the feature reports "enabled" while every BLAS triangle stays fully unknown.
            if (world.ommCache() != null) {
                materials.setMicromapSink(world.ommCache());
            }
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
        pass.bind(new RtFrameBindings.Views(world.tlasHandle(), world.sectionTable(), frameOutput().view, atlasView, materials.materialView(),
            materials.normalView(), 0L, gbViews, frameDepth() != null ? frameDepth().view : 0L, lightmapView, motionHandle));

        int writeSlot = statsLease.slot();
        long writeOffset = (long) writeSlot * STATS_STRIDE;
        VK10.vkCmdFillBuffer(cmd, statsBuf.handle, writeOffset, 16, 0);
        // leafBacklit sits far past the four head counters, so the 16-byte fill above does not reach it: without its own
        // zeroing the slot would keep the count of the frame that used it three slots ago.
        VK10.vkCmdFillBuffer(cmd, statsBuf.handle, writeOffset + RtStatsLayout.LEAF_BACKLIT, 4, 0);
        VK10.vkCmdFillBuffer(cmd, statsBuf.handle, writeOffset + RtStatsLayout.TRANS_QUERIES, 24, 0);
        // The trace's own F3 split: zeroed here, added to by the trace, so a slot reused three frames later starts clean.
        VK10.vkCmdFillBuffer(cmd, statsBuf.handle, writeOffset + RtStatsLayout.PATH_REJECTS, 16, 0);
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
        emitters.retainForRecording(cmd.address());
        long hdr = statsBuf.mapped + writeOffset;
        MemoryUtil.memPutLong(hdr + RtStatsLayout.OPTICAL_ADDR,
            io.github.andrexarlay.vulkancompat.rtx.config.RtSettings.opticalMaterials() ? materials.opticalAddress() : 0L);
        io.github.andrexarlay.vulkancompat.rt.sodium.GlassFaceFilter.setEnabled(
            io.github.andrexarlay.vulkancompat.rtx.config.RtSettings.cullSharedGlass());
        MemoryUtil.memPutInt(hdr + RtStatsLayout.EMITTER_COUNT, emitters.count());
        // One exposure number for the frame, read from the switch rather than from the flag bit that is composed below:
        // the display step, the volumetric composite, the denoiser packs and this slot all take params.exposure, so a
        // change of the compensation cannot brighten one of them and leave another where it was.
        RtExposure exposure = statsTracker.exposure();
        params.exposure = exposure.applied(io.github.andrexarlay.vulkancompat.rtx.config.RtSettings.autoExposure(),
            io.github.andrexarlay.vulkancompat.rtx.config.RtSettings.exposureEvTenths());
        MemoryUtil.memPutFloat(hdr + RtStatsLayout.EXPOSURE, params.exposure);
        MemoryUtil.memPutFloat(hdr + RtStatsLayout.CAM_REL, (float) (camera.pos.x - emitters.originX()));
        MemoryUtil.memPutFloat(hdr + RtStatsLayout.CAM_REL + 4, (float) (camera.pos.y - emitters.originY()));
        MemoryUtil.memPutFloat(hdr + RtStatsLayout.CAM_REL + 8, (float) (camera.pos.z - emitters.originZ()));
        MemoryUtil.memPutLong(hdr + RtStatsLayout.EMITTER_ADDR, emitters.address());
        params.emitterHandle = emitters.handle();
        params.emitterOffset = emitters.segmentOffset();
        params.emitterCount = emitters.count();
        params.emitterFingerprint = emitters.fingerprint();
        params.emitterCamRelX = MemoryUtil.memGetFloat(hdr + RtStatsLayout.CAM_REL);
        params.emitterCamRelY = MemoryUtil.memGetFloat(hdr + RtStatsLayout.CAM_REL + 4);
        params.emitterCamRelZ = MemoryUtil.memGetFloat(hdr + RtStatsLayout.CAM_REL + 8);
        VkBarriers.fullMemoryBarrier(cmd);

        // stable (unjittered) matrix for both rays and accumulation decision:
        // camera rays apply subpixel jitter explicitly via cameraJitter offset in StatsBuf;
        // invViewProj must stay strictly unjittered so forward, viewZ, and motion vectors are jitter-free.
        InstanceMath.inverseViewProjection(frameProjection, camera.viewRotationMatrix, params.invViewProjStable);
        params.invViewProj.set(params.invViewProjStable);
        params.statsAddress = statsBuf.address + writeOffset;
        // The same slot as a descriptor, for the pass that fills its counters: a device address reaches only the
        // shaders that take one, and the statistics pass binds the sixteen-byte head like any other buffer.
        params.statsBuffer = statsBuf.handle;
        params.statsOffset = writeOffset;
        params.flipY = RtConfig.FLIP_Y;
        params.tonemap = RtConfig.TONEMAP_ACES;
        params.frame = (int) dispatchCounter;
        params.rtRadiusXz = lastRadiusXz;
        params.cameraX = camera.pos.x;
        params.cameraY = camera.pos.y;
        params.cameraZ = camera.pos.z;
        params.bobX = bobOrigin.x;
        params.bobY = bobOrigin.y;
        params.bobZ = bobOrigin.z;
        params.sceneChanged = sceneChanged;
        lastSunAngle = sky.sunAngle;
        boolean hasSky = sky.skybox == DimensionType.Skybox.OVERWORLD;
        if (skyTextures == null || skyTextures.address() == 0)
            throw new RtFailure("sky_atlas", "atlas was not prepared before command recording");
        skyState.collect(levelState, camera.pos.x + bobOrigin.x, camera.pos.y + bobOrigin.y, camera.pos.z + bobOrigin.z, RtConfig.SUN_FLIP_X,
            skyTextures.cloudWidth(), skyTextures.cloudHeight());
        RtSky.keyLight(sky.sunAngle, sky.moonAngle, RtConfig.SUN_FLIP_X, hasSky, skyState.moonLight, skyState.rainBrightness,
            skyState.thunder, params.key);
        skyState.write(hdr, skyTextures.address(), RtConfig.SUN_FLIP_X);
        // vanilla fog of the game (render distance edge, biome / weather / water) + aerial perspective: rt_common.glsl fogTransmittanceHit
        fogState.collect(Minecraft.getInstance(), levelState.worldPartialTicks, lastRadiusXz, skyState.rainBrightness);
        fogState.write(hdr, RtAtmosphereSettings.fogVanilla(), RtAtmosphereSettings.fogAerial(), RtAtmosphereSettings.chunkFadeSeconds());
        // The medium a primary ray starts in: the same fluid state that decides the fog above, so a swimmer's paths
        // absorb through their own water column instead of through clear air.
        params.cameraInWater = fogState.medium == 1;
        if (sky.skyColor != null) {
            params.skyColor[0] = sky.skyColor.x();
            params.skyColor[1] = sky.skyColor.y();
            params.skyColor[2] = sky.skyColor.z();
        }
        // The sky as a light: the legacy hemisphere model (near-neutral, AMBIENT_SCALE at noon), not the display colour.
        float sunY = params.key.isMoon ? -params.key.dir[1] : params.key.dir[1];
        RtSky.SkyLight skyLight = new RtSky.SkyLight();
        RtSky.skyLight(params.skyColor, params.key.day, sunY, -sunY, skyState.moonLight, skyState.rainBrightness,
            params.key.color, hasSky, skyLight);
        System.arraycopy(skyLight.zenith, 0, params.skyLightZenith, 0, 3);
        System.arraycopy(skyLight.horizon, 0, params.skyLightHorizon, 0, 3);
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
            | (io.github.andrexarlay.vulkancompat.rtx.config.RtSettings.autoExposure() ? RtFrameParams.FLAG_AUTO_EXPOSURE : 0)
            | ((denoiseMode == DenoiserMode.DLSS_RR && dlssRrDenoiser != null
                || denoiseMode.isNrd() && NRD_DENOISE_SUN) ? RtFrameParams.FLAG_SUN_DENOISED : 0)
            | (DEMODULATE ? RtFrameParams.FLAG_DEMODULATE : 0)
            | (LOW_DISC ? RtFrameParams.FLAG_LOWDISC : 0);
        if (!io.github.andrexarlay.vulkancompat.rtx.config.RtSettings.directTranslucentShadows()) params.flags |= RtFrameParams.FLAG_SKIP_DIRECT_TRANSLUCENT;
        if (!io.github.andrexarlay.vulkancompat.rtx.config.RtSettings.indirectTranslucentShadows()) params.flags |= RtFrameParams.FLAG_SKIP_INDIRECT_TRANSLUCENT;
        if (io.github.andrexarlay.vulkancompat.rtx.config.RtSettings.transparencyStats()) params.flags |= RtFrameParams.FLAG_TRANSPARENCY_STATS;
        if (io.github.andrexarlay.vulkancompat.rtx.config.RtSettings.opticalMaterials()) params.flags |= RtFrameParams.FLAG_OPTICAL_MATERIALS;
        params.rrGuides = useDenoiser && denoiseMode == DenoiserMode.DLSS_RR;
        pass.configure(params, useDenoiser);
        MemoryUtil.memPutFloat(hdr + RtStatsLayout.GI_DIST, params.giDistance);
        MemoryUtil.memPutFloat(hdr + RtStatsLayout.EMIS_DIST, params.emissiveDistance);
        MemoryUtil.memPutFloat(hdr + RtStatsLayout.AO_RADIUS, params.aoRadius);
        MemoryUtil.memPutFloat(hdr + RtStatsLayout.AUTO_NORMAL_STRENGTH,
            io.github.andrexarlay.vulkancompat.rtx.config.RtSettings.autoNormalStrengthPercent() / 100f);
        MemoryUtil.memPutFloat(hdr + RtStatsLayout.SUN_RADIUS, io.github.andrexarlay.vulkancompat.rtx.config.RtSettings.sunRadiusRad());
        MemoryUtil.memPutFloat(hdr + RtStatsLayout.HISTORY_CAP, params.historyCap);
        // StatsBuf.shadowPixel: pixelated-shadow cell edge in texture pixels (1/16 block), 0 = the option is off
        MemoryUtil.memPutFloat(hdr + RtStatsLayout.SHADOW_PIXEL, pixelShadowStatsValue());
        // The deleted ray pass's dormant reservoir slots now carry the private display-sky packet below.
        // Temporal reprojection uses the full pose in both projections. Hit positions include the current bob origin, while
        // camDelta remains the raw camera-position delta; the prior full projection already carries the previous bob origin.
        cameraHistory.writeTemporal(hdr, params, camera, frameProjection);
        float dx = cameraHistory.dx(), dy = cameraHistory.dy(), dz = cameraHistory.dz();
        float baseDistance = (float) Math.max(32, params.rayDistance);
        float fogDistance = baseDistance * params.fogDistanceScale;
        MemoryUtil.memPutFloat(hdr + RtStatsLayout.FOG_DENSITY, params.fogDensity);
        MemoryUtil.memPutFloat(hdr + RtStatsLayout.FOG_INSCATTERING, params.fogInScattering);
        MemoryUtil.memPutFloat(hdr + RtStatsLayout.FOG_DISTANCE, fogDistance);
        // 2 = distance fog + the former in-shader god rays, 1 = distance fog only (the light shafts come from RtAtmosphere)
        MemoryUtil.memPutInt(hdr + RtStatsLayout.FOG_MODE, params.volumetricFogEnabled
            ? (RtAtmosphereSettings.godRays() == RtAtmosphereSettings.GodRays.INLINE ? 2 : 1) : 0);
        MemoryUtil.memPutFloat(hdr + RtStatsLayout.FOG_DISTANCE_SCALE, params.fogDistanceScale);
        // entity geometry of this frame (RtEntityGeometryManager ring slice; the same slice the entity BLAS was built from)
        RtEntityGeometryManager eg = RtEntityGeometryManager.INSTANCE;
        boolean entOk = world.entityPrimitiveCount() > 0;
        MemoryUtil.memPutInt(hdr + RtStatsLayout.ENTITY_TRIS, entOk ? eg.frameTriangles() : 0);
        // Triangle range of the local player's body captured for this frame: the primary rays skip it (an inside face of the
        // avatar at 0.001 from the eye is what the first-person screen would otherwise show).
        io.github.andrexarlay.vulkancompat.rt.entity.EntityCaptureManager capture =
            io.github.andrexarlay.vulkancompat.rt.entity.EntityCaptureManager.getInstance();
        MemoryUtil.memPutInt(hdr + RtStatsLayout.SELF_AVATAR_TRI_START, entOk ? capture.selfAvatarTriStart() : 0);
        MemoryUtil.memPutInt(hdr + RtStatsLayout.SELF_AVATAR_TRI_COUNT, entOk ? capture.selfAvatarTriCount() : 0);
        MemoryUtil.memPutLong(hdr + RtStatsLayout.ENT_POS_ADDR, entOk ? eg.framePosAddress() : 0L);
        MemoryUtil.memPutLong(hdr + RtStatsLayout.ENT_IDX_ADDR, entOk ? eg.frameIndexAddress() : 0L);
        MemoryUtil.memPutLong(hdr + RtStatsLayout.ENT_ATTR_ADDR, entOk ? eg.frameAttrAddress() : 0L);
        MemoryUtil.memPutLong(hdr + RtStatsLayout.ENT_PREV_ADDR, entOk ? eg.framePrevAddress() : 0L);
        MemoryUtil.memPutLong(hdr + RtStatsLayout.ENT_ATLAS_ADDR, eg.atlasAddress());
        MemoryUtil.memPutLong(hdr + RtStatsLayout.ENT_RECT_ADDR, eg.rectAddress());
        // The consumer receives exactly the allocation bound for this frame's producer, including zero on disable.
        long motionAddr = motionHandle != 0L ? frameMotion().address : 0L;
        if (dlssRrDenoiser != null) dlssRrDenoiser.entityMotionAddress = motionAddr;
        if (denoiser != null) denoiser.entityMotionAddress = motionAddr;
        MemoryUtil.memPutLong(hdr + RtStatsLayout.MOTION_ADDR, motionAddr);
        MemoryUtil.memPutInt(hdr + RtStatsLayout.ENT_ATLAS_SIZE, eg.atlasAddress() != 0L ? io.github.andrexarlay.vulkancompat.rt.entity.EntityTextureAtlas.SIZE : 0);
        float jitterPxX = 0.0f;
        float jitterPxY = 0.0f;
        boolean upscalerJittered = io.github.andrexarlay.vulkancompat.rtx.upscaler.CameraJitterManager.isJitterActive()
            && io.github.andrexarlay.vulkancompat.rtx.upscaler.CameraJitterManager.isActive()
            && io.github.andrexarlay.vulkancompat.rtx.upscaler.CameraJitterManager.isScalingActive();
        if (upscalerJittered) {
            jitterPxX = io.github.andrexarlay.vulkancompat.rtx.upscaler.CameraJitterManager.getCurrentPixelJitterX();
            jitterPxY = io.github.andrexarlay.vulkancompat.rtx.upscaler.CameraJitterManager.getCurrentPixelJitterY();
        }
        params.upscalerJittered = upscalerJittered;
        params.upscalerJitterX = jitterPxX;
        params.upscalerJitterY = jitterPxY;
        MemoryUtil.memPutFloat(hdr + RtStatsLayout.CAMERA_JITTER, jitterPxX);
        MemoryUtil.memPutFloat(hdr + RtStatsLayout.CAMERA_JITTER + 4, jitterPxY);
        MemoryUtil.memPutFloat(hdr + RtStatsLayout.CAMERA_BOB_ORIGIN, bobOrigin.x);
        MemoryUtil.memPutFloat(hdr + RtStatsLayout.CAMERA_BOB_ORIGIN + 4, bobOrigin.y);
        MemoryUtil.memPutFloat(hdr + RtStatsLayout.CAMERA_BOB_ORIGIN + 8, bobOrigin.z);
        MemoryUtil.memPutFloat(hdr + RtStatsLayout.CAMERA_BOB_ORIGIN + 12, 0.0f);
        org.joml.Vector3f rawForward = io.github.andrexarlay.vulkancompat.rt.world.CameraBobPose.rawForward(
            camera.viewRotationMatrix, new org.joml.Vector3f());
        MemoryUtil.memPutFloat(hdr + RtStatsLayout.CAMERA_FORWARD, rawForward.x);
        MemoryUtil.memPutFloat(hdr + RtStatsLayout.CAMERA_FORWARD + 4, rawForward.y);
        MemoryUtil.memPutFloat(hdr + RtStatsLayout.CAMERA_FORWARD + 8, rawForward.z);
        // StatsBuf.cameraForward.w: strength of the optional baked vanilla light fill (RtSettings.bakedLightFill, rays mode)
        MemoryUtil.memPutFloat(hdr + RtStatsLayout.CAMERA_FORWARD + 12, io.github.andrexarlay.vulkancompat.rtx.config.RtSettings.bakedLightFill());
        org.joml.Matrix4f probePrev = cameraHistory.probePrev();
        cameraHistory.commit(camera);
        lastParams = params;
        RtSkyDisplayLayout.write(MemoryUtil.memByteBuffer(hdr, STATS_STRIDE), params.skyColor,
            params.key.day, params.tonemap, skyTextures.wordCount());
        renderLog.logSkyDisplay(pass, skyTextures);
        lastPassMode = pass.mode();
        renderLog.logShading(atlasW, atlasH, hasSky, sky);
        skyState.log(params.key, exposure.value(), skyTextures.loadedMask());
        renderLog.logPass(pass.describe(params));
        if (gpuTimer != null) {
            gpuTimer.stamp(cmd, SPAN_RAYS, false);
        }
        pass.dispatch(cmd, params, w, h);
        if (gpuTimer != null) {
            gpuTimer.stamp(cmd, SPAN_RAYS, true);
        }
        statsTracker.slots().written(statsLease, new StatsMetadata(
            (params.flags & RtFrameParams.FLAG_ACCUMULATE) != 0 && params.accumCount > 0,
            (params.flags & RtFrameParams.FLAG_TRANSPARENCY_STATS) != 0, params.key.day, dispatchCounter,
            System.nanoTime(), w, h, params.frame));
        statsTracker.incrementDispatchCounter();
        VkBarriers.fullMemoryBarrier(cmd);
        if (probePrev != null && frameDepth() != null) {
            io.github.andrexarlay.vulkancompat.rt.denoise.StabilityProbe.record(cmd, world.memory(), frameDepth().image, w, h,
                new org.joml.Matrix4f(params.invViewProjStable), probePrev, dx, dy, dz);
            VkBarriers.fullMemoryBarrier(cmd);
        }
        io.github.andrexarlay.vulkancompat.rt.denoise.StabilityProbe.tick();
        if (gpuTimer != null) {
            gpuTimer.stamp(cmd, SPAN_DENOISE, false);
        }
        RtDenoiserLifecycle.Recording denoised = denoisers.record(cmd, vulkanDevice, camera, target, pass, params,
            frameProjection, rrHardwareDepth, useDenoiser);
        provenance.setPendingFgGuides(denoised.pendingFgGuides());
        if (gpuTimer != null) {
            gpuTimer.stamp(cmd, SPAN_DENOISE, true);
        }
        recordPresentation(cmd, vulkanDevice, target, denoised.rrColorResolved(), atlasView, lightmapView, bobOrigin);
    }

    /** Output resolve, particle composite, atmosphere and (display only) the copy into the engine colour image. */
    private static void recordPresentation(VkCommandBuffer cmd, VulkanDevice vulkanDevice, RenderTarget target,
                                           boolean rrColorResolved, long atlasView, long lightmapView,
                                           org.joml.Vector3f bobOrigin) {
        RtMemory.Img presented = frameOutput(), presentedDepth = frameDepth();
        if (WorldTargetManager.rrResolvesOutput()) {
            outputResolve.record(cmd, params, frameResources, rrColorResolved);
            VkBarriers.fullMemoryBarrier(cmd);
            presented = frameComposite();
            presentedDepth = frameResources.displayDepth();
        }
        // Raster particles/weather and world depth match the presentation grid and stay outside RR.
        io.github.andrexarlay.vulkancompat.rt.particles.RtParticleLayer.composite(cmd, pathSlangRuntime(vulkanDevice),
            presented.view, presented.width, presented.height);

        // god rays + bloom + sun glare on the denoised final image (reads the ray distance, writes only rt_output)
        if (gpuTimer != null) {
            gpuTimer.stamp(cmd, SPAN_ATMO, false);
        }
        atmosphere.record(cmd, vulkanDevice, world.memory(), params, cameraHistory.curViewProj(),
            new RtFrameBindings.Views(world.tlasHandle(), world.sectionTable(), presented.view, atlasView, materials.materialView(),
                materials.normalView(), 0L, null, presentedDepth.view, lightmapView),
            presented.width, presented.height, bobOrigin.x, bobOrigin.y, bobOrigin.z);
        if (gpuTimer != null) {
            gpuTimer.stamp(cmd, SPAN_ATMO, true);
        }

        if (RtConfig.displayed()) {
            if (presented.width != target.width || presented.height != target.height)
                throw new RtFailure("copy_to_target", "presentation extent does not match the engine target");
            long dst = WorldTargetManager.extractVkImage(target);
            if (dst == 0L) {
                throw new RtFailure("copy_to_target", "engine VkImage of the main/world target is 0");
            }
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkImageCopy.Buffer region = VkImageCopy.calloc(1, stack);
                region.get(0).srcSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
                region.get(0).dstSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
                region.get(0).extent().width(presented.width).height(presented.height).depth(1);
                VK10.vkCmdCopyImage(cmd, presented.image, VK10.VK_IMAGE_LAYOUT_GENERAL, dst, VK10.VK_IMAGE_LAYOUT_GENERAL, region);
            }
            provenance.setPendingDisplayedTarget(target);
        }
    }

    /** True when this frame's denoiser layout consumes {@code entity_motion_px}; both of them read it as IN_MV input. */
    private static boolean wantsEntityMotion() {
        return (denoisers.nrd() != null || denoisers.rr() != null) && !Boolean.getBoolean("vkrtx.entityMotion.off");
    }

    /** Ray Tracing and Path Tracing run the ray profile and the path profile of the same Slang pass. */
    static RtMode passModeFor(RtMode mode) {
        return mode == RtMode.PATH_TRACED ? RtMode.PATH_TRACED : RtMode.RAY_TRACED;
    }

    /** StatsBuf.shadowPixel: pixelated-shadow cell edge in texture pixels (1 = one 16x16 block-texture pixel), 0 = off. */
    static float pixelShadowStatsValue() {
        return RtSettings.pixelShadows() ? RtSettings.pixelShadowSize() : 0f;
    }

    /**
     * Whether the pass of {@code mode} is built with the G-buffer: true for both ray tracing and path tracing whenever a
     * denoiser is selected (the denoiser needs the raw radiance, guide and motion channels of the G-buffer).
     */
    private static boolean wantGbuffer(RtMode mode) {
        DenoiserMode denoiseMode = denoisers.mode();
        return denoiseMode != DenoiserMode.OFF;
    }

    private static RtPass createPass(VkDevice device, RtMode mode, boolean gbuffer) {
        if (mode == RtMode.PATH_TRACED || mode == RtMode.RAY_TRACED) {
            boolean rayMode = mode == RtMode.RAY_TRACED;
            RtPass slang = passFactory.createSlangPathPass(device, gbuffer, rayMode, world != null,
                denoisers.mode(), wantsEntityMotion());
            if (slang != null) {
                return slang;
            }
            String pathSlangUnavailableReason = passFactory.unavailableReason();
            throw new RtFailure(rayMode ? "slang_ray" : "slang_path", pathSlangUnavailableReason);
        }
        // AO and DIRECT are debug shadings of the deleted ray pass, not images the Slang tracer produces: a lit path
        // labelled "ao" would be a wrong picture with a working log.
        throw new RtFailure("slang_" + mode.logName(),
            "rt_mode=" + mode.logName() + " shades a debug image the Slang renderer does not produce (use rays or path)");
    }

    private static SlangRuntime pathSlangRuntime(VulkanDevice vulkanDevice) {
        return passFactory.pathSlangRuntime(vulkanDevice);
    }

    /** True when the NRD path is active for this session (selected at world creation from -Dvkrtx.denoiser). */
    public static boolean nrdActive() {
        return denoisers.nrdActive();
    }

    /** True only while an NRD instance is present and has not been scheduled for failure shutdown. */
    public static boolean nrdRuntimeActive() {
        return denoisers.nrdRuntimeActive();
    }

    // [DLSS-RR] True when DLSS Ray Reconstruction is active for this session
    public static boolean dlssRrActive() {
        return denoisers.dlssRrActive();
    }

    /** True only while the DLSS-RR instance is present and has not been scheduled for failure shutdown. */
    public static boolean dlssRrRuntimeActive() {
        return denoisers.dlssRrRuntimeActive();
    }

    /** User/config request, distinct from the resolved native mode and its runtime instance. */
    public static RtSettings.PathDenoiser denoiserRequested() {
        return RtSettings.denoiser();
    }

    /** Selected native mode after resolving availability and fallback rules. */
    public static DenoiserMode denoiserSelected() {
        return denoisers.mode();
    }

    /** Resolution explanation; "setting" means the requested mode resolved directly. */
    public static String denoiserResolutionReason() {
        return denoisers.denoiserResolutionReason();
    }

    /** Last NRD/DLSS-RR instance creation or record failure in this world, if any. */
    public static String denoiserRuntimeFailure() {
        return denoisers.denoiserRuntimeFailure();
    }

    /** Current instance state for diagnostics. pending-drop is kept separate from active. */
    public static String denoiserRuntimeState() {
        return denoisers.denoiserRuntimeState();
    }

    // [DLSS-RR] Returns the active DLSS-RR denoiser, or null if inactive
    public static DlssRrDenoiser dlssRrDenoiser() {
        return denoisers.rr();
    }

    /** Mode of the pass that rendered the last RT frame (null before the first one); autotests. */
    public static RtMode lastPassMode() {
        return lastPassMode;
    }

    /**
     * True when the colour that reached the world target this frame has already been temporally resolved at render
     * resolution by NRD or DLSS-RR. Such an image is back on the pixel grid, so a following DLSS-SR pass must not apply
     * the camera jitter a second time.
     */
    public static boolean frameColorTemporallyResolved() {
        return denoisers.frameColorTemporallyResolved();
    }

    /** Clears the resolved-colour flag at the start of a world phase; called once per frame. */
    public static void resetFrameColorTemporallyResolved() {
        denoisers.resetFrameColorTemporallyResolved();
    }

    /** Frames averaged into the last path traced image (1 for the ray mode or a reset frame); autotests. */
    public static int lastAccumulatedFrames() {
        RtFrameParams p = lastParams;
        return p == null ? 0 : p.accumCount + 1;
    }

    private static void requireRayTracingDevice(VkDevice device) {
        VKCapabilitiesDevice caps = device.getCapabilities();
        if (!caps.VK_KHR_acceleration_structure || !caps.VK_KHR_ray_query
            || caps.vkCmdBuildAccelerationStructuresKHR == 0L) {
            throw new RtFailure("init", "device_without_ray_tracing (VK_KHR_acceleration_structure="
                + caps.VK_KHR_acceleration_structure + " VK_KHR_ray_query=" + caps.VK_KHR_ray_query + ")");
        }
    }

    private static void fail(String stage, String result, Throwable cause) {
        String safeResult = LogPaths.show(result);
        RtGeometryGate.markFailed(stage, safeResult);
        if (cause != null) {
            LOGGER.error("[VKB-RT] FAILED stage={} result={} (RT disabled for this session)\n{}", stage, safeResult, LogPaths.stack(cause));
        } else {
            LOGGER.error("[VKB-RT] FAILED stage={} result={} (RT disabled for this session)", stage, safeResult);
        }
        shutdown("failed");
    }

    private static boolean drainingHostSubmissions;
    /** The engine target is borrowed by native copies too; prove those uses before host resize/destruction. */
    public static void drainTargetRecordings() {
        if (world != null) {
            var host = VulkanCapabilityChecker.getVulkanDevice();
            if (host == null) throw new IllegalStateException("cannot retire an RT target without its host device");
            drainHostSubmissions(host.vkDevice(), "world_target_retirement");
        }
    }
    private static boolean rebuildPassNext;
    private static boolean teardownPending;

    /** Called outside a local recording: pending encoder work must be submitted before idle can prove completion. */
    private static void drainHostSubmissions(VkDevice device, String stage) {
        if (drainingHostSubmissions) throw new RtFailure(stage, "recursive host submission drain");
        VulkanDevice host = VulkanCapabilityChecker.getVulkanDevice();
        if (host == null || host.vkDevice().address() != device.address()) {
            throw new RtFailure(stage, "host device is unavailable or changed");
        }
        // The host fence captures this encoder's current submission serial.
        // On the single graphics queue it covers pending work and earlier uses;
        // timeout/failure is not permission to release any recording.
        drainingHostSubmissions = true;
        VulkanCommandEncoder encoder = host.createCommandEncoder();
        try (var completion = encoder.createFence()) {
            io.github.andrexarlay.vulkancompat.rtx.framegen.FrameGenRuntime.drainProducerRecordings();
            encoder.submit();
            if (!completion.awaitCompletion(5_000_000_000L)) {
                throw new RtFailure(stage, "host graphics submission completion timed out; resources retained");
            }
            recordingCompletion.afterHostDrain();
        } finally {
            drainingHostSubmissions = false;
        }
    }

    private static void shutdown(String reason) {
        if (drainingHostSubmissions) return;
        provenance.beginFrame();
        RtPass pl = pass;
        RtWorld w = world;
        if (w != null) {
            teardownPending = true;
            try {
                drainHostSubmissions(w.memory().device(), "shutdown_drain");
            } catch (RuntimeException | LinkageError e) {
                // Keep strong references for an explicit later shutdown. A
                // failed flush/idle is not permission to free pending resources.
                RtGeometryGate.markFailed("shutdown_drain", LogPaths.show(e.getMessage()));
                LOGGER.error("[VKB-RT] shutdown drain refused; retaining renderer resources reason={}\n{}", reason, LogPaths.stack(e));
                return;
            }
        }
        io.github.andrexarlay.vulkancompat.rt.particles.RtParticleLayer.release(reason);
        if (w == null) {
            // Replay can have queued game commands even when scene creation failed.
            try {
                if (io.github.andrexarlay.vulkancompat.rt.particles.RtParticleLayer.needsHostDrain()) {
                    VulkanDevice host = VulkanCapabilityChecker.getVulkanDevice();
                    if (host == null) {
                        teardownPending = true;
                        return;
                    }
                    drainHostSubmissions(host.vkDevice(), "particle_startup_failure_drain");
                    io.github.andrexarlay.vulkancompat.rt.particles.RtParticleLayer.collectAfterHostDrain(true);
                }
                atmosphere.destroy(); // same reason as below: its recordings live on this core
                if (passFactory.hasRuntime()) {
                    passFactory.closeLease();
                }
                teardownPending = false;
            } catch (RuntimeException | LinkageError e) {
                teardownPending = true;
                LOGGER.error("[VKB-RT] runtime cleanup failed before world publication\n{}", LogPaths.stack(e));
            }
            return;
        }
        try {
            io.github.andrexarlay.vulkancompat.rt.particles.RtParticleLayer.collectAfterHostDrain(true);
            if (pl != null) {
                pl.releaseRecordingsAfterHostDrain();
                pl.destroy();
                pass = null;
            }
            denoisers.destroyDenoisers();
            // Before the lease below: the atmosphere's passes hold recordings on this same core, and releasing the
            // last lease closes the core, which refuses while a recording is still pinned.
            atmosphere.destroy();
            if (outputResolve != null) { outputResolve.afterHostDrain(); outputResolve = null; }
            if (rrHardwareDepth != null) { rrHardwareDepth.afterHostDrain(); rrHardwareDepth = null; }
            if (passFactory.hasRuntime()) {
                w.closeComputeAfterHostDrain();
                passFactory.closeLease();
            }
            passFactory.resetUnavailable();
            if (gpuTimer != null) {
                gpuTimer.destroy();
                if (RtGpuTimer.active == gpuTimer) RtGpuTimer.active = null;
                gpuTimer = null;
            }
            if (materials != null) {
                materials.destroy();
                materials = null;
            }
            if (emitters != null) {
                emitters.destroy();
                emitters = null;
            }
            if (frameResources != null) {
                frameResources.close();
                frameResources = null;
            }
            if (statsTracker.statsBuf() != null) {
                w.memory().destroy(statsTracker.statsBuf());
                statsTracker.clearStatsBuf();
            }
            if (skyTextures != null) {
                skyTextures.destroy();
                skyTextures = null;
            }
            w.destroy();
            world = null;
            LOGGER.info("[VKB-RT] world_destroyed reason={}", reason);
        } catch (RuntimeException | LinkageError e) {
            LOGGER.error("[VKB-RT] FAILED stage=destroy result={} reason={}\n{}", LogPaths.show(e.getMessage()), reason, LogPaths.stack(e));
            RtGeometryGate.markFailed("destroy", LogPaths.show(e.getMessage()));
            return;
        }
        teardownPending = false;
        statsTracker.resetAfterShutdown();
        renderLog.resetAfterShutdown();
        rebuildPassNext = false;
        denoisers.resetAfterShutdown();
        lastPassMode = null;
    }
}