package io.github.andrexarlay.vulkancompat.mixin.framegen;

import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import io.github.andrexarlay.vulkancompat.rtx.framegen.fsr3.Fsr3FrameGen;
import org.lwjgl.vulkan.VkAllocationCallbacks;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkPresentInfoKHR;
import org.lwjgl.vulkan.VkQueue;
import org.lwjgl.vulkan.VkSwapchainCreateInfoKHR;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.nio.IntBuffer;
import java.nio.LongBuffer;

/**
 * Routes the swapchain calls of {@code VulkanGpuSurface} through the FSR 3 frame interpolation swapchain while that provider is active
 * (the redirect targets fall back to the plain KHRSwapchain calls otherwise, see {@link Fsr3FrameGen}). Call sites verified with
 * {@code javap -c} on libs/minecraft-26.3-client.jar: {@code destroySwapchain()} -> vkDestroySwapchainKHR(VkDevice,J,VkAllocationCallbacks)V;
 * {@code configure(Configuration)} -> vkCreateSwapchainKHR(VkDevice,VkSwapchainCreateInfoKHR,VkAllocationCallbacks,LongBuffer)I and two
 * vkGetSwapchainImagesKHR(VkDevice,J,IntBuffer,LongBuffer)I; {@code acquireNextTexture()} -> vkAcquireNextImageKHR(VkDevice,JJJJIntBuffer)I;
 * {@code present()} -> vkQueuePresentKHR(VkQueue,VkPresentInfoKHR)I.
 */
@Mixin(targets = "com.mojang.renderpearl.backend.vulkan.VulkanGpuSurface", remap = false)
public abstract class MixinVulkanGpuSurfaceFsr3 {
    @Shadow
    @Final
    private VulkanDevice device;

    @Redirect(method = "destroySwapchain", at = @At(value = "INVOKE",
        target = "Lorg/lwjgl/vulkan/KHRSwapchain;vkDestroySwapchainKHR(Lorg/lwjgl/vulkan/VkDevice;JLorg/lwjgl/vulkan/VkAllocationCallbacks;)V"))
    private void vkb$destroy(VkDevice d, long swapchain, VkAllocationCallbacks alloc) {
        Fsr3FrameGen.destroySwapchain(d, swapchain);
    }

    @Redirect(method = "configure", at = @At(value = "INVOKE",
        target = "Lorg/lwjgl/vulkan/KHRSwapchain;vkCreateSwapchainKHR(Lorg/lwjgl/vulkan/VkDevice;Lorg/lwjgl/vulkan/VkSwapchainCreateInfoKHR;Lorg/lwjgl/vulkan/VkAllocationCallbacks;Ljava/nio/LongBuffer;)I"))
    private int vkb$create(VkDevice d, VkSwapchainCreateInfoKHR ci, VkAllocationCallbacks alloc, LongBuffer out) {
        return Fsr3FrameGen.createSwapchain(this.device, d, ci, out);
    }

    @Redirect(method = "configure", at = @At(value = "INVOKE",
        target = "Lorg/lwjgl/vulkan/KHRSwapchain;vkGetSwapchainImagesKHR(Lorg/lwjgl/vulkan/VkDevice;JLjava/nio/IntBuffer;Ljava/nio/LongBuffer;)I"))
    private int vkb$images(VkDevice d, long swapchain, IntBuffer count, LongBuffer images) {
        return Fsr3FrameGen.getSwapchainImages(d, swapchain, count, images);
    }

    @Redirect(method = "acquireNextTexture", at = @At(value = "INVOKE",
        target = "Lorg/lwjgl/vulkan/KHRSwapchain;vkAcquireNextImageKHR(Lorg/lwjgl/vulkan/VkDevice;JJJJLjava/nio/IntBuffer;)I"))
    private int vkb$acquire(VkDevice d, long swapchain, long timeout, long semaphore, long fence, IntBuffer index) {
        return Fsr3FrameGen.acquireNextImage(d, swapchain, timeout, semaphore, fence, index);
    }

    @Redirect(method = "present", at = @At(value = "INVOKE",
        target = "Lorg/lwjgl/vulkan/KHRSwapchain;vkQueuePresentKHR(Lorg/lwjgl/vulkan/VkQueue;Lorg/lwjgl/vulkan/VkPresentInfoKHR;)I"))
    private int vkb$present(VkQueue queue, VkPresentInfoKHR info) {
        return Fsr3FrameGen.queuePresent(queue, info);
    }
}
