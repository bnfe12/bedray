package io.github.andrexarlay.vulkanbridge.glbridge;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.backend.api.CommandEncoderBackend;
import com.mojang.renderpearl.backend.api.GpuDeviceBackend;
import com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import com.mojang.renderpearl.backend.vulkan.VulkanQueue;
import com.mojang.renderpearl.frontend.FrontendCommandEncoder;
import io.github.andrexarlay.vulkanbridge.mixin.FrontendGpuDeviceAccess;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkInstance;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkQueue;

/**
 * Handles of the engine's Vulkan objects. Every accessor was found with javap -p on libs/minecraft-26.3-client.jar
 * (see docs/experiments/gl-interop-ingame.md, section "Signatures"):
 * {@code RenderSystem.getDevice()}, {@code FrontendGpuDevice.backend} (accessor mixin), {@code VulkanDevice.vkDevice()},
 * {@code VulkanDevice.graphicsQueue()}, {@code VulkanQueue.vkQueue()/queueFamilyIndex()},
 * {@code FrontendCommandEncoder.backend()}, {@code VulkanDevice.createCommandEncoder()}.
 */
record EngineVk(VulkanDevice device, VkDevice vkDevice, VkPhysicalDevice physicalDevice, VkInstance instance,
                VulkanQueue graphicsQueue, VkQueue queue, int queueFamily, VulkanCommandEncoder encoder) {

    /** Must be called on the render thread once the engine device exists. */
    static EngineVk acquire() {
        GpuDevice gpu = RenderSystem.getDevice();
        if (!(gpu instanceof FrontendGpuDeviceAccess access)) {
            throw new IllegalStateException("RenderSystem device " + gpu.getClass().getName()
                + " is not a FrontendGpuDevice (FrontendGpuDeviceAccess mixin not applied)");
        }
        GpuDeviceBackend backend = access.vkb$backend();
        if (!(backend instanceof VulkanDevice vd)) {
            throw new IllegalStateException("engine backend is " + backend.getClass().getName() + ", not Vulkan");
        }
        CommandEncoder frontEnc = gpu.createCommandEncoder();
        if (!(frontEnc instanceof FrontendCommandEncoder fe)) {
            throw new IllegalStateException("command encoder is " + frontEnc.getClass().getName());
        }
        CommandEncoderBackend be = fe.backend();
        if (!(be instanceof VulkanCommandEncoder vce)) {
            throw new IllegalStateException("encoder backend is " + be.getClass().getName());
        }
        VkDevice dev = vd.vkDevice();
        VulkanQueue q = vd.graphicsQueue();
        return new EngineVk(vd, dev, dev.getPhysicalDevice(), dev.getPhysicalDevice().getInstance(), q, q.vkQueue(),
            q.queueFamilyIndex(), vce);
    }
}
