package io.github.andrexarlay.vulkancompat.shaderhost.rtpipeline;

import io.github.andrexarlay.vulkancompat.shaderhost.rtpipeline.RtPipelineConfig.BindingInfo;
import io.github.andrexarlay.vulkancompat.shaderhost.rtpipeline.RtPipelineConfig.DescriptorType;
import io.github.andrexarlay.vulkancompat.shaderhost.rtpipeline.RtPipelineConfig.RayPass;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRAccelerationStructure;
import org.lwjgl.vulkan.KHRRayTracingPipeline;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo;
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo;
import org.lwjgl.vulkan.VkPipelineShaderStageCreateInfo;
import org.lwjgl.vulkan.VkPushConstantRange;
import org.lwjgl.vulkan.VkRayTracingPipelineCreateInfoKHR;
import org.lwjgl.vulkan.VkRayTracingShaderGroupCreateInfoKHR;

import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Vulkanite RT-pipeline pack shader ordering and descriptor ABI; native create-info storage belongs to the caller's stack. */
public final class RtPipelineLayouts {

    private RtPipelineLayouts() {}

    public static final int TOTAL_PASSES = 3;
    public static final int STAGES_PER_PASS = 4;
    public static final int TOTAL_STAGES = TOTAL_PASSES * STAGES_PER_PASS; // 12
    public static final int GROUPS_PER_PASS = 3;
    public static final int TOTAL_SHADER_GROUPS = TOTAL_PASSES * GROUPS_PER_PASS; // 9

    public static final int DEFAULT_MAX_GEOMETRY_BUFFERS = 1024;
    public static final int DEFAULT_MAX_RECURSION_DEPTH = 1;
    public static final int PUSH_CONSTANT_BYTES = 16;

    public static final int RT_ALL_STAGES =
            KHRRayTracingPipeline.VK_SHADER_STAGE_RAYGEN_BIT_KHR
            | KHRRayTracingPipeline.VK_SHADER_STAGE_MISS_BIT_KHR
            | KHRRayTracingPipeline.VK_SHADER_STAGE_CLOSEST_HIT_BIT_KHR
            | KHRRayTracingPipeline.VK_SHADER_STAGE_ANY_HIT_BIT_KHR;

    public static final int PUSH_CONSTANT_STAGES =
            KHRRayTracingPipeline.VK_SHADER_STAGE_RAYGEN_BIT_KHR
            | KHRRayTracingPipeline.VK_SHADER_STAGE_CLOSEST_HIT_BIT_KHR;

    public enum ShaderStageType {
        RAYGEN(KHRRayTracingPipeline.VK_SHADER_STAGE_RAYGEN_BIT_KHR, ".rgen"),
        MISS(KHRRayTracingPipeline.VK_SHADER_STAGE_MISS_BIT_KHR, ".rmiss"),
        CLOSEST_HIT(KHRRayTracingPipeline.VK_SHADER_STAGE_CLOSEST_HIT_BIT_KHR, ".rchit"),
        ANY_HIT(KHRRayTracingPipeline.VK_SHADER_STAGE_ANY_HIT_BIT_KHR, ".rahit");

        private final int vkStageBit;
        private final String extension;

        ShaderStageType(int vkStageBit, String extension) {
            this.vkStageBit = vkStageBit;
            this.extension = extension;
        }

        public int vkStageBit() {
            return vkStageBit;
        }

        public String extension() {
            return extension;
        }
    }

    public record StageDesc(
            int stageIndex,
            RayPass pass,
            ShaderStageType stageType,
            String canonicalName,
            int vkStageBit
    ) {
        public StageDesc {
            Objects.requireNonNull(pass, "pass must not be null");
            Objects.requireNonNull(stageType, "stageType must not be null");
            Objects.requireNonNull(canonicalName, "canonicalName must not be null");
        }
    }

    public enum ShaderGroupType {
        GENERAL(KHRRayTracingPipeline.VK_RAY_TRACING_SHADER_GROUP_TYPE_GENERAL_KHR),
        TRIANGLES_HIT(KHRRayTracingPipeline.VK_RAY_TRACING_SHADER_GROUP_TYPE_TRIANGLES_HIT_GROUP_KHR);

        private final int vkType;

        ShaderGroupType(int vkType) {
            this.vkType = vkType;
        }

        public int vkType() {
            return vkType;
        }
    }

    public record ShaderGroupDesc(
            int groupIndex,
            RayPass pass,
            ShaderGroupType groupType,
            int generalShaderIndex,
            int closestHitShaderIndex,
            int anyHitShaderIndex,
            int intersectionShaderIndex
    ) {
        public static ShaderGroupDesc general(int groupIndex, RayPass pass, int generalShaderIndex) {
            return new ShaderGroupDesc(
                    groupIndex,
                    pass,
                    ShaderGroupType.GENERAL,
                    generalShaderIndex,
                    KHRRayTracingPipeline.VK_SHADER_UNUSED_KHR,
                    KHRRayTracingPipeline.VK_SHADER_UNUSED_KHR,
                    KHRRayTracingPipeline.VK_SHADER_UNUSED_KHR
            );
        }

        public static ShaderGroupDesc trianglesHit(int groupIndex, RayPass pass, int closestHitIndex, int anyHitIndex) {
            return new ShaderGroupDesc(
                    groupIndex,
                    pass,
                    ShaderGroupType.TRIANGLES_HIT,
                    KHRRayTracingPipeline.VK_SHADER_UNUSED_KHR,
                    closestHitIndex,
                    anyHitIndex,
                    KHRRayTracingPipeline.VK_SHADER_UNUSED_KHR
            );
        }

        public void fillVk(VkRayTracingShaderGroupCreateInfoKHR struct) {
            Objects.requireNonNull(struct, "struct must not be null");
            struct.sType$Default();
            struct.type(groupType.vkType());
            struct.generalShader(generalShaderIndex);
            struct.closestHitShader(closestHitShaderIndex);
            struct.anyHitShader(anyHitShaderIndex);
            struct.intersectionShader(intersectionShaderIndex);
        }
    }

    /**
     * Builds list of all 12 canonical shader stage descriptors in sequential order.
     */
    public static List<StageDesc> buildStageDescriptors() {
        List<StageDesc> stages = new ArrayList<>(TOTAL_STAGES);
        int index = 0;
        for (RayPass pass : RtPipelineConfig.PASSES) {
            for (ShaderStageType type : ShaderStageType.values()) {
                String canonicalName = pass.shaderPrefix() + type.extension() + ".spv";
                stages.add(new StageDesc(index++, pass, type, canonicalName, type.vkStageBit()));
            }
        }
        return Collections.unmodifiableList(stages);
    }

    /**
     * Builds list of 9 shader group descriptors.
     *
     * @param stageOrder If true, groups are arranged by stage type [rgen0..2, miss0..2, hit0..2];
     *                   if false (default), groups are arranged by pass [pass0(rgen,miss,hit), pass1, pass2].
     */
    public static List<ShaderGroupDesc> buildGroupDescriptors(boolean stageOrder) {
        List<ShaderGroupDesc> groups = new ArrayList<>(TOTAL_SHADER_GROUPS);
        if (!stageOrder) {
            // Pass order (matches RtPipelineSbtBuilder default)
            for (int p = 0; p < TOTAL_PASSES; p++) {
                RayPass pass = RtPipelineConfig.PASSES.get(p);
                int baseStage = p * STAGES_PER_PASS;
                int rgenStage = baseStage;     // stage 0
                int missStage = baseStage + 1; // stage 1
                int chitStage = baseStage + 2; // stage 2
                int ahitStage = baseStage + 3; // stage 3

                groups.add(ShaderGroupDesc.general(p * GROUPS_PER_PASS, pass, rgenStage));
                groups.add(ShaderGroupDesc.general(p * GROUPS_PER_PASS + 1, pass, missStage));
                groups.add(ShaderGroupDesc.trianglesHit(p * GROUPS_PER_PASS + 2, pass, chitStage, ahitStage));
            }
        } else {
            // Stage order
            for (int p = 0; p < TOTAL_PASSES; p++) {
                RayPass pass = RtPipelineConfig.PASSES.get(p);
                groups.add(ShaderGroupDesc.general(p, pass, p * STAGES_PER_PASS));
            }
            for (int p = 0; p < TOTAL_PASSES; p++) {
                RayPass pass = RtPipelineConfig.PASSES.get(p);
                groups.add(ShaderGroupDesc.general(3 + p, pass, p * STAGES_PER_PASS + 1));
            }
            for (int p = 0; p < TOTAL_PASSES; p++) {
                RayPass pass = RtPipelineConfig.PASSES.get(p);
                groups.add(ShaderGroupDesc.trianglesHit(6 + p, pass, p * STAGES_PER_PASS + 2, p * STAGES_PER_PASS + 3));
            }
        }
        return Collections.unmodifiableList(groups);
    }

    public record DescriptorBindingDesc(
            int set,
            int binding,
            String name,
            DescriptorType type,
            int descriptorCount,
            int stageFlags
    ) {
        public void fillVk(VkDescriptorSetLayoutBinding struct) {
            Objects.requireNonNull(struct, "struct must not be null");
            struct.binding(binding);
            struct.descriptorType(toVkDescriptorType(type));
            struct.descriptorCount(descriptorCount);
            struct.stageFlags(stageFlags);
        }
    }

    public record DescriptorSetDesc(
            int setIndex,
            String name,
            List<DescriptorBindingDesc> bindings
    ) {}

    public static int toVkDescriptorType(DescriptorType type) {
        Objects.requireNonNull(type, "type must not be null");
        return switch (type) {
            case UNIFORM_BUFFER -> VK10.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER;
            case ACCELERATION_STRUCTURE_KHR -> KHRAccelerationStructure.VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR;
            case COMBINED_IMAGE_SAMPLER -> VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
            case STORAGE_IMAGE -> VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
            case STORAGE_BUFFER -> VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
        };
    }

    public static int toVkDescriptorType(io.github.andrexarlay.vulkancompat.shaderhost.glsl.SpirvReflection.DescriptorKind kind) {
        return switch (kind) {
            case UNIFORM_BUFFER -> VK10.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER;
            case STORAGE_BUFFER -> VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
            case COMBINED_IMAGE_SAMPLER -> VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
            case SAMPLED_IMAGE -> VK10.VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE;
            case STORAGE_IMAGE -> VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
            case SAMPLER -> VK10.VK_DESCRIPTOR_TYPE_SAMPLER;
            case UNIFORM_TEXEL_BUFFER -> VK10.VK_DESCRIPTOR_TYPE_UNIFORM_TEXEL_BUFFER;
            case STORAGE_TEXEL_BUFFER -> VK10.VK_DESCRIPTOR_TYPE_STORAGE_TEXEL_BUFFER;
            case INPUT_ATTACHMENT -> VK10.VK_DESCRIPTOR_TYPE_INPUT_ATTACHMENT;
            case ACCELERATION_STRUCTURE -> KHRAccelerationStructure.VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR;
        };
    }

    /**
     * Returns full descriptor set descriptions for Sets 0, 1, 2, and 3.
     */
    public static List<DescriptorSetDesc> describeDescriptorSets(int maxGeometryBuffers) {
        List<DescriptorSetDesc> sets = new ArrayList<>(4);

        // Set 0: Frame uniforms (CameraInfo UBO 256B, TLAS, samplers, RayTraceData image)
        List<DescriptorBindingDesc> set0 = new ArrayList<>();
        for (BindingInfo b : RtPipelineConfig.SET0_BINDINGS) {
            int stageFlags = switch (b.type()) {
                case UNIFORM_BUFFER, ACCELERATION_STRUCTURE_KHR ->
                        KHRRayTracingPipeline.VK_SHADER_STAGE_RAYGEN_BIT_KHR | KHRRayTracingPipeline.VK_SHADER_STAGE_CLOSEST_HIT_BIT_KHR;
                case COMBINED_IMAGE_SAMPLER ->
                        KHRRayTracingPipeline.VK_SHADER_STAGE_CLOSEST_HIT_BIT_KHR | KHRRayTracingPipeline.VK_SHADER_STAGE_ANY_HIT_BIT_KHR;
                case STORAGE_IMAGE ->
                        KHRRayTracingPipeline.VK_SHADER_STAGE_RAYGEN_BIT_KHR;
                default -> RT_ALL_STAGES;
            };
            set0.add(new DescriptorBindingDesc(b.set(), b.binding(), b.name(), b.type(), b.count(), stageFlags));
        }
        sets.add(new DescriptorSetDesc(0, "FrameUniforms", Collections.unmodifiableList(set0)));

        // Set 1: Geometry quad SSBO array
        List<DescriptorBindingDesc> set1 = new ArrayList<>();
        int geomCount = maxGeometryBuffers > 0 ? maxGeometryBuffers : DEFAULT_MAX_GEOMETRY_BUFFERS;
        set1.add(new DescriptorBindingDesc(
                1,
                RtPipelineConfig.SET1_GEOMETRY_BUFFERS.binding(),
                RtPipelineConfig.SET1_GEOMETRY_BUFFERS.name(),
                RtPipelineConfig.SET1_GEOMETRY_BUFFERS.type(),
                geomCount,
                KHRRayTracingPipeline.VK_SHADER_STAGE_CLOSEST_HIT_BIT_KHR | KHRRayTracingPipeline.VK_SHADER_STAGE_ANY_HIT_BIT_KHR
        ));
        sets.add(new DescriptorSetDesc(1, "GeometryBuffers", Collections.unmodifiableList(set1)));

        // Set 2: Texture atlases
        List<DescriptorBindingDesc> set2 = new ArrayList<>();
        for (BindingInfo b : RtPipelineConfig.SET2_BINDINGS) {
            set2.add(new DescriptorBindingDesc(
                    b.set(),
                    b.binding(),
                    b.name(),
                    b.type(),
                    b.count(),
                    KHRRayTracingPipeline.VK_SHADER_STAGE_CLOSEST_HIT_BIT_KHR | KHRRayTracingPipeline.VK_SHADER_STAGE_ANY_HIT_BIT_KHR
            ));
        }
        sets.add(new DescriptorSetDesc(2, "TextureAtlases", Collections.unmodifiableList(set2)));

        // Set 3: 5 output SSBO buffers
        List<DescriptorBindingDesc> set3 = new ArrayList<>();
        for (BindingInfo b : RtPipelineConfig.SET3_BINDINGS) {
            int stageFlags = switch (b.binding()) {
                case 0 -> KHRRayTracingPipeline.VK_SHADER_STAGE_RAYGEN_BIT_KHR | KHRRayTracingPipeline.VK_SHADER_STAGE_CLOSEST_HIT_BIT_KHR;
                case 1 -> RT_ALL_STAGES;
                default -> KHRRayTracingPipeline.VK_SHADER_STAGE_RAYGEN_BIT_KHR;
            };
            set3.add(new DescriptorBindingDesc(b.set(), b.binding(), b.name(), b.type(), b.count(), stageFlags));
        }
        sets.add(new DescriptorSetDesc(3, "OutputBuffers", Collections.unmodifiableList(set3)));

        return Collections.unmodifiableList(sets);
    }

    public static VkDescriptorSetLayoutCreateInfo createDescriptorSetLayoutInfo(
            int setIndex,
            MemoryStack stack,
            int maxGeometryBuffers
    ) {
        Objects.requireNonNull(stack, "stack must not be null");
        List<DescriptorSetDesc> sets = describeDescriptorSets(maxGeometryBuffers);
        if (setIndex < 0 || setIndex >= sets.size()) {
            throw new IndexOutOfBoundsException("Invalid setIndex: " + setIndex + " (0.." + (sets.size() - 1) + ")");
        }
        DescriptorSetDesc desc = sets.get(setIndex);
        VkDescriptorSetLayoutBinding.Buffer bindings = VkDescriptorSetLayoutBinding.calloc(desc.bindings().size(), stack);
        for (int i = 0; i < desc.bindings().size(); i++) {
            desc.bindings().get(i).fillVk(bindings.get(i));
        }
        return VkDescriptorSetLayoutCreateInfo.calloc(stack)
                .sType$Default()
                .pBindings(bindings);
    }

    static VkDescriptorSetLayoutCreateInfo createReflectedDescriptorSetLayoutInfo(
            int setIndex,
            List<RtPipelinePackProgram.BindingStatus> bindings,
            int runtimeArrayCapacity,
            MemoryStack stack
    ) {
        List<RtPipelinePackProgram.BindingStatus> inSet = new ArrayList<>();
        for (RtPipelinePackProgram.BindingStatus b : bindings) if (b.binding().set() == setIndex) inSet.add(b);
        int highest = inSet.stream().mapToInt(b -> b.binding().binding()).max().orElse(-1);
        VkDescriptorSetLayoutBinding.Buffer vk = VkDescriptorSetLayoutBinding.calloc(inSet.size(), stack);
        java.nio.IntBuffer flags = stack.mallocInt(Math.max(1, inSet.size()));
        boolean anyFlags = false;
        for (int i = 0; i < inSet.size(); i++) {
            var b = inSet.get(i).binding();
            int count = b.runtimeArray() ? runtimeArrayCapacity : b.count();
            vk.get(i).binding(b.binding()).descriptorType(toVkDescriptorType(b.kind()))
                .descriptorCount(count).stageFlags(inSet.get(i).stageFlags());
            int f = 0;
            if (b.runtimeArray()) {
                if (b.binding() != highest) throw new IllegalArgumentException("unbounded array at set " + setIndex
                    + " binding " + b.binding() + " is not the highest binding of its set");
                f = org.lwjgl.vulkan.VK12.VK_DESCRIPTOR_BINDING_PARTIALLY_BOUND_BIT
                    | org.lwjgl.vulkan.VK12.VK_DESCRIPTOR_BINDING_VARIABLE_DESCRIPTOR_COUNT_BIT;
                anyFlags = true;
            }
            flags.put(i, f);
        }
        VkDescriptorSetLayoutCreateInfo info = VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default().pBindings(vk);
        if (anyFlags) {
            flags.limit(inSet.size());
            var bindingFlags = org.lwjgl.vulkan.VkDescriptorSetLayoutBindingFlagsCreateInfo.calloc(stack)
                .sType$Default().pBindingFlags(flags);
            info.pNext(bindingFlags.address());
        }
        return info;
    }

    public static VkPipelineLayoutCreateInfo createPipelineLayoutInfo(
            LongBuffer setLayouts,
            MemoryStack stack
    ) {
        Objects.requireNonNull(setLayouts, "setLayouts must not be null");
        Objects.requireNonNull(stack, "stack must not be null");

        VkPushConstantRange.Buffer push = VkPushConstantRange.calloc(1, stack);
        push.get(0)
                .stageFlags(PUSH_CONSTANT_STAGES)
                .offset(0)
                .size(PUSH_CONSTANT_BYTES);

        return VkPipelineLayoutCreateInfo.calloc(stack)
                .sType$Default()
                .pSetLayouts(setLayouts)
                .pPushConstantRanges(push);
    }

    public static VkPipelineShaderStageCreateInfo.Buffer createPipelineShaderStages(
            MemoryStack stack,
            LongBuffer shaderModules
    ) {
        Objects.requireNonNull(stack, "stack must not be null");
        Objects.requireNonNull(shaderModules, "shaderModules must not be null");
        if (shaderModules.remaining() < TOTAL_STAGES) {
            throw new IllegalArgumentException("shaderModules must contain at least " + TOTAL_STAGES + " modules, had: " + shaderModules.remaining());
        }

        List<StageDesc> stages = buildStageDescriptors();
        VkPipelineShaderStageCreateInfo.Buffer pStages = VkPipelineShaderStageCreateInfo.calloc(TOTAL_STAGES, stack);

        for (int i = 0; i < TOTAL_STAGES; i++) {
            StageDesc stage = stages.get(i);
            long module = shaderModules.get(shaderModules.position() + i);
            pStages.get(i)
                    .sType$Default()
                    .stage(stage.vkStageBit())
                    .module(module)
                    .pName(stack.UTF8("main"));
        }

        return pStages;
    }

    public static VkRayTracingShaderGroupCreateInfoKHR.Buffer createPipelineShaderGroups(
            MemoryStack stack,
            boolean stageOrder
    ) {
        Objects.requireNonNull(stack, "stack must not be null");
        List<ShaderGroupDesc> groups = buildGroupDescriptors(stageOrder);
        VkRayTracingShaderGroupCreateInfoKHR.Buffer pGroups = VkRayTracingShaderGroupCreateInfoKHR.calloc(TOTAL_SHADER_GROUPS, stack);
        for (int i = 0; i < TOTAL_SHADER_GROUPS; i++) {
            groups.get(i).fillVk(pGroups.get(i));
        }
        return pGroups;
    }

    public static VkRayTracingPipelineCreateInfoKHR createRayTracingPipelineInfo(
            MemoryStack stack,
            long pipelineLayout,
            VkPipelineShaderStageCreateInfo.Buffer stages,
            VkRayTracingShaderGroupCreateInfoKHR.Buffer groups,
            int maxRecursionDepth
    ) {
        Objects.requireNonNull(stack, "stack must not be null");
        Objects.requireNonNull(stages, "stages must not be null");
        Objects.requireNonNull(groups, "groups must not be null");

        int recursion = maxRecursionDepth > 0 ? maxRecursionDepth : DEFAULT_MAX_RECURSION_DEPTH;

        return VkRayTracingPipelineCreateInfoKHR.calloc(stack)
                .sType$Default()
                .pStages(stages)
                .pGroups(groups)
                .maxPipelineRayRecursionDepth(recursion)
                .layout(pipelineLayout);
    }
}
