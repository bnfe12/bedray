package io.github.andrexarlay.vulkancompat.rt.world;

import io.github.andrexarlay.vulkancompat.rt.scene.BedraySceneNative;
import io.github.andrexarlay.vulkancompat.rt.sodium.DecodedSection;
import io.github.andrexarlay.vulkancompat.rt.sodium.SectionGeometryRegistry;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Consumer;

/** CPU capture index and POD packer. Native owns admission, eviction and committed section identity. */
final class NativeSectionCapture {
    record Result(int queued, int pending, long nearestSq, boolean rescanned) {}
    private record Capture(DecodedSection section, long revision, Long tagHash) {}

    private final SectionGeometryRegistry source;
    private final NativeSceneBridge scene;
    private final Function<DecodedSection, int[]> tagger;
    private Map<Long, Capture> index = Map.of();
    // Capture bytes retained for emitter extraction while the C++ scene still references their revisions.
    private final Map<Long, Capture> submitted = new HashMap<>();
    private Map<Long, Capture> committedCaptures = Map.of();
    private long sourceVersion = -1;
    private long nextRevision = 1;
    private long identicalSkipped;

    NativeSectionCapture(SectionGeometryRegistry source, NativeSceneBridge scene, Function<DecodedSection, int[]> tagger) {
        this.source = source;
        this.scene = scene;
        this.tagger = tagger;
    }

    Result update(int x, int y, int z, int radiusXz, int radiusY, int limit, long budgetNanos) {
        long version = source.version();
        boolean refresh = version != sourceVersion;
        List<BedraySceneNative.Candidate> candidates = null;
        if (refresh) {
            var next = new HashMap<Long, Capture>();
            var metadata = new ArrayList<BedraySceneNative.Candidate>(source.sectionCount());
            source.forEachSection(section -> {
                long id = sceneId(section.key);
                Capture old = index.get(id);
                long revision;
                Long tagHash = null;
                if (old != null && old.section() == section) {
                    revision = old.revision();
                    tagHash = old.tagHash();
                } else if (old != null && old.tagHash() != null && SectionGeometryIdentity.same(old.section(), section)) {
                    tagHash = tagHash(section, tagger.apply(section));
                    if (old.tagHash().equals(tagHash)) {
                        revision = old.revision();
                        identicalSkipped++;
                    } else {
                        revision = nextRevision++;
                    }
                } else {
                    revision = nextRevision++;
                }
                next.put(id, new Capture(section, revision, tagHash));
                metadata.add(new BedraySceneNative.Candidate(id, revision, section.sectionX, section.sectionY,
                    section.sectionZ, section.totalQuads()));
            });
            index = next;
            candidates = metadata;
        }
        var selected = scene.selectSections(candidates, x, y, z, radiusXz, radiusY, limit);
        sourceVersion = version;
        int queued = 0;
        long nearest = Long.MAX_VALUE;
        long started = System.nanoTime();
        for (long id : selected.requests()) {
            if (queued > 0 && System.nanoTime() - started >= budgetNanos) break;
            Capture capture = index.get(id);
            if (capture == null || source.get(capture.section().key) != capture.section()) continue;
            var section = capture.section();
            int[] flags = tagger.apply(section);
            scene.submitSection(id, capture.revision(), section, flags);
            Capture packed = new Capture(section, capture.revision(), tagHash(section, flags));
            submitted.put(id, packed);
            index.put(id, packed);
            nearest = Math.min(nearest, InstanceMath.distanceSq(section.sectionX, section.sectionY, section.sectionZ, x, y, z));
            queued++;
        }
        return new Result(queued, Math.max(0, selected.pending() - queued), nearest, refresh);
    }

    private static long tagHash(DecodedSection section, int[] flags) {
        return SectionGeometryIdentity.lightHash(section.light, flags, section.totalQuads() * 4);
    }

    void forEachCommitted(Consumer<DecodedSection> visitor) {
        long[] pairs = scene.sectionRevisions();
        var retained = new HashMap<Long, Capture>();
        for (int i = 0; i < pairs.length; i += 2) {
            long id = pairs[i];
            long revision = pairs[i + 1];
            Capture capture = submitted.get(id);
            if (capture == null || capture.revision() != revision) capture = committedCaptures.get(id);
            if (capture != null && capture.revision() == revision) {
                retained.put(id, capture);
                visitor.accept(capture.section());
            }
        }
        committedCaptures = retained;
        submitted.keySet().removeIf(id -> !retained.containsKey(id) && !index.containsKey(id));
    }

    long identicalSkipped() { return identicalSkipped; }

    // All legal Minecraft X coordinates are inside the signed 22-bit packing range, excluding its minimum.
    // Toggling the sign bit preserves a stable ID including key 0, without a second residency index.
    static long sceneId(long key) {
        if (key == Long.MIN_VALUE) throw new IllegalArgumentException("section outside the supported world border");
        return key ^ Long.MIN_VALUE;
    }
}
