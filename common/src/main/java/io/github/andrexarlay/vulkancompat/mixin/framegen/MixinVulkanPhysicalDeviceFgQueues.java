package io.github.andrexarlay.vulkancompat.mixin.framegen;

import io.github.andrexarlay.vulkancompat.rtx.framegen.FrameGenProvider;
import io.github.andrexarlay.vulkancompat.rtx.framegen.fsr3.Fsr3Queues;
import it.unimi.dsi.fastutil.ints.Int2IntMap;
import it.unimi.dsi.fastutil.ints.IntIntPair;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * FSR 3 frame generation needs two more distinct queues (present, image acquire) than the engine creates. The engine feeds
 * {@code VulkanPhysicalDevice.queueFamilyCreateInfoMap()} (family -> queue count) into {@code VkDeviceQueueCreateInfo}
 * ({@code VulkanBackend.createDevice} l.556-566, decompile in scratch/decomp/out). Fields verified with javap on libs/minecraft-26.3-client.jar:
 * {@code private final Int2IntMap queueFamilyCreateInfoMap}, {@code private final IntIntPair graphicsQueueFamilyAndIndex},
 * constructor {@code VulkanPhysicalDevice(VkPhysicalDevice)}. Only active when FSR 3 is the frame generation provider.
 */
@Mixin(targets = "com.mojang.renderpearl.backend.vulkan.VulkanPhysicalDevice", remap = false)
public abstract class MixinVulkanPhysicalDeviceFgQueues {
    @Shadow
    @Final
    @Mutable
    private Int2IntMap queueFamilyCreateInfoMap;
    @Shadow
    @Final
    private IntIntPair graphicsQueueFamilyAndIndex;

    @Inject(method = "<init>(Lorg/lwjgl/vulkan/VkPhysicalDevice;)V", at = @At("TAIL"))
    private void vkb$reserveFgQueues(VkPhysicalDevice pd, CallbackInfo ci) {
        if (!FrameGenProvider.amd() || this.graphicsQueueFamilyAndIndex == null) {
            return;
        }
        this.queueFamilyCreateInfoMap = Fsr3Queues.reserve(pd, this.queueFamilyCreateInfoMap, this.graphicsQueueFamilyAndIndex.firstInt());
    }
}
