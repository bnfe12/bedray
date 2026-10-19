package io.github.andrexarlay.vulkanbridge.glbridge;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.vulkan.VulkanConst;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTextureView;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.shaderc.Shaderc;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK12.*;

/**
 * Composites the GL image over the engine's main render target, inside the engine's own frame submission.
 * <p>
 * Embedding (all signatures from javap -p on libs/minecraft-26.3-client.jar, decompiled bodies in <local decompiled sources>):
 * the engine encoder has public {@code waitSemaphore(long semaphore, long value, long stageMask)},
 * {@code allocateAndBeginTransientCommandBuffer()} and {@code execute(VkCommandBuffer)}; they feed
 * {@code VulkanQueue.Submission}, which becomes one {@code vkQueueSubmit2KHR} at {@code encoder.submit()}. So the frame
 * submission reads: [engine commands so far] wait(glToVk) [our CB: acquire + blend pass] [the engine's blit to the swapchain...].
 * Every engine texture lives in VK_IMAGE_LAYOUT_GENERAL (known pitfall: VulkanGpuTexture init UNDEFINED to GENERAL), so the render
 * target is used with imageLayout GENERAL, like the engine's own render passes (VulkanCommandEncoder.createRenderPass).
 * <p>
 * The pass is a fullscreen triangle sampling the shared image with premultiplied-alpha blending
 * (src ONE, dst ONE_MINUS_SRC_ALPHA). NO Y flip: the engine's main render target is stored bottom-up like a GL image (framebuffer
 * row 0 = bottom of the picture): VulkanGpuSurface.blitFromTexture flips it when blitting to the swapchain (dstOffsets y from
 * copyHeight to 0, decompiled VulkanGpuSurface.java:~415). A first version of this class flipped v and the in-game
 * screenshot showed the GL triangle apex-down (known pitfall candidate: a GL texture and the engine target share the same row order,
 * a Vulkan-style "flip for a GL texture" is wrong here).
 * Dynamic rendering through the KHR extension the engine enables (KHRDynamicRendering, as VulkanCommandEncoder does).
 */
final class OverlayCompositor {
    private static final String VERT = "#version 450\n"
        + "layout(location = 0) out vec2 uv;\n"
        + "void main() {\n"
        + "    vec2 p = vec2(float((gl_VertexIndex << 1) & 2), float(gl_VertexIndex & 2));\n"
        + "    uv = p;\n"
        + "    gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);\n"
        + "}\n";
    private static final String FRAG = "#version 450\n"
        + "layout(set = 0, binding = 0) uniform sampler2D overlay;\n"
        + "layout(push_constant) uniform Params { int straightAlpha; float opacity; } params;\n"
        + "layout(location = 0) in vec2 uv;\n"
        + "layout(location = 0) out vec4 outColor;\n"
        + "void main() {\n"
        + "    vec4 c = texture(overlay, uv);\n"
        + "    if (params.straightAlpha != 0) { c.rgb *= c.a; }\n"
        + "    outColor = c * params.opacity;\n"
        + "}\n";

    /** VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT, the stage mask the engine itself uses for its submit semaphore (VulkanCommandEncoder.submit). */
    private static final long STAGE2_ALL_COMMANDS = 65536L;

    private final EngineVk e;
    private final int colorFormat;
    private final long vert, frag, setLayout, pipelineLayout, pipeline, sampler;

    OverlayCompositor(EngineVk e, RenderTarget main) {
        this.e = e;
        VkDevice dev = e.vkDevice();
        this.colorFormat = VulkanConst.toVk(main.getColorTexture().getFormat());
        vert = shader(dev, VERT, Shaderc.shaderc_glsl_vertex_shader, "vkb_overlay.vert");
        frag = shader(dev, FRAG, Shaderc.shaderc_glsl_fragment_shader, "vkb_overlay.frag");
        try (MemoryStack s = stackPush()) {
            LongBuffer p = s.mallocLong(1);

            VkSamplerCreateInfo sci = VkSamplerCreateInfo.calloc(s).sType$Default()
                .magFilter(VK_FILTER_LINEAR).minFilter(VK_FILTER_LINEAR).mipmapMode(VK_SAMPLER_MIPMAP_MODE_NEAREST)
                .addressModeU(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE).addressModeV(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                .addressModeW(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE).maxLod(0f);
            SharedRing.check(vkCreateSampler(dev, sci, null, p), "vkCreateSampler");
            sampler = p.get(0);

            VkDescriptorSetLayoutBinding.Buffer binding = VkDescriptorSetLayoutBinding.calloc(1, s).binding(0)
                .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1).stageFlags(VK_SHADER_STAGE_FRAGMENT_BIT);
            VkDescriptorSetLayoutCreateInfo dli = VkDescriptorSetLayoutCreateInfo.calloc(s).sType$Default().pBindings(binding);
            SharedRing.check(vkCreateDescriptorSetLayout(dev, dli, null, p), "vkCreateDescriptorSetLayout");
            setLayout = p.get(0);

            VkPushConstantRange.Buffer pcr = VkPushConstantRange.calloc(1, s).stageFlags(VK_SHADER_STAGE_FRAGMENT_BIT).offset(0).size(8);
            VkPipelineLayoutCreateInfo pli = VkPipelineLayoutCreateInfo.calloc(s).sType$Default().pSetLayouts(s.longs(setLayout))
                .pPushConstantRanges(pcr);
            SharedRing.check(vkCreatePipelineLayout(dev, pli, null, p), "vkCreatePipelineLayout");
            pipelineLayout = p.get(0);

            VkPipelineShaderStageCreateInfo.Buffer stages = VkPipelineShaderStageCreateInfo.calloc(2, s);
            stages.get(0).sType$Default().stage(VK_SHADER_STAGE_VERTEX_BIT).module(vert).pName(s.UTF8("main"));
            stages.get(1).sType$Default().stage(VK_SHADER_STAGE_FRAGMENT_BIT).module(frag).pName(s.UTF8("main"));
            VkPipelineVertexInputStateCreateInfo vi = VkPipelineVertexInputStateCreateInfo.calloc(s).sType$Default();
            VkPipelineInputAssemblyStateCreateInfo ia = VkPipelineInputAssemblyStateCreateInfo.calloc(s).sType$Default()
                .topology(VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST);
            VkPipelineViewportStateCreateInfo vp = VkPipelineViewportStateCreateInfo.calloc(s).sType$Default().viewportCount(1).scissorCount(1);
            VkPipelineRasterizationStateCreateInfo rs = VkPipelineRasterizationStateCreateInfo.calloc(s).sType$Default()
                .polygonMode(VK_POLYGON_MODE_FILL).cullMode(VK_CULL_MODE_NONE).frontFace(VK_FRONT_FACE_COUNTER_CLOCKWISE).lineWidth(1f);
            VkPipelineMultisampleStateCreateInfo ms = VkPipelineMultisampleStateCreateInfo.calloc(s).sType$Default()
                .rasterizationSamples(VK_SAMPLE_COUNT_1_BIT);
            VkPipelineColorBlendAttachmentState.Buffer cba = VkPipelineColorBlendAttachmentState.calloc(1, s)
                .blendEnable(true)
                .srcColorBlendFactor(VK_BLEND_FACTOR_ONE).dstColorBlendFactor(VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA).colorBlendOp(VK_BLEND_OP_ADD)
                .srcAlphaBlendFactor(VK_BLEND_FACTOR_ONE).dstAlphaBlendFactor(VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA).alphaBlendOp(VK_BLEND_OP_ADD)
                .colorWriteMask(VK_COLOR_COMPONENT_R_BIT | VK_COLOR_COMPONENT_G_BIT | VK_COLOR_COMPONENT_B_BIT | VK_COLOR_COMPONENT_A_BIT);
            VkPipelineColorBlendStateCreateInfo cb = VkPipelineColorBlendStateCreateInfo.calloc(s).sType$Default().pAttachments(cba);
            VkPipelineDynamicStateCreateInfo ds = VkPipelineDynamicStateCreateInfo.calloc(s).sType$Default()
                .pDynamicStates(s.ints(VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR));
            VkPipelineRenderingCreateInfoKHR ri = VkPipelineRenderingCreateInfoKHR.calloc(s).sType$Default()
                .colorAttachmentCount(1).pColorAttachmentFormats(s.ints(colorFormat));
            VkGraphicsPipelineCreateInfo.Buffer gpi = VkGraphicsPipelineCreateInfo.calloc(1, s).sType$Default().pNext(ri.address())
                .pStages(stages).pVertexInputState(vi).pInputAssemblyState(ia).pViewportState(vp).pRasterizationState(rs)
                .pMultisampleState(ms).pColorBlendState(cb).pDynamicState(ds).layout(pipelineLayout);
            SharedRing.check(vkCreateGraphicsPipelines(dev, VK_NULL_HANDLE, gpi, null, p), "vkCreateGraphicsPipelines(overlay)");
            pipeline = p.get(0);
        }
        GlLog.line(6, "event=compositor_ready colorFormat=" + colorFormat + " blend=premultiplied flipY=false");
    }

    /** Descriptor pool + one set per slot of one ring (the sets point at that ring's image views). Destroyed with the ring generation. */
    static final class Binding {
        final long pool;
        final long[] sets;

        private Binding(long pool, long[] sets) {
            this.pool = pool;
            this.sets = sets;
        }
    }

    Binding bind(SharedRing ring) {
        VkDevice dev = e.vkDevice();
        try (MemoryStack s = stackPush()) {
            LongBuffer p = s.mallocLong(1);
            VkDescriptorPoolSize.Buffer ps = VkDescriptorPoolSize.calloc(1, s).type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                .descriptorCount(ring.slots);
            VkDescriptorPoolCreateInfo dpi = VkDescriptorPoolCreateInfo.calloc(s).sType$Default().maxSets(ring.slots).pPoolSizes(ps);
            SharedRing.check(vkCreateDescriptorPool(dev, dpi, null, p), "vkCreateDescriptorPool");
            long pool = p.get(0);
            LongBuffer layouts = s.mallocLong(ring.slots);
            for (int i = 0; i < ring.slots; i++) {
                layouts.put(i, setLayout);
            }
            VkDescriptorSetAllocateInfo dai = VkDescriptorSetAllocateInfo.calloc(s).sType$Default().descriptorPool(pool).pSetLayouts(layouts);
            LongBuffer sets = s.mallocLong(ring.slots);
            SharedRing.check(vkAllocateDescriptorSets(dev, dai, sets), "vkAllocateDescriptorSets");
            long[] descSet = new long[ring.slots];
            for (int i = 0; i < ring.slots; i++) {
                descSet[i] = sets.get(i);
                VkDescriptorImageInfo.Buffer ii = VkDescriptorImageInfo.calloc(1, s).sampler(sampler).imageView(ring.imageView[i])
                    .imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
                VkWriteDescriptorSet.Buffer w = VkWriteDescriptorSet.calloc(1, s).sType$Default().dstSet(descSet[i]).dstBinding(0)
                    .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1).pImageInfo(ii);
                vkUpdateDescriptorSets(dev, w, null);
            }
            return new Binding(pool, descSet);
        }
    }

    /** Frees the pool (and with it the sets). The GPU must be done with them (same rule as {@link SharedRing#destroy}). */
    void unbind(Binding b) {
        vkDestroyDescriptorPool(e.vkDevice(), b.pool, null);
    }

    /** Shutdown only: the queue is idle. */
    void destroy() {
        VkDevice dev = e.vkDevice();
        vkDestroyPipeline(dev, pipeline, null);
        vkDestroyPipelineLayout(dev, pipelineLayout, null);
        vkDestroyDescriptorSetLayout(dev, setLayout, null);
        vkDestroySampler(dev, sampler, null);
        vkDestroyShaderModule(dev, vert, null);
        vkDestroyShaderModule(dev, frag, null);
    }

    private static long shader(VkDevice dev, String source, int kind, String name) {
        long compiler = Shaderc.shaderc_compiler_initialize();
        if (compiler == 0L) {
            throw new IllegalStateException("shaderc_compiler_initialize failed");
        }
        long result = Shaderc.shaderc_compile_into_spv(compiler, source, kind, name, "main", 0L);
        try {
            if (Shaderc.shaderc_result_get_compilation_status(result) != Shaderc.shaderc_compilation_status_success) {
                throw new IllegalStateException("shaderc failed for " + name + ": " + Shaderc.shaderc_result_get_error_message(result));
            }
            ByteBuffer spv = Shaderc.shaderc_result_get_bytes(result);
            try (MemoryStack s = stackPush()) {
                VkShaderModuleCreateInfo ci = VkShaderModuleCreateInfo.calloc(s).sType$Default().pCode(spv);
                LongBuffer p = s.mallocLong(1);
                SharedRing.check(vkCreateShaderModule(dev, ci, null, p), "vkCreateShaderModule(" + name + ")");
                return p.get(0);
            }
        } finally {
            Shaderc.shaderc_result_release(result);
            Shaderc.shaderc_compiler_release(compiler);
        }
    }

    /**
     * Render thread, outside any engine render pass. Adds "wait glToVk, our CB" to the engine's frame submission.
     * The CB: (1) acquire barrier for the shared image (EXTERNAL to the queue family, old=new=SHADER_READ_ONLY, the layout GL
     * was told to leave it in) and a global barrier that makes the engine's earlier writes to the target visible to our blend;
     * (2) dynamic rendering into the main colour view (LOAD/STORE, layout GENERAL), one fullscreen triangle;
     * (3) a global barrier like the engine's own (ALL_COMMANDS, MEMORY_READ|WRITE) so the engine's following operations
     * (the blit to the swapchain) see the result.
     */
    void record(SharedRing ring, Binding binding, int slot, RenderTarget main, GlbPerf perf, long frame, boolean straightAlpha, float opacity) {
        GpuTextureView view = main.getColorTextureView();
        if (!(view instanceof VulkanGpuTextureView vkView)) {
            throw new IllegalStateException("main colour view is not a VulkanGpuTextureView: " + view.getClass().getName());
        }
        int fmt = VulkanConst.toVk(main.getColorTexture().getFormat());
        if (fmt != colorFormat) {
            throw new IllegalStateException("main colour format changed from " + colorFormat + " to " + fmt + " (pipeline is built for the first one)");
        }
        int width = main.width, height = main.height;
        var enc = e.encoder();
        enc.waitSemaphore(ring.semGlToVk[slot], 0L, STAGE2_ALL_COMMANDS);
        VkCommandBuffer cb = enc.allocateAndBeginTransientCommandBuffer();
        try (MemoryStack s = stackPush()) {
            perf.vkBegin(cb, frame);
            ring.recordAcquire(cb, s, slot, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
                VK_ACCESS_SHADER_READ_BIT);

            VkMemoryBarrier.Buffer mb = VkMemoryBarrier.calloc(1, s).sType$Default()
                .srcAccessMask(VK_ACCESS_MEMORY_WRITE_BIT)
                .dstAccessMask(VK_ACCESS_COLOR_ATTACHMENT_READ_BIT | VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT);
            vkCmdPipelineBarrier(cb, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT, 0, mb, null, null);

            VkRenderingAttachmentInfoKHR.Buffer att = VkRenderingAttachmentInfoKHR.calloc(1, s).sType$Default()
                .imageView(vkView.vkImageView()).imageLayout(VK_IMAGE_LAYOUT_GENERAL)
                .loadOp(VK_ATTACHMENT_LOAD_OP_LOAD).storeOp(VK_ATTACHMENT_STORE_OP_STORE);
            VkRenderingInfoKHR ri = VkRenderingInfoKHR.calloc(s).sType$Default()
                .renderArea(a -> a.offset(o -> o.set(0, 0)).extent(x -> x.set(width, height)))
                .layerCount(1).pColorAttachments(att);
            KHRDynamicRendering.vkCmdBeginRenderingKHR(cb, ri);
            vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline);
            vkCmdBindDescriptorSets(cb, VK_PIPELINE_BIND_POINT_GRAPHICS, pipelineLayout, 0, s.longs(binding.sets[slot]), null);
            java.nio.ByteBuffer pc = s.malloc(8);
            pc.putInt(0, straightAlpha ? 1 : 0).putFloat(4, opacity);
            vkCmdPushConstants(cb, pipelineLayout, VK_SHADER_STAGE_FRAGMENT_BIT, 0, pc);
            VkViewport.Buffer vp = VkViewport.calloc(1, s).x(0f).y(0f).width(width).height(height).minDepth(0f).maxDepth(1f);
            vkCmdSetViewport(cb, 0, vp);
            VkRect2D.Buffer sc = VkRect2D.calloc(1, s);
            sc.offset().set(0, 0);
            sc.extent().set(width, height);
            vkCmdSetScissor(cb, 0, sc);
            vkCmdDraw(cb, 3, 1, 0, 0);
            KHRDynamicRendering.vkCmdEndRenderingKHR(cb);

            VkMemoryBarrier.Buffer after = VkMemoryBarrier.calloc(1, s).sType$Default()
                .srcAccessMask(VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT)
                .dstAccessMask(VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT);
            vkCmdPipelineBarrier(cb, VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, 0, after, null, null);
            perf.vkEnd(cb, frame);
            SharedRing.check(vkEndCommandBuffer(cb), "vkEndCommandBuffer(overlay)");
        }
        enc.execute(cb);
    }
}
