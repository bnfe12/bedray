package io.github.andrexarlay.vulkancompat.rt.world;

import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import io.github.andrexarlay.vulkancompat.rt.slang.SlangRuntime;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDevice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Temporary Minecraft-side adapter for bloom and sun glare after the main pass.
 * The new Slang volumetric passes are not connected yet; requested god rays are
 * reported unavailable rather than selecting a runtime-compiled GLSL pass.
 *
 * <p>Order of a frame: main pass (rt_rays / rt_path) -> denoiser (NRD / DLSS-RR G-buffer path) -> this -> copy to the engine target ->
 * the temporal upscaler / frame generation of the engine. The passes see the denoised image at render resolution; they write only
 * the final image, never a G-buffer or a motion vector buffer, so DLSS Ray Reconstruction, NRD, FSR, XeSS and frame generation get
 * the same guide data as without them.
 */
final class RtAtmosphere {
    private static final Logger LOGGER = LoggerFactory.getLogger("VKB-RT");
    private static final int SPAN_BLOOM = 1;

    private RtBloomPass bloom;
    /** Native Slang runtime, created with the first bloom pass; {@code null} until then and when unavailable. */
    private SlangRuntime slangRuntime;
    private boolean slangUnavailableLogged;
    private boolean volumetricUnavailableLogged;
    private RtGpuTimer timer;
    private String lastLogged = "";
    /**
     * The settings folded into one primitive. describe() is thirteen boxed arguments through String.format, and the frame
     * that changes nothing still had to pay for it to discover that nothing changed.
     */
    private long lastLoggedToken = Long.MIN_VALUE;
    private final Vector4f tmp = new Vector4f();

    /**
     * @param views    views of the main pass of this frame (output = final image, depth = ray distance of the primary hit)
     * @param viewProj camera-relative view-projection of this frame (the same one the temporal reprojection uses)
     * @param bobX     camera origin offset of the frame (bob), camera-relative
     */
    void record(VkCommandBuffer cmd, VulkanDevice vulkanDevice, RtMemory mem, RtFrameParams p, Matrix4f viewProj,
                RtPipeline.Views views, int w, int h, float bobX, float bobY, float bobZ) {
        VkDevice device = vulkanDevice.vkDevice();
        if (p.mode != RtConfig.MODE_LIT || views.depth() == 0L) {
            return; // debug views show the raw buffer
        }
        RtAtmosphereSettings.GodRays gr = RtAtmosphereSettings.godRays();
        boolean shaftsRequested = gr == RtAtmosphereSettings.GodRays.POST && p.volumetricFogEnabled && p.fogDensity > 0f
            && RtAtmosphereSettings.godRayStrength() > 0f;
        if (shaftsRequested && !volumetricUnavailableLogged) {
            LOGGER.warn("[VKB-ATMO] Slang volumetric passes are not connected; god rays are unavailable");
            volumetricUnavailableLogged = true;
        } else if (!shaftsRequested) {
            volumetricUnavailableLogged = false;
        }
        boolean shafts = false;
        boolean glow = RtAtmosphereSettings.bloom();
        boolean glare = RtAtmosphereSettings.sunGlare();
        logIfChanged(shafts);
        if (!shafts && !glow && !glare) {
            return;
        }
        if (RtGpuTimer.ENABLED && timer == null) {
            try {
                timer = new RtGpuTimer(device, "VKB-ATMO", "godrays", "bloom");
            } catch (RuntimeException e) {
                LOGGER.warn("[VKB-ATMO] gpu timers unavailable: {}", e.getMessage());
            }
        }
        if (timer != null) {
            timer.beginFrame(cmd);
        }
        io.github.andrexarlay.vulkancompat.rtx.upscaler.VkBarriers.fullMemoryBarrier(cmd);   // the denoiser / main pass wrote the image
        if (glow || glare) {
            if (bloom == null) {
                bloom = new RtBloomPass(slangRuntime(vulkanDevice));
            }
            float[] dir = p.key.dir;
            boolean keyOn = Math.max(p.key.color[0], Math.max(p.key.color[1], p.key.color[2])) > 1.0e-3f;
            // bodyAlpha is the alpha vanilla draws the sun sprite with: while a storm hides the body the halo must be off too.
            float glareK = glare && keyOn && !p.key.isMoon
                ? RtSky.glareStrength(RtAtmosphereSettings.sunGlareStrength(), p.key.day, dir[1] / 0.2f, p.key.bodyAlpha)
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
            RtBloomPass.Params bp = new RtBloomPass.Params(p.invViewProj, dir, glareK,
                bobX, bobY, bobZ, p.flipY, p.tonemap, threshold, 0.35f * threshold, RtAtmosphereSettings.bloomStrength(),
                RtAtmosphereSettings.bloomScatter(), ndcX, ndcY);
            if (timer != null) {
                timer.stamp(cmd, SPAN_BLOOM, false);
            }
            bloom.record(cmd, w, h, views.output(), views.depth(), bp, glow);
            if (timer != null) {
                timer.stamp(cmd, SPAN_BLOOM, true);
            }
        }
    }

    private void logIfChanged(boolean shafts) {
        long token = settingsToken(shafts);
        if (token == lastLoggedToken) {
            return;
        }
        lastLoggedToken = token;
        String now = RtAtmosphereSettings.describe() + " shafts_pass=" + shafts;
        if (!now.equals(lastLogged)) {
            lastLogged = now;
            LOGGER.info("[VKB-ATMO] {}", now);
        }
    }

    /**
     * Every value RtAtmosphereSettings.describe() prints, hashed into one long (FNV-1a). A collision would cost one missed
     * debug line; it cannot cost correctness, because the log is the only consumer.
     */
    private static long settingsToken(boolean shafts) {
        long h = 0xcbf29ce484222325L;
        h = 0x100000001b3L * h + (shafts ? 1 : 0);
        h = 0x100000001b3L * h + RtAtmosphereSettings.godRays().ordinal();
        h = 0x100000001b3L * h + RtAtmosphereSettings.godRaySteps();
        h = 0x100000001b3L * h + Float.floatToRawIntBits(RtAtmosphereSettings.godRayStrength());
        h = 0x100000001b3L * h + RtAtmosphereSettings.godRayRange();
        h = 0x100000001b3L * h + (RtAtmosphereSettings.bloom() ? 1 : 0);
        h = 0x100000001b3L * h + Float.floatToRawIntBits(RtAtmosphereSettings.bloomStrength());
        h = 0x100000001b3L * h + Float.floatToRawIntBits(RtAtmosphereSettings.bloomThreshold());
        h = 0x100000001b3L * h + Float.floatToRawIntBits(RtAtmosphereSettings.bloomScatter());
        h = 0x100000001b3L * h + (RtAtmosphereSettings.sunGlare() ? 1 : 0);
        h = 0x100000001b3L * h + Float.floatToRawIntBits(RtAtmosphereSettings.sunGlareStrength());
        h = 0x100000001b3L * h + Float.floatToRawIntBits(RtAtmosphereSettings.fogVanilla());
        h = 0x100000001b3L * h + Float.floatToRawIntBits(RtAtmosphereSettings.fogAerial());
        return 0x100000001b3L * h + Float.floatToRawIntBits(RtAtmosphereSettings.chunkFadeSeconds());
    }

    /**
     * Creates the native runtime on first use. A missing DLL or AOT bundle is
     * reported; the new renderer does not compile a legacy GLSL replacement.
     */
    private SlangRuntime slangRuntime(VulkanDevice device) {
        if (slangRuntime != null || slangUnavailableLogged) {
            return slangRuntime;
        }
        slangUnavailableLogged = true;
        boolean compiled;
        try {
            compiled = SlangRuntime.hasCompiledPasses();
        } catch (RuntimeException e) {
            LOGGER.warn("[VKB-ATMO] Slang pass index is unreadable; bloom is unavailable: {}", e.getMessage());
            return null;
        }
        if (!compiled) {
            LOGGER.info("[VKB-ATMO] no AOT Slang passes in the jar; bloom is unavailable");
            return null;
        }
        try {
            // Bloom needs no optional device feature: compute, combined image samplers and storage images are core.
            slangRuntime = SlangRuntime.create(device, 0);
        } catch (RuntimeException e) {
            LOGGER.warn("[VKB-ATMO] native Slang runtime unavailable; bloom is unavailable: {}", e.getMessage());
        }
        return slangRuntime;
    }

    /** The command buffer is about to be re-recorded: its previous native recording completed two submits ago. */
    void releaseRecording(long commandBuffer) {
        if (bloom != null) {
            bloom.releaseRecording(commandBuffer);
        }
    }

    void resetHistory() {
        // No volumetric history until the Slang passes are integrated.
    }

    void destroy() {
        if (bloom != null) {
            bloom.destroy(); // releases the remaining recordings before the runtime shuts down
            bloom = null;
        }
        if (slangRuntime != null) {
            slangRuntime.close();
            slangRuntime = null;
        }
        if (timer != null) {
            timer.destroy();
            timer = null;
        }
    }
}
