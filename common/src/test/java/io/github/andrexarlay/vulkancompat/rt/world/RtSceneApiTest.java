package io.github.andrexarlay.vulkancompat.rt.world;

import io.github.andrexarlay.vulkancompat.api.rt.RtInstanceHandle;
import io.github.andrexarlay.vulkancompat.api.rt.RtLayer;
import io.github.andrexarlay.vulkancompat.api.rt.RtMeshHandle;
import io.github.andrexarlay.vulkancompat.api.rt.RtSceneApi;
import io.github.andrexarlay.vulkancompat.api.rt.RtVertexFormat;
import io.github.andrexarlay.vulkancompat.rt.entity.EntityCaptureManager;
import io.github.andrexarlay.vulkancompat.rt.entity.EntityGeometryFrame;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.*;

/**
 * L2 JUnit tests for {@link RtSceneApi}:
 * <ul>
 *   <li>Mesh registration with Blaze3D {@code BLOCK}, {@code NEW_ENTITY}, and custom indexed formats</li>
 *   <li>Instance submission with current and previous-frame world transforms</li>
 *   <li>Integration with {@link EntityCaptureManager} and {@link RtEntityGeometryManager}</li>
 *   <li>Removal of instances and meshes</li>
 * </ul>
 */
public class RtSceneApiTest {

    @BeforeEach
    public void setUp() {
        RtSceneApi.getInstance().clear();
    }

    @AfterEach
    public void tearDown() {
        RtSceneApi.getInstance().clear();
    }

    public static void runAll() {
        RtSceneApiTest t = new RtSceneApiTest();
        try {
            t.setUp();
            t.testMeshRegistrationAndQuadTriangulation();
            t.tearDown();

            t.setUp();
            t.testInstanceSubmissionAndEntityFrameIntegration();
            t.tearDown();

            t.setUp();
            t.testExplicitIndexBufferAndRemoval();
        } finally {
            t.tearDown();
        }
    }

    @Test
    public void testMeshRegistrationAndQuadTriangulation() {
        RtSceneApi api = RtSceneApi.getInstance();
        assertEquals(0, api.meshCount());
        assertEquals(0, api.instanceCount());

        // 1 quad (4 vertices) in Blaze3D BLOCK format (32 bytes per vertex)
        ByteBuffer vb = ByteBuffer.allocateDirect(4 * RtVertexFormat.BLOCK.strideBytes()).order(ByteOrder.LITTLE_ENDIAN);
        putBlockVertex(vb, 0f, 0f, 0f, 0xFFFFFFFF, 0f, 0f, 15, 15, (byte) 0, (byte) 127, (byte) 0);
        putBlockVertex(vb, 1f, 0f, 0f, 0xFFFFFFFF, 1f, 0f, 15, 15, (byte) 0, (byte) 127, (byte) 0);
        putBlockVertex(vb, 1f, 1f, 0f, 0xFFFFFFFF, 1f, 1f, 15, 15, (byte) 0, (byte) 127, (byte) 0);
        putBlockVertex(vb, 0f, 1f, 0f, 0xFFFFFFFF, 0f, 1f, 15, 15, (byte) 0, (byte) 127, (byte) 0);
        vb.flip();

        RtMeshHandle mesh = api.registerMesh(vb, 4, RtVertexFormat.BLOCK, null, 0, RtLayer.SOLID);
        assertTrue(mesh.isValid(), "Registered quad mesh must be valid");
        assertEquals(4, mesh.vertexCount());
        assertEquals(6, mesh.indexCount(), "4-vertex quad must auto-triangulate to 6 indices");
        assertEquals(RtLayer.SOLID, mesh.layer());
        assertEquals(1, api.meshCount());
    }

    @Test
    public void testInstanceSubmissionAndEntityFrameIntegration() {
        RtSceneApi api = RtSceneApi.getInstance();

        ByteBuffer vb = ByteBuffer.allocateDirect(4 * RtVertexFormat.BLOCK.strideBytes()).order(ByteOrder.LITTLE_ENDIAN);
        putBlockVertex(vb, 0f, 0f, 0f, 0xFFFFFFFF, 0f, 0f, -1, -1, (byte) 0, (byte) 0, (byte) 127);
        putBlockVertex(vb, 2f, 0f, 0f, 0xFFFFFFFF, 1f, 0f, -1, -1, (byte) 0, (byte) 0, (byte) 127);
        putBlockVertex(vb, 2f, 2f, 0f, 0xFFFFFFFF, 1f, 1f, -1, -1, (byte) 0, (byte) 0, (byte) 127);
        putBlockVertex(vb, 0f, 2f, 0f, 0xFFFFFFFF, 0f, 1f, -1, -1, (byte) 0, (byte) 0, (byte) 127);
        vb.flip();

        RtMeshHandle mesh = api.registerMesh(vb, 4, new RtVertexFormat(32, 0, 12, 16, -1, 28, true), null, 0, RtLayer.CUTOUT);
        assertTrue(mesh.isValid());

        Matrix4f curTransform = new Matrix4f().translation(10f, 65f, -5f);
        Matrix4f prevTransform = new Matrix4f().translation(9.5f, 65f, -5f);
        Identifier tex = Identifier.fromNamespaceAndPath("create", "textures/block/waterwheel.png");

        RtInstanceHandle inst = api.addInstance(mesh, curTransform, prevTransform, tex, 12, 15);
        assertTrue(inst.isValid());
        assertEquals(1, api.instanceCount());

        // Capture a frame through EntityCaptureManager -> verify bridge instance feeds into EntityGeometryFrame
        EntityCaptureManager mgr = EntityCaptureManager.getInstance();
        mgr.beginFrame(500L);
        EntityGeometryFrame frame = mgr.endFrame();

        assertNotNull(frame);
        assertEquals(4, frame.totalVertices());
        assertEquals(6, frame.totalIndices());
        assertEquals(1, frame.entities().size());
        assertEquals(frame, RtEntityGeometryManager.getInstance().getCurrentFrame());

        EntityGeometryFrame.CapturedEntity ce = frame.entities().get(0);
        assertTrue(ce.entityId() < -1, "Bridge instance must have stable negative entityId");
        assertEquals("bridge:cutout", ce.entityType());
        assertEquals(curTransform, ce.currentTransform());
        assertEquals(prevTransform, ce.previousTransform());

        // First vertex position must be transformed by curTransform: (0,0,0) + (10,65,-5) = (10,65,-5)
        float[] vData = frame.vertexData();
        assertEquals(10f, vData[0], 1e-4f);
        assertEquals(65f, vData[1], 1e-4f);
        assertEquals(-5f, vData[2], 1e-4f);

        // Update instance transform for next frame
        Matrix4f nextTransform = new Matrix4f().translation(10.5f, 65f, -5f);
        api.updateInstance(inst, nextTransform, curTransform, 14, 15);

        mgr.beginFrame(501L);
        EntityGeometryFrame frame2 = mgr.endFrame();
        assertEquals(10.5f, frame2.vertexData()[0], 1e-4f);
        assertEquals(nextTransform, frame2.entities().get(0).currentTransform());
        assertEquals(curTransform, frame2.entities().get(0).previousTransform());
    }

    @Test
    public void testExplicitIndexBufferAndRemoval() {
        RtSceneApi api = RtSceneApi.getInstance();

        // 3 vertices (1 triangle) in POSITION_ONLY format (12 bytes per vertex)
        ByteBuffer vb = ByteBuffer.allocateDirect(3 * 12).order(ByteOrder.LITTLE_ENDIAN);
        vb.putFloat(0f).putFloat(0f).putFloat(0f);
        vb.putFloat(1f).putFloat(0f).putFloat(0f);
        vb.putFloat(0f).putFloat(1f).putFloat(0f);
        vb.flip();

        ByteBuffer ib = ByteBuffer.allocateDirect(3 * 4).order(ByteOrder.LITTLE_ENDIAN);
        ib.putInt(0).putInt(1).putInt(2);
        ib.flip();

        RtMeshHandle mesh = api.registerMesh(vb, 3, RtVertexFormat.POSITION_ONLY, ib, 3, RtLayer.EMISSIVE);
        assertTrue(mesh.isValid());
        assertEquals(3, mesh.vertexCount());
        assertEquals(3, mesh.indexCount());

        RtInstanceHandle inst1 = api.addInstance(mesh, new Matrix4f().identity(), new Matrix4f().identity(), null, 15, 15);
        RtInstanceHandle inst2 = api.addInstance(mesh, new Matrix4f().translation(2f, 0f, 0f), new Matrix4f().translation(2f, 0f, 0f), null, 15, 15);
        assertEquals(2, api.instanceCount());

        // Remove single instance
        api.removeInstance(inst1);
        assertEquals(1, api.instanceCount());

        EntityCaptureManager mgr = EntityCaptureManager.getInstance();
        mgr.beginFrame(600L);
        EntityGeometryFrame frame = mgr.endFrame();
        assertEquals(1, frame.entities().size());
        assertEquals(3, frame.totalVertices());
        assertTrue((frame.entities().get(0).renderFlags() & EntityGeometryFrame.CapturedEntity.FLAG_GLOWING) != 0,
            "EMISSIVE layer must set FLAG_GLOWING");

        // Removing mesh also removes remaining instances referencing it
        api.removeMesh(mesh);
        assertEquals(0, api.meshCount());
        assertEquals(0, api.instanceCount());

        mgr.beginFrame(601L);
        EntityGeometryFrame emptyFrame = mgr.endFrame();
        assertEquals(0, emptyFrame.entities().size());
        assertEquals(0, emptyFrame.totalVertices());
    }

    private static void putBlockVertex(ByteBuffer b, float x, float y, float z, int abgr, float u, float v,
                                       int blockLight, int skyLight, byte nx, byte ny, byte nz) {
        b.putFloat(x).putFloat(y).putFloat(z);
        b.putInt(abgr);
        b.putFloat(u).putFloat(v);
        int packedLight = ((skyLight & 0xFF) << 16) | (blockLight & 0xFF);
        b.putInt(packedLight);
        b.put(nx).put(ny).put(nz).put((byte) 0);
    }
}
