package io.github.andrexarlay.vulkancompat.compute;

import io.github.andrexarlay.bedray.api.v1.SectionKey;
import net.minecraft.client.multiplayer.ClientChunkCache;

import java.util.LinkedHashSet;
import java.util.Set;

/** Bounded notification delivery; light callbacks never capture world data or touch the provider. */
final class ComputeLightInvalidations {
    private static final int LIMIT = 8192, DRAIN_LIMIT = 256;
    private final Set<SectionKey> pending = new LinkedHashSet<>();
    private volatile ClientChunkCache activeSource;
    private boolean overflow;

    synchronized void setSource(ClientChunkCache source) {
        if (activeSource != source) { pending.clear(); overflow = false; activeSource = source; }
    }

    void changed(ClientChunkCache source, int x, int y, int z) {
        if (source == null || activeSource != source) return;
        SectionKey key = new SectionKey(x, y, z);
        synchronized (this) {
            if (activeSource != source || pending.contains(key)) return;
            if (pending.size() >= LIMIT) overflow = true;
            else pending.add(key);
        }
    }

    /** Called on the client thread. Overflow requests bounded recapture, preserving current GPU residency. */
    void drain(ClientChunkCache current, ComputeWorldCapture capture) {
        boolean reset;
        synchronized (this) {
            if (activeSource != current || current == null) { pending.clear(); overflow = false; return; }
            if (pending.isEmpty() && !overflow) return;
            reset = overflow;
            if (reset) { pending.clear(); overflow = false; }
            else {
                var iterator = pending.iterator();
                for (int i = 0; i < DRAIN_LIMIT && iterator.hasNext(); i++) {
                    SectionKey key = iterator.next();
                    // Keep rejected coordinates here until the bounded capture queue has capacity.
                    // Marking dirty does not read game data, so this short client-thread loop can
                    // share the notification lock without invoking capture or provider code.
                    if (!capture.sectionChanged(key.x(), key.y(), key.z())) break;
                    iterator.remove();
                }
            }
        }
        if (reset) capture.refreshSections();
    }
}
