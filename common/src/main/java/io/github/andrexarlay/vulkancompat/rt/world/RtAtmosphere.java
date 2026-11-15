package io.github.andrexarlay.vulkancompat.rt.world;

import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import io.github.andrexarlay.vulkancompat.rt.slang.SlangRuntime;
import io.github.andrexarlay.vulkancompat.rt.slang.SlangRuntimePool;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDevice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Minecraft settings/host-view adapter for native volumetric, bloom and sun glare passes.
 * All passes consume the same camera-relative frame contract, including view bob.
 *
 * <p>Order of a frame: main pass (rt_rays / rt_path) -> denoiser (NRD / DLSS-RR G-buffer path) -> this -> copy to the engine target ->
 * the temporal upscaler / frame generation of the engine. The passes see the denoised image at render resolution; they write only
 * the final image, never a G-buffer or a motion vector buffer, so DLSS Ray Reconstruction, NRD, FSR, XeSS and frame generation get
 * the same guide data as without them.
 */
final class RtAtmosphere {
    private static final Logger LOGGER = LoggerFactory.getLogger("VKB-RT");
    private static final int SPAN_BLOOM = 1;
    // The outer edge of the smoothstep in bloomSunVisibility.
    private static final float SUN_GLARE_NDC_LIMIT = 1.25f;

    private RtBloomPass bloom;
    private RtSlangVolumetricPass volumetric;
    /** The device's one native core, borrowed through a pool lease; {@code null} while it is unavailable. */
    private SlangRuntime slangRuntime;
    private SlangRuntimePool.Lease slangLease;
    private boolean slangUnavailableLogged;
    /** Set once the volumetric passes refuse to register, so the frame loop stops asking. */
    private boolean shaftsUnavailable;
    private boolean bloomUnavailableLogged;
    private RtGpuTimer timer;
    private String lastLogged = "";
    /**
     * The settings folded into one primitive so unchanged frames can skip describe() and its formatting work.
     */
    private long lastLoggedToken = Long.MIN_VALUE;
    private final Vector4f tmp = new Vector4f();
    private boolean bloomSourceLogged;
    private boolean bloomSourceWasHdr;

    record SunGlare(float strength, float ndcX, float ndcY) {
        private static final SunGlare OFF = new SunGlare(0f, 0f, 0f);

        boolean active() {
            return strength > 0f;
        }
    }

    /** Resolve the camera-dependent part once, before the renderer decides whether to export HDR. */
    SunGlare projectSunGlare(RtFrameParams p, Matrix4f viewProj) {
        if (p.mode != RtConfig.MODE_LIT || !RtAtmosphereSettings.sunGlare() || p.key.isMoon) {
            return SunGlare.OFF;
        }
        float[] color = p.key.color;
        if (Math.max(color[0], Math.max(color[1], color[2])) <= 1.0e-3f) {
            return SunGlare.OFF;
        }
        float[] dir = p.key.dir;
        // bodyAlpha tracks the sun sprite fading out in storms.
        float strength = RtSky.glareStrength(RtAtmosphereSettings.sunGlareStrength(), p.key.day, dir[1] / 0.2f, p.key.bodyAlpha);
        if (!(strength > 0f)) {
            return SunGlare.OFF;
        }
        viewProj.transform(tmp.set(dir[0] * 1000f, dir[1] * 1000f, dir[2] * 1000f, 1f));
        if (!glareOnScreen(tmp)) {
            return SunGlare.OFF;
        }
        float ndcY = tmp.y / tmp.w;
        return new SunGlare(strength, tmp.x / tmp.w, p.flipY ? -ndcY : ndcY);
    }

    static boolean glareOnScreen(Vector4f clip) {
        return clip.isFinite() && clip.w > 1.0e-3f
            && Math.max(Math.abs(clip.x / clip.w), Math.abs(clip.y / clip.w)) < SUN_GLARE_NDC_LIMIT;
    }

    private void logBloomSource(boolean hdr) {
        if (!bloomSourceLogged || hdr != bloomSourceWasHdr) {
            LOGGER.info("[VKB-ATMO] bloom source={}", hdr ? "hdr_resolve" : "tonemapped_ldr");
            bloomSourceLogged = true;
            bloomSourceWasHdr = hdr;
        }
    }

    /**
     * @param views    views of the main pass of this frame (output = final image, depth = ray distance of the primary hit)
     * @param hdrView  matching scene-linear frame, or 0 to use the presented image
     * @param bobX     camera origin offset of the frame (bob), camera-relative
     */
    void record(VkCommandBuffer cmd, VulkanDevice vulkanDevice, RtFrameParams p, SunGlare sunGlare, long hdrView,
                RtFrameBindings.Views views, int w, int h, float bobX, float bobY, float bobZ) {
        VkDevice device = vulkanDevice.vkDevice();
        if (p.mode != RtConfig.MODE_LIT || views.depth() == 0L) {
            return; // debug views show the raw buffer
        }
        RtAtmosphereSettings.GodRays gr = RtAtmosphereSettings.godRays();
        boolean shafts = gr == RtAtmosphereSettings.GodRays.POST && p.volumetricFogEnabled && p.fogDensity > 0f
            && RtAtmosphereSettings.godRayStrength() > 0f;
        boolean glow = RtAtmosphereSettings.bloom();
        boolean glare = sunGlare.active();
        logIfChanged(shafts);
        if (!shafts && !glow && !glare) {
            return;
        }
        SlangRuntime runtime = slangRuntime(vulkanDevice);
        if (runtime == null) {
            return;
        }
        if ((glow || glare) && !runtime.library().hasHdrExport()) {
            if (!bloomUnavailableLogged) {
                bloomUnavailableLogged = true;
                LOGGER.warn("[VKB-ATMO] event=feature_unavailable feature=hdr_bloom action=skip_bloom_and_glare reason=matching_native_core_required");
            }
            if (!shafts) {
                return;
            }
            glow = false;
            glare = false;
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
        if (shafts && !shaftsUnavailable && volumetric == null) {
            try {
                volumetric = new RtSlangVolumetricPass(runtime);
            } catch (RuntimeException e) {
                shaftsUnavailable = true;
                LOGGER.warn("[VKB-ATMO] light shafts unavailable: {}", e.getMessage());
            }
        }
        if (shafts && volumetric != null) {
            if (timer != null) timer.stamp(cmd, 0, false);
            volumetric.record(cmd, views, p, w, h);
            if (timer != null) timer.stamp(cmd, 0, true);
        }
        if (glow || glare) {
            if (bloom == null) {
                bloom = new RtBloomPass(runtime);
            }
            float threshold = RtAtmosphereSettings.bloomThreshold();
            RtBloomPass.Params bp = new RtBloomPass.Params(p.invViewProj, p.key.dir, sunGlare.strength(),
                bobX, bobY, bobZ, p.flipY, p.tonemap, threshold, 0.35f * threshold, RtAtmosphereSettings.bloomStrength(),
                RtAtmosphereSettings.bloomScatter(), sunGlare.ndcX(), sunGlare.ndcY(), hdrView, p.exposure);
            logBloomSource(bp.hdrSource());
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
        h = 0x100000001b3L * h + (RtAtmosphereSettings.bloomSky() ? 1 : 0);
        h = 0x100000001b3L * h + RtAtmosphereSettings.bloomShape().ordinal();
        h = 0x100000001b3L * h + RtAtmosphereSettings.sunGlareSizePercent();
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
     * Borrows the device's only native core through {@link SlangRuntimePool}. Creating a second {@code BedrayCore} here
     * used to be the shape of a crash the JVM cannot catch: the atmosphere's own core was created, reported ABI 2
     * ready, and then faulted inside the driver on its very first Vulkan call (nvoglv64.dll, read at 0x4) while
     * registering {@code volumetric_march}. One core per device also means one set of descriptor pools and pipeline
     * caches; the lease is what keeps the core alive while the atmosphere still holds recordings, and the pool closes
     * the core once the last holder releases it.
     */
    private SlangRuntime slangRuntime(VulkanDevice device) {
        if (slangRuntime != null && slangRuntime.isClosed()) {
            slangLease = null; // a world reload gave the device a new core
            slangRuntime = null;
        }
        if (slangRuntime != null || slangUnavailableLogged) {
            return slangRuntime;
        }
        try {
            if (!SlangRuntime.hasCompiledPasses()) {
                slangUnavailableLogged = true;
                LOGGER.info("[VKB-ATMO] no AOT Slang passes in the jar; light shafts and bloom are unavailable");
                return null;
            }
            // Bloom is feature-free; volumetric visibility needs the host's actually enabled RQ/BDA/Int64 features.
            slangLease = SlangRuntimePool.acquire(device);
            slangRuntime = slangLease.runtime();
        } catch (RuntimeException | Error e) {
            slangUnavailableLogged = true;
            LOGGER.warn("[VKB-ATMO] native Slang runtime unavailable; light shafts and bloom are unavailable: {}",
                e.getMessage());
        }
        return slangRuntime;
    }

    /** The exact host ticket proved completion and excluded resubmission. */
    void releaseRecording(long commandBuffer) {
        if (volumetric != null) volumetric.releaseRecording(commandBuffer);
        if (bloom != null) {
            bloom.releaseRecording(commandBuffer);
        }
    }

    void discardRecording(long commandBuffer) {
        resetHistory();
        if (volumetric != null) volumetric.discardRecording(commandBuffer);
        if (bloom != null) bloom.discardRecording(commandBuffer);
    }

    void resetHistory() {
        if (volumetric != null) volumetric.resetHistory();
    }

    void destroy() {
        if (volumetric != null) { volumetric.close(); volumetric = null; }
        if (bloom != null) {
            bloom.destroy(); // releases the remaining recordings before the runtime shuts down
            bloom = null;
        }
        // Last, after both passes let go of their recordings: releasing the lease is what can close the core.
        if (slangLease != null) {
            slangLease.close();
            slangLease = null;
        }
        slangRuntime = null;
        slangUnavailableLogged = false;
        bloomUnavailableLogged = false;
        if (timer != null) {
            timer.destroy();
            timer = null;
        }
    }
}
