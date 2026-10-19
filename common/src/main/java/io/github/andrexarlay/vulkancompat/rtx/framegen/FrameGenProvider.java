package io.github.andrexarlay.vulkancompat.rtx.framegen;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * The frame generation implementation chosen for this session. NVIDIA DLSS-G (Streamline) and AMD FSR 3 frame generation both
 * replace the swapchain, so only one can be active and the choice is made once at startup ({@link FrameGenEarly#prepare()},
 * mixin plugin time, before any Vulkan object exists). It is decided only when frame generation is not Off at startup.
 */
public final class FrameGenProvider {
    private static final Logger LOGGER = LoggerFactory.getLogger("Bedray/FrameGenProvider");

    public enum Active { NONE, NVIDIA, AMD }

    private static volatile Active active = Active.NONE;
    private static volatile String reason = "not_decided";

    private FrameGenProvider() {}

    public static Active active() {
        return active;
    }

    /** True when FSR 3 frame generation owns the swapchain this session. */
    public static boolean amd() {
        return active == Active.AMD;
    }

    public static String reason() {
        return reason;
    }

    /** Heuristic for AUTO: the NVIDIA user-mode driver installs nvapi64.dll into System32. Nothing else is inferred from it. */
    static boolean nvidiaDriverPresent() {
        String root = System.getenv("SystemRoot");
        if (root == null || root.isBlank()) {
            return false;
        }
        Path p = Paths.get(root, "System32", "nvapi64.dll");
        return Files.isRegularFile(p);
    }

    /** Decision with explicit inputs (tests). */
    static Active decide(FrameGenSettings.Provider requested, boolean modeOff, boolean streamlineComplete, boolean nvidiaDriver) {
        if (modeOff) {
            return Active.NONE;
        }
        return switch (requested) {
            case NVIDIA -> Active.NVIDIA;
            case AMD -> Active.AMD;
            case AUTO -> streamlineComplete && nvidiaDriver ? Active.NVIDIA : Active.AMD;
        };
    }

    static void resolveAtStartup(boolean modeOff, boolean streamlineComplete) {
        FrameGenSettings.Provider req = FrameGenSettings.provider();
        boolean nv = nvidiaDriverPresent();
        active = decide(req, modeOff, streamlineComplete, nv);
        reason = "requested=" + req + " mode_off=" + modeOff + " streamline_files=" + streamlineComplete + " nvidia_driver=" + nv;
        System.out.println("[VKB-FG] provider=" + active + " (" + reason + ")");
        LOGGER.info("[VKB-FG] provider={} ({})", active, reason);
    }
}
