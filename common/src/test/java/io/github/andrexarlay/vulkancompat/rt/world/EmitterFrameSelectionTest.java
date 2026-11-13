package io.github.andrexarlay.vulkancompat.rt.world;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class EmitterFrameSelectionTest {
    private static float[] record(float x, float y, float z, boolean entity) {
        float[] r = new float[RtEmitters.FLOATS];
        r[0] = x; r[1] = y; r[2] = z; r[3] = 1;
        r[4] = 1; r[7] = 1; r[10] = 1; r[11] = 1;
        r[14] = 1; r[15] = entity ? 1 : 0;
        r[20] = r[21] = r[22] = r[23] = 1;
        return r;
    }

    private static float[] entity(EmitterFrameSelection.Frame frame) {
        for (int i = 0; i < frame.count(); i++) if (frame.records()[i][15] > 0) return frame.records()[i];
        fail("entity light missing");
        return null;
    }

    @Test void scanCompletionUsesCurrentCameraForEntityWorldPosition() {
        var selection = new EmitterFrameSelection();
        float[][] statics = {record(1, 2, 3, false)};
        var first = selection.compose(statics, 1, 10, 20, 30, 10.25, 20.5, 30.75,
            128, 512, record(10, 0, 0, true), 1);
        assertEquals(20.25, first.originX() + (double) entity(first)[0], 0);
        // This is the same world light after the camera moved eight blocks during a slow static scan.
        var later = selection.compose(statics, 1, 10, 20, 30, 18.25, 20.5, 30.75,
            128, 512, record(2, 0, 0, true), 1);
        assertEquals(20.25, later.originX() + (double) entity(later)[0], 0,
            "the scan-start camera would incorrectly put the light at world x=12.25");
        assertEquals(20.5, later.originY() + (double) entity(later)[1], 0);
        assertEquals(30.75, later.originZ() + (double) entity(later)[2], 0);
    }

    @Test void movingEntityUpdatesEvenWhenStaticSelectionIsUnchanged() {
        var selection = new EmitterFrameSelection();
        float[][] statics = {record(1, 0, 0, false)};
        var first = selection.compose(statics, 1, 0, 0, 0, 8, 0, 0, 128, 512, record(2, 0, 0, true), 1);
        assertEquals(10, first.originX() + (double) entity(first)[0]);
        var moved = selection.compose(statics, 1, 0, 0, 0, 9, 0, 0, 128, 512, record(3, 0, 0, true), 1);
        assertEquals(12, moved.originX() + (double) entity(moved)[0]);
        var gone = selection.compose(statics, 1, 0, 0, 0, 9, 0, 0, 128, 512, null, 0);
        assertEquals(1, gone.count());
        assertEquals(0, gone.records()[0][15]);
    }

    @Test void cameraMovementPreservesUnchangedWorldLightIds() {
        var selection = new EmitterFrameSelection();
        var table = new EmitterTable();
        float[][] statics = {record(1, 0, 0, false)};
        var first = selection.compose(statics, 1, 10, 0, 0, 10.25, 0, 0, 128, 512, record(10, 0, 0, true), 1);
        var a = table.prepare(first.records(), first.count(), first.originX(), first.originY(), first.originZ());
        table.commit(a);
        var next = selection.compose(statics, 1, 10, 0, 0, 18.25, 0, 0, 128, 512, record(2, 0, 0, true), 1);
        var b = table.prepare(next.records(), next.count(), next.originX(), next.originY(), next.originZ());
        assertEquals(2, b.retained());
        assertEquals(0, b.added());
        assertEquals(0, b.removed());
        assertEquals(a.revision(), b.revision());
    }

    @Test void staticCoordinatesAreNotRebasedOrRoundedPerFrameAtLargeWorldPositions() {
        var selection = new EmitterFrameSelection();
        var table = new EmitterTable();
        float[][] statics = {record(0.1234567f, 0.2345678f, 0.3456789f, false)};
        float[] captured = statics[0].clone();
        int origin = 26_800_000;
        var first = selection.compose(statics, 1, origin, origin, origin,
            origin + 0.25, origin + 0.5, origin + 0.75, 128, 512, null, 0);
        var a = table.prepare(first.records(), first.count(), first.originX(), first.originY(), first.originZ());
        table.commit(a);
        var next = selection.compose(statics, 1, origin, origin, origin,
            origin + 0.75, origin + 0.25, origin + 0.5, 128, 512, null, 0);
        var b = table.prepare(next.records(), next.count(), next.originX(), next.originY(), next.originZ());
        assertEquals(origin, next.originX());
        assertEquals(Float.floatToRawIntBits(captured[0]), Float.floatToRawIntBits(next.records()[0][0]));
        assertEquals(1, b.retained());
        assertEquals(a.revision(), b.revision());
        assertArrayEquals(captured, statics[0]);
    }

    @Test void teleportWithoutSurvivingStaticsKeepsEntitySubBlockPrecision() {
        var selection = new EmitterFrameSelection();
        var frame = selection.compose(new float[][]{record(1, 0, 0, false)}, 1, 0, 0, 0,
            26_800_000.25, 0, 0, 128, 512, record(0.0012345f, 0, 0, true), 1);
        assertEquals(1, frame.count());
        assertEquals(26_800_000, frame.originX());
        assertEquals(26_800_000.2512345, frame.originX() + (double) entity(frame)[0], 1e-7);
    }

    @Test void mergingCannotMutateOrPublishThePartialSectionScan() {
        var selection = new EmitterFrameSelection();
        float[][] completed = {record(1, 0, 0, false)};
        float[] captured = completed[0].clone();
        List<Integer> processed = new ArrayList<>();
        var scan = new RtEmitters.ScanPass<>(List.of(7, 8, 9), 1, 0, 0, 0, 64);
        assertFalse(scan.advance(System.nanoTime() - 1, processed::add));
        var frame = selection.compose(completed, 1, 0, 0, 0, 0, 0, 0, 128, 512, record(3, 0, 0, true), 1);
        assertEquals(2, frame.count());
        assertEquals(List.of(7), processed);
        assertTrue(scan.advance(Long.MAX_VALUE, processed::add));
        assertEquals(List.of(7, 8, 9), processed);
        assertArrayEquals(captured, completed[0]);
    }

    @Test void selectionHonorsBudgetAndCurrentEntityRange() {
        var selection = new EmitterFrameSelection();
        float[][] statics = {record(10, 0, 0, false)};
        var near = selection.compose(statics, 1, 0, 0, 0, 0, 0, 0, 128, 1, record(1, 0, 0, true), 1);
        assertEquals(1, near.count());
        assertEquals(1, near.records()[0][15]);
        var outOfRange = selection.compose(statics, 1, 0, 0, 0, 0, 0, 0, 8, 512, record(20, 0, 0, true), 1);
        assertEquals(0, outOfRange.count());
        assertEquals(0, outOfRange.entityInRange());
        var disabled = selection.compose(statics, 1, 0, 0, 0, 0, 0, 0, 128, 0, record(1, 0, 0, true), 1);
        assertEquals(0, disabled.count());
        assertEquals(1, disabled.entityInRange());
    }

    @Test void emptyColdScanCanPublishEntitiesAndReusesItsBoundedHeap() {
        var selection = new EmitterFrameSelection();
        var first = selection.compose(new float[0][], 0, 0, 0, 0, 50.25, 0, 0, 128, 512, record(2, 0, 0, true), 1);
        assertEquals(1, first.count());
        assertEquals(52.25, first.originX() + (double) entity(first)[0]);
        var second = selection.compose(new float[0][], 0, 0, 0, 0, 50.5, 0, 0, 128, 512, record(1.75f, 0, 0, true), 1);
        assertSame(first.records(), second.records());
        assertEquals(52.25, second.originX() + (double) entity(second)[0]);
    }

    @Test void finalSelectionKeepsPublishedLightsUntilANewcomerWinsClearly() {
        var selection = new EmitterFrameSelection();
        float[][] statics = {record(0, 0, 0, false)};
        float[] newcomer = record(0.25f, 0, 0, true);
        newcomer[14] = 1.3f;
        var unpublished = selection.compose(statics, 1, 0, 0, 0, 0, 0, 0, 128, 1, null, 0);
        assertEquals(0, unpublished.records()[0][15]);
        assertEquals(0, selection.publishedKeysSnapshot().length);
        var withoutMembership = selection.compose(statics, 1, 0, 0, 0, 0, 0, 0, 128, 1, newcomer, 1);
        assertEquals(1, withoutMembership.records()[0][15], "composing a frame does not commit membership");

        var initial = selection.compose(statics, 1, 0, 0, 0, 0, 0, 0, 128, 1, null, 0);
        selection.recordPublished(initial);
        long[] scanMembership = selection.publishedKeysSnapshot();
        var nearTie = selection.compose(statics, 1, 0, 0, 0, 0, 0, 0, 128, 1, newcomer, 1);
        assertEquals(0, nearTie.records()[0][15], "an almost equally important entity must not evict a static light");

        newcomer[14] = 2f;
        var clearWinner = selection.compose(statics, 1, 0, 0, 0, 0, 0, 0, 128, 1, newcomer, 1);
        assertEquals(1, clearWinner.records()[0][15]);
        selection.recordPublished(clearWinner);
        assertNotEquals(scanMembership[0], selection.publishedKeysSnapshot()[0]);
        assertEquals(RtEmitters.stickKey(0, 0, 0, statics[0], 0, 0, 0, 0), scanMembership[0],
            "a later publication must not change the membership held by an in-flight scan");
    }
}
