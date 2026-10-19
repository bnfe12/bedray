package io.github.andrexarlay.vulkanbridge.api;

import org.joml.Matrix4f;

import java.util.Optional;
import java.util.function.UnaryOperator;

/**
 * Frame contract of the core (ADR-0003, API 0.6.0). the core owns the {@code GameRenderer} frame hooks; mods register
 * listeners per {@link FramePhase} instead of adding their own mixins at the same points, so their relative order is defined
 * here ({@link PhasePriority}, then registration order) and not by the order in which mixins happen to be applied.
 * <p>
 * Threading: listeners and projection modifiers run on the render thread. Registration and claims may be done from any thread
 * and take effect at the next dispatch. An exception thrown by a listener propagates into the frame (the remaining listeners of
 * that phase are not run); nothing is swallowed.
 * Obtain via {@link VulkanBridge#framePhases()}.
 */
@Experimental
public interface FramePhases {
    /** Registers a listener for one phase. */
    Registration on(FramePhase phase, PhasePriority priority, FramePhaseListener listener);

    /**
     * Registers a modifier of the level projection matrix: the argument of {@code ProjectionMatrixBuffer.getBuffer(Matrix4f)}
     * inside {@code GameRenderer.renderLevel()} (javap -c on the game jar). Modifiers run in priority order; each one receives the
     * previous result (the first one a copy of the engine matrix) and returns the matrix to use. Not exclusive by itself: a jitter
     * producer claims {@link ExclusiveRole#PROJECTION_JITTER} first.
     */
    Registration modifyProjection(PhasePriority priority, UnaryOperator<Matrix4f> modifier);

    /**
     * Claims an exclusive role for {@code modId}. The first claim wins; a repeated claim by the owner is granted again; a claim by
     * another mod is refused (granted=false, owner = the holder) and logged.
     */
    ClaimResult claim(ExclusiveRole role, String modId);

    /** Releases a role held by {@code modId}. Returns false when {@code modId} did not hold it. */
    boolean release(ExclusiveRole role, String modId);

    /** Current holder of a role. */
    Optional<String> owner(ExclusiveRole role);

    /** Snapshot of the current (or last) frame; {@code null} before the first {@link FramePhase#FRAME_BEGIN}. */
    FrameInfo currentFrame();

    /** See {@link FrameInfo#deviceGeneration()}; 0 before the first frame. */
    long deviceGeneration();

    /** See {@link FrameInfo#mainTargetGeneration()}; 0 before the main target was observed. */
    long mainTargetGeneration();
}
