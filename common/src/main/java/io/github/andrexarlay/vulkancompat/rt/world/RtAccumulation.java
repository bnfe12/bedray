package io.github.andrexarlay.vulkancompat.rt.world;

import org.joml.Matrix4f;

/**
 * Temporal history policy of the RT passes (pure Java, unit-tested). The history is reprojected in the shader (previous
 * view-projection + camera delta, depth/normal test), so moving the camera does not discard it; only its length is capped:
 * <ul>
 *   <li>reset (count 0): first frame, resize, a setting / view change, accumulation disabled;</li>
 *   <li>{@link #CHANGE_CAP} frames: a rebuilt section near the camera or the sun moved this frame (lighting changed);</li>
 *   <li>{@link #MOVING_CAP} frames: the camera moved or turned this frame (reprojected history, limited ghosting);</li>
 *   <li>otherwise unlimited (up to {@link RtFrameParams#MAX_ACCUM}): static camera, the image converges.</li>
 * </ul>
 */
public final class RtAccumulation {
    public static final float MATRIX_EPS = 1.0e-5f;
    public static final double POS_EPS = 1.0e-4;
    public static final float SUN_EPS = 1.0e-3f;
    /** Longest history while the camera moves; {@code -Dvkrtx.rt.movingCap=N} for A/B runs (the stability autotest). */
    public static final int MOVING_CAP = Integer.getInteger("vkrtx.rt.movingCap", 32);
    public static final int CHANGE_CAP = 8;

    private final Matrix4f lastInvViewProj = new Matrix4f();
    private double lastX = Double.NaN;
    private double lastY;
    private double lastZ;
    private final float[] lastSun = new float[3];
    private int lastSettings;
    private int lastW;
    private int lastH;
    private int count;
    private int cap = RtFrameParams.MAX_ACCUM;
    private String lastResetReason = "first_frame";
    private String lastDetail = "";
    private final java.util.TreeMap<String, Integer> resetCounts = new java.util.TreeMap<>();

    /**
     * Returns the frames since the last reset (0 = start over) and sets {@link #cap()} for this frame. All "last" values
     * are compared with the previous frame.
     */
    public int next(Matrix4f invViewProj, double x, double y, double z, float[] sunDir, boolean sceneChanged,
                    int settingsHash, int width, int height, boolean enabled) {
        String reason = null;
        if (!enabled) {
            reason = "disabled";
        } else if (Double.isNaN(lastX)) {
            reason = "first_frame";
        } else if (width != lastW || height != lastH) {
            reason = "resize";
        } else if (settingsHash != lastSettings) {
            reason = "settings";
        }
        boolean moved = !Double.isNaN(lastX) && (Math.abs(x - lastX) > POS_EPS || Math.abs(y - lastY) > POS_EPS
            || Math.abs(z - lastZ) > POS_EPS || !invViewProj.equals(lastInvViewProj, MATRIX_EPS));
        boolean sunMoved = Math.abs(sunDir[0] - lastSun[0]) > SUN_EPS || Math.abs(sunDir[1] - lastSun[1]) > SUN_EPS
            || Math.abs(sunDir[2] - lastSun[2]) > SUN_EPS;
        if (reason != null) {
            resetCounts.merge(reason, 1, Integer::sum);
            lastResetReason = reason;
            count = 0;
            cap = RtFrameParams.MAX_ACCUM;
        } else {
            count = Math.min(count + 1, RtFrameParams.MAX_ACCUM);
            if (sceneChanged || sunMoved) {
                cap = CHANGE_CAP;
                count = Math.min(count, CHANGE_CAP);
                resetCounts.merge(sceneChanged ? "cap_scene" : "cap_sun", 1, Integer::sum);
            } else if (moved) {
                cap = MOVING_CAP;
                count = Math.min(count, MOVING_CAP);
                resetCounts.merge("cap_moving", 1, Integer::sum);
                lastDetail = String.format(java.util.Locale.ROOT, "d=(%.4f,%.4f,%.4f)", x - lastX, y - lastY, z - lastZ);
            } else {
                cap = RtFrameParams.MAX_ACCUM;
            }
        }
        lastInvViewProj.set(invViewProj);
        lastX = x;
        lastY = y;
        lastZ = z;
        System.arraycopy(sunDir, 0, lastSun, 0, 3);
        lastSettings = settingsHash;
        lastW = width;
        lastH = height;
        return count;
    }

    /** Longest history (frames) the shader may use this frame. */
    public int cap() {
        return cap;
    }

    public String lastResetReason() {
        return lastResetReason;
    }

    public String lastDetail() {
        return lastDetail;
    }

    /** Resets and caps per reason since the last call, e.g. {@code cap_moving:30,settings:1}; clears the counters. */
    public String drainResetCounts() {
        StringBuilder sb = new StringBuilder();
        resetCounts.forEach((k, v) -> sb.append(sb.length() == 0 ? "" : ",").append(k).append(':').append(v));
        resetCounts.clear();
        return sb.length() == 0 ? "none" : sb.toString();
    }

    public void reset() {
        lastX = Double.NaN;
        count = 0;
    }
}
