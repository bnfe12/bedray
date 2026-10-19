package io.github.andrexarlay.vulkancompat.shaderhost.dirtrt;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Pipeline and Shader Binding Table (SBT) configuration for Dirt RT Vulkanite ray-tracing pipeline.
 * <p>
 * Dirt RT uses {@code VK_KHR_ray_tracing_pipeline} with 3 distinct ray passes:
 * <ul>
 *   <li>{@code ray0}: Diffuse / primary bounce</li>
 *   <li>{@code ray1}: Reflection bounce</li>
 *   <li>{@code ray2}: Refraction bounce</li>
 * </ul>
 * Descriptor sets layout:
 * <ul>
 *   <li>Set 0: Frame uniforms (CameraInfo UBO 256B, TLAS, terrain atlas samplers, RayTraceData image)</li>
 *   <li>Set 1: Quad geometry buffers (bindless array of 160-byte quad SSBOs)</li>
 *   <li>Set 3: Output buffers (GeometryMaterialBuffer, FrameData, DiffuseBuffer, ReflectBuffer, RefractBuffer)</li>
 * </ul>
 */
public final class DirtRtPipelineConfig {

    public static final String REQUIRED_EXTENSION = "VK_KHR_ray_tracing_pipeline";

    /**
     * Ray tracing passes executed per frame in index order.
     */
    public enum RayPass {
        DIFFUSE(0, "diffuse", "ray0", 2),
        REFLECTION(1, "reflection", "ray1", 1),
        REFRACTION(2, "refraction", "ray2", 3);

        private final int passIndex;
        private final String passName;
        private final String shaderPrefix;
        private final int firstLobeVal;

        RayPass(int passIndex, String passName, String shaderPrefix, int firstLobeVal) {
            this.passIndex = passIndex;
            this.passName = passName;
            this.shaderPrefix = shaderPrefix;
            this.firstLobeVal = firstLobeVal;
        }

        public int passIndex() {
            return passIndex;
        }

        public String passName() {
            return passName;
        }

        public String shaderPrefix() {
            return shaderPrefix;
        }

        public int firstLobeVal() {
            return firstLobeVal;
        }

        public String raygenShader() {
            return shaderPrefix + ".rgen";
        }

        public String closestHitShader() {
            return shaderPrefix + "_0.rchit";
        }

        public String anyHitShader() {
            return shaderPrefix + "_0.rahit";
        }

        public String missShader() {
            return shaderPrefix + "_0.rmiss";
        }
    }

    public static final List<RayPass> PASSES = List.of(
            RayPass.DIFFUSE,
            RayPass.REFLECTION,
            RayPass.REFRACTION
    );

    /**
     * Output SSBO buffers bound at Set 3.
     */
    public enum OutputBuffer {
        GEOMETRY_MATERIAL(0, "GeometryMaterialBuffer", 128, 0),
        FRAME_DATA(1, "FrameData", 0, 1024),
        DIFFUSE(2, "DiffuseBuffer", 96, 0),
        REFLECT(3, "ReflectBuffer", 64, 0),
        REFRACT(4, "RefractBuffer", 64, 0);

        private final int binding;
        private final String bufferName;
        private final int bytesPerPixel;
        private final int fixedSizeBytes;

        OutputBuffer(int binding, String bufferName, int bytesPerPixel, int fixedSizeBytes) {
            this.binding = binding;
            this.bufferName = bufferName;
            this.bytesPerPixel = bytesPerPixel;
            this.fixedSizeBytes = fixedSizeBytes;
        }

        public int binding() {
            return binding;
        }

        public String bufferName() {
            return bufferName;
        }

        public int bytesPerPixel() {
            return bytesPerPixel;
        }

        public int fixedSizeBytes() {
            return fixedSizeBytes;
        }

        public boolean isPerPixel() {
            return bytesPerPixel > 0;
        }

        /**
         * Calculates required buffer size in bytes for the given screen resolution.
         */
        public long calculateSizeBytes(int width, int height) {
            if (width < 0 || height < 0) {
                throw new IllegalArgumentException("Resolution dimensions must be non-negative: " + width + "x" + height);
            }
            if (fixedSizeBytes > 0) {
                return fixedSizeBytes;
            }
            return (long) width * (long) height * (long) bytesPerPixel;
        }
    }

    public static final List<OutputBuffer> OUTPUT_BUFFERS = List.of(
            OutputBuffer.GEOMETRY_MATERIAL,
            OutputBuffer.FRAME_DATA,
            OutputBuffer.DIFFUSE,
            OutputBuffer.REFLECT,
            OutputBuffer.REFRACT
    );

    /**
     * Vulkan descriptor types.
     */
    public enum DescriptorType {
        UNIFORM_BUFFER,
        ACCELERATION_STRUCTURE_KHR,
        COMBINED_IMAGE_SAMPLER,
        STORAGE_IMAGE,
        STORAGE_BUFFER
    }

    /**
     * Descriptor binding specification.
     */
    public record BindingInfo(
            int set,
            int binding,
            String name,
            DescriptorType type,
            int count,
            boolean isArray
    ) {
        public BindingInfo {
            Objects.requireNonNull(name, "name must not be null");
            Objects.requireNonNull(type, "type must not be null");
        }
    }

    // Set 0 bindings
    public static final BindingInfo SET0_CAMERA_INFO = new BindingInfo(0, 0, "CameraInfo", DescriptorType.UNIFORM_BUFFER, 1, false);
    public static final BindingInfo SET0_ACCELERATION_STRUCTURE = new BindingInfo(0, 1, "acc", DescriptorType.ACCELERATION_STRUCTURE_KHR, 1, false);
    public static final BindingInfo SET0_BLOCK_TEX = new BindingInfo(0, 3, "blockTex", DescriptorType.COMBINED_IMAGE_SAMPLER, 1, false);
    public static final BindingInfo SET0_BLOCK_TEX_NORMAL = new BindingInfo(0, 4, "blockTexNormal", DescriptorType.COMBINED_IMAGE_SAMPLER, 1, false);
    public static final BindingInfo SET0_BLOCK_TEX_SPECULAR = new BindingInfo(0, 5, "blockTexSpecular", DescriptorType.COMBINED_IMAGE_SAMPLER, 1, false);
    public static final BindingInfo SET0_RAY_TRACE_DATA = new BindingInfo(0, 6, "RayTraceData", DescriptorType.STORAGE_IMAGE, 1, false);

    public static final List<BindingInfo> SET0_BINDINGS = List.of(
            SET0_CAMERA_INFO,
            SET0_ACCELERATION_STRUCTURE,
            SET0_BLOCK_TEX,
            SET0_BLOCK_TEX_NORMAL,
            SET0_BLOCK_TEX_SPECULAR,
            SET0_RAY_TRACE_DATA
    );

    // Set 0 Entity-compatible bindings: set 0 binding 0 ("tlas") accepts full scene TLAS (world + entities)
    public static final BindingInfo SET0_ENTITY_TLAS = new BindingInfo(0, 0, "tlas", DescriptorType.ACCELERATION_STRUCTURE_KHR, 1, false);
    public static final BindingInfo SET0_ENTITY_CAMERA_INFO = new BindingInfo(0, 1, "CameraInfo", DescriptorType.UNIFORM_BUFFER, 1, false);

    public static final List<BindingInfo> SET0_ENTITY_BINDINGS = List.of(
            SET0_ENTITY_TLAS,
            SET0_ENTITY_CAMERA_INFO,
            SET0_BLOCK_TEX,
            SET0_BLOCK_TEX_NORMAL,
            SET0_BLOCK_TEX_SPECULAR,
            SET0_RAY_TRACE_DATA
    );

    // Set 1 bindings
    public static final BindingInfo SET1_GEOMETRY_BUFFERS = new BindingInfo(1, 0, "geometryBuffers", DescriptorType.STORAGE_BUFFER, -1, true);

    public static final List<BindingInfo> SET1_BINDINGS = List.of(
            SET1_GEOMETRY_BUFFERS
    );

    // Set 2 bindings (Texture Atlases)
    public static final BindingInfo SET2_BLOCK_ATLAS = new BindingInfo(2, 0, "blockAtlas", DescriptorType.COMBINED_IMAGE_SAMPLER, 1, false);
    public static final BindingInfo SET2_BLOCK_NORMAL = new BindingInfo(2, 1, "blockAtlasNormal", DescriptorType.COMBINED_IMAGE_SAMPLER, 1, false);
    public static final BindingInfo SET2_BLOCK_SPECULAR = new BindingInfo(2, 2, "blockAtlasSpecular", DescriptorType.COMBINED_IMAGE_SAMPLER, 1, false);

    public static final List<BindingInfo> SET2_BINDINGS = List.of(
            SET2_BLOCK_ATLAS,
            SET2_BLOCK_NORMAL,
            SET2_BLOCK_SPECULAR
    );

    // Set 3 bindings
    public static final BindingInfo SET3_GEOM_BUFFER = new BindingInfo(3, 0, "GeometryMaterialBuffer", DescriptorType.STORAGE_BUFFER, 1, false);
    public static final BindingInfo SET3_FRAME_DATA = new BindingInfo(3, 1, "FrameData", DescriptorType.STORAGE_BUFFER, 1, false);
    public static final BindingInfo SET3_DIFFUSE_BUFFER = new BindingInfo(3, 2, "DiffuseBuffer", DescriptorType.STORAGE_BUFFER, 1, false);
    public static final BindingInfo SET3_REFLECT_BUFFER = new BindingInfo(3, 3, "ReflectBuffer", DescriptorType.STORAGE_BUFFER, 1, false);
    public static final BindingInfo SET3_REFRACT_BUFFER = new BindingInfo(3, 4, "RefractBuffer", DescriptorType.STORAGE_BUFFER, 1, false);

    public static final List<BindingInfo> SET3_BINDINGS = List.of(
            SET3_GEOM_BUFFER,
            SET3_FRAME_DATA,
            SET3_DIFFUSE_BUFFER,
            SET3_REFLECT_BUFFER,
            SET3_REFRACT_BUFFER
    );

    public static final int TOTAL_DESCRIPTOR_SETS = 4;

    public static final List<List<BindingInfo>> ALL_DESCRIPTOR_SETS = List.of(
            SET0_BINDINGS,
            SET1_BINDINGS,
            SET2_BINDINGS,
            SET3_BINDINGS
    );

    /**
     * SBT (Shader Binding Table) layout calculator adhering to Vulkan ray-tracing requirements.
     */
    public static final class SbtLayout {
        public static final int DEFAULT_HANDLE_SIZE = 32;
        public static final int DEFAULT_BASE_ALIGNMENT = 64;
        public static final int DEFAULT_HANDLE_ALIGNMENT = 32;

        private final int handleSize;
        private final int handleAlignment;
        private final int baseAlignment;

        public SbtLayout() {
            this(DEFAULT_HANDLE_SIZE, DEFAULT_HANDLE_ALIGNMENT, DEFAULT_BASE_ALIGNMENT);
        }

        public SbtLayout(int handleSize, int handleAlignment, int baseAlignment) {
            if (handleSize <= 0) throw new IllegalArgumentException("handleSize must be positive: " + handleSize);
            if (handleAlignment <= 0) throw new IllegalArgumentException("handleAlignment must be positive: " + handleAlignment);
            if (baseAlignment <= 0) throw new IllegalArgumentException("baseAlignment must be positive: " + baseAlignment);
            this.handleSize = handleSize;
            this.handleAlignment = handleAlignment;
            this.baseAlignment = baseAlignment;
        }

        public int handleSize() {
            return handleSize;
        }

        public int handleAlignment() {
            return handleAlignment;
        }

        public int baseAlignment() {
            return baseAlignment;
        }

        public int handleSizeAligned() {
            return alignUp(handleSize, handleAlignment);
        }

        public int regionBaseAligned(int size) {
            return alignUp(size, baseAlignment);
        }

        public static int alignUp(int value, int alignment) {
            return (value + alignment - 1) & ~(alignment - 1);
        }

        /**
         * Computes region byte size given shader group count.
         */
        public int computeRegionSize(int groupCount) {
            if (groupCount <= 0) return 0;
            int stride = handleSizeAligned();
            return groupCount * stride;
        }

        /**
         * Computes total aligned SBT buffer size for raygen, miss, and hit groups.
         */
        public int computeTotalSbtSize(int raygenCount, int missCount, int hitGroupCount) {
            int raygenSize = regionBaseAligned(computeRegionSize(raygenCount));
            int missSize = regionBaseAligned(computeRegionSize(missCount));
            int hitSize = regionBaseAligned(computeRegionSize(hitGroupCount));
            return raygenSize + missSize + hitSize;
        }
    }

    private DirtRtPipelineConfig() {}
}
