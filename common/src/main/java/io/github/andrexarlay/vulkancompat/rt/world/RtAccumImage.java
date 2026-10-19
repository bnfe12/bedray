package io.github.andrexarlay.vulkancompat.rt.world;

import io.github.andrexarlay.vulkancompat.rtx.upscaler.VkBarriers;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * RGBA32F temporal history of a pass (binding 11) plus its {@link RtAccumulation} state. One image of {@code width x 4*height}:
 * rows [0,H) colour+count of even frames, [H,2H) of odd frames, [2H,3H) / [3H,4H) the matching geometry (normal xyz, hit
 * distance w, 0 = sky). Even/odd ping-pong inside one image keeps a single descriptor (no rewrite per frame) and avoids the
 * read/write race of reprojection. Recreated on resize; the history is reset then.
 */
final class RtAccumImage {
    private static final Logger LOGGER = LoggerFactory.getLogger("VKB-RT");

    /** Row bands of H rows: 0,1 colour+count (even/odd), 2,3 normal+distance, 4 albedo + filter flag, 5,6 spatial filter scratch, 7,8 rest R (specular, emission, fog) even/odd. */
    static final int ROWS = 9;

    final RtAccumulation history = new RtAccumulation();
    private final String name;
    private RtMemory memory;
    private RtMemory.Img image;
    private boolean ready;

    RtAccumImage(String name) {
        this.name = name;
    }

    void prepare(VkCommandBuffer cmd, RtMemory mem, int width, int height) {
        if (image != null && (image.width != width || image.height != height * ROWS)) {
            VK10.vkDeviceWaitIdle(mem.device); // frames in flight may still use the old image
            memory.destroy(image);
            image = null;
        }
        if (image == null) {
            memory = mem;
            image = mem.createImage(name, width, height * ROWS, VK10.VK_FORMAT_R32G32B32A32_SFLOAT, VK10.VK_IMAGE_USAGE_STORAGE_BIT);
            ready = false;
            history.reset();
            LOGGER.info("[VKB-RT] {} {}x{} RGBA32F (history ping-pong + geometry + albedo band + filter scratch, {} x {} rows)", name, width, height * ROWS, ROWS, height);
        }
        if (!ready) {
            VkBarriers.transition(cmd, image.image, VK10.VK_IMAGE_ASPECT_COLOR_BIT, VK10.VK_IMAGE_LAYOUT_UNDEFINED,
                VK10.VK_IMAGE_LAYOUT_GENERAL);
            ready = true;
        }
    }

    private long lastLogNs;

    /** Every 2 s: {@code [VKB-RT] <tag> frames=<n> resets_2s=<reason:count,...>}. */
    void logIfDue(String tag, int accumCount) {
        long now = System.nanoTime();
        if (now - lastLogNs > 2_000_000_000L) {
            lastLogNs = now;
            LOGGER.info("[VKB-RT] {} frames={} last_reset={} resets_2s={}", tag, accumCount + 1, history.lastResetReason(),
                history.drainResetCounts());
        }
    }

    long view() {
        return image.view;
    }

    int width() {
        return image.width;
    }

    int height() {
        return image.height / ROWS;
    }

    void destroy() {
        if (image != null) {
            memory.destroy(image);
            image = null;
        }
    }
}
