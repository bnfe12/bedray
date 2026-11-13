package io.github.andrexarlay.vulkancompat.rt.harness;

import io.github.andrexarlay.vulkancompat.rt.world.RtFrameParams;
import io.github.andrexarlay.vulkancompat.rt.world.RtInstanceWriter;
import io.github.andrexarlay.vulkancompat.rt.world.RtMemory;
import io.github.andrexarlay.vulkancompat.rt.world.RtStatsLayout;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;

import java.nio.LongBuffer;

import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT;

/**
 * Fixed synthetic RT scene for the offscreen baseline: one section of solid quads (floor + back
 * wall), a synthetic block atlas, flat material/normal atlases, a synthetic lightmap, a 512-byte
 * StatsBuf slot and a BLAS/TLAS. Camera at the origin looking down -Z. The ABI matches
 * rt_common_core.glsl exactly (Section 64 B, StatsBuf 512 B, push constants 128 B).
 */
final class RtBaselineScene implements AutoCloseable {
    private static final int QUADS = 2; // floor + back wall, geometry 0 (solid)
    private static final int VERTS = QUADS * 4;
    private static final int TRIS = QUADS * 2;
    private static final float[] IDENTITY_3X4 = {1,0,0,0, 0,1,0,0, 0,0,1,0};

    private final RtBaselineProbe probe;
    private final RtMemory memory;
    final int width, height;

    private RtMemory.Buf pos, uv, col, light, indices, instances, blasBuf, tlasBuf, scratch, stats;
    private RtMemory.Img atlas, material, normal, lightmap, depth, accum;
    private long blasHandle, tlasHandle, blasAddress;
    private VkAccelerationStructureGeometryKHR.Buffer blasGeometry, tlasGeometry;
    long tlas, outputView, atlasView, materialView, normalView, depthView, lightmapView, accumView, statsAddress;
    RtMemory.Buf table;

    RtBaselineScene(RtBaselineProbe probe) {
        this.probe = probe;
        this.memory = probe.memory();
        this.width = probe.width;
        this.height = probe.height;
        this.outputView = probe.targetView;
        build();
    }

    private void build() {
        // ---- vertex buffers (camera-relative world; camera at origin looking -Z) ----
        // floor quad y=-1, x[-3,3], z[-2,-8]; back wall quad z=-8, x[-3,3], y[-1,3].
        float[] positions = {
            -3,-1,-2,  3,-1,-2,  3,-1,-8,  -3,-1,-8,   // floor (quad 0)
            -3,-1,-8,  3,-1,-8,  3, 3,-8,  -3, 3,-8    // back wall (quad 1)
        };
        float[] uvs = {
            0,0, 1,0, 1,1, 0,1,
            0,0, 1,0, 1,1, 0,1
        };
        int[] colors = new int[VERTS];   // opaque white albedo (biome tint * AO)
        int[] lights = new int[VERTS];   // full sky light, no block light, material 0
        for (int i = 0; i < VERTS; i++) {
            colors[i] = 0xFFFFFFFF;
            lights[i] = 8 | (248 << 8);  // blockLight byte 8 (level 0), skyLight byte 248 (level 15)
        }
        int[] idx = new int[TRIS * 3];
        for (int q = 0; q < QUADS; q++) {
            int b = q * 4, o = q * 6;
            idx[o] = b; idx[o+1] = b+1; idx[o+2] = b+2;       // prim 2q
            idx[o+3] = b+2; idx[o+4] = b+3; idx[o+5] = b;     // prim 2q+1
        }
        int inputUsage = RtMemory.USAGE_AS_INPUT;
        pos = memory.createBuffer("rt_pos", positions.length * 4L, inputUsage, true);
        uv = memory.createBuffer("rt_uv", uvs.length * 4L, inputUsage, true);
        col = memory.createBuffer("rt_col", colors.length * 4L, inputUsage, true);
        light = memory.createBuffer("rt_light", lights.length * 4L, inputUsage, true);
        indices = memory.createBuffer("rt_idx", idx.length * 4L, inputUsage, true);
        MemoryUtil.memFloatBuffer(pos.mapped, positions.length).put(positions);
        MemoryUtil.memFloatBuffer(uv.mapped, uvs.length).put(uvs);
        MemoryUtil.memIntBuffer(col.mapped, colors.length).put(colors);
        MemoryUtil.memIntBuffer(light.mapped, lights.length).put(lights);
        MemoryUtil.memIntBuffer(indices.mapped, idx.length).put(idx);

        // ---- section table (one 64-byte Section, slot 0) ----
        table = memory.createBuffer("rt_sections", 64L, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, true);
        long t = table.mapped;
        MemoryUtil.memPutLong(t, pos.address);          // posAddr  @0
        MemoryUtil.memPutLong(t + 8, uv.address);       // uvAddr   @8
        MemoryUtil.memPutLong(t + 16, col.address);     // colAddr  @16
        MemoryUtil.memPutLong(t + 24, light.address);   // lightAddr@24
        MemoryUtil.memPutInt(t + 32, 0);                // quadStart.x = first quad of geom 0
        MemoryUtil.memPutInt(t + 36, 0);                // quadStart.y
        MemoryUtil.memPutInt(t + 40, 0);                // quadStart.z
        MemoryUtil.memPutInt(t + 44, 1);                // quadStart.w = geometry count
        MemoryUtil.memPutInt(t + 48, 0);                // origin.x
        MemoryUtil.memPutInt(t + 52, 0);                // origin.y
        MemoryUtil.memPutInt(t + 56, 0);                // origin.z
        MemoryUtil.memPutInt(t + 60, 0);                // origin.w = pass bits (0 = solid)

        // ---- StatsBuf slot (512 B): zero, then the fields the primary-hit shade path reads ----
        stats = memory.createBuffer("rt_stats", RtStatsLayout.SLOT_STRIDE,
            VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT, true);
        MemoryUtil.memSet(stats.mapped, 0, RtStatsLayout.SLOT_STRIDE);
        MemoryUtil.memPutFloat(stats.mapped + RtStatsLayout.EXPOSURE, 1.0f);
        MemoryUtil.memPutFloat(stats.mapped + RtStatsLayout.SUN_RADIUS, 0.0046f);
        MemoryUtil.memPutFloat(stats.mapped + RtStatsLayout.HISTORY_CAP, RtFrameParams.MAX_ACCUM);
        MemoryUtil.memPutFloat(stats.mapped + RtStatsLayout.GI_DIST, 64f);
        MemoryUtil.memPutFloat(stats.mapped + RtStatsLayout.EMIS_DIST, 32f);
        MemoryUtil.memPutFloat(stats.mapped + RtStatsLayout.AO_RADIUS, 2.5f);
        statsAddress = stats.address;

        // ---- atlases / lightmap / depth / accum images ----
        atlas = memory.createImage("rt_atlas", 16, 16, VK_FORMAT_R8G8B8A8_UNORM,
            VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT);
        material = memory.createImage("rt_material", 16, 16, VK_FORMAT_R8G8B8A8_UNORM,
            VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT);
        normal = memory.createImage("rt_normal", 16, 16, VK_FORMAT_R8G8B8A8_UNORM,
            VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT);
        lightmap = memory.createImage("rt_lightmap", 16, 16, VK_FORMAT_R8G8B8A8_UNORM,
            VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT);
        depth = memory.createImage("rt_depth", width, height, VK_FORMAT_R32_SFLOAT,
            VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT);
        accum = memory.createImage("rt_accum", width, height, VK_FORMAT_R32G32B32A32_SFLOAT,
            VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT);
        atlasView = atlas.view; materialView = material.view; normalView = normal.view;
        lightmapView = lightmap.view; depthView = depth.view; accumView = accum.view;
        uploadAtlases();

        buildAccelerationStructures();
    }

    /** Clears the synthetic atlases to fixed colours and transitions all images to GENERAL. */
    private void uploadAtlases() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            check(vkResetCommandBuffer(probe.command, 0), "Reset scene command buffer");
            check(vkBeginCommandBuffer(probe.command, VkCommandBufferBeginInfo.calloc(stack).sType$Default()
                .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT)), "Begin scene command buffer");
            RtMemory.Img[] imgs = {atlas, material, normal, lightmap, depth, accum};
            VkImageMemoryBarrier.Buffer toTransfer = VkImageMemoryBarrier.calloc(imgs.length, stack);
            for (int i = 0; i < imgs.length; i++) {
                toTransfer.get(i).sType$Default().image(imgs[i].image).srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).oldLayout(VK_IMAGE_LAYOUT_UNDEFINED)
                    .newLayout(VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL).srcAccessMask(0).dstAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT);
                toTransfer.get(i).subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
            }
            vkCmdPipelineBarrier(probe.command, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0, null, null, toTransfer);
            VkImageSubresourceRange.Buffer range = VkImageSubresourceRange.calloc(1, stack);
            range.get(0).aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
            // atlas: opaque mid-gray albedo; material: roughness 0.5 / f0 0.04 / no emission; normal: flat; lightmap: white.
            clear(stack, atlas, 0.5f, 0.5f, 0.5f, 1.0f, range);
            clear(stack, material, 0.5f, 0.04f, 0.0f, 0.0f, range);
            clear(stack, normal, 0.5f, 0.5f, 1.0f, 1.0f, range);
            clear(stack, lightmap, 1.0f, 1.0f, 1.0f, 1.0f, range);
            VkImageMemoryBarrier.Buffer toGeneral = VkImageMemoryBarrier.calloc(imgs.length, stack);
            for (int i = 0; i < imgs.length; i++) {
                toGeneral.get(i).sType$Default().image(imgs[i].image).srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).oldLayout(VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL)
                    .newLayout(VK_IMAGE_LAYOUT_GENERAL).srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT).dstAccessMask(VK_ACCESS_SHADER_READ_BIT);
                toGeneral.get(i).subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
            }
            vkCmdPipelineBarrier(probe.command, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, null, null, toGeneral);
            check(vkEndCommandBuffer(probe.command), "End scene command buffer");
            VkSubmitInfo.Buffer submit = VkSubmitInfo.calloc(1, stack);
            submit.get(0).sType$Default().pCommandBuffers(stack.pointers(probe.command.address()));
            check(vkQueueSubmit(probe.queue, submit, VK_NULL_HANDLE), "Submit scene upload");
            check(vkQueueWaitIdle(probe.queue), "Wait for scene upload");
        }
    }

    private void clear(MemoryStack stack, RtMemory.Img img, float r, float g, float b, float a, VkImageSubresourceRange.Buffer range) {
        VkClearColorValue color = VkClearColorValue.calloc(stack);
        color.float32(0, r).float32(1, g).float32(2, b).float32(3, a);
        vkCmdClearColorImage(probe.command, img.image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, color, range);
    }

    private void buildAccelerationStructures() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            // BLAS geometry: triangles from the pos buffer, indexed.
            blasGeometry = VkAccelerationStructureGeometryKHR.calloc(1);
            blasGeometry.get(0).sType$Default().geometryType(KHRAccelerationStructure.VK_GEOMETRY_TYPE_TRIANGLES_KHR)
                .flags(KHRAccelerationStructure.VK_GEOMETRY_OPAQUE_BIT_KHR);
            VkAccelerationStructureGeometryTrianglesDataKHR triangles = blasGeometry.get(0).geometry().triangles();
            triangles.sType$Default().vertexFormat(VK_FORMAT_R32G32B32_SFLOAT).vertexStride(3 * Float.BYTES)
                .maxVertex(VERTS - 1).indexType(VK_INDEX_TYPE_UINT32);
            triangles.vertexData().deviceAddress(pos.address);
            triangles.indexData().deviceAddress(indices.address);

            VkAccelerationStructureBuildGeometryInfoKHR blasBuild = VkAccelerationStructureBuildGeometryInfoKHR.calloc(stack)
                .sType$Default().type(KHRAccelerationStructure.VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR)
                .flags(KHRAccelerationStructure.VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR)
                .geometryCount(1).pGeometries(blasGeometry);
            VkAccelerationStructureBuildSizesInfoKHR blasSize = VkAccelerationStructureBuildSizesInfoKHR.calloc(stack).sType$Default();
            KHRAccelerationStructure.vkGetAccelerationStructureBuildSizesKHR(probe.device,
                KHRAccelerationStructure.VK_ACCELERATION_STRUCTURE_BUILD_TYPE_DEVICE_KHR, blasBuild, new int[]{TRIS}, blasSize);
            int asUsage = KHRAccelerationStructure.VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_STORAGE_BIT_KHR | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT;
            blasBuf = memory.createBuffer("rt_blas", blasSize.accelerationStructureSize(), asUsage, false);
            VkAccelerationStructureCreateInfoKHR blasCreate = VkAccelerationStructureCreateInfoKHR.calloc(stack).sType$Default()
                .buffer(blasBuf.handle).size(blasSize.accelerationStructureSize())
                .type(KHRAccelerationStructure.VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR);
            LongBuffer pBlas = stack.mallocLong(1);
            check(KHRAccelerationStructure.vkCreateAccelerationStructureKHR(probe.device, blasCreate, null, pBlas), "Create BLAS");
            blasHandle = pBlas.get(0);
            VkAccelerationStructureDeviceAddressInfoKHR blasAddr = VkAccelerationStructureDeviceAddressInfoKHR.calloc(stack)
                .sType$Default().accelerationStructure(blasHandle);
            blasAddress = KHRAccelerationStructure.vkGetAccelerationStructureDeviceAddressKHR(probe.device, blasAddr);

            // Instance buffer: one instance, customIndex 0, identity transform, cull disabled.
            instances = memory.createBuffer("rt_instances", RtInstanceWriter.SIZEOF, RtMemory.USAGE_AS_INPUT, true);
            RtInstanceWriter.write(instances.mapped, 0, 0xFF, blasAddress, IDENTITY_3X4);

            // TLAS geometry: instances.
            tlasGeometry = VkAccelerationStructureGeometryKHR.calloc(1);
            tlasGeometry.get(0).sType$Default().geometryType(KHRAccelerationStructure.VK_GEOMETRY_TYPE_INSTANCES_KHR)
                .flags(KHRAccelerationStructure.VK_GEOMETRY_OPAQUE_BIT_KHR);
            tlasGeometry.get(0).geometry().instances().sType$Default().arrayOfPointers(false).data().deviceAddress(instances.address);
            VkAccelerationStructureBuildGeometryInfoKHR tlasBuild = VkAccelerationStructureBuildGeometryInfoKHR.calloc(stack)
                .sType$Default().type(KHRAccelerationStructure.VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR)
                .flags(KHRAccelerationStructure.VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR)
                .geometryCount(1).pGeometries(tlasGeometry);
            VkAccelerationStructureBuildSizesInfoKHR tlasSize = VkAccelerationStructureBuildSizesInfoKHR.calloc(stack).sType$Default();
            KHRAccelerationStructure.vkGetAccelerationStructureBuildSizesKHR(probe.device,
                KHRAccelerationStructure.VK_ACCELERATION_STRUCTURE_BUILD_TYPE_DEVICE_KHR, tlasBuild, new int[]{1}, tlasSize);
            tlasBuf = memory.createBuffer("rt_tlas", tlasSize.accelerationStructureSize(), asUsage, false);
            VkAccelerationStructureCreateInfoKHR tlasCreate = VkAccelerationStructureCreateInfoKHR.calloc(stack).sType$Default()
                .buffer(tlasBuf.handle).size(tlasSize.accelerationStructureSize())
                .type(KHRAccelerationStructure.VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR);
            LongBuffer pTlas = stack.mallocLong(1);
            check(KHRAccelerationStructure.vkCreateAccelerationStructureKHR(probe.device, tlasCreate, null, pTlas), "Create TLAS");
            tlasHandle = pTlas.get(0);
            tlas = tlasHandle;

            long scratchSize = Math.max(blasSize.buildScratchSize(), tlasSize.buildScratchSize());
            scratchSize = (scratchSize + 255L) & ~255L;
            if (scratchSize == 0) scratchSize = 256;
            scratch = memory.createBuffer("rt_scratch", scratchSize,
                VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT, false);
        }
    }

    /** Records BLAS build -> barrier -> TLAS build -> barrier onto the command buffer. */
    void build(VkCommandBuffer cmd) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkAccelerationStructureBuildGeometryInfoKHR.Buffer blas = VkAccelerationStructureBuildGeometryInfoKHR.calloc(1, stack);
            blas.get(0).sType$Default().type(KHRAccelerationStructure.VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR)
                .flags(KHRAccelerationStructure.VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR)
                .mode(KHRAccelerationStructure.VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR)
                .dstAccelerationStructure(blasHandle).geometryCount(1).pGeometries(blasGeometry);
            blas.get(0).scratchData().deviceAddress(scratch.address);
            VkAccelerationStructureBuildRangeInfoKHR.Buffer blasRange = VkAccelerationStructureBuildRangeInfoKHR.calloc(1, stack);
            blasRange.get(0).primitiveCount(TRIS).primitiveOffset(0).firstVertex(0).transformOffset(0);
            KHRAccelerationStructure.vkCmdBuildAccelerationStructuresKHR(cmd, blas, stack.pointers(blasRange.address()));
            barrier(stack, cmd, KHRAccelerationStructure.VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR);

            VkAccelerationStructureBuildGeometryInfoKHR.Buffer tlasInfo = VkAccelerationStructureBuildGeometryInfoKHR.calloc(1, stack);
            tlasInfo.get(0).sType$Default().type(KHRAccelerationStructure.VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR)
                .flags(KHRAccelerationStructure.VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR)
                .mode(KHRAccelerationStructure.VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR)
                .dstAccelerationStructure(tlasHandle).geometryCount(1).pGeometries(tlasGeometry);
            tlasInfo.get(0).scratchData().deviceAddress(scratch.address);
            VkAccelerationStructureBuildRangeInfoKHR.Buffer tlasRange = VkAccelerationStructureBuildRangeInfoKHR.calloc(1, stack);
            tlasRange.get(0).primitiveCount(1).primitiveOffset(0).firstVertex(0).transformOffset(0);
            KHRAccelerationStructure.vkCmdBuildAccelerationStructuresKHR(cmd, tlasInfo, stack.pointers(tlasRange.address()));
            barrier(stack, cmd, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);
        }
    }

    private void barrier(MemoryStack stack, VkCommandBuffer cmd, int dstStage) {
        VkMemoryBarrier.Buffer b = VkMemoryBarrier.calloc(1, stack);
        b.get(0).sType$Default().srcAccessMask(KHRAccelerationStructure.VK_ACCESS_ACCELERATION_STRUCTURE_WRITE_BIT_KHR)
            .dstAccessMask(KHRAccelerationStructure.VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR);
        vkCmdPipelineBarrier(cmd, KHRAccelerationStructure.VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR, dstStage, 0, b, null, null);
    }

    /** Fixed camera at the origin looking down -Z; mode LIT, no optional flags (primary-hit shade path). */
    RtFrameParams params(int frame, int accumCount) {
        RtFrameParams p = new RtFrameParams();
        float aspect = (float) width / height;
        p.invViewProj.perspective((float) Math.toRadians(70), aspect, 0.05f, 1000f).invert();
        p.statsAddress = statsAddress;
        p.mode = 5; // MODE_LIT
        p.frame = frame;
        p.accumCount = accumCount;
        p.tonemap = true;
        p.flags = 0;
        p.key.dir[0] = 0.3f; p.key.dir[1] = 0.8f; p.key.dir[2] = -0.5f; p.key.day = 1.0f;
        p.key.color[0] = 1.0f; p.key.color[1] = 0.95f; p.key.color[2] = 0.9f;
        p.skyColor[0] = 0.47f; p.skyColor[1] = 0.65f; p.skyColor[2] = 1.0f;
        return p;
    }

    private static void check(int result, String operation) {
        if (result != VK_SUCCESS) throw new IllegalStateException(operation + " failed: VkResult=" + result);
    }

    @Override public void close() {
        vkDeviceWaitIdle(probe.device);
        if (tlasHandle != 0) KHRAccelerationStructure.vkDestroyAccelerationStructureKHR(probe.device, tlasHandle, null);
        if (blasHandle != 0) KHRAccelerationStructure.vkDestroyAccelerationStructureKHR(probe.device, blasHandle, null);
        if (blasGeometry != null) blasGeometry.free();
        if (tlasGeometry != null) tlasGeometry.free();
        for (RtMemory.Img img : new RtMemory.Img[]{atlas, material, normal, lightmap, depth, accum})
            if (img != null) memory.destroy(img);
        for (RtMemory.Buf buf : new RtMemory.Buf[]{pos, uv, col, light, indices, instances, blasBuf, tlasBuf, scratch, table, stats})
            if (buf != null) memory.destroy(buf);
    }
}