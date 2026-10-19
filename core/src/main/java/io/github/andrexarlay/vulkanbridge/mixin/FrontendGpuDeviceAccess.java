package io.github.andrexarlay.vulkanbridge.mixin;

import com.mojang.renderpearl.backend.api.GpuDeviceBackend;
import com.mojang.renderpearl.frontend.FrontendGpuDevice;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Accessor for the private final {@code GpuDeviceBackend backend} of {@link FrontendGpuDevice} (the engine's
 * VulkanDevice sits behind it). Field signature: javap -p on libs/minecraft-26.3-client.jar,
 * {@code private final com.mojang.renderpearl.backend.api.GpuDeviceBackend backend}.
 */
@Mixin(value = FrontendGpuDevice.class, remap = false)
public interface FrontendGpuDeviceAccess {
    @Accessor("backend")
    GpuDeviceBackend vkb$backend();
}
