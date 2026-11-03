package io.github.andrexarlay.vulkancompat.compute;

import io.github.andrexarlay.vulkancompat.rt.lifetime.RecordingCompletion;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ComputeRecordingTest {
    private static final class Fixture {
        final List<String> events = new ArrayList<>();
        final List<Runnable> callbacks = new ArrayList<>();
        final RecordingCompletion completion = new RecordingCompletion(error -> { throw new AssertionError(error); });
        final ComputeRecording recording = new ComputeRecording(completion, 42,
            new RecordingCompletion.Retirement(() -> events.add("completed"), () -> events.add("discarded")));
        void finish() { events.add("end"); }
        void execute() { events.add("execute"); }
    }

    @Test void successfulSubmissionWaitsForItsOwnHostCallback() {
        var f = new Fixture();
        assertTrue(f.recording.record(() -> { f.events.add("record"); return true; },
            f::finish, f::execute, f.callbacks::add));
        assertEquals(List.of("record", "end", "execute"), f.events);
        assertEquals(ComputeRecording.Submission.SUBMITTED, f.recording.submission());
        assertEquals(1, f.completion.pendingCount());
        f.callbacks.getFirst().run();
        assertEquals(List.of("record", "end", "execute", "completed"), f.events);
        assertEquals(0, f.completion.pendingCount());
    }

    @Test void falseAfterUploadsAbandonsTheWholeCommandBuffer() {
        var f = new Fixture();
        assertFalse(f.recording.record(() -> { f.events.add("upload"); return false; },
            f::finish, f::execute, f.callbacks::add));
        assertEquals(List.of("upload", "discarded"), f.events);
        assertEquals(0, f.completion.pendingCount());
        assertTrue(f.callbacks.isEmpty());
    }

    @Test void rendererFailureNeverEndsOrExecutesItsPartialCommands() {
        var f = new Fixture(); var failure = new IllegalStateException("dispatch failed after upload");
        assertSame(failure, assertThrows(IllegalStateException.class, () -> f.recording.record(
            () -> { f.events.add("upload"); throw failure; }, f::finish, f::execute, f.callbacks::add)));
        assertEquals(List.of("upload", "discarded"), f.events);
        assertEquals(0, f.completion.pendingCount());
    }

    @Test void endFailureAlsoHasProofThatExecuteWasNeverCalled() {
        var f = new Fixture();
        assertThrows(IllegalStateException.class, () -> f.recording.record(() -> true,
            () -> { throw new IllegalStateException("end"); }, f::execute, f.callbacks::add));
        assertEquals(List.of("discarded"), f.events);
        assertEquals(ComputeRecording.Submission.NEVER_EXECUTED, f.recording.submission());
    }

    @Test void executeFailurePinsNativeResourcesUntilHostDrain() {
        var f = new Fixture();
        assertThrows(IllegalStateException.class, () -> f.recording.record(() -> true, f::finish,
            () -> { throw new IllegalStateException("execute outcome unknown"); }, f.callbacks::add));
        assertEquals(List.of("end"), f.events);
        assertEquals(ComputeRecording.Submission.UNKNOWN, f.recording.submission());
        assertEquals(1, f.completion.pendingCount());
        f.completion.afterHostDrain();
        assertEquals(List.of("end", "discarded"), f.events);
        assertEquals(0, f.completion.pendingCount());
    }

    @Test void callbackEnqueueFailureAlsoPinsResourcesUntilHostDrain() {
        var f = new Fixture();
        assertThrows(IllegalStateException.class, () -> f.recording.record(() -> true, f::finish, f::execute,
            callback -> { throw new IllegalStateException("enqueue outcome unknown"); }));
        assertEquals(List.of("end", "execute"), f.events);
        assertEquals(1, f.completion.pendingCount());
        f.completion.afterHostDrain();
        assertEquals(List.of("end", "execute", "discarded"), f.events);
    }
}
