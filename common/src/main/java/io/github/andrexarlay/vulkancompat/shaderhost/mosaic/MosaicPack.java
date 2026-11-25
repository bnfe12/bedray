package io.github.andrexarlay.vulkancompat.shaderhost.mosaic;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The whole Bedray Slang pack as a node map: every module of shaders/slang is a node ({@code slang.module}, path in mask),
 * every import a wire from the imported module, every pass entry point feeds the pack output ({@code slang.pack}); folders
 * become coloured frames laid out by layer (contracts -> traversal / surface / light -> shading -> path tracing / ReSTIR /
 * cascades -> denoise / post -> passes). Each module also says which Renderer stage, if any, controls it today.
 */
public final class MosaicPack {

    private MosaicPack() {}

    /** One module: imports (resolved to paths), whether it is a pass entry, size and what it declares. */
    public record Module(String path, List<String> imports, boolean entry, int lines, List<String> structs, List<String> functions) {}

    private static final Pattern IMPORT = Pattern.compile("(?m)^\\s*(?:__exported\\s+)?import\\s+([A-Za-z0-9_./\"]+)\\s*;");
    private static final Pattern ENTRY = Pattern.compile("\\[shader\\(\"(compute|raygeneration|closesthit|miss|anyhit)\"\\)\\]|\\[numthreads\\(");
    private static final Pattern STRUCT = Pattern.compile("(?m)^\\s*(?:public\\s+)?(?:struct|interface)\\s+([A-Za-z_]\\w*)");
    private static final Pattern FUNC = Pattern.compile("(?m)^\\s*(?:public\\s+)?(?:static\\s+)?(?:inline\\s+)?[A-Za-z_][\\w<>,]*\\s+([a-zA-Z_]\\w*)\\s*\\([^;{)]*\\)\\s*\\{");

    /** Parses the sources (path relative to shaders/slang -> text) into modules with resolved imports. */
    public static Map<String, Module> parse(Map<String, String> sources) {
        Map<String, Module> out = new TreeMap<>();
        for (Map.Entry<String, String> e : sources.entrySet()) {
            String f = e.getKey(), t = e.getValue();
            List<String> deps = new ArrayList<>();
            Matcher m = IMPORT.matcher(t);
            while (m.find()) {
                String r = resolve(f, m.group(1).replace("\"", ""), sources);
                if (r != null && !deps.contains(r)) deps.add(r);
            }
            List<String> structs = new ArrayList<>(), funcs = new ArrayList<>();
            Matcher s = STRUCT.matcher(t);
            while (s.find()) structs.add(s.group(1));
            Matcher fn = FUNC.matcher(t);
            while (fn.find() && funcs.size() < 60) {
                String n = fn.group(1);
                if (!List.of("if", "for", "while", "switch", "return").contains(n) && !funcs.contains(n)) funcs.add(n);
            }
            out.put(f, new Module(f, deps, ENTRY.matcher(t).find(), (int) t.lines().count(), structs, funcs));
        }
        return out;
    }

    private static String resolve(String from, String imp, Map<String, String> sources) {
        String p = imp.endsWith(".slang") ? imp.substring(0, imp.length() - 6) : imp;
        if (!p.contains("/")) p = p.replace('.', '/');
        String dir = from.contains("/") ? from.substring(0, from.lastIndexOf('/') + 1) : "";
        for (String c : new String[] {p + ".slang", dir + p + ".slang", p.replaceFirst("^\\.\\./", "") + ".slang"})
            if (sources.containsKey(c)) return c;
        return null;
    }

    /** Folder order of the layout (lower layers first), as in shaders/slang/ARCHITECTURE.md. */
    public static final List<String> LAYERS = List.of("(root)", "contracts", "voxel", "traversal", "surface", "light", "shading", "history",
        "pathtrace", "restir", "radiance_cascades", "experimental", "nort", "denoise", "spatial", "bloom", "post", "passes");

    private static final int[] COLORS = {0x5A6478, 0x3A6EA5, 0x4E7A30, 0x2F8F7C, 0x8A6A4A, 0xC0902E, 0xA5683A, 0x6C8AA8, 0x4A76B8,
        0x7A4FA8, 0x2C7FB0, 0x8A3A6E, 0x5E7A2F, 0x3E8FB0, 0x5A7AC8, 0xB0507A, 0x9A4D7A, 0xB5524C};

    /** The Renderer stage that controls a module today, or null when no node controls it yet. */
    public static String stageOf(String path) {
        String[][] rules = {
            {"^restir/pt_|restir_pt", "bedray.restirPt"}, {"^restir/|restir_spatial|restir_di", "bedray.restirDi"},
            {"^radiance_cascades|rc_", "bedray.radianceCascades"}, {"^pathtrace/|path_trace|path_display|path_hdr", "bedray.pathTracer"},
            {"volumetric", "bedray.fog"}, {"^denoise/|nrd_|rr_depth", "bedray.denoiser"},
            {"tonemap|output_resolve|^post/", "bedray.exposure"}, {"bloom|post_cinema", "bedray.post"},
            {"^light/sun|^light/sky|sky_|frame_sky", "bedray.sky"}, {"^surface/|^shading/", "bedray.materials"},
            {"^traversal/|^voxel/|^light/(emitter|block_emitters)", "bedray.scene"}};
        for (String[] r : rules) if (Pattern.compile(r[0]).matcher(path).find()) return r[1];
        return null;
    }

    public static String folder(String path) { return path.contains("/") ? path.substring(0, path.indexOf('/')) : "(root)"; }

    static String id(String path) { return path.replace(".slang", "").replaceAll("[^A-Za-z0-9_]", "_"); }

    /** The map graph: one column per folder (lower layers left), frames per folder, every pass entry into the output. */
    public static String graph(Map<String, Module> mods) {
        Map<String, List<Module>> byFolder = new LinkedHashMap<>();
        for (String f : LAYERS) byFolder.put(f, new ArrayList<>());
        for (Module m : mods.values()) byFolder.computeIfAbsent(folder(m.path()), k -> new ArrayList<>()).add(m);
        JsonArray nodes = new JsonArray(), frames = new JsonArray();
        JsonObject outInputs = new JsonObject();
        int col = 0, maxY = 0, entries = 0, rowTop = 60, rowBottom = 60, inRow = 0;
        final int W = 200, ROW = 74, GAP = 70, PER_ROW = 6;
        for (Map.Entry<String, List<Module>> e : byFolder.entrySet()) {
            if (e.getValue().isEmpty()) continue;
            if (inRow == PER_ROW) { // the next folders go below the tallest of this row
                inRow = 0;
                rowTop = rowBottom + 90;
            }
            int x = 40 + inRow * (W + GAP), y = rowTop;
            inRow++;
            for (Module m : e.getValue()) {
                JsonObject n = new JsonObject();
                n.addProperty("id", id(m.path()));
                n.addProperty("type", "slang.module");
                n.addProperty("mask", m.path());
                n.addProperty("label", m.path().substring(m.path().lastIndexOf('/') + 1).replace(".slang", ""));
                n.addProperty("index", m.entry() ? 1 : 0);
                n.addProperty("x", x);
                n.addProperty("y", y);
                JsonObject in = new JsonObject();
                for (int i = 0; i < m.imports().size(); i++) in.addProperty("i" + i, id(m.imports().get(i)));
                if (in.size() > 0) n.add("inputs", in);
                nodes.add(n);
                if (m.entry()) outInputs.addProperty("p" + entries++, id(m.path()));
                y += ROW;
            }
            JsonObject fr = new JsonObject();
            fr.addProperty("id", "f_" + col);
            fr.addProperty("title", e.getKey() + "  (" + e.getValue().size() + ")");
            fr.addProperty("x", x - 16);
            fr.addProperty("y", rowTop - 40);
            fr.addProperty("w", W + 32);
            fr.addProperty("h", y - rowTop + 40);
            rowBottom = Math.max(rowBottom, y);
            fr.addProperty("color", COLORS[col % COLORS.length]);
            frames.add(fr);
            maxY = Math.max(maxY, y);
            col++;
        }
        JsonObject out = new JsonObject();
        out.addProperty("id", "pack");
        out.addProperty("type", "slang.pack");
        out.addProperty("label", "Bedray frame (" + entries + " passes)");
        out.addProperty("x", 40 + PER_ROW * (W + GAP));
        out.addProperty("y", 60);
        out.add("inputs", outInputs);
        nodes.add(out);
        JsonObject root = new JsonObject();
        root.addProperty("kind", "pack");
        root.add("nodes", nodes);
        root.add("frames", frames);
        return root.toString();
    }
}
