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
import org.lwjgl.vulkan.VkDescriptorBufferInfo;
import org.lwjgl.vulkan.VkDescriptorPoolCreateInfo;
import org.lwjgl.vulkan.VkDescriptorPoolSize;
import org.lwjgl.vulkan.VkDescriptorSetAllocateInfo;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkGraphicsPipelineCreateInfo;
import org.lwjgl.vulkan.VkImageCopy;
import org.lwjgl.vulkan.VkClearColorValue;
import org.lwjgl.vulkan.VkImageSubresourceRange;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;
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
 * Bounded fullscreen resource-graph executor on the host device/queue. Each logical color target
 * has separate sampled/output banks. A final pass uses a presentation image; the host color is
 * overwritten only after the complete graph records. No resource contents are temporal history.
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
    private final long[][][] boundImages = new long[FRAME_SLOTS][MAX_PASS_SETS][IrisUniforms.SAMPLER_BINDINGS];
    private final boolean[][] descriptorsInitialized = new boolean[FRAME_SLOTS][MAX_PASS_SETS];
    private final RtMemory.Img[][] colorImages = new RtMemory.Img[IrisUniforms.COLOR_TARGETS][2];
    private RtMemory.Img presentation;
    private Set<Integer> allocatedTargets = Set.of();
    private int allocatedWidth, allocatedHeight;
    private IrisResourcePlan resourcePlan;
    private static final long MAX_IMAGE_BYTES = 512L * 1024 * 1024;
    private RtMemory.Buf frameUniforms;
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
            frameUniforms = memory.createBuffer("iris_frame", IrisUniforms.FRAME_BYTES,
                VK10.VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT | VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT, false);
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

            // 3. Stable layout shared by every pass: nine samplers and one frame UBO
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
            VkDescriptorPoolSize.Buffer poolSizes = VkDescriptorPoolSize.calloc(2, stack);
            poolSizes.get(0).type(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                .descriptorCount(totalSets * IrisUniforms.SAMPLER_BINDINGS);
            poolSizes.get(1).type(VK10.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).descriptorCount(totalSets);
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

            int attachments = pass.isFinal() ? 1 : pass.drawBuffers().size();
            VkPipelineColorBlendStateCreateInfo colorBlend = createColorBlendState(stack, attachments);
            VkPipelineRenderingCreateInfo renderingCreateInfo = createPipelineRenderingCreateInfo(stack, colorFormat, attachments);

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

    /** Legacy color0 allocation seam; graph callers use prepare before opening a command buffer. */
    public synchronized void ensure(int width, int height) {
        ensureResources(Set.of(0), false, width, height);
    }

    private synchronized void ensureResources(Set<Integer> targets, boolean finalPass, int width, int height) {
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("Invalid Iris extent");
        if (memory == null) return;
        if (allocatedWidth == width && allocatedHeight == height && allocatedTargets.equals(targets)
            && (presentation != null) == finalPass) return;
        // The host must submit queued uses and retire this executor before target/graph changes.
        if (!allocatedTargets.isEmpty()) throw new IllegalStateException("Retire Iris before changing image resources");
        checkDeviceResources(targets.size() * 2 + (finalPass ? 1 : 0), width, height);
        int usage = VK10.VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK10.VK_IMAGE_USAGE_SAMPLED_BIT
            | VK10.VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK10.VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
        for (int target : targets) {
            for (int bank = 0; bank < 2; bank++) colorImages[target][bank] = memory.createImage(
                "iris_colortex" + target + "_" + bank, width, height, colorFormat, usage);
        }
        if (finalPass) presentation = memory.createImage("iris_presentation", width, height, colorFormat, usage);
        allocatedTargets = Set.copyOf(targets);
        allocatedWidth = width; allocatedHeight = height;
        imagesInitialized = false;
        LOGGER.info("[IrisPostExecutor] Allocated {} color target pairs, final={}, {}x{} format={}",
            targets.size(), finalPass, width, height, colorFormat);
    }

    private void checkDeviceResources(int imageCount, int width, int height) {
        int bytesPerPixel = switch (colorFormat) {
            case VK10.VK_FORMAT_R8G8B8A8_UNORM, VK10.VK_FORMAT_R8G8B8A8_SRGB,
                 VK10.VK_FORMAT_B8G8R8A8_UNORM, VK10.VK_FORMAT_B8G8R8A8_SRGB,
                 VK10.VK_FORMAT_A2B10G10R10_UNORM_PACK32, VK10.VK_FORMAT_A2R10G10B10_UNORM_PACK32,
                 VK10.VK_FORMAT_B10G11R11_UFLOAT_PACK32 -> 4;
            case VK10.VK_FORMAT_R16G16B16A16_SFLOAT, VK10.VK_FORMAT_R16G16B16A16_UNORM -> 8;
            case VK10.VK_FORMAT_R32G32B32A32_SFLOAT -> 16;
            default -> throw new IllegalArgumentException("Unsupported Iris color attachment format " + colorFormat);
        };
        long pixels = Math.multiplyExact((long) width, height);
        long bytes = Math.multiplyExact(pixels, (long) imageCount * bytesPerPixel);
        if (bytes > MAX_IMAGE_BYTES) throw new IllegalArgumentException("Iris color images exceed the 512 MiB budget");
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var properties = VkPhysicalDeviceProperties.calloc(stack);
            VK10.vkGetPhysicalDeviceProperties(device.getPhysicalDevice(), properties);
            var limits = properties.limits();
            if (width > limits.maxImageDimension2D() || height > limits.maxImageDimension2D()
                || width > limits.maxFramebufferWidth() || height > limits.maxFramebufferHeight())
                throw new IllegalArgumentException("Iris extent exceeds device image/framebuffer limits");
            if (limits.maxPerStageDescriptorSamplers() < IrisUniforms.SAMPLER_BINDINGS
                || limits.maxPerStageDescriptorSampledImages() < IrisUniforms.SAMPLER_BINDINGS
                || limits.maxDescriptorSetSamplers() < IrisUniforms.SAMPLER_BINDINGS
                || limits.maxDescriptorSetSampledImages() < IrisUniforms.SAMPLER_BINDINGS)
                throw new IllegalArgumentException("Insufficient sampled-image descriptor limits for Iris");
            int attachments = resourcePlan == null ? 1 : resourcePlan.steps().stream()
                .mapToInt(step -> step.finalPass() ? 1 : step.writes().size()).max().orElse(1);
            if (attachments > limits.maxColorAttachments() || attachments > limits.maxFragmentOutputAttachments())
                throw new IllegalArgumentException("Iris draw buffers exceed device attachment limits");
        }
    }

    /** Host calls before opening its transient command buffer; may allocate pipelines/images. */
    public void prepare(IrisPassGraph graph, int width, int height) {
        validateGraph(graph);
        if (activeGraph != null && activeGraph != graph)
            throw new IllegalStateException("Retire Iris before replacing the active graph");
        resourcePlan = IrisResourcePlan.build(graph);
        ensureResources(resourcePlan.targets(), resourcePlan.hasFinal(), width, height);
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
        record(cmd, graph, engineColorImage, engineColorView, depthView, width, height, frameTimeCounter,
            frameCounter, near, far, frameTime, null, null);
    }

    public void record(VkCommandBuffer cmd, IrisPassGraph graph, long engineColorImage, long engineColorView,
                       long depthView, int width, int height, float frameTimeCounter, int frameCounter,
                       float near, float far, float frameTime,
                       io.github.andrexarlay.vulkancompat.rtx.frame.WorldFrame.Camera camera,
                       io.github.andrexarlay.vulkancompat.rtx.frame.WorldFrame.Camera previousCamera) {
        if (cmd == null) {
            throw new IllegalArgumentException("cmd must not be null");
        }
        if (graph == null || graph.passes().isEmpty()) {
            return;
        }
        boolean readsDepth = graph.passes().stream().anyMatch(pass -> pass.allSamplerBindings().contains(IrisUniforms.BINDING_DEPTHTEX0));
        if (engineColorImage == 0L || engineColorView == 0L || (readsDepth && depthView == 0L)) {
            throw new IllegalArgumentException("Invalid image handles: engineColorImage=" + engineColorImage
                + " engineColorView=" + engineColorView + " depthView=" + depthView);
        }
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Invalid dimensions: " + width + "x" + height);
        }

        prepare(graph, width, height);

        if (device == null || memory == null) {
            return;
        }

        if (camera == null && graph.passes().stream().anyMatch(pass -> !pass.allFrameUsed().isEmpty()))
            throw new IllegalArgumentException("Frame uniforms need a captured current camera");
        if (previousCamera == null && graph.passes().stream().flatMap(pass -> pass.allFrameUsed().stream())
            .anyMatch(name -> name.startsWith("gbufferPrevious") || name.equals("previousCameraPosition")))
            throw new IllegalArgumentException("Previous-frame uniforms need continuous camera history");
        // Device-local UBO updates participate in queue ordering. No mapped ring is overwritten while in flight.
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var bytes = stack.malloc(IrisUniforms.FRAME_BYTES);
            IrisFrameUniforms.write(bytes, camera, previousCamera);
            VkBarriers.fullMemoryBarrier(cmd);
            VK10.vkCmdUpdateBuffer(cmd, frameUniforms.handle, 0, bytes);
            VkBarriers.fullMemoryBarrier(cmd);
        }

        if (!imagesInitialized) {
            for (int target : allocatedTargets) for (RtMemory.Img image : colorImages[target])
                VkBarriers.transition(cmd, image.image, VK10.VK_IMAGE_ASPECT_COLOR_BIT,
                    VK10.VK_IMAGE_LAYOUT_UNDEFINED, VK10.VK_IMAGE_LAYOUT_GENERAL);
            if (presentation != null) VkBarriers.transition(cmd, presentation.image, VK10.VK_IMAGE_ASPECT_COLOR_BIT,
                VK10.VK_IMAGE_LAYOUT_UNDEFINED, VK10.VK_IMAGE_LAYOUT_GENERAL);
            imagesInitialized = true;
        }
        currentFrameSlot = (currentFrameSlot + 1) % FRAME_SLOTS;
        int slot = currentFrameSlot;
        // Reset this frame's logical routing, without reading any previous auxiliary contents.
        copyColor(cmd, engineColorImage, colorImages[0][0].image, width, height);
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

        List<IrisResourcePlan.Step> steps = resourcePlan.steps();
        for (int passIdx = 0; passIdx < steps.size(); passIdx++) {
            var step = steps.get(passIdx);
            var pass = step.pass();
            Long pipeHandle = pipelines.get(pass.name());
            if (pipeHandle == null) throw new IllegalStateException("Pipeline not found: " + pass.name());
            RtMemory.Img fallback = colorImages[0][step.fallbackBank()];
            int attachmentCount = step.finalPass() ? 1 : step.writes().size();
            long[] targetViews = new long[attachmentCount];
            VkBarriers.fullMemoryBarrier(cmd);
            if (step.finalPass()) {
                copyColor(cmd, fallback.image, presentation.image, width, height);
                targetViews[0] = presentation.view;
            } else {
                for (int i = 0; i < step.writes().size(); i++) {
                    var output = step.writes().get(i);
                    var destination = colorImages[output.target()][output.bank()];
                    if (output.preserve()) copyColor(cmd, colorImages[output.target()][output.previousBank()].image,
                        destination.image, width, height);
                    else clearColor(cmd, destination.image);
                    targetViews[i] = destination.view;
                }
            }
            VkBarriers.fullMemoryBarrier(cmd);
            long dSet = descriptorSets[slot][passIdx];
            try (MemoryStack stack = MemoryStack.stackPush()) {
                var bindings = IrisUniforms.samplerBindings();
                var infos = VkDescriptorImageInfo.calloc(IrisUniforms.SAMPLER_BINDINGS, stack);
                var writes = VkWriteDescriptorSet.calloc(IrisUniforms.SAMPLER_BINDINGS + 1, stack);
                for (int i = 0; i < bindings.size(); i++) {
                    int binding = bindings.get(i), target = IrisUniforms.colorIndex(binding);
                    long view = fallback.view;
                    if (binding == IrisUniforms.BINDING_DEPTHTEX0) {
                        if (pass.allSamplerBindings().contains(binding)) view = depthView;
                    } else if (target >= 0 && step.readBanks().containsKey(target)) {
                        view = colorImages[target][step.readBanks().get(target)].view;
                    }
                    infos.get(i).sampler(binding == IrisUniforms.BINDING_DEPTHTEX0 ? depthSampler : colorSampler)
                        .imageView(view).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
                    writes.get(i).sType$Default().dstSet(dSet).dstBinding(binding)
                        .descriptorType(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1)
                        .pImageInfo(infos.slice(i, 1));
                    if (descriptorsInitialized[slot][passIdx] && boundImages[slot][passIdx][i] != view)
                        throw new IllegalStateException("Retire Iris before replacing sampled image descriptors");
                }
                var bufferInfo = VkDescriptorBufferInfo.calloc(1, stack);
                bufferInfo.get(0).buffer(frameUniforms.handle).offset(0).range(IrisUniforms.FRAME_BYTES);
                writes.get(IrisUniforms.SAMPLER_BINDINGS).sType$Default().dstSet(dSet).dstBinding(IrisUniforms.BINDING_FRAME)
                    .descriptorType(VK10.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).descriptorCount(1).pBufferInfo(bufferInfo);
                if (!descriptorsInitialized[slot][passIdx]) {
                    VK10.vkUpdateDescriptorSets(device, writes, null);
                    for (int i = 0; i < bindings.size(); i++) boundImages[slot][passIdx][i] = infos.get(i).imageView();
                    descriptorsInitialized[slot][passIdx] = true;
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
                VkRenderingAttachmentInfo.Buffer colorAttachment = VkRenderingAttachmentInfo.calloc(attachmentCount, stack);
                for (int i = 0; i < attachmentCount; i++) colorAttachment.get(i).sType$Default()
                    .imageView(targetViews[i]).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL)
                    .loadOp(VK10.VK_ATTACHMENT_LOAD_OP_LOAD).storeOp(VK10.VK_ATTACHMENT_STORE_OP_STORE);

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

            VkBarriers.fullMemoryBarrier(cmd);
        }
        var result = resourcePlan.hasFinal() ? presentation : colorImages[0][resourcePlan.resultBank()];
        copyColor(cmd, result.image, engineColorImage, width, height);
        VkBarriers.fullMemoryBarrier(cmd);
    }

    private static void copyColor(VkCommandBuffer cmd, long source, long destination, int width, int height) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var region = VkImageCopy.calloc(1, stack);
            region.get(0).srcSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
            region.get(0).dstSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
            region.get(0).extent().set(width, height, 1);
            VK10.vkCmdCopyImage(cmd, source, VK10.VK_IMAGE_LAYOUT_GENERAL,
                destination, VK10.VK_IMAGE_LAYOUT_GENERAL, region);
        }
    }

    private static void clearColor(VkCommandBuffer cmd, long image) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var clear = VkClearColorValue.calloc(stack);
            var range = VkImageSubresourceRange.calloc(1, stack);
            range.get(0).aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
            VK10.vkCmdClearColorImage(cmd, image, VK10.VK_IMAGE_LAYOUT_GENERAL, clear, range);
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
        var bindings = VkDescriptorSetLayoutBinding.calloc(IrisUniforms.SAMPLER_BINDINGS + 1, stack);
        var samplerBindings = IrisUniforms.samplerBindings();
        for (int i = 0; i < samplerBindings.size(); i++) bindings.get(i).binding(samplerBindings.get(i))
            .descriptorType(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1)
            .stageFlags(VK10.VK_SHADER_STAGE_VERTEX_BIT | VK10.VK_SHADER_STAGE_FRAGMENT_BIT);
        bindings.get(IrisUniforms.SAMPLER_BINDINGS).binding(IrisUniforms.BINDING_FRAME)
            .descriptorType(VK10.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).descriptorCount(1)
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
        return createColorBlendState(stack, 1);
    }

    public static VkPipelineColorBlendStateCreateInfo createColorBlendState(MemoryStack stack, int count) {
        VkPipelineColorBlendAttachmentState.Buffer blendAttachments = VkPipelineColorBlendAttachmentState.calloc(count, stack);
        for (int i = 0; i < count; i++) blendAttachments.get(i).blendEnable(false)
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
        return createPipelineRenderingCreateInfo(stack, colorFormat, 1);
    }

    public static VkPipelineRenderingCreateInfo createPipelineRenderingCreateInfo(MemoryStack stack, int colorFormat, int count) {
        var formats = stack.mallocInt(count);
        for (int i = 0; i < count; i++) formats.put(i, colorFormat);
        return VkPipelineRenderingCreateInfo.calloc(stack).sType$Default().pColorAttachmentFormats(formats);
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
            if (pass.vertexSpirv() == null || pass.fragmentSpirv() == null) {
                throw new IllegalArgumentException("Pass " + pass.name() + " has null SPIR-V bytecode");
            }
            if (pass.vertexTranslation() == null || pass.fragmentTranslation() == null)
                throw new IllegalArgumentException("Pass " + pass.name() + " has no translated resource metadata");
            Set<Integer> bindings = pass.allSamplerBindings();
            for (int b : bindings) {
                if (IrisUniforms.colorIndex(b) < 0 && b != IrisUniforms.BINDING_DEPTHTEX0) {
                    throw new IllegalArgumentException("Pass " + pass.name() + " has unsupported sampler binding: " + b);
                }
            }
        }
        IrisResourcePlan.build(graph);
    }

    public static List<PassExecutionStep> planPassExecution(IrisPassGraph graph, int frameSlot) {
        List<PassExecutionStep> plan = new ArrayList<>();
        if (graph == null || graph.passes().isEmpty()) {
            return plan;
        }
        var steps = IrisResourcePlan.build(graph).steps();
        for (int i = 0; i < steps.size(); i++) {
            var step = steps.get(i);
            String readName = step.readBanks().isEmpty() ? targetName(0, step.fallbackBank())
                : step.readBanks().entrySet().stream().map(entry -> targetName(entry.getKey(), entry.getValue()))
                    .collect(java.util.stream.Collectors.joining(", "));
            String writeName = step.finalPass() ? "presentation" : step.writes().stream()
                .map(output -> targetName(output.target(), output.bank())).collect(java.util.stream.Collectors.joining(", "));
            plan.add(new PassExecutionStep(i, step.pass().name(), step.finalPass(), readName, writeName, frameSlot, i));
        }
        return Collections.unmodifiableList(plan);
    }

    private static String targetName(int target, int bank) { return "colortex" + target + "[" + bank + "]"; }

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
        return colorImages[0][0];
    }

    public RtMemory.Img colortex1() {
        return colorImages[0][1];
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
            if (frameUniforms != null) { memory.destroy(frameUniforms); frameUniforms = null; }
            for (int target = 0; target < colorImages.length; target++) for (int bank = 0; bank < 2; bank++) {
                if (colorImages[target][bank] != null) memory.destroy(colorImages[target][bank]);
                colorImages[target][bank] = null;
            }
            if (presentation != null) { memory.destroy(presentation); presentation = null; }
        }
        allocatedTargets = Set.of(); allocatedWidth = allocatedHeight = 0; resourcePlan = null;
        imagesInitialized = false;
        activeGraph = null;
    }
}
