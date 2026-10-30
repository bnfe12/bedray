package io.github.andrexarlay.vulkancompat.rt.world;

import org.lwjgl.vulkan.VkCommandBuffer;

/** Execution boundary: the host captures frame state; the selected backend owns recording resources. */
interface PathTraceBackend extends AutoCloseable {
    /** Borrowed mutable inputs, consumed synchronously by record and never retained by a backend. */
    record Frame(VkCommandBuffer command, RtPipeline.Views views, RtPathTraceSlangPass.Frame constants,
                 RtPathTraceSlangPass.Dispatch dispatch, RtPathTraceSlangPass.Emitters emitters,
                 RtPathTraceSlangPass.Entities entities, int flags, int accumFrames) {}

    void prepare(int width, int height);
    void record(Frame frame);
    boolean spatialAvailable();
    String reservoirState();
    void releaseRecording(long commandBuffer);
    /** True only when this backend held and abandoned that recording. */
    boolean discardRecording(long commandBuffer);
    void releaseRecordingsAfterHostDrain();
    /** True when the backend writes the primary hit's world-space motion into an image it can expose. */
    default boolean providesWorldMotion() {
        return false;
    }
    /** The RGBA16F primary-hit world-motion image, 0 before the first record or when the backend has none. */
    default long motionImage() {
        return 0L;
    }
    /** The view of {@link #motionImage()}, 0 when there is none. */
    default long motionView() {
        return 0L;
    }
    @Override void close();
}
