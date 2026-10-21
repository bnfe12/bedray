package io.github.andrexarlay.bedray.api.v1;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;

/** Camera and immutable occupancy paired for one dispatch; all coordinates are grid/camera relative. */
public final class ComputeSceneFrame {
    public static final int CAMERA_BYTES = 96;
    private final VoxelSceneSnapshot snapshot;
    private final float[] inverseViewProjection;
    private final float localOriginX, localOriginY, localOriginZ;
    private final float bobOriginX, bobOriginY, bobOriginZ;

    public ComputeSceneFrame(VoxelSceneSnapshot snapshot, float[] inverseViewProjection,
                              float localOriginX, float localOriginY, float localOriginZ,
                              float bobOriginX, float bobOriginY, float bobOriginZ) {
        this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(inverseViewProjection, "inverseViewProjection");
        if (inverseViewProjection.length != 16) throw new IllegalArgumentException("Expected 16 matrix floats");
        this.inverseViewProjection = inverseViewProjection.clone();
        for (float value : this.inverseViewProjection) requireFinite(value);
        requireFinite(localOriginX); requireFinite(localOriginY); requireFinite(localOriginZ);
        requireFinite(bobOriginX); requireFinite(bobOriginY); requireFinite(bobOriginZ);
        this.localOriginX = localOriginX;
        this.localOriginY = localOriginY;
        this.localOriginZ = localOriginZ;
        this.bobOriginX = bobOriginX;
        this.bobOriginY = bobOriginY;
        this.bobOriginZ = bobOriginZ;
    }

    private static void requireFinite(float value) {
        if (!Float.isFinite(value)) throw new IllegalArgumentException("Compute camera must be finite");
    }

    public VoxelSceneSnapshot snapshot() { return snapshot; }
    public float localOriginX() { return localOriginX; }
    public float localOriginY() { return localOriginY; }
    public float localOriginZ() { return localOriginZ; }
    public float bobOriginX() { return bobOriginX; }
    public float bobOriginY() { return bobOriginY; }
    public float bobOriginZ() { return bobOriginZ; }

    /** Column-major mat4, local origin vec4, bob origin vec4; preserves caller byte order. */
    public void writeCameraTo(ByteBuffer target) {
        Objects.requireNonNull(target, "target");
        if (target.remaining() < CAMERA_BYTES) throw new IllegalArgumentException("Camera buffer too small");
        ByteBuffer output = target.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        for (float value : inverseViewProjection) output.putFloat(value);
        output.putFloat(localOriginX).putFloat(localOriginY).putFloat(localOriginZ).putFloat(0);
        output.putFloat(bobOriginX).putFloat(bobOriginY).putFloat(bobOriginZ).putFloat(0);
        target.position(output.position());
    }
}
