package io.github.andrexarlay.bedray.api.v1;

import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

class GraphicsBackendRegistryTest {
    /** One flow owns the process-wide registry; no reflective reset or test-order dependency. */
    @Test
    void registrationAndRequestsNeverPerformGpuLifecycleOrClaimLiveActivity() {
        boolean startupRequest = Boolean.getBoolean("bedray.compute.demo");
        assertEquals(1, GraphicsBackendRegistry.API_VERSION);
        assertNull(GraphicsBackendRegistry.backend());
        assertEquals(startupRequest, GraphicsBackendRegistry.demoEnabled());
        assertEquals("Voxel renderer not initialized", GraphicsBackendRegistry.status());

        long originalRevision = GraphicsBackendRegistry.requestRevision();
        GraphicsBackendRegistry.setDemoEnabled(startupRequest);
        assertEquals(originalRevision, GraphicsBackendRegistry.requestRevision());
        GraphicsBackendRegistry.setDemoEnabled(!startupRequest);
        assertEquals(originalRevision + 1, GraphicsBackendRegistry.requestRevision());
        GraphicsBackendRegistry.setDemoEnabled(!startupRequest);
        assertEquals(originalRevision + 1, GraphicsBackendRegistry.requestRevision());
        GraphicsBackendRegistry.setDemoEnabled(startupRequest);
        assertEquals(originalRevision + 2, GraphicsBackendRegistry.requestRevision());
        assertEquals("Voxel renderer not initialized", GraphicsBackendRegistry.status());

        assertThrows(NullPointerException.class, () -> GraphicsBackendRegistry.register(null));
        assertThrows(IllegalArgumentException.class, () -> GraphicsBackendRegistry.register(new Backend(null)));
        assertThrows(IllegalArgumentException.class, () -> GraphicsBackendRegistry.register(new Backend("")));
        assertThrows(IllegalArgumentException.class, () -> GraphicsBackendRegistry.register(new Backend(" \t")));
        assertNull(GraphicsBackendRegistry.backend());

        Backend accepted = new Backend("test:synthetic_voxels");
        GraphicsBackendRegistry.register(accepted);
        assertSame(accepted, GraphicsBackendRegistry.backend());
        assertEquals(startupRequest ? "Synthetic scene requested" : "Hardware / raster renderer selected",
            GraphicsBackendRegistry.status());
        long registeredRevision = GraphicsBackendRegistry.requestRevision();
        GraphicsBackendRegistry.register(accepted);
        assertEquals(registeredRevision, GraphicsBackendRegistry.requestRevision());
        assertThrows(IllegalStateException.class,
            () -> GraphicsBackendRegistry.register(new Backend(accepted.id())));
        assertThrows(IllegalStateException.class,
            () -> GraphicsBackendRegistry.register(new Backend("test:another_renderer")));
        assertSame(accepted, GraphicsBackendRegistry.backend());

        GraphicsBackendRegistry.setDemoEnabled(true);
        assertTrue(GraphicsBackendRegistry.demoEnabled());
        assertEquals("Synthetic scene requested", GraphicsBackendRegistry.status());
        assertDoesNotClaimActive();
        GraphicsBackendRegistry.noteRendered(accepted.id());
        assertEquals("Synthetic scene recorded: " + accepted.id(), GraphicsBackendRegistry.status());
        assertDoesNotClaimActive();
        GraphicsBackendRegistry.noteFailure("Queue lacks compute support");
        assertEquals("Synthetic scene failed: Queue lacks compute support", GraphicsBackendRegistry.status());
        assertTrue(GraphicsBackendRegistry.demoEnabled(), "Failure status must not silently rewrite the user request");
        assertDoesNotClaimActive();
        GraphicsBackendRegistry.noteIdle("Synthetic scene waiting for a world");
        assertEquals("Synthetic scene waiting for a world", GraphicsBackendRegistry.status());
        assertDoesNotClaimActive();

        long enabledRevision = GraphicsBackendRegistry.requestRevision();
        GraphicsBackendRegistry.setDemoEnabled(false);
        assertFalse(GraphicsBackendRegistry.demoEnabled());
        assertEquals(enabledRevision + 1, GraphicsBackendRegistry.requestRevision());
        assertEquals("Hardware / raster renderer selected", GraphicsBackendRegistry.status());
        GraphicsBackendRegistry.setDemoEnabled(false);
        assertEquals(enabledRevision + 1, GraphicsBackendRegistry.requestRevision());
        long modeRevision = GraphicsBackendRegistry.requestRevision();
        GraphicsBackendRegistry.setSceneMode(GraphicsBackendRegistry.SceneMode.LIVE_OPAQUE);
        assertEquals(modeRevision + 1, GraphicsBackendRegistry.requestRevision());
        assertEquals("Live opaque debug requested", GraphicsBackendRegistry.status());
        GraphicsBackendRegistry.setSceneMode(GraphicsBackendRegistry.SceneMode.LIVE_OPAQUE);
        assertEquals(modeRevision + 1, GraphicsBackendRegistry.requestRevision());
        GraphicsBackendRegistry.noteRendered(accepted.id());
        assertEquals("Live opaque debug recorded: " + accepted.id(), GraphicsBackendRegistry.status());
        assertDoesNotClaimActive();
        assertThrows(NullPointerException.class,()->GraphicsBackendRegistry.setSceneMode(null));
        GraphicsBackendRegistry.setSceneMode(GraphicsBackendRegistry.SceneMode.SYNTHETIC);
        assertEquals(modeRevision + 2, GraphicsBackendRegistry.requestRevision());
        assertEquals(0, accepted.renderCalls, "Registration and settings must never render on the caller thread");
        assertEquals(0, accepted.closeCalls, "Only the host render thread may close provider GPU resources");
    }

    private static void assertDoesNotClaimActive() {
        assertFalse(GraphicsBackendRegistry.status().toUpperCase(Locale.ROOT).contains("ACTIVE"));
    }

    private static final class Backend implements GraphicsBackend {
        private final String id;
        private int renderCalls;
        private int closeCalls;

        private Backend(String id) { this.id = id; }
        @Override public String id() { return id; }
        @Override public boolean render(ComputeFrameContext frame) { renderCalls++; return false; }
        @Override public void close() { closeCalls++; }
    }
}
