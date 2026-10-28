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
    @Override void close();
}
