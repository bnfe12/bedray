package io.github.andrexarlay.vulkancompat.shaderhost.iris;

import io.github.andrexarlay.vulkancompat.rt.world.RtFailure;
import io.github.andrexarlay.vulkancompat.rt.world.RtMemory;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.VkBarriers;
import io.github.andrexarlay.vulkancompat.shaderhost.glsl.IrisExpressionUniforms;
import io.github.andrexarlay.vulkancompat.shaderhost.glsl.IrisUniformBlock;
import io.github.andrexarlay.vulkancompat.shaderhost.glsl.IrisUniforms;
import io.github.andrexarlay.vulkancompat.shaderhost.pack.IrisPackSettings;
import io.github.andrexarlay.vulkancompat.shaderhost.pack.ProgramName;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.KHRDynamicRendering;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VK13;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDescriptorBufferInfo;
import org.lwjgl.vulkan.VkDescriptorImageInfo;
import org.lwjgl.vulkan.VkDescriptorPoolCreateInfo;
import org.lwjgl.vulkan.VkDescriptorPoolSize;
import org.lwjgl.vulkan.VkDescriptorSetAllocateInfo;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkGraphicsPipelineCreateInfo;
import org.lwjgl.vulkan.VkImageCopy;
import org.lwjgl.vulkan.VkBufferImageCopy;
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
import org.lwjgl.vulkan.VkRect2D;
import org.lwjgl.vulkan.VkRenderingAttachmentInfo;
import org.lwjgl.vulkan.VkRenderingInfo;
import org.lwjgl.vulkan.VkSamplerCreateInfo;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;
import org.lwjgl.vulkan.VkVertexInputAttributeDescription;
import org.lwjgl.vulkan.VkVertexInputBindingDescription;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Raster-core frame executor: records shadow -> gbuffers -> deferred/composite/final for one frame into the engine's
 * transient command buffer. Geometry comes from the capture-fed {@link IrisGeometrySource} meshes in {@link FrameInputs};
 * targets are the pack's colortex/depthtex/shadowtex set ({@link IrisTargets}); uniforms go through one std140 UBO per pass
 * at {@link IrisUniforms#UBO_BINDING} with canonical offsets. All images stay in GENERAL layout (host convention).
 * <p>
 * Honest fallbacks: a gbuffers program with no geometry source is skipped and logged by name; depthtex1/2 sample depthtex0
 * and shadowtex1 samples shadowtex0 (single depth pass); custom pack textures bind a 1x1 white image until loaded.
 */
public final class IrisForwardExecutor implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger("Bedray/IrisForwardExecutor");

    public static final int FRAME_SLOTS = 3;
    public static final int MAX_PASS_SETS = 64;
    public static final int SHADOW_SIZE = 2048;
    private static final int D32 = VK10.VK_FORMAT_D32_SFLOAT;
    private static final int STRIDE_BYTES = IrisGeometrySource.STRIDE_FLOATS * 4;

    /** Matches the translator's emitted shadow-compare sampler declaration to recover its binding per pass. */
    private static final Pattern SHADOW_SAMPLER_DECL =
        Pattern.compile("binding\\s*=\\s*(\\d+)\\)\\s*uniform\\s+sampler2DShadow");

    /**
     * @param meshes            geometry source key -> mesh ("terrain", "water", "entities", "hand", "sky", or a program name)
     * @param uniforms          extra UBO values (cameraPosition, sunPosition, worldTime, ...) supplied by the frame hook
     * @param engineColorImage  engine world color image (final copy destination when no final pass)
     * @param engineColorView   engine world color view (final pass render target)
     * @param engineDepthView   engine depth view (unused by raster-core passes; kept for integration symmetry)
     * @param atlasView         block atlas sampled view ("texture" sampler), 0 = white fallback
     * @param lightmapView      lightmap sampled view, 0 = white fallback
     */
    public record FrameInputs(
        Map<String, IrisGeometrySource.Mesh> meshes,
        float[] modelView,
        float[] projection,
        float[] shadowModelView,
        float[] shadowProjection,
        Map<String, float[]> uniforms,
        long engineColorImage,
        long engineColorView,
        long engineDepthView,
        long atlasView,
        long lightmapView,
        int width,
        int height,
        float frameTimeCounter,
        int frameCounter,
        float near,
        float far,
        float frameTime
    ) {}

    /** One planned pass: targets read/written, UBO size and sampler bindings (pure, testable without a device). */
    public record Step(String pass, ProgramName.Family family, boolean fullscreen, List<String> reads, List<String> writes,
                       int uboBytes, Set<Integer> samplerBindings) {}

    private record GpuMesh(RtMemory.Buf vertices, RtMemory.Buf indices, int indexCount) {}

    private final VkDevice device;
    private final RtMemory memory;
    private final IrisPackSettings settings;
    private IrisTargets targets;

    private long linearSampler;
    private long nearestSampler;
    private long compareSampler;
    private IrisExpressionUniforms expressions;
    private final Map<String, Set<Integer>> shadowSamplerBindings = new LinkedHashMap<>();
    private long descriptorSetLayout;
    private long pipelineLayout;
    private long descriptorPool;
    private long[][] descriptorSets;
    private RtMemory.Buf[][] uboBufs;
    private int maxSamplerBinding = 26;

    private RtMemory.Img[][] colortex;
    private RtMemory.Img[] depth;
    private RtMemory.Img[] shadowDepth;
    private RtMemory.Img[] shadowColor;
    private RtMemory.Img white;
    private boolean imagesInitialized;
    private int currentFrameSlot = -1;
    private final List<List<RtMemory.Buf>> meshBufRing = new ArrayList<>();

    private final Map<String, Long> pipelines = new LinkedHashMap<>();
    private IrisPassGraph.FullResult graph;
    private final Set<String> loggedSkips = new LinkedHashSet<>();
    private final Map<Integer, String> customNames = new LinkedHashMap<>();

    public IrisForwardExecutor(VkDevice device, RtMemory memory, IrisPackSettings settings) {
        this.device = Objects.requireNonNull(device, "device");
        this.memory = Objects.requireNonNull(memory, "memory");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.targets = IrisTargets.create(settings);
        this.expressions = new IrisExpressionUniforms(settings);
        for (int i = 0; i < FRAME_SLOTS; i++) {
            meshBufRing.add(new ArrayList<>());
        }
        initVulkan();
    }

    private IrisForwardExecutor() {
        this.device = null;
        this.memory = null;
        this.settings = null;
        for (int i = 0; i < FRAME_SLOTS; i++) {
            meshBufRing.add(new ArrayList<>());
        }
    }

    public static IrisForwardExecutor createForTesting() {
        return new IrisForwardExecutor();
    }

    public static int bindingFor(String sampler) {
        return IrisUniforms.GB_SAMPLERS.getOrDefault(sampler, -1);
    }

    private void initVulkan() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer p = stack.mallocLong(1);
            VkSamplerCreateInfo si = VkSamplerCreateInfo.calloc(stack).sType$Default()
                .magFilter(VK10.VK_FILTER_LINEAR).minFilter(VK10.VK_FILTER_LINEAR)
                .mipmapMode(VK10.VK_SAMPLER_MIPMAP_MODE_NEAREST)
                .addressModeU(VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                .addressModeV(VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                .addressModeW(VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                .maxLod(0.0f);
            RtFailure.check(VK10.vkCreateSampler(device, si, null, p), "iris_fwd_linear_sampler");
            linearSampler = p.get(0);
            si.magFilter(VK10.VK_FILTER_NEAREST).minFilter(VK10.VK_FILTER_NEAREST);
            RtFailure.check(VK10.vkCreateSampler(device, si, null, p), "iris_fwd_nearest_sampler");
            nearestSampler = p.get(0);
            si.compareEnable(true).compareOp(VK10.VK_COMPARE_OP_LESS_OR_EQUAL);
            RtFailure.check(VK10.vkCreateSampler(device, si, null, p), "iris_fwd_compare_sampler");
            compareSampler = p.get(0);
        } catch (Throwable t) {
            cleanup();
            throw t;
        }
    }

    /** Installs a compiled full graph; builds pipelines and descriptor/UBO resources when a device is present. */
    public synchronized void setGraph(IrisPassGraph.FullResult result) {
        Objects.requireNonNull(result, "result");
        if (!result.success()) {
            throw new IllegalArgumentException("graph not successful: " + result.status().detail());
        }
        this.graph = result;
        this.targets = settings != null ? IrisTargets.create(settings) : null;
        this.customNames.clear();
        if (settings != null) {
            int b = IrisUniforms.GB_CUSTOM_BINDING_BASE;
            for (String name : settings.customTextureNames()) {
                customNames.put(b++, name);
            }
        }
        this.shadowSamplerBindings.clear();
        for (IrisPassGraph.FullPass pass : result.passes()) {
            this.shadowSamplerBindings.put(pass.name(), shadowBindings(pass));
        }
        if (expressions != null) {
            try {
                expressions.evaluate(Map.of(), 0.016);
            } catch (IrisUniformBlock.Expr.Unsupported u) {
                throw new IllegalArgumentException("unsupported pack expression: " + u.getMessage(), u);
            }
        }
        int max = 26;
        for (IrisPassGraph.FullPass pass : result.passes()) {
            for (int binding : pass.allSamplerBindings()) {
                max = Math.max(max, binding);
            }
        }
        this.maxSamplerBinding = max;
        if (device == null) {
            return;
        }
        VK10.vkDeviceWaitIdle(device);
        destroyPassResources();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer p = stack.mallocLong(1);
            int samplerCount = maxSamplerBinding + 1;
            VkDescriptorSetLayoutBinding.Buffer bindings = VkDescriptorSetLayoutBinding.calloc(samplerCount + 1, stack);
            for (int i = 0; i < samplerCount; i++) {
                bindings.get(i).binding(i)
                    .descriptorType(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                    .descriptorCount(1)
                    .stageFlags(VK10.VK_SHADER_STAGE_VERTEX_BIT | VK10.VK_SHADER_STAGE_FRAGMENT_BIT);
            }
            bindings.get(samplerCount).binding(IrisUniforms.UBO_BINDING)
                .descriptorType(VK10.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER)
                .descriptorCount(1)
                .stageFlags(VK10.VK_SHADER_STAGE_VERTEX_BIT | VK10.VK_SHADER_STAGE_FRAGMENT_BIT);
            VkDescriptorSetLayoutCreateInfo li = VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default().pBindings(bindings);
            RtFailure.check(VK10.vkCreateDescriptorSetLayout(device, li, null, p), "iris_fwd_set_layout");
            descriptorSetLayout = p.get(0);

            VkPipelineLayoutCreateInfo pli = VkPipelineLayoutCreateInfo.calloc(stack).sType$Default()
                .pSetLayouts(stack.longs(descriptorSetLayout));
            RtFailure.check(VK10.vkCreatePipelineLayout(device, pli, null, p), "iris_fwd_pipe_layout");
            pipelineLayout = p.get(0);

            int totalSets = FRAME_SLOTS * MAX_PASS_SETS;
            VkDescriptorPoolSize.Buffer sizes = VkDescriptorPoolSize.calloc(2, stack);
            sizes.get(0).type(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(totalSets * samplerCount);
            sizes.get(1).type(VK10.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).descriptorCount(totalSets);
            VkDescriptorPoolCreateInfo pi = VkDescriptorPoolCreateInfo.calloc(stack).sType$Default()
                .maxSets(totalSets).pPoolSizes(sizes);
            RtFailure.check(VK10.vkCreateDescriptorPool(device, pi, null, p), "iris_fwd_pool");
            descriptorPool = p.get(0);

            descriptorSets = new long[FRAME_SLOTS][MAX_PASS_SETS];
            uboBufs = new RtMemory.Buf[FRAME_SLOTS][MAX_PASS_SETS];
            LongBuffer layouts = stack.mallocLong(MAX_PASS_SETS);
            for (int i = 0; i < MAX_PASS_SETS; i++) {
                layouts.put(i, descriptorSetLayout);
            }
            for (int slot = 0; slot < FRAME_SLOTS; slot++) {
                VkDescriptorSetAllocateInfo ai = VkDescriptorSetAllocateInfo.calloc(stack).sType$Default()
                    .descriptorPool(descriptorPool).pSetLayouts(layouts);
                LongBuffer pSets = stack.mallocLong(MAX_PASS_SETS);
                RtFailure.check(VK10.vkAllocateDescriptorSets(device, ai, pSets), "iris_fwd_sets");
                for (int i = 0; i < MAX_PASS_SETS; i++) {
                    descriptorSets[slot][i] = pSets.get(i);
                }
                for (int i = 0; i < result.passes().size() && i < MAX_PASS_SETS; i++) {
                    uboBufs[slot][i] = memory.createBuffer("iris_fwd_ubo_" + slot + "_" + i,
                        Math.max(16, result.passes().get(i).uboBytes()), VK10.VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT, true);
                }
            }
            for (IrisPassGraph.FullPass pass : result.passes()) {
                pipelines.put(pass.name(), createPipeline(stack, pass));
            }
        } catch (Throwable t) {
            cleanup();
            throw t;
        }
        LOGGER.info("[VKB-IRIS] forward executor: {} pipelines, {} samplers, UBO {} bytes",
            result.passes().size(), maxSamplerBinding + 1, IrisUniforms.UBO_LAYOUT.bytes());
    }

    private void destroyPassResources() {
        for (long pipe : pipelines.values()) {
            VK10.vkDestroyPipeline(device, pipe, null);
        }
        pipelines.clear();
        if (descriptorPool != 0L) {
            VK10.vkDestroyDescriptorPool(device, descriptorPool, null);
            descriptorPool = 0L;
        }
        if (pipelineLayout != 0L) {
            VK10.vkDestroyPipelineLayout(device, pipelineLayout, null);
            pipelineLayout = 0L;
        }
        if (descriptorSetLayout != 0L) {
            VK10.vkDestroyDescriptorSetLayout(device, descriptorSetLayout, null);
            descriptorSetLayout = 0L;
        }
        if (uboBufs != null) {
            for (RtMemory.Buf[] slot : uboBufs) {
                for (RtMemory.Buf buf : slot) {
                    if (buf != null) {
                        memory.destroy(buf);
                    }
                }
            }
            uboBufs = null;
        }
        descriptorSets = null;
    }

    private long createPipeline(MemoryStack stack, IrisPassGraph.FullPass pass) {
        LongBuffer p = stack.mallocLong(1);
        ByteBuffer vSpv = pass.vertexSpirv().duplicate();
        vSpv.rewind();
        ByteBuffer fSpv = pass.fragmentSpirv().duplicate();
        fSpv.rewind();
        VkShaderModuleCreateInfo vm = VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(vSpv);
        RtFailure.check(VK10.vkCreateShaderModule(device, vm, null, p), "iris_fwd_vmod:" + pass.name());
        long vMod = p.get(0);
        VkShaderModuleCreateInfo fm = VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(fSpv);
        RtFailure.check(VK10.vkCreateShaderModule(device, fm, null, p), "iris_fwd_fmod:" + pass.name());
        long fMod = p.get(0);
        try {
            VkPipelineShaderStageCreateInfo.Buffer stages = VkPipelineShaderStageCreateInfo.calloc(2, stack);
            stages.get(0).sType$Default().stage(VK10.VK_SHADER_STAGE_VERTEX_BIT).module(vMod).pName(stack.UTF8("main"));
            stages.get(1).sType$Default().stage(VK10.VK_SHADER_STAGE_FRAGMENT_BIT).module(fMod).pName(stack.UTF8("main"));

            VkPipelineVertexInputStateCreateInfo vertexInput = VkPipelineVertexInputStateCreateInfo.calloc(stack).sType$Default();
            if (!pass.fullscreen()) {
                VkVertexInputBindingDescription.Buffer binding = VkVertexInputBindingDescription.calloc(1, stack);
                binding.get(0).binding(0).stride(STRIDE_BYTES).inputRate(VK10.VK_VERTEX_INPUT_RATE_VERTEX);
                int[][] attrs = {{0, 0, VK10.VK_FORMAT_R32G32B32_SFLOAT}, {1, 12, VK10.VK_FORMAT_R32G32B32A32_SFLOAT},
                    {2, 28, VK10.VK_FORMAT_R32G32_SFLOAT}, {3, 36, VK10.VK_FORMAT_R32G32_SFLOAT},
                    {4, 44, VK10.VK_FORMAT_R32G32B32_SFLOAT}, {5, 56, VK10.VK_FORMAT_R32G32B32_SFLOAT},
                    {6, 68, VK10.VK_FORMAT_R32G32B32A32_SFLOAT}, {7, 84, VK10.VK_FORMAT_R32G32B32A32_SFLOAT},
                    {8, 100, VK10.VK_FORMAT_R32G32B32A32_SFLOAT}};
                VkVertexInputAttributeDescription.Buffer attrBuf = VkVertexInputAttributeDescription.calloc(attrs.length, stack);
                for (int i = 0; i < attrs.length; i++) {
                    attrBuf.get(i).location(attrs[i][0]).binding(0).format(attrs[i][2]).offset(attrs[i][1]);
                }
                vertexInput.pVertexBindingDescriptions(binding).pVertexAttributeDescriptions(attrBuf);
            }

            VkPipelineInputAssemblyStateCreateInfo ia = VkPipelineInputAssemblyStateCreateInfo.calloc(stack).sType$Default()
                .topology(VK10.VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST).primitiveRestartEnable(false);
            VkPipelineViewportStateCreateInfo vp = VkPipelineViewportStateCreateInfo.calloc(stack).sType$Default()
                .viewportCount(1).scissorCount(1);
            VkPipelineDynamicStateCreateInfo dyn = VkPipelineDynamicStateCreateInfo.calloc(stack).sType$Default()
                .pDynamicStates(stack.ints(VK10.VK_DYNAMIC_STATE_VIEWPORT, VK10.VK_DYNAMIC_STATE_SCISSOR));
            VkPipelineRasterizationStateCreateInfo raster = IrisPostExecutor.createRasterizationState(stack);
            VkPipelineMultisampleStateCreateInfo ms = VkPipelineMultisampleStateCreateInfo.calloc(stack).sType$Default()
                .rasterizationSamples(VK10.VK_SAMPLE_COUNT_1_BIT).sampleShadingEnable(false);

            boolean geometry = !pass.fullscreen();
            VkPipelineDepthStencilStateCreateInfo ds = VkPipelineDepthStencilStateCreateInfo.calloc(stack).sType$Default()
                .depthTestEnable(geometry).depthWriteEnable(geometry)
                .depthCompareOp(VK10.VK_COMPARE_OP_LESS_OR_EQUAL).stencilTestEnable(false);

            List<Integer> dbs = pass.drawBuffers();
            VkPipelineColorBlendAttachmentState.Buffer blend = VkPipelineColorBlendAttachmentState.calloc(dbs.size(), stack);
            boolean water = pass.name().equals("gbuffers_water");
            for (int i = 0; i < dbs.size(); i++) {
                blend.get(i).blendEnable(water)
                    .srcColorBlendFactor(VK10.VK_BLEND_FACTOR_SRC_ALPHA)
                    .dstColorBlendFactor(VK10.VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA)
                    .colorBlendOp(VK10.VK_BLEND_OP_ADD)
                    .srcAlphaBlendFactor(VK10.VK_BLEND_FACTOR_ONE)
                    .dstAlphaBlendFactor(VK10.VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA)
                    .alphaBlendOp(VK10.VK_BLEND_OP_ADD)
                    .colorWriteMask(VK10.VK_COLOR_COMPONENT_R_BIT | VK10.VK_COLOR_COMPONENT_G_BIT
                        | VK10.VK_COLOR_COMPONENT_B_BIT | VK10.VK_COLOR_COMPONENT_A_BIT);
            }
            VkPipelineColorBlendStateCreateInfo cb = VkPipelineColorBlendStateCreateInfo.calloc(stack).sType$Default()
                .logicOpEnable(false).pAttachments(blend);

            int[] formats = new int[dbs.size()];
            for (int i = 0; i < dbs.size(); i++) {
                formats[i] = targets.colors().get(dbs.get(i)).vkFormat();
                if (formats[i] < 0) {
                    throw new RtFailure("iris_fwd_format:" + pass.name(), "colortex" + dbs.get(i) + " format unknown");
                }
            }
            VkPipelineRenderingCreateInfo rendering = VkPipelineRenderingCreateInfo.calloc(stack).sType$Default()
                .pColorAttachmentFormats(stack.ints(formats));
            if (geometry) {
                rendering.depthAttachmentFormat(D32);
            }

            VkGraphicsPipelineCreateInfo.Buffer info = VkGraphicsPipelineCreateInfo.calloc(1, stack);
            info.get(0).sType$Default()
                .pNext(rendering.address())
                .pStages(stages)
                .pVertexInputState(vertexInput)
                .pInputAssemblyState(ia)
                .pViewportState(vp)
                .pRasterizationState(raster)
                .pMultisampleState(ms)
                .pDepthStencilState(ds)
                .pColorBlendState(cb)
                .pDynamicState(dyn)
                .layout(pipelineLayout)
                .renderPass(VK10.VK_NULL_HANDLE)
                .subpass(0);
            RtFailure.check(VK10.vkCreateGraphicsPipelines(device, VK10.VK_NULL_HANDLE, info, null, p),
                "iris_fwd_pipeline:" + pass.name());
            return p.get(0);
        } finally {
            VK10.vkDestroyShaderModule(device, vMod, null);
            VK10.vkDestroyShaderModule(device, fMod, null);
        }
    }

    /** Pure execution plan (no device needed): pass order, targets, UBO size, bindings. */
    public List<Step> plan() {
        List<Step> steps = new ArrayList<>();
        if (graph == null) {
            return steps;
        }
        for (IrisPassGraph.FullPass pass : graph.passes()) {
            List<String> reads = new ArrayList<>();
            List<String> writes = new ArrayList<>();
            int uboBytes = pass.uboBytes();
            if (pass.family() == ProgramName.Family.SHADOW) {
                writes.add("shadowcolor0");
                writes.add("shadowdepth0");
            } else if (!pass.fullscreen()) {
                for (int db : pass.drawBuffers()) {
                    writes.add("colortex" + db);
                }
                writes.add("depth0");
            } else {
                for (int b : pass.allSamplerBindings()) {
                    if (b == 0) {
                        reads.add("colortex0");
                    } else if (b >= 2 && b <= 16) {
                        reads.add("colortex" + (b - 1));
                    }
                }
                for (int db : pass.drawBuffers()) {
                    writes.add(pass.family() == ProgramName.Family.FINAL ? "engineColor" : "colortex" + db);
                }
            }
            steps.add(new Step(pass.name(), pass.family(), pass.fullscreen(), reads, writes, uboBytes, pass.allSamplerBindings()));
        }
        return Collections.unmodifiableList(steps);
    }

    private synchronized void ensureImages(int width, int height) {
        if (memory == null || targets == null) {
            return;
        }
        if (!targets.needsRecreate(width, height) && colortex != null) {
            return;
        }
        VK10.vkDeviceWaitIdle(device);
        destroyImages();
        targets.setSize(width, height);
        int usage = VK10.VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK10.VK_IMAGE_USAGE_SAMPLED_BIT
            | VK10.VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK10.VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
        colortex = new RtMemory.Img[targets.colorCount()][2];
        for (int i = 0; i < targets.colorCount(); i++) {
            int fmt = targets.colors().get(i).vkFormat();
            if (fmt < 0) {
                throw new RtFailure("iris_fwd_colortex_format", "colortex" + i + " format " + targets.colors().get(i).format());
            }
            colortex[i][0] = memory.createImage("iris_colortex" + i + "_a", width, height, fmt, usage);
            colortex[i][1] = memory.createImage("iris_colortex" + i + "_b", width, height, fmt, usage);
        }
        int depthUsage = VK10.VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT | VK10.VK_IMAGE_USAGE_SAMPLED_BIT;
        depth = new RtMemory.Img[targets.depths().size()];
        for (int i = 0; i < depth.length; i++) {
            depth[i] = memory.createImage("iris_depthtex" + i, width, height, D32, depthUsage);
        }
        shadowDepth = new RtMemory.Img[targets.shadowDepths().size()];
        for (int i = 0; i < shadowDepth.length; i++) {
            shadowDepth[i] = memory.createImage("iris_shadowtex" + i, SHADOW_SIZE, SHADOW_SIZE, D32, depthUsage);
        }
        shadowColor = new RtMemory.Img[targets.shadowColors().size()];
        for (int i = 0; i < shadowColor.length; i++) {
            shadowColor[i] = memory.createImage("iris_shadowcolor" + i, SHADOW_SIZE, SHADOW_SIZE,
                targets.shadowColors().get(i).vkFormat(), usage);
        }
        white = memory.createImage("iris_white", 1, 1, VK10.VK_FORMAT_R8G8B8A8_UNORM,
            VK10.VK_IMAGE_USAGE_SAMPLED_BIT | VK10.VK_IMAGE_USAGE_TRANSFER_DST_BIT);
        imagesInitialized = false;
        LOGGER.info("[VKB-IRIS] targets allocated: {} colortex pairs, {}x{}", targets.colorCount(), width, height);
    }

    private void destroyImages() {
        if (colortex != null) {
            for (RtMemory.Img[] pair : colortex) {
                for (RtMemory.Img img : pair) {
                    if (img != null) {
                        memory.destroy(img);
                    }
                }
            }
            colortex = null;
        }
        if (depth != null) {
            for (RtMemory.Img img : depth) {
                if (img != null) {
                    memory.destroy(img);
                }
            }
            depth = null;
        }
        if (shadowDepth != null) {
            for (RtMemory.Img img : shadowDepth) {
                if (img != null) {
                    memory.destroy(img);
                }
            }
            shadowDepth = null;
        }
        if (shadowColor != null) {
            for (RtMemory.Img img : shadowColor) {
                if (img != null) {
                    memory.destroy(img);
                }
            }
            shadowColor = null;
        }
        if (white != null) {
            memory.destroy(white);
            white = null;
        }
        imagesInitialized = false;
    }

    /**
     * Records one full raster-core frame. Returns false (no recording) when there is no device/command buffer/graph.
     */
    public synchronized boolean record(VkCommandBuffer cmd, FrameInputs in) {
        if (device == null || memory == null || cmd == null || graph == null || graph.passes().isEmpty()) {
            return false;
        }
        Objects.requireNonNull(in, "inputs");
        if (in.width() <= 0 || in.height() <= 0) {
            throw new IllegalArgumentException("invalid dimensions " + in.width() + "x" + in.height());
        }
        if (in.engineColorView() == 0L) {
            throw new IllegalArgumentException("engineColorView is null");
        }
        ensureImages(in.width(), in.height());
        currentFrameSlot = (currentFrameSlot + 1) % FRAME_SLOTS;
        int slot = currentFrameSlot;
        for (RtMemory.Buf buf : meshBufRing.get(slot)) {
            memory.destroy(buf);
        }
        meshBufRing.get(slot).clear();

        Map<String, GpuMesh> gpuMeshes = uploadMeshes(in.meshes(), slot);
        Map<String, float[]> baseUniforms = baseUniforms(in);
        Map<String, float[]> extra = new LinkedHashMap<>(in.uniforms());
        if (expressions != null) {
            Map<String, double[]> ctx = new LinkedHashMap<>();
            for (Map.Entry<String, float[]> e : baseUniforms.entrySet()) {
                ctx.put(e.getKey(), toDouble(e.getValue()));
            }
            for (Map.Entry<String, float[]> e : in.uniforms().entrySet()) {
                ctx.put(e.getKey(), toDouble(e.getValue()));
            }
            extra.putAll(expressions.evaluate(ctx, in.frameTime()));
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            if (!imagesInitialized) {
                transitionAll(cmd);
                uploadWhite(cmd, stack);
                imagesInitialized = true;
            }
            List<IrisPassGraph.FullPass> passes = graph.passes();
            for (int i = 0; i < passes.size(); i++) {
                IrisPassGraph.FullPass pass = passes.get(i);
                fillUbo(slot, i, pass, baseUniforms, extra);
                updateDescriptors(stack, slot, i, pass, in);
            }

            boolean firstGeometry = true;
            for (int i = 0; i < passes.size(); i++) {
                IrisPassGraph.FullPass pass = passes.get(i);
                Long pipe = pipelines.get(pass.name());
                if (pipe == null) {
                    throw new IllegalStateException("no pipeline for " + pass.name());
                }
                if (pass.family() == ProgramName.Family.SHADOW) {
                    recordShadow(cmd, stack, pass, pipe, slot, i, gpuMeshes);
                } else if (!pass.fullscreen()) {
                    recordGeometry(cmd, stack, pass, pipe, slot, i, gpuMeshes, firstGeometry);
                    firstGeometry = false;
                } else {
                    recordFullscreen(cmd, stack, pass, pipe, slot, i, in);
                }
                VkBarriers.fullMemoryBarrier(cmd);
            }
        }
        return true;
    }

    private Map<String, GpuMesh> uploadMeshes(Map<String, IrisGeometrySource.Mesh> meshes, int slot) {
        Map<String, GpuMesh> out = new LinkedHashMap<>();
        for (Map.Entry<String, IrisGeometrySource.Mesh> e : meshes.entrySet()) {
            IrisGeometrySource.Mesh mesh = e.getValue();
            if (mesh == null || mesh.vertices().length == 0 || mesh.indices().length == 0) {
                continue;
            }
            RtMemory.Buf vb = memory.createBuffer("iris_mesh_v_" + e.getKey(), mesh.vertices().length * 4L,
                VK10.VK_BUFFER_USAGE_VERTEX_BUFFER_BIT, true);
            MemoryUtil.memByteBuffer(vb.mapped, mesh.vertices().length * 4).asFloatBuffer().put(mesh.vertices());
            RtMemory.Buf ib = memory.createBuffer("iris_mesh_i_" + e.getKey(), mesh.indices().length * 4L,
                VK10.VK_BUFFER_USAGE_INDEX_BUFFER_BIT, true);
            MemoryUtil.memByteBuffer(ib.mapped, mesh.indices().length * 4).asIntBuffer().put(mesh.indices());
            meshBufRing.get(slot).add(vb);
            meshBufRing.get(slot).add(ib);
            out.put(e.getKey(), new GpuMesh(vb, ib, mesh.indices().length));
        }
        return out;
    }

    private Map<String, float[]> baseUniforms(FrameInputs in) {
        Map<String, float[]> u = new LinkedHashMap<>();
        float w = in.width();
        float h = in.height();
        u.put("viewWidth", new float[] {w});
        u.put("viewHeight", new float[] {h});
        u.put("aspectRatio", new float[] {h > 0 ? w / h : 1f});
        u.put("near", new float[] {in.near()});
        u.put("far", new float[] {in.far()});
        u.put("frameTimeCounter", new float[] {in.frameTimeCounter()});
        u.put("frameCounter", new float[] {in.frameCounter()});
        u.put("frameTime", new float[] {in.frameTime()});
        u.put("modelViewMatrix", in.modelView());
        u.put("projectionMatrix", in.projection());
        u.put("modelViewMatrixInverse", invert(in.modelView()));
        u.put("projectionMatrixInverse", invert(in.projection()));
        u.put("textureMatrix", identity());
        u.put("shadowModelView", in.shadowModelView());
        u.put("shadowProjection", in.shadowProjection());
        u.put("shadowModelViewInverse", invert(in.shadowModelView()));
        u.put("shadowProjectionInverse", invert(in.shadowProjection()));
        u.put("shadowDistance", new float[] {in.far()});
        u.put("shadowMapResolution", new float[] {SHADOW_SIZE});
        u.put("bedrockLevel", new float[] {-64});
        u.put("atlasSize", new float[] {1024, 1024});
        u.put("previousModelViewMatrix", in.modelView());
        u.put("previousProjectionMatrix", in.projection());
        u.put("renderStage", new float[] {0});
        u.put("blockEntityId", new float[] {-1});
        u.put("entityId", new float[] {-1});
        u.put("endFlashPosition", new float[] {0, 0, 0});
        u.put("relativeEyePosition", new float[] {0, 0, 0});
        float[] cam = in.uniforms().get("cameraPosition");
        if (cam != null && cam.length >= 3) {
            u.put("cameraPositionFract", new float[] {fract(cam[0]), fract(cam[1]), fract(cam[2])});
        }
        return u;
    }

    private static float fract(float x) {
        return x - (float) Math.floor(x);
    }

    private static double[] toDouble(float[] v) {
        double[] d = new double[v.length];
        for (int i = 0; i < v.length; i++) {
            d[i] = v[i];
        }
        return d;
    }

    private static Set<Integer> shadowBindings(IrisPassGraph.FullPass pass) {
        Set<Integer> out = new TreeSet<>();
        for (String src : new String[] {pass.vertexTranslation().source(), pass.fragmentTranslation().source()}) {
            Matcher m = SHADOW_SAMPLER_DECL.matcher(src);
            while (m.find()) {
                out.add(Integer.parseInt(m.group(1)));
            }
        }
        return out;
    }

    private static float[] identity() {
        return new float[] {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
    }

    private static float[] invert(float[] m) {
        if (m == null || m.length != 16) {
            return identity();
        }
        float[] out = new float[16];
        new Matrix4f().set(m).invert().get(out);
        return out;
    }

    private void fillUbo(int slot, int passIdx, IrisPassGraph.FullPass pass, Map<String, float[]> base, Map<String, float[]> extra) {
        RtMemory.Buf buf = uboBufs[slot][passIdx];
        int bytes = (int) buf.size;
        ByteBuffer dst = MemoryUtil.memByteBuffer(buf.mapped, bytes).order(ByteOrder.LITTLE_ENDIAN);
        for (int b = 0; b < bytes; b++) {
            dst.put(b, (byte) 0);
        }
        Map<String, Integer> offsets = pass.uboOffsets();
        for (Map.Entry<String, String> e : pass.allUboUsed().entrySet()) {
            Integer off = offsets.get(e.getKey());
            if (off == null) {
                continue;
            }
            float[] v = extra.containsKey(e.getKey()) ? extra.get(e.getKey()) : base.get(e.getKey());
            if (v != null) {
                IrisUniformBlock.writeMember(dst, off, e.getValue(), v);
            }
        }
    }

    private long viewForBinding(int binding, FrameInputs in) {
        if (binding == 0) {
            return colortex[0][targets.readIndex(0)].view;
        }
        if (binding >= 2 && binding <= 16) {
            int i = binding - 1;
            return colortex[i][targets.readIndex(i)].view;
        }
        return switch (binding) {
            case 1, 17, 18 -> depth[0].view;
            case 19, 20 -> shadowDepth[0].view;
            case 21 -> shadowColor[0].view;
            case 22 -> shadowColor[1].view;
            case 23 -> in.lightmapView() != 0L ? in.lightmapView() : white.view;
            case 26 -> in.atlasView() != 0L ? in.atlasView() : white.view;
            default -> {
                String custom = customNames.get(binding);
                if (custom != null && loggedSkips.add("custom:" + custom)) {
                    LOGGER.warn("[VKB-IRIS] custom texture '{}' not loaded yet, binding white fallback", custom);
                }
                yield white.view;
            }
        };
    }

    private void updateDescriptors(MemoryStack stack, int slot, int passIdx, IrisPassGraph.FullPass pass, FrameInputs in) {
        long dSet = descriptorSets[slot][passIdx];
        Set<Integer> bindings = new TreeSet<>(pass.allSamplerBindings());
        Set<Integer> shadow = shadowSamplerBindings.getOrDefault(pass.name(), Set.of());
        VkDescriptorImageInfo.Buffer imgs = VkDescriptorImageInfo.calloc(bindings.size(), stack);
        VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(bindings.size() + 1, stack);
        int i = 0;
        for (int b : bindings) {
            boolean depth = b == 1 || b == 17 || b == 18 || b == 19 || b == 20;
            long sampler = shadow.contains(b) ? compareSampler : (depth ? nearestSampler : linearSampler);
            imgs.get(i).sampler(sampler).imageView(viewForBinding(b, in))
                .imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
            writes.get(i).sType$Default().dstSet(dSet).dstBinding(b)
                .descriptorType(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1).pImageInfo(imgs.slice(i, 1));
            i++;
        }
        VkDescriptorBufferInfo.Buffer uboInfo = VkDescriptorBufferInfo.calloc(1, stack);
        uboInfo.get(0).buffer(uboBufs[slot][passIdx].handle).offset(0).range(Math.max(16, pass.uboBytes()));
        writes.get(i).sType$Default().dstSet(dSet).dstBinding(IrisUniforms.UBO_BINDING)
            .descriptorType(VK10.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).descriptorCount(1).pBufferInfo(uboInfo);
        VK10.vkUpdateDescriptorSets(device, writes, null);
    }

    private void transitionAll(VkCommandBuffer cmd) {
        for (RtMemory.Img[] pair : colortex) {
            for (RtMemory.Img img : pair) {
                VkBarriers.transition(cmd, img.image, VK10.VK_IMAGE_ASPECT_COLOR_BIT,
                    VK10.VK_IMAGE_LAYOUT_UNDEFINED, VK10.VK_IMAGE_LAYOUT_GENERAL);
            }
        }
        for (RtMemory.Img img : depth) {
            VkBarriers.transition(cmd, img.image, VK10.VK_IMAGE_ASPECT_DEPTH_BIT,
                VK10.VK_IMAGE_LAYOUT_UNDEFINED, VK10.VK_IMAGE_LAYOUT_GENERAL);
        }
        for (RtMemory.Img img : shadowDepth) {
            VkBarriers.transition(cmd, img.image, VK10.VK_IMAGE_ASPECT_DEPTH_BIT,
                VK10.VK_IMAGE_LAYOUT_UNDEFINED, VK10.VK_IMAGE_LAYOUT_GENERAL);
        }
        for (RtMemory.Img img : shadowColor) {
            VkBarriers.transition(cmd, img.image, VK10.VK_IMAGE_ASPECT_COLOR_BIT,
                VK10.VK_IMAGE_LAYOUT_UNDEFINED, VK10.VK_IMAGE_LAYOUT_GENERAL);
        }
    }

    private void uploadWhite(VkCommandBuffer cmd, MemoryStack stack) {
        RtMemory.Buf staging = memory.createBuffer("iris_white_staging", 4, VK10.VK_BUFFER_USAGE_TRANSFER_SRC_BIT, true);
        MemoryUtil.memPutInt(staging.mapped, 0xFFFFFFFF);
        VkBarriers.transition(cmd, white.image, VK10.VK_IMAGE_ASPECT_COLOR_BIT,
            VK10.VK_IMAGE_LAYOUT_UNDEFINED, VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
        VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack);
        region.get(0).imageSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
        region.get(0).imageExtent().set(1, 1, 1);
        VK10.vkCmdCopyBufferToImage(cmd, staging.handle, white.image, VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, region);
        VkBarriers.transition(cmd, white.image, VK10.VK_IMAGE_ASPECT_COLOR_BIT,
            VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK10.VK_IMAGE_LAYOUT_GENERAL);
        VkBarriers.fullMemoryBarrier(cmd);
        memory.destroy(staging);
    }

    private void setViewport(MemoryStack stack, VkCommandBuffer cmd, int w, int h) {
        VkViewport.Buffer vp = VkViewport.calloc(1, stack);
        vp.get(0).x(0).y(0).width(w).height(h).minDepth(0).maxDepth(1);
        VK10.vkCmdSetViewport(cmd, 0, vp);
        VkRect2D.Buffer sc = VkRect2D.calloc(1, stack);
        sc.get(0).offset().set(0, 0);
        sc.get(0).extent().set(w, h);
        VK10.vkCmdSetScissor(cmd, 0, sc);
    }

    private void bindPass(VkCommandBuffer cmd, MemoryStack stack, IrisPassGraph.FullPass pass, long pipe, int slot, int passIdx) {
        VK10.vkCmdBindPipeline(cmd, VK10.VK_PIPELINE_BIND_POINT_GRAPHICS, pipe);
        VK10.vkCmdBindDescriptorSets(cmd, VK10.VK_PIPELINE_BIND_POINT_GRAPHICS, pipelineLayout, 0,
            stack.longs(descriptorSets[slot][passIdx]), null);
    }

    private void drawMesh(VkCommandBuffer cmd, MemoryStack stack, GpuMesh mesh) {
        VK10.vkCmdBindVertexBuffers(cmd, 0, stack.longs(mesh.vertices().handle), stack.longs(0L));
        VK10.vkCmdBindIndexBuffer(cmd, mesh.indices().handle, 0, VK10.VK_INDEX_TYPE_UINT32);
        VK10.vkCmdDrawIndexed(cmd, mesh.indexCount(), 1, 0, 0, 0);
    }

    private void recordShadow(VkCommandBuffer cmd, MemoryStack stack, IrisPassGraph.FullPass pass, long pipe,
                              int slot, int passIdx, Map<String, GpuMesh> meshes) {
        setViewport(stack, cmd, SHADOW_SIZE, SHADOW_SIZE);
        bindPass(cmd, stack, pass, pipe, slot, passIdx);
        VkRenderingAttachmentInfo.Buffer color = VkRenderingAttachmentInfo.calloc(1, stack);
        color.get(0).sType$Default().imageView(shadowColor[0].view).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL)
            .loadOp(VK10.VK_ATTACHMENT_LOAD_OP_CLEAR).storeOp(VK10.VK_ATTACHMENT_STORE_OP_STORE)
            .clearValue(cv -> cv.color().float32(0, 0f).float32(1, 0f).float32(2, 0f).float32(3, 0f));
        VkRenderingAttachmentInfo depthAtt = VkRenderingAttachmentInfo.calloc(stack).sType$Default()
            .imageView(shadowDepth[0].view).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL)
            .loadOp(VK10.VK_ATTACHMENT_LOAD_OP_CLEAR).storeOp(VK10.VK_ATTACHMENT_STORE_OP_STORE)
            .clearValue(cv -> cv.depthStencil().depth(1f));
        VkRenderingInfo ri = VkRenderingInfo.calloc(stack).sType$Default()
            .renderArea(ra -> ra.extent().set(SHADOW_SIZE, SHADOW_SIZE)).layerCount(1)
            .pColorAttachments(color).pDepthAttachment(depthAtt);
        beginRendering(cmd, ri);
        for (String key : List.of("terrain", "entities")) {
            GpuMesh mesh = meshes.get(key);
            if (mesh != null) {
                drawMesh(cmd, stack, mesh);
            }
        }
        endRendering(cmd);
    }

    private void recordGeometry(VkCommandBuffer cmd, MemoryStack stack, IrisPassGraph.FullPass pass, long pipe,
                                int slot, int passIdx, Map<String, GpuMesh> meshes, boolean clear) {
        GpuMesh mesh = meshes.get(meshKey(pass.name()));
        if (mesh == null) {
            if (loggedSkips.add(pass.name())) {
                LOGGER.info("[VKB-IRIS] skip {}: no geometry source this frame", pass.name());
            }
            return;
        }
        setViewport(stack, cmd, targets.width(), targets.height());
        bindPass(cmd, stack, pass, pipe, slot, passIdx);
        List<Integer> dbs = pass.drawBuffers();
        VkRenderingAttachmentInfo.Buffer colors = VkRenderingAttachmentInfo.calloc(dbs.size(), stack);
        for (int i = 0; i < dbs.size(); i++) {
            int db = dbs.get(i);
            colors.get(i).sType$Default()
                .imageView(colortex[db][targets.readIndex(db)].view)
                .imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL)
                .loadOp(clear ? VK10.VK_ATTACHMENT_LOAD_OP_CLEAR : VK10.VK_ATTACHMENT_LOAD_OP_LOAD)
                .storeOp(VK10.VK_ATTACHMENT_STORE_OP_STORE)
                .clearValue(cv -> cv.color().float32(0, 0f).float32(1, 0f).float32(2, 0f).float32(3, 0f));
        }
        VkRenderingAttachmentInfo depthAtt = VkRenderingAttachmentInfo.calloc(stack).sType$Default()
            .imageView(depth[0].view).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL)
            .loadOp(clear ? VK10.VK_ATTACHMENT_LOAD_OP_CLEAR : VK10.VK_ATTACHMENT_LOAD_OP_LOAD)
            .storeOp(VK10.VK_ATTACHMENT_STORE_OP_STORE)
            .clearValue(cv -> cv.depthStencil().depth(1f));
        VkRenderingInfo ri = VkRenderingInfo.calloc(stack).sType$Default()
            .renderArea(ra -> ra.extent().set(targets.width(), targets.height())).layerCount(1)
            .pColorAttachments(colors).pDepthAttachment(depthAtt);
        beginRendering(cmd, ri);
        drawMesh(cmd, stack, mesh);
        endRendering(cmd);
    }

    private void recordFullscreen(VkCommandBuffer cmd, MemoryStack stack, IrisPassGraph.FullPass pass, long pipe,
                                  int slot, int passIdx, FrameInputs in) {
        setViewport(stack, cmd, in.width(), in.height());
        bindPass(cmd, stack, pass, pipe, slot, passIdx);
        List<Integer> dbs = pass.drawBuffers();
        boolean isFinal = pass.family() == ProgramName.Family.FINAL;
        VkRenderingAttachmentInfo.Buffer colors = VkRenderingAttachmentInfo.calloc(dbs.size(), stack);
        for (int i = 0; i < dbs.size(); i++) {
            int db = dbs.get(i);
            long view = isFinal ? in.engineColorView() : colortex[db][targets.writeIndex(db)].view;
            colors.get(i).sType$Default().imageView(view).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL)
                .loadOp(VK10.VK_ATTACHMENT_LOAD_OP_DONT_CARE).storeOp(VK10.VK_ATTACHMENT_STORE_OP_STORE);
        }
        VkRenderingInfo ri = VkRenderingInfo.calloc(stack).sType$Default()
            .renderArea(ra -> ra.extent().set(in.width(), in.height())).layerCount(1)
            .pColorAttachments(colors);
        beginRendering(cmd, ri);
        VK10.vkCmdDraw(cmd, 3, 1, 0, 0);
        endRendering(cmd);
        if (!isFinal) {
            for (int db : dbs) {
                targets.swap(db);
            }
        }
    }

    /** Maps a gbuffers program to its geometry source key. */
    static String meshKey(String program) {
        if (program.startsWith("gbuffers_terrain")) {
            return "terrain";
        }
        if (program.equals("gbuffers_water")) {
            return "water";
        }
        if (program.startsWith("gbuffers_entities") || program.equals("gbuffers_spidereyes")) {
            return "entities";
        }
        if (program.startsWith("gbuffers_hand")) {
            return "hand";
        }
        if (program.equals("gbuffers_skybasic") || program.equals("gbuffers_skytextured")) {
            return "sky";
        }
        return program;
    }

    private void beginRendering(VkCommandBuffer cmd, VkRenderingInfo info) {
        if (cmd.getCapabilities().vkCmdBeginRendering != 0L) {
            VK13.vkCmdBeginRendering(cmd, info);
        } else if (cmd.getCapabilities().vkCmdBeginRenderingKHR != 0L) {
            KHRDynamicRendering.vkCmdBeginRenderingKHR(cmd, info);
        } else {
            throw new RtFailure("dynamic_rendering", "neither VK13 nor VK_KHR_dynamic_rendering available");
        }
    }

    private void endRendering(VkCommandBuffer cmd) {
        if (cmd.getCapabilities().vkCmdEndRendering != 0L) {
            VK13.vkCmdEndRendering(cmd);
        } else if (cmd.getCapabilities().vkCmdEndRenderingKHR != 0L) {
            KHRDynamicRendering.vkCmdEndRenderingKHR(cmd);
        } else {
            throw new RtFailure("dynamic_rendering", "neither VK13 nor VK_KHR_dynamic_rendering available");
        }
    }

    public IrisTargets targets() {
        return targets;
    }

    public IrisPassGraph.FullResult graph() {
        return graph;
    }

    @Override
    public synchronized void close() {
        cleanup();
    }

    public synchronized void cleanup() {
        if (device != null) {
            VK10.vkDeviceWaitIdle(device);
            destroyPassResources();
            destroyImages();
            for (List<RtMemory.Buf> slot : meshBufRing) {
                for (RtMemory.Buf buf : slot) {
                    memory.destroy(buf);
                }
                slot.clear();
            }
            if (linearSampler != 0L) {
                VK10.vkDestroySampler(device, linearSampler, null);
                linearSampler = 0L;
            }
            if (nearestSampler != 0L) {
                VK10.vkDestroySampler(device, nearestSampler, null);
                nearestSampler = 0L;
            }
            if (compareSampler != 0L) {
                VK10.vkDestroySampler(device, compareSampler, null);
                compareSampler = 0L;
            }
        }
        graph = null;
    }
}