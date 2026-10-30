package io.github.andrexarlay.vulkancompat.rt.world;

import org.lwjgl.vulkan.VkCommandBuffer;

/** Execution boundary: the host captures frame state; the selected backend owns recording resources. */
interface PathTraceBackend extends AutoCloseable {
    /**
     * Borrowed mutable inputs, consumed synchronously by record and never retained by a backend.
     *
     * <p>{@code stats} is the sixteen-byte counter head of this frame's slot in the renderer's statistics buffer. A
     * backend that assembles its own frame on the Java side writes those four words through
     * {@code passes/frame_stats.slang}; the native core records the whole frame inside {@code bedray_core}, where the
     * radiance and accumulation images are its own, and has no writer for them yet - which the F3 counters and
     * {@link RtExposure} both report as "no measurement" rather than as a zero.
     */
    record Frame(VkCommandBuffer command, RtFrameBindings.Views views, RtPathTraceSlangPass.Frame constants,
                 RtPathTraceSlangPass.Dispatch dispatch, RtPathTraceSlangPass.Emitters emitters,
                 RtPathTraceSlangPass.Entities entities, RtPathTraceSlangPass.BufferView stats, int flags,
                 int accumFrames) {}

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
