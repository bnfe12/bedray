package io.github.andrexarlay.vulkancompat.shaderhost.dirtrt;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;

/**
 * Packs and serializes the 256-byte {@code CameraInfo} uniform buffer (UBO) for Dirt RT / Vulkanite ABI.
 * <p>
 * Layout (std430 alignment):
 * <ul>
 *   <li>Offset 0..63 (64B): {@code vec4 cornerRays[4]} (screen frustum rays in camera space)</li>
 *   <li>Offset 64..127 (64B): {@code mat4 viewInverse} (column-major inverse view matrix)</li>
 *   <li>Offset 128..131 (4B): {@code uint frameId}</li>
 *   <li>Offset 132..135 (4B): {@code uint flags} (eye in water, sky light, etc.)</li>
 *   <li>Offset 136..139 (4B): {@code int world_type} (0 = Overworld, -1 = Nether, 1 = End)</li>
 *   <li>Offset 140..143 (4B): {@code float pad0}</li>
 *   <li>Offset 144..155 (12B): {@code vec3 cameraPosition} (std430 alignment = 16B, offset 144)</li>
 *   <li>Offset 156..159 (4B): {@code float pad1}</li>
 *   <li>Offset 160..255 (96B): trailing padding to 256 bytes</li>
 * </ul>
 */
public final class DirtRtCameraUbo {

    public static final int UBO_SIZE = 256;

    public static final int CORNER_RAY_COUNT = 4;
    public static final int OFFSET_CORNER_RAYS = 0;
    public static final int SIZE_CORNER_RAYS = 64; // 4 * 16B

    public static final int OFFSET_VIEW_INVERSE = 64;
    public static final int SIZE_VIEW_INVERSE = 64; // 16 * 4B

    public static final int OFFSET_FRAME_ID = 128;
    public static final int SIZE_FRAME_ID = 4;

    public static final int OFFSET_FLAGS = 132;
    public static final int SIZE_FLAGS = 4;

    public static final int OFFSET_WORLD_TYPE = 136;
    public static final int SIZE_WORLD_TYPE = 4;

    public static final int OFFSET_PAD0 = 140;
    public static final int SIZE_PAD0 = 4;

    public static final int OFFSET_CAMERA_POSITION = 144;
    public static final int SIZE_CAMERA_POSITION = 12; // 3 * 4B

    public static final int OFFSET_PAD1 = 156;
    public static final int SIZE_PAD1 = 4;

    public static final int OFFSET_TRAILING_PAD = 160;
    public static final int SIZE_TRAILING_PAD = 96;

    public static final int WORLD_OVERWORLD = 0;
    public static final int WORLD_NETHER = -1;
    public static final int WORLD_END = 1;

    private final Vector4f[] cornerRays = new Vector4f[CORNER_RAY_COUNT];
    private final Matrix4f viewInverse = new Matrix4f();
    private int frameId = 0;
    private int flags = 0;
    private int worldType = WORLD_OVERWORLD;
    private float pad0 = 0.0f;
    private final Vector3f cameraPosition = new Vector3f();
    private float pad1 = 0.0f;

    public DirtRtCameraUbo() {
        for (int i = 0; i < CORNER_RAY_COUNT; i++) {
            cornerRays[i] = new Vector4f(0.0f, 0.0f, 0.0f, 0.0f);
        }
    }

    /**
     * Sets corner ray at specified index (0..3).
     */
    public DirtRtCameraUbo setCornerRay(int index, float x, float y, float z, float w) {
        if (index < 0 || index >= CORNER_RAY_COUNT) {
            throw new IndexOutOfBoundsException("Corner ray index out of range: " + index);
        }
        cornerRays[index].set(x, y, z, w);
        return this;
    }

    public DirtRtCameraUbo setCornerRay(int index, Vector4f ray) {
        Objects.requireNonNull(ray, "ray must not be null");
        return setCornerRay(index, ray.x, ray.y, ray.z, ray.w);
    }

    public DirtRtCameraUbo setCornerRay(int index, Vector3f ray, float w) {
        Objects.requireNonNull(ray, "ray must not be null");
        return setCornerRay(index, ray.x, ray.y, ray.z, w);
    }

    public Vector4f getCornerRay(int index) {
        if (index < 0 || index >= CORNER_RAY_COUNT) {
            throw new IndexOutOfBoundsException("Corner ray index out of range: " + index);
        }
        return new Vector4f(cornerRays[index]);
    }

    /**
     * Calculates the 4 corner rays from vertical FOV (in degrees) and aspect ratio (width / height).
     * <p>
     * Frustum ray targets in camera view space (looking along -Z):
     * <ul>
     *   <li>corner 0: (-xHalf, -yHalf, -1.0) [bottom-left]</li>
     *   <li>corner 1: (+xHalf, -yHalf, -1.0) [bottom-right]</li>
     *   <li>corner 2: (-xHalf, +yHalf, -1.0) [top-left]</li>
     *   <li>corner 3: (+xHalf, +yHalf, -1.0) [top-right]</li>
     * </ul>
     */
    public DirtRtCameraUbo computeCornerRays(float fovDegrees, float aspectRatio) {
        float halfFovRad = (float) Math.toRadians(fovDegrees * 0.5f);
        float yHalf = (float) Math.tan(halfFovRad);
        float xHalf = yHalf * aspectRatio;
        float z = -1.0f;

        cornerRays[0].set(-xHalf, -yHalf, z, 0.0f);
        cornerRays[1].set(+xHalf, -yHalf, z, 0.0f);
        cornerRays[2].set(-xHalf, +yHalf, z, 0.0f);
        cornerRays[3].set(+xHalf, +yHalf, z, 0.0f);
        return this;
    }

    /**
     * Calculates corner rays by unprojecting NDC corners using the inverse projection matrix.
     * Matches Vulkanite {@code invProjMatrix.transformProject(ndcX, ndcY, 0, 1, tmp)}.
     */
    public DirtRtCameraUbo computeCornerRays(Matrix4f projectionMatrix) {
        Objects.requireNonNull(projectionMatrix, "projectionMatrix must not be null");
        Matrix4f invProj = new Matrix4f(projectionMatrix).invert();
        Vector3f tmp = new Vector3f();

        invProj.transformProject(-1.0f, -1.0f, 0.0f, 1.0f, tmp);
        cornerRays[0].set(tmp.x, tmp.y, tmp.z, 0.0f);

        invProj.transformProject(+1.0f, -1.0f, 0.0f, 1.0f, tmp);
        cornerRays[1].set(tmp.x, tmp.y, tmp.z, 0.0f);

        invProj.transformProject(-1.0f, +1.0f, 0.0f, 1.0f, tmp);
        cornerRays[2].set(tmp.x, tmp.y, tmp.z, 0.0f);

        invProj.transformProject(+1.0f, +1.0f, 0.0f, 1.0f, tmp);
        cornerRays[3].set(tmp.x, tmp.y, tmp.z, 0.0f);
        return this;
    }

    public DirtRtCameraUbo setViewInverse(Matrix4f matrix) {
        Objects.requireNonNull(matrix, "matrix must not be null");
        this.viewInverse.set(matrix);
        return this;
    }

    public Matrix4f getViewInverse() {
        return new Matrix4f(viewInverse);
    }

    public DirtRtCameraUbo setFrameId(int frameId) {
        this.frameId = frameId;
        return this;
    }

    public int getFrameId() {
        return frameId;
    }

    public DirtRtCameraUbo setFlags(int flags) {
        this.flags = flags;
        return this;
    }

    public int getFlags() {
        return flags;
    }

    public DirtRtCameraUbo setWorldType(int worldType) {
        this.worldType = worldType;
        return this;
    }

    public int getWorldType() {
        return worldType;
    }

    public DirtRtCameraUbo setPad0(float pad0) {
        this.pad0 = pad0;
        return this;
    }

    public float getPad0() {
        return pad0;
    }

    public DirtRtCameraUbo setCameraPosition(float x, float y, float z) {
        this.cameraPosition.set(x, y, z);
        return this;
    }

    public DirtRtCameraUbo setCameraPosition(double x, double y, double z) {
        this.cameraPosition.set((float) x, (float) y, (float) z);
        return this;
    }

    public DirtRtCameraUbo setCameraPosition(Vector3f pos) {
        Objects.requireNonNull(pos, "pos must not be null");
        this.cameraPosition.set(pos);
        return this;
    }

    public Vector3f getCameraPosition() {
        return new Vector3f(cameraPosition);
    }

    public DirtRtCameraUbo setPad1(float pad1) {
        this.pad1 = pad1;
        return this;
    }

    public float getPad1() {
        return pad1;
    }

    /**
     * Serializes this UBO into the buffer at the given offset.
     *
     * @param buffer destination ByteBuffer, must have at least 256 bytes remaining from {@code offset}.
     * @param offset base offset in the buffer.
     */
    public void serialize(ByteBuffer buffer, int offset) {
        Objects.requireNonNull(buffer, "buffer must not be null");
        if (offset < 0 || buffer.capacity() - offset < UBO_SIZE) {
            throw new IllegalArgumentException("Insufficient buffer capacity for 256-byte CameraInfo UBO at offset " + offset);
        }

        ByteOrder originalOrder = buffer.order();
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        try {
            // 1. cornerRays[4] (64 bytes)
            for (int i = 0; i < CORNER_RAY_COUNT; i++) {
                int rayOff = offset + OFFSET_CORNER_RAYS + i * 16;
                buffer.putFloat(rayOff, cornerRays[i].x);
                buffer.putFloat(rayOff + 4, cornerRays[i].y);
                buffer.putFloat(rayOff + 8, cornerRays[i].z);
                buffer.putFloat(rayOff + 12, cornerRays[i].w);
            }

            // 2. viewInverse (64 bytes, column-major)
            float[] matValues = new float[16];
            viewInverse.get(matValues);
            for (int i = 0; i < 16; i++) {
                buffer.putFloat(offset + OFFSET_VIEW_INVERSE + i * 4, matValues[i]);
            }

            // 3. uint frameId (4 bytes)
            buffer.putInt(offset + OFFSET_FRAME_ID, frameId);

            // 4. uint flags (4 bytes)
            buffer.putInt(offset + OFFSET_FLAGS, flags);

            // 5. int world_type (4 bytes)
            buffer.putInt(offset + OFFSET_WORLD_TYPE, worldType);

            // 6. float pad0 (4 bytes)
            buffer.putFloat(offset + OFFSET_PAD0, pad0);

            // 7. vec3 cameraPosition (12 bytes)
            buffer.putFloat(offset + OFFSET_CAMERA_POSITION, cameraPosition.x);
            buffer.putFloat(offset + OFFSET_CAMERA_POSITION + 4, cameraPosition.y);
            buffer.putFloat(offset + OFFSET_CAMERA_POSITION + 8, cameraPosition.z);

            // 8. float pad1 (4 bytes)
            buffer.putFloat(offset + OFFSET_PAD1, pad1);

            // 9. trailing padding (96 bytes = 24 floats)
            for (int i = 0; i < SIZE_TRAILING_PAD; i += 4) {
                buffer.putFloat(offset + OFFSET_TRAILING_PAD + i, 0.0f);
            }
        } finally {
            buffer.order(originalOrder);
        }
    }

    /**
     * Serializes this UBO into the buffer at the current buffer position, advancing position by 256 bytes.
     */
    public void serialize(ByteBuffer buffer) {
        int pos = buffer.position();
        serialize(buffer, pos);
        buffer.position(pos + UBO_SIZE);
    }

    /**
     * Deserializes a DirtRtCameraUbo from a ByteBuffer at the given offset.
     */
    public static DirtRtCameraUbo deserialize(ByteBuffer buffer, int offset) {
        Objects.requireNonNull(buffer, "buffer must not be null");
        if (offset < 0 || buffer.capacity() - offset < UBO_SIZE) {
            throw new IllegalArgumentException("Insufficient buffer capacity for 256-byte CameraInfo UBO at offset " + offset);
        }

        ByteOrder originalOrder = buffer.order();
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        try {
            DirtRtCameraUbo ubo = new DirtRtCameraUbo();
            for (int i = 0; i < CORNER_RAY_COUNT; i++) {
                int rayOff = offset + OFFSET_CORNER_RAYS + i * 16;
                ubo.setCornerRay(i,
                        buffer.getFloat(rayOff),
                        buffer.getFloat(rayOff + 4),
                        buffer.getFloat(rayOff + 8),
                        buffer.getFloat(rayOff + 12));
            }

            float[] matValues = new float[16];
            for (int i = 0; i < 16; i++) {
                matValues[i] = buffer.getFloat(offset + OFFSET_VIEW_INVERSE + i * 4);
            }
            ubo.viewInverse.set(matValues);

            ubo.frameId = buffer.getInt(offset + OFFSET_FRAME_ID);
            ubo.flags = buffer.getInt(offset + OFFSET_FLAGS);
            ubo.worldType = buffer.getInt(offset + OFFSET_WORLD_TYPE);
            ubo.pad0 = buffer.getFloat(offset + OFFSET_PAD0);

            ubo.cameraPosition.set(
                    buffer.getFloat(offset + OFFSET_CAMERA_POSITION),
                    buffer.getFloat(offset + OFFSET_CAMERA_POSITION + 4),
                    buffer.getFloat(offset + OFFSET_CAMERA_POSITION + 8));

            ubo.pad1 = buffer.getFloat(offset + OFFSET_PAD1);
            return ubo;
        } finally {
            buffer.order(originalOrder);
        }
    }

    public static DirtRtCameraUbo deserialize(ByteBuffer buffer) {
        int pos = buffer.position();
        DirtRtCameraUbo ubo = deserialize(buffer, pos);
        buffer.position(pos + UBO_SIZE);
        return ubo;
    }
}
