package io.github.andrexarlay.vulkancompat.rt.slang;

import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;

/** Shared ownership keyed by actual host object identity. A refused final close is retryable. */
final class IdentityLeasePool<K, R> {
    private final IdentityHashMap<K, Entry> entries = new IdentityHashMap<>();
    private final Function<K, R> create;
    private final Consumer<R> close;
    private final class Entry {
        final R resource;
        int owners = 1;
        Entry(R resource) { this.resource = resource; }
    }
    IdentityLeasePool(Function<K, R> create, Consumer<R> close) {
        this.create = Objects.requireNonNull(create); this.close = Objects.requireNonNull(close);
    }
    synchronized Lease acquire(K key) {
        Objects.requireNonNull(key, "host identity is required");
        Entry entry = entries.get(key);
        if (entry == null) {
            entry = new Entry(Objects.requireNonNull(create.apply(key), "resource factory returned null"));
            entries.put(key, entry);
        } else {
            if (entry.owners == Integer.MAX_VALUE) throw new IllegalStateException("too many runtime owners");
            entry.owners++;
        }
        return new Lease(key, entry);
    }
    final class Lease implements AutoCloseable {
        private final K key;
        private final Entry entry;
        private boolean released;
        private Lease(K key, Entry entry) { this.key = key; this.entry = entry; }
        R resource() {
            synchronized (IdentityLeasePool.this) {
                if (released) throw new IllegalStateException("runtime lease is closed");
                return entry.resource;
            }
        }
        @Override public void close() {
            synchronized (IdentityLeasePool.this) {
                if (released) return;
                if (entry.owners == 1) {
                    close.accept(entry.resource); // Refusal keeps this final owner and the identity cache intact.
                    entries.remove(key);
                }
                entry.owners--; released = true;
            }
        }
    }
}
