package io.github.andrexarlay.vulkancompat.shaderhost.dirtrt;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * L2 JUnit tests for Dirt RT / Vulkanite ABI bridge and data structures:
 * <ul>
 *   <li>Exact struct sizes, field offsets, std430 alignment</li>
 *   <li>CameraInfo UBO (256 bytes) serialization, deserialization, corner rays math</li>
 *   <li>Quad geometry buffer (160 bytes/quad, 40 bytes/vertex) packing and normal/tangent math</li>
 *   <li>Pipeline passes, SBT layout, and descriptor set configurations</li>
 * </ul>
 */
class DirtRtAbiTest {

    private static final float EPSILON = 1e-5f;

    // =========================================================================
    // 1. CameraInfo Layout & Offsets (256 bytes)
    // =========================================================================

    @Test
    @DisplayName("CameraInfo UBO exact sizes and offsets conform to std430 alignment")
    void testCameraInfoLayoutAndSizes() {
        assertEquals(256, DirtRtCameraUbo.UBO_SIZE, "CameraInfo UBO must be exactly 256 bytes");

        // Verify individual field offsets
        assertEquals(0, DirtRtCameraUbo.OFFSET_CORNER_RAYS);
        assertEquals(64, DirtRtCameraUbo.SIZE_CORNER_RAYS, "4 corner rays * 16B = 64B");

        assertEquals(64, DirtRtCameraUbo.OFFSET_VIEW_INVERSE);
        assertEquals(64, DirtRtCameraUbo.SIZE_VIEW_INVERSE, "mat4 = 16 floats * 4B = 64B");

        assertEquals(128, DirtRtCameraUbo.OFFSET_FRAME_ID);
        assertEquals(4, DirtRtCameraUbo.SIZE_FRAME_ID);

        assertEquals(132, DirtRtCameraUbo.OFFSET_FLAGS);
        assertEquals(4, DirtRtCameraUbo.SIZE_FLAGS);

        assertEquals(136, DirtRtCameraUbo.OFFSET_WORLD_TYPE);
        assertEquals(4, DirtRtCameraUbo.SIZE_WORLD_TYPE);

        assertEquals(140, DirtRtCameraUbo.OFFSET_PAD0);
        assertEquals(4, DirtRtCameraUbo.SIZE_PAD0);

        assertEquals(144, DirtRtCameraUbo.OFFSET_CAMERA_POSITION, "vec3 cameraPosition requires 16-byte alignment in std430 (144 = 16 * 9)");
        assertEquals(12, DirtRtCameraUbo.SIZE_CAMERA_POSITION, "vec3 = 3 floats * 4B = 12B");

        assertEquals(156, DirtRtCameraUbo.OFFSET_PAD1);
        assertEquals(4, DirtRtCameraUbo.SIZE_PAD1);

        assertEquals(160, DirtRtCameraUbo.OFFSET_TRAILING_PAD);
        assertEquals(96, DirtRtCameraUbo.SIZE_TRAILING_PAD, "Trailing pad = 256 - 160 = 96B");

        // Verify contiguous packing
        assertEquals(DirtRtCameraUbo.OFFSET_VIEW_INVERSE, DirtRtCameraUbo.OFFSET_CORNER_RAYS + DirtRtCameraUbo.SIZE_CORNER_RAYS);
        assertEquals(DirtRtCameraUbo.OFFSET_FRAME_ID, DirtRtCameraUbo.OFFSET_VIEW_INVERSE + DirtRtCameraUbo.SIZE_VIEW_INVERSE);
        assertEquals(DirtRtCameraUbo.OFFSET_FLAGS, DirtRtCameraUbo.OFFSET_FRAME_ID + DirtRtCameraUbo.SIZE_FRAME_ID);
        assertEquals(DirtRtCameraUbo.OFFSET_WORLD_TYPE, DirtRtCameraUbo.OFFSET_FLAGS + DirtRtCameraUbo.SIZE_FLAGS);
        assertEquals(DirtRtCameraUbo.OFFSET_PAD0, DirtRtCameraUbo.OFFSET_WORLD_TYPE + DirtRtCameraUbo.SIZE_WORLD_TYPE);
        assertEquals(DirtRtCameraUbo.OFFSET_CAMERA_POSITION, DirtRtCameraUbo.OFFSET_PAD0 + DirtRtCameraUbo.SIZE_PAD0);
        assertEquals(DirtRtCameraUbo.OFFSET_PAD1, DirtRtCameraUbo.OFFSET_CAMERA_POSITION + DirtRtCameraUbo.SIZE_CAMERA_POSITION);
        assertEquals(DirtRtCameraUbo.OFFSET_TRAILING_PAD, DirtRtCameraUbo.OFFSET_PAD1 + DirtRtCameraUbo.SIZE_PAD1);
        assertEquals(DirtRtCameraUbo.UBO_SIZE, DirtRtCameraUbo.OFFSET_TRAILING_PAD + DirtRtCameraUbo.SIZE_TRAILING_PAD);
    }

    @Test
    @DisplayName("CameraInfo serialization writes correct bytes and deserializes accurately")
    void testCameraInfoSerializationAndDeserialization() {
        DirtRtCameraUbo ubo = new DirtRtCameraUbo();

        // 1. Populate fields
        ubo.setCornerRay(0, -1.5f, -1.0f, -1.0f, 0.0f);
        ubo.setCornerRay(1, +1.5f, -1.0f, -1.0f, 0.0f);
        ubo.setCornerRay(2, -1.5f, +1.0f, -1.0f, 0.0f);
        ubo.setCornerRay(3, +1.5f, +1.0f, -1.0f, 0.0f);

        Matrix4f viewInv = new Matrix4f().translation(10.0f, 20.0f, 30.0f);
        ubo.setViewInverse(viewInv);

        ubo.setFrameId(1024);
        ubo.setFlags(0x05);
        ubo.setWorldType(DirtRtCameraUbo.WORLD_NETHER);
        ubo.setPad0(1.234f);
        ubo.setCameraPosition(128.5f, 64.0f, -256.75f);
        ubo.setPad1(5.678f);

        // 2. Serialize to ByteBuffer
        ByteBuffer buffer = ByteBuffer.allocate(DirtRtCameraUbo.UBO_SIZE);
        ubo.serialize(buffer);

        // Check buffer position advanced by 256
        assertEquals(256, buffer.position());

        // 3. Verify byte contents directly
        buffer.flip();
        buffer.order(ByteOrder.LITTLE_ENDIAN);

        // Check corner rays
        assertEquals(-1.5f, buffer.getFloat(0), EPSILON);
        assertEquals(-1.0f, buffer.getFloat(4), EPSILON);
        assertEquals(-1.0f, buffer.getFloat(8), EPSILON);
        assertEquals(0.0f, buffer.getFloat(12), EPSILON);

        assertEquals(+1.5f, buffer.getFloat(16), EPSILON);
        assertEquals(-1.0f, buffer.getFloat(20), EPSILON);

        assertEquals(-1.5f, buffer.getFloat(32), EPSILON);
        assertEquals(+1.0f, buffer.getFloat(36), EPSILON);

        assertEquals(+1.5f, buffer.getFloat(48), EPSILON);
        assertEquals(+1.0f, buffer.getFloat(52), EPSILON);

        // Check view inverse column 3 (translation x, y, z at floats 12, 13, 14 of the matrix)
        // Offset in buffer: 64 + 12*4 = 112 (tx), 116 (ty), 120 (tz), 124 (tw = 1.0)
        assertEquals(10.0f, buffer.getFloat(64 + 12 * 4), EPSILON);
        assertEquals(20.0f, buffer.getFloat(64 + 13 * 4), EPSILON);
        assertEquals(30.0f, buffer.getFloat(64 + 14 * 4), EPSILON);
        assertEquals(1.0f, buffer.getFloat(64 + 15 * 4), EPSILON);

        // Check frameId, flags, worldType
        assertEquals(1024, buffer.getInt(DirtRtCameraUbo.OFFSET_FRAME_ID));
        assertEquals(0x05, buffer.getInt(DirtRtCameraUbo.OFFSET_FLAGS));
        assertEquals(-1, buffer.getInt(DirtRtCameraUbo.OFFSET_WORLD_TYPE));
        assertEquals(1.234f, buffer.getFloat(DirtRtCameraUbo.OFFSET_PAD0), EPSILON);

        // Check camera position
        assertEquals(128.5f, buffer.getFloat(DirtRtCameraUbo.OFFSET_CAMERA_POSITION), EPSILON);
        assertEquals(64.0f, buffer.getFloat(DirtRtCameraUbo.OFFSET_CAMERA_POSITION + 4), EPSILON);
        assertEquals(-256.75f, buffer.getFloat(DirtRtCameraUbo.OFFSET_CAMERA_POSITION + 8), EPSILON);
        assertEquals(5.678f, buffer.getFloat(DirtRtCameraUbo.OFFSET_PAD1), EPSILON);

        // Check trailing padding is zero
        for (int i = 0; i < DirtRtCameraUbo.SIZE_TRAILING_PAD; i += 4) {
            assertEquals(0.0f, buffer.getFloat(DirtRtCameraUbo.OFFSET_TRAILING_PAD + i), "Padding must be 0 at offset " + (DirtRtCameraUbo.OFFSET_TRAILING_PAD + i));
        }

        // 4. Deserialize and compare
        DirtRtCameraUbo deserialized = DirtRtCameraUbo.deserialize(buffer, 0);
        assertEquals(ubo.getFrameId(), deserialized.getFrameId());
        assertEquals(ubo.getFlags(), deserialized.getFlags());
        assertEquals(ubo.getWorldType(), deserialized.getWorldType());
        assertEquals(ubo.getPad0(), deserialized.getPad0(), EPSILON);
        assertEquals(ubo.getPad1(), deserialized.getPad1(), EPSILON);

        Vector3f camPos = deserialized.getCameraPosition();
        assertEquals(128.5f, camPos.x, EPSILON);
        assertEquals(64.0f, camPos.y, EPSILON);
        assertEquals(-256.75f, camPos.z, EPSILON);

        for (int i = 0; i < DirtRtCameraUbo.CORNER_RAY_COUNT; i++) {
            Vector4f expectedRay = ubo.getCornerRay(i);
            Vector4f actualRay = deserialized.getCornerRay(i);
            assertEquals(expectedRay.x, actualRay.x, EPSILON);
            assertEquals(expectedRay.y, actualRay.y, EPSILON);
            assertEquals(expectedRay.z, actualRay.z, EPSILON);
            assertEquals(expectedRay.w, actualRay.w, EPSILON);
        }
    }

    @Test
    @DisplayName("Camera corner rays calculation from FOV and aspect ratio")
    void testCameraCornerRaysComputation() {
        DirtRtCameraUbo ubo = new DirtRtCameraUbo();

        // 90 degrees FOV, aspect ratio = 1.0
        // tan(45 degrees) = 1.0
        ubo.computeCornerRays(90.0f, 1.0f);

        Vector4f c0 = ubo.getCornerRay(0); // Bottom-left
        Vector4f c1 = ubo.getCornerRay(1); // Bottom-right
        Vector4f c2 = ubo.getCornerRay(2); // Top-left
        Vector4f c3 = ubo.getCornerRay(3); // Top-right

        assertEquals(-1.0f, c0.x, EPSILON);
        assertEquals(-1.0f, c0.y, EPSILON);
        assertEquals(-1.0f, c0.z, EPSILON);

        assertEquals(+1.0f, c1.x, EPSILON);
        assertEquals(-1.0f, c1.y, EPSILON);
        assertEquals(-1.0f, c1.z, EPSILON);

        assertEquals(-1.0f, c2.x, EPSILON);
        assertEquals(+1.0f, c2.y, EPSILON);
        assertEquals(-1.0f, c2.z, EPSILON);

        assertEquals(+1.0f, c3.x, EPSILON);
        assertEquals(+1.0f, c3.y, EPSILON);
        assertEquals(-1.0f, c3.z, EPSILON);

        // Aspect ratio = 16.0 / 9.0
        float aspect = 16.0f / 9.0f;
        ubo.computeCornerRays(90.0f, aspect);

        c0 = ubo.getCornerRay(0);
        c1 = ubo.getCornerRay(1);
        c2 = ubo.getCornerRay(2);
        c3 = ubo.getCornerRay(3);

        assertEquals(-aspect, c0.x, EPSILON);
        assertEquals(-1.0f, c0.y, EPSILON);

        assertEquals(+aspect, c1.x, EPSILON);
        assertEquals(-1.0f, c1.y, EPSILON);

        assertEquals(-aspect, c2.x, EPSILON);
        assertEquals(+1.0f, c2.y, EPSILON);

        assertEquals(+aspect, c3.x, EPSILON);
        assertEquals(+1.0f, c3.y, EPSILON);

        // Test with projection matrix unprojection
        Matrix4f proj = new Matrix4f().perspective((float) Math.toRadians(90.0), aspect, 0.1f, 100.0f);
        ubo.computeCornerRays(proj);

        Vector4f projC0 = ubo.getCornerRay(0);
        Vector4f projC1 = ubo.getCornerRay(1);
        Vector4f projC2 = ubo.getCornerRay(2);
        Vector4f projC3 = ubo.getCornerRay(3);

        // Symmetry checks
        assertEquals(-projC1.x, projC0.x, EPSILON);
        assertEquals(projC1.y, projC0.y, EPSILON);
        assertEquals(-projC3.x, projC2.x, EPSILON);
        assertEquals(projC3.y, projC2.y, EPSILON);
    }

    // =========================================================================
    // 2. Vulkanite Quad & Vertex Geometry Buffer Layout (160B / 40B)
    // =========================================================================

    @Test
    @DisplayName("Vulkanite Quad and Vertex sizes and field offsets")
    void testQuadLayoutAndSizes() {
        assertEquals(40, DirtRtQuadAdapter.VERTEX_SIZE, "Vertex must be exactly 40 bytes");
        assertEquals(4, DirtRtQuadAdapter.VERTICES_PER_QUAD, "Quad must have 4 vertices");
        assertEquals(160, DirtRtQuadAdapter.QUAD_SIZE, "Quad must be exactly 160 bytes (4 * 40B)");

        // Vertex field offsets
        assertEquals(0, DirtRtQuadAdapter.OFFSET_POSITION);
        assertEquals(12, DirtRtQuadAdapter.SIZE_POSITION, "position = 3 floats * 4B = 12B");

        assertEquals(12, DirtRtQuadAdapter.OFFSET_TEXCOORD);
        assertEquals(8, DirtRtQuadAdapter.SIZE_TEXCOORD, "texcoord = 2 floats * 4B = 8B");

        assertEquals(20, DirtRtQuadAdapter.OFFSET_NORMAL_TANGENT);
        assertEquals(16, DirtRtQuadAdapter.SIZE_NORMAL_TANGENT, "normal_tangent = 4 floats * 4B = 16B");

        assertEquals(36, DirtRtQuadAdapter.OFFSET_BLOCK_ID);
        assertEquals(4, DirtRtQuadAdapter.SIZE_BLOCK_ID, "blockId = uint32 * 4B = 4B");

        // Verify contiguous layout
        assertEquals(DirtRtQuadAdapter.OFFSET_TEXCOORD, DirtRtQuadAdapter.OFFSET_POSITION + DirtRtQuadAdapter.SIZE_POSITION);
        assertEquals(DirtRtQuadAdapter.OFFSET_NORMAL_TANGENT, DirtRtQuadAdapter.OFFSET_TEXCOORD + DirtRtQuadAdapter.SIZE_TEXCOORD);
        assertEquals(DirtRtQuadAdapter.OFFSET_BLOCK_ID, DirtRtQuadAdapter.OFFSET_NORMAL_TANGENT + DirtRtQuadAdapter.SIZE_NORMAL_TANGENT);
        assertEquals(DirtRtQuadAdapter.VERTEX_SIZE, DirtRtQuadAdapter.OFFSET_BLOCK_ID + DirtRtQuadAdapter.SIZE_BLOCK_ID);
    }

    @Test
    @DisplayName("Quad serialization writes accurate bytes and validates little-endian encoding")
    void testQuadSerializationAndDeserialization() {
        DirtRtQuadAdapter.Vertex v0 = new DirtRtQuadAdapter.Vertex(
                1.0f, 2.0f, 3.0f,
                0.25f, 0.75f,
                0.0f, 1.0f, 0.0f, 1.0f,
                42
        );
        DirtRtQuadAdapter.Vertex v1 = new DirtRtQuadAdapter.Vertex(
                4.0f, 5.0f, 6.0f,
                0.5f, 0.5f,
                0.0f, 1.0f, 0.0f, 1.0f,
                42
        );
        DirtRtQuadAdapter.Vertex v2 = new DirtRtQuadAdapter.Vertex(
                7.0f, 8.0f, 9.0f,
                0.75f, 0.25f,
                0.0f, 1.0f, 0.0f, 1.0f,
                42
        );
        DirtRtQuadAdapter.Vertex v3 = new DirtRtQuadAdapter.Vertex(
                10.0f, 11.0f, 12.0f,
                1.0f, 0.0f,
                0.0f, 1.0f, 0.0f, 1.0f,
                42
        );

        DirtRtQuadAdapter.Quad quad = new DirtRtQuadAdapter.Quad(v0, v1, v2, v3);

        ByteBuffer buffer = ByteBuffer.allocate(DirtRtQuadAdapter.QUAD_SIZE);
        DirtRtQuadAdapter.writeQuad(buffer, quad);

        assertEquals(160, buffer.position());

        // Verify byte contents
        buffer.flip();
        buffer.order(ByteOrder.LITTLE_ENDIAN);

        // Vertex 0 at offset 0
        assertEquals(1.0f, buffer.getFloat(0), EPSILON);
        assertEquals(2.0f, buffer.getFloat(4), EPSILON);
        assertEquals(3.0f, buffer.getFloat(8), EPSILON);
        assertEquals(0.25f, buffer.getFloat(12), EPSILON);
        assertEquals(0.75f, buffer.getFloat(16), EPSILON);
        assertEquals(0.0f, buffer.getFloat(20), EPSILON);
        assertEquals(1.0f, buffer.getFloat(24), EPSILON);
        assertEquals(0.0f, buffer.getFloat(28), EPSILON);
        assertEquals(1.0f, buffer.getFloat(32), EPSILON);
        assertEquals(42, buffer.getInt(36));

        // Vertex 1 at offset 40
        assertEquals(4.0f, buffer.getFloat(40), EPSILON);
        assertEquals(5.0f, buffer.getFloat(44), EPSILON);
        assertEquals(6.0f, buffer.getFloat(48), EPSILON);
        assertEquals(42, buffer.getInt(40 + 36));

        // Vertex 2 at offset 80
        assertEquals(7.0f, buffer.getFloat(80), EPSILON);
        assertEquals(8.0f, buffer.getFloat(84), EPSILON);
        assertEquals(9.0f, buffer.getFloat(88), EPSILON);
        assertEquals(42, buffer.getInt(80 + 36));

        // Vertex 3 at offset 120
        assertEquals(10.0f, buffer.getFloat(120), EPSILON);
        assertEquals(11.0f, buffer.getFloat(124), EPSILON);
        assertEquals(12.0f, buffer.getFloat(128), EPSILON);
        assertEquals(42, buffer.getInt(120 + 36));

        // Read back quad
        DirtRtQuadAdapter.Quad readBack = DirtRtQuadAdapter.readQuad(buffer, 0);
        assertEquals(quad.v0().x(), readBack.v0().x(), EPSILON);
        assertEquals(quad.v1().y(), readBack.v1().y(), EPSILON);
        assertEquals(quad.v2().z(), readBack.v2().z(), EPSILON);
        assertEquals(quad.v3().blockId(), readBack.v3().blockId());
    }

    @Test
    @DisplayName("Multi-quad serialization packed in contiguous array")
    void testMultiQuadSerialization() {
        int quadCount = 10;
        int totalBytes = quadCount * DirtRtQuadAdapter.QUAD_SIZE; // 1600 bytes
        ByteBuffer buffer = ByteBuffer.allocate(totalBytes);

        List<DirtRtQuadAdapter.Quad> quads = new java.util.ArrayList<>();
        for (int i = 0; i < quadCount; i++) {
            DirtRtQuadAdapter.Vertex v = new DirtRtQuadAdapter.Vertex(
                    i, i, i,
                    0, 0,
                    0, 1, 0, 1,
                    i * 10
            );
            quads.add(new DirtRtQuadAdapter.Quad(v, v, v, v));
        }

        DirtRtQuadAdapter.writeQuads(buffer, quads);
        assertEquals(totalBytes, buffer.position());

        List<DirtRtQuadAdapter.Quad> readBack = DirtRtQuadAdapter.readQuads(buffer, 0, quadCount);
        assertEquals(quadCount, readBack.size());

        for (int i = 0; i < quadCount; i++) {
            assertEquals(i * 10, readBack.get(i).v0().blockId());
            assertEquals(i, readBack.get(i).v0().x(), EPSILON);
        }
    }

    @Test
    @DisplayName("Geometric face normal and tangent computation")
    void testNormalAndTangentMath() {
        // Horizontal quad on XZ plane facing +Y
        // v0=(0,0,0), v1=(1,0,0), v2=(1,0,1)
        Vector3f normal = DirtRtQuadAdapter.computeFaceNormal(
                0.0f, 0.0f, 0.0f,
                1.0f, 0.0f, 0.0f,
                1.0f, 0.0f, 1.0f,
                new Vector3f());

        // e1 = (1, 0, 0), e2 = (1, 0, 1)
        // e1 x e2 = (0*1 - 0*0, 0*1 - 1*1, 1*0 - 0*1) = (0, -1, 0)
        // Or if clockwise: (0, 0, 0) -> (1, 0, 1) -> (1, 0, 0)
        assertEquals(0.0f, normal.x, EPSILON);
        assertEquals(-1.0f, normal.y, EPSILON);
        assertEquals(0.0f, normal.z, EPSILON);

        // Counter-clockwise quad on XY plane facing +Z
        // v0=(0,0,0), v1=(1,0,0), v2=(0,1,0)
        Vector3f normalZ = DirtRtQuadAdapter.computeFaceNormal(
                0.0f, 0.0f, 0.0f,
                1.0f, 0.0f, 0.0f,
                0.0f, 1.0f, 0.0f,
                new Vector3f());

        assertEquals(0.0f, normalZ.x, EPSILON);
        assertEquals(0.0f, normalZ.y, EPSILON);
        assertEquals(1.0f, normalZ.z, EPSILON);

        // Tangent with standard UVs
        Vector4f tangent = DirtRtQuadAdapter.computeTangent(
                0.0f, 0.0f, 1.0f, // normal +Z
                0.0f, 0.0f, 0.0f, 0.0f, 0.0f, // v0
                1.0f, 0.0f, 0.0f, 1.0f, 0.0f, // v1 (+X, +U)
                0.0f, 1.0f, 0.0f, 0.0f, 1.0f, // v2 (+Y, +V)
                new Vector4f());

        // Tangent should point along +X
        assertEquals(1.0f, tangent.x, EPSILON);
        assertEquals(0.0f, tangent.y, EPSILON);
        assertEquals(0.0f, tangent.z, EPSILON);

        // createQuad helper
        float[] positions = {
                0.0f, 0.0f, 0.0f,
                1.0f, 0.0f, 0.0f,
                1.0f, 1.0f, 0.0f,
                0.0f, 1.0f, 0.0f
        };
        float[] uvs = {
                0.0f, 0.0f,
                1.0f, 0.0f,
                1.0f, 1.0f,
                0.0f, 1.0f
        };

        DirtRtQuadAdapter.Quad quad = DirtRtQuadAdapter.createQuad(positions, uvs, 100);
        assertNotNull(quad);
        assertEquals(100, quad.v0().blockId());
        assertEquals(100, quad.v3().blockId());
        assertEquals(0.0f, quad.v0().nx(), EPSILON);
        assertEquals(0.0f, quad.v0().ny(), EPSILON);
        assertEquals(1.0f, quad.v0().nz(), EPSILON);
    }

    // =========================================================================
    // 3. Pipeline Configuration, SBT Layout & Descriptor Sets
    // =========================================================================

    @Test
    @DisplayName("Dirt RT pipeline passes, shader names, and extension verification")
    void testPipelineConfiguration() {
        assertEquals("VK_KHR_ray_tracing_pipeline", DirtRtPipelineConfig.REQUIRED_EXTENSION);
        assertEquals(3, DirtRtPipelineConfig.PASSES.size(), "Dirt RT requires exactly 3 ray tracing passes");

        // Pass 0: Diffuse
        DirtRtPipelineConfig.RayPass pass0 = DirtRtPipelineConfig.RayPass.DIFFUSE;
        assertEquals(0, pass0.passIndex());
        assertEquals("diffuse", pass0.passName());
        assertEquals("ray0.rgen", pass0.raygenShader());
        assertEquals("ray0_0.rchit", pass0.closestHitShader());
        assertEquals("ray0_0.rahit", pass0.anyHitShader());
        assertEquals("ray0_0.rmiss", pass0.missShader());
        assertEquals(2, pass0.firstLobeVal());

        // Pass 1: Reflection
        DirtRtPipelineConfig.RayPass pass1 = DirtRtPipelineConfig.RayPass.REFLECTION;
        assertEquals(1, pass1.passIndex());
        assertEquals("reflection", pass1.passName());
        assertEquals("ray1.rgen", pass1.raygenShader());
        assertEquals("ray1_0.rchit", pass1.closestHitShader());
        assertEquals("ray1_0.rahit", pass1.anyHitShader());
        assertEquals("ray1_0.rmiss", pass1.missShader());
        assertEquals(1, pass1.firstLobeVal());

        // Pass 2: Refraction
        DirtRtPipelineConfig.RayPass pass2 = DirtRtPipelineConfig.RayPass.REFRACTION;
        assertEquals(2, pass2.passIndex());
        assertEquals("refraction", pass2.passName());
        assertEquals("ray2.rgen", pass2.raygenShader());
        assertEquals("ray2_0.rchit", pass2.closestHitShader());
        assertEquals("ray2_0.rahit", pass2.anyHitShader());
        assertEquals("ray2_0.rmiss", pass2.missShader());
        assertEquals(3, pass2.firstLobeVal());
    }

    @Test
    @DisplayName("Descriptor sets 0, 1, 3 bindings and types")
    void testDescriptorSets() {
        // Set 0 bindings
        assertEquals(6, DirtRtPipelineConfig.SET0_BINDINGS.size());
        assertEquals(0, DirtRtPipelineConfig.SET0_CAMERA_INFO.binding());
        assertEquals(DirtRtPipelineConfig.DescriptorType.UNIFORM_BUFFER, DirtRtPipelineConfig.SET0_CAMERA_INFO.type());

        assertEquals(1, DirtRtPipelineConfig.SET0_ACCELERATION_STRUCTURE.binding());
        assertEquals(DirtRtPipelineConfig.DescriptorType.ACCELERATION_STRUCTURE_KHR, DirtRtPipelineConfig.SET0_ACCELERATION_STRUCTURE.type());

        assertEquals(3, DirtRtPipelineConfig.SET0_BLOCK_TEX.binding());
        assertEquals(DirtRtPipelineConfig.DescriptorType.COMBINED_IMAGE_SAMPLER, DirtRtPipelineConfig.SET0_BLOCK_TEX.type());

        assertEquals(4, DirtRtPipelineConfig.SET0_BLOCK_TEX_NORMAL.binding());
        assertEquals(5, DirtRtPipelineConfig.SET0_BLOCK_TEX_SPECULAR.binding());

        assertEquals(6, DirtRtPipelineConfig.SET0_RAY_TRACE_DATA.binding());
        assertEquals(DirtRtPipelineConfig.DescriptorType.STORAGE_IMAGE, DirtRtPipelineConfig.SET0_RAY_TRACE_DATA.type());

        // Set 1 bindings (Geometry buffers)
        assertEquals(1, DirtRtPipelineConfig.SET1_BINDINGS.size());
        assertEquals(0, DirtRtPipelineConfig.SET1_GEOMETRY_BUFFERS.binding());
        assertEquals(DirtRtPipelineConfig.DescriptorType.STORAGE_BUFFER, DirtRtPipelineConfig.SET1_GEOMETRY_BUFFERS.type());
        assertTrue(DirtRtPipelineConfig.SET1_GEOMETRY_BUFFERS.isArray());

        // Set 3 bindings (Output SSBOs)
        assertEquals(5, DirtRtPipelineConfig.SET3_BINDINGS.size());
        assertEquals(0, DirtRtPipelineConfig.SET3_GEOM_BUFFER.binding());
        assertEquals(1, DirtRtPipelineConfig.SET3_FRAME_DATA.binding());
        assertEquals(2, DirtRtPipelineConfig.SET3_DIFFUSE_BUFFER.binding());
        assertEquals(3, DirtRtPipelineConfig.SET3_REFLECT_BUFFER.binding());
        assertEquals(4, DirtRtPipelineConfig.SET3_REFRACT_BUFFER.binding());
    }

    @Test
    @DisplayName("Output buffer sizing calculations for 1920x1080 resolution")
    void testOutputBufferSizing() {
        int width = 1920;
        int height = 1080;
        long pixelCount = (long) width * height; // 2,073,600 pixels

        // GeometryMaterialBuffer: 128 bytes/pixel
        DirtRtPipelineConfig.OutputBuffer geom = DirtRtPipelineConfig.OutputBuffer.GEOMETRY_MATERIAL;
        assertEquals(128, geom.bytesPerPixel());
        assertEquals(pixelCount * 128, geom.calculateSizeBytes(width, height)); // 265,420,800 bytes

        // FrameData: 1024 bytes fixed
        DirtRtPipelineConfig.OutputBuffer frameData = DirtRtPipelineConfig.OutputBuffer.FRAME_DATA;
        assertEquals(1024, frameData.fixedSizeBytes());
        assertEquals(1024, frameData.calculateSizeBytes(width, height));

        // DiffuseBuffer: 96 bytes/pixel
        DirtRtPipelineConfig.OutputBuffer diffuse = DirtRtPipelineConfig.OutputBuffer.DIFFUSE;
        assertEquals(96, diffuse.bytesPerPixel());
        assertEquals(pixelCount * 96, diffuse.calculateSizeBytes(width, height)); // 199,065,600 bytes

        // ReflectBuffer: 64 bytes/pixel
        DirtRtPipelineConfig.OutputBuffer reflect = DirtRtPipelineConfig.OutputBuffer.REFLECT;
        assertEquals(64, reflect.bytesPerPixel());
        assertEquals(pixelCount * 64, reflect.calculateSizeBytes(width, height)); // 132,710,400 bytes

        // RefractBuffer: 64 bytes/pixel
        DirtRtPipelineConfig.OutputBuffer refract = DirtRtPipelineConfig.OutputBuffer.REFRACT;
        assertEquals(64, refract.bytesPerPixel());
        assertEquals(pixelCount * 64, refract.calculateSizeBytes(width, height)); // 132,710,400 bytes
    }

    @Test
    @DisplayName("SBT layout striding and alignment calculations")
    void testSbtLayout() {
        DirtRtPipelineConfig.SbtLayout sbt = new DirtRtPipelineConfig.SbtLayout(32, 32, 64);

        assertEquals(32, sbt.handleSize());
        assertEquals(32, sbt.handleSizeAligned());
        assertEquals(64, sbt.baseAlignment());

        // 1 raygen handle (32 bytes) aligned to 64 bytes base = 64
        assertEquals(64, sbt.regionBaseAligned(sbt.computeRegionSize(1)));

        // 2 miss handles (64 bytes) aligned to 64 bytes base = 64
        assertEquals(64, sbt.regionBaseAligned(sbt.computeRegionSize(2)));

        // Total SBT size for 1 raygen, 1 miss, 1 hit group: 64 + 64 + 64 = 192 bytes
        assertEquals(192, sbt.computeTotalSbtSize(1, 1, 1));
    }
}
