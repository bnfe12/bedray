package io.github.andrexarlay.vulkancompat.shaderhost.dirtrt;

import io.github.andrexarlay.vulkancompat.shaderhost.dirtrt.DirtRtPipelineConfig.OutputBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkMemoryBarrier;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pass graph, dispatch dimension calculator, and memory barrier analyzer for the 27 compute passes of Dirt RT.
 * <p>
 * Dirt RT post-processing pipeline uses 27 compute passes ({@code composite*.csh}) executing SVGF denoising,
 * temporal accumulation, A-trous filtering, ReSTIR path guiding, Bloom downsampling/blurring, and auto exposure.
 * <p>
 * Workgroup dispatch adheres to the 8x8 tiled workgroup model ({@code local_size_x = 8, local_size_y = 8}),
 * ensuring optimal cache locality and full alignment with 8x8 tiled SSBO address indexing.
 */
public final class DirtRtComputePassGraph {

    public static final int DEFAULT_LOCAL_SIZE_X = 8;
    public static final int DEFAULT_LOCAL_SIZE_Y = 8;
    public static final int DEFAULT_LOCAL_SIZE_Z = 1;

    public static final int CANONICAL_PASS_COUNT = 27;

    private static final Pattern COMPOSITE_CSH_PATTERN = Pattern.compile("composite(\\d+)\\.csh$");

    // =========================================================================
    // Dispatch dimensions
    // =========================================================================

    public record DispatchDim(int x, int y, int z) {
        public DispatchDim {
            if (x < 0 || y < 0 || z < 0) {
                throw new IllegalArgumentException("Dispatch dimensions must be non-negative: " + x + "x" + y + "x" + z);
            }
        }

        public long totalGroups() {
            return (long) x * (long) y * (long) z;
        }

        public void dispatch(VkCommandBuffer cmd) {
            VK10.vkCmdDispatch(cmd, x, y, z);
        }
    }

    /**
     * Computes the 8x8 tiled workgroup dispatch dimensions for the specified resolution.
     */
    public static DispatchDim dispatchDim(int width, int height) {
        return dispatchDim(width, height, DEFAULT_LOCAL_SIZE_X, DEFAULT_LOCAL_SIZE_Y);
    }

    /**
     * Computes dispatch dimensions with custom local size.
     */
    public static DispatchDim dispatchDim(int width, int height, int localSizeX, int localSizeY) {
        if (width <= 0 || height <= 0) {
            return new DispatchDim(0, 0, 0);
        }
        int gx = (width + localSizeX - 1) / localSizeX;
        int gy = (height + localSizeY - 1) / localSizeY;
        return new DispatchDim(gx, gy, 1);
    }

    // =========================================================================
    // Pass classification and metadata
    // =========================================================================

    public enum PassCategory {
        DIFFUSE_TEMPORAL,
        SPECULAR_CURVATURE,
        REFLECT_TEMPORAL,
        REFRACT_TEMPORAL,
        DIFFUSE_FILTER,
        REFLECT_FILTER,
        REFRACT_FILTER,
        BLOOM,
        AUTO_EXPOSURE
    }

    public enum HazardType {
        RAW, // Read After Write
        WAW, // Write After Write
        WAR, // Write After Read
        NONE
    }

    public record ComputePass(
            int index,
            String name,
            String shaderPath,
            PassCategory category,
            String description,
            int localSizeX,
            int localSizeY,
            int localSizeZ,
            Set<OutputBuffer> readBuffers,
            Set<OutputBuffer> writeBuffers
    ) {
        public ComputePass {
            Objects.requireNonNull(name, "name must not be null");
            Objects.requireNonNull(shaderPath, "shaderPath must not be null");
            Objects.requireNonNull(category, "category must not be null");
            readBuffers = Collections.unmodifiableSet(EnumSet.copyOf(readBuffers));
            writeBuffers = Collections.unmodifiableSet(EnumSet.copyOf(writeBuffers));
        }

        public boolean reads(OutputBuffer buf) {
            return readBuffers.contains(buf);
        }

        public boolean writes(OutputBuffer buf) {
            return writeBuffers.contains(buf);
        }

        public boolean touches(OutputBuffer buf) {
            return reads(buf) || writes(buf);
        }

        public DispatchDim dispatchDim(int width, int height) {
            return DirtRtComputePassGraph.dispatchDim(width, height, localSizeX, localSizeY);
        }
    }

    // =========================================================================
    // Memory Barrier info
    // =========================================================================

    public record MemoryBarrierInfo(
            int srcStageMask,
            int dstStageMask,
            int srcAccessMask,
            int dstAccessMask,
            Set<OutputBuffer> hazardBuffers,
            Set<HazardType> hazardTypes
    ) {
        public boolean hasHazard() {
            return !hazardBuffers.isEmpty();
        }

        public boolean hasRawHazard() {
            return hazardTypes.contains(HazardType.RAW);
        }

        public boolean hasWawHazard() {
            return hazardTypes.contains(HazardType.WAW);
        }

        public boolean hasWarHazard() {
            return hazardTypes.contains(HazardType.WAR);
        }
    }

    public static Set<HazardType> detectHazards(ComputePass prev, ComputePass next, OutputBuffer buf) {
        Set<HazardType> hazards = EnumSet.noneOf(HazardType.class);
        if (prev.writes(buf) && next.reads(buf)) {
            hazards.add(HazardType.RAW);
        }
        if (prev.writes(buf) && next.writes(buf)) {
            hazards.add(HazardType.WAW);
        }
        if (prev.reads(buf) && next.writes(buf)) {
            hazards.add(HazardType.WAR);
        }
        return hazards;
    }

    public static MemoryBarrierInfo computeBarrierBetween(ComputePass prev, ComputePass next) {
        Set<OutputBuffer> hazardBuffers = EnumSet.noneOf(OutputBuffer.class);
        Set<HazardType> hazardTypes = EnumSet.noneOf(HazardType.class);

        for (OutputBuffer buf : OutputBuffer.values()) {
            Set<HazardType> hazards = detectHazards(prev, next, buf);
            if (!hazards.isEmpty()) {
                hazardBuffers.add(buf);
                hazardTypes.addAll(hazards);
            }
        }

        if (hazardBuffers.isEmpty()) {
            return new MemoryBarrierInfo(
                    VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    0,
                    0,
                    Collections.emptySet(),
                    Collections.emptySet()
            );
        }

        int srcAccess = 0;
        int dstAccess = 0;

        if (hazardTypes.contains(HazardType.RAW) || hazardTypes.contains(HazardType.WAW)) {
            srcAccess |= VK10.VK_ACCESS_SHADER_WRITE_BIT;
        }
        if (hazardTypes.contains(HazardType.WAR)) {
            srcAccess |= VK10.VK_ACCESS_SHADER_READ_BIT;
        }

        if (hazardTypes.contains(HazardType.RAW)) {
            dstAccess |= VK10.VK_ACCESS_SHADER_READ_BIT;
        }
        if (hazardTypes.contains(HazardType.WAW) || hazardTypes.contains(HazardType.WAR)) {
            dstAccess |= VK10.VK_ACCESS_SHADER_WRITE_BIT;
        }

        return new MemoryBarrierInfo(
                VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                srcAccess,
                dstAccess,
                Collections.unmodifiableSet(hazardBuffers),
                Collections.unmodifiableSet(hazardTypes)
        );
    }

    public static void recordBarrier(VkCommandBuffer cmd, MemoryBarrierInfo barrier) {
        if (!barrier.hasHazard() || barrier.srcAccessMask() == 0 || barrier.dstAccessMask() == 0) {
            return;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkMemoryBarrier.Buffer b = VkMemoryBarrier.calloc(1, stack);
            b.get(0).sType$Default()
                    .srcAccessMask(barrier.srcAccessMask())
                    .dstAccessMask(barrier.dstAccessMask());
            VK10.vkCmdPipelineBarrier(
                    cmd,
                    barrier.srcStageMask(),
                    barrier.dstStageMask(),
                    0,
                    b,
                    null,
                    null
            );
        }
    }

    public static void recordFullComputeSsboBarrier(VkCommandBuffer cmd) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkMemoryBarrier.Buffer b = VkMemoryBarrier.calloc(1, stack);
            b.get(0).sType$Default()
                    .srcAccessMask(VK10.VK_ACCESS_SHADER_WRITE_BIT | VK10.VK_ACCESS_SHADER_READ_BIT)
                    .dstAccessMask(VK10.VK_ACCESS_SHADER_WRITE_BIT | VK10.VK_ACCESS_SHADER_READ_BIT);
            VK10.vkCmdPipelineBarrier(
                    cmd,
                    VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    0,
                    b,
                    null,
                    null
            );
        }
    }

    // =========================================================================
    // Canonical 27 Dirt RT compute passes definition
    // =========================================================================

    public static final List<ComputePass> CANONICAL_PASSES = List.of(
            // 0: composite1 - Diffuse temporal accumulation
            new ComputePass(0, "composite1", "shaders/composite1.csh",
                    PassCategory.DIFFUSE_TEMPORAL, "Temporal accumulation diffuse",
                    DEFAULT_LOCAL_SIZE_X, DEFAULT_LOCAL_SIZE_Y, DEFAULT_LOCAL_SIZE_Z,
                    EnumSet.of(OutputBuffer.GEOMETRY_MATERIAL, OutputBuffer.FRAME_DATA, OutputBuffer.DIFFUSE),
                    EnumSet.of(OutputBuffer.DIFFUSE)),

            // 1: composite2 - Curvature correction
            new ComputePass(1, "composite2", "shaders/composite2.csh",
                    PassCategory.SPECULAR_CURVATURE, "Curvature correct specular",
                    DEFAULT_LOCAL_SIZE_X, DEFAULT_LOCAL_SIZE_Y, DEFAULT_LOCAL_SIZE_Z,
                    EnumSet.of(OutputBuffer.GEOMETRY_MATERIAL, OutputBuffer.REFLECT, OutputBuffer.REFRACT),
                    EnumSet.of(OutputBuffer.REFLECT, OutputBuffer.REFRACT)),

            // 2: composite4 - Reflect temporal accumulation
            new ComputePass(2, "composite4", "shaders/composite4.csh",
                    PassCategory.REFLECT_TEMPORAL, "Temporal accumulation reflect",
                    DEFAULT_LOCAL_SIZE_X, DEFAULT_LOCAL_SIZE_Y, DEFAULT_LOCAL_SIZE_Z,
                    EnumSet.of(OutputBuffer.GEOMETRY_MATERIAL, OutputBuffer.FRAME_DATA, OutputBuffer.REFLECT),
                    EnumSet.of(OutputBuffer.REFLECT)),

            // 3: composite5 - Refract temporal accumulation
            new ComputePass(3, "composite5", "shaders/composite5.csh",
                    PassCategory.REFRACT_TEMPORAL, "Temporal accumulation refract",
                    DEFAULT_LOCAL_SIZE_X, DEFAULT_LOCAL_SIZE_Y, DEFAULT_LOCAL_SIZE_Z,
                    EnumSet.of(OutputBuffer.GEOMETRY_MATERIAL, OutputBuffer.FRAME_DATA, OutputBuffer.REFRACT),
                    EnumSet.of(OutputBuffer.REFRACT)),

            // 4: composite50 - Diffuse variance prefilter
            new ComputePass(4, "composite50", "shaders/composite50.csh",
                    PassCategory.DIFFUSE_FILTER, "Variance prefilter diffuse",
                    DEFAULT_LOCAL_SIZE_X, DEFAULT_LOCAL_SIZE_Y, DEFAULT_LOCAL_SIZE_Z,
                    EnumSet.of(OutputBuffer.GEOMETRY_MATERIAL, OutputBuffer.DIFFUSE),
                    EnumSet.of(OutputBuffer.DIFFUSE)),

            // 5: composite51 - Diffuse A-trous step 1
            new ComputePass(5, "composite51", "shaders/composite51.csh",
                    PassCategory.DIFFUSE_FILTER, "A-trous denoise diffuse step 1 (R0=1, step=1)",
                    DEFAULT_LOCAL_SIZE_X, DEFAULT_LOCAL_SIZE_Y, DEFAULT_LOCAL_SIZE_Z,
                    EnumSet.of(OutputBuffer.GEOMETRY_MATERIAL, OutputBuffer.DIFFUSE),
                    EnumSet.of(OutputBuffer.DIFFUSE)),

            // 6: composite52 - Diffuse A-trous step 2
            new ComputePass(6, "composite52", "shaders/composite52.csh",
                    PassCategory.DIFFUSE_FILTER, "A-trous denoise diffuse step 2 (R0=2, step=2)",
                    DEFAULT_LOCAL_SIZE_X, DEFAULT_LOCAL_SIZE_Y, DEFAULT_LOCAL_SIZE_Z,
                    EnumSet.of(OutputBuffer.GEOMETRY_MATERIAL, OutputBuffer.DIFFUSE),
                    EnumSet.of(OutputBuffer.DIFFUSE)),

            // 7: composite53 - Diffuse A-trous step 3
            new ComputePass(7, "composite53", "shaders/composite53.csh",
                    PassCategory.DIFFUSE_FILTER, "A-trous denoise diffuse step 3 (R0=4, step=3)",
                    DEFAULT_LOCAL_SIZE_X, DEFAULT_LOCAL_SIZE_Y, DEFAULT_LOCAL_SIZE_Z,
                    EnumSet.of(OutputBuffer.GEOMETRY_MATERIAL, OutputBuffer.DIFFUSE),
                    EnumSet.of(OutputBuffer.DIFFUSE)),

            // 8: composite58 - ReSTIR path guide
            new ComputePass(8, "composite58", "shaders/composite58.csh",
                    PassCategory.DIFFUSE_FILTER, "ReSTIR path guide reservoir construction",
                    DEFAULT_LOCAL_SIZE_X, DEFAULT_LOCAL_SIZE_Y, DEFAULT_LOCAL_SIZE_Z,
                    EnumSet.of(OutputBuffer.GEOMETRY_MATERIAL, OutputBuffer.DIFFUSE),
                    EnumSet.of(OutputBuffer.DIFFUSE)),

            // 9: composite59 - Buffer swap diffuse
            new ComputePass(9, "composite59", "shaders/composite59.csh",
                    PassCategory.DIFFUSE_FILTER, "Buffer swap diffuse history",
                    DEFAULT_LOCAL_SIZE_X, DEFAULT_LOCAL_SIZE_Y, DEFAULT_LOCAL_SIZE_Z,
                    EnumSet.of(OutputBuffer.DIFFUSE),
                    EnumSet.of(OutputBuffer.DIFFUSE)),

            // 10: composite60 - Reflect variance prefilter
            new ComputePass(10, "composite60", "shaders/composite60.csh",
                    PassCategory.REFLECT_FILTER, "Variance prefilter reflect",
                    DEFAULT_LOCAL_SIZE_X, DEFAULT_LOCAL_SIZE_Y, DEFAULT_LOCAL_SIZE_Z,
                    EnumSet.of(OutputBuffer.GEOMETRY_MATERIAL, OutputBuffer.REFLECT),
                    EnumSet.of(OutputBuffer.REFLECT)),

            // 11: composite61 - Reflect A-trous step 1
            new ComputePass(11, "composite61", "shaders/composite61.csh",
                    PassCategory.REFLECT_FILTER, "A-trous denoise reflect step 1 (R0=1, step=1)",
                    DEFAULT_LOCAL_SIZE_X, DEFAULT_LOCAL_SIZE_Y, DEFAULT_LOCAL_SIZE_Z,
                    EnumSet.of(OutputBuffer.GEOMETRY_MATERIAL, OutputBuffer.REFLECT),
                    EnumSet.of(OutputBuffer.REFLECT)),

            // 12: composite62 - Reflect A-trous step 2
            new ComputePass(12, "composite62", "shaders/composite62.csh",
                    PassCategory.REFLECT_FILTER, "A-trous denoise reflect step 2 (R0=2, step=2)",
                    DEFAULT_LOCAL_SIZE_X, DEFAULT_LOCAL_SIZE_Y, DEFAULT_LOCAL_SIZE_Z,
                    EnumSet.of(OutputBuffer.GEOMETRY_MATERIAL, OutputBuffer.REFLECT),
                    EnumSet.of(OutputBuffer.REFLECT)),

            // 13: composite63 - Reflect A-trous step 3
            new ComputePass(13, "composite63", "shaders/composite63.csh",
                    PassCategory.REFLECT_FILTER, "A-trous denoise reflect step 3 (R0=4, step=2)",
                    DEFAULT_LOCAL_SIZE_X, DEFAULT_LOCAL_SIZE_Y, DEFAULT_LOCAL_SIZE_Z,
                    EnumSet.of(OutputBuffer.GEOMETRY_MATERIAL, OutputBuffer.REFLECT),
                    EnumSet.of(OutputBuffer.REFLECT)),

            // 14: composite69 - Buffer swap reflect
            new ComputePass(14, "composite69", "shaders/composite69.csh",
                    PassCategory.REFLECT_FILTER, "Buffer swap reflect history",
                    DEFAULT_LOCAL_SIZE_X, DEFAULT_LOCAL_SIZE_Y, DEFAULT_LOCAL_SIZE_Z,
                    EnumSet.of(OutputBuffer.REFLECT),
                    EnumSet.of(OutputBuffer.REFLECT)),

            // 15: composite70 - Refract variance prefilter
            new ComputePass(15, "composite70", "shaders/composite70.csh",
                    PassCategory.REFRACT_FILTER, "Variance prefilter refract",
                    DEFAULT_LOCAL_SIZE_X, DEFAULT_LOCAL_SIZE_Y, DEFAULT_LOCAL_SIZE_Z,
                    EnumSet.of(OutputBuffer.GEOMETRY_MATERIAL, OutputBuffer.REFRACT),
                    EnumSet.of(OutputBuffer.REFRACT)),

            // 16: composite71 - Refract A-trous step 1
            new ComputePass(16, "composite71", "shaders/composite71.csh",
                    PassCategory.REFRACT_FILTER, "A-trous denoise refract step 1 (R0=1, step=1)",
                    DEFAULT_LOCAL_SIZE_X, DEFAULT_LOCAL_SIZE_Y, DEFAULT_LOCAL_SIZE_Z,
                    EnumSet.of(OutputBuffer.GEOMETRY_MATERIAL, OutputBuffer.REFRACT),
                    EnumSet.of(OutputBuffer.REFRACT)),

            // 17: composite72 - Refract A-trous step 2
            new ComputePass(17, "composite72", "shaders/composite72.csh",
                    PassCategory.REFRACT_FILTER, "A-trous denoise refract step 2 (R0=2, step=2)",
                    DEFAULT_LOCAL_SIZE_X, DEFAULT_LOCAL_SIZE_Y, DEFAULT_LOCAL_SIZE_Z,
                    EnumSet.of(OutputBuffer.GEOMETRY_MATERIAL, OutputBuffer.REFRACT),
                    EnumSet.of(OutputBuffer.REFRACT)),

            // 18: composite73 - Refract A-trous step 3
            new ComputePass(18, "composite73", "shaders/composite73.csh",
                    PassCategory.REFRACT_FILTER, "A-trous denoise refract step 3 (R0=4, step=1)",
                    DEFAULT_LOCAL_SIZE_X, DEFAULT_LOCAL_SIZE_Y, DEFAULT_LOCAL_SIZE_Z,
                    EnumSet.of(OutputBuffer.GEOMETRY_MATERIAL, OutputBuffer.REFRACT),
                    EnumSet.of(OutputBuffer.REFRACT)),

            // 19: composite79 - Buffer swap refract
            new ComputePass(19, "composite79", "shaders/composite79.csh",
                    PassCategory.REFRACT_FILTER, "Buffer swap refract history",
                    DEFAULT_LOCAL_SIZE_X, DEFAULT_LOCAL_SIZE_Y, DEFAULT_LOCAL_SIZE_Z,
                    EnumSet.of(OutputBuffer.REFRACT),
                    EnumSet.of(OutputBuffer.REFRACT)),

            // 20: composite91 - Bloom L0 downsample
            new ComputePass(20, "composite91", "shaders/composite91.csh",
                    PassCategory.BLOOM, "Bloom downsample level 0",
                    16, 16, 1,
                    EnumSet.noneOf(OutputBuffer.class),
                    EnumSet.noneOf(OutputBuffer.class)),

            // 21: composite92 - Bloom L1-L3 downsample
            new ComputePass(21, "composite92", "shaders/composite92.csh",
                    PassCategory.BLOOM, "Bloom downsample levels 1..3",
                    16, 16, 1,
                    EnumSet.noneOf(OutputBuffer.class),
                    EnumSet.noneOf(OutputBuffer.class)),

            // 22: composite93 - Bloom L4-L6 downsample
            new ComputePass(22, "composite93", "shaders/composite93.csh",
                    PassCategory.BLOOM, "Bloom downsample levels 4..6",
                    16, 16, 1,
                    EnumSet.noneOf(OutputBuffer.class),
                    EnumSet.noneOf(OutputBuffer.class)),

            // 23: composite94 - Bloom L7-L8 downsample
            new ComputePass(23, "composite94", "shaders/composite94.csh",
                    PassCategory.BLOOM, "Bloom downsample levels 7..8",
                    16, 16, 1,
                    EnumSet.noneOf(OutputBuffer.class),
                    EnumSet.noneOf(OutputBuffer.class)),

            // 24: composite95 - Bloom horizontal Gaussian blur
            new ComputePass(24, "composite95", "shaders/composite95.csh",
                    PassCategory.BLOOM, "Bloom horizontal 33-tap Gaussian blur",
                    256, 1, 1,
                    EnumSet.noneOf(OutputBuffer.class),
                    EnumSet.noneOf(OutputBuffer.class)),

            // 25: composite96 - Bloom vertical Gaussian blur
            new ComputePass(25, "composite96", "shaders/composite96.csh",
                    PassCategory.BLOOM, "Bloom vertical 33-tap Gaussian blur",
                    1, 256, 1,
                    EnumSet.noneOf(OutputBuffer.class),
                    EnumSet.noneOf(OutputBuffer.class)),

            // 26: composite99 - Auto-exposure
            new ComputePass(26, "composite99", "shaders/composite99.csh",
                    PassCategory.AUTO_EXPOSURE, "Auto-exposure histogram calculation",
                    1, 1, 1,
                    EnumSet.of(OutputBuffer.FRAME_DATA),
                    EnumSet.of(OutputBuffer.FRAME_DATA))
    );

    // =========================================================================
    // Instance graph state
    // =========================================================================

    private final List<ComputePass> passes;
    private final Map<String, ComputePass> passByName;
    private final List<MemoryBarrierInfo> barriers;

    public DirtRtComputePassGraph(List<ComputePass> passes) {
        Objects.requireNonNull(passes, "passes must not be null");
        this.passes = Collections.unmodifiableList(new ArrayList<>(passes));
        Map<String, ComputePass> byName = new HashMap<>();
        for (ComputePass p : this.passes) {
            byName.put(p.name(), p);
        }
        this.passByName = Collections.unmodifiableMap(byName);

        // Precompute barriers between consecutive passes
        List<MemoryBarrierInfo> barrierList = new ArrayList<>();
        for (int i = 0; i < this.passes.size() - 1; i++) {
            barrierList.add(computeBarrierBetween(this.passes.get(i), this.passes.get(i + 1)));
        }
        this.barriers = Collections.unmodifiableList(barrierList);
    }

    /**
     * Creates the default graph with all 27 canonical Dirt RT compute passes.
     */
    public static DirtRtComputePassGraph createDefault() {
        return new DirtRtComputePassGraph(CANONICAL_PASSES);
    }

    /**
     * Analyzes and registers compute passes from a collection of shader filenames.
     */
    public static DirtRtComputePassGraph analyzeAndRegister(Collection<String> filenames) {
        Objects.requireNonNull(filenames, "filenames must not be null");

        // Canonical map for quick lookup
        Map<String, ComputePass> canonicalMap = new HashMap<>();
        for (ComputePass p : CANONICAL_PASSES) {
            canonicalMap.put(p.name(), p);
        }

        // Filter and sort composite*.csh names numerically
        List<String> matched = new ArrayList<>();
        for (String file : filenames) {
            Matcher m = COMPOSITE_CSH_PATTERN.matcher(file);
            if (m.find()) {
                matched.add(file);
            }
        }

        matched.sort(Comparator.comparingInt(f -> {
            Matcher m = COMPOSITE_CSH_PATTERN.matcher(f);
            return m.find() ? Integer.parseInt(m.group(1)) : 0;
        }));

        List<ComputePass> ordered = new ArrayList<>();
        int index = 0;
        for (String file : matched) {
            Matcher m = COMPOSITE_CSH_PATTERN.matcher(file);
            if (m.find()) {
                String passName = "composite" + m.group(1);
                ComputePass canonical = canonicalMap.get(passName);
                if (canonical != null) {
                    ordered.add(new ComputePass(
                            index++,
                            canonical.name(),
                            canonical.shaderPath(),
                            canonical.category(),
                            canonical.description(),
                            canonical.localSizeX(),
                            canonical.localSizeY(),
                            canonical.localSizeZ(),
                            canonical.readBuffers(),
                            canonical.writeBuffers()
                    ));
                } else {
                    // Fallback pass for custom composite*.csh
                    ordered.add(new ComputePass(
                            index++,
                            passName,
                            file,
                            PassCategory.DIFFUSE_FILTER,
                            "Custom compute pass " + passName,
                            DEFAULT_LOCAL_SIZE_X,
                            DEFAULT_LOCAL_SIZE_Y,
                            DEFAULT_LOCAL_SIZE_Z,
                            EnumSet.allOf(OutputBuffer.class),
                            EnumSet.allOf(OutputBuffer.class)
                    ));
                }
            }
        }

        return new DirtRtComputePassGraph(ordered);
    }

    public int passCount() {
        return passes.size();
    }

    public List<ComputePass> passes() {
        return passes;
    }

    public ComputePass pass(int index) {
        if (index < 0 || index >= passes.size()) {
            throw new IndexOutOfBoundsException("Pass index out of bounds: " + index + " (total: " + passes.size() + ")");
        }
        return passes.get(index);
    }

    public ComputePass pass(String name) {
        return passByName.get(name);
    }

    public List<MemoryBarrierInfo> barriers() {
        return barriers;
    }

    public MemoryBarrierInfo barrierBetween(int fromIndex, int toIndex) {
        if (fromIndex < 0 || fromIndex >= passes.size() || toIndex < 0 || toIndex >= passes.size()) {
            throw new IndexOutOfBoundsException("Invalid pass indices: " + fromIndex + " -> " + toIndex);
        }
        return computeBarrierBetween(passes.get(fromIndex), passes.get(toIndex));
    }

    public MemoryBarrierInfo barrierBeforePass(int passIndex) {
        if (passIndex <= 0 || passIndex >= passes.size()) {
            return null;
        }
        return barriers.get(passIndex - 1);
    }

    public List<ComputePass> passesTouching(OutputBuffer buffer) {
        Objects.requireNonNull(buffer, "buffer must not be null");
        List<ComputePass> list = new ArrayList<>();
        for (ComputePass p : passes) {
            if (p.touches(buffer)) {
                list.add(p);
            }
        }
        return list;
    }

    public long totalDispatchGroups(int width, int height) {
        long total = 0L;
        for (ComputePass p : passes) {
            total += p.dispatchDim(width, height).totalGroups();
        }
        return total;
    }
}
