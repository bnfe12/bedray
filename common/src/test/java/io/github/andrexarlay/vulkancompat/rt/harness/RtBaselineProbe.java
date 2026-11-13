package io.github.andrexarlay.vulkancompat.rt.harness;

import io.github.andrexarlay.vulkancompat.rt.world.RtMemory;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;

import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK12.VK_API_VERSION_1_2;

/**
 * Standalone offscreen RT device: a Vulkan 1.2 instance/device with ray query, acceleration
 * structure and buffer device address enabled (the host RT requirement set). Owns the output
 * image (STORAGE|TRANSFER_SRC|TRANSFER_DST), a host-visible readback buffer and one command
 * buffer. Mirrors VulkanComputeProbeTest.Probe; the game device is created by the engine, this
 * harness creates its own so RT can be measured offscreen (L2).
 */
final class RtBaselineProbe implements AutoCloseable {
    final int width, height, pixelBytes;
    VkInstance instance;
    VkDevice device;
    VkQueue queue;
    VkCommandBuffer command;
    String deviceName;
    int driverVersion, apiVersion;
    long commandPool, target, targetMemory, targetView, readback, readbackMemory;
    private RtMemory memory;
    private boolean targetInitialized;

    RtBaselineProbe(int width, int height) {
        this.width = width;
        this.height = height;
        this.pixelBytes = width * height * 4;
        try {
            create();
        } catch (RuntimeException | Error failure) {
            close();
            throw failure;
        }
    }

    RtMemory memory() {
        return memory;
    }

    private void create() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer pointer = stack.mallocPointer(1);
            VkApplicationInfo application = VkApplicationInfo.calloc(stack).sType$Default()
                .pApplicationName(stack.UTF8("Bedray RT offscreen baseline"))
                .applicationVersion(1).pEngineName(stack.UTF8("Bedray RT"))
                .engineVersion(1).apiVersion(VK_API_VERSION_1_2);
            VkInstanceCreateInfo info = VkInstanceCreateInfo.calloc(stack).sType$Default().pApplicationInfo(application);
            check(vkCreateInstance(info, null, pointer), "Create Vulkan 1.2 instance");
            instance = new VkInstance(pointer.get(0), info);

            IntBuffer count = stack.ints(0);
            check(vkEnumeratePhysicalDevices(instance, count, null), "Count physical devices");
            assertTrue(count.get(0) > 0, "No Vulkan physical devices");
            PointerBuffer physicals = stack.mallocPointer(count.get(0));
            check(vkEnumeratePhysicalDevices(instance, count, physicals), "Enumerate physical devices");
            VkPhysicalDevice physical = null;
            int family = -1;
            for (int i = 0; i < physicals.remaining() && physical == null; i++) {
                VkPhysicalDevice candidate = new VkPhysicalDevice(physicals.get(i), instance);
                // Require the RT extensions on this physical device.
                if (!hasExtensions(candidate, stack)) continue;
                vkGetPhysicalDeviceQueueFamilyProperties(candidate, count, null);
                VkQueueFamilyProperties.Buffer families = VkQueueFamilyProperties.calloc(count.get(0), stack);
                vkGetPhysicalDeviceQueueFamilyProperties(candidate, count, families);
                for (int j = 0; j < families.capacity(); j++) {
                    int flags = VK_QUEUE_GRAPHICS_BIT | VK_QUEUE_COMPUTE_BIT;
                    if (families.get(j).queueCount() > 0 && (families.get(j).queueFlags() & flags) == flags) {
                        physical = candidate;
                        family = j;
                        break;
                    }
                }
            }
            if (physical == null) {
                throw new IllegalStateException("No RT-capable Vulkan device (ray query + acceleration structure "
                    + "+ buffer device address). The harness fails rather than skipping: this is L2 RT evidence.");
            }
            VkPhysicalDeviceProperties properties = VkPhysicalDeviceProperties.calloc(stack);
            vkGetPhysicalDeviceProperties(physical, properties);
            deviceName = properties.deviceNameString();
            driverVersion = properties.driverVersion();
            apiVersion = properties.apiVersion();

            VkDeviceQueueCreateInfo.Buffer queues = VkDeviceQueueCreateInfo.calloc(1, stack);
            queues.get(0).sType$Default().queueFamilyIndex(family).pQueuePriorities(stack.floats(1));

            // Feature chain: Vulkan12.bufferDeviceAddress -> AS features -> ray query features.
            VkPhysicalDeviceVulkan12Features vk12 = VkPhysicalDeviceVulkan12Features.calloc(stack)
                .sType$Default().bufferDeviceAddress(true);
            VkPhysicalDeviceAccelerationStructureFeaturesKHR asFeatures =
                VkPhysicalDeviceAccelerationStructureFeaturesKHR.calloc(stack).sType$Default()
                    .accelerationStructure(true).pNext(vk12.address());
            VkPhysicalDeviceRayQueryFeaturesKHR rayQuery =
                VkPhysicalDeviceRayQueryFeaturesKHR.calloc(stack).sType$Default()
                    .rayQuery(true).pNext(asFeatures.address());

            PointerBuffer extensions = stack.pointers(
                stack.UTF8("VK_KHR_acceleration_structure"),
                stack.UTF8("VK_KHR_ray_query"),
                stack.UTF8("VK_KHR_buffer_device_address"),
                stack.UTF8("VK_KHR_deferred_host_operations"));
            VkDeviceCreateInfo deviceInfo = VkDeviceCreateInfo.calloc(stack).sType$Default()
                .pQueueCreateInfos(queues).ppEnabledExtensionNames(extensions).pNext(rayQuery.address());
            check(vkCreateDevice(physical, deviceInfo, null, pointer), "Create RT device");
            device = new VkDevice(pointer.get(0), physical, deviceInfo);
            vkGetDeviceQueue(device, family, 0, pointer);
            queue = new VkQueue(pointer.get(0), device);
            memory = new RtMemory(device);

            LongBuffer handle = stack.mallocLong(1);
            check(vkCreateCommandPool(device, VkCommandPoolCreateInfo.calloc(stack).sType$Default()
                .queueFamilyIndex(family).flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT), null, handle), "Create command pool");
            commandPool = handle.get(0);
            check(vkAllocateCommandBuffers(device, VkCommandBufferAllocateInfo.calloc(stack).sType$Default()
                .commandPool(commandPool).level(VK_COMMAND_BUFFER_LEVEL_PRIMARY).commandBufferCount(1), pointer), "Allocate command buffer");
            command = new VkCommandBuffer(pointer.get(0), device);

            RtMemory.Img img = memory.createImage("rt_baseline_output", width, height, VK_FORMAT_R8G8B8A8_UNORM,
                VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT);
            target = img.image;
            targetMemory = img.memory;
            targetView = img.view;
            check(vkCreateBuffer(device, VkBufferCreateInfo.calloc(stack).sType$Default().size(pixelBytes)
                .usage(VK_BUFFER_USAGE_TRANSFER_DST_BIT).sharingMode(VK_SHARING_MODE_EXCLUSIVE), null, handle), "Create readback buffer");
            readback = handle.get(0);
            VkMemoryRequirements requirements = VkMemoryRequirements.calloc(stack);
            vkGetBufferMemoryRequirements(device, readback, requirements);
            readbackMemory = allocate(physical, requirements,
                VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT, stack);
            check(vkBindBufferMemory(device, readback, readbackMemory, 0), "Bind readback memory");
        }
    }

    private static boolean hasExtensions(VkPhysicalDevice physical, MemoryStack stack) {
        IntBuffer count = stack.ints(0);
        check(vkEnumerateDeviceExtensionProperties(physical, (String) null, count, null), "Count device extensions");
        VkExtensionProperties.Buffer props = VkExtensionProperties.calloc(count.get(0), stack);
        check(vkEnumerateDeviceExtensionProperties(physical, (String) null, count, props), "Enumerate device extensions");
        boolean as = false, rq = false, bda = false, dho = false;
        for (int i = 0; i < props.capacity(); i++) {
            String name = props.get(i).extensionNameString();
            as |= name.equals("VK_KHR_acceleration_structure");
            rq |= name.equals("VK_KHR_ray_query");
            bda |= name.equals("VK_KHR_buffer_device_address");
            dho |= name.equals("VK_KHR_deferred_host_operations");
        }
        return as && rq && bda && dho;
    }

    long allocate(VkPhysicalDevice physical, VkMemoryRequirements requirements, int flags, MemoryStack stack) {
        VkPhysicalDeviceMemoryProperties mem = VkPhysicalDeviceMemoryProperties.calloc(stack);
        vkGetPhysicalDeviceMemoryProperties(physical, mem);
        for (int type = 0; type < mem.memoryTypeCount(); type++) {
            if ((requirements.memoryTypeBits() & (1 << type)) != 0
                && (mem.memoryTypes(type).propertyFlags() & flags) == flags) {
                LongBuffer handle = stack.mallocLong(1);
                check(vkAllocateMemory(device, VkMemoryAllocateInfo.calloc(stack).sType$Default()
                    .allocationSize(requirements.size()).memoryTypeIndex(type), null, handle), "Allocate probe memory");
                return handle.get(0);
            }
        }
        throw new AssertionError("No compatible probe memory type for flags " + flags);
    }

    /** Records {@code recorder} (build + dispatch) between an output-image transition and a readback copy; returns RGBA bytes. */
    byte[] render(java.util.function.Consumer<VkCommandBuffer> recorder) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            check(vkResetCommandBuffer(command, 0), "Reset command buffer");
            check(vkBeginCommandBuffer(command, VkCommandBufferBeginInfo.calloc(stack).sType$Default()
                .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT)), "Begin command buffer");
            // Clear the output to a known black before every dispatch so the baseline is reproducible.
            // rt_test.comp blends with the existing pixel (imageLoad + mix), so an uncleared image
            // accumulates across renders and is not idempotent; clearing fixes the initial state.
            int oldLayout = targetInitialized ? VK_IMAGE_LAYOUT_GENERAL : VK_IMAGE_LAYOUT_UNDEFINED;
            VkImageMemoryBarrier.Buffer toClear = VkImageMemoryBarrier.calloc(1, stack);
            toClear.get(0).sType$Default().image(target).srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).oldLayout(oldLayout)
                .newLayout(VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL).srcAccessMask(0).dstAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT);
            toClear.get(0).subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
            vkCmdPipelineBarrier(command, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0, null, null, toClear);
            VkClearColorValue black = VkClearColorValue.calloc(stack);
            black.float32(0, 0f).float32(1, 0f).float32(2, 0f).float32(3, 0f);
            VkImageSubresourceRange.Buffer clearRange = VkImageSubresourceRange.calloc(1, stack);
            clearRange.get(0).aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
            vkCmdClearColorImage(command, target, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, black, clearRange);
            VkImageMemoryBarrier.Buffer toGeneral = VkImageMemoryBarrier.calloc(1, stack);
            toGeneral.get(0).sType$Default().image(target).srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).oldLayout(VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL)
                .newLayout(VK_IMAGE_LAYOUT_GENERAL).srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT).dstAccessMask(VK_ACCESS_SHADER_WRITE_BIT);
            toGeneral.get(0).subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
            vkCmdPipelineBarrier(command, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, null, null, toGeneral);
            recorder.accept(command);
            VkImageMemoryBarrier.Buffer ready = VkImageMemoryBarrier.calloc(1, stack);
            ready.get(0).sType$Default().image(target).srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).oldLayout(VK_IMAGE_LAYOUT_GENERAL)
                .newLayout(VK_IMAGE_LAYOUT_GENERAL).srcAccessMask(VK_ACCESS_SHADER_WRITE_BIT)
                .dstAccessMask(VK_ACCESS_TRANSFER_READ_BIT);
            ready.get(0).subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
            vkCmdPipelineBarrier(command, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
                0, null, null, ready);
            VkBufferImageCopy.Buffer copy = VkBufferImageCopy.calloc(1, stack);
            copy.get(0).imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
            copy.get(0).imageExtent().set(width, height, 1);
            vkCmdCopyImageToBuffer(command, target, VK_IMAGE_LAYOUT_GENERAL, readback, copy);
            VkBufferMemoryBarrier.Buffer host = VkBufferMemoryBarrier.calloc(1, stack);
            host.get(0).sType$Default().buffer(readback).offset(0).size(pixelBytes)
                .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT).dstAccessMask(VK_ACCESS_HOST_READ_BIT);
            vkCmdPipelineBarrier(command, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_HOST_BIT, 0, null, host, null);
            check(vkEndCommandBuffer(command), "End command buffer");
            VkSubmitInfo.Buffer submit = VkSubmitInfo.calloc(1, stack);
            submit.get(0).sType$Default().pCommandBuffers(stack.pointers(command.address()));
            check(vkQueueSubmit(queue, submit, VK_NULL_HANDLE), "Submit RT probe");
            check(vkQueueWaitIdle(queue), "Wait for RT probe");
            targetInitialized = true;
            PointerBuffer mapped = stack.mallocPointer(1);
            check(vkMapMemory(device, readbackMemory, 0, pixelBytes, 0, mapped), "Map probe readback");
            try {
                ByteBuffer bytes = MemoryUtil.memByteBuffer(mapped.get(0), pixelBytes);
                byte[] result = new byte[pixelBytes];
                bytes.get(result);
                return result;
            } finally {
                vkUnmapMemory(device, readbackMemory);
            }
        }
    }

    private static void check(int result, String operation) {
        if (result != VK_SUCCESS) throw new IllegalStateException(operation + " failed: VkResult=" + result);
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    @Override public void close() {
        if (device != null) {
            vkDeviceWaitIdle(device);
            if (readback != 0) vkDestroyBuffer(device, readback, null);
            if (readbackMemory != 0) vkFreeMemory(device, readbackMemory, null);
            if (targetView != 0) vkDestroyImageView(device, targetView, null);
            if (target != 0) vkDestroyImage(device, target, null);
            if (targetMemory != 0) vkFreeMemory(device, targetMemory, null);
            if (commandPool != 0) vkDestroyCommandPool(device, commandPool, null);
            if (memory != null) memory.close();
            vkDestroyDevice(device, null);
            device = null;
        }
        if (instance != null) {
            vkDestroyInstance(instance, null);
            instance = null;
        }
    }
}