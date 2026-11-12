package io.github.andrexarlay.vulkancompat.rtx;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the per-frame lifecycle of the exact raster world projection tracker extracted from WorldTargetManager:
 * arm ({@code beginFrame}) -> the first {@code note} records the world projection -> later notes in the same frame
 * (the hand projection) are ignored -> queries return defensive copies -> {@code reset} clears and disarms.
 */
class WorldLevelProjectionTest {
    private static Matrix4f perspective() {
        return new Matrix4f().setPerspective((float) java.lang.Math.toRadians(70), 16f / 9f, 0.05f, 512f, true);
    }

    @Test
    void beforeArmingThereIsNoLevelProjectionAndQueriesFallBack() {
        WorldLevelProjection.reset();
        assertFalse(WorldLevelProjection.hasCurrent());
        assertNull(WorldLevelProjection.currentProjection());
        assertNull(WorldLevelProjection.currentCameraOrigin());

        Matrix4f fallback = perspective();
        assertSame(fallback, WorldLevelProjection.projectionOr(fallback), "no recorded projection returns the fallback itself");
        assertEquals(new Vector3f(), WorldLevelProjection.cameraOriginOr(null), "no origin and no fallback is the zero vector");
        Vector3f originFallback = new Vector3f(1f, 2f, 3f);
        assertEquals(originFallback, WorldLevelProjection.cameraOriginOr(originFallback));
    }

    @Test
    void theFirstNoteAfterArmingRecordsTheProjectionAndLaterNotesAreIgnored() {
        WorldLevelProjection.beginFrame();
        assertFalse(WorldLevelProjection.hasCurrent(), "arming alone does not record a projection");

        Matrix4f world = perspective();
        Matrix4f base = perspective();
        Matrix4f view = new Matrix4f().rotationY(0.2f);
        assertTrue(WorldLevelProjection.note(world, base, view));
        assertTrue(WorldLevelProjection.hasCurrent());
        assertEquals(world, WorldLevelProjection.currentProjection());

        Matrix4f hand = new Matrix4f().setPerspective((float) java.lang.Math.toRadians(50), 1f, 0.05f, 256f, true);
        assertFalse(WorldLevelProjection.note(hand, base, view), "the hand projection later in the frame is not the world's");
        assertEquals(world, WorldLevelProjection.currentProjection(), "the world projection stays in place");
    }

    @Test
    void aNullProjectionIsNotRecorded() {
        WorldLevelProjection.beginFrame();
        assertFalse(WorldLevelProjection.note(null, perspective(), new Matrix4f()));
        assertFalse(WorldLevelProjection.hasCurrent());
    }

    @Test
    void aNoteWithoutArmingIsIgnored() {
        WorldLevelProjection.reset();
        assertFalse(WorldLevelProjection.note(perspective(), perspective(), new Matrix4f()),
            "reset disarms the tracker; only beginFrame re-arms it");
        assertFalse(WorldLevelProjection.hasCurrent());
    }

    @Test
    void queriesReturnDefensiveCopies() {
        WorldLevelProjection.beginFrame();
        Matrix4f world = perspective();
        WorldLevelProjection.note(world, perspective(), new Matrix4f().rotationY(0.2f));

        Matrix4f copy = WorldLevelProjection.currentProjection();
        copy.m00(123f);
        assertEquals(world, WorldLevelProjection.currentProjection(), "mutating a returned copy must not change the tracker");

        Vector3f origin = WorldLevelProjection.currentCameraOrigin();
        assertNotNull(origin);
        assertTrue(Float.isFinite(origin.x) && Float.isFinite(origin.y) && Float.isFinite(origin.z));
        origin.set(9f, 9f, 9f);
        Vector3f again = WorldLevelProjection.currentCameraOrigin();
        assertTrue(Float.isFinite(again.x) && again.x != 9f, "the origin copy is defensive too");
    }

    @Test
    void resetClearsTheRecordedProjectionAndDisarms() {
        WorldLevelProjection.beginFrame();
        WorldLevelProjection.note(perspective(), perspective(), new Matrix4f());
        assertTrue(WorldLevelProjection.hasCurrent());

        WorldLevelProjection.reset();
        assertFalse(WorldLevelProjection.hasCurrent());
        assertNull(WorldLevelProjection.currentProjection());
        assertFalse(WorldLevelProjection.note(perspective(), perspective(), new Matrix4f()));
    }

    @Test
    void bobProjectionIsEnabledByDefault() {
        assertTrue(WorldLevelProjection.bobEnabled(), "vkrtx.rt.bobProjection defaults to on");
    }
}