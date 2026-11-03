package io.github.andrexarlay.vulkancompat.rtx.debug.benchmark;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Bounded, render-thread-only raw samples. No Minecraft, driver calls, files or per-sample formatting. */
public final class FrameCapture {
    public static final int WORLD = 1, PATH_RECORDED = 2, NATIVE_RESOLUTION = 4, UPSCALER_OFF = 8,
        FRAMEGEN_OFF = 16, SCREEN_CLOSED = 32, FOCUSED = 64;
    public static final int NATIVE_REQUIRED = WORLD | PATH_RECORDED | NATIVE_RESOLUTION | UPSCALER_OFF
        | FRAMEGEN_OFF | SCREEN_CLOSED | FOCUSED;

    public record Config(long warmupNs, long durationNs, int maxFrames, long drainNs) {
        public Config {
            if (warmupNs < 0 || warmupNs > 300_000_000_000L || durationNs <= 0 || durationNs > 300_000_000_000L
                || maxFrames < 1 || maxFrames > 120_000 || drainNs <= 0 || drainNs > 30_000_000_000L)
                throw new IllegalArgumentException("invalid bounded frame capture configuration");
        }
    }

    public record Facts(int flags, int renderWidth, int renderHeight, int outputWidth, int outputHeight,
                        String mode, String program, int spp, int bounces, float rayDistance, int traceFlags,
                        String upscalerRequest, String upscalerActive, String framegenState, String denoiser) {}
    public record Row(long frame, double intervalMs, Facts facts, double[] cpuMs, double[] gpuMs, boolean gpuResolved) {}
    public record Snapshot(boolean complete, String reason, long startNs, long endNs, String[] segments, List<Row> rows) {}
    private enum Phase { WAITING, WARMUP, RECORDING, DRAINING, ABORTED, SEALED }

    private final Config config;
    private final String[] segments;
    private final long[] frames, starts, intervals;
    private final Facts[] facts;
    private final double[][] cpu, gpu;
    private final boolean[] gpuDone;
    private Phase phase = Phase.WAITING;
    private long warmStart, captureStart, drainStart, liveFrame = -1, liveStart, lastNow = Long.MIN_VALUE;
    private int size, resolved, pending = -1;
    private boolean frameOpen;
    private String reason = "waiting_for_world";

    public FrameCapture(Config config, String[] segments) {
        this.config = config;
        this.segments = segments.clone();
        if (segments.length == 0 || segments.length > 32
            || Arrays.stream(segments).anyMatch(s -> s == null || !s.matches("[a-z_]+"))
            || Arrays.stream(segments).distinct().count() != segments.length)
            throw new IllegalArgumentException("invalid frame timing segments");
        frames = new long[config.maxFrames];
        starts = new long[config.maxFrames];
        intervals = new long[config.maxFrames];
        facts = new Facts[config.maxFrames];
        gpuDone = new boolean[config.maxFrames];
        cpu = new double[segments.length][config.maxFrames];
        gpu = new double[segments.length][config.maxFrames];
        for (double[] values : cpu) Arrays.fill(values, Double.NaN);
        for (double[] values : gpu) Arrays.fill(values, Double.NaN);
    }

    public void begin(long frame, long now) {
        if (phase == Phase.SEALED) return;
        if (now < lastNow || frameOpen) {
            abort("non_monotonic_or_unfinished_frame", now);
            return;
        }
        lastNow = now;
        if (pending >= 0) {
            long interval = now - starts[pending];
            if (interval <= 0 || frame != frames[pending] + 1) {
                abort("invalid_frame_interval_or_sequence", now);
                return;
            }
            intervals[pending] = interval;
            pending = -1;
        }
        if (phase == Phase.RECORDING && now - captureStart >= config.durationNs) {
            phase = Phase.DRAINING;
            drainStart = now;
            reason = "duration_elapsed";
        }
        liveFrame = frame;
        liveStart = now;
        frameOpen = true;
    }

    public void end(long frame, Facts state, double[] cpuMs) {
        if (phase == Phase.SEALED) return;
        if (!frameOpen || frame != liveFrame || cpuMs.length != segments.length) {
            abort("frame_end_without_matching_begin", lastNow);
            return;
        }
        frameOpen = false;
        int warmFlags = WORLD | PATH_RECORDED | SCREEN_CLOSED | FOCUSED;
        if (phase == Phase.WAITING || phase == Phase.WARMUP) {
            if ((state.flags & warmFlags) != warmFlags) {
                phase = Phase.WAITING;
                return;
            }
            if (phase == Phase.WAITING) {
                phase = Phase.WARMUP;
                warmStart = liveStart;
            }
            if (liveStart - warmStart < config.warmupNs) return;
            phase = Phase.RECORDING;
            captureStart = liveStart;
            reason = "recording";
        }
        if (phase != Phase.RECORDING) return;
        if (size == config.maxFrames) {
            abort("sample_capacity_exceeded", liveStart);
            return;
        }
        if (size > 0 && frame != frames[size - 1] + 1) {
            abort("non_contiguous_frame_ids", liveStart);
            return;
        }
        frames[size] = frame;
        starts[size] = liveStart;
        facts[size] = state;
        for (int s = 0; s < segments.length; ++s) cpu[s][size] = validTime(cpuMs[s]);
        pending = size++;
    }

    /** A late result belongs to its original application frame, never to the frame harvesting it. */
    public void gpu(long frame, double[] gpuMs) {
        if (phase == Phase.SEALED) return;
        if (gpuMs.length != segments.length) throw new IllegalArgumentException("wrong GPU segment count");
        int row = Arrays.binarySearch(frames, 0, size, frame);
        if (row < 0 || gpuDone[row]) return;
        for (int s = 0; s < segments.length; ++s) gpu[s][row] = validTime(gpuMs[s]);
        gpuDone[row] = true;
        ++resolved;
    }

    public void abort(String failure, long now) {
        if (phase == Phase.SEALED || phase == Phase.ABORTED) return;
        phase = Phase.ABORTED;
        reason = failure;
        drainStart = now;
        frameOpen = false;
    }

    public Snapshot poll(long now) {
        if (phase != Phase.DRAINING && phase != Phase.ABORTED) return null;
        if (phase == Phase.DRAINING && (pending >= 0 || resolved != size) && now - drainStart < config.drainNs)
            return null;
        boolean complete = phase == Phase.DRAINING && pending < 0 && size > 0;
        String resultReason = reason;
        if (resolved != size) resultReason += ":gpu_results_missing";
        if (pending >= 0) resultReason += ":last_interval_missing";
        List<Row> rows = new ArrayList<>(size);
        for (int i = 0; i < size; ++i) {
            double[] cpuRow = new double[segments.length], gpuRow = new double[segments.length];
            for (int s = 0; s < segments.length; ++s) { cpuRow[s] = cpu[s][i]; gpuRow[s] = gpu[s][i]; }
            rows.add(new Row(frames[i], intervals[i] > 0 ? intervals[i] * 1e-6 : Double.NaN,
                facts[i], cpuRow, gpuRow, gpuDone[i]));
        }
        long end = size == 0 ? now : starts[size - 1] + intervals[size - 1];
        phase = Phase.SEALED;
        return new Snapshot(complete, resultReason, captureStart, end, segments.clone(), List.copyOf(rows));
    }

    private static double validTime(double ms) { return Double.isFinite(ms) && ms >= 0 ? ms : Double.NaN; }
    public int size() { return size; }
    public boolean recording() { return phase == Phase.RECORDING; }
}
