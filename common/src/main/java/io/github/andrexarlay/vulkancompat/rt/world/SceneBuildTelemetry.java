package io.github.andrexarlay.vulkancompat.rt.world;

import io.github.andrexarlay.vulkancompat.rt.scene.BedraySceneLayouts;

/** Work counts over the log interval, rather than an arbitrary last frame. These are not GPU timings. */
final class SceneBuildTelemetry {
    record Counts(long frames, long blasBuilds, long blasRefits, long tlasBuilds, long tlasUpdates,
                  long copyOnWriteFrames, long deferredUpdates) {}
    private long frames, blasBuilds, blasRefits, tlasBuilds, tlasUpdates, copyOnWriteFrames, deferredUpdates;

    void add(int flags, long builds, long refits, long deferred) {
        frames++;
        blasBuilds += builds;
        blasRefits += refits;
        deferredUpdates += deferred;
        if ((flags & BedraySceneLayouts.FRAME_TLAS_REBUILT) != 0) tlasBuilds++;
        if ((flags & BedraySceneLayouts.FRAME_TLAS_UPDATED) != 0) tlasUpdates++;
        if ((flags & BedraySceneLayouts.FRAME_BLAS_COPY_ON_WRITE) != 0) copyOnWriteFrames++;
    }

    Counts drain() {
        var counts = new Counts(frames, blasBuilds, blasRefits, tlasBuilds, tlasUpdates, copyOnWriteFrames, deferredUpdates);
        frames = blasBuilds = blasRefits = tlasBuilds = tlasUpdates = copyOnWriteFrames = deferredUpdates = 0;
        return counts;
    }
}
