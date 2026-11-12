package io.github.andrexarlay.vulkancompat.shaderhost.iris;

import io.github.andrexarlay.vulkancompat.shaderhost.ShaderHosts;
import io.github.andrexarlay.vulkancompat.shaderhost.pack.ProgramName;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * L2 JUnit tests for {@link IrisRenderBridge}:
 * - Bridge lifecycle management (initialization, test injection, teardown, recreation upon pack change).
 * - Frame timing tracking for Iris uniform push constants.
 */
class IrisRenderBridgeTest {

    @BeforeEach
    void setUp() {
        IrisRenderBridge.teardown();
        IrisRenderBridge.resetTiming();
    }

    @AfterEach
    void tearDown() {
        IrisRenderBridge.teardown();
        IrisRenderBridge.resetTiming();
        ShaderHosts.resetForTesting();
    }

    private static IrisPassGraph createMockGraph(String packName) {
        ByteBuffer dummy = ByteBuffer.allocate(16);
        IrisPassGraph.Pass pass = new IrisPassGraph.Pass(
            "composite", ProgramName.Family.COMPOSITE, 0,
            "composite.vsh", "composite.fsh", "", "",
            null, null, dummy, dummy
        );
        return new IrisPassGraph(packName, "shaders/", List.of(pass));
    }

    // --- Bridge Lifecycle Tests ---

    @Test
    void lifecycleInitialStateIsUninitialized() {
        assertFalse(IrisRenderBridge.isInitialized());
        assertNull(IrisRenderBridge.getExecutor());
        assertNull(IrisRenderBridge.activePackName());
    }

    @Test
    void lifecycleTeardownIsIdempotent() {
        assertDoesNotThrow(IrisRenderBridge::teardown);
        assertDoesNotThrow(IrisRenderBridge::teardown);
        assertFalse(IrisRenderBridge.isInitialized());
    }

    @Test
    void lifecycleSetExecutorForTesting() {
        IrisPostExecutor testExec = IrisPostExecutor.createForTesting();
        IrisRenderBridge.setExecutorForTesting(testExec, "pack-alpha");

        assertTrue(IrisRenderBridge.isInitialized());
        assertSame(testExec, IrisRenderBridge.getExecutor());
        assertEquals("pack-alpha", IrisRenderBridge.activePackName());

        IrisRenderBridge.teardown();
        assertFalse(IrisRenderBridge.isInitialized());
        assertNull(IrisRenderBridge.getExecutor());
        assertNull(IrisRenderBridge.activePackName());
    }

    @Test
    void lifecycleRecreationWhenShaderpackChanges() {
        IrisPostExecutor exec1 = IrisPostExecutor.createForTesting();
        IrisRenderBridge.setExecutorForTesting(exec1, "pack-1");
        assertEquals("pack-1", IrisRenderBridge.activePackName());

        IrisPassGraph graph2 = createMockGraph("pack-2");
        IrisPostExecutor exec2 = IrisRenderBridge.getOrCreateExecutor(null, graph2);

        // A pack change retires the old executor. There is no Vulkan device in a unit test, so no replacement can be
        // created: the bridge must return null (native image retained) instead of handing back the stale executor.
        assertNull(exec2, "No executor can be created without a Vulkan device");
        assertNull(IrisRenderBridge.getExecutor(), "The pack-1 executor must be retired on pack change");
        assertNull(IrisRenderBridge.activePackName());
    }

    @Test
    void lifecycleReusesExecutorWhenShaderpackIsSame() {
        IrisPostExecutor exec1 = IrisPostExecutor.createForTesting();
        IrisRenderBridge.setExecutorForTesting(exec1, "pack-1");

        IrisPassGraph graph1 = createMockGraph("pack-1");
        IrisPostExecutor exec2 = IrisRenderBridge.getOrCreateExecutor(null, graph1);

        assertSame(exec1, exec2, "Executor should be reused when pack is identical");
        assertEquals("pack-1", IrisRenderBridge.activePackName());
    }

    // --- Frame Timing Tests ---

    @Test
    void frameTimingTrackingAndReset() {
        assertEquals(0.0f, IrisRenderBridge.frameTimeCounter());
        assertEquals(0, IrisRenderBridge.frameCounter());

        IrisRenderBridge.updateTiming();
        assertTrue(IrisRenderBridge.frameCounter() > 0, "frameCounter should advance");
        assertTrue(IrisRenderBridge.frameTime() > 0.0f, "frameTime should be positive");
        assertTrue(IrisRenderBridge.frameTimeCounter() > 0.0f, "frameTimeCounter should advance");

        IrisRenderBridge.resetTiming();
        assertEquals(0.0f, IrisRenderBridge.frameTimeCounter());
        assertEquals(0, IrisRenderBridge.frameCounter());
    }
}
