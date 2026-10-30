package io.github.andrexarlay.vulkancompat.rt.world;

import org.lwjgl.vulkan.VkCommandBuffer;

/** Execution boundary: the host captures frame state; the selected backend owns recording resources. */
interface PathTraceBackend extends AutoCloseable {
    /**
     * Borrowed mutable inputs, consumed synchronously by record and never retained by a backend.
     *
     * <p>{@code stats} is the sixteen-byte counter head of this frame's slot in the renderer's statistics buffer. The
     * reference backend dispatches {@code passes/frame_stats.slang} over it itself; the native core records the same
     * program, because the radiance and accumulation images the counters read are its own. Either way the slot is the
     * host's: a backend without a writer leaves it zeroed, and {@link RtExposure} and the F3 counters then report
     * "no measurement" rather than a zero that looks like one.
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
