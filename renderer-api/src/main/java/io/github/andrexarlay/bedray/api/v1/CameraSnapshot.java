package io.github.andrexarlay.bedray.api.v1;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;

/** Double world origin plus column-major inverse matrices. Projection includes view bob, excludes jitter.
 * Previous camera is supplied by the consumer only within the same world session.
 */
public final class CameraSnapshot {
    private final double x, y, z;
    private final float[] inverseProjection, inverseView;
    private final float[] rayOriginOffset;
    public CameraSnapshot(double x, double y, double z, float[] inverseProjection, float[] inverseView) {
        this(x,y,z,inverseProjection,inverseView,new float[3]);
    }
    public CameraSnapshot(double x, double y, double z, float[] inverseProjection, float[] inverseView,
                          float[] rayOriginOffset) {
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z))
            throw new IllegalArgumentException("Invalid camera origin");
        this.x = x; this.y = y; this.z = z;
        this.inverseProjection = matrix(inverseProjection);
        this.inverseView = matrix(inverseView);
        Objects.requireNonNull(rayOriginOffset);
        if (rayOriginOffset.length != 3) throw new IllegalArgumentException("Expected a 3D camera offset");
        this.rayOriginOffset = rayOriginOffset.clone();
        for (float value : this.rayOriginOffset) if (!Float.isFinite(value)) throw new IllegalArgumentException("Invalid camera offset");
    }
    private static float[] matrix(float[] values) {
        Objects.requireNonNull(values);
        if (values.length != 16) throw new IllegalArgumentException("Expected a 4x4 matrix");
        float[] copy = values.clone();
        for (float value : copy) if (!Float.isFinite(value)) throw new IllegalArgumentException("Invalid camera matrix");
        return copy;
    }
    public double x() { return x; }
    public double y() { return y; }
    public double z() { return z; }
    public float[] inverseProjection() { return inverseProjection.clone(); }
    public float[] inverseView() { return inverseView.clone(); }
    public float[] rayOriginOffset() { return rayOriginOffset.clone(); }

    /** Writes camera fields of the 112-byte push block, leaving control bytes80..95 intact.
     * Matrices are column-major. Caller position, limit and byte order are preserved.
     */
    public void writePushConstants(ByteBuffer target, int originX, int originY, int originZ) {
        if (target.remaining() < 112) throw new IllegalArgumentException("Camera push block too small");
        ByteBuffer output = target.slice().order(ByteOrder.LITTLE_ENDIAN);
        for (int col=0; col<4; col++) for (int row=0; row<4; row++) {
            float value = 0;
            for (int k=0; k<4; k++) value += inverseView[k*4+row]*inverseProjection[col*4+k];
            if (!Float.isFinite(value)) throw new IllegalArgumentException("Nonfinite combined camera matrix");
            output.putFloat((col*4+row)*4,value);
        }
        float localX=(float)(x-originX), localY=(float)(y-originY), localZ=(float)(z-originZ);
        if (!Float.isFinite(localX) || !Float.isFinite(localY) || !Float.isFinite(localZ))
            throw new IllegalArgumentException("Nonfinite local camera origin");
        output.putFloat(64,localX).putFloat(68,localY).putFloat(72,localZ).putFloat(76,0);
        for(int axis=0;axis<3;axis++) output.putFloat(96+axis*4,rayOriginOffset[axis]);
        output.putFloat(108,0);
    }
}
