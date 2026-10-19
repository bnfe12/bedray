package io.github.andrexarlay.vulkanbridge.api;

/**
 * Order of {@link FramePhases} listeners and projection modifiers inside one phase: declaration order is execution order
 * (HIGH first); equal priorities run in registration order.
 */
@Experimental
public enum PhasePriority {
    HIGH,
    NORMAL,
    LOW
}
