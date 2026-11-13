package io.github.andrexarlay.vulkancompat.rt.world;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The low-level Vulkan recording primitives (buffer copy, pipeline barrier, triangle-geometry fill) are a focused owner
 * separate from the section/world lifecycle. RtWorld keeps thin private delegates so every internal call site is
 * unchanged; the primitives themselves live in {@link RtWorldVkPrimitives}. This pins the split so the primitives cannot
 * silently drift back into the monolith or lose their exact Vulkan calls.
 */
class RtWorldVkPrimitivesTest {
    private static String source(String file) throws Exception {
        return Files.readString(Path.of("src/main/java/io/github/andrexarlay/vulkancompat/rt/world", file))
            .replace("\r\n", "\n");
    }

    @Test
    void theOwnerCarriesTheVerbatimVulkanRecordingPrimitives() throws Exception {
        String owner = source("RtWorldVkPrimitives.java");
        assertTrue(owner.contains("VK10.vkCmdCopyBuffer(cmd, src, dst, region);"),
            "copyBuffer must record the exact buffer copy");
        assertTrue(owner.contains("VK10.vkCmdPipelineBarrier(cmd, srcStage, dstStage, 0, b, null, null);"),
            "barrier must record the exact pipeline barrier");
        assertTrue(owner.contains(".geometryType(KHRAccelerationStructure.VK_GEOMETRY_TYPE_TRIANGLES_KHR)"),
            "fillTriangleGeometry must declare triangle geometry");
        assertTrue(owner.contains(".vertexFormat(VK10.VK_FORMAT_R32G32B32_SFLOAT)"),
            "fillTriangleGeometry must keep the vertex format");
    }

    @Test
    void theFacadeDelegatesAllThreePrimitivesToTheOwner() throws Exception {
        String world = source("RtWorld.java");
        assertTrue(world.contains("RtWorldVkPrimitives.copyBuffer("), "copyBuffer call site delegates to the owner");
        assertTrue(world.contains("RtWorldVkPrimitives.barrier("), "barrier call site delegates to the owner");
        assertTrue(world.contains("RtWorldVkPrimitives.fillTriangleGeometry("),
            "fillTriangleGeometry call site delegates to the owner");
    }
}