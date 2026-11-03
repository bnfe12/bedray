package io.github.andrexarlay.vulkancompat.rt.world;

import io.github.andrexarlay.vulkancompat.rt.lifetime.RecordingCompletion;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class EmitterCaptureBuffersTest {
    private static final class Storage implements EmitterCaptureBuffers.Storage {
        final Map<Long, byte[]> alive = new HashMap<>();
        long next = 1;
        boolean failCreate, failWrite, failDestroy;
        public RtMemory.Buf create(long bytes) {
            if (failCreate) throw new IllegalStateException("allocation failed");
            var buffer = new RtMemory.Buf(next++, 0, bytes, 0, 0, true);
            alive.put(buffer.handle, new byte[Math.toIntExact(bytes)]);
            return buffer;
        }
        public void write(RtMemory.Buf buffer, ByteBuffer data) {
            if (failWrite) throw new IllegalStateException("copy failed");
            data.get(alive.get(buffer.handle));
        }
        public void destroy(RtMemory.Buf buffer) {
            if (failDestroy) {
                failDestroy = false;
                throw new IllegalStateException("destruction failed");
            }
            assertNotNull(alive.remove(buffer.handle), "a buffer must be destroyed once");
        }
    }

    private static float[][] records(float first) {
        float[][] data = {new float[RtEmitters.FLOATS + 1]};
        for (int i = 0; i < data[0].length; i++) data[0][i] = first + i;
        return data;
    }

    @Test void sharedAndRepeatedPublicationsRemainImmutableUntilEveryReaderReleases() {
        Storage storage = new Storage();
        var buffers = new EmitterCaptureBuffers(storage);
        float[][] data = records(1);
        buffers.publish(data, 1);
        var first = buffers.current();
        assertEquals(EmitterTable.bytes(1), first.size);
        buffers.retain(1);
        buffers.retain(2);
        for (int command = 3; command <= 10; command++) {
            data[0][0] = command;
            buffers.publish(data, 1);
            buffers.retain(command);
        }
        assertEquals(9, storage.alive.size());
        assertEquals(1f, ByteBuffer.wrap(storage.alive.get(first.handle)).order(ByteOrder.nativeOrder()).getFloat(EmitterTable.HEADER_BYTES));
        buffers.releaseRecording(1);
        assertTrue(storage.alive.containsKey(first.handle));
        buffers.releaseRecording(2);
        assertFalse(storage.alive.containsKey(first.handle));
        for (int command = 3; command <= 10; command++) buffers.releaseRecording(command);
        assertEquals(1, storage.alive.size());
        buffers.close();
        assertTrue(storage.alive.isEmpty());
    }

    @Test void failedAllocationOrCopyPreservesThePublishedListAndRetryableOwnership() {
        Storage storage = new Storage();
        var buffers = new EmitterCaptureBuffers(storage);
        buffers.publish(records(1), 1);
        var first = buffers.current();
        storage.failCreate = true;
        assertThrows(IllegalStateException.class, () -> buffers.publish(records(2), 1));
        assertSame(first, buffers.current());
        storage.failCreate = false;
        storage.failWrite = storage.failDestroy = true;
        var failure = assertThrows(IllegalStateException.class, () -> buffers.publish(records(2), 1));
        assertEquals(1, failure.getSuppressed().length);
        assertSame(first, buffers.current());
        assertEquals(2, storage.alive.size());
        storage.failWrite = false;
        buffers.retain(7);
        buffers.releaseRecording(7); // Retry collection of the abandoned partial publication.
        assertEquals(1, storage.alive.size());
        buffers.close();
        assertTrue(storage.alive.isEmpty());
    }

    @Test void emptyPublicationsAllocateNothingAndCloseStillRequiresProof() {
        Storage storage = new Storage();
        var buffers = new EmitterCaptureBuffers(storage);
        buffers.publish(records(1), 1);
        buffers.retain(1);
        buffers.publish(new float[0][], 0);
        assertNull(buffers.current());
        assertEquals(1, storage.alive.size());
        buffers.retain(2);
        assertThrows(IllegalStateException.class, buffers::close);
        buffers.releaseRecording(1);
        assertTrue(storage.alive.isEmpty());
        buffers.releaseRecording(2);
        buffers.close();
        buffers.close();
        assertThrows(IllegalStateException.class, () -> buffers.publish(records(1), 1));
    }

    @Test void failedDestructionRetainsTheProvenTicketForRetry() {
        Storage storage = new Storage();
        var buffers = new EmitterCaptureBuffers(storage);
        buffers.publish(records(1), 1);
        buffers.retain(1);
        buffers.publish(records(2), 1);
        storage.failDestroy = true;
        assertThrows(IllegalStateException.class, () -> buffers.releaseRecording(1));
        assertThrows(IllegalStateException.class, () -> buffers.retain(1));
        buffers.releaseRecording(1);
        assertEquals(1, storage.alive.size());
        buffers.close();
        assertTrue(storage.alive.isEmpty());
    }

    @Test void invalidRecordsAreRejectedBeforeAllocation() {
        Storage storage = new Storage();
        var buffers = new EmitterCaptureBuffers(storage);
        assertThrows(IllegalArgumentException.class, () -> buffers.publish(records(1), -1));
        assertThrows(IllegalArgumentException.class, () -> buffers.publish(records(1), RtEmitters.MAX_EMITTERS + 1));
        assertThrows(IllegalArgumentException.class, () -> buffers.publish(new float[][]{new float[3]}, 1));
        assertTrue(storage.alive.isEmpty());
        buffers.close();
    }

    @Test void lateHostCallbacksCannotReleaseAReusedCommandHandle() {
        Storage storage = new Storage();
        var buffers = new EmitterCaptureBuffers(storage);
        var completion = new RecordingCompletion(failure -> fail(failure));
        buffers.publish(records(1), 1);
        var first = completion.begin(7, () -> buffers.releaseRecording(7));
        buffers.retain(7);
        completion.submissionAttempted(first);
        Runnable[] callback = {null};
        completion.submitted(first, action -> callback[0] = action);
        callback[0].run();
        buffers.publish(records(2), 1);
        var secondBuffer = buffers.current();
        var second = completion.begin(7, () -> buffers.releaseRecording(7));
        buffers.retain(7);
        buffers.publish(records(3), 1);
        callback[0].run();
        assertTrue(storage.alive.containsKey(secondBuffer.handle));
        assertEquals(1, completion.pendingCount());
        assertThrows(IllegalStateException.class, buffers::close);
        completion.discard(second);
        assertFalse(storage.alive.containsKey(secondBuffer.handle));
        buffers.close();
        assertTrue(storage.alive.isEmpty());
    }

    @Test void failedPublicationDoesNotCommitIdentityChanges() {
        Storage storage = new Storage();
        var buffers = new EmitterCaptureBuffers(storage);
        buffers.publish(records(1), 1);
        long revision = buffers.publication().revision();
        int id = buffers.publication().data().getInt(EmitterTable.HEADER_BYTES + RtEmitters.BYTES);
        storage.failWrite = true;
        assertThrows(IllegalStateException.class, () -> buffers.publish(records(2), 1));
        assertEquals(revision, buffers.publication().revision());
        storage.failWrite = false;
        buffers.publish(records(1), 1);
        assertEquals(id, buffers.publication().data().getInt(EmitterTable.HEADER_BYTES + RtEmitters.BYTES));
        assertEquals(1, buffers.publication().retained());
        assertEquals(revision, buffers.publication().revision());
        buffers.close();
    }
}
