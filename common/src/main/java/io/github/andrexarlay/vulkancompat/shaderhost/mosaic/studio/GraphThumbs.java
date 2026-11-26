package io.github.andrexarlay.vulkancompat.shaderhost.mosaic.studio;

import io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicCodegen;
import io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicGraph;
import io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicPasses;
import io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicShaderCheck;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Pictures of graphs for cards (templates, projects without a picture): the graph rendered by the GPU preview over the
 * built-in world, small, in the background, one at a time, cached on disk by the graph's hash
 * ({@code ~/.bedray/studio/thumbs}). A graph that cannot be drawn without the game (ray tracing hardware) gets none.
 */
final class GraphThumbs {

    private GraphThumbs() {}

    static final int W = 384, H = 216;
    private static final Map<String, BufferedImage> MEMORY = new ConcurrentHashMap<>();
    private static final java.util.Set<String> FAILED = ConcurrentHashMap.newKeySet();
    /** Cards waiting for a picture: every one of them hears when it is ready. */
    private static final Map<String, List<Runnable>> WAITING = new ConcurrentHashMap<>();
    private static final java.util.concurrent.ExecutorService WORKER = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "mosaic-thumbs");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });
    private static GpuPreview gpu;

    static Path dir() { return StudioSettings.file().resolveSibling("studio").resolve("thumbs"); }

    private static String key(String json) { return Integer.toHexString(json.hashCode()) + "-" + json.length(); }

    /** The picture of a graph if it is ready (memory or disk), else null and it is queued; {@code ready} runs when it is made. */
    static BufferedImage get(String json, Runnable ready) {
        String k = key(json);
        BufferedImage img = MEMORY.get(k);
        if (img != null) return img;
        if (FAILED.contains(k)) { // queued already: wait with the others
            if (ready != null) WAITING.computeIfAbsent(k, x -> new java.util.concurrent.CopyOnWriteArrayList<>()).add(ready);
            return null;
        }
        Path f = dir().resolve(k + ".png");
        if (Files.isRegularFile(f)) {
            try {
                img = ImageIO.read(f.toFile());
                if (img != null) {
                    MEMORY.put(k, img);
                    return img;
                }
            } catch (Exception ignored) {
                // render it again
            }
        }
        FAILED.add(k); // queued once; removed again when it succeeds
        if (ready != null) WAITING.computeIfAbsent(k, x -> new java.util.concurrent.CopyOnWriteArrayList<>()).add(ready);
        WORKER.submit(() -> {
            BufferedImage made = render(json);
            if (made == null) return;
            MEMORY.put(k, made);
            FAILED.remove(k);
            try {
                Files.createDirectories(dir());
                ImageIO.write(made, "png", f.toFile());
            } catch (Exception ignored) {
                // memory only
            }
            List<Runnable> w = WAITING.remove(k);
            if (w != null) for (Runnable r : w) javax.swing.SwingUtilities.invokeLater(r);
        });
        return null;
    }

    /** Renders a graph over the built-in world (a few frames, so temporal graphs settle), or null. */
    static synchronized BufferedImage render(String json) {
        try {
            MosaicGraph g = MosaicGraph.parse(json);
            if (g == null) return null;
            List<GpuPreview.PassRun> runs = new ArrayList<>();
            var plan = g.multiPass() ? MosaicPasses.plan(g) : null;
            List<String> jsons = new ArrayList<>();
            if (plan != null) for (var p : plan.all()) jsons.add(p.json());
            else jsons.add(json);
            for (int i = 0; i < jsons.size(); i++) {
                MosaicShaderCheck.Result r = MosaicShaderCheck.compile(MosaicCodegen.generate(MosaicGraph.parse(jsons.get(i)), MosaicCodegen.Options.PREVIEW, false));
                if (!r.ok()) return null;
                if (plan == null) runs.add(new GpuPreview.PassRun(r.spirv(), 1, -1));
                else {
                    var p = plan.all().get(i);
                    runs.add(new GpuPreview.PassRun(r.spirv(), p.scale(), p.name() == null ? -1 : plan.bindings().get(p.name()), plan.after().contains(p)));
                }
            }
            if (gpu == null) gpu = GpuPreview.create();
            gpu.worldWidth = W;
            gpu.worldHeight = H;
            PreviewWorld.View view = PreviewWorld.View.of(PreviewWorld.demo(), null).with(g.settings(),
                StudioUi.settings == null ? Map.of() : StudioUi.settings.previewLook);
            BufferedImage img = null;
            for (int f = 0; f < 4; f++) img = gpu.runChain(runs, null, f / 30f, f, null, view);
            return img;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Frees the GPU when the start screen closes (the editor's preview has its own). */
    static void release() {
        WORKER.submit(() -> {
            synchronized (GraphThumbs.class) {
                if (gpu != null) {
                    try {
                        gpu.close();
                    } catch (Exception ignored) {
                        // closing
                    }
                    gpu = null;
                }
            }
        });
    }

    /** The graph text of a project (its mosaic.json), or null. */
    static String graphOf(ProjectInfo p) {
        try {
            return Files.readString(ProjectInfo.graphOf(p.dir), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }
}
