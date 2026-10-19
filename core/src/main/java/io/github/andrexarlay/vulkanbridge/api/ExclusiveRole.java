package io.github.andrexarlay.vulkanbridge.api;

/**
 * Roles in the frame that only one mod can hold at a time (ADR-0003). Claimed with {@link FramePhases#claim}. the core records
 * the owner and refuses other claimants; it does not police what the owner does, so a mod checks the claim result before it acts.
 */
@Experimental
public enum ExclusiveRole {
    /** Renders the world into a target of another size than the main target (dynamic resolution, upscalers). */
    WORLD_RENDER_SCALE,
    /** Adds a sub-pixel jitter to the level projection (temporal upscalers/AA), applied through {@link FramePhases#modifyProjection}. */
    PROJECTION_JITTER,
    /** Patches usage flags of engine textures/buffers at creation (for example STORAGE for compute passes). */
    STORAGE_USAGE_PATCH
}
