package io.github.andrexarlay.vulkancompat.rt.entity;

import io.github.andrexarlay.vulkancompat.rt.world.RtEntityGeometryManager;
import org.junit.jupiter.api.Test;
import org.joml.Matrix4f;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class RtEntityUploadTest {

    @Test
    public void testEmptyUpload() {
        RtEntityUploadManager uploadManager = new RtEntityUploadManager(2);
        EntityGpuFrame gpuFrame = uploadManager.upload(null, EntityGeometryFrame.EMPTY, null, 0);
        assertNotNull(gpuFrame);
        assertFalse(gpuFrame.isValid());
        assertEquals(EntityGpuFrame.EMPTY, gpuFrame);
    }

    @Test
    public void testEntityFrameUploadSimulation() {
        RtEntityUploadManager uploadManager = new RtEntityUploadManager(2);

        Matrix4f transform = new Matrix4f().identity();
        EntityGeometryFrame.CapturedEntity entity = new EntityGeometryFrame.CapturedEntity(
            101,
            "minecraft:cow",
            transform,
            transform,
            0,
            24,
            0,
            36,
            1,
            EntityGeometryFrame.CapturedEntity.FLAG_LIVING,
            false
        );

        float[] vertexData = new float[24 * 3]; // 24 vertices * 3 components
        int[] indexData = new int[36];          // 36 indices

        EntityGeometryFrame frame = EntityGeometryFrame.builder(1000L, 5L)
            .addEntity(entity)
            .vertexData(vertexData)
            .indexData(indexData)
            .build();

        assertTrue(frame.hasEntities());

        EntityGpuFrame gpuFrame = uploadManager.upload(null, frame, null, 0);
        assertNotNull(gpuFrame);
        assertEquals(1000L, gpuFrame.frameId());
        assertEquals(24, gpuFrame.vertexCount());
        assertEquals(36, gpuFrame.indexCount());
        assertEquals(12, gpuFrame.primitiveCount());
        assertEquals(1, gpuFrame.entityCount());
        assertEquals(5L, gpuFrame.generation());

        uploadManager.close(null);
    }

    @Test
    public void testGeometryManagerFormatDiagnostics() {
        RtEntityGeometryManager gm = new RtEntityGeometryManager();
        String diag = gm.formatDiagnostics();
        assertNotNull(diag);
        assertTrue(diag.contains("[VK-ENTITY-UPLOAD]"), "must have prefix");
        assertTrue(diag.contains("vertices="), "must have vertices");
        assertTrue(diag.contains("primitives="), "must have primitives");
        assertTrue(diag.contains("atlasReady="), "must have atlasReady");
    }

    @Test
    public void testGeometryManagerGetLastGpuFrameEmpty() {
        RtEntityGeometryManager gm = new RtEntityGeometryManager();
        // No upload called — lastGpuFrame must be EMPTY
        EntityGpuFrame frame = gm.getLastGpuFrame();
        assertNotNull(frame);
        assertEquals(EntityGpuFrame.EMPTY, frame);
        assertFalse(frame.isValid());
    }

    @Test
    public void testUploadAndGetGpuFrameNullMem() {
        RtEntityGeometryManager gm = new RtEntityGeometryManager();
        // With null mem, uploadAndGetGpuFrame should return EMPTY without crashing
        EntityGpuFrame frame = gm.uploadAndGetGpuFrame(null, 0, 0, 0);
        assertNotNull(frame);
        assertEquals(EntityGpuFrame.EMPTY, frame);
    }
}
