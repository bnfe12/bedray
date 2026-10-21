package io.github.andrexarlay.bedray.api.v1;

import java.util.Objects;

/** Versioned companion discovery and explicit synthetic-demo request; no GPU calls during registration. */
public final class GraphicsBackendRegistry {
    public static final int API_VERSION = 1;
    public static final int API_MINOR_VERSION = 2;
    public enum DebugView { SHADED, NORMALS, DISTANCE, OCCUPANCY }
    private static volatile DebugView debugView = DebugView.SHADED;
    public static DebugView debugView() { return debugView; }
    public static void setDebugView(DebugView view) { debugView = Objects.requireNonNull(view); }
    private static volatile GraphicsBackend backend;
    public enum SceneMode { OFF, SYNTHETIC, LIVE_OPAQUE }
    public record SceneRequest(SceneMode mode, long revision) {
        public SceneRequest { Objects.requireNonNull(mode); }
    }
    private static volatile SceneRequest request = new SceneRequest(
        Boolean.getBoolean("bedray.compute.world") ? SceneMode.LIVE_OPAQUE
            : Boolean.getBoolean("bedray.compute.demo") ? SceneMode.SYNTHETIC : SceneMode.OFF, 0);
    public static SceneRequest request() { return request; }
    private static volatile String status = "Companion not installed";

    private GraphicsBackendRegistry() {}

    public static synchronized void register(GraphicsBackend candidate) {
        Objects.requireNonNull(candidate, "candidate");
        if (candidate.id() == null || candidate.id().isBlank()) {
            throw new IllegalArgumentException("A graphics backend requires a nonempty ID");
        }
        if (backend != null && backend != candidate) {
            throw new IllegalStateException("A compute diagnostic backend is already registered: " + backend.id());
        }
        backend = candidate;
        status = sceneMode() == SceneMode.OFF ? "Synthetic scene disabled" : sceneLabel() + " requested";
    }

    public static GraphicsBackend backend() { return backend; }
    public static boolean demoEnabled() { return sceneMode() != SceneMode.OFF; }
    public static SceneMode sceneMode() { return request.mode(); }
    public static boolean liveWorldEnabled() { return sceneMode() == SceneMode.LIVE_OPAQUE; }
    public static String sceneLabel() { return liveWorldEnabled() ? "Live opaque debug" : "Synthetic scene"; }
    public static long requestRevision() { return request.revision(); }
    public static String status() { return status; }

    /** Requests are applied by the host render thread; toggling back on retries a failed initialization. */
    public static synchronized void setDemoEnabled(boolean enabled) {
        setSceneMode(enabled ? SceneMode.SYNTHETIC : SceneMode.OFF);
    }

    public static synchronized void setSceneMode(SceneMode mode) {
        Objects.requireNonNull(mode, "mode");
        SceneRequest previous = request;
        if (previous.mode() != mode) {
            request = new SceneRequest(mode, previous.revision()+1);
            status = backend == null ? "Companion not installed" : sceneMode() == SceneMode.OFF
                ? "Synthetic scene disabled" : sceneLabel() + " requested";
        }
    }

    public static synchronized void cycleSceneMode() {
        if (backend == null) return;
        SceneMode[] modes = SceneMode.values();
        for (int i = 1; i <= modes.length; i++) {
            SceneMode next = modes[(request.mode().ordinal()+i)%modes.length];
            if (next == SceneMode.OFF || backend.supportsSceneMode(next)) { setSceneMode(next); return; }
        }
    }

    /** Means commands were accepted by the host encoder, not that live gameplay was verified. */
    public static void noteRendered(String id) { noteRendered(id,request); }
    public static synchronized void noteRendered(String id, SceneRequest applied) {
        if (request == applied) status = sceneLabel() + " recorded: " + id;
    }
    public static void noteFailure(String reason) { noteFailure(reason,request); }
    public static synchronized void noteFailure(String reason, SceneRequest applied) {
        if (request == applied) status = sceneLabel() + " failed: " + reason;
    }
    public static void noteIdle(String reason) { noteIdle(reason,request); }
    public static synchronized void noteIdle(String reason, SceneRequest applied) {
        if (request == applied) status = reason;
    }
}
