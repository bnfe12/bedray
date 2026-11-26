package io.github.andrexarlay.vulkancompat.shaderhost.mosaic.studio;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A Mosaic project: a shader pack folder ({@code <pack>/shaders/bedray/mosaic.json}) with its card - name, description,
 * picture, author, version, GitHub repository - kept in {@code <pack>/mosaic-project.json}. The picture is
 * {@code <pack>/pack.png}, else the last preview or game frame of the project.
 */
final class ProjectInfo {

    static final String FILE = "mosaic-project.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    String name = "", description = "", author = "", version = "1.0.0", github = "", license = "", created = "";
    List<String> tags = new ArrayList<>();
    transient Path dir;

    static Path graphOf(Path pack) { return pack.resolve("shaders").resolve("bedray").resolve("mosaic.json"); }

    static boolean isProject(Path dir) { return dir != null && Files.isRegularFile(graphOf(dir)); }

    /** The pack folder of a graph file (.../<pack>/shaders/bedray/mosaic.json), or null. */
    static Path packOf(Path graph) {
        if (graph == null) return null;
        Path d = graph.toAbsolutePath().getParent();
        if (d == null || d.getFileName() == null || !d.getFileName().toString().equals("bedray")) return null;
        Path s = d.getParent();
        if (s == null || s.getFileName() == null || !s.getFileName().toString().equals("shaders")) return null;
        return s.getParent();
    }

    /** The card of a project folder: its mosaic-project.json, else what can be read from the folder (README, name). */
    static ProjectInfo load(Path dir) {
        ProjectInfo p = null;
        try {
            Path f = dir.resolve(FILE);
            if (Files.isRegularFile(f)) p = GSON.fromJson(Files.readString(f, StandardCharsets.UTF_8), ProjectInfo.class);
        } catch (Exception ignored) {
            // a broken card is rebuilt from the folder
        }
        if (p == null) p = new ProjectInfo();
        if (p.tags == null) p.tags = new ArrayList<>();
        for (String s : new String[] {p.name, p.description, p.author, p.version, p.github, p.license, p.created}) if (s == null) { }
        if (p.name == null || p.name.isBlank()) p.name = dir.getFileName().toString();
        if (p.description == null) p.description = "";
        if (p.description.isBlank()) p.description = readmeSummary(dir);
        if (p.author == null) p.author = "";
        if (p.version == null) p.version = "1.0.0";
        if (p.github == null) p.github = "";
        if (p.license == null) p.license = "";
        if (p.created == null) p.created = "";
        p.dir = dir;
        return p;
    }

    void save() throws java.io.IOException {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(FILE), GSON.toJson(this), StandardCharsets.UTF_8);
    }

    /** The first paragraph of README.md (headings, badges and images left out), or "". */
    static String readmeSummary(Path dir) {
        try {
            Path r = dir.resolve("README.md");
            if (!Files.isRegularFile(r)) return "";
            StringBuilder sb = new StringBuilder();
            for (String line : Files.readAllLines(r, StandardCharsets.UTF_8)) {
                String t = line.trim();
                if (t.startsWith("#") || t.startsWith("![") || t.startsWith("[![") || t.startsWith("<")) {
                    if (sb.length() > 0) break;
                    continue;
                }
                if (t.isEmpty()) {
                    if (sb.length() > 0) break;
                    continue;
                }
                if (sb.length() > 0) sb.append(' ');
                sb.append(t);
            }
            return sb.length() > 300 ? sb.substring(0, 297) + "..." : sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    /** The picture of the project: pack.png, else the last preview picture, else the last game frame; null when none. */
    Path picture() {
        for (Path p : new Path[] {dir.resolve("pack.png"), dir.resolve("shaders/bedray/.studio/thumbnail.png"),
            dir.resolve("shaders/bedray/.studio/after.png")})
            if (Files.isRegularFile(p)) return p;
        return null;
    }

    /** Writes the picture of the project (pack.png), scaled to at most 640 wide. */
    void setPicture(BufferedImage img) throws java.io.IOException {
        BufferedImage out = img.getWidth() > 640 ? PreviewPanel.fit(img, 640) : img;
        ImageIO.write(out, "png", dir.resolve("pack.png").toFile());
        // the README of the repository shows the picture under its title
        Path readme = dir.resolve("README.md");
        if (Files.isRegularFile(readme)) {
            String t = Files.readString(readme, StandardCharsets.UTF_8);
            if (!t.contains("](pack.png)")) {
                int nl = t.indexOf('\n');
                String line = "\n![" + name + "](pack.png)\n";
                Files.writeString(readme, nl < 0 ? t + line : t.substring(0, nl + 1) + line + t.substring(nl + 1), StandardCharsets.UTF_8);
            }
        }
    }

    /** "owner/repo" from a GitHub address (https, ssh or owner/repo), or null. */
    static String repoOf(String s) {
        if (s == null) return null;
        String t = s.trim().replaceAll("\\.git$", "").replaceAll("/+$", "");
        var m = java.util.regex.Pattern.compile("(?:github\\.com[/:])?([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+)$").matcher(t);
        return m.find() ? m.group(1) + "/" + m.group(2) : null;
    }

    Map<String, String> summary() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("description", description);
        m.put("author", author);
        m.put("version", version);
        m.put("github", github);
        m.put("license", license);
        m.put("folder", dir.toString());
        m.put("picture", picture() == null ? "" : picture().toString());
        return m;
    }

    /** Every project Studio knows: the game's shader packs with a Mosaic graph, recent files' packs, ~/.bedray/projects. */
    static List<ProjectInfo> known(Path gameDir) {
        List<Path> dirs = new ArrayList<>();
        java.util.function.Consumer<Path> scan = root -> {
            if (root == null || !Files.isDirectory(root)) return;
            try (var s = Files.list(root)) {
                s.filter(ProjectInfo::isProject).forEach(dirs::add);
            } catch (Exception ignored) {
                // unreadable folder
            }
        };
        if (gameDir != null) scan.accept(gameDir.resolve("shaderpacks"));
        scan.accept(projectsHome());
        if (StudioUi.settings != null)
            for (String r : StudioUi.settings.recentFiles) {
                Path pack = packOf(Path.of(r));
                if (pack != null && isProject(pack)) dirs.add(pack);
            }
        List<ProjectInfo> out = new ArrayList<>();
        java.util.Set<Path> seen = new java.util.HashSet<>();
        for (Path d : dirs) if (seen.add(d.toAbsolutePath().normalize())) out.add(load(d));
        out.sort((a, b) -> Long.compare(mtime(graphOf(b.dir)), mtime(graphOf(a.dir))));
        return out;
    }

    static long mtime(Path p) {
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (Exception e) {
            return 0;
        }
    }

    /** Where new projects go when there is no game folder. */
    static Path projectsHome() {
        String custom = StudioUi.settings == null ? "" : StudioUi.settings.projectsDir;
        if (custom != null && !custom.isBlank()) return Path.of(custom);
        return Path.of(System.getProperty("user.home"), ".bedray", "projects");
    }

    /** A new project folder from a template graph; returns its card (saved). */
    static ProjectInfo create(Path dir, String name, String description, String templateJson) throws java.io.IOException {
        if (Files.exists(graphOf(dir))) throw new java.io.IOException("there is already a project in " + dir);
        Files.createDirectories(graphOf(dir).getParent());
        Files.writeString(graphOf(dir), templateJson, StandardCharsets.UTF_8);
        ProjectInfo p = new ProjectInfo();
        p.dir = dir;
        p.name = name;
        p.description = description == null ? "" : description;
        p.created = java.time.LocalDate.now().toString();
        p.save();
        return p;
    }

    static JsonObject json(ProjectInfo p) { return JsonParser.parseString(GSON.toJson(p)).getAsJsonObject(); }
}
