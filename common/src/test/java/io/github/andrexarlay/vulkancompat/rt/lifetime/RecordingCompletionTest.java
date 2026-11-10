package io.github.andrexarlay.vulkancompat.rt.lifetime;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecordingCompletionTest {
    @Test
    void failedRetirementReportsTheRecordingProofAndOwnerWithoutLosingTheCause() {
        List<Throwable> failures = new ArrayList<>();
        var completion = new RecordingCompletion(failures::add);
        var original = new IllegalStateException("native image collect failed");
        var ticket = completion.begin(42, () -> { throw original; });
        completion.submissionAttempted(ticket);
        completion.submitted(ticket, Runnable::run);
        assertEquals(1, completion.pendingCount());
        Throwable reported = failures.getFirst();
        assertTrue(reported.getMessage().contains("command=0x2a state=UNKNOWN proof=completed resources=retained"));
        assertEquals("owner_index=0", reported.getSuppressed()[0].getMessage());
        assertEquals(original, reported.getSuppressed()[0].getCause());
    }

    @Test
    void deviceLossDropsOutputAndLateCompletionCannotPublishIt() {
        var afterCompletion = new ArrayList<Runnable>();
        var events = new ArrayList<String>();
        var completion = new RecordingCompletion(failure -> { throw new AssertionError(failure); });
        var action = new RecordingCompletion.Retirement(() -> events.add("complete"), () -> events.add("discard"));
        var submitted = completion.beginWithDiscard(42, action);
        completion.submissionAttempted(submitted);
        completion.submitted(submitted, afterCompletion::add);
        completion.beginWithDiscard(43, action);
        completion.afterEncoderClose(true);
        afterCompletion.getFirst().run();
        assertEquals(List.of("discard", "discard"), events);
        assertEquals(0, completion.pendingCount());
    }

    @Test
    void hostProofRetiresOnlyTheMatchingRecordingGeneration() {
        RecordingCompletionChecks.main(new String[0]);
    }

    /**
     * P1-4: retirement used to be requested at the start of the next frame, before anything proved that the GPU had
     * finished with the recording. A submission is not a completion proof: the release must wait for the host's
     * destruction queue, which drains after the submission fence.
     */
    @Test
    void retirementWaitsForHostCompletionProof() {
        List<Runnable> afterCompletion = new ArrayList<>();
        int[] releases = {0};
        RecordingCompletion completion = new RecordingCompletion(failure -> {
            throw new AssertionError(failure);
        });
        var ticket = completion.begin(42, () -> releases[0]++);
        completion.submissionAttempted(ticket);
        completion.submitted(ticket, afterCompletion::add);
        assertEquals(0, releases[0], "submission alone must not release a recording");
        afterCompletion.getFirst().run();
        assertEquals(1, releases[0]);
    }
}
