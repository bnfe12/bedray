package io.github.andrexarlay.vulkancompat.rt.sodium;

import io.github.andrexarlay.vulkancompat.rt.world.RtFailure;
import io.github.andrexarlay.vulkancompat.rt.world.RtShaderCompiler;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkComputePipelineCreateInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo;
import org.lwjgl.vulkan.VkPushConstantRange;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;

/**
 * Compute pipeline of {@code sodium_dequantize.comp}: decodes the raw Sodium quad words of one section straight into
 * the geometry arena block (module 2 of the refactor). Descriptor-free by design - the shader reaches everything
 * through buffer device addresses in the push constants, so the layout is push constants only.
 *
 * <p>Push constants (32 B, matches the GLSL block): uvec2 rawAddr, uvec2 arenaAddr, uvec2 flagsAddr, uint quadCount,
 * uint pad0. {@code flagsAddr} may be 0 (no foliage tagging for the section). Render thread only.
 */
public final class SodiumDequantize {
    public static final int PUSH_BYTES = 32;
    private static final int LOCAL_SIZE = 64;

    private final VkDevice device;
    private long shaderModule;
    private long pipelineLayout;
    private long pipeline;

    public SodiumDequantize(VkDevice device) {
        this.device = device;
        ByteBuffer spirv = RtShaderCompiler.compileResource("/shaders/sodium_dequantize.comp", "sodium_dequantize.comp");
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkShaderModuleCreateInfo smci = VkShaderModuleCreateInfo.calloc(stack)
                .sType$Default()
                .pCode(spirv);
            LongBuffer pModule = stack.mallocLong(1);
            RtFailure.check(VK10.vkCreateShaderModule(device, smci, null, pModule), "dequant_shader");
            this.shaderModule = pModule.get(0);

            VkPushConstantRange.Buffer range = VkPushConstantRange.calloc(1, stack)
                .stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT)
                .offset(0)
                .size(PUSH_BYTES);
            VkPipelineLayoutCreateInfo lci = VkPipelineLayoutCreateInfo.calloc(stack)
                .sType$Default()
                .pPushConstantRanges(range);
            LongBuffer pLayout = stack.mallocLong(1);
            RtFailure.check(VK10.vkCreatePipelineLayout(device, lci, null, pLayout), "dequant_layout");
            this.pipelineLayout = pLayout.get(0);

            VkComputePipelineCreateInfo.Buffer pci = VkComputePipelineCreateInfo.calloc(1, stack)
                .sType$Default()
                .stage(s -> s.sType$Default()
                    .stage(VK10.VK_SHADER_STAGE_COMPUTE_BIT)
                    .module(shaderModule)
                    .pName(stack.UTF8("main")))
                .layout(pipelineLayout);
            LongBuffer pPipeline = stack.mallocLong(1);
            RtFailure.check(VK10.vkCreateComputePipelines(device, 0L, pci, null, pPipeline), "dequant_pipeline");
            this.pipeline = pPipeline.get(0);
        }
    }

    /**
     * Records the dequantize dispatch of one section: {@code quadCount} quads from the raw capture at {@code rawAddr}
     * into the arena block at {@code arenaAddr} ([positions][uvs][colours][light], 112 B per quad). {@code flagsAddr}
     * is the per-quad foliage OR bits or 0. The caller barriers the writes against the acceleration structure build.
     */
    public void dispatch(VkCommandBuffer cmd, long rawAddr, long arenaAddr, long flagsAddr, int quadCount) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VK10.vkCmdBindPipeline(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
            ByteBuffer pc = stack.malloc(PUSH_BYTES);
            pc.putLong(0, rawAddr);
            pc.putLong(8, arenaAddr);
            pc.putLong(16, flagsAddr);
            pc.putInt(24, quadCount);
            pc.putInt(28, 0);
            VK10.vkCmdPushConstants(cmd, pipelineLayout, VK10.VK_SHADER_STAGE_COMPUTE_BIT, 0, pc);
            VK10.vkCmdDispatch(cmd, (quadCount + LOCAL_SIZE - 1) / LOCAL_SIZE, 1, 1);
        }
    }

    public void destroy() {
        if (pipeline != 0L) {
            VK10.vkDestroyPipeline(device, pipeline, null);
            pipeline = 0L;
        }
        if (pipelineLayout != 0L) {
            VK10.vkDestroyPipelineLayout(device, pipelineLayout, null);
            pipelineLayout = 0L;
        }
        if (shaderModule != 0L) {
            VK10.vkDestroyShaderModule(device, shaderModule, null);
            shaderModule = 0L;
        }
    }
}
