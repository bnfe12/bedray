package io.github.andrexarlay.vulkancompat.mixin.debug;

import com.mojang.renderpearl.backend.vulkan.VulkanQueryPool;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Raw {@code VkQueryPool} handle of an engine timestamp pool, so {@code PerfMonitor} can read results into a preallocated
 * buffer (the engine's {@code getValues} allocates an {@code OptionalLong[]} per call). Signature: {@code protected long
 * vkQueryPool()} of {@code com.mojang.renderpearl.backend.vulkan.VulkanQueryPool} (javap -p libs/minecraft-26.3-client.jar).
 */
@Mixin(value = VulkanQueryPool.class, remap = false)
public interface VulkanQueryPoolAccessor {
    @Invoker(value = "vkQueryPool", remap = false)
    long vkb$vkQueryPool();
}
