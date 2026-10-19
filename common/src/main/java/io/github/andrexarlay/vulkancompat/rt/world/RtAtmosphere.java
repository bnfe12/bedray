package io.github.andrexarlay.vulkancompat.rt.world;

import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDevice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Owner of the atmosphere post passes of {@link RtFrameRenderer}: light shafts ({@link RtVolumetricPass}), then bloom and sun glare
 * ({@link RtBloomPass}), both on the final image after the denoiser, in this order (so the shafts bloom). Everything is switched by
 * {@link RtAtmosphereSettings}; a disabled effect costs nothing and its objects are only created when first needed.
 *
 * <p>Order of a frame: main pass (rt_rays / rt_path) -> denoiser (NRD / DLSS-RR G-buffer path) -> this -> copy to the engine target ->
 * the temporal upscaler / frame generation of the engine. The passes see the denoised image at render resolution; they write only
 * the final image, never a G-buffer or a motion vector buffer, so DLSS Ray Reconstruction, NRD, FSR, XeSS and frame generation get
 * the same guide data as without them.
 */
final class RtAtmosphere {
    private static final Logger LOGGER = LoggerFactory.getLogger("VKB-RT");
    private static final int SPAN_GODRAYS = 0;
    private static final int SPAN_BLOOM = 1;

    private RtVolumetricPass volumetric;
    private RtBloomPass bloom;
    private RtGpuTimer timer;
    private String lastLogged = "";
    private final Vector4f tmp = new Vector4f();

    /**
     * @param views    views of the main pass of this frame (output = final image, depth = ray distance of the primary hit)
     * @param viewProj camera-relative view-projection of this frame (the same one the temporal reprojection uses)
     * @param bobX     camera origin offset of the frame (bob), camera-relative
     */
    void record(VkCommandBuffer cmd, VkDevice device, RtMemory mem, RtFrameParams p, Matrix4f viewProj, RtPipeline.Views views,
                int w, int h, float bobX, float bobY, float bobZ) {
        if (p.mode != RtConfig.MODE_LIT || views.depth() == 0L) {
            return; // debug views show the raw buffer
        }
        RtAtmosphereSettings.GodRays gr = RtAtmosphereSettings.godRays();
        boolean shafts = gr == RtAtmosphereSettings.GodRays.POST && p.volumetricFogEnabled && p.fogDensity > 0f
            && RtAtmosphereSettings.godRayStrength() > 0f;
        boolean glow = RtAtmosphereSettings.bloom();
        boolean glare = RtAtmosphereSettings.sunGlare();
        logIfChanged(shafts);
        if (!shafts && !glow && !glare) {
            return;
        }
        if (RtGpuTimer.ENABLED && timer == null) {
            timer = new RtGpuTimer(device, "godrays", "bloom");
        }
        if (timer != null) {
            timer.beginFrame(cmd);
        }
        io.github.andrexarlay.vulkancompat.rtx.upscaler.VkBarriers.fullMemoryBarrier(cmd);   // the denoiser / main pass wrote the image
        if (shafts) {
            if (volumetric == null) {
                volumetric = new RtVolumetricPass(device);
            }
            if (timer != null) {
                timer.stamp(cmd, SPAN_GODRAYS, false);
            }
            volumetric.record(cmd, mem, p, views, w, h, RtAtmosphereSettings.godRaySteps(), RtAtmosphereSettings.godRayStrength(),
                RtAtmosphereSettings.godRayRange());
            if (timer != null) {
                timer.stamp(cmd, SPAN_GODRAYS, true);
            }
        }
        if (glow || glare) {
            if (bloom == null) {
                bloom = new RtBloomPass(device);
            }
            float[] dir = p.key.dir;
            boolean keyOn = Math.max(p.key.color[0], Math.max(p.key.color[1], p.key.color[2])) > 1.0e-3f;
            float elevation = Math.max(0f, Math.min(1f, dir[1] / 0.2f));
            float glareK = glare && keyOn && !p.key.isMoon
                ? RtAtmosphereSettings.sunGlareStrength() * Math.max(0f, Math.min(1f, p.key.day)) * elevation * elevation * (3f - 2f * elevation)
                : 0f;
            // NDC of the sun (the shader maps NDC straight to pixels: apply the RT's flip of the y axis here)
            float ndcX = 9f;
            float ndcY = 9f;
            if (glareK > 0f) {
                viewProj.transform(tmp.set(dir[0] * 1000f, dir[1] * 1000f, dir[2] * 1000f, 1f));
                if (tmp.w > 1.0e-3f) {
                    ndcX = tmp.x / tmp.w;
                    ndcY = p.flipY ? -tmp.y / tmp.w : tmp.y / tmp.w;
                }
            }
            float threshold = RtAtmosphereSettings.bloomThreshold();
            RtBloomPass.Params bp = new RtBloomPass.Params(p.invViewProj, dir, glareK, RtAtmosphereSettings.sunGlareStar() ? 1f : 0f,
                bobX, bobY, bobZ, p.flipY, p.tonemap, threshold, 0.35f * threshold, RtAtmosphereSettings.bloomStrength(),
                RtAtmosphereSettings.bloomScatter(), ndcX, ndcY);
            if (timer != null) {
                timer.stamp(cmd, SPAN_BLOOM, false);
            }
            bloom.record(cmd, mem, w, h, views.output(), views.depth(), bp, glow);
            if (timer != null) {
                timer.stamp(cmd, SPAN_BLOOM, true);
            }
        }
    }

    private void logIfChanged(boolean shafts) {
        String now = RtAtmosphereSettings.describe() + " shafts_pass=" + shafts;
        if (!now.equals(lastLogged)) {
            lastLogged = now;
            LOGGER.info("[VKB-ATMO] {}", now);
        }
    }

    void resetHistory() {
        if (volumetric != null) {
            volumetric.resetHistory();
        }
    }

    void destroy() {
        if (volumetric != null) {
            volumetric.destroy();
            volumetric = null;
        }
        if (bloom != null) {
            bloom.destroy();
            bloom = null;
        }
        if (timer != null) {
            timer.destroy();
            timer = null;
        }
    }
}
