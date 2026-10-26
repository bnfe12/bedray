package io.github.andrexarlay.vulkancompat.rt.world;

import io.github.andrexarlay.vulkancompat.rtx.config.RtSettings;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.VkBarriers;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDevice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

/**
 * Path Tracing mode: shader {@code rt_path.comp} (full light transport: {@code bounces} vertices, NEE of the sun, emissive
 * surfaces lit by being hit, specular/diffuse lobes, early probabilistic path termination) with motion-reprojected temporal accumulation
 * into an RGBA32F image ({@link RtAccumulation}) and a spatial A-Trous pass for short histories when no G-buffer denoiser is active.
 * Settings (group "Path Tracing", RtSettings.path*): bounces,
 * samples per pixel, accumulation, auto exposure, max ray distance, denoiser (NRD when the game was started with
 * {@code -Dvkrtx.denoiser=nrd}: the pass then also writes the NRD G-buffer and the NRD compose pass produces the image).
 */
public final class RtPathTracerPass implements RtPass {
    private static final Logger LOGGER = LoggerFactory.getLogger("VKB-RT");
    public static final String ACCUM_DEFINE = "#define VKB_ACCUM 1" + (char) 10;

    private final RtPipeline pipeline;
    private final RtPipeline filter;
    private final RtAccumImage accum = new RtAccumImage("rt_path_accum");
    private final RtAccumulation accumulation = accum.history;
    private long lastLogNs;
    private int lastLoggedResetCount = -1;
    private int lastBounces = -1;
    private int lastSpp = -1;
    private int lastRayDistance = -1;
    private RtSettings.PathDenoiser lastDenoiser = null;
    private RtSettings.PathPreset lastPathPreset = null;
    private RtSettings.Preset lastPreset = null;
    private boolean lastAutoExposure = true;
    private boolean forceReset = false;
    private final org.joml.Matrix4f lastInvViewProj = new org.joml.Matrix4f();
    private double lastCamX = Double.NaN;
    private double lastCamY;
    private double lastCamZ;

    /** {@code gbuffer}: also write the NRD G-buffer (albedo-demodulated per-frame signals, see rt_path.comp). */
    public RtPathTracerPass(VkDevice device, boolean gbuffer) {
        this.pipeline = new RtPipeline(device, RtPipeline.PATH_SHADER_PATH, "rt_path.comp", ACCUM_DEFINE, gbuffer, true);
        this.filter = !gbuffer ? new RtPipeline(device, "/shaders/rt_atrous.comp", "rt_atrous.comp", ACCUM_DEFINE, false, true) : null;
    }

    @Override
    public RtMode mode() {
        return RtMode.PATH_TRACED;
    }

    @Override
    public void resetHistory() {
        accumulation.reset();
        forceReset = true;
    }

    @Override
    public String describe(RtFrameParams p) {
        return "path(bounces=" + p.bounces + ",spp=" + p.spp + ",nee=sun,emissive=hit,rr=from3"
            + ((p.flags & RtFrameParams.FLAG_ACCUMULATE) != 0 ? (filter != null ? ",accumulate,spatial_filter" : ",accumulate") : "")
            + ((p.flags & RtFrameParams.FLAG_NORMAL_MAPS) != 0 ? ",normal_maps" : "")
            + ((p.flags & RtFrameParams.FLAG_AUTO_NORMALS) != 0 ? ",auto_normals" + RtSettings.autoNormalStrengthPercent() : "")
            + ((p.flags & RtFrameParams.FLAG_GEOMETRIC_NORMALS) != 0 ? ",geom_normals" : "")
            + ((p.flags & RtFrameParams.FLAG_WATER_REALISTIC) != 0 ? ",water_realistic" : ",water_vanilla")
            + (p.mode != RtConfig.MODE_LIT ? ",debug_" + RtConfig.effectiveModeName() : "")
            + ",dist=" + p.rayDistance + ",denoiser=" + (pipeline.isGbuffer() ? (RtFrameRenderer.dlssRrActive() ? "dlss_rr" : "nrd") : "off") + ")";
    }

    @Override
    public void prepare(VkCommandBuffer cmd, RtMemory mem, int width, int height) {
        accum.prepare(cmd, mem, width, height);
    }

    @Override
    public void configure(RtFrameParams params, boolean denoising) {
        params.mode = RtConfig.effectiveMode();
        params.bounces = RtAdaptiveQuality.bounces(RtSettings.spp(), RtSettings.bounces());
        params.spp = RtAdaptiveQuality.spp(RtSettings.spp());
        params.rayDistance = RtSettings.pathMaxDistance();
        params.quality = 1;
        params.rayQualities = -1;
        params.aoSamples = 0;
        boolean accumulate = RtSettings.accumulate() && !denoising;
        int f = params.flags | RtFrameParams.FLAG_EMISSIVE;
        if (accumulate) {
            f |= RtFrameParams.FLAG_ACCUMULATE;
        }
        if (RtSettings.autoExposure()) {
            f |= RtFrameParams.FLAG_AUTO_EXPOSURE;
        }
        if (RtSettings.dlssRrGeometricNormals()) {
            f |= RtFrameParams.FLAG_GEOMETRIC_NORMALS;
        }
        if (RtSettings.waterStyle() == 1) {
            f |= RtFrameParams.FLAG_WATER_REALISTIC;
        }
        params.flags = f;

        RtSettings.PathDenoiser denoiserSetting = RtSettings.denoiser();
        RtSettings.PathPreset pathPreset = RtSettings.pathPreset;
        RtSettings.Preset preset = RtSettings.preset;
        boolean autoExposure = RtSettings.autoExposure();

        boolean cameraMoved = !Double.isNaN(lastCamX) && (Math.abs(params.cameraX - lastCamX) > RtAccumulation.POS_EPS
            || Math.abs(params.cameraY - lastCamY) > RtAccumulation.POS_EPS
            || Math.abs(params.cameraZ - lastCamZ) > RtAccumulation.POS_EPS
            || !params.invViewProjStable.equals(lastInvViewProj, RtAccumulation.MATRIX_EPS));

        boolean settingsChanged = forceReset
            || (lastBounces != -1 && (params.bounces != lastBounces || params.spp != lastSpp
                || params.rayDistance != lastRayDistance || denoiserSetting != lastDenoiser
                || pathPreset != lastPathPreset || preset != lastPreset || autoExposure != lastAutoExposure));

        if (settingsChanged) {
            accumulation.reset();
            forceReset = false;
            LOGGER.info("[VKB-RT] path_accum reset: bounces({}->{}) spp({}->{}) dist({}->{}) denoiser({}->{}) pt_preset({}->{}) preset({}->{}) autoExp({}->{})",
                lastBounces, params.bounces, lastSpp, params.spp, lastRayDistance, params.rayDistance,
                lastDenoiser, denoiserSetting, lastPathPreset, pathPreset, lastPreset, preset, lastAutoExposure, autoExposure);
        }

        lastCamX = params.cameraX;
        lastCamY = params.cameraY;
        lastCamZ = params.cameraZ;
        lastInvViewProj.set(params.invViewProjStable);

        lastBounces = params.bounces;
        lastSpp = params.spp;
        lastRayDistance = params.rayDistance;
        lastDenoiser = denoiserSetting;
        lastPathPreset = pathPreset;
        lastPreset = preset;
        lastAutoExposure = autoExposure;

        int settingsHash = Objects.hash(params.mode, params.bounces, params.spp, params.rayDistance, f, params.tonemap,
            denoiserSetting, pathPreset, preset, autoExposure, denoising);
        params.accumCount = accumulation.next(params.invViewProjStable, params.cameraX, params.cameraY, params.cameraZ,
            params.key.dir, params.sceneChanged, settingsHash, accum.width(), accum.height(), accumulate);
        params.historyCap = accumulation.cap();
        long now = System.nanoTime();
        if (now - lastLogNs > 2_000_000_000L) {
            lastLogNs = now;
            if (params.accumCount != lastLoggedResetCount) {
                LOGGER.info("[VKB-RT] path_accum frames={} last_reset={} {} resets_2s={}", params.accumCount + 1,
                    accumulation.lastResetReason(), accumulation.lastDetail(), accumulation.drainResetCounts());
                lastLoggedResetCount = params.accumCount;
            }
        }
    }

    /** Frames averaged into the image of the last dispatch (1 = a single noisy frame). */
    public int accumulatedFrames(RtFrameParams params) {
        return params.accumCount + 1;
    }

    @Override
    public void bind(RtPipeline.Views c) {
        RtPipeline.Views views = new RtPipeline.Views(c.tlas(), c.table(), c.output(), c.atlas(), c.material(), c.normal(), accum.view(),
            c.gbuffer(), c.depth(), c.lightmap());
        pipeline.bind(views);
        if (filter != null) {
            filter.bind(views);
        }
    }

    @Override
    public boolean usesGbuffer() {
        return pipeline.isGbuffer();
    }

    @Override
    public void dispatch(VkCommandBuffer cmd, RtFrameParams params, int width, int height) {
        pipeline.dispatch(cmd, params, width, height);
        if (filter != null && (params.flags & RtFrameParams.FLAG_ACCUMULATE) != 0 && params.mode == RtConfig.MODE_LIT) {
            params.filterIters = 3;
            for (int i = 0; i < params.filterIters; i++) {
                VkBarriers.computeToCompute(cmd);
                params.filterIter = i;
                filter.dispatch(cmd, params, width, height);
            }
            VkBarriers.fullMemoryBarrier(cmd);   // trailing: guards the engine's non-compute consumers
            params.filterIter = 0;
            params.filterIters = 0;
        }
    }

    @Override
    public void destroy() {
        if (filter != null) {
            filter.destroy();
        }
        pipeline.destroy(); // waits for the device to go idle
        accum.destroy();
    }
}
