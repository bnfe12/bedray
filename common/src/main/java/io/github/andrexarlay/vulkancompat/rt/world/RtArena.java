package io.github.andrexarlay.vulkancompat.rt.world;

import java.util.ArrayList;
import java.util.List;

/**
 * Growable arena of device-local buffers with the same usage flags; blocks are carved with {@link FreeListAllocator}.
 * Chunks are never shrunk or moved, so every returned device address stays valid until the block is freed.
 */
public final class RtArena {
    /** One chunk = one VkBuffer. */
    static final class Chunk {
        final RtMemory.Buf buf;
        final FreeListAllocator alloc;

        Chunk(RtMemory.Buf buf, FreeListAllocator alloc) {
            this.buf = buf;
            this.alloc = alloc;
        }
    }

    /** A sub-range of a chunk. */
    public static final class Block {
        final Chunk chunk;
        public final long offset;
        public final long size;
        public final long align;

        Block(Chunk chunk, long offset, long size, long align) {
            this.chunk = chunk;
            this.offset = offset;
            this.size = size;
            this.align = align;
        }

        public long buffer() {
            return chunk.buf.handle;
        }

        /** Device address of the block start (0 when the arena has no device-address usage). */
        public long address() {
            return chunk.buf.address == 0L ? 0L : chunk.buf.address + offset;
        }
    }

    private final RtMemory mem;
    private final String name;
    private final int usage;
    private final long chunkBytes;
    private final List<Chunk> chunks = new ArrayList<>();

    public RtArena(RtMemory mem, String name, int usage, long chunkBytes) {
        this.mem = mem;
        this.name = name;
        this.usage = usage;
        this.chunkBytes = chunkBytes;
    }

    public Block alloc(long size, long align) {
        for (Chunk c : chunks) {
            long off = c.alloc.alloc(size, align);
            if (off >= 0) {
                return new Block(c, off, size, align);
            }
        }
        long capacity = Math.max(chunkBytes, FreeListAllocator.roundUp(size, align));
        RtMemory.Buf buf = mem.createBuffer(name + "#" + chunks.size(), capacity, usage, false);
        Chunk chunk = new Chunk(buf, new FreeListAllocator(buf.size));
        chunks.add(chunk);
        long off = chunk.alloc.alloc(size, align);
        if (off < 0) {
            throw new RtFailure("arena:" + name, "alloc_after_new_chunk_failed size=" + size);
        }
        return new Block(chunk, off, size, align);
    }

    public void free(Block block) {
        block.chunk.alloc.free(block.offset, block.size, block.align);
    }

    public long usedBytes() {
        long used = 0;
        for (Chunk c : chunks) {
            used += c.alloc.used();
        }
        return used;
    }

    public long reservedBytes() {
        long r = 0;
        for (Chunk c : chunks) {
            r += c.buf.size;
        }
        return r;
    }

    public void destroy() {
        for (Chunk c : chunks) {
            mem.destroy(c.buf);
        }
        chunks.clear();
    }
}
