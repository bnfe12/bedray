package io.github.andrexarlay.vulkancompat.rt.poc;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.KHRAccelerationStructure;
import org.lwjgl.vulkan.KHRBufferDeviceAddress;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkAccelerationStructureBuildGeometryInfoKHR;
import org.lwjgl.vulkan.VkAccelerationStructureBuildRangeInfoKHR;
import org.lwjgl.vulkan.VkAccelerationStructureBuildSizesInfoKHR;
import org.lwjgl.vulkan.VkAccelerationStructureCreateInfoKHR;
import org.lwjgl.vulkan.VkAccelerationStructureDeviceAddressInfoKHR;
import org.lwjgl.vulkan.VkAccelerationStructureGeometryKHR;
import org.lwjgl.vulkan.VkAccelerationStructureGeometryInstancesDataKHR;
import org.lwjgl.vulkan.VkAccelerationStructureGeometryTrianglesDataKHR;
import org.lwjgl.vulkan.VkAccelerationStructureInstanceKHR;
import org.lwjgl.vulkan.VkBufferCreateInfo;
import org.lwjgl.vulkan.VkBufferDeviceAddressInfo;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkMemoryAllocateFlagsInfo;
import org.lwjgl.vulkan.VkMemoryAllocateInfo;
import org.lwjgl.vulkan.VkMemoryBarrier;
import org.lwjgl.vulkan.VkMemoryRequirements;
import org.lwjgl.vulkan.VkPhysicalDeviceMemoryProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.Objects;

/**
 * Autonomous builder for Vulkan Hardware Acceleration Structures (PoC).
 * <p>
 * Allocates and builds:
 * <ul>
 *   <li>One Bottom-Level Acceleration Structure (BLAS) containing 1 isolated test triangle:
 *       {@code {-0.5f, -0.5f, -2.0f}}, {@code {0.5f, -0.5f, -2.0f}}, {@code {0.0f, 0.5f, -2.0f}}.</li>
 *   <li>One Top-Level Acceleration Structure (TLAS) referencing the BLAS instance with an identity 3x4 matrix.</li>
 *   <li>Dedicated scratch buffers calculated via {@code vkGetAccelerationStructureBuildSizesKHR}.</li>
 * </ul>
 * This builder operates independently of Sodium chunk meshes.
 */
public final class SimpleAccelerationStructureBuilder implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger("Bedray/ASBuilder");

    public static final float[] TEST_TRIANGLE_VERTICES = {
        -0.5f, -0.5f, -2.0f,
         0.5f, -0.5f, -2.0f,
         0.0f,  0.5f, -2.0f
    };

    public static final int[] TEST_TRIANGLE_INDICES = {
        0, 1, 2
    };

    /**
     * Managed Vulkan GPU buffer and its device memory.
     */
    public static final class VulkanBuffer {
        private final long handle;
        private final long memory;
        private final long size;
        private final long deviceAddress;

        public VulkanBuffer(long handle, long memory, long size, long deviceAddress) {
            this.handle = handle;
            this.memory = memory;
            this.size = size;
            this.deviceAddress = deviceAddress;
        }

        public long getHandle() {
            return handle;
        }

        public long getMemory() {
            return memory;
        }

        public long getSize() {
            return size;
        }

        public long getDeviceAddress() {
            return deviceAddress;
        }

        public void destroy(VkDevice device) {
            if (handle != 0L) {
                VK10.vkDestroyBuffer(device, handle, null);
            }
            if (memory != 0L) {
                VK10.vkFreeMemory(device, memory, null);
            }
        }
    }

    private final VkDevice device;
    private final VkPhysicalDeviceMemoryProperties memProperties;

    // Buffers
    private VulkanBuffer vertexBuffer;
    private VulkanBuffer indexBuffer;
    private VulkanBuffer instanceBuffer;
    private VulkanBuffer blasBuffer;
    private VulkanBuffer tlasBuffer;
    private VulkanBuffer scratchBuffer;

    // Acceleration structure handles & device addresses
    private long blasHandle = 0L;
    private long blasDeviceAddress = 0L;
    private long tlasHandle = 0L;
    private long tlasDeviceAddress = 0L;

    // Build sizing info
    private long blasScratchSize = 0L;
    private long tlasScratchSize = 0L;

    // Heap-allocated geometries for build commands
    private VkAccelerationStructureGeometryKHR.Buffer blasGeometry;
    private VkAccelerationStructureGeometryKHR.Buffer tlasGeometry;

    private boolean initialized = false;

    public SimpleAccelerationStructureBuilder(VkDevice device) {
        this(device, true);
    }

    public SimpleAccelerationStructureBuilder(VkDevice device, boolean autoInit) {
        this.device = Objects.requireNonNull(device, "VkDevice must not be null");
        this.memProperties = VkPhysicalDeviceMemoryProperties.calloc();
        VK10.vkGetPhysicalDeviceMemoryProperties(device.getPhysicalDevice(), this.memProperties);

        if (autoInit) {
            init();
        }
    }

    /**
     * Allocates buffers and creates BLAS and TLAS handles.
     */
    public synchronized void init() {
        if (initialized) {
            return;
        }
        LOGGER.info("[SimpleAccelerationStructureBuilder] Initializing Ray Tracing Acceleration Structures...");

        createInputBuffers();
        createBlas();
        createInstanceBuffer();
        createTlas();
        createScratchBuffer();

        initialized = true;
        LOGGER.info("[SimpleAccelerationStructureBuilder] Initialized successfully. BLAS=0x{}, TLAS=0x{}",
            Long.toHexString(blasHandle), Long.toHexString(tlasHandle));
    }

    private void createInputBuffers() {
        int inputUsage = VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT
            | KHRAccelerationStructure.VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR;
        int hostMemoryProps = VK10.VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK10.VK_MEMORY_PROPERTY_HOST_COHERENT_BIT;

        // 1. Vertex Buffer
        long vertexByteSize = (long) TEST_TRIANGLE_VERTICES.length * Float.BYTES;
        this.vertexBuffer = createBuffer(vertexByteSize, inputUsage, hostMemoryProps);
        uploadFloats(this.vertexBuffer, TEST_TRIANGLE_VERTICES);

        // 2. Index Buffer
        long indexByteSize = (long) TEST_TRIANGLE_INDICES.length * Integer.BYTES;
        this.indexBuffer = createBuffer(indexByteSize, inputUsage, hostMemoryProps);
        uploadInts(this.indexBuffer, TEST_TRIANGLE_INDICES);
    }

    private void createBlas() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            this.blasGeometry = VkAccelerationStructureGeometryKHR.calloc(1);
            this.blasGeometry.get(0).sType$Default()
                .geometryType(KHRAccelerationStructure.VK_GEOMETRY_TYPE_TRIANGLES_KHR)
                .flags(KHRAccelerationStructure.VK_GEOMETRY_OPAQUE_BIT_KHR);

            VkAccelerationStructureGeometryTrianglesDataKHR triangles = this.blasGeometry.get(0).geometry().triangles();
            triangles.sType$Default()
                .vertexFormat(VK10.VK_FORMAT_R32G32B32_SFLOAT)
                .vertexStride(3 * Float.BYTES)
                .maxVertex(TEST_TRIANGLE_VERTICES.length / 3 - 1)
                .indexType(VK10.VK_INDEX_TYPE_UINT32);
            triangles.vertexData().deviceAddress(this.vertexBuffer.getDeviceAddress());
            triangles.indexData().deviceAddress(this.indexBuffer.getDeviceAddress());

            // Query BLAS build sizes
            VkAccelerationStructureBuildGeometryInfoKHR buildInfo = VkAccelerationStructureBuildGeometryInfoKHR.calloc(stack)
                .sType$Default()
                .type(KHRAccelerationStructure.VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR)
                .flags(KHRAccelerationStructure.VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR)
                .geometryCount(1)
                .pGeometries(this.blasGeometry);

            int[] maxPrimitiveCounts = new int[] { 1 };
            VkAccelerationStructureBuildSizesInfoKHR sizeInfo = VkAccelerationStructureBuildSizesInfoKHR.calloc(stack)
                .sType$Default();

            KHRAccelerationStructure.vkGetAccelerationStructureBuildSizesKHR(
                device,
                KHRAccelerationStructure.VK_ACCELERATION_STRUCTURE_BUILD_TYPE_DEVICE_KHR,
                buildInfo,
                maxPrimitiveCounts,
                sizeInfo
            );

            long blasAsSize = sizeInfo.accelerationStructureSize();
            this.blasScratchSize = sizeInfo.buildScratchSize();

            // Allocate BLAS backing buffer
            int asUsage = KHRAccelerationStructure.VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_STORAGE_BIT_KHR
                | VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT;
            this.blasBuffer = createBuffer(blasAsSize, asUsage, VK10.VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);

            // Create BLAS
            VkAccelerationStructureCreateInfoKHR createInfo = VkAccelerationStructureCreateInfoKHR.calloc(stack)
                .sType$Default()
                .buffer(this.blasBuffer.getHandle())
                .size(blasAsSize)
                .type(KHRAccelerationStructure.VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR);

            LongBuffer pBlas = stack.mallocLong(1);
            int err = KHRAccelerationStructure.vkCreateAccelerationStructureKHR(device, createInfo, null, pBlas);
            if (err != VK10.VK_SUCCESS) {
                throw new RuntimeException("vkCreateAccelerationStructureKHR for BLAS failed: " + err);
            }
            this.blasHandle = pBlas.get(0);

            // Query BLAS device address
            VkAccelerationStructureDeviceAddressInfoKHR addrInfo = VkAccelerationStructureDeviceAddressInfoKHR.calloc(stack)
                .sType$Default()
                .accelerationStructure(this.blasHandle);
            this.blasDeviceAddress = KHRAccelerationStructure.vkGetAccelerationStructureDeviceAddressKHR(device, addrInfo);
        }
    }

    private void createInstanceBuffer() {
        int inputUsage = VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT
            | KHRAccelerationStructure.VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR;
        int hostMemoryProps = VK10.VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK10.VK_MEMORY_PROPERTY_HOST_COHERENT_BIT;

        this.instanceBuffer = createBuffer(VkAccelerationStructureInstanceKHR.SIZEOF, inputUsage, hostMemoryProps);
        uploadInstance(this.instanceBuffer, this.blasDeviceAddress);
    }

    private void createTlas() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            this.tlasGeometry = VkAccelerationStructureGeometryKHR.calloc(1);
            this.tlasGeometry.get(0).sType$Default()
                .geometryType(KHRAccelerationStructure.VK_GEOMETRY_TYPE_INSTANCES_KHR)
                .flags(KHRAccelerationStructure.VK_GEOMETRY_OPAQUE_BIT_KHR);

            VkAccelerationStructureGeometryInstancesDataKHR instances = this.tlasGeometry.get(0).geometry().instances();
            instances.sType$Default()
                .arrayOfPointers(false);
            instances.data().deviceAddress(this.instanceBuffer.getDeviceAddress());

            // Query TLAS build sizes
            VkAccelerationStructureBuildGeometryInfoKHR buildInfo = VkAccelerationStructureBuildGeometryInfoKHR.calloc(stack)
                .sType$Default()
                .type(KHRAccelerationStructure.VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR)
                .flags(KHRAccelerationStructure.VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR)
                .geometryCount(1)
                .pGeometries(this.tlasGeometry);

            int[] maxInstanceCounts = new int[] { 1 };
            VkAccelerationStructureBuildSizesInfoKHR sizeInfo = VkAccelerationStructureBuildSizesInfoKHR.calloc(stack)
                .sType$Default();

            KHRAccelerationStructure.vkGetAccelerationStructureBuildSizesKHR(
                device,
                KHRAccelerationStructure.VK_ACCELERATION_STRUCTURE_BUILD_TYPE_DEVICE_KHR,
                buildInfo,
                maxInstanceCounts,
                sizeInfo
            );

            long tlasAsSize = sizeInfo.accelerationStructureSize();
            this.tlasScratchSize = sizeInfo.buildScratchSize();

            // Allocate TLAS backing buffer
            int asUsage = KHRAccelerationStructure.VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_STORAGE_BIT_KHR
                | VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT;
            this.tlasBuffer = createBuffer(tlasAsSize, asUsage, VK10.VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);

            // Create TLAS
            VkAccelerationStructureCreateInfoKHR createInfo = VkAccelerationStructureCreateInfoKHR.calloc(stack)
                .sType$Default()
                .buffer(this.tlasBuffer.getHandle())
                .size(tlasAsSize)
                .type(KHRAccelerationStructure.VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR);

            LongBuffer pTlas = stack.mallocLong(1);
            int err = KHRAccelerationStructure.vkCreateAccelerationStructureKHR(device, createInfo, null, pTlas);
            if (err != VK10.VK_SUCCESS) {
                throw new RuntimeException("vkCreateAccelerationStructureKHR for TLAS failed: " + err);
            }
            this.tlasHandle = pTlas.get(0);

            // Query TLAS device address
            VkAccelerationStructureDeviceAddressInfoKHR addrInfo = VkAccelerationStructureDeviceAddressInfoKHR.calloc(stack)
                .sType$Default()
                .accelerationStructure(this.tlasHandle);
            this.tlasDeviceAddress = KHRAccelerationStructure.vkGetAccelerationStructureDeviceAddressKHR(device, addrInfo);
        }
    }

    private void createScratchBuffer() {
        long requiredScratch = Math.max(this.blasScratchSize, this.tlasScratchSize);
        // Align scratch buffer size to 256 bytes
        requiredScratch = (requiredScratch + 255L) & ~255L;
        if (requiredScratch == 0L) {
            requiredScratch = 256L;
        }

        int scratchUsage = VK12.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT;
        this.scratchBuffer = createBuffer(requiredScratch, scratchUsage, VK10.VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
    }

    /**
     * Records the BLAS build command on the command buffer.
     */
    public void buildBlas(VkCommandBuffer cmd) {
        if (!initialized) {
            init();
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkAccelerationStructureBuildGeometryInfoKHR.Buffer buildInfos = VkAccelerationStructureBuildGeometryInfoKHR.calloc(1, stack);
            buildInfos.get(0).sType$Default()
                .type(KHRAccelerationStructure.VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR)
                .flags(KHRAccelerationStructure.VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR)
                .mode(KHRAccelerationStructure.VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR)
                .dstAccelerationStructure(this.blasHandle)
                .geometryCount(1)
                .pGeometries(this.blasGeometry);
            buildInfos.get(0).scratchData().deviceAddress(this.scratchBuffer.getDeviceAddress());

            VkAccelerationStructureBuildRangeInfoKHR.Buffer rangeInfos = VkAccelerationStructureBuildRangeInfoKHR.calloc(1, stack);
            rangeInfos.get(0)
                .primitiveCount(1)
                .primitiveOffset(0)
                .firstVertex(0)
                .transformOffset(0);

            PointerBuffer ppRanges = stack.pointers(rangeInfos.address());
            KHRAccelerationStructure.vkCmdBuildAccelerationStructuresKHR(cmd, buildInfos, ppRanges);
        }
    }

    /**
     * Inserts a memory barrier ensuring BLAS build completion before TLAS build.
     */
    public void insertBlasToTlasBarrier(VkCommandBuffer cmd) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkMemoryBarrier.Buffer barrier = VkMemoryBarrier.calloc(1, stack);
            barrier.get(0).sType$Default()
                .srcAccessMask(KHRAccelerationStructure.VK_ACCESS_ACCELERATION_STRUCTURE_WRITE_BIT_KHR)
                .dstAccessMask(KHRAccelerationStructure.VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR);

            VK10.vkCmdPipelineBarrier(
                cmd,
                KHRAccelerationStructure.VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR,
                KHRAccelerationStructure.VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR,
                0,
                barrier,
                null,
                null
            );
        }
    }

    /**
     * Records the TLAS build command on the command buffer.
     */
    public void buildTlas(VkCommandBuffer cmd) {
        if (!initialized) {
            init();
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkAccelerationStructureBuildGeometryInfoKHR.Buffer buildInfos = VkAccelerationStructureBuildGeometryInfoKHR.calloc(1, stack);
            buildInfos.get(0).sType$Default()
                .type(KHRAccelerationStructure.VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR)
                .flags(KHRAccelerationStructure.VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR)
                .mode(KHRAccelerationStructure.VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR)
                .dstAccelerationStructure(this.tlasHandle)
                .geometryCount(1)
                .pGeometries(this.tlasGeometry);
            buildInfos.get(0).scratchData().deviceAddress(this.scratchBuffer.getDeviceAddress());

            VkAccelerationStructureBuildRangeInfoKHR.Buffer rangeInfos = VkAccelerationStructureBuildRangeInfoKHR.calloc(1, stack);
            rangeInfos.get(0)
                .primitiveCount(1)
                .primitiveOffset(0)
                .firstVertex(0)
                .transformOffset(0);

            PointerBuffer ppRanges = stack.pointers(rangeInfos.address());
            KHRAccelerationStructure.vkCmdBuildAccelerationStructuresKHR(cmd, buildInfos, ppRanges);
        }
    }

    /**
     * Inserts a memory barrier ensuring TLAS build completion before ray query shader execution.
     */
    public void insertTlasToShaderBarrier(VkCommandBuffer cmd) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkMemoryBarrier.Buffer barrier = VkMemoryBarrier.calloc(1, stack);
            barrier.get(0).sType$Default()
                .srcAccessMask(KHRAccelerationStructure.VK_ACCESS_ACCELERATION_STRUCTURE_WRITE_BIT_KHR)
                // Ray queries read the TLAS with the acceleration-structure access (not SHADER_READ).
                .dstAccessMask(KHRAccelerationStructure.VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR);

            VK10.vkCmdPipelineBarrier(
                cmd,
                KHRAccelerationStructure.VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR,
                VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                0,
                barrier,
                null,
                null
            );
        }
    }

    /**
     * Records the complete build sequence (BLAS, barrier, TLAS, barrier) onto the command buffer.
     */
    public void buildAll(VkCommandBuffer cmd) {
        buildBlas(cmd);
        insertBlasToTlasBarrier(cmd);
        buildTlas(cmd);
        insertTlasToShaderBarrier(cmd);
    }

    public VulkanBuffer createBuffer(long size, int usage, int memoryProperties) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkBufferCreateInfo createInfo = VkBufferCreateInfo.calloc(stack)
                .sType$Default()
                .size(size)
                .usage(usage)
                .sharingMode(VK10.VK_SHARING_MODE_EXCLUSIVE);

            LongBuffer pBuffer = stack.mallocLong(1);
            int err = VK10.vkCreateBuffer(device, createInfo, null, pBuffer);
            if (err != VK10.VK_SUCCESS) {
                throw new RuntimeException("Failed to create Vulkan buffer (size=" + size + ", usage=" + usage + "): vkError=" + err);
            }
            long buffer = pBuffer.get(0);

            VkMemoryRequirements memReqs = VkMemoryRequirements.calloc(stack);
            VK10.vkGetBufferMemoryRequirements(device, buffer, memReqs);

            int memTypeIndex = findMemoryType(memReqs.memoryTypeBits(), memoryProperties);
            if (memTypeIndex < 0) {
                VK10.vkDestroyBuffer(device, buffer, null);
                throw new RuntimeException("No suitable memory type found for buffer requirement bits: 0x" + Integer.toHexString(memReqs.memoryTypeBits()));
            }

            VkMemoryAllocateInfo allocInfo = VkMemoryAllocateInfo.calloc(stack)
                .sType$Default()
                .allocationSize(memReqs.size())
                .memoryTypeIndex(memTypeIndex);

            if ((usage & VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT) != 0) {
                VkMemoryAllocateFlagsInfo flagsInfo = VkMemoryAllocateFlagsInfo.calloc(stack)
                    .sType$Default()
                    .flags(VK12.VK_MEMORY_ALLOCATE_DEVICE_ADDRESS_BIT);
                allocInfo.pNext(flagsInfo.address());
            }

            LongBuffer pMemory = stack.mallocLong(1);
            err = VK10.vkAllocateMemory(device, allocInfo, null, pMemory);
            if (err != VK10.VK_SUCCESS) {
                VK10.vkDestroyBuffer(device, buffer, null);
                throw new RuntimeException("Failed to allocate buffer memory (size=" + memReqs.size() + "): vkError=" + err);
            }
            long memory = pMemory.get(0);

            err = VK10.vkBindBufferMemory(device, buffer, memory, 0);
            if (err != VK10.VK_SUCCESS) {
                VK10.vkDestroyBuffer(device, buffer, null);
                VK10.vkFreeMemory(device, memory, null);
                throw new RuntimeException("Failed to bind buffer memory: vkError=" + err);
            }

            long deviceAddress = 0L;
            if ((usage & VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT) != 0) {
                deviceAddress = queryBufferDeviceAddress(buffer);
            }

            return new VulkanBuffer(buffer, memory, memReqs.size(), deviceAddress);
        }
    }

    public long queryBufferDeviceAddress(long buffer) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkBufferDeviceAddressInfo addrInfo = VkBufferDeviceAddressInfo.calloc(stack)
                .sType$Default()
                .buffer(buffer);
            if (device.getCapabilities().vkGetBufferDeviceAddress != 0L) {
                return VK12.vkGetBufferDeviceAddress(device, addrInfo);
            } else if (device.getCapabilities().vkGetBufferDeviceAddressKHR != 0L) {
                return KHRBufferDeviceAddress.vkGetBufferDeviceAddressKHR(device, addrInfo);
            } else {
                throw new UnsupportedOperationException("Device does not support vkGetBufferDeviceAddress or vkGetBufferDeviceAddressKHR");
            }
        }
    }

    public int findMemoryType(int typeFilter, int properties) {
        for (int i = 0; i < memProperties.memoryTypeCount(); i++) {
            if ((typeFilter & (1 << i)) != 0 &&
                (memProperties.memoryTypes(i).propertyFlags() & properties) == properties) {
                return i;
            }
        }
        for (int i = 0; i < memProperties.memoryTypeCount(); i++) {
            if ((typeFilter & (1 << i)) != 0) {
                return i;
            }
        }
        return -1;
    }

    private void uploadFloats(VulkanBuffer buffer, float[] data) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer pData = stack.mallocPointer(1);
            long byteSize = (long) data.length * Float.BYTES;
            int err = VK10.vkMapMemory(device, buffer.getMemory(), 0, byteSize, 0, pData);
            if (err != VK10.VK_SUCCESS) {
                throw new RuntimeException("vkMapMemory failed: " + err);
            }
            long ptr = pData.get(0);
            FloatBuffer fb = MemoryUtil.memFloatBuffer(ptr, data.length);
            fb.put(data);
            VK10.vkUnmapMemory(device, buffer.getMemory());
        }
    }

    private void uploadInts(VulkanBuffer buffer, int[] data) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer pData = stack.mallocPointer(1);
            long byteSize = (long) data.length * Integer.BYTES;
            int err = VK10.vkMapMemory(device, buffer.getMemory(), 0, byteSize, 0, pData);
            if (err != VK10.VK_SUCCESS) {
                throw new RuntimeException("vkMapMemory failed: " + err);
            }
            long ptr = pData.get(0);
            IntBuffer ib = MemoryUtil.memIntBuffer(ptr, data.length);
            ib.put(data);
            VK10.vkUnmapMemory(device, buffer.getMemory());
        }
    }

    private void uploadInstance(VulkanBuffer buffer, long blasAddress) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer pData = stack.mallocPointer(1);
            int err = VK10.vkMapMemory(device, buffer.getMemory(), 0, VkAccelerationStructureInstanceKHR.SIZEOF, 0, pData);
            if (err != VK10.VK_SUCCESS) {
                throw new RuntimeException("vkMapMemory failed for instance buffer: " + err);
            }
            long ptr = pData.get(0);
            VkAccelerationStructureInstanceKHR instance = VkAccelerationStructureInstanceKHR.create(ptr);

            // 3x4 Identity Matrix:
            // [ 1.0, 0.0, 0.0, 0.0 ]
            // [ 0.0, 1.0, 0.0, 0.0 ]
            // [ 0.0, 0.0, 1.0, 0.0 ]
            instance.transform().matrix(0, 1.0f);
            instance.transform().matrix(1, 0.0f);
            instance.transform().matrix(2, 0.0f);
            instance.transform().matrix(3, 0.0f);

            instance.transform().matrix(4, 0.0f);
            instance.transform().matrix(5, 1.0f);
            instance.transform().matrix(6, 0.0f);
            instance.transform().matrix(7, 0.0f);

            instance.transform().matrix(8, 0.0f);
            instance.transform().matrix(9, 0.0f);
            instance.transform().matrix(10, 1.0f);
            instance.transform().matrix(11, 0.0f);

            instance.instanceCustomIndex(0);
            instance.mask(0xFF);
            instance.instanceShaderBindingTableRecordOffset(0);
            instance.flags(KHRAccelerationStructure.VK_GEOMETRY_INSTANCE_TRIANGLE_FACING_CULL_DISABLE_BIT_KHR);
            instance.accelerationStructureReference(blasAddress);

            VK10.vkUnmapMemory(device, buffer.getMemory());
        }
    }

    @Override
    public synchronized void close() {
        cleanup();
    }

    public synchronized void cleanup() {
        if (!initialized) {
            return;
        }
        LOGGER.info("[SimpleAccelerationStructureBuilder] Releasing acceleration structures and GPU buffers...");

        // Previously submitted frames may still be tracing against these structures.
        VK10.vkDeviceWaitIdle(device);

        if (tlasHandle != 0L) {
            KHRAccelerationStructure.vkDestroyAccelerationStructureKHR(device, tlasHandle, null);
            tlasHandle = 0L;
        }
        if (blasHandle != 0L) {
            KHRAccelerationStructure.vkDestroyAccelerationStructureKHR(device, blasHandle, null);
            blasHandle = 0L;
        }

        if (scratchBuffer != null) {
            scratchBuffer.destroy(device);
            scratchBuffer = null;
        }
        if (tlasBuffer != null) {
            tlasBuffer.destroy(device);
            tlasBuffer = null;
        }
        if (blasBuffer != null) {
            blasBuffer.destroy(device);
            blasBuffer = null;
        }
        if (instanceBuffer != null) {
            instanceBuffer.destroy(device);
            instanceBuffer = null;
        }
        if (indexBuffer != null) {
            indexBuffer.destroy(device);
            indexBuffer = null;
        }
        if (vertexBuffer != null) {
            vertexBuffer.destroy(device);
            vertexBuffer = null;
        }

        if (blasGeometry != null) {
            blasGeometry.free();
            blasGeometry = null;
        }
        if (tlasGeometry != null) {
            tlasGeometry.free();
            tlasGeometry = null;
        }
        if (memProperties != null) {
            memProperties.free();
        }

        initialized = false;
        LOGGER.info("[SimpleAccelerationStructureBuilder] Cleanup completed.");
    }

    public VkDevice getDevice() {
        return device;
    }

    public long getBlasHandle() {
        return blasHandle;
    }

    public long getBlasDeviceAddress() {
        return blasDeviceAddress;
    }

    public long getTlasHandle() {
        return tlasHandle;
    }

    public long getTlasDeviceAddress() {
        return tlasDeviceAddress;
    }

    public VulkanBuffer getVertexBuffer() {
        return vertexBuffer;
    }

    public VulkanBuffer getIndexBuffer() {
        return indexBuffer;
    }

    public VulkanBuffer getInstanceBuffer() {
        return instanceBuffer;
    }

    public VulkanBuffer getBlasBuffer() {
        return blasBuffer;
    }

    public VulkanBuffer getTlasBuffer() {
        return tlasBuffer;
    }

    public VulkanBuffer getScratchBuffer() {
        return scratchBuffer;
    }

    public long getBlasScratchSize() {
        return blasScratchSize;
    }

    public long getTlasScratchSize() {
        return tlasScratchSize;
    }

    public boolean isInitialized() {
        return initialized;
    }
}
