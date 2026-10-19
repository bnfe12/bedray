package io.github.andrexarlay.vulkancompat.shaderhost.dirtrt;

import io.github.andrexarlay.vulkanbridge.api.DeviceRequirement;
import io.github.andrexarlay.vulkanbridge.api.FeatureRequest;
import io.github.andrexarlay.vulkanbridge.core.DeviceFeatureRegistryImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkPhysicalDeviceRayTracingPipelineFeaturesKHR;
import org.lwjgl.vulkan.VkStridedDeviceAddressRegionKHR;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * L2 JUnit tests for Dirt RT ray-tracing pipeline support:
 * <ul>
 *   <li>{@link DirtRtDeviceRequirements}: device extensions, features, late provider registration, property defaults.</li>
 *   <li>{@link DirtRtSbtBuilder}: SBT offset and alignment calculations (64-byte base alignment boundary for raygen/miss/hit).</li>
 *   <li>{@link VkStridedDeviceAddressRegionKHR}: stride and size rules (raygen stride == size, 64-byte alignment).</li>
 *   <li>Shader group handle packing, extraction, and 3-pass multi-pass SBT serialization.</li>
 * </ul>
 */
class DirtRtSbtTest {

    // =========================================================================
    // 1. DirtRtDeviceRequirements Tests
    // =========================================================================

    @Test
    @DisplayName("DirtRtDeviceRequirements declares required extension, features, and properties")
    void testDeviceRequirementsSpecification() {
        assertEquals("VK_KHR_ray_tracing_pipeline", DirtRtDeviceRequirements.EXTENSION_NAME);
        assertEquals(Set.of("VK_KHR_ray_tracing_pipeline"), DirtRtDeviceRequirements.DEVICE_EXTENSIONS);
        assertEquals(DirtRtDeviceRequirements.DEVICE_EXTENSIONS, DirtRtDeviceRequirements.extraDeviceExtensions());

        DeviceRequirement req = DirtRtDeviceRequirements.requirement();
        assertEquals(DirtRtDeviceRequirements.ID, req.id());
        assertEquals(DirtRtDeviceRequirements.DEVICE_EXTENSIONS, req.deviceExtensions());
        assertTrue(req.optional());
        assertTrue(req.conditionHolds());

        // Features check: rayTracingPipeline in VkPhysicalDeviceRayTracingPipelineFeaturesKHR
        List<FeatureRequest> features = req.features();
        assertEquals(1, features.size());
        assertEquals(VkPhysicalDeviceRayTracingPipelineFeaturesKHR.class.getName(), features.get(0).struct());
        assertEquals("rayTracingPipeline", features.get(0).field());

        // Default properties check
        assertEquals(32, DirtRtDeviceRequirements.DEFAULT_SHADER_GROUP_HANDLE_SIZE);
        assertEquals(32, DirtRtDeviceRequirements.DEFAULT_SHADER_GROUP_HANDLE_ALIGNMENT);
        assertEquals(64, DirtRtDeviceRequirements.DEFAULT_SHADER_GROUP_BASE_ALIGNMENT);

        DirtRtDeviceRequirements.DirtRtProperties props = DirtRtDeviceRequirements.DirtRtProperties.defaults();
        assertEquals(32, props.shaderGroupHandleSize());
        assertEquals(32, props.shaderGroupHandleAlignment());
        assertEquals(64, props.shaderGroupBaseAlignment());
        assertEquals(31, props.maxRayRecursionDepth());
        assertEquals(4096, props.maxShaderGroupStride());
        assertTrue(props.isValid());

        DirtRtPipelineConfig.SbtLayout layoutFromProps = props.toSbtLayout();
        assertEquals(32, layoutFromProps.handleSize());
        assertEquals(32, layoutFromProps.handleAlignment());
        assertEquals(64, layoutFromProps.baseAlignment());

        // Extension support helper
        assertTrue(DirtRtDeviceRequirements.isExtensionSupported(Set.of("VK_KHR_swapchain", "VK_KHR_ray_tracing_pipeline")));
        assertFalse(DirtRtDeviceRequirements.isExtensionSupported(Set.of("VK_KHR_swapchain")));
        assertFalse(DirtRtDeviceRequirements.isExtensionSupported(null));
    }

    @Test
    @DisplayName("DirtRtDeviceRequirements registers into DeviceFeatureRegistry directly and as late provider")
    void testDeviceRequirementsRegistration() {
        // Direct registration
        DeviceFeatureRegistryImpl registry = new DeviceFeatureRegistryImpl();
        DirtRtDeviceRequirements.register(registry);
        assertEquals(1, registry.snapshot().size());
        assertEquals(DirtRtDeviceRequirements.ID, registry.snapshot().get(0).id());

        // Late provider registration
        DeviceFeatureRegistryImpl lateRegistry = new DeviceFeatureRegistryImpl();
        DirtRtDeviceRequirements.registerLateProvider(lateRegistry);
        assertTrue(lateRegistry.snapshot().isEmpty(), "No requirements before running late providers");

        lateRegistry.runLateProviders();
        assertEquals(1, lateRegistry.snapshot().size());
        assertEquals(DirtRtDeviceRequirements.ID, lateRegistry.snapshot().get(0).id());
    }

    // =========================================================================
    // 2. SBT Offsets and Alignment Calculations (64B Boundary)
    // =========================================================================

    @Test
    @DisplayName("Single pass SBT offsets and alignments conform to 64-byte base alignment")
    void testSinglePassSbtOffsetsAndAlignments() {
        DirtRtPipelineConfig.SbtLayout layout = new DirtRtPipelineConfig.SbtLayout(32, 32, 64);
        DirtRtSbtBuilder builder = new DirtRtSbtBuilder(layout);

        byte[] dummy32 = new byte[32];
        builder.setRaygen(dummy32);
        builder.addMiss(dummy32);
        builder.addHit(dummy32);

        // Group counts
        assertEquals(1, builder.raygenCount());
        assertEquals(1, builder.missCount());
        assertEquals(1, builder.hitCount());
        assertEquals(0, builder.callableCount());

        // Sizes: each handle is 32B aligned
        assertEquals(32, builder.raygenSize());
        assertEquals(32, builder.missSize());
        assertEquals(32, builder.hitSize());
        assertEquals(0, builder.callableSize());

        // Offsets: each region starts on a 64-byte boundary
        assertEquals(0, builder.raygenOffset(), "Raygen starts at offset 0");
        assertEquals(64, builder.missOffset(), "Miss starts at 64 (aligned up from 0 + 32)");
        assertEquals(128, builder.hitOffset(), "Hit starts at 128 (aligned up from 64 + 32 = 96 to 128)");
        assertEquals(0, builder.callableOffset(), "Callable is empty (offset 0)");

        // All offsets must be exact multiples of baseAlignment (64)
        assertEquals(0, builder.raygenOffset() % 64);
        assertEquals(0, builder.missOffset() % 64);
        assertEquals(0, builder.hitOffset() % 64);

        // Total size: 128 + 32 = 160 aligned up to 64 = 192 bytes
        assertEquals(192, builder.totalSizeBytes());
        assertEquals(0, builder.totalSizeBytes() % 64);
    }

    @Test
    @DisplayName("SBT offsets and alignments with multiple miss and hit handles")
    void testMultipleHandlesSbtOffsetsAndAlignments() {
        DirtRtPipelineConfig.SbtLayout layout = new DirtRtPipelineConfig.SbtLayout(32, 32, 64);
        DirtRtSbtBuilder builder = new DirtRtSbtBuilder(layout);

        byte[] handle = new byte[32];
        builder.setRaygen(handle);

        // Add 3 miss shaders
        builder.addMiss(handle);
        builder.addMiss(handle);
        builder.addMiss(handle);

        // Add 2 hit groups
        builder.addHit(handle);
        builder.addHit(handle);

        assertEquals(1, builder.raygenCount());
        assertEquals(3, builder.missCount());
        assertEquals(2, builder.hitCount());

        assertEquals(32, builder.raygenSize());
        assertEquals(96, builder.missSize(), "3 miss handles * 32B = 96B");
        assertEquals(64, builder.hitSize(), "2 hit groups * 32B = 64B");

        // Raygen: offset 0, size 32 -> next base alignment = 64
        assertEquals(0, builder.raygenOffset());
        assertEquals(64, builder.missOffset());

        // Miss: offset 64, size 96 -> 64 + 96 = 160 -> next base alignment = 192
        assertEquals(192, builder.hitOffset());

        // Hit: offset 192, size 64 -> 192 + 64 = 256 -> next base alignment = 256
        assertEquals(256, builder.totalSizeBytes());

        // Multiples of 64 verification
        assertEquals(0, builder.raygenOffset() % 64);
        assertEquals(0, builder.missOffset() % 64);
        assertEquals(0, builder.hitOffset() % 64);
        assertEquals(0, builder.totalSizeBytes() % 64);
    }

    @Test
    @DisplayName("Three ray passes (ray0, ray1, ray2) offsets and alignments across entire buffer")
    void testThreePassOffsetsAndAlignments() {
        DirtRtPipelineConfig.SbtLayout layout = new DirtRtPipelineConfig.SbtLayout(32, 32, 64);
        byte[] dummy = new byte[32];

        DirtRtSbtBuilder pass0 = new DirtRtSbtBuilder(layout).setRayPass(DirtRtPipelineConfig.RayPass.DIFFUSE)
                .setRaygen(dummy).addMiss(dummy).addHit(dummy);
        DirtRtSbtBuilder pass1 = new DirtRtSbtBuilder(layout).setRayPass(DirtRtPipelineConfig.RayPass.REFLECTION)
                .setRaygen(dummy).addMiss(dummy).addHit(dummy);
        DirtRtSbtBuilder pass2 = new DirtRtSbtBuilder(layout).setRayPass(DirtRtPipelineConfig.RayPass.REFRACTION)
                .setRaygen(dummy).addMiss(dummy).addHit(dummy);

        long baseAddress = 0x10000000L; // 64-byte aligned
        DirtRtSbtBuilder.MultiPassSbt multi = DirtRtSbtBuilder.combine(baseAddress, layout, List.of(pass0, pass1, pass2));

        assertEquals(3, multi.passCount());
        assertEquals(576, multi.totalSizeBytes(), "3 passes * 192B = 576B");
        assertEquals(0, multi.totalSizeBytes() % 64);

        // Pass 0 (diffuse / ray0)
        DirtRtSbtBuilder.PassSbt p0 = multi.pass(DirtRtPipelineConfig.RayPass.DIFFUSE);
        assertEquals(0, p0.bufferOffset());
        assertEquals(192, p0.totalSizeBytes());
        assertEquals(0, p0.raygen().bufferOffset());
        assertEquals(64, p0.miss().bufferOffset());
        assertEquals(128, p0.hit().bufferOffset());

        // Pass 1 (reflection / ray1)
        DirtRtSbtBuilder.PassSbt p1 = multi.pass(DirtRtPipelineConfig.RayPass.REFLECTION);
        assertEquals(192, p1.bufferOffset());
        assertEquals(192, p1.totalSizeBytes());
        assertEquals(192, p1.raygen().bufferOffset());
        assertEquals(256, p1.miss().bufferOffset());
        assertEquals(320, p1.hit().bufferOffset());

        // Pass 2 (refraction / ray2)
        DirtRtSbtBuilder.PassSbt p2 = multi.pass(DirtRtPipelineConfig.RayPass.REFRACTION);
        assertEquals(384, p2.bufferOffset());
        assertEquals(192, p2.totalSizeBytes());
        assertEquals(384, p2.raygen().bufferOffset());
        assertEquals(448, p2.miss().bufferOffset());
        assertEquals(512, p2.hit().bufferOffset());

        // All 9 region offsets must be on 64-byte boundaries
        int[] allOffsets = {
                p0.raygen().bufferOffset(), p0.miss().bufferOffset(), p0.hit().bufferOffset(),
                p1.raygen().bufferOffset(), p1.miss().bufferOffset(), p1.hit().bufferOffset(),
                p2.raygen().bufferOffset(), p2.miss().bufferOffset(), p2.hit().bufferOffset()
        };
        for (int offset : allOffsets) {
            assertEquals(0, offset % 64, "Offset " + offset + " must be a multiple of 64");
        }
    }

    // =========================================================================
    // 3. VkStridedDeviceAddressRegionKHR Structure Verification
    // =========================================================================

    @Test
    @DisplayName("VkStridedDeviceAddressRegionKHR deviceAddress, stride, and size values satisfy Vulkan VUIDs")
    void testVkStridedDeviceAddressRegionStructure() {
        DirtRtPipelineConfig.SbtLayout layout = new DirtRtPipelineConfig.SbtLayout(32, 32, 64);
        DirtRtSbtBuilder builder = new DirtRtSbtBuilder(layout);

        byte[] handle = new byte[32];
        builder.setRaygen(handle);
        builder.addMiss(handle);
        builder.addHit(handle);

        long gpuAddress = 0x20000000L; // 64-byte aligned
        DirtRtSbtBuilder.PassSbt passSbt = builder.build(gpuAddress);

        // 1. Raygen Region:
        // VUID-vkCmdTraceRaysKHR-size-04023: stride must equal size
        DirtRtSbtBuilder.SbtRegion rgen = passSbt.raygen();
        assertEquals(0x20000000L, rgen.deviceAddress());
        assertEquals(32, rgen.size());
        assertEquals(32, rgen.stride(), "Raygen stride must equal size");
        assertEquals(0, rgen.deviceAddress() % 64, "Raygen deviceAddress must be aligned to 64B");

        // 2. Miss Region:
        DirtRtSbtBuilder.SbtRegion miss = passSbt.miss();
        assertEquals(0x20000000L + 64, miss.deviceAddress());
        assertEquals(32, miss.size());
        assertEquals(32, miss.stride());
        assertEquals(0, miss.deviceAddress() % 64, "Miss deviceAddress must be aligned to 64B");
        assertEquals(0, miss.stride() % 32, "Miss stride must be aligned to handleAlignment (32B)");

        // 3. Hit Region:
        DirtRtSbtBuilder.SbtRegion hit = passSbt.hit();
        assertEquals(0x20000000L + 128, hit.deviceAddress());
        assertEquals(32, hit.size());
        assertEquals(32, hit.stride());
        assertEquals(0, hit.deviceAddress() % 64, "Hit deviceAddress must be aligned to 64B");
        assertEquals(0, hit.stride() % 32, "Hit stride must be aligned to handleAlignment (32B)");

        // 4. Callable Region: empty
        DirtRtSbtBuilder.SbtRegion callable = passSbt.callable();
        assertEquals(0L, callable.deviceAddress());
        assertEquals(0L, callable.stride());
        assertEquals(0L, callable.size());
        assertTrue(callable.isEmpty());

        // 5. Test allocation and population into native LWJGL structs
        try (MemoryStack stack = MemoryStack.stackPush()) {
            DirtRtSbtBuilder.VkRegions vk = passSbt.allocateVkRegions(stack);

            assertNotNull(vk.raygen());
            assertEquals(0x20000000L, vk.raygen().deviceAddress());
            assertEquals(32, vk.raygen().stride());
            assertEquals(32, vk.raygen().size());

            assertNotNull(vk.miss());
            assertEquals(0x20000000L + 64, vk.miss().deviceAddress());
            assertEquals(32, vk.miss().stride());
            assertEquals(32, vk.miss().size());

            assertNotNull(vk.hit());
            assertEquals(0x20000000L + 128, vk.hit().deviceAddress());
            assertEquals(32, vk.hit().stride());
            assertEquals(32, vk.hit().size());

            assertNotNull(vk.callable());
            assertEquals(0L, vk.callable().deviceAddress());
            assertEquals(0L, vk.callable().stride());
            assertEquals(0L, vk.callable().size());
        }

        // 6. Test fill() into pre-allocated native structs
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkStridedDeviceAddressRegionKHR rgenStruct = VkStridedDeviceAddressRegionKHR.calloc(stack);
            VkStridedDeviceAddressRegionKHR missStruct = VkStridedDeviceAddressRegionKHR.calloc(stack);
            VkStridedDeviceAddressRegionKHR hitStruct = VkStridedDeviceAddressRegionKHR.calloc(stack);
            VkStridedDeviceAddressRegionKHR callStruct = VkStridedDeviceAddressRegionKHR.calloc(stack);

            passSbt.fillVkRegions(rgenStruct, missStruct, hitStruct, callStruct);

            assertEquals(0x20000000L, rgenStruct.deviceAddress());
            assertEquals(32, rgenStruct.stride());
            assertEquals(32, rgenStruct.size());

            assertEquals(0x20000040L, missStruct.deviceAddress());
            assertEquals(32, missStruct.stride());
            assertEquals(32, missStruct.size());

            assertEquals(0x20000080L, hitStruct.deviceAddress());
            assertEquals(32, hitStruct.stride());
            assertEquals(32, hitStruct.size());

            assertEquals(0L, callStruct.deviceAddress());
            assertEquals(0L, callStruct.stride());
            assertEquals(0L, callStruct.size());
        }
    }

    // =========================================================================
    // 4. Shader Group Handle Packing and Serialization Tests
    // =========================================================================

    @Test
    @DisplayName("Shader group handle packing places handles and zero-padding at exact byte offsets")
    void testShaderGroupHandlePacking() {
        DirtRtPipelineConfig.SbtLayout layout = new DirtRtPipelineConfig.SbtLayout(32, 32, 64);
        DirtRtSbtBuilder builder = new DirtRtSbtBuilder(layout);

        byte[] rgenHandle = new byte[32];
        Arrays.fill(rgenHandle, (byte) 0xAA);

        byte[] missHandle = new byte[32];
        Arrays.fill(missHandle, (byte) 0xBB);

        byte[] hitHandle = new byte[32];
        Arrays.fill(hitHandle, (byte) 0xCC);

        builder.setRaygen(rgenHandle);
        builder.addMiss(missHandle);
        builder.addHit(hitHandle);

        ByteBuffer sbtBuffer = builder.buildBuffer();
        assertEquals(192, sbtBuffer.capacity());

        // Verify Raygen at [0..31]
        for (int i = 0; i < 32; i++) {
            assertEquals((byte) 0xAA, sbtBuffer.get(i), "Raygen byte at " + i);
        }
        // Verify padding at [32..63]
        for (int i = 32; i < 64; i++) {
            assertEquals((byte) 0x00, sbtBuffer.get(i), "Padding byte at " + i);
        }
        // Verify Miss at [64..95]
        for (int i = 64; i < 96; i++) {
            assertEquals((byte) 0xBB, sbtBuffer.get(i), "Miss byte at " + i);
        }
        // Verify padding at [96..127]
        for (int i = 96; i < 128; i++) {
            assertEquals((byte) 0x00, sbtBuffer.get(i), "Padding byte at " + i);
        }
        // Verify Hit at [128..159]
        for (int i = 128; i < 160; i++) {
            assertEquals((byte) 0xCC, sbtBuffer.get(i), "Hit byte at " + i);
        }
        // Verify padding at [160..191]
        for (int i = 160; i < 192; i++) {
            assertEquals((byte) 0x00, sbtBuffer.get(i), "Padding byte at " + i);
        }
    }

    @Test
    @DisplayName("Handle extraction from contiguous ByteBuffer source (vkGetRayTracingShaderGroupHandlesKHR simulation)")
    void testHandleExtractionFromByteBuffer() {
        DirtRtPipelineConfig.SbtLayout layout = new DirtRtPipelineConfig.SbtLayout(32, 32, 64);
        int totalGroups = 9;
        ByteBuffer sourceHandles = ByteBuffer.allocateDirect(totalGroups * 32).order(ByteOrder.nativeOrder());

        // Populate 9 distinct handles: 0x10, 0x11, ..., 0x18
        for (int g = 0; g < totalGroups; g++) {
            byte val = (byte) (0x10 + g);
            for (int b = 0; b < 32; b++) {
                sourceHandles.put((byte) val);
            }
        }
        sourceHandles.flip();

        // Pass order: groups [0,1,2]=diffuse, [3,4,5]=reflection, [6,7,8]=refraction
        DirtRtSbtBuilder.MultiPassSbt multiPass = DirtRtSbtBuilder.createThreePassSbt(
                0x30000000L, layout, sourceHandles, false);

        assertEquals(3, multiPass.passCount());
        assertEquals(576, multiPass.totalSizeBytes());
        ByteBuffer outBuffer = multiPass.sbtBuffer();
        assertEquals(576, outBuffer.capacity());

        // Check Diffuse (ray0): rgen=0x10, miss=0x11, hit=0x12
        DirtRtSbtBuilder.PassSbt p0 = multiPass.pass(DirtRtPipelineConfig.RayPass.DIFFUSE);
        assertEquals(0x30000000L, p0.raygen().deviceAddress());
        assertEquals(0x30000040L, p0.miss().deviceAddress());
        assertEquals(0x30000080L, p0.hit().deviceAddress());
        assertEquals((byte) 0x10, outBuffer.get(0));
        assertEquals((byte) 0x11, outBuffer.get(64));
        assertEquals((byte) 0x12, outBuffer.get(128));

        // Check Reflection (ray1): rgen=0x13, miss=0x14, hit=0x15
        DirtRtSbtBuilder.PassSbt p1 = multiPass.pass(DirtRtPipelineConfig.RayPass.REFLECTION);
        assertEquals(0x30000000L + 192, p1.raygen().deviceAddress());
        assertEquals(0x30000000L + 256, p1.miss().deviceAddress());
        assertEquals(0x30000000L + 320, p1.hit().deviceAddress());
        assertEquals((byte) 0x13, outBuffer.get(192));
        assertEquals((byte) 0x14, outBuffer.get(256));
        assertEquals((byte) 0x15, outBuffer.get(320));

        // Check Refraction (ray2): rgen=0x16, miss=0x17, hit=0x18
        DirtRtSbtBuilder.PassSbt p2 = multiPass.pass(DirtRtPipelineConfig.RayPass.REFRACTION);
        assertEquals(0x30000000L + 384, p2.raygen().deviceAddress());
        assertEquals(0x30000000L + 448, p2.miss().deviceAddress());
        assertEquals(0x30000000L + 512, p2.hit().deviceAddress());
        assertEquals((byte) 0x16, outBuffer.get(384));
        assertEquals((byte) 0x17, outBuffer.get(448));
        assertEquals((byte) 0x18, outBuffer.get(512));

        // Test stage order: [rgen0, rgen1, rgen2, miss0, miss1, miss2, hit0, hit1, hit2]
        sourceHandles.rewind();
        DirtRtSbtBuilder.MultiPassSbt stageMulti = DirtRtSbtBuilder.createThreePassSbt(
                0x30000000L, layout, sourceHandles, true);
        ByteBuffer stageOut = stageMulti.sbtBuffer();

        // Pass 0: rgen=g0 (0x10), miss=g3 (0x13), hit=g6 (0x16)
        assertEquals((byte) 0x10, stageOut.get(0));
        assertEquals((byte) 0x13, stageOut.get(64));
        assertEquals((byte) 0x16, stageOut.get(128));

        // Pass 1: rgen=g1 (0x11), miss=g4 (0x14), hit=g7 (0x17)
        assertEquals((byte) 0x11, stageOut.get(192));
        assertEquals((byte) 0x14, stageOut.get(256));
        assertEquals((byte) 0x17, stageOut.get(320));

        // Pass 2: rgen=g2 (0x12), miss=g5 (0x15), hit=g8 (0x18)
        assertEquals((byte) 0x12, stageOut.get(384));
        assertEquals((byte) 0x15, stageOut.get(448));
        assertEquals((byte) 0x18, stageOut.get(512));
    }

    // =========================================================================
    // 5. Validation and Error Handling Tests
    // =========================================================================

    @Test
    @DisplayName("Validation rejects unaligned addresses, negative values, and mismatched handle sizes")
    void testValidationAndErrors() {
        DirtRtPipelineConfig.SbtLayout layout = new DirtRtPipelineConfig.SbtLayout(32, 32, 64);
        DirtRtSbtBuilder builder = new DirtRtSbtBuilder(layout);

        // Reject unaligned deviceAddress (0x1001 is not a multiple of 64)
        assertThrows(IllegalArgumentException.class, () -> builder.setDeviceAddress(0x1001L));
        assertThrows(IllegalArgumentException.class, () -> builder.build(0x1001L));
        assertThrows(IllegalArgumentException.class, () -> builder.setDeviceAddress(-1L));

        // Reject unaligned bufferStartOffset
        assertThrows(IllegalArgumentException.class, () -> builder.buildWithOffset(32, 0L));
        assertThrows(IllegalArgumentException.class, () -> builder.buildWithOffset(-64, 0L));

        // Reject wrong handle length
        assertThrows(IllegalArgumentException.class, () -> builder.setRaygen(new byte[16]));
        assertThrows(IllegalArgumentException.class, () -> builder.setRaygen(new byte[64]));
        assertThrows(IllegalArgumentException.class, () -> builder.addMiss(new byte[30]));
        assertThrows(IllegalArgumentException.class, () -> builder.addHit(new byte[0]));

        // Reject buffer out-of-bounds on extraction
        ByteBuffer small = ByteBuffer.allocate(64);
        assertThrows(IndexOutOfBoundsException.class, () -> builder.setRaygen(small, 40));

        // Reject insufficient handles buffer for 3-pass creation
        ByteBuffer insufficient = ByteBuffer.allocate(8 * 32); // only 8 handles
        assertThrows(IllegalArgumentException.class, () -> DirtRtSbtBuilder.createThreePassSbt(0L, layout, insufficient));
    }
}
