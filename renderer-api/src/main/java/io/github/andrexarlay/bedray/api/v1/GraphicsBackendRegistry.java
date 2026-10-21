package io.github.andrexarlay.bedray.api.v1;

import java.util.Objects;

/** Versioned companion discovery and explicit synthetic-demo request; no GPU calls during registration. */
public final class GraphicsBackendRegistry {
    public static final int API_VERSION = 1;
    private static volatile GraphicsBackend backend;
    private static volatile boolean demoEnabled = Boolean.getBoolean("bedray.compute.demo");
    private static volatile long requestRevision;
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
        status = demoEnabled ? "Synthetic scene requested" : "Synthetic scene disabled";
    }

    public static GraphicsBackend backend() { return backend; }
    public static boolean demoEnabled() { return demoEnabled; }
    public static long requestRevision() { return requestRevision; }
    public static String status() { return status; }

    /** Requests are applied by the host render thread; toggling back on retries a failed initialization. */
    public static synchronized void setDemoEnabled(boolean enabled) {
        if (demoEnabled != enabled) {
            demoEnabled = enabled;
            requestRevision++;
            status = backend == null ? "Companion not installed"
                : enabled ? "Synthetic scene requested" : "Synthetic scene disabled";
        }
    }

    /** Means commands were accepted by the host encoder, not that live gameplay was verified. */
    public static void noteRendered(String id) { status = "Synthetic scene recorded: " + id; }
    public static void noteFailure(String reason) { status = "Synthetic scene failed: " + reason; }
    public static void noteIdle(String reason) { status = reason; }
}
