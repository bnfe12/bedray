package io.github.andrexarlay.vulkancompat.rt.lifetime;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RecordingCompletionTest {
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
