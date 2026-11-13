package io.github.andrexarlay.vulkancompat.rtx.raytracing;

import io.github.andrexarlay.vulkancompat.rt.slang.SlangDeviceCapabilities;
import io.github.andrexarlay.vulkancompat.rtx.config.GiStackPolicy;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceMemoryProperties;

import static org.lwjgl.vulkan.VK10.VK_MEMORY_HEAP_DEVICE_LOCAL_BIT;
import static org.lwjgl.vulkan.VK10.vkGetPhysicalDeviceMemoryProperties;

/**
 * Builds the {@link GiStackPolicy.Device} the auto-policy reasons about from the live logical device: the enabled feature
 * mask (never physical support — see {@link SlangDeviceCapabilities}) and the total DEVICE_LOCAL heap memory. The heap sum
 * is a pure function so the budget input is unit-tested without a GPU; {@link #probe()} returns null before the device
 * exists, and the caller falls back to a manual tier choice.
 */
public final class GiDeviceProbe {

    private GiDeviceProbe() {
    }

    /** Sum of the DEVICE_LOCAL heaps (the video memory the reservoir budget is a quarter of). */
    public static long deviceLocalBytes(long[] heapSizes, int[] heapFlags) {
        long sum = 0;
        for (int i = 0; i < heapSizes.length && i < heapFlags.length; i++) {
            if ((heapFlags[i] & VK_MEMORY_HEAP_DEVICE_LOCAL_BIT) != 0) {
                sum += heapSizes[i];
            }
        }
        return sum;
    }

    /** The live device's memory and enabled-feature mask, or null when the Vulkan device is not available yet. */
    public static GiStackPolicy.Device probe() {
        var device = VulkanCapabilityChecker.getVulkanDevice();
        if (device == null) {
            return null;
        }
        int mask = SlangDeviceCapabilities.enabledMask(device);
        VkPhysicalDevice physical = VulkanCapabilityChecker.getVkPhysicalDevice();
        long memory = 0;
        if (physical != null) {
            try (var props = VkPhysicalDeviceMemoryProperties.calloc()) {
                vkGetPhysicalDeviceMemoryProperties(physical, props);
                long[] sizes = new long[props.memoryHeapCount()];
                int[] flags = new int[props.memoryHeapCount()];
                for (int i = 0; i < sizes.length; i++) {
                    sizes[i] = props.memoryHeaps(i).size();
                    flags[i] = props.memoryHeaps(i).flags();
                }
                memory = deviceLocalBytes(sizes, flags);
            }
        }
        return new GiStackPolicy.Device(memory, mask);
    }
}