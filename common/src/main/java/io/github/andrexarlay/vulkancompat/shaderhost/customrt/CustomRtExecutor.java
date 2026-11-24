package io.github.andrexarlay.vulkancompat.shaderhost.customrt;

import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import io.github.andrexarlay.vulkancompat.rt.world.RtMemory;
import io.github.andrexarlay.vulkancompat.rtx.frame.WorldFrame;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.VkBarriers;
import io.github.andrexarlay.vulkancompat.shaderhost.glsl.SpirvReflection;
import io.github.andrexarlay.vulkancompat.shaderhost.glsl.VulkanGlslPackCompiler;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRAccelerationStructure;
import org.lwjgl.vulkan.VkBufferCopy;
import org.lwjgl.vulkan.VkBufferImageCopy;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkComputePipelineCreateInfo;
import org.lwjgl.vulkan.VkDescriptorBufferInfo;
import org.lwjgl.vulkan.VkDescriptorImageInfo;
import org.lwjgl.vulkan.VkDescriptorPoolCreateInfo;
import org.lwjgl.vulkan.VkDescriptorPoolSize;
import org.lwjgl.vulkan.VkDescriptorSetAllocateInfo;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkFormatProperties;
import org.lwjgl.vulkan.VkImageBlit;
import org.lwjgl.vulkan.VkImageCopy;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo;
import org.lwjgl.vulkan.VkPushConstantRange;
import org.lwjgl.vulkan.VkSamplerCreateInfo;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;
import org.lwjgl.vulkan.VkWriteDescriptorSet;
import org.lwjgl.vulkan.VkWriteDescriptorSetAccelerationStructureKHR;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

import static org.lwjgl.vulkan.VK10.*;

/**
 * Vulkan resources of {@link CustomRtStage}: one compute pipeline, a device-local frame UBO written by
 * {@code vkCmdUpdateBuffer} (ordered with the frame, no CPU/GPU race), a sampled copy of the world color, an RGBA16F
 * output image and one descriptor set. Images stay in GENERAL like every engine texture.
 * <p>
 * Extended contract: when the shader declares a push-constant block, the layout carries one compute range of
 * {@code maxPushConstantsSize} bytes and every dispatch pushes that range (zero-filled, front-loaded from the pack's
 * {@code rt.push} file), so any push block that fits the device is covered. Extra set-0 bindings get one buffer or
 * sampled image each; their bytes live in one host-visible staging buffer and reach the GPU only when the content
 * changed on disk, so a shader writing a storage buffer keeps its writes between edits.
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
    private final CustomRtResources resources;
    private final boolean pushConstants;
    private final RtMemory.Buf[] userBuffers;
    private final RtMemory.Img[] userTextures;
    private final int[] bufferStagingOffset, bufferBytes;
    private final int[] textureStagingOffset;
    private final boolean[] bufferDirty;
    private RtMemory.Buf staging;
    private ByteBuffer stagingView;
    private boolean texturesUploaded;
    private ByteBuffer pushData;
    private int maxPush;

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
    /** World time of day for the shader's sun (0 = noon, 0.25 = sunset, 0.75 = sunrise), set by the stage per frame. */
    float timeOfDay;
    // Optional pieces the compiled shader may not use: the scene TLAS (binding 0) and the host world volume (binding 15).
    private final boolean usesTlas, usesWorld;
    private RtMemory.Buf worldBuffer;
    private long worldVersion = -1;
    /** Studio link capture: small before/after images and a double-buffered host-visible readback. */
    boolean captureRequested;
    private RtMemory.Img capBefore, capAfter;
    private final RtMemory.Buf[] capRead = new RtMemory.Buf[2];
    private int capSlot, capW, capH;
    private boolean capReady, capInitialized;

    CustomRtExecutor(VkDevice device, VulkanGlslPackCompiler.Compiled compiled, CustomRtResources resources) {
        this.device = device;
        this.memory = new RtMemory(device);
        this.localSize = compiled.module().localSize();
        this.resources = resources;
        this.pushConstants = compiled.module().pushConstants();
        int bufferCount = resources == null ? 0 : resources.bufferSpecs().size();
        int textureCount = resources == null ? 0 : resources.textureSpecs().size();
        this.userBuffers = new RtMemory.Buf[bufferCount];
        this.userTextures = new RtMemory.Img[textureCount];
        this.bufferStagingOffset = new int[bufferCount];
        this.bufferBytes = new int[bufferCount];
        this.bufferDirty = new boolean[bufferCount];
        this.textureStagingOffset = new int[textureCount];
        this.usesTlas = uses(compiled, CustomRtStage.B_SCENE);
        this.usesWorld = uses(compiled, io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicWorld.BINDING);
        try {
            create(compiled, bufferCount, textureCount);
        } catch (RuntimeException | LinkageError e) {
            close();
            throw e;
        }
    }

    private static boolean uses(VulkanGlslPackCompiler.Compiled compiled, int binding) {
        return compiled.module().bindings().stream().anyMatch(b -> b.set() == 0 && b.binding() == binding);
    }

    /** True when the shader traces the scene TLAS (only then the stage needs the hardware RT renderer). */
    boolean usesTlas() { return usesTlas; }

    private void create(VulkanGlslPackCompiler.Compiled compiled, int bufferCount, int textureCount) {
        List<CustomRtResources.BufferSpec> bufferSpecs = resources == null ? List.of() : resources.bufferSpecs();
        List<CustomRtResources.TextureSpec> textureSpecs = resources == null ? List.of() : resources.textureSpecs();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var props = VkPhysicalDeviceProperties.calloc(stack);
            vkGetPhysicalDeviceProperties(device.getPhysicalDevice(), props);
            maxPush = props.limits().maxPushConstantsSize() & ~3;

            var handle = stack.mallocLong(1);
            check(vkCreateSampler(device, VkSamplerCreateInfo.calloc(stack).sType$Default().magFilter(VK_FILTER_NEAREST)
                .minFilter(VK_FILTER_NEAREST).mipmapMode(VK_SAMPLER_MIPMAP_MODE_NEAREST)
                .addressModeU(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE).addressModeV(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                .addressModeW(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE).maxLod(0), null, handle), "sampler");
            sampler = handle.get(0);

            // A shader that never traces gets no acceleration-structure binding at all, so it also runs on devices and
            // renderers without ray tracing (raster, voxel compute).
            var bindings = VkDescriptorSetLayoutBinding.calloc((usesTlas ? 5 : 4) + (usesWorld ? 1 : 0) + bufferCount + textureCount, stack);
            int slot = 0;
            if (usesTlas) bindings.get(slot++).binding(CustomRtStage.B_SCENE)
                .descriptorType(KHRAccelerationStructure.VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR)
                .descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            bindings.get(slot++).binding(CustomRtStage.B_FRAME).descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            bindings.get(slot++).binding(CustomRtStage.B_COLOR).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            bindings.get(slot++).binding(CustomRtStage.B_OUTPUT).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            bindings.get(slot++).binding(CustomRtStage.B_DEPTH).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            if (usesWorld) bindings.get(slot++).binding(io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicWorld.BINDING)
                .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            for (CustomRtResources.BufferSpec spec : bufferSpecs) {
                bindings.get(slot++).binding(spec.binding()).descriptorType(bufferType(spec.kind()))
                    .descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            }
            for (CustomRtResources.TextureSpec spec : textureSpecs) {
                bindings.get(slot++).binding(spec.binding()).descriptorType(textureType(spec.kind()))
                    .descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            }
            check(vkCreateDescriptorSetLayout(device, VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default().pBindings(bindings), null, handle), "set layout");
            setLayout = handle.get(0);
            var layoutInfo = VkPipelineLayoutCreateInfo.calloc(stack).sType$Default().pSetLayouts(stack.longs(setLayout));
            if (pushConstants) {
                var range = VkPushConstantRange.calloc(1, stack);
                range.get(0).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT).offset(0).size(maxPush);
                layoutInfo.pPushConstantRanges(range);
            }
            check(vkCreatePipelineLayout(device, layoutInfo, null, handle), "pipeline layout");
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

            int uniformBuffers = 1;
            int storageBuffers = usesWorld ? 1 : 0;
            int combinedImages = 2;
            int sampledImages = 0;
            for (CustomRtResources.BufferSpec spec : bufferSpecs)
                if (spec.kind() == SpirvReflection.DescriptorKind.UNIFORM_BUFFER) uniformBuffers++;
                else storageBuffers++; // storage buffers need pool room too (missing before: strict drivers refused the set)
            for (CustomRtResources.TextureSpec spec : textureSpecs)
                if (spec.kind() == SpirvReflection.DescriptorKind.SAMPLED_IMAGE) sampledImages++;
                else combinedImages++;
            int types = 2 + (usesTlas ? 1 : 0) + (sampledImages > 0 ? 1 : 0) + (storageBuffers > 0 ? 1 : 0);
            var sizes = VkDescriptorPoolSize.calloc(types, stack);
            int t = 0;
            if (usesTlas) sizes.get(t++).type(KHRAccelerationStructure.VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR).descriptorCount(1);
            sizes.get(t++).type(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).descriptorCount(uniformBuffers);
            sizes.get(t++).type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(combinedImages);
            if (sampledImages > 0) sizes.get(t++).type(VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE).descriptorCount(sampledImages);
            if (storageBuffers > 0) sizes.get(t++).type(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(storageBuffers);
            check(vkCreateDescriptorPool(device, VkDescriptorPoolCreateInfo.calloc(stack).sType$Default().maxSets(1).pPoolSizes(sizes),
                null, handle), "descriptor pool");
            pool = handle.get(0);
            check(vkAllocateDescriptorSets(device, VkDescriptorSetAllocateInfo.calloc(stack).sType$Default().descriptorPool(pool)
                .pSetLayouts(stack.longs(setLayout)), handle), "descriptor set");
            set = handle.get(0);
            frameUbo = memory.createBuffer("customrt_frame", FRAME_UBO_BYTES,
                VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT, false);
            if (usesWorld) worldBuffer = memory.createBuffer("customrt_world",
                io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicWorld.BYTES,
                VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT, false);
        }
        createUserResources();
    }

    /** Maps a reflected extra-binding kind to its descriptor type; the validator refuses anything else. */
    private static int bufferType(SpirvReflection.DescriptorKind kind) {
        return kind == SpirvReflection.DescriptorKind.STORAGE_BUFFER
            ? VK_DESCRIPTOR_TYPE_STORAGE_BUFFER : VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER;
    }

    private static int textureType(SpirvReflection.DescriptorKind kind) {
        return kind == SpirvReflection.DescriptorKind.SAMPLED_IMAGE
            ? VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE : VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    }

    /** Allocates user hardware against real device limits and fills the staging buffer with the initial bytes. */
    private void createUserResources() {
        if (resources == null) return;
        long stagingBytes = 0;
        int bufferSlot = 0;
        for (CustomRtResources.BufferSpec spec : resources.bufferSpecs()) {
            int usage = VK_BUFFER_USAGE_TRANSFER_DST_BIT
                | (spec.kind() == SpirvReflection.DescriptorKind.STORAGE_BUFFER ? VK_BUFFER_USAGE_STORAGE_BUFFER_BIT : VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT);
            long aligned = (spec.size() + 3) & ~3L;
            userBuffers[bufferSlot] = memory.createBuffer("customrt_user_buffer" + spec.binding(), aligned, usage, false);
            bufferStagingOffset[bufferSlot] = (int) stagingBytes;
            bufferBytes[bufferSlot] = (int) spec.size();
            bufferDirty[bufferSlot] = true;
            stagingBytes += aligned;
            bufferSlot++;
        }
        for (CustomRtResources.TextureSpec spec : resources.textureSpecs()) {
            userTextures[resources.textureSpecs().indexOf(spec)] = memory.createImage("customrt_user_texture" + spec.binding(),
                spec.width(), spec.height(), VK_FORMAT_R8G8B8A8_UNORM, VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT);
            int index = resources.textureSpecs().indexOf(spec);
            textureStagingOffset[index] = (int) stagingBytes;
            stagingBytes += spec.width() * spec.height() * 4L;
        }
        staging = memory.createBuffer("customrt_staging", Math.max(1, stagingBytes), VK_BUFFER_USAGE_TRANSFER_SRC_BIT, true);
        copyInitialContent();
    }

    private void copyInitialContent() {
        for (int i = 0; i < userBuffers.length; i++) {
            copyIntoStaging(bufferStagingOffset[i], resources.bufferContent(i));
            bufferDirty[i] = true;
        }
        for (int i = 0; i < userTextures.length; i++) {
            copyIntoStaging(textureStagingOffset[i], resources.textureRgba(i));
        }
        pushRefresh();
    }

    private void copyIntoStaging(int offset, byte[] data) {
        ByteBuffer target = stagingView();
        target.position(offset);
        target.put(data);
    }

    private ByteBuffer stagingView() {
        if (stagingView == null) {
            stagingView = org.lwjgl.system.MemoryUtil.memByteBuffer(staging.mapped, (int) staging.size).order(ByteOrder.nativeOrder());
        }
        return stagingView;
    }

    /**
     * Copies fresh user buffer bytes (and re-derives the push data) after {@link CustomRtResources#refresh} saw a
     * change. Runs with no command buffer open: the caller submitted queued work and this waits for the device, so an
     * in-flight copy of an earlier frame never races the CPU write.
     */
    void stageUser(CustomRtResources res) {
        int wait = vkDeviceWaitIdle(device);
        if (wait != VK_SUCCESS) throw new IllegalStateException("custom RT wait failed: VkResult " + wait);
        for (int i = 0; i < userBuffers.length; i++) {
            byte[] data = res.bufferContent(i);
            if (data.length > bufferBytes[i])
                throw new IllegalStateException("custom RT user buffer " + i + " grew to " + data.length
                    + " B but was created for " + bufferBytes[i] + " B; restart to pick up the larger file");
            copyIntoStaging(bufferStagingOffset[i], data);
            bufferBytes[i] = data.length;
            bufferDirty[i] = true;
        }
        // Textures are static: only their bootstrap content is uploaded once.
        pushRefresh();
    }

    private void pushRefresh() {
        if (!pushConstants) return;
        int capacity = Math.max(4, maxPush);
        if (pushData == null) pushData = org.lwjgl.system.MemoryUtil.memCalloc(capacity).order(ByteOrder.nativeOrder());
        byte[] content = resources.push(maxPush);
        if (content != null) {
            pushData.position(0);
            pushData.put(content, 0, Math.min(content.length, capacity));
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
                VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT);
            destroyCapture();
            output = memory.createImage("customrt_output", color.width(), color.height(), OUTPUT_FORMAT,
                VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT);
            imagesInitialized = false;
            width = color.width();
            height = color.height();
            colorFormat = color.format();
        }
        writeDescriptors();
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

    private void writeDescriptors() {
        if (resources == null) {
            writeFixedDescriptors();
        } else {
            writeAllDescriptors();
        }
        writeWorldDescriptor();
    }

    private void writeWorldDescriptor() {
        if (!usesWorld) return;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var info = VkDescriptorBufferInfo.calloc(1, stack);
            info.get(0).buffer(worldBuffer.handle).offset(0).range(io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicWorld.BYTES);
            var writes = VkWriteDescriptorSet.calloc(1, stack);
            writes.get(0).sType$Default().dstSet(set).dstBinding(io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicWorld.BINDING)
                .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1).pBufferInfo(info);
            vkUpdateDescriptorSets(device, writes, null);
        }
    }

    /** Drops the acceleration-structure write when the shader has no TLAS binding (writes index 0 is that write). */
    private static VkWriteDescriptorSet.Buffer withoutTlas(VkWriteDescriptorSet.Buffer writes, boolean keep) {
        return keep ? writes : writes.slice(1, writes.remaining() - 1);
    }

    private void writeAllDescriptors() {
        List<CustomRtResources.BufferSpec> bufferSpecs = resources.bufferSpecs();
        List<CustomRtResources.TextureSpec> textureSpecs = resources.textureSpecs();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var bufferInfos = VkDescriptorBufferInfo.calloc(1 + bufferSpecs.size(), stack);
            bufferInfos.get(0).buffer(frameUbo.handle).offset(0).range(FRAME_UBO_BYTES);
            for (int i = 0; i < bufferSpecs.size(); i++) {
                bufferInfos.get(1 + i).buffer(userBuffers[i].handle).offset(0).range(bufferBytes[i]);
            }
            var imageInfos = VkDescriptorImageInfo.calloc(3 + textureSpecs.size(), stack);
            imageInfos.get(0).sampler(sampler).imageView(input.view).imageLayout(VK_IMAGE_LAYOUT_GENERAL);
            imageInfos.get(1).imageView(output.view).imageLayout(VK_IMAGE_LAYOUT_GENERAL);
            // Without a depth guide the slot holds the color copy; BedrayFrame.depthInfo.z = 0 tells the shader not to read it.
            imageInfos.get(2).sampler(sampler).imageView(depthBound ? boundDepthView : input.view).imageLayout(VK_IMAGE_LAYOUT_GENERAL);
            for (int i = 0; i < textureSpecs.size(); i++) {
                imageInfos.get(3 + i).sampler(sampler).imageView(userTextures[i].view).imageLayout(VK_IMAGE_LAYOUT_GENERAL);
            }

            var writes = VkWriteDescriptorSet.calloc(5 + bufferSpecs.size() + textureSpecs.size(), stack);
            var as = VkWriteDescriptorSetAccelerationStructureKHR.calloc(stack).sType$Default()
                .pAccelerationStructures(stack.longs(boundTlas));
            writes.get(0).sType$Default().pNext(as.address()).dstSet(set).dstBinding(CustomRtStage.B_SCENE)
                .descriptorType(KHRAccelerationStructure.VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR).descriptorCount(1);
            writes.get(1).sType$Default().dstSet(set).dstBinding(CustomRtStage.B_FRAME)
                .descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).descriptorCount(1).pBufferInfo(bufferInfos.slice(0, 1));
            writes.get(2).sType$Default().dstSet(set).dstBinding(CustomRtStage.B_COLOR)
                .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1).pImageInfo(imageInfos.slice(0, 1));
            writes.get(3).sType$Default().dstSet(set).dstBinding(CustomRtStage.B_OUTPUT)
                .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(1).pImageInfo(imageInfos.slice(1, 1));
            writes.get(4).sType$Default().dstSet(set).dstBinding(CustomRtStage.B_DEPTH)
                .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1).pImageInfo(imageInfos.slice(2, 1));
            int w = 5;
            for (int i = 0; i < bufferSpecs.size(); i++) {
                writes.get(w++).sType$Default().dstSet(set).dstBinding(bufferSpecs.get(i).binding())
                    .descriptorType(bufferType(bufferSpecs.get(i).kind())).descriptorCount(1).pBufferInfo(bufferInfos.slice(1 + i, 1));
            }
            for (int i = 0; i < textureSpecs.size(); i++) {
                writes.get(w++).sType$Default().dstSet(set).dstBinding(textureSpecs.get(i).binding())
                    .descriptorType(textureType(textureSpecs.get(i).kind())).descriptorCount(1).pImageInfo(imageInfos.slice(3 + i, 1));
            }
            vkUpdateDescriptorSets(device, withoutTlas(writes, usesTlas), null);
        }
    }

    /** The pre-extension descriptor write, kept for a shader with no user resources. */
    private void writeFixedDescriptors() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var imageInfos = VkDescriptorImageInfo.calloc(3, stack);
            imageInfos.get(0).sampler(sampler).imageView(input.view).imageLayout(VK_IMAGE_LAYOUT_GENERAL);
            imageInfos.get(1).imageView(output.view).imageLayout(VK_IMAGE_LAYOUT_GENERAL);
            imageInfos.get(2).sampler(sampler).imageView(depthBound ? boundDepthView : input.view).imageLayout(VK_IMAGE_LAYOUT_GENERAL);
            var writes = VkWriteDescriptorSet.calloc(5, stack);
            var as = VkWriteDescriptorSetAccelerationStructureKHR.calloc(stack).sType$Default()
                .pAccelerationStructures(stack.longs(boundTlas));
            writes.get(0).sType$Default().pNext(as.address()).dstSet(set).dstBinding(CustomRtStage.B_SCENE)
                .descriptorType(KHRAccelerationStructure.VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR).descriptorCount(1);
            var frameBufferInfo = VkDescriptorBufferInfo.calloc(1, stack);
            frameBufferInfo.get(0).buffer(frameUbo.handle).offset(0).range(FRAME_UBO_BYTES);
            writes.get(1).sType$Default().dstSet(set).dstBinding(CustomRtStage.B_FRAME)
                .descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).descriptorCount(1).pBufferInfo(frameBufferInfo);
            writes.get(2).sType$Default().dstSet(set).dstBinding(CustomRtStage.B_COLOR)
                .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1).pImageInfo(imageInfos.slice(0, 1));
            writes.get(3).sType$Default().dstSet(set).dstBinding(CustomRtStage.B_OUTPUT)
                .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(1).pImageInfo(imageInfos.slice(1, 1));
            writes.get(4).sType$Default().dstSet(set).dstBinding(CustomRtStage.B_DEPTH)
                .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1).pImageInfo(imageInfos.slice(2, 1));
            vkUpdateDescriptorSets(device, withoutTlas(writes, usesTlas), null);
        }
    }

    /** Records UBO update, user uploads, color copy, dispatch and the blit back into the world color. */
    void record(VkCommandBuffer cmd, WorldFrame frame) { record(cmd, frame, null); }

    /** Header every frame (the camera moves), cells only when the volume changed, in 64 KiB update chunks. */
    private void recordWorldUpload(VkCommandBuffer cmd, WorldFrame.Camera camera,
                                   io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicWorld.Volume volume) {
        if (!usesWorld) return;
        int cellsBytes = io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicWorld.CELLS * 2;
        int header = io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicWorld.HEADER_BYTES;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer h = stack.calloc(header);
            if (volume != null) volume.writeHeader(h, camera.x(), camera.y(), camera.z());
            vkCmdUpdateBuffer(cmd, worldBuffer.handle, 0, h); // zeros (invalid) when no volume yet
        }
        if (volume != null && volume.version() != worldVersion) {
            ByteBuffer cells = org.lwjgl.system.MemoryUtil.memAlloc(cellsBytes);
            try {
                volume.writeCells(cells, 0);
                for (int off = 0; off < cellsBytes; off += 65536) {
                    int len = Math.min(65536, cellsBytes - off);
                    cells.limit(off + len).position(off);
                    vkCmdUpdateBuffer(cmd, worldBuffer.handle, header + off, cells.slice());
                    cells.clear();
                }
            } finally {
                org.lwjgl.system.MemoryUtil.memFree(cells);
            }
            worldVersion = volume.version();
        }
        VkBarriers.fullMemoryBarrier(cmd);
    }

    void record(VkCommandBuffer cmd, WorldFrame frame,
                io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicWorld.Volume volume) {
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

            recordUserUploads(cmd);
            recordWorldUpload(cmd, frame.camera(), volume);

            var copy = VkImageCopy.calloc(1, stack);
            copy.get(0).srcSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
            copy.get(0).dstSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
            copy.get(0).extent().set(color.width(), color.height(), 1);
            vkCmdCopyImage(cmd, color.image(), VK_IMAGE_LAYOUT_GENERAL, input.image, VK_IMAGE_LAYOUT_GENERAL, copy);
            VkBarriers.fullMemoryBarrier(cmd);

            vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
            vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, pipelineLayout, 0, stack.longs(set), null);
            if (pushData != null) {
                vkCmdPushConstants(cmd, pipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT, 0, pushData);
            }
            vkCmdDispatch(cmd, (color.width() + localSize[0] - 1) / localSize[0], (color.height() + localSize[1] - 1) / localSize[1], 1);
            VkBarriers.fullMemoryBarrier(cmd);

            var blit = VkImageBlit.calloc(1, stack);
            blit.get(0).srcSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
            blit.get(0).dstSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
            blit.get(0).srcOffsets(1).set(color.width(), color.height(), 1);
            blit.get(0).dstOffsets(1).set(color.width(), color.height(), 1);
            vkCmdBlitImage(cmd, output.image, VK_IMAGE_LAYOUT_GENERAL, color.image(), VK_IMAGE_LAYOUT_GENERAL, blit, VK_FILTER_NEAREST);
            if (captureRequested) recordCapture(cmd, stack);
        }
    }

    /**
     * Shrinks the before (input) and after (output) images into RGBA8, flipped to top-down rows, and copies them into
     * this capture's readback slot. The previous slot - written at least one capture interval ago, so long complete -
     * is handed to the studio link first; no wait on the GPU.
     */
    private void recordCapture(VkCommandBuffer cmd, MemoryStack stack) {
        captureRequested = false;
        if (capBefore == null) {
            capW = Math.min(StudioLink.MAX_WIDTH, width);
            capH = Math.max(1, Math.round(capW * (height / (float) width)));
            int usage = VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
            capBefore = memory.createImage("customrt_cap_before", capW, capH, VK_FORMAT_R8G8B8A8_UNORM, usage);
            capAfter = memory.createImage("customrt_cap_after", capW, capH, VK_FORMAT_R8G8B8A8_UNORM, usage);
            for (int i = 0; i < 2; i++)
                capRead[i] = memory.createBuffer("customrt_cap_read" + i, (long) capW * capH * 8, VK_BUFFER_USAGE_TRANSFER_DST_BIT, true);
            capInitialized = false;
            capReady = false;
        }
        if (capReady) {
            RtMemory.Buf prev = capRead[capSlot ^ 1];
            StudioLink.deliver(capW, capH, org.lwjgl.system.MemoryUtil.memByteBuffer(prev.mapped, (int) prev.size));
        }
        if (!capInitialized) {
            VkBarriers.transition(cmd, capBefore.image, VK_IMAGE_ASPECT_COLOR_BIT, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_GENERAL);
            VkBarriers.transition(cmd, capAfter.image, VK_IMAGE_ASPECT_COLOR_BIT, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_GENERAL);
            capInitialized = true;
        }
        VkBarriers.fullMemoryBarrier(cmd);
        for (int i = 0; i < 2; i++) {
            var b = VkImageBlit.calloc(1, stack);
            b.get(0).srcSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
            b.get(0).dstSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
            b.get(0).srcOffsets(1).set(width, height, 1);
            // the world target is bottom-up (GL origin): flip into top-down rows for the PNG
            b.get(0).dstOffsets(0).set(0, capH, 0);
            b.get(0).dstOffsets(1).set(capW, 0, 1);
            vkCmdBlitImage(cmd, (i == 0 ? input : output).image, VK_IMAGE_LAYOUT_GENERAL, (i == 0 ? capBefore : capAfter).image,
                VK_IMAGE_LAYOUT_GENERAL, b, VK_FILTER_NEAREST); // NEAREST: every blittable format supports it
        }
        VkBarriers.fullMemoryBarrier(cmd);
        RtMemory.Buf slot = capRead[capSlot];
        for (int i = 0; i < 2; i++) {
            var region = VkBufferImageCopy.calloc(1, stack);
            region.get(0).bufferOffset((long) i * capW * capH * 4);
            region.get(0).imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
            region.get(0).imageExtent().set(capW, capH, 1);
            vkCmdCopyImageToBuffer(cmd, (i == 0 ? capBefore : capAfter).image, VK_IMAGE_LAYOUT_GENERAL, slot.handle, region);
        }
        VkBarriers.fullMemoryBarrier(cmd);
        capSlot ^= 1;
        capReady = true;
    }

    private void destroyCapture() {
        if (capBefore != null) memory.destroy(capBefore);
        if (capAfter != null) memory.destroy(capAfter);
        for (int i = 0; i < 2; i++) if (capRead[i] != null) memory.destroy(capRead[i]);
        capBefore = capAfter = null;
        capRead[0] = capRead[1] = null;
        capReady = false;
    }

    /** Staging-to-buffer copies for changed user buffers, then the one-time texture upload from the same staging. */
    private void recordUserUploads(VkCommandBuffer cmd) {
        boolean copied = false;
        for (int i = 0; i < userBuffers.length; i++) {
            if (!bufferDirty[i]) continue;
            try (MemoryStack stack = MemoryStack.stackPush()) {
                var copy = VkBufferCopy.calloc(1, stack);
                copy.srcOffset(bufferStagingOffset[i]).dstOffset(0).size(bufferBytes[i]);
                vkCmdCopyBuffer(cmd, staging.handle, userBuffers[i].handle, copy);
            }
            bufferDirty[i] = false;
            copied = true;
        }
        if (copied) VkBarriers.fullMemoryBarrier(cmd);
        for (int i = 0; i < userTextures.length; i++) {
            if (texturesUploaded) break;
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkBarriers.transition(cmd, userTextures[i].image, VK_IMAGE_ASPECT_COLOR_BIT, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_GENERAL);
                var copy = VkBufferImageCopy.calloc(1, stack);
                copy.get(0).bufferOffset(textureStagingOffset[i]);
                copy.get(0).imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
                copy.get(0).imageExtent().set(userTextures[i].width, userTextures[i].height, 1);
                vkCmdCopyBufferToImage(cmd, staging.handle, userTextures[i].image, VK_IMAGE_LAYOUT_GENERAL, copy);
            }
        }
        if (!texturesUploaded && userTextures.length > 0) {
            VkBarriers.fullMemoryBarrier(cmd);
            texturesUploaded = true;
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
        b.putFloat(288, timeCounter).putFloat(292, frameTime).putFloat(296, frameCounter).putFloat(300, timeOfDay);
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
        for (int i = 0; i < userBuffers.length; i++) {
            if (userBuffers[i] != null) {
                memory.destroy(userBuffers[i]);
                userBuffers[i] = null;
            }
        }
        for (int i = 0; i < userTextures.length; i++) {
            if (userTextures[i] != null) {
                memory.destroy(userTextures[i]);
                userTextures[i] = null;
            }
        }
        if (staging != null) memory.destroy(staging);
        staging = null;
        stagingView = null;
        if (pushData != null) {
            org.lwjgl.system.MemoryUtil.memFree(pushData);
            pushData = null;
        }
        if (input != null) memory.destroy(input);
        if (output != null) memory.destroy(output);
        if (frameUbo != null) memory.destroy(frameUbo);
        if (worldBuffer != null) memory.destroy(worldBuffer);
        worldBuffer = null;
        destroyCapture();
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
