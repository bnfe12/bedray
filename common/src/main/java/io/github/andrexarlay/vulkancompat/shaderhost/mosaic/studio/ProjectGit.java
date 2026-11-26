package io.github.andrexarlay.vulkancompat.shaderhost.mosaic.studio;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * A project on GitHub: the files a repository of a shader pack needs (README with the picture and description,
 * .gitignore, a release workflow that zips the pack on a version tag, the licence you choose, the project card), and the
 * git / gh commands to publish it, clone one and refresh its card from GitHub. Nothing here runs on its own: every
 * command is started by the user (Studio's buttons, mosaic project ...), and network use is git's, gh's or one read of
 * the repository's public description.
 */
final class ProjectGit {

    private ProjectGit() {}

    static final String[] LICENSES = {"None (all rights reserved)", "MIT", "Apache-2.0", "MPL-2.0", "GPL-3.0", "LGPL-3.0", "CC-BY-4.0",
        "CC-BY-SA-4.0", "CC-BY-NC-4.0", "CC0-1.0"};

    /** One line for the licence chooser: what each licence lets others do. */
    static String licenseHint(String id) {
        return switch (id == null ? "" : id) {
            case "MIT" -> "Anyone may use, change and share it, also in closed packs; they keep your name in the copy.";
            case "Apache-2.0" -> "Like MIT, plus a patent grant and a note of changes in modified files.";
            case "MPL-2.0" -> "Changed files of yours stay open; they may be mixed into packs under other licences.";
            case "GPL-3.0" -> "Anything built from it must be shared under GPL-3.0 too, with its source.";
            case "LGPL-3.0" -> "Changes to your pack stay open; using it next to other work is free (Bedray's own licence).";
            case "CC-BY-4.0" -> "For art: anyone may share and adapt it, crediting you.";
            case "CC-BY-SA-4.0" -> "For art: share and adapt with credit; adaptations under the same licence.";
            case "CC-BY-NC-4.0" -> "For art: share and adapt with credit, not for money.";
            case "CC0-1.0" -> "Public domain: no conditions at all.";
            default -> "Nobody may copy, change or share it without your permission (GitHub still shows it).";
        };
    }

    /** Writes the repository files into the project (an existing README or LICENSE is kept unless overwrite). */
    static List<Path> prepare(ProjectInfo p, String license, boolean overwrite) throws IOException {
        List<Path> written = new ArrayList<>();
        Path d = p.dir;
        if (license != null && !license.startsWith("None")) p.license = license;
        p.save();
        written.add(d.resolve(ProjectInfo.FILE));
        written.add(write(d.resolve("README.md"), readme(p), overwrite));
        written.add(write(d.resolve(".gitignore"), String.join("\n",
            "# Mosaic Studio: per-machine state, caches and recordings",
            "shaders/bedray/.studio/cache/", "shaders/bedray/.studio/history/", "shaders/bedray/.studio/*.gif",
            "shaders/bedray/.studio/*.mp4", "*.zip", ".DS_Store", "Thumbs.db", ""), overwrite));
        written.add(write(d.resolve(".github/workflows/release.yml"), workflow(), overwrite));
        if (p.license != null && !p.license.isBlank()) {
            written.add(write(d.resolve("LICENSE"), licenseText(p), overwrite));
            // the LGPL is a set of extra permissions on top of the GPL: both texts go with it
            if (p.license.equals("LGPL-3.0")) written.add(write(d.resolve("COPYING"), bundled("GPL-3.0"), overwrite));
        }
        written.removeIf(java.util.Objects::isNull);
        return written;
    }

    private static Path write(Path f, String text, boolean overwrite) throws IOException {
        if (Files.exists(f) && !overwrite) return null;
        Files.createDirectories(f.getParent());
        Files.writeString(f, text, StandardCharsets.UTF_8);
        return f;
    }

    private static final String BT = String.valueOf((char) 96); // a backtick, for Markdown code

    static String readme(ProjectInfo p) {
        String repo = ProjectInfo.repoOf(p.github);
        StringBuilder sb = new StringBuilder("# ").append(p.name).append("\n\n");
        if (Files.isRegularFile(p.dir.resolve("pack.png"))) sb.append("![").append(p.name).append("](pack.png)\n\n");
        sb.append(p.description.isBlank() ? "A shader pack made with Mosaic Studio for Bedray." : p.description).append("\n\n");
        sb.append("## Install\n\n");
        sb.append(repo != null ? "1. Download the zip of the latest release: https://github.com/" + repo + "/releases/latest\n"
            : "1. Download the zip of the latest release of this repository.\n");
        sb.append("2. Put it into ").append(BT).append("<minecraft>/shaderpacks/").append(BT).append(" (or unpack it there).\n");
        sb.append("3. Start Minecraft with Bedray and choose the pack.\n\n");
        sb.append("## Edit\n\nOpen the folder in Mosaic Studio (the graph is ").append(BT).append("shaders/bedray/mosaic.json").append(BT).append(").\n\n");
        sb.append("## Release\n\nPush a tag such as ").append(BT).append("v").append(p.version.isBlank() ? "1.0.0" : p.version).append(BT)
            .append(": the workflow in ").append(BT).append(".github/workflows/release.yml").append(BT)
            .append(" zips the pack and attaches it to a release.\n");
        if (!p.author.isBlank()) sb.append("\nBy ").append(p.author).append(".\n");
        if (p.license != null && !p.license.isBlank()) sb.append("\nLicence: ").append(spdx(p.license)).append(" (see LICENSE).\n");
        sb.append("\nNot an official Minecraft product. Not approved by or associated with Mojang or Microsoft.\n");
        return sb.toString();
    }

    static String workflow() {
        String d = "$";
        return String.join("\n",
            "name: Release",
            "on:",
            "  push:",
            "    tags: [\"v*\"]",
            "  workflow_dispatch:",
            "permissions:",
            "  contents: write",
            "jobs:",
            "  release:",
            "    runs-on: ubuntu-latest",
            "    steps:",
            "      - uses: actions/checkout@v4",
            "      - name: Zip the shader pack",
            "        run: |",
            "          name=\"" + d + "(basename \"" + d + "GITHUB_REPOSITORY\")\"",
            "          zip -r \"" + d + "name.zip\" shaders mosaic-project.json " + d + "(ls pack.png README.md LICENSE 2>/dev/null) -x \"shaders/bedray/.studio/*\"",
            "          echo \"ZIP=" + d + "name.zip\" >> \"" + d + "GITHUB_ENV\"",
            "      - name: Release",
            "        env:",
            "          GH_TOKEN: " + d + "{{ github.token }}",
            "        run: |",
            "          tag=\"" + d + "{GITHUB_REF_NAME}\"",
            "          if [ \"" + d + "GITHUB_REF_TYPE\" != \"tag\" ]; then tag=\"build-" + d + "GITHUB_RUN_NUMBER\"; fi",
            "          gh release create \"" + d + "tag\" \"" + d + "ZIP\" --title \"" + d + "tag\" --generate-notes",
            "");
    }

    static String licenseText(ProjectInfo p) {
        String who = p.author.isBlank() ? "the authors" : p.author, year = String.valueOf(java.time.Year.now().getValue());
        if (p.license.equals("MIT")) return "MIT License\n\nCopyright (c) " + year + " " + who + "\n\nPermission is hereby granted, free of charge,"
            + " to any person obtaining a copy of this software and associated documentation files (the \"Software\"), to deal in the"
            + " Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute,"
            + " sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject"
            + " to the following conditions:\n\nThe above copyright notice and this permission notice shall be included in all copies or"
            + " substantial portions of the Software.\n\nTHE SOFTWARE IS PROVIDED \"AS IS\", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR"
            + " IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND"
            + " NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,"
            + " WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR"
            + " OTHER DEALINGS IN THE SOFTWARE.\n";
        String full = bundled(p.license);
        String head = "Copyright (c) " + year + " " + who + "\nSPDX-License-Identifier: " + spdx(p.license) + "\n\n";
        if (full != null) return head + full;
        return head + "Licensed under " + p.license + ". The full text: https://spdx.org/licenses/" + p.license + ".html\n";
    }

    /** The SPDX id written into the files: the GNU licences as "-or-later", like Bedray's own. */
    static String spdx(String id) {
        return id.equals("GPL-3.0") || id.equals("LGPL-3.0") ? id + "-or-later" : id;
    }

    /** The full licence text shipped with Bedray (assets/bedray/licenses), or null. */
    static String bundled(String id) {
        try (var in = ProjectGit.class.getResourceAsStream("/assets/bedray/licenses/" + id + ".txt")) {
            return in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    /** Adds files to the project's .gitignore (paths relative to the project), once each. */
    static void ignore(Path dir, List<String> rels) throws IOException {
        Path gi = dir.resolve(".gitignore");
        String cur = Files.exists(gi) ? Files.readString(gi, StandardCharsets.UTF_8) : "";
        StringBuilder sb = new StringBuilder(cur);
        if (!cur.isEmpty() && !cur.endsWith("\n")) sb.append('\n');
        boolean header = false;
        for (String r : rels) {
            String path = "/" + r.replaceAll("  \\(.*\\)$", "");
            if (cur.lines().anyMatch(l -> l.trim().equals(path))) continue;
            if (!header) sb.append("# Minecraft's own files: not ours to share\n");
            header = true;
            sb.append(path).append('\n');
        }
        Files.writeString(gi, sb.toString(), StandardCharsets.UTF_8);
    }

    /** Files of foreignAssets the .gitignore does not keep out yet. */
    static List<String> unignoredForeign(Path dir) {
        List<String> all = foreignAssets(dir);
        Path gi = dir.resolve(".gitignore");
        if (!Files.exists(gi)) return all;
        try {
            java.util.Set<String> lines = new java.util.HashSet<>(Files.readAllLines(gi, StandardCharsets.UTF_8));
            all.removeIf(r -> lines.contains("/" + r.replaceAll("  \\(.*\\)$", "")));
        } catch (IOException ignored) {
            // unreadable: list them all
        }
        return all;
    }

    // ------------------------------------------------------------------ assets that are not yours to publish

    /**
     * Files of a project that are Minecraft's own (byte for byte a file of the client jar, or under assets/minecraft/):
     * Mojang's licence does not allow sharing them, so they should stay out of a public repository and a release.
     */
    static List<String> foreignAssets(Path dir) {
        List<String> out = new ArrayList<>();
        java.util.Set<String> vanilla = vanillaHashes();
        try (var files = Files.walk(dir)) {
            for (Path f : (Iterable<Path>) files::iterator) {
                if (!Files.isRegularFile(f)) continue;
                String rel = dir.relativize(f).toString().replace('\\', '/');
                if (rel.startsWith(".git/") || rel.contains("/.studio/")) continue;
                String name = rel.toLowerCase(java.util.Locale.ROOT);
                if (!(name.endsWith(".png") || name.endsWith(".ogg") || name.endsWith(".json") || name.endsWith(".mcmeta"))) continue;
                boolean copy = Files.size(f) < 8 << 20 && vanilla.contains(sha(Files.readAllBytes(f)));
                if (copy) out.add(rel + "  (the same file as Minecraft's)");
                else if (name.contains("assets/minecraft/") && name.endsWith(".png")) out.add(rel + "  (replaces a Minecraft texture: fine if you drew it)");
            }
        } catch (IOException e) {
            // an unreadable folder: nothing listed
        }
        return out;
    }

    private static java.util.Set<String> vanillaHashes;

    private static synchronized java.util.Set<String> vanillaHashes() {
        if (vanillaHashes != null) return vanillaHashes;
        java.util.Set<String> out = new java.util.HashSet<>();
        try {
            if (StudioUi.settings == null) StudioUi.settings = StudioSettings.load(); // the command line: no studio around
            for (StudioLibrary.Source v : StudioLibrary.vanillaJars()) {
                try (var zip = new java.util.zip.ZipFile(v.path().toFile())) {
                    var en = zip.entries();
                    while (en.hasMoreElements()) {
                        var e = en.nextElement();
                        String n = e.getName();
                        if (!n.startsWith("assets/") || e.isDirectory() || !(n.endsWith(".png") || n.endsWith(".ogg"))) continue;
                        try (var in = zip.getInputStream(e)) { out.add(sha(in.readAllBytes())); }
                    }
                }
                break; // the newest client is enough
            }
        } catch (Exception e) {
            // no client jar: only the path rule applies
        }
        vanillaHashes = out;
        return out;
    }

    private static String sha(byte[] b) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(b));
        } catch (java.security.NoSuchAlgorithmException e) {
            return "";
        }
    }

    // ------------------------------------------------------------------ commands (started by the user)

    /** Runs a command in a folder, streaming its output; returns the exit code (-1 when it cannot start). */
    static int run(Path dir, Consumer<String> out, String... cmd) {
        try {
            out.accept("> " + String.join(" ", cmd));
            Process p = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true).start();
            try (var r = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                for (String line; (line = r.readLine()) != null; ) out.accept(line);
            }
            return p.waitFor();
        } catch (Exception e) {
            out.accept("cannot run " + cmd[0] + ": " + e.getMessage());
            return -1;
        }
    }

    static boolean has(String tool) { return StudioModel.which(tool).isPresent(); }

    /** git init / add / commit, then gh repo create --source --push: a new repository under your GitHub account. */
    static boolean publish(ProjectInfo p, boolean isPublic, Consumer<String> out) throws IOException {
        if (!has("git")) {
            out.accept("git is not installed: https://git-scm.com");
            return false;
        }
        if (!has("gh")) {
            out.accept("GitHub CLI (gh) is not installed: https://cli.github.com - then run gh auth login once");
            return false;
        }
        Path d = p.dir;
        if (!Files.isDirectory(d.resolve(".git")) && run(d, out, "git", "init", "-b", "main") != 0) return false;
        run(d, out, "git", "add", "-A");
        run(d, out, "git", "commit", "-m", "Mosaic project " + p.name);
        String repoName = p.name.trim().replaceAll("[^A-Za-z0-9_.-]+", "-");
        List<String> cmd = new ArrayList<>(List.of("gh", "repo", "create", repoName, isPublic ? "--public" : "--private", "--source", ".", "--push"));
        if (!p.description.isBlank()) cmd.addAll(List.of("--description", p.description.length() > 300 ? p.description.substring(0, 300) : p.description));
        if (run(d, out, cmd.toArray(new String[0])) != 0) return false;
        refresh(p, out);
        return true;
    }

    /** Clones a repository into into/<repo> and returns the project (its card refreshed from GitHub). */
    static ProjectInfo cloneInto(String address, Path into, Consumer<String> out) throws IOException {
        String repo = ProjectInfo.repoOf(address);
        if (repo == null) throw new IOException("not a GitHub address: " + address);
        Path dir = into.resolve(repo.substring(repo.indexOf('/') + 1));
        Files.createDirectories(into);
        int code = has("gh") ? run(into, out, "gh", "repo", "clone", repo, dir.toString())
            : run(into, out, "git", "clone", "https://github.com/" + repo + ".git", dir.toString());
        if (code != 0) throw new IOException("clone failed (see the log)");
        if (!ProjectInfo.isProject(dir)) out.accept("note: the repository has no shaders/bedray/mosaic.json: it is not a Mosaic project");
        ProjectInfo p = ProjectInfo.load(dir);
        if (p.github.isBlank()) p.github = "https://github.com/" + repo;
        p.save();
        refresh(p, out);
        return ProjectInfo.load(dir);
    }

    /** git pull, then the description, topics, owner and address from GitHub into the card (gh, else the public API). */
    static void refresh(ProjectInfo p, Consumer<String> out) throws IOException {
        Path d = p.dir;
        if (Files.isDirectory(d.resolve(".git")) && has("git")) {
            run(d, out, "git", "pull", "--ff-only");
            if (p.github.isBlank()) {
                StringBuilder url = new StringBuilder();
                run(d, s -> { if (!s.startsWith("> ")) url.append(s.trim()); }, "git", "remote", "get-url", "origin");
                if (ProjectInfo.repoOf(url.toString()) != null) p.github = "https://github.com/" + ProjectInfo.repoOf(url.toString());
            }
        }
        String repo = ProjectInfo.repoOf(p.github);
        if (repo == null) {
            p.save();
            return;
        }
        JsonObject info = null;
        if (has("gh")) {
            StringBuilder sb = new StringBuilder();
            if (run(d, s -> { if (!s.startsWith("> ")) sb.append(s); }, "gh", "repo", "view", repo, "--json", "description,repositoryTopics,url,owner") == 0) {
                try {
                    info = JsonParser.parseString(sb.toString()).getAsJsonObject();
                } catch (Exception ignored) {
                    // not JSON: try the public API
                }
            }
        }
        if (info == null) {
            try {
                var client = java.net.http.HttpClient.newHttpClient();
                var req = java.net.http.HttpRequest.newBuilder(java.net.URI.create("https://api.github.com/repos/" + repo))
                    .header("Accept", "application/vnd.github+json").header("User-Agent", "mosaic-studio").build();
                var res = client.send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
                if (res.statusCode() == 200) info = JsonParser.parseString(res.body()).getAsJsonObject();
                else out.accept("GitHub answered " + res.statusCode() + " for " + repo);
            } catch (Exception e) {
                out.accept("could not reach GitHub: " + e.getMessage());
            }
        }
        if (info != null) {
            if (info.has("description") && !info.get("description").isJsonNull() && !info.get("description").getAsString().isBlank())
                p.description = info.get("description").getAsString();
            List<String> tags = new ArrayList<>();
            if (info.has("repositoryTopics") && info.get("repositoryTopics").isJsonArray())
                for (var t : info.getAsJsonArray("repositoryTopics")) tags.add(t.getAsJsonObject().get("name").getAsString());
            if (info.has("topics") && info.get("topics").isJsonArray()) for (var t : info.getAsJsonArray("topics")) tags.add(t.getAsString());
            if (!tags.isEmpty()) p.tags = tags;
            if (p.author.isBlank() && info.has("owner") && info.get("owner").isJsonObject() && info.getAsJsonObject("owner").has("login"))
                p.author = info.getAsJsonObject("owner").get("login").getAsString();
            out.accept("card updated from GitHub: " + repo);
        }
        p.save();
    }
}
