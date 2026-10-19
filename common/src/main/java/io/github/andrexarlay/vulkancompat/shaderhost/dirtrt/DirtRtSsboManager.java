package io.github.andrexarlay.vulkancompat.shaderhost.dirtrt;

import io.github.andrexarlay.vulkancompat.rt.world.RtMemory;
import io.github.andrexarlay.vulkancompat.shaderhost.dirtrt.DirtRtPipelineConfig.OutputBuffer;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK10;

import java.nio.BufferOverflowException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;

/**
 * Manages Set 3 SSBO buffers and FrameData serialization for the Dirt RT / Vulkanite pipeline.
 * <p>
 * Set 3 consists of 5 storage buffers:
 * <ul>
 *   <li>Binding 0: {@code GeometryMaterialBuffer} (5x vec4 per pixel = 80B, or 128B aligned with 8x8 tiling)</li>
 *   <li>Binding 1: {@code FrameData} (1024B fixed uniform block)</li>
 *   <li>Binding 2: {@code DiffuseBuffer} (6x vec4 per pixel = 96B with 8x8 tiling)</li>
 *   <li>Binding 3: {@code ReflectBuffer} (4x vec4 per pixel = 64B with 8x8 tiling)</li>
 *   <li>Binding 4: {@code RefractBuffer} (4x vec4 per pixel = 64B with 8x8 tiling)</li>
 * </ul>
 * Also computes screen resolution allocation sizing and serializes std430-aligned 1024-byte {@code FrameData}.
 */
public final class DirtRtSsboManager implements AutoCloseable {

    public static final int FRAME_DATA_SIZE = 1024;

    public static final int GEOM_BYTES_PER_PIXEL_COMPACT = 80;   // 5x vec4
    public static final int GEOM_BYTES_PER_PIXEL_ALIGNED = 128;  // 8x vec4 aligned
    public static final int DIFFUSE_BYTES_PER_PIXEL = 96;        // 6x vec4
    public static final int REFLECT_BYTES_PER_PIXEL = 64;        // 4x vec4
    public static final int REFRACT_BYTES_PER_PIXEL = 64;        // 4x vec4

    // FrameData std430 field offsets & sizes (total 1024 bytes)
    public static final int OFFSET_RT_MODEL_VIEW = 0;
    public static final int SIZE_RT_MODEL_VIEW = 64; // mat4 = 16 floats * 4B

    public static final int OFFSET_RT_PREV_MODEL_VIEW = 64;
    public static final int SIZE_RT_PREV_MODEL_VIEW = 64;

    public static final int OFFSET_RT_PROJECTION = 128;
    public static final int SIZE_RT_PROJECTION = 64;

    public static final int OFFSET_RT_PREV_PROJECTION = 192;
    public static final int SIZE_RT_PREV_PROJECTION = 64;

    public static final int OFFSET_LIGHT_DIR = 256;
    public static final int SIZE_LIGHT_DIR = 12; // vec3 = 3 floats * 4B (16B aligned)

    public static final int OFFSET_AVG_EXPOSURE = 268;
    public static final int SIZE_AVG_EXPOSURE = 4; // float

    public static final int OFFSET_CAM_POS = 272;
    public static final int SIZE_CAM_POS = 12; // vec3 (16B aligned)

    public static final int OFFSET_DIV_AVG_EXPOSURE = 284;
    public static final int SIZE_DIV_AVG_EXPOSURE = 4; // float

    public static final int OFFSET_CAM_X = 288;
    public static final int SIZE_CAM_X = 12; // vec3 (16B aligned)

    public static final int OFFSET_TIME = 300;
    public static final int SIZE_TIME = 4; // float

    public static final int OFFSET_CAM_Y = 304;
    public static final int SIZE_CAM_Y = 12; // vec3 (16B aligned)

    public static final int OFFSET_DTIME = 316;
    public static final int SIZE_DTIME = 4; // float

    public static final int OFFSET_PREV_RAYTRACING_CAM_POS = 320;
    public static final int SIZE_PREV_RAYTRACING_CAM_POS = 12; // vec3 (16B aligned)

    public static final int OFFSET_RAIN_STRENGTH = 332;
    public static final int SIZE_RAIN_STRENGTH = 4; // float

    public static final int OFFSET_RESOLUTION = 336;
    public static final int SIZE_RESOLUTION = 8; // uvec2 = 2 uints * 4B (8B aligned)

    public static final int OFFSET_WORLD_TYPE = 344;
    public static final int SIZE_WORLD_TYPE = 4; // int

    public static final int OFFSET_FRAME_ID = 348;
    public static final int SIZE_FRAME_ID = 4; // int

    public static final int OFFSET_WET_STRENGTH = 352;
    public static final int SIZE_WET_STRENGTH = 4; // float

    public static final int OFFSET_WETNESS = 356;
    public static final int SIZE_WETNESS = 4; // float

    public static final int OFFSET_TRAILING_PAD = 360;
    public static final int SIZE_TRAILING_PAD = 664; // padding up to 1024 bytes (1024 - 360)

    // Sizing & Tiling helper functions
    public static int tileCountX(int width) {
        if (width <= 0) return 0;
        return (width + 7) >> 3;
    }

    public static int tileCountY(int height) {
        if (height <= 0) return 0;
        return (height + 7) >> 3;
    }

    /**
     * Total number of pixels padded to 8x8 tile boundaries.
     */
    public static long tiledPixelCount(int width, int height) {
        if (width <= 0 || height <= 0) return 0L;
        return ((long) tileCountX(width) * (long) tileCountY(height)) << 6;
    }

    public static long calculateGeometrySize(int width, int height) {
        return calculateGeometrySize(width, height, false);
    }

    public static long calculateGeometrySize(int width, int height, boolean aligned128B) {
        return tiledPixelCount(width, height) * (aligned128B ? GEOM_BYTES_PER_PIXEL_ALIGNED : GEOM_BYTES_PER_PIXEL_COMPACT);
    }

    public static long calculateGeometrySize(int width, int height, int bytesPerPixel) {
        return tiledPixelCount(width, height) * (long) bytesPerPixel;
    }

    public static long calculateDiffuseSize(int width, int height) {
        return tiledPixelCount(width, height) * (long) DIFFUSE_BYTES_PER_PIXEL;
    }

    public static long calculateReflectSize(int width, int height) {
        return tiledPixelCount(width, height) * (long) REFLECT_BYTES_PER_PIXEL;
    }

    public static long calculateRefractSize(int width, int height) {
        return tiledPixelCount(width, height) * (long) REFRACT_BYTES_PER_PIXEL;
    }

    public static long calculateFrameDataSize() {
        return FRAME_DATA_SIZE;
    }

    public static long calculateTotalSize(int width, int height) {
        return calculateTotalSize(width, height, false);
    }

    public static long calculateTotalSize(int width, int height, boolean geomAligned128B) {
        return calculateGeometrySize(width, height, geomAligned128B)
                + calculateFrameDataSize()
                + calculateDiffuseSize(width, height)
                + calculateReflectSize(width, height)
                + calculateRefractSize(width, height);
    }

    public static long calculateBufferSize(OutputBuffer buffer, int width, int height) {
        return calculateBufferSize(buffer, width, height, true);
    }

    public static long calculateBufferSize(OutputBuffer buffer, int width, int height, boolean tiled) {
        Objects.requireNonNull(buffer, "buffer must not be null");
        if (!tiled) {
            return buffer.calculateSizeBytes(width, height);
        }
        return switch (buffer) {
            case GEOMETRY_MATERIAL -> calculateGeometrySize(width, height, buffer.bytesPerPixel());
            case FRAME_DATA -> calculateFrameDataSize();
            case DIFFUSE -> calculateDiffuseSize(width, height);
            case REFLECT -> calculateReflectSize(width, height);
            case REFRACT -> calculateRefractSize(width, height);
        };
    }

    // =========================================================================
    // Instance fields & lifecycle
    // =========================================================================

    private final RtMemory memory;
    private final boolean useAligned128BGeometry;

    private RtMemory.Buf geometryMaterialBuffer;
    private RtMemory.Buf frameDataBuffer;
    private RtMemory.Buf diffuseBuffer;
    private RtMemory.Buf reflectBuffer;
    private RtMemory.Buf refractBuffer;

    private int currentWidth = 0;
    private int currentHeight = 0;

    public DirtRtSsboManager() {
        this(null, false);
    }

    public DirtRtSsboManager(RtMemory memory) {
        this(memory, false);
    }

    public DirtRtSsboManager(RtMemory memory, boolean useAligned128BGeometry) {
        this.memory = memory;
        this.useAligned128BGeometry = useAligned128BGeometry;
    }

    public RtMemory memory() {
        return memory;
    }

    public boolean useAligned128BGeometry() {
        return useAligned128BGeometry;
    }

    public int width() {
        return currentWidth;
    }

    public int height() {
        return currentHeight;
    }

    public boolean isAllocated() {
        if (memory == null) {
            return currentWidth > 0 && currentHeight > 0;
        }
        return geometryMaterialBuffer != null
                && frameDataBuffer != null
                && diffuseBuffer != null
                && reflectBuffer != null
                && refractBuffer != null;
    }

    public RtMemory.Buf geometryMaterialBuffer() {
        return geometryMaterialBuffer;
    }

    public RtMemory.Buf frameDataBuffer() {
        return frameDataBuffer;
    }

    public RtMemory.Buf diffuseBuffer() {
        return diffuseBuffer;
    }

    public RtMemory.Buf reflectBuffer() {
        return reflectBuffer;
    }

    public RtMemory.Buf refractBuffer() {
        return refractBuffer;
    }

    public RtMemory.Buf buffer(OutputBuffer buffer) {
        Objects.requireNonNull(buffer, "buffer must not be null");
        return switch (buffer) {
            case GEOMETRY_MATERIAL -> geometryMaterialBuffer;
            case FRAME_DATA -> frameDataBuffer;
            case DIFFUSE -> diffuseBuffer;
            case REFLECT -> reflectBuffer;
            case REFRACT -> refractBuffer;
        };
    }

    /**
     * Ensures all 5 SSBO buffers are allocated for the given resolution.
     */
    public void ensure(int width, int height) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Width and height must be positive: " + width + "x" + height);
        }
        if (currentWidth == width && currentHeight == height && isAllocated()) {
            return;
        }

        destroyBuffers();

        if (memory != null) {
            long geomSize = calculateGeometrySize(width, height, useAligned128BGeometry);
            long diffuseSize = calculateDiffuseSize(width, height);
            long reflectSize = calculateReflectSize(width, height);
            long refractSize = calculateRefractSize(width, height);

            int usage = VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT;

            geometryMaterialBuffer = memory.createBuffer("DirtRt_GeometryMaterialBuffer", geomSize, usage, false);
            frameDataBuffer = memory.createBuffer("DirtRt_FrameData", FRAME_DATA_SIZE, usage, true);
            diffuseBuffer = memory.createBuffer("DirtRt_DiffuseBuffer", diffuseSize, usage, false);
            reflectBuffer = memory.createBuffer("DirtRt_ReflectBuffer", reflectSize, usage, false);
            refractBuffer = memory.createBuffer("DirtRt_RefractBuffer", refractSize, usage, false);
        }

        currentWidth = width;
        currentHeight = height;
    }

    /**
     * Serializes FrameData into the host-visible FrameData SSBO buffer.
     */
    public void updateFrameData(FrameData data) {
        Objects.requireNonNull(data, "data must not be null");
        if (frameDataBuffer != null) {
            data.uploadToBuffer(frameDataBuffer);
        }
    }

    @Override
    public void close() {
        destroyBuffers();
        currentWidth = 0;
        currentHeight = 0;
    }

    private void destroyBuffers() {
        if (memory != null) {
            if (geometryMaterialBuffer != null) {
                memory.destroy(geometryMaterialBuffer);
                geometryMaterialBuffer = null;
            }
            if (frameDataBuffer != null) {
                memory.destroy(frameDataBuffer);
                frameDataBuffer = null;
            }
            if (diffuseBuffer != null) {
                memory.destroy(diffuseBuffer);
                diffuseBuffer = null;
            }
            if (reflectBuffer != null) {
                memory.destroy(reflectBuffer);
                reflectBuffer = null;
            }
            if (refractBuffer != null) {
                memory.destroy(refractBuffer);
                refractBuffer = null;
            }
        } else {
            geometryMaterialBuffer = null;
            frameDataBuffer = null;
            diffuseBuffer = null;
            reflectBuffer = null;
            refractBuffer = null;
        }
    }

    // =========================================================================
    // FrameData definition and serialization (1024B)
    // =========================================================================

    public static final class FrameData {
        private final Matrix4f rtModelView = new Matrix4f();
        private final Matrix4f rtPrevModelView = new Matrix4f();
        private final Matrix4f rtProjection = new Matrix4f();
        private final Matrix4f rtPrevProjection = new Matrix4f();

        private final Vector3f lightDir_global = new Vector3f(0.0f, 1.0f, 0.0f);
        private float avgExposure = 1.0f;

        private final Vector3f camPos = new Vector3f();
        private float div_avgExposure = 1.0f;

        private final Vector3f camX_global = new Vector3f(1.0f, 0.0f, 0.0f);
        private float time_global = 0.0f;

        private final Vector3f camY_global = new Vector3f(0.0f, 1.0f, 0.0f);
        private float dTime_global = 0.0f;

        private final Vector3f prevRaytracingCamPos = new Vector3f();
        private float rainStrength_global = 0.0f;

        private int resolutionWidth = 0;
        private int resolutionHeight = 0;

        private int world_type_global = 0;
        private int frame_id = 0;

        private float wetStrength_global = 0.0f;
        private float wetness_global = 0.0f;

        public FrameData() {}

        // --- Getters & Fluent Setters ---

        public Matrix4f rtModelView() { return rtModelView; }
        public FrameData setRtModelView(Matrix4f m) { this.rtModelView.set(m); return this; }

        public Matrix4f rtPrevModelView() { return rtPrevModelView; }
        public FrameData setRtPrevModelView(Matrix4f m) { this.rtPrevModelView.set(m); return this; }

        public Matrix4f rtProjection() { return rtProjection; }
        public FrameData setRtProjection(Matrix4f m) { this.rtProjection.set(m); return this; }

        public Matrix4f rtPrevProjection() { return rtPrevProjection; }
        public FrameData setRtPrevProjection(Matrix4f m) { this.rtPrevProjection.set(m); return this; }

        public Vector3f lightDir() { return lightDir_global; }
        public FrameData setLightDir(Vector3f v) { this.lightDir_global.set(v); return this; }
        public FrameData setLightDir(float x, float y, float z) { this.lightDir_global.set(x, y, z); return this; }

        public float avgExposure() { return avgExposure; }
        public FrameData setAvgExposure(float val) { this.avgExposure = val; return this; }

        public Vector3f camPos() { return camPos; }
        public FrameData setCamPos(Vector3f v) { this.camPos.set(v); return this; }
        public FrameData setCamPos(float x, float y, float z) { this.camPos.set(x, y, z); return this; }

        public float divAvgExposure() { return div_avgExposure; }
        public FrameData setDivAvgExposure(float val) { this.div_avgExposure = val; return this; }

        public Vector3f camX() { return camX_global; }
        public FrameData setCamX(Vector3f v) { this.camX_global.set(v); return this; }
        public FrameData setCamX(float x, float y, float z) { this.camX_global.set(x, y, z); return this; }

        public float time() { return time_global; }
        public FrameData setTime(float val) { this.time_global = val; return this; }

        public Vector3f camY() { return camY_global; }
        public FrameData setCamY(Vector3f v) { this.camY_global.set(v); return this; }
        public FrameData setCamY(float x, float y, float z) { this.camY_global.set(x, y, z); return this; }

        public float dTime() { return dTime_global; }
        public FrameData setDTime(float val) { this.dTime_global = val; return this; }

        public Vector3f prevRaytracingCamPos() { return prevRaytracingCamPos; }
        public FrameData setPrevRaytracingCamPos(Vector3f v) { this.prevRaytracingCamPos.set(v); return this; }
        public FrameData setPrevRaytracingCamPos(float x, float y, float z) { this.prevRaytracingCamPos.set(x, y, z); return this; }

        public float rainStrength() { return rainStrength_global; }
        public FrameData setRainStrength(float val) { this.rainStrength_global = val; return this; }

        public int resolutionWidth() { return resolutionWidth; }
        public int resolutionHeight() { return resolutionHeight; }
        public FrameData setResolution(int width, int height) {
            this.resolutionWidth = width;
            this.resolutionHeight = height;
            return this;
        }

        public int worldType() { return world_type_global; }
        public FrameData setWorldType(int val) { this.world_type_global = val; return this; }

        public int frameId() { return frame_id; }
        public FrameData setFrameId(int val) { this.frame_id = val; return this; }

        public float wetStrength() { return wetStrength_global; }
        public FrameData setWetStrength(float val) { this.wetStrength_global = val; return this; }

        public float wetness() { return wetness_global; }
        public FrameData setWetness(float val) { this.wetness_global = val; return this; }

        /**
         * Serializes FrameData into the target ByteBuffer at the specified offset.
         */
        public void serialize(ByteBuffer buffer, int offset) {
            Objects.requireNonNull(buffer, "buffer must not be null");
            if (buffer.capacity() < offset + FRAME_DATA_SIZE) {
                throw new BufferOverflowException();
            }
            ByteOrder prevOrder = buffer.order();
            buffer.order(ByteOrder.LITTLE_ENDIAN);
            try {
                writeMatrix(buffer, offset + OFFSET_RT_MODEL_VIEW, rtModelView);
                writeMatrix(buffer, offset + OFFSET_RT_PREV_MODEL_VIEW, rtPrevModelView);
                writeMatrix(buffer, offset + OFFSET_RT_PROJECTION, rtProjection);
                writeMatrix(buffer, offset + OFFSET_RT_PREV_PROJECTION, rtPrevProjection);

                writeVector3(buffer, offset + OFFSET_LIGHT_DIR, lightDir_global);
                buffer.putFloat(offset + OFFSET_AVG_EXPOSURE, avgExposure);

                writeVector3(buffer, offset + OFFSET_CAM_POS, camPos);
                buffer.putFloat(offset + OFFSET_DIV_AVG_EXPOSURE, div_avgExposure);

                writeVector3(buffer, offset + OFFSET_CAM_X, camX_global);
                buffer.putFloat(offset + OFFSET_TIME, time_global);

                writeVector3(buffer, offset + OFFSET_CAM_Y, camY_global);
                buffer.putFloat(offset + OFFSET_DTIME, dTime_global);

                writeVector3(buffer, offset + OFFSET_PREV_RAYTRACING_CAM_POS, prevRaytracingCamPos);
                buffer.putFloat(offset + OFFSET_RAIN_STRENGTH, rainStrength_global);

                buffer.putInt(offset + OFFSET_RESOLUTION, resolutionWidth);
                buffer.putInt(offset + OFFSET_RESOLUTION + 4, resolutionHeight);

                buffer.putInt(offset + OFFSET_WORLD_TYPE, world_type_global);
                buffer.putInt(offset + OFFSET_FRAME_ID, frame_id);
                buffer.putFloat(offset + OFFSET_WET_STRENGTH, wetStrength_global);
                buffer.putFloat(offset + OFFSET_WETNESS, wetness_global);

                // Zero out trailing pad
                for (int i = 0; i < SIZE_TRAILING_PAD; i++) {
                    buffer.put(offset + OFFSET_TRAILING_PAD + i, (byte) 0);
                }
            } finally {
                buffer.order(prevOrder);
            }
        }

        public void serialize(ByteBuffer buffer) {
            int pos = buffer.position();
            serialize(buffer, pos);
            buffer.position(pos + FRAME_DATA_SIZE);
        }

        public void deserialize(ByteBuffer buffer, int offset) {
            Objects.requireNonNull(buffer, "buffer must not be null");
            if (buffer.capacity() < offset + FRAME_DATA_SIZE) {
                throw new BufferUnderflowException();
            }
            ByteOrder prevOrder = buffer.order();
            buffer.order(ByteOrder.LITTLE_ENDIAN);
            try {
                readMatrix(buffer, offset + OFFSET_RT_MODEL_VIEW, rtModelView);
                readMatrix(buffer, offset + OFFSET_RT_PREV_MODEL_VIEW, rtPrevModelView);
                readMatrix(buffer, offset + OFFSET_RT_PROJECTION, rtProjection);
                readMatrix(buffer, offset + OFFSET_RT_PREV_PROJECTION, rtPrevProjection);

                readVector3(buffer, offset + OFFSET_LIGHT_DIR, lightDir_global);
                avgExposure = buffer.getFloat(offset + OFFSET_AVG_EXPOSURE);

                readVector3(buffer, offset + OFFSET_CAM_POS, camPos);
                div_avgExposure = buffer.getFloat(offset + OFFSET_DIV_AVG_EXPOSURE);

                readVector3(buffer, offset + OFFSET_CAM_X, camX_global);
                time_global = buffer.getFloat(offset + OFFSET_TIME);

                readVector3(buffer, offset + OFFSET_CAM_Y, camY_global);
                dTime_global = buffer.getFloat(offset + OFFSET_DTIME);

                readVector3(buffer, offset + OFFSET_PREV_RAYTRACING_CAM_POS, prevRaytracingCamPos);
                rainStrength_global = buffer.getFloat(offset + OFFSET_RAIN_STRENGTH);

                resolutionWidth = buffer.getInt(offset + OFFSET_RESOLUTION);
                resolutionHeight = buffer.getInt(offset + OFFSET_RESOLUTION + 4);

                world_type_global = buffer.getInt(offset + OFFSET_WORLD_TYPE);
                frame_id = buffer.getInt(offset + OFFSET_FRAME_ID);
                wetStrength_global = buffer.getFloat(offset + OFFSET_WET_STRENGTH);
                wetness_global = buffer.getFloat(offset + OFFSET_WETNESS);
            } finally {
                buffer.order(prevOrder);
            }
        }

        public void deserialize(ByteBuffer buffer) {
            int pos = buffer.position();
            deserialize(buffer, pos);
            buffer.position(pos + FRAME_DATA_SIZE);
        }

        public byte[] toByteArray() {
            byte[] bytes = new byte[FRAME_DATA_SIZE];
            ByteBuffer buf = ByteBuffer.wrap(bytes);
            serialize(buf, 0);
            return bytes;
        }

        public void uploadToBuffer(RtMemory.Buf target) {
            Objects.requireNonNull(target, "target buffer must not be null");
            if (target.mapped != 0L) {
                ByteBuffer mem = MemoryUtil.memByteBuffer(target.mapped, FRAME_DATA_SIZE);
                serialize(mem, 0);
            }
        }

        private static void writeMatrix(ByteBuffer buf, int offset, Matrix4f m) {
            buf.putFloat(offset + 0, m.m00());
            buf.putFloat(offset + 4, m.m01());
            buf.putFloat(offset + 8, m.m02());
            buf.putFloat(offset + 12, m.m03());
            buf.putFloat(offset + 16, m.m10());
            buf.putFloat(offset + 20, m.m11());
            buf.putFloat(offset + 24, m.m12());
            buf.putFloat(offset + 28, m.m13());
            buf.putFloat(offset + 32, m.m20());
            buf.putFloat(offset + 36, m.m21());
            buf.putFloat(offset + 40, m.m22());
            buf.putFloat(offset + 44, m.m23());
            buf.putFloat(offset + 48, m.m30());
            buf.putFloat(offset + 52, m.m31());
            buf.putFloat(offset + 56, m.m32());
            buf.putFloat(offset + 60, m.m33());
        }

        private static void readMatrix(ByteBuffer buf, int offset, Matrix4f m) {
            m.set(
                    buf.getFloat(offset + 0), buf.getFloat(offset + 4), buf.getFloat(offset + 8), buf.getFloat(offset + 12),
                    buf.getFloat(offset + 16), buf.getFloat(offset + 20), buf.getFloat(offset + 24), buf.getFloat(offset + 28),
                    buf.getFloat(offset + 32), buf.getFloat(offset + 36), buf.getFloat(offset + 40), buf.getFloat(offset + 44),
                    buf.getFloat(offset + 48), buf.getFloat(offset + 52), buf.getFloat(offset + 56), buf.getFloat(offset + 60)
            );
        }

        private static void writeVector3(ByteBuffer buf, int offset, Vector3f v) {
            buf.putFloat(offset + 0, v.x());
            buf.putFloat(offset + 4, v.y());
            buf.putFloat(offset + 8, v.z());
        }

        private static void readVector3(ByteBuffer buf, int offset, Vector3f v) {
            v.set(buf.getFloat(offset + 0), buf.getFloat(offset + 4), buf.getFloat(offset + 8));
        }
    }
}
