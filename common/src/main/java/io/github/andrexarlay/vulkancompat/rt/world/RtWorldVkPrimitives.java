package io.github.andrexarlay.vulkancompat.rt.world;

import io.github.andrexarlay.vulkancompat.rt.sodium.DecodedSection;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRAccelerationStructure;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkAccelerationStructureGeometryKHR;
import org.lwjgl.vulkan.VkBufferCopy;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkMemoryBarrier;

/**
 * The low-level Vulkan recording primitives shared by the section-build and TLAS paths of {@link RtWorld}: a buffer copy,
 * a pipeline barrier and the triangle-geometry fill of an acceleration-structure build. Split out so the world/section
 * lifecycle no longer carries the raw Vulkan command recording; the bodies are pure functions of their arguments with no
 * world state, and RtWorld keeps thin private delegates so every internal call site is unchanged.
 */
final class RtWorldVkPrimitives {
    private RtWorldVkPrimitives() {
    }

    static void copyBuffer(VkCommandBuffer cmd, long src, long srcOffset, long dst, long dstOffset, long size) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkBufferCopy.Buffer region = VkBufferCopy.calloc(1, stack);
            region.get(0).srcOffset(srcOffset).dstOffset(dstOffset).size(size);
            VK10.vkCmdCopyBuffer(cmd, src, dst, region);
        }
    }

    static void fillTriangleGeometry(VkAccelerationStructureGeometryKHR geom, int pass, int quadCount,
                                     long vertexAddress, long indexAddress, long opacityMicromapPNext) {
        geom.sType$Default()
            .geometryType(KHRAccelerationStructure.VK_GEOMETRY_TYPE_TRIANGLES_KHR)
            .flags(pass == DecodedSection.PASS_SOLID ? KHRAccelerationStructure.VK_GEOMETRY_OPAQUE_BIT_KHR : 0);
        var triangles = geom.geometry().triangles().sType$Default()
            .vertexFormat(VK10.VK_FORMAT_R32G32B32_SFLOAT)
            .vertexStride(12)
            .maxVertex(quadCount * 4 - 1)
            .indexType(VK10.VK_INDEX_TYPE_UINT32);
        if (opacityMicromapPNext != 0L) {
            triangles.pNext(opacityMicromapPNext);
        }
        triangles.vertexData().deviceAddress(vertexAddress);
        triangles.indexData().deviceAddress(indexAddress);
    }

    static void barrier(VkCommandBuffer cmd, int srcStage, int srcAccess, int dstStage, int dstAccess) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkMemoryBarrier.Buffer b = VkMemoryBarrier.calloc(1, stack);
            b.get(0).sType$Default().srcAccessMask(srcAccess).dstAccessMask(dstAccess);
            VK10.vkCmdPipelineBarrier(cmd, srcStage, dstStage, 0, b, null, null);
        }
    }
}