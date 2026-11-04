package io.github.andrexarlay.vulkancompat.rtx.framegen;

import io.github.andrexarlay.vulkancompat.rtx.frame.ReconstructionCamera;
import io.github.andrexarlay.vulkancompat.rtx.frame.WorldFrame;

import java.util.Objects;

/** Current RR producer snapshot: trace-sized guides, output-sized color, and an explicit borrowed-image lifetime. */
public record FrameGenGuides(WorldFrame.Stamp stamp, Object device, long generation,
                             WorldFrame.Image depth, WorldFrame.Image motion,
                             int outputWidth, int outputHeight, ReconstructionCamera camera, float primaryJitterX, float primaryJitterY,
                             FrameGenGuideReads.Source reads) {
    public FrameGenGuides {
        Objects.requireNonNull(stamp); Objects.requireNonNull(device);
        Objects.requireNonNull(depth); Objects.requireNonNull(motion);
        Objects.requireNonNull(camera); Objects.requireNonNull(reads);
        if (generation <= 0 || outputWidth <= 0 || outputHeight <= 0
                || !Float.isFinite(primaryJitterX) || !Float.isFinite(primaryJitterY)
                || depth.format() != 100 /* R32_SFLOAT */ || motion.format() != 83 /* R16G16_SFLOAT */
                || depth.width() != motion.width() || depth.height() != motion.height()
                || depth.origin() != WorldFrame.Origin.BOTTOM_LEFT || motion.origin() != depth.origin())
            throw new IllegalArgumentException("invalid RR frame-generation guide contract");
    }

    public void requireMatches(WorldFrame.Stamp current, Object currentDevice, int width, int height) {
        if (!stamp.equals(current) || device != currentDevice || outputWidth != width || outputHeight != height)
            throw new IllegalStateException("RR FG guide mismatch stamp=" + stamp + " current=" + current
                + " device_match=" + (device == currentDevice) + " generation=" + generation
                + " guides=" + depth.width() + "x" + depth.height() + " output=" + outputWidth + "x" + outputHeight
                + " requested_output=" + width + "x" + height);
    }

    /** fg_prepare flips rows and MV.y once. The consumer camera/jitter must undergo the same clip-Y change. */
    public float ffxJitterX() { return -primaryJitterX; }
    public float ffxJitterY() { return primaryJitterY; }

    public ReconstructionCamera preparedCamera() { return camera.flipClipY(); }
}
