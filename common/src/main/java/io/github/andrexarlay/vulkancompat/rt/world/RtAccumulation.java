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
    private boolean hasDetail;
    private double lastDetailX;
    private double lastDetailY;
    private double lastDetailZ;
    // Reset / cap counters of this window, in the alphabetical order TreeMap used to print them. Plain ints: next() runs on the
    // render thread every frame, and a Map merge boxed an Integer and walked the tree on every moving frame.
    private static final String[] RESET_REASONS = {
        "cap_moving", "cap_scene", "cap_sun", "disabled", "first_frame", "resize", "settings"};
    private static final int R_MOVING = 0;
    private static final int R_SCENE = 1;
    private static final int R_SUN = 2;
    private static final int R_DISABLED = 3;
    private static final int R_FIRST = 4;
    private static final int R_RESIZE = 5;
    private static final int R_SETTINGS = 6;
    private final int[] resetCounts = new int[RESET_REASONS.length];

    /**
     * Share (%) of the counted hit pixels whose reprojected history passed the distance / normal test, or -1 when this frame
     * does not accumulate. The shader increments the counter only from temporalAccumulate (the {@code VKB_ACCUM} variant), so
     * without accumulation the readback is a hard 0 that reads as "the history is dead" while nothing was accumulated at all.
     */
    public static double acceptedPct(int accepted, int hits, boolean accumulate) {
        if (!accumulate || hits <= 0) {
            return -1.0;
        }
        return 100.0 * accepted / hits;
    }

    /**
     * Returns the frames since the last reset (0 = start over) and sets {@link #cap()} for this frame. All "last" values
     * are compared with the previous frame.
     */
    public int next(Matrix4f invViewProj, double x, double y, double z, float[] sunDir, boolean sceneChanged,
                    int settingsHash, int width, int height, boolean enabled) {
        int reason = -1;
        if (!enabled) {
            reason = R_DISABLED;
        } else if (Double.isNaN(lastX)) {
            reason = R_FIRST;
        } else if (width != lastW || height != lastH) {
            reason = R_RESIZE;
        } else if (settingsHash != lastSettings) {
            reason = R_SETTINGS;
        }
        boolean moved = !Double.isNaN(lastX) && (Math.abs(x - lastX) > POS_EPS || Math.abs(y - lastY) > POS_EPS
            || Math.abs(z - lastZ) > POS_EPS || !invViewProj.equals(lastInvViewProj, MATRIX_EPS));
        boolean sunMoved = Math.abs(sunDir[0] - lastSun[0]) > SUN_EPS || Math.abs(sunDir[1] - lastSun[1]) > SUN_EPS
            || Math.abs(sunDir[2] - lastSun[2]) > SUN_EPS;
        if (reason >= 0) {
            resetCounts[reason]++;
            lastResetReason = RESET_REASONS[reason];
            count = 0;
            cap = RtFrameParams.MAX_ACCUM;
            // a reset has no camera delta associated with it: the log prints the pair, and a stale one would read as the cause
            hasDetail = false;
        } else {
            count = Math.min(count + 1, RtFrameParams.MAX_ACCUM);
            if (sceneChanged || sunMoved) {
                cap = CHANGE_CAP;
                count = Math.min(count, CHANGE_CAP);
                resetCounts[sceneChanged ? R_SCENE : R_SUN]++;
            } else if (moved) {
                cap = MOVING_CAP;
                count = Math.min(count, MOVING_CAP);
                resetCounts[R_MOVING]++;
                hasDetail = true;
                lastDetailX = x - lastX;
                lastDetailY = y - lastY;
                lastDetailZ = z - lastZ;
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

    /** Camera delta of the last moving frame, formatted on demand (the caller logs it once per second at most). */
    public String lastDetail() {
        return hasDetail ? String.format(java.util.Locale.ROOT, "d=(%.4f,%.4f,%.4f)",
            lastDetailX, lastDetailY, lastDetailZ) : "";
    }

    /** Resets and caps per reason since the last call, e.g. {@code cap_moving:30,settings:1}; clears the counters. */
    public String drainResetCounts() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < resetCounts.length; i++) {
            if (resetCounts[i] == 0) {
                continue;
            }
            sb.append(sb.length() == 0 ? "" : ",").append(RESET_REASONS[i]).append(':').append(resetCounts[i]);
            resetCounts[i] = 0;
        }
        return sb.length() == 0 ? "none" : sb.toString();
    }

    public void reset() {
        lastX = Double.NaN;
        count = 0;
    }
}
