package io.github.andrexarlay.vulkancompat.rtx.framegen;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Early initialisation of NVIDIA Streamline for DLSS Frame Generation (docs/framegen/FINDINGS.md).
 * <p>
 * Contract (Streamline ProgrammingGuide.md / ProgrammingGuideManualHooking.md): {@code slInit} before any Vulkan call, and
 * Vulkan must be reached through {@code sl.interposer.dll}. We use the "global" way: the JVM is started with
 * {@code -Dorg.lwjgl.vulkan.libname=<SL dir>/sl.interposer.dll}, so LWJGL and SDL (which the engine makes load the very same
 * library, VulkanBackend.loadLibrary compares both vkGetInstanceProcAddr) resolve the SL proxies. This class is called from
 * {@code MixinVulkanBackendStreamline} at the head of {@code VulkanBackend.loadLibrary()}, i.e. before the first instance exists.
 * <p>
 * Switches (all JVM properties, nothing is done without {@code vkrtx.framegen}):
 * <ul>
 * <li>{@code -Dvkrtx.framegen=dlss} request DLSS Frame Generation;</li>
 * <li>{@code -Dvkrtx.sl.dir=<dir>} directory with the Streamline binaries (sl.interposer.dll, sl.dlss_g.dll, sl.reflex.dll, sl.pcl.dll, sl.common.dll, nvngx_dlssg.dll);</li>
 * <li>{@code -Dvkrtx.sl.shim=<file>} path of vkb_sl.dll (native/sl_bridge/build.bat), default: {@code <sl.dir>/vkb_sl.dll}.</li>
 * </ul>
 * A failed request is logged as {@code [VKB-FG] sl=FAILED stage=.. result=..} and never retried or hidden; the feature stays unavailable.
 */
public final class StreamlineBootstrap {
    private static final Logger LOGGER = LoggerFactory.getLogger("Bedray/Streamline");
    public static final String PROP_MODE = "vkrtx.framegen";
    public static final String PROP_DIR = "vkrtx.sl.dir";
    public static final String PROP_SHIM = "vkrtx.sl.shim";

    private static boolean attempted;
    private static SlNative nativeLib;
    private static String failure;

    private StreamlineBootstrap() {}

    /** True when DLSS Frame Generation was requested on the command line (does not mean it works). */
    public static boolean requested() {
        return FrameGenSettings.startupMode() != FrameGenSettings.Mode.OFF && !FrameGenProvider.amd();
    }

    /** The initialised shim, or null when not requested / failed. */
    public static synchronized SlNative sl() {
        return nativeLib;
    }

    public static synchronized boolean isReady() {
        return nativeLib != null;
    }

    public static synchronized String failure() {
        return failure;
    }

    /** Idempotent; the first call does the work. Called at the head of VulkanBackend.loadLibrary(). */
    public static synchronized void initEarly() {
        if (attempted || !requested()) {
            return;
        }
        attempted = true;
        Path dir = FrameGenEarly.slDir();
        Path interposer = dir.resolve("sl.interposer.dll");
        if (!Files.isRegularFile(interposer)) {
            fail("config", "not found: " + interposer);
            return;
        }
        Path shim = FrameGenEarly.shimPath();
        SlNative n;
        try {
            n = SlNative.load(shim);
        } catch (RuntimeException | LinkageError e) {
            fail("load_shim", e.toString());
            LOGGER.error("[VKB-FG] shim load error", e);
            return;
        }
        Path logDir = Paths.get("bedray", "sl").toAbsolutePath().normalize();
        try {
            Files.createDirectories(logDir);
        } catch (java.io.IOException e) {
            LOGGER.warn("[VKB-FG] cannot create {}: {}", logDir, e.toString());
        }
        int r = n.init(dir.toString(), logDir.toString(), false);
        pumpLog(n);
        if (r != 0) {
            String stage = r == -2 ? "signature" : r == -3 ? "load_interposer" : r == -4 ? "exports" : "slInit";
            fail(stage, "result=" + r);
            return;
        }
        // LWJGL already created its Vulkan function provider from vulkan-1.dll (NativeLibrariesBootstrap runs before any mod code),
        // unless the launcher passed -Dorg.lwjgl.vulkan.libname. Re-create it from the interposer: no Vulkan object exists yet
        // (this runs at the head of VulkanBackend.loadLibrary, SDL loads the very same path right after, the engine then compares
        // both vkGetInstanceProcAddr).
        try {
            org.lwjgl.system.FunctionProvider fp = org.lwjgl.vulkan.VK.getFunctionProvider();
            String cur = fp instanceof org.lwjgl.system.SharedLibrary sl0 ? sl0.getPath() : null;
            if (cur == null || !Paths.get(cur).toAbsolutePath().normalize().equals(interposer)) {
                LOGGER.info("[VKB-FG] LWJGL Vulkan library was {}, re-creating it from {}", cur, interposer);
                org.lwjgl.vulkan.VK.destroy();
                org.lwjgl.vulkan.VK.create(interposer.toString());
            }
        } catch (RuntimeException | LinkageError e) {
            fail("lwjgl_vk_reload", e.toString());
            LOGGER.error("[VKB-FG] VK.create(interposer) failed", e);
            return;
        }
        nativeLib = n;
        int mask = n.loadedMask();
        LOGGER.info("[VKB-FG] sl=INIT_OK dir={} loaded_mask={} (bit0 DLSS_G, bit1 Reflex, bit2 PCL)", dir, mask);
        int[] req = n.dlssgRequirements();
        if (req != null) {
            LOGGER.info("[VKB-FG] dlss_g requirements flags=0x{} vk_graphics_queues={} vk_compute_queues={} driver={}.{}.{}",
                Integer.toHexString(req[0]), req[1], req[2], req[3], req[4], req[5]);
        }
    }

    /** True when Streamline loaded its DLSS Super Resolution plugin: DLSS SR/RR must then run through it (see DlssAvailability). */
    public static synchronized boolean dlssViaStreamline() {
        if (nativeLib == null) {
            return false;
        }
        int m = nativeLib.loadedMask();
        return m > 0 && (m & 8) != 0;
    }

    /** True when Streamline loaded its DLSS Ray Reconstruction plugin (sl.dlss_d.dll + nvngx_dlssd.dll next to sl.interposer.dll). */
    public static synchronized boolean rrViaStreamline() {
        return nativeLib != null && (nativeLib.loadedMask() & 16) != 0;
    }

    private static long[] nativeAddrs;

    /**
     * {vkGetInstanceProcAddr, vkGetDeviceProcAddr} of the system Vulkan loader (vulkan-1.dll), bypassing the interposer. For libraries
     * that talk to Vulkan on their own (NGX); the engine and Streamline keep using the interposer.
     */
    public static synchronized long[] nativeProcAddrs() {
        if (nativeAddrs == null) {
            try {
                java.lang.foreign.SymbolLookup l = java.lang.foreign.SymbolLookup.libraryLookup("vulkan-1.dll", java.lang.foreign.Arena.global());
                long a = l.find("vkGetInstanceProcAddr").map(java.lang.foreign.MemorySegment::address).orElse(0L);
                long b = l.find("vkGetDeviceProcAddr").map(java.lang.foreign.MemorySegment::address).orElse(0L);
                if (a == 0L || b == 0L) {
                    LOGGER.error("[VKB-FG] vulkan-1.dll has no vkGetInstanceProcAddr/vkGetDeviceProcAddr");
                    return null;
                }
                nativeAddrs = new long[] {a, b};
            } catch (RuntimeException e) {
                LOGGER.error("[VKB-FG] cannot load vulkan-1.dll for NGX", e);
                return null;
            }
        }
        return nativeAddrs;
    }

    /** Forwards queued Streamline log lines to the game log. */
    public static void pumpLog(SlNative n) {
        String s = n.drainLog();
        if (s.isEmpty()) {
            return;
        }
        for (String line : s.split("\n")) {
            if (line.startsWith("[E]")) {
                LOGGER.error("[VKB-FG] sl: {}", line);
            } else {
                LOGGER.info("[VKB-FG] sl: {}", line);
            }
        }
    }

    private static void fail(String stage, String detail) {
        failure = stage + ": " + detail;
        LOGGER.error("[VKB-FG] sl=FAILED stage={} {}", stage, detail);
    }
}
