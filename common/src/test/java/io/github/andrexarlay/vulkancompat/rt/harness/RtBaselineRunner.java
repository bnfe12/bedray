package io.github.andrexarlay.vulkancompat.rt.harness;

import io.github.andrexarlay.vulkancompat.rt.poc.RtTestPipeline;
import io.github.andrexarlay.vulkancompat.rt.poc.SimpleAccelerationStructureBuilder;
import io.github.andrexarlay.vulkancompat.rt.world.RtFrameParams;
import io.github.andrexarlay.vulkancompat.rt.world.RtPipeline;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.LongBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

import static org.lwjgl.vulkan.VK10.*;

/**
 * V0 RT baseline: three fixed offscreen scenes (rt_test triangle, rt_rays section, rt_path accum),
 * 30 warmup + 120 measured frames at 320x240. Writes docs/RT_BASELINE.md. L2 offscreen evidence on
 * a real Vulkan 1.2 ray-query device. The harness waits for the queue every frame, so harness wall
 * time is an upper bound; GPU dispatch times come from timestamp queries. This is the GLSL reference
 * captured BEFORE any Slang port; the port must reproduce these hashes (A/B), never regenerate them.
 */
public final class RtBaselineRunner {
    private static final int WIDTH = 320, HEIGHT = 240, WARMUP = 30, FRAMES = 120;

    private RtBaselineRunner() {}

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args.length > 0 ? args[0] : "").toAbsolutePath();
        List<Result> results = new ArrayList<>();
        results.add(measureTest(root));
        results.add(measureRays(root));
        results.add(measurePath(root));
        String markdown = report(root, results);
        Path doc = root.resolve("docs").resolve("RT_BASELINE.md");
        Files.createDirectories(doc.getParent());
        Files.writeString(doc, markdown, StandardCharsets.UTF_8);
        System.out.println(markdown);
        System.out.println("[BEDRAY-RT-BASELINE] wrote " + doc);
    }

    private record Result(String scene, String hash, Path png, long gpuP50Ns, long gpuP95Ns, long gpuP99Ns,
                          long harnessP50Ns, int hits, String deviceName, int driverVersion, int apiVersion,
                          boolean gpuTimers, float periodNs) {}

    private static Result measureTest(Path root) throws Exception {
        try (RtBaselineProbe probe = new RtBaselineProbe(WIDTH, HEIGHT);
             SimpleAccelerationStructureBuilder as = new SimpleAccelerationStructureBuilder(probe.device);
             RtTestPipeline pipeline = new RtTestPipeline(probe.device);
             GpuTimer timer = new GpuTimer(probe)) {
            probe.render(cmd -> { as.buildAll(cmd); pipeline.dispatchTest(cmd, probe.targetView, as.getTlasHandle(), WIDTH, HEIGHT); });
            for (int i = 0; i < WARMUP; i++)
                probe.render(cmd -> pipeline.dispatchTest(cmd, probe.targetView, as.getTlasHandle(), WIDTH, HEIGHT));
            long[] gpu = new long[FRAMES], harness = new long[FRAMES];
            byte[] image = null;
            for (int i = 0; i < FRAMES; i++) {
                long start = System.nanoTime();
                image = probe.render(cmd -> {
                    timer.begin(cmd);
                    pipeline.dispatchTest(cmd, probe.targetView, as.getTlasHandle(), WIDTH, HEIGHT);
                    timer.end(cmd);
                });
                harness[i] = System.nanoTime() - start;
                gpu[i] = timer.read();
            }
            return result("rt_test", probe, timer, gpu, harness, image, root, countHits(image, 24));
        }
    }

    private static Result measureRays(Path root) throws Exception {
        try (RtBaselineProbe probe = new RtBaselineProbe(WIDTH, HEIGHT);
             RtBaselineScene scene = new RtBaselineScene(probe);
             GpuTimer timer = new GpuTimer(probe)) {
            RtPipeline pipeline = new RtPipeline(probe.device, false);
            try {
                pipeline.bind(new RtPipeline.Views(scene.tlas, scene.table, scene.outputView, scene.atlasView,
                    scene.materialView, scene.normalView, 0L, null, scene.depthView, scene.lightmapView));
                probe.render(cmd -> { scene.build(cmd); pipeline.dispatch(cmd, scene.params(0, 0), WIDTH, HEIGHT); });
                for (int i = 0; i < WARMUP; i++) {
                    final int frame = i;
                    probe.render(cmd -> { scene.build(cmd); pipeline.dispatch(cmd, scene.params(frame, 0), WIDTH, HEIGHT); });
                }
                long[] gpu = new long[FRAMES], harness = new long[FRAMES];
                byte[] image = null;
                for (int i = 0; i < FRAMES; i++) {
                    long start = System.nanoTime();
                    final int frame = WARMUP + i;
                    image = probe.render(cmd -> {
                        timer.begin(cmd);
                        scene.build(cmd);
                        pipeline.dispatch(cmd, scene.params(frame, 0), WIDTH, HEIGHT);
                        timer.end(cmd);
                    });
                    harness[i] = System.nanoTime() - start;
                    gpu[i] = timer.read();
                }
                return result("rt_rays", probe, timer, gpu, harness, image, root, countHits(image, 24));
            } finally {
                pipeline.destroy();
            }
        }
    }

    private static Result measurePath(Path root) throws Exception {
        try (RtBaselineProbe probe = new RtBaselineProbe(WIDTH, HEIGHT);
             RtBaselineScene scene = new RtBaselineScene(probe);
             GpuTimer timer = new GpuTimer(probe)) {
            RtPipeline pipeline = new RtPipeline(probe.device, RtPipeline.PATH_SHADER_PATH, "rt_path.comp", "", false, true);
            try {
                pipeline.bind(new RtPipeline.Views(scene.tlas, scene.table, scene.outputView, scene.atlasView,
                    scene.materialView, scene.normalView, scene.accumView, null, scene.depthView, scene.lightmapView));
                probe.render(cmd -> { scene.build(cmd); pipeline.dispatch(cmd, scene.params(0, 0), WIDTH, HEIGHT); });
                for (int i = 0; i < WARMUP; i++) {
                    final int frame = i;
                    probe.render(cmd -> { scene.build(cmd); pipeline.dispatch(cmd, scene.params(frame, Math.min(frame, RtFrameParams.MAX_ACCUM)), WIDTH, HEIGHT); });
                }
                long[] gpu = new long[FRAMES], harness = new long[FRAMES];
                byte[] image = null;
                for (int i = 0; i < FRAMES; i++) {
                    long start = System.nanoTime();
                    final int frame = WARMUP + i;
                    final int accum = Math.min(WARMUP + i, RtFrameParams.MAX_ACCUM);
                    image = probe.render(cmd -> {
                        timer.begin(cmd);
                        scene.build(cmd);
                        pipeline.dispatch(cmd, scene.params(frame, accum), WIDTH, HEIGHT);
                        timer.end(cmd);
                    });
                    harness[i] = System.nanoTime() - start;
                    gpu[i] = timer.read();
                }
                return result("rt_path", probe, timer, gpu, harness, image, root, countHits(image, 24));
            } finally {
                pipeline.destroy();
            }
        }
    }

    private static Result result(String scene, RtBaselineProbe probe, GpuTimer timer, long[] gpu, long[] harness,
                                 byte[] image, Path root, int hits) throws Exception {
        java.util.Arrays.sort(gpu);
        java.util.Arrays.sort(harness);
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(image));
        Path png = root.resolve("build").resolve("reports").resolve("rt").resolve("baseline-" + scene + ".png");
        writeImage(image, png);
        return new Result(scene, hash, png, percentile(gpu, 0.50), percentile(gpu, 0.95), percentile(gpu, 0.99),
            percentile(harness, 0.50), hits, probe.deviceName, probe.driverVersion, probe.apiVersion,
            timer.available, timer.periodNs);
    }

    private static int countHits(byte[] rgba, int threshold) {
        int hits = 0;
        for (int i = 0; i < WIDTH * HEIGHT; i++) {
            int r = Byte.toUnsignedInt(rgba[i * 4]), g = Byte.toUnsignedInt(rgba[i * 4 + 1]), b = Byte.toUnsignedInt(rgba[i * 4 + 2]);
            if (r + g + b > threshold) hits++;
        }
        return hits;
    }

    private static long percentile(long[] sorted, double p) {
        if (sorted.length == 0) return 0;
        int rank = (int) Math.ceil(p * sorted.length);
        return sorted[Math.min(sorted.length - 1, Math.max(0, rank - 1))];
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
        StringBuilder md = new StringBuilder(4096);
        md.append("# RT baseline (Stage 2.0, GLSL reference)\n\n");
        md.append("Generated ").append(LocalDate.now()).append(" by `:common:rtBaseline`. ")
            .append("Evidence level: **L2 offscreen** on a real Vulkan 1.2 ray-query device. Not in-game (L4). ")
            .append("The harness waits for the queue every frame, so `harness_p50_ms` is an upper bound; ")
            .append("`gpu_dispatch_*` come from timestamp queries around the dispatch. ")
            .append("This is the GLSL reference captured BEFORE any Slang port; the port must reproduce these hashes (A/B).\n\n");
        md.append("- Device: ").append(first.deviceName()).append(", driverVersion raw `").append(first.driverVersion())
            .append("`, device Vulkan API ").append(VK_VERSION_MAJOR(first.apiVersion())).append('.')
            .append(VK_VERSION_MINOR(first.apiVersion())).append('.').append(VK_VERSION_PATCH(first.apiVersion())).append('\n');
        md.append("- GPU timestamp queries: ").append(first.gpuTimers() ? "available" : "UNAVAILABLE")
            .append(", period ").append(String.format(Locale.ROOT, "%.3f", first.periodNs())).append(" ns\n");
        md.append("- Resolution ").append(WIDTH).append('x').append(HEIGHT).append(", ").append(WARMUP)
            .append(" warmup frames discarded, ").append(FRAMES).append(" measured frames per scene; fixed camera, fixed lighting.\n\n");
        md.append("## GPU dispatch timings (").append(FRAMES).append(" frames)\n\n");
        md.append("| scene | gpu_p50_ms | gpu_p95_ms | gpu_p99_ms | harness_p50_ms | hits |\n");
        md.append("|---|---|---|---|---|---|\n");
        for (Result r : results)
            md.append("| ").append(r.scene()).append(" | ").append(ms(r.gpuP50Ns())).append(" | ").append(ms(r.gpuP95Ns()))
                .append(" | ").append(ms(r.gpuP99Ns())).append(" | ").append(ms(r.harnessP50Ns()))
                .append(" | ").append(r.hits()).append('/').append(WIDTH * HEIGHT).append(" |\n");
        md.append("\n## Images\n\n");
        md.append("| scene | SHA-256 (final frame) | PNG |\n|---|---|---|\n");
        for (Result r : results)
            md.append("| ").append(r.scene()).append(" | `").append(r.hash()).append("` | `")
                .append(root.relativize(r.png())).append("` |\n");
        md.append("\n## Scenes\n\n");
        md.append("- **rt_test**: one hardware triangle (SimpleAccelerationStructureBuilder), minimal ray-query shader.\n");
        md.append("- **rt_rays**: one synthetic section (floor + back wall, solid pass), MODE_LIT, no optional flags.\n");
        md.append("- **rt_path**: same scene, accumulation variant (VKB_ACCUM), accumulating over the measured window.\n");
        md.append("\n## Reproduce\n\n```\n./gradlew :common:rtBaseline\n```\n\n");
        md.append("A/B gate (Stage 2.1/2.2): the Slang port must reproduce these hashes (or a documented, justified delta), ")
            .append("GPU totals within measurement noise. Do not regenerate this baseline to hide a delta.\n");
        return md.toString();
    }

    private static String ms(long nanos) { return String.format(Locale.ROOT, "%.3f", nanos * 1.0e-6); }

    /** Two timestamp queries around the dispatch; harvested after the per-frame queue wait. */
    private static final class GpuTimer implements AutoCloseable {
        private final RtBaselineProbe probe;
        private long pool;
        boolean available;
        float periodNs;
        GpuTimer(RtBaselineProbe probe) {
            this.probe = probe;
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkPhysicalDeviceProperties props = VkPhysicalDeviceProperties.calloc(stack);
                vkGetPhysicalDeviceProperties(probe.device.getPhysicalDevice(), props);
                periodNs = props.limits().timestampPeriod();
                available = props.limits().timestampComputeAndGraphics();
                LongBuffer handle = stack.mallocLong(1);
                int err = vkCreateQueryPool(probe.device, VkQueryPoolCreateInfo.calloc(stack).sType$Default()
                    .queryType(VK_QUERY_TYPE_TIMESTAMP).queryCount(2), null, handle);
                if (err == VK_SUCCESS) pool = handle.get(0); else available = false;
            }
        }
        void begin(VkCommandBuffer cmd) { if (available) { vkCmdResetQueryPool(cmd, pool, 0, 2); vkCmdWriteTimestamp(cmd, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, pool, 0); } }
        void end(VkCommandBuffer cmd) { if (available) vkCmdWriteTimestamp(cmd, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, pool, 1); }
        long read() {
            if (!available) return 0;
            try (MemoryStack stack = MemoryStack.stackPush()) {
                LongBuffer data = stack.mallocLong(2);
                int err = vkGetQueryPoolResults(probe.device, pool, 0, 2, data, 8, VK_QUERY_RESULT_64_BIT);
                if (err != VK_SUCCESS) return 0;
                return (data.get(1) - data.get(0)) * (long) periodNs;
            }
        }
        @Override public void close() { if (pool != 0) vkDestroyQueryPool(probe.device, pool, null); }
    }
}