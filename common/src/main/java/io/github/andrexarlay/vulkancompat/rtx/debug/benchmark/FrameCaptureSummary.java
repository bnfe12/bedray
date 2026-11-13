package io.github.andrexarlay.vulkancompat.rtx.debug.benchmark;

import com.google.gson.JsonObject;
import java.util.Arrays;
import java.util.Locale;

/** Statistics of measured application frame intervals. Generated display frames are never counted. */
public final class FrameCaptureSummary {
    private FrameCaptureSummary() {}

    public static JsonObject summarize(FrameCapture.Snapshot capture) {
        int eligible = FrameCapture.WORLD | FrameCapture.SCREEN_CLOSED | FrameCapture.FOCUSED;
        double[] intervals = capture.rows().stream()
            .filter(row -> (row.facts().flags() & eligible) == eligible)
            .mapToDouble(FrameCapture.Row::intervalMs)
            .filter(ms -> Double.isFinite(ms) && ms > 0).sorted().toArray();
        JsonObject result = new JsonObject();
        result.addProperty("scope", "application_frames_excludes_generated_display_frames");
        result.addProperty("capture_complete", capture.complete());
        result.addProperty("reason", capture.reason());
        result.addProperty("measured_frames", intervals.length);
        result.addProperty("excluded_frames", capture.rows().size() - intervals.length);
        result.addProperty("settings_variants", capture.rows().stream().map(FrameCapture.Row::facts).distinct().count());
        result.addProperty("one_percent_low_definition", "1000 / mean of slowest ceil(N * 0.01) frame intervals in ms");
        if (intervals.length > 0) {
            double sum = Arrays.stream(intervals).sum();
            result.addProperty("measured_seconds", sum / 1000);
            result.addProperty("average_fps", intervals.length * 1000 / sum);
            result.addProperty("one_percent_low_fps", low(intervals, 0.01));
            result.addProperty("point_one_percent_low_fps", low(intervals, 0.001));
            result.addProperty("p50_frame_ms", percentile(intervals, 0.50));
            result.addProperty("p95_frame_ms", percentile(intervals, 0.95));
            result.addProperty("p99_frame_ms", percentile(intervals, 0.99));
            result.addProperty("worst_frame_ms", intervals[intervals.length - 1]);
        }
        return result;
    }

    public static String describe(JsonObject summary) {
        if (!summary.has("average_fps")) return "No eligible gameplay frames measured.";
        return String.format(Locale.ROOT,
            "%.1f FPS average; %.1f FPS 1%% low; %.1f ms p99; %d frames / %.1f s. Application FPS; generated frames excluded. Complete: %s.",
            summary.get("average_fps").getAsDouble(), summary.get("one_percent_low_fps").getAsDouble(),
            summary.get("p99_frame_ms").getAsDouble(), summary.get("measured_frames").getAsInt(),
            summary.get("measured_seconds").getAsDouble(), summary.get("capture_complete").getAsBoolean());
    }

    private static double low(double[] sorted, double fraction) {
        int count = Math.max(1, (int) Math.ceil(sorted.length * fraction));
        double sum = 0;
        for (int i = sorted.length - count; i < sorted.length; ++i) sum += sorted[i];
        return count * 1000 / sum;
    }

    private static double percentile(double[] sorted, double fraction) {
        return sorted[Math.max(0, (int) Math.ceil(sorted.length * fraction) - 1)];
    }
}
