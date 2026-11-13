package io.github.andrexarlay.bedray.compute;

import io.github.andrexarlay.bedray.api.v1.AtlasFrame;
import io.github.andrexarlay.bedray.api.v1.CameraSnapshot;
import io.github.andrexarlay.bedray.api.v1.GraphicsBackendRegistry;
import io.github.andrexarlay.bedray.api.v1.RasterFrame;
import io.github.andrexarlay.bedray.api.v1.WorldSnapshot;
import io.github.andrexarlay.bedray.compute.voxel.VoxelGrid;
import io.github.andrexarlay.bedray.compute.voxel.VoxelWorldUpload;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.lwjgl.vulkan.VK10.*;

/**
 * V0 baseline measurement: three fixed offscreen scenes, one camera each, 30 warmup + 120 measured
 * frames at 1280x720. Writes docs/COMPUTE_BASELINE.md and prints the same markdown. Offscreen L2
 * evidence only: the harness waits for the queue every frame, so harness wall time is an upper
 * bound; GPU region times come from timestamp queries harvested without steady-state waits.
 * In-game capture/submit costs appear only in the host's [BEDRAY-COMPUTE] frame_timing log.
 */
public final class ComputeBaselineRunner {
    private static final int WIDTH = 1280, HEIGHT = 720, WARMUP = 30, FRAMES = 120;
    private static final float[] IDENTITY = {1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,0,1};

    private ComputeBaselineRunner() {}

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args.length > 0 ? args[0] : "").toAbsolutePath();
        GraphicsBackendRegistry.DebugView previousView = GraphicsBackendRegistry.debugView();
        GraphicsBackendRegistry.setDebugView(GraphicsBackendRegistry.DebugView.SHADED);
        List<Result> results = new ArrayList<>();
        try {
            for (ComputeBaselineScenes.Scene scene : List.of(ComputeBaselineScenes.fieldBiomeBorder(),
                    ComputeBaselineScenes.darkRoomEmitters(), ComputeBaselineScenes.deepCave()))
                results.add(measure(scene, root));
        } finally {
            GraphicsBackendRegistry.setDebugView(previousView);
        }
        String markdown = report(root, results);
        Path doc = root.resolve("docs").resolve("COMPUTE_BASELINE.md");
        Files.createDirectories(doc.getParent());
        Files.writeString(doc, markdown, StandardCharsets.UTF_8);
        System.out.println(markdown);
        System.out.println("[BEDRAY-COMPUTE-BASELINE] wrote " + doc);
    }

    private record Result(ComputeBaselineScenes.Scene scene, int hits,
                          ComputeDiagnostics.Snapshot firstFrame, long firstUploadNs,
                          ComputeDiagnostics.Snapshot steady, long harnessP50Ns, long harnessP95Ns, long harnessP99Ns,
                          String hash, Path png, Map<String, Long> memory, int emitters, int emittersFound,
                          boolean gpuTimers, float periodNs, String deviceName, int driverVersion, int apiVersion) {}

    private static Result measure(ComputeBaselineScenes.Scene scene, Path root) throws Exception {
        CameraSnapshot camera = new CameraSnapshot(scene.camera().x(), scene.camera().y(), scene.camera().z(),
            ComputeBaselineScenes.inverseProjection(WIDTH, HEIGHT), IDENTITY);
        float[] depth = ComputeBaselineScenes.depthOracle(scene, camera, WIDTH, HEIGHT);
        int hits = 0;
        for (float d : depth) if (d > 0) hits++;
        if (hits <= WIDTH * HEIGHT / 10) throw new IllegalStateException("Baseline camera barely sees the scene: " + scene.name());
        try (VulkanComputeProbeTest.Probe probe = new VulkanComputeProbeTest.Probe(WIDTH, HEIGHT);
             VulkanComputeProbeTest.LitInputs inputs = new VulkanComputeProbeTest.LitInputs(probe, depth)) {
            AtlasFrame atlas = new AtlasFrame(1, probe.device.address(), inputs.atlasView, VK_IMAGE_LAYOUT_GENERAL,
                VK_FORMAT_R8G8B8A8_UNORM, 16, 16, AtlasFrame.ColorEncoding.SRGB_IN_UNORM);
            RasterFrame raster = new RasterFrame(probe.device.address(), inputs.colorView, inputs.depthView,
                VK_FORMAT_R8G8B8A8_UNORM, VK_FORMAT_D32_SFLOAT, WIDTH, HEIGHT);
            try (VoxelComputePipeline pipeline = new VoxelComputePipeline(probe.device, WIDTH, HEIGHT,
                    VK_FORMAT_R8G8B8A8_UNORM, VoxelGrid.builder(48, 48, 48).build(), atlas, raster)) {
                VoxelWorldUpload uploads = new VoxelWorldUpload();
                WorldSnapshot snapshot = new WorldSnapshot(1, 1, camera, scene.sections());
                probe.lighting = scene.lighting();
                try {
                    long uploadStart = System.nanoTime();
                    List<VoxelWorldUpload.Tile> first = uploads.update(snapshot);
                    long firstUploadNs = System.nanoTime() - uploadStart;
                    byte[] image = probe.renderWorld(pipeline, false, camera, uploads, first);
                    pipeline.diagnostics().addUploadPrepNs(firstUploadNs);
                    ComputeDiagnostics.Snapshot firstFrame = pipeline.diagnostics().snapshotAndReset();
                    for (int i = 0; i < WARMUP; i++)
                        probe.renderWorld(pipeline, false, camera, uploads, uploads.update(snapshot));
                    pipeline.diagnostics().snapshotAndReset();
                    long[] harness = new long[FRAMES];
                    for (int i = 0; i < FRAMES; i++) {
                        long prepStart = System.nanoTime();
                        List<VoxelWorldUpload.Tile> updates = uploads.update(snapshot);
                        pipeline.diagnostics().addUploadPrepNs(System.nanoTime() - prepStart);
                        long frameStart = System.nanoTime();
                        image = probe.renderWorld(pipeline, false, camera, uploads, updates);
                        harness[i] = System.nanoTime() - frameStart;
                    }
                    ComputeDiagnostics.Snapshot steady = pipeline.diagnostics().snapshotAndReset();
                    Arrays.sort(harness);
                    String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(image));
                    Path png = root.resolve("build").resolve("reports").resolve("compute")
                        .resolve("baseline-" + scene.name() + ".png");
                    writeImage(image, png);
                    int driverVersion, apiVersion;
                    try (MemoryStack stack = MemoryStack.stackPush()) {
                        VkPhysicalDeviceProperties properties = VkPhysicalDeviceProperties.calloc(stack);
                        vkGetPhysicalDeviceProperties(probe.device.getPhysicalDevice(), properties);
                        driverVersion = properties.driverVersion();
                        apiVersion = properties.apiVersion();
                    }
                    return new Result(scene, hits, firstFrame, firstUploadNs, steady,
                        ComputeDiagnostics.percentile(harness, harness.length, 0.50),
                        ComputeDiagnostics.percentile(harness, harness.length, 0.95),
                        ComputeDiagnostics.percentile(harness, harness.length, 0.99),
                        hash, png, pipeline.memoryBytes(), uploads.emitters().count(), uploads.emitters().found(),
                        pipeline.gpuTimersAvailable(), pipeline.gpuTimestampPeriodNs(), probe.deviceName,
                        driverVersion, apiVersion);
                } finally {
                    probe.lighting = null;
                }
            }
        }
    }

    private static void writeImage(byte[] rgba, Path output) throws Exception {
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < HEIGHT; y++) for (int x = 0; x < WIDTH; x++) {
            int at = (x + WIDTH * y) * 4;
            image.setRGB(x, y, Byte.toUnsignedInt(rgba[at + 3]) << 24 | Byte.toUnsignedInt(rgba[at]) << 16
                | Byte.toUnsignedInt(rgba[at + 1]) << 8 | Byte.toUnsignedInt(rgba[at + 2]));
        }
        Files.createDirectories(output.getParent());
        if (!ImageIO.write(image, "png", output.toFile())) throw new IllegalStateException("PNG writer is unavailable");
    }

    private static String report(Path root, List<Result> results) {
        Result first = results.getFirst();
        StringBuilder md = new StringBuilder(8192);
        md.append("# Compute baseline (V0)\n\n");
        md.append("Generated ").append(LocalDate.now()).append(" by `:compute-common:computeBaseline` at commit `")
            .append(git(root, "rev-parse", "--short", "HEAD")).append("`")
            .append(git(root, "status", "--porcelain").isEmpty() ? " (clean)" : " (dirty working tree)").append(".\n\n");
        md.append("Evidence level: **L2 offscreen** on a real Vulkan 1.0 device with zero enabled extensions. ")
            .append("This is not in-game (L4) evidence. The probe harness waits for the queue after every frame, ")
            .append("so `harness_*` wall times are an upper bound; in-game frames never wait. ")
            .append("GPU region times come from timestamp queries harvested four frames late without steady-state waits. ")
            .append("In-game capture/submit costs are reported only by the host's `[BEDRAY-COMPUTE] frame_timing` log (at most once per second).\n\n");
        md.append("- Device: ").append(first.deviceName()).append(", driverVersion raw `").append(first.driverVersion())
            .append("`, device Vulkan API ").append(VK_VERSION_MAJOR(first.apiVersion())).append('.')
            .append(VK_VERSION_MINOR(first.apiVersion())).append('.').append(VK_VERSION_PATCH(first.apiVersion())).append('\n');
        md.append("- GPU timestamp queries: ").append(first.gpuTimers() ? "available" : "UNAVAILABLE (CPU-only fallback)")
            .append(", period ").append(String.format(Locale.ROOT, "%.3f", first.periodNs())).append(" ns\n");
        md.append("- Resolution ").append(WIDTH).append('x').append(HEIGHT).append(", ").append(WARMUP)
            .append(" warmup frames discarded, ").append(FRAMES).append(" measured frames per scene; fixed camera, fixed lighting, ")
            .append("no upscaler/frame generation offscreen. GPU history counters enabled (`-Dbedray.compute.gpuCounters=true`).\n");
        md.append("- Steady state uploads nothing: all 27 sections are resident after the first frame; residency cost is reported separately.\n\n");

        md.append("## Steady-state GPU timings (").append(FRAMES).append(" frames)\n\n");
        md.append("| scene | gpu_upload_ms | dispatch_ms | blit_ms | gpu_total_ms | p50_ms | p95_ms | p99_ms | samples |\n");
        md.append("|---|---|---|---|---|---|---|---|---|\n");
        for (Result r : results) {
            ComputeDiagnostics.Snapshot s = r.steady();
            md.append("| ").append(r.scene().name()).append(" | ").append(ms(s.gpuUploadNs(), s.gpuSamples()))
                .append(" | ").append(ms(s.gpuDispatchNs(), s.gpuSamples())).append(" | ").append(ms(s.gpuBlitNs(), s.gpuSamples()))
                .append(" | ").append(ms(s.gpuTotalNs(), s.gpuSamples())).append(" | ").append(ms(s.p50Ns()))
                .append(" | ").append(ms(s.p95Ns())).append(" | ").append(ms(s.p99Ns()))
                .append(" | ").append(s.gpuSamples()).append(" |\n");
        }
        md.append("\nPercentiles are nearest-rank over per-frame GPU totals (upload+dispatch+blit).\n\n");

        md.append("## CPU preparation, harness wall time and first-frame residency\n\n");
        md.append("| scene | upload_prep_ms | record_ms | harness_p50_ms | harness_p95_ms | harness_p99_ms | first_upload_ms | sections | cubes | models | quads | bytes | emitters | history |\n");
        md.append("|---|---|---|---|---|---|---|---|---|---|---|---|---|---|\n");
        for (Result r : results) {
            ComputeDiagnostics.Snapshot s = r.steady(), f = r.firstFrame();
            md.append("| ").append(r.scene().name()).append(" | ").append(ms(s.uploadPrepNs(), s.frames()))
                .append(" | ").append(ms(s.recordNs(), s.frames())).append(" | ").append(ms(r.harnessP50Ns()))
                .append(" | ").append(ms(r.harnessP95Ns())).append(" | ").append(ms(r.harnessP99Ns()))
                .append(" | ").append(ms(r.firstUploadNs())).append(" | ").append(f.sections())
                .append(" | ").append(f.cubes()).append(" | ").append(f.models()).append(" | ").append(f.quads())
                .append(" | ").append(f.bytes()).append(" | ").append(r.emitters()).append('/').append(r.emittersFound())
                .append(" | ").append(s.history()).append(" |\n");
        }
        md.append("\n`upload_prep_ms`/`record_ms` are per-frame CPU means over the measured window; `first_upload_ms` is the one-time CPU cost of preparing all 27 sections. ")
            .append("`bytes` counts first-frame upload payload (tiles, tints, palettes, model headers/quads, lighting). `history` is the temporal accumulator state at window end.\n\n");

        md.append("## GPU history counters (debug flag, accumulated over the measured window)\n\n");
        md.append("| scene | accepted | distance_reject | normal_reject | empty | bounds | invalid_reprojection | nan_radiance | low_weight |\n");
        md.append("|---|---|---|---|---|---|---|---|---|\n");
        for (Result r : results) {
            long[] c = r.steady().gpuCounters();
            md.append("| ").append(r.scene().name());
            if (c == null) md.append(" | unavailable | | | | | | | |");
            else for (long value : c) md.append(" | ").append(value);
            md.append(" |\n");
        }
        md.append('\n');

        md.append("## Provider-owned GPU memory (actual VkMemoryRequirements sizes, bytes)\n\n");
        md.append("| scene | grid | palette | models | frame | history | output | counters | total |\n");
        md.append("|---|---|---|---|---|---|---|---|---|\n");
        for (Result r : results) {
            Map<String, Long> m = r.memory();
            md.append("| ").append(r.scene().name());
            for (String key : List.of("grid", "palette", "models", "frame", "history", "output", "counters", "total"))
                md.append(" | ").append(m.get(key));
            md.append(" |\n");
        }
        md.append('\n');

        md.append("## Images\n\n");
        md.append("| scene | oracle hits | SHA-256 (final frame) | PNG |\n");
        md.append("|---|---|---|---|\n");
        for (Result r : results)
            md.append("| ").append(r.scene().name()).append(" | ").append(r.hits()).append('/').append(WIDTH * HEIGHT)
                .append(" | `").append(r.hash()).append("` | `").append(root.relativize(r.png())).append("` |\n");
        md.append('\n');

        md.append("## Scenes\n\n");
        for (Result r : results) md.append("- **").append(r.scene().name()).append("**: ").append(r.scene().description()).append('\n');
        md.append("\nAll scenes fill the resident 48x48x48 window (27 sections), use one fixed camera and one lighting frame, ")
            .append("and synthesize reverse-Z raster depth from the CPU DDA oracle exactly like the in-game composition path.\n\n");
        md.append("## Reproduce\n\n");
        md.append("```\n./gradlew :compute-common:computeBaseline\n```\n\n");
        md.append("In-game follow-up (L4, owner-verified): enable the live scene and read `[BEDRAY-COMPUTE] frame_timing` ")
            .append("for capture_ms/submit_ms plus the same provider summary; compare against this table before any shader change (baseline/A-B rule).\n");
        return md.toString();
    }

    private static String ms(long nanos) { return String.format(Locale.ROOT, "%.3f", nanos * 1.0e-6); }

    private static String ms(long nanos, int count) {
        return count <= 0 ? "n/a" : String.format(Locale.ROOT, "%.3f", nanos / (double) count * 1.0e-6);
    }

    private static String git(Path root, String... args) {
        try {
            List<String> command = new ArrayList<>();
            command.add("git");
            command.addAll(List.of(args));
            Process process = new ProcessBuilder(command).directory(root.toFile()).redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            return process.waitFor(10, TimeUnit.SECONDS) && process.exitValue() == 0 ? output : "unknown";
        } catch (Exception failure) {
            return "unknown";
        }
    }
}