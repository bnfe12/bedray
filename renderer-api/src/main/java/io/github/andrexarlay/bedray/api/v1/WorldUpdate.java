package io.github.andrexarlay.bedray.api.v1;

import java.util.List;
import java.util.Objects;

/** API minor 6 immutable delta. A reset discards the previous session before applying this update.
 * Missing coordinates remain unknown; removals invalidate a previously captured coordinate.
 * Section payloads retain the existing model, light, tint and resource generation contracts.
 */
public record WorldUpdate(long session, long revision, CameraSnapshot camera, long atlasGeneration,
        WorldCoverageBounds bounds, List<SectionSnapshot> upserts, List<SectionKey> removals,
        boolean reset, int discoveredSections, int emptySections, int queuedCaptureSections,
        WorldRenderQuality renderQuality) {
    public WorldUpdate(long session, long revision, CameraSnapshot camera, long atlasGeneration,
            WorldCoverageBounds bounds, List<SectionSnapshot> upserts, List<SectionKey> removals,
            boolean reset, int discoveredSections, int emptySections, int queuedCaptureSections) {
        this(session, revision, camera, atlasGeneration, bounds, upserts, removals, reset, discoveredSections,
            emptySections, queuedCaptureSections, new WorldRenderQuality(true, true));
    }
    public WorldUpdate {
        Objects.requireNonNull(camera);
        Objects.requireNonNull(bounds);
        Objects.requireNonNull(renderQuality);
        upserts = List.copyOf(upserts);
        removals = List.copyOf(removals);
        if (session <= 0 || revision < 0 || atlasGeneration <= 0 || discoveredSections < 0
                || emptySections < 0 || queuedCaptureSections < 0 || upserts.size() > 64 || removals.size() > 1024)
            throw new IllegalArgumentException("Invalid world update");
        for (SectionSnapshot section : upserts)
            if (!bounds.contains(new SectionKey(section.x(), section.y(), section.z()))
                    || section.materialGeneration() != 0 && section.materialGeneration() != atlasGeneration)
                throw new IllegalArgumentException("Section outside coverage or resource generation");
    }
}
