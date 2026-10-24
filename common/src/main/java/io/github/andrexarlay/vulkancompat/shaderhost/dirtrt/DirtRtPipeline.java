package io.github.andrexarlay.vulkancompat.shaderhost.dirtrt;

import io.github.andrexarlay.vulkancompat.rt.world.RtVulkanPipelineCache;
import io.github.andrexarlay.vulkancompat.shaderhost.dirtrt.DirtRtPipelineConfig.BindingInfo;
import io.github.andrexarlay.vulkancompat.shaderhost.dirtrt.DirtRtPipelineConfig.DescriptorType;
import io.github.andrexarlay.vulkancompat.shaderhost.dirtrt.DirtRtPipelineConfig.RayPass;
import io.github.andrexarlay.vulkancompat.shaderhost.dirtrt.DirtRtPipelineConfig.SbtLayout;
import io.github.andrexarlay.vulkancompat.shaderhost.dirtrt.DirtRtSbtBuilder.MultiPassSbt;
import io.github.andrexarlay.vulkancompat.shaderhost.dirtrt.DirtRtSbtBuilder.PassSbt;
import io.github.andrexarlay.vulkancompat.shaderhost.dirtrt.DirtRtSbtBuilder.VkRegions;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRAccelerationStructure;
import org.lwjgl.vulkan.KHRRayTracingPipeline;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkMemoryBarrier;
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo;
import org.lwjgl.vulkan.VkPipelineShaderStageCreateInfo;
import org.lwjgl.vulkan.VkPushConstantRange;
import org.lwjgl.vulkan.VkRayTracingPipelineCreateInfoKHR;
import org.lwjgl.vulkan.VkRayTracingShaderGroupCreateInfoKHR;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;
import io.github.andrexarlay.vulkancompat.rt.entity.RtSceneInstances;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * Manages the {@code VK_KHR_ray_tracing_pipeline} pipeline for Dirt RT.
 * <p>
 * Responsibilities:
 * <ul>
 *   <li>Loading compiled SPIR-V shaders for 3 ray passes: diffuse ({@code ray0}),
 *       reflection ({@code ray1}), and refraction ({@code ray2}).</li>
 *   <li>Configuring shader stages (12 stages: raygen, miss, closest-hit, any-hit per pass)
 *       and shader groups (9 groups: 3 general raygen, 3 general miss, 3 triangle hit groups).</li>
 *   <li>Configuring descriptor sets:
 *     <ul>
 *       <li>Set 0: CameraInfo UBO (256B), TLAS acceleration structure, block texture samplers, RayTraceData image</li>
 *       <li>Set 1: Geometry Quad SSBO bindless array (160B quads)</li>
 *       <li>Set 2: Texture atlases (block, normal, specular)</li>
 *       <li>Set 3: 5 screen SSBO buffers (GeometryMaterialBuffer, FrameData, DiffuseBuffer, ReflectBuffer, RefractBuffer)</li>
 *     </ul>
 *   </li>
 *   <li>Constructing {@link VkPipelineLayoutCreateInfo} and {@link VkRayTracingPipelineCreateInfoKHR}.</li>
 *   <li>Interfacing with {@link DirtRtSbtBuilder} to generate SBT device addresses and strided regions.</li>
 *   <li>Recording {@code vkCmdTraceRaysKHR} dispatches and inter-pass memory synchronization barriers.</li>
 * </ul>
 */
public final class DirtRtPipeline implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger("VulkanRtx/DirtRtPipeline");

    public static final int TOTAL_PASSES = 3;
    public static final int STAGES_PER_PASS = 4;
    public static final int TOTAL_STAGES = TOTAL_PASSES * STAGES_PER_PASS; // 12
    public static final int GROUPS_PER_PASS = 3;
    public static final int TOTAL_SHADER_GROUPS = TOTAL_PASSES * GROUPS_PER_PASS; // 9

    public static final int DEFAULT_MAX_GEOMETRY_BUFFERS = 1024;
    public static final int DEFAULT_MAX_RECURSION_DEPTH = 1;
    public static final int PUSH_CONSTANT_BYTES = 16;

    public static final int SPIRV_MAGIC = 0x07230203;

    public static final int RT_ALL_STAGES =
            KHRRayTracingPipeline.VK_SHADER_STAGE_RAYGEN_BIT_KHR
            | KHRRayTracingPipeline.VK_SHADER_STAGE_MISS_BIT_KHR
            | KHRRayTracingPipeline.VK_SHADER_STAGE_CLOSEST_HIT_BIT_KHR
            | KHRRayTracingPipeline.VK_SHADER_STAGE_ANY_HIT_BIT_KHR;

    public static final int PUSH_CONSTANT_STAGES =
            KHRRayTracingPipeline.VK_SHADER_STAGE_RAYGEN_BIT_KHR
            | KHRRayTracingPipeline.VK_SHADER_STAGE_CLOSEST_HIT_BIT_KHR;

    public enum State {
        UNAVAILABLE,
        DISCOVERED,
        PARSING,
        COMPILING,
        CREATING_MODULES,
        CREATING_PIPELINE,
        CREATING_SBT,
        VALIDATING,
        READY,
        ACTIVATING,
        ACTIVE,
        FAILED,
        ROLLING_BACK
    }

    public record TlasBinding(
            int set,
            int binding,
            String name,
            long tlasAddressOrHandle,
            boolean includesEntities,
            int instanceMask
    ) {
        public static final String ENTITY_TLAS_NAME = "tlas";
        public static final int ENTITY_TLAS_SET = 0;
        public static final int ENTITY_TLAS_BINDING = 0;

        public String format() {
            return tlasAddressOrHandle != 0L ? ("0x" + Long.toHexString(tlasAddressOrHandle)) : "N/A";
        }
    }

    private static volatile State currentState = State.UNAVAILABLE;
    private static volatile String selectedPack = "none";
    private static volatile long currentPipelineHandle = 0L;
    private static volatile int pipelineGeneration = 0;
    private static volatile long currentShaderHash = 0L;
    private static volatile long currentPipelineHash = 0L;
    private static volatile int currentShaderModules = 0;
    private static volatile long currentSbtAddress = 0L;
    private static volatile int currentSbtGroups = 0;
    private static volatile int currentDescriptors = 0;
    private static volatile long currentTraceCalls = 0L;
    private static volatile long currentFramesRendered = 0L;
    private static volatile String lastError = "none";
    private static volatile long rollbackPipelineHandle = 0L;
    private static volatile long nativeRtFallbackPipeline = 0xCAFE_BABE_0001L;
    private static volatile String entityLayoutStatus = "COMPATIBLE";
    private static volatile TlasBinding currentTlasBinding = null;
    private static volatile long currentTlasDescriptor = 0L;

    public static synchronized void updateState(State state, String pack, String error) {
        currentState = state;
        if (pack != null) selectedPack = pack;
        if (error != null) lastError = error;
    }

    public static synchronized void onPipelineCreated(long handle, int modules, int groups, int descriptors) {
        currentPipelineHandle = handle;
        currentShaderModules = modules;
        currentSbtGroups = groups;
        currentDescriptors = descriptors;
        pipelineGeneration++;
        currentState = State.READY;
    }

    public static synchronized void onPipelineCreated(long handle, long tlasHandle, int modules, int groups, int descriptors) {
        currentPipelineHandle = handle;
        currentTlasDescriptor = tlasHandle;
        currentShaderModules = modules;
        currentSbtGroups = groups;
        currentDescriptors = descriptors;
        pipelineGeneration++;
        currentState = State.READY;
    }

    public static synchronized void onFrameRendered() {
        currentFramesRendered++;
        currentTraceCalls += TOTAL_PASSES;
        if (currentPipelineHandle != 0L && (currentState == State.READY || currentState == State.ACTIVATING)) {
            currentState = State.ACTIVE;
        }
    }

    public static synchronized void onRollback(long fallbackHandle, String reason) {
        rollbackPipelineHandle = currentPipelineHandle;
        currentPipelineHandle = fallbackHandle;
        currentState = State.ROLLING_BACK;
        lastError = reason;
    }

    public static synchronized void failEntityCompatibility(String reason) {
        currentState = State.FAILED;
        entityLayoutStatus = "INCOMPATIBLE";
        lastError = (reason != null && !reason.isBlank()) ? reason : "Entity TLAS layout unsupported by pack";
        if (nativeRtFallbackPipeline != 0L && currentPipelineHandle == 0L) {
            currentPipelineHandle = nativeRtFallbackPipeline;
        }
    }

    public static synchronized void onFailure(String error, long fallbackPipeline) {
        currentState = State.FAILED;
        lastError = (error != null && !error.isBlank()) ? error : "Unknown failure";
        if (fallbackPipeline != 0L) {
            currentPipelineHandle = fallbackPipeline;
        } else if (nativeRtFallbackPipeline != 0L) {
            currentPipelineHandle = nativeRtFallbackPipeline;
        }
    }

    public static synchronized boolean acceptSceneTlas(long tlasHandle, boolean includesEntities) {
        if (tlasHandle == 0L) {
            currentTlasBinding = null;
            currentTlasDescriptor = 0L;
            return false;
        }
        int mask = includesEntities ? RtSceneInstances.MASK_FULL_SCENE : RtSceneInstances.MASK_WORLD;
        currentTlasBinding = new TlasBinding(0, 0, "tlas", tlasHandle, includesEntities, mask);
        currentTlasDescriptor = tlasHandle;
        entityLayoutStatus = "COMPATIBLE";
        return true;
    }

    public static synchronized boolean acceptSceneTlas(RtSceneInstances sceneInstances) {
        if (sceneInstances == null) {
            failEntityCompatibility("Scene instances contract was null");
            return false;
        }
        long ref = sceneInstances.worldAsReference();
        if (ref == 0L) {
            failEntityCompatibility("Scene TLAS reference was 0");
            return false;
        }
        boolean hasEnt = sceneInstances.hasEntities();
        return acceptSceneTlas(ref, hasEnt);
    }

    public static synchronized void resetState() {
        currentState = State.UNAVAILABLE;
        selectedPack = "none";
        currentPipelineHandle = 0L;
        pipelineGeneration = 0;
        currentShaderHash = 0L;
        currentPipelineHash = 0L;
        currentShaderModules = 0;
        currentSbtAddress = 0L;
        currentSbtGroups = 0;
        currentDescriptors = 0;
        currentTraceCalls = 0L;
        currentFramesRendered = 0L;
        lastError = "none";
        rollbackPipelineHandle = 0L;
        entityLayoutStatus = "COMPATIBLE";
        currentTlasBinding = null;
        currentTlasDescriptor = 0L;
    }

    public static boolean isDirtRtPack(String packName) {
        if (packName == null) return false;
        String lower = packName.toLowerCase(Locale.ROOT);
        return lower.contains("dirt rt") || lower.contains("dirtrt") || lower.contains("dirt-rt");
    }

    public static synchronized boolean handleShaderpackReload(String packName) {
        if (!isDirtRtPack(packName)) {
            return false;
        }
        updateState(State.UNAVAILABLE, packName, "Dirt RT runtime loader is not implemented (scaffold only)");
        return true;
    }

    public static String getF3Telemetry() {
        State reportingState = (currentPipelineHandle != 0L && currentFramesRendered > 0L && currentState != State.FAILED && currentState != State.ROLLING_BACK)
            ? State.ACTIVE
            : currentState;
        return String.format(
            "[DirtRT] selectedPack=%s state=%s pipeline=%s entityLayout=%s traceCalls=%d framesRendered=%d lastError=%s",
            selectedPack,
            reportingState,
            currentPipelineHandle != 0L ? ("0x" + Long.toHexString(currentPipelineHandle)) : "N/A",
            entityLayoutStatus,
            currentTraceCalls,
            currentFramesRendered,
            lastError
        );
    }

    public static String getTlasDescriptorString() {
        return currentTlasDescriptor != 0L ? ("0x" + Long.toHexString(currentTlasDescriptor)) : "N/A";
    }

    public static String getDescriptorsTelemetry() {
        return currentDescriptors > 0 ? String.valueOf(currentDescriptors) : "N/A";
    }

    public static String formatDescriptor(long handle) {
        return handle != 0L ? ("0x" + Long.toHexString(handle)) : "N/A";
    }

    public static State getState() { return currentState; }
    public static String getSelectedPack() { return selectedPack; }
    public static long getCurrentPipelineHandle() { return currentPipelineHandle; }
    public static String getEntityLayoutStatus() { return entityLayoutStatus; }
    public static void setEntityLayoutStatus(String status) { entityLayoutStatus = status; }
    public static long getFramesRendered() { return currentFramesRendered; }
    public static long getTraceCalls() { return currentTraceCalls; }
    public static String getLastError() { return lastError; }
    public static TlasBinding getCurrentTlasBinding() { return currentTlasBinding; }
    public static void setNativeRtFallbackPipeline(long handle) { nativeRtFallbackPipeline = handle; }
    public static long getNativeRtFallbackPipeline() { return nativeRtFallbackPipeline; }

    // =========================================================================
    // Shader Stage and Group Descriptors
    // =========================================================================

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

    public record DirtRtShaderBundle(
            PassShaders diffuse,
            PassShaders reflection,
            PassShaders refraction
    ) {
        public DirtRtShaderBundle {
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
            RayPass pass = DirtRtPipelineConfig.PASSES.get(passIdx);
            ShaderStageType type = ShaderStageType.values()[typeIdx];
            return forPass(pass).getBytecode(type);
        }
    }

    /**
     * Builds list of all 12 canonical shader stage descriptors in sequential order.
     */
    public static List<StageDesc> buildStageDescriptors() {
        List<StageDesc> stages = new ArrayList<>(TOTAL_STAGES);
        int index = 0;
        for (RayPass pass : DirtRtPipelineConfig.PASSES) {
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
            // Pass order (matches DirtRtSbtBuilder default)
            for (int p = 0; p < TOTAL_PASSES; p++) {
                RayPass pass = DirtRtPipelineConfig.PASSES.get(p);
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
                RayPass pass = DirtRtPipelineConfig.PASSES.get(p);
                groups.add(ShaderGroupDesc.general(p, pass, p * STAGES_PER_PASS));
            }
            for (int p = 0; p < TOTAL_PASSES; p++) {
                RayPass pass = DirtRtPipelineConfig.PASSES.get(p);
                groups.add(ShaderGroupDesc.general(3 + p, pass, p * STAGES_PER_PASS + 1));
            }
            for (int p = 0; p < TOTAL_PASSES; p++) {
                RayPass pass = DirtRtPipelineConfig.PASSES.get(p);
                groups.add(ShaderGroupDesc.trianglesHit(6 + p, pass, p * STAGES_PER_PASS + 2, p * STAGES_PER_PASS + 3));
            }
        }
        return Collections.unmodifiableList(groups);
    }

    public static List<ShaderGroupDesc> buildGroupDescriptors() {
        return buildGroupDescriptors(false);
    }

    // =========================================================================
    // Descriptor Set Layout Specification
    // =========================================================================

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

    /**
     * Returns full descriptor set descriptions for Sets 0, 1, 2, and 3.
     */
    public static List<DescriptorSetDesc> describeDescriptorSets(int maxGeometryBuffers) {
        List<DescriptorSetDesc> sets = new ArrayList<>(4);

        // Set 0: Frame uniforms (CameraInfo UBO 256B, TLAS, samplers, RayTraceData image)
        List<DescriptorBindingDesc> set0 = new ArrayList<>();
        for (BindingInfo b : DirtRtPipelineConfig.SET0_BINDINGS) {
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
                DirtRtPipelineConfig.SET1_GEOMETRY_BUFFERS.binding(),
                DirtRtPipelineConfig.SET1_GEOMETRY_BUFFERS.name(),
                DirtRtPipelineConfig.SET1_GEOMETRY_BUFFERS.type(),
                geomCount,
                KHRRayTracingPipeline.VK_SHADER_STAGE_CLOSEST_HIT_BIT_KHR | KHRRayTracingPipeline.VK_SHADER_STAGE_ANY_HIT_BIT_KHR
        ));
        sets.add(new DescriptorSetDesc(1, "GeometryBuffers", Collections.unmodifiableList(set1)));

        // Set 2: Texture atlases
        List<DescriptorBindingDesc> set2 = new ArrayList<>();
        for (BindingInfo b : DirtRtPipelineConfig.SET2_BINDINGS) {
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
        for (BindingInfo b : DirtRtPipelineConfig.SET3_BINDINGS) {
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

    public static List<DescriptorSetDesc> describeDescriptorSets() {
        return describeDescriptorSets(DEFAULT_MAX_GEOMETRY_BUFFERS);
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

    public static DirtRtShaderBundle createSyntheticShaderBundle() {
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
        return new DirtRtShaderBundle(p0, p1, p2);
    }

    public static ByteBuffer loadPrecompiledSpirv(String resourcePath) throws IOException {
        Objects.requireNonNull(resourcePath, "resourcePath must not be null");
        InputStream stream = DirtRtPipeline.class.getResourceAsStream(resourcePath);
        if (stream == null) {
            String alt = resourcePath.startsWith("/") ? resourcePath.substring(1) : "/" + resourcePath;
            stream = DirtRtPipeline.class.getResourceAsStream(alt);
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

    public static DirtRtShaderBundle loadShaderBundleFromMap(Map<String, ByteBuffer> bytecodeMap) {
        Objects.requireNonNull(bytecodeMap, "bytecodeMap must not be null");
        Map<RayPass, PassShaders> passes = new LinkedHashMap<>();

        for (RayPass pass : DirtRtPipelineConfig.PASSES) {
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

        return new DirtRtShaderBundle(
                passes.get(RayPass.DIFFUSE),
                passes.get(RayPass.REFLECTION),
                passes.get(RayPass.REFRACTION)
        );
    }

    public static DirtRtShaderBundle loadShaderBundleFromProvider(Function<String, ByteBuffer> provider) {
        Objects.requireNonNull(provider, "provider must not be null");
        Map<String, ByteBuffer> resolved = new LinkedHashMap<>();
        for (RayPass pass : DirtRtPipelineConfig.PASSES) {
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

    public static DirtRtShaderBundle loadShaderBundleFromResources(String basePath) throws IOException {
        String base = (basePath == null || basePath.isEmpty()) ? "/shaders/" : (basePath.endsWith("/") ? basePath : basePath + "/");
        Map<String, ByteBuffer> map = new LinkedHashMap<>();
        for (RayPass pass : DirtRtPipelineConfig.PASSES) {
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
    // Native Vulkan Structure Population
    // =========================================================================

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
        return DirtRtSbtBuilder.createThreePassSbt(bufferDeviceAddress, layout, shaderGroupHandles, stageOrder);
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

        // 2. Push constants (e.g. firstLobe value)
        ByteBuffer pc = stack.malloc(PUSH_CONSTANT_BYTES);
        pc.putInt(0, pass.firstLobeVal());
        VK10.vkCmdPushConstants(cmd, pipelineLayout, PUSH_CONSTANT_STAGES, 0, pc);

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
        Objects.requireNonNull(cmd, "cmd must not be null");
        Objects.requireNonNull(multiPassSbt, "multiPassSbt must not be null");

        try (MemoryStack stack = MemoryStack.stackPush()) {
            for (int i = 0; i < DirtRtPipelineConfig.PASSES.size(); i++) {
                RayPass pass = DirtRtPipelineConfig.PASSES.get(i);
                PassSbt passSbt = multiPassSbt.pass(pass);
                recordTracePass(cmd, pipelineHandle, pipelineLayout, pass, passSbt, width, height, stack);

                if (i < DirtRtPipelineConfig.PASSES.size() - 1) {
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
    private final long[] setLayoutHandles = new long[DirtRtPipelineConfig.TOTAL_DESCRIPTOR_SETS];
    private final long[] shaderModuleHandles = new long[TOTAL_STAGES];
    private boolean initialized = false;

    public DirtRtPipeline(VkDevice device, boolean stageOrder) {
        this.device = device;
        this.stageOrder = stageOrder;
    }

    public DirtRtPipeline(VkDevice device) {
        this(device, false);
    }

    public DirtRtPipeline() {
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
            DirtRtShaderBundle shaders,
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
            for (int s = 0; s < DirtRtPipelineConfig.TOTAL_DESCRIPTOR_SETS; s++) {
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

            LOGGER.info("Dirt RT Ray Tracing Pipeline created successfully: handle=0x{}", Long.toHexString(pipelineHandle));
        }
    }

    public void recordTracePass(
            VkCommandBuffer cmd,
            RayPass pass,
            PassSbt passSbt,
            int width,
            int height
    ) {
        recordTracePass(cmd, pipelineHandle, pipelineLayoutHandle, pass, passSbt, width, height);
    }

    public void recordTraceAllPasses(
            VkCommandBuffer cmd,
            MultiPassSbt multiPassSbt,
            int width,
            int height
    ) {
        recordTraceAllPasses(cmd, pipelineHandle, pipelineLayoutHandle, multiPassSbt, width, height);
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
            for (int s = 0; s < DirtRtPipelineConfig.TOTAL_DESCRIPTOR_SETS; s++) {
                if (setLayoutHandles[s] != 0L) {
                    VK10.vkDestroyDescriptorSetLayout(device, setLayoutHandles[s], null);
                    setLayoutHandles[s] = 0L;
                }
            }
            initialized = false;
        }
    }
}
