package io.github.andrexarlay.vulkancompat.rt.world;

import io.github.andrexarlay.vulkancompat.rt.sodium.DecodedSection;
import io.github.andrexarlay.vulkancompat.rt.sodium.SodiumMeshDecoder;
import org.junit.jupiter.api.Test;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;

class NativeSectionCaptureTest {
    private static final class Source implements NativeSectionCapture.Source {
        final Map<Long, DecodedSection> sections = new LinkedHashMap<>();
        long version;
        void put(DecodedSection section) { sections.put(section.key, section); version++; }
        void remove(long key) { sections.remove(key); version++; }
        public long version() { return version; }
        public DecodedSection get(long key) { return sections.get(key); }
        public void forEach(Consumer<DecodedSection> visitor) { sections.values().forEach(visitor); }
    }
    private static final class Sink implements NativeSectionCapture.Sink {
        final List<Long> built = new ArrayList<>(), removed = new ArrayList<>();
        boolean failBuild, failRemoval;
        public void sectionBuilt(DecodedSection section, int[] flags) {
            if (failBuild) throw new IllegalStateException("injected copy failure");
            built.add(section.key);
        }
        public void sectionRemoved(long key) {
            if (failRemoval) throw new IllegalStateException("injected eviction failure");
            removed.add(key);
        }
    }
    private static DecodedSection section(long key, int x, int y) {
        return SodiumMeshDecoder.decode(key, x, y, 0, new ByteBuffer[]{ByteBuffer.allocate(4 * 20), null, null});
    }
    private static NativeSectionCapture capture(Source source, Sink sink) {
        return new NativeSectionCapture(source, sink, ignored -> null, () -> 0, 1);
    }

    @Test void nearestFirstOrderAndQuotaAreDeterministic() {
        Source source = new Source();
        Sink sink = new Sink();
        source.put(section(9, 2, 0));
        source.put(section(5, 0, 1)); // same weighted distance as x=2; tie by section key
        source.put(section(1, 0, 0));
        NativeSectionCapture capture = capture(source, sink);
        var first = capture.update(0, 0, 0, 8, 8, 1, 1_000);
        assertEquals(List.of(1L), sink.built);
        assertEquals(2, first.pending());
        assertEquals(0, first.nearestSq());
        assertTrue(first.rescanned());
        var next = capture.update(0, 0, 0, 8, 8, 5, 1_000);
        assertEquals(List.of(1L, 5L, 9L), sink.built);
        assertEquals(0, next.pending());
        assertFalse(next.rescanned());
        assertEquals(3, capture.structureVersion());
    }

    @Test void failedCopyKeepsTheSameItemQueuedAndDoesNotPublishIt() {
        Source source = new Source();
        Sink sink = new Sink();
        source.put(section(1, 0, 0));
        NativeSectionCapture capture = capture(source, sink);
        sink.failBuild = true;
        assertThrows(IllegalStateException.class, () -> capture.update(0, 0, 0, 2, 2, 1, 1_000));
        assertEquals(0, capture.structureVersion());
        List<DecodedSection> accepted = new ArrayList<>();
        capture.forEach(accepted::add);
        assertTrue(accepted.isEmpty());
        sink.failBuild = false;
        assertEquals(1, capture.update(0, 0, 0, 2, 2, 1, 1_000).queued());
        assertEquals(List.of(1L), sink.built);
    }

    @Test void identicalRemeshKeepsHistoryButDifferentTagsRequireAnUpdate() {
        Source source = new Source();
        Sink sink = new Sink();
        int[] flags = {0};
        NativeSectionCapture capture = new NativeSectionCapture(source, sink, ignored -> flags, () -> 0, 0);
        source.put(section(1, 0, 0));
        capture.update(0, 0, 0, 2, 2, 4, 1_000);
        DecodedSection replacement = section(1, 0, 0);
        source.put(replacement);
        assertEquals(0, capture.update(0, 0, 0, 2, 2, 4, 1_000).queued());
        assertEquals(1, capture.identicalSkipped());
        assertEquals(1, capture.structureVersion());
        List<DecodedSection> accepted = new ArrayList<>();
        capture.forEach(accepted::add);
        assertSame(replacement, accepted.getFirst());
        flags[0] = RtWorld.VEGETATION_BIT;
        source.put(section(1, 0, 0));
        assertEquals(1, capture.update(0, 0, 0, 2, 2, 4, 1_000).queued());
        assertEquals(2, capture.structureVersion());
    }

    @Test void staleQueuedCapturesAreNotSentAndCanBeRescanned() {
        Source source = new Source();
        Sink sink = new Sink();
        source.put(section(1, 0, 0));
        source.put(section(2, 1, 0));
        NativeSectionCapture capture = capture(source, sink);
        capture.update(0, 0, 0, 8, 8, 1, 1_000);
        source.sections.put(2L, section(2, 1, 0)); // registry changed after the queue's snapshot
        assertEquals(0, capture.update(0, 0, 0, 8, 8, 4, 1_000).queued());
        source.version++;
        assertEquals(1, capture.update(0, 0, 0, 8, 8, 4, 1_000).queued());
        assertEquals(List.of(1L, 2L), sink.built);
    }

    @Test void evictionFailureRetainsTheSnapshotForRetryAndCameraChangesRescan() {
        Source source = new Source();
        Sink sink = new Sink();
        source.put(section(1, 0, 0));
        NativeSectionCapture capture = capture(source, sink);
        capture.update(0, 0, 0, 1, 1, 4, 1_000);
        source.remove(1);
        sink.failRemoval = true;
        assertThrows(IllegalStateException.class, () -> capture.update(0, 0, 0, 1, 1, 4, 1_000));
        assertEquals(1, capture.structureVersion());
        sink.failRemoval = false;
        capture.update(0, 0, 0, 1, 1, 4, 1_000);
        assertEquals(List.of(1L), sink.removed);
        assertEquals(2, capture.structureVersion());
        source.put(section(2, 10, 0));
        assertEquals(0, capture.update(0, 0, 0, 1, 1, 4, 1_000).queued());
        assertEquals(1, capture.update(10, 0, 0, 1, 1, 4, 1_000).queued());
    }

    @Test void timeBudgetAlsoBoundsIdenticalCapturesAndClearAllowsARescan() {
        Source source = new Source();
        Sink sink = new Sink();
        long[] clock = {0};
        NativeSectionCapture capture = new NativeSectionCapture(source, sink, ignored -> null, () -> clock[0]++, 0);
        source.put(section(1, 0, 0));
        source.put(section(2, 1, 0));
        assertEquals(1, capture.update(0, 0, 0, 4, 4, 8, 1).pending());
        capture.update(0, 0, 0, 4, 4, 8, 1);
        source.put(section(1, 0, 0));
        source.put(section(2, 1, 0));
        var identical = capture.update(0, 0, 0, 4, 4, 8, 1);
        assertEquals(0, identical.queued());
        assertEquals(1, identical.pending());
        capture.clear();
        assertEquals(1, capture.update(0, 0, 0, 4, 4, 8, 1).queued());
    }
}
