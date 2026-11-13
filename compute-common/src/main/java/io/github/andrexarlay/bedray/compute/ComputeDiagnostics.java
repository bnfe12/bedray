package io.github.andrexarlay.bedray.compute;

import java.util.Arrays;
import java.util.Locale;

/**
 * Per-provider frame diagnostics: CPU preparation/record times, GPU region times harvested without
 * waiting, upload counters and the temporal-history state. The host polls {@link #summarizeAndReset()}
 * at most once per second; the offscreen baseline runner polls the same snapshot per measurement window.
 * All durations are nanoseconds; percentiles use the nearest-rank method over per-frame GPU totals.
 */
public final class ComputeDiagnostics {
    /** Bounds one window when nobody polls (e.g. a paused host); at 60 fps a second needs only 60 slots. */
    private static final int MAX_SAMPLES = 4096;
    public static final int GPU_COUNTER_COUNT = 8;

    private int frames, gpuSamples, gpuTotalCount;
    private long uploadPrepNs, recordNs, gpuUploadNs, gpuDispatchNs, gpuBlitNs;
    private final long[] gpuTotals = new long[MAX_SAMPLES];
    private long sections, cubes, models, quads, bytes;
    private int emitters, emittersFound;
    private String history = "off";
    private long[] gpuCounters;

    public void frame() { frames++; }
    public void addUploadPrepNs(long nanos) { uploadPrepNs += nanos; }
    public void addRecordNs(long nanos) { recordNs += nanos; }

    public void addGpuSample(long uploadNs, long dispatchNs, long blitNs) {
        gpuSamples++;
        gpuUploadNs += uploadNs; gpuDispatchNs += dispatchNs; gpuBlitNs += blitNs;
        if (gpuTotalCount < MAX_SAMPLES) gpuTotals[gpuTotalCount++] = uploadNs + dispatchNs + blitNs;
    }

    public void addUpload(long sections, long cubes, long models, long quads, long bytes) {
        this.sections += sections; this.cubes += cubes; this.models += models; this.quads += quads; this.bytes += bytes;
    }

    public void setScene(int emitters, int emittersFound, String history) {
        this.emitters = emitters; this.emittersFound = emittersFound; this.history = history;
    }

    /** Accumulates per-frame GPU history-counter deltas; only called while the debug flag is enabled. */
    public void addGpuCounters(long[] deltas) {
        if (gpuCounters == null) gpuCounters = new long[GPU_COUNTER_COUNT];
        for (int i = 0; i < GPU_COUNTER_COUNT; i++) gpuCounters[i] += deltas[i];
    }

    /** Immutable window result; GPU counters are null unless the debug flag produced samples. */
    public record Snapshot(int frames, long uploadPrepNs, long recordNs, long gpuUploadNs, long gpuDispatchNs,
                           long gpuBlitNs, int gpuSamples, long p50Ns, long p95Ns, long p99Ns,
                           long sections, long cubes, long models, long quads, long bytes,
                           int emitters, int emittersFound, String history, long[] gpuCounters) {
        public long gpuTotalNs() { return gpuUploadNs + gpuDispatchNs + gpuBlitNs; }
    }

    public Snapshot snapshotAndReset() {
        long[] sorted = Arrays.copyOf(gpuTotals, gpuTotalCount);
        Arrays.sort(sorted);
        Snapshot snapshot = new Snapshot(frames, uploadPrepNs, recordNs, gpuUploadNs, gpuDispatchNs, gpuBlitNs,
            gpuSamples, percentile(sorted, sorted.length, 0.50), percentile(sorted, sorted.length, 0.95),
            percentile(sorted, sorted.length, 0.99), sections, cubes, models, quads, bytes,
            emitters, emittersFound, history, gpuCounters);
        frames = gpuSamples = gpuTotalCount = 0;
        uploadPrepNs = recordNs = gpuUploadNs = gpuDispatchNs = gpuBlitNs = 0;
        sections = cubes = models = quads = bytes = 0;
        emitters = emittersFound = 0;
        history = "off";
        gpuCounters = null;
        return snapshot;
    }

    public String summarizeAndReset() { return format(snapshotAndReset()); }

    /** Nearest-rank percentile of an ascending array; zero for an empty window. */
    static long percentile(long[] sorted, int count, double quantile) {
        if (count <= 0) return 0;
        int rank = (int) Math.ceil(quantile * count);
        return sorted[Math.min(count - 1, Math.max(0, rank - 1))];
    }

    public static String format(Snapshot s) {
        if (s.frames() == 0) return "";
        StringBuilder line = new StringBuilder(256);
        line.append("frames=").append(s.frames());
        appendMs(line, "upload_ms", s.uploadPrepNs() / (double) s.frames());
        appendMs(line, "record_ms", s.recordNs() / (double) s.frames());
        if (s.gpuSamples() > 0) {
            appendMs(line, "gpu_upload_ms", s.gpuUploadNs() / (double) s.gpuSamples());
            appendMs(line, "dispatch_ms", s.gpuDispatchNs() / (double) s.gpuSamples());
            appendMs(line, "blit_ms", s.gpuBlitNs() / (double) s.gpuSamples());
            appendMs(line, "gpu_total_ms", s.gpuTotalNs() / (double) s.gpuSamples());
            appendMs(line, "p50_ms", s.p50Ns());
            appendMs(line, "p95_ms", s.p95Ns());
            appendMs(line, "p99_ms", s.p99Ns());
        } else {
            line.append(" gpu=unavailable");
        }
        line.append(" sections=").append(s.sections()).append(" cubes=").append(s.cubes())
            .append(" models=").append(s.models()).append(" quads=").append(s.quads())
            .append(" bytes=").append(s.bytes())
            .append(" emitters=").append(s.emitters()).append('/').append(s.emittersFound())
            .append(" history=").append(s.history());
        if (s.gpuCounters() != null) {
            long[] c = s.gpuCounters();
            line.append(" hist_ok=").append(c[0]).append(" hist_dist=").append(c[1]).append(" hist_nrm=").append(c[2])
                .append(" hist_empty=").append(c[3]).append(" hist_bounds=").append(c[4]).append(" hist_invalid=").append(c[5])
                .append(" hist_nan=").append(c[6]).append(" hist_low=").append(c[7]);
        }
        return line.toString();
    }

    private static void appendMs(StringBuilder line, String key, double nanos) {
        line.append(' ').append(key).append('=').append(String.format(Locale.ROOT, "%.3f", nanos * 1.0e-6));
    }
}