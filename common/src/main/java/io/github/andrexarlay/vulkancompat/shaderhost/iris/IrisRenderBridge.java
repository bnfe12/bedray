package io.github.andrexarlay.vulkancompat.shaderhost.iris;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.backend.vulkan.VulkanConst;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import io.github.andrexarlay.vulkancompat.rtx.WorldTargetManager;
import io.github.andrexarlay.vulkancompat.rtx.framegen.FrameGenRuntime;
import io.github.andrexarlay.vulkancompat.rtx.framegen.FrameGenSettings;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.TemporalUpscaler;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.VkBarriers;
import io.github.andrexarlay.vulkancompat.shaderhost.ShaderHostKind;
import io.github.andrexarlay.vulkancompat.shaderhost.ShaderHosts;
import io.github.andrexarlay.vulkancompat.shaderhost.glsl.IrisUniforms;
import io.github.andrexarlay.vulkancompat.rtx.frame.WorldFrame;
import io.github.andrexarlay.vulkancompat.rtx.frame.WorldFrames;
import io.github.andrexarlay.vulkancompat.rtx.frame.WorldFrameCapture;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkFormatProperties;
import org.lwjgl.vulkan.VkQueueFamilyProperties;

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
        if (owner != null) { retire(); return; }
        if (executor != null) {
            executor.close();
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
                LOGGER.warn("[IrisRenderBridge] Vulkan device unavailable; retaining the native image");
                return null;
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
        // Legacy raw-handle callers have no provenance/conversion or frame history contract.
        if (graph.passes().stream().anyMatch(pass -> !pass.allFrameUsed().isEmpty()
            || pass.allSamplerBindings().contains(IrisUniforms.BINDING_DEPTHTEX0))) return false;
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

    private static VulkanDevice owner;
    private static IrisPassGraph ownedGraph;
    private static IrisDepthConverter depthConverter;
    private static int ownedWidth, ownedHeight, ownedFormat;
    private static long ownedDepthView;
    private static WorldFrame.DepthEncoding ownedDepthEncoding;
    /** Target whose views the owned graph borrows; null means unknown, so any target closing retires. */
    private static RenderTarget ownedTarget;
    private static Object worldIdentity;
    private static boolean failed, cleanupBroken;
    private static String runtimeStatus = "Experimental post host disabled";

    public static String runtimeStatus() { return runtimeStatus; }

    /** Host frame boundary. A world transition retires resources even if no empty frame was rendered. */
    public static void onFrameBegin(Object world) {
        var selection = ShaderHosts.selection();
        if (!failed && selection != null && selection.requested() == ShaderHostKind.IRIS
            && selection.active() != ShaderHostKind.IRIS)
            runtimeStatus = selection.requestedStatus().detail();
        if (worldIdentity != world || world == null || !Boolean.getBoolean("bedray.iris.post")
            || ShaderHosts.active()
                != ShaderHostKind.IRIS) {
            retire();
            worldIdentity = world;
        }
    }

    public static void onDeviceClose(VulkanDevice device) {
        if (owner == device) retire();
        WorldFrames.invalidateDevice(device);
    }

    /**
     * Retire borrowed target descriptors before vanilla closes target views, not at the next frame.
     * Pooled and auxiliary targets close during normal play; they are not borrowed and must not retire the graph.
     */
    public static boolean onTargetClosing(RenderTarget target) {
        if (ownedTarget != null && ownedTarget != target) return true;
        var device = owner;
        boolean retired = retire();
        if (device != null) WorldFrames.invalidateDevice(device);
        return retired;
    }

    /** Compatibility wrapper. New producers must report their actual completed image, not their selected mode. */
    public static boolean renderFrame(RenderTarget target, CameraRenderState camera,
                                      boolean rasterDepthMatchesColor) {
        var renderer = io.github.andrexarlay.vulkancompat.rt.world.RtFrameRenderer.displayedThisFrame(target)
            ? WorldFrame.Renderer.HARDWARE_RT : rasterDepthMatchesColor ? WorldFrame.Renderer.RASTER : WorldFrame.Renderer.SYNTHETIC;
        return renderFrame(target, camera, renderer);
    }

    public static boolean renderFrame(RenderTarget target, CameraRenderState camera, WorldFrame.Renderer renderer) {
        if (failed || cleanupBroken || !Boolean.getBoolean("bedray.iris.post") || ShaderHosts.active() != ShaderHostKind.IRIS) return false;
        var device = VulkanCapabilityChecker.getVulkanDevice();
        if (device == null) return false;
        try {
            boolean rendered = renderFrame(WorldFrameCapture.capture(device, target, camera, renderer));
            ownedTarget = owner != null ? target : null;
            return rendered;
        }
        catch (IllegalArgumentException | IllegalStateException error) {
            runtimeStatus = "World frame unavailable: " + error.getMessage(); return false;
        }
    }

    /** Fullscreen seam for current-frame borrowed color/guides on the existing host device and queue. */
    public static boolean renderFrame(WorldFrame frame) {
        if (failed || cleanupBroken || !Boolean.getBoolean("bedray.iris.post")
            || ShaderHosts.active()
                != ShaderHostKind.IRIS) return false;
        IrisShaderHost host = ShaderHosts.iris();
        IrisPassGraph graph = host == null ? null : host.activeGraph();
        if (graph == null || frame == null) return false;
        var device = VulkanCapabilityChecker.getVulkanDevice();
        if (device == null || frame.deviceIdentity() != device || !WorldFrames.isCurrent(frame.stamp())) {
            runtimeStatus = "Iris rejected stale frame or foreign device"; return false;
        }
        if (frame.color().origin() != WorldFrame.Origin.BOTTOM_LEFT) {
            runtimeStatus = "Iris needs native GL-oriented color and guides"; return false;
        }
        try { WorldFrames.publish(frame); }
        catch (IllegalArgumentException | IllegalStateException error) { runtimeStatus = "Iris frame publication: " + error.getMessage(); return false; }
        var camera = frame.camera();
        var previousCamera = WorldFrames.previousCamera(frame);
        boolean readsDepth = graph.passes().stream().anyMatch(pass -> pass.allSamplerBindings().contains(
            IrisUniforms.BINDING_DEPTHTEX0));
        boolean readsCamera = readsDepth || graph.passes().stream().anyMatch(pass -> !pass.allFrameUsed().isEmpty()
            || pass.allPushUsed().contains("near") || pass.allPushUsed().contains("far"));
        if (readsCamera && camera == null) {
            runtimeStatus = "Iris is waiting for current-frame camera/projection"; return false;
        }
        if (previousCamera == null && graph.passes().stream().flatMap(pass -> pass.allFrameUsed().stream())
            .anyMatch(name -> name.startsWith("gbufferPrevious") || name.equals("previousCameraPosition"))) {
            runtimeStatus = "Iris is waiting for continuous previous-frame camera data"; return false;
        }
        if (readsDepth && frame.depth() == null) {
            retire(); runtimeStatus="No matching depth guide from " + frame.renderer();
            return false;
        }
        var encoding = readsDepth ? frame.depth().encoding() : null;
        if (readsDepth && !supportedDepth(frame.depth(), camera)) {
            runtimeStatus = "Unsupported depth format/projection for " + encoding; return false;
        }
        // Until temporal guides include pack-specific history/depth, do not combine incompatible pipelines.
        if (TemporalUpscaler.isActive()
            || FrameGenRuntime.isActive()
            || FrameGenSettings.mode()
                != FrameGenSettings.Mode.OFF) {
            retire(); runtimeStatus = "Disable temporal upscaling and frame generation for Iris post"; return false;
        }
        long colorImage=frame.color().image(), colorView=frame.color().view();
        // The executor fills unused depth bindings from its owned sampled color image.
        long depthView=readsDepth ? frame.depth().image().view() : 0;
        int format=frame.color().format(), width=frame.color().width(), height=frame.color().height();
        boolean convertsDepth = readsDepth && encoding != WorldFrame.DepthEncoding.OPENGL_WINDOW_Z;
        if (owner != null && owner != device) {
            failed=true; runtimeStatus="Device changed without Iris cleanup; restart required";
            ShaderHosts.noteIrisFailure(runtimeStatus); return false;
        }
        if (owner != null && (ownedGraph != graph || ownedWidth != width || ownedHeight != height
            || ownedFormat != format || ownedDepthView != depthView || ownedDepthEncoding != encoding)) {
            if (!retire()) return false;
        }
        try {
            if (executor == null) {
                owner=device; ownedGraph=graph; ownedWidth=width; ownedHeight=height;
                ownedFormat=format; ownedDepthView=depthView; ownedDepthEncoding=encoding;
                var capabilities=device.vkDevice().getCapabilities();
                if (capabilities.vkCmdBeginRendering==0 && capabilities.vkCmdBeginRenderingKHR==0)
                    throw new IllegalStateException("Dynamic rendering is unavailable");
                try (var stack=MemoryStack.stackPush()) {
                    if (convertsDepth) {
                        var count=stack.mallocInt(1);
                        VK10.vkGetPhysicalDeviceQueueFamilyProperties(device.vkDevice().getPhysicalDevice(),count,null);
                        var families=VkQueueFamilyProperties.calloc(count.get(0),stack);
                        VK10.vkGetPhysicalDeviceQueueFamilyProperties(device.vkDevice().getPhysicalDevice(),count,families);
                        int family=device.graphicsQueue().queueFamilyIndex();
                        if (family<0 || family>=families.capacity()
                            || (families.get(family).queueFlags() & VK10.VK_QUEUE_COMPUTE_BIT)==0)
                            throw new IllegalStateException("Host graphics queue cannot record depth conversion compute");
                    }
                    var properties=VkFormatProperties.calloc(stack);
                    VK10.vkGetPhysicalDeviceFormatProperties(device.vkDevice().getPhysicalDevice(),format,properties);
                    int required=VK10.VK_FORMAT_FEATURE_COLOR_ATTACHMENT_BIT | VK10.VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT
                        | VK10.VK_FORMAT_FEATURE_SAMPLED_IMAGE_FILTER_LINEAR_BIT;
                    if ((properties.optimalTilingFeatures() & required)!=required) throw new IllegalStateException("Iris target format unsupported");
                    if (readsDepth) {
                        VK10.vkGetPhysicalDeviceFormatProperties(device.vkDevice().getPhysicalDevice(), frame.depth().image().format(), properties);
                        if ((properties.optimalTilingFeatures() & VK10.VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT) == 0)
                            throw new IllegalStateException("Depth guide format cannot be sampled");
                    }
                }
                executor=new IrisPostExecutor(device.vkDevice(),new RtMemory(device.vkDevice()),format);
                activePackName=graph.packName();
                executor.prepare(graph,width,height);
                if(convertsDepth)depthConverter=new IrisDepthConverter(device.vkDevice(),width,height,depthView);
            }
        } catch (RuntimeException | LinkageError error) {
            failed=true;runtimeStatus="Iris preparation failed: "+error.getMessage();
            LOGGER.error("[IrisRenderBridge] {}",runtimeStatus,error);
            ShaderHosts.noteIrisFailure(runtimeStatus);retire();return false;
        }
        updateTiming();
        var encoder=device.createCommandEncoder();
        var command=encoder.allocateAndBeginTransientCommandBuffer();
        boolean recorded=false;
        try {
            VkBarriers.fullMemoryBarrier(command);
            float near=camera == null ? 0.05f : camera.near(), far=camera == null ? 256f : camera.far();
            if(depthConverter!=null)depthConverter.record(command,camera,encoding);
            executor.record(command,graph,colorImage,colorView,depthConverter!=null ? depthConverter.view() : depthView,width,height,
                frameTimeCounter(),frameCounter(),near,far,frameTime(),camera,previousCamera);
            recorded=true;
        } catch (RuntimeException | LinkageError error) {
            failed=true;runtimeStatus="Iris recording failed: "+error.getMessage();
            LOGGER.error("[IrisRenderBridge] {}",runtimeStatus,error);
        } finally {
            VkBarriers.fullMemoryBarrier(command);
            int result=VK10.vkEndCommandBuffer(command);
            if(result==VK10.VK_SUCCESS)encoder.execute(command);
            else {failed=true;cleanupBroken=true;recorded=false;runtimeStatus="Iris command finalization failed; restart required";}
        }
        if(recorded)runtimeStatus="Iris post commands recorded: "+graph.packName()+" (unverified in-game)";
        else if(failed)ShaderHosts.noteIrisFailure(runtimeStatus);
        return recorded;
    }

    private static boolean supportedDepth(WorldFrame.Depth depth, WorldFrame.Camera camera) {
        int format = depth.image().format();
        boolean floating = format == VK10.VK_FORMAT_R32_SFLOAT;
        boolean deviceDepth = format == VK10.VK_FORMAT_D32_SFLOAT || format == VK10.VK_FORMAT_D16_UNORM
            || format == VK10.VK_FORMAT_X8_D24_UNORM_PACK32 || format == VK10.VK_FORMAT_D24_UNORM_S8_UINT
            || format == VK10.VK_FORMAT_D32_SFLOAT_S8_UINT;
        if (depth.encoding() == WorldFrame.DepthEncoding.REVERSE_DEVICE_Z || depth.encoding() == WorldFrame.DepthEncoding.OPENGL_WINDOW_Z)
            return floating || deviceDepth;
        if (!floating || camera == null) return false;
        var p = camera.projection(); var v = camera.modelView();
        float expected22 = -(camera.far()+camera.near())/(camera.far()-camera.near());
        float expected32 = -2*camera.far()*camera.near()/(camera.far()-camera.near());
        return Math.abs(p.m22()-expected22) < 1e-4f && Math.abs(p.m32()-expected32) < 1e-4f
            && Math.abs(p.m02()) < 1e-5f && Math.abs(p.m12()) < 1e-5f
            && Math.abs(p.m03()) < 1e-5f && Math.abs(p.m13()) < 1e-5f
            && Math.abs(p.m23()+1) < 1e-5f && Math.abs(p.m33()) < 1e-5f
            && Math.abs(v.m03()) < 1e-5f && Math.abs(v.m13()) < 1e-5f && Math.abs(v.m23()) < 1e-5f && Math.abs(v.m33()-1) < 1e-5f;
    }

    private static boolean retire() {
        if (cleanupBroken) return false;
        if (owner == null) return true;
        try {
            owner.createCommandEncoder().submit(); // submit queued references before any vkDeviceWaitIdle/destruction
            int waitResult = VK10.vkDeviceWaitIdle(owner.vkDevice());
            if (waitResult != VK10.VK_SUCCESS) throw new IllegalStateException("Iris retirement wait failed: " + waitResult);
            if(depthConverter!=null) {depthConverter.close();depthConverter=null;}
            if(executor!=null) {executor.close();executor=null;}
            activePackName=null; owner=null;ownedGraph=null;ownedDepthView=0;ownedDepthEncoding=null;ownedTarget=null;
            resetTiming();
            return true;
        } catch (RuntimeException | LinkageError error) {
            cleanupBroken=true;failed=true;runtimeStatus="Iris cleanup failed; restart required";
            LOGGER.error("[IrisRenderBridge] {}",runtimeStatus,error);return false;
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
