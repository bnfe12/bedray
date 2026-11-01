package io.github.andrexarlay.vulkancompat.rtx.upscaler;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class UpscalerContextRetirementTest {
    @Test void queuedWorkMustCompleteBeforeNativeDestruction() {
        List<String> events = new ArrayList<>();
        String handle = "context";
        handle = UpscalerContextRetirement.release(handle, () -> events.add("submitted and fenced"),
            value -> events.add("destroy " + value));
        assertNull(handle);
        assertEquals(List.of("submitted and fenced", "destroy context"), events);
    }

    @Test void failedProofKeepsTheOnlyHandleAndDoesNotCallTheSdk() {
        String[] owner = {"context"};
        assertThrows(IllegalStateException.class, () -> owner[0] = UpscalerContextRetirement.release(owner[0],
            () -> { throw new IllegalStateException("host fence timed out"); },
            value -> fail("no completion proof")));
        assertEquals("context", owner[0]);
    }

    @Test void failedNativeDestructionKeepsTheHandleForAnExplicitRetry() {
        String[] owner = {"context"};
        assertThrows(IllegalStateException.class, () -> owner[0] = UpscalerContextRetirement.release(owner[0],
            () -> {}, value -> { throw new IllegalStateException("SDK destroy failed"); }));
        assertEquals("context", owner[0]);
        owner[0] = UpscalerContextRetirement.release(owner[0], () -> {}, value -> {});
        assertNull(owner[0]);
    }

    @Test void primitiveFeatureHandleHasTheSameRetryAndCompletionRules() {
        long[] owner = {91};
        List<Long> destroyed = new ArrayList<>();
        assertThrows(IllegalStateException.class, () -> owner[0] = UpscalerContextRetirement.release(owner[0],
            () -> {}, value -> { throw new IllegalStateException("release rejected"); }));
        assertEquals(91, owner[0]);
        owner[0] = UpscalerContextRetirement.release(owner[0], () -> {}, destroyed::add);
        assertEquals(0, owner[0]);
        assertEquals(List.of(91L), destroyed);
    }

    @Test void emptyOwnersNeedNeitherADeviceProofNorAnSdkCall() {
        assertNull(UpscalerContextRetirement.release((String) null,
            () -> fail("no owned context"), value -> fail("no owned context")));
        assertEquals(0, UpscalerContextRetirement.release(0L,
            () -> fail("no owned feature"), value -> fail("no owned feature")));
    }

    @Test void plannerRequestsReleaseWithoutPretendingToProveCompletion() {
        assertEquals(UpscalerReconfigure.Step.DESTROY_RETIRED, UpscalerReconfigure.plan(
            UpscalerReconfigure.Phase.RECONFIGURE_PENDING, "new", null, "old"));
    }
}
