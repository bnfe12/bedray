package io.github.andrexarlay.vulkancompat.rtx;

import io.github.andrexarlay.vulkancompat.rt.world.CameraBobPose;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Tracks the exact, unjittered raster world projection of the current frame and the camera-origin offset derived from the
 * view-bobbing pose. {@code GameRenderer.renderLevel} multiplies the camera projection by the bob / hurt-tilt / portal-wobble
 * pose before it reaches the projection buffer, so the recorded projection differs from {@code CameraRenderState.projectionMatrix}.
 * <p>
 * Per-Vulkan-frame lifecycle: {@link #beginFrame()} arms the tracker, the first {@link #note} call after arming records the
 * world projection, and {@link #reset()} clears it when the dedicated world targets are released. State is static because the
 * tracker is a single per-client instance shared by the render mixins and the RT/compute capture paths.
 */
final class WorldLevelProjection {
    private static final Matrix4f levelProjection = new Matrix4f();
    private static final Vector3f levelCameraOrigin = new Vector3f();
    private static boolean haveLevelProjection;
    private static boolean awaitingLevelProjection;
    private static final boolean BOB_PROJECTION = !"off".equalsIgnoreCase(System.getProperty("vkrtx.rt.bobProjection", "on"));

    private WorldLevelProjection() {}

    /**
     * Records the projection the engine really uses for the world this frame (unjittered) and derives the camera-origin
     * offset in raw-camera-relative world coordinates. renderLevel also passes the hand (hud3d) projection through the
     * projection buffer later in the frame, so only the first call after {@link #beginFrame()} is the world's one.
     */
    static boolean note(Matrix4f projMatrix, Matrix4f baseProjection, Matrix4f viewRotation) {
        if (projMatrix != null && awaitingLevelProjection) {
            awaitingLevelProjection = false;
            levelProjection.set(projMatrix);
            CameraBobPose.originOffset(projMatrix, baseProjection, viewRotation, levelCameraOrigin);
            haveLevelProjection = true;
            return true;
        }
        return false;
    }

    /** Arms {@link #note}: called before {@code renderLevel} of every Vulkan frame. */
    static void beginFrame() {
        haveLevelProjection = false;
        levelCameraOrigin.zero();
        awaitingLevelProjection = true;
    }

    /** True only after the world projection hook has run in the current renderLevel invocation. */
    static boolean hasCurrent() {
        return haveLevelProjection;
    }

    /** The current frame's full, unjittered world projection including bobbing, or {@code fallback}. */
    static Matrix4f projectionOr(Matrix4f fallback) {
        return BOB_PROJECTION && haveLevelProjection ? new Matrix4f(levelProjection) : fallback;
    }

    /** Exact raster projection, irrespective of the optional RT/compute bob approximation setting. */
    static Matrix4f currentProjection() {
        return haveLevelProjection ? new Matrix4f(levelProjection) : null;
    }

    /** Effective origin of the exact raster camera, independent of hardware RT diagnostic settings. */
    static Vector3f currentCameraOrigin() {
        return haveLevelProjection ? new Vector3f(levelCameraOrigin) : null;
    }

    /** Camera origin offset caused by the view-bob transform, in coordinates relative to the base camera. */
    static Vector3f cameraOriginOr(Vector3f fallback) {
        return BOB_PROJECTION && haveLevelProjection ? new Vector3f(levelCameraOrigin)
            : (fallback != null ? new Vector3f(fallback) : new Vector3f());
    }

    static boolean bobEnabled() {
        return BOB_PROJECTION;
    }

    /** Clears the recorded projection and disarms the tracker; called when the world targets are released. */
    static void reset() {
        haveLevelProjection = false;
        awaitingLevelProjection = false;
        levelProjection.identity();
        levelCameraOrigin.zero();
    }
}