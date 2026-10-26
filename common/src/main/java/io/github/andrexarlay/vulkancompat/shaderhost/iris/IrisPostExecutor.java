package io.github.andrexarlay.vulkancompat.shaderhost.iris;

import io.github.andrexarlay.vulkancompat.rt.world.RtVulkanPipelineCache;
import io.github.andrexarlay.vulkancompat.rt.world.RtFailure;
import io.github.andrexarlay.vulkancompat.rt.world.RtMemory;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.VkBarriers;
import io.github.andrexarlay.vulkancompat.shaderhost.glsl.IrisUniforms;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRDynamicRendering;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VK11;
import org.lwjgl.vulkan.VK13;
import org.lwjgl.vulkan.VkClearColorValue;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDescriptorBufferInfo;
import org.lwjgl.vulkan.VkDescriptorImageInfo;
import org.lwjgl.vulkan.VkDescriptorPoolCreateInfo;
import org.lwjgl.vulkan.VkDescriptorPoolSize;
import org.lwjgl.vulkan.VkDescriptorSetAllocateInfo;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkFormatProperties;
import org.lwjgl.vulkan.VkGraphicsPipelineCreateInfo;
import org.lwjgl.vulkan.VkImageBlit;
import org.lwjgl.vulkan.VkImageCopy;
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
 * Post resource-graph executor on the host device/queue. Each logical colortex target owns two banks in its own format
 * (from {@code colortexNFormat}), with a mip chain when a program enables mipmaps for it. Targets keep their contents
 * across frames when {@code colortexNClear = false}; others are cleared to their clear color at the start of each frame.
 * A final pass writes a presentation image in the host format; the host color is overwritten only after the complete
 * graph records. All owned images and descriptors are retired together with this executor (graph, extent, format,
 * device or world change), which is also what ends a persistent target's history.
 */
public final class IrisPostExecutor implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger("Bedray/IrisPostExecutor");

    public static final int DEFAULT_COLOR_FORMAT = VK10.VK_FORMAT_R8G8B8A8_UNORM;
    public static final int FRAME_SLOTS = 3;
    public static final int MAX_PASS_SETS = 32;
    private static final long MAX_IMAGE_BYTES = 768L * 1024 * 1024;
    private static final int ALL_STAGES = VK10.VK_SHADER_STAGE_VERTEX_BIT | VK10.VK_SHADER_STAGE_FRAGMENT_BIT;

    /** Execution plan step record used for tracking and testing pass execution order. */
    public record PassExecutionStep(int passIndex, String passName, boolean isFinal, String readTarget, String writeTarget,
                                    int slot, int setIndex) {}

    private final VkDevice device;
    private final RtMemory memory;
    private final int colorFormat;

    private long colorSampler = 0L;
    private long mipSampler = 0L;
    private long depthSampler = 0L;
    private long descriptorSetLayout = 0L;
    private long pipelineLayout = 0L;
    private long descriptorPool = 0L;
    private long[][] descriptorSets; // [FRAME_SLOTS][MAX_PASS_SETS]
    private int currentFrameSlot = -1;
    private final long[][][] boundImages = new long[FRAME_SLOTS][MAX_PASS_SETS][IrisUniforms.SAMPLER_BINDINGS];
    private final boolean[][] descriptorsInitialized = new boolean[FRAME_SLOTS][MAX_PASS_SETS];
    private final IrisImage[][] colorImages = new IrisImage[IrisUniforms.COLOR_TARGETS][2];
    private RtMemory.Img presentation;
    private Set<Integer> allocatedTargets = Set.of();
    private int allocatedWidth, allocatedHeight;
    private IrisResourcePlan resourcePlan;
    private RtMemory.Buf frameUniforms;
    private RtMemory.Buf worldUniforms;
    private boolean imagesInitialized = false;

    private final Map<String, Long> pipelines = new LinkedHashMap<>();
    private IrisPassGraph activeGraph;

    /** Host color format RGBA8_UNORM. */
    public IrisPostExecutor(VkDevice device, RtMemory memory) {
        this(device, memory, DEFAULT_COLOR_FORMAT);
    }

    /** @param colorFormat Vulkan format of the host world image (presentation image and world copy source) */
    public IrisPostExecutor(VkDevice device, RtMemory memory, int colorFormat) {
        this.device = Objects.requireNonNull(device, "device");
        this.memory = Objects.requireNonNull(memory, "memory");
        this.colorFormat = colorFormat;
        try { initVulkan(); }
        catch (RuntimeException | LinkageError error) { cleanup(); throw error; }
    }

    /** Testing constructor that skips GPU initialization. */
    IrisPostExecutor() {
        this.device = null;
        this.memory = null;
        this.colorFormat = DEFAULT_COLOR_FORMAT;
    }

    public static IrisPostExecutor createForTesting() {
        return new IrisPostExecutor();
    }

    private void initVulkan() {
        LOGGER.info("[IrisPostExecutor] Initializing Vulkan runtime resources (host format={})...", colorFormat);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            frameUniforms = memory.createBuffer("iris_frame", IrisUniforms.FRAME_BYTES,
                VK10.VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT | VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT, false);
            worldUniforms = memory.createBuffer("iris_world", IrisUniforms.WORLD_BYTES,
                VK10.VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT | VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT, false);
            LongBuffer p = stack.mallocLong(1);

            // Iris samples colortex with GL_LINEAR, and GL_LINEAR_MIPMAP_LINEAR for buffers a program mipmaps.
            VkSamplerCreateInfo samplerInfo = VkSamplerCreateInfo.calloc(stack).sType$Default()
                .magFilter(VK10.VK_FILTER_LINEAR).minFilter(VK10.VK_FILTER_LINEAR)
                .mipmapMode(VK10.VK_SAMPLER_MIPMAP_MODE_NEAREST)
                .addressModeU(VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                .addressModeV(VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                .addressModeW(VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                .maxLod(0.0f);
            RtFailure.check(VK10.vkCreateSampler(device, samplerInfo, null, p), "create_color_sampler");
            this.colorSampler = p.get(0);
            samplerInfo.mipmapMode(VK10.VK_SAMPLER_MIPMAP_MODE_LINEAR).maxLod(VK10.VK_LOD_CLAMP_NONE);
            RtFailure.check(VK10.vkCreateSampler(device, samplerInfo, null, p), "create_mip_sampler");
            this.mipSampler = p.get(0);
            samplerInfo.magFilter(VK10.VK_FILTER_NEAREST).minFilter(VK10.VK_FILTER_NEAREST)
                .mipmapMode(VK10.VK_SAMPLER_MIPMAP_MODE_NEAREST).maxLod(0.0f);
            RtFailure.check(VK10.vkCreateSampler(device, samplerInfo, null, p), "create_depth_sampler");
            this.depthSampler = p.get(0);

            VkDescriptorSetLayoutBinding.Buffer bindings = createDescriptorBindings(stack);
            RtFailure.check(VK10.vkCreateDescriptorSetLayout(device, VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default()
                .pBindings(bindings), null, p), "create_descriptor_set_layout");
            this.descriptorSetLayout = p.get(0);

            RtFailure.check(VK10.vkCreatePipelineLayout(device, VkPipelineLayoutCreateInfo.calloc(stack).sType$Default()
                .pSetLayouts(stack.longs(this.descriptorSetLayout)).pPushConstantRanges(createPushConstantRange(stack)), null, p),
                "create_pipeline_layout");
            this.pipelineLayout = p.get(0);

            int totalSets = FRAME_SLOTS * MAX_PASS_SETS;
            VkDescriptorPoolSize.Buffer poolSizes = VkDescriptorPoolSize.calloc(2, stack);
            poolSizes.get(0).type(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(totalSets * IrisUniforms.SAMPLER_BINDINGS);
            poolSizes.get(1).type(VK10.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).descriptorCount(totalSets * 2);
            RtFailure.check(VK10.vkCreateDescriptorPool(device, VkDescriptorPoolCreateInfo.calloc(stack).sType$Default()
                .maxSets(totalSets).pPoolSizes(poolSizes), null, p), "create_descriptor_pool");
            this.descriptorPool = p.get(0);

            this.descriptorSets = new long[FRAME_SLOTS][MAX_PASS_SETS];
            LongBuffer layouts = stack.mallocLong(MAX_PASS_SETS);
            for (int i = 0; i < MAX_PASS_SETS; i++) layouts.put(i, this.descriptorSetLayout);
            for (int slot = 0; slot < FRAME_SLOTS; slot++) {
                LongBuffer pSets = stack.mallocLong(MAX_PASS_SETS);
                RtFailure.check(VK10.vkAllocateDescriptorSets(device, VkDescriptorSetAllocateInfo.calloc(stack).sType$Default()
                    .descriptorPool(this.descriptorPool).pSetLayouts(layouts), pSets), "alloc_descriptor_sets");
                for (int i = 0; i < MAX_PASS_SETS; i++) this.descriptorSets[slot][i] = pSets.get(i);
            }
        } catch (Throwable t) {
            cleanup();
            throw t;
        }
    }

    /** Builds dynamic-rendering graphics pipelines for all passes, with each pass's attachment formats and write masks. */
    public synchronized void buildPipelines(IrisPassGraph graph) {
        Objects.requireNonNull(graph, "graph");
        if (this.activeGraph == graph && !this.pipelines.isEmpty()) return;
        validateGraph(graph);
        if (device != null) {
            RtFailure.check(VK10.vkDeviceWaitIdle(device), "iris_pipeline_retirement_wait");
            for (long pipe : this.pipelines.values()) VK10.vkDestroyPipeline(device, pipe, null);
            this.pipelines.clear();
            IrisRenderTargets.Resolution targets = graph.targets();
            try (MemoryStack stack = MemoryStack.stackPush()) {
                for (IrisPassGraph.Pass pass : graph.passes()) {
                    int[] formats;
                    boolean[] alphaLocked;
                    if (pass.isFinal()) {
                        formats = new int[] {colorFormat};
                        alphaLocked = new boolean[] {false};
                    } else {
                        List<Integer> outputs = pass.drawBuffers();
                        formats = new int[outputs.size()];
                        alphaLocked = new boolean[outputs.size()];
                        for (int i = 0; i < outputs.size(); i++) {
                            var format = targets.target(outputs.get(i)).format();
                            formats[i] = format.vkFormat();
                            alphaLocked[i] = format.alphaLocked();
                        }
                    }
                    this.pipelines.put(pass.name(), createPassPipeline(stack, pass, formats, alphaLocked));
                }
            }
        }
        this.activeGraph = graph;
        LOGGER.info("[IrisPostExecutor] Built {} Vulkan graphics pipelines for pack '{}'.", graph.passCount(), graph.packName());
    }

    private long createPassPipeline(MemoryStack stack, IrisPassGraph.Pass pass, int[] formats, boolean[] alphaLocked) {
        ByteBuffer vSpv = pass.vertexSpirv().duplicate();
        vSpv.rewind();
        ByteBuffer fSpv = pass.fragmentSpirv().duplicate();
        fSpv.rewind();

        LongBuffer p = stack.mallocLong(1);
        RtFailure.check(VK10.vkCreateShaderModule(device, VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(vSpv), null, p),
            "create_vert_module:" + pass.name());
        long vMod = p.get(0);
        int err = VK10.vkCreateShaderModule(device, VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(fSpv), null, p);
        if (err != VK10.VK_SUCCESS) {
            VK10.vkDestroyShaderModule(device, vMod, null);
            throw new RtFailure("create_frag_module:" + pass.name(), "VkResult=" + err);
        }
        long fMod = p.get(0);

        try {
            VkPipelineShaderStageCreateInfo.Buffer stages = VkPipelineShaderStageCreateInfo.calloc(2, stack);
            stages.get(0).sType$Default().stage(VK10.VK_SHADER_STAGE_VERTEX_BIT).module(vMod).pName(stack.UTF8("main"));
            stages.get(1).sType$Default().stage(VK10.VK_SHADER_STAGE_FRAGMENT_BIT).module(fMod).pName(stack.UTF8("main"));

            VkGraphicsPipelineCreateInfo.Buffer pipelineInfo = VkGraphicsPipelineCreateInfo.calloc(1, stack);
            pipelineInfo.get(0).sType$Default()
                .pNext(createPipelineRenderingCreateInfo(stack, formats).address())
                .pStages(stages)
                .pVertexInputState(VkPipelineVertexInputStateCreateInfo.calloc(stack).sType$Default())
                .pInputAssemblyState(VkPipelineInputAssemblyStateCreateInfo.calloc(stack).sType$Default()
                    .topology(VK10.VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST).primitiveRestartEnable(false))
                .pViewportState(VkPipelineViewportStateCreateInfo.calloc(stack).sType$Default().viewportCount(1).scissorCount(1))
                .pRasterizationState(createRasterizationState(stack))
                .pMultisampleState(VkPipelineMultisampleStateCreateInfo.calloc(stack).sType$Default()
                    .rasterizationSamples(VK10.VK_SAMPLE_COUNT_1_BIT).sampleShadingEnable(false))
                .pDepthStencilState(VkPipelineDepthStencilStateCreateInfo.calloc(stack).sType$Default()
                    .depthTestEnable(false).depthWriteEnable(false).depthCompareOp(VK10.VK_COMPARE_OP_ALWAYS).stencilTestEnable(false))
                .pColorBlendState(createColorBlendState(stack, alphaLocked))
                .pDynamicState(VkPipelineDynamicStateCreateInfo.calloc(stack).sType$Default()
                    .pDynamicStates(stack.ints(VK10.VK_DYNAMIC_STATE_VIEWPORT, VK10.VK_DYNAMIC_STATE_SCISSOR)))
                .layout(this.pipelineLayout)
                .renderPass(VK10.VK_NULL_HANDLE)
                .subpass(0);
            RtFailure.check(RtVulkanPipelineCache.createGraphics(device, pipelineInfo, null, p), "create_graphics_pipeline:" + pass.name());
            return p.get(0);
        } finally {
            VK10.vkDestroyShaderModule(device, vMod, null);
            VK10.vkDestroyShaderModule(device, fMod, null);
        }
    }

    /** Legacy color0 allocation seam; graph callers use prepare before opening a command buffer. */
    public synchronized void ensure(int width, int height) {
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("Invalid Iris extent");
    }

    private synchronized void ensureResources(IrisResourcePlan plan, int width, int height) {
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("Invalid Iris extent");
        if (memory == null) return;
        Set<Integer> targets = plan.targets();
        if (allocatedWidth == width && allocatedHeight == height && allocatedTargets.equals(targets)
            && (presentation != null) == plan.hasFinal()) return;
        // The host must submit queued uses and retire this executor before target/graph changes.
        if (!allocatedTargets.isEmpty()) throw new IllegalStateException("Retire Iris before changing image resources");
        checkDeviceResources(plan, width, height);
        int levels = IrisImage.fullChain(width, height);
        int usage = VK10.VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK10.VK_IMAGE_USAGE_SAMPLED_BIT
            | VK10.VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK10.VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
        try {
            for (int target : targets) {
                var format = plan.settings().target(target).format();
                int targetLevels = plan.mipmappedTargets().contains(target) ? levels : 1;
                for (int bank = 0; bank < 2; bank++) colorImages[target][bank] = IrisImage.create(device,
                    "iris_colortex" + target + "_" + bank, width, height, format.vkFormat(), targetLevels, usage);
            }
            if (plan.hasFinal()) presentation = memory.createImage("iris_presentation", width, height, colorFormat, usage);
        } catch (RuntimeException e) {
            destroyImages();
            throw e;
        }
        allocatedTargets = Set.copyOf(targets);
        allocatedWidth = width;
        allocatedHeight = height;
        imagesInitialized = false;
        LOGGER.info("[IrisPostExecutor] Allocated {} color target pairs (mipmapped {}), final={}, {}x{}",
            targets.size(), plan.mipmappedTargets(), plan.hasFinal(), width, height);
    }

    /** Refuses formats, extents, budgets and limits this device cannot serve, naming the GL format and target. */
    private void checkDeviceResources(IrisResourcePlan plan, int width, int height) {
        long pixels = Math.multiplyExact((long) width, height);
        long bytes = plan.hasFinal() ? pixels * 16 : 0;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var props = VkFormatProperties.calloc(stack);
            for (int target : plan.targets()) {
                var format = plan.settings().target(target).format();
                long size = Math.multiplyExact(pixels, 2L * format.bytesPerPixel());
                if (plan.mipmappedTargets().contains(target)) size += size / 3;
                bytes = Math.addExact(bytes, size);
                VK10.vkGetPhysicalDeviceFormatProperties(device.getPhysicalDevice(), format.vkFormat(), props);
                int need = VK10.VK_FORMAT_FEATURE_COLOR_ATTACHMENT_BIT | VK10.VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT
                    | VK10.VK_FORMAT_FEATURE_SAMPLED_IMAGE_FILTER_LINEAR_BIT
                    | VK11.VK_FORMAT_FEATURE_TRANSFER_SRC_BIT | VK11.VK_FORMAT_FEATURE_TRANSFER_DST_BIT;
                if (plan.mipmappedTargets().contains(target)) need |= VK10.VK_FORMAT_FEATURE_BLIT_SRC_BIT | VK10.VK_FORMAT_FEATURE_BLIT_DST_BIT;
                if (target == 0 && format.vkFormat() != colorFormat)
                    need |= VK10.VK_FORMAT_FEATURE_BLIT_SRC_BIT | VK10.VK_FORMAT_FEATURE_BLIT_DST_BIT;
                if ((props.optimalTilingFeatures() & need) != need)
                    throw new IllegalArgumentException("colortex" + target + " format " + format.glName()
                        + " lacks color-attachment/linear-sampling/copy" + ((need & VK10.VK_FORMAT_FEATURE_BLIT_SRC_BIT) != 0 ? "/blit" : "")
                        + " support on this device");
            }
            if (plan.settings().target(0).format().vkFormat() != colorFormat) {
                VK10.vkGetPhysicalDeviceFormatProperties(device.getPhysicalDevice(), colorFormat, props);
                int need = VK10.VK_FORMAT_FEATURE_BLIT_SRC_BIT | VK10.VK_FORMAT_FEATURE_BLIT_DST_BIT;
                if ((props.optimalTilingFeatures() & need) != need)
                    throw new IllegalArgumentException("host color format " + colorFormat + " cannot be blitted to/from colortex0 "
                        + plan.settings().target(0).format().glName());
            }
            if (bytes > MAX_IMAGE_BYTES) throw new IllegalArgumentException("Iris color images exceed the 768 MiB budget");
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
                throw new IllegalArgumentException("Insufficient sampled-image descriptor limits for Iris ("
                    + IrisUniforms.SAMPLER_BINDINGS + " needed)");
            int attachments = plan.steps().stream().mapToInt(step -> step.finalPass() ? 1 : step.writes().size()).max().orElse(1);
            if (attachments > limits.maxColorAttachments() || attachments > limits.maxFragmentOutputAttachments())
                throw new IllegalArgumentException("Iris draw buffers (" + attachments + ") exceed this device's "
                    + Math.min(limits.maxColorAttachments(), limits.maxFragmentOutputAttachments()) + " color attachments");
        }
    }

    /** Host calls before opening its transient command buffer; may allocate pipelines/images. */
    public void prepare(IrisPassGraph graph, int width, int height) {
        validateGraph(graph);
        if (activeGraph != null && activeGraph != graph)
            throw new IllegalStateException("Retire Iris before replacing the active graph");
        resourcePlan = IrisResourcePlan.build(graph);
        ensureResources(resourcePlan, width, height);
        buildPipelines(graph);
    }

    /** Legacy signature without camera data. */
    public void record(VkCommandBuffer cmd, IrisPassGraph graph, long engineColorImage, long engineColorView, long depthView,
                       int width, int height, float frameTimeCounter, int frameCounter, float near, float far, float frameTime) {
        record(cmd, graph, engineColorImage, engineColorView, depthView, width, height, frameTimeCounter,
            frameCounter, near, far, frameTime, null, null);
    }

    public void record(VkCommandBuffer cmd, IrisPassGraph graph, long engineColorImage, long engineColorView,
                       long depthView, int width, int height, float frameTimeCounter, int frameCounter,
                       float near, float far, float frameTime,
                       io.github.andrexarlay.vulkancompat.rtx.frame.WorldFrame.Camera camera,
                       io.github.andrexarlay.vulkancompat.rtx.frame.WorldFrame.Camera previousCamera) {
        record(cmd, graph, engineColorImage, engineColorView, depthView, width, height, frameTimeCounter, frameCounter, near, far,
            frameTime, camera, previousCamera, null);
    }

    /** @param world the {@link IrisUniforms#WORLD_BYTES} world block of this frame; null only if no pass reads it */
    public void record(VkCommandBuffer cmd, IrisPassGraph graph, long engineColorImage, long engineColorView,
                       long depthView, int width, int height, float frameTimeCounter, int frameCounter,
                       float near, float far, float frameTime,
                       io.github.andrexarlay.vulkancompat.rtx.frame.WorldFrame.Camera camera,
                       io.github.andrexarlay.vulkancompat.rtx.frame.WorldFrame.Camera previousCamera, ByteBuffer world) {
        if (cmd == null) throw new IllegalArgumentException("cmd must not be null");
        if (graph == null || graph.passes().isEmpty()) return;
        boolean readsDepth = graph.passes().stream().anyMatch(pass -> pass.allSamplerBindings().contains(IrisUniforms.BINDING_DEPTHTEX0));
        if (engineColorImage == 0L || engineColorView == 0L || (readsDepth && depthView == 0L)) {
            throw new IllegalArgumentException("Invalid image handles: engineColorImage=" + engineColorImage
                + " engineColorView=" + engineColorView + " depthView=" + depthView);
        }
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("Invalid dimensions: " + width + "x" + height);

        prepare(graph, width, height);
        if (device == null || memory == null) return;

        if (camera == null && graph.passes().stream().anyMatch(pass -> !pass.allFrameUsed().isEmpty()))
            throw new IllegalArgumentException("Frame uniforms need a captured current camera");
        if (previousCamera == null && graph.passes().stream().flatMap(pass -> pass.allFrameUsed().stream())
            .anyMatch(name -> name.startsWith("gbufferPrevious") || name.equals("previousCameraPosition")))
            throw new IllegalArgumentException("Previous-frame uniforms need continuous camera history");
        if (world == null && !graph.worldUsed().isEmpty())
            throw new IllegalArgumentException("World uniforms " + graph.worldUsed() + " need this frame's world state");
        // Device-local UBO updates participate in queue ordering. No mapped ring is overwritten while in flight.
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var bytes = stack.malloc(IrisUniforms.FRAME_BYTES);
            IrisFrameUniforms.write(bytes, camera, previousCamera);
            VkBarriers.fullMemoryBarrier(cmd);
            VK10.vkCmdUpdateBuffer(cmd, frameUniforms.handle, 0, bytes);
            if (world != null) {
                if (world.remaining() != IrisUniforms.WORLD_BYTES) throw new IllegalArgumentException("World block must be "
                    + IrisUniforms.WORLD_BYTES + " bytes");
                VK10.vkCmdUpdateBuffer(cmd, worldUniforms.handle, 0, world);
            }
            VkBarriers.fullMemoryBarrier(cmd);
        }
        IrisRenderTargets.Resolution settings = resourcePlan.settings();

        if (!imagesInitialized) {
            // First frame after allocation: every bank starts at its clear color, so history begins defined (Iris full clear).
            for (int target : allocatedTargets) for (IrisImage image : colorImages[target]) image.initialize(cmd);
            if (presentation != null) VkBarriers.transition(cmd, presentation.image, VK10.VK_IMAGE_ASPECT_COLOR_BIT,
                VK10.VK_IMAGE_LAYOUT_UNDEFINED, VK10.VK_IMAGE_LAYOUT_GENERAL);
            VkBarriers.fullMemoryBarrier(cmd);
            for (int target : allocatedTargets) for (IrisImage image : colorImages[target])
                clearColor(cmd, image.image, settings.target(target).clearColor(), image.levels);
            imagesInitialized = true;
        }
        currentFrameSlot = (currentFrameSlot + 1) % FRAME_SLOTS;
        int slot = currentFrameSlot;
        VkBarriers.fullMemoryBarrier(cmd);
        // Frame start: world image into colortex0, clear color into cleared targets; persistent targets keep bank 0.
        transfer(cmd, engineColorImage, colorFormat, colorImages[0][0].image, colorImages[0][0].format, width, height);
        for (int target : resourcePlan.frameClears()) clearColor(cmd, colorImages[target][0].image, settings.target(target).clearColor(), 1);
        VkBarriers.fullMemoryBarrier(cmd);

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
            IrisImage fallback = colorImages[0][step.fallbackBank()];
            int attachmentCount = step.finalPass() ? 1 : step.writes().size();
            long[] targetViews = new long[attachmentCount];
            VkBarriers.fullMemoryBarrier(cmd);
            for (int target : step.mipmapped()) generateMips(cmd, colorImages[target][step.readBanks().get(target)]);
            if (step.finalPass()) {
                transfer(cmd, fallback.image, fallback.format, presentation.image, colorFormat, width, height);
                targetViews[0] = presentation.view;
            } else {
                for (int i = 0; i < step.writes().size(); i++) {
                    var output = step.writes().get(i);
                    var destination = colorImages[output.target()][output.bank()];
                    copyColor(cmd, colorImages[output.target()][output.previousBank()].image, destination.image, width, height);
                    targetViews[i] = destination.baseView;
                }
            }
            VkBarriers.fullMemoryBarrier(cmd);
            long dSet = descriptorSets[slot][passIdx];
            try (MemoryStack stack = MemoryStack.stackPush()) {
                var bindings = IrisUniforms.samplerBindings();
                var infos = VkDescriptorImageInfo.calloc(IrisUniforms.SAMPLER_BINDINGS, stack);
                var writes = VkWriteDescriptorSet.calloc(IrisUniforms.SAMPLER_BINDINGS + 2, stack);
                for (int i = 0; i < bindings.size(); i++) {
                    int binding = bindings.get(i), target = IrisUniforms.colorIndex(binding);
                    long view = fallback.baseView;
                    long sampler = colorSampler;
                    if (binding == IrisUniforms.BINDING_DEPTHTEX0) {
                        sampler = depthSampler;
                        if (pass.allSamplerBindings().contains(binding)) view = depthView;
                    } else if (target >= 0 && step.readBanks().containsKey(target)) {
                        IrisImage source = colorImages[target][step.readBanks().get(target)];
                        boolean mips = step.mipmapped().contains(target);
                        view = mips ? source.fullView : source.baseView;
                        if (mips) sampler = mipSampler;
                    }
                    infos.get(i).sampler(sampler).imageView(view).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
                    writes.get(i).sType$Default().dstSet(dSet).dstBinding(binding)
                        .descriptorType(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1)
                        .pImageInfo(infos.slice(i, 1));
                    if (descriptorsInitialized[slot][passIdx] && boundImages[slot][passIdx][i] != view)
                        throw new IllegalStateException("Retire Iris before replacing sampled image descriptors");
                }
                var bufferInfo = VkDescriptorBufferInfo.calloc(2, stack);
                bufferInfo.get(0).buffer(frameUniforms.handle).offset(0).range(IrisUniforms.FRAME_BYTES);
                bufferInfo.get(1).buffer(worldUniforms.handle).offset(0).range(IrisUniforms.WORLD_BYTES);
                writes.get(IrisUniforms.SAMPLER_BINDINGS).sType$Default().dstSet(dSet).dstBinding(IrisUniforms.BINDING_FRAME)
                    .descriptorType(VK10.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).descriptorCount(1).pBufferInfo(bufferInfo.slice(0, 1));
                writes.get(IrisUniforms.SAMPLER_BINDINGS + 1).sType$Default().dstSet(dSet).dstBinding(IrisUniforms.BINDING_WORLD)
                    .descriptorType(VK10.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).descriptorCount(1).pBufferInfo(bufferInfo.slice(1, 1));
                if (!descriptorsInitialized[slot][passIdx]) {
                    VK10.vkUpdateDescriptorSets(device, writes, null);
                    for (int i = 0; i < bindings.size(); i++) boundImages[slot][passIdx][i] = infos.get(i).imageView();
                    descriptorsInitialized[slot][passIdx] = true;
                }

                VK10.vkCmdBindPipeline(cmd, VK10.VK_PIPELINE_BIND_POINT_GRAPHICS, pipeHandle);
                VK10.vkCmdBindDescriptorSets(cmd, VK10.VK_PIPELINE_BIND_POINT_GRAPHICS, pipelineLayout, 0, stack.longs(dSet), null);
                ByteBuffer pushBuf = stack.malloc(IrisUniforms.PUSH_BYTES);
                fillPushConstants(pushBuf, width, height, frameTimeCounter, frameCounter, near, far, frameTime);
                VK10.vkCmdPushConstants(cmd, pipelineLayout, ALL_STAGES, 0, pushBuf);

                VkRenderingAttachmentInfo.Buffer colorAttachment = VkRenderingAttachmentInfo.calloc(attachmentCount, stack);
                for (int i = 0; i < attachmentCount; i++) colorAttachment.get(i).sType$Default()
                    .imageView(targetViews[i]).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL)
                    .loadOp(VK10.VK_ATTACHMENT_LOAD_OP_LOAD).storeOp(VK10.VK_ATTACHMENT_STORE_OP_STORE);
                VkRenderingInfo renderingInfo = VkRenderingInfo.calloc(stack).sType$Default()
                    .renderArea(ra -> ra.extent().set(width, height)).layerCount(1).pColorAttachments(colorAttachment);
                beginRendering(cmd, renderingInfo);
                try {
                    VK10.vkCmdDraw(cmd, 3, 1, 0, 0);
                } finally {
                    endRendering(cmd);
                }
            }
            VkBarriers.fullMemoryBarrier(cmd);
        }
        // Persistent targets always begin the next frame in bank 0.
        for (int target : resourcePlan.persistCopies()) copyColor(cmd, colorImages[target][1].image, colorImages[target][0].image, width, height);
        if (resourcePlan.hasFinal()) {
            copyColor(cmd, presentation.image, engineColorImage, width, height);
        } else {
            IrisImage result = colorImages[0][resourcePlan.resultBank()];
            transfer(cmd, result.image, result.format, engineColorImage, colorFormat, width, height);
        }
        VkBarriers.fullMemoryBarrier(cmd);
    }

    /** Same-format copy, or a format-converting blit (colortex0 format differing from the host's). Mip 0 only. */
    private static void transfer(VkCommandBuffer cmd, long source, int sourceFormat, long destination, int destinationFormat,
                                 int width, int height) {
        if (sourceFormat == destinationFormat) {
            copyColor(cmd, source, destination, width, height);
            return;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var blit = VkImageBlit.calloc(1, stack);
            blit.get(0).srcSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
            blit.get(0).dstSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
            blit.get(0).srcOffsets(1).set(width, height, 1);
            blit.get(0).dstOffsets(1).set(width, height, 1);
            VK10.vkCmdBlitImage(cmd, source, VK10.VK_IMAGE_LAYOUT_GENERAL, destination, VK10.VK_IMAGE_LAYOUT_GENERAL, blit,
                VK10.VK_FILTER_NEAREST);
        }
    }

    /** Regenerates levels 1..n-1 from level 0 with linear blits (glGenerateMipmap equivalent). */
    private static void generateMips(VkCommandBuffer cmd, IrisImage image) {
        if (image.levels <= 1) return;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var blit = VkImageBlit.calloc(1, stack);
            for (int level = 1; level < image.levels; level++) {
                VkBarriers.fullMemoryBarrier(cmd);
                blit.get(0).srcSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(level - 1).layerCount(1);
                blit.get(0).dstSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(level).layerCount(1);
                blit.get(0).srcOffsets(1).set(Math.max(1, image.width >> (level - 1)), Math.max(1, image.height >> (level - 1)), 1);
                blit.get(0).dstOffsets(1).set(Math.max(1, image.width >> level), Math.max(1, image.height >> level), 1);
                VK10.vkCmdBlitImage(cmd, image.image, VK10.VK_IMAGE_LAYOUT_GENERAL, image.image, VK10.VK_IMAGE_LAYOUT_GENERAL,
                    blit, VK10.VK_FILTER_LINEAR);
            }
            VkBarriers.fullMemoryBarrier(cmd);
        }
    }

    private static void copyColor(VkCommandBuffer cmd, long source, long destination, int width, int height) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var region = VkImageCopy.calloc(1, stack);
            region.get(0).srcSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
            region.get(0).dstSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
            region.get(0).extent().set(width, height, 1);
            VK10.vkCmdCopyImage(cmd, source, VK10.VK_IMAGE_LAYOUT_GENERAL, destination, VK10.VK_IMAGE_LAYOUT_GENERAL, region);
        }
    }

    private static void clearColor(VkCommandBuffer cmd, long image, float[] color, int levels) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var clear = VkClearColorValue.calloc(stack);
            clear.float32(0, color[0]).float32(1, color[1]).float32(2, color[2]).float32(3, color[3]);
            var range = VkImageSubresourceRange.calloc(1, stack);
            range.get(0).aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).levelCount(levels).layerCount(1);
            VK10.vkCmdClearColorImage(cmd, image, VK10.VK_IMAGE_LAYOUT_GENERAL, clear, range);
        }
    }

    private void beginRendering(VkCommandBuffer cmd, VkRenderingInfo renderingInfo) {
        if (cmd.getCapabilities().vkCmdBeginRendering != 0L) VK13.vkCmdBeginRendering(cmd, renderingInfo);
        else if (cmd.getCapabilities().vkCmdBeginRenderingKHR != 0L) KHRDynamicRendering.vkCmdBeginRenderingKHR(cmd, renderingInfo);
        else throw new RtFailure("dynamic_rendering", "Neither VK13 nor VK_KHR_dynamic_rendering available on VkCommandBuffer");
    }

    private void endRendering(VkCommandBuffer cmd) {
        if (cmd.getCapabilities().vkCmdEndRendering != 0L) VK13.vkCmdEndRendering(cmd);
        else if (cmd.getCapabilities().vkCmdEndRenderingKHR != 0L) KHRDynamicRendering.vkCmdEndRenderingKHR(cmd);
        else throw new RtFailure("dynamic_rendering", "Neither VK13 nor VK_KHR_dynamic_rendering available on VkCommandBuffer");
    }

    // --- Static Builders, Validators and Utilities ---

    /** Stable set-0 layout: colortex0, depthtex0, frame UBO, colortex1..15, world UBO. */
    public static VkDescriptorSetLayoutBinding.Buffer createDescriptorBindings(MemoryStack stack) {
        var bindings = VkDescriptorSetLayoutBinding.calloc(IrisUniforms.SAMPLER_BINDINGS + 2, stack);
        var samplerBindings = IrisUniforms.samplerBindings();
        for (int i = 0; i < samplerBindings.size(); i++) bindings.get(i).binding(samplerBindings.get(i))
            .descriptorType(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1).stageFlags(ALL_STAGES);
        bindings.get(IrisUniforms.SAMPLER_BINDINGS).binding(IrisUniforms.BINDING_FRAME)
            .descriptorType(VK10.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).descriptorCount(1).stageFlags(ALL_STAGES);
        bindings.get(IrisUniforms.SAMPLER_BINDINGS + 1).binding(IrisUniforms.BINDING_WORLD)
            .descriptorType(VK10.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).descriptorCount(1).stageFlags(ALL_STAGES);
        return bindings;
    }

    public static VkPushConstantRange.Buffer createPushConstantRange(MemoryStack stack) {
        VkPushConstantRange.Buffer pushRanges = VkPushConstantRange.calloc(1, stack);
        pushRanges.get(0).stageFlags(ALL_STAGES).offset(0).size(IrisUniforms.PUSH_BYTES);
        return pushRanges;
    }

    public static VkPipelineColorBlendStateCreateInfo createColorBlendState(MemoryStack stack) {
        return createColorBlendState(stack, 1);
    }

    public static VkPipelineColorBlendStateCreateInfo createColorBlendState(MemoryStack stack, int count) {
        return createColorBlendState(stack, new boolean[count]);
    }

    /** No blending; alpha writes are masked for targets whose GL format has no alpha. */
    public static VkPipelineColorBlendStateCreateInfo createColorBlendState(MemoryStack stack, boolean[] alphaLocked) {
        VkPipelineColorBlendAttachmentState.Buffer blendAttachments = VkPipelineColorBlendAttachmentState.calloc(alphaLocked.length, stack);
        int rgb = VK10.VK_COLOR_COMPONENT_R_BIT | VK10.VK_COLOR_COMPONENT_G_BIT | VK10.VK_COLOR_COMPONENT_B_BIT;
        for (int i = 0; i < alphaLocked.length; i++) blendAttachments.get(i).blendEnable(false)
            .colorWriteMask(alphaLocked[i] ? rgb : rgb | VK10.VK_COLOR_COMPONENT_A_BIT);
        return VkPipelineColorBlendStateCreateInfo.calloc(stack).sType$Default().logicOpEnable(false).pAttachments(blendAttachments);
    }

    public static VkPipelineRasterizationStateCreateInfo createRasterizationState(MemoryStack stack) {
        return VkPipelineRasterizationStateCreateInfo.calloc(stack).sType$Default()
            .depthClampEnable(false).rasterizerDiscardEnable(false).polygonMode(VK10.VK_POLYGON_MODE_FILL)
            .cullMode(VK10.VK_CULL_MODE_NONE).frontFace(VK10.VK_FRONT_FACE_COUNTER_CLOCKWISE).depthBiasEnable(false).lineWidth(1.0f);
    }

    public static VkPipelineRenderingCreateInfo createPipelineRenderingCreateInfo(MemoryStack stack, int colorFormat) {
        return createPipelineRenderingCreateInfo(stack, colorFormat, 1);
    }

    public static VkPipelineRenderingCreateInfo createPipelineRenderingCreateInfo(MemoryStack stack, int colorFormat, int count) {
        int[] formats = new int[count];
        java.util.Arrays.fill(formats, colorFormat);
        return createPipelineRenderingCreateInfo(stack, formats);
    }

    public static VkPipelineRenderingCreateInfo createPipelineRenderingCreateInfo(MemoryStack stack, int[] colorFormats) {
        var formats = stack.mallocInt(colorFormats.length);
        for (int i = 0; i < colorFormats.length; i++) formats.put(i, colorFormats[i]);
        return VkPipelineRenderingCreateInfo.calloc(stack).sType$Default().pColorAttachmentFormats(formats);
    }

    public static ByteBuffer packPushConstants(int width, int height, float frameTimeCounter, int frameCounter,
                                               float near, float far, float frameTime) {
        ByteBuffer buf = ByteBuffer.allocateDirect(IrisUniforms.PUSH_BYTES).order(ByteOrder.nativeOrder());
        fillPushConstants(buf, width, height, frameTimeCounter, frameCounter, near, far, frameTime);
        return buf;
    }

    private static void fillPushConstants(ByteBuffer buf, int width, int height, float frameTimeCounter, int frameCounter,
                                          float near, float far, float frameTime) {
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
        if (graph.passes().isEmpty()) throw new IllegalArgumentException("Pass graph has no passes to execute");
        if (graph.passCount() > MAX_PASS_SETS) throw new IllegalArgumentException("Too many fullscreen passes");
        long finals = graph.passes().stream().filter(IrisPassGraph.Pass::isFinal).count();
        if (finals > 1 || finals == 1 && !graph.passes().getLast().isFinal())
            throw new IllegalArgumentException("A graph may have one final pass, last in the sequence");
        for (IrisPassGraph.Pass pass : graph.passes()) {
            if (pass.vertexSpirv() == null || pass.fragmentSpirv() == null)
                throw new IllegalArgumentException("Pass " + pass.name() + " has null SPIR-V bytecode");
            if (pass.vertexTranslation() == null || pass.fragmentTranslation() == null)
                throw new IllegalArgumentException("Pass " + pass.name() + " has no translated resource metadata");
            for (int b : pass.allSamplerBindings()) {
                if (IrisUniforms.colorIndex(b) < 0 && b != IrisUniforms.BINDING_DEPTHTEX0)
                    throw new IllegalArgumentException("Pass " + pass.name() + " has unsupported sampler binding: " + b);
            }
        }
        IrisResourcePlan.build(graph);
    }

    public static List<PassExecutionStep> planPassExecution(IrisPassGraph graph, int frameSlot) {
        List<PassExecutionStep> plan = new ArrayList<>();
        if (graph == null || graph.passes().isEmpty()) return plan;
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

    public VkDevice device() { return device; }
    public RtMemory memory() { return memory; }
    public int colorFormat() { return colorFormat; }
    public long colorSampler() { return colorSampler; }
    public long depthSampler() { return depthSampler; }
    public long descriptorSetLayout() { return descriptorSetLayout; }
    public long pipelineLayout() { return pipelineLayout; }
    public long descriptorPool() { return descriptorPool; }
    public long getPipeline(String passName) { return pipelines.getOrDefault(passName, 0L); }
    public Map<String, Long> pipelines() { return Collections.unmodifiableMap(pipelines); }
    public int currentFrameSlot() { return currentFrameSlot; }

    @Override
    public synchronized void close() {
        cleanup();
    }

    private void destroyImages() {
        if (device == null) return;
        for (int target = 0; target < colorImages.length; target++) for (int bank = 0; bank < 2; bank++) {
            if (colorImages[target][bank] != null) colorImages[target][bank].destroy(device);
            colorImages[target][bank] = null;
        }
        if (presentation != null && memory != null) { memory.destroy(presentation); presentation = null; }
    }

    public synchronized void cleanup() {
        if (device != null) {
            RtFailure.check(VK10.vkDeviceWaitIdle(device), "iris_cleanup_wait");
            for (long pipe : pipelines.values()) VK10.vkDestroyPipeline(device, pipe, null);
            pipelines.clear();
            if (pipelineLayout != 0L) { VK10.vkDestroyPipelineLayout(device, pipelineLayout, null); pipelineLayout = 0L; }
            if (descriptorPool != 0L) { VK10.vkDestroyDescriptorPool(device, descriptorPool, null); descriptorPool = 0L; }
            if (descriptorSetLayout != 0L) { VK10.vkDestroyDescriptorSetLayout(device, descriptorSetLayout, null); descriptorSetLayout = 0L; }
            if (colorSampler != 0L) { VK10.vkDestroySampler(device, colorSampler, null); colorSampler = 0L; }
            if (mipSampler != 0L) { VK10.vkDestroySampler(device, mipSampler, null); mipSampler = 0L; }
            if (depthSampler != 0L) { VK10.vkDestroySampler(device, depthSampler, null); depthSampler = 0L; }
            destroyImages();
        }
        if (memory != null && frameUniforms != null) { memory.destroy(frameUniforms); frameUniforms = null; }
        if (memory != null && worldUniforms != null) { memory.destroy(worldUniforms); worldUniforms = null; }
        allocatedTargets = Set.of(); allocatedWidth = allocatedHeight = 0; resourcePlan = null;
        imagesInitialized = false;
        activeGraph = null;
    }
}
