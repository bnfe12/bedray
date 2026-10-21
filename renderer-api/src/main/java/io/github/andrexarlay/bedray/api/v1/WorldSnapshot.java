package io.github.andrexarlay.bedray.api.v1;

import java.util.List;

/** Bounded complete resident set, not a delta. Missing coordinates are unknown, never solid.
 * Section coordinates use floor division by 16. All data can be retained by a worker thread.
 */
public record WorldSnapshot(long session, long revision, CameraSnapshot camera,
                            List<SectionSnapshot> sections) {
    public WorldSnapshot {
        if (session <= 0 || revision < 0 || camera == null || sections.size() > 27)
            throw new IllegalArgumentException("Invalid world snapshot");
        sections = List.copyOf(sections);
    }
}
