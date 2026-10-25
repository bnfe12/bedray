package io.github.andrexarlay.vulkancompat.compute;

import io.github.andrexarlay.bedray.api.v1.SectionKey;
import net.minecraft.client.multiplayer.ClientChunkCache;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Set;

/** Bounded notification delivery; light callbacks never capture world data or touch the provider. */
final class ComputeLightInvalidations {
    private static final int LIMIT = 8192, DRAIN_LIMIT = 256;
    private record Change(ClientChunkCache source, SectionKey key) {}
    private final Set<Change> pending = new LinkedHashSet<>();
    private volatile boolean enabled;
    private boolean overflow;

    synchronized void setEnabled(boolean value) {
        enabled = value;
        if (!value) { pending.clear(); overflow = false; }
    }

    void changed(ClientChunkCache source, int x, int y, int z) {
        if (!enabled) return;
        Change change = new Change(source, new SectionKey(x, y, z));
        synchronized (this) {
            if (!enabled || pending.contains(change)) return;
            if (pending.size() >= LIMIT) overflow = true;
            else pending.add(change);
        }
    }

    /** Called on the client thread. An overflow requests a fresh stream session, not silent data loss. */
    void drain(ClientChunkCache current, ComputeWorldCapture capture) {
        ArrayList<SectionKey> changes;
        boolean reset;
        synchronized (this) {
            if (!enabled || current == null) { pending.clear(); overflow = false; return; }
            if (pending.isEmpty() && !overflow) return;
            changes = new ArrayList<>(Math.min(DRAIN_LIMIT, pending.size()));
            reset = overflow;
            if (reset) { pending.clear(); overflow = false; }
            else {
                var iterator = pending.iterator();
                for (int i = 0; i < DRAIN_LIMIT && iterator.hasNext(); i++) {
                    Change change = iterator.next(); iterator.remove();
                    // A callback from a departed world cannot dirty the next world's same coordinates.
                    if (change.source() == current) changes.add(change.key());
                }
            }
        }
        if (reset) capture.clear();
        else for (SectionKey key : changes) capture.sectionChanged(key.x(), key.y(), key.z());
    }
}
