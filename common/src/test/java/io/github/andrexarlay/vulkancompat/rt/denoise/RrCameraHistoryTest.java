package io.github.andrexarlay.vulkancompat.rt.denoise;

import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RrCameraHistoryTest {
    private static Matrix4f projection() {
        return new Matrix4f().setPerspective((float) Math.toRadians(70), 16f / 9, .05f, 512, true);
    }

    @Test void invalidFirstCameraIsSkippedAndTheNextValidFrameStartsFresh() {
        var history = new RrCameraHistory();
        assertNull(history.prepare(projection(), new Matrix4f().rotation(new Quaternionf(0, 0, 0, 0)), 1, 2, 3));
        assertNull(history.prepare(new Matrix4f().zero(), new Matrix4f(), 1, 2, 3));
        var recovered = history.prepare(projection(), new Matrix4f(), 1, 2, 3);
        assertNotNull(recovered);
        assertTrue(recovered.reset());
        assertEquals(Vec3.ZERO, recovered.delta());
        assertTrue(recovered.inverse().isFinite());
    }

    @Test void anUncommittedReconstructionNeverBecomesThePreviousCamera() {
        var history = new RrCameraHistory();
        var projection = projection();
        var firstRotation = new Matrix4f().rotationY(.1f);
        history.commit(history.prepare(projection, firstRotation, 0, 0, 0));
        // Evaluation of this frame fails: the caller does not commit it.
        history.prepare(projection, new Matrix4f().rotationY(.5f), 4, 0, 0);
        var next = history.prepare(projection, new Matrix4f().rotationY(.2f), 1, 0, 0);
        assertFalse(next.reset());
        assertEquals(new Vec3(1, 0, 0), next.delta());
        assertEquals(new Matrix4f(projection).mul(firstRotation), next.previous());
    }

    @Test void badPositionAndTeleportResetMotionWithoutPoisoningRecovery() {
        var history = new RrCameraHistory();
        history.commit(history.prepare(projection(), new Matrix4f(), 0, 0, 0));
        assertNull(history.prepare(projection(), new Matrix4f(), Double.NaN, 0, 0));
        var recovered = history.prepare(projection(), new Matrix4f(), 1, 0, 0);
        assertTrue(recovered.reset());
        assertEquals(Vec3.ZERO, recovered.delta());
        history.commit(recovered);
        var teleported = history.prepare(projection(), new Matrix4f(), 100, 0, 0);
        assertTrue(teleported.reset());
        assertEquals(Vec3.ZERO, teleported.delta());
        history.commit(teleported);
        var next = history.prepare(projection(), new Matrix4f(), 100.5, 0, 0);
        assertFalse(next.reset());
        assertEquals(new Vec3(.5, 0, 0), next.delta());
    }

    @Test void committedCameraOwnsItsMatricesAndExplicitResetAndClearStartFresh() {
        var history = new RrCameraHistory();
        var projection = projection();
        var rotation = new Matrix4f().rotationY(.1f);
        var expected = new Matrix4f(projection).mul(rotation);
        history.commit(history.prepare(projection, rotation, 0, 0, 0));
        projection.zero(); rotation.zero();
        assertEquals(expected, history.prepare(projection(), new Matrix4f(), 1, 0, 0).previous());
        history.reset();
        assertTrue(history.prepare(projection(), new Matrix4f(), 1, 0, 0).reset());
        history.clear();
        assertTrue(history.prepare(projection(), new Matrix4f(), 1, 0, 0).reset());
    }
}
