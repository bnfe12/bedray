package io.github.andrexarlay.vulkancompat.rtx.config;

import com.mojang.blaze3d.systems.RenderSystem;
import io.github.andrexarlay.vulkanbridge.platform.LogPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Keeps the texture sampler mip LOD bias in step with the active render scale.
 * <p>
 * Root cause fixed here: {@code SamplerCache.initialize()} creates every sampler once at startup (when no upscaler is active,
 * bias 0) and {@code MixinVulkanGpuSampler} only reads {@link TextureLodBias#getEffectiveLodBias()} at that moment, so the
 * negative bias never followed an upscaler / mode / scale change (DLSS Programming Guide 3.5: "the mip level should be
 * updated each time the render resolution changes", section 3.x dynamic resolution). When the effective bias changes by more
 * than {@link #EPS} this re-runs {@code initialize()} at the head of the frame (no render pass open); the old samplers are
 * intentionally not destroyed (other holders may still reference them; they are a few tiny objects per mode switch).
 * Render thread only.
 */
public final class SamplerBiasSync {
    private static final Logger LOGGER = LoggerFactory.getLogger("Bedray/SamplerBiasSync");
    private static final float EPS = 0.02f;
    private static float applied = 0.0f;
    private static boolean appliedPointSharpness = false;

    private SamplerBiasSync() {}

    public static float appliedBias() {
        return applied;
    }

    public static void onFrameBegin() {
        if (RenderSystem.tryGetDevice() == null) {
            return;
        }
        float want = TextureLodBias.getEffectiveLodBias();
        boolean wantPoint = TextureLodBias.isPointSamplingSharpness();
        if (Math.abs(want - applied) <= EPS && wantPoint == appliedPointSharpness) {
            return;
        }
        try {
            RenderSystem.getSamplerCache().initialize();
            var mc = net.minecraft.client.Minecraft.getInstance();
            if (mc != null && mc.gameRenderer != null && mc.gameRenderer.gameRenderState() != null
                && mc.gameRenderer.gameRenderState().levelRenderState != null) {
                mc.gameRenderer.gameRenderState().levelRenderState.shouldResetChunkLayerSampler = true;
            }
            LOGGER.info("[VKB-AUTOTEST] lod_bias applied={} previous={} pointSharp={} scale={}",
                want, applied, wantPoint, TextureLodBias.getActiveRenderScale());
            applied = want;
            appliedPointSharpness = wantPoint;
        } catch (RuntimeException e) {
            // keep `applied` so the next frame retries; the failure is logged, not swallowed
            LOGGER.error("[VKB-AUTOTEST] lod_bias=FAILED want={} message={}", want, LogPaths.show(e));
        }
    }
}
