package io.github.andrexarlay.vulkancompat.shaderhost.iris;

import io.github.andrexarlay.vulkancompat.rt.world.RtMemory;
import io.github.andrexarlay.vulkancompat.rtx.raytracing.VulkanCapabilityChecker;
import io.github.andrexarlay.vulkancompat.shaderhost.IrisShaderHost;
import io.github.andrexarlay.vulkancompat.shaderhost.ShaderHostState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDevice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Game render bridge connecting Minecraft's frame loop to {@link IrisPostExecutor}.
 * <p>
 * Manages the singleton lifecycle of {@link IrisPostExecutor} (initialization on first call
 * with an active {@link VkDevice}, destruction and recreation on shaderpack change, and
 * {@link #teardown()}), timing counters for Iris uniforms, and executes the compiled
 * composite/final pass graph for the active {@link IrisShaderHost}.
 */
public final class IrisRenderBridge {

    private static final Logger LOGGER = LoggerFactory.getLogger("Bedray/IrisRenderBridge");

    private static IrisPostExecutor executor;
    private static String activePackName;

    // Frame timing tracking
    private static long lastFrameNano = 0L;
    private static float currentFrameTimeCounter = 0.0f;
    private static int currentFrameCounter = 0;
    private static float currentFrameTime = 0.01666667f;

    private IrisRenderBridge() {}

    /**
     * Shuts down and releases the active {@link IrisPostExecutor} instance.
     */
    public static synchronized void teardown() {
        if (executor != null) {
            try {
                executor.close();
            } catch (Throwable t) {
                LOGGER.warn("[IrisRenderBridge] Error closing IrisPostExecutor during teardown: {}", t.getMessage());
            }
            executor = null;
        }
        activePackName = null;
    }

    /**
     * Checks whether an {@link IrisPostExecutor} instance is currently initialized.
     */
    public static synchronized boolean isInitialized() {
        return executor != null;
    }

    /**
     * Returns the name of the currently active shaderpack managed by the executor, or null if none.
     */
    public static synchronized String activePackName() {
        return activePackName;
    }

    /**
     * Returns the active {@link IrisPostExecutor} singleton instance, or null if not yet initialized.
     */
    public static synchronized IrisPostExecutor getExecutor() {
        return executor;
    }

    /**
     * Testing hook to inject a mock or test {@link IrisPostExecutor} with a specific pack name.
     */
    public static synchronized void setExecutorForTesting(IrisPostExecutor testExec, String packName) {
        if (executor != null && executor != testExec) {
            try {
                executor.close();
            } catch (Throwable ignored) {}
        }
        executor = testExec;
        activePackName = packName;
    }

    /**
     * Testing hook to inject a mock or test {@link IrisPostExecutor}.
     */
    public static synchronized void setExecutorForTesting(IrisPostExecutor testExec) {
        setExecutorForTesting(testExec, testExec != null ? "test-pack" : null);
    }

    /**
     * Gets or creates the {@link IrisPostExecutor} singleton.
     * Recreates the executor if the shaderpack has changed.
     */
    public static synchronized IrisPostExecutor getOrCreateExecutor(VkCommandBuffer cmd, IrisPassGraph graph) {
        String packName = (graph != null) ? graph.packName() : null;

        // If shaderpack changed, recreate executor
        if (executor != null && activePackName != null && packName != null && !activePackName.equals(packName)) {
            LOGGER.info("[IrisRenderBridge] Shaderpack changed from '{}' to '{}', recreating IrisPostExecutor",
                activePackName, packName);
            teardown();
        }

        if (executor == null) {
            VkDevice device = null;
            var vd = VulkanCapabilityChecker.getVulkanDevice();
            if (vd != null) {
                device = vd.vkDevice();
            } else if (cmd != null) {
                try {
                    device = cmd.getDevice();
                } catch (Throwable ignored) {}
            }

            if (device != null) {
                RtMemory memory = new RtMemory(device);
                executor = new IrisPostExecutor(device, memory);
                LOGGER.info("[IrisRenderBridge] Initialized IrisPostExecutor on VkDevice for pack '{}'", packName);
            } else {
                executor = IrisPostExecutor.createForTesting();
                LOGGER.info("[IrisRenderBridge] Initialized test/fallback IrisPostExecutor for pack '{}'", packName);
            }
            activePackName = packName;
        }

        return executor;
    }

    /**
     * Renders Iris composite and final fullscreen passes into the engine color image.
     *
     * @param cmd              active command buffer
     * @param host             active {@link IrisShaderHost}
     * @param engineColorImage handle of the engine color image (source to copy from)
     * @param engineColorView  view of the engine color image (final render target)
     * @param depthView        view of the engine depth image (depthtex0 sampler)
     * @param width            render width
     * @param height           render height
     * @param camera           camera render state
     * @param frameTimeCounter time in seconds (wraps at 3600)
     * @param frameCounter     monotonic frame count (wraps at 720720)
     * @param near             camera near plane
     * @param far              camera far plane
     * @param frameTime        delta time of the last frame in seconds
     * @return {@code true} if rendering commands were recorded; {@code false} if any guard condition failed
     */
    public static boolean render(
        VkCommandBuffer cmd,
        IrisShaderHost host,
        long engineColorImage,
        long engineColorView,
        long depthView,
        int width,
        int height,
        CameraRenderState camera,
        float frameTimeCounter,
        int frameCounter,
        float near,
        float far,
        float frameTime
    ) {
        if (cmd == null) {
            return false;
        }
        if (host == null) {
            return false;
        }
        if (host.state() != ShaderHostState.READY) {
            return false;
        }
        IrisPassGraph graph = host.activeGraph();
        if (graph == null || graph.passes().isEmpty()) {
            return false;
        }
        if (engineColorImage == 0L || engineColorView == 0L || depthView == 0L) {
            return false;
        }
        if (width <= 0 || height <= 0) {
            return false;
        }

        IrisPostExecutor exec = getOrCreateExecutor(cmd, graph);
        if (exec == null) {
            return false;
        }

        try {
            exec.record(
                cmd,
                graph,
                engineColorImage,
                engineColorView,
                depthView,
                width,
                height,
                frameTimeCounter,
                frameCounter,
                near,
                far,
                frameTime
            );
            return true;
        } catch (Throwable t) {
            LOGGER.error("[IrisRenderBridge] Exception during IrisPostExecutor.record: {}", t.getMessage(), t);
            return false;
        }
    }

    /**
     * Updates frame timing counters for Iris uniforms.
     */
    public static synchronized void updateTiming() {
        long now = System.nanoTime();
        if (lastFrameNano > 0L) {
            float dt = (float) ((now - lastFrameNano) / 1.0e9);
            if (dt < 0.0001f) dt = 0.0001f;
            if (dt > 1.0f) dt = 1.0f;
            currentFrameTime = dt;
        } else {
            currentFrameTime = 1.0f / 60.0f;
        }
        lastFrameNano = now;
        currentFrameTimeCounter = (currentFrameTimeCounter + currentFrameTime) % 3600.0f;
        currentFrameCounter = (currentFrameCounter + 1) % 720720;
    }

    public static synchronized float frameTimeCounter() {
        return currentFrameTimeCounter;
    }

    public static synchronized int frameCounter() {
        return currentFrameCounter;
    }

    public static synchronized float frameTime() {
        return currentFrameTime;
    }

    public static synchronized void resetTiming() {
        lastFrameNano = 0L;
        currentFrameTimeCounter = 0.0f;
        currentFrameCounter = 0;
        currentFrameTime = 0.01666667f;
    }
}
