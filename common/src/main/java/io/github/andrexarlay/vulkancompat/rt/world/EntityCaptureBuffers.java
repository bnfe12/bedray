package io.github.andrexarlay.vulkancompat.rt.world;

import io.github.andrexarlay.vulkancompat.rt.entity.EntityTextureAtlas;
import io.github.andrexarlay.vulkancompat.rt.lifetime.RecordingResourceOwner;
import io.github.andrexarlay.vulkancompat.rt.lifetime.RecordingResourceOwner.Generation;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VK12;

/** GPU storage for host entity capture. Writable slots and immutable atlas generations share exact recording proof. */
final class EntityCaptureBuffers implements AutoCloseable {
    interface Allocator {
        RtMemory.Buf create(String name, long bytes, int usage);
        void destroy(RtMemory.Buf buffer);
    }
    interface AtlasSource {
        long version();
        void copyTo(long texels, long rects);
    }
    private static final int RING_SLOTS = RtEntityGeometryManager.RING;
    private static final int STORAGE = VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
    private static final int ADDRESS = STORAGE | VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT;
    private record Spec(String name, long bytes, int usage) {}
    record Geometry(RtMemory.Buf positions, RtMemory.Buf indices, RtMemory.Buf attributes, RtMemory.Buf previous,
                    long vertexCapacity, long indexCapacity, int slot) {}
    record Atlas(RtMemory.Buf texels, RtMemory.Buf rects, long version) {}

    private final Object memoryIdentity;
    private final Allocator allocator;
    private final RecordingResourceOwner<RtMemory.Buf[]> owner;
    private Generation<RtMemory.Buf[]> ring;
    private Generation<RtMemory.Buf[]> atlas;
    private Generation<RtMemory.Buf[]> quads;
    private long vertexCapacity, indexCapacity;
    private long atlasVersion = -1;

    EntityCaptureBuffers(RtMemory memory) {
        this(memory, new Allocator() {
            public RtMemory.Buf create(String name, long bytes, int usage) {
                return memory.createBuffer(name, bytes, usage, true);
            }
            public void destroy(RtMemory.Buf buffer) { memory.destroy(buffer); }
        });
    }

    EntityCaptureBuffers(Object memoryIdentity, Allocator allocator) {
        this.memoryIdentity = java.util.Objects.requireNonNull(memoryIdentity);
        this.allocator = java.util.Objects.requireNonNull(allocator);
        owner = new RecordingResourceOwner<>(buffers -> {
            for (int i = 0; i < buffers.length; i++) {
                if (buffers[i] == null) continue;
                allocator.destroy(buffers[i]);
                buffers[i] = null; // A partial destructor failure can retry without destroying earlier buffers twice.
            }
        });
    }

    boolean belongsTo(Object memory) { return memoryIdentity == memory; }
    void begin(long command) { owner.begin(command); }
    int pendingCount() { return owner.pendingCount(); }
    long retiredCount() { return owner.retiredCount(); }

    Geometry geometry(long command, int vertices, int indices) {
        if (vertices <= 0 || indices <= 0) throw new IllegalArgumentException("entity counts must be positive");
        var recording = owner.recording(command);
        if (recording == null) throw new IllegalStateException("entity recording was not begun");
        if (ring == null || vertices > vertexCapacity || indices > indexCapacity || owner.freeSlot(ring) < 0) {
            long nextVertices = Math.max(65536L, Long.highestOneBit((long) vertices) << 1);
            long nextIndices = Math.max(98304L, Long.highestOneBit((long) indices) << 1);
            Generation<RtMemory.Buf[]> next = create(RING_SLOTS,
                new Spec("entity_pos_ring", RING_SLOTS * nextVertices * 12L, ADDRESS | RtMemory.USAGE_AS_INPUT),
                new Spec("entity_idx_ring", RING_SLOTS * nextIndices * 4L, ADDRESS | RtMemory.USAGE_AS_INPUT),
                new Spec("entity_attr_ring", RING_SLOTS * nextVertices * 16L, ADDRESS),
                new Spec("entity_prev_ring", RING_SLOTS * nextVertices * 12L, ADDRESS));
            var old = ring;
            ring = next;
            vertexCapacity = nextVertices;
            indexCapacity = nextIndices;
            if (old != null) owner.retire(old);
        }
        int slot = owner.lease(recording, ring);
        if (slot < 0) throw new IllegalStateException("entity slot acquisition failed");
        var buffers = ring.value();
        return new Geometry(buffers[0], buffers[1], buffers[2], buffers[3], vertexCapacity, indexCapacity, slot);
    }

    Atlas atlas(long command, EntityTextureAtlas source) {
        return atlas(command, new AtlasSource() {
            public long version() { return source.version(); }
            public void copyTo(long texels, long rects) { source.copyTo(texels, rects); }
        });
    }

    Atlas atlas(long command, AtlasSource source) {
        if (owner.recording(command) == null) throw new IllegalStateException("entity recording was not begun");
        long version = source.version();
        if (atlas == null || version != atlasVersion) {
            int size = EntityTextureAtlas.SIZE;
            var next = create(0,
                new Spec("entity_tex_atlas", (long) size * size * 4L, ADDRESS),
                new Spec("entity_tex_rects", EntityTextureAtlas.MAX_SLOTS * 16L, ADDRESS));
            try {
                source.copyTo(next.value()[0].mapped, next.value()[1].mapped);
            } catch (RuntimeException | Error failure) {
                abandon(next, failure);
                throw failure;
            }
            var old = atlas;
            atlas = next;
            atlasVersion = version;
            if (old != null) owner.retire(old);
        }
        owner.pin(owner.recording(command), atlas);
        return new Atlas(atlas.value()[0], atlas.value()[1], atlasVersion);
    }

    RtMemory.Buf[] quads(long command, int count) {
        if (count <= 0) throw new IllegalArgumentException("quad count must be positive");
        if (owner.recording(command) == null) throw new IllegalStateException("entity recording was not begun");
        long[] sizes = {count * 4L * 12, count * 6L * 4, count * 4L * 8, count * 4L * 4, count * 4L * 4};
        boolean grow = quads == null || owner.freeSlot(quads) < 0;
        if (!grow) {
            for (int i = 0; i < sizes.length; i++) grow |= quads.value()[i].size < sizes[i];
        }
        if (grow) {
            int transfer = VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT;
            var next = create(1,
                new Spec("entity_vertex", Math.max(sizes[0], 1L << 20), STORAGE | transfer | RtMemory.USAGE_AS_INPUT),
                new Spec("entity_index", Math.max(sizes[1], 256L << 10), transfer | RtMemory.USAGE_AS_INPUT),
                new Spec("entity_uv", Math.max(sizes[2], 256L << 10), STORAGE | transfer),
                new Spec("entity_col", Math.max(sizes[3], 256L << 10), STORAGE | transfer),
                new Spec("entity_light", Math.max(sizes[4], 256L << 10), STORAGE | transfer));
            var old = quads;
            quads = next;
            if (old != null) owner.retire(old);
        }
        if (owner.lease(owner.recording(command), quads) < 0) throw new IllegalStateException("quad slot acquisition failed");
        return quads.value();
    }

    private Generation<RtMemory.Buf[]> create(int slots, Spec... specs) {
        RtMemory.Buf[] buffers = new RtMemory.Buf[specs.length];
        // Register even partial allocations before native calls, so cleanup failure retains ownership.
        var generation = owner.create(buffers, slots);
        try {
            for (int i = 0; i < specs.length; i++) {
                Spec spec = specs[i];
                buffers[i] = allocator.create(spec.name(), spec.bytes(), spec.usage());
            }
        } catch (RuntimeException | Error failure) {
            abandon(generation, failure);
            throw failure;
        }
        return generation;
    }

    private void abandon(Generation<RtMemory.Buf[]> generation, Throwable failure) {
        try { owner.retire(generation); }
        catch (RuntimeException | Error cleanup) { failure.addSuppressed(cleanup); }
    }

    /** Called only by the recording's host completion/discard action. */
    void releaseRecording(long command) { owner.releaseAfterProof(owner.recording(command)); }

    @Override public void close() {
        owner.close();
        ring = atlas = quads = null;
    }
}
