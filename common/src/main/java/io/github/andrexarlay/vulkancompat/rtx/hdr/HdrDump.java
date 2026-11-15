package io.github.andrexarlay.vulkancompat.rtx.hdr;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkBufferCreateInfo;
import org.lwjgl.vulkan.VkBufferImageCopy;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkMemoryAllocateInfo;
import org.lwjgl.vulkan.VkMemoryRequirements;
import org.lwjgl.vulkan.VkPhysicalDeviceMemoryProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.LongBuffer;
import java.util.Arrays;

/**
 * Evidence tool, off by default: {@code -Dbedray.hdr.dump=<dir>} copies the PQ image the output pass produced to the host a
 * few times (upright, the engine's blit flips the PQ image on its way to the swapchain; the first at blit 30, then every 90th while the HDR scene is valid, at most four) and writes
 * {@code hdr_dump_<n>_view.png} (nits / peak, sRGB encoded: the highlights stay visible), {@code hdr_dump_<n>_clip.png}
 * (what an SDR display would show: clipped at paper white) and a {@code [VKB-HDR] dump} line with the measured nits.
 * It waits for the device before reading, so it is only for tests.
 */
final class HdrDump {
    private static final Logger LOGGER = LoggerFactory.getLogger("Bedray/HDR");
    private static final String DIR = System.getProperty("bedray.hdr.dump", "").trim();
    private static final int MAX_CAPTURES = 4;

    private static int blits;
    private static int captures;
    private static long buffer;
    private static long memory;
    private static long mappedSize;
    private static int pendingW;
    private static int pendingH;
    private static boolean pending;
    private static boolean pendingScene;

    private HdrDump() {}

    static boolean enabled() {
        return !DIR.isEmpty();
    }

    static void afterOutputPass(VkDevice device, VkCommandBuffer cmd, long pqImage, int w, int h, boolean sceneValid) {
        if (!enabled()) {
            return;
        }
        blits++;
        try {
            if (pending && blits >= pendingFrame() + 3) {
                collect(device);
            }
            boolean want = captures < MAX_CAPTURES && !pending
                && (blits == 30 || (sceneValid && blits > 30 && blits % 90 == 0));
            if (!want) {
                return;
            }
            long size = (long) w * h * 8L;
            ensureBuffer(device, size);
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkBufferImageCopy.Buffer r = VkBufferImageCopy.calloc(1, stack);
                r.imageSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
                r.imageExtent().set(w, h, 1);
                VK10.vkCmdCopyImageToBuffer(cmd, pqImage, 1, buffer, r);
            }
            pending = true;
            pendingW = w;
            pendingH = h;
            pendingScene = sceneValid;
            pendingAt = blits;
        } catch (Throwable t) {
            LOGGER.error("[VKB-HDR] dump failed", t);
            pending = false;
            captures = MAX_CAPTURES;
        }
    }

    private static int pendingAt;

    private static int pendingFrame() {
        return pendingAt;
    }

    private static void ensureBuffer(VkDevice device, long size) {
        if (buffer != 0L && mappedSize >= size) {
            return;
        }
        release(device);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer out = stack.mallocLong(1);
            VkBufferCreateInfo ci = VkBufferCreateInfo.calloc(stack).sType$Default().size(size)
                .usage(VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT).sharingMode(VK10.VK_SHARING_MODE_EXCLUSIVE);
            check(VK10.vkCreateBuffer(device, ci, null, out), "vkCreateBuffer");
            buffer = out.get(0);
            VkMemoryRequirements req = VkMemoryRequirements.calloc(stack);
            VK10.vkGetBufferMemoryRequirements(device, buffer, req);
            VkPhysicalDeviceMemoryProperties props = VkPhysicalDeviceMemoryProperties.calloc(stack);
            VK10.vkGetPhysicalDeviceMemoryProperties(device.getPhysicalDevice(), props);
            int want = VK10.VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK10.VK_MEMORY_PROPERTY_HOST_COHERENT_BIT;
            int type = -1;
            for (int i = 0; i < props.memoryTypeCount(); i++) {
                if ((req.memoryTypeBits() & (1 << i)) != 0 && (props.memoryTypes(i).propertyFlags() & want) == want) {
                    type = i;
                    break;
                }
            }
            if (type < 0) {
                throw new IllegalStateException("no host-visible coherent memory for the HDR dump");
            }
            check(VK10.vkAllocateMemory(device, VkMemoryAllocateInfo.calloc(stack).sType$Default()
                .allocationSize(req.size()).memoryTypeIndex(type), null, out), "vkAllocateMemory");
            memory = out.get(0);
            check(VK10.vkBindBufferMemory(device, buffer, memory, 0), "vkBindBufferMemory");
            mappedSize = size;
        }
    }

    private static void collect(VkDevice device) throws Exception {
        pending = false;
        VK10.vkDeviceWaitIdle(device);
        int w = pendingW;
        int h = pendingH;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer pp = stack.mallocPointer(1);
            check(VK10.vkMapMemory(device, memory, 0, (long) w * h * 8L, 0, pp), "vkMapMemory");
            ByteBuffer data = MemoryUtil.memByteBuffer(pp.get(0), w * h * 8).order(ByteOrder.LITTLE_ENDIAN);
            analyse(data, w, h, ++captures, pendingScene);
            VK10.vkUnmapMemory(device, memory);
        }
    }

    private static void analyse(ByteBuffer data, int w, int h, int n, boolean scene) throws Exception {
        float paper = HdrSettings.paperWhiteNits();
        float peak = HdrSettings.peakNits();
        BufferedImage view = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        BufferedImage clip = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        float[] lumas = new float[(w / 2) * (h / 2) + 1];
        int li = 0;
        float maxNits = 0f;
        double sum = 0;
        long overPaper = 0;
        long over2 = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int o = (y * w + x) * 8;
                float r = nits(Float.float16ToFloat(data.getShort(o)));
                float g = nits(Float.float16ToFloat(data.getShort(o + 2)));
                float b = nits(Float.float16ToFloat(data.getShort(o + 4)));
                float lum = 0.2627f * r + 0.6780f * g + 0.0593f * b;
                maxNits = Math.max(maxNits, Math.max(r, Math.max(g, b)));
                sum += lum;
                if (lum > paper * 1.05f) overPaper++;
                if (lum > paper * 2.0f) over2++;
                if ((x & 1) == 0 && (y & 1) == 0 && li < lumas.length) lumas[li++] = lum;
                view.setRGB(x, h - 1 - y, encode(r / peak, g / peak, b / peak));
                clip.setRGB(x, h - 1 - y, encode(Math.min(r, paper) / paper, Math.min(g, paper) / paper, Math.min(b, paper) / paper));
            }
        }
        Arrays.sort(lumas, 0, li);
        float p99 = li > 0 ? lumas[Math.min(li - 1, (int) (li * 0.99))] : 0f;
        File dir = new File(DIR);
        dir.mkdirs();
        ImageIO.write(view, "png", new File(dir, "hdr_dump_" + n + "_view.png"));
        ImageIO.write(clip, "png", new File(dir, "hdr_dump_" + n + "_clip.png"));
        double px = (double) w * h;
        LOGGER.info("[VKB-HDR] dump n={} file=hdr_dump_{}_view.png extent={}x{} scene={} paper_white={} peak={} max_nits={} p99_luma_nits={} mean_luma_nits={} over_paper_white={}% over_2x_paper_white={}%",
            n, n, w, h, scene, (int) paper, (int) peak, Math.round(maxNits), Math.round(p99), Math.round(sum / px),
            String.format(java.util.Locale.ROOT, "%.2f", 100.0 * overPaper / px),
            String.format(java.util.Locale.ROOT, "%.2f", 100.0 * over2 / px));
    }

    /** ST 2084 code value (BT.2020 channel) to nits. */
    private static float nits(float e) {
        double m1 = 0.1593017578125, m2 = 78.84375, c1 = 0.8359375, c2 = 18.8515625, c3 = 18.6875;
        double ep = Math.pow(Math.max(e, 0.0f), 1.0 / m2);
        double num = Math.max(ep - c1, 0.0);
        double den = c2 - c3 * ep;
        return (float) (10000.0 * Math.pow(num / den, 1.0 / m1));
    }

    /** Linear BT.2020 (1.0 = full scale) to an sRGB-encoded Rec.709 preview pixel. */
    private static int encode(float r2, float g2, float b2) {
        float r = 1.660491f * r2 - 0.587641f * g2 - 0.072850f * b2;
        float g = -0.124550f * r2 + 1.132900f * g2 - 0.008349f * b2;
        float b = -0.018151f * r2 - 0.100579f * g2 + 1.118730f * b2;
        return (to8(r) << 16) | (to8(g) << 8) | to8(b);
    }

    private static int to8(float lin) {
        float c = Math.max(0f, Math.min(1f, lin));
        double s = c <= 0.0031308f ? c * 12.92 : 1.055 * Math.pow(c, 1.0 / 2.4) - 0.055;
        return (int) Math.round(Math.max(0.0, Math.min(1.0, s)) * 255.0);
    }

    static void release(VkDevice device) {
        if (device == null) {
            return;
        }
        if (buffer != 0L) {
            VK10.vkDestroyBuffer(device, buffer, null);
            buffer = 0L;
        }
        if (memory != 0L) {
            VK10.vkFreeMemory(device, memory, null);
            memory = 0L;
        }
        mappedSize = 0;
        pending = false;
    }

    private static void check(int result, String what) {
        if (result != VK10.VK_SUCCESS) {
            throw new IllegalStateException(what + " returned " + result);
        }
    }
}
