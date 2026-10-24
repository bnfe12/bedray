package io.github.andrexarlay.vulkancompat.shaderhost.iris;

import io.github.andrexarlay.vulkancompat.rt.world.RtFailure;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkImageCreateInfo;
import org.lwjgl.vulkan.VkImageMemoryBarrier;
import org.lwjgl.vulkan.VkImageViewCreateInfo;
import org.lwjgl.vulkan.VkMemoryAllocateInfo;
import org.lwjgl.vulkan.VkMemoryRequirements;
import org.lwjgl.vulkan.VkPhysicalDeviceMemoryProperties;

import java.nio.LongBuffer;

/**
 * One owned 2D color image of the post graph with an optional mip chain: {@code baseView} covers mip 0 (attachment and
 * plain sampling), {@code fullView} all levels (sampling by a program that enables mipmaps). Device-local, created
 * UNDEFINED; the executor moves it to GENERAL once and keeps it there like every engine texture.
 */
final class IrisImage {
    final long image, memory, baseView, fullView;
    final int width, height, format, levels;
    final long bytes;

    private IrisImage(long image, long memory, long baseView, long fullView, int width, int height, int format, int levels, long bytes) {
        this.image = image;
        this.memory = memory;
        this.baseView = baseView;
        this.fullView = fullView;
        this.width = width;
        this.height = height;
        this.format = format;
        this.levels = levels;
        this.bytes = bytes;
    }

    /** Number of levels of a full mip chain for the extent. */
    static int fullChain(int width, int height) {
        return 32 - Integer.numberOfLeadingZeros(Math.max(width, height));
    }

    static IrisImage create(VkDevice device, String what, int width, int height, int format, int levels, int usage) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer p = stack.mallocLong(1);
            VkImageCreateInfo info = VkImageCreateInfo.calloc(stack).sType$Default()
                .imageType(VK10.VK_IMAGE_TYPE_2D).format(format).mipLevels(levels).arrayLayers(1)
                .samples(VK10.VK_SAMPLE_COUNT_1_BIT).tiling(VK10.VK_IMAGE_TILING_OPTIMAL).usage(usage)
                .sharingMode(VK10.VK_SHARING_MODE_EXCLUSIVE).initialLayout(VK10.VK_IMAGE_LAYOUT_UNDEFINED);
            info.extent().set(width, height, 1);
            RtFailure.check(VK10.vkCreateImage(device, info, null, p), "create_image:" + what);
            long image = p.get(0);
            long memory = 0, base = 0, full = 0;
            try {
                VkMemoryRequirements reqs = VkMemoryRequirements.calloc(stack);
                VK10.vkGetImageMemoryRequirements(device, image, reqs);
                VkPhysicalDeviceMemoryProperties props = VkPhysicalDeviceMemoryProperties.calloc(stack);
                VK10.vkGetPhysicalDeviceMemoryProperties(device.getPhysicalDevice(), props);
                int type = -1;
                for (int i = 0; i < props.memoryTypeCount(); i++) {
                    if ((reqs.memoryTypeBits() & (1 << i)) != 0
                        && (props.memoryTypes(i).propertyFlags() & VK10.VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT) != 0) { type = i; break; }
                }
                if (type < 0) throw new RtFailure("create_image:" + what, "no device-local memory type");
                RtFailure.check(VK10.vkAllocateMemory(device, VkMemoryAllocateInfo.calloc(stack).sType$Default()
                    .allocationSize(reqs.size()).memoryTypeIndex(type), null, p), "allocate_image:" + what);
                memory = p.get(0);
                RtFailure.check(VK10.vkBindImageMemory(device, image, memory, 0), "bind_image:" + what);
                base = view(device, stack, image, format, 1, what);
                full = levels == 1 ? base : view(device, stack, image, format, levels, what);
                return new IrisImage(image, memory, base, full, width, height, format, levels, reqs.size());
            } catch (RuntimeException e) {
                if (full != 0 && full != base) VK10.vkDestroyImageView(device, full, null);
                if (base != 0) VK10.vkDestroyImageView(device, base, null);
                VK10.vkDestroyImage(device, image, null);
                if (memory != 0) VK10.vkFreeMemory(device, memory, null);
                throw e;
            }
        }
    }

    private static long view(VkDevice device, MemoryStack stack, long image, int format, int levels, String what) {
        VkImageViewCreateInfo info = VkImageViewCreateInfo.calloc(stack).sType$Default()
            .image(image).viewType(VK10.VK_IMAGE_VIEW_TYPE_2D).format(format);
        info.subresourceRange().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).baseMipLevel(0).levelCount(levels)
            .baseArrayLayer(0).layerCount(1);
        LongBuffer p = stack.mallocLong(1);
        RtFailure.check(VK10.vkCreateImageView(device, info, null, p), "create_image_view:" + what);
        return p.get(0);
    }

    /** UNDEFINED -> GENERAL for every level. */
    void initialize(VkCommandBuffer cmd) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkImageMemoryBarrier.Buffer b = VkImageMemoryBarrier.calloc(1, stack);
            b.get(0).sType$Default().oldLayout(VK10.VK_IMAGE_LAYOUT_UNDEFINED).newLayout(VK10.VK_IMAGE_LAYOUT_GENERAL)
                .srcAccessMask(0).dstAccessMask(VK10.VK_ACCESS_MEMORY_READ_BIT | VK10.VK_ACCESS_MEMORY_WRITE_BIT)
                .srcQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED).image(image);
            b.get(0).subresourceRange().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).levelCount(levels).layerCount(1);
            VK10.vkCmdPipelineBarrier(cmd, VK10.VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK10.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                0, null, null, b);
        }
    }

    void destroy(VkDevice device) {
        if (fullView != baseView) VK10.vkDestroyImageView(device, fullView, null);
        VK10.vkDestroyImageView(device, baseView, null);
        VK10.vkDestroyImage(device, image, null);
        VK10.vkFreeMemory(device, memory, null);
    }
}
