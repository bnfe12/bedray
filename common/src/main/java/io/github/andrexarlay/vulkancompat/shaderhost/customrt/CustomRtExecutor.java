package io.github.andrexarlay.vulkancompat.shaderhost.customrt;

import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import io.github.andrexarlay.vulkancompat.rt.world.RtMemory;
import io.github.andrexarlay.vulkancompat.rtx.frame.WorldFrame;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.VkBarriers;
import io.github.andrexarlay.vulkancompat.shaderhost.glsl.VulkanGlslPackCompiler;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRAccelerationStructure;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkComputePipelineCreateInfo;
import org.lwjgl.vulkan.VkDescriptorImageInfo;
import org.lwjgl.vulkan.VkDescriptorBufferInfo;
import org.lwjgl.vulkan.VkDescriptorPoolCreateInfo;
import org.lwjgl.vulkan.VkDescriptorPoolSize;
import org.lwjgl.vulkan.VkDescriptorSetAllocateInfo;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkFormatProperties;
import org.lwjgl.vulkan.VkImageBlit;
import org.lwjgl.vulkan.VkImageCopy;
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo;
import org.lwjgl.vulkan.VkSamplerCreateInfo;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;
import org.lwjgl.vulkan.VkWriteDescriptorSet;
import org.lwjgl.vulkan.VkWriteDescriptorSetAccelerationStructureKHR;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.lwjgl.vulkan.VK10.*;

/**
 * Vulkan resources of {@link CustomRtStage}: one compute pipeline with the fixed set-0 layout, a device-local frame UBO
 * written by {@code vkCmdUpdateBuffer} (ordered with the frame, no CPU/GPU race), a sampled copy of the world color, an
 * RGBA16F output image and one descriptor set. Images stay in GENERAL like every engine texture.
 * <p>
 * Descriptors are rewritten only in {@link #prepare}, outside an open command buffer, after queued host work was
 * submitted and the device went idle: the previous frame may still be reading the set.
 */
final class CustomRtExecutor implements AutoCloseable {
    static final int FRAME_UBO_BYTES = 320;
    private static final int OUTPUT_FORMAT = VK_FORMAT_R16G16B16A16_SFLOAT;

    private final VkDevice device;
    private final RtMemory memory;
    private final int[] localSize;
    private long sampler, setLayout, pipelineLayout, pipeline, pool, set;
    private RtMemory.Buf frameUbo;
    private RtMemory.Img input, output;
    private boolean imagesInitialized;
    // descriptor key
    private int width, height, colorFormat;
    private long boundTlas, boundDepthView = -1;
    private boolean depthBound;
    private long lastNanos;
    private float timeCounter, frameTime = 1f / 60f;
    private int frameCounter;

    CustomRtExecutor(VkDevice device, VulkanGlslPackCompiler.Compiled compiled) {
        this.device = device;
        this.memory = new RtMemory(device);
        this.localSize = compiled.module().localSize();
        try {
            create(compiled);
        } catch (RuntimeException | LinkageError e) {
            close();
            throw e;
        }
    }

    private void create(VulkanGlslPackCompiler.Compiled compiled) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var handle = stack.mallocLong(1);
            check(vkCreateSampler(device, VkSamplerCreateInfo.calloc(stack).sType$Default().magFilter(VK_FILTER_NEAREST)
                .minFilter(VK_FILTER_NEAREST).mipmapMode(VK_SAMPLER_MIPMAP_MODE_NEAREST)
                .addressModeU(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE).addressModeV(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                .addressModeW(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE).maxLod(0), null, handle), "sampler");
            sampler = handle.get(0);

            var b = VkDescriptorSetLayoutBinding.calloc(5, stack);
            b.get(0).binding(CustomRtStage.B_SCENE).descriptorType(KHRAccelerationStructure.VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR)
                .descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            b.get(1).binding(CustomRtStage.B_FRAME).descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            b.get(2).binding(CustomRtStage.B_COLOR).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            b.get(3).binding(CustomRtStage.B_OUTPUT).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            b.get(4).binding(CustomRtStage.B_DEPTH).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            check(vkCreateDescriptorSetLayout(device, VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default().pBindings(b), null, handle), "set layout");
            setLayout = handle.get(0);
            check(vkCreatePipelineLayout(device, VkPipelineLayoutCreateInfo.calloc(stack).sType$Default().pSetLayouts(stack.longs(setLayout)),
                null, handle), "pipeline layout");
            pipelineLayout = handle.get(0);

            check(vkCreateShaderModule(device, VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(compiled.spirv()), null, handle),
                "shader module");
            long module = handle.get(0);
            try {
                var info = VkComputePipelineCreateInfo.calloc(1, stack);
                info.get(0).sType$Default().layout(pipelineLayout);
                info.get(0).stage().sType$Default().stage(VK_SHADER_STAGE_COMPUTE_BIT).module(module).pName(stack.UTF8("main"));
                check(vkCreateComputePipelines(device, VK_NULL_HANDLE, info, null, handle), "compute pipeline");
                pipeline = handle.get(0);
            } finally {
                vkDestroyShaderModule(device, module, null);
            }

            var sizes = VkDescriptorPoolSize.calloc(4, stack);
            sizes.get(0).type(KHRAccelerationStructure.VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR).descriptorCount(1);
            sizes.get(1).type(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).descriptorCount(1);
            sizes.get(2).type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(2);
            sizes.get(3).type(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(1);
            check(vkCreateDescriptorPool(device, VkDescriptorPoolCreateInfo.calloc(stack).sType$Default().maxSets(1).pPoolSizes(sizes),
                null, handle), "descriptor pool");
            pool = handle.get(0);
            check(vkAllocateDescriptorSets(device, VkDescriptorSetAllocateInfo.calloc(stack).sType$Default().descriptorPool(pool)
                .pSetLayouts(stack.longs(setLayout)), handle), "descriptor set");
            set = handle.get(0);
            frameUbo = memory.createBuffer("customrt_frame", FRAME_UBO_BYTES,
                VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT, false);
        }
    }

    /** Forces the next {@link #prepare} to rewrite every descriptor. */
    void invalidate() {
        boundTlas = 0L;
        boundDepthView = -1;
    }

    /**
     * (Re)creates the extent-dependent images and rewrites the descriptor set when anything it references changed. Called
     * with no command buffer open; waits for the device before touching a set that an earlier frame may still use.
     */
    void prepare(VulkanDevice host, WorldFrame frame, long tlas, RtMemory.Img depth) {
        var color = frame.color();
        boolean extentChanged = input == null || color.width() != width || color.height() != height || color.format() != colorFormat;
        long depthView = depth != null ? depth.view : 0L;
        if (!extentChanged && tlas == boundTlas && depthView == boundDepthView) return;
        host.createCommandEncoder().submit(); // queued references first, then wait: the set/images may be in flight
        int wait = vkDeviceWaitIdle(device);
        if (wait != VK_SUCCESS) throw new IllegalStateException("custom RT wait failed: VkResult " + wait);
        if (extentChanged) {
            checkFormats(color.format());
            if (input != null) memory.destroy(input);
            if (output != null) memory.destroy(output);
            input = null;
            output = null;
            input = memory.createImage("customrt_color", color.width(), color.height(), color.format(),
                VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT);
            output = memory.createImage("customrt_output", color.width(), color.height(), OUTPUT_FORMAT,
                VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT);
            imagesInitialized = false;
            width = color.width();
            height = color.height();
            colorFormat = color.format();
        }
        writeDescriptors(tlas, depth);
        boundTlas = tlas;
        boundDepthView = depthView;
        depthBound = depth != null;
    }

    private void checkFormats(int colorFormat) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var props = VkFormatProperties.calloc(stack);
            vkGetPhysicalDeviceFormatProperties(device.getPhysicalDevice(), colorFormat, props);
            int colorNeeds = VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT | VK_FORMAT_FEATURE_BLIT_DST_BIT;
            if ((props.optimalTilingFeatures() & colorNeeds) != colorNeeds)
                throw new IllegalStateException("world color format " + colorFormat + " cannot be sampled and blitted into");
            vkGetPhysicalDeviceFormatProperties(device.getPhysicalDevice(), OUTPUT_FORMAT, props);
            int outNeeds = VK_FORMAT_FEATURE_STORAGE_IMAGE_BIT | VK_FORMAT_FEATURE_BLIT_SRC_BIT;
            if ((props.optimalTilingFeatures() & outNeeds) != outNeeds)
                throw new IllegalStateException("RGBA16F output cannot be a storage image and blit source on this device");
        }
    }

    private void writeDescriptors(long tlas, RtMemory.Img depth) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var writes = VkWriteDescriptorSet.calloc(5, stack);
            var as = VkWriteDescriptorSetAccelerationStructureKHR.calloc(stack).sType$Default()
                .pAccelerationStructures(stack.longs(tlas));
            writes.get(0).sType$Default().pNext(as.address()).dstSet(set).dstBinding(CustomRtStage.B_SCENE)
                .descriptorType(KHRAccelerationStructure.VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR).descriptorCount(1);
            var buffers = VkDescriptorBufferInfo.calloc(1, stack);
            buffers.get(0).buffer(frameUbo.handle).offset(0).range(FRAME_UBO_BYTES);
            writes.get(1).sType$Default().dstSet(set).dstBinding(CustomRtStage.B_FRAME)
                .descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).descriptorCount(1).pBufferInfo(buffers);
            var images = VkDescriptorImageInfo.calloc(3, stack);
            images.get(0).sampler(sampler).imageView(input.view).imageLayout(VK_IMAGE_LAYOUT_GENERAL);
            images.get(1).imageView(output.view).imageLayout(VK_IMAGE_LAYOUT_GENERAL);
            // Without a depth guide the slot holds the color copy; BedrayFrame.depthInfo.z = 0 tells the shader not to read it.
            images.get(2).sampler(sampler).imageView(depth != null ? depth.view : input.view).imageLayout(VK_IMAGE_LAYOUT_GENERAL);
            writes.get(2).sType$Default().dstSet(set).dstBinding(CustomRtStage.B_COLOR)
                .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1).pImageInfo(images.slice(0, 1));
            writes.get(3).sType$Default().dstSet(set).dstBinding(CustomRtStage.B_OUTPUT)
                .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(1).pImageInfo(images.slice(1, 1));
            writes.get(4).sType$Default().dstSet(set).dstBinding(CustomRtStage.B_DEPTH)
                .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1).pImageInfo(images.slice(2, 1));
            vkUpdateDescriptorSets(device, writes, null);
        }
    }

    /** Records UBO update, color copy, dispatch and the blit back into the world color. */
    void record(VkCommandBuffer cmd, WorldFrame frame) {
        updateTiming();
        var color = frame.color();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            if (!imagesInitialized) {
                VkBarriers.transition(cmd, input.image, VK_IMAGE_ASPECT_COLOR_BIT, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_GENERAL);
                VkBarriers.transition(cmd, output.image, VK_IMAGE_ASPECT_COLOR_BIT, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_GENERAL);
                imagesInitialized = true;
            }
            ByteBuffer ubo = stack.calloc(FRAME_UBO_BYTES).order(ByteOrder.nativeOrder());
            fillFrame(ubo, frame.camera(), color.width(), color.height());
            vkCmdUpdateBuffer(cmd, frameUbo.handle, 0, ubo);

            var copy = VkImageCopy.calloc(1, stack);
            copy.get(0).srcSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
            copy.get(0).dstSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
            copy.get(0).extent().set(color.width(), color.height(), 1);
            vkCmdCopyImage(cmd, color.image(), VK_IMAGE_LAYOUT_GENERAL, input.image, VK_IMAGE_LAYOUT_GENERAL, copy);
            VkBarriers.fullMemoryBarrier(cmd);

            vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
            vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, pipelineLayout, 0, stack.longs(set), null);
            vkCmdDispatch(cmd, (color.width() + localSize[0] - 1) / localSize[0], (color.height() + localSize[1] - 1) / localSize[1], 1);
            VkBarriers.fullMemoryBarrier(cmd);

            var blit = VkImageBlit.calloc(1, stack);
            blit.get(0).srcSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
            blit.get(0).dstSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
            blit.get(0).srcOffsets(1).set(color.width(), color.height(), 1);
            blit.get(0).dstOffsets(1).set(color.width(), color.height(), 1);
            vkCmdBlitImage(cmd, output.image, VK_IMAGE_LAYOUT_GENERAL, color.image(), VK_IMAGE_LAYOUT_GENERAL, blit, VK_FILTER_NEAREST);
        }
    }

    /** std140 BedrayFrame block; offsets documented in docs/CUSTOM_RT_SHADERS.md. */
    private void fillFrame(ByteBuffer b, WorldFrame.Camera camera, int w, int h) {
        camera.projection().get(0, b);
        camera.inverseProjection().get(64, b);
        camera.modelView().get(128, b);
        camera.inverseModelView().get(192, b);
        b.putFloat(256, (float) camera.x()).putFloat(260, (float) camera.y()).putFloat(264, (float) camera.z()).putFloat(268, 0f);
        b.putFloat(272, w).putFloat(276, h).putFloat(280, 1f / w).putFloat(284, 1f / h);
        b.putFloat(288, timeCounter).putFloat(292, frameTime).putFloat(296, frameCounter).putFloat(300, 0f);
        b.putFloat(304, camera.near()).putFloat(308, camera.far()).putFloat(312, depthBound ? 1f : 0f).putFloat(316, 0f);
    }

    private void updateTiming() {
        long now = System.nanoTime();
        if (lastNanos != 0) frameTime = Math.max(1e-4f, Math.min(1f, (now - lastNanos) / 1e9f));
        lastNanos = now;
        timeCounter = (timeCounter + frameTime) % 3600f;
        frameCounter = (frameCounter + 1) % 720720;
    }

    @Override
    public void close() {
        if (input != null) memory.destroy(input);
        if (output != null) memory.destroy(output);
        if (frameUbo != null) memory.destroy(frameUbo);
        input = output = null;
        frameUbo = null;
        if (pool != 0) vkDestroyDescriptorPool(device, pool, null);
        if (pipeline != 0) vkDestroyPipeline(device, pipeline, null);
        if (pipelineLayout != 0) vkDestroyPipelineLayout(device, pipelineLayout, null);
        if (setLayout != 0) vkDestroyDescriptorSetLayout(device, setLayout, null);
        if (sampler != 0) vkDestroySampler(device, sampler, null);
        pool = pipeline = pipelineLayout = setLayout = sampler = set = 0;
        memory.close();
    }

    private static void check(int result, String what) {
        if (result != VK_SUCCESS) throw new IllegalStateException("custom RT " + what + " failed: VkResult " + result);
    }
}
