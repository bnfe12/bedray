package io.github.andrexarlay.vulkancompat.rt.world;

import java.nio.file.Path;
import java.util.function.LongConsumer;
import java.util.function.LongFunction;

/** Serializes synchronous cache borrows and retirement, including callers which already captured this owner. */
final class PipelineCacheOwner {
    private long handle;
    private Path file;
    private volatile boolean closed;
    private boolean closing;
    private int borrows;

    PipelineCacheOwner(long handle, Path file) { this.handle = handle; this.file = file; }
    void file(Path file) { this.file = file; }
    /** Initialization publishes a successful Vulkan handle immediately, before any logging/allocation. */
    void initialized(long handle) { this.handle = handle; }
    synchronized void initializationFailed() { closing = true; }
    Path file() { return file; }
    boolean isClosed() { return closed; }

    synchronized <T> T use(LongFunction<T> operation) {
        if (closing || closed) throw new IllegalStateException("pipeline cache retired; handle=0x" + Long.toHexString(handle));
        borrows++;
        try { return operation.apply(handle); }
        finally { borrows--; }
    }

    synchronized void close(LongConsumer retirement) {
        if (closed) return;
        if (borrows != 0) throw new IllegalStateException("pipeline cache close inside a borrow; handle=0x" + Long.toHexString(handle));
        closing = true;
        retirement.accept(handle);
        closed = true; // Failure above preserves ownership and refuses new borrows until a successful retry.
    }
}
