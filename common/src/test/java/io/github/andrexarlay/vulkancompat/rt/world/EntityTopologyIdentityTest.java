package io.github.andrexarlay.vulkancompat.rt.world;

import io.github.andrexarlay.vulkancompat.rt.scene.BedraySceneLayouts;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EntityTopologyIdentityTest {
    private static long update(EntityTopologyIdentity identity, int[] indices) {
        return identity.update(4, 3, 12, BedraySceneLayouts.INDEX_UINT32, MemorySegment.ofArray(indices));
    }

    @Test void identicalIndicesInAnotherFrameAllocationKeepTheirTopologyRevision() {
        var identity = new EntityTopologyIdentity();
        assertEquals(1, update(identity, new int[]{0, 1, 2}));
        assertEquals(1, update(identity, new int[]{0, 1, 2}));
    }

    @Test void reorderedIndicesRequireRebuildEvenAtTheSameCounts() {
        var identity = new EntityTopologyIdentity();
        update(identity, new int[]{0, 1, 2});
        assertEquals(2, update(identity, new int[]{0, 2, 1}));
        assertEquals(2, update(identity, new int[]{0, 2, 1}));
    }

    @Test void vertexCountAndStrideArePartOfShape() {
        var identity = new EntityTopologyIdentity();
        var indices = MemorySegment.ofArray(new int[]{0, 1, 2});
        assertEquals(1, identity.update(4, 3, 12, 2, indices));
        assertEquals(2, identity.update(5, 3, 12, 2, indices));
        assertEquals(3, identity.update(5, 3, 16, 2, indices));
    }

    @Test void indexFormatAndPrimitiveCountArePartOfShape() {
        var identity = new EntityTopologyIdentity();
        var indices = MemorySegment.ofArray(new int[]{0, 1, 2, 0, 2, 3});
        assertEquals(1, identity.update(4, 3, 12, 2, indices));
        assertEquals(2, identity.update(4, 6, 12, 2, indices));
        assertEquals(3, identity.update(4, 6, 12, 1, indices));
    }

    @Test void producerMutationCannotChangeTheAcceptedSnapshot() {
        var identity = new EntityTopologyIdentity();
        int[] indices = {0, 1, 2};
        update(identity, indices);
        indices[2] = 3;
        assertEquals(2, update(identity, indices));
    }

    @Test void unusedRingCapacityDoesNotChangeTopology() {
        var identity = new EntityTopologyIdentity();
        int[] indices = {0, 1, 2, 30, 40};
        update(identity, indices);
        indices[3] = 50;
        assertEquals(1, update(identity, indices));
    }

    @Test void nativeInputLifetimeDoesNotBecomeTopologySnapshotLifetime() {
        var identity = new EntityTopologyIdentity();
        try (var arena = Arena.ofConfined()) {
            var nativeIndices = arena.allocateFrom(ValueLayout.JAVA_INT, 0, 1, 2);
            assertEquals(1, identity.update(4, 3, 12, 2, nativeIndices));
        }
        assertEquals(1, update(identity, new int[]{0, 1, 2}));
    }

    @Test void removalInvalidatesIdentityWithoutReusingAnOldRevision() {
        var identity = new EntityTopologyIdentity();
        update(identity, new int[]{0, 1, 2}); identity.clear();
        assertEquals(2, update(identity, new int[]{0, 1, 2}));
    }

    @Test void rejectedRangeCannotAdvanceTheAcceptedRevision() {
        var identity = new EntityTopologyIdentity();
        update(identity, new int[]{0, 1, 2});
        assertThrows(IllegalArgumentException.class, () -> update(identity, new int[]{0, 1}));
        assertEquals(1, update(identity, new int[]{0, 1, 2}));
    }
}
