package io.github.andrexarlay.vulkancompat.rt.lifetime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RecordingReadbackSlotsTest {
    private record Metadata(boolean accumulated, boolean transparency) {}

    @Test void writtenStorageStaysExclusiveUntilCompletionAndConsumption() {
        var slots = new RecordingReadbackSlots<Metadata>(1);
        assertTrue(slots.hasFreeSlot());
        var lease = slots.reserve(11);
        assertEquals(0, lease.slot());
        assertEquals(11, lease.commandBuffer());
        slots.written(lease, new Metadata(true, false));
        assertFalse(slots.hasFreeSlot());
        assertTrue(slots.ready().isEmpty());
        assertThrows(IllegalStateException.class, lease::metadata);
        assertNull(slots.reserve(12), "slot pressure never recycles pending GPU writes");
        assertFalse(slots.consume(lease), "the host cannot release storage before completion proof");
        assertTrue(slots.completed(lease));
        assertFalse(slots.hasFreeSlot(), "a completed unread slot is still retained");
        assertEquals(new Metadata(true, false), lease.metadata());
        assertEquals(lease, slots.ready().getFirst());
        assertNull(slots.reserve(12), "completion permits reading, not overwriting unread data");
        assertTrue(slots.consume(lease));
        assertTrue(slots.hasFreeSlot());
        assertThrows(IllegalStateException.class, lease::metadata);
        assertNotNull(slots.reserve(12));
    }

    @Test void discardAndUnwrittenCompletionPublishNothing() {
        var slots = new RecordingReadbackSlots<String>(1);
        var discarded = slots.reserve(11);
        slots.written(discarded, "never executed");
        assertTrue(slots.discarded(discarded));
        assertTrue(slots.ready().isEmpty());
        assertFalse(slots.completed(discarded));
        var unwritten = slots.reserve(12);
        assertTrue(slots.completed(unwritten));
        assertTrue(slots.ready().isEmpty());
        assertNotNull(slots.reserve(13));
    }

    @Test void staleOrForeignCallbacksCannotReleaseAReusedSlot() {
        var slots = new RecordingReadbackSlots<String>(1);
        var previous = slots.reserve(11);
        slots.written(previous, "old");
        assertTrue(slots.completed(previous));
        assertTrue(slots.consume(previous));
        var current = slots.reserve(11); // Same command address, different recording identity.
        slots.written(current, "new");
        var foreignOwner = new RecordingReadbackSlots<String>(1);
        var foreign = foreignOwner.reserve(11);
        assertFalse(slots.completed(previous));
        assertFalse(slots.discarded(previous));
        assertFalse(slots.consume(previous));
        assertFalse(slots.completed(foreign));
        assertFalse(slots.discarded(foreign));
        assertFalse(slots.consume(foreign));
        assertNull(slots.reserve(12));
        assertTrue(slots.completed(current));
        assertEquals("new", current.metadata());
        assertFalse(slots.discarded(current), "completion cannot be changed into discard later");
    }

    @Test void readySnapshotsStayImmutableUntilConsumed() {
        var slots = new RecordingReadbackSlots<String>(2);
        var first = slots.reserve(11);
        var second = slots.reserve(12);
        slots.written(first, "first");
        slots.written(second, "second");
        slots.completed(first);
        var snapshot = slots.ready();
        assertThrows(UnsupportedOperationException.class, snapshot::clear);
        slots.completed(second);
        assertEquals(1, snapshot.size(), "a snapshot is not changed by another completion");
        assertEquals(2, slots.ready().size());
        assertEquals("first", slots.ready().getFirst().metadata(), "an unconsumed failed read remains retryable");
        assertTrue(slots.consume(first));
        assertFalse(slots.consume(first), "duplicate cleanup is harmless");
        assertEquals("second", slots.ready().getFirst().metadata());
    }

    @Test void invalidRecordingTransitionsFailWithoutMutatingLeases() {
        assertThrows(IllegalArgumentException.class, () -> new RecordingReadbackSlots<>(0));
        var slots = new RecordingReadbackSlots<String>(2);
        assertThrows(IllegalArgumentException.class, () -> slots.reserve(0));
        var first = slots.reserve(11);
        assertThrows(IllegalStateException.class, () -> slots.reserve(11));
        assertThrows(NullPointerException.class, () -> slots.written(first, null));
        slots.written(first, "first");
        assertThrows(IllegalStateException.class, () -> slots.written(first, "replacement"));
        slots.completed(first);
        assertThrows(IllegalStateException.class, () -> slots.written(first, "late"));
        var nextRecording = slots.reserve(11);
        assertNotNull(nextRecording, "completed unread data does not prevent reusing a command address in another slot");
        assertEquals(1, nextRecording.slot());
        slots.discarded(nextRecording);
        assertEquals("first", first.metadata());
    }
}
