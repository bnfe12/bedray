package io.github.andrexarlay.vulkancompat.rt.lifetime;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class RecordingReadbackIntegrationTest {
    @Test void anotherOwnersFailedRetirementDoesNotPublishAReusedSlotOnRetry() {
        var slots = new RecordingReadbackSlots<String>(1);
        List<Throwable> failures = new ArrayList<>();
        var completion = new RecordingCompletion(failures::add);
        var lease = slots.reserve(21);
        boolean[] failRelease = {true};
        var ticket = completion.beginWithDiscard(21,
            new RecordingCompletion.Retirement(() -> slots.completed(lease), () -> slots.discarded(lease)),
            new RecordingCompletion.Retirement(() -> {
                if (failRelease[0]) throw new IllegalStateException("another owner still retained");
            }, () -> fail("submitted recording must keep its completion disposition")));
        slots.written(lease, "completed producer");
        List<Runnable> callbacks = new ArrayList<>();
        completion.submissionAttempted(ticket);
        completion.submitted(ticket, callbacks::add);
        callbacks.getFirst().run();
        assertEquals(1, failures.size());
        assertEquals(1, completion.pendingCount());
        assertEquals("completed producer", slots.ready().getFirst().metadata());
        slots.consume(lease);
        var next = slots.reserve(22);
        slots.written(next, "pending producer");
        failRelease[0] = false;
        completion.afterHostDrain();
        assertEquals(0, completion.pendingCount());
        assertTrue(slots.ready().isEmpty());
        assertFalse(slots.hasFreeSlot());
    }

    @Test void delayedCompletionProtectsTheWholeSlotAndLateCallbackCannotPublishItsReplacement() {
        var slots = new RecordingReadbackSlots<String>(1);
        var completion = new RecordingCompletion(failure -> fail(failure));
        var lease = slots.reserve(17);
        var ticket = completion.beginWithDiscard(17, new RecordingCompletion.Retirement(
            () -> slots.completed(lease), () -> slots.discarded(lease)));
        slots.written(lease, "producer A");
        List<Runnable> callbacks = new ArrayList<>();
        completion.submissionAttempted(ticket);
        completion.submitted(ticket, callbacks::add);
        for (int frame = 0; frame < 100; frame++) {
            assertFalse(slots.hasFreeSlot());
            assertTrue(slots.ready().isEmpty());
            assertNull(slots.reserve(18));
        }
        callbacks.getFirst().run();
        assertEquals("producer A", slots.ready().getFirst().metadata());
        assertFalse(slots.hasFreeSlot(), "completed bytes remain retained until host read finishes");
        assertTrue(slots.consume(lease));
        var next = slots.reserve(17);
        slots.written(next, "producer B");
        callbacks.getFirst().run();
        assertTrue(slots.ready().isEmpty(), "late producer A callback cannot publish producer B");
        assertFalse(slots.hasFreeSlot());
    }

    @Test void unknownExecutionDrainsWithoutPublishingAndUnwrittenFramesPublishNothing() {
        var slots = new RecordingReadbackSlots<String>(1);
        var completion = new RecordingCompletion(failure -> fail(failure));
        var lease = slots.reserve(19);
        var ticket = completion.beginWithDiscard(19, new RecordingCompletion.Retirement(
            () -> slots.completed(lease), () -> slots.discarded(lease)));
        slots.written(lease, "unproven results");
        completion.submissionAttempted(ticket);
        assertFalse(slots.hasFreeSlot());
        completion.afterHostDrain();
        assertTrue(slots.ready().isEmpty());
        assertTrue(slots.hasFreeSlot());
        var empty = slots.reserve(20);
        var emptyTicket = completion.beginWithDiscard(20, new RecordingCompletion.Retirement(
            () -> slots.completed(empty), () -> slots.discarded(empty)));
        completion.discard(emptyTicket);
        assertTrue(slots.ready().isEmpty());
        assertTrue(slots.hasFreeSlot());
    }
}
