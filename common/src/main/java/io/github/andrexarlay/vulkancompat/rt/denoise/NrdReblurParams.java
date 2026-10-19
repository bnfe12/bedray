package io.github.andrexarlay.vulkancompat.rt.denoise;

import java.lang.foreign.MemorySegment;

import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;

/**
 * REBLUR settings (VkbNrdReblurSettings, vkb_nrd.h:163-173). Defaults match the 4.17/4.18 settings exposed by the wrapper.
 * The hit distance parameters are the single Java source of truth: the same A, B, C go to rt_pack_nrd.comp (push constant) and
 * to NRD, they MUST match or the temporal accumulation of the hit distance is wrong.
 */
public record NrdReblurParams(int maxAccumulatedFrameNum, int maxFastAccumulatedFrameNum, int maxStabilizedFrameNum,
                              float hitDistA, float hitDistB, float hitDistC, float minBlurRadius, float maxBlurRadius,
                              float diffusePrepassBlurRadius, float specularPrepassBlurRadius, float planeDistanceSensitivity,
                              boolean antiFirefly) {
    public static NrdReblurParams defaults() {
        return new NrdReblurParams(30, 6, 63, 3.0f, 0.1f, 20.0f, 1.0f, 30.0f, 30.0f, 50.0f, 0.02f, true);
    }

    public void write(MemorySegment s) {
        if (s.byteSize() < NrdLayouts.REBLUR_SETTINGS_SIZE) {
            throw new IllegalArgumentException("segment too small: " + s.byteSize());
        }
        s.set(JAVA_INT, 0, NrdLayouts.REBLUR_SETTINGS_SIZE);
        s.set(JAVA_INT, 4, maxAccumulatedFrameNum);
        s.set(JAVA_INT, 8, maxFastAccumulatedFrameNum);
        s.set(JAVA_INT, 12, maxStabilizedFrameNum);
        s.set(JAVA_FLOAT, 16, hitDistA);
        s.set(JAVA_FLOAT, 20, hitDistB);
        s.set(JAVA_FLOAT, 24, hitDistC);
        s.set(JAVA_FLOAT, 28, minBlurRadius);
        s.set(JAVA_FLOAT, 32, maxBlurRadius);
        s.set(JAVA_FLOAT, 36, diffusePrepassBlurRadius);
        s.set(JAVA_FLOAT, 40, specularPrepassBlurRadius);
        s.set(JAVA_FLOAT, 44, planeDistanceSensitivity);
        s.set(JAVA_INT, 48, antiFirefly ? 1 : 0);
    }
}
