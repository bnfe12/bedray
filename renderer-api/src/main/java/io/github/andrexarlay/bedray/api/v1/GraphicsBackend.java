package io.github.andrexarlay.bedray.api.v1;

/**
 * Optional compute renderer, registered once by a companion mod and called on the render thread.
 * The host owns the device, command buffer, target and queue submission. Implementations must not
 * create a second device, submit the supplied command buffer, or destroy any host object.
 */
public interface GraphicsBackend extends AutoCloseable {
    String id();

    /** CPU-side scene diagnostics; never a presentation/compatibility claim. */
    default String sceneDetail() { return "No scene diagnostics"; }

    /** Older providers remain synthetic-only rather than being mislabeled as live-world renderers. */
    default boolean supportsSceneMode(GraphicsBackendRegistry.SceneMode mode) {
        return mode == GraphicsBackendRegistry.SceneMode.SYNTHETIC;
    }

    default boolean supportsDebugViews() { return false; }

    /** Opt-in to API minor 4 model occupancy and per-cell light. Older providers keep cube-only capture. */
    default boolean supportsModelGeometry() { return false; }

    /** Opt-in to API minor 5 per-cell tint (SectionTintData, CubeFaceMaterial.cellTint, ModelQuad.CELL_TINT).
     * Providers that do not opt in keep receiving palettes with the tint baked into every entry. */
    default boolean supportsCellTint() { return false; }

    /** Opt-in to bounded live-world snapshots. Called on the render thread, before render(). */
    default boolean wantsWorldSnapshots() { return false; }

    /** Immutable CPU data only; no borrowed game objects or GPU work. A new session replaces all old data. */
    default void acceptWorldSnapshot(WorldSnapshot snapshot) {}

    /** API minor 6: bounded immutable section deltas over the loaded render-distance domain. */
    default boolean supportsWorldStreaming() { return false; }

    /** Called instead of acceptWorldSnapshot when supportsWorldStreaming returns true. */
    default void acceptWorldUpdate(WorldUpdate update) {}

    default WorldStreamingStatus streamingStatus() { return WorldStreamingStatus.unavailable(); }

    /** At most 64 evicted/rejected coordinates per call. The host forgets delivery and may capture them again.
     * Consumers must retain invalidations until drained; unavailable pages always preserve raster output. */
    default java.util.List<SectionKey> drainWorldInvalidations() { return java.util.List.of(); }

    /** Clear retained CPU scene/camera history on world exit or when capture is disabled. */
    default void clearWorldSnapshot() {}

    /**
     * Records a complete requested diagnostic image into the supplied target in GENERAL layout.
     * Returns true only after the full image has been recorded; false must leave the target intact.
     * Validate and allocate before recording commands: an exception must not leave a partial image.
     * No hardware ray tracing extensions may be required by this contract.
     */
    boolean render(ComputeFrameContext frame);

    /** Releases owned GPU objects. The host flushes pending commands first. Must permit later reuse. */
    @Override
    void close();
}
