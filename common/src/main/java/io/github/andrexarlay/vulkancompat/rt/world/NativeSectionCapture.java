package io.github.andrexarlay.vulkancompat.rt.world;

import io.github.andrexarlay.vulkancompat.rt.sodium.DecodedSection;
import io.github.andrexarlay.vulkancompat.rt.sodium.SectionGeometryRegistry;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongSupplier;

/** Paced CPU capture queue. Acceptance means copied POD queued for scene update, never GPU completion. */
final class NativeSectionCapture {
    interface Source {
        long version();
        DecodedSection get(long key);
        void forEach(Consumer<DecodedSection> visitor);
    }
    interface Sink {
        void sectionBuilt(DecodedSection section, int[] quadFlags);
        void sectionRemoved(long key);
    }
    record Result(int queued, int pending, long nearestSq, boolean rescanned) {}
    private record Focus(int x, int y, int z, int radiusXz, int radiusY) {}
    private record Capture(DecodedSection section, long tagHash) {}

    private final Source source;
    private final Sink sink;
    private final Function<DecodedSection, int[]> tagger;
    private final LongSupplier clock;
    private final int evictionMargin;
    private final Map<Long, Capture> accepted = new HashMap<>();
    private List<DecodedSection> pending = List.of();
    private int cursor;
    private Focus focus;
    private long sourceVersion = -1;
    private long structureVersion;
    private long identicalSkipped;

    NativeSectionCapture(SectionGeometryRegistry registry, Sink sink, Function<DecodedSection, int[]> tagger) {
        this(new Source() {
            public long version() { return registry.version(); }
            public DecodedSection get(long key) { return registry.get(key); }
            public void forEach(Consumer<DecodedSection> visitor) { registry.forEachSection(visitor); }
        }, sink, tagger, System::nanoTime, RtConfig.EVICT_MARGIN);
    }

    NativeSectionCapture(Source source, Sink sink, Function<DecodedSection, int[]> tagger,
                         LongSupplier clock, int evictionMargin) {
        if (evictionMargin < 0) throw new IllegalArgumentException("negative eviction margin");
        this.source = Objects.requireNonNull(source);
        this.sink = Objects.requireNonNull(sink);
        this.tagger = Objects.requireNonNull(tagger);
        this.clock = Objects.requireNonNull(clock);
        this.evictionMargin = evictionMargin;
    }

    Result update(int x, int y, int z, int radiusXz, int radiusY, int limit, long budgetNanos) {
        if (radiusXz < 0 || radiusY < 0 || limit < 0 || budgetNanos < 0)
            throw new IllegalArgumentException("negative capture radius, quota or budget");
        Focus next = new Focus(x, y, z, radiusXz, radiusY);
        long version = source.version();
        boolean rescan = !next.equals(focus) || sourceVersion != version;
        if (rescan) {
            rescan(next);
            // Publish the scan key only after every eviction and queue construction succeeded.
            focus = next;
            sourceVersion = version;
        }
        int queued = 0, visited = 0;
        long nearest = Long.MAX_VALUE;
        long started = clock.getAsLong();
        while (cursor < pending.size() && queued < limit) {
            // Stale/identical captures also consume CPU time and cannot bypass the budget.
            if (visited > 0 && clock.getAsLong() - started >= budgetNanos) break;
            DecodedSection section = pending.get(cursor);
            visited++;
            if (source.get(section.key) != section) {
                cursor++;
                continue;
            }
            int[] flags = tagger.apply(section);
            long hash = SectionGeometryIdentity.lightHash(section.light, flags, section.totalQuads() * 4);
            Capture previous = accepted.get(section.key);
            if (previous != null && previous.tagHash() == hash
                    && SectionGeometryIdentity.same(previous.section(), section)) {
                accepted.put(section.key, new Capture(section, hash));
                identicalSkipped++;
            } else {
                // Failure leaves this item queued and the accepted snapshot/version unchanged.
                sink.sectionBuilt(section, flags);
                accepted.put(section.key, new Capture(section, hash));
                structureVersion++;
                nearest = Math.min(nearest, distanceSq(section, next, 1));
                queued++;
            }
            cursor++;
        }
        return new Result(queued, pending.size() - cursor, nearest, rescan);
    }

    private void rescan(Focus next) {
        int evictXz = Math.addExact(next.radiusXz(), evictionMargin);
        int evictY = Math.addExact(next.radiusY(), evictionMargin);
        for (Iterator<Capture> it = accepted.values().iterator(); it.hasNext(); ) {
            DecodedSection section = it.next().section();
            if (source.get(section.key) == null || !inRadius(section, next, evictXz, evictY)) {
                sink.sectionRemoved(section.key);
                it.remove();
                structureVersion++;
            }
        }
        ArrayList<DecodedSection> queue = new ArrayList<>();
        source.forEach(section -> {
            Capture old = accepted.get(section.key);
            if ((old == null || old.section() != section)
                    && inRadius(section, next, next.radiusXz(), next.radiusY())) queue.add(section);
        });
        queue.sort(Comparator.<DecodedSection>comparingLong(section -> distanceSq(section, next, 2))
            .thenComparingLong(section -> section.key));
        pending = queue;
        cursor = 0;
    }

    private static boolean inRadius(DecodedSection section, Focus focus, int radiusXz, int radiusY) {
        return InstanceMath.inRadius(section.sectionX, section.sectionY, section.sectionZ,
            focus.x(), focus.y(), focus.z(), radiusXz, radiusY);
    }

    private static long distanceSq(DecodedSection section, Focus focus, int verticalWeight) {
        long dx = (long) section.sectionX - focus.x();
        long dy = ((long) section.sectionY - focus.y()) * verticalWeight;
        long dz = (long) section.sectionZ - focus.z();
        return dx * dx + dy * dy + dz * dz;
    }

    long structureVersion() { return structureVersion; }
    long identicalSkipped() { return identicalSkipped; }
    void forEach(Consumer<DecodedSection> visitor) { accepted.values().forEach(value -> visitor.accept(value.section())); }

    void clear() {
        accepted.clear();
        pending = List.of();
        cursor = 0;
        focus = null;
        sourceVersion = -1;
    }
}
