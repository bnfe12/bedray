package io.github.andrexarlay.vulkancompat.rtx.debug;

import io.github.andrexarlay.vulkancompat.rt.world.RtConfig;
import io.github.andrexarlay.vulkancompat.rtx.config.RtSettings;
import io.github.andrexarlay.vulkancompat.rtx.debug.benchmark.FrameCapture;
import io.github.andrexarlay.vulkancompat.rtx.debug.benchmark.FrameCaptureWriter;
import io.github.andrexarlay.vulkancompat.rtx.framegen.FrameGenRuntime;
import io.github.andrexarlay.vulkancompat.rtx.framegen.FrameGenSettings;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.TemporalUpscaler;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.UpscalerBackend;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.UpscalerRequest;
import net.minecraft.client.Minecraft;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static io.github.andrexarlay.vulkancompat.rtx.debug.benchmark.FrameCapture.*;

/** Optional render-thread adapter. Collection, persistence and offline acceptance have separate owners. */
public final class FrameBenchmark {
    public record Result(Path directory, boolean complete, int frames, String reason) {}
    private record Trace(String mode, String program, int width, int height, int spp, int bounces, float distance, int flags) {}
    private static FrameCapture capture;
    private static FrameCapture.Config config;
    private static Path directory;
    private static Map<String, String> context;
    private static CompletableFuture<Result> result;
    private static Trace trace;
    private static boolean begun;

    private FrameBenchmark() {}

    public static CompletableFuture<Result> start(Path output, FrameCapture.Config settings, Map<String, String> tags) {
        if (result != null && !result.isDone()) throw new IllegalStateException("benchmark already collecting or writing");
        String[] segments = java.util.Arrays.stream(PerfMonitor.Segment.values())
            .map(s -> s.name().toLowerCase(Locale.ROOT)).toArray(String[]::new);
        capture = new FrameCapture(settings, segments);
        config = settings;
        directory = output;
        context = Map.copyOf(tags);
        result = new CompletableFuture<>();
        trace = null;
        begun = false;
        LoggerFactory.getLogger("VKB-BENCH").info("[VKB-BENCH] event=armed directory={} warmup_ns={} duration_ns={} max_frames={}",
            output, settings.warmupNs(), settings.durationNs(), settings.maxFrames());
        return result;
    }

    public static boolean active() { return capture != null; }
    public static boolean busy() { return result != null && !result.isDone(); }

    static void begin(long frame, long now) {
        if (capture == null) return;
        trace = null;
        begun = true;
        capture.begin(frame, now);
        poll(now);
    }

    /** Called only after the selected path backend successfully recorded this frame. */
    public static void traceRecorded(String mode, String program, int width, int height, int spp, int bounces, float distance, int flags) {
        if (capture != null) trace = new Trace(mode, program, width, height, spp, bounces, distance, flags);
    }

    static void end(long frame, boolean world, double[] cpuMs) {
        if (capture == null || !begun) return;
        var mc = Minecraft.getInstance();
        var request = UpscalerRequest.current().backend();
        var upscaler = TemporalUpscaler.active();
        var fg = FrameGenRuntime.status().state();
        int rw = trace == null ? DebugInfo.renderW() : trace.width;
        int rh = trace == null ? DebugInfo.renderH() : trace.height;
        int ow = mc.getWindow().getWidth(), oh = mc.getWindow().getHeight();
        int flags = (world ? WORLD : 0)
            | (trace != null && "path".equals(trace.mode) && "path".equals(RtConfig.effectiveModeName())
                && io.github.andrexarlay.vulkancompat.rt.world.RtFrameRenderer.displayedThisFrame() ? PATH_RECORDED : 0)
            | (rw == 1920 && rh == 1080 && rw == ow && rh == oh ? NATIVE_RESOLUTION : 0)
            | (request == UpscalerBackend.BILINEAR && upscaler == null ? UPSCALER_OFF : 0)
            | (FrameGenSettings.mode() == FrameGenSettings.Mode.OFF
                && (fg == FrameGenRuntime.State.READY || fg == FrameGenRuntime.State.UNAVAILABLE) ? FRAMEGEN_OFF : 0)
            | (mc.gui.screen() == null ? SCREEN_CLOSED : 0) | (mc.getWindow().isFocused() ? FOCUSED : 0);
        capture.end(frame, new Facts(flags, rw, rh, ow, oh, trace == null ? "none" : trace.mode,
            trace == null ? "" : trace.program, trace == null ? 0 : trace.spp, trace == null ? 0 : trace.bounces,
            trace == null ? 0 : trace.distance,
            trace == null ? 0 : trace.flags, request.name(), upscaler == null ? "none" : upscaler.name(),
            fg.name(), RtSettings.denoiser().name()), cpuMs);
        poll(System.nanoTime());
    }

    static void gpu(long frame, double[] ms) {
        if (capture != null) capture.gpu(frame, ms);
    }

    public static void abort(String reason) {
        if (capture == null) return;
        long now = System.nanoTime();
        capture.abort(reason, now);
        poll(now);
    }

    private static void poll(long now) {
        var snapshot = capture.poll(now);
        if (snapshot == null) return;
        capture = null;
        // Immutable request locals: a later capture cannot change an in-flight writer's destination or metadata.
        var output = directory;
        var settings = config;
        var tags = context;
        var completion = result;
        LoggerFactory.getLogger("VKB-BENCH").info("[VKB-BENCH] event=sealed directory={} complete={} frames={} reason={}",
            output, snapshot.complete(), snapshot.rows().size(), snapshot.reason());
        CompletableFuture.runAsync(() -> {
            try {
                FrameCaptureWriter.write(output, settings, snapshot, tags);
                completion.complete(new Result(output, snapshot.complete(), snapshot.rows().size(), snapshot.reason()));
                LoggerFactory.getLogger("VKB-BENCH").info("[VKB-BENCH] event=saved directory={}", output);
            } catch (Exception failure) {
                LoggerFactory.getLogger("VKB-BENCH").error("[VKB-BENCH] event=write_failed directory={}", output, failure);
                completion.completeExceptionally(failure);
            }
        });
    }
}
