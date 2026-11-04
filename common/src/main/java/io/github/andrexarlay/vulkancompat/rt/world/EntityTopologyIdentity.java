package io.github.andrexarlay.vulkancompat.rt.world;

import io.github.andrexarlay.vulkancompat.rt.scene.BedraySceneLayouts;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/** Exact topology identity; vertex positions and host frame/slot numbers are deliberately independent. */
final class EntityTopologyIdentity {
    private record Shape(long vertices, long indices, int stride, int indexType) {}
    private Shape shape;
    private byte[] indices;
    private long revision;

    long update(long vertexCount, long indexCount, int vertexStride, int indexType, MemorySegment stream) {
        int indexBytes = switch (indexType) {
            case BedraySceneLayouts.INDEX_UINT16 -> 2;
            case BedraySceneLayouts.INDEX_UINT32 -> 4;
            default -> throw new IllegalArgumentException("entity topology requires 16/32-bit indices");
        };
        long byteCount = Math.multiplyExact(indexCount, indexBytes);
        if (vertexCount <= 0 || indexCount <= 0 || indexCount % 3 != 0 || vertexStride < 12
                || vertexStride % 4 != 0 || byteCount > Integer.MAX_VALUE || stream == null || stream.byteSize() < byteCount)
            throw new IllegalArgumentException("invalid entity topology counts/stride/index range");
        Shape next = new Shape(vertexCount, indexCount, vertexStride, indexType);
        MemorySegment current = stream.asSlice(0, byteCount);
        if (next.equals(shape) && current.mismatch(MemorySegment.ofArray(indices)) == -1) return revision;
        byte[] snapshot = current.toArray(ValueLayout.JAVA_BYTE);
        long nextRevision = Math.incrementExact(revision);
        shape = next;
        indices = snapshot;
        revision = nextRevision;
        return revision;
    }

    long revision() { return revision; }
    void clear() { shape = null; indices = null; }
}
