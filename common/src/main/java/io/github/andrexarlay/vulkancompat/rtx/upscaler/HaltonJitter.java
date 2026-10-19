package io.github.andrexarlay.vulkancompat.rtx.upscaler;

import org.joml.Vector2f;

/**
 * Generates low-discrepancy sub-pixel camera jitter using the 2D Halton sequence.
 * Essential for temporal anti-aliasing and super resolution algorithms (DLSS, FSR, XeSS).
 */
public final class HaltonJitter {

    private int sampleIndex = 0;
    private int phaseCount = 16;
    private final Vector2f currentJitter = new Vector2f();
    private final Vector2f currentNdcJitter = new Vector2f();

    public HaltonJitter() {
        this(16);
    }

    public HaltonJitter(int phaseCount) {
        this.phaseCount = phaseCount;
    }

    public void setPhaseCount(int phaseCount) {
        this.phaseCount = Math.max(1, phaseCount);
    }

    public int getPhaseCount() {
        return phaseCount;
    }

    /**
     * Advances to the next frame and calculates pixel-space sub-pixel jitter in range [-0.5, 0.5].
     */
    public Vector2f next(int renderWidth, int renderHeight) {
        sampleIndex = (sampleIndex + 1) % phaseCount;

        float jx = halton(sampleIndex + 1, 2) - 0.5f;
        float jy = halton(sampleIndex + 1, 3) - 0.5f;
        currentJitter.set(jx, jy);

        if (renderWidth > 0 && renderHeight > 0) {
            // Convert to NDC coordinate offset: (2.0 * jitter / resolution)
            currentNdcJitter.set((2.0f * jx) / renderWidth, (2.0f * jy) / renderHeight);
        } else {
            currentNdcJitter.set(0.0f, 0.0f);
        }

        return currentJitter;
    }

    public Vector2f getCurrentJitter() {
        return currentJitter;
    }

    public Vector2f getCurrentNdcJitter() {
        return currentNdcJitter;
    }

    public void reset() {
        sampleIndex = 0;
        currentJitter.set(0.0f, 0.0f);
        currentNdcJitter.set(0.0f, 0.0f);
    }

    /**
     * Calculates the radical inverse Halton function for a given index and base.
     */
    public static float halton(int index, int base) {
        float f = 1.0f;
        float r = 0.0f;
        while (index > 0) {
            f /= base;
            r += f * (index % base);
            index /= base;
        }
        return r;
    }
}
