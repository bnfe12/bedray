package io.github.andrexarlay.vulkancompat.rt.world;

import org.junit.jupiter.api.Test;
import java.nio.IntBuffer;
import java.util.HashMap;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class EntityCaptureBuffersTest {
    private static final class Allocator implements EntityCaptureBuffers.Allocator {
        final Map<Long, RtMemory.Buf> alive = new HashMap<>();
        long next = 1;
        int failAfter = -1;
        long failDestroy;
        public RtMemory.Buf create(String name, long bytes, int usage) {
            if (failAfter == 0) throw new IllegalStateException("injected allocation failure");
            if (failAfter > 0) failAfter--;
            var buffer = new RtMemory.Buf(next++, 0, bytes, 0, 0, true);
            alive.put(buffer.handle, buffer);
            return buffer;
        }
        public void destroy(RtMemory.Buf buffer) {
            if (buffer.handle == failDestroy) {
                failDestroy = 0;
                throw new IllegalStateException("injected destroy failure");
            }
            assertNotNull(alive.remove(buffer.handle), "a buffer must be destroyed once");
        }
    }
    private static final class Atlas implements EntityCaptureBuffers.AtlasSource {
        long version = 1;
        int copies;
        boolean failCopy;
        public long version() { return version; }
        public void copyTo(long texels, long rects) {
            if (failCopy) throw new IllegalStateException("copy failure");
            copies++;
        }
    }

    @Test void slotPressureCreatesANewGenerationWithoutOverwritingPendingFrames() {
        Allocator allocator = new Allocator();
        var buffers = new EntityCaptureBuffers(new Object(), allocator);
        buffers.begin(1);
        var first = buffers.geometry(1, 4, 6);
        buffers.begin(2);
        var second = buffers.geometry(2, 4, 6);
        buffers.begin(3);
        var third = buffers.geometry(3, 4, 6);
        assertSame(first.positions(), second.positions());
        assertEquals(0, first.slot());
        assertEquals(1, second.slot());
        assertEquals(2, third.slot());
        buffers.begin(4);
        var fourth = buffers.geometry(4, 4, 6);
        assertNotSame(first.positions(), fourth.positions());
        assertEquals(8, allocator.alive.size());
        buffers.releaseRecording(2);
        buffers.releaseRecording(1);
        assertTrue(allocator.alive.containsKey(first.positions().handle));
        buffers.releaseRecording(3);
        assertFalse(allocator.alive.containsKey(first.positions().handle));
        assertThrows(IllegalStateException.class, buffers::close);
        buffers.releaseRecording(4);
        buffers.close();
        assertTrue(allocator.alive.isEmpty());
    }

    @Test void atlasVersionsAreImmutableAndSharedUntilEveryReaderReleases() {
        Allocator allocator = new Allocator();
        var buffers = new EntityCaptureBuffers(new Object(), allocator);
        Atlas source = new Atlas();
        buffers.begin(1);
        var first = buffers.atlas(1, source);
        buffers.begin(2);
        assertSame(first.texels(), buffers.atlas(2, source).texels());
        assertEquals(1, source.copies);
        source.version++;
        buffers.begin(3);
        assertNotSame(first.texels(), buffers.atlas(3, source).texels());
        buffers.releaseRecording(2);
        assertTrue(allocator.alive.containsKey(first.texels().handle));
        buffers.releaseRecording(1);
        assertFalse(allocator.alive.containsKey(first.texels().handle));
        buffers.releaseRecording(3);
        buffers.close();
        assertTrue(allocator.alive.isEmpty());
    }

    @Test void allocationOrAtlasCopyFailureCannotOrphanBuffersOrReplaceValidData() {
        Allocator allocator = new Allocator();
        var buffers = new EntityCaptureBuffers(new Object(), allocator);
        buffers.begin(1);
        allocator.failAfter = 2;
        assertThrows(IllegalStateException.class, () -> buffers.geometry(1, 4, 6));
        assertTrue(allocator.alive.isEmpty());
        buffers.releaseRecording(1);
        allocator.failAfter = -1;
        Atlas source = new Atlas();
        buffers.begin(2);
        var first = buffers.atlas(2, source);
        source.version++;
        source.failCopy = true;
        buffers.begin(3);
        assertThrows(IllegalStateException.class, () -> buffers.atlas(3, source));
        assertEquals(2, allocator.alive.size());
        assertTrue(allocator.alive.containsKey(first.texels().handle));
        buffers.releaseRecording(3);
        source.version--;
        source.failCopy = false;
        buffers.begin(4);
        assertSame(first.texels(), buffers.atlas(4, source).texels());
        buffers.releaseRecording(2);
        buffers.releaseRecording(4);
        buffers.close();
        assertTrue(allocator.alive.isEmpty());
    }

    @Test void partialDestructorFailureCanRetryWithoutDoubleFree() {
        Allocator allocator = new Allocator();
        var buffers = new EntityCaptureBuffers(new Object(), allocator);
        for (long command = 1; command <= 4; command++) {
            buffers.begin(command);
            buffers.geometry(command, 4, 6);
        }
        buffers.releaseRecording(1);
        buffers.releaseRecording(2);
        allocator.failDestroy = 2;
        assertThrows(IllegalStateException.class, () -> buffers.releaseRecording(3));
        assertFalse(allocator.alive.containsKey(1L));
        assertTrue(allocator.alive.containsKey(2L));
        buffers.releaseRecording(3);
        buffers.releaseRecording(4);
        buffers.close();
        assertTrue(allocator.alive.isEmpty());
    }

    @Test void quadStorageCannotBeOverwrittenBeforeProof() {
        Allocator allocator = new Allocator();
        Object memory = new Object();
        var buffers = new EntityCaptureBuffers(memory, allocator);
        assertTrue(buffers.belongsTo(memory));
        assertFalse(buffers.belongsTo(new Object()));
        buffers.begin(1);
        var first = buffers.quads(1, 1);
        buffers.begin(2);
        var second = buffers.quads(2, 1);
        assertNotSame(first, second);
        assertEquals(10, allocator.alive.size());
        buffers.releaseRecording(1);
        assertEquals(5, allocator.alive.size());
        buffers.releaseRecording(2);
        buffers.begin(3);
        assertSame(second, buffers.quads(3, 1));
        assertEquals(5, allocator.alive.size());
        buffers.releaseRecording(3);
        buffers.close();
        assertTrue(allocator.alive.isEmpty());
    }

    @Test void quadPackingWritesOnlyTheRequestedMappedRange() {
        IntBuffer target = IntBuffer.allocate(16);
        for (int i = 0; i < target.capacity(); i++) target.put(i, 99);
        target.position(2).limit(14);
        EntityQuadIndices.write(target, 2);
        assertEquals(14, target.position());
        target.clear();
        assertEquals(99, target.get(1));
        assertEquals(99, target.get(14));
        int[] indices = new int[12];
        target.position(2).get(indices);
        assertArrayEquals(new int[]{0,1,2,2,3,0,4,5,6,6,7,4}, indices);
        IntBuffer shortRange = IntBuffer.allocate(6);
        assertThrows(IllegalArgumentException.class, () -> EntityQuadIndices.write(shortRange, 2));
        assertEquals(0, shortRange.position());
    }
}
