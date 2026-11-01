package io.github.andrexarlay.vulkancompat.rtx.framegen;

import io.github.andrexarlay.vulkancompat.rt.lifetime.RecordingCompletion;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FrameGenProducerRecordingsTest {
    private static final class Submission implements FrameGenProducerRecordings.Submission {
        final List<Runnable> callbacks = new ArrayList<>();
        boolean failEnd, failExecute, failEnqueue;
        int executions;
        @Override public long commandBuffer() { return 42; }
        @Override public void end() { if (failEnd) throw new IllegalStateException("end"); }
        @Override public void execute() {
            executions++;
            if (failExecute) throw new IllegalStateException("execute");
        }
        @Override public void afterCompletion(Runnable action) {
            callbacks.add(action); // The enqueue outcome can be unknown after it accepted the callback.
            if (failEnqueue) throw new IllegalStateException("enqueue");
        }
    }

    private static final class Owner {
        int completed, discarded;
        boolean initialized;
        RecordingCompletion.Retirement retirement() {
            return new RecordingCompletion.Retirement(() -> completed++, () -> { discarded++; initialized = false; });
        }
    }

    private static FrameGenProducerRecordings recordings() {
        return new FrameGenProducerRecordings(failure -> { throw new AssertionError(failure); });
    }

    @Test void completionCallbackReleasesTheCapturedOwnerOnlyAfterTheHostProof() {
        var records = recordings(); var submission = new Submission(); var owner = new Owner();
        records.record(submission, owner.retirement(), () -> owner.initialized = true);
        assertEquals(1, records.pendingCount());
        assertEquals(0, owner.completed);
        submission.callbacks.getFirst().run();
        assertEquals(1, owner.completed);
        assertEquals(0, owner.discarded);
        assertEquals(0, records.pendingCount());
    }

    @Test void bodyFailureDiscardsBeforeExecutionAndRollsBackFirstUse() {
        var records = recordings(); var submission = new Submission(); var owner = new Owner();
        assertThrows(IllegalStateException.class, () -> records.record(submission, owner.retirement(), () -> {
            owner.initialized = true;
            throw new IllegalStateException("vendor prepare");
        }));
        assertFalse(owner.initialized);
        assertEquals(1, owner.discarded);
        assertEquals(0, submission.executions);
        assertEquals(0, records.pendingCount());
    }

    @Test void endFailureDiscardsTheNeverExecutedProducer() {
        var records = recordings(); var submission = new Submission(); var owner = new Owner();
        submission.failEnd = true;
        assertThrows(IllegalStateException.class, () -> records.record(submission, owner.retirement(), () -> {}));
        assertEquals(1, owner.discarded);
        assertEquals(0, submission.executions);
        assertEquals(0, records.pendingCount());
    }

    @Test void executeFailureRetainsAllOwnersUntilAnExplicitSubmissionFence() {
        var records = recordings(); var submission = new Submission(); var owner = new Owner();
        submission.failExecute = true;
        var failure = assertThrows(IllegalStateException.class, () -> records.record(submission, owner.retirement(), () -> {}));
        assertTrue(failure.getMessage().contains("stage=execute command=0x2a outcome=unknown pending=1"));
        assertEquals("execute", failure.getCause().getMessage());
        assertEquals(1, records.pendingCount());
        assertEquals(0, owner.discarded);
        boolean[] waited = {false};
        records.drain(() -> { assertEquals(0, owner.completed + owner.discarded); waited[0] = true; });
        assertTrue(waited[0]);
        assertEquals(1, owner.discarded);
        assertEquals(0, records.pendingCount());
    }

    @Test void failedFenceRetainsAnUncertainEnqueueForRetry() {
        var records = recordings(); var submission = new Submission(); var owner = new Owner();
        submission.failEnqueue = true;
        assertThrows(IllegalStateException.class, () -> records.record(submission, owner.retirement(), () -> {}));
        assertThrows(IllegalStateException.class, () -> records.drain(() -> { throw new IllegalStateException("timeout"); }));
        assertEquals(1, records.pendingCount());
        assertEquals(0, owner.completed + owner.discarded);
        records.drain(() -> {});
        assertEquals(1, owner.discarded);
        submission.callbacks.getFirst().run();
        assertEquals(1, owner.completed + owner.discarded);
    }

    @Test void staleCallbackCannotReleaseANewerRecordingAtTheSameAddress() {
        var records = recordings(); var old = new Submission(); var first = new Owner();
        records.record(old, first.retirement(), () -> {});
        records.drain(() -> {});
        var next = new Submission(); var second = new Owner();
        records.record(next, second.retirement(), () -> {});
        old.callbacks.getFirst().run();
        assertEquals(1, records.pendingCount());
        assertEquals(0, second.completed + second.discarded);
        next.callbacks.getFirst().run();
        assertEquals(1, second.completed);
    }

    @Test void drainCannotTurnAnOpenRecordingIntoACompletionProof() {
        var records = recordings(); var submission = new Submission(); var owner = new Owner();
        records.record(submission, owner.retirement(), () ->
            assertThrows(IllegalStateException.class, () -> records.drain(() -> fail("must not submit inside recording"))));
        assertEquals(1, records.pendingCount());
        submission.callbacks.getFirst().run();
        assertEquals(1, owner.completed);
    }
}
