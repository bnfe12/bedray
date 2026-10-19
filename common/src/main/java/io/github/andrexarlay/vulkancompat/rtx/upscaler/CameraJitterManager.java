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
    private static boolean active = false;

    private CameraJitterManager() {}

    /**
     * Checks whether camera jittering is enabled and required by the active upscaler backend.
     * Jitter is strictly disabled for {@link UpscalerBackend#BILINEAR} and when scaling is off.
     */
    public static boolean isJitterActive() {
        return temporalConsumerActive() && isScalingActive() && WorldTargetManager.isInWorldPhase();
    }

    /**
     * True when a temporal upscaler needs sub-pixel jitter. This includes DLAA / native-AA (render == display): the DLSS
     * Programming Guide requires jitter for every temporal mode (the accumulation IS the anti-aliasing); without it DLAA
     * degenerates to an un-jittered history blend with unstable edges. (Previously gated on scale > 1.0001: bug.)
     */
    public static boolean isScalingActive() {
        return temporalConsumerActive();
    }

    /**
     * True when a real temporal upscaler consumes jitter and motion vectors: an initialised Intel XeSS, NVIDIA DLSS or
     * AMD FSR context ({@link TemporalUpscaler#isActive()}).
     */
    public static boolean temporalConsumerActive() {
        return TemporalUpscaler.isActive();
    }

    /** Minimum Halton length for a super-resolution scale (XeSS guide: 8 * scale^2), never below {@link #PHASE_COUNT}. */
    public static int phaseCountFor(double scale) {
        return Math.max(PHASE_COUNT, (int) Math.ceil(8.0 * scale * scale - 1e-9));
    }

    /**
     * Advances to the next phase in the 16-phase Halton sequence and computes current offsets.
     *
     * @param renderWidth  Render width of the 3D target
     * @param renderHeight Render height of the 3D target
     * @param phaseCount   Length of the Halton cycle, see {@link #phaseCountFor(double)}
     */
    public static void advanceFrame(int renderWidth, int renderHeight, int phaseCount) {
        if (!temporalConsumerActive()) {
            return;
        }
        prevJitterNdcX = currentJitterNdcX;
        prevJitterNdcY = currentJitterNdcY;
        prevPixelJitterX = currentPixelJitterX;
        prevPixelJitterY = currentPixelJitterY;

        final boolean wasActive = active;
        if (!isJitterActive() || !isScalingActive() || renderWidth <= 0 || renderHeight <= 0) {
            if (wasActive) {
                LOGGER.info("[VKB-AUTOTEST] jitter_state=inactive");
            }
            currentJitterNdcX = 0.0f;
            currentJitterNdcY = 0.0f;
            currentPixelJitterX = 0.0f;
            currentPixelJitterY = 0.0f;
            active = false;
            return;
        }

        active = true;
        phaseIndex = (phaseIndex + 1) % Math.max(PHASE_COUNT, phaseCount);

        // Halton sequence (base 2 for X, base 3 for Y)
        float hx = HaltonJitter.halton(phaseIndex + 1, 2);
        float hy = HaltonJitter.halton(phaseIndex + 1, 3);

        // Pixel-space jitter in [-0.5, +0.5]
        currentPixelJitterX = hx - 0.5f;
        currentPixelJitterY = hy - 0.5f;

        // Convert to NDC space offset: (pixel / res) * 2.0
        currentJitterNdcX = (currentPixelJitterX / (float) renderWidth) * 2.0f;
        currentJitterNdcY = (currentPixelJitterY / (float) renderHeight) * 2.0f;

        if (!wasActive) {
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
        if (!isJitterActive() || !active || projMatrix == null) {
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
        active = false;
    }

    public static float getCurrentJitterNdcX() {
        return currentJitterNdcX;
    }

    public static float getCurrentJitterNdcY() {
        return currentJitterNdcY;
    }

    public static float getPrevJitterNdcX() {
        return prevJitterNdcX;
    }

    public static float getPrevJitterNdcY() {
        return prevJitterNdcY;
    }

    public static float getCurrentPixelJitterX() {
        return currentPixelJitterX;
    }

    public static float getCurrentPixelJitterY() {
        return currentPixelJitterY;
    }

    public static float getPrevPixelJitterX() {
        return prevPixelJitterX;
    }

    public static float getPrevPixelJitterY() {
        return prevPixelJitterY;
    }

    public static int getPhaseIndex() {
        return phaseIndex;
    }

    public static boolean isActive() {
        return active;
    }

    public static Vector2f getCurrentNdcVector() {
        return new Vector2f(currentJitterNdcX, currentJitterNdcY);
    }

    public static Vector2f getPrevNdcVector() {
        return new Vector2f(prevJitterNdcX, prevJitterNdcY);
    }
}
