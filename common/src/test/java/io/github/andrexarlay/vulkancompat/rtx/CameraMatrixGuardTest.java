package io.github.andrexarlay.vulkancompat.rtx;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The first RT frame of 26.3/latest.log 2026-11-02 23:20:41 carried a zero-quaternion view rotation
 * ({@code diag(0, 0, 0, 1)}). Its product with the projection is singular, JOML inverts it to all-NaN
 * ({@code invViewProj.m00 = NaN}), the native velocity contract refuses the frame and DLSS is disabled for the
 * session. These tests pin the guard that keeps the last usable matrices and skips the frame instead.
 */
class CameraMatrixGuardTest {
    private static final Path WORLD_TARGET_MANAGER = Path.of("src/main/java/io/github/andrexarlay/"
        + "vulkancompat/rtx/WorldTargetManager.java");
    private static final Path WORLD_CAMERA_HISTORY = Path.of("src/main/java/io/github/andrexarlay/"
        + "vulkancompat/rtx/WorldCameraHistory.java");

    private static Matrix4f perspective() {
        return new Matrix4f().setPerspective((float) java.lang.Math.toRadians(70), 2560f / 1369f, 512f, 0.05f, true);
    }

    private static Matrix4f zeroQuaternionRotation() {
        return new Matrix4f().rotation(new Quaternionf(0f, 0f, 0f, 0f));
    }

    @Test
    void aZeroQuaternionViewRotationIsRejectedBeforeInversion() {
        Matrix4f projection = perspective();
        Matrix4f degenerate = zeroQuaternionRotation();
        assertFalse(CameraMatrixGuard.isUsable(degenerate), "diag(0,0,0,1) has determinant 0");
        assertFalse(CameraMatrixGuard.multiply(projection, degenerate, new Matrix4f()), "the product is singular");

        Matrix4f lastGoodInverse = new Matrix4f().translation(3f, -4f, 5f);
        Matrix4f lastGoodPrevious = new Matrix4f().translation(-1f, 2f, 0.5f);
        Matrix4f inverse = new Matrix4f(lastGoodInverse);
        Matrix4f previous = new Matrix4f(lastGoodPrevious);
        CameraMatrixGuard.Update update = CameraMatrixGuard.update(projection, degenerate, null, null, true, inverse, previous);

        assertFalse(update.usable());
        assertTrue(update.resetHistory());
        assertEquals(lastGoodInverse, inverse, "the last usable inverse stays in place");
        assertEquals(lastGoodPrevious, previous, "the last usable previous view-projection stays in place");
        assertTrue(inverse.isFinite() && previous.isFinite());
    }

    @Test
    void aSingularOrNonFiniteProjectionIsRejected() {
        assertFalse(CameraMatrixGuard.isUsable(new Matrix4f().zero()));
        assertFalse(CameraMatrixGuard.isUsable(new Matrix4f().setPerspective((float) java.lang.Math.toRadians(70),
            2560f / 1369f, 0f, 0.05f, true)), "zFar = 0 has determinant 0");
        assertFalse(CameraMatrixGuard.isUsable(new Matrix4f().setPerspective((float) java.lang.Math.toRadians(70),
            0f / 0f, 512f, 0.05f, true)), "a zero-size viewport makes the aspect NaN");
        assertFalse(CameraMatrixGuard.isUsable(new Matrix4f().setPerspective((float) java.lang.Math.toRadians(70),
            2560f / 1369f, 512f, 512f, true)), "zNear = zFar has determinant 0");
    }

    @Test
    void aValidFrameProducesAFiniteInverseAndReprojectsAgainstThePreviousFrame() {
        Matrix4f projection = perspective();
        Matrix4f rotation = new Matrix4f().rotationY(0.31f).rotateX(-0.12f);
        Matrix4f inverse = new Matrix4f();
        Matrix4f previous = new Matrix4f();

        CameraMatrixGuard.Update first = CameraMatrixGuard.update(projection, rotation, null, null, true, inverse, previous);
        assertTrue(first.usable());
        assertTrue(first.resetHistory());
        assertTrue(inverse.isFinite());
        assertEquals(new Matrix4f(projection).mul(rotation), previous, "the first frame has no previous frame");

        Matrix4f nextProjection = new Matrix4f(projection).translate(0f, 0f, 0.25f);
        Matrix4f nextRotation = new Matrix4f().rotationY(0.33f).rotateX(-0.12f);
        CameraMatrixGuard.Update second = CameraMatrixGuard.update(nextProjection, nextRotation,
            projection, rotation, false, inverse, previous);
        assertTrue(second.usable());
        assertFalse(second.resetHistory());
        assertTrue(inverse.isFinite());
        assertEquals(new Matrix4f(projection).mul(rotation), previous, "previous frame, not the current one");
    }

    @Test
    void aDegeneratePreviousFrameForcesAResetWithoutLosingTheCurrentFrame() {
        Matrix4f projection = perspective();
        Matrix4f rotation = new Matrix4f().rotationY(0.31f);
        Matrix4f inverse = new Matrix4f();
        Matrix4f previous = new Matrix4f();

        CameraMatrixGuard.Update update = CameraMatrixGuard.update(projection, rotation,
            projection, zeroQuaternionRotation(), false, inverse, previous);

        assertTrue(update.usable());
        assertTrue(update.resetHistory(), "a broken previous camera cannot be reprojected against");
        assertTrue(inverse.isFinite());
        assertEquals(new Matrix4f(projection).mul(rotation), previous);
    }

    @Test
    void aBadFrameKeepsTheLastGoodHistoryAndTheNextGoodFrameRecovers() {
        Matrix4f projection = perspective();
        Matrix4f rotation = new Matrix4f().rotationY(0.31f);
        Matrix4f inverse = new Matrix4f();
        Matrix4f previous = new Matrix4f();
        assertTrue(CameraMatrixGuard.update(projection, rotation, null, null, true, inverse, previous).usable());

        Matrix4f goodInverse = new Matrix4f(inverse);
        Matrix4f goodPrevious = new Matrix4f(previous);
        CameraMatrixGuard.Update bad = CameraMatrixGuard.update(projection, zeroQuaternionRotation(),
            projection, rotation, false, inverse, previous);
        assertFalse(bad.usable());
        assertEquals(goodInverse, inverse);
        assertEquals(goodPrevious, previous);

        Matrix4f nextRotation = new Matrix4f().rotationY(0.35f);
        CameraMatrixGuard.Update recovered = CameraMatrixGuard.update(projection, nextRotation,
            projection, rotation, true, inverse, previous);
        assertTrue(recovered.usable());
        assertTrue(inverse.isFinite());
        assertEquals(new Matrix4f(projection).mul(nextRotation), previous, "history restarts at the recovered frame");
    }

    @Test
    void aDegenerateCameraSkipsTheTemporalFrameInsteadOfFailingTheUpscaler() throws IOException {
        String source = Files.readString(WORLD_TARGET_MANAGER).replace("\r\n", "\n");
        assertTrue(source.contains("if (!computeMatrices()) {\n"
            + "            return false; // degenerate camera: skip this temporal frame instead of failing the upscaler for the session"),
            "recordXess must return false without calling executeFailed");
        assertTrue(source.contains("if (!computeMatrices()) {\n            return;\n        }\n        final Vec3 camDelta = cameraDelta();"),
            "motion-vectors-only must skip the same frame");
        String history = Files.readString(WORLD_CAMERA_HISTORY).replace("\r\n", "\n");
        assertTrue(history.contains("if (cameraMatricesValidThisFrame) {"),
            "advanceFrameHistory must not promote a skipped or degenerate frame");
    }
}
