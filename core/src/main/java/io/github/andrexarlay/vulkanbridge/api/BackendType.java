package io.github.andrexarlay.vulkanbridge.api;

/** Graphics backend the game is running on. */
public enum BackendType {
    VULKAN,
    OPENGL,
    /** Neither the detector nor the live RenderSystem device could tell (e.g. called before the device exists). */
    UNKNOWN
}
