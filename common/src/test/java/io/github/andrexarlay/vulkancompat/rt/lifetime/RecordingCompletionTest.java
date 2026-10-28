package io.github.andrexarlay.vulkancompat.rt.lifetime;

import org.junit.jupiter.api.Test;

class RecordingCompletionTest {
    @Test
    void hostProofRetiresOnlyTheMatchingRecordingGeneration() {
        RecordingCompletionChecks.main(new String[0]);
    }
}
