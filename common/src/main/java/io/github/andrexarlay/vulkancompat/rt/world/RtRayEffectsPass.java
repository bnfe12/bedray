package io.github.andrexarlay.vulkancompat.rt.world;

import io.github.andrexarlay.vulkancompat.rtx.config.RtSettings;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.VkBarriers;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDevice;

/**
 * Ray Tracing mode: primary rays through the TLAS of Sodium sections plus separately switchable ray traced effects, shader
 * {@code rt_rays.comp}. Settings (group "Ray Tracing" of the settings page, RtSettings.rays*), each with its own quality
 * (rays per pixel) and distance: shadows (hard/soft), reflections, ambient occlusion, emissive lighting (NEE over the light
 * list), one-bounce GI; plus temporal accumulation of the stochastic effects while nothing moves. With {@code gbuffer} the
 * shader also writes the G-buffer of the NRD denoiser.
 */
public final class RtRayEffectsPass implements RtPass {
    public static final String ACCUM_DEFINE = "#define VKB_ACCUM 1" + (char) 10;

    private final RtPipeline pipeline;
    private final RtAccumImage accum = new RtAccumImage("rt_rays_accum");

    /** Spatial a-trous filter of short histories (rt_atrous.comp); {@code -Dvkrtx.rt.spatialFilter=off} disables it for A/B runs. */
    private static final boolean SPATIAL_FILTER = !"off".equalsIgnoreCase(System.getProperty("vkrtx.rt.spatialFilter", "on"));
    private static final int FILTER_ITERATIONS = 3;
    private final RtPipeline filter;
    /** ReSTIR DI spatial reuse (rt_restir_spatial.comp); {@code -Dvkrtx.rt.restirSpatial=off} disables it for A/B runs. */
    private static final boolean RESTIR_SPATIAL = !"off".equalsIgnoreCase(System.getProperty("vkrtx.rt.restirSpatial", "on"));
    private final RtPipeline restirSpatial;

    public RtRayEffectsPass(VkDevice device, boolean gbuffer) {
        this.pipeline = new RtPipeline(device, RtPipeline.SHADER_PATH, "rt_rays.comp", ACCUM_DEFINE, gbuffer, true);
        // the NRD variant does its own filtering and never accumulates
        this.filter = SPATIAL_FILTER && !gbuffer
            ? new RtPipeline(device, "/shaders/glsl/rt_atrous.comp", "rt_atrous.comp", ACCUM_DEFINE, false, true) : null;
        // Spatial reuse merges reservoirs, not pixels, and rt_rays.comp keeps its temporal merge under a denoiser too, so the
        // pass is built for both variants - it only has to share the binding layout of the pass whose records it rewrites.
        this.restirSpatial = RESTIR_SPATIAL
            ? new RtPipeline(device, "/shaders/glsl/rt_restir_spatial.comp", "rt_restir_spatial.comp", ACCUM_DEFINE, gbuffer, true) : null;
    }

    @Override
    public RtMode mode() {
        return RtMode.RAY_TRACED;
    }

    @Override
    public String describe(RtFrameParams p) {
        StringBuilder sb = new StringBuilder("primary");
        if (p.mode != RtConfig.MODE_LIT && p.mode != RtConfig.MODE_REFLECTION) {
            sb.append(",debug_").append(RtConfig.effectiveModeName());
        }
        if ((p.flags & RtFrameParams.FLAG_SHADOWS) != 0) {
            sb.append((p.flags & RtFrameParams.FLAG_SOFT_SHADOWS) != 0 ? ",soft_shadow" + RtSettings.shadowQuality() : ",shadow");
        }
        if (p.aoSamples > 0) {
            sb.append(",ao").append(p.aoSamples).append("r").append((int) p.aoRadius);
        }
        if ((p.flags & RtFrameParams.FLAG_EMISSIVE) != 0) {
            sb.append((p.flags & RtFrameParams.FLAG_MATERIALS) != 0
                ? ",emissive" + RtSettings.emissiveQuality() + "d" + (int) p.emissiveDistance : ",emissive_no_materials");
            if (restirSpatial != null) {
                sb.append(",restir_spatial");
            }
        }
        if ((p.flags & RtFrameParams.FLAG_REFLECTIONS) != 0) {
            sb.append(",reflections").append(RtSettings.reflectionQuality()).append("d").append(p.rayDistance);
        }
        if ((p.flags & RtFrameParams.FLAG_GI) != 0) {
            sb.append(",gi").append(RtSettings.giQuality()).append("d").append((int) p.giDistance);
        }
        if ((p.flags & RtFrameParams.FLAG_ACCUMULATE) != 0) {
            sb.append(filter != null ? ",accumulate,spatial_filter" : ",accumulate");
        }
        if ((p.flags & RtFrameParams.FLAG_NORMAL_MAPS) != 0) {
            sb.append(",normal_maps");
        }
        if ((p.flags & RtFrameParams.FLAG_AUTO_NORMALS) != 0) {
            sb.append(",auto_normals").append(RtSettings.autoNormalStrengthPercent());
        }
        if ((p.flags & RtFrameParams.FLAG_WATER_REALISTIC) != 0) {
            sb.append(",water_realistic");
        } else {
            sb.append(",water_vanilla");
        }
        if (pipeline.isGbuffer()) {
            sb.append(",nrd_gbuffer");
        }
        return sb.toString();
    }

    @Override
    public void prepare(VkCommandBuffer cmd, RtMemory memory, int width, int height) {
        accum.prepare(cmd, memory, width, height);
    }

    /** Nibbles of params2 in the ray mode: shadow, emissive, GI, reflection rays per pixel (1..15). */
    static int packQualities(int shadow, int emissive, int gi, int reflection) {
        return (clampNibble(shadow)) | (clampNibble(emissive) << 4) | (clampNibble(gi) << 8) | (clampNibble(reflection) << 12);
    }

    private static int clampNibble(int v) {
        return Math.max(1, Math.min(15, v));
    }

    @Override
    public void configure(RtFrameParams params, boolean denoising) {
        params.mode = RtConfig.effectiveMode();
        params.rayQualities = packQualities(RtSettings.shadowQuality(), RtSettings.emissiveQuality(), RtSettings.giQuality(),
            RtSettings.reflectionQuality());
        params.rayDistance = RtSettings.reflectionDistance();
        params.giDistance = RtSettings.giDistance();
        params.emissiveDistance = RtSettings.emissiveDistance();
        RtMode currentMode = RtConfig.mode();
        // 0 = AO off. Secondary shading (rt_common_shade.glsl) gates its contact AO on this, so a non-zero legacy radius here
        // silently charged every reflection two visibility rays even with AO disabled; rt_rays.comp substitutes the legacy 2.5
        // for the single denoiser-guide ray when it reads 0.
        params.aoRadius = (currentMode == RtMode.AO || RtSettings.ao()) ? RtSettings.aoRadius() : 0f;
        params.bounces = 0;
        params.spp = 1;
        int ao = Math.max(RtConfig.effectiveAoSamples(), (currentMode == RtMode.AO || RtSettings.ao()) ? RtSettings.aoQuality() : 0);
        // The denoiser needs one noisy AO ray per pixel (without AO samples the lit view has nothing to denoise).
        params.aoSamples = denoising ? Math.max(1, ao) : ao;
        int f = params.flags;
        if (currentMode == RtMode.AO) {
            f |= RtFrameParams.FLAG_AO;
        } else if (currentMode == RtMode.DIRECT) {
            if (RtSettings.shadows()) {
                f |= RtFrameParams.FLAG_SHADOWS;
                if (RtSettings.softShadows()) {
                    f |= RtFrameParams.FLAG_SOFT_SHADOWS;
                }
            }
            if (RtSettings.emissive()) {
                f |= RtFrameParams.FLAG_EMISSIVE;
            }
        } else {
            if (RtSettings.shadows()) {
                f |= RtFrameParams.FLAG_SHADOWS;
                if (RtSettings.softShadows()) {
                    f |= RtFrameParams.FLAG_SOFT_SHADOWS;
                }
            }
            if (RtSettings.reflections()) {
                f |= RtFrameParams.FLAG_REFLECTIONS;
            }
            if (RtSettings.ao()) {
                f |= RtFrameParams.FLAG_AO;
            }
            if (RtSettings.emissive()) {
                f |= RtFrameParams.FLAG_EMISSIVE;
            }
            if (RtSettings.gi()) {
                f |= RtFrameParams.FLAG_GI;
            }
        }
        // NRD does its own temporal filtering of the G-buffer; our accumulation only for the plain image
        boolean accumulate = RtSettings.raysAccumulation() && !denoising;
        if (accumulate) {
            f |= RtFrameParams.FLAG_ACCUMULATE;
        }
        if (RtSettings.dlssRrGeometricNormals()) {
            f |= RtFrameParams.FLAG_GEOMETRIC_NORMALS;
        }
        if (RtSettings.waterStyle() == 1) {
            f |= RtFrameParams.FLAG_WATER_REALISTIC;
        }
        if (RtSettings.vibrantVisuals()) {
            f |= RtFrameParams.FLAG_VIBRANT_VISUALS;
        }
        params.flags = f;
        // Mixed as primitives: Objects.hash would box all nine values into an Object[] every frame, and the only consumer
        // (RtAccumulation.next) just tests it against last frame's int.
        int settingsHash = params.mode;
        settingsHash = 31 * settingsHash + params.rayQualities;
        settingsHash = 31 * settingsHash + params.rayDistance;
        settingsHash = 31 * settingsHash + Float.floatToRawIntBits(params.giDistance);
        settingsHash = 31 * settingsHash + Float.floatToRawIntBits(params.emissiveDistance);
        settingsHash = 31 * settingsHash + Float.floatToRawIntBits(params.aoRadius);
        settingsHash = 31 * settingsHash + params.aoSamples;
        settingsHash = 31 * settingsHash + f;
        settingsHash = 31 * settingsHash + (params.tonemap ? 1 : 0);
        params.accumCount = accum.history.next(params.invViewProjStable, params.cameraX, params.cameraY, params.cameraZ, params.key.dir,
            params.sceneChanged, settingsHash, accum.width(), accum.height(), accumulate);
        params.historyCap = accum.history.cap();
        if (accumulate) {
            accum.logIfDue("rays_accum", params.accumCount);
        }
    }

    @Override
    public void bind(RtPipeline.Views c) {
        RtPipeline.Views v = new RtPipeline.Views(c.tlas(), c.table(), c.output(), c.atlas(), c.material(), c.normal(), accum.view(),
            c.gbuffer(), c.depth(), c.lightmap());
        pipeline.bind(v);
        if (restirSpatial != null) {
            restirSpatial.bind(v);
        }
        if (filter != null) {
            filter.bind(v);
        }
    }

    @Override
    public void dispatch(VkCommandBuffer cmd, RtFrameParams params, int width, int height) {
        pipeline.dispatch(cmd, params, width, height);
        if (restirSpatial != null && RtSettings.restirSpatial() && params.mode == RtConfig.MODE_LIT
            && (params.flags & RtFrameParams.FLAG_EMISSIVE) != 0
            && (params.flags & RtFrameParams.FLAG_MATERIALS) != 0) {
            // Two checker phases (params2 bits 16-19, the nibble the a-trous filter borrows for its own iterations):
            // phase 0 merges the even cells reading odd ones, phase 1 the other way round over phase 0's results. The
            // shader skips pixels itself when the reservoir buffer is off.
            VkBarriers.computeToCompute(cmd);   // the ray pass wrote the reservoirs and receiver positions this one reads
            params.filterIter = 0;
            restirSpatial.dispatch(cmd, params, width, height);
            VkBarriers.computeToCompute(cmd);   // phase 1 reads phase 0's merged records
            params.filterIter = 1;
            restirSpatial.dispatch(cmd, params, width, height);
            VkBarriers.fullMemoryBarrier(cmd);   // trailing: guards consumers that are not this mod's compute dispatches
            params.filterIter = 0;
        }
        if (filter != null && (params.flags & RtFrameParams.FLAG_ACCUMULATE) != 0 && params.mode == RtConfig.MODE_LIT) {
            params.filterIters = FILTER_ITERATIONS;
            for (int i = 0; i < FILTER_ITERATIONS; i++) {
                VkBarriers.computeToCompute(cmd);   // the previous dispatch wrote the accumulation image this one reads
                params.filterIter = i;
                filter.dispatch(cmd, params, width, height);
            }
            VkBarriers.fullMemoryBarrier(cmd);   // trailing: guards the engine's non-compute consumers
            params.filterIter = 0;
            params.filterIters = 0;
        }
    }

    @Override
    public boolean usesGbuffer() {
        return pipeline.isGbuffer();
    }

    @Override
    public void resetHistory() {
        accum.history.reset();
    }

    @Override
    public void destroy() {
        if (restirSpatial != null) {
            restirSpatial.destroy();
        }
        if (filter != null) {
            filter.destroy();
        }
        pipeline.destroy(); // waits for the device to go idle
        accum.destroy();
    }
}
