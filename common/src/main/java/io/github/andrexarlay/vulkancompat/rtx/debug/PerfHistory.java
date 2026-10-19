package io.github.andrexarlay.vulkancompat.rtx.debug;

import java.util.Locale;

/**
 * Ring of the performance windows published by {@link PerfMonitor} (one per 500 ms), kept only in memory for the diagnostics
 * report: frame time, fps and GPU milliseconds per phase. 240 windows = the last two minutes. Pure Java apart from {@link #sample()}.
 */
public final class PerfHistory {
    public static final int CAPACITY = 240;
    /** Phases kept per window (PerfMonitor.Segment names, lower case). */
    public static final String[] PHASES = {"pre", "world", "rt", "post", "upscale", "gui", "hooks", "blit", "total"};

    private final long[] timeNs = new long[CAPACITY];
    private final double[] frameMs = new double[CAPACITY];
    private final double[][] gpuMs = new double[PHASES.length][CAPACITY];
    private final boolean[][] gpuOk = new boolean[PHASES.length][CAPACITY];
    private int head;
    private int size;

    /** Result of {@link #summary}: averages over the windows of the last N seconds. */
    public record Summary(int windows, double seconds, double avgFrameMs, double avgFps, double worstFrameMs,
                          double[] avgGpuMs, boolean[] gpuSeen) {
        public String format() {
            if (windows == 0) {
                return "no performance data yet";
            }
            return String.format(Locale.ROOT, "avg frame %.2f ms (%.1f fps), worst window %.2f ms, %d windows / %.0f s",
                avgFrameMs, avgFps, worstFrameMs, windows, seconds);
        }

        public String formatGpu() {
            StringBuilder b = new StringBuilder(120);
            for (int i = 0; i < PHASES.length; i++) {
                if (gpuSeen[i]) {
                    b.append(b.length() == 0 ? "" : ", ").append(PHASES[i]).append(' ').append(String.format(Locale.ROOT, "%.2f", avgGpuMs[i]));
                }
            }
            return b.length() == 0 ? "GPU timestamps not available" : b.append(" (ms)").toString();
        }
    }

    private static final PerfHistory INSTANCE = new PerfHistory();

    public static PerfHistory instance() {
        return INSTANCE;
    }

    /** Called by {@link DebugInfo#onPerfPublished()} at the end of every window. */
    static void sample() {
        double[] gpu = new double[PHASES.length];
        boolean[] ok = new boolean[PHASES.length];
        for (PerfMonitor.Segment sg : PerfMonitor.Segment.values()) {
            String n = sg.name().toLowerCase(Locale.ROOT);
            for (int i = 0; i < PHASES.length; i++) {
                if (PHASES[i].equals(n)) {
                    ok[i] = PerfMonitor.gpuValid(sg);
                    gpu[i] = ok[i] ? PerfMonitor.gpuMs(sg) : 0.0;
                }
            }
        }
        INSTANCE.record(System.nanoTime(), PerfMonitor.frameIntervalMs(), gpu, ok);
    }

    /** Adds one window. {@code frame <= 0} (no frames yet) is stored but ignored by {@link #summary}. */
    public synchronized void record(long nowNs, double frame, double[] gpu, boolean[] ok) {
        timeNs[head] = nowNs;
        frameMs[head] = frame;
        for (int i = 0; i < PHASES.length; i++) {
            gpuMs[i][head] = gpu[i];
            gpuOk[i][head] = ok[i];
        }
        head = (head + 1) % CAPACITY;
        size = Math.min(size + 1, CAPACITY);
    }

    public synchronized void clear() {
        head = 0;
        size = 0;
    }

    /** Averages over the windows whose time is within {@code seconds} of {@code nowNs}. */
    public synchronized Summary summary(long nowNs, double seconds) {
        long limit = nowNs - (long) (seconds * 1.0e9);
        int n = 0;
        double sum = 0;
        double worst = 0;
        double[] gsum = new double[PHASES.length];
        int[] gcnt = new int[PHASES.length];
        long oldest = nowNs;
        for (int k = 0; k < size; k++) {
            int idx = (head - 1 - k + CAPACITY) % CAPACITY;
            if (timeNs[idx] < limit) {
                break;
            }
            if (!(frameMs[idx] > 0.0)) {
                continue;
            }
            n++;
            sum += frameMs[idx];
            worst = Math.max(worst, frameMs[idx]);
            oldest = Math.min(oldest, timeNs[idx]);
            for (int i = 0; i < PHASES.length; i++) {
                if (gpuOk[i][idx]) {
                    gsum[i] += gpuMs[i][idx];
                    gcnt[i]++;
                }
            }
        }
        double[] avg = new double[PHASES.length];
        boolean[] seen = new boolean[PHASES.length];
        for (int i = 0; i < PHASES.length; i++) {
            seen[i] = gcnt[i] > 0;
            avg[i] = seen[i] ? gsum[i] / gcnt[i] : 0.0;
        }
        double avgFrame = n > 0 ? sum / n : 0.0;
        double span = n > 0 ? Math.min(seconds, (nowNs - oldest) / 1.0e9 + 0.5) : 0.0;
        return new Summary(n, span, avgFrame, avgFrame > 0 ? 1000.0 / avgFrame : 0.0, worst, avg, seen);
    }
}
