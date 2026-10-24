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
        long generation = 0;
        for (SectionSnapshot section : sections) {
            long next = section.materialGeneration();
            if (next == 0) continue;
            if (generation != 0 && generation != next)
                throw new IllegalArgumentException("Mixed material resource generations");
            generation = next;
        }
    }
    public long materialGeneration() {
        for (SectionSnapshot section : sections) if (section.materialGeneration() != 0) return section.materialGeneration();
        return 0;
    }
}
