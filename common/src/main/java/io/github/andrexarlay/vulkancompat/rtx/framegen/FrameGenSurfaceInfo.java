package io.github.andrexarlay.vulkancompat.rtx.framegen;

/**
 * Swapchain facts published by {@code MixinVulkanGpuSurfaceInfo} after every {@code VulkanGpuSurface.configure}: the engine
 * picks the back buffer format (VkFormat 37 or 44) and size itself, frame generation has to match them.
 */
public final class FrameGenSurfaceInfo {
    private static volatile int format;
    private static volatile int width;
    private static volatile int height;
    private static volatile int imageCount;
    private static volatile long generation;

    private FrameGenSurfaceInfo() {}

    public static void publish(int fmt, int w, int h, int count) {
        format = fmt;
        width = w;
        height = h;
        imageCount = count;
        generation++;
    }

    public static int format() {
        return format;
    }

    public static int width() {
        return width;
    }

    public static int height() {
        return height;
    }

    public static int imageCount() {
        return imageCount;
    }

    /** Incremented on every swapchain (re)configuration; frame generation is switched off for that frame. */
    public static long generation() {
        return generation;
    }
}
