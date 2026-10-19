package io.github.andrexarlay.vulkancompat.rt.world;

import io.github.andrexarlay.vulkancompat.rtx.upscaler.VkBarriers;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDevice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Volumetric light shafts of the key light as a post pass ({@code rt_volumetric.comp}, see its header for the algorithm): a half
 * resolution ray march with one TLAS shadow ray per step (leaves, windows and cave openings cast the shafts), temporal accumulation
 * with reprojection, then a depth aware upsample added to the final image in scene space.
 *
 * <p>Runs on the final image AFTER the denoiser / upscaler input stage, so it never touches the G-buffer of NRD or DLSS Ray
 * Reconstruction (their guide buffers stay clean) and the shafts are not smeared by a denoiser. It reuses the descriptor layout
 * of {@link RtPipeline} (TLAS, section table, atlas, material atlases) so that {@code traceVisibility} of {@code rt_common.glsl}
 * (alpha tested leaves) is the shadow test; the history image is the pipeline's accumulation binding.
 *
 * <p>The per-pass parameters travel in the 128 push constant bytes ({@link RtFrameParams}) of a private copy: strength and shaft range in
 * the unused sky colour floats, steps and "history valid" in the params2 word.
 */
final class RtVolumetricPass {
    private static final Logger LOGGER = LoggerFactory.getLogger("VKB-RT");
    private static final String DEFINES = "#define VKB_POST 1" + (char) 10 + "#define VKB_ACCUM 1" + (char) 10;

    private final VkDevice device;
    private final RtPipeline march;
    private final RtPipeline apply;
    private final RtFrameParams local = new RtFrameParams();
    private RtMemory memory;
    private RtMemory.Img history;
    private boolean historyReady;
    private long lastFrame = Long.MIN_VALUE;

    RtVolumetricPass(VkDevice device) {
        this.device = device;
        this.march = new RtPipeline(device, "/shaders/rt_volumetric.comp", "rt_volumetric.comp+march", DEFINES + "#define VOL_STAGE 0" + (char) 10,
            false, true);
        this.apply = new RtPipeline(device, "/shaders/rt_volumetric.comp", "rt_volumetric.comp+apply", DEFINES + "#define VOL_STAGE 1" + (char) 10,
            false, true);
    }

    /** Half resolution size of the history for a {@code w x h} image. */
    static int halfSize(int v) {
        return (v + 1) / 2;
    }

    /** Frames since the last recorded one are consecutive (the accumulated history can be reprojected). */
    static boolean consecutive(long last, long now) {
        return last != Long.MIN_VALUE && now - last == 1L;
    }

    /**
     * Records march + upsample into {@code cmd}. {@code views} are the views of the main pass of this frame with
     * {@code accum == 0} (the history is added here); {@code output} must be in GENERAL and hold the final image.
     */
    void record(VkCommandBuffer cmd, RtMemory mem, RtFrameParams p, RtPipeline.Views views, int w, int h, int steps, float strength,
                float range) {
        int hw = halfSize(w);
        int hh = halfSize(h);
        if (history != null && (history.width != hw || history.height != 2 * hh)) {
            VK10.vkDeviceWaitIdle(device); // frames in flight may still use the old history
            memory.destroy(history);
            history = null;
        }
        if (history == null) {
            memory = mem;
            history = mem.createImage("rt_volumetric_history", hw, 2 * hh, VK10.VK_FORMAT_R32G32B32A32_SFLOAT, VK10.VK_IMAGE_USAGE_STORAGE_BIT);
            VkBarriers.transition(cmd, history.image, VK10.VK_IMAGE_ASPECT_COLOR_BIT, VK10.VK_IMAGE_LAYOUT_UNDEFINED, VK10.VK_IMAGE_LAYOUT_GENERAL);
            historyReady = false;
            LOGGER.info("[VKB-ATMO] rt_volumetric_history {}x{} RGBA32F (half resolution, 2 frames) for {}x{}", hw, 2 * hh, w, h);
        }
        boolean valid = historyReady && consecutive(lastFrame, p.frame);
        RtPipeline.Views v = new RtPipeline.Views(views.tlas(), views.table(), views.output(), views.atlas(), views.material(),
            views.normal(), history.view, null, views.depth());
        march.bind(v);
        apply.bind(v);

        local.invViewProj.set(p.invViewProj);
        local.statsAddress = p.statsAddress;
        local.mode = p.mode;
        local.aoSamples = 0;
        local.flipY = p.flipY;
        local.tonemap = p.tonemap;
        local.accumCount = 0;
        local.frame = p.frame;
        local.flags = p.flags;
        local.rayQualities = (steps & 0xFF) | (valid ? 1 << 8 : 0);
        local.rayDistance = 0;
        local.filterIter = 0;
        local.filterIters = 0;
        System.arraycopy(p.key.dir, 0, local.key.dir, 0, 3);
        System.arraycopy(p.key.color, 0, local.key.color, 0, 3);
        local.key.day = p.key.day;
        local.key.isMoon = p.key.isMoon;
        local.skyColor[0] = strength;
        local.skyColor[1] = range;
        local.skyColor[2] = 0f;

        march.dispatch(cmd, local, hw, hh);
        VkBarriers.fullMemoryBarrier(cmd);
        apply.dispatch(cmd, local, w, h);
        VkBarriers.fullMemoryBarrier(cmd);
        historyReady = true;
        lastFrame = p.frame;
    }

    /** Forget the accumulated shafts (settings or world change). */
    void resetHistory() {
        historyReady = false;
    }

    void destroy() {
        march.destroy();   // waits for the device to go idle
        apply.destroy();
        if (history != null) {
            memory.destroy(history);
            history = null;
        }
    }
}
