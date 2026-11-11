package io.github.andrexarlay.vulkancompat.rt.material.bedrock;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Imports Bedrock Edition ray-tracing resource packs (Vanilla RTX and other packs declaring the {@code raytraced}
 * capability) for Bedray: drop a pack folder, {@code .mcpack} or {@code .zip} into {@code <game>/bedray/bedrock_rtx_packs/}
 * and press the button on the settings page. Each pack's MERS + heightmap texture sets become a Java resource pack
 * {@code resourcepacks/Bedray RTX - <name>/} holding only LabPBR {@code _s} / {@code _n} maps (Java keeps its own colour
 * textures), which is enabled and reloaded at once. Conversion runs off the render thread.
 *
 * <p>Only material maps are read. Bedrock shader packs ({@code .rtpack}, BetterRTX, renderer {@code .material.bin}) are
 * compiled DirectX shaders for the Bedrock renderer and cannot run in Bedray; they are reported and skipped. The output
 * stays on this machine and keeps the source pack's licence.
 */
public final class BedrockRtxPackImporter {
    private static final Logger LOGGER = LoggerFactory.getLogger("Bedray/BedrockRtx");
    public static final String SOURCE_DIR = "bedray/bedrock_rtx_packs";
    private static final String MARKER = "bedray_generated.txt";
    private static final String PACK_PREFIX = "Bedray RTX - ";
    private static final int PACK_FORMAT = 97;
    private static volatile boolean running;

    private BedrockRtxPackImporter() {
    }

    /** True while an import (conversion or the resource reload it triggers) is in progress. */
    public static boolean isRunning() {
        return running;
    }

    /** The drop folder, created on first use so the player can find it. */
    public static Path sourceDir(Minecraft mc) throws IOException {
        Path dir = mc.gameDirectory.toPath().resolve(SOURCE_DIR);
        Files.createDirectories(dir);
        return dir;
    }

    /** Settings button: convert every pack in the drop folder in the background, then enable the results. */
    public static void importAllAsync() {
        Minecraft mc = Minecraft.getInstance();
        if (running) {
            notify(mc, "Bedray: import already running", "");
            return;
        }
        running = true;
        ResourceManager resources = mc.getResourceManager();
        Thread worker = new Thread(() -> {
            List<String> enabled = new ArrayList<>();
            StringBuilder summary = new StringBuilder();
            try {
                Path src = sourceDir(mc);
                Map<String, String> javaTextures = javaBlockTextures(resources);
                List<Path> entries;
                try (Stream<Path> s = Files.list(src)) {
                    entries = s.sorted().toList();
                }
                for (Path entry : entries) {
                    String name = entry.getFileName().toString();
                    if (name.toLowerCase(Locale.ROOT).endsWith(".rtpack") || name.toLowerCase(Locale.ROOT).endsWith(".material.bin")) {
                        LOGGER.warn("[BEDROCK-RTX] skipped {}: Bedrock shader pack (compiled DirectX renderer shaders), not a material pack", name);
                        summary.append(name).append(": shader pack, not supported; ");
                        continue;
                    }
                    try (PackSource pack = PackSource.open(entry)) {
                        if (pack == null) continue;
                        Result r = convert(pack, mc.getResourcePackDirectory(), javaTextures, resources);
                        LOGGER.info("[BEDROCK-RTX] pack=\"{}\" converted={} unmatched={} water_skipped={} errors={} out=\"{}\"",
                            r.packName, r.converted, r.unmatched, r.water, r.errors, r.folder);
                        summary.append(r.packName).append(": ").append(r.converted).append(" textures; ");
                        if (r.converted > 0) enabled.add("file/" + r.folder);
                    } catch (IOException | RuntimeException e) {
                        LOGGER.error("[BEDROCK-RTX] pack {} failed: {}", name, e.toString());
                        summary.append(name).append(": failed (").append(e.getMessage()).append("); ");
                    }
                }
                if (entries.isEmpty()) {
                    summary.append("no packs in ").append(SOURCE_DIR);
                }
            } catch (IOException | RuntimeException e) {
                LOGGER.error("[BEDROCK-RTX] import failed: {}", e.toString());
                summary.append("failed: ").append(e.getMessage());
            } finally {
                String text = summary.toString();
                mc.execute(() -> {
                    try {
                        if (!enabled.isEmpty()) enable(mc, enabled);
                    } finally {
                        running = false;
                    }
                    notify(mc, "Bedray: Bedrock RTX import", text);
                });
            }
        }, "Bedray-BedrockRtxImport");
        worker.setDaemon(true);
        worker.start();
    }

    private static void enable(Minecraft mc, List<String> ids) {
        PackRepository repo = mc.getResourcePackRepository();
        repo.reload();
        List<String> selected = new ArrayList<>(repo.getSelectedIds());
        boolean changed = false;
        for (String id : ids) {
            if (!repo.getAvailableIds().contains(id)) {
                LOGGER.warn("[BEDROCK-RTX] generated pack {} is not offered by the pack repository", id);
                continue;
            }
            if (!selected.contains(id)) {
                selected.add(id);
                changed = true;
            }
        }
        repo.setSelected(selected);
        mc.options.updateResourcePacks(repo);
        LOGGER.info("[BEDROCK-RTX] enabled={} changed={} selected={}", ids, changed, selected.size());
        mc.reloadResourcePacks();
    }

    private record Result(String packName, String folder, int converted, int unmatched, int water, int errors) {
    }

    private static Result convert(PackSource pack, Path resourcePacks, Map<String, String> javaTextures, ResourceManager resources)
            throws IOException {
        JsonObject manifest = JsonParser.parseString(new String(pack.read("manifest.json"), StandardCharsets.UTF_8)).getAsJsonObject();
        JsonObject header = manifest.getAsJsonObject("header");
        String packName = header != null && header.has("name") ? header.get("name").getAsString().trim() : pack.name();
        boolean raytraced = manifest.has("capabilities") && manifest.get("capabilities").toString().contains("raytraced");
        if (!raytraced) {
            LOGGER.warn("[BEDROCK-RTX] pack \"{}\" does not declare the raytraced capability; converting its texture sets anyway", packName);
        }
        String folder = PACK_PREFIX + packName.replaceAll("[\\\\/:*?\"<>|§]", "_");
        Path out = resourcePacks.resolve(folder);
        if (Files.exists(out)) {
            if (!Files.exists(out.resolve(MARKER))) {
                throw new IOException("\"" + folder + "\" exists and was not generated by Bedray; not overwritten");
            }
            try (Stream<Path> walk = Files.walk(out)) {
                for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) Files.delete(p);
            }
        }
        Path blockDir = out.resolve("assets/minecraft/textures/block");
        Files.createDirectories(blockDir);
        int converted = 0;
        int unmatched = 0;
        int water = 0;
        int errors = 0;
        Set<String> used = new HashSet<>();
        for (String setPath : pack.list("textures/blocks/", ".texture_set.json")) {
            String dir = setPath.substring(0, setPath.lastIndexOf('/') + 1);
            String name = setPath.substring(dir.length(), setPath.length() - ".texture_set.json".length());
            if (BedrockMaterialConverter.isWater(name)) {
                water++;
                continue;
            }
            try {
                JsonObject set = JsonParser.parseString(new String(pack.read(setPath), StandardCharsets.UTF_8))
                    .getAsJsonObject().getAsJsonObject("minecraft:texture_set");
                String mersName = string(set, "metalness_emissive_roughness_subsurface");
                boolean hasSubsurface = mersName != null;
                if (mersName == null) mersName = string(set, "metalness_emissive_roughness");
                String target = null;
                for (String c : BedrockMaterialConverter.javaCandidates(name)) {
                    if (javaTextures.containsKey(c) && !used.contains(c)) {
                        target = c;
                        break;
                    }
                }
                Optional<String> mersFile = mersName == null ? Optional.empty() : pack.findImage(dir + mersName);
                if (target == null || mersFile.isEmpty()) {
                    unmatched++;
                    continue;
                }
                BufferedImage base = readJava(resources, javaTextures.get(target));
                int jw = base.getWidth();
                int jh = base.getHeight();
                int[] albedo = base.getRGB(0, 0, jw, jh, null, 0, jw);
                TgaReader.Image mers = pack.image(mersFile.get());
                int[] m = BedrockMaterialConverter.toJavaLayout(mers.argb(), mers.width(), mers.height(), jw, jh);
                if (!hasSubsurface) {
                    for (int i = 0; i < m.length; i++) m[i] &= 0x00FFFFFF;
                }
                write(blockDir.resolve(target + "_s.png"), BedrockMaterialConverter.specular(m, albedo), jw, jh);
                String heightName = string(set, "heightmap");
                Optional<String> heightFile = heightName == null ? Optional.empty() : pack.findImage(dir + heightName);
                if (heightFile.isPresent()) {
                    TgaReader.Image hm = pack.image(heightFile.get());
                    int[] h = BedrockMaterialConverter.toJavaLayout(hm.argb(), hm.width(), hm.height(), jw, jh);
                    write(blockDir.resolve(target + "_n.png"),
                        BedrockMaterialConverter.normals(h, jw, jh, BedrockMaterialConverter.DEFAULT_NORMAL_STRENGTH), jw, jh);
                }
                used.add(target);
                converted++;
            } catch (IOException | RuntimeException e) {
                errors++;
                if (errors <= 5) LOGGER.warn("[BEDROCK-RTX] {} in \"{}\": {}", name, packName, e.toString());
            }
        }
        String mcmeta = "{\"pack\":{\"pack_format\":" + PACK_FORMAT + ",\"min_format\":[" + PACK_FORMAT + ",0],\"max_format\":["
            + PACK_FORMAT + ",1],\"description\":\"" + packName.replace("\"", "'") + " materials for Bedray (local conversion)\"}}";
        Files.writeString(out.resolve("pack.mcmeta"), mcmeta, StandardCharsets.UTF_8);
        Files.writeString(out.resolve(MARKER), "Generated by Bedray from the Bedrock pack \"" + packName
            + "\". Local use only: it keeps the source pack's licence. Safe to delete.\n", StandardCharsets.UTF_8);
        return new Result(packName, folder, converted, unmatched, water, errors);
    }

    private static String string(JsonObject o, String key) {
        JsonElement e = o == null ? null : o.get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
    }

    /** Java block texture name -> resource path, from the vanilla pack the game itself loaded. */
    private static Map<String, String> javaBlockTextures(ResourceManager resources) {
        Map<String, String> out = new HashMap<>();
        resources.listResources("textures/block", id -> id.getPath().endsWith(".png")).keySet().forEach(id -> {
            if (!"minecraft".equals(id.getNamespace())) return;
            String path = id.getPath();
            String rest = path.substring("textures/block/".length());
            if (rest.contains("/") || rest.endsWith("_s.png") || rest.endsWith("_n.png")) return;
            out.put(rest.substring(0, rest.length() - 4), path);
        });
        return out;
    }

    private static BufferedImage readJava(ResourceManager resources, String path) throws IOException {
        List<Resource> stack = resources.getResourceStack(Identifier.withDefaultNamespace(path));
        if (stack.isEmpty()) throw new IOException("no Java texture " + path);
        try (InputStream in = stack.get(stack.size() - 1).open()) {
            BufferedImage img = ImageIO.read(in);
            if (img == null) throw new IOException("unreadable Java texture " + path);
            return img;
        }
    }

    private static void write(Path file, int[] argb, int w, int h) throws IOException {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        img.setRGB(0, 0, w, h, argb, 0, w);
        if (!ImageIO.write(img, "png", file.toFile())) throw new IOException("no PNG writer");
    }

    private static void notify(Minecraft mc, String title, String text) {
        try {
            SystemToast.add(mc.gui.toastManager(), SystemToast.SystemToastId.PERIODIC_NOTIFICATION, Component.literal(title),
                Component.literal(text.length() > 160 ? text.substring(0, 157) + "..." : text));
            mc.gui.hud.getChat().addClientSystemMessage(Component.literal("[" + title + "] " + text));
        } catch (RuntimeException e) {
            LOGGER.warn("[BEDROCK-RTX] cannot show the notification: {}", e.toString());
        }
    }

    /** A Bedrock pack as a folder or an archive ({@code .mcpack}/{@code .zip}, manifest at the root or one level down). */
    private abstract static class PackSource implements AutoCloseable {
        abstract String name();

        abstract byte[] read(String path) throws IOException;

        abstract boolean exists(String path);

        abstract List<String> list(String prefix, String suffix) throws IOException;

        @Override
        public void close() throws IOException {
        }

        Optional<String> findImage(String base) {
            for (String ext : new String[] {".tga", ".png"}) {
                if (exists(base + ext)) return Optional.of(base + ext);
            }
            return Optional.empty();
        }

        TgaReader.Image image(String path) throws IOException {
            byte[] data = read(path);
            if (path.endsWith(".tga")) return TgaReader.read(data);
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(data));
            if (img == null) throw new IOException("unreadable image " + path);
            return new TgaReader.Image(img.getWidth(), img.getHeight(),
                img.getRGB(0, 0, img.getWidth(), img.getHeight(), null, 0, img.getWidth()));
        }

        static PackSource open(Path entry) throws IOException {
            String lower = entry.getFileName().toString().toLowerCase(Locale.ROOT);
            if (Files.isDirectory(entry)) {
                Path root = Files.exists(entry.resolve("manifest.json")) ? entry : null;
                if (root == null) {
                    try (Stream<Path> s = Files.list(entry)) {
                        root = s.filter(p -> Files.exists(p.resolve("manifest.json"))).findFirst().orElse(null);
                    }
                }
                return root == null ? null : new FolderSource(root);
            }
            if (lower.endsWith(".mcpack") || lower.endsWith(".zip")) {
                return new ZipSource(entry);
            }
            return null;
        }
    }

    private static final class FolderSource extends PackSource {
        private final Path root;

        FolderSource(Path root) {
            this.root = root;
        }

        @Override
        String name() {
            return root.getFileName().toString();
        }

        @Override
        byte[] read(String path) throws IOException {
            return Files.readAllBytes(root.resolve(path));
        }

        @Override
        boolean exists(String path) {
            return Files.isRegularFile(root.resolve(path));
        }

        @Override
        List<String> list(String prefix, String suffix) throws IOException {
            Path dir = root.resolve(prefix);
            if (!Files.isDirectory(dir)) return List.of();
            try (Stream<Path> walk = Files.walk(dir)) {
                return walk.filter(p -> p.getFileName().toString().endsWith(suffix))
                    .map(p -> root.relativize(p).toString().replace('\\', '/')).sorted().toList();
            }
        }
    }

    private static final class ZipSource extends PackSource {
        private final ZipFile zip;
        private final String base;
        private final String name;

        ZipSource(Path file) throws IOException {
            zip = new ZipFile(file.toFile());
            name = file.getFileName().toString();
            String found = null;
            var e = zip.entries();
            while (e.hasMoreElements()) {
                String n = e.nextElement().getName();
                if (n.endsWith("manifest.json") && n.chars().filter(c -> c == '/').count() <= 1) {
                    found = n.substring(0, n.length() - "manifest.json".length());
                    if (found.isEmpty()) break;
                }
            }
            if (found == null) {
                zip.close();
                throw new IOException("no manifest.json in " + name);
            }
            base = found;
        }

        @Override
        String name() {
            return name;
        }

        @Override
        byte[] read(String path) throws IOException {
            ZipEntry entry = zip.getEntry(base + path);
            if (entry == null) throw new IOException("missing " + path);
            try (InputStream in = zip.getInputStream(entry)) {
                return in.readAllBytes();
            }
        }

        @Override
        boolean exists(String path) {
            return zip.getEntry(base + path) != null;
        }

        @Override
        List<String> list(String prefix, String suffix) {
            List<String> out = new ArrayList<>();
            var e = zip.entries();
            while (e.hasMoreElements()) {
                String n = e.nextElement().getName();
                if (n.startsWith(base + prefix) && n.endsWith(suffix)) out.add(n.substring(base.length()));
            }
            out.sort(null);
            return out;
        }

        @Override
        public void close() throws IOException {
            zip.close();
        }
    }
}
