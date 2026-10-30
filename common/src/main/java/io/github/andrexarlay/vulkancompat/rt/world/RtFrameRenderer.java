package io.github.andrexarlay.vulkancompat.rt.world;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import io.github.andrexarlay.vulkanbridge.api.VulkanBridge;
import io.github.andrexarlay.vulkancompat.rt.lifetime.RecordingCompletion;
import io.github.andrexarlay.vulkancompat.rt.lifetime.RecordingReadbackSlots;
import io.github.andrexarlay.vulkancompat.rt.lifetime.RecordingCompletion.Retirement;
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
import io.github.andrexarlay.vulkancompat.rt.slang.SlangDeviceCapabilities;
import io.github.andrexarlay.vulkancompat.rt.slang.SlangRuntime;
import io.github.andrexarlay.vulkancompat.rt.slang.SlangRuntimePool;
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
import org.lwjgl.vulkan.VkBufferMemoryBarrier;
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
    /** Bytes per pixel of one ReSTIR parity half (Reservoir in rt_common.glsl, cross-checked by RtStatsLayoutTest). */
    static final int RESERVOIR_BYTES = 64;
    /** Bytes per pixel of one ReSTIR receiver-position half (ReservoirAuxBuf: vec4 camera-relative position). */
    static final int RESERVOIR_AUX_BYTES = 16;
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
    /** GPU timestamp spans of the RT frame; on by default, disabled with -Dvkrtx.rt.gpuTimers=false. */
    private static RtGpuTimer gpuTimer;
    /** God rays, bloom and sun glare post passes (Bedray atmosphere), run on the final image after the denoiser. */
    private static final RtAtmosphere atmosphere = new RtAtmosphere();
    private static final RecordingCompletion recordingCompletion =
        new RecordingCompletion(
            failure -> LOGGER.error("[VKB-RT] native recording release failed; retaining it for host drain", failure));
    /**
     * This renderer's handle on the device's one native core, leased from {@link SlangRuntimePool}. Created with the
     * feature mask the logical device really enabled, because path_trace uses ray queries, buffer device addresses and
     * Int64. The pool is what keeps it to one core per device: the atmosphere used to create its own, and a second
     * {@code BedrayCore} on the same device faults inside the driver on its first Vulkan call.
     */
    private static SlangRuntime pathSlangRuntime;
    private static SlangRuntimePool.Lease pathSlangLease;
    private static boolean pathSlangUnavailable;
    private static String pathSlangUnavailableReason = "Slang path runtime is not initialized";
    private static boolean pathSlangUnavailableLogged;
    private static final RtFogState fogState = new RtFogState();
    /** Per-pixel entity motion of rt_path for the DLSS-RR motion vectors (vec4 per pixel). */
    private static RtMemory.Buf entityMotionBuf;
    /** ReSTIR DI reservoirs of rt_rays.comp: two parity halves of 64 B per pixel (ReservoirBuf in rt_common.glsl). */
    private static RtMemory.Buf reservoirBuf;
    /** ReSTIR DI receiver positions of rt_rays.comp: two parity halves of 16 B per pixel, written with the reservoirs. */
    private static RtMemory.Buf reservoirAuxBuf;
    private static long reservoirPixels = -1L;
    private static long reservoirLightFingerprint = Long.MIN_VALUE;
    private static boolean reservoirResetRequested = true;
    private record StatsMetadata(boolean accumulated, boolean transparency, float day, long sequence, long recordedNanos) {}
    private static RecordingReadbackSlots<StatsMetadata> statsReadbacks = new RecordingReadbackSlots<>(STATS_SLOTS);
    private static long lastStatsSequence = -1;
    private static long dispatchCounter = 0;
    private static long passFrames = 0;
    private static double lastHitPct = -1.0;
    /** Share of the hit pixels whose key light reached them through a leaf card (1-in-16 mask, like hist_pct). */
    private static double lastLeafPct = -1.0;
    private static volatile String lastTransparency = "[RT-TRANSPARENCY] counters=WAITING";

    public static String transparencyTelemetry() {
        var settings = io.github.andrexarlay.vulkancompat.rtx.config.RtSettings.transparencyStats();
        return (settings ? lastTransparency : "[RT-TRANSPARENCY] counters=OFF")
            + " glass_faces_examined=" + io.github.andrexarlay.vulkancompat.rt.sodium.GlassFaceFilter.examinedFaces()
            + " glass_faces_removed=" + io.github.andrexarlay.vulkancompat.rt.sodium.GlassFaceFilter.removedFaces()
            + " optical_palette_profiles=" + (materials == null ? 0 : materials.opticalProfileCount());
    }
    private static double passMsInWindow = 0.0;
    private static int passFramesInWindow = 0;
    /** Wall clock of the previous traced frame: the time base of {@link RtAdaptiveQuality} (frame-to-frame, not per span). */
    private static long lastTracedNs = 0L;
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
    /** Set when this frame's RT colour was resolved by a same-resolution temporal denoiser (NRD / DLSS-RR). */
    private static volatile boolean frameColorTemporallyResolved = false;

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
        String worldStatus = world == null ? "UNAVAILABLE" : (lastStats == null ? "INITIALIZING" : "READY");
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

    /**
     * Motion vectors the denoiser itself reprojected with (RG16F, render resolution, GENERAL layout), 0 = none.
     * DLSS-RR derives them from the RT linear view Z with per-pixel entity motion, so a following DLSS-SR pass must reuse
     * them instead of a raster-depth pass that only knows camera motion.
     */
    public static long activeMotionVectorView() {
        return dlssRrDenoiser != null && dlssRrDenoiser.inMotion() != null ? dlssRrDenoiser.inMotion().view : 0L;
    }

    public static long activeMotionVectorImage() {
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
    private static io.github.andrexarlay.vulkancompat.rtx.frame.WorldFrame.Stamp displayedStamp;
    private static RenderTarget displayedTarget, pendingDisplayedTarget;
    private static org.joml.Matrix4f displayedProjection;

    /** Current-frame color provenance; old output allocations are not evidence that RT replaced this frame. */
    public static boolean displayedThisFrame(RenderTarget target) {
        return displayedStamp != null && displayedTarget == target
            && io.github.andrexarlay.vulkancompat.rtx.frame.WorldFrames.isCurrent(displayedStamp);
    }

    public static org.joml.Matrix4f displayedProjection(RenderTarget target) {
        return displayedThisFrame(target) && displayedProjection != null ? new org.joml.Matrix4f(displayedProjection) : null;
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
        return displayedThisFrame(target) ? outputDepth : null;
    }
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
        if (denoiser != null) {
            denoiser.resetHistory();
        }
        if (dlssRrDenoiser != null) {
            dlssRrDenoiser.resetHistory();
        }
        exposure.reset();
        atmosphere.resetHistory();
        reservoirResetRequested = true;
        lastHistPct = -1.0;
        lastLeafPct = -1.0;
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
        displayedStamp = null; displayedTarget = pendingDisplayedTarget = null; displayedProjection = null;
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
        } catch (RtFailure f) {
            fail(f.stage(), f.result(), f.getCause() != null ? f.getCause() : f);
        } catch (RuntimeException | LinkageError e) {
            fail("frame", e.getClass().getSimpleName() + ": " + e.getMessage(), e);
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
                    gpuTimer = new RtGpuTimer(device, "VKB-RT-GPU", "world", "rays", "denoise", "atmo");
                } catch (RuntimeException e) {
                    // A device without timestamp queries must lose the timings, not the whole RT frame.
                    LOGGER.warn("[VKB-RT] gpu timers unavailable: {}", e.getMessage());
                }
            }
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
        int curTransparencyPolicy = (RtSettings.opticalMaterials() ? 1 : 0)
            | (RtSettings.directTranslucentShadows() ? 2 : 0)
            | (RtSettings.indirectTranslucentShadows() ? 4 : 0);
        // ReSTIR is a per-technique switch, and every one of them changes what a stored record means: turning reuse on or
        // off, or pooling neighbours on top of it, must not continue an accumulation that was built without it.
        int curReusePolicy = (RtSettings.restirTemporal() ? 1 : 0) | (RtSettings.restirSpatial() ? 2 : 0)
            | (RtSettings.pathRestirDi() ? 4 : 0) | (RtSettings.pathRestirSpatial() ? 8 : 0);

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

        boolean retireRr = denoiseMode != DenoiserMode.DLSS_RR && dlssRrDenoiser != null;
        boolean retireNrd = (!denoiseMode.isNrd() || (denoiser != null && denoiser.mode() != denoiseMode)) && denoiser != null;
        if (retireRr || retireNrd) drainHostSubmissions(device, "denoiser_switch_drain");
        if (retireRr) {
            dlssRrDenoiser.destroy();
            dlssRrDenoiser = null;
        }
        if (retireNrd) {
            denoiser.destroy();
            denoiser = null;
        }

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
            lastPassLog = "";
        }
        consumeCompletedStats();
        if (!statsReadbacks.hasFreeSlot()) {
            drainHostSubmissions(device, "stats_slots_drain");
            consumeCompletedStats();
            if (!statsReadbacks.hasFreeSlot()) throw new RtFailure("stats_slots", "completion proof unavailable; stats slots retained");
        }
        if (world.requiresLegacyHostDrain()) {
            drainHostSubmissions(device, "legacy_world_inputs_drain");
            world.prepareLegacyFrameAfterHostDrain();
        }
        long frameStart = System.nanoTime();
        if (emitters == null) emitters = new RtEmitters(world.memory());
        RtEmitters recordingEmitters = emitters;
        VulkanCommandEncoder encoder = vulkanDevice.createCommandEncoder();
        VkCommandBuffer cmd = encoder.allocateAndBeginTransientCommandBuffer();
        var statsOwner = statsReadbacks;
        var statsLease = statsOwner.reserve(cmd.address());
        if (statsLease == null) throw new RtFailure("stats_slots", "stats reservation unavailable");
        RtWorld recordingWorld = world;
        RtPass recordingPass = pass;
        Runnable[] particleRelease = {() -> {}};
        Retirement[] denoiserProof = {new Retirement(() -> {}, () -> {})};
        RecordingCompletion.Ticket recording;
        try {
            recording = recordingCompletion.beginWithDiscard(cmd.address(),
            new Retirement(() -> statsOwner.completed(statsLease), () -> statsOwner.discarded(statsLease)),
            new Retirement(
                () -> atmosphere.releaseRecording(cmd.address()), () -> atmosphere.discardRecording(cmd.address())),
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
            lastStats = stats;
            blasBuiltTotal += stats.builtThisFrame();
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
        if (pendingDisplayedTarget == target && frameProjection != null) {
            displayedStamp = io.github.andrexarlay.vulkancompat.rtx.frame.WorldFrames.stampOrNull();
            displayedTarget = target;
            displayedProjection = RtConfig.FLIP_Y
                ? new org.joml.Matrix4f().scaling(1, -1, 1).mul(frameProjection) : new org.joml.Matrix4f(frameProjection);
        }
        if (traced) {
            long tracedNs = System.nanoTime();
            double dtMs = lastTracedNs == 0L ? 0.0 : (tracedNs - lastTracedNs) / 1.0e6;
            lastTracedNs = tracedNs;
            passFrames++;
            passFramesInWindow++;
            passMsInWindow += (tracedNs - frameStart) / 1.0e6;
            if (pass.mode() == RtMode.PATH_TRACED) {
                // what the ladder can actually move: the traced rays and the denoiser (the chunk build behind SPAN_WORLD does
                // not get cheaper with fewer rays, and would only pin the scene at one ray while the world streams)
                RtAdaptiveQuality.observe(gpuTimer == null ? Double.NaN : gpuTimer.recentMs(SPAN_RAYS, SPAN_DENOISE), dtMs);
            }
        }
        logWindow(stats, target);
    }

    /** Only completed, still-leased slots may be read; sequence orders results, never proves completion. */
    private static void consumeCompletedStats() {
        var ready = statsReadbacks.ready().stream()
            .sorted(java.util.Comparator.comparingLong(lease -> lease.metadata().sequence())).toList();
        for (var lease : ready) {
            var metadata = lease.metadata();
            if (metadata.sequence() > lastStatsSequence) {
                long base = statsBuf.mapped + (long) lease.slot() * STATS_STRIDE;
                int hits = MemoryUtil.memGetInt(base + RtStatsLayout.HITS);
                int samples = MemoryUtil.memGetInt(base + RtStatsLayout.SAMPLES);
                lastTransparency = metadata.transparency() ? String.format(Locale.ROOT,
                    "[RT-TRANSPARENCY] counters=READBACK queries=%d candidates=%d layers=%d overflows=%d direct_shadow_queries=%d indirect_shadow_queries=%d",
                    Integer.toUnsignedLong(MemoryUtil.memGetInt(base + RtStatsLayout.TRANS_QUERIES)),
                    Integer.toUnsignedLong(MemoryUtil.memGetInt(base + RtStatsLayout.TRANS_CANDIDATES)),
                    Integer.toUnsignedLong(MemoryUtil.memGetInt(base + RtStatsLayout.TRANS_LAYERS)),
                    Integer.toUnsignedLong(MemoryUtil.memGetInt(base + RtStatsLayout.TRANS_OVERFLOWS)),
                    Integer.toUnsignedLong(MemoryUtil.memGetInt(base + RtStatsLayout.TRANS_DIRECT_SHADOWS)),
                    Integer.toUnsignedLong(MemoryUtil.memGetInt(base + RtStatsLayout.TRANS_INDIRECT_SHADOWS)))
                    : "[RT-TRANSPARENCY] counters=WAITING";
                if (hits > 0) {
                    // Without the accumulating variant histAccepted is never incremented, and the share used to print as a
                    // plausible 0% ("history rejected every pixel") in exactly the sessions that have no history at all.
                    lastHistPct = RtAccumulation.acceptedPct(MemoryUtil.memGetInt(base + RtStatsLayout.HIST_ACCEPTED), hits,
                        metadata.accumulated());
                    // leafBacklit is the one head counter with no writer. The legacy transparency pass counted the
                    // sun-through-a-leaf hits behind its own 1-in-16 mask and the statistics pass keeps no count of it,
                    // so a zero here is the absence of a measurement, not a share of 0%: -1 is what the F3 line prints
                    // as n/a.
                    int leafBacklit = MemoryUtil.memGetInt(base + RtStatsLayout.LEAF_BACKLIT);
                    lastLeafPct = leafBacklit > 0 ? 100.0 * leafBacklit / hits : -1.0;
                }
                if (samples > 0) {
                    lastHitPct = 100.0 * hits / samples;
                    exposure.setMax(RtSkyState.maxExposure(metadata.day()));
                    long expNow = metadata.recordedNanos();
                    exposure.observe(Integer.toUnsignedLong(MemoryUtil.memGetInt(base + RtStatsLayout.LUM_LOG_SUM)), samples,
                        lastExposureNanos == 0L ? 1.0f / 60.0f : (float) ((expNow - lastExposureNanos) / 1.0e9));
                    lastExposureNanos = expNow;
                    lastExposureValue = exposure.value();
                    long sumLog = Integer.toUnsignedLong(MemoryUtil.memGetInt(base + RtStatsLayout.LUM_LOG_SUM));
                    lastAvgLum = sumLog == 0L ? 0.0 : Math.pow(2.0, sumLog / 64.0 / samples - 16.0);
                }
                lastStatsSequence = metadata.sequence();
            }
            statsReadbacks.consume(lease);
        }
    }

    /** GPU writes become visible to coherent mapped host memory after this recording completes. */
    private static void recordStatsHostVisibility(VkCommandBuffer cmd, int slot) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var barrier = VkBufferMemoryBarrier.calloc(1, stack).sType$Default()
                .srcAccessMask(VK10.VK_ACCESS_SHADER_WRITE_BIT | VK10.VK_ACCESS_TRANSFER_WRITE_BIT)
                .dstAccessMask(VK10.VK_ACCESS_HOST_READ_BIT)
                .srcQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                .buffer(statsBuf.handle).offset((long) slot * STATS_STRIDE).size(STATS_STRIDE);
            VK10.vkCmdPipelineBarrier(cmd, VK10.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK10.VK_PIPELINE_STAGE_HOST_BIT,
                0, null, barrier, null);
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
        // The vanilla lightmap (16x16, block light x sky light) colours the baked light fill and the vanilla water sheet
        // with the real tint of the pack, dimension and effects; 0 binds the atlas as a benign fallback.
        long lightmapView = 0L;
        try {
            lightmapView = WorldTargetManager.extractVkImageView(Minecraft.getInstance().gameRenderer.lightmap());
        } catch (RuntimeException | LinkageError e) {
            LOGGER.debug("[VKB-RT] lightmap view unavailable: {}", e.toString());
        }
        if (denoiseMode == DenoiserMode.DLSS_RR) {
            // [DLSS-RR]
            if (pass.usesGbuffer() && dlssRrDenoiser == null) {
                createDlssRrDenoiser(device, w, h);
            }
            if (dlssRrDenoiser != null) {
                DlssRrDenoiser owner = dlssRrDenoiser;
                denoiserProof[0] = new Retirement(() -> owner.releaseRecording(cmd.address()),
                    () -> owner.discardRecording(cmd.address()));
                owner.ensure(cmd, w, h, output);
            }
        }
        if (denoiseMode.isNrd()) {
            if (pass.usesGbuffer() && denoiser == null) {
                createDenoiser(device, w, h);
            }
            if (denoiser != null) {
                NrdDenoiser owner = denoiser;
                denoiserProof[0] = new Retirement(() -> owner.releaseRecording(cmd.address()),
                    () -> owner.discardRecording(cmd.address()));
                owner.ensure(w, h, output.view);
                owner.prepare(cmd);
            }
        }
        boolean useDenoiser = pass.usesGbuffer() && (denoiser != null || dlssRrDenoiser != null);
        // NRD reads this frame's per-pixel world motion through its own binding, so the buffer has to exist before the
        // pass binds its views: the handle travels with them, and the pass writes exactly that range.
        long motionHandle = 0L;
        if (useDenoiser && denoiseMode.isNrd() && wantsEntityMotion()) {
            ensureEntityMotionBuffer(device, w, h);
            motionHandle = entityMotionBuf.handle;
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
        pass.bind(new RtFrameBindings.Views(world.tlasHandle(), world.sectionTable(), output.view, atlasView, materials.materialView(),
            materials.normalView(), 0L, gbViews, outputDepth != null ? outputDepth.view : 0L, lightmapView, motionHandle));

        int writeSlot = statsLease.slot();
        long writeOffset = (long) writeSlot * STATS_STRIDE;
        VK10.vkCmdFillBuffer(cmd, statsBuf.handle, writeOffset, 16, 0);
        // leafBacklit sits far past the four head counters, so the 16-byte fill above does not reach it: without its own
        // zeroing the slot would keep the count of the frame that used it three slots ago.
        VK10.vkCmdFillBuffer(cmd, statsBuf.handle, writeOffset + RtStatsLayout.LEAF_BACKLIT, 4, 0);
        VK10.vkCmdFillBuffer(cmd, statsBuf.handle, writeOffset + RtStatsLayout.TRANS_QUERIES, 24, 0);
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
        params.cameraX = camera.pos.x;
        params.cameraY = camera.pos.y;
        params.cameraZ = camera.pos.z;
        params.bobX = bobOrigin.x;
        params.bobY = bobOrigin.y;
        params.bobZ = bobOrigin.z;
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
        // ReSTIR DI: one reservoir per pixel, two parity halves. Only the ray pass reads it; path tracing keeps plain RIS.
        // With reuse switched off the shader is handed a zero reservoir address, which is the shader's own "off" test, and
        // the ring is not even filled - so switching back on converges from nothing rather than from stale records.
        long reservoirAddr = 0L;
        long reservoirAuxAddr = 0L;
        if (pass.mode() == RtMode.RAY_TRACED && RtSettings.restirTemporal()
            && (params.flags & RtFrameParams.FLAG_EMISSIVE) != 0) {
            long pixels = (long) w * h;
            long need = pixels * 2L * RESERVOIR_BYTES;
            boolean reset = false;
            if (reservoirBuf == null || reservoirBuf.size < need) {
                if (reservoirBuf != null) {
                    VK10.vkDeviceWaitIdle(device);
                    world.memory().destroy(reservoirBuf);
                }
                reservoirBuf = world.memory().createBuffer("rt_restir", need,
                    VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT
                        | org.lwjgl.vulkan.VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT, false);
                reset = true;
                LOGGER.info("[VKB-RT] ReSTIR reservoirs {}x{} x2 = {} KiB", w, h, need / 1024);
            }
            long auxNeed = pixels * 2L * RESERVOIR_AUX_BYTES;
            if (reservoirAuxBuf == null || reservoirAuxBuf.size < auxNeed) {
                if (reservoirAuxBuf != null) {
                    VK10.vkDeviceWaitIdle(device);
                    world.memory().destroy(reservoirAuxBuf);
                }
                reservoirAuxBuf = world.memory().createBuffer("rt_restir_aux", auxNeed,
                    VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT
                        | org.lwjgl.vulkan.VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT, false);
                LOGGER.info("[VKB-RT] ReSTIR receiver aux {}x{} x2 = {} KiB", w, h, auxNeed / 1024);
                // No fill: every pixel rewrites its own slot before the spatial pass reads it, and a stale position
                // next to a zeroed (empty) reservoir is skipped by the M == 0 test there anyway.
            }
            if (pixels != reservoirPixels) {
                // A different resolution moves every pixel: the old records sit at other indices and must not be read.
                reservoirPixels = pixels;
                reset = true;
            }
            // Only a change of the lights themselves invalidates a stored sample (a removed torch would otherwise keep
            // glowing through history forever). A streaming section or a camera re-anchor leaves the history valid, and
            // rebuilding the whole buffer for those would cost hundreds of MB of fill per frame while moving.
            long lightFingerprint = emitters.fingerprint();
            if (lightFingerprint != reservoirLightFingerprint) {
                reservoirLightFingerprint = lightFingerprint;
                reset = true;
            }
            if (reservoirResetRequested) {
                reset = true;
                reservoirResetRequested = false;
            }
            reservoirAddr = reservoirBuf.address;
            reservoirAuxAddr = reservoirAuxBuf.address;
            if (reset) {
                // A removed torch must not keep contributing through a stored sample: the world changed, drop all history.
                VK10.vkCmdFillBuffer(cmd, reservoirBuf.handle, 0L, reservoirBuf.size, 0);
                VkBarriers.fullMemoryBarrier(cmd);
            }
        }
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
        // StatsBuf.reservoirAddr (488): ReSTIR parity buffer of the ray pass; 0 disables temporal reuse
        MemoryUtil.memPutLong(hdr + RtStatsLayout.RESERVOIR_ADDR, reservoirAddr);
        // StatsBuf.reservoirAuxAddr (496): camera-relative receiver position per reservoir record (spatial reuse)
        MemoryUtil.memPutLong(hdr + RtStatsLayout.RESERVOIR_AUX_ADDR, reservoirAuxAddr);
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
        prevViewProj.get(RtStatsLayout.PREV_VIEW_PROJ, MemoryUtil.memByteBuffer(hdr, STATS_STRIDE));
        float dx = (float) (camera.pos.x - prevCamX);
        float dy = (float) (camera.pos.y - prevCamY);
        float dz = (float) (camera.pos.z - prevCamZ);
        boolean camMoved = Math.abs(dx) > 1.0e-7f || Math.abs(dy) > 1.0e-7f || Math.abs(dz) > 1.0e-7f
            || !prevViewProj.equals(curViewProj, 1.0e-7f);
        MemoryUtil.memPutFloat(hdr + RtStatsLayout.CAM_DELTA, dx);
        MemoryUtil.memPutFloat(hdr + RtStatsLayout.CAM_DELTA + 4, dy);
        MemoryUtil.memPutFloat(hdr + RtStatsLayout.CAM_DELTA + 8, dz);
        MemoryUtil.memPutFloat(hdr + RtStatsLayout.CAM_DELTA + 12, camMoved ? 1.0f : 0.0f);
        params.prevViewProj.set(prevViewProj);
        params.cameraDeltaX = dx;
        params.cameraDeltaY = dy;
        params.cameraDeltaZ = dz;
        params.cameraStatic = !camMoved;
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
        long motionAddr = 0L;
        if (useDenoiser && wantsEntityMotion()) {
            ensureEntityMotionBuffer(device, w, h);
            motionAddr = entityMotionBuf.address;
            if (dlssRrDenoiser != null) {
                dlssRrDenoiser.entityMotionAddress = motionAddr;
            }
            if (denoiser != null) {
                denoiser.entityMotionAddress = motionAddr;
            }
        }
        MemoryUtil.memPutLong(hdr + RtStatsLayout.MOTION_ADDR, motionAddr);
        MemoryUtil.memPutInt(hdr + RtStatsLayout.ENT_ATLAS_SIZE, eg.atlasAddress() != 0L ? io.github.andrexarlay.vulkancompat.rt.entity.EntityTextureAtlas.SIZE : 0);
        float jitterPxX = 0.0f;
        float jitterPxY = 0.0f;
        if (io.github.andrexarlay.vulkancompat.rtx.upscaler.CameraJitterManager.isJitterActive()
            && io.github.andrexarlay.vulkancompat.rtx.upscaler.CameraJitterManager.isActive()
            && io.github.andrexarlay.vulkancompat.rtx.upscaler.CameraJitterManager.isScalingActive()) {
            jitterPxX = io.github.andrexarlay.vulkancompat.rtx.upscaler.CameraJitterManager.getCurrentPixelJitterX();
            jitterPxY = io.github.andrexarlay.vulkancompat.rtx.upscaler.CameraJitterManager.getCurrentPixelJitterY();
        }
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
        if (gpuTimer != null) {
            gpuTimer.stamp(cmd, SPAN_RAYS, false);
        }
        pass.dispatch(cmd, params, w, h);
        if (gpuTimer != null) {
            gpuTimer.stamp(cmd, SPAN_RAYS, true);
        }
        statsReadbacks.written(statsLease, new StatsMetadata(
            (params.flags & RtFrameParams.FLAG_ACCUMULATE) != 0 && params.accumCount > 0,
            (params.flags & RtFrameParams.FLAG_TRANSPARENCY_STATS) != 0, params.key.day, dispatchCounter, System.nanoTime()));
        dispatchCounter++;
        VkBarriers.fullMemoryBarrier(cmd);
        if (probePrev != null && outputDepth != null) {
            io.github.andrexarlay.vulkancompat.rt.denoise.StabilityProbe.record(cmd, world.memory(), outputDepth.image, w, h,
                new org.joml.Matrix4f(params.invViewProjStable), probePrev, dx, dy, dz);
            VkBarriers.fullMemoryBarrier(cmd);
        }
        io.github.andrexarlay.vulkancompat.rt.denoise.StabilityProbe.tick();
        if (gpuTimer != null) {
            gpuTimer.stamp(cmd, SPAN_DENOISE, false);
        }
        if (useDenoiser) {
            if (dlssRrDenoiser != null) {
                // [DLSS-RR] Record DLSS Ray Reconstruction
                try {
                    // The frame's own exposure, decided once above: the pack's tonemap and the path display cannot diverge.
                    float composeExposure = params.exposure;
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
                    DlssRrDenoiser owner = dlssRrDenoiser;
                    owner.record(cmd, frameProjection, camera.viewRotationMatrix, camera.pos.x, camera.pos.y, camera.pos.z,
                        params.tonemap, composeExposure, depthView, rrJitterX, rrJitterY);
                    frameColorTemporallyResolved = true;
                    VkBarriers.fullMemoryBarrier(cmd);
                } catch (Exception e) {
                    if (e instanceof io.github.andrexarlay.vulkancompat.rt.core.BedrayCoreException nativeFailure)
                        throw nativeFailure; // partial coarse recording requires whole-command discard
                    denoiserRuntimeFailure = "dlss_rr_record: " + String.valueOf(e.getMessage());
                    LOGGER.error("[DLSS-RR] FAILED record: {} (denoiser disabled for this session, raw RT image is shown)",
                        e.getMessage(), e);
                    dropDlssRrDenoiserNext = true;
                    dropDlssRrByError = true;
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
        if (gpuTimer != null) {
            gpuTimer.stamp(cmd, SPAN_DENOISE, true);
        }

        // particles of the game (drawn into their own layer by RtParticleLayer.replay) over the denoised image, before bloom / god rays
        io.github.andrexarlay.vulkancompat.rt.particles.RtParticleLayer.composite(cmd, pathSlangRuntime(vulkanDevice), output.view, w, h);

        // god rays + bloom + sun glare on the denoised final image (reads the ray distance, writes only rt_output)
        if (gpuTimer != null) {
            gpuTimer.stamp(cmd, SPAN_ATMO, false);
        }
        atmosphere.record(cmd, vulkanDevice, world.memory(), params, curViewProj,
            new RtFrameBindings.Views(world.tlasHandle(), world.sectionTable(), output.view, atlasView, materials.materialView(),
                materials.normalView(), 0L, null, outputDepth.view, lightmapView),
            w, h, bobOrigin.x, bobOrigin.y, bobOrigin.z);
        if (gpuTimer != null) {
            gpuTimer.stamp(cmd, SPAN_ATMO, true);
        }

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
            pendingDisplayedTarget = target;
        }
    }

    /** True when this frame's denoiser layout consumes {@code entity_motion_px}; both of them read it as IN_MV input. */
    private static boolean wantsEntityMotion() {
        return (denoiser != null || dlssRrDenoiser != null) && !Boolean.getBoolean("vkrtx.entityMotion.off");
    }

    /**
     * One vec4 per render pixel, storage+device-address: NRD's {@code IN_MV} channel and the RR velocity pass's entity
     * term. Idempotent within a frame sized like the last one; a resize waits for the device because the old extent may
     * still be in flight.
     */
    private static void ensureEntityMotionBuffer(VkDevice device, int w, int h) {
        long need = (long) w * h * 16L;
        if (entityMotionBuf != null && entityMotionBuf.size >= need) {
            return;
        }
        if (entityMotionBuf != null) {
            VK10.vkDeviceWaitIdle(device);
            world.memory().destroy(entityMotionBuf);
        }
        entityMotionBuf = world.memory().createBuffer("entity_motion_px", need,
            VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | org.lwjgl.vulkan.VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT, false);
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
        if (RtConfig.mode() == RtMode.PATH_TRACED && want != RtSettings.PathDenoiser.OFF) {
            // The new path does not yet supply the guides required by NRD/DLSS-RR.
            // Preserve the requested setting, report the disabled stage and never
            // select a different renderer to make that request appear supported.
            String reason = "slang_path_has_no_gbuffer";
            String line = "requested=" + want + " active=off reason=" + reason;
            denoiserResolutionReason = reason;
            if (!line.equals(lastDenoiserLog)) {
                lastDenoiserLog = line;
                LOGGER.info("[VKB-RT] denoiser {}", line);
            }
            return DenoiserMode.OFF;
        }
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
            dlssRrDenoiser = new DlssRrDenoiser(device, world.memory(), w, h, output,
                pathSlangRuntime(VulkanCapabilityChecker.getVulkanDevice()));
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
            drainHostSubmissions(device, "drop_rr_drain");
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
            denoiser = new NrdDenoiser(device, world.memory(), denoiseMode, denoiseAvailability, w, h, output.view,
                pathSlangRuntime(VulkanCapabilityChecker.getVulkanDevice()));
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
            drainHostSubmissions(device, "drop_nrd_drain");
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
        // May be requested while a local command buffer is being recorded.
        // Rebuild only at the next frame boundary, before allocating that buffer.
        rebuildPassNext = true;
    }

    /** Ray Tracing unless Path Tracing is selected (also the pass of the legacy debug-view-only runs, mode off). */
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
        return denoiseMode != DenoiserMode.OFF;
    }

    private static RtPass createPass(VkDevice device, RtMode mode, boolean gbuffer) {
        if (mode == RtMode.PATH_TRACED) {
            RtPass slang = createSlangPathPass(device, gbuffer);
            if (slang != null) {
                return slang;
            }
            throw new RtFailure("slang_path", pathSlangUnavailableReason);
        }
        // Ray tracing mode has no Slang effect pass yet; the GLSL one is deleted with the legacy renderer.
        throw new RtFailure("slang_ray", "ray effects are not yet available in the Slang renderer");
    }

    /**
     * Creates the AOT Slang path or reports why it cannot run. There is no second renderer to fall back to: a missing
     * Slang artifact fails the frame, and {@code vkbridge.rt.failure} records the reason.
     */
    private static RtPass createSlangPathPass(VkDevice device, boolean gbuffer) {
        String debugView = RtConfig.currentDebugView();
        if (!debugView.isEmpty()) {
            pathSlangUnavailableReason = "debug view '" + debugView + "' is not implemented in the Slang path";
            logSlangUnavailable(pathSlangUnavailableReason);
            return null;
        }
        VulkanDevice vulkanDevice = VulkanCapabilityChecker.getVulkanDevice();
        if (vulkanDevice == null) {
            pathSlangUnavailableReason = "VulkanDevice is not available";
            logSlangUnavailable(pathSlangUnavailableReason);
            return null;
        }
        try {
            SlangRuntime runtime = pathSlangRuntime(vulkanDevice);
            String problem = RtSlangPathPass.supportProblem(runtime, gbuffer);
            if (problem == null && RtConfig.SLANG_PATH_TRACE_CPP && gbuffer && denoiseMode.isNrd()
                && !runtime.library().hasNativeMotion()) {
                problem = "native NRD requires a matching core with NRD_MOTION";
            }
            if (problem != null) {
                pathSlangUnavailableReason = problem;
                logSlangUnavailable(problem);
                return null;
            }
            if (world == null) {
                pathSlangUnavailableReason = "the RT world is not initialized";
                logSlangUnavailable(pathSlangUnavailableReason);
                return null;
            }
            RtSlangPathPass slang = new RtSlangPathPass(device, runtime, gbuffer);
            LOGGER.info("[VKB-RT] path tracing on AOT Slang passes {}+{} (gbuffer={})",
                RtPathTraceSlangPass.PASS_NAME, RtSlangPathPass.DISPLAY_PASS, gbuffer);
            return slang;
        } catch (RtFailure failure) {
            pathSlangUnavailableReason = failure.result();
            throw failure;
        } catch (RuntimeException e) {
            String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            pathSlangUnavailableReason = reason;
            throw new RtFailure("slang_path", reason, e);
        }
    }

    private static SlangRuntime pathSlangRuntime(VulkanDevice vulkanDevice) {
        if (pathSlangRuntime != null && !pathSlangRuntime.isClosed()) {
            return pathSlangRuntime;
        }
        if (pathSlangUnavailable) {
            throw new IllegalStateException(pathSlangUnavailableReason);
        }
        try {
            int enabledMask = SlangDeviceCapabilities.enabledMask(vulkanDevice);
            pathSlangLease = SlangRuntimePool.acquire(vulkanDevice);
            SlangRuntime runtime = pathSlangLease.runtime();
            pathSlangRuntime = runtime;
            LOGGER.info("[VKB-RT] Slang path runtime ready enabled_mask=0x{}", Integer.toHexString(enabledMask));
            return runtime;
        } catch (RuntimeException e) {
            pathSlangUnavailable = true;
            pathSlangUnavailableReason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            throw e;
        }
    }

    private static void logSlangUnavailable(String reason) {
        if (!pathSlangUnavailableLogged) {
            pathSlangUnavailableLogged = true;
            LOGGER.info("[VKB-RT] Slang path tracing unavailable: {}", reason);
        }
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

    /**
     * True when the colour that reached the world target this frame has already been temporally resolved at render
     * resolution by NRD or DLSS-RR. Such an image is back on the pixel grid, so a following DLSS-SR pass must not apply
     * the camera jitter a second time.
     */
    public static boolean frameColorTemporallyResolved() {
        return frameColorTemporallyResolved;
    }

    /** Clears the resolved-colour flag at the start of a world phase; called once per frame. */
    public static void resetFrameColorTemporallyResolved() {
        frameColorTemporallyResolved = false;
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

    /** Milliseconds for the status line, or "n/a" while the GPU timer has no readable sample. */
    private static String msOrNa(double v) {
        return v < 0.0 ? "n/a" : String.format(Locale.ROOT, "%.1f", v);
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
                "[VKB-RT] blas built=%d total=%d build_ms=%.2f tlas instances=%d tlas_ms=%.2f vram_mb=%d pending=%d failed=%d identical_skipped=%d veg_sections=%d veg_quads=%d",
                builtInWindow, s.totalSections(), buildMsInWindow, s.instances(), s.tlasMs(), s.vramBytes() >> 20,
                s.pending(), s.failedSections(), world != null ? world.identicalSkipped() : 0L,
                world != null ? world.vegetationSections() : 0L, world != null ? world.vegetationQuads() : 0L));
        }
        if (passFramesInWindow > 0) {
            int userSpp = RtSettings.spp();
            int userBounces = RtSettings.bounces();
            double budget = RtSettings.adaptiveBudgetMs();
            LOGGER.info(String.format(Locale.ROOT,
                "[VKB-RT] rt_pass ok frames=%d rays=%d hit_pct=%s hist_pct=%s leaf_pct=%s ms=%.2f spp=%d bounces=%d work_ms=%s pass_ms=%s budget_ms=%s adaptive=%d/%d displayed=%s mode=%s",
                passFrames, target != null ? (long) target.width * target.height : 0L,
                lastHitPct < 0 ? "n/a" : String.format(Locale.ROOT, "%.1f", lastHitPct),
                lastHistPct < 0 ? "n/a" : String.format(Locale.ROOT, "%.1f", lastHistPct),
                lastLeafPct < 0 ? "n/a" : String.format(Locale.ROOT, "%.1f", lastLeafPct),
                passMsInWindow / passFramesInWindow,
                RtAdaptiveQuality.spp(userSpp), RtAdaptiveQuality.bounces(userSpp, userBounces),
                // work_ms: the rays and the denoiser, i.e. what the governor compares with the budget; pass_ms: the whole RT
                // command buffer (chunk build, atmosphere, the copy), i.e. what the frame actually pays for ray tracing
                msOrNa(gpuTimer == null ? -1.0 : gpuTimer.recentMs(SPAN_RAYS, SPAN_DENOISE)),
                msOrNa(gpuTimer == null ? -1.0 : gpuTimer.recentMs(SPAN_WORLD, SPAN_ATMO)),
                budget <= 0.0 ? "off" : String.format(Locale.ROOT, "%.1f", budget),
                RtAdaptiveQuality.level(), RtAdaptiveQuality.levels(userSpp, userBounces),
                RtConfig.displayed(), RtConfig.effectiveModeName()));
        } else if (s.instances() == 0 && s.pending() > 0) {
            LOGGER.info("[VKB-RT] rt_pass waiting instances=0 pending={}", s.pending());
        }
        if (world != null) {
            OpacityMicromapCache omm = world.ommCache();
            if (omm != null) {
                omm.logStatsIfDue(now);
            }
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

    private static boolean drainingHostSubmissions;
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
        displayedStamp = null; displayedTarget = pendingDisplayedTarget = null; displayedProjection = null;
        RtPass pl = pass;
        RtWorld w = world;
        if (w != null) {
            teardownPending = true;
            try {
                drainHostSubmissions(w.memory().device(), "shutdown_drain");
            } catch (RuntimeException | LinkageError e) {
                // Keep strong references for an explicit later shutdown. A
                // failed flush/idle is not permission to free pending resources.
                RtGeometryGate.markFailed("shutdown_drain", String.valueOf(e.getMessage()));
                LOGGER.error("[VKB-RT] shutdown drain refused; retaining renderer resources reason={}", reason, e);
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
                if (pathSlangRuntime != null) {
                    pathSlangLease.close();
                    pathSlangLease = null;
                    pathSlangRuntime = null;
                }
                teardownPending = false;
            } catch (RuntimeException | LinkageError e) {
                teardownPending = true;
                LOGGER.error("[VKB-RT] runtime cleanup failed before world publication", e);
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
            if (denoiser != null) {
                denoiser.destroy();
                denoiser = null;
            }
            if (dlssRrDenoiser != null) {
                dlssRrDenoiser.destroy();
                dlssRrDenoiser = null;
            }
            // Before the lease below: the atmosphere's passes hold recordings on this same core, and releasing the
            // last lease closes the core, which refuses while a recording is still pinned.
            atmosphere.destroy();
            if (pathSlangRuntime != null) {
                w.closeComputeAfterHostDrain();
                pathSlangLease.close();
                pathSlangLease = null;
                pathSlangRuntime = null;
            }
            pathSlangUnavailable = false;
            pathSlangUnavailableLogged = false;
            pathSlangUnavailableReason = "Slang path runtime is not initialized";
            if (gpuTimer != null) {
                gpuTimer.destroy();
                gpuTimer = null;
            }
            if (denoiser != null) {
                denoiser.destroy();
                denoiser = null;
            }
            // [DLSS-RR]
            if (dlssRrDenoiser != null) {
                dlssRrDenoiser.destroy();
                dlssRrDenoiser = null;
            }
            if (materials != null) {
                materials.destroy();
                materials = null;
            }
            if (emitters != null) {
                emitters.destroy();
                emitters = null;
            }
            if (output != null) {
                w.memory().destroy(output);
                output = null;
            }
            if (outputDepth != null) {
                w.memory().destroy(outputDepth);
                outputDepth = null;
            }
            if (compositeOutput != null) {
                w.memory().destroy(compositeOutput);
                compositeOutput = null;
            }
            if (statsBuf != null) {
                w.memory().destroy(statsBuf);
                statsBuf = null;
            }
            if (skyTextures != null) {
                skyTextures.destroy();
                skyTextures = null;
            }
            if (entityMotionBuf != null) {
                w.memory().destroy(entityMotionBuf);
                entityMotionBuf = null;
            }
            if (reservoirBuf != null) {
                w.memory().destroy(reservoirBuf);
                reservoirBuf = null;
            }
            w.destroy();
            world = null;
            LOGGER.info("[VKB-RT] world_destroyed reason={}", reason);
        } catch (RuntimeException | LinkageError e) {
            LOGGER.error("[VKB-RT] FAILED stage=destroy result={} reason={}", e.getMessage(), reason, e);
            RtGeometryGate.markFailed("destroy", String.valueOf(e.getMessage()));
            return;
        }
        teardownPending = false;
        lastStats = null;
        lastPassLog = "";
        rebuildPassNext = false;
        dropDlssRrDenoiserNext = false;
        lastPassMode = null;
        dropDenoiserNext = false;
        reservoirPixels = -1L;
        reservoirLightFingerprint = Long.MIN_VALUE;
        reservoirResetRequested = true;
        outputReady = false;
        statsReadbacks = new RecordingReadbackSlots<>(STATS_SLOTS);
        lastStatsSequence = -1;
        lastTransparency = "[RT-TRANSPARENCY] counters=WAITING";

    }
}
