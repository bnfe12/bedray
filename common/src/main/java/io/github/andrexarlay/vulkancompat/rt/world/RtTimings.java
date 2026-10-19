package io.github.andrexarlay.vulkancompat.rt.world;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.Locale;

/**
 * Measurement M1 of ADR-0001 (local reference): CPU time of RtWorld.update split into phases,
 * per frame, reported as p50/p95/p99/max over {@link #WINDOW} frames, plus Sodium-worker decode time per section. Only active
 * with {@code -Dvkrtx.rt.timings=true}; logs only ({@code [VKB-RT-M1] ...}), no behaviour change.
 */
public final class RtTimings {
    public enum Phase { RESCAN, STAGE_COPY, VK_SIZES, VK_CREATE, VK_ADDR, RECORD_BLAS, TLAS_PACK, TLAS_RECORD, UPDATE_TOTAL }

    public static final boolean ENABLED = Boolean.getBoolean("vkrtx.rt.timings");
    public static final int WINDOW = 600;
    private static final Logger LOGGER = LoggerFactory.getLogger("VKB-RT");

    private static final long[] frame = new long[Phase.values().length];
    private static final long[][] window = new long[Phase.values().length][WINDOW];
    private static int frames;
    private static int rescans;
    private static int blasBuilt;
    private static final long[] decodeNs = new long[4096];
    private static final long[] decodeBytes = new long[4096];
    private static int decodes;

    private RtTimings() {
    }

    public static long start() {
        return ENABLED ? System.nanoTime() : 0L;
    }

    /** Adds the time since {@code t0} (from {@link #start()}) to {@code phase} of the current frame. */
    public static void stop(Phase phase, long t0) {
        if (ENABLED) {
            frame[phase.ordinal()] += System.nanoTime() - t0;
        }
    }

    public static void countRescan() {
        if (ENABLED) {
            rescans++;
        }
    }

    public static void countBlas(int n) {
        if (ENABLED) {
            blasBuilt += n;
        }
    }

    /** Sodium worker thread: one decoded section. */
    public static void decode(long ns, long bytes) {
        if (!ENABLED) {
            return;
        }
        synchronized (decodeNs) {
            if (decodes < decodeNs.length) {
                decodeNs[decodes] = ns;
                decodeBytes[decodes] = bytes;
                decodes++;
            }
        }
    }

    /** Render thread, after RtWorld.update: closes the frame and logs a window every {@link #WINDOW} frames. */
    public static void endFrame() {
        if (!ENABLED) {
            return;
        }
        for (int p = 0; p < frame.length; p++) {
            window[p][frames] = frame[p];
            frame[p] = 0L;
        }
        frames++;
        if (frames < WINDOW) {
            return;
        }
        StringBuilder sb = new StringBuilder("[VKB-RT-M1] window_frames=").append(WINDOW).append(" rescans=").append(rescans)
            .append(" blas_built=").append(blasBuilt);
        for (Phase ph : Phase.values()) {
            long[] v = Arrays.copyOf(window[ph.ordinal()], WINDOW);
            Arrays.sort(v);
            sb.append(String.format(Locale.ROOT, " %s_us=p50:%.1f/p95:%.1f/p99:%.1f/max:%.1f", ph.name().toLowerCase(Locale.ROOT),
                v[WINDOW / 2] / 1e3, v[(int) (WINDOW * 0.95)] / 1e3, v[(int) (WINDOW * 0.99)] / 1e3, v[WINDOW - 1] / 1e3));
        }
        synchronized (decodeNs) {
            if (decodes > 0) {
                long[] d = Arrays.copyOf(decodeNs, decodes);
                long[] b = Arrays.copyOf(decodeBytes, decodes);
                Arrays.sort(d);
                Arrays.sort(b);
                sb.append(String.format(Locale.ROOT, " decode_us=p50:%.1f/p95:%.1f/max:%.1f decode_bytes=p50:%d/p95:%d sections=%d",
                    d[decodes / 2] / 1e3, d[(int) (decodes * 0.95)] / 1e3, d[decodes - 1] / 1e3, b[decodes / 2],
                    b[(int) (decodes * 0.95)], decodes));
            }
            decodes = 0;
        }
        LOGGER.info(sb.toString());
        frames = 0;
        rescans = 0;
        blasBuilt = 0;
    }
}
