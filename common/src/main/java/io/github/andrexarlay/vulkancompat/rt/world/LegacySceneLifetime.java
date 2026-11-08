package io.github.andrexarlay.vulkancompat.rt.world;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Exact owners of the legacy scene's shared inputs.
 *
 * <p>A legacy update writes one staging/instance segment ({@code frameIndex % segments}) and may retire sections or
 * entity BLAS that earlier, still-running recordings reference. A recording owns its segment and every object retired
 * while it is the newest recording; only that recording's exact completion, its deliberate discard or a host drain
 * (which completes every ticket) releases them. Frame age never does. An update needs a host drain only when the
 * segment it is about to write is still owned (2026-11-08: the old rule drained every frame, so the GPU idled while
 * the CPU recorded the next frame).
 */
final class LegacySceneLifetime {
    private record Owner(int segment, List<Runnable> retired) {
    }

    private final int segments;
    /** Insertion order is recording order: the last entry is the newest recording. */
    private final LinkedHashMap<Long, Owner> owners = new LinkedHashMap<>();

    LegacySceneLifetime(int segments) {
        if (segments < 2) throw new IllegalArgumentException("ownership needs at least two segments, got " + segments);
        this.segments = segments;
    }

    /** True when the segment written by the update for {@code frameIndex} is still owned by an unreleased recording. */
    boolean segmentBusy(long frameIndex) {
        int segment = segmentOf(frameIndex);
        for (Owner owner : owners.values()) {
            if (owner.segment == segment) return true;
        }
        return false;
    }

    void begin(long commandBuffer, long frameIndex) {
        if (commandBuffer == 0) throw new IllegalStateException("legacy scene inputs require a recording");
        if (owners.containsKey(commandBuffer)) throw new IllegalStateException("recording already owns legacy inputs");
        if (segmentBusy(frameIndex)) throw new IllegalStateException("legacy segment still owned; host drain required");
        owners.put(commandBuffer, new Owner(segmentOf(frameIndex), new ArrayList<>()));
    }

    /** Runs {@code release} once no recording that could reference the retired object is in flight. */
    void retire(Runnable release) {
        Owner newest = newest();
        if (newest == null) release.run();
        else newest.retired.add(release);
    }

    /** Exact completion (or host proof) of {@code commandBuffer}; a stale or repeated handle releases nothing. */
    void release(long commandBuffer) {
        Owner owner = owners.remove(commandBuffer);
        if (owner != null) runAll(owner.retired);
    }

    /** The recording never executed; what it retired may still be referenced by the recordings before it. */
    void discard(long commandBuffer) {
        Owner owner = owners.remove(commandBuffer);
        if (owner == null) return;
        Owner newest = newest();
        if (newest == null) runAll(owner.retired);
        else newest.retired.addAll(owner.retired);
    }

    void requireReleased() {
        if (!owners.isEmpty()) throw new IllegalStateException("legacy scene recordings still in use: " + owners.size());
    }

    int owned() {
        return owners.size();
    }

    private int segmentOf(long frameIndex) {
        return (int) Math.floorMod(frameIndex, (long) segments);
    }

    private Owner newest() {
        Owner last = null;
        for (Owner owner : owners.values()) last = owner;
        return last;
    }

    private static void runAll(List<Runnable> releases) {
        for (Runnable release : releases) release.run();
    }
}
