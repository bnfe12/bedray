package io.github.andrexarlay.vulkancompat.rt.world;

import com.mojang.blaze3d.pipeline.RenderTarget;
import io.github.andrexarlay.vulkancompat.rtx.frame.WorldFrame;
import io.github.andrexarlay.vulkancompat.rtx.frame.WorldFrames;
import io.github.andrexarlay.vulkancompat.rtx.framegen.FrameGenGuides;

/**
 * Current-frame colour provenance: which engine target RT actually replaced this frame, with the engine frame stamp that
 * proves it, the projection it was traced with and the frame-generation guides the denoiser published. Old output
 * allocations are not evidence that RT replaced this frame, so every accessor gates on the stamp being current.
 */
final class RtDisplayProvenance {
    private WorldFrame.Stamp displayedStamp;
    private RenderTarget displayedTarget, pendingDisplayedTarget;
    private org.joml.Matrix4f displayedProjection;
    private FrameGenGuides pendingFgGuides, displayedFgGuides;

    /** Clears the previous frame's provenance before a new world phase can publish into it. */
    void beginFrame() {
        displayedStamp = null; displayedTarget = pendingDisplayedTarget = null; displayedProjection = null;
        displayedFgGuides = pendingFgGuides = null;
    }

    void setPendingFgGuides(FrameGenGuides guides) { pendingFgGuides = guides; }

    void setPendingDisplayedTarget(RenderTarget target) { pendingDisplayedTarget = target; }

    /** Publishes this frame's provenance once the recording that filled the pending target has been submitted. */
    void publish(RenderTarget target, org.joml.Matrix4f frameProjection) {
        if (pendingDisplayedTarget == target && frameProjection != null) {
            displayedStamp = WorldFrames.stampOrNull();
            displayedTarget = target;
            displayedFgGuides = pendingFgGuides;
            displayedProjection = RtConfig.FLIP_Y
                ? new org.joml.Matrix4f().scaling(1, -1, 1).mul(frameProjection) : new org.joml.Matrix4f(frameProjection);
        }
    }

    FrameGenGuides frameGenGuides() {
        return displayedThisFrame() ? displayedFgGuides : null;
    }

    /** Current-frame color provenance; old output allocations are not evidence that RT replaced this frame. */
    boolean displayedThisFrame(RenderTarget target) {
        return displayedTarget == target && displayedThisFrame();
    }

    /** Diagnostic proof independent of the target temporarily swapped during world rendering. */
    boolean displayedThisFrame() {
        return displayedStamp != null && displayedTarget != null && WorldFrames.isCurrent(displayedStamp);
    }

    org.joml.Matrix4f displayedProjection(RenderTarget target) {
        return displayedThisFrame(target) && displayedProjection != null ? new org.joml.Matrix4f(displayedProjection) : null;
    }
}