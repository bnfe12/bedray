package io.github.andrexarlay.vulkancompat.rtx.upscaler.dlss;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class DlssFeatureOwnerTest {
    @Test void partialCreationIsOwnedButCannotBeEvaluated() {
        var owner = new DlssFeatureOwner();
        owner.adopt(71, false);
        assertEquals(71, owner.handle());
        assertFalse(owner.usable());
        assertTrue(owner.retiring());
        var events = new ArrayList<String>();
        owner.retire(() -> events.add("discard proved"), h -> events.add("release " + h));
        assertEquals(List.of("discard proved", "release 71"), events);
        assertTrue(owner.empty());
    }

    @Test void failedCompletionCannotReleaseOrResumeTheFeature() {
        var owner = new DlssFeatureOwner();
        owner.adopt(72, true);
        assertThrows(IllegalStateException.class, () -> owner.retire(
            () -> { throw new IllegalStateException("fence failed"); }, h -> fail("no proof")));
        assertEquals(72, owner.handle());
        assertFalse(owner.usable());
        assertThrows(IllegalStateException.class, () -> owner.adopt(73, true));
    }

    @Test void sdkFailureRetainsAReleaseOnlyOwnerUntilRetry() {
        var owner = new DlssFeatureOwner();
        owner.adopt(74, true);
        assertThrows(IllegalStateException.class, () -> owner.retire(() -> {},
            h -> { throw new IllegalStateException("parameters still owned"); }));
        assertEquals(74, owner.handle());
        assertFalse(owner.usable());
        owner.retire(() -> {}, h -> assertEquals(74, h));
        assertTrue(owner.empty());
        owner.adopt(75, true);
        assertTrue(owner.usable());
        assertEquals(75, owner.handle());
    }

    @Test void repeatedSuspensionDoesNotFenceOrFreeAgain() {
        var owner = new DlssFeatureOwner();
        owner.adopt(76, true);
        owner.retire(() -> {}, h -> assertEquals(76, h));
        owner.retire(() -> fail("empty owner"), h -> fail("already released"));
        assertFalse(owner.retiring());
    }

    @Test void transferringForReconfigurationHasExactlyOneReleaseOnlyOwner() {
        var current = new DlssFeatureOwner();
        var retired = new DlssFeatureOwner();
        current.adopt(77, true);
        current.transferTo(retired);
        assertTrue(current.empty());
        assertEquals(77, retired.handle());
        assertFalse(retired.usable());
        current.adopt(78, true);
        assertThrows(IllegalStateException.class, () -> current.transferTo(retired));
        assertEquals(78, current.handle());
        assertEquals(77, retired.handle());
    }

    @Test void failedCreationWithoutAnOwnerStillRequiresARecordingBoundary() {
        var owner = new DlssFeatureOwner();
        owner.adopt(0, false);
        assertTrue(owner.empty());
        assertTrue(owner.retiring());
        owner.retire(() -> fail("no resource to fence"), h -> fail("no resource to free"));
        assertFalse(owner.retiring());
    }

    @Test void sdkSuccessMustActuallyReturnAnOwner() {
        var owner = new DlssFeatureOwner();
        assertThrows(IllegalStateException.class, () -> owner.adopt(0, true));
        assertTrue(owner.empty());
    }

    @Test void srSuspensionCannotReleaseIndependentRrAndFgOwners() {
        var sr = new DlssFeatureOwner();
        var rr = new DlssFeatureOwner();
        var fg = new DlssFeatureOwner();
        sr.adopt(1, true); rr.adopt(2, true); fg.adopt(3, true);
        sr.retire(() -> {}, h -> assertEquals(1, h));
        assertTrue(rr.usable()); assertTrue(fg.usable());
        assertEquals(2, rr.handle()); assertEquals(3, fg.handle());
    }
}
