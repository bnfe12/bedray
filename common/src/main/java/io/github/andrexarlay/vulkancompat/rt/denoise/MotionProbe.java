package io.github.andrexarlay.vulkancompat.rt.denoise;

import io.github.andrexarlay.vulkancompat.rt.world.RtMemory;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkBufferImageCopy;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Debug readback of the DLSS-RR temporal inputs (diagnostic only, armed by the autotest): copies the linear view-Z G-buffer
 * and the motion vector image of one frame to a host buffer, then (a few frames later, when the GPU is done) writes
 * The PNGs are flipped vertically to match the screen (the RT images store the bottom row first: row 0 = NDC y -1 of the
 * GL-style projection; MV and all DLSS-RR inputs share that storage, so they are consistent with each other).
 * <ul>
 *   <li>a motion-vector visualisation PNG (hue = direction, brightness = length, 8 px = full; sky/no-hit = dark blue),</li>
 *   <li>a linear depth PNG and a disocclusion PNG (MV pointing outside the screen),</li>
 *   <li>a log line {@code [VKB-MOTION]} with MV statistics of the geometry pixels and the error of the GPU MV against an
 *       independent CPU reprojection that uses the same view-Z, matrices and camera delta (DLSS convention, Programming
 *       Guide 3.6 / 3.6.1: current pixel -> previous pixel, render-resolution pixels, origin upper-left).</li>
 * </ul>
 */
public final class MotionProbe {
    private static final Logger LOGGER = LoggerFactory.getLogger("Bedray/MotionProbe");

    private static volatile Path armedDir;
    private static volatile String armedLabel;
    private static volatile String lastResult;

    private static RtMemory.Buf buf;
    private static RtMemory owner;
    private static int pendingW;
    private static int pendingH;
    private static int pendingFrames = -1;
    private static Path pendingDir;
    private static String pendingLabel;
    private static final Matrix4f pInv = new Matrix4f();
    private static final Matrix4f pPrev = new Matrix4f();
    private static double pdx;
    private static double pdy;
    private static double pdz;
    private static boolean pReset;
    private static boolean pHasMask;
    private static boolean pRayDistance;

    private MotionProbe() {}

    /** Autotest: capture the next DLSS-RR frame into {@code dir/<label>_*.png}. */
    public static void arm(Path dir, String label) {
        lastResult = null;
        armedLabel = label;
        armedDir = dir;
    }

    /** Result line of the last finished capture, or null while pending. */
    public static String lastResult() {
        return lastResult;
    }

    public static boolean armed() {
        return armedDir != null && pendingFrames < 0;
    }

    /** Records the copies (both images in GENERAL, after the velocity dispatch and a barrier). */
    static void record(VkCommandBuffer cmd, RtMemory memory, RtMemory.Img viewZ, RtMemory.Img mv, RtMemory.Img mask, int w, int h,
                       Matrix4f invViewProj, Matrix4f prevViewProj, Vec3 camDelta, boolean reset) {
        record(cmd, memory, viewZ.image, mv.image, mask != null ? mask.image : 0L, w, h, invViewProj, prevViewProj, camDelta, reset, false);
    }

    /**
     * Raw-handle variant (all images R32F / RG16F / R32F in GENERAL). {@code rayDistance}: the depth image holds the RT
     * {@code outDepth} (distance along the primary ray) instead of the linear view Z; the CPU reference then uses it directly.
     */
    public static void record(VkCommandBuffer cmd, RtMemory memory, long viewZImage, long mvImage, long maskImage, int w, int h,
                              Matrix4f invViewProj, Matrix4f prevViewProj, Vec3 camDelta, boolean reset, boolean rayDistance) {
        pRayDistance = rayDistance;
        long need = (long) w * h * 12L;
        if (buf == null || buf.size < need || owner != memory) {
            if (buf != null && owner != null) {
                VK10.vkDeviceWaitIdle(memory.device());
                owner.destroy(buf);
            }
            buf = memory.createBuffer("motion_probe", need, VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT, true);
            owner = memory;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkBufferImageCopy.Buffer r = VkBufferImageCopy.calloc(1, stack);
            r.get(0).bufferOffset(0).imageSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
            r.get(0).imageExtent().set(w, h, 1);
            VK10.vkCmdCopyImageToBuffer(cmd, viewZImage, VK10.VK_IMAGE_LAYOUT_GENERAL, buf.handle, r);
            r.get(0).bufferOffset((long) w * h * 4L);
            VK10.vkCmdCopyImageToBuffer(cmd, mvImage, VK10.VK_IMAGE_LAYOUT_GENERAL, buf.handle, r);
            if (maskImage != 0L) {
                r.get(0).bufferOffset((long) w * h * 8L);
                VK10.vkCmdCopyImageToBuffer(cmd, maskImage, VK10.VK_IMAGE_LAYOUT_GENERAL, buf.handle, r);
            }
        }
        pHasMask = maskImage != 0L;
        pInv.set(invViewProj);
        pPrev.set(prevViewProj);
        pdx = camDelta.x;
        pdy = camDelta.y;
        pdz = camDelta.z;
        pReset = reset;
        pendingW = w;
        pendingH = h;
        pendingDir = armedDir;
        pendingLabel = armedLabel;
        armedDir = null;
        pendingFrames = 4;
    }

    /** Called once per DLSS-RR frame; evaluates a capture whose GPU work is certainly finished. */
    public static void tick() {
        if (pendingFrames < 0) {
            return;
        }
        if (--pendingFrames > 0) {
            return;
        }
        pendingFrames = -1;
        try {
            lastResult = evaluate();
        } catch (Throwable t) {
            lastResult = "error=" + t;
            LOGGER.error("[VKB-MOTION] evaluation failed", t);
        }
        LOGGER.info("[VKB-MOTION] label={} {}", pendingLabel, lastResult);
    }

    private static float half(short h) {
        return Float.float16ToFloat(h);
    }

    private static String evaluate() throws Exception {
        int w = pendingW;
        int h = pendingH;
        long base = buf.mapped;
        long mvBase = base + (long) w * h * 4L;
        BufferedImage mvImg = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        BufferedImage zImg = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        BufferedImage occImg = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Vector4f c = pInv.transform(new Vector4f(0f, 0f, 0.5f, 1f));
        float fx = c.x / c.w;
        float fy = c.y / c.w;
        float fz = c.z / c.w;
        float fl = (float) Math.sqrt(fx * fx + fy * fy + fz * fz);
        fx /= fl;
        fy /= fl;
        fz /= fl;
        long geo = 0;
        long zeroMv = 0;
        long offscreen = 0;
        long compared = 0;
        double sumLen = 0;
        double sumErr = 0;
        double maxErr = 0;
        double sumRefLen = 0;
        int negZ = 0;
        int posZ = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                float vz = MemoryUtil.memGetFloat(base + i * 4L);
                float mx = half(MemoryUtil.memGetShort(mvBase + i * 4L));
                float my = half(MemoryUtil.memGetShort(mvBase + i * 4L + 2));
                boolean sky = !(Math.abs(vz) < 1.0e5f);
                float len = (float) Math.hypot(mx, my);
                // MV view: hue = direction, value = min(len / 8 px, 1)
                float hue = (float) ((Math.atan2(my, mx) / (2 * Math.PI)) + 1.0) % 1.0f;
                int rgb = java.awt.Color.HSBtoRGB(hue, len > 1e-3f ? 1f : 0f, Math.min(1f, len / 8f));
                mvImg.setRGB(x, h - 1 - y, sky ? (rgb | 0x000040) : rgb);
                int zv = sky ? 0x002040 : Math.max(0, 255 - (int) (Math.abs(vz) * 4f)) * 0x010101;
                zImg.setRGB(x, h - 1 - y, zv);
                float px = x + 0.5f + mx;
                float py = y + 0.5f + my;
                boolean occ = pHasMask ? MemoryUtil.memGetFloat(base + (long) w * h * 8L + i * 4L) > 0.5f
                    : (px < 0 || py < 0 || px >= w || py >= h);
                occImg.setRGB(x, h - 1 - y, occ ? 0xFF0000 : (sky ? 0x002040 : 0x202020));
                if (sky) {
                    continue;
                }
                geo++;
                if (vz < 0) {
                    negZ++;
                } else {
                    posZ++;
                }
                sumLen += len;
                if (len < 1.0e-3f) {
                    zeroMv++;
                }
                if (occ) {
                    offscreen++;
                }
                if (((x | y) & 7) == 0) {
                    // independent CPU reference: ray of the pixel centre (unjittered) * |viewZ| / cos -> camera-relative pos
                    float ndcX = (x + 0.5f) / w * 2f - 1f;
                    float ndcY = (y + 0.5f) / h * 2f - 1f;
                    Vector4f d = pInv.transform(new Vector4f(ndcX, ndcY, 0.5f, 1f));
                    float dx = d.x / d.w;
                    float dy = d.y / d.w;
                    float dz = d.z / d.w;
                    float dl = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
                    dx /= dl;
                    dy /= dl;
                    dz /= dl;
                    float cos = dx * fx + dy * fy + dz * fz;
                    if (cos < 1e-3f) {
                        continue;
                    }
                    float t = pRayDistance ? vz : Math.abs(vz) / cos;
                    Vector4f pc = pPrev.transform(new Vector4f((float) (dx * t + pdx), (float) (dy * t + pdy), (float) (dz * t + pdz), 1f));
                    if (Math.abs(pc.w) < 1e-6f) {
                        continue;
                    }
                    float rx = ((pc.x / pc.w) * 0.5f + 0.5f) * w - (x + 0.5f);
                    float ry = ((pc.y / pc.w) * 0.5f + 0.5f) * h - (y + 0.5f);
                    double err = Math.hypot(rx - mx, ry - my);
                    compared++;
                    sumErr += err;
                    sumRefLen += Math.hypot(rx, ry);
                    maxErr = Math.max(maxErr, err);
                }
            }
        }
        Path dir = pendingDir;
        Files.createDirectories(dir);
        ImageIO.write(mvImg, "png", dir.resolve(pendingLabel + "_mv.png").toFile());
        ImageIO.write(zImg, "png", dir.resolve(pendingLabel + "_depth.png").toFile());
        ImageIO.write(occImg, "png", dir.resolve(pendingLabel + "_disocclusion.png").toFile());
        return String.format(Locale.ROOT,
            "depth=%s mask=%s size=%dx%d reset=%s camDelta=%.4f,%.4f,%.4f geo_px=%d viewZ_neg=%d viewZ_pos=%d mv_mean_px=%.3f mv_zero_frac=%.3f"
                + " offscreen_frac=%.3f ref_samples=%d ref_mean_px=%.3f gpu_vs_ref_mean_err_px=%.4f gpu_vs_ref_max_err_px=%.4f",
            pRayDistance ? "ray_distance" : "view_z", pHasMask ? "dlss_rr_input" : "mv_offscreen", w, h, pReset, pdx, pdy, pdz, geo, negZ, posZ, geo > 0 ? sumLen / geo : 0, geo > 0 ? (double) zeroMv / geo : 0,
            geo > 0 ? (double) offscreen / geo : 0, compared, compared > 0 ? sumRefLen / compared : 0,
            compared > 0 ? sumErr / compared : 0, maxErr);
    }
}
