package io.github.andrexarlay.vulkancompat.rtx.hdr;

import io.github.andrexarlay.vulkancompat.rtx.framegen.FrameGenSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Whether the engine swapchain is HDR10 this session. The surface mixin decides once, when {@code VulkanGpuSurface} picks its
 * format: the player asked for HDR10 ({@link HdrSettings#wantsHdr10()}), the driver offers A2B10G10R10 + ST2084 and no frame
 * generation provider owns the swapchain. From then on {@link #active()} is true until the surface closes, and the final blit
 * must hand PQ code values to the swapchain ({@link HdrGpu}).
 */
public final class HdrOutput {
    private static final Logger LOGGER = LoggerFactory.getLogger("Bedray/HDR");

    /** VK_FORMAT_A2B10G10R10_UNORM_PACK32. */
    public static final int FORMAT_HDR10 = 64;
    /** VK_COLOR_SPACE_HDR10_ST2084_EXT. */
    public static final int COLOR_SPACE_HDR10 = 1000104008;

    private static volatile boolean active;

    private HdrOutput() {}

    public static boolean active() {
        return active;
    }

    /** Called by the surface mixin when the HDR10 format was picked (or by {@link HdrGpu#release} when the surface closes). */
    public static void setActive(boolean on) {
        active = on;
    }

    /** Colour space the swapchain must be created with: HDR10 when the HDR format was picked, otherwise sRGB (0). */
    public static int swapchainColorSpace(int engineChoice) {
        return active ? COLOR_SPACE_HDR10 : engineChoice;
    }

    /** True when the player's request cannot be honoured because frame generation replaces the swapchain. */
    public static boolean blockedByFrameGeneration() {
        return FrameGenSettings.startupMode() != FrameGenSettings.Mode.OFF;
    }

    /**
     * Picks the HDR10 pair out of the surface's {@code (format, colorSpace)} list. Pure so it can be tested.
     *
     * @param formats surface formats, one entry per pair
     * @param colorSpaces colour space of each entry, same length
     * @return index of the HDR10 entry or -1
     */
    public static int findHdr10(int[] formats, int[] colorSpaces) {
        for (int i = 0; i < formats.length; i++) {
            if (formats[i] == FORMAT_HDR10 && colorSpaces[i] == COLOR_SPACE_HDR10) {
                return i;
            }
        }
        return -1;
    }

    /** Result of the format decision, logged once. */
    public static boolean decide(boolean wanted, boolean blockedByFrameGen, int hdrIndex, List<String> reasons) {
        boolean on = wanted && !blockedByFrameGen && hdrIndex >= 0;
        if (wanted && !on) {
            reasons.add(blockedByFrameGen ? "frame_generation_owns_swapchain" : "driver_offers_no_hdr10");
        }
        LOGGER.info("[VKB-HDR] output requested={} active={}{}", wanted, on, reasons.isEmpty() ? "" : " reason=" + reasons.get(0));
        return on;
    }
}
