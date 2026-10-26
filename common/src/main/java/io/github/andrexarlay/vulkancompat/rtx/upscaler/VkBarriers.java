package io.github.andrexarlay.vulkancompat.rtx.upscaler;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRAccelerationStructure;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkImageMemoryBarrier;
import org.lwjgl.vulkan.VkMemoryBarrier;

/**
 * Barrier helpers for transient command buffers that are spliced into the engine frame with
 * {@code VulkanCommandEncoder.execute(cb)}. Between command buffers of one submission Vulkan gives no implicit
 * ordering, so every such buffer starts and ends with a full memory barrier (same scope the engine itself uses in
 * {@code VulkanCommandEncoder.memoryBarrier}: ALL_COMMANDS, MEMORY_READ|MEMORY_WRITE).
 *
 * <p>{@link #computeToCompute} and {@link #computeToAsBuild} are the stage-exact forms for dependencies between two
 * compute dispatches of this mod and from a dispatch into an acceleration structure build. They use the core
 * {@code vkCmdPipelineBarrier} on purpose, not synchronization2: the command buffers are the engine's, and whether the
 * engine's device enabled VK_KHR_synchronization2 is not ours to assume (a barrier2 call without the feature is
 * undefined and cost a GPU device loss). The stage-exactness - the actual optimization - survives: the masks name
 * COMPUTE_SHADER instead of ALL_COMMANDS. {@link #fullMemoryBarrier} stays for everything else on purpose: sites that
 * hand off to native libraries (DLSS / NRD / FSR / XeSS) run stages this mod cannot name, and sites around
 * {@code vkCmdFillBuffer} or acceleration-structure builds of other subsystems are transfer / AS-build stages -
 * narrowing those would be a correctness bug, not an optimization.
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
     * Compute dispatch -> compute dispatch: the previous dispatch's storage writes become visible to the next
     * dispatch's storage reads/writes and sampled reads. Exact stages (COMPUTE_SHADER WRITE -> COMPUTE_SHADER
     * READ|WRITE) instead of ALL_COMMANDS -> ALL_COMMANDS: both endpoints are compute, so a full-scope barrier only
     * stalls unrelated transfer / graphics / AS work. Core {@code vkCmdPipelineBarrier} (SHADER_READ covers sampled,
     * uniform and storage reads in one bit).
     */
    public static void computeToCompute(VkCommandBuffer cmd) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkMemoryBarrier.Buffer b = VkMemoryBarrier.calloc(1, stack);
            b.get(0).sType$Default()
                .srcAccessMask(VK10.VK_ACCESS_SHADER_WRITE_BIT)
                .dstAccessMask(VK10.VK_ACCESS_SHADER_READ_BIT | VK10.VK_ACCESS_SHADER_WRITE_BIT);
            VK10.vkCmdPipelineBarrier(cmd, VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                0, b, null, null);
        }
    }

    /**
     * Compute dispatch -> acceleration structure build: the dispatch wrote the geometry the build reads. Exact stages
     * (COMPUTE_SHADER WRITE -> ACCELERATION_STRUCTURE_BUILD READ/WRITE), core {@code vkCmdPipelineBarrier} (the
     * build-stage and access constants are numerically identical between core and synchronization2).
     */
    public static void computeToAsBuild(VkCommandBuffer cmd) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkMemoryBarrier.Buffer b = VkMemoryBarrier.calloc(1, stack);
            b.get(0).sType$Default()
                .srcAccessMask(VK10.VK_ACCESS_SHADER_WRITE_BIT)
                .dstAccessMask(KHRAccelerationStructure.VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR
                    | KHRAccelerationStructure.VK_ACCESS_ACCELERATION_STRUCTURE_WRITE_BIT_KHR);
            VK10.vkCmdPipelineBarrier(cmd, VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                KHRAccelerationStructure.VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR,
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
