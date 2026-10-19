package io.github.andrexarlay.vulkancompat.rtx.framegen.fsr3;

import com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import io.github.andrexarlay.vulkancompat.rtx.WorldTargetManager;
import io.github.andrexarlay.vulkancompat.rtx.framegen.FrameGenGpu;
import io.github.andrexarlay.vulkancompat.rtx.framegen.FrameGenProvider;
import io.github.andrexarlay.vulkancompat.rtx.framegen.FrameGenRuntime;
import io.github.andrexarlay.vulkancompat.rtx.framegen.FrameGenSettings;
import io.github.andrexarlay.vulkancompat.rtx.framegen.FrameGenSurfaceInfo;
import io.github.andrexarlay.vulkancompat.rtx.raytracing.VulkanCapabilityChecker;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.CameraJitterManager;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.VkBarriers;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.fsr.FsrCamera;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.fsr.FsrLibrary;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.KHRSwapchain;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkPresentInfoKHR;
import org.lwjgl.vulkan.VkQueue;
import org.lwjgl.vulkan.VkSwapchainCreateInfoKHR;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * AMD FSR 3 frame generation (ffx-api of {@code amd_fidelityfx_vk.dll}, FidelityFX SDK v1.1.4), the second implementation next to NVIDIA
 * DLSS-G. Used only when {@link FrameGenProvider#amd()}. Contract: docs/framegen/FSR3_FINDINGS.md. Render thread only.
 * <ul>
 * <li><b>Swapchain</b>: while this provider is active the engine's swapchain calls (create, destroy, get images, acquire, present of
 *     {@code VulkanGpuSurface}) are redirected here by {@code MixinVulkanGpuSurfaceFsr3}; the first create makes the frame interpolation swapchain
 *     context (it also creates the swapchain), later ones go through the replacement functions. The wrapper stays alive when generation is
 *     off (one extra copy per present), so Off and 2x switch live.</li>
 * <li><b>Per frame</b> (same hooks as DLSS-G): hudless copy + flipped depth/motion vectors ({@link FrameGenGpu}, shared with DLSS-G), then
 *     {@code ffxConfigure} (enable, hudless image, frame id) and {@code ffxDispatch(Prepare)} recorded into the same command buffer.
 *     The interpolation itself runs inside the replaced vkQueuePresentKHR (frame generation callback).</li>
 * </ul>
 * A failure is logged once as {@code [VKB-FG] fsr3=FAILED stage=..}; the swapchain then keeps presenting every frame unchanged.
 */
public final class Fsr3FrameGen {
    private static final Logger LOGGER = LoggerFactory.getLogger("Bedray/Fsr3FrameGen");
    public static final String PROP_DLL = "vkrtx.fsr3.dll";
    private static final int WARMUP_FRAMES = 3;
    private static final long REPORT_NS = 5_000_000_000L;
    private static final int RATIO_WINDOW = 30;

    private static FfxFgNative nat;
    private static String dllNote = "not_loaded";
    private static FfxFgNative.Context sc;
    private static long scHandle;
    private static FfxFgNative.Replacement repl;
    private static long lastPresentCountFn;
    private static boolean swapchainFailed;
    private static String failure = "";
    private static boolean failed;

    private static FfxFgNative.Context fg;
    private static int fgW, fgH, fgFormat;
    private static FrameGenGpu gpu;

    private static long frameId;
    private static long lastFrameNs;
    private static float frameMs = 16.6f;
    private static boolean worldReady;
    private static String worldFail;
    private static int slot;
    private static int validFrames;
    private static boolean modeOn;
    private static long configuredFrame = -1;
    private static long lastSurfaceGeneration = -1;
    private static boolean multiClampLogged;
    private static boolean userEnabled = true;
    private static String idleReason = "not_in_world";
    private static int winW, winH, renderW, renderH;

    // evidence
    private static long renderedTotal;
    private static long presentedTotal;
    private static long doubledFrames;
    private static int genStreak;
    private static long ratioRendered0;
    private static long ratioPresented0 = -1;
    private static long windowStartNs;
    private static long windowRendered;
    private static long windowPresented0;
    private static double fpsRendered;
    private static double fpsPresented;
    private static boolean windowFocused = true;

    private Fsr3FrameGen() {}

    public static boolean selected() {
        return FrameGenProvider.amd();
    }

    // ------------------------------------------------------------------------------------------------ library / swapchain

    private static Path resolveDll() {
        FsrLibrary.Library lib = FsrLibrary.library();
        if (lib.usable()) {
            dllNote = lib.reason();
            return lib.dll();
        }
        String p = System.getProperty(PROP_DLL);
        if (p != null && !p.isBlank() && Files.isRegularFile(Paths.get(p))) {
            dllNote = "developer override -D" + PROP_DLL + " (not hash-checked)";
            return Paths.get(p);
        }
        dllNote = lib.reason();
        return null;
    }

    private static boolean ensureNative(VulkanDevice device) {
        if (nat != null) {
            return true;
        }
        if (swapchainFailed) {
            return false;
        }
        Path dll = resolveDll();
        if (dll == null) {
            swapchainFail("library", dllNote);
            return false;
        }
        if (!Fsr3Queues.fetch(device.vkDevice())) {
            swapchainFail("queues", Fsr3Queues.note());
            return false;
        }
        try {
            nat = FfxFgNative.load(dll);
        } catch (RuntimeException | Error e) {
            LOGGER.error("[VKB-FG] fsr3 load failed", e);
            swapchainFail("load", e.toString());
            return false;
        }
        if (VkSwapchainCreateInfoKHR.SIZEOF != FfxFgNative.SWAPCHAIN_CREATE_INFO_SIZE) {
            swapchainFail("layout", "VkSwapchainCreateInfoKHR is " + VkSwapchainCreateInfoKHR.SIZEOF + " bytes");
            nat = null;
            return false;
        }
        return true;
    }

    private static void swapchainFail(String stage, String detail) {
        swapchainFailed = true;
        failed = true;
        failure = stage + ": " + detail;
        LOGGER.error("[VKB-FG] fsr3=FAILED stage={} {} (the swapchain stays the engine's own)", stage, detail);
    }

    private static boolean swapchainActive() {
        return sc != null && repl != null;
    }

    /** Redirect of {@code KHRSwapchain.vkCreateSwapchainKHR} in VulkanGpuSurface.configure. */
    public static int createSwapchain(VulkanDevice device, VkDevice vk, VkSwapchainCreateInfoKHR ci, LongBuffer out) {
        if (!FrameGenProvider.amd() || swapchainFailed || !ensureNative(device)) {
            return KHRSwapchain.vkCreateSwapchainKHR(vk, ci, null, out);
        }
        try {
            if (sc == null) {
                FfxFgNative.Created c = nat.createSwapchainContext(vk.getPhysicalDevice().address(), vk.address(), 0L, ci.address(),
                    device.graphicsQueue().vkQueue().address(), device.graphicsQueue().queueFamilyIndex(),
                    Fsr3Queues.presentQueue().address(), Fsr3Queues.family(), Fsr3Queues.acquireQueue().address(), Fsr3Queues.family());
                if (c.result() != 0 || c.context().handle() == 0L || c.swapchain() == 0L) {
                    swapchainFail("create_swapchain_context", "ffxCreateContext returned " + c.result());
                    return KHRSwapchain.vkCreateSwapchainKHR(vk, ci, null, out);
                }
                sc = c.context();
                scHandle = c.context().handle();
                repl = nat.queryReplacement(sc);
                lastPresentCountFn = repl.getLastPresentCount();
                scSwapchain = c.swapchain();
                out.put(out.position(), c.swapchain());
                LOGGER.info("[VKB-FG] [VKB-AUTOTEST] fsr3=swapchain_context_created result=0 extent={}x{} present_mode={} queues=game(family {}) present/acquire(family {}) dll={} ({})",
                    ci.imageExtent().width(), ci.imageExtent().height(), ci.presentMode(), device.graphicsQueue().queueFamilyIndex(),
                    Fsr3Queues.family(), nat.libraryPath(), dllNote);
                return VK10.VK_SUCCESS;
            }
            int r = nat.createSwapchain(repl.createSwapchain(), vk.address(), ci.address(), MemoryUtil.memAddress(out), scHandle);
            if (r == VK10.VK_SUCCESS) {
                scSwapchain = out.get(out.position());
            }
            LOGGER.info("[VKB-FG] fsr3 swapchain re-created through the replacement function result={} extent={}x{}", r,
                ci.imageExtent().width(), ci.imageExtent().height());
            return r;
        } catch (RuntimeException | Error e) {
            LOGGER.error("[VKB-FG] fsr3 swapchain create threw", e);
            swapchainFail("create_swapchain", e.toString());
            return VK10.VK_ERROR_INITIALIZATION_FAILED;
        }
    }

    /** Redirect of {@code KHRSwapchain.vkDestroySwapchainKHR} (VulkanGpuSurface.destroySwapchain). */
    public static void destroySwapchain(VkDevice vk, long swapchain) {
        if (swapchainActive() && swapchain != 0L) {
            shutdownFgContext("swapchain_destroy");
            nat.destroySwapchain(repl.destroySwapchain(), vk.address(), swapchain, scHandle);
            scSwapchain = 0L;
            return;
        }
        KHRSwapchain.vkDestroySwapchainKHR(vk, swapchain, null);
    }

    public static int getSwapchainImages(VkDevice vk, long swapchain, IntBuffer count, LongBuffer images) {
        if (swapchainActive()) {
            return nat.getSwapchainImages(repl.getSwapchainImages(), vk.address(), swapchain, MemoryUtil.memAddress(count),
                MemoryUtil.memAddressSafe(images));
        }
        return KHRSwapchain.vkGetSwapchainImagesKHR(vk, swapchain, count, images);
    }

    public static int acquireNextImage(VkDevice vk, long swapchain, long timeout, long semaphore, long fence, IntBuffer index) {
        if (swapchainActive()) {
            return nat.acquireNextImage(repl.acquireNextImage(), vk.address(), swapchain, timeout, semaphore, fence, MemoryUtil.memAddress(index));
        }
        return KHRSwapchain.vkAcquireNextImageKHR(vk, swapchain, timeout, semaphore, fence, index);
    }

    public static int queuePresent(VkQueue queue, VkPresentInfoKHR info) {
        if (swapchainActive()) {
            return nat.queuePresent(repl.queuePresent(), queue.address(), info.address());
        }
        return KHRSwapchain.vkQueuePresentKHR(queue, info);
    }

    // ------------------------------------------------------------------------------------------------ FG context

    private static boolean ensureFg(VulkanDevice device, int w, int h, int format, FsrCamera cam) {
        if (fg != null && fgW == w && fgH == h && fgFormat == format) {
            return true;
        }
        shutdownFgContext("recreate");
        int ffxFormat = format == 44 ? FfxFgNative.FORMAT_B8G8R8A8_UNORM : FfxFgNative.FORMAT_R8G8B8A8_UNORM;
        int flags = FfxFgNative.FG_DEPTH_INVERTED | (cam.infiniteFar() ? FfxFgNative.FG_DEPTH_INFINITE : 0);
        long gdpa = device.vkDevice().getCapabilities().vkGetDeviceProcAddr;
        long t0 = System.nanoTime();
        FfxFgNative.FgCreated c = nat.createFgContext(flags, w, h, w, h, ffxFormat, ffxFormat, device.vkDevice().address(),
            device.vkDevice().getPhysicalDevice().address(), gdpa);
        long ms = (System.nanoTime() - t0) / 1_000_000L;
        if (c.result() != 0 || c.context().handle() == 0L) {
            fail("create_fg_context", "ffxCreateContext(frame generation) returned " + c.result());
            return false;
        }
        fg = c.context();
        fgW = w;
        fgH = h;
        fgFormat = format;
        LOGGER.info("[VKB-FG] [VKB-AUTOTEST] fsr3=fg_context_created result=0 display={}x{} format={} flags=0x{} camera={} ms={}", w, h, ffxFormat,
            Integer.toHexString(flags), cam, ms);
        return true;
    }

    /** Turns generation off, waits for the swapchain's presents and destroys the FG context (before a swapchain change / shutdown). */
    private static void shutdownFgContext(String reason) {
        if (fg == null || nat == null) {
            return;
        }
        try {
            // ffxConfigure uses the VkSwapchainKHR, not the ffxContext handle. The SDK's wait
            // dispatch dereferences fiSwapChain even after its replacement destroy has cleared it.
            Fsr3Teardown.releaseGeneration(sc == null ? 0L : scSwapchain,
                swapchain -> nat.configureFg(fg, swapchain, false, FfxFgNative.Image.NONE, frameId),
                () -> nat.waitForPresents(sc), () -> nat.destroy(fg));
            LOGGER.info("[VKB-FG] fsr3 fg context destroyed reason={}", reason);
        } catch (RuntimeException | Error e) {
            LOGGER.error("[VKB-FG] fsr3 fg context destroy failed", e);
        }
        fg = null;
        modeOn = false;
        genStreak = 0;
        ratioPresented0 = -1;
    }

    private static void fail(String stage, String detail) {
        failed = true;
        failure = stage + ": " + detail;
        modeOn = false;
        LOGGER.error("[VKB-FG] fsr3=FAILED stage={} {}", stage, detail);
    }

    private static boolean usable() {
        return FrameGenProvider.amd() && !failed && swapchainActive();
    }

    // ------------------------------------------------------------------------------------------------ frame hooks

    public static void onFrameHead() {
        if (!FrameGenProvider.amd()) {
            return;
        }
        frameId++;
        worldReady = false;
        worldFail = null;
        long now = System.nanoTime();
        if (lastFrameNs != 0L) {
            frameMs = Math.max(0.1f, Math.min(1000f, (now - lastFrameNs) / 1.0e6f));
        }
        lastFrameNs = now;
    }

    /** After the world (and the temporal upscaler) is in the main target, before the GUI: inputs for the interpolation. */
    public static void onWorldReady(GameRenderer renderer) {
        if (!usable()) {
            return;
        }
        try {
            var world = WorldTargetManager.getWorldTarget();
            var depthCopy = WorldTargetManager.getDepthCopy();
            var mvTarget = WorldTargetManager.getMotionVectorsTarget();
            var main = renderer.mainRenderTarget();
            if (world == null || depthCopy == null || mvTarget == null || main == null) {
                worldFail = "missing_targets";
                return;
            }
            winW = main.width;
            winH = main.height;
            renderW = depthCopy.width;
            renderH = depthCopy.height;
            int fmt = FrameGenSurfaceInfo.format();
            if (fmt == 0 || FrameGenSurfaceInfo.width() != winW || FrameGenSurfaceInfo.height() != winH) {
                worldFail = "swapchain_reconfiguring";
                return;
            }
            VulkanDevice device = VulkanCapabilityChecker.getVulkanDevice();
            if (device == null) {
                return;
            }
            Matrix4f pc = WorldTargetManager.getCurrProjMatrix();
            Matrix4f rc = WorldTargetManager.getCurrViewRotationMatrix();
            Vec3 cc = WorldTargetManager.getCurrCameraPos();
            if (pc == null || rc == null || cc == null) {
                worldFail = "no_camera";
                return;
            }
            windowFocused = Minecraft.getInstance().getWindow().isFocused();
            FrameGenSettings.Mode wanted = FrameGenSettings.mode();
            boolean allowed = userEnabled && wanted != FrameGenSettings.Mode.OFF;
            if (!allowed) {
                if (modeOn) {
                    modeOn = false;
                    if (fg != null && scSwapchain != 0L) {
                        nat.configureFg(fg, scSwapchain, false, FfxFgNative.Image.NONE, frameId);
                    }
                    configuredFrame = frameId;
                    LOGGER.info("[VKB-FG] fsr3 mode=OFF frame={}", frameId);
                }
                worldReady = false;
                return;
            }
            if (scSwapchain == 0L) {
                worldFail = "no_swapchain";
                return;
            }
            if (wanted.generated() > 1 && !multiClampLogged) {
                multiClampLogged = true;
                LOGGER.warn("[VKB-FG] requested {} but FSR 3 frame generation produces one generated frame per rendered frame (2x), using 2x", wanted.label());
            }
            long surfaceGen = FrameGenSurfaceInfo.generation();
            boolean surfaceChanged = surfaceGen != lastSurfaceGeneration;
            lastSurfaceGeneration = surfaceGen;
            validFrames = surfaceChanged ? 0 : validFrames + 1;
            boolean want = validFrames > WARMUP_FRAMES;

            FsrCamera cam = FsrCamera.fromProjection(pc);
            if (gpu == null) {
                gpu = new FrameGenGpu(device.vkDevice());
            }
            gpu.ensure(fmt, winW, winH, renderW, renderH);
            if (!ensureFg(device, winW, winH, fmt, cam)) {
                return;
            }
            long mainImg = WorldTargetManager.extractVkImage(main);
            long depthView = WorldTargetManager.extractVkImageView(depthCopy.getDepthTextureView());
            long mvView = WorldTargetManager.extractVkImageView(mvTarget.getColorTextureView());
            if (mainImg == 0L || depthView == 0L || mvView == 0L) {
                throw new IllegalStateException("missing Vulkan handle main=" + mainImg + " depthView=" + depthView + " mvView=" + mvView);
            }
            slot = (int) (frameId % FrameGenGpu.SETS);
            int ffxFormat = fmt == 44 ? FfxFgNative.FORMAT_B8G8R8A8_UNORM : FfxFgNative.FORMAT_R8G8B8A8_UNORM;

            FfxFgNative.Image hudless = new FfxFgNative.Image(gpu.hudless(slot).image(), ffxFormat, winW, winH,
                FfxFgNative.USAGE_READ_ONLY, FfxFgNative.STATE_COMMON);
            FfxFgNative.Image depth = new FfxFgNative.Image(gpu.depth(slot).image(), FfxFgNative.FORMAT_R32_FLOAT, renderW, renderH,
                FfxFgNative.USAGE_READ_ONLY, FfxFgNative.STATE_COMMON);
            FfxFgNative.Image mv = new FfxFgNative.Image(gpu.mv(slot).image(), FfxFgNative.FORMAT_R16G16_FLOAT, renderW, renderH,
                FfxFgNative.USAGE_READ_ONLY, FfxFgNative.STATE_COMMON);

            VulkanCommandEncoder encoder = device.createCommandEncoder();
            VkCommandBuffer cmd = encoder.allocateAndBeginTransientCommandBuffer();
            VkBarriers.fullMemoryBarrier(cmd);
            gpu.recordInitialTransitions(cmd);
            gpu.record(cmd, slot, mainImg, depthView, mvView);
            VkBarriers.fullMemoryBarrier(cmd);

            int r = nat.configureFg(fg, scSwapchain, want, hudless, frameId);
            if (r != 0) {
                throw new IllegalStateException("ffxConfigure(frame generation) returned " + r);
            }
            configuredFrame = frameId;
            float near;
            float far;
            if (cam.infiniteFar()) {
                far = cam.near();
                near = Float.MAX_VALUE;
            } else {
                far = cam.near();
                near = cam.far();
            }
            float[] vectors = {
                (float) cc.x, (float) cc.y, (float) cc.z,
                rc.m01(), rc.m11(), rc.m21(),
                rc.m00(), rc.m10(), rc.m20(),
                -rc.m02(), -rc.m12(), -rc.m22()
            };
            // ffx camera vectors: position (3), up (3), right (3), forward (3)
            float[] camVec = {vectors[0], vectors[1], vectors[2], vectors[3], vectors[4], vectors[5], vectors[6], vectors[7], vectors[8],
                vectors[9], vectors[10], vectors[11]};
            r = nat.dispatchPrepare(fg, new FfxFgNative.PrepareParams(cmd.address(), depth, mv, renderW, renderH,
                CameraJitterManager.getCurrentPixelJitterX(), CameraJitterManager.getCurrentPixelJitterY(), 1.0f, 1.0f, frameMs,
                near, far, cam.fovY(), camVec, frameId));
            if (r != 0) {
                throw new IllegalStateException("ffxDispatch(frame generation prepare) returned " + r);
            }
            VkBarriers.fullMemoryBarrier(cmd);
            int end = VK10.vkEndCommandBuffer(cmd);
            if (end != VK10.VK_SUCCESS) {
                throw new IllegalStateException("vkEndCommandBuffer failed: " + end);
            }
            encoder.execute(cmd);
            worldReady = true;
            if (want != modeOn) {
                LOGGER.info("[VKB-FG] fsr3 mode={} multiplier=2x (was {}) frame={} output={}x{} render={}x{} back_buffers={} focused={}",
                    want ? "ON" : "OFF", modeOn ? "ON" : "OFF", frameId, winW, winH, renderW, renderH, FrameGenSurfaceInfo.imageCount(), windowFocused);
                modeOn = want;
                genStreak = 0;
                ratioPresented0 = -1;
            }
        } catch (Throwable t) {
            LOGGER.error("[VKB-FG] fsr3 world_ready threw", t);
            fail("world_ready", t.toString());
        }
    }

    public static void onAfterRender() {
        if (!usable()) {
            return;
        }
        idleReason = worldReady ? "idle" : worldFail != null ? worldFail : "not_in_world";
        if (worldReady || fg == null || configuredFrame == frameId) {
            return;
        }
        // no world this frame (menu, loading): generation off, nothing tagged
        try {
            int r = scSwapchain == 0L ? 0 : nat.configureFg(fg, scSwapchain, false, FfxFgNative.Image.NONE, frameId);
            configuredFrame = frameId;
            validFrames = 0;
            if (modeOn) {
                modeOn = false;
                genStreak = 0;
                ratioPresented0 = -1;
                LOGGER.info("[VKB-FG] fsr3 mode=OFF reason={} frame={} configure_result={}", idleReason, frameId, r);
            }
        } catch (Throwable t) {
            fail("after_render", t.toString());
        }
    }

    public static void onPresentEnd() {
        if (!usable()) {
            return;
        }
        try {
            long now = System.nanoTime();
            if (windowStartNs == 0L) {
                windowStartNs = now;
                windowPresented0 = presentCount();
            }
            if (modeOn) {
                renderedTotal++;
                windowRendered++;
                long pc = presentCount();
                if (ratioPresented0 < 0) {
                    ratioPresented0 = pc;
                    ratioRendered0 = renderedTotal;
                } else if (renderedTotal - ratioRendered0 >= RATIO_WINDOW) {
                    double ratio = (double) (pc - ratioPresented0) / (renderedTotal - ratioRendered0);
                    if (ratio >= 1.8) {
                        genStreak += RATIO_WINDOW;
                        doubledFrames += RATIO_WINDOW;
                    } else {
                        genStreak = 0;
                    }
                    ratioPresented0 = pc;
                    ratioRendered0 = renderedTotal;
                }
                presentedTotal = pc - presentBase;
            } else {
                presentBase = presentCount() - presentedTotal;
            }
            long dt = now - windowStartNs;
            if (dt >= REPORT_NS) {
                double sec = dt / 1e9;
                long pc = presentCount();
                fpsRendered = windowRendered / sec;
                fpsPresented = modeOn ? (pc - windowPresented0) / sec : 0.0;
                LOGGER.info("[VKB-FG] [VKB-AUTOTEST] framegen provider=amd mode={} multiplier={}x rendered_frames={} presented_frames={} window_s={} fps_rendered={} fps_presented={} total_rendered={} total_presented={} status=0x0",
                    modeOn ? "ON" : "OFF", modeOn ? 2 : 1, windowRendered, modeOn ? pc - windowPresented0 : 0,
                    String.format(java.util.Locale.ROOT, "%.2f", sec), String.format(java.util.Locale.ROOT, "%.1f", fpsRendered),
                    String.format(java.util.Locale.ROOT, "%.1f", fpsPresented), renderedTotal, presentedTotal);
                windowStartNs = now;
                windowRendered = 0;
                windowPresented0 = pc;
            }
        } catch (Throwable t) {
            fail("present_end", t.toString());
        }
    }

    private static long presentBase;

    /** Real swapchain presents since creation (rendered + generated), {@code getLastPresentCountFFXAPI} of the replacement functions. */
    private static long presentCount() {
        return scSwapchain == 0L ? 0L : nat.lastPresentCount(lastPresentCountFn, scSwapchain);
    }

    private static long scSwapchain;

    // ------------------------------------------------------------------------------------------------ lifecycle

    public static void onSurfaceReconfigureBegin() {
        if (!FrameGenProvider.amd()) {
            return;
        }
        shutdownFgContext("swapchain_reconfigure");
        validFrames = 0;
        if (gpu != null) {
            gpu.close();
            gpu = null;
        }
        LOGGER.info("[VKB-FG] fsr3 mode=OFF reason=swapchain_reconfigure");
    }

    public static void onSurfaceClose() {
        if (!FrameGenProvider.amd()) {
            return;
        }
        shutdownFgContext("surface_close");
        if (gpu != null) {
            gpu.close();
            gpu = null;
        }
        LOGGER.info("[VKB-FG] teardown fsr3 surface_close rendered_total={} presented_total={} doubled_frames={}", renderedTotal, presentedTotal, doubledFrames);
    }

    public static void onDeviceClose() {
        if (!FrameGenProvider.amd() || nat == null) {
            return;
        }
        shutdownFgContext("device_close");
        if (sc != null) {
            try {
                // Surface destruction normally already destroyed the wrapped swapchain. Waiting
                // again would pass the SDK's now-null fiSwapChain into ffxWaitForPresents.
                Fsr3Teardown.releaseSwapchainContext(scSwapchain, () -> nat.waitForPresents(sc), () -> {
                    int r = nat.destroy(sc);
                    LOGGER.info("[VKB-FG] teardown fsr3 swapchain context destroyed result={}", r);
                });
            } catch (RuntimeException | Error e) {
                LOGGER.error("[VKB-FG] fsr3 swapchain context destroy failed", e);
            }
            sc = null;
            repl = null;
        }
    }

    // ------------------------------------------------------------------------------------------------ status

    public static void setUserEnabled(boolean enabled) {
        userEnabled = enabled;
    }

    public static boolean failed() {
        return failed;
    }

    public static String failure() {
        return failure;
    }

    public static String dllNote() {
        return dllNote;
    }

    public static boolean ready() {
        return usable();
    }

    public static long renderedFrames() {
        return renderedTotal;
    }

    public static long presentedFrames() {
        return presentedTotal;
    }

    public static FrameGenRuntime.Status status() {
        if (failed) {
            return new FrameGenRuntime.Status(FrameGenRuntime.State.FAILED, failure, renderedTotal, presentedTotal, doubledFrames);
        }
        if (!swapchainActive()) {
            return new FrameGenRuntime.Status(FrameGenRuntime.State.UNAVAILABLE, "swapchain_not_wrapped", 0, 0, 0);
        }
        if (modeOn) {
            boolean generating = genStreak >= FrameGenRuntime.GEN_STREAK_FOR_ACTIVE;
            return new FrameGenRuntime.Status(generating ? FrameGenRuntime.State.ACTIVE : FrameGenRuntime.State.ENGAGING,
                generating ? "generating" : "presents_not_doubled_yet", renderedTotal, presentedTotal, doubledFrames);
        }
        return new FrameGenRuntime.Status(FrameGenRuntime.State.READY,
            FrameGenSettings.mode() == FrameGenSettings.Mode.OFF ? "mode_off" : !userEnabled ? "user_off" : idleReason,
            renderedTotal, presentedTotal, doubledFrames);
    }

    public static FrameGenRuntime.Snapshot snapshot() {
        return new FrameGenRuntime.Snapshot(status(), modeOn ? 2 : 1, 2, fpsRendered, fpsPresented, -1L, -1L,
            nat != null ? "FSR 3 loaded" : "not loaded", "AMD FSR 3 frame generation");
    }

    public static FrameGenRuntime.Availability availability() {
        if (failed) {
            return FrameGenRuntime.Availability.FAILED;
        }
        if (nat == null && swapchainFailed) {
            return FrameGenRuntime.Availability.NO_BINARIES;
        }
        return FrameGenRuntime.Availability.READY;
    }

    public static String statusLine() {
        FrameGenRuntime.Snapshot sn = snapshot();
        FrameGenRuntime.Status st = sn.status();
        return switch (st.state()) {
            case ACTIVE -> String.format(java.util.Locale.ROOT, "Active 2x (AMD FSR 3): %.0f rendered / %.0f presented fps", sn.fpsRendered(), sn.fpsPresented());
            case ENGAGING -> "Not generating (AMD FSR 3): " + st.reason().replace('_', ' ') + String.format(java.util.Locale.ROOT,
                " (%.0f rendered / %.0f presented fps)", sn.fpsRendered(), sn.fpsPresented());
            case READY -> "Inactive (AMD FSR 3): " + st.reason().replace('_', ' ');
            case FAILED -> "Failed (AMD FSR 3): " + st.reason();
            default -> st.state() + ": " + st.reason();
        };
    }
}
