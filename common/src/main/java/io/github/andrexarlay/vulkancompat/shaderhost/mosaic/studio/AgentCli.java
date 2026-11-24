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
}
