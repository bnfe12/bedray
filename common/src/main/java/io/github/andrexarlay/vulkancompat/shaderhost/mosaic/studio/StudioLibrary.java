package io.github.andrexarlay.vulkancompat.shaderhost.mosaic.studio;

import io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicMaterial;

import javax.imageio.ImageIO;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Where textures come from: Minecraft client jars, resource packs (folders and zips) of the game folder, and the studio's
 * own material pack. Lists {@code assets/<ns>/textures/**.png} entries, reads images, makes thumbnails, and knows where
 * material results are written (a resource pack the game loads).
 */
final class StudioLibrary {

    enum Kind { VANILLA, PACK, PROJECT }

    record Source(String id, String label, Path path, Kind kind) {
        boolean zip() { return Files.isRegularFile(path); }
        @Override public String toString() { return label; }
    }

    static final String MATERIAL_PACK = "Mosaic Materials";
    static final Pattern TEXTURE = Pattern.compile("^assets/([a-z0-9_.-]+)/textures/(.+)\\.png$");

    private StudioLibrary() {}

    // ------------------------------------------------------------------ discovery

    static Path home() { return Path.of(System.getProperty("user.home")); }

    /** The game folder: Settings, else the folder above shaderpacks/ when the shader graph lives in one. */
    static Path gameDir(StudioModel model) {
        String s = StudioUi.settings.gameDir;
        if (s != null && !s.isBlank() && Files.isDirectory(Path.of(s))) return Path.of(s);
        Path f = model.shaderFile != null ? model.shaderFile : model.doc.file;
        for (Path p = f == null ? null : f.toAbsolutePath(); p != null; p = p.getParent())
            if (p.getFileName() != null && p.getFileName().toString().equals("shaderpacks") && p.getParent() != null) return p.getParent();
        return null;
    }

    /** Resource pack the studio writes materials into (inside the game folder when known). */
    static Path materialPack(StudioModel model) {
        Path g = gameDir(model);
        return (g != null ? g.resolve("resourcepacks") : home().resolve(".bedray").resolve("studio")).resolve(MATERIAL_PACK);
    }

    static void ensurePack(Path pack) throws java.io.IOException {
        Files.createDirectories(pack);
        Path meta = pack.resolve("pack.mcmeta");
        if (!Files.exists(meta))
            Files.writeString(meta, "{\"pack\": {\"description\": \"Materials made in Mosaic Studio (Bedray)\", \"pack_format\": 69,"
                + " \"supported_formats\": {\"min_inclusive\": 15, \"max_inclusive\": 999}, \"min_format\": 15, \"max_format\": 999}}",
                StandardCharsets.UTF_8);
    }

    private static final Pattern VERSION = Pattern.compile("(\\d+)\\.(\\d+)(?:\\.(\\d+))?");

    static long versionKey(String v) {
        Matcher m = VERSION.matcher(v);
        if (!m.find()) return 0;
        return Long.parseLong(m.group(1)) * 1_000_000 + Long.parseLong(m.group(2)) * 1_000 + (m.group(3) == null ? 0 : Long.parseLong(m.group(3)));
    }

    /** Client jars found in the usual launcher and build caches, newest first. */
    static List<Source> vanillaJars() {
        Map<String, Path> found = new LinkedHashMap<>();
        String custom = StudioUi.settings.vanillaJar;
        if (custom != null && !custom.isBlank() && Files.isRegularFile(Path.of(custom))) found.put("custom", Path.of(custom));
        String appdata = System.getenv("APPDATA");
        List<Path> versionRoots = new ArrayList<>();
        if (appdata != null) versionRoots.add(Path.of(appdata, ".minecraft", "versions"));
        versionRoots.add(home().resolve(".minecraft").resolve("versions"));
        versionRoots.add(home().resolve("Library/Application Support/minecraft/versions"));
        for (Path root : versionRoots)
            each(root, d -> {
                Path jar = d.resolve(d.getFileName() + ".jar");
                if (Files.isRegularFile(jar)) found.putIfAbsent(d.getFileName().toString(), jar);
            });
        each(home().resolve(".gradle/caches/fabric-loom"), d -> {
            Path jar = d.resolve("minecraft-client.jar");
            if (Files.isRegularFile(jar) && versionKey(d.getFileName().toString()) > 0) found.putIfAbsent(d.getFileName().toString(), jar);
        });
        List<Path> prism = new ArrayList<>();
        if (appdata != null) prism.add(Path.of(appdata, "PrismLauncher"));
        prism.add(home().resolve(".local/share/PrismLauncher"));
        prism.add(home().resolve("Library/Application Support/PrismLauncher"));
        for (Path p : prism)
            each(p.resolve("libraries/com/mojang/minecraft"), d -> {
                Path jar = d.resolve("minecraft-" + d.getFileName() + "-client.jar");
                if (Files.isRegularFile(jar)) found.putIfAbsent(d.getFileName().toString(), jar);
            });
        List<Source> out = new ArrayList<>();
        found.entrySet().stream()
            .sorted(Comparator.comparingLong((Map.Entry<String, Path> e) -> e.getKey().equals("custom") ? Long.MAX_VALUE : versionKey(e.getKey())).reversed())
            .limit(4)
            .forEach(e -> out.add(new Source("jar:" + e.getValue(), "Minecraft " + (e.getKey().equals("custom") ? "(chosen jar)" : e.getKey()),
                e.getValue(), Kind.VANILLA)));
        return out;
    }

    static List<Source> sources(StudioModel model) {
        List<Source> out = new ArrayList<>();
        Path pack = materialPack(model);
        out.add(new Source("project:" + pack, "Project: " + MATERIAL_PACK, pack, Kind.PROJECT));
        out.addAll(vanillaJars());
        Path g = gameDir(model);
        if (g != null)
            each(g.resolve("resourcepacks"), p -> {
                String n = p.getFileName().toString();
                if (n.equals(MATERIAL_PACK)) return;
                if (Files.isDirectory(p.resolve("assets")) || n.toLowerCase(Locale.ROOT).endsWith(".zip"))
                    out.add(new Source("pack:" + p, "Pack: " + n.replaceAll("(?i)\\.zip$", ""), p, Kind.PACK));
            });
        return out;
    }

    private static void each(Path dir, java.util.function.Consumer<Path> f) {
        if (!Files.isDirectory(dir)) return;
        try (Stream<Path> s = Files.list(dir)) {
            s.sorted().forEach(f);
        } catch (Exception ignored) {
            // unreadable folder
        }
    }

    // ------------------------------------------------------------------ listing and reading

    private static final Map<String, List<String>> LISTS = new ConcurrentHashMap<>();

    /** Texture entries ("assets/minecraft/textures/block/stone.png"), sorted; cached per source. */
    static List<String> textures(Source s) {
        if (s.kind() == Kind.PROJECT) return list(s); // changes while the studio runs
        return LISTS.computeIfAbsent(s.id(), k -> list(s));
    }

    /** A source back from its id ("jar:<path>", "pack:<path>", "project:<path>"); null when the file is gone. */
    static Source fromId(String id) {
        int colon = id.indexOf(':');
        if (colon < 0) return null;
        Path p = Path.of(id.substring(colon + 1));
        if (!Files.exists(p)) return null;
        String prefix = id.substring(0, colon);
        Kind k = prefix.equals("jar") ? Kind.VANILLA : prefix.equals("pack") ? Kind.PACK : Kind.PROJECT;
        String label = k == Kind.VANILLA ? "Minecraft (" + p.getFileName() + ")" : (k == Kind.PACK ? "Pack: " : "Project: ") + p.getFileName();
        return new Source(id, label, p, k);
    }

    static void forget(Source s) { LISTS.remove(s.id()); }

    private static List<String> list(Source s) {
        List<String> out = new ArrayList<>();
        try {
            if (s.zip()) {
                try (ZipFile z = new ZipFile(s.path().toFile())) {
                    z.stream().map(ZipEntry::getName).filter(n -> TEXTURE.matcher(n).matches() && !derived(n)).forEach(out::add);
                }
            } else if (Files.isDirectory(s.path())) {
                try (Stream<Path> w = Files.walk(s.path())) {
                    w.filter(Files::isRegularFile).map(p -> s.path().relativize(p).toString().replace('\\', '/'))
                        .filter(n -> TEXTURE.matcher(n).matches() && !derived(n)).forEach(out::add);
                }
            }
        } catch (Exception ignored) {
            // unreadable pack: empty list
        }
        out.sort(Comparator.naturalOrder());
        return out;
    }

    /** LabPBR maps and the studio's own outputs are not textures to start a material from. */
    static boolean derived(String entry) { return entry.endsWith("_s.png") || entry.endsWith("_n.png"); }

    static byte[] read(Source s, String entry) throws java.io.IOException {
        if (s.zip()) {
            try (ZipFile z = new ZipFile(s.path().toFile())) {
                ZipEntry e = z.getEntry(entry);
                if (e == null) throw new java.io.FileNotFoundException(entry);
                return z.getInputStream(e).readAllBytes();
            }
        }
        return Files.readAllBytes(s.path().resolve(entry));
    }

    static BufferedImage image(Source s, String entry) {
        try {
            return ImageIO.read(new ByteArrayInputStream(read(s, entry)));
        } catch (Exception e) {
            return null;
        }
    }

    static MosaicMaterial.Image toMaterial(BufferedImage img) {
        int w = img.getWidth(), h = img.getHeight();
        return new MosaicMaterial.Image(w, h, img.getRGB(0, 0, w, h, null, 0, w));
    }

    static BufferedImage fromArgb(int w, int h, int[] argb) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        img.setRGB(0, 0, w, h, argb, 0, w);
        return img;
    }

    private static final Map<String, Image> THUMBS = new ConcurrentHashMap<>();

    /** A square nearest-neighbour thumbnail (first frame of animation strips), or null while it loads. */
    static Image thumb(Source s, String entry, int size) {
        return THUMBS.get(s.id() + "|" + entry + "|" + size);
    }

    static void loadThumb(Source s, String entry, int size) {
        String key = s.id() + "|" + entry + "|" + size;
        if (THUMBS.containsKey(key)) return;
        BufferedImage img = image(s, entry);
        if (img == null) img = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        int frame = Math.min(img.getWidth(), img.getHeight());
        BufferedImage first = img.getSubimage(0, 0, frame, frame);
        BufferedImage t = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        var g = t.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g.drawImage(first, 0, 0, size, size, null);
        g.dispose();
        THUMBS.put(key, t);
    }

    /** "assets/minecraft/textures/block/stone.png" -> "minecraft:block/stone". */
    static String id(String entry) {
        Matcher m = TEXTURE.matcher(entry);
        return m.matches() ? m.group(1) + ":" + m.group(2) : entry;
    }

    static String shortName(String entry) {
        String id = id(entry);
        return id.substring(id.lastIndexOf('/') + 1);
    }

    /** block, item, entity, ... (the folder under textures/). */
    static String group(String entry) {
        Matcher m = TEXTURE.matcher(entry);
        if (!m.matches()) return "other";
        String rest = m.group(2);
        int slash = rest.indexOf('/');
        return slash < 0 ? "other" : rest.substring(0, slash);
    }
}
