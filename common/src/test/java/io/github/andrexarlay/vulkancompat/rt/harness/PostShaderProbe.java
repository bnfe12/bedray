package io.github.andrexarlay.vulkancompat.rt.harness;

import io.github.andrexarlay.vulkancompat.rt.world.RtMemory;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK12.VK_API_VERSION_1_2;

/**
 * Offscreen plain-compute device for the 8 non-ray-query post shaders (L2 baseline harness). Vulkan 1.2 with
 * bufferDeviceAddress (needed by rt_atrous/velocity_reproject/rt_pack_nrd device addresses), NO ray query or
 * acceleration structure. Builds a compute pipeline from any SPIR-V under an explicit descriptor layout + push
 * constants, uploads synthetic images, dispatches, reads back output bytes. Mirrors RtBaselineProbe's device
 * creation but does not perturb the RT reference harness.
 */
final class PostShaderProbe implements AutoCloseable {
    VkDevice device;
    VkQueue queue;
    VkCommandBuffer command;
    RtMemory memory;
    long sampler;
    String deviceName;
    int driverVersion, apiVersion;
    private VkInstance instance;
    private long commandPool;

    PostShaderProbe() {
        try {
            create();
        } catch (RuntimeException | Error failure) {
            close();
            throw failure;
        }
    }

    private void create() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer pointer = stack.mallocPointer(1);
            VkApplicationInfo app = VkApplicationInfo.calloc(stack).sType$Default()
                .pApplicationName(stack.UTF8("Bedray post-shader baseline"))
                .applicationVersion(1).pEngineName(stack.UTF8("Bedray")).engineVersion(1)
                .apiVersion(VK_API_VERSION_1_2);
            VkInstanceCreateInfo ici = VkInstanceCreateInfo.calloc(stack).sType$Default().pApplicationInfo(app);
            check(vkCreateInstance(ici, null, pointer), "Create Vulkan 1.2 instance");
            instance = new VkInstance(pointer.get(0), ici);

            IntBuffer count = stack.ints(0);
            check(vkEnumeratePhysicalDevices(instance, count, null), "Count physical devices");
            assertTrue(count.get(0) > 0, "No Vulkan physical devices");
            PointerBuffer physicals = stack.mallocPointer(count.get(0));
            check(vkEnumeratePhysicalDevices(instance, count, physicals), "Enumerate physical devices");
            VkPhysicalDevice physical = null;
            int family = -1;
            for (int i = 0; i < physicals.remaining() && physical == null; i++) {
                VkPhysicalDevice candidate = new VkPhysicalDevice(physicals.get(i), instance);
                vkGetPhysicalDeviceQueueFamilyProperties(candidate, count, null);
                VkQueueFamilyProperties.Buffer families = VkQueueFamilyProperties.calloc(count.get(0), stack);
                vkGetPhysicalDeviceQueueFamilyProperties(candidate, count, families);
                for (int j = 0; j < families.capacity(); j++) {
                    if (families.get(j).queueCount() > 0 && (families.get(j).queueFlags() & VK_QUEUE_COMPUTE_BIT) != 0) {
                        physical = candidate;
                        family = j;
                        break;
                    }
                }
            }
            if (physical == null) {
                throw new IllegalStateException("No Vulkan compute device. The harness fails rather than skipping: L2 evidence.");
            }
            VkPhysicalDeviceProperties props = VkPhysicalDeviceProperties.calloc(stack);
            vkGetPhysicalDeviceProperties(physical, props);
            deviceName = props.deviceNameString();
            driverVersion = props.driverVersion();
            apiVersion = props.apiVersion();

            VkDeviceQueueCreateInfo.Buffer queues = VkDeviceQueueCreateInfo.calloc(1, stack);
            queues.get(0).sType$Default().queueFamilyIndex(family).pQueuePriorities(stack.floats(1));
            VkPhysicalDeviceVulkan12Features vk12 = VkPhysicalDeviceVulkan12Features.calloc(stack)
                .sType$Default().bufferDeviceAddress(true);
            VkDeviceCreateInfo dci = VkDeviceCreateInfo.calloc(stack).sType$Default()
                .pQueueCreateInfos(queues).pNext(vk12.address());
            check(vkCreateDevice(physical, dci, null, pointer), "Create compute device");
            device = new VkDevice(pointer.get(0), physical, dci);
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

            VkSamplerCreateInfo si = VkSamplerCreateInfo.calloc(stack).sType$Default()
                .magFilter(VK_FILTER_NEAREST).minFilter(VK_FILTER_NEAREST)
                .mipmapMode(VK_SAMPLER_MIPMAP_MODE_NEAREST)
                .addressModeU(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                .addressModeV(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                .addressModeW(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE);
            check(vkCreateSampler(device, si, null, handle), "Create sampler");
            sampler = handle.get(0);
        }
    }

    record Binding(int index, int type) {}

    /** A compute pipeline + one descriptor set built from SPIR-V and an explicit binding layout. */
    final class Pipeline implements AutoCloseable {
        private long shaderModule, setLayout, pipelineLayout, pipeline, pool, set;
        private final int pcSize;

        Pipeline(ByteBuffer spirv, Binding[] bindings, int pcSize) {
            this.pcSize = pcSize;
            try (MemoryStack stack = MemoryStack.stackPush()) {
                LongBuffer p = stack.mallocLong(1);
                VkShaderModuleCreateInfo mci = VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(spirv);
                check(vkCreateShaderModule(device, mci, null, p), "Create shader module");
                shaderModule = p.get(0);

                VkDescriptorSetLayoutBinding.Buffer b = VkDescriptorSetLayoutBinding.calloc(bindings.length, stack);
                Map<Integer, Integer> counts = new HashMap<>();
                for (int i = 0; i < bindings.length; i++) {
                    b.get(i).binding(bindings[i].index()).descriptorType(bindings[i].type())
                        .descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
                    counts.merge(bindings[i].type(), 1, Integer::sum);
                }
                VkDescriptorSetLayoutCreateInfo lci = VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default().pBindings(b);
                check(vkCreateDescriptorSetLayout(device, lci, null, p), "Create descriptor set layout");
                setLayout = p.get(0);

                VkPipelineLayoutCreateInfo plci = VkPipelineLayoutCreateInfo.calloc(stack).sType$Default()
                    .pSetLayouts(stack.longs(setLayout));
                if (pcSize > 0) {
                    plci.pPushConstantRanges(VkPushConstantRange.calloc(1, stack)
                        .stageFlags(VK_SHADER_STAGE_COMPUTE_BIT).offset(0).size(pcSize));
                }
                check(vkCreatePipelineLayout(device, plci, null, p), "Create pipeline layout");
                pipelineLayout = p.get(0);

                VkComputePipelineCreateInfo.Buffer cpi = VkComputePipelineCreateInfo.calloc(1, stack);
                cpi.get(0).sType$Default().layout(pipelineLayout).stage().sType$Default()
                    .stage(VK_SHADER_STAGE_COMPUTE_BIT).module(shaderModule).pName(stack.UTF8("main"));
                check(vkCreateComputePipelines(device, VK_NULL_HANDLE, cpi, null, p), "Create compute pipeline");
                pipeline = p.get(0);

                VkDescriptorPoolSize.Buffer ps = VkDescriptorPoolSize.calloc(counts.size(), stack);
                int idx = 0;
                for (Map.Entry<Integer, Integer> e : counts.entrySet()) {
                    ps.get(idx++).type(e.getKey()).descriptorCount(e.getValue());
                }
                VkDescriptorPoolCreateInfo dpi = VkDescriptorPoolCreateInfo.calloc(stack).sType$Default().maxSets(1).pPoolSizes(ps);
                check(vkCreateDescriptorPool(device, dpi, null, p), "Create descriptor pool");
                pool = p.get(0);
                VkDescriptorSetAllocateInfo dai = VkDescriptorSetAllocateInfo.calloc(stack).sType$Default()
                    .descriptorPool(pool).pSetLayouts(stack.longs(setLayout));
                check(vkAllocateDescriptorSets(device, dai, p), "Allocate descriptor set");
                set = p.get(0);
            }
        }

        void image(int binding, long view, boolean sampled) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkDescriptorImageInfo.Buffer info = VkDescriptorImageInfo.calloc(1, stack)
                    .imageView(view).imageLayout(VK_IMAGE_LAYOUT_GENERAL);
                if (sampled) info.sampler(sampler);
                VkWriteDescriptorSet.Buffer w = VkWriteDescriptorSet.calloc(1, stack).sType$Default()
                    .dstSet(set).dstBinding(binding)
                    .descriptorType(sampled ? VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER : VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                    .descriptorCount(1).pImageInfo(info);
                vkUpdateDescriptorSets(device, w, null);
            }
        }

        void buffer(int binding, long buf, int type, long range) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkDescriptorBufferInfo.Buffer info = VkDescriptorBufferInfo.calloc(1, stack)
                    .buffer(buf).offset(0).range(range);
                VkWriteDescriptorSet.Buffer w = VkWriteDescriptorSet.calloc(1, stack).sType$Default()
                    .dstSet(set).dstBinding(binding).descriptorType(type).descriptorCount(1).pBufferInfo(info);
                vkUpdateDescriptorSets(device, w, null);
            }
        }

        void record(VkCommandBuffer cmd, byte[] pc, int gx, int gy, int gz) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
                vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, pipelineLayout, 0, stack.longs(set), null);
                if (pcSize > 0) {
                    ByteBuffer pcBuf = stack.malloc(pc.length);
                    pcBuf.put(pc).flip();
                    vkCmdPushConstants(cmd, pipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT, 0, pcBuf);
                }
                vkCmdDispatch(cmd, gx, gy, gz);
            }
        }

        @Override public void close() {
            vkDeviceWaitIdle(device);
            if (pipeline != 0) vkDestroyPipeline(device, pipeline, null);
            if (pipelineLayout != 0) vkDestroyPipelineLayout(device, pipelineLayout, null);
            if (pool != 0) vkDestroyDescriptorPool(device, pool, null);
            if (setLayout != 0) vkDestroyDescriptorSetLayout(device, setLayout, null);
            if (shaderModule != 0) vkDestroyShaderModule(device, shaderModule, null);
            pipeline = pipelineLayout = pool = setLayout = shaderModule = 0;
        }
    }

    void submit(Consumer<VkCommandBuffer> recorder) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            check(vkResetCommandBuffer(command, 0), "Reset command buffer");
            check(vkBeginCommandBuffer(command, VkCommandBufferBeginInfo.calloc(stack).sType$Default()
                .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT)), "Begin command buffer");
            recorder.accept(command);
            check(vkEndCommandBuffer(command), "End command buffer");
            VkSubmitInfo.Buffer si = VkSubmitInfo.calloc(1, stack).sType$Default()
                .pCommandBuffers(stack.pointers(command.address()));
            check(vkQueueSubmit(queue, si, VK_NULL_HANDLE), "Submit");
            check(vkQueueWaitIdle(queue), "Wait");
        }
    }

    void toGeneral(VkCommandBuffer cmd, long image, int oldLayout) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkImageMemoryBarrier.Buffer bar = VkImageMemoryBarrier.calloc(1, stack).sType$Default()
                .image(image).srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .oldLayout(oldLayout).newLayout(VK_IMAGE_LAYOUT_GENERAL)
                .srcAccessMask(0).dstAccessMask(VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT);
            bar.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
            vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, null, null, bar);
        }
    }

    /** Uploads tightly-packed texel bytes (bpp = bytes per pixel) and leaves the image in GENERAL. */
    void upload(RtMemory.Img img, byte[] data, int bpp) {
        RtMemory.Buf staging = memory.createBuffer("post_upload", data.length, VK_BUFFER_USAGE_TRANSFER_SRC_BIT, true);
        try {
            MemoryUtil.memByteBuffer(staging.mapped, data.length).put(data);
            submit(cmd -> {
                try (MemoryStack stack = MemoryStack.stackPush()) {
                    VkImageMemoryBarrier.Buffer toDst = VkImageMemoryBarrier.calloc(1, stack).sType$Default()
                        .image(img.image).srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                        .oldLayout(VK_IMAGE_LAYOUT_UNDEFINED).newLayout(VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL)
                        .srcAccessMask(0).dstAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT);
                    toDst.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
                    vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0, null, null, toDst);
                    VkBufferImageCopy.Buffer copy = VkBufferImageCopy.calloc(1, stack);
                    copy.imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
                    copy.imageExtent().set(img.width, img.height, 1);
                    vkCmdCopyBufferToImage(cmd, staging.handle, img.image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, copy);
                    VkImageMemoryBarrier.Buffer toGen = VkImageMemoryBarrier.calloc(1, stack).sType$Default()
                        .image(img.image).srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                        .oldLayout(VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL).newLayout(VK_IMAGE_LAYOUT_GENERAL)
                        .srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT).dstAccessMask(VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT);
                    toGen.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
                    vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, null, null, toGen);
                }
            });
        } finally {
            memory.destroy(staging);
        }
    }

    /** Copies the image (GENERAL) to a host buffer and returns its bytes; leaves the image in GENERAL. */
    byte[] readback(RtMemory.Img img, int bpp) {
        int bytes = img.width * img.height * bpp;
        RtMemory.Buf dst = memory.createBuffer("post_readback", bytes, VK_BUFFER_USAGE_TRANSFER_DST_BIT, true);
        try {
            submit(cmd -> {
                try (MemoryStack stack = MemoryStack.stackPush()) {
                    VkImageMemoryBarrier.Buffer toSrc = VkImageMemoryBarrier.calloc(1, stack).sType$Default()
                        .image(img.image).srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                        .oldLayout(VK_IMAGE_LAYOUT_GENERAL).newLayout(VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL)
                        .srcAccessMask(VK_ACCESS_SHADER_WRITE_BIT).dstAccessMask(VK_ACCESS_TRANSFER_READ_BIT);
                    toSrc.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
                    vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0, null, null, toSrc);
                    VkBufferImageCopy.Buffer copy = VkBufferImageCopy.calloc(1, stack);
                    copy.imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
                    copy.imageExtent().set(img.width, img.height, 1);
                    vkCmdCopyImageToBuffer(cmd, img.image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, dst.handle, copy);
                    VkImageMemoryBarrier.Buffer toGen = VkImageMemoryBarrier.calloc(1, stack).sType$Default()
                        .image(img.image).srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                        .oldLayout(VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL).newLayout(VK_IMAGE_LAYOUT_GENERAL)
                        .srcAccessMask(VK_ACCESS_TRANSFER_READ_BIT).dstAccessMask(VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT);
                    toGen.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
                    vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, null, null, toGen);
                    VkBufferMemoryBarrier.Buffer host = VkBufferMemoryBarrier.calloc(1, stack).sType$Default()
                        .buffer(dst.handle).offset(0).size(bytes)
                        .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                        .srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT).dstAccessMask(VK_ACCESS_HOST_READ_BIT);
                    vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_HOST_BIT, 0, null, host, null);
                }
            });
            ByteBuffer mapped = MemoryUtil.memByteBuffer(dst.mapped, bytes);
            byte[] out = new byte[bytes];
            mapped.get(out);
            return out;
        } finally {
            memory.destroy(dst);
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
            if (sampler != 0) vkDestroySampler(device, sampler, null);
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