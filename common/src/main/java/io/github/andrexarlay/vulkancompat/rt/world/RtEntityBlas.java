package io.github.andrexarlay.vulkancompat.rt.world;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRAccelerationStructure;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkAccelerationStructureBuildGeometryInfoKHR;
import org.lwjgl.vulkan.VkAccelerationStructureBuildRangeInfoKHR;
import org.lwjgl.vulkan.VkAccelerationStructureBuildSizesInfoKHR;
import org.lwjgl.vulkan.VkAccelerationStructureCreateInfoKHR;
import org.lwjgl.vulkan.VkAccelerationStructureDeviceAddressInfoKHR;
import org.lwjgl.vulkan.VkAccelerationStructureGeometryKHR;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDevice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.LongBuffer;
import java.util.Locale;

/**
 * Manages the dynamic BLAS for entities, built every frame with PREFER_FAST_BUILD_BIT_KHR.
 */
public final class RtEntityBlas {
    private static final Logger LOGGER = LoggerFactory.getLogger("VKB-RT-ENTITY-BLAS");

    private final VkDevice device;
    private final RtMemory mem;
    private RtMemory.Buf blasBuf;
    private long blasHandle = 0L;
    private long blasAddress = 0L;
    private int builtPrimitives = 0;

    public RtEntityBlas(VkDevice device, RtMemory mem) {
        this.device = device;
        this.mem = mem;
    }

    public void build(VkCommandBuffer cb, io.github.andrexarlay.vulkancompat.rt.entity.EntityGpuFrame gpuFrame, RtMemory.Buf scratchBuf, long scratchBase) {
        if (gpuFrame == null || !gpuFrame.isValid()) {
            blasAddress = 0L;
            builtPrimitives = 0;
            return;
        }
        buildInternal(cb, gpuFrame.vertexBufferAddress(), gpuFrame.indexBufferAddress(), gpuFrame.vertexCount(), gpuFrame.primitiveCount(), scratchBuf, scratchBase);
    }

    public void build(VkCommandBuffer cb, RtEntityGeometryManager geometryManager, RtMemory.Buf scratchBuf, long scratchBase) {
        int triangleCount = geometryManager.frameTriangles();
        int vertexCount = geometryManager.frameVertices();
        long vertexAddress = geometryManager.framePosAddress();
        long indexAddress = geometryManager.frameIndexAddress();
        if (triangleCount <= 0 || vertexCount <= 0 || vertexAddress == 0L || indexAddress == 0L) {
            blasAddress = blasHandle != 0L ? blasAddress : 0L;
            builtPrimitives = 0;
            return;
        }
        buildInternal(cb, vertexAddress, indexAddress, vertexCount, triangleCount, scratchBuf, scratchBase);
    }

    private void buildInternal(VkCommandBuffer cb, long vertexAddress, long indexAddress, int vertexCount, int triangleCount, RtMemory.Buf scratchBuf, long scratchBase) {
        if (vertexAddress == 0L || indexAddress == 0L || triangleCount <= 0 || vertexCount < 3) {
            blasAddress = 0L;
            builtPrimitives = 0;
            blasState = State.EMPTY;
            return;
        }

        long scratchAddress = scratchBuf.address + scratchBase;
        if (mem.scratchAlignment > 0 && (scratchAddress & (mem.scratchAlignment - 1L)) != 0L) {
            scratchAddress = FreeListAllocator.roundUp(scratchAddress, mem.scratchAlignment);
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkAccelerationStructureGeometryKHR.Buffer geometry = VkAccelerationStructureGeometryKHR.calloc(1, stack);
            VkAccelerationStructureGeometryKHR g = geometry.get(0);
            g.sType$Default();
            g.geometryType(KHRAccelerationStructure.VK_GEOMETRY_TYPE_TRIANGLES_KHR);
            g.geometry().triangles().sType$Default()
                .vertexFormat(VK10.VK_FORMAT_R32G32B32_SFLOAT)
                .vertexData(d -> d.deviceAddress(vertexAddress))
                .vertexStride(12)
                // maxVertex is the highest valid vertex index, not the number of vertices.
                .maxVertex(vertexCount - 1)
                .indexType(VK10.VK_INDEX_TYPE_UINT32)
                .indexData(d -> d.deviceAddress(indexAddress));
            // not opaque: the candidate loop alpha-tests entity/item texels (entityCandidateAccepted in rt_common.glsl)
            g.flags(KHRAccelerationStructure.VK_GEOMETRY_NO_DUPLICATE_ANY_HIT_INVOCATION_BIT_KHR);

            VkAccelerationStructureBuildGeometryInfoKHR.Buffer buildInfoBuf = VkAccelerationStructureBuildGeometryInfoKHR.calloc(1, stack);
            VkAccelerationStructureBuildGeometryInfoKHR buildInfo = buildInfoBuf.get(0);
            buildInfo.sType$Default()
                .type(KHRAccelerationStructure.VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR)
                .flags(KHRAccelerationStructure.VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_BUILD_BIT_KHR)
                .mode(KHRAccelerationStructure.VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR)
                .geometryCount(1)
                .pGeometries(geometry);

            VkAccelerationStructureBuildSizesInfoKHR sizeInfo = VkAccelerationStructureBuildSizesInfoKHR.calloc(stack).sType$Default();
            KHRAccelerationStructure.vkGetAccelerationStructureBuildSizesKHR(
                device,
                KHRAccelerationStructure.VK_ACCELERATION_STRUCTURE_BUILD_TYPE_DEVICE_KHR,
                buildInfo,
                new int[]{triangleCount},
                sizeInfo
            );

            long requiredSize = sizeInfo.accelerationStructureSize();
            long requiredScratch = sizeInfo.buildScratchSize();
            if (scratchAddress + requiredScratch > scratchBuf.address + scratchBuf.size) {
                throw new RtFailure("entity_blas_scratch", "insufficient scratch buffer space");
            }

            if (blasBuf == null || blasBuf.size < requiredSize) {
                if (blasHandle != 0L) {
                    KHRAccelerationStructure.vkDestroyAccelerationStructureKHR(device, blasHandle, null);
                    blasHandle = 0L;
                    blasAddress = 0L;
                }
                if (blasBuf != null) {
                    mem.destroy(blasBuf);
                }
                blasBuf = mem.createBuffer("entity_blas", Math.max(requiredSize, 2L << 20),
                    KHRAccelerationStructure.VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_STORAGE_BIT_KHR
                        | VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT, false);

                VkAccelerationStructureCreateInfoKHR createInfo = VkAccelerationStructureCreateInfoKHR.calloc(stack)
                    .sType$Default()
                    .buffer(blasBuf.handle)
                    .offset(0)
                    .size(blasBuf.size)
                    .type(KHRAccelerationStructure.VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR);
                LongBuffer pHandle = stack.mallocLong(1);
                RtFailure.check(KHRAccelerationStructure.vkCreateAccelerationStructureKHR(device, createInfo, null, pHandle), "create_entity_blas");
                blasHandle = pHandle.get(0);

                VkAccelerationStructureDeviceAddressInfoKHR addrInfo = VkAccelerationStructureDeviceAddressInfoKHR.calloc(stack)
                    .sType$Default()
                    .accelerationStructure(blasHandle);
                blasAddress = KHRAccelerationStructure.vkGetAccelerationStructureDeviceAddressKHR(device, addrInfo);
                if (blasAddress == 0L) {
                    throw new RtFailure("entity_as_device_address", "address=0");
                }
            }

            buildInfo.dstAccelerationStructure(blasHandle);
            long finalScratchAddress = scratchAddress;
            buildInfo.scratchData(d -> d.deviceAddress(finalScratchAddress));

            VkAccelerationStructureBuildRangeInfoKHR.Buffer rangeInfo = VkAccelerationStructureBuildRangeInfoKHR.calloc(1, stack);
            rangeInfo.get(0).primitiveCount(triangleCount).primitiveOffset(0).firstVertex(0).transformOffset(0);

            PointerBuffer pp = stack.pointers(rangeInfo.address());
            KHRAccelerationStructure.vkCmdBuildAccelerationStructuresKHR(cb, buildInfoBuf, pp);
            this.builtPrimitives = triangleCount;
            this.blasState = State.BLAS_READY;

            // The following build reuses scratch: its build-stage read/write accesses must see this build's scratch writes.
            try (MemoryStack bStack = MemoryStack.stackPush()) {
                org.lwjgl.vulkan.VkMemoryBarrier.Buffer mb = org.lwjgl.vulkan.VkMemoryBarrier.calloc(1, bStack);
                mb.get(0).sType$Default()
                    .srcAccessMask(KHRAccelerationStructure.VK_ACCESS_ACCELERATION_STRUCTURE_WRITE_BIT_KHR)
                    .dstAccessMask(KHRAccelerationStructure.VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR
                        | KHRAccelerationStructure.VK_ACCESS_ACCELERATION_STRUCTURE_WRITE_BIT_KHR);
                VK10.vkCmdPipelineBarrier(cb,
                    KHRAccelerationStructure.VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR,
                    KHRAccelerationStructure.VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR,
                    0, mb, null, null);
            }
        }
    }

    public long getBlasAddress() {
        return blasAddress;
    }

    public long getBlasHandle() {
        return blasHandle;
    }

    public int getPrimitiveCount() {
        return builtPrimitives;
    }

    // ---- Lifecycle state ----

    /** Lifecycle state of the entity BLAS/TLAS for a single frame. */
    public enum State {
        EMPTY,           // no geometry this frame (triangleCount <= 0 or vertexAddress == 0)
        WAITING_UPLOAD,  // geometry pending GPU upload (reserved for future use)
        BUILDING_BLAS,   // buildInternal in progress (not yet used; placeholder)
        BLAS_READY,      // buildInternal completed successfully
        BUILDING_TLAS,   // markTlasReady called with entityInstances > 0 (reserved)
        TLAS_READY,      // TLAS written with entity instance; entityInstances > 0
        FAILED           // an error occurred during build
    }

    private volatile State blasState = State.EMPTY;
    private long lastFrame = 0;
    private double lastBuildMs = 0.0;
    private int lastEntityInstances = 0;
    private long lastTlasGeneration = 0;
    private String lastBuildError = "none";

    /**
     * Called by RtWorld.buildTlas after writing the entity TLAS instance.
     * Transitions BLAS_READY -> TLAS_READY (if entityInstances > 0 and BLAS is valid)
     * or EMPTY (if no instances).
     */
    public synchronized void markTlasReady(long frameId, int entityInstances, long tlasGeneration) {
        this.lastFrame = frameId;
        this.lastEntityInstances = entityInstances;
        this.lastTlasGeneration = tlasGeneration;
        if (entityInstances > 0 && blasAddress != 0L && builtPrimitives > 0) {
            this.blasState = State.TLAS_READY;
        } else {
            this.blasState = State.EMPTY;
        }
    }

    /** Returns the current BLAS/TLAS lifecycle state. */
    public State getState() { return blasState; }

    public String formatStatusDiagnostics() {
        return String.format(Locale.ROOT,
            "[VK-ENTITY-AS] state=%s frame=%d primitives=%d blasAddress=%s entityInstances=%d" +
            " tlasGeneration=%d buildMs=%.3f lastError=%s",
            blasState,
            lastFrame,
            builtPrimitives,
            blasAddress == 0L ? "N/A" : String.format("0x%x", blasAddress),
            lastEntityInstances,
            lastTlasGeneration,
            lastBuildMs,
            lastBuildError
        );
    }

    public static String formatTelemetry(int captured, boolean blasBuilt, int primitives, int tlasInstances, long blasAddress) {
        return String.format(Locale.ROOT,
            "[VK-ENTITY-AS] captured=%d blasBuilt=%b primitives=%d tlasInstances=%d entityMask=0x02 blasAddress=0x%x",
            captured, blasBuilt, primitives, tlasInstances, blasAddress);
    }

    public void close() {
        if (blasHandle != 0L) {
            KHRAccelerationStructure.vkDestroyAccelerationStructureKHR(device, blasHandle, null);
            blasHandle = 0L;
            blasAddress = 0L;
        }
        if (blasBuf != null) {
            mem.destroy(blasBuf);
            blasBuf = null;
        }
        builtPrimitives = 0;
        blasState = State.EMPTY;
    }
}
