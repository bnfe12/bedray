package io.github.andrexarlay.vulkancompat.shaderhost.customrt;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.server.packs.repository.PackRepository;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * The game side of Mosaic Studio's Materials workspace. The studio bakes a material into the resource pack
 * {@code resourcepacks/Mosaic Materials} and writes {@code bedray-studio/materials.json} ({@code reload}: a stamp,
 * {@code pack}: the folder name). Twice a second this checks the stamp; on a change it enables the pack when it is not
 * selected yet and reloads resources, so the new surface shows on every block within a moment. Writes
 * {@code materials-status.txt} back. Costs one file stat per half second, nothing when the folder does not exist.
 */
final class MaterialLink {

    private static long lastCheck, lastStamp = -1, lastReload = -1;

    private MaterialLink() {}

    /** Render thread, once per frame. */
    static void tick() {
        long now = System.currentTimeMillis();
        if (now - lastCheck < 500) return;
        lastCheck = now;
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null || mc.gameDirectory == null) return;
            Path dir = mc.gameDirectory.toPath().resolve("bedray-studio");
            exportModels(mc, dir);
            Path f = dir.resolve("materials.json");
            if (!Files.exists(f)) return;
            long stamp = Files.getLastModifiedTime(f).toMillis();
            if (stamp == lastStamp) return;
            lastStamp = stamp;
            JsonObject o = JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)).getAsJsonObject();
            long reload = o.has("reload") ? o.get("reload").getAsLong() : 0;
            String pack = o.has("pack") ? o.get("pack").getAsString() : "Mosaic Materials";
            if (reload == lastReload) return;
            boolean first = lastReload == -1;
            lastReload = reload;
            // a request older than this game session was already loaded at startup; only make sure the pack is on
            boolean stale = first && reload < now - 60_000;
            mc.execute(() -> apply(mc, dir, "file/" + pack, !stale));
        } catch (Exception e) {
            // the studio may be writing the file; next check retries
        }
    }

    private static volatile boolean exported;

    /** Once per session, when the studio is in use: every entity model layer (vanilla and mods) for its previews. */
    private static void exportModels(Minecraft mc, Path dir) {
        if (exported || !Files.isDirectory(dir)) return;
        exported = true;
        Object models = mc.getEntityModels();
        Thread t = new Thread(() -> {
            try {
                int n = EntityModelExport.export(EntityModelExport.gameRoots(models), dir.resolve("models").resolve("entities.json"));
                org.slf4j.LoggerFactory.getLogger("Bedray").info("[VKB-STUDIO] exported {} entity model layers for Mosaic Studio", n);
            } catch (Exception e) {
                org.slf4j.LoggerFactory.getLogger("Bedray").warn("[VKB-STUDIO] entity model export failed: {}", e.toString());
            }
        }, "bedray-model-export");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    private static void apply(Minecraft mc, Path dir, String id, boolean reload) {
        String status;
        try {
            PackRepository repo = mc.getResourcePackRepository();
            repo.reload();
            if (!repo.isAvailable(id)) {
                status = "pack " + id + " not found in resourcepacks/";
            } else if (!repo.getSelectedIds().contains(id)) {
                repo.addPack(id);
                mc.options.updateResourcePacks(repo);
                mc.reloadResourcePacks();
                status = "enabled " + id + " and reloaded resources";
            } else if (reload) {
                mc.reloadResourcePacks();
                status = "reloaded resources";
            } else {
                status = "pack is on";
            }
        } catch (RuntimeException e) {
            status = "reload failed: " + e;
        }
        try {
            Path tmp = dir.resolve("materials-status.txt.tmp");
            Files.writeString(tmp, status + " (" + java.time.LocalTime.now().withNano(0) + ")", StandardCharsets.UTF_8);
            Files.move(tmp, dir.resolve("materials-status.txt"), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception ignored) {
            // status is a courtesy
        }
    }
}
