package io.github.andrexarlay.vulkancompat.rt.denoise;

import org.joml.Matrix4f;

import java.lang.foreign.MemorySegment;

import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;

/** Java side of VkbNrdFrameSettings (vkb_nrd.h:142-161). Plain data; {@link #write} fills the 344-byte struct. */
public final class NrdFrameSettings {
    public int frameIndex;
    public int accumulationMode = NrdLayouts.ACCUM_CONTINUE;
    public int flags = NrdLayouts.FRAME_MV_WORLD_SPACE;
    public final Matrix4f viewToClip = new Matrix4f();
    public final Matrix4f viewToClipPrev = new Matrix4f();
    public final Matrix4f worldToView = new Matrix4f();
    public final Matrix4f worldToViewPrev = new Matrix4f();
    public final float[] mvScale = {1f, 1f, 1f};
    public final float[] mvBias = {0f, 0f, 0f};
    public final float[] jitter = {0f, 0f};
    public final float[] jitterPrev = {0f, 0f};
    public int rectWidth;
    public int rectHeight;
    public int rectWidthPrev;
    public int rectHeightPrev;
    public float viewZScale = 1f;
    public float timeDeltaMs;
    public float denoisingRange = 4096f;
    public float disocclusionThreshold = 0.01f;

    public void write(MemorySegment s) {
        if (s.byteSize() < NrdLayouts.FRAME_SETTINGS_SIZE) {
            throw new IllegalArgumentException("segment too small: " + s.byteSize());
        }
        s.set(JAVA_INT, 0, NrdLayouts.FRAME_SETTINGS_SIZE);
        s.set(JAVA_INT, NrdLayouts.FS_FRAME_INDEX, frameIndex);
        s.set(JAVA_INT, NrdLayouts.FS_ACCUMULATION_MODE, accumulationMode);
        s.set(JAVA_INT, NrdLayouts.FS_FLAGS, flags);
        putMatrix(s, NrdLayouts.FS_VIEW_TO_CLIP, viewToClip);
        putMatrix(s, NrdLayouts.FS_VIEW_TO_CLIP_PREV, viewToClipPrev);
        putMatrix(s, NrdLayouts.FS_WORLD_TO_VIEW, worldToView);
        putMatrix(s, NrdLayouts.FS_WORLD_TO_VIEW_PREV, worldToViewPrev);
        putFloats(s, NrdLayouts.FS_MV_SCALE, mvScale);
        putFloats(s, NrdLayouts.FS_MV_BIAS, mvBias);
        putFloats(s, NrdLayouts.FS_JITTER, jitter);
        putFloats(s, NrdLayouts.FS_JITTER_PREV, jitterPrev);
        s.set(JAVA_INT, NrdLayouts.FS_RECT, rectWidth);
        s.set(JAVA_INT, NrdLayouts.FS_RECT + 4, rectHeight);
        s.set(JAVA_INT, NrdLayouts.FS_RECT_PREV, rectWidthPrev);
        s.set(JAVA_INT, NrdLayouts.FS_RECT_PREV + 4, rectHeightPrev);
        s.set(JAVA_FLOAT, NrdLayouts.FS_VIEW_Z_SCALE, viewZScale);
        s.set(JAVA_FLOAT, NrdLayouts.FS_TIME_DELTA_MS, timeDeltaMs);
        s.set(JAVA_FLOAT, NrdLayouts.FS_DENOISING_RANGE, denoisingRange);
        s.set(JAVA_FLOAT, NrdLayouts.FS_DISOCCLUSION_THRESHOLD, disocclusionThreshold);
    }

    private static void putMatrix(MemorySegment s, int offset, Matrix4f m) {
        float[] a = new float[16];
        m.get(a);   // JOML: column-major, as NRD expects
        putFloats(s, offset, a);
    }

    private static void putFloats(MemorySegment s, int offset, float[] v) {
        for (int i = 0; i < v.length; i++) {
            s.set(JAVA_FLOAT, offset + 4L * i, v[i]);
        }
    }
}
