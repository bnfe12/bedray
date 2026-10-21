package io.github.andrexarlay.bedray.api.v1;

/**
 * Optional compute renderer, registered once by a companion mod and called on the render thread.
 * The host owns the device, command buffer, target and queue submission. Implementations must not
 * create a second device, submit the supplied command buffer, or destroy any host object.
 */
public interface GraphicsBackend extends AutoCloseable {
    String id();

    /** Opt-in to bounded live-world snapshots. Called on the render thread, before render(). */
    default boolean wantsWorldSnapshots() { return false; }

    /** Immutable CPU data only; no borrowed game objects or GPU work. A new session replaces all old data. */
    default void acceptWorldSnapshot(WorldSnapshot snapshot) {}

    /** Clear retained CPU scene/camera history on world exit or when capture is disabled. */
    default void clearWorldSnapshot() {}

    /**
     * Records a complete synthetic diagnostic image into the supplied target in GENERAL layout.
     * Returns true only after the full image has been recorded; false must leave the target intact.
     * Validate and allocate before recording commands: an exception must not leave a partial image.
     * No hardware ray tracing extensions may be required by this contract.
     */
    boolean render(ComputeFrameContext frame);

    /** Releases owned GPU objects. The host flushes pending commands first. Must permit later reuse. */
    @Override
    void close();
}
