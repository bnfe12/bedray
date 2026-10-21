package io.github.andrexarlay.bedray.api.v1;

/**
 * Optional compute renderer, registered once by a companion mod and called on the render thread.
 * The host owns the device, command buffer, target and queue submission. Implementations must not
 * create a second device, submit the supplied command buffer, or destroy any host object.
 */
public interface GraphicsBackend extends AutoCloseable {
    String id();

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
