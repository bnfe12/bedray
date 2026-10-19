package io.github.andrexarlay.vulkancompat.shaderhost.dirtrt;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkStridedDeviceAddressRegionKHR;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Shader Binding Table (SBT) builder for Dirt RT ray-tracing pipeline ({@code VK_KHR_ray_tracing_pipeline}).
 * <p>
 * Specifications and constraints adhering to Vulkan spec and Dirt RT requirements:
 * <ul>
 *   <li>Raygen region: exactly 1 entry per pass, aligned to {@code shaderGroupBaseAlignment} (typically 64B).
 *       Per VUID-vkCmdTraceRaysKHR-size-04023, {@code stride} must equal {@code size}.</li>
 *   <li>Miss region: entries for miss shaders, aligned to {@code shaderGroupBaseAlignment} (64B).
 *       Stride is handle size aligned (32B).</li>
 *   <li>Hit region: entries for hit groups (closest-hit + any-hit), aligned to {@code shaderGroupBaseAlignment} (64B).
 *       Stride is handle size aligned (32B).</li>
 *   <li>Callable region: empty (deviceAddress = 0, stride = 0, size = 0).</li>
 *   <li>Populates {@link VkStridedDeviceAddressRegionKHR} structures and writes shader group handle
 *       payloads into host or GPU memory buffers.</li>
 * </ul>
 */
public final class DirtRtSbtBuilder {

    /**
     * Immutable representation of an individual SBT strided device address region.
     */
    public record SbtRegion(
            long deviceAddress,
            long stride,
            long size,
            int bufferOffset
    ) {
        public static final SbtRegion EMPTY = new SbtRegion(0L, 0L, 0L, 0);

        public boolean isEmpty() {
            return size == 0L || deviceAddress == 0L;
        }

        /**
         * Fills an existing LWJGL {@link VkStridedDeviceAddressRegionKHR} struct.
         */
        public VkStridedDeviceAddressRegionKHR fill(VkStridedDeviceAddressRegionKHR struct) {
            Objects.requireNonNull(struct, "struct must not be null");
            struct.set(deviceAddress, stride, size);
            return struct;
        }

        /**
         * Allocates and fills a {@link VkStridedDeviceAddressRegionKHR} on the given memory stack.
         */
        public VkStridedDeviceAddressRegionKHR allocate(MemoryStack stack) {
            Objects.requireNonNull(stack, "stack must not be null");
            VkStridedDeviceAddressRegionKHR struct = VkStridedDeviceAddressRegionKHR.malloc(stack);
            return fill(struct);
        }
    }

    /**
     * Container of the four native {@link VkStridedDeviceAddressRegionKHR} structs required by {@code vkCmdTraceRaysKHR}.
     */
    public record VkRegions(
            VkStridedDeviceAddressRegionKHR raygen,
            VkStridedDeviceAddressRegionKHR miss,
            VkStridedDeviceAddressRegionKHR hit,
            VkStridedDeviceAddressRegionKHR callable
    ) {}

    /**
     * Complete SBT configuration for a single ray-tracing pass (e.g. {@code ray0}, {@code ray1}, {@code ray2}).
     */
    public record PassSbt(
            DirtRtPipelineConfig.RayPass rayPass,
            int bufferOffset,
            int totalSizeBytes,
            SbtRegion raygen,
            SbtRegion miss,
            SbtRegion hit,
            SbtRegion callable
    ) {
        public void fillVkRegions(
                VkStridedDeviceAddressRegionKHR rgen,
                VkStridedDeviceAddressRegionKHR rmiss,
                VkStridedDeviceAddressRegionKHR rhit,
                VkStridedDeviceAddressRegionKHR rcall
        ) {
            raygen.fill(rgen);
            miss.fill(rmiss);
            hit.fill(rhit);
            callable.fill(rcall);
        }

        public VkRegions allocateVkRegions(MemoryStack stack) {
            return new VkRegions(
                    raygen.allocate(stack),
                    miss.allocate(stack),
                    hit.allocate(stack),
                    callable.allocate(stack)
            );
        }
    }

    /**
     * Multi-pass SBT representing all three Dirt RT passes packed into a single buffer.
     */
    public record MultiPassSbt(
            List<PassSbt> passes,
            int totalSizeBytes,
            long bufferDeviceAddress,
            ByteBuffer sbtBuffer
    ) {
        public PassSbt pass(DirtRtPipelineConfig.RayPass rayPass) {
            for (PassSbt p : passes) {
                if (p.rayPass() == rayPass) {
                    return p;
                }
            }
            throw new IllegalArgumentException("No PassSbt found for pass " + rayPass);
        }

        public PassSbt pass(int index) {
            return passes.get(index);
        }

        public int passCount() {
            return passes.size();
        }
    }

    private final DirtRtPipelineConfig.SbtLayout layout;
    private byte[] raygenHandle;
    private final List<byte[]> missHandles = new ArrayList<>();
    private final List<byte[]> hitHandles = new ArrayList<>();
    private final List<byte[]> callableHandles = new ArrayList<>();
    private long deviceAddress = 0L;
    private DirtRtPipelineConfig.RayPass rayPass;

    public DirtRtSbtBuilder() {
        this(new DirtRtPipelineConfig.SbtLayout());
    }

    public DirtRtSbtBuilder(DirtRtPipelineConfig.SbtLayout layout) {
        this.layout = Objects.requireNonNull(layout, "layout must not be null");
    }

    public DirtRtSbtBuilder(DirtRtDeviceRequirements.DirtRtProperties properties) {
        this(Objects.requireNonNull(properties, "properties must not be null").toSbtLayout());
    }

    public DirtRtPipelineConfig.SbtLayout layout() {
        return layout;
    }

    public DirtRtPipelineConfig.RayPass rayPass() {
        return rayPass;
    }

    public DirtRtSbtBuilder setRayPass(DirtRtPipelineConfig.RayPass rayPass) {
        this.rayPass = rayPass;
        return this;
    }

    public long deviceAddress() {
        return deviceAddress;
    }

    public DirtRtSbtBuilder setDeviceAddress(long deviceAddress) {
        if (deviceAddress < 0) {
            throw new IllegalArgumentException("deviceAddress must be non-negative: " + deviceAddress);
        }
        if (deviceAddress > 0 && deviceAddress % layout.baseAlignment() != 0) {
            throw new IllegalArgumentException("deviceAddress must be aligned to " + layout.baseAlignment() + ": " + deviceAddress);
        }
        this.deviceAddress = deviceAddress;
        return this;
    }

    public byte[] raygenHandle() {
        return raygenHandle != null ? raygenHandle.clone() : null;
    }

    public List<byte[]> missHandles() {
        return Collections.unmodifiableList(missHandles);
    }

    public List<byte[]> hitHandles() {
        return Collections.unmodifiableList(hitHandles);
    }

    public List<byte[]> callableHandles() {
        return Collections.unmodifiableList(callableHandles);
    }

    public DirtRtSbtBuilder setRaygen(byte[] handle) {
        Objects.requireNonNull(handle, "handle must not be null");
        if (handle.length != layout.handleSize()) {
            throw new IllegalArgumentException("handle size must be " + layout.handleSize() + " bytes, was: " + handle.length);
        }
        this.raygenHandle = handle.clone();
        return this;
    }

    public DirtRtSbtBuilder setRaygen(ByteBuffer src, int srcOffset) {
        Objects.requireNonNull(src, "src must not be null");
        int handleSize = layout.handleSize();
        if (srcOffset < 0 || srcOffset + handleSize > src.capacity()) {
            throw new IndexOutOfBoundsException("Invalid srcOffset " + srcOffset + " for handle size " + handleSize + " in capacity " + src.capacity());
        }
        byte[] handle = new byte[handleSize];
        int originalPos = src.position();
        src.position(srcOffset);
        src.get(handle, 0, handleSize);
        src.position(originalPos);
        return setRaygen(handle);
    }

    public DirtRtSbtBuilder addMiss(byte[] handle) {
        Objects.requireNonNull(handle, "handle must not be null");
        if (handle.length != layout.handleSize()) {
            throw new IllegalArgumentException("handle size must be " + layout.handleSize() + " bytes, was: " + handle.length);
        }
        this.missHandles.add(handle.clone());
        return this;
    }

    public DirtRtSbtBuilder addMiss(ByteBuffer src, int srcOffset) {
        Objects.requireNonNull(src, "src must not be null");
        int handleSize = layout.handleSize();
        if (srcOffset < 0 || srcOffset + handleSize > src.capacity()) {
            throw new IndexOutOfBoundsException("Invalid srcOffset " + srcOffset + " for handle size " + handleSize + " in capacity " + src.capacity());
        }
        byte[] handle = new byte[handleSize];
        int originalPos = src.position();
        src.position(srcOffset);
        src.get(handle, 0, handleSize);
        src.position(originalPos);
        return addMiss(handle);
    }

    public DirtRtSbtBuilder addHit(byte[] handle) {
        Objects.requireNonNull(handle, "handle must not be null");
        if (handle.length != layout.handleSize()) {
            throw new IllegalArgumentException("handle size must be " + layout.handleSize() + " bytes, was: " + handle.length);
        }
        this.hitHandles.add(handle.clone());
        return this;
    }

    public DirtRtSbtBuilder addHit(ByteBuffer src, int srcOffset) {
        Objects.requireNonNull(src, "src must not be null");
        int handleSize = layout.handleSize();
        if (srcOffset < 0 || srcOffset + handleSize > src.capacity()) {
            throw new IndexOutOfBoundsException("Invalid srcOffset " + srcOffset + " for handle size " + handleSize + " in capacity " + src.capacity());
        }
        byte[] handle = new byte[handleSize];
        int originalPos = src.position();
        src.position(srcOffset);
        src.get(handle, 0, handleSize);
        src.position(originalPos);
        return addHit(handle);
    }

    public DirtRtSbtBuilder addCallable(byte[] handle) {
        Objects.requireNonNull(handle, "handle must not be null");
        if (handle.length != layout.handleSize()) {
            throw new IllegalArgumentException("handle size must be " + layout.handleSize() + " bytes, was: " + handle.length);
        }
        this.callableHandles.add(handle.clone());
        return this;
    }

    public DirtRtSbtBuilder addCallable(ByteBuffer src, int srcOffset) {
        Objects.requireNonNull(src, "src must not be null");
        int handleSize = layout.handleSize();
        if (srcOffset < 0 || srcOffset + handleSize > src.capacity()) {
            throw new IndexOutOfBoundsException("Invalid srcOffset " + srcOffset + " for handle size " + handleSize + " in capacity " + src.capacity());
        }
        byte[] handle = new byte[handleSize];
        int originalPos = src.position();
        src.position(srcOffset);
        src.get(handle, 0, handleSize);
        src.position(originalPos);
        return addCallable(handle);
    }

    public DirtRtSbtBuilder clear() {
        this.raygenHandle = null;
        this.missHandles.clear();
        this.hitHandles.clear();
        this.callableHandles.clear();
        this.deviceAddress = 0L;
        this.rayPass = null;
        return this;
    }

    public int raygenCount() {
        return raygenHandle != null ? 1 : 0;
    }

    public int missCount() {
        return missHandles.size();
    }

    public int hitCount() {
        return hitHandles.size();
    }

    public int callableCount() {
        return callableHandles.size();
    }

    public int raygenSize() {
        return raygenCount() * layout.handleSizeAligned();
    }

    public int missSize() {
        return missCount() * layout.handleSizeAligned();
    }

    public int hitSize() {
        return hitCount() * layout.handleSizeAligned();
    }

    public int callableSize() {
        return callableCount() * layout.handleSizeAligned();
    }

    public int raygenOffset() {
        return 0;
    }

    public int missOffset() {
        return layout.regionBaseAligned(raygenOffset() + raygenSize());
    }

    public int hitOffset() {
        return layout.regionBaseAligned(missOffset() + missSize());
    }

    public int callableOffset() {
        return callableCount() > 0 ? layout.regionBaseAligned(hitOffset() + hitSize()) : 0;
    }

    public int totalSizeBytes() {
        int endOffset = hitOffset() + hitSize();
        if (callableCount() > 0) {
            endOffset = callableOffset() + callableSize();
        }
        return layout.regionBaseAligned(endOffset);
    }

    public PassSbt build() {
        return buildWithOffset(0, this.deviceAddress);
    }

    public PassSbt build(long bufferDeviceAddress) {
        return buildWithOffset(0, bufferDeviceAddress);
    }

    public PassSbt buildWithOffset(int bufferStartOffset, long bufferDeviceAddress) {
        if (bufferStartOffset < 0) {
            throw new IllegalArgumentException("bufferStartOffset must not be negative: " + bufferStartOffset);
        }
        if (bufferStartOffset % layout.baseAlignment() != 0) {
            throw new IllegalArgumentException("bufferStartOffset must be aligned to " + layout.baseAlignment() + ": " + bufferStartOffset);
        }
        if (bufferDeviceAddress < 0) {
            throw new IllegalArgumentException("bufferDeviceAddress must not be negative: " + bufferDeviceAddress);
        }
        if (bufferDeviceAddress > 0 && bufferDeviceAddress % layout.baseAlignment() != 0) {
            throw new IllegalArgumentException("bufferDeviceAddress must be aligned to " + layout.baseAlignment() + ": " + bufferDeviceAddress);
        }

        // Raygen: exactly 1 entry per pass, stride == size
        int rgenOffset = bufferStartOffset + raygenOffset();
        int rgenSize = raygenSize();
        long rgenAddress = (bufferDeviceAddress > 0 && rgenSize > 0) ? (bufferDeviceAddress + raygenOffset()) : 0L;
        long rgenStride = rgenSize; // VUID-vkCmdTraceRaysKHR-size-04023: stride must equal size
        SbtRegion raygenRegion = rgenSize > 0
                ? new SbtRegion(rgenAddress, rgenStride, rgenSize, rgenOffset)
                : SbtRegion.EMPTY;

        // Miss region
        int mOffset = bufferStartOffset + missOffset();
        int mSize = missSize();
        long mStride = mSize > 0 ? layout.handleSizeAligned() : 0L;
        long mAddress = (bufferDeviceAddress > 0 && mSize > 0) ? (bufferDeviceAddress + missOffset()) : 0L;
        SbtRegion missRegion = mSize > 0
                ? new SbtRegion(mAddress, mStride, mSize, mOffset)
                : SbtRegion.EMPTY;

        // Hit region
        int hOffset = bufferStartOffset + hitOffset();
        int hSize = hitSize();
        long hStride = hSize > 0 ? layout.handleSizeAligned() : 0L;
        long hAddress = (bufferDeviceAddress > 0 && hSize > 0) ? (bufferDeviceAddress + hitOffset()) : 0L;
        SbtRegion hitRegion = hSize > 0
                ? new SbtRegion(hAddress, hStride, hSize, hOffset)
                : SbtRegion.EMPTY;

        // Callable region (empty by default for Dirt RT)
        int cSize = callableSize();
        SbtRegion callableRegion;
        if (cSize > 0) {
            int cOffset = bufferStartOffset + callableOffset();
            long cStride = layout.handleSizeAligned();
            long cAddress = bufferDeviceAddress > 0 ? (bufferDeviceAddress + callableOffset()) : 0L;
            callableRegion = new SbtRegion(cAddress, cStride, cSize, cOffset);
        } else {
            callableRegion = SbtRegion.EMPTY;
        }

        return new PassSbt(rayPass, bufferStartOffset, totalSizeBytes(), raygenRegion, missRegion, hitRegion, callableRegion);
    }

    /**
     * Serializes shader group handles into the specified target buffer at {@code targetOffset}.
     */
    public void writeTo(ByteBuffer target, int targetOffset) {
        Objects.requireNonNull(target, "target buffer must not be null");
        int handleSize = layout.handleSize();
        int originalPos = target.position();

        try {
            // 1. Raygen
            if (raygenHandle != null) {
                target.position(targetOffset + raygenOffset());
                target.put(raygenHandle, 0, handleSize);
            }

            // 2. Miss
            if (!missHandles.isEmpty()) {
                int mBase = targetOffset + missOffset();
                int stride = layout.handleSizeAligned();
                for (int i = 0; i < missHandles.size(); i++) {
                    target.position(mBase + i * stride);
                    target.put(missHandles.get(i), 0, handleSize);
                }
            }

            // 3. Hit
            if (!hitHandles.isEmpty()) {
                int hBase = targetOffset + hitOffset();
                int stride = layout.handleSizeAligned();
                for (int i = 0; i < hitHandles.size(); i++) {
                    target.position(hBase + i * stride);
                    target.put(hitHandles.get(i), 0, handleSize);
                }
            }

            // 4. Callable
            if (!callableHandles.isEmpty()) {
                int cBase = targetOffset + callableOffset();
                int stride = layout.handleSizeAligned();
                for (int i = 0; i < callableHandles.size(); i++) {
                    target.position(cBase + i * stride);
                    target.put(callableHandles.get(i), 0, handleSize);
                }
            }
        } finally {
            target.position(originalPos);
        }
    }

    /**
     * Allocates a native direct ByteBuffer and serializes this SBT's handles into it.
     */
    public ByteBuffer buildBuffer() {
        int totalBytes = totalSizeBytes();
        ByteBuffer buffer = ByteBuffer.allocateDirect(totalBytes).order(ByteOrder.nativeOrder());
        writeTo(buffer, 0);
        buffer.rewind();
        return buffer;
    }

    /**
     * Combines multiple pass builders into a single contiguous multi-pass SBT buffer.
     * Each pass is placed at a {@code shaderGroupBaseAlignment} boundary.
     */
    public static MultiPassSbt combine(
            long bufferDeviceAddress,
            DirtRtPipelineConfig.SbtLayout layout,
            List<DirtRtSbtBuilder> passBuilders
    ) {
        Objects.requireNonNull(layout, "layout must not be null");
        Objects.requireNonNull(passBuilders, "passBuilders must not be null");
        if (bufferDeviceAddress < 0) {
            throw new IllegalArgumentException("bufferDeviceAddress must not be negative: " + bufferDeviceAddress);
        }
        if (bufferDeviceAddress > 0 && bufferDeviceAddress % layout.baseAlignment() != 0) {
            throw new IllegalArgumentException("bufferDeviceAddress must be aligned to " + layout.baseAlignment() + ": " + bufferDeviceAddress);
        }

        int currentOffset = 0;
        for (DirtRtSbtBuilder builder : passBuilders) {
            currentOffset = layout.regionBaseAligned(currentOffset + builder.totalSizeBytes());
        }
        int totalBufferBytes = currentOffset;

        ByteBuffer buffer = ByteBuffer.allocateDirect(totalBufferBytes).order(ByteOrder.nativeOrder());

        List<PassSbt> passes = new ArrayList<>(passBuilders.size());
        currentOffset = 0;
        for (DirtRtSbtBuilder builder : passBuilders) {
            long passDeviceAddress = bufferDeviceAddress > 0 ? (bufferDeviceAddress + currentOffset) : 0L;
            PassSbt passSbt = builder.buildWithOffset(currentOffset, passDeviceAddress);
            passes.add(passSbt);
            builder.writeTo(buffer, currentOffset);
            currentOffset = layout.regionBaseAligned(currentOffset + builder.totalSizeBytes());
        }

        buffer.rewind();
        return new MultiPassSbt(Collections.unmodifiableList(passes), totalBufferBytes, bufferDeviceAddress, buffer);
    }

    /**
     * Constructs a 3-pass SBT for Dirt RT (diffuse ray0, reflection ray1, refraction ray2)
     * from a contiguous buffer of 9 shader group handles.
     *
     * @param bufferDeviceAddress Base GPU device address of the SBT buffer.
     * @param layout              SBT layout parameters.
     * @param handles             Buffer containing at least 9 group handles (9 * handleSize bytes).
     * @param stageOrder          If true, handles are arranged by stage [rgen0..2, miss0..2, hit0..2];
     *                            if false, by pass [pass0(rgen,miss,hit), pass1, pass2].
     */
    public static MultiPassSbt createThreePassSbt(
            long bufferDeviceAddress,
            DirtRtPipelineConfig.SbtLayout layout,
            ByteBuffer handles,
            boolean stageOrder
    ) {
        Objects.requireNonNull(layout, "layout must not be null");
        Objects.requireNonNull(handles, "handles must not be null");
        int handleSize = layout.handleSize();
        if (handles.remaining() < 9 * handleSize) {
            throw new IllegalArgumentException("Handles buffer must contain at least 9 handles ("
                    + (9 * handleSize) + " bytes), had: " + handles.remaining());
        }

        DirtRtPipelineConfig.RayPass[] passes = DirtRtPipelineConfig.RayPass.values();
        List<DirtRtSbtBuilder> builders = new ArrayList<>(3);

        int basePos = handles.position();
        for (int p = 0; p < 3; p++) {
            DirtRtSbtBuilder b = new DirtRtSbtBuilder(layout).setRayPass(passes[p]);
            int rgenIdx = stageOrder ? p : (p * 3);
            int missIdx = stageOrder ? (3 + p) : (p * 3 + 1);
            int hitIdx  = stageOrder ? (6 + p) : (p * 3 + 2);

            b.setRaygen(handles, basePos + rgenIdx * handleSize);
            b.addMiss(handles, basePos + missIdx * handleSize);
            b.addHit(handles, basePos + hitIdx * handleSize);
            builders.add(b);
        }

        return combine(bufferDeviceAddress, layout, builders);
    }

    public static MultiPassSbt createThreePassSbt(
            long bufferDeviceAddress,
            DirtRtPipelineConfig.SbtLayout layout,
            ByteBuffer handles
    ) {
        return createThreePassSbt(bufferDeviceAddress, layout, handles, false);
    }

    public static MultiPassSbt createThreePassSbt(
            long bufferDeviceAddress,
            ByteBuffer handles
    ) {
        return createThreePassSbt(bufferDeviceAddress, new DirtRtPipelineConfig.SbtLayout(), handles, false);
    }
}
