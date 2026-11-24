package io.github.andrexarlay.vulkancompat.shaderhost.customrt;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.andrexarlay.bedray.api.v1.light.LightProviders;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The game side of the Mosaic Studio link, in the active folder pack's {@code shaders/bedray/.studio/}:
 * <ul>
 *   <li>{@code before.png} / {@code after.png}: small frames of the world before and after the custom stage, a few per
 *   second (captured by {@link CustomRtExecutor}, encoded off the render thread, written atomically);</li>
 *   <li>{@code status.txt}: the stage state, compile time and last problem;</li>
 *   <li>{@code addons.json}: Bedray add-ons with their switch state;</li>
 *   <li>{@code control.json} (read): {@code disabledAddons}, {@code previewNode}, {@code nanCheck} written by the studio.</li>
 * </ul>
 * Only active while the folder exists or the studio created it, so a pack without the studio pays nothing.
 */
final class StudioLink {

    static final long FRAME_MS = 250, STATUS_MS = 1000;
    static final int MAX_WIDTH = 480;

    private static Path dir;
    private static long lastFrame, lastStatus, controlStamp = -1;
    private static final AtomicBoolean writing = new AtomicBoolean();
    private static final ExecutorService WRITER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "bedray-studio-link");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });

    private StudioLink() {}

    /** Called once per rendered frame from the render thread; returns true when the executor should capture a frame. */
    static boolean tick(Path liveDir) {
        if (liveDir == null) return false;
        Path d = liveDir.resolve("shaders").resolve("bedray").resolve(".studio");
        if (!Files.isDirectory(d)) return false; // the studio creates it (project metadata); no studio, no cost
        dir = d;
        long now = System.currentTimeMillis();
        if (now - lastStatus >= STATUS_MS) {
            lastStatus = now;
            readControl();
            writeStatus();
        }
        if (now - lastFrame >= FRAME_MS && !writing.get()) {
            lastFrame = now;
            return true;
        }
        return false;
    }

    /** Hands over a captured pair (RGBA8, rows already top-down); encoding happens on the writer thread. */
    static void deliver(int w, int h, ByteBuffer pixels) {
        if (dir == null || !writing.compareAndSet(false, true)) return;
        byte[] copy = new byte[w * h * 8];
        pixels.get(0, copy, 0, copy.length);
        Path target = dir;
        WRITER.execute(() -> {
            try {
                write(target.resolve("before.png"), copy, 0, w, h);
                write(target.resolve("after.png"), copy, w * h * 4, w, h);
            } catch (Exception ignored) {
                // a busy disk only drops a preview frame
            } finally {
                writing.set(false);
            }
        });
    }

    private static void write(Path file, byte[] rgba, int offset, int w, int h) throws java.io.IOException {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        int[] row = new int[w];
        for (int y = 0; y < h; y++) {
            int o = offset + y * w * 4;
            for (int x = 0; x < w; x++, o += 4)
                row[x] = (rgba[o] & 0xFF) << 16 | (rgba[o + 1] & 0xFF) << 8 | (rgba[o + 2] & 0xFF);
            img.setRGB(0, y, w, 1, row, 0, w);
        }
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        ImageIO.write(img, "png", tmp.toFile());
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private static void writeStatus() {
        CustomRtStage.Status s = CustomRtStage.status();
        String text = s.state() + " - " + (s.compileMicros() > 0 ? s.compileMicros() / 1000 + " ms compile, " : "")
            + (s.spirvBytes() > 0 ? s.spirvBytes() + " B SPIR-V, " : "") + s.dispatches() + " frames"
            + (s.previewNode() != null ? ", previewing " + s.previewNode() : "") + (s.nanCheck() ? ", NaN check" : "")
            + (s.lastProblem() != null ? " - last problem: " + s.lastProblem() : "");
        JsonArray addons = new JsonArray();
        for (LightProviders.Status a : LightProviders.status()) {
            JsonObject o = new JsonObject();
            o.addProperty("id", a.addonId());
            o.addProperty("name", a.addonId());
            o.addProperty("kind", "light provider" + (a.disabled() ? " (stopped after failures)" : ""));
            o.addProperty("version", a.lastAccepted() + " lights");
            o.addProperty("enabled", LightProviders.userEnabled(a.addonId()));
            addons.add(o);
        }
        Path d = dir;
        WRITER.execute(() -> {
            try {
                atomic(d.resolve("status.txt"), text);
                atomic(d.resolve("addons.json"), addons.toString());
            } catch (Exception ignored) {
                // next second retries
            }
        });
    }

    private static void atomic(Path file, String text) throws java.io.IOException {
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, text, StandardCharsets.UTF_8);
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    /** Applies control.json when it changed: add-on switches and the in-game node preview. */
    private static void readControl() {
        try {
            Path f = dir.resolve("control.json");
            if (!Files.exists(f)) return;
            long stamp = Files.getLastModifiedTime(f).toMillis();
            if (stamp == controlStamp) return;
            controlStamp = stamp;
            JsonObject o = JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)).getAsJsonObject();
            Set<String> off = new LinkedHashSet<>();
            if (o.has("disabledAddons")) for (var e : o.getAsJsonArray("disabledAddons")) off.add(e.getAsString());
            for (LightProviders.Status a : LightProviders.status()) LightProviders.setUserEnabled(a.addonId(), !off.contains(a.addonId()));
            String preview = o.has("previewNode") && !o.get("previewNode").isJsonNull() ? o.get("previewNode").getAsString() : null;
            boolean nan = o.has("nanCheck") && o.get("nanCheck").getAsBoolean();
            CustomRtStage.setMosaicDebug(preview == null || preview.isBlank() ? null : preview, nan);
        } catch (Exception ignored) {
            // the studio may be writing it right now
        }
    }
}
