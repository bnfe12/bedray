package io.github.andrexarlay.vulkancompat.rtx.upscaler;

import io.github.andrexarlay.vulkancompat.rtx.WorldTargetManager;
import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages sub-pixel camera jitter for temporal anti-aliasing, super resolution
 * (DLSS, FSR, XeSS), and frame generation.
 * <p>
 * Implements a Halton sequence (bases 2, 3; at least 16 phases, more for aggressive super-resolution modes), calculating sub-pixel offsets
 * in the range [-0.5, +0.5] pixel and projecting them to NDC space:
 * <pre>
 *   jitterX_ndc = ((halton_x - 0.5) / renderWidth) * 2.0
 *   jitterY_ndc = ((halton_y - 0.5) / renderHeight) * 2.0
 * </pre>
 * Offsets are injected into projection matrix elements {@code m20} and {@code m21}
 * strictly during the 3D world phase, leaving vanilla GUI and HUD 100% native and jitter-free.
 */
public final class CameraJitterManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("Bedray/CameraJitterManager");

    /** Minimum number of Halton phases (the real count is max(16, ceil(8 * scale^2)), see {@link #phaseCountFor(double)}). */
    public static final int PHASE_COUNT = 16;

    private static float currentJitterNdcX = 0.0f;
    private static float currentJitterNdcY = 0.0f;
    private static float prevJitterNdcX = 0.0f;
    private static float prevJitterNdcY = 0.0f;

    private static float currentPixelJitterX = 0.0f;
    private static float currentPixelJitterY = 0.0f;
    private static float prevPixelJitterX = 0.0f;
    private static float prevPixelJitterY = 0.0f;

    private static int phaseIndex = -1;
    private static int phaseCount;
    private static boolean active = false;

    private CameraJitterManager() {}

    /** True only when the current world phase prepared a jitter sample. */
    public static boolean isJitterActive() {
        return active && WorldTargetManager.isInWorldPhase();
    }

    /** Invalidates offsets for this render while retaining the sequence across consecutive world frames. */
    public static void beginFrame() {
        if (!active && phaseIndex >= 0) {
            reset();
        }
        active = false;
    }

    /** Minimum Halton length for a super-resolution scale (XeSS guide: 8 * scale^2), never below {@link #PHASE_COUNT}. */
    public static int phaseCountFor(double scale) {
        return Math.max(PHASE_COUNT, (int) Math.ceil(8.0 * scale * scale - 1e-9));
    }

    /** Prepares a new sample for a frame with a temporal consumer. */
    public static void advanceFrame(int renderWidth, int renderHeight, int requestedPhaseCount) {
        if (renderWidth <= 0 || renderHeight <= 0) {
            reset();
            return;
        }
        boolean continuing = phaseIndex >= 0;
        phaseCount = Math.max(PHASE_COUNT, requestedPhaseCount);
        phaseIndex = (phaseIndex + 1) % phaseCount;
        active = true;
        prevJitterNdcX = currentJitterNdcX;
        prevJitterNdcY = currentJitterNdcY;
        prevPixelJitterX = currentPixelJitterX;
        prevPixelJitterY = currentPixelJitterY;

        // Halton sequence (base 2 for X, base 3 for Y)
        float hx = HaltonJitter.halton(phaseIndex + 1, 2);
        float hy = HaltonJitter.halton(phaseIndex + 1, 3);

        // Pixel-space jitter in [-0.5, +0.5]
        currentPixelJitterX = hx - 0.5f;
        currentPixelJitterY = hy - 0.5f;

        // Convert to NDC space offset: (pixel / res) * 2.0
        currentJitterNdcX = (currentPixelJitterX / (float) renderWidth) * 2.0f;
        currentJitterNdcY = (currentPixelJitterY / (float) renderHeight) * 2.0f;

        if (!continuing) {
            prevJitterNdcX = currentJitterNdcX;
            prevJitterNdcY = currentJitterNdcY;
            prevPixelJitterX = currentPixelJitterX;
            prevPixelJitterY = currentPixelJitterY;
            LOGGER.info("[VKB-AUTOTEST] jitter_state=active render={}x{}", renderWidth, renderHeight);
        }
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("jitter_phase={} pixel=({}, {}) ndc=({}, {})",
                phaseIndex, currentPixelJitterX, currentPixelJitterY, currentJitterNdcX, currentJitterNdcY);
        }
    }

    /**
     * Injects the current sub-pixel jitter into the projection matrix elements {@code m20} (col 2, row 0)
     * and {@code m21} (col 2, row 1).
     *
     * @param projMatrix Original unjittered projection matrix
     * @return New projection matrix with jitter applied, or original if jitter is inactive
     */
    public static Matrix4f applyJitter(Matrix4f projMatrix) {
        if (!isJitterActive() || projMatrix == null) {
            return projMatrix;
        }
        Matrix4f jittered = new Matrix4f(projMatrix);
        jittered.m20(projMatrix.m20() + currentJitterNdcX);
        jittered.m21(projMatrix.m21() + currentJitterNdcY);
        return jittered;
    }

    /**
     * Resets jitter state to zero.
     */
    public static void reset() {
        currentJitterNdcX = 0.0f;
        currentJitterNdcY = 0.0f;
        prevJitterNdcX = 0.0f;
        prevJitterNdcY = 0.0f;
        currentPixelJitterX = 0.0f;
        currentPixelJitterY = 0.0f;
        prevPixelJitterX = 0.0f;
        prevPixelJitterY = 0.0f;
        phaseIndex = -1;
        phaseCount = 0;
        active = false;
    }

    public static float getCurrentJitterNdcX() {
        return active ? currentJitterNdcX : 0.0f;
    }

    public static float getCurrentJitterNdcY() {
        return active ? currentJitterNdcY : 0.0f;
    }

    public static float getPrevJitterNdcX() {
        return active ? prevJitterNdcX : 0.0f;
    }

    public static float getPrevJitterNdcY() {
        return active ? prevJitterNdcY : 0.0f;
    }

    public static float getCurrentPixelJitterX() {
        return active ? currentPixelJitterX : 0.0f;
    }

    public static float getCurrentPixelJitterY() {
        return active ? currentPixelJitterY : 0.0f;
    }

    public static float getPrevPixelJitterX() {
        return active ? prevPixelJitterX : 0.0f;
    }

    public static float getPrevPixelJitterY() {
        return active ? prevPixelJitterY : 0.0f;
    }

    public static int getPhaseIndex() {
        return active ? phaseIndex : -1;
    }

    public static int getPhaseCount() {
        return active ? phaseCount : 0;
    }

    public static boolean isActive() {
        return active;
    }

    public static Vector2f getCurrentNdcVector() {
        return new Vector2f(getCurrentJitterNdcX(), getCurrentJitterNdcY());
    }

    public static Vector2f getPrevNdcVector() {
        return new Vector2f(getPrevJitterNdcX(), getPrevJitterNdcY());
    }
}
