package io.github.andrexarlay.vulkancompat.rtx.framegen;

import io.github.andrexarlay.vulkancompat.rtx.frame.ReconstructionCamera;

import java.util.function.Consumer;

/** Accepted common constants have their own timeline, independent of FG targets, tags and warmup. */
final class StreamlineCameraHistory {
    record Extent(int renderWidth, int renderHeight, int outputWidth, int outputHeight) {
        Extent {
            if (renderWidth <= 0 || renderHeight <= 0 || outputWidth <= 0 || outputHeight <= 0)
                throw new IllegalArgumentException("invalid Streamline viewport extent");
        }
    }

    private long frame = -1, worldEpoch;
    private ReconstructionCamera camera;
    private Extent extent;

    boolean published(long frame) { return camera != null && this.frame == frame; }

    void publish(long nextFrame, long nextWorldEpoch, ReconstructionCamera next, Extent nextExtent,
                 Consumer<StreamlineCameraConstants.Packed> send) {
        if (nextFrame <= 0 || nextFrame < frame)
            throw new IllegalArgumentException("invalid Streamline camera frame=" + nextFrame);
        if (published(nextFrame)) {
            if (worldEpoch != nextWorldEpoch || !extent.equals(nextExtent) || !next.sameConstants(camera))
                throw new IllegalStateException("camera conflicts with Streamline constants already published for frame=" + frame);
            return;
        }
        boolean continuous = frame == nextFrame - 1 && worldEpoch == nextWorldEpoch && nextExtent.equals(extent);
        var packed = StreamlineCameraConstants.pack(next, continuous ? camera : null,
            nextExtent.renderWidth(), nextExtent.renderHeight(), nextExtent.outputWidth(), nextExtent.outputHeight());
        send.accept(packed);
        // Failed SDK publication cannot advance history or claim this frame's constants.
        frame = nextFrame;
        worldEpoch = nextWorldEpoch;
        camera = next;
        extent = nextExtent;
    }

    void clear() {
        frame = -1;
        camera = null;
        extent = null;
    }
}
