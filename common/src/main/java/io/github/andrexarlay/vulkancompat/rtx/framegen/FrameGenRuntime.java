package io.github.andrexarlay.vulkancompat.rtx.framegen;

import com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import io.github.andrexarlay.vulkancompat.rtx.WorldTargetManager;
import io.github.andrexarlay.vulkancompat.rtx.raytracing.VulkanCapabilityChecker;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.CameraJitterManager;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.TemporalUpscaler;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.VkBarriers;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.fsr.FsrCamera;
import io.github.andrexarlay.vulkancompat.rtx.framegen.fsr3.Fsr3FrameGen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VK11;
import org.lwjgl.vulkan.VkPhysicalDeviceIDProperties;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties2;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Per-frame side of NVIDIA DLSS Frame Generation (Streamline), render thread only. Hooks (all in {@code mixin/framegen}):
 * <pre>
 * Minecraft.renderFrame HEAD          onFrameHead      new frame token, slReflexSleep, PCL simulation start
 * before GameRenderer.render()        onBeforeRender   PCL simulation end, render submit start
 * GameRenderer.render() before GUI    onWorldReady     hudless copy + flipped depth/motion vectors (this class, FrameGenGpu)
 * after GameRenderer.render()         onAfterRender    constants, resource tags, slDLSSGSetOptions on/off
 * before GpuSurface.present()         onPresentStart   PCL render submit end + present start
 * after  GpuSurface.present()         onPresentEnd     PCL present end, counters, slDLSSGGetState
 * </pre>
 * Contract: ProgrammingGuideDLSS_G.md sections 5 (tags), 6 (options), 7 (constants), 8 (Reflex), 13/14 (state), 16 (Vulkan sync;
 * the engine already waits on the acquire semaphore and signals the present semaphore the way section 16 demands).
 * Frame generation is on only in frames that actually rendered the world through a temporal upscaler (depth + motion vectors
 * exist); in every other frame (menus, loading) it is switched off (section 6.4/12) and the tags are cleared (section 5.2 note).
 * A failure is logged once as {@code [VKB-FG] fg=FAILED stage=..} and disables the feature for the session; nothing is retried silently.
 */
public final class FrameGenRuntime {
    private static final Logger LOGGER = LoggerFactory.getLogger("Bedray/FrameGen");

    private static final int VIEWPORT = 0;
    private static final int LAYOUT_GENERAL = VK10.VK_IMAGE_LAYOUT_GENERAL;
    // sl_core_types.h BufferType values
    private static final int BT_DEPTH = 0;
    private static final int BT_MVEC = 1;
    private static final int BT_HUDLESS = 2;
    private static final int LIFE_UNTIL_PRESENT = 1; // sl::ResourceLifecycle::eValidUntilPresent
    // sl_dlss_g.h DLSSGMode / DLSSGFlags
    private static final int MODE_OFF = 0;
    private static final int MODE_ON = 1;
    private static final int FLAG_RETAIN_RESOURCES_WHEN_OFF = 1 << 3;
    private static final int FLAG_DYNAMIC_RESOLUTION = 1 << 1;
    private static final int WARMUP_FRAMES = 3;
    private static final long REPORT_NS = 5_000_000_000L;

    /** Autotest only (-Dvkrtx.autotest.keepFocus=true): Streamline pauses generation while the window is not focused, so an unattended run foregrounds it. */
    private static final boolean KEEP_FOCUS = Boolean.getBoolean("vkrtx.autotest.keepFocus");
    private static boolean failed;
    private static String failure = "";
    private static FrameGenGpu gpu;
    private static long frameIndex;
    private static int slot;
    private static boolean multiClampLogged;

    // per-frame state
    private static boolean worldReady;
    private static int renderW, renderH, winW, winH;
    private static boolean tagsActive;

    // temporal history for the constants (WorldTargetManager advances its own history before our hook)
    private static Matrix4f prevProj, prevView;
    private static Vec3 prevCam;
    private static int validFrames;
    private static boolean modeOn;
    private static boolean ranOnce;
    private static long lastSurfaceGeneration = -1;
    private static boolean unsupportedLogged;

    // counters (evidence for the log)
    private static long renderedTotal;
    private static long presentedTotal;
    private static long windowStartNs;
    private static long windowRendered;
    private static long windowPresented;
    private static long lastStatus = -1;

    private FrameGenRuntime() {}

    /** User switch (settings UI hook, see docs/framegen/UI_HOOK.md). Default on; only meaningful when Streamline initialised. */
    private static volatile boolean userEnabled = true;
    /** Result of slIsFeatureSupported(DLSS_G) for the engine adapter: null = not asked yet. */
    private static Boolean supported;
    private static String supportReason = "not_checked";
    private static boolean windowFocused = true;
    private static long doubledFrames;
    /** Consecutive frames in which the swapchain really presented multiplier frames (slDLSSGGetState.numFramesActuallyPresented). */
    private static int genStreak;
    /** numFramesToGenerateMax of the adapter (slDLSSGGetState): 1 = 2x only, up to 5 = 6x on RTX 50. */
    private static int framesMax = 1;
    private static int appliedGenerate;
    private static boolean appliedDynRes;
    private static int appliedReflex = -1;
    private static int appliedLimitUs = -1;
    private static double fpsRendered, fpsPresented;
    private static long latencyUs = -1;
    private static long gpuFrameUs = -1;

    /** Honest state of the feature for the UI and the log. */
    public enum State {
        /** Streamline was not requested (-Dvkrtx.framegen) or could not be initialised: the option must stay hidden/disabled. */
        UNAVAILABLE,
        /** Streamline loaded and DLSS-G supported, currently not generating (user off, menu, no temporal upscaler, window not focused). */
        READY,
        /** Mode ON but no doubled present counted yet. */
        ENGAGING,
        /** Mode ON and the swapchain presented two frames per rendered frame for at least PROVEN_FRAMES frames. */
        ACTIVE,
        FAILED
    }

    public static final int PROVEN_FRAMES = 60;
    /** Active is claimed only after this many consecutive frames with presented == multiplier x rendered. */
    public static final int GEN_STREAK_FOR_ACTIVE = 30;

    public record Status(State state, String reason, long renderedFrames, long presentedFrames, long doubledFrames) {}

    private static boolean amd() {
        return FrameGenProvider.amd();
    }

    public static Status status() {
        if (amd()) {
            return Fsr3FrameGen.status();
        }
        if (failed) {
            return new Status(State.FAILED, failure, renderedTotal, presentedTotal, doubledFrames);
        }
        if (!StreamlineBootstrap.isReady()) {
            String f = StreamlineBootstrap.failure();
            return new Status(State.UNAVAILABLE, f != null ? f : (StreamlineBootstrap.requested() ? "not_initialised" : "not_requested"),
                0, 0, 0);
        }
        if (supported != null && !supported) {
            return new Status(State.UNAVAILABLE, supportReason, renderedTotal, presentedTotal, doubledFrames);
        }
        if (modeOn) {
            boolean generating = genStreak >= GEN_STREAK_FOR_ACTIVE;
            return new Status(generating ? State.ACTIVE : State.ENGAGING,
                generating ? "generating" : windowFocused ? "streamline_not_generating" : "window_not_focused", renderedTotal, presentedTotal, doubledFrames);
        }
        return new Status(State.READY, FrameGenSettings.mode() == FrameGenSettings.Mode.OFF ? "mode_off" : !userEnabled ? "user_off" : !windowFocused ? "window_not_focused" : idleReason,
            renderedTotal, presentedTotal, doubledFrames);
    }

    /** Autotest only: tries to foreground the game window (Streamline needs a focused window); false when SL is not loaded. */
    public static boolean focusGameWindowForTest() {
        if (amd()) {
            return true;
        }
        if (!StreamlineBootstrap.isReady()) {
            return false;
        }
        return StreamlineBootstrap.sl().focusGameWindow() == 1;
    }

    public static void setUserEnabled(boolean enabled) {
        userEnabled = enabled;
        Fsr3FrameGen.setUserEnabled(enabled);
    }

    public static boolean userEnabled() {
        return userEnabled;
    }

    /** Live numbers for the F3 screen / settings page. */
    public record Snapshot(Status status, int multiplier, int maxMultiplier, double fpsRendered, double fpsPresented, long latencyUs,
                           long gpuFrameUs, String streamline, String driver) {}

    public static Snapshot snapshot() {
        if (amd()) {
            return Fsr3FrameGen.snapshot();
        }
        return new Snapshot(status(), modeOn ? appliedGenerate + 1 : 1, framesMax + 1, fpsRendered, fpsPresented, latencyUs, gpuFrameUs,
            StreamlineBootstrap.isReady() ? "loaded" : "not loaded", driverText);
    }

    /** Largest multiplier the adapter/driver can generate (2 = 2x only). Valid after the first world frame; 1 while unknown/unsupported. */
    public static int maxMultiplier() {
        if (amd()) {
            return Fsr3FrameGen.ready() ? 2 : 1;
        }
        return Boolean.TRUE.equals(supported) ? framesMax + 1 : 1;
    }

    /** True when the settings page may offer frame generation at all (Streamline loaded, adapter supported). */
    public static boolean available() {
        if (amd()) {
            return Fsr3FrameGen.ready();
        }
        return StreamlineBootstrap.isReady() && !failed && Boolean.TRUE.equals(supported);
    }

    private static String driverText = "?";
    /** Why the last frame had no generation although it could: set per frame by the hooks. */
    private static String idleReason = "not_in_world";
    private static String worldFail;

    /** What the settings page may offer right now. */
    public enum Availability {
        /** Streamline is loaded and the adapter supports DLSS-G (or is not asked yet): the mode can be changed live. */
        READY,
        /** The binaries exist but Streamline was not hooked at startup: choosing a mode takes effect after a restart. */
        NEEDS_RESTART,
        /** No Streamline binaries (sl.interposer.dll / vkb_sl.dll) in the Streamline directory. */
        NO_BINARIES,
        /** slInit or a later call failed; see {@link #status()} for the reason. */
        FAILED,
        /** The adapter/driver/OS does not support DLSS-G (slIsFeatureSupported). */
        UNSUPPORTED
    }

    public static Availability availability() {
        FrameGenSettings.Provider wanted = FrameGenSettings.provider();
        if (amd()) {
            // The provider is fixed at startup: choosing the other one only takes effect after a restart.
            return wanted == FrameGenSettings.Provider.NVIDIA ? Availability.NEEDS_RESTART : Fsr3FrameGen.availability();
        }
        if (wanted == FrameGenSettings.Provider.AMD) {
            return io.github.andrexarlay.vulkancompat.rtx.upscaler.fsr.FsrLibrary.library().usable()
                ? Availability.NEEDS_RESTART : Availability.NO_BINARIES;
        }
        if (failed || StreamlineBootstrap.failure() != null) {
            return Availability.FAILED;
        }
        if (StreamlineBootstrap.isReady()) {
            return supported != null && !supported ? Availability.UNSUPPORTED : Availability.READY;
        }
        if (FrameGenEarly.missingFiles().isEmpty()) {
            return Availability.NEEDS_RESTART;
        }
        if (wanted == FrameGenSettings.Provider.AUTO
            && io.github.andrexarlay.vulkancompat.rtx.upscaler.fsr.FsrLibrary.library().usable()) {
            return Availability.NEEDS_RESTART;
        }
        return Availability.NO_BINARIES;
    }

    public static String statusBadge() {
        Availability a = availability();
        if (amd() && a == Availability.READY) {
            return isActive() ? "2x" : FrameGenSettings.mode() == FrameGenSettings.Mode.OFF ? "Off" : "Standby";
        }
        if (a == Availability.READY) {
            return isActive() ? (modeOn ? (appliedGenerate + 1) + "x" : "Ready") : "Standby";
        }
        if (FrameGenSettings.mode() == FrameGenSettings.Mode.OFF) {
            return "Off";
        }
        return switch (a) {
            case NEEDS_RESTART -> "Pending Restart";
            case NO_BINARIES -> "No DLLs";
            case FAILED -> "Failed";
            case UNSUPPORTED -> "Unsupported";
            default -> "Off";
        };
    }

    /** One line for the settings page / F3, always derived from real state. */
    public static String statusLine() {
        if (amd() && availability() == Availability.READY) {
            return Fsr3FrameGen.statusLine();
        }
        if (amd() && availability() == Availability.FAILED) {
            return Fsr3FrameGen.statusLine();
        }
        if (amd() && availability() == Availability.NO_BINARIES) {
            return "Unavailable (AMD FSR 3): " + Fsr3FrameGen.failure();
        }
        if (!amd() && availability() == Availability.NEEDS_RESTART && FrameGenSettings.provider() == FrameGenSettings.Provider.AMD) {
            return "Restart required to activate AMD FSR 3 frame generation";
        }
        Snapshot sn = snapshot();
        Status st = sn.status();
        return switch (availability()) {
            case NO_BINARIES -> "Unavailable: missing " + FrameGenEarly.missingFiles() + " in " + FrameGenEarly.slDir();
            case NEEDS_RESTART -> FrameGenSettings.mode() == FrameGenSettings.Mode.OFF
                ? "Off (Select 2x and restart to activate Streamline)"
                : "Restart required to activate Streamline (" + FrameGenSettings.mode().label() + ")";
            case FAILED -> "Failed: " + (failed ? failure : StreamlineBootstrap.failure());
            case UNSUPPORTED -> "Unsupported: " + supportReason;
            case READY -> switch (st.state()) {
                case ACTIVE -> String.format(java.util.Locale.ROOT, "Active %dx: %.0f rendered / %.0f presented fps",
                    sn.multiplier(), sn.fpsRendered(), sn.fpsPresented());
                case ENGAGING -> "Not generating: " + st.reason().replace('_', ' ') + String.format(java.util.Locale.ROOT, " (%.0f rendered / %.0f presented fps)",
                    sn.fpsRendered(), sn.fpsPresented());
                case READY -> "Inactive: " + st.reason().replace('_', ' ');
                default -> st.state() + ": " + st.reason();
            };
        };
    }

    /** True while frame generation is running (SL initialised, not failed, last frame had it switched on). */
    public static boolean isActive() {
        if (amd()) {
            return Fsr3FrameGen.status().state() == State.ACTIVE;
        }
        return StreamlineBootstrap.isReady() && !failed && modeOn;
    }

    /** Frames the game rendered while frame generation was on / frames the swapchain actually presented, cumulative. */
    public static long renderedFrames() {
        if (amd()) {
            return Fsr3FrameGen.renderedFrames();
        }
        return renderedTotal;
    }

    public static long presentedFrames() {
        if (amd()) {
            return Fsr3FrameGen.presentedFrames();
        }
        return presentedTotal;
    }

    public static boolean usable() {
        return !amd() && StreamlineBootstrap.isReady() && !failed;
    }

    private static void fail(String stage, String detail, Throwable t) {
        failed = true;
        failure = stage + ": " + detail;
        LOGGER.error("[VKB-FG] fg=FAILED stage={} {}", stage, detail, t);
    }

    private static void check(int r, String what) {
        if (r != 0) {
            throw new IllegalStateException(what + " returned sl::Result " + r);
        }
    }

    // ------------------------------------------------------------------------------------------------------------ hooks

    public static void onFrameHead() {
        if (amd()) {
            Fsr3FrameGen.onFrameHead();
            return;
        }
        if (!usable()) {
            return;
        }
        try {
            SlNative sl = StreamlineBootstrap.sl();
            frameIndex++;
            worldReady = false;
            worldFail = null;
            if (KEEP_FOCUS && frameIndex % 90 == 0) {
                sl.focusGameWindow();
            }
            if (supported == null) {
                checkSupport(sl);
            }
            check(sl.frameBegin((int) frameIndex), "slGetNewFrameToken");
            applyReflex(sl);
            check(sl.reflexSleep(), "slReflexSleep");
            check(sl.marker(0), "slPCLSetMarker(SimulationStart)");
            if (frameIndex % 120 == 1) {
                StreamlineBootstrap.pumpLog(sl);
            }
        } catch (Throwable t) {
            fail("frame_head", t.toString(), t);
        }
    }

    public static void onBeforeRender() {
        if (!usable()) {
            return;
        }
        try {
            SlNative sl = StreamlineBootstrap.sl();
            check(sl.marker(1), "slPCLSetMarker(SimulationEnd)");
            check(sl.marker(2), "slPCLSetMarker(RenderSubmitStart)");
        } catch (Throwable t) {
            fail("before_render", t.toString(), t);
        }
    }

    /** After the world (and the temporal upscaler) was written into the main target, before the GUI is drawn. */
    public static void onWorldReady(GameRenderer renderer) {
        if (amd()) {
            Fsr3FrameGen.onWorldReady(renderer);
            return;
        }
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
                if (!unsupportedLogged) {
                    unsupportedLogged = true;
                    LOGGER.warn("[VKB-FG] fg=UNAVAILABLE reason=missing_targets (depth and motion vectors needed; "
                        + "temporal_active={} depth_copy={} motion_vectors={})", TemporalUpscaler.isActive(), depthCopy != null, mvTarget != null);
                }
                return;
            }
            winW = main.width;
            winH = main.height;
            renderW = depthCopy.width;
            renderH = depthCopy.height;
            int fmt = FrameGenSurfaceInfo.format();
            if (fmt == 0 || FrameGenSurfaceInfo.width() != winW || FrameGenSurfaceInfo.height() != winH) {
                worldFail = "swapchain_reconfiguring";
                return; // swapchain not (re)configured for this window size yet
            }
            VulkanDevice device = VulkanCapabilityChecker.getVulkanDevice();
            if (device == null) {
                return;
            }
            if (gpu == null) {
                gpu = new FrameGenGpu(device.vkDevice());
            }
            gpu.ensure(fmt, winW, winH, renderW, renderH);
            long mainImg = WorldTargetManager.extractVkImage(main);
            long depthView = WorldTargetManager.extractVkImageView(depthCopy.getDepthTextureView());
            long mvView = WorldTargetManager.extractVkImageView(mvTarget.getColorTextureView());
            if (mainImg == 0L || depthView == 0L || mvView == 0L) {
                throw new IllegalStateException("missing Vulkan handle main=" + mainImg + " depthView=" + depthView + " mvView=" + mvView);
            }
            slot = (int) (frameIndex % FrameGenGpu.SETS);
            VulkanCommandEncoder encoder = device.createCommandEncoder();
            VkCommandBuffer cmd = encoder.allocateAndBeginTransientCommandBuffer();
            VkBarriers.fullMemoryBarrier(cmd);
            gpu.recordInitialTransitions(cmd);
            gpu.record(cmd, slot, mainImg, depthView, mvView);
            VkBarriers.fullMemoryBarrier(cmd);
            int end = VK10.vkEndCommandBuffer(cmd);
            if (end != VK10.VK_SUCCESS) {
                throw new IllegalStateException("vkEndCommandBuffer failed: " + end);
            }
            encoder.execute(cmd);
            worldReady = true;
        } catch (Throwable t) {
            fail("world_ready", t.toString(), t);
        }
    }

    public static void onAfterRender() {
        if (amd()) {
            Fsr3FrameGen.onAfterRender();
            return;
        }
        if (!usable()) {
            return;
        }
        try {
            SlNative sl = StreamlineBootstrap.sl();
            long surfaceGen = FrameGenSurfaceInfo.generation();
            boolean surfaceChanged = surfaceGen != lastSurfaceGeneration;
            lastSurfaceGeneration = surfaceGen;
            idleReason = worldReady ? "idle" : worldFail != null ? worldFail : "not_in_world";
            boolean want = false;
            windowFocused = Minecraft.getInstance().getWindow().isFocused();
            FrameGenSettings.Mode wanted = FrameGenSettings.mode();
            boolean allowed = userEnabled && windowFocused && Boolean.TRUE.equals(supported) && wanted != FrameGenSettings.Mode.OFF;
            if (worldReady && !surfaceChanged) {
                validFrames++;
                if (constantsFrame != frameIndex) {
                    sendConstants(sl);
                    constantsFrame = frameIndex;
                }
                tag(sl);
                want = allowed && validFrames > WARMUP_FRAMES;
            } else {
                validFrames = 0;
                prevProj = null;
                clearTags(sl);
            }
            int generate = Math.max(1, Math.min(wanted.generated(), framesMax));
            if (want && wanted.generated() > framesMax && !multiClampLogged) {
                multiClampLogged = true;
                LOGGER.warn("[VKB-FG] requested {} but the adapter supports at most {}x (numFramesToGenerateMax={}), using {}x",
                    wanted.label(), framesMax + 1, framesMax, generate + 1);
            }
            boolean dyn = worldReady && (renderW != winW || renderH != winH);
            if (want != modeOn || (want && (!ranOnce || generate != appliedGenerate || dyn != appliedDynRes))) {
                int mode = want ? MODE_ON : MODE_OFF;
                int flags = FLAG_RETAIN_RESOURCES_WHEN_OFF | (dyn ? FLAG_DYNAMIC_RESOLUTION : 0);
                check(sl.dlssgOptions(mode, generate, flags, winW, winH, FrameGenSurfaceInfo.imageCount(), false, VIEWPORT,
                    dyn ? renderW : 0, dyn ? renderH : 0), "slDLSSGSetOptions(mode=" + mode + ")");
                boolean was = modeOn;
                modeOn = want;
                if (!want) {
                    genStreak = 0;
                }
                ranOnce = true;
                if (want) {
                    appliedGenerate = generate;
                    appliedDynRes = dyn;
                }
                LOGGER.info("[VKB-FG] dlssg mode={} multiplier={}x (was {}) frame={} output={}x{} render={}x{} dynamic_res={} back_buffers={} focused={}",
                    want ? "ON" : "OFF", generate + 1, was ? "ON" : "OFF", frameIndex, winW, winH, renderW, renderH, dyn,
                    FrameGenSurfaceInfo.imageCount(), windowFocused);
                StreamlineBootstrap.pumpLog(sl);
            }
        } catch (Throwable t) {
            fail("after_render", t.toString(), t);
        }
    }

    public static void onPresentStart() {
        if (!usable()) {
            return;
        }
        try {
            SlNative sl = StreamlineBootstrap.sl();
            check(sl.marker(3), "slPCLSetMarker(RenderSubmitEnd)");
            check(sl.marker(4), "slPCLSetMarker(PresentStart)");
        } catch (Throwable t) {
            fail("present_start", t.toString(), t);
        }
    }

    public static void onPresentEnd() {
        if (amd()) {
            Fsr3FrameGen.onPresentEnd();
            return;
        }
        if (!usable()) {
            return;
        }
        try {
            SlNative sl = StreamlineBootstrap.sl();
            check(sl.marker(5), "slPCLSetMarker(PresentEnd)");
            if (!modeOn && !ranOnce) {
                return;
            }
            int[] st = new int[5];
            long[] vram = new long[1];
            int r = sl.dlssgState(st, vram, VIEWPORT);
            if (r != 0) {
                throw new IllegalStateException("slDLSSGGetState returned " + r);
            }
            if (st[0] != lastStatus) {
                lastStatus = st[0];
                LOGGER.info("[VKB-FG] dlssg status=0x{} (0 = eOk) min_wh={} frames_to_generate_max={} vsync_support={} vram_mb={}",
                    Integer.toHexString(st[0]), st[1], st[3], st[4], vram[0] / (1024 * 1024));
                StreamlineBootstrap.pumpLog(sl);
            }
            if (st[0] != 0) {
                fail("evaluate", "NGX error 0x" + Integer.toHexString(st[0]), null);
                modeOn = false;
                FrameGenSettings.setMode(FrameGenSettings.Mode.OFF);
                clearTags(sl);
                try {
                    sl.dlssgOptions(MODE_OFF, 1, 0, winW, winH, FrameGenSurfaceInfo.imageCount(), false, VIEWPORT, 0, 0);
                } catch (Throwable ignored) {
                }
                return;
            }
            long now = System.nanoTime();
            if (windowStartNs == 0L) {
                windowStartNs = now;
            }
            if (modeOn) {
                renderedTotal++;
                windowRendered++;
                presentedTotal += st[2];
                windowPresented += st[2];
                if (st[2] >= appliedGenerate + 1) {
                    doubledFrames++;
                    genStreak++;
                } else {
                    genStreak = 0;
                }
            }
            long dt = now - windowStartNs;
            if (dt >= REPORT_NS) {
                double sec = dt / 1e9;
                LOGGER.info("[VKB-FG] [VKB-AUTOTEST] framegen mode={} multiplier={}x rendered_frames={} presented_frames={} window_s={} fps_rendered={} fps_presented={} "
                        + "total_rendered={} total_presented={} status=0x{}",
                    modeOn ? "ON" : "OFF", modeOn ? appliedGenerate + 1 : 1, windowRendered, windowPresented, String.format(java.util.Locale.ROOT, "%.2f", sec),
                    String.format(java.util.Locale.ROOT, "%.1f", windowRendered / sec), String.format(java.util.Locale.ROOT, "%.1f", windowPresented / sec),
                    renderedTotal, presentedTotal, Integer.toHexString(st[0]));
                fpsRendered = windowRendered / sec;
                fpsPresented = windowPresented / sec;
                int[] lat = sl.reflexLatency();
                if (lat != null) {
                    latencyUs = lat[1] & 0xFFFFFFFFL;
                    gpuFrameUs = lat[2] & 0xFFFFFFFFL;
                    LOGGER.info("[VKB-FG] reflex latency_ms={} gpu_frame_ms={}", String.format(java.util.Locale.ROOT, "%.1f", latencyUs / 1000.0),
                        String.format(java.util.Locale.ROOT, "%.2f", gpuFrameUs / 1000.0));
                }
                windowStartNs = now;
                windowRendered = 0;
                windowPresented = 0;
                StreamlineBootstrap.pumpLog(sl);
            }
        } catch (Throwable t) {
            fail("present_end", t.toString(), t);
        }
    }

    /** slIsFeatureSupported(DLSS_G) for the adapter the engine uses (LUID from VkPhysicalDeviceIDProperties) + versions, logged once. */
    private static void checkSupport(SlNative sl) {
        VulkanDevice device = VulkanCapabilityChecker.getVulkanDevice();
        if (device == null) {
            return;
        }
        try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
            VkPhysicalDeviceIDProperties id = VkPhysicalDeviceIDProperties.calloc(stack).sType$Default();
            VkPhysicalDeviceProperties2 props = VkPhysicalDeviceProperties2.calloc(stack).sType$Default().pNext(id);
            VK11.vkGetPhysicalDeviceProperties2(device.vkDevice().getPhysicalDevice(), props);
            if (!id.deviceLUIDValid()) {
                supported = Boolean.FALSE;
                supportReason = "no_device_luid";
                LOGGER.error("[VKB-FG] slIsFeatureSupported not asked: the adapter has no LUID");
                return;
            }
            byte[] luid = new byte[8];
            id.deviceLUID().get(luid);
            int r = sl.dlssgSupported(luid);
            int[] v = sl.dlssgVersion();
            supported = r == 0;
            supportReason = r == 0 ? "supported" : "slIsFeatureSupported=" + r + " (sl_result.h)";
            LOGGER.info("[VKB-FG] slIsFeatureSupported(DLSS_G)={} ({}) sl_plugin={} ngx={}", r, r == 0 ? "supported" : "NOT supported",
                v == null ? "?" : v[0] + "." + v[1] + "." + v[2], v == null ? "?" : v[3] + "." + v[4] + "." + v[5]);
            int[] req = sl.dlssgRequirements();
            if (req != null) {
                driverText = req[3] + "." + req[4] + "." + req[5];
            }
            int[] st0 = new int[5];
            long[] vram0 = new long[1];
            if (sl.dlssgState(st0, vram0, VIEWPORT) == 0) {
                framesMax = Math.max(1, st0[3]);
            }
            LOGGER.info("[VKB-FG] dlssg capabilities frames_to_generate_max={} (max multiplier {}x) driver={}", framesMax, framesMax + 1, driverText);
            StreamlineBootstrap.pumpLog(sl);
        }
    }

    /** Applies Reflex mode and the frame cap when they changed (also the first call). Reflex is mandatory for DLSS-G (guide 8.0). */
    private static void applyReflex(SlNative sl) {
        FrameGenSettings.Reflex rm = FrameGenSettings.reflex();
        int fps = FrameGenSettings.frameLimitFps();
        // The cap is given as presented fps; Reflex limits the rendered frame rate, so divide by the multiplier.
        int mult = Math.max(1, modeOn ? appliedGenerate + 1 : 1);
        int limitUs = fps <= 0 ? 0 : (int) Math.max(1000L, 1_000_000L * mult / fps);
        if (rm.slValue() == appliedReflex && limitUs == appliedLimitUs) {
            return;
        }
        check(sl.reflexOptions(rm.slValue(), limitUs), "slReflexSetOptions");
        appliedReflex = rm.slValue();
        appliedLimitUs = limitUs;
        LOGGER.info("[VKB-FG] reflex mode={} frame_limit_us={} (presented cap {} fps, multiplier {}x)", rm, limitUs, fps, mult);
    }

    /** Head of VulkanGpuSurface.configure: Streamline must not see a swapchain destroy while generating (guide 12.0). */
    public static void onSurfaceReconfigureBegin() {
        if (amd()) {
            Fsr3FrameGen.onSurfaceReconfigureBegin();
            return;
        }
        if (!usable()) {
            return;
        }
        try {
            SlNative sl = StreamlineBootstrap.sl();
            if (modeOn) {
                check(sl.dlssgOptions(MODE_OFF, 1, FLAG_RETAIN_RESOURCES_WHEN_OFF, winW, winH, FrameGenSurfaceInfo.imageCount(), false, VIEWPORT, 0, 0),
                    "slDLSSGSetOptions(off before swapchain reconfigure)");
                modeOn = false;
            }
            validFrames = 0;
            ranOnce = false;
            clearTags(sl);
            if (gpu != null) {
                gpu.close();
                gpu = null;
            }
            LOGGER.info("[VKB-FG] dlssg mode=OFF reason=swapchain_reconfigure");
        } catch (Throwable t) {
            fail("reconfigure", t.toString(), t);
        }
    }

    /** Head of VulkanGpuSurface.close(): generation off and our GPU objects released while the device is still alive. */
    public static void onSurfaceClose() {
        if (amd()) {
            Fsr3FrameGen.onSurfaceClose();
            return;
        }
        if (!StreamlineBootstrap.isReady()) {
            return;
        }
        try {
            SlNative sl = StreamlineBootstrap.sl();
            if (modeOn) {
                sl.dlssgOptions(MODE_OFF, 1, 0, winW, winH, FrameGenSurfaceInfo.imageCount(), false, VIEWPORT, 0, 0);
                modeOn = false;
            }
            clearTags(sl);
            if (gpu != null) {
                gpu.close();
                gpu = null;
            }
            LOGGER.info("[VKB-FG] teardown surface_close rendered_total={} presented_total={} doubled_frames={}", renderedTotal, presentedTotal, doubledFrames);
            StreamlineBootstrap.pumpLog(sl);
        } catch (Throwable t) {
            LOGGER.error("[VKB-FG] teardown surface_close failed", t);
        }
    }

    /** Head of VulkanDevice.close(): slShutdown before the device is destroyed (guide 2.0). */
    public static void onDeviceClose() {
        if (amd()) {
            Fsr3FrameGen.onDeviceClose();
            return;
        }
        if (!StreamlineBootstrap.isReady()) {
            return;
        }
        try {
            SlNative sl = StreamlineBootstrap.sl();
            int r = sl.shutdown();
            LOGGER.info("[VKB-FG] teardown slShutdown result={}", r);
            StreamlineBootstrap.pumpLog(sl);
        } catch (Throwable t) {
            LOGGER.error("[VKB-FG] teardown slShutdown failed", t);
        }
    }

    // ------------------------------------------------------------------------------------------------------- constants/tags

    private static void tag(SlNative sl) {
        FrameGenGpu.Img d = gpu.depth(slot);
        FrameGenGpu.Img m = gpu.mv(slot);
        FrameGenGpu.Img h = gpu.hudless(slot);
        int usage = h.usage();
        check(sl.tag(BT_DEPTH, d.image(), d.view(), LAYOUT_GENERAL, d.width(), d.height(), d.format(), d.usage(), LIFE_UNTIL_PRESENT, VIEWPORT), "tag depth");
        check(sl.tag(BT_MVEC, m.image(), m.view(), LAYOUT_GENERAL, m.width(), m.height(), m.format(), m.usage(), LIFE_UNTIL_PRESENT, VIEWPORT), "tag mvec");
        check(sl.tag(BT_HUDLESS, h.image(), h.view(), LAYOUT_GENERAL, h.width(), h.height(), h.format(), usage, LIFE_UNTIL_PRESENT, VIEWPORT), "tag hudless");
        tagsActive = true;
    }

    private static void clearTags(SlNative sl) {
        if (!tagsActive) {
            return;
        }
        // Guide 5.2: when the inputs are not valid (menus, loading) set all tags to null.
        check(sl.tag(BT_DEPTH, 0L, 0L, 0, 0, 0, 0, 0, LIFE_UNTIL_PRESENT, VIEWPORT), "clear depth tag");
        check(sl.tag(BT_MVEC, 0L, 0L, 0, 0, 0, 0, 0, LIFE_UNTIL_PRESENT, VIEWPORT), "clear mvec tag");
        check(sl.tag(BT_HUDLESS, 0L, 0L, 0, 0, 0, 0, 0, LIFE_UNTIL_PRESENT, VIEWPORT), "clear hudless tag");
        tagsActive = false;
    }

    /**
     * Common constants (guide 7.0 and sl_consts.h). SL matrices are row-major with row vectors; the JOML float array
     * (column-major, column vectors) of M is byte-identical to the row-major array of M transposed, i.e. the row-vector form,
     * so {@code Matrix4f.get} is passed as is. Depth: reverse-Z hardware depth; motion vectors: pixels, previous minus current,
     * camera + object motion, no jitter, already flipped to the back buffer orientation by fg_prepare.comp.
     */
    private static long constantsFrame = -1;

    /**
     * Common constants of the current frame for the Streamline DLSS plugins: slEvaluateFeature of DLSS SR / Ray Reconstruction happens in
     * the world pass, before the frame generation hook of the same frame, and every feature needs the constants of that frame
     * (ProgrammingGuide.md "frameToken and viewport must match across all API calls"). Constants are sent once per frame; this is the first caller.
     */
    public static void ensureConstants(int rw, int rh, int ow, int oh) {
        if (!usable() || constantsFrame == frameIndex) {
            return;
        }
        try {
            renderW = rw;
            renderH = rh;
            winW = ow;
            winH = oh;
            sendConstants(StreamlineBootstrap.sl());
            constantsFrame = frameIndex;
        } catch (Throwable t) {
            fail("constants", t.toString(), t);
        }
    }

    private static void sendConstants(SlNative sl) {
        Matrix4f pc = WorldTargetManager.getCurrProjMatrix();
        Matrix4f rc = WorldTargetManager.getCurrViewRotationMatrix();
        Vec3 cc = WorldTargetManager.getCurrCameraPos();
        if (pc == null || rc == null || cc == null) {
            throw new IllegalStateException("camera state of the world pass is missing (proj=" + (pc != null) + " view=" + (rc != null) + " pos=" + (cc != null) + ")");
        }
        boolean havePrev = prevProj != null && prevView != null && prevCam != null;
        boolean reset = !havePrev || cc.distanceToSqr(prevCam) > 64.0 * 64.0;
        Matrix4f pp = havePrev ? prevProj : pc;
        Matrix4f rp = havePrev ? prevView : rc;
        Vec3 cp = havePrev ? prevCam : cc;

        Matrix4f pcInv = new Matrix4f(pc).invert();
        // view_prev = Rp * (Rc^-1 * view_cur + (Cc - Cp))
        Matrix4f viewToViewPrev = new Matrix4f(rp)
            .translate((float) (cc.x - cp.x), (float) (cc.y - cp.y), (float) (cc.z - cp.z))
            .mul(new Matrix4f(rc).invert());
        // NOTE: JOML translate() post-multiplies, so Rp.translate(d).mul(Rc^-1) = Rp * T(d) * Rc^-1 as required.
        Matrix4f clipToPrevClip = new Matrix4f(pp).mul(viewToViewPrev).mul(pcInv);
        Matrix4f prevClipToClip = new Matrix4f(clipToPrevClip).invert();

        float[] m = new float[64];
        pc.get(m, 0);
        pcInv.get(m, 16);
        clipToPrevClip.get(m, 32);
        prevClipToClip.get(m, 48);

        FsrCamera cam = FsrCamera.fromProjection(pc);
        float[] v = new float[21];
        v[0] = CameraJitterManager.getCurrentPixelJitterX();
        v[1] = CameraJitterManager.getCurrentPixelJitterY();
        v[2] = 1.0f / renderW;
        v[3] = 1.0f / renderH;
        v[4] = (float) cc.x;
        v[5] = (float) cc.y;
        v[6] = (float) cc.z;
        // rotation rows of the world -> view matrix: right, up, backward
        v[7] = rc.m01();
        v[8] = rc.m11();
        v[9] = rc.m21();
        v[10] = rc.m00();
        v[11] = rc.m10();
        v[12] = rc.m20();
        v[13] = -rc.m02();
        v[14] = -rc.m12();
        v[15] = -rc.m22();
        v[16] = cam.near();
        v[17] = cam.far();
        v[18] = cam.fovY();
        v[19] = (float) winW / (float) winH;
        v[20] = Float.MAX_VALUE; // sl::INVALID_FLOAT, only used when cameraMotionIncluded is false
        int[] i = {1 /*depthInverted*/, 1 /*cameraMotionIncluded*/, 0 /*mvec 3D*/, reset ? 1 : 0, 0 /*mvec jittered*/, 0 /*dilated*/};
        check(sl.setConstants(m, v, i, VIEWPORT), "slSetConstants");

        prevProj = new Matrix4f(pc);
        prevView = new Matrix4f(rc);
        prevCam = cc;
    }

    /** For the log at shutdown / failure queries. */
    public static String failure() {
        return failure;
    }

    /** Releases the GPU resources (device idle first). */
    public static void shutdown() {
        if (gpu != null) {
            gpu.close();
            gpu = null;
        }
    }
}
