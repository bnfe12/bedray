package io.github.andrexarlay.vulkancompat.rtx.upscaler;

import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.lwjgl.BufferUtils;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.shaderc.Shaderc;
import org.lwjgl.vulkan.VK10;
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
import org.lwjgl.vulkan.VkMemoryAllocateInfo;
import org.lwjgl.vulkan.VkMemoryRequirements;
import org.lwjgl.vulkan.VkPhysicalDeviceMemoryProperties;
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo;
import org.lwjgl.vulkan.VkSamplerCreateInfo;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;
import org.lwjgl.vulkan.VkWriteDescriptorSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.LongBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Computes 2D screen-space motion vectors on the GPU ({@code velocity_reproject.comp}) for temporal upscalers.
 * <p>
 * Input: the depth COPY of the world target (reverse-Z D32) and camera matrices; output: RG16F motion vectors in
 * pixels of the render resolution, direction previous minus current, without jitter (see the shader).
 * Camera motion is always reprojected; moving entities can also use optional transform or RT path-tracer motion inputs.
 * <p>
 * Per-frame resources: a ring of {@link #SLOTS} slots (uniform buffer + descriptor set each), so a slot is never
 * rewritten while an earlier frame (at most 2 submissions in flight in the engine) may still read it.
 * <p>
 * Images are expected in {@code VK_IMAGE_LAYOUT_GENERAL}, the layout the engine keeps all textures in. The caller
 * brackets the dispatch with {@link VkBarriers#fullMemoryBarrier}.
 */
public final class VelocityPipeline implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger("Bedray/VelocityPipeline");

    public static final String SHADER_RESOURCE_PATH = "/shaders/velocity_reproject.comp";

    /** Number of ring slots; engine MAX_SUBMITS_IN_FLIGHT is 2, one spare. */
    private static final int SLOTS = 3;
    /** 2 x mat4 (cam) + 3 x vec4 (cam) + 2 x mat4 (entity) + 2 x vec4 (entity state) (std140). */
    public static final int UNIFORM_FLOATS = 16 + 16 + 4 + 4 + 4 + 16 + 16 + 4 + 4 + 4;
    public static final long UNIFORM_BYTES = (long) UNIFORM_FLOATS * Float.BYTES;

    public static final String TELEMETRY_LINE =
        "[RT-MV] source=VELOCITY_REPROJECT convention=PREV_MINUS_CURR units=PIXELS cameraValid=UNKNOWN entityValid=UNKNOWN";

    public static String getTelemetryString(boolean cameraValid, boolean entityValid) {
        return String.format(java.util.Locale.ROOT,
            "[RT-MV] source=VELOCITY_REPROJECT convention=PREV_MINUS_CURR units=PIXELS cameraValid=%s entityValid=%s",
            cameraValid, entityValid);
    }

    private final VkDevice device;
    private final VkPhysicalDeviceMemoryProperties memProperties;

    private long shaderModule = 0L;
    private long sampler = 0L;
    private long descriptorSetLayout = 0L;
    private long pipelineLayout = 0L;
    private long computePipeline = 0L;
    private long descriptorPool = 0L;

    private final long[] descriptorSets = new long[SLOTS];
    private final long[] uniformBuffers = new long[SLOTS];
    private final long[] uniformMemories = new long[SLOTS];
    private final long[] uniformMapped = new long[SLOTS];
    private int slot = -1;

    private boolean initialized = false;

    public VelocityPipeline(VkDevice device) {
        this.device = Objects.requireNonNull(device, "VkDevice must not be null");
        this.memProperties = VkPhysicalDeviceMemoryProperties.calloc();
        VK10.vkGetPhysicalDeviceMemoryProperties(device.getPhysicalDevice(), this.memProperties);
        init();
    }

    public synchronized void init() {
        if (initialized) {
            return;
        }
        LOGGER.info("[VelocityPipeline] Initializing motion vector reprojection compute pipeline...");

        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer spirv = compileShaderToSpirv();
            VkShaderModuleCreateInfo moduleCreateInfo = VkShaderModuleCreateInfo.calloc(stack)
                .sType$Default()
                .pCode(spirv);
            LongBuffer pModule = stack.mallocLong(1);
            check(VK10.vkCreateShaderModule(device, moduleCreateInfo, null, pModule), "vkCreateShaderModule");
            this.shaderModule = pModule.get(0);

            VkSamplerCreateInfo samplerInfo = VkSamplerCreateInfo.calloc(stack)
                .sType$Default()
                .magFilter(VK10.VK_FILTER_NEAREST)
                .minFilter(VK10.VK_FILTER_NEAREST)
                .mipmapMode(VK10.VK_SAMPLER_MIPMAP_MODE_NEAREST)
                .addressModeU(VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                .addressModeV(VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                .addressModeW(VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                .maxLod(0.0f);
            LongBuffer pSampler = stack.mallocLong(1);
            check(VK10.vkCreateSampler(device, samplerInfo, null, pSampler), "vkCreateSampler");
            this.sampler = pSampler.get(0);

            VkDescriptorSetLayoutBinding.Buffer bindings = VkDescriptorSetLayoutBinding.calloc(3, stack);
            bindings.get(0).binding(0).descriptorType(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                .descriptorCount(1).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);
            bindings.get(1).binding(1).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                .descriptorCount(1).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);
            bindings.get(2).binding(2).descriptorType(VK10.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER)
                .descriptorCount(1).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);
            VkDescriptorSetLayoutCreateInfo layoutCreateInfo = VkDescriptorSetLayoutCreateInfo.calloc(stack)
                .sType$Default()
                .pBindings(bindings);
            LongBuffer pSetLayout = stack.mallocLong(1);
            check(VK10.vkCreateDescriptorSetLayout(device, layoutCreateInfo, null, pSetLayout), "vkCreateDescriptorSetLayout");
            this.descriptorSetLayout = pSetLayout.get(0);

            VkPipelineLayoutCreateInfo pipeLayoutInfo = VkPipelineLayoutCreateInfo.calloc(stack)
                .sType$Default()
                .pSetLayouts(pSetLayout);
            LongBuffer pPipeLayout = stack.mallocLong(1);
            check(VK10.vkCreatePipelineLayout(device, pipeLayoutInfo, null, pPipeLayout), "vkCreatePipelineLayout");
            this.pipelineLayout = pPipeLayout.get(0);

            VkComputePipelineCreateInfo.Buffer compPipeInfo = VkComputePipelineCreateInfo.calloc(1, stack);
            compPipeInfo.get(0)
                .sType$Default()
                .stage().sType$Default()
                    .stage(VK10.VK_SHADER_STAGE_COMPUTE_BIT)
                    .module(this.shaderModule)
                    .pName(stack.UTF8("main"));
            compPipeInfo.get(0).layout(this.pipelineLayout);
            LongBuffer pComputePipeline = stack.mallocLong(1);
            check(VK10.vkCreateComputePipelines(device, 0L, compPipeInfo, null, pComputePipeline), "vkCreateComputePipelines");
            this.computePipeline = pComputePipeline.get(0);

            VkDescriptorPoolSize.Buffer poolSizes = VkDescriptorPoolSize.calloc(3, stack);
            poolSizes.get(0).type(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(SLOTS);
            poolSizes.get(1).type(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(SLOTS);
            poolSizes.get(2).type(VK10.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).descriptorCount(SLOTS);
            VkDescriptorPoolCreateInfo poolInfo = VkDescriptorPoolCreateInfo.calloc(stack)
                .sType$Default()
                .maxSets(SLOTS)
                .pPoolSizes(poolSizes);
            LongBuffer pPool = stack.mallocLong(1);
            check(VK10.vkCreateDescriptorPool(device, poolInfo, null, pPool), "vkCreateDescriptorPool");
            this.descriptorPool = pPool.get(0);

            for (int i = 0; i < SLOTS; i++) {
                createUniformBuffer(stack, i);
                LongBuffer layouts = stack.longs(this.descriptorSetLayout);
                VkDescriptorSetAllocateInfo allocInfo = VkDescriptorSetAllocateInfo.calloc(stack)
                    .sType$Default()
                    .descriptorPool(this.descriptorPool)
                    .pSetLayouts(layouts);
                LongBuffer pSet = stack.mallocLong(1);
                check(VK10.vkAllocateDescriptorSets(device, allocInfo, pSet), "vkAllocateDescriptorSets");
                this.descriptorSets[i] = pSet.get(0);
            }

            initialized = true;
            LOGGER.info("[VelocityPipeline] Compute pipeline initialized ({} frame slots).", SLOTS);
        } catch (Throwable t) {
            cleanup();
            throw t;
        }
    }

    private static void check(int result, String what) {
        if (result != VK10.VK_SUCCESS) {
            throw new IllegalStateException(what + " failed: " + result);
        }
    }

    private void createUniformBuffer(MemoryStack stack, int i) {
        org.lwjgl.vulkan.VkBufferCreateInfo bufInfo = org.lwjgl.vulkan.VkBufferCreateInfo.calloc(stack)
            .sType$Default()
            .size(UNIFORM_BYTES)
            .usage(VK10.VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT)
            .sharingMode(VK10.VK_SHARING_MODE_EXCLUSIVE);
        LongBuffer pBuffer = stack.mallocLong(1);
        check(VK10.vkCreateBuffer(device, bufInfo, null, pBuffer), "vkCreateBuffer");
        this.uniformBuffers[i] = pBuffer.get(0);

        VkMemoryRequirements memReqs = VkMemoryRequirements.calloc(stack);
        VK10.vkGetBufferMemoryRequirements(device, this.uniformBuffers[i], memReqs);
        int memTypeIndex = findMemoryType(memReqs.memoryTypeBits(),
            VK10.VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK10.VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
        if (memTypeIndex < 0) {
            throw new IllegalStateException("No host visible + coherent memory type for the velocity uniform buffer");
        }
        VkMemoryAllocateInfo allocInfo = VkMemoryAllocateInfo.calloc(stack)
            .sType$Default()
            .allocationSize(memReqs.size())
            .memoryTypeIndex(memTypeIndex);
        LongBuffer pMemory = stack.mallocLong(1);
        check(VK10.vkAllocateMemory(device, allocInfo, null, pMemory), "vkAllocateMemory");
        this.uniformMemories[i] = pMemory.get(0);
        check(VK10.vkBindBufferMemory(device, this.uniformBuffers[i], this.uniformMemories[i], 0), "vkBindBufferMemory");

        PointerBuffer pData = stack.mallocPointer(1);
        check(VK10.vkMapMemory(device, this.uniformMemories[i], 0, UNIFORM_BYTES, 0, pData), "vkMapMemory");
        this.uniformMapped[i] = pData.get(0); // persistent mapping, HOST_COHERENT
    }

    /** Compiles velocity_reproject.comp with Shaderc (also used by the unit test). */
    public static ByteBuffer compileShaderToSpirv() {
        try {
            String glsl = loadGlslSource(SHADER_RESOURCE_PATH);
            long compiler = Shaderc.shaderc_compiler_initialize();
            long options = Shaderc.shaderc_compile_options_initialize();
            try {
                Shaderc.shaderc_compile_options_set_target_env(options, Shaderc.shaderc_target_env_vulkan, Shaderc.shaderc_env_version_vulkan_1_2);
                Shaderc.shaderc_compile_options_set_optimization_level(options, Shaderc.shaderc_optimization_level_performance);
                long result = Shaderc.shaderc_compile_into_spv(
                    compiler, glsl, Shaderc.shaderc_compute_shader, "velocity_reproject.comp", "main", options);
                try {
                    int status = Shaderc.shaderc_result_get_compilation_status(result);
                    if (status != Shaderc.shaderc_compilation_status_success) {
                        String error = Shaderc.shaderc_result_get_error_message(result);
                        throw new IllegalStateException("Shaderc compilation failed (" + status + "): " + error);
                    }
                    ByteBuffer bytes = Shaderc.shaderc_result_get_bytes(result);
                    ByteBuffer direct = BufferUtils.createByteBuffer(bytes.remaining());
                    direct.put(bytes);
                    direct.flip();
                    LOGGER.info("[VelocityPipeline] Runtime Shaderc compilation succeeded (SPIR-V size: {} bytes)", direct.remaining());
                    return direct;
                } finally {
                    Shaderc.shaderc_result_release(result);
                }
            } finally {
                Shaderc.shaderc_compile_options_release(options);
                Shaderc.shaderc_compiler_release(compiler);
            }
        } catch (RuntimeException e) {
            throw new IllegalStateException("Failed to compile velocity_reproject.comp: " + e.getMessage(), e);
        }
    }

    private static String loadGlslSource(String path) {
        InputStream is = VelocityPipeline.class.getResourceAsStream(path);
        if (is == null) {
            String alt = path.startsWith("/") ? path.substring(1) : "/" + path;
            is = VelocityPipeline.class.getResourceAsStream(alt);
        }
        if (is == null) {
            throw new IllegalStateException("Resource not found: " + path);
        }
        try (InputStream in = is) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Failed to read resource " + path, e);
        }
    }

    private int findMemoryType(int typeFilter, int properties) {
        for (int i = 0; i < memProperties.memoryTypeCount(); i++) {
            if ((typeFilter & (1 << i)) != 0
                && (memProperties.memoryTypes(i).propertyFlags() & properties) == properties) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Records the descriptor update, uniform upload and compute dispatch for one frame. Both images must be in
     * GENERAL layout; the caller adds the surrounding memory barriers.
     *
     * @param depthView  view (DEPTH aspect) of the world depth copy, sampled
     * @param mvView     view of the RG16F motion vector image, storage
     */
    public void dispatch(
        VkCommandBuffer cmd,
        long depthView,
        long mvView,
        int width,
        int height,
        Matrix4f invViewProj,
        Matrix4f prevViewProj,
        Vec3 camDelta
    ) {
        dispatch(cmd, depthView, mvView, width, height, invViewProj, prevViewProj, camDelta, null, null, 0, false);
    }

    /**
     * Same as {@link #dispatch(VkCommandBuffer, long, long, int, int, Matrix4f, Matrix4f, Vec3)}, but {@code depthView} holds the
     * linear view Z of the RT G-buffer ({@code rt_path.comp}: {@code -dot(pos, forward)}, sky {@code >= 1e5}) instead of a
     * hardware NDC depth.
     */
    public void dispatchLinearViewZ(VkCommandBuffer cmd, long depthView, long mvView, int width, int height,
                                    Matrix4f invViewProj, Matrix4f prevViewProj, Vec3 camDelta) {
        dispatchLinearViewZ(cmd, depthView, mvView, width, height, invViewProj, prevViewProj, camDelta, 0L);
    }

    /** {@code entityMotionAddress}: device address of the per-pixel entity motion written by rt_path (0 = none). */
    public void dispatchLinearViewZ(VkCommandBuffer cmd, long depthView, long mvView, int width, int height,
                                    Matrix4f invViewProj, Matrix4f prevViewProj, Vec3 camDelta, long entityMotionAddress) {
        dispatchLinearViewZ(cmd, depthView, mvView, width, height, invViewProj, prevViewProj, camDelta, entityMotionAddress, 0L);
    }

    public void dispatchLinearViewZ(VkCommandBuffer cmd, long depthView, long mvView, int width, int height,
                                    Matrix4f invViewProj, Matrix4f prevViewProj, Vec3 camDelta, long entityMotionAddress, long maskAddress) {
        dispatchLinearViewZ(cmd, depthView, mvView, width, height, invViewProj, prevViewProj, camDelta, entityMotionAddress, maskAddress, 0L);
    }

    /** {@code maskAddress}: float per pixel written with the disocclusion mask (1 = no valid history), 0 = none;
     *  {@code prevDepthAddress}: previous frame's linear view Z (float per pixel) for the depth disocclusion test, 0 = none. */
    public void dispatchLinearViewZ(VkCommandBuffer cmd, long depthView, long mvView, int width, int height,
                                    Matrix4f invViewProj, Matrix4f prevViewProj, Vec3 camDelta, long entityMotionAddress, long maskAddress,
                                    long prevDepthAddress) {
        this.prevDepthAddress = prevDepthAddress;
        this.entityMotionAddress = entityMotionAddress;
        this.maskAddress = maskAddress;
        depthMode = DEPTH_LINEAR_VIEW_Z;
        try {
            dispatch(cmd, depthView, mvView, width, height, invViewProj, prevViewProj, camDelta, null, null, 0, false);
        } finally {
            depthMode = DEPTH_HARDWARE;
            entityMotionAddress = 0L;
            maskAddress = 0L;
            prevDepthAddress = 0L;
        }
    }

    /**
     * Same as {@link #dispatch(VkCommandBuffer, long, long, int, int, Matrix4f, Matrix4f, Vec3)}, but {@code depthView} holds the
     * RT output depth ({@code rt_rays.comp} / {@code rt_path.comp} {@code outDepth}: distance from the camera along the primary
     * ray in blocks, positive, sky {@code 1e6}). Used by the temporal upscalers (DLSS SR / XeSS / FSR) when the RT image
     * replaces the raster world: the hardware depth convention would put a distance of e.g. 10 blocks through invViewProj
     * as NDC z (motion vectors of the whole world wrong while walking).
     */
    public void dispatchRayDistance(VkCommandBuffer cmd, long depthView, long mvView, int width, int height,
                                    Matrix4f invViewProj, Matrix4f prevViewProj, Vec3 camDelta) {
        depthMode = DEPTH_RAY_DISTANCE;
        try {
            dispatch(cmd, depthView, mvView, width, height, invViewProj, prevViewProj, camDelta, null, null, 0, false);
        } finally {
            depthMode = DEPTH_HARDWARE;
        }
    }

    /** Depth conventions of binding 0 (camDelta.w of the shader uniforms). */
    public static final int DEPTH_HARDWARE = 0;
    public static final int DEPTH_LINEAR_VIEW_Z = 1;
    public static final int DEPTH_RAY_DISTANCE = 2;

    private int depthMode = DEPTH_HARDWARE;
    private long entityMotionAddress;
    private long maskAddress;
    private long prevDepthAddress;

    public void dispatch(
        VkCommandBuffer cmd,
        long depthView,
        long mvView,
        int width,
        int height,
        Matrix4f invViewProj,
        Matrix4f prevViewProj,
        Vec3 camDelta,
        Matrix4f entityCurrTransform,
        Matrix4f entityPrevTransform,
        int entityCustomIndex,
        boolean historyReset
    ) {
        if (!initialized) {
            init();
        }
        if (cmd == null || depthView == 0L || mvView == 0L || width <= 0 || height <= 0) {
            throw new IllegalArgumentException("VelocityPipeline.dispatch: invalid arguments cmd=" + cmd + " depthView="
                + depthView + " mvView=" + mvView + " size=" + width + "x" + height);
        }
        slot = (slot + 1) % SLOTS;
        uploadUniforms(slot, invViewProj, prevViewProj, camDelta, width, height,
            entityCurrTransform, entityPrevTransform, entityCustomIndex, historyReset);

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorImageInfo.Buffer depthInfo = VkDescriptorImageInfo.calloc(1, stack);
            depthInfo.get(0).sampler(this.sampler).imageView(depthView).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
            VkDescriptorImageInfo.Buffer mvInfo = VkDescriptorImageInfo.calloc(1, stack);
            mvInfo.get(0).sampler(0L).imageView(mvView).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
            VkDescriptorBufferInfo.Buffer uboInfo = VkDescriptorBufferInfo.calloc(1, stack);
            uboInfo.get(0).buffer(this.uniformBuffers[slot]).offset(0).range(UNIFORM_BYTES);

            VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(3, stack);
            writes.get(0).sType$Default().dstSet(this.descriptorSets[slot]).dstBinding(0).descriptorCount(1)
                .descriptorType(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).pImageInfo(depthInfo);
            writes.get(1).sType$Default().dstSet(this.descriptorSets[slot]).dstBinding(1).descriptorCount(1)
                .descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).pImageInfo(mvInfo);
            writes.get(2).sType$Default().dstSet(this.descriptorSets[slot]).dstBinding(2).descriptorCount(1)
                .descriptorType(VK10.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).pBufferInfo(uboInfo);
            VK10.vkUpdateDescriptorSets(device, writes, null);

            VK10.vkCmdBindPipeline(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, this.computePipeline);
            VK10.vkCmdBindDescriptorSets(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, this.pipelineLayout, 0,
                stack.longs(this.descriptorSets[slot]), null);
            VK10.vkCmdDispatch(cmd, Math.max(1, (width + 7) / 8), Math.max(1, (height + 7) / 8), 1);
        }
    }

    private void uploadUniforms(
        int s,
        Matrix4f invViewProj,
        Matrix4f prevViewProj,
        Vec3 camDelta,
        int width,
        int height,
        Matrix4f entityCurrTransform,
        Matrix4f entityPrevTransform,
        int entityCustomIndex,
        boolean historyReset
    ) {
        float[] data = new float[UNIFORM_FLOATS];
        int o = 0;
        (invViewProj != null ? invViewProj : new Matrix4f()).get(data, o);
        o += 16;
        (prevViewProj != null ? prevViewProj : new Matrix4f()).get(data, o);
        o += 16;
        data[o++] = camDelta != null ? (float) camDelta.x : 0.0f;
        data[o++] = camDelta != null ? (float) camDelta.y : 0.0f;
        data[o++] = camDelta != null ? (float) camDelta.z : 0.0f;
        data[o++] = (float) depthMode;
        org.joml.Vector3f bobOrigin = io.github.andrexarlay.vulkancompat.rtx.WorldTargetManager.levelCameraOriginOr(null);
        data[o++] = bobOrigin.x;
        data[o++] = bobOrigin.y;
        data[o++] = bobOrigin.z;
        data[o++] = 0.0f;
        data[o++] = (float) width;
        data[o++] = (float) height;
        data[o++] = 1.0f / (float) width;
        data[o++] = 1.0f / (float) height;

        Matrix4f currEnt = entityCurrTransform != null ? entityCurrTransform : new Matrix4f();
        Matrix4f prevEnt = entityPrevTransform != null ? entityPrevTransform : currEnt;
        currEnt.get(data, o);
        o += 16;
        prevEnt.get(data, o);
        o += 16;

        boolean hasEntity = entityCurrTransform != null;
        data[o++] = hasEntity ? 1.0f : 0.0f;
        data[o++] = (float) entityCustomIndex;
        data[o++] = historyReset ? 1.0f : 0.0f;
        data[o++] = 16.0f;
        data[o++] = Float.intBitsToFloat((int) entityMotionAddress);
        data[o++] = Float.intBitsToFloat((int) (entityMotionAddress >>> 32));
        data[o++] = Float.intBitsToFloat((int) maskAddress);
        data[o++] = Float.intBitsToFloat((int) (maskAddress >>> 32));
        data[o++] = Float.intBitsToFloat((int) prevDepthAddress);
        data[o++] = Float.intBitsToFloat((int) (prevDepthAddress >>> 32));
        data[o++] = 0f;
        data[o] = 0f;

        FloatBuffer fb = MemoryUtil.memFloatBuffer(uniformMapped[s], UNIFORM_FLOATS);
        fb.put(data);
    }

    public record ReprojectionResult(
        float mvX,
        float mvY,
        float dynamicObjectMask,
        float disocclusionMask,
        boolean valid
    ) {}

    /**
     * CPU reference reprojection implementation matching velocity_reproject.comp.
     * Computes motion vectors, dynamicObjectMask, and disocclusionMask while protecting against NaN/Inf and teleports.
     */
    public static ReprojectionResult reprojectCpu(
        float uvX,
        float uvY,
        float depth,
        int width,
        int height,
        Matrix4f invViewProj,
        Matrix4f prevViewProj,
        Vec3 camDelta,
        boolean isEntity,
        Matrix4f entityCurrTransform,
        Matrix4f entityPrevTransform,
        int entityCustomIndex,
        boolean historyReset
    ) {
        return reprojectCpu(uvX, uvY, depth, width, height, invViewProj, prevViewProj, camDelta, isEntity,
            entityCurrTransform, entityPrevTransform, entityCustomIndex, historyReset, DEPTH_HARDWARE, new org.joml.Vector3f());
    }

    /** CPU reference overload with the depth convention and bobbed camera origin used by the compute shader. */
    public static ReprojectionResult reprojectCpu(
        float uvX,
        float uvY,
        float depth,
        int width,
        int height,
        Matrix4f invViewProj,
        Matrix4f prevViewProj,
        Vec3 camDelta,
        boolean isEntity,
        Matrix4f entityCurrTransform,
        Matrix4f entityPrevTransform,
        int entityCustomIndex,
        boolean historyReset,
        int depthConvention,
        org.joml.Vector3f cameraOrigin
    ) {
        float ndcX = uvX * 2.0f - 1.0f;
        float ndcY = uvY * 2.0f - 1.0f;

        Matrix4f invVP = invViewProj != null ? invViewProj : new Matrix4f();
        Matrix4f prevVP = prevViewProj != null ? prevViewProj : new Matrix4f();

        org.joml.Vector4f prevClip;
        float dynamicObjectMask = 0.0f;
        float disocclusionMask = 0.0f;

        boolean skyDepth = depthConvention == DEPTH_LINEAR_VIEW_Z || depthConvention == DEPTH_RAY_DISTANCE
            ? Math.abs(depth) >= 1.0e5f : depth >= 1.0e5f || depth <= 1.0e-7f;
        org.joml.Vector3f origin = cameraOrigin != null ? new org.joml.Vector3f(cameraOrigin) : new org.joml.Vector3f();
        if (skyDepth) {
            org.joml.Vector4f rayW = invVP.transform(new org.joml.Vector4f(ndcX, ndcY, 0.5f, 1.0f));
            org.joml.Vector3f rayDir = new org.joml.Vector3f(rayW.x / rayW.w, rayW.y / rayW.w, rayW.z / rayW.w).sub(origin).normalize();
            prevClip = prevVP.transform(new org.joml.Vector4f(rayDir, 0.0f));
        } else {
            org.joml.Vector4f rayW = invVP.transform(new org.joml.Vector4f(ndcX, ndcY, 0.5f, 1.0f));
            org.joml.Vector3f unprojected = new org.joml.Vector3f(rayW.x / rayW.w, rayW.y / rayW.w, rayW.z / rayW.w);
            org.joml.Vector3f worldPosRel;
            org.joml.Vector4f worldPosRel4;
            if (depthConvention == DEPTH_LINEAR_VIEW_Z) {
                org.joml.Vector3f dir = new org.joml.Vector3f(unprojected).sub(origin).normalize();
                org.joml.Vector4f fwdW = invVP.transform(new org.joml.Vector4f(0.0f, 0.0f, 0.5f, 1.0f));
                org.joml.Vector3f forward = new org.joml.Vector3f(fwdW.x / fwdW.w, fwdW.y / fwdW.w, fwdW.z / fwdW.w).sub(origin).normalize();
                worldPosRel = new org.joml.Vector3f(origin).fma(Math.abs(depth) / Math.max(dir.dot(forward), 1.0e-4f), dir);
                worldPosRel4 = new org.joml.Vector4f(worldPosRel, 1.0f);
            } else if (depthConvention == DEPTH_RAY_DISTANCE) {
                org.joml.Vector3f dir = new org.joml.Vector3f(unprojected).sub(origin).normalize();
                worldPosRel = new org.joml.Vector3f(origin).fma(depth, dir);
                worldPosRel4 = new org.joml.Vector4f(worldPosRel, 1.0f);
            } else {
                worldPosRel4 = invVP.transform(new org.joml.Vector4f(ndcX, ndcY, depth, 1.0f));
                worldPosRel = new org.joml.Vector3f(worldPosRel4.x / worldPosRel4.w, worldPosRel4.y / worldPosRel4.w, worldPosRel4.z / worldPosRel4.w);
            }

            if (isEntity && entityCurrTransform != null) {
                org.joml.Vector3f currPos = entityCurrTransform.getTranslation(new org.joml.Vector3f());
                org.joml.Vector3f prevPos = entityPrevTransform != null
                    ? entityPrevTransform.getTranslation(new org.joml.Vector3f())
                    : currPos;
                float moveDist = currPos.distance(prevPos);
                boolean teleported = historyReset || moveDist > 16.0f;

                dynamicObjectMask = moveDist > 1.0e-4f ? 1.0f : 0.0f;
                disocclusionMask = 0.0f;

                if (teleported) {
                    disocclusionMask = 1.0f;
                    prevClip = new org.joml.Vector4f(ndcX * worldPosRel4.w, ndcY * worldPosRel4.w, 0.5f, 1.0f);
                } else {
                    Matrix4f invCurr = new Matrix4f(entityCurrTransform).invert();
                    org.joml.Vector4f localPos = invCurr.transform(new org.joml.Vector4f(worldPosRel, 1.0f));
                    Matrix4f prevEnt = entityPrevTransform != null ? entityPrevTransform : entityCurrTransform;
                    org.joml.Vector4f prevPosRel = prevEnt.transform(localPos);
                    prevClip = prevVP.transform(new org.joml.Vector4f(prevPosRel.x, prevPosRel.y, prevPosRel.z, 1.0f));
                }
            } else {
                float cdx = camDelta != null ? (float) camDelta.x : 0.0f;
                float cdy = camDelta != null ? (float) camDelta.y : 0.0f;
                float cdz = camDelta != null ? (float) camDelta.z : 0.0f;
                org.joml.Vector4f prevPosRel = new org.joml.Vector4f(worldPosRel.x + cdx, worldPosRel.y + cdy, worldPosRel.z + cdz, 1.0f);
                prevClip = prevVP.transform(prevPosRel);
            }
        }

        float prevUvX = uvX;
        float prevUvY = uvY;
        if (Math.abs(prevClip.w) > 1e-7f) {
            prevUvX = (prevClip.x / prevClip.w) * 0.5f + 0.5f;
            prevUvY = (prevClip.y / prevClip.w) * 0.5f + 0.5f;
        } else {
            disocclusionMask = 1.0f;
        }

        if (prevUvX < 0.0f || prevUvX > 1.0f || prevUvY < 0.0f || prevUvY > 1.0f) {
            disocclusionMask = 1.0f;
        }

        float mvX = (prevUvX - uvX) * width;
        float mvY = (prevUvY - uvY) * height;

        float maxMotion = Math.max(width, height) * 0.5f;
        float len = (float) Math.hypot(mvX, mvY);
        if (len > maxMotion) {
            mvX = (mvX / len) * maxMotion;
            mvY = (mvY / len) * maxMotion;
        }

        if (Float.isNaN(mvX) || Float.isNaN(mvY) || Float.isInfinite(mvX) || Float.isInfinite(mvY)) {
            mvX = 0.0f;
            mvY = 0.0f;
            disocclusionMask = 1.0f;
        }
        if (Float.isNaN(dynamicObjectMask) || Float.isInfinite(dynamicObjectMask)) {
            dynamicObjectMask = 0.0f;
        }
        if (Float.isNaN(disocclusionMask) || Float.isInfinite(disocclusionMask)) {
            disocclusionMask = 1.0f;
        }

        return new ReprojectionResult(mvX, mvY, dynamicObjectMask, disocclusionMask, true);
    }

    @Override
    public synchronized void close() {
        cleanup();
    }

    /** Frees the Vulkan objects. The GPU must not be using them (callers wait for idle before destroying). */
    public synchronized void cleanup() {
        if (!initialized && shaderModule == 0L) {
            return;
        }
        LOGGER.info("[VelocityPipeline] Cleaning up motion vector compute pipeline...");
        for (int i = 0; i < SLOTS; i++) {
            if (uniformMemories[i] != 0L && uniformMapped[i] != 0L) {
                VK10.vkUnmapMemory(device, uniformMemories[i]);
            }
            uniformMapped[i] = 0L;
            if (uniformBuffers[i] != 0L) {
                VK10.vkDestroyBuffer(device, uniformBuffers[i], null);
                uniformBuffers[i] = 0L;
            }
            if (uniformMemories[i] != 0L) {
                VK10.vkFreeMemory(device, uniformMemories[i], null);
                uniformMemories[i] = 0L;
            }
            descriptorSets[i] = 0L;
        }
        if (descriptorPool != 0L) {
            VK10.vkDestroyDescriptorPool(device, descriptorPool, null);
            descriptorPool = 0L;
        }
        if (computePipeline != 0L) {
            VK10.vkDestroyPipeline(device, computePipeline, null);
            computePipeline = 0L;
        }
        if (pipelineLayout != 0L) {
            VK10.vkDestroyPipelineLayout(device, pipelineLayout, null);
            pipelineLayout = 0L;
        }
        if (descriptorSetLayout != 0L) {
            VK10.vkDestroyDescriptorSetLayout(device, descriptorSetLayout, null);
            descriptorSetLayout = 0L;
        }
        if (sampler != 0L) {
            VK10.vkDestroySampler(device, sampler, null);
            sampler = 0L;
        }
        if (shaderModule != 0L) {
            VK10.vkDestroyShaderModule(device, shaderModule, null);
            shaderModule = 0L;
        }
        memProperties.free();
        initialized = false;
    }

    public boolean isInitialized() {
        return initialized;
    }
}
