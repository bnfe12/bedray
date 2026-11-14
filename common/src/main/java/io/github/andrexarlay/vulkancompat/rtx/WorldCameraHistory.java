package io.github.andrexarlay.vulkancompat.rtx;

import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The temporal camera history of the world phase: the current and previous projection, view rotation and camera position,
 * the inverse view-projection matrices the velocity pass reprojects with, and the reset flag that invalidates the upscaler
 * history when the camera teleports, a target is resized or the resolution plan changes. Split out of
 * {@link WorldTargetManager} so the target lifecycle and the temporal camera state are separate owners: the manager keeps
 * the frozen public accessors as delegates and tells this owner to reset whenever a target it publishes changes.
 */
final class WorldCameraHistory {
    private WorldCameraHistory() {
    }

    private static final Logger LOGGER = LoggerFactory.getLogger("Bedray/WorldTargetManager");

    private static Matrix4f currProjMatrix;
    private static Matrix4f prevProjMatrix;
    private static Matrix4f currViewRotationMatrix;
    private static Matrix4f prevViewRotationMatrix;
    private static Vec3 currCameraPos;
    private static Vec3 prevCameraPos;
    private static boolean projectionCaptured;
    private static boolean cameraCaptured;

    private static final Matrix4f invViewProj = new Matrix4f();
    private static final Matrix4f prevViewProj = new Matrix4f();

    private static boolean resetHistory = true;
    /** True only when {@link #computeMatrices()} accepted this frame's camera; history advances only then. */
    private static boolean cameraMatricesValidThisFrame = false;
    private static long lastDegenerateCameraLogNanos = 0L;

    /** Copy of the last unjittered world projection (null before the first world frame). Read by FSR for near/far/fov. */
    static Matrix4f currentProjection() {
        return currProjMatrix == null ? null : new Matrix4f(currProjMatrix);
    }

    /** Reuse matrix storage without treating the previous frame's input as current. */
    static void beginFrame() {
        projectionCaptured = false;
        cameraCaptured = false;
        cameraMatricesValidThisFrame = false;
    }

    static void setCurrentProjectionMatrix(Matrix4f projMatrix) {
        projectionCaptured = projMatrix != null;
        if (projMatrix != null) {
            if (currProjMatrix == null) {
                currProjMatrix = new Matrix4f(projMatrix);
            } else {
                currProjMatrix.set(projMatrix);
            }
        }
    }

    static void setCameraState(Matrix4f viewRotationMatrix, Vec3 pos) {
        cameraCaptured = viewRotationMatrix != null && pos != null;
        if (viewRotationMatrix != null) {
            if (currViewRotationMatrix == null) {
                currViewRotationMatrix = new Matrix4f(viewRotationMatrix);
            } else {
                currViewRotationMatrix.set(viewRotationMatrix);
            }
        }
        if (pos != null) {
            if (prevCameraPos != null && pos.distanceToSqr(prevCameraPos) > 64.0 * 64.0) {
                resetHistory = true;
            }
            currCameraPos = pos;
        }
    }

    static void setCameraView(Matrix4f viewRotationMatrix, Vec3 pos) {
        setCameraState(viewRotationMatrix, pos);
    }

    /**
     * Computes invViewProj = (currProj * currViewRotation)^-1 and prevViewProj = prevProj * prevViewRotation. A degenerate
     * camera is not inverted: the last usable matrices stay in place, history is reset and the caller skips this temporal
     * frame, so a NaN inverse never reaches the native velocity contract (which disabled DLSS for the whole session).
     *
     * @return false when this frame's camera cannot produce a finite inverse view-projection
     */
    static boolean computeMatrices() {
        cameraMatricesValidThisFrame = false;
        if (!projectionCaptured || !cameraCaptured) {
            resetHistory = true;
            logDegenerateCamera("projection_captured=" + projectionCaptured + " camera_captured=" + cameraCaptured);
            return false;
        }
        if (!isFinite(currCameraPos)) {
            resetHistory = true;
            logDegenerateCamera("camera_position=" + currCameraPos);
            return false;
        }
        CameraMatrixGuard.Update update = CameraMatrixGuard.update(
            currProjMatrix, currViewRotationMatrix, prevProjMatrix, prevViewRotationMatrix,
            resetHistory, invViewProj, prevViewProj);
        if (!update.usable()) {
            resetHistory = true;
            Matrix4f combined = currProjMatrix != null && currViewRotationMatrix != null
                ? new Matrix4f(currProjMatrix).mul(currViewRotationMatrix) : null;
            logDegenerateCamera(CameraMatrixGuard.describe(currProjMatrix, currViewRotationMatrix, combined));
            return false;
        }
        if (update.resetHistory()) {
            resetHistory = true;
        }
        cameraMatricesValidThisFrame = true;
        return true;
    }

    private static void logDegenerateCamera(String detail) {
        long now = System.nanoTime();
        if (now - lastDegenerateCameraLogNanos < 1_000_000_000L) {
            return;
        }
        lastDegenerateCameraLogNanos = now;
        LOGGER.warn("[VKB-AUTOTEST] camera_matrices=degenerate {} (temporal frame skipped, history reset, upscaler kept)",
            detail);
    }

    private static boolean isFinite(Vec3 value) {
        return Double.isFinite(value.x) && Double.isFinite(value.y) && Double.isFinite(value.z);
    }

    /** Camera translation since the last submitted temporal frame; zero whenever the history was reset. */
    static Vec3 cameraDelta() {
        if (currCameraPos == null || prevCameraPos == null || resetHistory
            || !isFinite(currCameraPos) || !isFinite(prevCameraPos)) {
            return Vec3.ZERO;
        }
        return currCameraPos.subtract(prevCameraPos);
    }

    static void advanceFrameHistory() {
        // A skipped or degenerate frame must not become the previous frame: the next submitted temporal frame has to
        // reproject against the last camera that actually produced motion vectors.
        if (cameraMatricesValidThisFrame) {
            if (currProjMatrix != null) {
                if (prevProjMatrix == null) {
                    prevProjMatrix = new Matrix4f(currProjMatrix);
                } else {
                    prevProjMatrix.set(currProjMatrix);
                }
            }
            if (currViewRotationMatrix != null) {
                if (prevViewRotationMatrix == null) {
                    prevViewRotationMatrix = new Matrix4f(currViewRotationMatrix);
                } else {
                    prevViewRotationMatrix.set(currViewRotationMatrix);
                }
            }
            if (currCameraPos != null && isFinite(currCameraPos)) {
                prevCameraPos = currCameraPos;
            }
            resetHistory = false;
        }
        cameraMatricesValidThisFrame = false;
    }

    /** Drops the whole temporal camera state: called when the world targets are released. */
    static void reset() {
        beginFrame();
        resetHistory = true;
        lastDegenerateCameraLogNanos = 0L;
        invViewProj.identity();
        prevViewProj.identity();
        currProjMatrix = null;
        prevProjMatrix = null;
        currViewRotationMatrix = null;
        prevViewRotationMatrix = null;
        currCameraPos = null;
        prevCameraPos = null;
    }

    /** A target the manager publishes changed (created, resized, new resolution plan or epoch): the history is invalid. */
    static void markReset() {
        resetHistory = true;
    }

    static boolean resetHistory() {
        return resetHistory;
    }

    static void setResetHistory(boolean reset) {
        resetHistory = reset;
    }

    static Matrix4f invViewProj() {
        return invViewProj;
    }

    static Matrix4f prevViewProj() {
        return prevViewProj;
    }

    static Matrix4f currProjMatrix() {
        return currProjMatrix;
    }

    static Matrix4f prevProjMatrix() {
        return prevProjMatrix;
    }

    static Matrix4f currViewRotationMatrix() {
        return currViewRotationMatrix;
    }

    static Matrix4f prevViewRotationMatrix() {
        return prevViewRotationMatrix;
    }

    static Vec3 currCameraPos() {
        return currCameraPos;
    }

    static Vec3 prevCameraPos() {
        return prevCameraPos;
    }
}