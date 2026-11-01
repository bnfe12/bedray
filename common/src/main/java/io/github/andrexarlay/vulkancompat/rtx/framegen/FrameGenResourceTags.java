package io.github.andrexarlay.vulkancompat.rtx.framegen;

import java.util.Objects;

/** Tracks vendor references even when only part of a tag update succeeds. Render thread only. */
final class FrameGenResourceTags {
    private boolean active;

    boolean isActive() {
        return active;
    }

    void register(Runnable assignments) {
        Objects.requireNonNull(assignments);
        // A failed call can follow a successful assignment, or have an unknown native outcome.
        active = true;
        assignments.run();
    }

    void clear(Runnable releases) {
        Objects.requireNonNull(releases);
        if (!active) return;
        releases.run();
        active = false;
    }
}
