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

    enum Kind { VANILLA, PACK, PROJECT, MOD }

    /** The version Bedray is written for: its textures are preferred and other versions are flagged. */
    static final String TARGET = "26.3";

    /** A game installation found in a launcher: its folder (resourcepacks/, mods/, shaderpacks/) and Minecraft version. */
    record Instance(String launcher, String name, Path dir, String version) {
        @Override public String toString() { return launcher + "  -  " + name + (version == null ? "" : "  (" + version + ")"); }
    }

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

    private static Path appdata() {
        String a = System.getenv("APPDATA");
        return a == null ? null : Path.of(a);
    }

    /** Folders a launcher may keep its data in, per OS. */
    private static List<Path> launcherRoots(String windowsName, String macName, String linuxName) {
        List<Path> out = new ArrayList<>();
        if (appdata() != null) out.add(appdata().resolve(windowsName));
        out.add(home().resolve("Library/Application Support").resolve(macName));
        out.add(home().resolve(".local/share").resolve(linuxName));
        out.add(home().resolve(".config").resolve(linuxName));
        return out;
    }

    /** Every client jar found, version -> jar (launchers, the official launcher, build caches, the chosen jar). */
    static Map<String, Path> allJars() {
        Map<String, Path> found = new LinkedHashMap<>();
        String custom = StudioUi.settings.vanillaJar;
        if (custom != null && !custom.isBlank() && Files.isRegularFile(Path.of(custom)))
            found.put(versionIn(Path.of(custom).getFileName().toString(), "chosen"), Path.of(custom));
        List<Path> versionRoots = new ArrayList<>();
        for (Path r : launcherRoots(".minecraft", "minecraft", "../../.minecraft")) versionRoots.add(r.resolve("versions"));
        versionRoots.add(home().resolve(".minecraft/versions"));
        versionRoots.add(home().resolve("curseforge/minecraft/Install/versions"));
        for (Path r : launcherRoots("ModrinthApp", "ModrinthApp", "ModrinthApp")) versionRoots.add(r.resolve("meta/versions"));
        for (Path r : launcherRoots("com.modrinth.theseus", "com.modrinth.theseus", "com.modrinth.theseus")) versionRoots.add(r.resolve("meta/versions"));
        for (Path root : versionRoots)
            each(root, d -> {
                Path jar = d.resolve(d.getFileName() + ".jar");
                if (Files.isRegularFile(jar) && versionKey(d.getFileName().toString()) > 0) found.putIfAbsent(d.getFileName().toString(), jar);
            });
        for (Path r : launcherRoots("PrismLauncher", "PrismLauncher", "PrismLauncher"))
            each(r.resolve("libraries/com/mojang/minecraft"), d -> {
                Path jar = d.resolve("minecraft-" + d.getFileName() + "-client.jar");
                if (Files.isRegularFile(jar)) found.putIfAbsent(d.getFileName().toString(), jar);
            });
        each(home().resolve(".gradle/caches/fabric-loom"), d -> {
            Path jar = d.resolve("minecraft-client.jar");
            if (Files.isRegularFile(jar) && versionKey(d.getFileName().toString()) > 0) found.putIfAbsent(d.getFileName().toString(), jar);
        });
        // a Bedray checkout (libs/minecraft-<version>-client.jar) above the working folder: the version the mod is built for
        for (Path p = Path.of(System.getProperty("user.dir")).toAbsolutePath(); p != null; p = p.getParent())
            each(p.resolve("libs"), f -> {
                Matcher m = Pattern.compile("minecraft-([0-9.]+)-client\\.jar").matcher(f.getFileName().toString());
                if (m.matches()) found.putIfAbsent(m.group(1), f);
            });
        each(home().resolve(".gradle/caches/neoformruntime/artifacts"), f -> {
            Matcher m = Pattern.compile("minecraft_([0-9.]+)_client\\.jar").matcher(f.getFileName().toString());
            if (m.matches()) found.putIfAbsent(m.group(1), f);
        });
        return found;
    }

    private static String versionIn(String name, String fallback) {
        Matcher m = VERSION.matcher(name);
        return m.find() ? m.group() : fallback;
    }

    /** Distance of a version from the target, for sorting: 26.3 first, then the closest. */
    static long distance(String v) {
        if (v == null) return Long.MAX_VALUE / 2;
        return Math.abs(versionKey(v) - versionKey(TARGET));
    }

    /** Client jars as sources, the target version (26.3) or the closest first. */
    static List<Source> vanillaJars() {
        List<Source> out = new ArrayList<>();
        allJars().entrySet().stream()
            .sorted(Comparator.comparingLong((Map.Entry<String, Path> e) -> distance(e.getKey())))
            .limit(6)
            .forEach(e -> out.add(new Source("jar:" + e.getValue(), "Minecraft " + e.getKey()
                + (e.getKey().equals(TARGET) ? "" : versionKey(e.getKey()) > 0 ? "  (Bedray: " + TARGET + ")" : ""), e.getValue(), Kind.VANILLA)));
        return out;
    }

    // ------------------------------------------------------------------ libraries of a client jar (for offline model export)

    /** Library folders of launchers (maven layout) and a Bedray checkout's libs/ next to a dev jar. */
    static List<Path> libraryRoots(Path clientJar) {
        List<Path> roots = new ArrayList<>();
        for (Path r : launcherRoots("PrismLauncher", "PrismLauncher", "PrismLauncher")) roots.add(r.resolve("libraries"));
        for (Path r : launcherRoots("MultiMC", "MultiMC", "multimc")) roots.add(r.resolve("libraries"));
        for (Path r : launcherRoots(".minecraft", "minecraft", "../../.minecraft")) roots.add(r.resolve("libraries"));
        roots.add(home().resolve(".minecraft/libraries"));
        roots.add(home().resolve("curseforge/minecraft/Install/libraries"));
        for (Path r : launcherRoots("ModrinthApp", "ModrinthApp", "ModrinthApp")) roots.add(r.resolve("meta/libraries"));
        roots.add(home().resolve(".gradle/caches/modules-2/files-2.1"));
        if (clientJar != null && clientJar.getParent() != null) roots.add(clientJar.getParent());
        return roots;
    }

    /** Newest jar of every library artifact found (natives and LWJGL left out: model building needs neither). */
    static List<Path> libraryJars(Path clientJar) {
        Map<String, Path> best = new LinkedHashMap<>();
        Map<String, Long> bestVersion = new LinkedHashMap<>();
        Pattern artifact = Pattern.compile("^([A-Za-z][A-Za-z0-9_.-]*?)-(\\d[\\w.+-]*)\\.jar$");
        for (Path root : libraryRoots(clientJar)) {
            if (!Files.isDirectory(root)) continue;
            try (Stream<Path> w = Files.walk(root, 9)) {
                for (Path p : (Iterable<Path>) w.filter(f -> f.toString().endsWith(".jar"))::iterator) {
                    String n = p.getFileName().toString();
                    if (n.contains("natives") || n.startsWith("lwjgl") || n.startsWith("minecraft-") || n.contains("-sources")) continue;
                    Matcher m = artifact.matcher(n);
                    if (!m.matches()) continue;
                    long v = versionKey(m.group(2));
                    if (v > bestVersion.getOrDefault(m.group(1), -1L)) {
                        bestVersion.put(m.group(1), v);
                        best.put(m.group(1), p);
                    }
                }
            } catch (Exception ignored) {
                // unreadable folder
            }
        }
        return new ArrayList<>(best.values());
    }

    // ------------------------------------------------------------------ launcher instances

    private static final Map<String, List<Instance>> INSTANCES = new ConcurrentHashMap<>();

    static void forgetInstances() { INSTANCES.clear(); }

    /** Game installations of the official launcher, Prism / MultiMC, CurseForge, Modrinth App, ATLauncher and GDLauncher. */
    static List<Instance> instances() {
        return INSTANCES.computeIfAbsent("all", k -> findInstances());
    }

    private static List<Instance> findInstances() {
        List<Instance> out = new ArrayList<>();
        for (Path r : List.of(appdata() == null ? home().resolve(".minecraft") : appdata().resolve(".minecraft"),
            home().resolve("Library/Application Support/minecraft"), home().resolve(".minecraft")))
            if (Files.isDirectory(r.resolve("versions")) && out.stream().noneMatch(i -> i.dir().equals(r))) {
                String[] newest = {null};
                each(r.resolve("versions"), d -> {
                    String v = d.getFileName().toString();
                    if (versionKey(v) > 0 && (newest[0] == null || distance(v) < distance(newest[0]))) newest[0] = v;
                });
                out.add(new Instance("Minecraft Launcher", ".minecraft", r, newest[0]));
            }
        for (String launcher : List.of("PrismLauncher", "MultiMC", "PolyMC"))
            for (Path r : launcherRoots(launcher, launcher, launcher))
                each(r.resolve("instances"), d -> {
                    Path game = Files.isDirectory(d.resolve("minecraft")) ? d.resolve("minecraft") : d.resolve(".minecraft");
                    if (!Files.isDirectory(game)) return;
                    out.add(new Instance(launcher.equals("PrismLauncher") ? "Prism" : launcher, d.getFileName().toString(), game,
                        jsonVersion(d.resolve("mmc-pack.json"), "net.minecraft")));
                });
        each(home().resolve("curseforge/minecraft/Instances"), d ->
            out.add(new Instance("CurseForge", d.getFileName().toString(), d, jsonField(d.resolve("minecraftinstance.json"), "gameVersion"))));
        for (String app : List.of("ModrinthApp", "com.modrinth.theseus"))
            for (Path r : launcherRoots(app, app, app))
                each(r.resolve("profiles"), d -> out.add(new Instance("Modrinth", d.getFileName().toString(), d,
                    jsonField(d.resolve("profile.json"), "game_version"))));
        for (Path r : launcherRoots("ATLauncher", "ATLauncher", "atlauncher"))
            each(r.resolve("instances"), d -> out.add(new Instance("ATLauncher", d.getFileName().toString(), d,
                jsonField(d.resolve("instance.json"), "minecraftVersion"))));
        for (Path r : launcherRoots("gdlauncher_next", "gdlauncher_next", "gdlauncher_next"))
            each(r.resolve("instances"), d -> out.add(new Instance("GDLauncher", d.getFileName().toString(), d,
                jsonField(d.resolve("config.json"), "mcVersion"))));
        out.sort(Comparator.comparingLong((Instance i) -> distance(i.version())).thenComparing(Instance::name));
        return out;
    }

    /** The version of a component in a Prism/MultiMC mmc-pack.json. */
    private static String jsonVersion(Path file, String uid) {
        try {
            var root = com.google.gson.JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            for (var c : root.getAsJsonArray("components")) {
                var o = c.getAsJsonObject();
                if (o.has("uid") && o.get("uid").getAsString().equals(uid) && o.has("version")) return o.get("version").getAsString();
            }
        } catch (Exception ignored) {
            // unknown format
        }
        return null;
    }

    /** The first string value with this key anywhere in a JSON file (launchers nest it differently). */
    private static String jsonField(Path file, String key) {
        try {
            if (!Files.isRegularFile(file) || Files.size(file) > 4_000_000) return null;
            Matcher m = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\"([^\"]+)\"").matcher(Files.readString(file));
            return m.find() ? m.group(1) : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** The instance the studio works with: the chosen game folder, else the one closest to 26.3. */
    static Instance currentInstance(StudioModel model) {
        Path g = gameDir(model);
        for (Instance i : instances()) if (g != null && i.dir().toAbsolutePath().normalize().equals(g.toAbsolutePath().normalize())) return i;
        return g != null ? new Instance("Folder", g.getFileName() == null ? g.toString() : g.getFileName().toString(), g, null) : null;
    }

    static List<Source> sources(StudioModel model) {
        List<Source> out = new ArrayList<>();
        Path pack = materialPack(model);
        out.add(new Source("project:" + pack, "Project: " + MATERIAL_PACK, pack, Kind.PROJECT));
        Instance inst = currentInstance(model);
        List<Source> jars = vanillaJars();
        if (inst != null && inst.version() != null) {
            Path own = allJars().get(inst.version());
            if (own != null) {
                jars.removeIf(s -> s.path().equals(own));
                jars.add(0, new Source("jar:" + own, "Minecraft " + inst.version() + " (this instance)", own, Kind.VANILLA));
            }
        }
        out.addAll(jars);
        Path g = gameDir(model);
        if (g != null)
            each(g.resolve("mods"), p -> {
                String n = p.getFileName().toString();
                if (n.toLowerCase(Locale.ROOT).endsWith(".jar")) out.add(new Source("mod:" + p, "Mod: " + n.replaceAll("(?i)\\.jar$", ""), p, Kind.MOD));
            });
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
        Kind k = switch (prefix) {
            case "jar" -> Kind.VANILLA;
            case "pack" -> Kind.PACK;
            case "mod" -> Kind.MOD;
            default -> Kind.PROJECT;
        };
        String label = switch (k) {
            case VANILLA -> "Minecraft (" + p.getFileName() + ")";
            case PACK -> "Pack: " + p.getFileName();
            case MOD -> "Mod: " + p.getFileName();
            default -> "Project: " + p.getFileName();
        };
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
