package io.github.andrexarlay.vulkanbridge.api;

/**
 * Snapshot of the frame handed to every {@link FramePhaseListener}.
 *
 * @param frameIndex           frame counter, incremented at every {@link FramePhase#FRAME_BEGIN} (first frame = 1)
 * @param deviceGeneration     1 for the first GPU device the game created, +1 for every later one; 0 before the first frame.
 *                             Objects created for an older generation belong to a dead device and must not be used.
 * @param mainTargetGeneration 1 for the first observed main render target, +1 every time {@code GameRenderer.resize(int,int)}
 *                             recreated it (window resize). In a frame where the engine resizes, {@link FramePhase#FRAME_BEGIN}
 *                             still reports the old generation (the resize runs inside {@code render()} after HEAD); the later
 *                             phases report the new one.
 * @param width                main render target width in pixels for {@code mainTargetGeneration}
 * @param height               main render target height in pixels for {@code mainTargetGeneration}
 * @param partialTick          {@code DeltaTracker.getGameTimeDeltaPartialTick(false)} sampled at FRAME_BEGIN, 0..1
 */
@Experimental
public record FrameInfo(long frameIndex, long deviceGeneration, long mainTargetGeneration, int width, int height, double partialTick) {
}
