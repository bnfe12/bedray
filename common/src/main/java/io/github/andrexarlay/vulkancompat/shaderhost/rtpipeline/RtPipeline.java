package io.github.andrexarlay.vulkancompat.shaderhost.rtpipeline;

import io.github.andrexarlay.vulkancompat.rt.world.RtVulkanPipelineCache;
import io.github.andrexarlay.vulkancompat.shaderhost.rtpipeline.RtPipelineConfig.RayPass;
import io.github.andrexarlay.vulkancompat.shaderhost.rtpipeline.RtPipelineConfig.SbtLayout;
import io.github.andrexarlay.vulkancompat.shaderhost.rtpipeline.RtPipelineLayouts.ShaderStageType;
import io.github.andrexarlay.vulkancompat.shaderhost.rtpipeline.RtPipelineSbtBuilder.MultiPassSbt;
import io.github.andrexarlay.vulkancompat.shaderhost.rtpipeline.RtPipelineSbtBuilder.PassSbt;
import io.github.andrexarlay.vulkancompat.shaderhost.rtpipeline.RtPipelineSbtBuilder.VkRegions;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRRayTracingPipeline;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkMemoryBarrier;
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo;
import org.lwjgl.vulkan.VkPipelineShaderStageCreateInfo;
import org.lwjgl.vulkan.VkRayTracingPipelineCreateInfoKHR;
import org.lwjgl.vulkan.VkRayTracingShaderGroupCreateInfoKHR;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.LongBuffer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

import static io.github.andrexarlay.vulkancompat.shaderhost.rtpipeline.RtPipelineLayouts.DEFAULT_MAX_RECURSION_DEPTH;
import static io.github.andrexarlay.vulkancompat.shaderhost.rtpipeline.RtPipelineLayouts.PUSH_CONSTANT_BYTES;
import static io.github.andrexarlay.vulkancompat.shaderhost.rtpipeline.RtPipelineLayouts.PUSH_CONSTANT_STAGES;
import static io.github.andrexarlay.vulkancompat.shaderhost.rtpipeline.RtPipelineLayouts.STAGES_PER_PASS;
import static io.github.andrexarlay.vulkancompat.shaderhost.rtpipeline.RtPipelineLayouts.TOTAL_SHADER_GROUPS;
import static io.github.andrexarlay.vulkancompat.shaderhost.rtpipeline.RtPipelineLayouts.TOTAL_STAGES;
import static io.github.andrexarlay.vulkancompat.shaderhost.rtpipeline.RtPipelineLayouts.createDescriptorSetLayoutInfo;
import static io.github.andrexarlay.vulkancompat.shaderhost.rtpipeline.RtPipelineLayouts.createPipelineLayoutInfo;
import static io.github.andrexarlay.vulkancompat.shaderhost.rtpipeline.RtPipelineLayouts.createPipelineShaderGroups;
import static io.github.andrexarlay.vulkancompat.shaderhost.rtpipeline.RtPipelineLayouts.createPipelineShaderStages;
import static io.github.andrexarlay.vulkancompat.shaderhost.rtpipeline.RtPipelineLayouts.createReflectedDescriptorSetLayoutInfo;

/**
 * Ray-tracing-pipeline shaderpack host: loads the compiled SPIR-V stages, builds the shader binding table, records
 * {@code vkCmdTraceRaysKHR} dispatches with inter-pass barriers, and owns the live device pipeline lifecycle.
 * <p>
 * Shader ordering and the descriptor ABI live in {@link RtPipelineLayouts}; shared status and F3 telemetry live in
 * {@link RtPipelineStatus}. This class orchestrates those two owners plus {@link RtPipelineSbtBuilder}.
 */
public final class RtPipeline implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger("VulkanRtx/RtPipeline");

    public static final int SPIRV_MAGIC = 0x07230203;

    // =========================================================================
    // Shader Bundle & SPIR-V Loading
    // =========================================================================

    public record PassShaders(
            RayPass pass,
            ByteBuffer raygen,
            ByteBuffer miss,
            ByteBuffer closestHit,
            ByteBuffer anyHit
    ) {
        public PassShaders {
            Objects.requireNonNull(pass, "pass must not be null");
            Objects.requireNonNull(raygen, "raygen bytecode must not be null");
            Objects.requireNonNull(miss, "miss bytecode must not be null");
            Objects.requireNonNull(closestHit, "closestHit bytecode must not be null");
            Objects.requireNonNull(anyHit, "anyHit bytecode must not be null");
        }

        public ByteBuffer getBytecode(ShaderStageType type) {
            return switch (type) {
                case RAYGEN -> raygen;
                case MISS -> miss;
                case CLOSEST_HIT -> closestHit;
                case ANY_HIT -> anyHit;
            };
        }
    }

    public record RtShaderBundle(
            PassShaders diffuse,
            PassShaders reflection,
            PassShaders refraction
    ) {
        public RtShaderBundle {
            Objects.requireNonNull(diffuse, "diffuse shaders must not be null");
            Objects.requireNonNull(reflection, "reflection shaders must not be null");
            Objects.requireNonNull(refraction, "refraction shaders must not be null");
        }

        public PassShaders forPass(RayPass pass) {
            return switch (pass) {
                case DIFFUSE -> diffuse;
                case REFLECTION -> reflection;
                case REFRACTION -> refraction;
            };
        }

        public List<PassShaders> passes() {
            return List.of(diffuse, reflection, refraction);
        }

        public ByteBuffer getShader(int stageIndex) {
            if (stageIndex < 0 || stageIndex >= TOTAL_STAGES) {
                throw new IndexOutOfBoundsException("Invalid stageIndex: " + stageIndex + " (0.." + (TOTAL_STAGES - 1) + ")");
            }
            int passIdx = stageIndex / STAGES_PER_PASS;
            int typeIdx = stageIndex % STAGES_PER_PASS;
            RayPass pass = RtPipelineConfig.PASSES.get(passIdx);
            ShaderStageType type = ShaderStageType.values()[typeIdx];
            return forPass(pass).getBytecode(type);
        }
    }

    // =========================================================================
    // SPIR-V Loading Utilities & Synthetic Bundles
    // =========================================================================

    public static boolean validateSpirvHeader(ByteBuffer buffer) {
        if (buffer == null || buffer.remaining() < 20) {
            return false;
        }
        int pos = buffer.position();
        ByteOrder originalOrder = buffer.order();
        try {
            buffer.order(ByteOrder.LITTLE_ENDIAN);
            int magic = buffer.getInt(pos);
            if (magic == SPIRV_MAGIC) {
                return true;
            }
            buffer.order(ByteOrder.BIG_ENDIAN);
            magic = buffer.getInt(pos);
            return magic == SPIRV_MAGIC;
        } finally {
            buffer.order(originalOrder);
        }
    }

    public static ByteBuffer createSyntheticSpirv(int dwordCount) {
        int words = Math.max(dwordCount, 5);
        ByteBuffer buffer = ByteBuffer.allocateDirect(words * 4).order(ByteOrder.nativeOrder());
        buffer.putInt(SPIRV_MAGIC);
        buffer.putInt(0x00010400); // SPIR-V 1.4
        buffer.putInt(0x000D000A); // Generator ID
        buffer.putInt(words);      // Bound
        buffer.putInt(0);          // Schema
        while (buffer.hasRemaining()) {
            buffer.putInt(0);
        }
        buffer.flip();
        return buffer;
    }

    public static ByteBuffer createSyntheticSpirv() {
        return createSyntheticSpirv(8);
    }

    public static RtShaderBundle createSyntheticShaderBundle() {
        PassShaders p0 = new PassShaders(
                RayPass.DIFFUSE,
                createSyntheticSpirv(),
                createSyntheticSpirv(),
                createSyntheticSpirv(),
                createSyntheticSpirv()
        );
        PassShaders p1 = new PassShaders(
                RayPass.REFLECTION,
                createSyntheticSpirv(),
                createSyntheticSpirv(),
                createSyntheticSpirv(),
                createSyntheticSpirv()
        );
        PassShaders p2 = new PassShaders(
                RayPass.REFRACTION,
                createSyntheticSpirv(),
                createSyntheticSpirv(),
                createSyntheticSpirv(),
                createSyntheticSpirv()
        );
        return new RtShaderBundle(p0, p1, p2);
    }

    public static ByteBuffer loadPrecompiledSpirv(String resourcePath) throws IOException {
        Objects.requireNonNull(resourcePath, "resourcePath must not be null");
        InputStream stream = RtPipeline.class.getResourceAsStream(resourcePath);
        if (stream == null) {
            String alt = resourcePath.startsWith("/") ? resourcePath.substring(1) : "/" + resourcePath;
            stream = RtPipeline.class.getResourceAsStream(alt);
        }
        if (stream == null) {
            return null;
        }
        try (InputStream in = stream) {
            byte[] bytes = in.readAllBytes();
            ByteBuffer buffer = ByteBuffer.allocateDirect(bytes.length).order(ByteOrder.nativeOrder());
            buffer.put(bytes);
            buffer.flip();
            return buffer;
        }
    }

    /**
     * Resolves and returns candidates for shader filenames.
     */
    public static List<String> candidateShaderNames(RayPass pass, ShaderStageType type) {
        String p = pass.shaderPrefix();
        String ext = type.extension();
        return List.of(
                p + ext + ".spv",
                p + "_0" + ext + ".spv",
                p + ext,
                p + "_0" + ext
        );
    }

    public static RtShaderBundle loadShaderBundleFromMap(Map<String, ByteBuffer> bytecodeMap) {
        Objects.requireNonNull(bytecodeMap, "bytecodeMap must not be null");
        Map<RayPass, PassShaders> passes = new LinkedHashMap<>();

        for (RayPass pass : RtPipelineConfig.PASSES) {
            ByteBuffer rgen = resolveFromMap(bytecodeMap, pass, ShaderStageType.RAYGEN);
            ByteBuffer miss = resolveFromMap(bytecodeMap, pass, ShaderStageType.MISS);
            ByteBuffer chit = resolveFromMap(bytecodeMap, pass, ShaderStageType.CLOSEST_HIT);
            ByteBuffer ahit = resolveFromMap(bytecodeMap, pass, ShaderStageType.ANY_HIT);

            if (rgen == null || miss == null || chit == null || ahit == null) {
                throw new IllegalArgumentException("Incomplete shader stages for pass " + pass + ": rgen="
                        + (rgen != null) + ", miss=" + (miss != null) + ", chit=" + (chit != null) + ", ahit=" + (ahit != null));
            }
            passes.put(pass, new PassShaders(pass, ensureDirect(rgen), ensureDirect(miss), ensureDirect(chit), ensureDirect(ahit)));
        }

        return new RtShaderBundle(
                passes.get(RayPass.DIFFUSE),
                passes.get(RayPass.REFLECTION),
                passes.get(RayPass.REFRACTION)
        );
    }

    public static RtShaderBundle loadShaderBundleFromProvider(Function<String, ByteBuffer> provider) {
        Objects.requireNonNull(provider, "provider must not be null");
        Map<String, ByteBuffer> resolved = new LinkedHashMap<>();
        for (RayPass pass : RtPipelineConfig.PASSES) {
            for (ShaderStageType type : ShaderStageType.values()) {
                ByteBuffer buf = null;
                for (String candidate : candidateShaderNames(pass, type)) {
                    buf = provider.apply(candidate);
                    if (buf != null) {
                        resolved.put(candidate, buf);
                        break;
                    }
                }
            }
        }
        return loadShaderBundleFromMap(resolved);
    }

    public static RtShaderBundle loadShaderBundleFromResources(String basePath) throws IOException {
        String base = (basePath == null || basePath.isEmpty()) ? "/shaders/" : (basePath.endsWith("/") ? basePath : basePath + "/");
        Map<String, ByteBuffer> map = new LinkedHashMap<>();
        for (RayPass pass : RtPipelineConfig.PASSES) {
            for (ShaderStageType type : ShaderStageType.values()) {
                for (String name : candidateShaderNames(pass, type)) {
                    ByteBuffer buf = loadPrecompiledSpirv(base + name);
                    if (buf != null) {
                        map.put(name, buf);
                        break;
                    }
                }
            }
        }
        return loadShaderBundleFromMap(map);
    }

    private static ByteBuffer resolveFromMap(Map<String, ByteBuffer> map, RayPass pass, ShaderStageType type) {
        for (String candidate : candidateShaderNames(pass, type)) {
            ByteBuffer buf = map.get(candidate);
            if (buf != null) return buf;
            if (candidate.endsWith(".spv")) {
                buf = map.get(candidate.substring(0, candidate.length() - 4));
                if (buf != null) return buf;
            }
        }
        return null;
    }

    private static ByteBuffer ensureDirect(ByteBuffer buffer) {
        if (buffer.isDirect()) {
            return buffer;
        }
        ByteBuffer direct = ByteBuffer.allocateDirect(buffer.remaining()).order(ByteOrder.nativeOrder());
        int pos = buffer.position();
        direct.put(buffer);
        buffer.position(pos);
        direct.flip();
        return direct;
    }

    // =========================================================================
    // SBT Construction & Dispatch Helpers
    // =========================================================================

    public static ByteBuffer createMockGroupHandles(int groupCount, int handleSize) {
        if (groupCount <= 0 || handleSize <= 0) {
            throw new IllegalArgumentException("groupCount and handleSize must be positive: " + groupCount + ", " + handleSize);
        }
        ByteBuffer handles = ByteBuffer.allocateDirect(groupCount * handleSize).order(ByteOrder.nativeOrder());
        for (int g = 0; g < groupCount; g++) {
            for (int b = 0; b < handleSize; b++) {
                handles.put((byte) ((g + 1) * 16 + (b % 16)));
            }
        }
        handles.flip();
        return handles;
    }

    public static ByteBuffer createMockGroupHandles(int handleSize) {
        return createMockGroupHandles(TOTAL_SHADER_GROUPS, handleSize);
    }

    public static ByteBuffer createMockGroupHandles() {
        return createMockGroupHandles(TOTAL_SHADER_GROUPS, SbtLayout.DEFAULT_HANDLE_SIZE);
    }

    public static MultiPassSbt buildSbt(
            long bufferDeviceAddress,
            SbtLayout layout,
            ByteBuffer shaderGroupHandles,
            boolean stageOrder
    ) {
        return RtPipelineSbtBuilder.createThreePassSbt(bufferDeviceAddress, layout, shaderGroupHandles, stageOrder);
    }

    public static MultiPassSbt buildSbt(
            long bufferDeviceAddress,
            SbtLayout layout,
            ByteBuffer shaderGroupHandles
    ) {
        return buildSbt(bufferDeviceAddress, layout, shaderGroupHandles, false);
    }

    public static MultiPassSbt queryAndBuildSbt(
            VkDevice device,
            long pipelineHandle,
            long bufferDeviceAddress,
            SbtLayout layout,
            boolean stageOrder
    ) {
        Objects.requireNonNull(device, "device must not be null");
        Objects.requireNonNull(layout, "layout must not be null");
        int handleSize = layout.handleSize();
        ByteBuffer handles = ByteBuffer.allocateDirect(TOTAL_SHADER_GROUPS * handleSize).order(ByteOrder.nativeOrder());

        int result = KHRRayTracingPipeline.vkGetRayTracingShaderGroupHandlesKHR(
                device,
                pipelineHandle,
                0,
                TOTAL_SHADER_GROUPS,
                handles
        );
        if (result != VK10.VK_SUCCESS) {
            throw new IllegalStateException("vkGetRayTracingShaderGroupHandlesKHR failed with error code: " + result);
        }
        handles.rewind();
        return buildSbt(bufferDeviceAddress, layout, handles, stageOrder);
    }

    // =========================================================================
    // Trace Rays Recording & Synchronization
    // =========================================================================

    public static void recordTracePass(
            VkCommandBuffer cmd,
            long pipelineHandle,
            long pipelineLayout,
            RayPass pass,
            PassSbt passSbt,
            int width,
            int height,
            MemoryStack stack
    ) {
        recordTracePass(cmd, pipelineHandle, pipelineLayout, pass, passSbt, width, height, stack, true);
    }

    public static void recordTracePass(
            VkCommandBuffer cmd,
            long pipelineHandle,
            long pipelineLayout,
            RayPass pass,
            PassSbt passSbt,
            int width,
            int height,
            MemoryStack stack,
            boolean pushFirstLobe
    ) {
        Objects.requireNonNull(cmd, "cmd must not be null");
        Objects.requireNonNull(pass, "pass must not be null");
        Objects.requireNonNull(passSbt, "passSbt must not be null");
        Objects.requireNonNull(stack, "stack must not be null");
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Trace dimensions must be positive: " + width + "x" + height);
        }

        // 1. Bind pipeline
        VK10.vkCmdBindPipeline(
                cmd,
                KHRRayTracingPipeline.VK_PIPELINE_BIND_POINT_RAY_TRACING_KHR,
                pipelineHandle
        );

        // 2. Push constants (legacy layout only). The pack selects the lobe at compile time (FIRST_LOBE_VAL) and its
        //    stages declare no push-constant block, so a reflected layout has no range and nothing is pushed.
        if (pushFirstLobe) {
            ByteBuffer pc = stack.malloc(PUSH_CONSTANT_BYTES);
            pc.putInt(0, pass.firstLobeVal());
            VK10.vkCmdPushConstants(cmd, pipelineLayout, PUSH_CONSTANT_STAGES, 0, pc);
        }

        // 3. Allocate native VkStridedDeviceAddressRegionKHR structs
        VkRegions regions = passSbt.allocateVkRegions(stack);

        // 4. Trace rays
        KHRRayTracingPipeline.vkCmdTraceRaysKHR(
                cmd,
                regions.raygen(),
                regions.miss(),
                regions.hit(),
                regions.callable(),
                width,
                height,
                1
        );
    }

    public static void recordTracePass(
            VkCommandBuffer cmd,
            long pipelineHandle,
            long pipelineLayout,
            RayPass pass,
            PassSbt passSbt,
            int width,
            int height
    ) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            recordTracePass(cmd, pipelineHandle, pipelineLayout, pass, passSbt, width, height, stack);
        }
    }

    public static void recordRayPassBarrier(VkCommandBuffer cmd, MemoryStack stack) {
        Objects.requireNonNull(cmd, "cmd must not be null");
        Objects.requireNonNull(stack, "stack must not be null");

        VkMemoryBarrier.Buffer barrier = VkMemoryBarrier.calloc(1, stack);
        barrier.get(0).sType$Default()
                .srcAccessMask(VK10.VK_ACCESS_SHADER_WRITE_BIT)
                .dstAccessMask(VK10.VK_ACCESS_SHADER_READ_BIT | VK10.VK_ACCESS_SHADER_WRITE_BIT);

        VK10.vkCmdPipelineBarrier(
                cmd,
                KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR,
                KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR,
                0,
                barrier,
                null,
                null
        );
    }

    public static void recordTraceAllPasses(
            VkCommandBuffer cmd,
            long pipelineHandle,
            long pipelineLayout,
            MultiPassSbt multiPassSbt,
            int width,
            int height
    ) {
        recordTraceAllPasses(cmd, pipelineHandle, pipelineLayout, multiPassSbt, width, height, true);
    }

    public static void recordTraceAllPasses(
            VkCommandBuffer cmd,
            long pipelineHandle,
            long pipelineLayout,
            MultiPassSbt multiPassSbt,
            int width,
            int height,
            boolean pushFirstLobe
    ) {
        Objects.requireNonNull(cmd, "cmd must not be null");
        Objects.requireNonNull(multiPassSbt, "multiPassSbt must not be null");

        try (MemoryStack stack = MemoryStack.stackPush()) {
            for (int i = 0; i < RtPipelineConfig.PASSES.size(); i++) {
                RayPass pass = RtPipelineConfig.PASSES.get(i);
                PassSbt passSbt = multiPassSbt.pass(pass);
                recordTracePass(cmd, pipelineHandle, pipelineLayout, pass, passSbt, width, height, stack, pushFirstLobe);

                if (i < RtPipelineConfig.PASSES.size() - 1) {
                    recordRayPassBarrier(cmd, stack);
                }
            }
        }
    }

    // =========================================================================
    // Instance State & Lifecycle
    // =========================================================================

    private final VkDevice device;
    private final boolean stageOrder;
    private long pipelineHandle = 0L;
    private long pipelineLayoutHandle = 0L;
    private final long[] setLayoutHandles = new long[RtPipelineConfig.TOTAL_DESCRIPTOR_SETS];
    private final long[] shaderModuleHandles = new long[TOTAL_STAGES];
    private boolean initialized = false;
    /** Legacy hand-written layout carries a 16-byte push range; a reflected layout has exactly what the SPIR-V declares. */
    private boolean pushFirstLobe = true;

    public RtPipeline(VkDevice device, boolean stageOrder) {
        this.device = device;
        this.stageOrder = stageOrder;
    }

    public RtPipeline(VkDevice device) {
        this(device, false);
    }

    public RtPipeline() {
        this(null, false);
    }

    public boolean isInitialized() {
        return initialized;
    }

    public long pipeline() {
        return pipelineHandle;
    }

    public long pipelineLayout() {
        return pipelineLayoutHandle;
    }

    public long descriptorSetLayout(int setIndex) {
        return setLayoutHandles[setIndex];
    }

    public long[] descriptorSetLayouts() {
        return setLayoutHandles.clone();
    }

    public long[] shaderModules() {
        return shaderModuleHandles.clone();
    }

    public boolean stageOrder() {
        return stageOrder;
    }

    /**
     * Initializes the ray tracing pipeline on the live Vulkan device.
     */
    public void initialize(
            RtShaderBundle shaders,
            int maxGeometryBuffers,
            int maxRecursionDepth,
            long pipelineCache
    ) {
        Objects.requireNonNull(device, "Cannot initialize on device: VkDevice was null");
        Objects.requireNonNull(shaders, "shaders must not be null");
        if (initialized) {
            return;
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer p = stack.mallocLong(1);

            // 1. Create Descriptor Set Layouts (Sets 0..3)
            for (int s = 0; s < RtPipelineConfig.TOTAL_DESCRIPTOR_SETS; s++) {
                VkDescriptorSetLayoutCreateInfo setInfo = createDescriptorSetLayoutInfo(s, stack, maxGeometryBuffers);
                int err = VK10.vkCreateDescriptorSetLayout(device, setInfo, null, p);
                if (err != VK10.VK_SUCCESS) {
                    throw new IllegalStateException("Failed to create descriptor set layout " + s + ": error " + err);
                }
                setLayoutHandles[s] = p.get(0);
            }

            // 2. Create Pipeline Layout
            LongBuffer setLayoutsBuf = stack.longs(setLayoutHandles);
            VkPipelineLayoutCreateInfo plInfo = createPipelineLayoutInfo(setLayoutsBuf, stack);
            int err = VK10.vkCreatePipelineLayout(device, plInfo, null, p);
            if (err != VK10.VK_SUCCESS) {
                throw new IllegalStateException("Failed to create pipeline layout: error " + err);
            }
            pipelineLayoutHandle = p.get(0);

            // 3. Create Shader Modules (12 stages)
            LongBuffer modulesBuf = stack.mallocLong(TOTAL_STAGES);
            for (int i = 0; i < TOTAL_STAGES; i++) {
                ByteBuffer spirv = shaders.getShader(i);
                VkShaderModuleCreateInfo moduleInfo = VkShaderModuleCreateInfo.calloc(stack)
                        .sType$Default()
                        .pCode(spirv);
                err = VK10.vkCreateShaderModule(device, moduleInfo, null, p);
                if (err != VK10.VK_SUCCESS) {
                    throw new IllegalStateException("Failed to create shader module for stage " + i + ": error " + err);
                }
                shaderModuleHandles[i] = p.get(0);
                modulesBuf.put(i, p.get(0));
            }

            // 4. Create Ray Tracing Pipeline
            VkPipelineShaderStageCreateInfo.Buffer pStages = createPipelineShaderStages(stack, modulesBuf);
            VkRayTracingShaderGroupCreateInfoKHR.Buffer pGroups = createPipelineShaderGroups(stack, stageOrder);
            VkRayTracingPipelineCreateInfoKHR.Buffer pipeInfos = VkRayTracingPipelineCreateInfoKHR.calloc(1, stack);
            VkRayTracingPipelineCreateInfoKHR pipeInfo = pipeInfos.get(0);
            pipeInfo.sType$Default()
                    .pStages(pStages)
                    .pGroups(pGroups)
                    .maxPipelineRayRecursionDepth(maxRecursionDepth > 0 ? maxRecursionDepth : DEFAULT_MAX_RECURSION_DEPTH)
                    .layout(pipelineLayoutHandle);

            err = RtVulkanPipelineCache.createRayTracing(
                    device,
                    VK10.VK_NULL_HANDLE,
                    pipelineCache,
                    pipeInfos,
                    null,
                    p
            );
            if (err != VK10.VK_SUCCESS) {
                throw new IllegalStateException("vkCreateRayTracingPipelinesKHR failed: error " + err);
            }
            pipelineHandle = p.get(0);
            initialized = true;

            LOGGER.info("Ray-tracing pipeline created successfully: handle=0x{}", Long.toHexString(pipelineHandle));
        }
    }

    /**
     * Initializes the pipeline from the layout the compiled pack stages actually declare (see {@link RtPipelinePackProgram}),
     * instead of the hand-written {@link RtPipelineLayouts#describeDescriptorSets} guess. Sets without bindings get an empty layout so set
     * numbers stay what the shaders use. An unbounded array is created with {@code runtimeArrayCapacity} descriptors,
     * partially bound and with a variable count; it must be the highest binding of its set.
     */
    public void initializeReflected(
            RtShaderBundle shaders,
            List<RtPipelinePackProgram.BindingStatus> bindings,
            int runtimeArrayCapacity,
            long pipelineCache
    ) {
        Objects.requireNonNull(device, "Cannot initialize on device: VkDevice was null");
        Objects.requireNonNull(shaders, "shaders must not be null");
        Objects.requireNonNull(bindings, "bindings must not be null");
        if (initialized) {
            return;
        }
        int maxSet = -1;
        for (RtPipelinePackProgram.BindingStatus b : bindings) maxSet = Math.max(maxSet, b.binding().set());
        if (maxSet >= RtPipelineConfig.TOTAL_DESCRIPTOR_SETS) {
            throw new IllegalArgumentException("pack uses descriptor set " + maxSet + "; this host supports sets 0.."
                + (RtPipelineConfig.TOTAL_DESCRIPTOR_SETS - 1));
        }
        pushFirstLobe = false;
        boolean ok = false;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer p = stack.mallocLong(1);
            int setCount = Math.max(1, maxSet + 1);
            for (int s = 0; s < setCount; s++) {
                VkDescriptorSetLayoutCreateInfo info = createReflectedDescriptorSetLayoutInfo(s, bindings, runtimeArrayCapacity, stack);
                check(VK10.vkCreateDescriptorSetLayout(device, info, null, p), "descriptor set layout " + s);
                setLayoutHandles[s] = p.get(0);
            }
            LongBuffer layouts = stack.mallocLong(setCount);
            for (int s = 0; s < setCount; s++) layouts.put(s, setLayoutHandles[s]);
            VkPipelineLayoutCreateInfo plInfo = VkPipelineLayoutCreateInfo.calloc(stack).sType$Default().pSetLayouts(layouts);
            check(VK10.vkCreatePipelineLayout(device, plInfo, null, p), "pipeline layout");
            pipelineLayoutHandle = p.get(0);

            LongBuffer modulesBuf = stack.mallocLong(TOTAL_STAGES);
            for (int i = 0; i < TOTAL_STAGES; i++) {
                VkShaderModuleCreateInfo moduleInfo = VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(shaders.getShader(i));
                check(VK10.vkCreateShaderModule(device, moduleInfo, null, p), "shader module " + i);
                shaderModuleHandles[i] = p.get(0);
                modulesBuf.put(i, p.get(0));
            }
            VkRayTracingPipelineCreateInfoKHR.Buffer pipeInfos = VkRayTracingPipelineCreateInfoKHR.calloc(1, stack);
            pipeInfos.get(0).sType$Default()
                .pStages(createPipelineShaderStages(stack, modulesBuf))
                .pGroups(createPipelineShaderGroups(stack, stageOrder))
                .maxPipelineRayRecursionDepth(DEFAULT_MAX_RECURSION_DEPTH)
                .layout(pipelineLayoutHandle);
            check(KHRRayTracingPipeline.vkCreateRayTracingPipelinesKHR(device, VK10.VK_NULL_HANDLE, pipelineCache, pipeInfos, null, p),
                "vkCreateRayTracingPipelinesKHR");
            pipelineHandle = p.get(0);
            initialized = true;
            ok = true;
            LOGGER.info("Ray-tracing pipeline created from reflected layout: handle=0x{} sets={}",
                Long.toHexString(pipelineHandle), setCount);
        } finally {
            if (!ok) {
                initialized = true; // let close() release whatever was created before the failure
                close();
            }
        }
    }

    private static void check(int result, String what) {
        if (result != VK10.VK_SUCCESS) throw new IllegalStateException(what + " failed: VkResult " + result);
    }

    public void recordTracePass(
            VkCommandBuffer cmd,
            RayPass pass,
            PassSbt passSbt,
            int width,
            int height
    ) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            recordTracePass(cmd, pipelineHandle, pipelineLayoutHandle, pass, passSbt, width, height, stack, pushFirstLobe);
        }
    }

    public void recordTraceAllPasses(
            VkCommandBuffer cmd,
            MultiPassSbt multiPassSbt,
            int width,
            int height
    ) {
        recordTraceAllPasses(cmd, pipelineHandle, pipelineLayoutHandle, multiPassSbt, width, height, pushFirstLobe);
    }

    public MultiPassSbt queryAndBuildSbt(
            long bufferDeviceAddress,
            SbtLayout layout
    ) {
        return queryAndBuildSbt(device, pipelineHandle, bufferDeviceAddress, layout, stageOrder);
    }

    @Override
    public void close() {
        if (device != null && initialized) {
            VK10.vkDeviceWaitIdle(device);

            if (pipelineHandle != 0L) {
                VK10.vkDestroyPipeline(device, pipelineHandle, null);
                pipelineHandle = 0L;
            }
            for (int i = 0; i < TOTAL_STAGES; i++) {
                if (shaderModuleHandles[i] != 0L) {
                    VK10.vkDestroyShaderModule(device, shaderModuleHandles[i], null);
                    shaderModuleHandles[i] = 0L;
                }
            }
            if (pipelineLayoutHandle != 0L) {
                VK10.vkDestroyPipelineLayout(device, pipelineLayoutHandle, null);
                pipelineLayoutHandle = 0L;
            }
            for (int s = 0; s < RtPipelineConfig.TOTAL_DESCRIPTOR_SETS; s++) {
                if (setLayoutHandles[s] != 0L) {
                    VK10.vkDestroyDescriptorSetLayout(device, setLayoutHandles[s], null);
                    setLayoutHandles[s] = 0L;
                }
            }
            initialized = false;
        }
    }
}
