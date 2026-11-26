package io.github.andrexarlay.vulkancompat.shaderhost.mosaic.studio;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicArrange;
import io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicCatalog;
import io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicCodegen;
import io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicDocument;
import io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicEffects;
import io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicFunction;
import io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicGraph;
import io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicMaterial;
import io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicShaderCheck;
import io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicSlang;
import io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicTemplates;

import javax.imageio.ImageIO;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * {@code mosaic} - Mosaic Studio from the command line, for people and AI agents. Every command works on files (a shader
 * graph, a material, the studio settings); a running studio picks the change up as an undoable "External edit". Access is
 * governed by Settings > Agent: nothing runs while agent access is off, and each kind of change has its own switch.
 * Every call is appended to ~/.bedray/agent-log.txt. {@code --json} prints machine-readable results.
 */
public final class AgentCli {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    /** Permission kinds an agent can be given. */
    enum Need { READ, GRAPHS, MATERIALS, DESIGN, COMPILE }

    private final PrintStream out;
    private final boolean json;
    private final StudioSettings settings;

    private AgentCli(PrintStream out, boolean json, StudioSettings settings) {
        this.out = out;
        this.json = json;
        this.settings = settings;
    }

    public static void main(String[] args) {
        System.exit(run(args, System.out));
    }

    /** Runs one command; returns the exit code (0 ok, 1 refused or failed, 2 usage). */
    static int run(String[] args, PrintStream out) {
        List<String> a = new ArrayList<>(Arrays.asList(args));
        boolean json = a.remove("--json");
        AgentCli cli = new AgentCli(out, json, StudioSettings.load());
        if (a.isEmpty() || a.get(0).equals("help") || a.get(0).equals("--help")) {
            out.println(HELP);
            return a.isEmpty() ? 2 : 0;
        }
        try {
            int code = cli.dispatch(a);
            log(String.join(" ", args) + "  -> " + (code == 0 ? "ok" : "exit " + code));
            return code;
        } catch (Refused r) {
            cli.fail(r.getMessage());
            log(String.join(" ", args) + "  -> refused: " + r.getMessage());
            return 1;
        } catch (java.nio.file.NoSuchFileException e) {
            cli.fail("no such file: " + e.getFile() + " (mosaic new <file> makes one)");
            log(String.join(" ", args) + "  -> no such file");
            return 1;
        } catch (Exception e) {
            cli.fail(e.getMessage() == null ? e.toString() : e.getMessage());
            log(String.join(" ", args) + "  -> error: " + e);
            return 1;
        }
    }

    static final String HELP = """
        mosaic - Mosaic Studio from the command line (shader graphs, materials, studio design)

        Reading
          mosaic types [filter]                      node types with their ports
          mosaic info <graph.json>                   nodes, wires, problems, cost
          mosaic code <graph.json> [--slang]         the generated shader
          mosaic compile <graph.json> [--slang]      compile check (errors on nodes)
          mosaic templates                           ready graphs
          mosaic lint <graph.json>                   errors, warnings and hints with the node they sit on
          mosaic find <graph.json> <text>            nodes by id, type, label or buffer name
          mosaic passes <graph.json>                 the pass plan: buffers, scales, what each pass reads, history copies
          mosaic looks [part]                        ready looks of the Minecraft parts (partWater, partLeaves...)
          mosaic look <graph.json> <node> <look>     give a part node one of them
          mosaic graph-settings <graph.json> [key [values|default]] [--preset name] [--reset]
                                                     the graph's settings (sky, rays, world volume...): list, read, set
          mosaic settings                            studio settings (design)
        Graphs and materials (a running studio shows each change as an undoable step)
          mosaic new <graph.json> [template]         a new graph (empty, or from a template)
          mosaic add <graph.json> <type> [id] [x y]  add a node, prints its id
          mosaic connect <graph.json> <from[.output]> <to.port>
          mosaic disconnect <graph.json> <to.port>
          mosaic set <graph.json> <id> <what> <value>
                 what: value (1,0.5,0) | mask | index | label | muted (true/false) | <port> (inline default)
          mosaic delete <graph.json> <id>
          mosaic mute <graph.json> <id> [true|false]
          mosaic arrange <graph.json>
          mosaic bake <material.mosaic.json> <texture.png> <out-folder>   LabPBR _s / _n (+ colour)
        Projects (a shader pack folder with its card: name, description, picture, GitHub)
          mosaic project new <folder> [--name N] [--description D] [--template T] [--github [--license MIT]]
          mosaic project list | info <folder> | card <folder> [--name ..] [--description ..] [--picture file.png] [--version ..] [--github url]
          mosaic project github <folder> [--license L] [--overwrite]   README, release workflow, .gitignore, licence
          mosaic project refresh <folder>             git pull and the description / topics from GitHub
          mosaic project clone <owner/repo> <into>    a Mosaic project from GitHub (publishing: Studio > File > This project)
        Rendering and Bedray hooks (for people and agents)
          mosaic render <graph.json> <out.png> [--time ticks] [--pitch deg] [--yaw deg] [--pos x,y,z] [--size WxH] [--frames N] [--world file|save folder|save name] [--profile]
                                                     the graph over the preview world, headless, to a PNG
          mosaic hook <function|user.stage> --slang <body.slang>   a Bedray function / your stage from Slang text
          mosaic hook <function|user.stage> --reset                back to Bedray's own
                 stages: user.material, user.light, user.skyLight, user.medium (path tracer), user.sky, user.hdr, user.post
        Bedray's pack as nodes
          mosaic pack functions [filter]             every function of the Slang pack (id, signature)
          mosaic pack function <id>                  its body as a graph file (prints the path; edit it with add / connect / set)
          mosaic pack check <id>                     slangc checks the function graph inside its module
          mosaic pack stage <bedray.stage>           a stage's modules and functions
          mosaic pack apply                          edited functions -> bedray-studio/slang + rebuilt passes for the game
          mosaic pack clear                          Bedray runs its own passes again
        Renderer config (public numbers, sent with priority)
          mosaic config                              every value of the renderer graph
          mosaic config set <key> <value>            change one (on the stage that owns it) and send the config
        Design
          mosaic set-setting <key> <value>           theme, accent, fontPercent, wires, nodeWidth, grid, ...

        --json prints results as JSON. Access is set in Mosaic Studio: Settings > Agent.""";

    private static final class Refused extends RuntimeException {
        Refused(String m) { super(m); }
    }

    private void need(Need n) {
        StudioSettings.Agent g = settings.agent;
        if (g == null || !g.enabled) throw new Refused("agent access is off (Mosaic Studio: Settings > Agent)");
        boolean ok = switch (n) {
            case READ -> true;
            case GRAPHS -> g.editGraphs;
            case MATERIALS -> g.editMaterials;
            case DESIGN -> g.editDesign;
            case COMPILE -> g.compile;
        };
        if (!ok) throw new Refused("not allowed: " + n.name().toLowerCase(Locale.ROOT) + " (Mosaic Studio: Settings > Agent)");
    }

    private int dispatch(List<String> a) throws Exception {
        String cmd = a.get(0);
        switch (cmd) {
            case "pack" -> {
                return pack(a);
            }
            case "config" -> {
                return config(a);
            }
            case "render" -> {
                need(Need.COMPILE);
                if (a.size() < 3) return usage();
                return render(Path.of(a.get(1)), Path.of(a.get(2)), a);
            }
            case "hook" -> {
                List<String> p = new ArrayList<>(a);
                p.add(0, "pack");
                return pack(p);
            }
            case "types" -> {
                need(Need.READ);
                String f = a.size() > 1 ? a.get(1).toLowerCase(Locale.ROOT) : "";
                List<Map<String, Object>> rows = new ArrayList<>();
                for (MosaicCatalog.Entry e : MosaicCatalog.all()) {
                    if (!f.isEmpty() && !(e.type() + " " + e.label()).toLowerCase(Locale.ROOT).contains(f)) continue;
                    rows.add(Map.of("type", e.type(), "label", e.label(), "category", e.category().label,
                        "ports", MosaicGraph.portsOf(e.type(), Map.of()).stream().map(p -> p.name() + ":" + p.kind()).toList(), "help", e.help()));
                }
                for (MosaicFunction fn : MosaicEffects.all()) {
                    if (!f.isEmpty() && !(fn.name() + " " + fn.label()).toLowerCase(Locale.ROOT).contains(f)) continue;
                    rows.add(Map.of("type", fn.name(), "label", fn.label(), "category", fn.category(),
                        "ports", fn.params().stream().map(p -> p.name() + ":" + p.kind()).toList(), "help", fn.help() == null ? "" : fn.help()));
                }
                if (json) out.println(GSON.toJson(rows));
                else for (Map<String, Object> r : rows) out.println(r.get("type") + "  (" + r.get("label") + ", " + r.get("category") + ")  " + r.get("ports"));
                return 0;
            }
            case "project" -> {
                return project(a);
            }
            case "looks" -> {
                need(Need.READ);
                String part = a.size() > 1 ? a.get(1) : null;
                for (var l : io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicEffects.PART_LOOKS)
                    if (part == null || l.part().equals(part)) out.println(l.part() + "  " + l.label() + "  " + l.values().keySet());
                return 0;
            }
            case "look" -> {
                need(Need.GRAPHS);
                if (a.size() < 4) return usage();
                MosaicDocument d = MosaicDocument.load(path(a, 1));
                MosaicDocument.ENode n = node(d, a.get(2));
                String fn = MosaicFunction.isFunctionType(n.type) ? MosaicFunction.nameOf(n.type) : "";
                String want = String.join(" ", a.subList(3, a.size()));
                var l = io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicEffects.looksOf(fn).stream()
                    .filter(x -> x.label().equalsIgnoreCase(want)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("no look " + want + " for " + fn + " (see: mosaic looks " + fn + ")"));
                for (String k : List.of("tint", "brightness", "saturation", "contrast")) n.defaults.remove(k);
                l.values().forEach((k, v) -> n.defaults.put(k, v.clone()));
                d.save();
                say(n.id + ": look " + l.label());
                return 0;
            }
            case "graph-settings" -> {
                return graphSettings(a);
            }
            case "passes" -> {
                need(Need.READ);
                MosaicGraph.Analysis an = MosaicDocument.load(path(a, 1)).analyze();
                if (!an.ok()) throw new IllegalStateException("graph has errors: " + an.firstError());
                if (!an.graph().multiPass()) {
                    say("one pass (no Buffer nodes)");
                    return 0;
                }
                var plan = io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicPasses.plan(an.graph());
                Map<Integer, String> names = new java.util.TreeMap<>();
                plan.bindings().forEach((k, v) -> names.put(v, k));
                List<Map<String, Object>> rows = new ArrayList<>();
                for (var p : plan.all())
                    rows.add(Map.of("name", p.name() == null ? "(final image)" : p.name(), "scale", p.scale(), "history", p.history(),
                        "reads", p.reads().stream().sorted().map(b -> names.getOrDefault(b, "#" + b)).toList(),
                        "after", plan.after().contains(p)));
                if (json) out.println(GSON.toJson(Map.of("passes", rows, "bindings", plan.bindings())));
                else for (Map<String, Object> r : rows)
                    out.println(r.get("name") + "  1/" + r.get("scale") + (Boolean.TRUE.equals(r.get("after")) ? "  (after the frame: history)" : "")
                        + (((List<?>) r.get("reads")).isEmpty() ? "" : "  reads " + r.get("reads")));
                return 0;
            }
            case "find" -> {
                need(Need.READ);
                if (a.size() < 3) return usage();
                MosaicDocument d = MosaicDocument.load(path(a, 1));
                String q = String.join(" ", a.subList(2, a.size())).toLowerCase(Locale.ROOT);
                List<Map<String, Object>> rows = new ArrayList<>();
                for (MosaicDocument.ENode n : d.nodes) {
                    String text = (n.id + " " + n.type + " " + (n.label == null ? "" : n.label) + " " + (n.mask == null ? "" : n.mask)).toLowerCase(Locale.ROOT);
                    if (!text.contains(q)) continue;
                    Map<String, Object> r = new java.util.LinkedHashMap<>();
                    r.put("id", n.id);
                    r.put("type", n.type);
                    if (n.label != null) r.put("label", n.label);
                    if (n.mask != null) r.put("mask", n.mask);
                    r.put("inputs", n.inputs);
                    rows.add(r);
                }
                if (json) out.println(GSON.toJson(rows));
                else for (Map<String, Object> r : rows) out.println(r.get("id") + "  " + r.get("type") + (r.containsKey("label") ? "  \"" + r.get("label") + "\"" : "")
                    + (r.containsKey("mask") ? "  [" + r.get("mask") + "]" : "") + "  <- " + r.get("inputs"));
                return rows.isEmpty() ? 1 : 0;
            }
            case "lint" -> {
                need(Need.READ);
                MosaicGraph.Analysis an = MosaicDocument.load(path(a, 1)).analyze();
                if (json) out.println(GSON.toJson(Map.of("ok", an.ok(), "problems", an.problems())));
                else {
                    for (MosaicGraph.Severity sv : MosaicGraph.Severity.values())
                        for (MosaicGraph.Problem p : an.problems())
                            if (p.severity() == sv) out.println(sv + (p.node() == null ? "" : "  [" + p.node() + (p.port() == null ? "" : "." + p.port()) + "]")
                                + "  " + p.message() + (p.fix() == MosaicGraph.Fix.NONE ? "" : "  (fix: " + p.fix().name().toLowerCase(Locale.ROOT) + ")"));
                    out.println(an.problems().isEmpty() ? "clean" : an.ok() ? "valid, " + an.problems().size() + " note(s)" : "has errors");
                }
                return an.ok() ? 0 : 1;
            }
            case "templates" -> {
                need(Need.READ);
                for (MosaicTemplates.Template t : MosaicTemplates.all()) out.println(t.name() + "  -  " + t.description());
                return 0;
            }
            case "info" -> {
                need(Need.READ);
                MosaicDocument d = MosaicDocument.load(path(a, 1));
                MosaicGraph.Analysis an = d.analyze();
                if (json) {
                    JsonObject o = new JsonObject();
                    o.add("graph", JsonParser.parseString(d.toJson()));
                    o.add("problems", GSON.toJsonTree(an.problems()));
                    o.addProperty("ok", an.ok());
                    out.println(GSON.toJson(o));
                } else {
                    for (MosaicDocument.ENode n : d.nodes)
                        out.println(n.id + "  " + n.type + (n.muted ? "  [muted]" : "") + (n.inputs.isEmpty() ? "" : "  <- " + n.inputs)
                            + (n.value == null ? "" : "  value " + Arrays.toString(n.value)) + (n.mask == null ? "" : "  mask " + n.mask));
                    for (MosaicGraph.Problem p : an.problems()) out.println(p.severity() + "  " + p.message());
                    out.println(an.ok() ? "valid" : an.problems().stream().filter(p -> p.severity() == MosaicGraph.Severity.ERROR).count() + " error(s)");
                }
                return an.ok() ? 0 : 1;
            }
            case "code", "compile" -> {
                need(cmd.equals("compile") ? Need.COMPILE : Need.READ);
                MosaicGraph.Analysis an = MosaicDocument.load(path(a, 1)).analyze();
                if (!an.ok()) throw new IllegalStateException("graph has errors: " + an.firstError());
                boolean slang = a.contains("--slang");
                MosaicCodegen.Generated gen = MosaicCodegen.generate(an.graph(), MosaicCodegen.Options.DEFAULT, slang);
                if (cmd.equals("code")) {
                    out.println(gen.source());
                    return 0;
                }
                MosaicShaderCheck.Result r = slang ? MosaicSlang.compile(gen, MosaicSlang.locate(settings.slangcPath)) : MosaicShaderCheck.compile(gen);
                if (json) out.println(GSON.toJson(Map.of("ok", r.ok(), "spirvBytes", r.spirvBytes(), "errors", r.errors())));
                else if (r.ok()) out.println("compiles: " + r.spirvBytes() + " B SPIR-V");
                else for (MosaicShaderCheck.Error e : r.errors()) out.println("error (" + e.where() + "): " + e.message());
                return r.ok() ? 0 : 1;
            }
            case "settings" -> {
                need(Need.READ);
                JsonObject o = JsonParser.parseString(new Gson().toJson(settings)).getAsJsonObject();
                o.remove("layout");
                o.remove("materialLayout");
                out.println(GSON.toJson(o));
                return 0;
            }
            case "set-setting" -> {
                need(Need.DESIGN);
                if (a.size() < 3) return usage();
                String key = a.get(1), value = a.get(2);
                if (key.equals("agent")) throw new Refused("agent permissions can only be changed in the studio");
                JsonObject o = JsonParser.parseString(new Gson().toJson(settings)).getAsJsonObject();
                if (!o.has(key)) throw new IllegalArgumentException("unknown setting " + key + " (see: mosaic settings)");
                JsonElement old = o.get(key);
                o.add(key, old.isJsonPrimitive() && old.getAsJsonPrimitive().isNumber() ? new com.google.gson.JsonPrimitive(Double.parseDouble(value))
                    : old.isJsonPrimitive() && old.getAsJsonPrimitive().isBoolean() ? new com.google.gson.JsonPrimitive(Boolean.parseBoolean(value))
                    : new com.google.gson.JsonPrimitive(value.toUpperCase(Locale.ROOT).equals(value) || !old.isJsonPrimitive() ? value
                    : (enumLike(old) ? value.toUpperCase(Locale.ROOT) : value)));
                StudioSettings next = new Gson().fromJson(o, StudioSettings.class).sanitized();
                next.save();
                say(key + " = " + value + " (a running studio applies it within a second)");
                return 0;
            }
            case "new" -> {
                need(Need.GRAPHS);
                Path p = path(a, 1);
                String text = "{\"nodes\": []}";
                if (a.size() > 2) {
                    String name = String.join(" ", a.subList(2, a.size()));
                    text = MosaicTemplates.all().stream().filter(t -> t.name().equalsIgnoreCase(name)).findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("no template " + name + " (see: mosaic templates)")).json();
                }
                Files.createDirectories(p.toAbsolutePath().getParent());
                Files.writeString(p, text, StandardCharsets.UTF_8);
                say("created " + p);
                return 0;
            }
            case "bake" -> {
                need(Need.MATERIALS);
                if (a.size() < 4) return usage();
                MosaicGraph.Analysis an = MosaicDocument.load(Path.of(a.get(1))).analyze();
                if (!an.ok() || !an.graph().isMaterial()) throw new IllegalStateException("not a valid material graph: " + an.firstError());
                java.awt.image.BufferedImage img = ImageIO.read(Path.of(a.get(2)).toFile());
                if (img == null) throw new IllegalArgumentException("cannot read " + a.get(2));
                MosaicMaterial.Baked b = MosaicMaterial.bake(an.graph(), StudioLibrary.toMaterial(img), Map.of());
                Path dir = Path.of(a.get(3));
                Files.createDirectories(dir);
                String base = Path.of(a.get(2)).getFileName().toString().replaceAll("(?i)[.]png$", "");
                ImageIO.write(StudioLibrary.fromArgb(b.w(), b.h(), b.specular()), "png", dir.resolve(base + "_s.png").toFile());
                if (b.normal() != null) ImageIO.write(StudioLibrary.fromArgb(b.w(), b.h(), b.normal()), "png", dir.resolve(base + "_n.png").toFile());
                if (b.albedo() != null) ImageIO.write(StudioLibrary.fromArgb(b.w(), b.h(), b.albedo()), "png", dir.resolve(base + ".png").toFile());
                for (String w : b.warnings()) out.println("warning: " + w);
                say("baked " + b.w() + "x" + b.h() + " into " + dir);
                return 0;
            }
            default -> {
                return edit(a);
            }
        }
    }

    /**
     * {@code graph-settings <graph>} lists every graph setting (value, default, range); with a key it prints one; with a key and
     * values it sets it ({@code default} removes it); {@code --preset <name>} applies a preset; {@code --reset} clears all.
     */
    private int graphSettings(List<String> a) throws Exception {
        Path p = path(a, 1);
        MosaicDocument d = MosaicDocument.load(p);
        int pi = a.indexOf("--preset");
        if (pi > 0 || a.contains("--reset")) {
            need(Need.GRAPHS);
            if (a.contains("--reset")) d.settings.clear();
            else {
                String name = String.join(" ", a.subList(pi + 1, a.size()));
                var preset = io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicSettings.PRESETS.stream()
                    .filter(x -> x.name().equalsIgnoreCase(name)).findFirst().or(() -> InspectorPanel.userPresets().stream()
                        .filter(x -> x.name().equalsIgnoreCase(name)).findFirst())
                    .orElseThrow(() -> new IllegalArgumentException("no preset " + name + " (presets: " + io.github.andrexarlay.vulkancompat.shaderhost.mosaic
                        .MosaicSettings.PRESETS.stream().map(x -> x.name()).toList() + " and yours)"));
                preset.values().forEach((k, v) -> d.settings.put(k, v.clone()));
            }
            d.save();
            say(a.contains("--reset") ? "graph settings back to their defaults" : "applied preset " + String.join(" ", a.subList(pi + 1, a.size())));
            return 0;
        }
        if (a.size() <= 2) {
            need(Need.READ);
            List<Map<String, Object>> rows = new ArrayList<>();
            for (var s : io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicSettings.ALL) {
                Map<String, Object> r = new java.util.LinkedHashMap<>();
                r.put("key", s.key());
                r.put("label", s.label());
                r.put("group", s.group());
                r.put("value", io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicSettings.value(d.settings, s));
                r.put("default", s.def());
                double[] range = io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicSettings.range(s.key());
                if (range != null) r.put("range", range);
                r.put("changed", d.settings.containsKey(s.key()));
                r.put("help", s.help());
                rows.add(r);
            }
            if (json) out.println(GSON.toJson(rows));
            else for (Map<String, Object> r : rows)
                out.println((Boolean.TRUE.equals(r.get("changed")) ? "* " : "  ") + r.get("key") + " = " + nums((double[]) r.get("value"))
                    + "   (default " + nums((double[]) r.get("default")) + (r.containsKey("range") ? ", range " + nums((double[]) r.get("range")) : "")
                    + ")   " + r.get("label"));
            return 0;
        }
        var s = io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicSettings.of(a.get(2));
        if (s == null) throw new IllegalArgumentException("unknown graph setting " + a.get(2) + " (see: mosaic graph-settings " + a.get(1) + ")");
        if (a.size() == 3) {
            need(Need.READ);
            out.println(nums(io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicSettings.value(d.settings, s)));
            return 0;
        }
        need(Need.GRAPHS);
        String text = String.join(",", a.subList(3, a.size()));
        if (text.equalsIgnoreCase("default")) d.settings.remove(s.key());
        else {
            double[] v = numbers(text);
            if (v.length != s.size()) throw new IllegalArgumentException(s.key() + " takes " + s.size() + " number(s), got " + v.length);
            if (Arrays.equals(v, s.def())) d.settings.remove(s.key());
            else d.settings.put(s.key(), v);
        }
        d.save();
        for (String m : io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicSettings.check(d.settings)) out.println("warning: " + m);
        say(s.key() + " = " + nums(io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicSettings.value(d.settings, s)));
        return 0;
    }

    /** mosaic project new|info|card|github|publish|refresh|clone ... */
    private int project(List<String> a) throws Exception {
        String sub = a.size() > 1 ? a.get(1) : "";
        switch (sub) {
            case "new" -> {
                need(Need.GRAPHS);
                if (a.size() < 3) return usage();
                Path dir = Path.of(a.get(2));
                String tname = opt(a, "--template", "Minecraft by parts");
                var t = MosaicTemplates.all().stream().filter(x -> x.name().equalsIgnoreCase(tname)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("no template " + tname));
                ProjectInfo p = ProjectInfo.create(dir, opt(a, "--name", dir.getFileName().toString()), opt(a, "--description", ""), t.json());
                p.author = opt(a, "--author", settings.projectAuthor);
                p.save();
                if (a.contains("--github")) ProjectGit.prepare(p, opt(a, "--license", null), false);
                say("created project " + p.name + " in " + dir);
                return 0;
            }
            case "info" -> {
                need(Need.READ);
                ProjectInfo p = ProjectInfo.load(Path.of(a.get(2)));
                if (json) out.println(GSON.toJson(p.summary()));
                else p.summary().forEach((k, v) -> out.println(k + ": " + v));
                return 0;
            }
            case "list" -> {
                need(Need.READ);
                StudioUi.settings = settings;
                Path gd = settings.gameDir == null || settings.gameDir.isBlank() ? null : Path.of(settings.gameDir);
                var ps = ProjectInfo.known(gd);
                if (json) out.println(GSON.toJson(ps.stream().map(ProjectInfo::summary).toList()));
                else for (ProjectInfo p : ps) out.println(p.name + "  -  " + p.dir + (p.description.isBlank() ? "" : "\n    " + p.description));
                return 0;
            }
            case "card" -> {
                need(Need.GRAPHS);
                ProjectInfo p = ProjectInfo.load(Path.of(a.get(2)));
                for (String k : List.of("name", "description", "author", "version", "github", "license")) {
                    String v = opt(a, "--" + k, null);
                    if (v == null) continue;
                    switch (k) {
                        case "name" -> p.name = v;
                        case "description" -> p.description = v;
                        case "author" -> p.author = v;
                        case "version" -> p.version = v;
                        case "github" -> p.github = v;
                        default -> p.license = v;
                    }
                }
                String pic = opt(a, "--picture", null);
                if (pic != null) {
                    java.awt.image.BufferedImage img = ImageIO.read(Path.of(pic).toFile());
                    if (img == null) throw new IllegalArgumentException("cannot read " + pic);
                    p.setPicture(img);
                }
                p.save();
                say("card of " + p.name + " saved");
                return 0;
            }
            case "github" -> {
                need(Need.GRAPHS);
                ProjectInfo p = ProjectInfo.load(Path.of(a.get(2)));
                for (Path w : ProjectGit.prepare(p, opt(a, "--license", null), a.contains("--overwrite"))) out.println("wrote " + w);
                return 0;
            }
            case "refresh" -> {
                need(Need.GRAPHS);
                ProjectGit.refresh(ProjectInfo.load(Path.of(a.get(2))), out::println);
                return 0;
            }
            case "clone" -> {
                need(Need.GRAPHS);
                if (a.size() < 4) return usage();
                ProjectInfo p = ProjectGit.cloneInto(a.get(2), Path.of(a.get(3)), out::println);
                say("cloned " + p.name + " into " + p.dir);
                return 0;
            }
            default -> {
                return usage();
            }
        }
    }

    private static String nums(double[] v) {
        StringBuilder sb = new StringBuilder();
        for (double x : v) {
            if (sb.length() > 0) sb.append(",");
            sb.append(x == Math.rint(x) && Math.abs(x) < 1e9 ? String.valueOf((long) x)
                : new java.math.BigDecimal(String.format(Locale.ROOT, "%.4g", x)).stripTrailingZeros().toPlainString());
        }
        return sb.toString();
    }

    private static boolean enumLike(JsonElement e) {
        String s = e.getAsString();
        return !s.isEmpty() && s.equals(s.toUpperCase(Locale.ROOT)) && s.matches("[A-Z_]+");
    }

    /** Commands that change a graph file: one load, one change, one save, then the analysis. */
    private int edit(List<String> a) throws Exception {
        String cmd = a.get(0);
        if (!List.of("add", "connect", "disconnect", "set", "delete", "mute", "arrange").contains(cmd)) return usage();
        Path p = path(a, 1);
        MosaicDocument d = MosaicDocument.load(p);
        need(d.nodes.stream().anyMatch(n -> n.type.startsWith("mat.")) ? Need.MATERIALS : Need.GRAPHS);
        String result;
        switch (cmd) {
            case "add" -> {
                if (a.size() < 3) return usage();
                String type = a.get(2);
                MosaicFunction fn = MosaicEffects.get(type);
                if (fn != null) {
                    d.ensureFunction(fn);
                    type = fn.type();
                } else if (!MosaicGraph.isKnown(type) && !type.contains(".")) type = "mosaic." + type;
                if (fn == null && !MosaicGraph.isKnown(type)) throw new IllegalArgumentException("unknown node type " + a.get(2) + " (see: mosaic types)");
                int x = a.size() > 5 ? Integer.parseInt(a.get(4)) : d.nodes.stream().mapToInt(n -> n.x).max().orElse(0) + 220;
                int y = a.size() > 5 ? Integer.parseInt(a.get(5)) : 80;
                MosaicDocument.ENode n = d.add(type, x, y);
                if (a.size() > 3 && !a.get(3).isBlank()) {
                    if (d.byId(a.get(3)) != null) throw new IllegalArgumentException("id " + a.get(3) + " is taken");
                    if (!a.get(3).matches("\\w+")) throw new IllegalArgumentException("an id is one word");
                    n.id = a.get(3);
                }
                if (type.equals("mosaic.const")) n.value = new double[] {1.0};
                if (type.equals("mosaic.swizzle")) n.mask = "xyz";
                if (type.equals("mosaic.vec")) n.index = 3;
                result = n.id;
            }
            case "connect" -> {
                if (a.size() < 4) return usage();
                String from = a.get(2), to = a.get(3);
                int dot = to.lastIndexOf('.');
                if (dot < 0) throw new IllegalArgumentException("the target is node.port, e.g. out.color");
                MosaicDocument.ENode target = node(d, to.substring(0, dot));
                node(d, MosaicGraph.split(from)[0]);
                target.inputs.put(to.substring(dot + 1), from);
                result = from + " -> " + to;
            }
            case "disconnect" -> {
                String to = a.get(2);
                int dot = to.lastIndexOf('.');
                node(d, to.substring(0, dot)).inputs.remove(to.substring(dot + 1));
                result = "disconnected " + to;
            }
            case "set" -> {
                if (a.size() < 5) return usage();
                MosaicDocument.ENode n = node(d, a.get(2));
                String what = a.get(3), v = String.join(" ", a.subList(4, a.size()));
                switch (what) {
                    case "value" -> n.value = numbers(v);
                    case "mask" -> n.mask = v;
                    case "index" -> n.index = Integer.parseInt(v);
                    case "label" -> n.label = v.isBlank() ? null : v;
                    case "muted" -> n.muted = Boolean.parseBoolean(v);
                    default -> n.defaults.put(what, numbers(v));
                }
                result = n.id + "." + what + " = " + v;
            }
            case "delete" -> {
                MosaicDocument.ENode n = node(d, a.get(2));
                d.removeAll(List.of(n));
                result = "deleted " + n.id;
            }
            case "mute" -> {
                MosaicDocument.ENode n = node(d, a.get(2));
                n.muted = a.size() < 4 || Boolean.parseBoolean(a.get(3));
                result = (n.muted ? "muted " : "unmuted ") + n.id;
            }
            default -> {
                MosaicArrange.arrange(d.nodes, 170, n -> 60 + 18 * Math.max(1, n.inputs.size()),
                    n -> MosaicGraph.portsOf(n.type, Map.of()).stream().map(MosaicGraph.Port::name).toList());
                result = "arranged " + d.nodes.size() + " nodes";
            }
        }
        d.save();
        MosaicGraph.Analysis an = d.analyze();
        if (json) out.println(GSON.toJson(Map.of("result", result, "ok", an.ok(), "problems", an.problems())));
        else {
            out.println(result);
            for (MosaicGraph.Problem pr : an.problems()) if (pr.severity() != MosaicGraph.Severity.HINT) out.println(pr.severity() + "  " + pr.message());
        }
        return 0;
    }

    // ------------------------------------------------------------------ Bedray's pack as nodes

    private io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicSlangLib.Library library() {
        var lib = io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicSlangGraph.library();
        if (lib != null) return lib;
        Path unpacked = StudioSettings.file().resolveSibling("studio").resolve("pack").resolve("slang");
        if (Files.isDirectory(unpacked)) {
            io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicSlangGraph.install(
                io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicSlangGraph.load(unpacked), unpacked);
            return io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicSlangGraph.library();
        }
        throw new IllegalStateException("Bedray's Slang pack was not found: run from a Bedray checkout or open Studio once with the game instance");
    }

    private int pack(List<String> a) throws Exception {
        String sub = a.size() > 1 ? a.get(1) : "";
        var lib = library();
        switch (sub) {
            case "functions" -> {
                need(Need.READ);
                String f = a.size() > 2 ? a.get(2).toLowerCase(Locale.ROOT) : "";
                List<Map<String, String>> rows = new ArrayList<>();
                for (var fn : lib.functions().values()) {
                    if (!fn.isNode() || !fn.id().toLowerCase(Locale.ROOT).contains(f)) continue;
                    String sig = fn.returnType() + " " + fn.label() + "(" + String.join(", ", fn.params().stream()
                        .map(p -> (p.direction().equals("in") ? "" : p.direction() + " ") + p.type() + " " + p.name()).toList()) + ")";
                    rows.add(Map.of("id", fn.id(), "signature", sig));
                }
                if (json) out.println(GSON.toJson(rows));
                else rows.forEach(r -> out.println(r.get("id") + "    " + r.get("signature")));
                return 0;
            }
            case "function" -> {
                need(Need.GRAPHS);
                if (a.size() < 3) return usage();
                var fn = lib.functions().get(a.get(2));
                if (fn == null) throw new IllegalArgumentException("no function " + a.get(2) + " (mosaic pack functions)");
                Path file = StudioModel.functionFile(fn.id());
                if (!Files.exists(file)) {
                    Files.createDirectories(file.getParent());
                    Files.writeString(file, io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicSlangDecompile.decompile(fn, lib).json(), StandardCharsets.UTF_8);
                }
                say(file.toString());
                return 0;
            }
            case "hook" -> {
                // a Bedray function (or a renderer stage of your own) from Slang text: one Slang block between its
                // parameters and its ends, saved as the function's graph, then checked inside its module
                need(Need.GRAPHS);
                if (a.size() < 3) return usage();
                String id = StudioModel.USER_STAGES.getOrDefault(a.get(1 + 1), a.get(2));
                var fn = lib.functions().get(id);
                if (fn == null) throw new IllegalArgumentException("no function " + id + " (mosaic pack functions; stages: " + StudioModel.USER_STAGES.keySet() + ")");
                Path file = StudioModel.functionFile(fn.id());
                if (a.contains("--reset")) {
                    Files.deleteIfExists(file);
                    out.println("reset " + fn.id() + " to Bedray's own");
                    return 0;
                }
                int si = a.indexOf("--slang");
                if (si < 0 || si + 1 >= a.size()) return usage();
                String body = Files.readString(Path.of(a.get(si + 1)), StandardCharsets.UTF_8);
                Files.createDirectories(file.getParent());
                Files.writeString(file, hookGraph(fn, body), StandardCharsets.UTF_8);
                out.println("wrote " + file);
                return pack(new ArrayList<>(List.of("pack", "check", fn.id())));
            }
            case "check" -> {
                need(Need.COMPILE);
                if (a.size() < 3) return usage();
                var fn = lib.functions().get(a.get(2));
                if (fn == null) throw new IllegalArgumentException("no function " + a.get(2));
                String module = StudioPack.overridden(lib, fn.module(), null, null);
                var c = io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicSlangGraph.checkModules(Map.of(fn.module(), module),
                    MosaicSlang.locate(settings.slangcPath), io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicSlangGraph.packRoot());
                if (json) out.println(GSON.toJson(Map.of("ok", c.ok(), "log", c.log())));
                else out.println(c.ok() ? fn.module() + " compiles with your function graphs" : c.log());
                return c.ok() ? 0 : 1;
            }
            case "stage" -> {
                need(Need.READ);
                if (a.size() < 3) return usage();
                List<String> mods = io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicStageMap.modules(a.get(2), lib);
                Map<String, List<String>> out2 = new java.util.LinkedHashMap<>();
                for (String m : mods) out2.put(m, lib.ofModule(m).stream().map(io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicSlangLib.Function::id).toList());
                if (json) out.println(GSON.toJson(out2));
                else out2.forEach((m, fns) -> out.println(m + ": " + String.join(", ", fns)));
                return 0;
            }
            case "apply" -> {
                need(Need.COMPILE);
                StudioPack.Built b = StudioPack.build(StudioPack.headless(settings));
                if (json) out.println(GSON.toJson(Map.of("modules", b.modules(), "passes", b.passes(), "problems", b.problems(), "out", b.out().toString())));
                else {
                    out.println("modules: " + b.modules());
                    out.println("passes rebuilt: " + b.passes());
                    b.problems().forEach(p -> out.println("problem: " + p));
                    out.println("written to " + b.out());
                }
                return b.problems().isEmpty() ? 0 : 1;
            }
            case "clear" -> {
                need(Need.COMPILE);
                Path out2 = StudioPack.headless(settings).out();
                for (String s : List.of("passes", "slang")) StudioPack.MosaicSlangGraphAccess.deleteTree(out2.resolve(s));
                say("removed Studio's passes from " + out2);
                return 0;
            }
            default -> {
                return usage();
            }
        }
    }

    // ------------------------------------------------------------------ renderer config

    private Path rendererGraph() {
        Path g = settings.gameDir == null || settings.gameDir.isBlank() ? null : Path.of(settings.gameDir);
        return (g != null ? g.resolve("bedray-studio") : StudioSettings.file().resolveSibling("studio")).resolve("renderer.mosaic.json");
    }

    private int config(List<String> a) throws Exception {
        Path f = rendererGraph();
        if (!Files.exists(f)) throw new IllegalStateException("no renderer graph yet: open the Renderer workspace once (" + f + ")");
        MosaicDocument d = MosaicDocument.load(f);
        if (a.size() >= 4 && a.get(1).equals("set")) {
            need(Need.GRAPHS);
            String key = a.get(2), value = a.get(3);
            var s = io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicRenderer.setting(key);
            String stage = io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicRenderer.stageOfSetting(key);
            if (s == null) throw new IllegalArgumentException("no setting " + key + " (mosaic config)");
            MosaicDocument.ENode n = d.nodes.stream().filter(x -> x.type.equals(stage)).findFirst().orElse(null);
            if (n == null) throw new IllegalStateException(key + " belongs to " + stage + ", which is not in the renderer graph");
            n.defaults.put(key, new double[] {s.parse(value)});
            d.save();
        }
        need(Need.READ);
        MosaicGraph g = d.analyze().graph();
        Map<String, String> values = g == null ? Map.of() : io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicRenderer.settings(g);
        if (a.size() >= 4 && a.get(1).equals("set") && settings.gameDir != null && !settings.gameDir.isBlank()) {
            JsonObject o = new JsonObject();
            o.addProperty("apply", System.currentTimeMillis());
            o.addProperty("save", true);
            o.addProperty("priority", true);
            JsonObject set = new JsonObject();
            values.forEach(set::addProperty);
            o.add("settings", set);
            Files.writeString(Path.of(settings.gameDir).resolve("bedray-studio").resolve("renderer.json"), o.toString(), StandardCharsets.UTF_8);
        }
        if (json) out.println(GSON.toJson(values));
        else values.forEach((k, v) -> out.println(k + " = " + v));
        return 0;
    }

    private static MosaicDocument.ENode node(MosaicDocument d, String id) {
        MosaicDocument.ENode n = d.byId(id);
        if (n == null) throw new IllegalArgumentException("no node " + id);
        return n;
    }

    private static double[] numbers(String v) {
        String[] parts = v.replace("[", "").replace("]", "").split("[,\\s]+");
        double[] r = new double[parts.length];
        for (int i = 0; i < parts.length; i++) r[i] = Double.parseDouble(parts[i]);
        return r;
    }

    private static Path path(List<String> a, int i) {
        if (a.size() <= i) throw new IllegalArgumentException("missing file argument (mosaic help)");
        return Path.of(a.get(i));
    }

    private int usage() {
        out.println(HELP);
        return 2;
    }

    private void say(String s) {
        if (json) out.println(GSON.toJson(Map.of("result", s)));
        else out.println(s);
    }

    private void fail(String s) {
        if (json) out.println(GSON.toJson(Map.of("error", s)));
        else out.println("mosaic: " + s);
    }

    static Path logFile() { return StudioSettings.file().resolveSibling("agent-log.txt"); }

    private static void log(String line) {
        try {
            Files.createDirectories(logFile().getParent());
            Files.writeString(logFile(), LocalDateTime.now().withNano(0) + "  " + line + System.lineSeparator(), StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (Exception ignored) {
            // logging is a courtesy
        }
    }

    /** The graph of a function whose body is Slang text: parameters -> one Slang block -> out ends and the return. */
    static String hookGraph(io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicSlangLib.Function fn, String body) {
        com.google.gson.JsonArray nodes = new com.google.gson.JsonArray();
        StringBuilder head = new StringBuilder("//@");
        JsonObject code = new JsonObject();
        JsonObject codeIn = new JsonObject();
        int y = 60;
        for (var p : fn.params()) {
            String dir = p.direction().equals("out") ? "out" : p.direction().equals("inout") ? "inout" : "in";
            if (!dir.equals("out")) {
                JsonObject n = new JsonObject();
                n.addProperty("id", "p_" + p.name());
                n.addProperty("type", "slang.param");
                n.addProperty("mask", p.type() + " " + p.name());
                n.addProperty("x", 40);
                n.addProperty("y", y);
                nodes.add(n);
                codeIn.addProperty(p.name(), "p_" + p.name());
                y += 90;
            }
            head.append(' ').append(dir.equals("out") ? "inout" : dir).append(' ').append(p.type()).append(' ').append(p.name()).append(';');
        }
        String ret = fn.isVoid() ? "void" : fn.returnType();
        head.append(" ret ").append(ret);
        code.addProperty("id", "code");
        code.addProperty("type", "slang.code");
        code.addProperty("mask", head + "\n" + body.strip());
        code.add("inputs", codeIn);
        code.addProperty("x", 320);
        code.addProperty("y", 60);
        nodes.add(code);
        y = 60;
        for (var p : fn.params()) {
            if (p.direction().equals("in")) continue;
            JsonObject o = new JsonObject();
            o.addProperty("id", "o_" + p.name());
            o.addProperty("type", "slang.out");
            o.addProperty("mask", p.type() + " " + p.name());
            JsonObject in = new JsonObject();
            in.addProperty("value", "code." + p.name());
            o.add("inputs", in);
            o.addProperty("x", 640);
            o.addProperty("y", y);
            nodes.add(o);
            y += 90;
        }
        JsonObject r = new JsonObject();
        r.addProperty("id", "result");
        r.addProperty("type", "slang.result");
        r.addProperty("mask", ret);
        if (!fn.isVoid()) {
            JsonObject in = new JsonObject();
            in.addProperty("value", "code");
            r.add("inputs", in);
        }
        r.addProperty("x", 640);
        r.addProperty("y", y);
        nodes.add(r);
        JsonObject root = new JsonObject();
        root.add("nodes", nodes);
        root.addProperty("function", fn.id());
        return GSON.toJson(root);
    }

    /**
     * Renders a graph headlessly over the preview world (the game's capture, or a file given with --world, or the built-in
     * landscape) and writes the last frame as a PNG: --time ticks, --pitch / --yaw degrees, --size WxH, --frames N (for
     * Accumulate and TAA to settle). Uses the studio's GPU preview: same passes, voxel rays instead of hardware ones.
     */
    private int render(Path graphFile, Path png, List<String> a) throws Exception {
        MosaicGraph g = MosaicGraph.parse(Files.readString(graphFile, StandardCharsets.UTF_8));
        String size = opt(a, "--size", settings.previewWidth + "x" + settings.previewHeight);
        int frames = Integer.parseInt(opt(a, "--frames", "24"));
        List<GpuPreview.PassRun> runs = new ArrayList<>();
        var plan = g.multiPass() ? io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicPasses.plan(g) : null;
        List<String> jsons = new ArrayList<>();
        if (plan != null) for (var p : plan.all()) jsons.add(p.json());
        else jsons.add(Files.readString(graphFile, StandardCharsets.UTF_8));
        for (int i = 0; i < jsons.size(); i++) {
            MosaicShaderCheck.Result r = MosaicShaderCheck.compile(MosaicCodegen.generate(MosaicGraph.parse(jsons.get(i)), MosaicCodegen.Options.PREVIEW, false));
            if (!r.ok()) throw new IllegalArgumentException("pass " + i + " does not compile: " + r.errors());
            if (plan == null) runs.add(new GpuPreview.PassRun(r.spirv(), 1, -1));
            else {
                var p = plan.all().get(i);
                runs.add(new GpuPreview.PassRun(r.spirv(), p.scale(), p.name() == null ? -1 : plan.bindings().get(p.name()), plan.after().contains(p)));
            }
        }
        StudioUi.settings = settings;
        String worldFile = opt(a, "--world", null);
        if (worldFile != null && !Files.exists(Path.of(worldFile)) && gameDirOf(graphFile) != null
            && Files.isDirectory(gameDirOf(graphFile).resolve("saves").resolve(worldFile)))
            worldFile = gameDirOf(graphFile).resolve("saves").resolve(worldFile).toString(); // a save by its name
        int wr = io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicSettings.intValue(g.settings(), "worldRadius", 8, 256);
        int wh0 = io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicSettings.intValue(g.settings(), "worldHeight", 16, 256);
        var snap = worldFile == null ? PreviewWorld.load(gameDirOf(graphFile))
            : Files.isDirectory(Path.of(worldFile)) ? PreviewWorld.load(gameDirOf(graphFile), worldFile, wr * 2, wh0, wr * 2) // a save folder
            : io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicWorld.Snapshot.read(Files.readAllBytes(Path.of(worldFile)));
        if (snap == null) throw new IllegalArgumentException("not a preview world file: " + worldFile);
        if (PreviewWorld.lastError != null) out.println("warning: " + PreviewWorld.lastError);
        PreviewWorld.View view = PreviewWorld.View.of(snap, gameDirOf(graphFile));
        String time = opt(a, "--time", null), pitch = opt(a, "--pitch", null), yaw = opt(a, "--yaw", null);
        String pos = opt(a, "--pos", null);
        if (pos != null) {
            String[] xyz = pos.split(",");
            view = new PreviewWorld.View(view.world(), Double.parseDouble(xyz[0]), Double.parseDouble(xyz[1]), Double.parseDouble(xyz[2]),
                view.yaw(), view.pitch(), view.timeOfDay(), view.atlas());
        }
        if (time != null) view = view.at((float) (Math.floorMod(Integer.parseInt(time) - 6000, 24000) / 24000.0));
        if (pitch != null) view = view.look(0f, Float.parseFloat(pitch) - view.pitch());
        if (yaw != null) view = view.look(Float.parseFloat(yaw) - view.yaw(), 0f);
        view = view.with(g.settings(), settings.previewLook);
        String[] wh = size.toLowerCase(Locale.ROOT).split("x");
        try (GpuPreview gpu = GpuPreview.create()) {
            gpu.worldWidth = Integer.parseInt(wh[0].trim());
            gpu.worldHeight = Integer.parseInt(wh[1].trim());
            java.awt.image.BufferedImage img = null;
            long t0 = System.nanoTime();
            double[] sum = null;
            int counted = 0;
            for (int f = 0; f < Math.max(1, frames); f++) {
                img = gpu.runChain(runs, null, f / 30f, f, null, view);
                double[] tm = gpu.lastTimings;
                if (f >= 2 && tm != null) { // the first frames build pipelines and fill buffers
                    if (sum == null) sum = new double[tm.length];
                    for (int i = 0; i < tm.length && i < sum.length; i++) sum[i] += tm[i];
                    counted++;
                }
            }
            long ms = (System.nanoTime() - t0) / 1_000_000 / Math.max(1, frames);
            if (a.contains("--profile")) {
                if (sum == null) out.println("profile: this GPU queue writes no timestamps");
                else {
                    double total = 0;
                    for (double v : sum) total += v / counted;
                    out.println(String.format(Locale.ROOT, "GPU time per frame %.2f ms (average of %d frames)", total, counted));
                    out.println(String.format(Locale.ROOT, "  %-34s %7.2f ms", "world G-buffer", sum[0] / counted));
                    for (int i = 1; i < sum.length; i++) {
                        String name = plan == null ? "final image" : plan.all().get(i - 1).name() == null ? "final image"
                            : "Buffer " + plan.all().get(i - 1).name() + " 1/" + plan.all().get(i - 1).scale();
                        out.println(String.format(Locale.ROOT, "  %-34s %7.2f ms", name, sum[i] / counted));
                    }
                }
            }
            if (png.getParent() != null) Files.createDirectories(png.getParent());
            ImageIO.write(img, "png", png.toFile());
            out.println("rendered " + png + "  " + gpu.worldWidth + "x" + gpu.worldHeight + ", " + runs.size() + " passes, " + frames
                + " frames, " + ms + " ms/frame on " + gpu.deviceName + ", " + PreviewWorld.originOf(snap)
            + (PreviewWorld.blocksOf(snap) != null ? " (" + PreviewWorld.blocksOf(snap).names().size() + " kinds of blocks)" : ""));
        }
        return 0;
    }

    /** The game folder a graph lives in (the folder above shaderpacks/), or the one in the settings. */
    private Path gameDirOf(Path graphFile) {
        if (settings.gameDir != null && !settings.gameDir.isBlank() && Files.isDirectory(Path.of(settings.gameDir))) return Path.of(settings.gameDir);
        for (Path p = graphFile.toAbsolutePath(); p != null; p = p.getParent())
            if (p.getFileName() != null && p.getFileName().toString().equals("shaderpacks") && p.getParent() != null) return p.getParent();
        return null;
    }

    private static String opt(List<String> a, String name, String def) {
        int i = a.indexOf(name);
        return i >= 0 && i + 1 < a.size() ? a.get(i + 1) : def;
    }
}
