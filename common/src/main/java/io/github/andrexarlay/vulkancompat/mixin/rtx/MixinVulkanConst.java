package io.github.andrexarlay.vulkancompat.mixin.rtx;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.backend.vulkan.VulkanConst;
import io.github.andrexarlay.vulkancompat.rtx.WorldTargetManager;
import org.lwjgl.vulkan.VK10;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Adds {@link VK10#VK_IMAGE_USAGE_STORAGE_BIT} only to the few textures that {@link WorldTargetManager} is creating
 * right now for a compute pass: the world colour target (always, independent of the Hardware RT toggle), the XeSS output image (RGBA8) and the
 * motion vector image (RG16F). The format is matched against {@link WorldTargetManager#isStorageUsageRequestedFor(GpuFormat)},
 * which is true only inside those constructor calls. Every other texture of the game keeps the engine's own usage
 * flags (no DCC/compression penalty).
 * <p>
 * RGBA8_UNORM storage-image support is mandatory in the Vulkan spec. RG16F as a storage image needs the
 * shaderStorageImageExtendedFormats device feature, which the "VKB XeSS" FeatureSet enables.
 * <p>
 * Signature checked with javap: {@code VulkanConst.textureUsageToVk(int, GpuFormat)I}, called only from
 * {@code VulkanGpuTexture} at image creation (decomp {@code local reference}).
 */
@Mixin(value = VulkanConst.class, remap = false)
public abstract class MixinVulkanConst {

    @Inject(method = "textureUsageToVk", at = @At("RETURN"), cancellable = true)
    private static void vkb$addStorageUsageFlag(int usage, GpuFormat format, CallbackInfoReturnable<Integer> cir) {
        if (WorldTargetManager.isStorageUsageRequestedFor(format)) {
            cir.setReturnValue(cir.getReturnValue() | VK10.VK_IMAGE_USAGE_STORAGE_BIT);
        }
    }
}
