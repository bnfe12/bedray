package io.github.andrexarlay.vulkancompat.rt.world;

import java.util.Map;
import java.util.TreeMap;

/**
 * First-fit sub-allocator over a linear address range with coalescing free list. Pure Java (unit-tested), used by
 * {@link RtArena} to carve section geometry and BLAS storage out of a few large Vulkan buffers.
 * Not thread-safe: used from the render thread only.
 */
public final class FreeListAllocator {
    private final TreeMap<Long, Long> free = new TreeMap<>();
    private final long capacity;
    private long used;

    public FreeListAllocator(long capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive: " + capacity);
        }
        this.capacity = capacity;
        this.free.put(0L, capacity);
    }

    public static long roundUp(long value, long align) {
        return (value + align - 1) / align * align;
    }

    /**
     * Allocates {@code size} bytes (rounded up to a multiple of {@code align}) at an offset that is a multiple of
     * {@code align}. Returns the offset, or -1 when no free range is large enough.
     */
    public long alloc(long size, long align) {
        if (size <= 0 || align <= 0) {
            throw new IllegalArgumentException("size=" + size + " align=" + align);
        }
        long rounded = roundUp(size, align);
        for (Map.Entry<Long, Long> e : free.entrySet()) {
            long off = e.getKey();
            long len = e.getValue();
            long aligned = roundUp(off, align);
            long pad = aligned - off;
            if (len >= pad + rounded) {
                free.remove(off);
                if (pad > 0) {
                    free.put(off, pad);
                }
                long rest = len - pad - rounded;
                if (rest > 0) {
                    free.put(aligned + rounded, rest);
                }
                used += rounded;
                return aligned;
            }
        }
        return -1;
    }

    /** Frees a range previously returned by {@link #alloc} (same size and align as at allocation time). */
    public void free(long offset, long size, long align) {
        long rounded = roundUp(size, align);
        if (offset < 0 || offset + rounded > capacity) {
            throw new IllegalArgumentException("range outside the allocator: " + offset + "+" + rounded);
        }
        long start = offset;
        long len = rounded;
        Map.Entry<Long, Long> prev = free.floorEntry(offset);
        if (prev != null) {
            if (prev.getKey() + prev.getValue() > offset) {
                throw new IllegalStateException("double free or overlap at " + offset);
            }
            if (prev.getKey() + prev.getValue() == offset) {
                start = prev.getKey();
                len += prev.getValue();
                free.remove(prev.getKey());
            }
        }
        Map.Entry<Long, Long> next = free.ceilingEntry(offset);
        if (next != null) {
            if (next.getKey() < offset + rounded) {
                throw new IllegalStateException("double free or overlap at " + offset);
            }
            if (next.getKey() == offset + rounded) {
                len += next.getValue();
                free.remove(next.getKey());
            }
        }
        free.put(start, len);
        used -= rounded;
    }

    public long used() {
        return used;
    }

    public long capacity() {
        return capacity;
    }

    /** Number of separate free ranges (fragmentation indicator). */
    public int freeRangeCount() {
        return free.size();
    }
}
