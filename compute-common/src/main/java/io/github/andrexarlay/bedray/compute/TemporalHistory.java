package io.github.andrexarlay.bedray.compute;

import io.github.andrexarlay.bedray.api.v1.CameraSnapshot;
import io.github.andrexarlay.bedray.api.v1.LightingFrame;
import io.github.andrexarlay.bedray.compute.voxel.VoxelWorldUpload;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * CPU half of temporal accumulation. It supplies the previous frame's forward view-projection, its
 * camera origin and eye in the current grid coordinates, and a per-frame history cap. The GPU then
 * reprojects every surface point and accepts history only where distance and normal agree.
 *
 * The cap controls the effective sample count of exponential averaging: 64 while camera and scene are
 * unchanged, 32 during camera motion and 16 after scene/lighting changes. Distant streaming must not
 * hold every visible pixel to the former eight-frame window. Depth/normal rejection still decides
 * whether a particular history sample can be reused; these caps are response/noise tradeoffs.
 */
final class TemporalHistory {
    static final int STATIC_CAP = 64, CHANGED_CAP = 16, MOTION_CAP = 32;
    private static final int LIGHTING_COOLDOWN = 8;

    private float[] previousInverse;
    private double previousX, previousY, previousZ;
    private float[] previousOffset;

    private float[] pendingInverse;
    private double pendingX, pendingY, pendingZ;
    private float[] pendingOffset;

    private final float[] previousForward = new float[16];
    private final float[] previousCamera = new float[3];
    private boolean valid, motion, parity;
    private int cap;
    private long frameCounter, steadyFrames;
    private String lastReset = "first frame";
    private float[] lightingAnchor;
    private int lightingCooldown;

    /** Compare against an anchor, not the previous frame: gradual daylight is allowed to accumulate,
     * while weather/time/exposure changes shorten history for several frames. Large discontinuities
     * reject the old history entirely. No images are blurred and no additional rays are required. */
    void environment(LightingFrame lighting) {
        if (lighting == null) return;
        if (lightingAnchor == null) {
            lightingAnchor = new float[LightingFrame.FLOAT_COUNT];
            for (int i = 0; i < lightingAnchor.length; i++) lightingAnchor[i] = lighting.component(i);
            return;
        }
        float difference = 0;
        for (int i = 0; i < lightingAnchor.length; i++)
            difference = Math.max(difference, Math.abs(lighting.component(i) - lightingAnchor[i]));
        if (difference < 0.025f) return;
        for (int i = 0; i < lightingAnchor.length; i++) lightingAnchor[i] = lighting.component(i);
        lightingCooldown = LIGHTING_COOLDOWN;
        if (difference >= 0.25f) {
            previousInverse = null;
            lastReset = "lighting discontinuity";
        }
    }

    void invalidate() {
        previousInverse = null;
        lastReset = "new session";
    }

    void prepare(CameraSnapshot camera, VoxelWorldUpload origin, boolean sceneChanged, boolean shaded) {
        if(origin==null){prepare(camera,0,0,0,sceneChanged,shaded);return;}
        prepare(camera,origin.originX(),origin.originY(),origin.originZ(),sceneChanged,shaded);
    }

    void prepare(CameraSnapshot camera,int originX,int originY,int originZ,boolean sceneChanged,boolean shaded) {
        valid = false;
        motion = true;
        cap = CHANGED_CAP;
        pendingInverse = null;
        if (camera == null) return;
        ByteBuffer push = ByteBuffer.allocate(112).order(ByteOrder.LITTLE_ENDIAN);
        camera.writePushConstants(push, originX, originY, originZ);
        float[] inverse = new float[16];
        for (int i = 0; i < 16; i++) inverse[i] = push.getFloat(i * 4);
        pendingInverse = inverse;
        pendingX = camera.x(); pendingY = camera.y(); pendingZ = camera.z();
        pendingOffset = camera.rayOriginOffset();
        if (!shaded) {
            previousInverse = null;
            lastReset = "debug view";
            return;
        }
        if (previousInverse == null) return;
        if (!invert(previousInverse, previousForward)) {
            lastReset = "singular previous projection";
            return;
        }
        double cx = previousX - originX, cy = previousY - originY, cz = previousZ - originZ;
        previousCamera[0] = (float) cx; previousCamera[1] = (float) cy; previousCamera[2] = (float) cz;
        if (!Float.isFinite(previousCamera[0]) || !Float.isFinite(previousCamera[1]) || !Float.isFinite(previousCamera[2])) {
            lastReset = "nonfinite previous camera";
            return;
        }
        valid = true;
        motion = !Arrays.equals(previousInverse, inverse) || previousX != pendingX || previousY != pendingY
            || previousZ != pendingZ || !Arrays.equals(previousOffset, pendingOffset);
        cap = sceneChanged || lightingCooldown > 0 ? CHANGED_CAP : motion ? MOTION_CAP : STATIC_CAP;
        steadyFrames = cap == STATIC_CAP ? steadyFrames + 1 : 0;
    }

    /** 112 bytes after the LightingFrame: mat4, previous camera, previous eye, uvec4 info. */
    void writeFrame(ByteBuffer target, int emitterCount) {
        for (int i = 0; i < 16; i++) target.putFloat(valid ? previousForward[i] : 0);
        for (int axis = 0; axis < 3; axis++) target.putFloat(valid ? previousCamera[axis] : 0);
        target.putFloat(0);
        for (int axis = 0; axis < 3; axis++)
            target.putFloat(valid ? previousCamera[axis] + previousOffset[axis] : 0);
        target.putFloat(0);
        target.putInt(emitterCount).putInt(valid ? 1 : 0).putInt(cap).putInt(motion ? 1 : 0);
    }

    void recorded() {
        if (pendingInverse != null) {
            previousInverse = pendingInverse;
            previousX = pendingX; previousY = pendingY; previousZ = pendingZ;
            previousOffset = pendingOffset;
        }
        parity = !parity;
        frameCounter++;
        if (lightingCooldown > 0) lightingCooldown--;
    }

    boolean parity() { return parity; }
    long frameCounter() { return frameCounter; }

    String status() {
        if (!valid) return "reset (" + lastReset + ")";
        return cap == STATIC_CAP
            ? "steady, window " + Math.min(steadyFrames + 1, STATIC_CAP) + "/" + STATIC_CAP + " frames"
            : (lightingCooldown > 0 ? "lighting update" : motion ? "moving" : "scene update")
                + ", window " + cap + " frames";
    }

    /** Column-major 4x4 inverse in double precision; false for a singular or nonfinite result. */
    static boolean invert(float[] m, float[] out) {
        double[] a = new double[16];
        for (int i = 0; i < 16; i++) a[i] = m[i];
        double[] inv = new double[16];
        inv[0] = a[5]*a[10]*a[15] - a[5]*a[11]*a[14] - a[9]*a[6]*a[15] + a[9]*a[7]*a[14] + a[13]*a[6]*a[11] - a[13]*a[7]*a[10];
        inv[4] = -a[4]*a[10]*a[15] + a[4]*a[11]*a[14] + a[8]*a[6]*a[15] - a[8]*a[7]*a[14] - a[12]*a[6]*a[11] + a[12]*a[7]*a[10];
        inv[8] = a[4]*a[9]*a[15] - a[4]*a[11]*a[13] - a[8]*a[5]*a[15] + a[8]*a[7]*a[13] + a[12]*a[5]*a[11] - a[12]*a[7]*a[9];
        inv[12] = -a[4]*a[9]*a[14] + a[4]*a[10]*a[13] + a[8]*a[5]*a[14] - a[8]*a[6]*a[13] - a[12]*a[5]*a[10] + a[12]*a[6]*a[9];
        inv[1] = -a[1]*a[10]*a[15] + a[1]*a[11]*a[14] + a[9]*a[2]*a[15] - a[9]*a[3]*a[14] - a[13]*a[2]*a[11] + a[13]*a[3]*a[10];
        inv[5] = a[0]*a[10]*a[15] - a[0]*a[11]*a[14] - a[8]*a[2]*a[15] + a[8]*a[3]*a[14] + a[12]*a[2]*a[11] - a[12]*a[3]*a[10];
        inv[9] = -a[0]*a[9]*a[15] + a[0]*a[11]*a[13] + a[8]*a[1]*a[15] - a[8]*a[3]*a[13] - a[12]*a[1]*a[11] + a[12]*a[3]*a[9];
        inv[13] = a[0]*a[9]*a[14] - a[0]*a[10]*a[13] - a[8]*a[1]*a[14] + a[8]*a[2]*a[13] + a[12]*a[1]*a[10] - a[12]*a[2]*a[9];
        inv[2] = a[1]*a[6]*a[15] - a[1]*a[7]*a[14] - a[5]*a[2]*a[15] + a[5]*a[3]*a[14] + a[13]*a[2]*a[7] - a[13]*a[3]*a[6];
        inv[6] = -a[0]*a[6]*a[15] + a[0]*a[7]*a[14] + a[4]*a[2]*a[15] - a[4]*a[3]*a[14] - a[12]*a[2]*a[7] + a[12]*a[3]*a[6];
        inv[10] = a[0]*a[5]*a[15] - a[0]*a[7]*a[13] - a[4]*a[1]*a[15] + a[4]*a[3]*a[13] + a[12]*a[1]*a[7] - a[12]*a[3]*a[5];
        inv[14] = -a[0]*a[5]*a[14] + a[0]*a[6]*a[13] + a[4]*a[1]*a[14] - a[4]*a[2]*a[13] - a[12]*a[1]*a[6] + a[12]*a[2]*a[5];
        inv[3] = -a[1]*a[6]*a[11] + a[1]*a[7]*a[10] + a[5]*a[2]*a[11] - a[5]*a[3]*a[10] - a[9]*a[2]*a[7] + a[9]*a[3]*a[6];
        inv[7] = a[0]*a[6]*a[11] - a[0]*a[7]*a[10] - a[4]*a[2]*a[11] + a[4]*a[3]*a[10] + a[8]*a[2]*a[7] - a[8]*a[3]*a[6];
        inv[11] = -a[0]*a[5]*a[11] + a[0]*a[7]*a[9] + a[4]*a[1]*a[11] - a[4]*a[3]*a[9] - a[8]*a[1]*a[7] + a[8]*a[3]*a[5];
        inv[15] = a[0]*a[5]*a[10] - a[0]*a[6]*a[9] - a[4]*a[1]*a[10] + a[4]*a[2]*a[9] + a[8]*a[1]*a[6] - a[8]*a[2]*a[5];
        double det = a[0]*inv[0] + a[1]*inv[4] + a[2]*inv[8] + a[3]*inv[12];
        if (!Double.isFinite(det) || det == 0) return false;
        for (int i = 0; i < 16; i++) {
            float value = (float) (inv[i] / det);
            if (!Float.isFinite(value)) return false;
            out[i] = value;
        }
        return true;
    }
}
