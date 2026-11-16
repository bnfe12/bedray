package io.github.andrexarlay.vulkancompat.shaderhost.rtpipeline;

import io.github.andrexarlay.vulkancompat.shaderhost.rtpipeline.RtPipelineConfig.OutputBuffer;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.lwjgl.vulkan.VK10;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * L2 JUnit tests for the Vulkanite RT-pipeline Set 3 SSBO Buffer Manager and Compute Pass Graph:
 * <ul>
 *   <li>Exact struct sizes, std430 alignment, and field offsets for FrameData (1024B)</li>
 *   <li>FrameData serialization/deserialization accuracy and little-endian packing</li>
 *   <li>Screen SSBO buffer sizing across standard resolutions (720p, 1080p, 1440p, 4K)</li>
 *   <li>8x8 tiled workgroup dispatch calculations and edge case handling</li>
 *   <li>27 compute passes analysis, registration, ordering, and dependency barriers</li>
 * </ul>
 */
class RtPipelineSsboManagerTest {

    private static final float EPSILON = 1e-5f;

    // =========================================================================
    // 1. FrameData Layout, Offsets & std430 Alignment (1024 bytes)
    // =========================================================================

    @Test
    @DisplayName("FrameData SSBO exact size, field offsets and std430 alignment")
    void testFrameDataLayoutAndOffsets() {
        assertEquals(1024, RtPipelineSsboManager.FRAME_DATA_SIZE, "FrameData must be exactly 1024 bytes");

        // 1. Check individual offsets and sizes
        assertEquals(0, RtPipelineSsboManager.OFFSET_RT_MODEL_VIEW);
        assertEquals(64, RtPipelineSsboManager.SIZE_RT_MODEL_VIEW, "mat4 = 16 floats * 4B = 64B");

        assertEquals(64, RtPipelineSsboManager.OFFSET_RT_PREV_MODEL_VIEW);
        assertEquals(64, RtPipelineSsboManager.SIZE_RT_PREV_MODEL_VIEW);

        assertEquals(128, RtPipelineSsboManager.OFFSET_RT_PROJECTION);
        assertEquals(64, RtPipelineSsboManager.SIZE_RT_PROJECTION);

        assertEquals(192, RtPipelineSsboManager.OFFSET_RT_PREV_PROJECTION);
        assertEquals(64, RtPipelineSsboManager.SIZE_RT_PREV_PROJECTION);

        assertEquals(256, RtPipelineSsboManager.OFFSET_LIGHT_DIR);
        assertEquals(12, RtPipelineSsboManager.SIZE_LIGHT_DIR, "vec3 = 3 floats * 4B = 12B");

        assertEquals(268, RtPipelineSsboManager.OFFSET_AVG_EXPOSURE);
        assertEquals(4, RtPipelineSsboManager.SIZE_AVG_EXPOSURE);

        assertEquals(272, RtPipelineSsboManager.OFFSET_CAM_POS);
        assertEquals(12, RtPipelineSsboManager.SIZE_CAM_POS);

        assertEquals(284, RtPipelineSsboManager.OFFSET_DIV_AVG_EXPOSURE);
        assertEquals(4, RtPipelineSsboManager.SIZE_DIV_AVG_EXPOSURE);

        assertEquals(288, RtPipelineSsboManager.OFFSET_CAM_X);
        assertEquals(12, RtPipelineSsboManager.SIZE_CAM_X);

        assertEquals(300, RtPipelineSsboManager.OFFSET_TIME);
        assertEquals(4, RtPipelineSsboManager.SIZE_TIME);

        assertEquals(304, RtPipelineSsboManager.OFFSET_CAM_Y);
        assertEquals(12, RtPipelineSsboManager.SIZE_CAM_Y);

        assertEquals(316, RtPipelineSsboManager.OFFSET_DTIME);
        assertEquals(4, RtPipelineSsboManager.SIZE_DTIME);

        assertEquals(320, RtPipelineSsboManager.OFFSET_PREV_RAYTRACING_CAM_POS);
        assertEquals(12, RtPipelineSsboManager.SIZE_PREV_RAYTRACING_CAM_POS);

        assertEquals(332, RtPipelineSsboManager.OFFSET_RAIN_STRENGTH);
        assertEquals(4, RtPipelineSsboManager.SIZE_RAIN_STRENGTH);

        assertEquals(336, RtPipelineSsboManager.OFFSET_RESOLUTION);
        assertEquals(8, RtPipelineSsboManager.SIZE_RESOLUTION, "uvec2 = 2 uints * 4B = 8B");

        assertEquals(344, RtPipelineSsboManager.OFFSET_WORLD_TYPE);
        assertEquals(4, RtPipelineSsboManager.SIZE_WORLD_TYPE);

        assertEquals(348, RtPipelineSsboManager.OFFSET_FRAME_ID);
        assertEquals(4, RtPipelineSsboManager.SIZE_FRAME_ID);

        assertEquals(352, RtPipelineSsboManager.OFFSET_WET_STRENGTH);
        assertEquals(4, RtPipelineSsboManager.SIZE_WET_STRENGTH);

        assertEquals(356, RtPipelineSsboManager.OFFSET_WETNESS);
        assertEquals(4, RtPipelineSsboManager.SIZE_WETNESS);

        assertEquals(360, RtPipelineSsboManager.OFFSET_TRAILING_PAD);
        assertEquals(664, RtPipelineSsboManager.SIZE_TRAILING_PAD, "Trailing pad = 1024 - 360 = 664B");

        // 2. Strict contiguity verification: offset(i+1) == offset(i) + size(i)
        assertEquals(RtPipelineSsboManager.OFFSET_RT_PREV_MODEL_VIEW, RtPipelineSsboManager.OFFSET_RT_MODEL_VIEW + RtPipelineSsboManager.SIZE_RT_MODEL_VIEW);
        assertEquals(RtPipelineSsboManager.OFFSET_RT_PROJECTION, RtPipelineSsboManager.OFFSET_RT_PREV_MODEL_VIEW + RtPipelineSsboManager.SIZE_RT_PREV_MODEL_VIEW);
        assertEquals(RtPipelineSsboManager.OFFSET_RT_PREV_PROJECTION, RtPipelineSsboManager.OFFSET_RT_PROJECTION + RtPipelineSsboManager.SIZE_RT_PROJECTION);
        assertEquals(RtPipelineSsboManager.OFFSET_LIGHT_DIR, RtPipelineSsboManager.OFFSET_RT_PREV_PROJECTION + RtPipelineSsboManager.SIZE_RT_PREV_PROJECTION);
        assertEquals(RtPipelineSsboManager.OFFSET_AVG_EXPOSURE, RtPipelineSsboManager.OFFSET_LIGHT_DIR + RtPipelineSsboManager.SIZE_LIGHT_DIR);
        assertEquals(RtPipelineSsboManager.OFFSET_CAM_POS, RtPipelineSsboManager.OFFSET_AVG_EXPOSURE + RtPipelineSsboManager.SIZE_AVG_EXPOSURE);
        assertEquals(RtPipelineSsboManager.OFFSET_DIV_AVG_EXPOSURE, RtPipelineSsboManager.OFFSET_CAM_POS + RtPipelineSsboManager.SIZE_CAM_POS);
        assertEquals(RtPipelineSsboManager.OFFSET_CAM_X, RtPipelineSsboManager.OFFSET_DIV_AVG_EXPOSURE + RtPipelineSsboManager.SIZE_DIV_AVG_EXPOSURE);
        assertEquals(RtPipelineSsboManager.OFFSET_TIME, RtPipelineSsboManager.OFFSET_CAM_X + RtPipelineSsboManager.SIZE_CAM_X);
        assertEquals(RtPipelineSsboManager.OFFSET_CAM_Y, RtPipelineSsboManager.OFFSET_TIME + RtPipelineSsboManager.SIZE_TIME);
        assertEquals(RtPipelineSsboManager.OFFSET_DTIME, RtPipelineSsboManager.OFFSET_CAM_Y + RtPipelineSsboManager.SIZE_CAM_Y);
        assertEquals(RtPipelineSsboManager.OFFSET_PREV_RAYTRACING_CAM_POS, RtPipelineSsboManager.OFFSET_DTIME + RtPipelineSsboManager.SIZE_DTIME);
        assertEquals(RtPipelineSsboManager.OFFSET_RAIN_STRENGTH, RtPipelineSsboManager.OFFSET_PREV_RAYTRACING_CAM_POS + RtPipelineSsboManager.SIZE_PREV_RAYTRACING_CAM_POS);
        assertEquals(RtPipelineSsboManager.OFFSET_RESOLUTION, RtPipelineSsboManager.OFFSET_RAIN_STRENGTH + RtPipelineSsboManager.SIZE_RAIN_STRENGTH);
        assertEquals(RtPipelineSsboManager.OFFSET_WORLD_TYPE, RtPipelineSsboManager.OFFSET_RESOLUTION + RtPipelineSsboManager.SIZE_RESOLUTION);
        assertEquals(RtPipelineSsboManager.OFFSET_FRAME_ID, RtPipelineSsboManager.OFFSET_WORLD_TYPE + RtPipelineSsboManager.SIZE_WORLD_TYPE);
        assertEquals(RtPipelineSsboManager.OFFSET_WET_STRENGTH, RtPipelineSsboManager.OFFSET_FRAME_ID + RtPipelineSsboManager.SIZE_FRAME_ID);
        assertEquals(RtPipelineSsboManager.OFFSET_WETNESS, RtPipelineSsboManager.OFFSET_WET_STRENGTH + RtPipelineSsboManager.SIZE_WET_STRENGTH);
        assertEquals(RtPipelineSsboManager.OFFSET_TRAILING_PAD, RtPipelineSsboManager.OFFSET_WETNESS + RtPipelineSsboManager.SIZE_WETNESS);
        assertEquals(RtPipelineSsboManager.FRAME_DATA_SIZE, RtPipelineSsboManager.OFFSET_TRAILING_PAD + RtPipelineSsboManager.SIZE_TRAILING_PAD);

        // 3. std430 alignment rules
        assertEquals(0, RtPipelineSsboManager.OFFSET_RT_MODEL_VIEW % 16, "mat4 requires 16-byte alignment");
        assertEquals(0, RtPipelineSsboManager.OFFSET_RT_PREV_MODEL_VIEW % 16, "mat4 requires 16-byte alignment");
        assertEquals(0, RtPipelineSsboManager.OFFSET_RT_PROJECTION % 16, "mat4 requires 16-byte alignment");
        assertEquals(0, RtPipelineSsboManager.OFFSET_RT_PREV_PROJECTION % 16, "mat4 requires 16-byte alignment");

        assertEquals(0, RtPipelineSsboManager.OFFSET_LIGHT_DIR % 16, "vec3 requires 16-byte alignment");
        assertEquals(0, RtPipelineSsboManager.OFFSET_AVG_EXPOSURE % 4, "float requires 4-byte alignment");

        assertEquals(0, RtPipelineSsboManager.OFFSET_CAM_POS % 16, "vec3 requires 16-byte alignment");
        assertEquals(0, RtPipelineSsboManager.OFFSET_DIV_AVG_EXPOSURE % 4, "float requires 4-byte alignment");

        assertEquals(0, RtPipelineSsboManager.OFFSET_CAM_X % 16, "vec3 requires 16-byte alignment");
        assertEquals(0, RtPipelineSsboManager.OFFSET_TIME % 4, "float requires 4-byte alignment");

        assertEquals(0, RtPipelineSsboManager.OFFSET_CAM_Y % 16, "vec3 requires 16-byte alignment");
        assertEquals(0, RtPipelineSsboManager.OFFSET_DTIME % 4, "float requires 4-byte alignment");

        assertEquals(0, RtPipelineSsboManager.OFFSET_PREV_RAYTRACING_CAM_POS % 16, "vec3 requires 16-byte alignment");
        assertEquals(0, RtPipelineSsboManager.OFFSET_RAIN_STRENGTH % 4, "float requires 4-byte alignment");

        assertEquals(0, RtPipelineSsboManager.OFFSET_RESOLUTION % 8, "uvec2 requires 8-byte alignment");
        assertEquals(0, RtPipelineSsboManager.OFFSET_WORLD_TYPE % 4, "int requires 4-byte alignment");
        assertEquals(0, RtPipelineSsboManager.OFFSET_FRAME_ID % 4, "int requires 4-byte alignment");
        assertEquals(0, RtPipelineSsboManager.OFFSET_WET_STRENGTH % 4, "float requires 4-byte alignment");
        assertEquals(0, RtPipelineSsboManager.OFFSET_WETNESS % 4, "float requires 4-byte alignment");
    }

    // =========================================================================
    // 2. FrameData Serialization and Deserialization
    // =========================================================================

    @Test
    @DisplayName("FrameData serialization writes little-endian bytes and deserializes accurately")
    void testFrameDataSerializationAndDeserialization() {
        RtPipelineSsboManager.FrameData data = new RtPipelineSsboManager.FrameData();

        Matrix4f mv = new Matrix4f().translation(10.0f, 20.0f, 30.0f);
        Matrix4f prevMv = new Matrix4f().translation(9.5f, 19.5f, 29.5f);
        Matrix4f proj = new Matrix4f().perspective(1.2f, 16.0f / 9.0f, 0.1f, 1000.0f);
        Matrix4f prevProj = new Matrix4f().perspective(1.19f, 16.0f / 9.0f, 0.1f, 1000.0f);

        data.setRtModelView(mv)
                .setRtPrevModelView(prevMv)
                .setRtProjection(proj)
                .setRtPrevProjection(prevProj)
                .setLightDir(0.2f, 0.8f, -0.5f)
                .setAvgExposure(1.25f)
                .setCamPos(100.5f, 64.0f, -200.75f)
                .setDivAvgExposure(0.8f)
                .setCamX(1.0f, 0.0f, 0.0f)
                .setTime(123.456f)
                .setCamY(0.0f, 1.0f, 0.0f)
                .setDTime(0.0166f)
                .setPrevRaytracingCamPos(99.0f, 64.0f, -199.5f)
                .setRainStrength(0.75f)
                .setResolution(1920, 1080)
                .setWorldType(1)
                .setFrameId(4242)
                .setWetStrength(0.33f)
                .setWetness(0.66f);

        // Serialize
        ByteBuffer buffer = ByteBuffer.allocate(RtPipelineSsboManager.FRAME_DATA_SIZE);
        data.serialize(buffer);

        assertEquals(RtPipelineSsboManager.FRAME_DATA_SIZE, buffer.position());

        // Validate raw byte contents at specific offsets
        buffer.flip();
        buffer.order(ByteOrder.LITTLE_ENDIAN);

        assertEquals(0.2f, buffer.getFloat(RtPipelineSsboManager.OFFSET_LIGHT_DIR), EPSILON);
        assertEquals(0.8f, buffer.getFloat(RtPipelineSsboManager.OFFSET_LIGHT_DIR + 4), EPSILON);
        assertEquals(-0.5f, buffer.getFloat(RtPipelineSsboManager.OFFSET_LIGHT_DIR + 8), EPSILON);
        assertEquals(1.25f, buffer.getFloat(RtPipelineSsboManager.OFFSET_AVG_EXPOSURE), EPSILON);

        assertEquals(100.5f, buffer.getFloat(RtPipelineSsboManager.OFFSET_CAM_POS), EPSILON);
        assertEquals(64.0f, buffer.getFloat(RtPipelineSsboManager.OFFSET_CAM_POS + 4), EPSILON);
        assertEquals(-200.75f, buffer.getFloat(RtPipelineSsboManager.OFFSET_CAM_POS + 8), EPSILON);
        assertEquals(0.8f, buffer.getFloat(RtPipelineSsboManager.OFFSET_DIV_AVG_EXPOSURE), EPSILON);

        assertEquals(1920, buffer.getInt(RtPipelineSsboManager.OFFSET_RESOLUTION));
        assertEquals(1080, buffer.getInt(RtPipelineSsboManager.OFFSET_RESOLUTION + 4));
        assertEquals(1, buffer.getInt(RtPipelineSsboManager.OFFSET_WORLD_TYPE));
        assertEquals(4242, buffer.getInt(RtPipelineSsboManager.OFFSET_FRAME_ID));
        assertEquals(0.33f, buffer.getFloat(RtPipelineSsboManager.OFFSET_WET_STRENGTH), EPSILON);
        assertEquals(0.66f, buffer.getFloat(RtPipelineSsboManager.OFFSET_WETNESS), EPSILON);

        // Verify trailing padding bytes are zero
        for (int i = 0; i < RtPipelineSsboManager.SIZE_TRAILING_PAD; i++) {
            assertEquals((byte) 0, buffer.get(RtPipelineSsboManager.OFFSET_TRAILING_PAD + i), "Trailing padding byte at " + i + " must be 0");
        }

        // Deserialize
        RtPipelineSsboManager.FrameData readBack = new RtPipelineSsboManager.FrameData();
        readBack.deserialize(buffer);

        assertEquals(mv, readBack.rtModelView());
        assertEquals(prevMv, readBack.rtPrevModelView());
        assertEquals(proj, readBack.rtProjection());
        assertEquals(prevProj, readBack.rtPrevProjection());

        assertEquals(0.2f, readBack.lightDir().x(), EPSILON);
        assertEquals(0.8f, readBack.lightDir().y(), EPSILON);
        assertEquals(-0.5f, readBack.lightDir().z(), EPSILON);
        assertEquals(1.25f, readBack.avgExposure(), EPSILON);

        assertEquals(100.5f, readBack.camPos().x(), EPSILON);
        assertEquals(64.0f, readBack.camPos().y(), EPSILON);
        assertEquals(-200.75f, readBack.camPos().z(), EPSILON);
        assertEquals(0.8f, readBack.divAvgExposure(), EPSILON);

        assertEquals(123.456f, readBack.time(), EPSILON);
        assertEquals(0.0166f, readBack.dTime(), EPSILON);

        assertEquals(99.0f, readBack.prevRaytracingCamPos().x(), EPSILON);
        assertEquals(64.0f, readBack.prevRaytracingCamPos().y(), EPSILON);
        assertEquals(-199.5f, readBack.prevRaytracingCamPos().z(), EPSILON);

        assertEquals(0.75f, readBack.rainStrength(), EPSILON);
        assertEquals(1920, readBack.resolutionWidth());
        assertEquals(1080, readBack.resolutionHeight());
        assertEquals(1, readBack.worldType());
        assertEquals(4242, readBack.frameId());
        assertEquals(0.33f, readBack.wetStrength(), EPSILON);
        assertEquals(0.66f, readBack.wetness(), EPSILON);
    }

    // =========================================================================
    // 3. Screen SSBO Sizing (720p, 1080p, 1440p, 4K)
    // =========================================================================

    @Test
    @DisplayName("SSBO sizing calculations for standard resolutions (720p, 1080p, 1440p, 4K)")
    void testScreenSsboSizingStandardResolutions() {
        // --- 720p (1280x720) ---
        int w720 = 1280, h720 = 720;
        long pixels720 = 1280L * 720L; // 921,600
        assertEquals(pixels720, RtPipelineSsboManager.tiledPixelCount(w720, h720));
        assertEquals(pixels720 * 80L, RtPipelineSsboManager.calculateGeometrySize(w720, h720, false));   // 73,728,000B
        assertEquals(pixels720 * 128L, RtPipelineSsboManager.calculateGeometrySize(w720, h720, true));  // 117,964,800B
        assertEquals(pixels720 * 96L, RtPipelineSsboManager.calculateDiffuseSize(w720, h720));          // 88,473,600B
        assertEquals(pixels720 * 64L, RtPipelineSsboManager.calculateReflectSize(w720, h720));          // 58,982,400B
        assertEquals(pixels720 * 64L, RtPipelineSsboManager.calculateRefractSize(w720, h720));          // 58,982,400B
        assertEquals(1024L, RtPipelineSsboManager.calculateFrameDataSize());

        long expectedTotal720 = 73_728_000L + 1024L + 88_473_600L + 58_982_400L + 58_982_400L; // 280,167,424B
        assertEquals(expectedTotal720, RtPipelineSsboManager.calculateTotalSize(w720, h720, false));

        // --- 1080p (1920x1080) ---
        int w1080 = 1920, h1080 = 1080;
        long pixels1080 = 1920L * 1080L; // 2,073,600
        assertEquals(pixels1080, RtPipelineSsboManager.tiledPixelCount(w1080, h1080));
        assertEquals(pixels1080 * 80L, RtPipelineSsboManager.calculateGeometrySize(w1080, h1080, false));  // 165,888,000B
        assertEquals(pixels1080 * 128L, RtPipelineSsboManager.calculateGeometrySize(w1080, h1080, true)); // 265,420,800B
        assertEquals(pixels1080 * 96L, RtPipelineSsboManager.calculateDiffuseSize(w1080, h1080));         // 199,065,600B
        assertEquals(pixels1080 * 64L, RtPipelineSsboManager.calculateReflectSize(w1080, h1080));         // 132,710,400B
        assertEquals(pixels1080 * 64L, RtPipelineSsboManager.calculateRefractSize(w1080, h1080));         // 132,710,400B

        long expectedTotal1080 = 165_888_000L + 1024L + 199_065_600L + 132_710_400L + 132_710_400L; // 630,375,424B
        assertEquals(expectedTotal1080, RtPipelineSsboManager.calculateTotalSize(w1080, h1080, false));

        // --- 1440p (2560x1440) ---
        int w1440 = 2560, h1440 = 1440;
        long pixels1440 = 2560L * 1440L; // 3,686,400
        assertEquals(pixels1440, RtPipelineSsboManager.tiledPixelCount(w1440, h1440));
        assertEquals(pixels1440 * 80L, RtPipelineSsboManager.calculateGeometrySize(w1440, h1440, false));  // 294,912,000B
        assertEquals(pixels1440 * 128L, RtPipelineSsboManager.calculateGeometrySize(w1440, h1440, true)); // 471,859,200B
        assertEquals(pixels1440 * 96L, RtPipelineSsboManager.calculateDiffuseSize(w1440, h1440));         // 353,894,400B
        assertEquals(pixels1440 * 64L, RtPipelineSsboManager.calculateReflectSize(w1440, h1440));         // 235,929,600B
        assertEquals(pixels1440 * 64L, RtPipelineSsboManager.calculateRefractSize(w1440, h1440));         // 235,929,600B

        long expectedTotal1440 = 294_912_000L + 1024L + 353_894_400L + 235_929_600L + 235_929_600L; // 1,120,666,624B
        assertEquals(expectedTotal1440, RtPipelineSsboManager.calculateTotalSize(w1440, h1440, false));

        // --- 4K (3840x2160) ---
        int w4k = 3840, h4k = 2160;
        long pixels4k = 3840L * 2160L; // 8,294,400
        assertEquals(pixels4k, RtPipelineSsboManager.tiledPixelCount(w4k, h4k));
        assertEquals(pixels4k * 80L, RtPipelineSsboManager.calculateGeometrySize(w4k, h4k, false));  // 663,552,000B
        assertEquals(pixels4k * 128L, RtPipelineSsboManager.calculateGeometrySize(w4k, h4k, true)); // 1,061,683,200B
        assertEquals(pixels4k * 96L, RtPipelineSsboManager.calculateDiffuseSize(w4k, h4k));         // 796,262,400B
        assertEquals(pixels4k * 64L, RtPipelineSsboManager.calculateReflectSize(w4k, h4k));         // 530,841,600B
        assertEquals(pixels4k * 64L, RtPipelineSsboManager.calculateRefractSize(w4k, h4k));         // 530,841,600B

        long expectedTotal4k = 663_552_000L + 1024L + 796_262_400L + 530_841_600L + 530_841_600L; // 2,521,500,424B
        assertEquals(expectedTotal4k, RtPipelineSsboManager.calculateTotalSize(w4k, h4k, false));
    }

    // =========================================================================
    // 4. 8x8 Tiled Workgroups and Dispatch Dimensions
    // =========================================================================

    @Test
    @DisplayName("8x8 tiled workgroup dimensions and non-divisible resolution padding")
    void testTiledWorkgroup8x8Dispatch() {
        // Exact tile multiples
        // 720p: 1280 / 8 = 160, 720 / 8 = 90
        RtPipelineComputePassGraph.DispatchDim dim720 = RtPipelineComputePassGraph.dispatchDim(1280, 720);
        assertEquals(160, dim720.x());
        assertEquals(90, dim720.y());
        assertEquals(1, dim720.z());
        assertEquals(14_400L, dim720.totalGroups());

        // 1080p: 1920 / 8 = 240, 1080 / 8 = 135
        RtPipelineComputePassGraph.DispatchDim dim1080 = RtPipelineComputePassGraph.dispatchDim(1920, 1080);
        assertEquals(240, dim1080.x());
        assertEquals(135, dim1080.y());
        assertEquals(1, dim1080.z());
        assertEquals(32_400L, dim1080.totalGroups());

        // 1440p: 2560 / 8 = 320, 1440 / 8 = 180
        RtPipelineComputePassGraph.DispatchDim dim1440 = RtPipelineComputePassGraph.dispatchDim(2560, 1440);
        assertEquals(320, dim1440.x());
        assertEquals(180, dim1440.y());
        assertEquals(1, dim1440.z());
        assertEquals(57_600L, dim1440.totalGroups());

        // 4K: 3840 / 8 = 480, 2160 / 8 = 270
        RtPipelineComputePassGraph.DispatchDim dim4k = RtPipelineComputePassGraph.dispatchDim(3840, 2160);
        assertEquals(480, dim4k.x());
        assertEquals(270, dim4k.y());
        assertEquals(1, dim4k.z());
        assertEquals(129_600L, dim4k.totalGroups());

        // Non-divisible resolution: 1366 x 768
        // ceil(1366 / 8) = 171, ceil(768 / 8) = 96
        RtPipelineComputePassGraph.DispatchDim dim1366 = RtPipelineComputePassGraph.dispatchDim(1366, 768);
        assertEquals(171, dim1366.x());
        assertEquals(96, dim1366.y());
        assertEquals(1, dim1366.z());
        assertEquals(16_416L, dim1366.totalGroups());

        // Tiled pixel count for 1366x768 has padding: 171 * 96 * 64 = 1,050,624 > 1366*768 (1,049,088)
        assertEquals(1_050_624L, RtPipelineSsboManager.tiledPixelCount(1366, 768));
        assertTrue(RtPipelineSsboManager.tiledPixelCount(1366, 768) >= 1366L * 768L);

        // Edge cases
        RtPipelineComputePassGraph.DispatchDim dim1x1 = RtPipelineComputePassGraph.dispatchDim(1, 1);
        assertEquals(1, dim1x1.x());
        assertEquals(1, dim1x1.y());
        assertEquals(1, dim1x1.z());

        RtPipelineComputePassGraph.DispatchDim dim8x8 = RtPipelineComputePassGraph.dispatchDim(8, 8);
        assertEquals(1, dim8x8.x());
        assertEquals(1, dim8x8.y());

        RtPipelineComputePassGraph.DispatchDim dim9x9 = RtPipelineComputePassGraph.dispatchDim(9, 9);
        assertEquals(2, dim9x9.x());
        assertEquals(2, dim9x9.y());

        RtPipelineComputePassGraph.DispatchDim dimZero = RtPipelineComputePassGraph.dispatchDim(0, 0);
        assertEquals(0, dimZero.x());
        assertEquals(0, dimZero.y());
        assertEquals(0, dimZero.z());
    }

    // =========================================================================
    // 5. Compute Pass Graph Registration and Hazard Barriers
    // =========================================================================

    @Test
    @DisplayName("27 canonical compute passes (Dirt RT example) registration, ordering, and buffer hazard barriers")
    void testComputePassGraphAndMemoryBarriers() {
        RtPipelineComputePassGraph graph = RtPipelineComputePassGraph.createDefault();

        // 1. Verify exactly 27 compute passes
        assertEquals(27, graph.passCount());
        assertEquals(RtPipelineComputePassGraph.CANONICAL_PASS_COUNT, graph.passCount());

        // 2. Verify sequence and names
        assertEquals("composite1", graph.pass(0).name());
        assertEquals("composite2", graph.pass(1).name());
        assertEquals("composite4", graph.pass(2).name());
        assertEquals("composite5", graph.pass(3).name());
        assertEquals("composite50", graph.pass(4).name());
        assertEquals("composite51", graph.pass(5).name());
        assertEquals("composite52", graph.pass(6).name());
        assertEquals("composite53", graph.pass(7).name());
        assertEquals("composite58", graph.pass(8).name());
        assertEquals("composite59", graph.pass(9).name());
        assertEquals("composite60", graph.pass(10).name());
        assertEquals("composite61", graph.pass(11).name());
        assertEquals("composite62", graph.pass(12).name());
        assertEquals("composite63", graph.pass(13).name());
        assertEquals("composite69", graph.pass(14).name());
        assertEquals("composite70", graph.pass(15).name());
        assertEquals("composite71", graph.pass(16).name());
        assertEquals("composite72", graph.pass(17).name());
        assertEquals("composite73", graph.pass(18).name());
        assertEquals("composite79", graph.pass(19).name());
        assertEquals("composite91", graph.pass(20).name());
        assertEquals("composite92", graph.pass(21).name());
        assertEquals("composite93", graph.pass(22).name());
        assertEquals("composite94", graph.pass(23).name());
        assertEquals("composite95", graph.pass(24).name());
        assertEquals("composite96", graph.pass(25).name());
        assertEquals("composite99", graph.pass(26).name());

        // 3. Verify pass lookup by name
        assertNotNull(graph.pass("composite50"));
        assertEquals(4, graph.pass("composite50").index());
        assertEquals("shaders/composite50.csh", graph.pass("composite50").shaderPath());

        // 4. RAW & WAW hazards on DiffuseBuffer
        // composite50 (writes Diffuse) -> composite51 (reads & writes Diffuse)
        RtPipelineComputePassGraph.MemoryBarrierInfo b50_51 = graph.barrierBetween(4, 5);
        assertTrue(b50_51.hasHazard());
        assertTrue(b50_51.hasRawHazard(), "composite50 writes Diffuse, composite51 reads Diffuse (RAW)");
        assertTrue(b50_51.hasWawHazard(), "composite50 writes Diffuse, composite51 writes Diffuse (WAW)");
        assertTrue(b50_51.hazardBuffers().contains(OutputBuffer.DIFFUSE));
        assertEquals(VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, b50_51.srcStageMask());
        assertEquals(VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, b50_51.dstStageMask());
        assertEquals(VK10.VK_ACCESS_SHADER_WRITE_BIT | VK10.VK_ACCESS_SHADER_READ_BIT, b50_51.srcAccessMask());
        assertEquals(VK10.VK_ACCESS_SHADER_READ_BIT | VK10.VK_ACCESS_SHADER_WRITE_BIT, b50_51.dstAccessMask());

        // composite60 -> composite61 (Reflect RAW/WAW hazard)
        RtPipelineComputePassGraph.MemoryBarrierInfo b60_61 = graph.barrierBetween(10, 11);
        assertTrue(b60_61.hasHazard());
        assertTrue(b60_61.hazardBuffers().contains(OutputBuffer.REFLECT));

        // composite70 -> composite71 (Refract RAW/WAW hazard)
        RtPipelineComputePassGraph.MemoryBarrierInfo b70_71 = graph.barrierBetween(15, 16);
        assertTrue(b70_71.hasHazard());
        assertTrue(b70_71.hazardBuffers().contains(OutputBuffer.REFRACT));

        // composite91 -> composite92 (Bloom passes do not touch Set 3 SSBOs)
        RtPipelineComputePassGraph.MemoryBarrierInfo b91_92 = graph.barrierBetween(20, 21);
        assertFalse(b91_92.hasHazard());
        assertTrue(b91_92.hazardBuffers().isEmpty());

        // Passes touching specific buffer
        List<RtPipelineComputePassGraph.ComputePass> diffusePasses = graph.passesTouching(OutputBuffer.DIFFUSE);
        assertEquals(7, diffusePasses.size(), "7 compute passes touch DiffuseBuffer (composite1, 50, 51, 52, 53, 58, 59)");

        List<RtPipelineComputePassGraph.ComputePass> reflectPasses = graph.passesTouching(OutputBuffer.REFLECT);
        assertEquals(7, reflectPasses.size(), "7 compute passes touch ReflectBuffer (composite2, 4, 60, 61, 62, 63, 69)");

        List<RtPipelineComputePassGraph.ComputePass> refractPasses = graph.passesTouching(OutputBuffer.REFRACT);
        assertEquals(7, refractPasses.size(), "7 compute passes touch RefractBuffer (composite2, 5, 70, 71, 72, 73, 79)");
    }

    // =========================================================================
    // 6. Dynamic Registration and Lifecycle
    // =========================================================================

    @Test
    @DisplayName("Dynamic compute pass registration from filename collection and manager lifecycle")
    void testDynamicRegistrationAndLifecycle() {
        // Dynamic scan simulation
        List<String> discoveredFiles = List.of(
                "shaders/composite99.csh",
                "shaders/composite1.csh",
                "shaders/composite50.csh",
                "shaders/composite4.csh",
                "shaders/composite2.csh"
        );

        RtPipelineComputePassGraph dynamicGraph = RtPipelineComputePassGraph.analyzeAndRegister(discoveredFiles);
        assertEquals(5, dynamicGraph.passCount());
        // Must be sorted in numeric composite order
        assertEquals("composite1", dynamicGraph.pass(0).name());
        assertEquals("composite2", dynamicGraph.pass(1).name());
        assertEquals("composite4", dynamicGraph.pass(2).name());
        assertEquals("composite50", dynamicGraph.pass(3).name());
        assertEquals("composite99", dynamicGraph.pass(4).name());

        // Manager lifecycle (offline mode without GPU device)
        try (RtPipelineSsboManager manager = new RtPipelineSsboManager()) {
            assertFalse(manager.isAllocated());
            assertEquals(0, manager.width());
            assertEquals(0, manager.height());

            manager.ensure(1920, 1080);
            assertTrue(manager.isAllocated());
            assertEquals(1920, manager.width());
            assertEquals(1080, manager.height());

            // Resize to 2560x1440
            manager.ensure(2560, 1440);
            assertTrue(manager.isAllocated());
            assertEquals(2560, manager.width());
            assertEquals(1440, manager.height());

            // Exception on illegal dimensions
            assertThrows(IllegalArgumentException.class, () -> manager.ensure(-1, 1080));
            assertThrows(IllegalArgumentException.class, () -> manager.ensure(1920, 0));
        }
    }
}
