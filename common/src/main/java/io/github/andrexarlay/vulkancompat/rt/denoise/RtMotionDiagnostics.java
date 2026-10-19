package io.github.andrexarlay.vulkancompat.rt.denoise;

import io.github.andrexarlay.vulkancompat.rtx.upscaler.VelocityPipeline;

import java.util.Locale;

/**
 * Diagnostic snapshot of the motion vector / depth reprojection contract.
 *
 * <h2>Depth Encoding Conventions</h2>
 * The velocity pipeline supports three depth conventions:
 * <ol>
 *   <li><b>DEPTH_HARDWARE (0)</b> — Raw hardware depth buffer [0..1], non-linear (D3D/Vulkan NDC).
 *       Shader: {@code VelocityPipeline.dispatch()} default path.</li>
 *   <li><b>DEPTH_LINEAR_VIEW_Z (1)</b> — Linear view-space Z (positive = into screen) in world units.
 *       Used by DLSS-RR / NRD which require linear depth. Shader: {@code dispatchLinearViewZ()}.</li>
 *   <li><b>DEPTH_RAY_DISTANCE (2)</b> — Raw ray hit distance (t) in world units from primary RT pass.
 *       Used when RT provides depth instead of rasterizer. Shader: {@code dispatchRayDistance()}.</li>
 * </ol>
 *
 * <h2>Encoding to GLSL</h2>
 * depthMode is passed as a push constant ({@code pc.depthMode}) and switches the
 * GLSL path in {@code velocity_reproject.comp} for depth-to-NDC conversion.
 */
public final class RtMotionDiagnostics {

    public enum State {
        /** No dispatch has been called this session. */
        DISCONNECTED,
        /** Dispatch was called but no runtime verification has been done (no frame test). */
        CONNECTED_NOT_RUNTIME_VERIFIED,
        /** At least one frame reprojection test passed (CPU reference matches GPU expectations). */
        RUNTIME_VERIFIED,
        /** A depth / matrix error occurred during dispatch. */
        FAILED
    }

    private static volatile State state = State.DISCONNECTED;
    private static volatile int depthSource = -1;           // -1 = not set
    private static volatile int depthEncoding = VelocityPipeline.DEPTH_HARDWARE;
    private static volatile int convention = VelocityPipeline.DEPTH_HARDWARE;
    private static volatile boolean cameraValid = false;
    private static volatile boolean entityRigid = false;
    private static volatile long lastFrameId = -1L;
    private static volatile String lastError = "none";

    private RtMotionDiagnostics() {}

    /**
     * Called by VelocityPipeline.dispatch* after each frame, recording the depth mode used.
     */
    public static void recordDispatch(int currentDepthMode, boolean cameraMatrixValid, long frameId) {
        depthEncoding = currentDepthMode;
        convention = currentDepthMode;
        cameraValid = cameraMatrixValid;
        lastFrameId = frameId;
        lastError = "none";
        if (state == State.DISCONNECTED) {
            state = State.CONNECTED_NOT_RUNTIME_VERIFIED;
        }
    }

    /**
     * Called when entity rigid motion vectors are confirmed live (prev-frame address != 0).
     */
    public static void recordEntityRigidActive(boolean active) {
        entityRigid = active;
    }

    /**
     * Call after a successful CPU reference test ({@link VelocityPipeline#reprojectCpu}) to mark RUNTIME_VERIFIED.
     */
    public static void markRuntimeVerified() {
        if (state == State.CONNECTED_NOT_RUNTIME_VERIFIED) {
            state = State.RUNTIME_VERIFIED;
        }
    }

    public static void recordFailure(String error) {
        state = State.FAILED;
        lastError = error != null ? error : "unknown";
    }

    public static void reset() {
        state = State.DISCONNECTED;
        depthSource = -1;
        depthEncoding = VelocityPipeline.DEPTH_HARDWARE;
        convention = VelocityPipeline.DEPTH_HARDWARE;
        cameraValid = false;
        entityRigid = false;
        lastFrameId = -1L;
        lastError = "none";
    }

    public static State getState() { return state; }

    /** Human-readable name for a depth mode constant. */
    public static String depthModeName(int mode) {
        return switch (mode) {
            case VelocityPipeline.DEPTH_HARDWARE       -> "DEPTH_HARDWARE";
            case VelocityPipeline.DEPTH_LINEAR_VIEW_Z  -> "DEPTH_LINEAR_VIEW_Z";
            case VelocityPipeline.DEPTH_RAY_DISTANCE   -> "DEPTH_RAY_DISTANCE";
            default -> "UNKNOWN(" + mode + ")";
        };
    }

    /**
     * Returns a structured diagnostic line. Example:
     * {@code [RT-MV] state=RUNTIME_VERIFIED depthSource=0 depthEncoding=DEPTH_HARDWARE
     * convention=DEPTH_HARDWARE cameraValid=true entityRigid=true entitySkinned=MISSING lastError=none}
     */
    public static String formatDiagnostics() {
        return String.format(Locale.ROOT,
            "[RT-MV] state=%s depthSource=%d depthEncoding=%s convention=%s" +
            " cameraValid=%b entityRigid=%b entitySkinned=MISSING lastError=%s",
            state,
            depthSource,
            depthModeName(depthEncoding),
            depthModeName(convention),
            cameraValid,
            entityRigid,
            lastError
        );
    }
}
