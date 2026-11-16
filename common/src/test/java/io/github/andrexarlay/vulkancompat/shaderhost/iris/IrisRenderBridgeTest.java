package io.github.andrexarlay.vulkancompat.shaderhost.iris;

import io.github.andrexarlay.vulkancompat.shaderhost.IrisShaderHost;
import io.github.andrexarlay.vulkancompat.shaderhost.ShaderHostKind;
import io.github.andrexarlay.vulkancompat.shaderhost.ShaderHostState;
import io.github.andrexarlay.vulkancompat.shaderhost.ShaderHostStatus;
import io.github.andrexarlay.vulkancompat.shaderhost.ShaderHosts;
import io.github.andrexarlay.vulkancompat.shaderhost.pack.IrisPackSettings;
import io.github.andrexarlay.vulkancompat.shaderhost.pack.PackReport;
import io.github.andrexarlay.vulkancompat.shaderhost.pack.PackReportCache;
import io.github.andrexarlay.vulkancompat.shaderhost.pack.PackSource;
import io.github.andrexarlay.vulkancompat.shaderhost.pack.ProgramName;
import io.github.andrexarlay.vulkancompat.shaderhost.pack.ScanLimits;
import io.github.andrexarlay.vulkancompat.shaderhost.pack.ShaderpackScanner;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.lwjgl.vulkan.VkCommandBuffer;
import sun.misc.Unsafe;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

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

    @TempDir
    Path tmp;

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
            camera, null, 0.0f, 0, 0.05f, 128.0f, 0.016f
        );
        assertFalse(result, "render must return false when cmd is null");
    }

    @Test
    void renderGuardsAgainstNullHost() {
        assumeTrue(dummyCmd != null, "dummy VkCommandBuffer required");
        boolean result = IrisRenderBridge.render(
            dummyCmd, null, 1L, 2L, 3L, 1920, 1080,
            camera, null, 0.0f, 0, 0.05f, 128.0f, 0.016f
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
                camera, null, 0.0f, 0, 0.05f, 128.0f, 0.016f
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
            camera, null, 0.0f, 0, 0.05f, 128.0f, 0.016f
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
            camera, null, 0.0f, 0, 0.05f, 128.0f, 0.016f
        );
        assertFalse(result, "render must return false when activeGraph has no passes");
    }

    @Test
    void renderGuardsAgainstZeroImageHandles() {
        assumeTrue(dummyCmd != null, "dummy VkCommandBuffer required");

        // Zero engineColorImage
        assertFalse(IrisRenderBridge.render(
            dummyCmd, readyHost, 0L, 2L, 3L, 1920, 1080,
            camera, null, 0.0f, 0, 0.05f, 128.0f, 0.016f
        ), "engineColorImage == 0");

        // Zero engineColorView
        assertFalse(IrisRenderBridge.render(
            dummyCmd, readyHost, 1L, 0L, 3L, 1920, 1080,
            camera, null, 0.0f, 0, 0.05f, 128.0f, 0.016f
        ), "engineColorView == 0");

        // Zero depthView
        assertFalse(IrisRenderBridge.render(
            dummyCmd, readyHost, 1L, 2L, 0L, 1920, 1080,
            camera, null, 0.0f, 0, 0.05f, 128.0f, 0.016f
        ), "depthView == 0");
    }

    @Test
    void renderGuardsAgainstInvalidDimensions() {
        assumeTrue(dummyCmd != null, "dummy VkCommandBuffer required");

        // Zero width
        assertFalse(IrisRenderBridge.render(
            dummyCmd, readyHost, 1L, 2L, 3L, 0, 1080,
            camera, null, 0.0f, 0, 0.05f, 128.0f, 0.016f
        ), "width == 0");

        // Zero height
        assertFalse(IrisRenderBridge.render(
            dummyCmd, readyHost, 1L, 2L, 3L, 1920, 0,
            camera, null, 0.0f, 0, 0.05f, 128.0f, 0.016f
        ), "height == 0");

        // Negative width
        assertFalse(IrisRenderBridge.render(
            dummyCmd, readyHost, 1L, 2L, 3L, -1, 1080,
            camera, null, 0.0f, 0, 0.05f, 128.0f, 0.016f
        ), "width < 0");

        // Negative height
        assertFalse(IrisRenderBridge.render(
            dummyCmd, readyHost, 1L, 2L, 3L, 1920, -1,
            camera, null, 0.0f, 0, 0.05f, 128.0f, 0.016f
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
            null,
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

    // --- Forward-path outcome contract (Phase 1: graceful skip, no self-disable) ---

    /**
     * A forward frame whose gbuffers have no geometry this frame (capture warmup) must be SKIPPED, not FAILED, so the
     * hook leaves the engine image untouched and retries next frame instead of disabling the iris host for the session.
     */
    @Test
    void forwardFrameWithNoGeometryIsSkippedNotFailed() throws IOException {
        assumeTrue(dummyCmd != null, "dummy VkCommandBuffer required");
        IrisPassGraph.FullResult full = fullGraph();
        IrisShaderHost host = new IrisShaderHost(new PackReportCache(ScanLimits.defaults()));
        host.setStatusForTesting(ShaderHostStatus.of(ShaderHostKind.IRIS, ShaderHostState.READY, "ready"));
        host.setActiveFullGraphForTesting(full);
        camera.pos = new Vec3(0.0, 64.0, 0.0);
        camera.projectionMatrix = new Matrix4f().identity();
        camera.viewRotationMatrix = new Matrix4f().identity();
        IrisRenderBridge.setForwardExecutorForTesting(IrisForwardExecutor.createForTesting(), "Forward");

        IrisRenderBridge.RenderOutcome outcome = IrisRenderBridge.renderWithOutcome(
            dummyCmd, host, 1001L, 1002L, 1003L, 64, 48, camera, null, 0.0f, 0, 0.05f, 128.0f, 0.016f);

        assertEquals(IrisRenderBridge.RenderOutcome.SKIPPED, outcome,
            "an empty-geometry warmup frame must skip, not fail (the host must not be disabled for the session)");
        assertFalse(IrisRenderBridge.render(
            dummyCmd, host, 1001L, 1002L, 1003L, 64, 48, camera, null, 0.0f, 0, 0.05f, 128.0f, 0.016f),
            "the boolean contract still reports false for a skipped frame");
    }

    private IrisPassGraph.FullResult fullGraph() throws IOException {
        Path p = tmp.resolve("Forward.zip");
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(p))) {
            put(z, "shaders/gbuffers_terrain.vsh", """
                #version 120
                attribute vec3 vaPosition;
                attribute vec4 vaColor;
                attribute vec2 vaUV0;
                uniform mat4 modelViewMatrix;
                uniform mat4 projectionMatrix;
                varying vec2 texcoord;
                varying vec4 color;
                void main() {
                    gl_Position = projectionMatrix * modelViewMatrix * vec4(vaPosition, 1.0);
                    texcoord = vaUV0; color = vaColor;
                }
                """);
            put(z, "shaders/gbuffers_terrain.fsh", """
                #version 120
                /* DRAWBUFFERS:0 */
                uniform sampler2D texture;
                varying vec2 texcoord;
                varying vec4 color;
                void main() { gl_FragData[0] = texture2D(texture, texcoord) * color; }
                """);
            put(z, "shaders/composite.fsh", """
                #version 120
                uniform sampler2D colortex0;
                varying vec2 texcoord;
                void main() { gl_FragData[0] = texture2D(colortex0, texcoord); }
                """);
            put(z, "shaders/final.fsh", """
                #version 120
                uniform sampler2D colortex0;
                varying vec2 texcoord;
                void main() { gl_FragColor = texture2D(colortex0, texcoord); }
                """);
        }
        try (PackSource src = PackSource.open(p, ScanLimits.DEFAULT)) {
            PackReport report = ShaderpackScanner.scan(src);
            IrisPackSettings settings = IrisPackSettings.parse(src, report);
            IrisPassGraph.FullResult res = IrisPassGraph.build(src, report, settings);
            assertTrue(res.success(), res.status().detail());
            return res;
        }
    }

    private static void put(ZipOutputStream z, String name, String text) throws IOException {
        z.putNextEntry(new ZipEntry(name));
        z.write(text.getBytes(StandardCharsets.UTF_8));
        z.closeEntry();
    }
}
