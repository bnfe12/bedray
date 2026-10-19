package io.github.andrexarlay.vulkancompat.mixin.rtx;

import com.mojang.renderpearl.backend.vulkan.VulkanGpuSampler;
import io.github.andrexarlay.vulkancompat.rtx.config.TextureLodBias;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Applies negative Mipmap LOD Bias and optional point sampling sharpness to Vulkan texture samplers
 * created by RenderPearl (used by Sodium and Minecraft).
 * By default RenderPearl sets {@code createInfo.mipLodBias(0.0F)}. When an upscaler (DLSS, FSR, XeSS, FSR1, NIS, Bilinear)
 * renders at reduced resolution, the GPU selects blurrier mip levels unless negative LOD bias (log2(render/display) + offset)
 * is applied to pull high-frequency detail back into the sampler.
 * Point sampling sharpness forces NEAREST magnification filter for crisp 16x16 voxel textures.
 */
@Mixin(value = VulkanGpuSampler.class, remap = false)
public abstract class MixinVulkanGpuSampler {

    @ModifyArg(
        method = "<init>",
        at = @At(
            value = "INVOKE",
            target = "Lorg/lwjgl/vulkan/VkSamplerCreateInfo;mipLodBias(F)Lorg/lwjgl/vulkan/VkSamplerCreateInfo;"
        ),
        index = 0
    )
    private float vkb$applyNegativeLodBias(float originalBias) {
        float bias = TextureLodBias.getEffectiveLodBias();
        if (TextureLodBias.autoTextureLodBias && TextureLodBias.getActiveRenderScale() < 0.999) {
            bias = TextureLodBias.clamp(bias - 0.35f);
        }
        return bias;
    }

    @ModifyArg(
        method = "<init>",
        at = @At(
            value = "INVOKE",
            target = "Lorg/lwjgl/vulkan/VkSamplerCreateInfo;magFilter(I)Lorg/lwjgl/vulkan/VkSamplerCreateInfo;"
        ),
        index = 0
    )
    private int vkb$applyPointSamplingMagFilter(int originalMagFilter) {
        return TextureLodBias.getMagFilter(originalMagFilter);
    }
}
