package io.github.andrexarlay.vulkancompat.shaderhost.mosaic.studio;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicSlang;
import io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicSlangGraph;
import io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicSlangLib;

import javax.swing.SwingWorker;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Applies edited pack functions to the game: the modules with the graphs' bodies go to {@code bedray-studio/slang},
 * every pass that imports one of them (directly or through others) is rebuilt with Bedray's own slangc flags, checked
 * against the pass's resources, and written as {@code bedray-studio/passes/<name>.spv} plus its manifest. Bedray loads
 * a pass from there before its built-in one (SlangPassLibrary), on the next renderer start.
 */
final class StudioPack {

    private StudioPack() {}

    record Built(List<String> modules, List<String> passes, List<String> problems, Path out) {}

    static void apply(StudioModel m) {
        m.say("Applying your Bedray functions: rebuilding the passes that use them...", StudioUi.MUTED);
        new SwingWorker<Built, Void>() {
            @Override protected Built doInBackground() { return build(m); }

            @Override protected void done() {
                try {
                    Built b = get();
                    if (b.modules().isEmpty()) m.say("Nothing to apply: no function of the pack was edited", StudioUi.MUTED);
                    else if (!b.problems().isEmpty()) m.say("Applied " + b.passes().size() + " pass(es); " + b.problems().get(0)
                        + (b.problems().size() > 1 ? " (+" + (b.problems().size() - 1) + " more)" : ""), StudioUi.BAD);
                    else m.say("Applied to Bedray: " + b.modules().size() + " module(s), " + b.passes().size() + " pass(es) rebuilt in "
                        + b.out() + ". Bedray uses them from its next renderer start.", StudioUi.OK);
                } catch (Exception e) {
                    m.say("Apply failed: " + e.getMessage(), StudioUi.BAD);
                }
            }
        }.execute();
    }

    /** Where the game reads Studio's passes: the game's bedray-studio folder (else ~/.bedray/studio/game). */
    static Path outDir(StudioModel m) {
        Path g = StudioLibrary.gameDir(m);
        return g != null ? g.resolve("bedray-studio") : StudioSettings.file().resolveSibling("studio").resolve("game");
    }

    /** Removes Studio's passes and modules from the game: Bedray runs its own again. */
    static void clear(StudioModel m) {
        Path out = outDir(m);
        for (String sub : List.of("passes", "slang")) MosaicSlangGraphAccess.deleteTree(out.resolve(sub));
        m.say("Bedray runs its own passes again (Studio's were removed from " + out + ")", StudioUi.OK);
    }

    static Built build(StudioModel m) {
        MosaicSlangLib.Library lib = m.bedrayLibrary();
        List<String> problems = new ArrayList<>();
        Path out = outDir(m);
        if (lib == null) return new Built(List.of(), List.of(), List.of("Bedray's pack was not found"), out);
        // the modules with an edited function
        Set<String> modules = new TreeSet<>();
        try (var files = Files.list(StudioModel.functionsDir())) {
            for (Path f : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".mosaic.json"))::iterator) {
                try {
                    JsonObject o = JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)).getAsJsonObject();
                    if (!o.has("function")) continue;
                    MosaicSlangLib.Function fn = lib.functions().get(o.get("function").getAsString());
                    if (fn != null) modules.add(fn.module());
                } catch (Exception ignored) {
                    // a broken graph file is not applied
                }
            }
        } catch (Exception ignored) {
            // no edited functions yet
        }
        if (modules.isEmpty()) return new Built(List.of(), List.of(), List.of(), out);
        Map<String, String> replaced = new LinkedHashMap<>();
        for (String mod : modules) {
            String src = m.overriddenModule(mod);
            if (src != null) replaced.put(mod, src);
        }
        // passes whose imports reach a replaced module
        Map<String, Set<String>> imports = new LinkedHashMap<>();
        Pattern imp = Pattern.compile("(?m)^\\s*(?:__exported\\s+)?import\\s+\"([^\"]+)\"");
        for (Map.Entry<String, String> e : lib.sources().entrySet()) {
            Set<String> s = new LinkedHashSet<>();
            Matcher mm = imp.matcher(e.getValue());
            while (mm.find()) s.add(MosaicSlangLib.moduleId(mm.group(1)));
            // "import post_cinema;" / "import a.b;": a module by name (dots are folders)
            Matcher named = Pattern.compile("(?m)^\\s*(?:__exported\\s+)?import\\s+([A-Za-z_][\\w.]*)\\s*;").matcher(e.getValue());
            while (named.find()) s.add(named.group(1).replace('.', '/'));
            Matcher inc = Pattern.compile("(?m)^\\s*#include\\s+\"([^\"]+\\.slang)\"").matcher(e.getValue());
            while (inc.find()) s.add(MosaicSlangLib.moduleId(resolve(e.getKey(), inc.group(1))));
            imports.put(MosaicSlangLib.moduleId(e.getKey()), s);
        }
        List<String> passes = new ArrayList<>();
        for (String mod : imports.keySet())
            if (mod.startsWith("passes/") && reaches(mod, replaced.keySet(), imports, new LinkedHashSet<>())) passes.add(mod);
        Path slangc = MosaicSlang.locate(StudioUi.settings.slangcPath);
        if (slangc == null) return new Built(List.copyOf(modules), List.of(), List.of("slangc not found (Settings > Compiler)"), out);
        Path root = MosaicSlangGraph.packRoot();
        Path tmp = null;
        List<String> built = new ArrayList<>();
        try {
            Files.createDirectories(out.resolve("slang"));
            Files.createDirectories(out.resolve("passes"));
            for (Map.Entry<String, String> e : replaced.entrySet()) {
                Path f = out.resolve("slang").resolve(e.getKey() + ".slang");
                Files.createDirectories(f.getParent());
                Files.writeString(f, e.getValue(), StandardCharsets.UTF_8);
            }
            tmp = Files.createTempDirectory("mosaic-apply");
            Path parent = root.toAbsolutePath().getParent();
            try (var w = Files.walk(parent)) {
                for (Path p : (Iterable<Path>) w::iterator) {
                    Path t = tmp.resolve(parent.relativize(p).toString());
                    if (Files.isDirectory(p)) Files.createDirectories(t);
                    else Files.copy(p, t);
                }
            }
            Path copy = tmp.resolve(root.getFileName().toString());
            for (Map.Entry<String, String> e : replaced.entrySet())
                Files.writeString(copy.resolve(e.getKey() + ".slang"), e.getValue(), StandardCharsets.UTF_8);
            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            for (String pass : passes) {
                String name = pass.substring("passes/".length()).replace('/', '.');
                JsonObject manifest = manifest(m, name);
                if (manifest == null) {
                    problems.add(name + ": Bedray's manifest of this pass was not found (build the mod or pick the instance)");
                    continue;
                }
                Path spv = tmp.resolve(name + ".spv"), refl = tmp.resolve(name + ".reflection.json");
                boolean ser = pass.endsWith(".ser"), raygen = ser || pass.endsWith(".raygen");
                List<String> cmd = new ArrayList<>(List.of(slangc.toString(), "-target", "spirv", "-profile", raygen ? "lib_6_5" : "cs_6_5",
                    "-entry", "main"));
                if (raygen) cmd.addAll(List.of("-stage", "raygeneration"));
                cmd.addAll(List.of("-matrix-layout-column-major", "-I", copy.toString()));
                if (raygen) cmd.add("-emit-spirv-directly");
                if (ser) cmd.addAll(List.of("-capability", "spvShaderInvocationReorderNV"));
                cmd.addAll(List.of("-o", spv.toString(), "-reflection-json", refl.toString(), copy.resolve(pass + ".slang").toString()));
                Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
                String log = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                if (!p.waitFor(300, TimeUnit.SECONDS) || p.exitValue() != 0 || !Files.isRegularFile(spv)) {
                    problems.add(name + ": " + log.lines().filter(l -> l.contains("error")).findFirst().orElse("slangc failed"));
                    continue;
                }
                // the pass must not reach for resources Bedray does not bind to it
                Set<String> have = new TreeSet<>();
                for (JsonElement b : manifest.getAsJsonArray("bindings")) have.add(b.getAsJsonObject().get("name").getAsString());
                Set<String> used = usedBindings(refl);
                // push constants are not descriptor bindings: the manifest sizes them apart
                if (r(refl).has("parameters")) for (JsonElement param : r(refl).getAsJsonArray("parameters")) {
                    JsonObject o = param.getAsJsonObject();
                    if (o.has("binding") && o.getAsJsonObject("binding").has("kind")
                        && o.getAsJsonObject("binding").get("kind").getAsString().equals("pushConstantBuffer")) used.remove(o.get("name").getAsString());
                }
                used.removeAll(have);
                if (!used.isEmpty()) {
                    problems.add(name + ": uses resources this pass does not have (" + String.join(", ", used) + ")");
                    continue;
                }
                byte[] words = Files.readAllBytes(spv);
                manifest.addProperty("spirvWordCount", words.length / 4);
                manifest.addProperty("sourceSha256", sha256(Files.readString(copy.resolve(pass + ".slang"))));
                manifest.addProperty("studio", "Mosaic Studio: " + String.join(", ", replaced.keySet()));
                Files.write(out.resolve("passes").resolve(name + ".spv"), words);
                Files.writeString(out.resolve("passes").resolve(name + ".json"), gson.toJson(manifest), StandardCharsets.UTF_8);
                built.add(name);
            }
            JsonObject index = new JsonObject();
            JsonArray ps = new JsonArray();
            built.forEach(ps::add);
            index.add("passes", ps);
            JsonArray ms = new JsonArray();
            replaced.keySet().forEach(ms::add);
            index.add("modules", ms);
            index.addProperty("built", java.time.Instant.now().toString());
            JsonArray pr = new JsonArray();
            problems.forEach(pr::add);
            if (!problems.isEmpty()) index.add("problems", pr);
            JsonArray considered = new JsonArray();
            passes.forEach(considered::add);
            index.add("considered", considered);
            Files.writeString(out.resolve("passes").resolve("index.json"), gson.toJson(index), StandardCharsets.UTF_8);
        } catch (Exception e) {
            problems.add("apply: " + e.getMessage());
        } finally {
            MosaicSlangGraphAccess.deleteTree(tmp);
        }
        return new Built(List.copyOf(replaced.keySet()), built, problems, out);
    }

    static JsonObject r(Path reflection) {
        try {
            return JsonParser.parseString(Files.readString(reflection)).getAsJsonObject();
        } catch (Exception e) {
            return new JsonObject();
        }
    }

    static String resolve(String from, String rel) {
        Path base = Path.of(from).getParent();
        return (base == null ? Path.of(rel) : base.resolve(rel)).normalize().toString().replace('\\', '/');
    }

    static boolean reaches(String mod, Set<String> targets, Map<String, Set<String>> imports, Set<String> seen) {
        if (!seen.add(mod)) return false;
        for (String i : imports.getOrDefault(mod, Set.of())) if (targets.contains(i) || reaches(i, targets, imports, seen)) return true;
        return false;
    }

    static Set<String> usedBindings(Path reflection) {
        Set<String> out = new TreeSet<>();
        try {
            JsonObject r = JsonParser.parseString(Files.readString(reflection)).getAsJsonObject();
            if (!r.has("entryPoints")) return out;
            for (JsonElement ep : r.getAsJsonArray("entryPoints")) {
                JsonObject o = ep.getAsJsonObject();
                if (!o.has("bindings")) continue;
                for (JsonElement b : o.getAsJsonArray("bindings")) out.add(b.getAsJsonObject().get("name").getAsString());
            }
        } catch (Exception ignored) {
            // no reflection: nothing to compare
        }
        return out;
    }

    /** Bedray's manifest of a pass: from a checkout's build, else from the Bedray jar of the instance. */
    static JsonObject manifest(StudioModel m, String name) {
        for (Path p = Path.of(System.getProperty("user.dir")).toAbsolutePath(); p != null; p = p.getParent()) {
            Path f = p.resolve("common/build/resources/main/shaders/slang/manifest").resolve(name + ".json");
            if (Files.isRegularFile(f)) {
                try {
                    return JsonParser.parseString(Files.readString(f)).getAsJsonObject();
                } catch (Exception ignored) {
                    return null;
                }
            }
        }
        Path g = StudioLibrary.gameDir(m);
        if (g == null || !Files.isDirectory(g.resolve("mods"))) return null;
        try (var s = Files.list(g.resolve("mods"))) {
            for (Path jar : (Iterable<Path>) s.filter(x -> x.getFileName().toString().toLowerCase(Locale.ROOT).startsWith("bedray")
                && x.toString().endsWith(".jar"))::iterator)
                try (ZipFile z = new ZipFile(jar.toFile())) {
                    ZipEntry e = z.getEntry("shaders/slang/manifest/" + name + ".json");
                    if (e != null) return JsonParser.parseString(new String(z.getInputStream(e).readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
                }
        } catch (Exception ignored) {
            // unreadable jar
        }
        return null;
    }

    static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return "";
        }
    }

    /** Package access to the temp-folder cleanup of MosaicSlangGraph. */
    static final class MosaicSlangGraphAccess {
        static void deleteTree(Path dir) {
            if (dir == null || !Files.exists(dir)) return;
            try (var w = Files.walk(dir)) {
                w.sorted(java.util.Comparator.reverseOrder()).forEach(f -> f.toFile().delete());
            } catch (Exception ignored) {
                // best effort
            }
        }
    }
}
