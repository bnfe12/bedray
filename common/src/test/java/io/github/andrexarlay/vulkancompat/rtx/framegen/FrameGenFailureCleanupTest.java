package io.github.andrexarlay.vulkancompat.rtx.framegen;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FrameGenFailureCleanupTest {
    @Test void failedDisableStillAttemptsTagClearingAndPreservesTheOriginalError() {
        var cause = new IllegalStateException("frame recording failed");
        var disable = new IllegalStateException("vendor disable failed");
        List<String> calls = new ArrayList<>();

        Throwable result = FrameGenFailureCleanup.run(cause,
            () -> { calls.add("disable"); throw disable; },
            () -> calls.add("clear tags"));

        assertSame(cause, result);
        assertEquals(List.of("disable", "clear tags"), calls);
        assertArrayEquals(new Throwable[]{disable}, result.getSuppressed());
    }

    @Test void allVendorFailuresRemainAttachedInAttemptOrder() {
        var cause = new IllegalStateException("frame failed");
        var disable = new IllegalStateException("disable failed");
        var clear = new AssertionError("clear failed");
        FrameGenFailureCleanup.run(cause, () -> { throw disable; }, () -> { throw clear; });
        assertArrayEquals(new Throwable[]{disable, clear}, cause.getSuppressed());
    }

    @Test void successfulCleanupDoesNotReplaceTheOriginalFailure() {
        var cause = new IllegalStateException("frame failed");
        List<String> calls = new ArrayList<>();
        assertSame(cause, FrameGenFailureCleanup.run(cause,
            () -> calls.add("disable"), () -> calls.add("clear tags")));
        assertEquals(List.of("disable", "clear tags"), calls);
        assertEquals(0, cause.getSuppressed().length);
    }

    @Test void aRepeatedNativeFailureCannotPreventTheRemainingCleanup() {
        var cause = new IllegalStateException("native operation failed");
        boolean[] cleared = {false};
        FrameGenFailureCleanup.run(cause, () -> { throw cause; }, () -> cleared[0] = true);
        assertTrue(cleared[0]);
        assertEquals(0, cause.getSuppressed().length);
    }
}
