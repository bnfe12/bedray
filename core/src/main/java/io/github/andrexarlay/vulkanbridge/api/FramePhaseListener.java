package io.github.andrexarlay.vulkanbridge.api;

/** Callback of {@link FramePhases#on}. Runs on the render thread; an exception propagates into the frame (it is not swallowed). */
@Experimental
@FunctionalInterface
public interface FramePhaseListener {
    void onPhase(FramePhase phase, FrameInfo frame);
}
