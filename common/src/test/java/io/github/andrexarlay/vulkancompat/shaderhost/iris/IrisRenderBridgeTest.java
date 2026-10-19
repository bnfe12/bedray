package io.github.andrexarlay.vulkancompat.shaderhost.iris;

import io.github.andrexarlay.vulkancompat.shaderhost.IrisShaderHost;
import io.github.andrexarlay.vulkancompat.shaderhost.ShaderHostKind;
import io.github.andrexarlay.vulkancompat.shaderhost.ShaderHostState;
import io.github.andrexarlay.vulkancompat.shaderhost.ShaderHostStatus;
import io.github.andrexarlay.vulkancompat.shaderhost.ShaderHosts;
import io.github.andrexarlay.vulkancompat.shaderhost.pack.PackReportCache;
import io.github.andrexarlay.vulkancompat.shaderhost.pack.ProgramName;
import io.github.andrexarlay.vulkancompat.shaderhost.pack.ScanLimits;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.vulkan.VkCommandBuffer;
import sun.misc.Unsafe;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * L2 JUnit tests for {@link IrisRenderBridge}:
 * - Guard condition verification (null command buffer, null host, non-READY states, missing/empty active graph,
 *   zero image handles, invalid width/height).
 * - Bridge lifecycle management (initialization, test injection, teardown, recreation upon pack change).
 * - Frame timing tracking for Iris uniform push constants.
 * - End-to-end execution with tiny-testpack and test executor.
 */
class IrisRenderBridgeTest {

    private VkCommandBuffer dummyCmd;
    private IrisShaderHost readyHost;
    private IrisPassGraph testGraph;
    private CameraRenderState camera;

    @BeforeEach
    void setUp() throws IOException {
        IrisRenderBridge.teardown();
        IrisRenderBridge.resetTiming();

        dummyCmd = createDummyCommandBuffer();

        Path packDir = findTinyTestpack();
        IrisPassGraph.ParseResult res = IrisPassGraph.build(packDir, ScanLimits.defaults());
        assertTrue(res.success(), "tiny-testpack must parse successfully");
        testGraph = res.graph();

        readyHost = new IrisShaderHost(new PackReportCache(ScanLimits.defaults()));
        readyHost.setActiveGraphForTesting(testGraph);
        readyHost.setStatusForTesting(ShaderHostStatus.of(ShaderHostKind.IRIS, ShaderHostState.READY, "ready"));

        camera = new CameraRenderState();
        camera.depthFar = 128.0f;
    }

    @AfterEach
    void tearDown() {
        IrisRenderBridge.teardown();
        IrisRenderBridge.resetTiming();
        ShaderHosts.resetForTesting();
    }

    private static Path findTinyTestpack() {
        Path p = Path.of("src", "test", "resources", "testpacks", "tiny-testpack");
        if (Files.isDirectory(p)) {
            return p;
        }
        p = Path.of("..", "testpacks", "tiny-testpack");
        if (Files.isDirectory(p)) {
            return p;
        }
        p = Path.of("testpacks", "tiny-testpack");
        if (Files.isDirectory(p)) {
            return p;
        }
        throw new IllegalStateException("tiny-testpack not found at ../testpacks/tiny-testpack or testpacks/tiny-testpack");
    }

    private static VkCommandBuffer createDummyCommandBuffer() {
        try {
            Field f = Unsafe.class.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            Unsafe unsafe = (Unsafe) f.get(null);
            return (VkCommandBuffer) unsafe.allocateInstance(VkCommandBuffer.class);
        } catch (Throwable t) {
            return null;
        }
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

    // --- Guard Condition Tests ---

    @Test
    void renderGuardsAgainstNullCommandBuffer() {
        boolean result = IrisRenderBridge.render(
            null, readyHost, 1L, 2L, 3L, 1920, 1080,
            camera, 0.0f, 0, 0.05f, 128.0f, 0.016f
        );
        assertFalse(result, "render must return false when cmd is null");
    }

    @Test
    void renderGuardsAgainstNullHost() {
        assumeTrue(dummyCmd != null, "dummy VkCommandBuffer required");
        boolean result = IrisRenderBridge.render(
            dummyCmd, null, 1L, 2L, 3L, 1920, 1080,
            camera, 0.0f, 0, 0.05f, 128.0f, 0.016f
        );
        assertFalse(result, "render must return false when host is null");
    }

    @Test
    void renderGuardsAgainstNonReadyHostStates() {
        assumeTrue(dummyCmd != null, "dummy VkCommandBuffer required");

        ShaderHostState[] nonReadyStates = {
            ShaderHostState.NOT_INSTALLED,
            ShaderHostState.UNSUPPORTED_PACK_FEATURES,
            ShaderHostState.COMPILE_FAILED,
            ShaderHostState.DEVICE_UNSUPPORTED,
            ShaderHostState.DISABLED_FOR_SESSION
        };

        for (ShaderHostState state : nonReadyStates) {
            IrisShaderHost unreadyHost = new IrisShaderHost(new PackReportCache(ScanLimits.defaults()));
            unreadyHost.setActiveGraphForTesting(testGraph);
            unreadyHost.setStatusForTesting(ShaderHostStatus.of(ShaderHostKind.IRIS, state, "state test: " + state));

            boolean result = IrisRenderBridge.render(
                dummyCmd, unreadyHost, 1L, 2L, 3L, 1920, 1080,
                camera, 0.0f, 0, 0.05f, 128.0f, 0.016f
            );
            assertFalse(result, "render must return false when host state is " + state);
        }
    }

    @Test
    void renderGuardsAgainstNullActiveGraph() {
        assumeTrue(dummyCmd != null, "dummy VkCommandBuffer required");

        IrisShaderHost hostWithoutGraph = new IrisShaderHost(new PackReportCache(ScanLimits.defaults()));
        hostWithoutGraph.setStatusForTesting(ShaderHostStatus.of(ShaderHostKind.IRIS, ShaderHostState.READY, "ready"));
        hostWithoutGraph.setActiveGraphForTesting(null);

        boolean result = IrisRenderBridge.render(
            dummyCmd, hostWithoutGraph, 1L, 2L, 3L, 1920, 1080,
            camera, 0.0f, 0, 0.05f, 128.0f, 0.016f
        );
        assertFalse(result, "render must return false when host.activeGraph() is null");
    }

    @Test
    void renderGuardsAgainstEmptyPassGraph() {
        assumeTrue(dummyCmd != null, "dummy VkCommandBuffer required");

        IrisPassGraph emptyGraph = new IrisPassGraph("empty", "shaders/", List.of());
        IrisShaderHost hostWithEmptyGraph = new IrisShaderHost(new PackReportCache(ScanLimits.defaults()));
        hostWithEmptyGraph.setStatusForTesting(ShaderHostStatus.of(ShaderHostKind.IRIS, ShaderHostState.READY, "ready"));
        hostWithEmptyGraph.setActiveGraphForTesting(emptyGraph);

        boolean result = IrisRenderBridge.render(
            dummyCmd, hostWithEmptyGraph, 1L, 2L, 3L, 1920, 1080,
            camera, 0.0f, 0, 0.05f, 128.0f, 0.016f
        );
        assertFalse(result, "render must return false when activeGraph has no passes");
    }

    @Test
    void renderGuardsAgainstZeroImageHandles() {
        assumeTrue(dummyCmd != null, "dummy VkCommandBuffer required");

        // Zero engineColorImage
        assertFalse(IrisRenderBridge.render(
            dummyCmd, readyHost, 0L, 2L, 3L, 1920, 1080,
            camera, 0.0f, 0, 0.05f, 128.0f, 0.016f
        ), "engineColorImage == 0");

        // Zero engineColorView
        assertFalse(IrisRenderBridge.render(
            dummyCmd, readyHost, 1L, 0L, 3L, 1920, 1080,
            camera, 0.0f, 0, 0.05f, 128.0f, 0.016f
        ), "engineColorView == 0");

        // Zero depthView
        assertFalse(IrisRenderBridge.render(
            dummyCmd, readyHost, 1L, 2L, 0L, 1920, 1080,
            camera, 0.0f, 0, 0.05f, 128.0f, 0.016f
        ), "depthView == 0");
    }

    @Test
    void renderGuardsAgainstInvalidDimensions() {
        assumeTrue(dummyCmd != null, "dummy VkCommandBuffer required");

        // Zero width
        assertFalse(IrisRenderBridge.render(
            dummyCmd, readyHost, 1L, 2L, 3L, 0, 1080,
            camera, 0.0f, 0, 0.05f, 128.0f, 0.016f
        ), "width == 0");

        // Zero height
        assertFalse(IrisRenderBridge.render(
            dummyCmd, readyHost, 1L, 2L, 3L, 1920, 0,
            camera, 0.0f, 0, 0.05f, 128.0f, 0.016f
        ), "height == 0");

        // Negative width
        assertFalse(IrisRenderBridge.render(
            dummyCmd, readyHost, 1L, 2L, 3L, -1, 1080,
            camera, 0.0f, 0, 0.05f, 128.0f, 0.016f
        ), "width < 0");

        // Negative height
        assertFalse(IrisRenderBridge.render(
            dummyCmd, readyHost, 1L, 2L, 3L, 1920, -1,
            camera, 0.0f, 0, 0.05f, 128.0f, 0.016f
        ), "height < 0");
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

        assertNotNull(exec2);
        assertNotSame(exec1, exec2, "Executor should be recreated when pack changes");
        assertEquals("pack-2", IrisRenderBridge.activePackName());
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

    // --- Successful Execution with Test Executor ---

    @Test
    void renderSucceedsWithValidGuardsAndTestExecutor() {
        assumeTrue(dummyCmd != null, "dummy VkCommandBuffer required");

        IrisPostExecutor testExec = IrisPostExecutor.createForTesting();
        IrisRenderBridge.setExecutorForTesting(testExec, testGraph.packName());

        boolean rendered = IrisRenderBridge.render(
            dummyCmd,
            readyHost,
            1001L, // engineColorImage
            1002L, // engineColorView
            1003L, // depthView
            1920,
            1080,
            camera,
            12.5f,
            42,
            0.05f,
            128.0f,
            0.016f
        );

        assertTrue(rendered, "render must succeed with valid guards and test executor");
        assertTrue(IrisRenderBridge.isInitialized());
        assertEquals(testGraph.packName(), IrisRenderBridge.activePackName());
    }
}
