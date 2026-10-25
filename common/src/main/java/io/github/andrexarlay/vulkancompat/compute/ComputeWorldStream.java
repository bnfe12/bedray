package io.github.andrexarlay.vulkancompat.compute;

import io.github.andrexarlay.bedray.api.v1.*;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

/** Client-thread incremental capture. Coordinates, rather than captured payloads, wait in the dirty queue.
 * Polling is bounded and also discovers loaded chunks independently of Sodium visibility and hardware RT.
 */
final class ComputeWorldStream {
    interface Source {
        boolean loaded(SectionKey key);
        boolean empty(SectionKey key);
        SectionSnapshot capture(SectionKey key, long revision);
    }
    static final int UPLOAD_BYTES = 2 * 1024 * 1024;
    private static final long CACHE_BYTES = 32L * 1024 * 1024;
    private static final long CAPTURE_NANOS = 2_000_000;
    private static final int DISCOVERY_CHECKS = 256, CAPTURES = 4, DIRTY_LIMIT = 8192;
    private final LinkedHashMap<SectionKey, SectionSnapshot> recent = new LinkedHashMap<>(128, .75f, true);
    private final ArrayDeque<SectionKey> dirty = new ArrayDeque<>();
    private final Set<SectionKey> dirtySet = new HashSet<>();
    private final Set<SectionKey> removed = new java.util.LinkedHashSet<>();
    private final LinkedHashMap<SectionKey, Long> retryAfter = new LinkedHashMap<>();
    private final BitSet discovered = new BitSet(), empty = new BitSet(), captured = new BitSet();
    private Object world;
    private long generation, session, revision, cacheBytes, frame, nextRefresh;
    private WorldCoverageBounds bounds;
    private int discoveredCount, emptyCount, capturedCount, verticalCenter = Integer.MIN_VALUE;
    private int discoveryShell, discoveryY, discoveryColumn = -1;
    private SectionKey discoveryKey;
    private int[] vertical;
    private boolean reset, refreshing;
    private SectionSnapshot pending;

    void clear() {
        world = null; generation = 0; bounds = null; recent.clear(); dirty.clear(); dirtySet.clear();
        removed.clear(); discovered.clear(); empty.clear(); captured.clear(); cacheBytes = 0;
        discoveredCount = emptyCount = capturedCount = 0; pending = null; refreshing = false;
        discoveryShell = discoveryY = 0; discoveryColumn = -1; discoveryKey = null;
        verticalCenter = Integer.MIN_VALUE; retryAfter.clear();
    }
    void changed(int x, int y, int z) {
        SectionKey key = new SectionKey(x, y, z);
        if (bounds == null || !bounds.contains(key)) return;
        if (dirtySet.size() < DIRTY_LIMIT && dirtySet.add(key)) dirty.addLast(key);
    }
    void removed(int x, int y, int z) {
        SectionKey key = new SectionKey(x, y, z);
        if (bounds == null || !bounds.contains(key)) return;
        // A mass unload replaces the session rather than retaining an unbounded removal list.
        if (removed.size() >= DIRTY_LIMIT) { clear(); return; }
        removed.add(key);
        invalidate(key);
    }
    void invalidated(SectionKey key) {
        if (bounds == null || !bounds.contains(key)) return;
        invalidate(key);
        retryAfter.put(key, frame + 120);
        while (retryAfter.size() > DIRTY_LIMIT) retryAfter.remove(retryAfter.keySet().iterator().next());
    }
    private int index(SectionKey key) {
        int width = 2 * bounds.effectiveRenderDistanceChunks() + 1;
        int height = bounds.maxSectionY() - bounds.minSectionY() + 1;
        return key.x() - bounds.minSectionX() + width *
            (key.y() - bounds.minSectionY() + height * (key.z() - bounds.minSectionZ()));
    }
    private void invalidate(SectionKey key) {
        int i = index(key);
        if (discovered.get(i)) { discovered.clear(i); discoveredCount--; }
        if (empty.get(i)) { empty.clear(i); emptyCount--; }
        if (captured.get(i)) { captured.clear(i); capturedCount--; }
        SectionSnapshot old = recent.remove(key);
        if (old != null) cacheBytes -= cacheCost(old);
        if (pending != null && pending.x() == key.x() && pending.y() == key.y() && pending.z() == key.z()) pending = null;
    }

    WorldUpdate capture(Object identity, long atlasGeneration, CameraSnapshot camera, WorldCoverageBounds next,
                        Source source, int providerQueuedSections, WorldRenderQuality renderQuality) {
        frame++;
        if (world != identity || generation != atlasGeneration) {
            clear(); world = identity; generation = atlasGeneration; session++; revision++; reset = true;
            nextRefresh = frame + 600;
        }
        int cameraY = Math.clamp((int) Math.floor(camera.y() / 16), next.minSectionY(), next.maxSectionY());
        if (!next.equals(bounds) || cameraY != verticalCenter) {
            boolean domainChanged = bounds == null || next.centerSectionX() != bounds.centerSectionX()
                || next.centerSectionZ() != bounds.centerSectionZ()
                || next.effectiveRenderDistanceChunks() != bounds.effectiveRenderDistanceChunks()
                || next.minSectionY() != bounds.minSectionY() || next.maxSectionY() != bounds.maxSectionY()
                || cameraY != verticalCenter;
            bounds = next;
            if (domainChanged) {
                discovered.clear(); empty.clear(); captured.clear();
                discoveredCount = emptyCount = capturedCount = 0;
                discoveryShell = discoveryY = 0; discoveryColumn = -1; discoveryKey = null;
                verticalCenter = cameraY; retryAfter.clear();
                vertical = new int[next.maxSectionY() - next.minSectionY() + 1];
                int count = 0;
                for (int d = 0; count < vertical.length; d++) {
                    if (cameraY + d <= next.maxSectionY()) vertical[count++] = cameraY + d;
                    if (d != 0 && cameraY - d >= next.minSectionY()) vertical[count++] = cameraY - d;
                }
                var iterator = recent.entrySet().iterator();
                while (iterator.hasNext()) {
                    var entry = iterator.next();
                    if (!bounds.contains(entry.getKey())) { cacheBytes -= cacheCost(entry.getValue()); iterator.remove(); }
                }
                if (pending != null && !bounds.contains(new SectionKey(pending.x(), pending.y(), pending.z()))) pending = null;
            }
        }
        List<SectionSnapshot> upserts = new ArrayList<>();
        List<SectionKey> removals = new ArrayList<>();
        var removalIterator = removed.iterator();
        while (removalIterator.hasNext() && removals.size() < 256) {
            removals.add(removalIterator.next()); removalIterator.remove();
        }
        int used = 0, captures = 0;
        long deadline = System.nanoTime() + CAPTURE_NANOS;
        boolean backpressure = providerQueuedSections >= 4;
        if (pending != null && !backpressure) {
            upserts.add(pending); used = bytes(pending); pending = null;
        }
        for (int checks = 0; checks < DISCOVERY_CHECKS; checks++) {
            if (captures >= CAPTURES || upserts.size() >= CAPTURES || used + 826 * 1024 > UPLOAD_BYTES
                    || System.nanoTime() >= deadline) break;
            boolean fromDirty = !dirty.isEmpty();
            SectionKey key;
            if (fromDirty) { key = dirty.removeFirst(); dirtySet.remove(key); }
            else key = discovery();
            if (!bounds.contains(key)) continue;
            if (!fromDirty && retryAfter.getOrDefault(key, 0L) > frame) { discoveryKey = null; continue; }
            int i = index(key);
            if (!source.loaded(key)) {
                if (captured.get(i) || recent.containsKey(key)) { removals.add(key); invalidate(key); }
            } else {
                if (!discovered.get(i)) { discovered.set(i); discoveredCount++; }
                boolean isEmpty = source.empty(key);
                boolean needsCapture = fromDirty || !captured.get(i) || isEmpty != empty.get(i) || refreshing;
                if (needsCapture) {
                    if (backpressure) {
                        if (fromDirty && dirtySet.add(key)) dirty.addFirst(key);
                        break;
                    }
                    // Air-only geometry can still carry nonuniform propagated sky/block light.
                    // Capture that light payload; the provider compacts genuinely uniform air pages.
                    SectionSnapshot section = source.capture(key, ++revision);
                    captures++;
                    if (section == null) { removals.add(key); invalidate(key); }
                    else {
                        SectionSnapshot old = recent.get(key);
                        if (old == null || !old.hasSameContent(section)) {
                            int size = bytes(section);
                            if (used + size <= UPLOAD_BYTES) { upserts.add(section); used += size; }
                            else pending = section;
                            remember(key, section);
                        }
                        if (!captured.get(i)) { captured.set(i); capturedCount++; }
                        if (isEmpty && !empty.get(i)) { empty.set(i); emptyCount++; }
                        if (!isEmpty && empty.get(i)) { empty.clear(i); emptyCount--; }
                    }
                }
            }
            if (!fromDirty) discoveryKey = null;
        }
        WorldUpdate result = new WorldUpdate(session, revision, camera, generation, bounds, upserts, removals,
            reset, discoveredCount, emptyCount, Math.max(0, discoveredCount - capturedCount) + dirtySet.size()
                + (pending == null ? 0 : 1), renderQuality);
        reset = false;
        return result;
    }
    private void remember(SectionKey key, SectionSnapshot section) {
        SectionSnapshot old = recent.put(key, section);
        if (old != null) cacheBytes -= cacheCost(old);
        cacheBytes += cacheCost(section);
        var iterator = recent.entrySet().iterator();
        while (cacheBytes > CACHE_BYTES && iterator.hasNext()) {
            var entry = iterator.next(); cacheBytes -= cacheCost(entry.getValue()); iterator.remove();
        }
    }
    private static long cacheCost(SectionSnapshot section) { return 65536L + 2L * bytes(section); }

    /** Enumerate 3D Chebyshev shells: the nearest 27 sections precede every more distant section.
     * No complete world-sized position list or sort is needed, including after vertical movement. */
    private SectionKey discovery() {
        if (discoveryKey != null) return discoveryKey;
        int radius = bounds.effectiveRenderDistanceChunks();
        int maxShell = Math.max(radius, Math.max(verticalCenter - bounds.minSectionY(), bounds.maxSectionY() - verticalCenter));
        while (true) {
            if (discoveryShell > maxShell) {
                discoveryShell = discoveryY = 0; discoveryColumn = -1;
                refreshing = frame >= nextRefresh;
                if (refreshing) nextRefresh = frame + 600;
            }
            if (discoveryY == vertical.length) {
                discoveryShell++; discoveryY = 0; discoveryColumn = -1; continue;
            }
            int y = vertical[discoveryY], distance = Math.abs(y - verticalCenter);
            if (distance > discoveryShell || discoveryShell > radius && distance != discoveryShell) {
                discoveryY++; discoveryColumn = -1; continue;
            }
            int ring = Math.min(radius, discoveryShell);
            int start = distance == discoveryShell ? 0 : (2 * ring - 1) * (2 * ring - 1);
            int end = (2 * ring + 1) * (2 * ring + 1);
            if (discoveryColumn < 0) discoveryColumn = start;
            if (discoveryColumn == end) { discoveryY++; discoveryColumn = -1; continue; }
            int[] offset = spiral(discoveryColumn++);
            discoveryKey = new SectionKey(bounds.centerSectionX() + offset[0], y, bounds.centerSectionZ() + offset[1]);
            return discoveryKey;
        }
    }
    /** Conservative packed payload accounting; immutable Java object overhead is additional. */
    static int bytes(SectionSnapshot section) {
        return 16384 + (section.tints() == null ? 0 : 16384)
            + (section.materials() == null ? 0 : section.materials().palette().size() * 192)
            + (section.models() == null ? 0 : section.models().palette().size() * 16 + section.models().quadCount() * 96);
    }
    private static int[] spiral(int n) {
        if (n == 0) return new int[]{0, 0};
        int ring = (int) Math.ceil((Math.sqrt(n + 1.0) - 1) / 2), side = 2 * ring;
        int d = (2 * ring + 1) * (2 * ring + 1) - 1 - n;
        if (d < side) return new int[]{ring - d, -ring};
        if (d < 2 * side) return new int[]{-ring, -ring + d - side};
        if (d < 3 * side) return new int[]{-ring + d - 2 * side, ring};
        return new int[]{ring, ring - d + 3 * side};
    }
}
