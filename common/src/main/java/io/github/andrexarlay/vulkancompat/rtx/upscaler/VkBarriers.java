package io.github.andrexarlay.vulkancompat.rtx.upscaler;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkImageMemoryBarrier;
import org.lwjgl.vulkan.VkMemoryBarrier;

/**
 * Barrier helpers for transient command buffers that are spliced into the engine frame with
 * {@code VulkanCommandEncoder.execute(cb)}. Between command buffers of one submission Vulkan gives no implicit
 * ordering, so every such buffer starts and ends with a full memory barrier (same scope the engine itself uses in
 * {@code VulkanCommandEncoder.memoryBarrier}: ALL_COMMANDS, MEMORY_READ|MEMORY_WRITE).
 */
public final class VkBarriers {
    private VkBarriers() {}

    /** ALL_COMMANDS -> ALL_COMMANDS, MEMORY_WRITE -> MEMORY_READ|MEMORY_WRITE. */
    public static void fullMemoryBarrier(VkCommandBuffer cmd) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkMemoryBarrier.Buffer b = VkMemoryBarrier.calloc(1, stack);
            b.get(0).sType$Default()
                .srcAccessMask(VK10.VK_ACCESS_MEMORY_WRITE_BIT | VK10.VK_ACCESS_MEMORY_READ_BIT)
                .dstAccessMask(VK10.VK_ACCESS_MEMORY_WRITE_BIT | VK10.VK_ACCESS_MEMORY_READ_BIT);
            VK10.vkCmdPipelineBarrier(cmd, VK10.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK10.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                0, b, null, null);
        }
    }

    /**
     * Layout transition of a whole single-mip, single-layer image with an ALL_COMMANDS -> ALL_COMMANDS dependency.
     * The engine keeps all of its textures in GENERAL, so callers always transition back to GENERAL afterwards.
     */
    public static void transition(VkCommandBuffer cmd, long image, int aspectMask, int oldLayout, int newLayout) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkImageMemoryBarrier.Buffer b = VkImageMemoryBarrier.calloc(1, stack);
            b.get(0).sType$Default()
                .oldLayout(oldLayout)
                .newLayout(newLayout)
                .srcAccessMask(VK10.VK_ACCESS_MEMORY_WRITE_BIT | VK10.VK_ACCESS_MEMORY_READ_BIT)
                .dstAccessMask(VK10.VK_ACCESS_MEMORY_WRITE_BIT | VK10.VK_ACCESS_MEMORY_READ_BIT)
                .srcQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                .dstQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                .image(image);
            b.get(0).subresourceRange()
                .aspectMask(aspectMask)
                .baseMipLevel(0)
                .levelCount(1)
                .baseArrayLayer(0)
                .layerCount(1);
            VK10.vkCmdPipelineBarrier(cmd, VK10.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK10.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                0, null, null, b);
        }
    }
}
