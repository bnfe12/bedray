package io.github.andrexarlay.vulkanbridge.api;

/** What a GL mod can get from the {@link GlBridge} (ADR-0006 Tier model). */
@Experimental
public enum GlTier {
    /** GL overlay over the final frame. */
    TIER_1,
    /** Self-contained GL (own programs, FBOs, textures, compute) with frame parameters ({@link GlFrame}). */
    TIER_2,
    /** Exchange with the engine frame: scene colour/depth as GL textures, output into a mid-frame phase. Not implemented. */
    TIER_3,
    /** Adapters for mods that use Mojang's GL classes directly. Not implemented. */
    TIER_4
}
