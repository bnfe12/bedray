package io.github.andrexarlay.vulkancompat.rt.world;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The light-list scan is spread over frames, and the world changes under it while sections stream in. Owner report 2026-10-24:
 * a torch placed in a cave lit nothing — only the flame particles — until the area finished loading. The scan used to be thrown
 * away and restarted on every world change, and a restart publishes nothing, so while chunks stream (a version bump every frame)
 * no pass ever reached its end and the previously published list — from before the cave — stayed on the GPU. These tests pin the
 * pass semantics that fix it: a change makes the pass stale but never shrinks its progress, a stale pass still finishes so its
 * result can be published and superseded by the fresh pass the next frame starts, and only a camera jump discards a pass.
 */
public class RtEmitterScanPassTest {

    private static final double CX = 12.5;
    private static final double CY = 64.0;
    private static final double CZ = -30.5;
    /** 68 blocks: what {@link RtEmitters#passAbortDistance(float)} returns for the 128-block testbed scan range. */
    private static final double ABORT = RtEmitters.passAbortDistance(128f);

    private static long expiredDeadline() {
        return System.nanoTime() - 1L; // System.nanoTime() has an arbitrary origin, so never pass a literal 0
    }

    private static RtEmitters.ScanPass<Integer> pass(List<Integer> items, long version) {
        return new RtEmitters.ScanPass<>(items, version, CX, CY, CZ, ABORT);
    }

    @Test
    void oneFrameMakesProgressAndStopsOnTheDeadline() {
        List<Integer> steps = new ArrayList<>();
        RtEmitters.ScanPass<Integer> pass = pass(List.of(0, 1, 2, 3), 7L);

        assertFalse(pass.advance(expiredDeadline(), steps::add), "an expired deadline must end the frame early, not the pass");
        assertEquals(List.of(0), steps, "a frame must process at least one section, so a huge snapshot cannot stall forever");

        assertTrue(pass.advance(Long.MAX_VALUE, steps::add), "the rest of the snapshot fits in the next frame");
        assertEquals(List.of(0, 1, 2, 3), steps, "every section is visited exactly once, in order");
    }

    @Test
    void worldChangeKeepsProgressInsteadOfRestarting() {
        List<Integer> steps = new ArrayList<>();
        RtEmitters.ScanPass<Integer> pass = pass(List.of(0, 1, 2, 3, 4), 100L);
        assertFalse(pass.advance(expiredDeadline(), steps::add), "the first frame stops on the deadline");
        assertFalse(pass.stale(), "nothing changed yet");
        assertEquals(List.of(0), steps);

        pass.noteVersion(101L); // a section was rebuilt / streamed in during the pass
        assertTrue(pass.stale(), "the world moved on: the pass result is stale and must be superseded after it publishes");

        assertTrue(pass.advance(Long.MAX_VALUE, steps::add), "a stale pass still completes");
        assertEquals(List.of(0, 1, 2, 3, 4), steps,
            "the change must not restart the pass: re-visiting section 0 and dropping the tail is exactly the starvation that "
                + "left a freshly lit cave black until the area finished loading");
    }

    @Test
    void unchangedVersionStaysFreshAndStaleIsSticky() {
        RtEmitters.ScanPass<Integer> pass = pass(List.of(0, 1), 5L);
        pass.noteVersion(5L);
        assertFalse(pass.stale(), "the same version is not a change");
        pass.advance(expiredDeadline(), step -> { });
        pass.noteVersion(5L);
        assertFalse(pass.stale(), "a version that keeps matching across frames never makes the pass stale");

        pass.noteVersion(6L);
        pass.noteVersion(6L);
        assertTrue(pass.stale(), "once the world changed, later matching versions do not make the pass fresh again");
    }

    @Test
    void emptySnapshotFinishesImmediately() {
        List<Integer> steps = new ArrayList<>();
        RtEmitters.ScanPass<Integer> pass = pass(List.of(), 1L);
        assertTrue(pass.advance(expiredDeadline(), steps::add), "an empty world publishes at once instead of keeping the scan open");
        assertTrue(steps.isEmpty());
    }

    @Test
    void movementKeepsThePassAndOnlyATeleportDropsIt() {
        List<Integer> steps = new ArrayList<>();
        RtEmitters.ScanPass<Integer> pass = pass(List.of(0, 1, 2), 3L);
        assertFalse(pass.advance(expiredDeadline(), steps::add), "the first frame stops on the deadline");
        assertEquals(List.of(0), steps);

        assertTrue(pass.usableFor(CX, CY, CZ), "the camera is where the distance selection was computed");
        assertTrue(pass.usableFor(CX + 0.5, CY, CZ - 0.5), "walking during a pass is routine and must keep its progress");
        assertTrue(pass.usableFor(CX, CY - 20.0, CZ), "a fall must not drop the pass either");
        assertFalse(pass.usableFor(CX, CY - ABORT - 0.01, CZ),
            "past the abort distance the list is weighted around a place the player left: the pass must be dropped");
        assertFalse(pass.usableFor(CX + 300.0, CY, CZ), "a teleport or respawn must not publish a list aimed at the old position");
        assertTrue(pass.usableFor(CX, CY + ABORT, CZ), "the abort distance itself still counts as the same frame");

        pass.noteVersion(4L);
        assertTrue(pass.usableFor(CX, CY, CZ), "a world change is not a camera jump: it makes the pass stale, not unusable");

        assertTrue(pass.advance(Long.MAX_VALUE, steps::add), "the pass still completes");
        assertEquals(List.of(0, 1, 2), steps, "moving and changing the world must not cost the pass its progress");
    }

    @Test
    void abortDistanceScalesWithTheScanRangeButNeverCollapses() {
        assertEquals(64.0, RtEmitters.passAbortDistance(128f), 1e-9,
            "half of the 128-block testbed range: a pass restarting on every frame of a long flight would publish nothing");
        assertEquals(16.0, RtEmitters.passAbortDistance(32f), 1e-9, "a short range keeps a usable floor");
        assertEquals(16.0, RtEmitters.passAbortDistance(0f), 1e-9, "a zero range must not abort on the first step");
    }
}
