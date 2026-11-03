package io.github.andrexarlay.vulkancompat.rt.lifetime;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class RecordingResourceOwnerTest {
    @Test void writableSlotsAndRetiredGenerationsRequireExactProof() {
        List<String> destroyed = new ArrayList<>();
        var owner = new RecordingResourceOwner<String>(destroyed::add);
        var generation = owner.create("ring", 2);
        var first = owner.begin(1);
        var second = owner.begin(2);
        assertEquals(0, owner.lease(first, generation));
        assertEquals(1, owner.lease(second, generation));
        assertEquals(-1, owner.freeSlot(generation));
        assertThrows(IllegalStateException.class, () -> owner.begin(1));
        assertThrows(IllegalStateException.class, owner::close);
        owner.retire(generation);
        assertTrue(destroyed.isEmpty());
        owner.releaseAfterProof(first);
        assertTrue(destroyed.isEmpty());
        owner.releaseAfterProof(second);
        assertEquals(List.of("ring"), destroyed);
        owner.retire(generation); // retirement is idempotent even after collection
        owner.close();
        owner.close();
        assertEquals(List.of("ring"), destroyed);
    }

    @Test void staleTicketsCannotReleaseAReusedCommandHandle() {
        var owner = new RecordingResourceOwner<String>(ignored -> {});
        var generation = owner.create("ring", 1);
        var first = owner.begin(7);
        owner.lease(first, generation);
        owner.releaseAfterProof(first);
        var second = owner.begin(7);
        owner.lease(second, generation);
        assertFalse(owner.releaseAfterProof(first));
        assertEquals(-1, owner.freeSlot(generation));
        owner.releaseAfterProof(second);
        owner.close();
    }

    @Test void immutableGenerationsRemainPinnedUntilEveryReaderCompletes() {
        List<String> destroyed = new ArrayList<>();
        var owner = new RecordingResourceOwner<String>(destroyed::add);
        var atlas = owner.create("atlas", 0);
        var first = owner.begin(1);
        var second = owner.begin(2);
        owner.pin(first, atlas);
        owner.pin(second, atlas);
        owner.retire(atlas);
        owner.releaseAfterProof(second);
        assertTrue(destroyed.isEmpty());
        owner.releaseAfterProof(first);
        assertEquals(List.of("atlas"), destroyed);
        owner.close();
    }

    @Test void destructorFailureKeepsProofForRetryAndCannotAcceptMorePins() {
        int[] calls = {0};
        var owner = new RecordingResourceOwner<String>(ignored -> {
            if (calls[0]++ == 0) throw new IllegalStateException("destroy failed");
        });
        var generation = owner.create("ring", 1);
        var first = owner.begin(1);
        owner.lease(first, generation);
        owner.retire(generation);
        assertThrows(IllegalStateException.class, () -> owner.releaseAfterProof(first));
        assertEquals(1, owner.pendingCount());
        assertThrows(IllegalStateException.class, () -> owner.pin(first, generation));
        assertTrue(owner.releaseAfterProof(first));
        assertEquals(2, calls[0]);
        assertEquals(0, owner.pendingCount());
        owner.close();
    }

    @Test void failedCloseRefusesNewRecordingsUntilCleanupSucceeds() {
        int[] calls = {0};
        var owner = new RecordingResourceOwner<String>(ignored -> {
            if (calls[0]++ == 0) throw new IllegalStateException("destroy failed");
        });
        owner.create("unused", 1);
        assertThrows(IllegalStateException.class, owner::close);
        assertThrows(IllegalStateException.class, () -> owner.begin(1));
        owner.close();
        owner.close();
        assertEquals(2, calls[0]);
    }

    @Test void ownersCannotBorrowOrReleaseEachOthersIdentities() {
        var owner = new RecordingResourceOwner<String>(ignored -> {});
        var other = new RecordingResourceOwner<String>(ignored -> {});
        var generation = other.create("foreign", 1);
        var recording = owner.begin(1);
        assertThrows(IllegalArgumentException.class, () -> owner.pin(recording, generation));
        assertFalse(other.releaseAfterProof(recording));
        owner.releaseAfterProof(recording);
        owner.close();
        other.close();
    }
}
