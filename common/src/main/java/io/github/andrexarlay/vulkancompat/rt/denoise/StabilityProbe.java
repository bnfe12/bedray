package io.github.andrexarlay.vulkancompat.rt.denoise;

import io.github.andrexarlay.vulkancompat.rt.world.RtMemory;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkBufferImageCopy;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Diagnostic (autotest only): temporal stability of the FINAL image while the camera moves.
 * <p>
 * The MV metric of {@link MotionProbe} compares the GPU motion vectors with a CPU reference on one frame; it cannot see
 * flicker, jitter shake, trails or a view matrix that differs between the RT image and the raster. This probe records, for a
 * run of consecutive RT frames, the primary-hit distance image ({@code rt_depth}, distance along the pixel ray, sky 1e6)
 * together with that frame's camera matrices. Consecutive screenshots of the main render target are then scored: every
 * geometry pixel of frame k+1 is reprojected into frame k with the CPU (current to previous, the DLSS convention) and the
 * luminances are compared. {@code naive_err} is the same without reprojection, so {@code err / naive_err} is the share of
 * the change that the camera motion does not explain (shake, flicker, trails).
 */
public final class StabilityProbe {
    /** Reference of one frame: ray distance image + matrices of that frame (camera-relative space). */
    public record Reference(float[] dist, int w, int h, Matrix4f invViewProj, Matrix4f prevViewProj, double dx, double dy, double dz) {}

    private static final class Slot {
        RtMemory.Buf buf;
        final Matrix4f inv = new Matrix4f();
        final Matrix4f prev = new Matrix4f();
        double dx;
        double dy;
        double dz;
    }

    private static volatile int seqRemaining;
    private static volatile boolean seqDone;
    private static int seqLength;
    private static Slot[] slots = new Slot[0];
    private static RtMemory owner;
    private static int pw;
    private static int ph;
    private static int recorded;
    private static int settleFrames;
    private static volatile List<Reference> sequence = List.of();

    private StabilityProbe() {}

    /** Records the next {@code frames} RT frames. */
    public static void startSequence(int frames) {
        sequence = List.of();
        seqDone = false;
        seqLength = frames;
        recorded = 0;
        settleFrames = -1;
        seqRemaining = frames;
    }

    public static boolean done() {
        return seqDone;
    }

    public static List<Reference> sequence() {
        return sequence;
    }

    public static boolean recording() {
        return seqRemaining > 0;
    }

    /** Records the copy of {@code depthImage} (R32F, GENERAL, dispatch finished and a barrier issued). */
    public static void record(VkCommandBuffer cmd, RtMemory memory, long depthImage, int w, int h, Matrix4f invViewProj,
                              Matrix4f prevViewProj, double dx, double dy, double dz) {
        if (seqRemaining <= 0) {
            return;
        }
        long need = (long) w * h * 4L;
        if (slots.length != seqLength || owner != memory || pw != w || ph != h) {
            if (owner != null) {
                VK10.vkDeviceWaitIdle(memory.device());
                for (Slot s : slots) {
                    if (s != null && s.buf != null) {
                        owner.destroy(s.buf);
                    }
                }
            }
            slots = new Slot[seqLength];
            owner = memory;
            pw = w;
            ph = h;
        }
        Slot s = slots[recorded];
        if (s == null) {
            s = new Slot();
            slots[recorded] = s;
        }
        if (s.buf == null) {
            s.buf = memory.createBuffer("stability_probe_" + recorded, need, VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT, true);
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkBufferImageCopy.Buffer r = VkBufferImageCopy.calloc(1, stack);
            r.get(0).bufferOffset(0).imageSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
            r.get(0).imageExtent().set(w, h, 1);
            VK10.vkCmdCopyImageToBuffer(cmd, depthImage, VK10.VK_IMAGE_LAYOUT_GENERAL, s.buf.handle, r);
        }
        s.inv.set(invViewProj);
        s.prev.set(prevViewProj);
        s.dx = dx;
        s.dy = dy;
        s.dz = dz;
        recorded++;
        seqRemaining--;
        if (seqRemaining == 0) {
            settleFrames = 4;
        }
    }

    /** Once per RT frame: reads the slots back when the GPU work of the last recorded frame is certainly done. */
    public static void tick() {
        if (settleFrames < 0 || --settleFrames > 0) {
            return;
        }
        settleFrames = -1;
        List<Reference> out = new ArrayList<>();
        for (int i = 0; i < recorded; i++) {
            Slot s = slots[i];
            float[] d = new float[pw * ph];
            for (int k = 0; k < d.length; k++) {
                d[k] = MemoryUtil.memGetFloat(s.buf.mapped + k * 4L);
            }
            out.add(new Reference(d, pw, ph, new Matrix4f(s.inv), new Matrix4f(s.prev), s.dx, s.dy, s.dz));
        }
        sequence = out;
        seqDone = true;
    }

    private static float luma(int argb) {
        return (((argb >> 16) & 255) + ((argb >> 8) & 255) + (argb & 255)) / 3f;
    }

    private static float sample(int[] img, int w, int h, float px, float py) {
        float fx = px - 0.5f;
        float fy = py - 0.5f;
        int x0 = (int) Math.floor(fx);
        int y0 = (int) Math.floor(fy);
        float tx = fx - x0;
        float ty = fy - y0;
        float acc = 0;
        for (int j = 0; j < 2; j++) {
            for (int i = 0; i < 2; i++) {
                int x = Math.min(w - 1, Math.max(0, x0 + i));
                int y = Math.min(h - 1, Math.max(0, y0 + j));
                acc += luma(img[y * w + x]) * (i == 0 ? 1 - tx : tx) * (j == 0 ? 1 - ty : ty);
            }
        }
        return acc;
    }

    /**
     * Score of one consecutive pair, {@code ref} = reference of the NEWER frame. {@code prev}/{@code cur}: screen images (row 0
     * = top) of size sw x sh. Returns {@code [err, naiveErr, covered]} (luminance 0..255; covered = share of geometry pixels
     * that reproject inside the screen).
     */
    public static double[] score(Reference ref, int[] prev, int[] cur, int sw, int sh) {
        double sumErr = 0;
        double sumNaive = 0;
        double sumEdge = 0;
        double sumFlat = 0;
        long nEdge = 0;
        long nFlat = 0;
        long n = 0;
        long total = 0;
        for (int sy = 2; sy < sh - 2; sy += 2) {
            for (int sx = 2; sx < sw - 2; sx += 2) {
                // the RT images store the bottom row first (NDC y = -1 is row 0), screens the top row first
                int rx = Math.min(ref.w() - 1, sx * ref.w() / sw);
                int ry = Math.min(ref.h() - 1, (sh - 1 - sy) * ref.h() / sh);
                float t = ref.dist()[ry * ref.w() + rx];
                if (!(t < 1.0e5f) || t <= 0f) {
                    continue;
                }
                total++;
                float ndcX = (rx + 0.5f) / ref.w() * 2f - 1f;
                float ndcY = (ry + 0.5f) / ref.h() * 2f - 1f;
                Vector4f d = ref.invViewProj().transform(new Vector4f(ndcX, ndcY, 0.5f, 1f));
                float ux = d.x / d.w;
                float uy = d.y / d.w;
                float uz = d.z / d.w;
                float dl = (float) Math.sqrt(ux * ux + uy * uy + uz * uz);
                Vector4f pc = ref.prevViewProj().transform(new Vector4f(
                    (float) (ux / dl * t + ref.dx()), (float) (uy / dl * t + ref.dy()), (float) (uz / dl * t + ref.dz()), 1f));
                if (pc.w < 1e-4f) {
                    continue;
                }
                float u = (pc.x / pc.w) * 0.5f + 0.5f;
                float v = (pc.y / pc.w) * 0.5f + 0.5f;
                if (u < 0.02f || u > 0.98f || v < 0.02f || v > 0.98f) {
                    continue;
                }
                float a = luma(cur[sy * sw + sx]);
                float e = Math.abs(a - sample(prev, sw, sh, u * sw, (1f - v) * sh));
                sumErr += e;
                sumNaive += Math.abs(a - luma(prev[sy * sw + sx]));
                n++;
                // geometry edge = a neighbour 2 ref pixels away at a clearly different distance (block silhouettes, sky/ground)
                boolean edge = false;
                for (int k = 0; k < 4 && !edge; k++) {
                    int nx = Math.max(0, Math.min(ref.w() - 1, rx + (k == 0 ? -2 : k == 1 ? 2 : 0)));
                    int ny = Math.max(0, Math.min(ref.h() - 1, ry + (k == 2 ? -2 : k == 3 ? 2 : 0)));
                    float tn = ref.dist()[ny * ref.w() + nx];
                    edge = Math.abs(tn - t) > 0.04f * t || tn >= 1.0e5f;
                }
                if (edge) {
                    sumEdge += e;
                    nEdge++;
                } else {
                    sumFlat += e;
                    nFlat++;
                }
            }
        }
        return new double[] {n > 0 ? sumErr / n : -1, n > 0 ? sumNaive / n : -1, total > 0 ? (double) n / total : 0,
            nEdge > 0 ? sumEdge / nEdge : -1, nFlat > 0 ? sumFlat / nFlat : -1, n > 0 ? (double) nEdge / n : 0};
    }

    public static String format(double[] s) {
        return String.format(Locale.ROOT, "err=%.3f naive_err=%.3f covered=%.2f", s[0], s[1], s[2]);
    }
}
