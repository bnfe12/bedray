package io.github.andrexarlay.vulkancompat.shaderhost.iris;

import io.github.andrexarlay.vulkancompat.rt.world.RtFailure;
import io.github.andrexarlay.vulkancompat.rt.world.RtMemory;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.VkBarriers;
import io.github.andrexarlay.vulkancompat.shaderhost.glsl.IrisUniforms;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRDynamicRendering;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VK13;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDescriptorImageInfo;
import org.lwjgl.vulkan.VkDescriptorPoolCreateInfo;
import org.lwjgl.vulkan.VkDescriptorPoolSize;
import org.lwjgl.vulkan.VkDescriptorSetAllocateInfo;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkGraphicsPipelineCreateInfo;
import org.lwjgl.vulkan.VkImageCopy;
import org.lwjgl.vulkan.VkPipelineColorBlendAttachmentState;
import org.lwjgl.vulkan.VkPipelineColorBlendStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineDepthStencilStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineDynamicStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineInputAssemblyStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo;
import org.lwjgl.vulkan.VkPipelineMultisampleStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineRasterizationStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineRenderingCreateInfo;
import org.lwjgl.vulkan.VkPipelineShaderStageCreateInfo;
import org.lwjgl.vulkan.VkPipelineVertexInputStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineViewportStateCreateInfo;
import org.lwjgl.vulkan.VkPushConstantRange;
import org.lwjgl.vulkan.VkRect2D;
import org.lwjgl.vulkan.VkRenderingAttachmentInfo;
import org.lwjgl.vulkan.VkRenderingInfo;
import org.lwjgl.vulkan.VkSamplerCreateInfo;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;
import org.lwjgl.vulkan.VkViewport;
import org.lwjgl.vulkan.VkWriteDescriptorSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Stage I3 execution runtime for Iris composite and final fullscreen passes.
 * <p>
 * Manages:
 * <ul>
 *   <li>Two ping-pong RGBA8 images ({@code colortex0} and {@code colortex1}) allocated through {@link RtMemory#createImage}.</li>
 *   <li>Dynamic rendering graphics pipelines ({@link VkGraphicsPipelineCreateInfo}) for each pass in {@link IrisPassGraph}.</li>
 *   <li>Ring-buffered descriptor sets (binding 0: sampled colortex ping-pong, binding 1: engine depth texture).</li>
 *   <li>32-byte push constants block per draw according to {@link IrisUniforms#PUSH}.</li>
 *   <li>Ping-pong rendering loop: engine color image copy -> sequential pass draws -> final target to engine color view.</li>
 * </ul>
 */
public final class IrisPostExecutor implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger("Bedray/IrisPostExecutor");

    public static final int DEFAULT_COLOR_FORMAT = VK10.VK_FORMAT_R8G8B8A8_UNORM;
    public static final int FRAME_SLOTS = 3;
    public static final int MAX_PASS_SETS = 32;

    /**
     * Execution plan step record used for tracking and testing pass execution order.
     */
    public record PassExecutionStep(
        int passIndex,
        String passName,
        boolean isFinal,
        String readTarget,
        String writeTarget,
        int slot,
        int setIndex
    ) {}

    private final VkDevice device;
    private final RtMemory memory;
    private final int colorFormat;

    private long colorSampler = 0L;
    private long depthSampler = 0L;
    private long descriptorSetLayout = 0L;
    private long pipelineLayout = 0L;
    private long descriptorPool = 0L;
    private long[][] descriptorSets; // [FRAME_SLOTS][MAX_PASS_SETS]
    private int currentFrameSlot = -1;
    private final long[][] boundColor = new long[FRAME_SLOTS][MAX_PASS_SETS];
    private final long[][] boundDepth = new long[FRAME_SLOTS][MAX_PASS_SETS];

    private RtMemory.Img colortex0;
    private RtMemory.Img colortex1;
    private boolean imagesInitialized = false;

    private final Map<String, Long> pipelines = new LinkedHashMap<>();
    private IrisPassGraph activeGraph;

    /**
     * Standard constructor using default color attachment format RGBA8_UNORM.
     */
    public IrisPostExecutor(VkDevice device, RtMemory memory) {
        this(device, memory, DEFAULT_COLOR_FORMAT);
    }

    /**
     * Constructor with explicit color format.
     */
    public IrisPostExecutor(VkDevice device, RtMemory memory, int colorFormat) {
        this.device = Objects.requireNonNull(device, "device");
        this.memory = Objects.requireNonNull(memory, "memory");
        this.colorFormat = colorFormat;
        try { initVulkan(); }
        catch (RuntimeException | LinkageError error) { cleanup(); throw error; }
    }

    /**
     * Testing constructor that skips GPU initialization.
     */
    IrisPostExecutor() {
        this.device = null;
        this.memory = null;
        this.colorFormat = DEFAULT_COLOR_FORMAT;
    }

    public static IrisPostExecutor createForTesting() {
        return new IrisPostExecutor();
    }

    private void initVulkan() {
        LOGGER.info("[IrisPostExecutor] Initializing Vulkan runtime resources (format={})...", colorFormat);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer p = stack.mallocLong(1);

            // 1. Color sampler: bilinear clamp to edge
            VkSamplerCreateInfo samplerInfo = VkSamplerCreateInfo.calloc(stack).sType$Default()
                .magFilter(VK10.VK_FILTER_LINEAR)
                .minFilter(VK10.VK_FILTER_LINEAR)
                .mipmapMode(VK10.VK_SAMPLER_MIPMAP_MODE_NEAREST)
                .addressModeU(VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                .addressModeV(VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                .addressModeW(VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                .maxLod(0.0f);
            RtFailure.check(VK10.vkCreateSampler(device, samplerInfo, null, p), "create_color_sampler");
            this.colorSampler = p.get(0);

            // 2. Depth sampler: nearest clamp to edge (safe for depth formats)
            samplerInfo.magFilter(VK10.VK_FILTER_NEAREST)
                .minFilter(VK10.VK_FILTER_NEAREST);
            RtFailure.check(VK10.vkCreateSampler(device, samplerInfo, null, p), "create_depth_sampler");
            this.depthSampler = p.get(0);

            // 3. Descriptor set layout: binding 0 = colortex0, binding 1 = depthtex0
            VkDescriptorSetLayoutBinding.Buffer bindings = createDescriptorBindings(stack);
            VkDescriptorSetLayoutCreateInfo layoutInfo = VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default()
                .pBindings(bindings);
            RtFailure.check(VK10.vkCreateDescriptorSetLayout(device, layoutInfo, null, p), "create_descriptor_set_layout");
            this.descriptorSetLayout = p.get(0);

            // 4. Pipeline layout: set 0 layout + 32-byte push constant range
            VkPushConstantRange.Buffer pushRanges = createPushConstantRange(stack);
            VkPipelineLayoutCreateInfo pipeLayoutInfo = VkPipelineLayoutCreateInfo.calloc(stack).sType$Default()
                .pSetLayouts(stack.longs(this.descriptorSetLayout))
                .pPushConstantRanges(pushRanges);
            RtFailure.check(VK10.vkCreatePipelineLayout(device, pipeLayoutInfo, null, p), "create_pipeline_layout");
            this.pipelineLayout = p.get(0);

            // 5. Descriptor pool and sets
            int totalSets = FRAME_SLOTS * MAX_PASS_SETS;
            VkDescriptorPoolSize.Buffer poolSizes = VkDescriptorPoolSize.calloc(1, stack);
            poolSizes.get(0).type(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                .descriptorCount(totalSets * IrisUniforms.SAMPLER_BINDINGS);
            VkDescriptorPoolCreateInfo poolInfo = VkDescriptorPoolCreateInfo.calloc(stack).sType$Default()
                .maxSets(totalSets)
                .pPoolSizes(poolSizes);
            RtFailure.check(VK10.vkCreateDescriptorPool(device, poolInfo, null, p), "create_descriptor_pool");
            this.descriptorPool = p.get(0);

            this.descriptorSets = new long[FRAME_SLOTS][MAX_PASS_SETS];
            LongBuffer layouts = stack.mallocLong(MAX_PASS_SETS);
            for (int i = 0; i < MAX_PASS_SETS; i++) {
                layouts.put(i, this.descriptorSetLayout);
            }
            for (int slot = 0; slot < FRAME_SLOTS; slot++) {
                VkDescriptorSetAllocateInfo allocInfo = VkDescriptorSetAllocateInfo.calloc(stack).sType$Default()
                    .descriptorPool(this.descriptorPool)
                    .pSetLayouts(layouts);
                LongBuffer pSets = stack.mallocLong(MAX_PASS_SETS);
                RtFailure.check(VK10.vkAllocateDescriptorSets(device, allocInfo, pSets), "alloc_descriptor_sets");
                for (int i = 0; i < MAX_PASS_SETS; i++) {
                    this.descriptorSets[slot][i] = pSets.get(i);
                }
            }
        } catch (Throwable t) {
            cleanup();
            throw t;
        }
    }

    /**
     * Builds and compiles dynamic rendering graphics pipelines for all passes in {@link IrisPassGraph}.
     */
    public synchronized void buildPipelines(IrisPassGraph graph) {
        Objects.requireNonNull(graph, "graph");
        if (this.activeGraph == graph && !this.pipelines.isEmpty()) {
            return;
        }
        validateGraph(graph);

        if (device != null) {
            RtFailure.check(VK10.vkDeviceWaitIdle(device), "iris_pipeline_retirement_wait");
            for (long pipe : this.pipelines.values()) {
                VK10.vkDestroyPipeline(device, pipe, null);
            }
            this.pipelines.clear();

            try (MemoryStack stack = MemoryStack.stackPush()) {
                for (IrisPassGraph.Pass pass : graph.passes()) {
                    long pipe = createPassPipeline(stack, pass);
                    this.pipelines.put(pass.name(), pipe);
                }
            }
        }
        this.activeGraph = graph;
        LOGGER.info("[IrisPostExecutor] Built {} Vulkan graphics pipelines for pack '{}'.",
            graph.passCount(), graph.packName());
    }

    private long createPassPipeline(MemoryStack stack, IrisPassGraph.Pass pass) {
        ByteBuffer vSpv = pass.vertexSpirv().duplicate();
        vSpv.rewind();
        ByteBuffer fSpv = pass.fragmentSpirv().duplicate();
        fSpv.rewind();

        LongBuffer p = stack.mallocLong(1);
        VkShaderModuleCreateInfo vModInfo = VkShaderModuleCreateInfo.calloc(stack).sType$Default()
            .pCode(vSpv);
        RtFailure.check(VK10.vkCreateShaderModule(device, vModInfo, null, p), "create_vert_module:" + pass.name());
        long vMod = p.get(0);

        VkShaderModuleCreateInfo fModInfo = VkShaderModuleCreateInfo.calloc(stack).sType$Default()
            .pCode(fSpv);
        int err = VK10.vkCreateShaderModule(device, fModInfo, null, p);
        if (err != VK10.VK_SUCCESS) {
            VK10.vkDestroyShaderModule(device, vMod, null);
            throw new RtFailure("create_frag_module:" + pass.name(), "VkResult=" + err);
        }
        long fMod = p.get(0);

        try {
            VkPipelineShaderStageCreateInfo.Buffer stages = VkPipelineShaderStageCreateInfo.calloc(2, stack);
            stages.get(0).sType$Default().stage(VK10.VK_SHADER_STAGE_VERTEX_BIT).module(vMod).pName(stack.UTF8("main"));
            stages.get(1).sType$Default().stage(VK10.VK_SHADER_STAGE_FRAGMENT_BIT).module(fMod).pName(stack.UTF8("main"));

            // Fullscreen pass: no vertex buffers needed (gl_VertexIndex 0..2)
            VkPipelineVertexInputStateCreateInfo vertexInput = VkPipelineVertexInputStateCreateInfo.calloc(stack).sType$Default();

            VkPipelineInputAssemblyStateCreateInfo inputAssembly = VkPipelineInputAssemblyStateCreateInfo.calloc(stack).sType$Default()
                .topology(VK10.VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST)
                .primitiveRestartEnable(false);

            VkPipelineViewportStateCreateInfo viewportState = VkPipelineViewportStateCreateInfo.calloc(stack).sType$Default()
                .viewportCount(1)
                .scissorCount(1);

            VkPipelineDynamicStateCreateInfo dynamicState = VkPipelineDynamicStateCreateInfo.calloc(stack).sType$Default()
                .pDynamicStates(stack.ints(VK10.VK_DYNAMIC_STATE_VIEWPORT, VK10.VK_DYNAMIC_STATE_SCISSOR));

            VkPipelineRasterizationStateCreateInfo rasterization = createRasterizationState(stack);

            VkPipelineMultisampleStateCreateInfo multisample = VkPipelineMultisampleStateCreateInfo.calloc(stack).sType$Default()
                .rasterizationSamples(VK10.VK_SAMPLE_COUNT_1_BIT)
                .sampleShadingEnable(false);

            VkPipelineDepthStencilStateCreateInfo depthStencil = VkPipelineDepthStencilStateCreateInfo.calloc(stack).sType$Default()
                .depthTestEnable(false)
                .depthWriteEnable(false)
                .depthCompareOp(VK10.VK_COMPARE_OP_ALWAYS)
                .stencilTestEnable(false);

            VkPipelineColorBlendStateCreateInfo colorBlend = createColorBlendState(stack);
            VkPipelineRenderingCreateInfo renderingCreateInfo = createPipelineRenderingCreateInfo(stack, colorFormat);

            VkGraphicsPipelineCreateInfo.Buffer pipelineInfo = VkGraphicsPipelineCreateInfo.calloc(1, stack);
            pipelineInfo.get(0).sType$Default()
                .pNext(renderingCreateInfo.address())
                .pStages(stages)
                .pVertexInputState(vertexInput)
                .pInputAssemblyState(inputAssembly)
                .pViewportState(viewportState)
                .pRasterizationState(rasterization)
                .pMultisampleState(multisample)
                .pDepthStencilState(depthStencil)
                .pColorBlendState(colorBlend)
                .pDynamicState(dynamicState)
                .layout(this.pipelineLayout)
                .renderPass(VK10.VK_NULL_HANDLE)
                .subpass(0);

            RtFailure.check(VK10.vkCreateGraphicsPipelines(device, VK10.VK_NULL_HANDLE, pipelineInfo, null, p),
                "create_graphics_pipeline:" + pass.name());
            return p.get(0);
        } finally {
            VK10.vkDestroyShaderModule(device, vMod, null);
            VK10.vkDestroyShaderModule(device, fMod, null);
        }
    }

    /**
     * Ensures ping-pong images exist with the required resolution.
     */
    public synchronized void ensure(int width, int height) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Invalid dimensions: " + width + "x" + height);
        }
        if (colortex0 != null && colortex1 != null && colortex0.width == width && colortex0.height == height) {
            return;
        }
        if (memory == null) {
            return;
        }
        if (device != null && (colortex0 != null || colortex1 != null)) {
            RtFailure.check(VK10.vkDeviceWaitIdle(device), "iris_image_retirement_wait");
        }
        if (colortex0 != null) {
            memory.destroy(colortex0);
            colortex0 = null;
        }
        if (colortex1 != null) {
            memory.destroy(colortex1);
            colortex1 = null;
        }
        int usage = VK10.VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT
            | VK10.VK_IMAGE_USAGE_SAMPLED_BIT
            | VK10.VK_IMAGE_USAGE_TRANSFER_DST_BIT
            | VK10.VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
        colortex0 = memory.createImage("iris_colortex0", width, height, colorFormat, usage);
        colortex1 = memory.createImage("iris_colortex1", width, height, colorFormat, usage);
        imagesInitialized = false;
        LOGGER.info("[IrisPostExecutor] Allocated ping-pong images colortex0/1: {}x{} format={}",
            width, height, colorFormat);
    }

    /** Host calls before opening its transient command buffer; may allocate pipelines/images. */
    public void prepare(IrisPassGraph graph, int width, int height) {
        validateGraph(graph);
        ensure(width,height);
        buildPipelines(graph);
    }

    /**
     * Records execution of all graph passes for one frame.
     *
     * @param cmd              open command buffer
     * @param graph            compiled pass graph
     * @param engineColorImage handle of the engine color image (source to copy from)
     * @param engineColorView  view of the engine color image (final render target)
     * @param depthView        view of the engine depth image (depthtex0 sampler)
     * @param width            render width
     * @param height           render height
     * @param frameTimeCounter time in seconds (wraps at 3600)
     * @param frameCounter     monotonic frame count (wraps at 720720)
     * @param near             camera near plane (0.05)
     * @param far              camera far plane (render distance in blocks)
     * @param frameTime        delta time of the last frame in seconds
     */
    public void record(
        VkCommandBuffer cmd,
        IrisPassGraph graph,
        long engineColorImage,
        long engineColorView,
        long depthView,
        int width,
        int height,
        float frameTimeCounter,
        int frameCounter,
        float near,
        float far,
        float frameTime
    ) {
        if (cmd == null) {
            throw new IllegalArgumentException("cmd must not be null");
        }
        if (graph == null || graph.passes().isEmpty()) {
            return;
        }
        if (engineColorImage == 0L || engineColorView == 0L || depthView == 0L) {
            throw new IllegalArgumentException("Invalid image handles: engineColorImage=" + engineColorImage
                + " engineColorView=" + engineColorView + " depthView=" + depthView);
        }
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Invalid dimensions: " + width + "x" + height);
        }

        ensure(width, height);
        buildPipelines(graph);

        if (device == null || memory == null) {
            return;
        }

        // Transition ping-pong images from UNDEFINED to GENERAL on first use or resize
        if (!imagesInitialized) {
            VkBarriers.transition(cmd, colortex0.image, VK10.VK_IMAGE_ASPECT_COLOR_BIT,
                VK10.VK_IMAGE_LAYOUT_UNDEFINED, VK10.VK_IMAGE_LAYOUT_GENERAL);
            VkBarriers.transition(cmd, colortex1.image, VK10.VK_IMAGE_ASPECT_COLOR_BIT,
                VK10.VK_IMAGE_LAYOUT_UNDEFINED, VK10.VK_IMAGE_LAYOUT_GENERAL);
            imagesInitialized = true;
        }

        currentFrameSlot = (currentFrameSlot + 1) % FRAME_SLOTS;
        int slot = currentFrameSlot;

        // Step 1: Copy engineColorImage into colortex0
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkImageCopy.Buffer copyRegion = VkImageCopy.calloc(1, stack);
            copyRegion.get(0).srcSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
            copyRegion.get(0).dstSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
            copyRegion.get(0).extent().width(width).height(height).depth(1);
            VK10.vkCmdCopyImage(cmd, engineColorImage, VK10.VK_IMAGE_LAYOUT_GENERAL,
                colortex0.image, VK10.VK_IMAGE_LAYOUT_GENERAL, copyRegion);
        }
        VkBarriers.fullMemoryBarrier(cmd);

        // Dynamic viewport and scissor
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkViewport.Buffer viewport = VkViewport.calloc(1, stack);
            viewport.get(0).x(0.0f).y(0.0f).width((float) width).height((float) height).minDepth(0.0f).maxDepth(1.0f);
            VK10.vkCmdSetViewport(cmd, 0, viewport);

            VkRect2D.Buffer scissor = VkRect2D.calloc(1, stack);
            scissor.get(0).offset().set(0, 0);
            scissor.get(0).extent().set(width, height);
            VK10.vkCmdSetScissor(cmd, 0, scissor);
        }

        int readIndex = 0; // 0 = colortex0, 1 = colortex1
        List<IrisPassGraph.Pass> passes = graph.passes();


        for (int passIdx = 0; passIdx < passes.size(); passIdx++) {
            IrisPassGraph.Pass pass = passes.get(passIdx);
            Long pipeHandle = pipelines.get(pass.name());
            if (pipeHandle == null) {
                throw new IllegalStateException("Pipeline not found for pass: " + pass.name());
            }
            if (passIdx >= MAX_PASS_SETS) {
                throw new IllegalStateException("Pass index " + passIdx + " exceeds MAX_PASS_SETS (" + MAX_PASS_SETS + ")");
            }

            RtMemory.Img readImg = (readIndex == 0) ? colortex0 : colortex1;
            RtMemory.Img writeImg = (readIndex == 0) ? colortex1 : colortex0;
            // All passes, including final, stay offscreen. Commit to the host image only after all succeed.
            long targetView = writeImg.view;
            long dSet = descriptorSets[slot][passIdx];
            // Deterministic contents even when a pack clips vertices or discards fragments.
            // Keep the preceding color, rather than exposing uninitialized ping-pong pixels.
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkBarriers.fullMemoryBarrier(cmd);
                VkImageCopy.Buffer preserve = VkImageCopy.calloc(1,stack);
                preserve.get(0).srcSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
                preserve.get(0).dstSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
                preserve.get(0).extent().set(width,height,1);
                VK10.vkCmdCopyImage(cmd,readImg.image,VK10.VK_IMAGE_LAYOUT_GENERAL,
                    writeImg.image,VK10.VK_IMAGE_LAYOUT_GENERAL,preserve);
                VkBarriers.fullMemoryBarrier(cmd);
            }

            try (MemoryStack stack = MemoryStack.stackPush()) {
                // 1. Update descriptor set for current pass
                VkDescriptorImageInfo.Buffer imgInfos = VkDescriptorImageInfo.calloc(2, stack);
                imgInfos.get(0).sampler(colorSampler).imageView(readImg.view).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
                imgInfos.get(1).sampler(depthSampler).imageView(depthView).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);

                VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(2, stack);
                writes.get(0).sType$Default().dstSet(dSet).dstBinding(IrisUniforms.BINDING_COLORTEX0)
                    .descriptorType(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1).pImageInfo(imgInfos.slice(0, 1));
                writes.get(1).sType$Default().dstSet(dSet).dstBinding(IrisUniforms.BINDING_DEPTHTEX0)
                    .descriptorType(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1).pImageInfo(imgInfos.slice(1, 1));

                if (boundColor[slot][passIdx] != readImg.view || boundDepth[slot][passIdx] != depthView) {
                    if (boundColor[slot][passIdx] != 0)
                        throw new IllegalStateException("Host must retire Iris executor before replacing sampled images");
                    VK10.vkUpdateDescriptorSets(device, writes, null);
                    boundColor[slot][passIdx] = readImg.view;
                    boundDepth[slot][passIdx] = depthView;
                }

                // 2. Bind pipeline
                VK10.vkCmdBindPipeline(cmd, VK10.VK_PIPELINE_BIND_POINT_GRAPHICS, pipeHandle);

                // 3. Bind descriptor set
                VK10.vkCmdBindDescriptorSets(cmd, VK10.VK_PIPELINE_BIND_POINT_GRAPHICS, pipelineLayout, 0, stack.longs(dSet), null);

                // 4. Push constants
                ByteBuffer pushBuf = stack.malloc(IrisUniforms.PUSH_BYTES);
                fillPushConstants(pushBuf, width, height, frameTimeCounter, frameCounter, near, far, frameTime);
                VK10.vkCmdPushConstants(cmd, pipelineLayout,
                    VK10.VK_SHADER_STAGE_VERTEX_BIT | VK10.VK_SHADER_STAGE_FRAGMENT_BIT,
                    0, pushBuf);

                // 5. Begin dynamic rendering
                VkRenderingAttachmentInfo.Buffer colorAttachment = VkRenderingAttachmentInfo.calloc(1, stack);
                colorAttachment.get(0).sType$Default()
                    .imageView(targetView)
                    .imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL)
                    .loadOp(VK10.VK_ATTACHMENT_LOAD_OP_LOAD)
                    .storeOp(VK10.VK_ATTACHMENT_STORE_OP_STORE);

                VkRenderingInfo renderingInfo = VkRenderingInfo.calloc(stack);
                renderingInfo.sType$Default()
                    .renderArea(ra -> ra.extent().set(width, height))
                    .layerCount(1)
                    .pColorAttachments(colorAttachment);

                beginRendering(cmd, renderingInfo);

                try {
                    VK10.vkCmdDraw(cmd, 3, 1, 0, 0);
                } finally {
                    endRendering(cmd);
                }
            }

            // 8. Memory barrier and ping-pong switch
            VkBarriers.fullMemoryBarrier(cmd);
            readIndex = 1 - readIndex;
        }

        // Commit the completed graph to the host image. No earlier pass modifies it.
        if (!passes.isEmpty()) {
            RtMemory.Img lastWritten = (readIndex == 1) ? colortex1 : colortex0;
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkImageCopy.Buffer copyRegion = VkImageCopy.calloc(1, stack);
                copyRegion.get(0).srcSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
                copyRegion.get(0).dstSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
                copyRegion.get(0).extent().width(width).height(height).depth(1);
                VK10.vkCmdCopyImage(cmd, lastWritten.image, VK10.VK_IMAGE_LAYOUT_GENERAL,
                    engineColorImage, VK10.VK_IMAGE_LAYOUT_GENERAL, copyRegion);
            }
            VkBarriers.fullMemoryBarrier(cmd);
        }
    }

    private void beginRendering(VkCommandBuffer cmd, VkRenderingInfo renderingInfo) {
        if (cmd.getCapabilities().vkCmdBeginRendering != 0L) {
            VK13.vkCmdBeginRendering(cmd, renderingInfo);
        } else if (cmd.getCapabilities().vkCmdBeginRenderingKHR != 0L) {
            KHRDynamicRendering.vkCmdBeginRenderingKHR(cmd, renderingInfo);
        } else {
            throw new RtFailure("dynamic_rendering", "Neither VK13 nor VK_KHR_dynamic_rendering available on VkCommandBuffer");
        }
    }

    private void endRendering(VkCommandBuffer cmd) {
        if (cmd.getCapabilities().vkCmdEndRendering != 0L) {
            VK13.vkCmdEndRendering(cmd);
        } else if (cmd.getCapabilities().vkCmdEndRenderingKHR != 0L) {
            KHRDynamicRendering.vkCmdEndRenderingKHR(cmd);
        } else {
            throw new RtFailure("dynamic_rendering", "Neither VK13 nor VK_KHR_dynamic_rendering available on VkCommandBuffer");
        }
    }

    // --- Static Builders, Validators and Utilities ---

    public static VkDescriptorSetLayoutBinding.Buffer createDescriptorBindings(MemoryStack stack) {
        VkDescriptorSetLayoutBinding.Buffer bindings = VkDescriptorSetLayoutBinding.calloc(2, stack);
        bindings.get(0).binding(IrisUniforms.BINDING_COLORTEX0)
            .descriptorType(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
            .descriptorCount(1)
            .stageFlags(VK10.VK_SHADER_STAGE_VERTEX_BIT | VK10.VK_SHADER_STAGE_FRAGMENT_BIT);
        bindings.get(1).binding(IrisUniforms.BINDING_DEPTHTEX0)
            .descriptorType(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
            .descriptorCount(1)
            .stageFlags(VK10.VK_SHADER_STAGE_VERTEX_BIT | VK10.VK_SHADER_STAGE_FRAGMENT_BIT);
        return bindings;
    }

    public static VkPushConstantRange.Buffer createPushConstantRange(MemoryStack stack) {
        VkPushConstantRange.Buffer pushRanges = VkPushConstantRange.calloc(1, stack);
        pushRanges.get(0).stageFlags(VK10.VK_SHADER_STAGE_VERTEX_BIT | VK10.VK_SHADER_STAGE_FRAGMENT_BIT)
            .offset(0)
            .size(IrisUniforms.PUSH_BYTES);
        return pushRanges;
    }

    public static VkPipelineColorBlendStateCreateInfo createColorBlendState(MemoryStack stack) {
        VkPipelineColorBlendAttachmentState.Buffer blendAttachments = VkPipelineColorBlendAttachmentState.calloc(1, stack);
        blendAttachments.get(0).blendEnable(false)
            .colorWriteMask(VK10.VK_COLOR_COMPONENT_R_BIT
                | VK10.VK_COLOR_COMPONENT_G_BIT
                | VK10.VK_COLOR_COMPONENT_B_BIT
                | VK10.VK_COLOR_COMPONENT_A_BIT);
        return VkPipelineColorBlendStateCreateInfo.calloc(stack).sType$Default()
            .logicOpEnable(false)
            .pAttachments(blendAttachments);
    }

    public static VkPipelineRasterizationStateCreateInfo createRasterizationState(MemoryStack stack) {
        return VkPipelineRasterizationStateCreateInfo.calloc(stack).sType$Default()
            .depthClampEnable(false)
            .rasterizerDiscardEnable(false)
            .polygonMode(VK10.VK_POLYGON_MODE_FILL)
            .cullMode(VK10.VK_CULL_MODE_NONE)
            .frontFace(VK10.VK_FRONT_FACE_COUNTER_CLOCKWISE)
            .depthBiasEnable(false)
            .lineWidth(1.0f);
    }

    public static VkPipelineRenderingCreateInfo createPipelineRenderingCreateInfo(MemoryStack stack, int colorFormat) {
        return VkPipelineRenderingCreateInfo.calloc(stack).sType$Default()
            .pColorAttachmentFormats(stack.ints(colorFormat));
    }

    public static ByteBuffer packPushConstants(
        int width,
        int height,
        float frameTimeCounter,
        int frameCounter,
        float near,
        float far,
        float frameTime
    ) {
        ByteBuffer buf = ByteBuffer.allocateDirect(IrisUniforms.PUSH_BYTES).order(ByteOrder.nativeOrder());
        fillPushConstants(buf, width, height, frameTimeCounter, frameCounter, near, far, frameTime);
        return buf;
    }

    private static void fillPushConstants(
        ByteBuffer buf,
        int width,
        int height,
        float frameTimeCounter,
        int frameCounter,
        float near,
        float far,
        float frameTime
    ) {
        float w = (float) width;
        float h = (float) height;
        float aspect = h > 0.0f ? w / h : 1.0f;
        buf.putFloat(0, w);
        buf.putFloat(4, h);
        buf.putFloat(8, aspect);
        buf.putFloat(12, frameTimeCounter);
        buf.putInt(16, frameCounter);
        buf.putFloat(20, near);
        buf.putFloat(24, far);
        buf.putFloat(28, frameTime);
    }

    public static void validateGraph(IrisPassGraph graph) {
        Objects.requireNonNull(graph, "graph");
        if (graph.passes().isEmpty()) {
            throw new IllegalArgumentException("Pass graph has no passes to execute");
        }
        if (graph.passCount() > MAX_PASS_SETS) throw new IllegalArgumentException("Too many fullscreen passes");
        long finals = graph.passes().stream().filter(IrisPassGraph.Pass::isFinal).count();
        if (finals > 1 || finals == 1 && !graph.passes().getLast().isFinal())
            throw new IllegalArgumentException("A graph may have one final pass, last in the sequence");
        for (IrisPassGraph.Pass pass : graph.passes()) {
            if (!pass.drawBuffers().equals(List.of(0))) throw new IllegalArgumentException("Only colortex0 output is supported");
            if (pass.vertexSpirv() == null || pass.fragmentSpirv() == null) {
                throw new IllegalArgumentException("Pass " + pass.name() + " has null SPIR-V bytecode");
            }
            Set<Integer> bindings = pass.allSamplerBindings();
            for (int b : bindings) {
                if (b != IrisUniforms.BINDING_COLORTEX0 && b != IrisUniforms.BINDING_DEPTHTEX0) {
                    throw new IllegalArgumentException("Pass " + pass.name() + " has unsupported sampler binding: " + b);
                }
            }
        }
    }

    public static List<PassExecutionStep> planPassExecution(IrisPassGraph graph, int frameSlot) {
        List<PassExecutionStep> plan = new ArrayList<>();
        if (graph == null || graph.passes().isEmpty()) {
            return plan;
        }
        int readIndex = 0;
        for (int i = 0; i < graph.passes().size(); i++) {
            IrisPassGraph.Pass pass = graph.passes().get(i);
            String readName = (readIndex == 0) ? "colortex0" : "colortex1";
            String writeName = (readIndex == 0) ? "colortex1" : "colortex0";
            plan.add(new PassExecutionStep(i, pass.name(), pass.isFinal(), readName, writeName, frameSlot, i));
            readIndex = 1 - readIndex;
        }
        return Collections.unmodifiableList(plan);
    }

    // --- Getters & Lifecycle ---

    public VkDevice device() {
        return device;
    }

    public RtMemory memory() {
        return memory;
    }

    public int colorFormat() {
        return colorFormat;
    }

    public RtMemory.Img colortex0() {
        return colortex0;
    }

    public RtMemory.Img colortex1() {
        return colortex1;
    }

    public long colorSampler() {
        return colorSampler;
    }

    public long depthSampler() {
        return depthSampler;
    }

    public long descriptorSetLayout() {
        return descriptorSetLayout;
    }

    public long pipelineLayout() {
        return pipelineLayout;
    }

    public long descriptorPool() {
        return descriptorPool;
    }

    public long getPipeline(String passName) {
        return pipelines.getOrDefault(passName, 0L);
    }

    public Map<String, Long> pipelines() {
        return Collections.unmodifiableMap(pipelines);
    }

    public int currentFrameSlot() {
        return currentFrameSlot;
    }

    @Override
    public synchronized void close() {
        cleanup();
    }

    public synchronized void cleanup() {
        if (device != null) {
            RtFailure.check(VK10.vkDeviceWaitIdle(device), "iris_cleanup_wait");
            for (long pipe : pipelines.values()) {
                VK10.vkDestroyPipeline(device, pipe, null);
            }
            pipelines.clear();

            if (pipelineLayout != 0L) {
                VK10.vkDestroyPipelineLayout(device, pipelineLayout, null);
                pipelineLayout = 0L;
            }
            if (descriptorPool != 0L) {
                VK10.vkDestroyDescriptorPool(device, descriptorPool, null);
                descriptorPool = 0L;
            }
            if (descriptorSetLayout != 0L) {
                VK10.vkDestroyDescriptorSetLayout(device, descriptorSetLayout, null);
                descriptorSetLayout = 0L;
            }
            if (colorSampler != 0L) {
                VK10.vkDestroySampler(device, colorSampler, null);
                colorSampler = 0L;
            }
            if (depthSampler != 0L) {
                VK10.vkDestroySampler(device, depthSampler, null);
                depthSampler = 0L;
            }
        }
        if (memory != null) {
            if (colortex0 != null) {
                memory.destroy(colortex0);
                colortex0 = null;
            }
            if (colortex1 != null) {
                memory.destroy(colortex1);
                colortex1 = null;
            }
        }
        imagesInitialized = false;
        activeGraph = null;
    }
}
