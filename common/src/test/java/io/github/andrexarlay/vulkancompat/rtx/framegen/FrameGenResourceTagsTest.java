package io.github.andrexarlay.vulkancompat.rtx.framegen;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FrameGenResourceTagsTest {
    @Test void partialRegistrationStillRequiresClearingEveryVendorReference() {
        var tags = new FrameGenResourceTags();
        List<String> calls = new ArrayList<>();

        assertThrows(IllegalStateException.class, () -> tags.register(() -> {
            calls.add("depth assigned");
            throw new IllegalStateException("motion tag failed");
        }));

        assertTrue(tags.isActive());
        tags.clear(() -> calls.addAll(List.of("depth cleared", "motion cleared", "hudless cleared")));
        assertEquals(List.of("depth assigned", "depth cleared", "motion cleared", "hudless cleared"), calls);
        assertFalse(tags.isActive());
    }

    @Test void anUnknownFirstAssignmentOutcomeAlsoKeepsTheVendorLease() {
        var tags = new FrameGenResourceTags();
        assertThrows(IllegalStateException.class, () -> tags.register(() -> {
            throw new IllegalStateException("native call outcome unknown");
        }));
        assertTrue(tags.isActive());
    }

    @Test void failedClearingRetainsOwnershipUntilARetryClearsEveryTag() {
        var tags = new FrameGenResourceTags();
        tags.register(() -> {});
        assertThrows(IllegalStateException.class, () -> tags.clear(() -> {
            throw new IllegalStateException("clear failed");
        }));
        assertTrue(tags.isActive());

        int[] releases = {0};
        tags.clear(() -> releases[0]++);
        tags.clear(() -> fail("a completed clear must be idempotent"));
        assertEquals(1, releases[0]);
        assertFalse(tags.isActive());
    }

    @Test void failedReplacementCannotEraseAnEarlierVendorLease() {
        var tags = new FrameGenResourceTags();
        tags.register(() -> {});
        assertThrows(IllegalStateException.class, () -> tags.register(() -> {
            throw new IllegalStateException("replacement failed");
        }));
        assertTrue(tags.isActive());
    }
}
