package io.github.andrexarlay.vulkancompat.rtx.framegen;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FrameGenGuideReadsTest {
    @Test void guideReplacementWaitsForEveryProducerRead() {
        var reads = new FrameGenGuideReads(); reads.replace(1);
        var a = reads.source().read(); var b = reads.source().read();
        a.retain(10); b.retain(20);
        assertThrows(IllegalStateException.class, () -> reads.replace(2));
        a.retirement().completed().run();
        assertThrows(IllegalStateException.class, reads::invalidate);
        b.retirement().discarded().run();
        assertDoesNotThrow(() -> reads.replace(2));
    }

    @Test void anOldSourceCannotPinAReplacementGeneration() {
        var reads = new FrameGenGuideReads(); reads.replace(1);
        var old = reads.source().read();
        reads.replace(2);
        assertThrows(IllegalStateException.class, () -> old.retain(10));
        old.retirement().discarded().run(); // Empty bookkeeping acquired before rejected generation pin.
        assertDoesNotThrow(reads::requireUnreferenced);
        var current = reads.source().read(); current.retain(10);
        assertThrows(IllegalStateException.class, reads::invalidate);
        current.retirement().completed().run();
        reads.invalidate();
    }

    @Test void duplicateLateProofCannotReleaseANewerReadAtTheSameCommandAddress() {
        var reads = new FrameGenGuideReads(); reads.replace(1);
        var old = reads.source().read(); old.retain(10); old.retirement().completed().run();
        var current = reads.source().read(); current.retain(10);
        old.retirement().completed().run(); old.retirement().discarded().run();
        assertThrows(IllegalStateException.class, reads::invalidate);
        current.retirement().completed().run(); reads.invalidate();
    }

    @Test void discardBeforeAcquisitionCannotLeaveAPinOrPermitLaterSubmission() {
        var reads = new FrameGenGuideReads(); reads.replace(1);
        var read = reads.source().read(); read.retirement().discarded().run();
        assertThrows(IllegalStateException.class, () -> read.retain(10));
        assertDoesNotThrow(reads::invalidate);
    }
}
