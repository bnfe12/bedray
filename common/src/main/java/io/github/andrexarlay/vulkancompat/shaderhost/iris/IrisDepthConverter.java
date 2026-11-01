package io.github.andrexarlay.vulkancompat.shaderhost.iris;

import io.github.andrexarlay.vulkancompat.rt.core.BedrayCoreLayouts;
import io.github.andrexarlay.vulkancompat.rt.slang.SlangPass;
import io.github.andrexarlay.vulkancompat.rt.slang.SlangPassDriverGate;
import io.github.andrexarlay.vulkancompat.rt.slang.SlangPassManifest;
import io.github.andrexarlay.vulkancompat.rt.world.RtMemory;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.VkBarriers;
import org.joml.Matrix4f;
import io.github.andrexarlay.vulkancompat.rtx.frame.WorldFrame;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import static org.lwjgl.vulkan.VK10.*;

/** Converts the host's reverse-Z raster depth into Iris/OpenGL 0-near/1-far depth.
 * The pass is the AOT {@code iris_depth} artifact; the pipeline belongs to the bridge, which is not the renderer.
 * Resources belong to the Bedray device; the bridge flushes/waits before closing them.
 */
final class IrisDepthConverter implements AutoCloseable {
    /** {@code IrisDepthPush}: float4x4 + four float4. */
    static final int PUSH_BYTES = 128;
    static final int[] THREADS = {8, 8, 1};
    private final VkDevice device;
    private final RtMemory memory;
    private RtMemory.Img output;
    private long sampler, setLayout, pipelineLayout, pipeline, pool, set;
    private boolean initialized;

    /** The bridge writes a fixed push block and divides by a fixed group size, so the artifact has to agree. */
    static void requireManifest(SlangPassManifest manifest) {
        if (manifest.pushConstantBytes() != PUSH_BYTES) {
            throw new IllegalArgumentException("iris_depth: push constants are " + manifest.pushConstantBytes()
                + " bytes, the bridge writes " + PUSH_BYTES);
        }
        if (!Arrays.equals(manifest.threadGroupSize(), THREADS)) {
            throw new IllegalArgumentException("iris_depth: threads " + Arrays.toString(manifest.threadGroupSize())
                + ", the bridge dispatches " + Arrays.toString(THREADS));
        }
        if (manifest.bindings().size() != 2) {
            throw new IllegalArgumentException("iris_depth: " + manifest.bindings().size()
                + " bindings, the bridge sets 2");
        }
        SlangPassManifest.Binding depth = manifest.binding("rasterDepth");
        if (depth == null || depth.binding() != 0
            || depth.descriptorType() != BedrayCoreLayouts.DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER) {
            throw new IllegalArgumentException("iris_depth: binding 0 must be the combined rasterDepth sampler");
        }
        SlangPassManifest.Binding out = manifest.binding("irisDepth");
        if (out == null || out.binding() != 1
            || out.descriptorType() != BedrayCoreLayouts.DESCRIPTOR_TYPE_STORAGE_IMAGE) {
            throw new IllegalArgumentException("iris_depth: binding 1 must be the irisDepth storage image");
        }
    }
    IrisDepthConverter(VkDevice device, int width, int height, long sourceView) {
        this.device=device; memory=new RtMemory(device);
        try { create(width,height,sourceView); }
        catch (Exception | LinkageError error) { close(); throw new IllegalStateException("Iris depth conversion initialization failed",error); }
    }
    private void create(int width,int height,long sourceView) throws Exception {
        try (MemoryStack stack=MemoryStack.stackPush()) {
            var format=VkFormatProperties.calloc(stack);
            vkGetPhysicalDeviceFormatProperties(device.getPhysicalDevice(),VK_FORMAT_R32_SFLOAT,format);
            int required=VK_FORMAT_FEATURE_STORAGE_IMAGE_BIT | VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT;
            if ((format.optimalTilingFeatures() & required)!=required) throw new IllegalStateException("R32F depth conversion unsupported");
            output=memory.createImage("iris_gl_depth",width,height,VK_FORMAT_R32_SFLOAT,
                VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT);
            var handle=stack.mallocLong(1);
            check(vkCreateSampler(device,VkSamplerCreateInfo.calloc(stack).sType$Default().magFilter(VK_FILTER_NEAREST)
                .minFilter(VK_FILTER_NEAREST).mipmapMode(VK_SAMPLER_MIPMAP_MODE_NEAREST)
                .addressModeU(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE).addressModeV(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                .addressModeW(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE).maxLod(0),null,handle)); sampler=handle.get(0);
            var bindings=VkDescriptorSetLayoutBinding.calloc(2,stack);
            bindings.get(0).binding(0).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            bindings.get(1).binding(1).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            check(vkCreateDescriptorSetLayout(device,VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default().pBindings(bindings),null,handle)); setLayout=handle.get(0);
            var range=VkPushConstantRange.calloc(1,stack);range.get(0).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT).offset(0).size(128);
            check(vkCreatePipelineLayout(device,VkPipelineLayoutCreateInfo.calloc(stack).sType$Default().pSetLayouts(stack.longs(setLayout)).pPushConstantRanges(range),null,handle));pipelineLayout=handle.get(0);
            SlangPass pass = SlangPass.load("iris_depth").orElseThrow(() -> new IllegalStateException(
                "iris_depth is not in this build; the Iris depth bridge has no runtime GLSL fallback"));
            requireManifest(pass.manifest());
            String blocker = SlangPassDriverGate.blocker("iris_depth", pass.spirv());
            if (blocker != null) throw new IllegalStateException(blocker);
            var bytecode = ByteBuffer.wrap(pass.spirv()).order(ByteOrder.nativeOrder());
            check(vkCreateShaderModule(device,VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(bytecode),null,handle));
            long shader=handle.get(0);
            try {
                var info=VkComputePipelineCreateInfo.calloc(1,stack);info.get(0).sType$Default().layout(pipelineLayout);
                info.get(0).stage().sType$Default().stage(VK_SHADER_STAGE_COMPUTE_BIT).module(shader).pName(stack.UTF8("main"));
                check(vkCreateComputePipelines(device,VK_NULL_HANDLE,info,null,handle));pipeline=handle.get(0);
            } finally {vkDestroyShaderModule(device,shader,null);}
            var sizes=VkDescriptorPoolSize.calloc(2,stack);
            sizes.get(0).type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1);
            sizes.get(1).type(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(1);
            check(vkCreateDescriptorPool(device,VkDescriptorPoolCreateInfo.calloc(stack).sType$Default().maxSets(1).pPoolSizes(sizes),null,handle));pool=handle.get(0);
            check(vkAllocateDescriptorSets(device,VkDescriptorSetAllocateInfo.calloc(stack).sType$Default().descriptorPool(pool).pSetLayouts(stack.longs(setLayout)),handle));set=handle.get(0);
            var images=VkDescriptorImageInfo.calloc(2,stack);
            images.get(0).sampler(sampler).imageView(sourceView).imageLayout(VK_IMAGE_LAYOUT_GENERAL);
            images.get(1).imageView(output.view).imageLayout(VK_IMAGE_LAYOUT_GENERAL);
            var writes=VkWriteDescriptorSet.calloc(2,stack);
            writes.get(0).sType$Default().dstSet(set).dstBinding(0).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1).pImageInfo(images.slice(0,1));
            writes.get(1).sType$Default().dstSet(set).dstBinding(1).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(1).pImageInfo(images.slice(1,1));
            vkUpdateDescriptorSets(device,writes,null);
        }
    }
    long view() {return output.view;}
    void record(VkCommandBuffer command,WorldFrame.Camera camera,WorldFrame.DepthEncoding encoding) {
        try(MemoryStack stack=MemoryStack.stackPush()) {
            if(!initialized)VkBarriers.transition(command,output.image,VK_IMAGE_ASPECT_COLOR_BIT,VK_IMAGE_LAYOUT_UNDEFINED,VK_IMAGE_LAYOUT_GENERAL);
            VkBarriers.fullMemoryBarrier(command);
            var push=stack.calloc(128).order(ByteOrder.nativeOrder());
            camera.inverseProjection().get(0,push);
            Matrix4f inverseView=camera.inverseModelView();
            push.putFloat(64,inverseView.m00()).putFloat(68,inverseView.m01()).putFloat(72,inverseView.m02());
            push.putFloat(80,inverseView.m10()).putFloat(84,inverseView.m11()).putFloat(88,inverseView.m12());
            push.putFloat(96,inverseView.m20()).putFloat(100,inverseView.m21()).putFloat(104,inverseView.m22());
            push.putFloat(76,switch (encoding) {
                case REVERSE_DEVICE_Z -> 0; case OPENGL_WINDOW_Z -> 1; case LINEAR_VIEW_Z -> 2; case RAY_DISTANCE -> 3;
            });
            push.putFloat(112,camera.near()).putFloat(116,camera.far()).putFloat(120,camera.depthScale()).putFloat(124,camera.depthBias());
            vkCmdBindPipeline(command,VK_PIPELINE_BIND_POINT_COMPUTE,pipeline);
            vkCmdBindDescriptorSets(command,VK_PIPELINE_BIND_POINT_COMPUTE,pipelineLayout,0,stack.longs(set),null);
            vkCmdPushConstants(command,pipelineLayout,VK_SHADER_STAGE_COMPUTE_BIT,0,push);
            vkCmdDispatch(command,(output.width+7)/8,(output.height+7)/8,1);
            VkBarriers.fullMemoryBarrier(command);initialized=true;
        }
    }
    private static void check(int result) {if(result!=VK_SUCCESS)throw new IllegalStateException("Iris depth Vulkan result="+result);}
    @Override public void close() {
        if(pool!=0)vkDestroyDescriptorPool(device,pool,null);
        if(pipeline!=0)vkDestroyPipeline(device,pipeline,null);
        if(pipelineLayout!=0)vkDestroyPipelineLayout(device,pipelineLayout,null);
        if(setLayout!=0)vkDestroyDescriptorSetLayout(device,setLayout,null);
        if(sampler!=0)vkDestroySampler(device,sampler,null);
        if(output!=null)memory.destroy(output);
        pool=pipeline=pipelineLayout=setLayout=sampler=set=0;output=null;
    }
}
