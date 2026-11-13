package io.github.andrexarlay.vulkancompat.rtx.debug.benchmark;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;

/** Writes a sealed capture away from the render thread. Manifest appears only after the CSV is fully closed. */
public final class FrameCaptureWriter {
    private FrameCaptureWriter() {}

    public static void write(Path directory, FrameCapture.Config config, FrameCapture.Snapshot capture,
                             Map<String, String> context) throws IOException {
        Files.createDirectories(directory);
        try (Writer out = Files.newBufferedWriter(directory.resolve("frames.csv"), StandardCharsets.UTF_8,
            StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            csv(out, capture);
        }
        JsonObject manifest = new JsonObject();
        manifest.addProperty("schema", 1);
        manifest.addProperty("capture_complete", capture.complete());
        manifest.addProperty("reason", capture.reason());
        manifest.addProperty("rows", capture.rows().size());
        manifest.addProperty("requested_warmup_ns", config.warmupNs());
        manifest.addProperty("requested_duration_ns", config.durationNs());
        manifest.addProperty("warmup_required_flags", config.warmupFlags());
        manifest.addProperty("measured_interval_ns", capture.endNs() - capture.startNs());
        manifest.addProperty("native_required_flags", FrameCapture.NATIVE_REQUIRED);
        manifest.addProperty("interval_source", "application_render_begin_to_next_render_begin");
        manifest.addProperty("gpu_source", "available_engine_timestamp_queries_correlated_by_application_frame");
        manifest.addProperty("gpu_total_scope", "game_render_begin_to_pre_submit_frame_end_excludes_presentation");
        manifest.addProperty("denoiser_source", "requested_setting_not_execution_proof");
        JsonObject tags = new JsonObject();
        context.forEach(tags::addProperty);
        manifest.add("context", tags);
        JsonObject summary = FrameCaptureSummary.summarize(capture);
        manifest.add("summary", summary);
        Files.writeString(directory.resolve("summary.txt"), FrameCaptureSummary.describe(summary) + "\n",
            StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        try (Writer out = Files.newBufferedWriter(directory.resolve("manifest.json"), StandardCharsets.UTF_8,
            StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            new GsonBuilder().setPrettyPrinting().create().toJson(manifest, out);
        }
    }

    static void csv(Writer out, FrameCapture.Snapshot capture) throws IOException {
        out.write("frame,interval_ms,flags,render_width,render_height,output_width,output_height,mode,program,spp,bounces,ray_distance,trace_flags,upscaler_request,upscaler_active,framegen_state,denoiser,gpu_resolved");
        for (String group : new String[]{"cpu_", "gpu_"})
            for (String segment : capture.segments()) out.write("," + group + segment + "_ms");
        out.write('\n');
        for (FrameCapture.Row row : capture.rows()) {
            var f = row.facts();
            out.write(Long.toString(row.frame()));
            number(out, row.intervalMs());
            integer(out, f.flags()); integer(out, f.renderWidth()); integer(out, f.renderHeight());
            integer(out, f.outputWidth()); integer(out, f.outputHeight());
            field(out, f.mode()); field(out, f.program()); integer(out, f.spp()); integer(out, f.bounces());
            number(out, f.rayDistance());
            integer(out, f.traceFlags()); field(out, f.upscalerRequest()); field(out, f.upscalerActive());
            field(out, f.framegenState()); field(out, f.denoiser()); integer(out, row.gpuResolved() ? 1 : 0);
            for (double ms : row.cpuMs()) number(out, ms);
            for (double ms : row.gpuMs()) number(out, ms);
            out.write('\n');
        }
    }

    private static void integer(Writer out, int value) throws IOException { out.write(','); out.write(Integer.toString(value)); }
    private static void number(Writer out, double value) throws IOException {
        out.write(',');
        if (Double.isFinite(value)) out.write(Double.toString(value)); // missing is empty, never invented zero
    }
    private static void field(Writer out, String value) throws IOException {
        out.write(',');
        out.write('"');
        if (value != null) out.write(value.replace("\"", "\"\""));
        out.write('"');
    }
}
