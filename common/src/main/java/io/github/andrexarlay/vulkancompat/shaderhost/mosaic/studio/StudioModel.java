package io.github.andrexarlay.vulkancompat.shaderhost.mosaic.studio;

import io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicArrange;
import io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicCatalog;
import io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicCodegen;
import io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicDocument;
import io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicEffects;
import io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicFunction;
import io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicGraph;
import io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicMaterial;
import io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicShaderCheck;
import io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicTemplates;

import javax.imageio.ImageIO;
import javax.swing.SwingWorker;
import javax.swing.Timer;
import java.awt.Color;
import java.awt.Point;
import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The studio's single source of truth: the document, the selection, analysis, generated code, the background compile,
 * Live saving and the result frames coming back from the game. Every panel (in any window) reads it and calls its
 * operations; listeners repaint them. All methods run on the Swing thread.
 */
final class StudioModel {

    final MosaicDocument doc;
    final Set<MosaicDocument.ENode> selection = new LinkedHashSet<>();
    MosaicDocument.ENode primary;
    MosaicDocument.Frame selectedFrame;
    MosaicGraph.Analysis analysis;
    MosaicCodegen.Generated generated;
    MosaicShaderCheck.Result compile;
    boolean compiling, live;
    String message = "";
    Color messageColor = StudioUi.MUTED;
    StudioKeys keys;
    GraphPanel activeGraph;
    int docVersion, selectionVersion;

    private final List<Runnable> listeners = new ArrayList<>();
    private final Timer settle;
    private SwingWorker<MosaicShaderCheck.Result, Void> worker;

    // result frames from the game (see linkDir)
    record Shot(int step, String label, BufferedImage before, BufferedImage after, long at) {}

    final List<Shot> shots = new ArrayList<>();
    BufferedImage liveBefore, liveAfter;
    long linkStamp;
    String gameStatus = "no game connected";
    private final Timer linkPoll;

    StudioModel(MosaicDocument doc, StudioSettings settings) {
        this.doc = doc;
        this.live = settings.liveByDefault;
        this.keys = new StudioKeys(settings.keys);
        settle = new Timer(350, e -> settled());
        settle.setRepeats(false);
        linkPoll = new Timer(250, e -> pollGame());
        linkPoll.start();
        analyze();
        layoutIfUnplaced();
    }

    /** A graph written without positions (an agent, a hand-made file) is laid out once so it can be read. */
    void layoutIfUnplaced() {
        if (doc.nodes.size() < 2 || !doc.nodes.stream().allMatch(n -> n.x == doc.nodes.get(0).x && n.y == doc.nodes.get(0).y)) return;
        MosaicArrange.arrange(doc.nodes, StudioUi.settings.nodeWidth, n -> 60 + 18 * Math.max(1, n.inputs.size()),
            n -> MosaicGraph.portsOf(n.type, analysis.functions()).stream().map(MosaicGraph.Port::name).toList());
    }

    void listen(Runnable r) { listeners.add(r); }

    void unlisten(Runnable r) { listeners.remove(r); }

    void fire() { for (Runnable r : List.copyOf(listeners)) r.run(); }

    void say(String text, Color c) {
        message = text;
        messageColor = c;
        fire();
    }

    // ================================================================== analysis, compile, live

    private void analyze() {
        analysis = doc.analyze();
        MosaicGraph g = analysis.graph();
        functionCode = null;
        if (doc.bedrayFunction != null) {
            generated = null;
            baked = null;
            rendererSettings = null;
            if (g != null && g.isFunctionGraph() && analysis.ok()) {
                String name = doc.bedrayFunction.substring(doc.bedrayFunction.lastIndexOf('/') + 1).replaceAll("\\(.*", "");
                if (name.contains(".")) name = name.substring(name.lastIndexOf('.') + 1);
                functionCode = io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicSlangGraph.function(g, name);
            }
        } else if (g != null && (g.isPackMap() || g.isStageMap()) || doc.stage != null || doc.nodes.stream().anyMatch(n -> n.type.startsWith("slang."))) {
            generated = null;
            baked = null;
            rendererSettings = null; // a map is a view: it never sends settings
        } else if (g != null && g.isMaterial()) {
            generated = null;
            bake(g);
        } else if (g != null && g.isRenderer() || workspace == Workspace.RENDERER) {
            generated = null;
            baked = null;
            rendererSettings = g == null || !g.isRenderer() ? null : io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicRenderer.settings(g);
        } else {
            baked = null;
            bakeError = null;
            plan = null;
            planError = null;
            if (g != null && g.multiPass()) {
                try {
                    plan = io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicPasses.plan(g);
                    MosaicGraph last = MosaicGraph.analyze(plan.last().json()).graph();
                    generated = last == null ? null : MosaicCodegen.generate(last, MosaicCodegen.Options.DEFAULT, false); // shown in Code
                } catch (IllegalArgumentException e) {
                    planError = e.getMessage();
                    generated = null;
                }
            } else generated = analysis.ok() ? MosaicCodegen.generate(g, MosaicCodegen.Options.DEFAULT, false) : null;
        }
    }

    // ================================================================== Bedray renderer graphs

    /** The bedray.toml pairs the open renderer graph stands for (null when it has errors or is not a renderer graph). */
    java.util.Map<String, String> rendererSettings;
    volatile String rendererStatus = "", rendererGameStatus = "";

    boolean rendererDoc() { return analysis.graph() != null ? analysis.graph().isRenderer() : workspace == Workspace.RENDERER; }

    /** Where the renderer graph lives: next to the game's studio link, else in ~/.bedray/studio. */
    Path rendererFile() {
        Path g = StudioLibrary.gameDir(this);
        return (g != null ? g.resolve("bedray-studio") : StudioSettings.file().resolveSibling("studio")).resolve("renderer.mosaic.json");
    }

    /** The game's current renderer values (written by the mod), or null. */
    java.util.Map<String, String> rendererState() {
        Path g = StudioLibrary.gameDir(this);
        Path f = g == null ? null : g.resolve("bedray-studio/renderer-state.json");
        if (f == null || !Files.exists(f)) return null;
        try {
            java.util.Map<String, String> out = new java.util.LinkedHashMap<>();
            for (var e : com.google.gson.JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)).getAsJsonObject().entrySet())
                out.put(e.getKey(), e.getValue().getAsString());
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    /** The modules of the open pack map (for the inspector), by path. */
    java.util.Map<String, io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicPack.Module> packModules = java.util.Map.of();
    java.util.Map<String, String> packSources = java.util.Map.of();
    String packOrigin = "";

    boolean packDoc() { return doc.nodes.stream().anyMatch(n -> n.type.equals("slang.pack")); }

    // ================================================================== Bedray's functions as graphs

    /** The function a "Your post stage" node is the graph of (post_cinema.slang: identity in Bedray's own build). */
    static final String POST_HOOK = "post_cinema/postStudio";
    /** Your stages: node type -> the hook function of Bedray's pack its graph is ({@code user.*}). */
    static final java.util.Map<String, String> USER_STAGES = java.util.Map.of("user.post", POST_HOOK, "user.hdr", "post_cinema/hdrStudio",
        "user.sky", "light/frame_sky/skyStudio");

    /** Opens a pack function ("light/sun/sunSolidAngle") in a tab of its own; set by the app. */
    java.util.function.Consumer<String> openFunction;
    private boolean libraryReady;
    /** The open function graph written back as Slang (null when it is not one or has errors). */
    io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicSlangGraph.Code functionCode;
    volatile String functionStatus = "";
    volatile Boolean functionOk;
    private SwingWorker<?, ?> functionWorker;

    boolean functionDoc() { return doc.bedrayFunction != null; }

    /** Bedray's pack as a node library: read once from the checkout or the Bedray jar of the game instance. */
    io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicSlangLib.Library bedrayLibrary() {
        if (libraryReady) return io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicSlangGraph.library();
        libraryReady = true;
        StudioLibrary.SlangPack sp = StudioLibrary.bedraySlang(this);
        if (sp.sources().isEmpty()) return io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicSlangGraph.library();
        Path root;
        if (sp.origin().startsWith("checkout ")) root = Path.of(sp.origin().substring("checkout ".length()));
        else {
            root = StudioSettings.file().resolveSibling("studio").resolve("pack").resolve("slang");
            try {
                for (var e : sp.sources().entrySet()) {
                    Path f = root.resolve(e.getKey());
                    Files.createDirectories(f.getParent());
                    Files.writeString(f, e.getValue(), StandardCharsets.UTF_8);
                }
            } catch (Exception e) {
                say("Could not unpack Bedray's Slang pack: " + e.getMessage(), StudioUi.BAD);
            }
        }
        io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicSlangGraph.install(
            io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicSlangLib.parse(sp.sources()), root);
        return io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicSlangGraph.library();
    }

    /** Where edited pack functions live: one graph file per function. */
    static Path functionsDir() { return StudioSettings.file().resolveSibling("studio").resolve("functions"); }

    static Path functionFile(String id) { return functionsDir().resolve(id.replaceAll("[^A-Za-z0-9_.()-]+", "_") + ".mosaic.json"); }

    /** Opens a function of the pack as nodes (decompiled the first time; your edited graph after that). */
    boolean openFunctionGraph(String id) {
        var lib = bedrayLibrary();
        var f = lib == null ? null : lib.functions().get(id);
        if (f == null) {
            say("No function " + id + " in this Bedray pack", StudioUi.BAD);
            return false;
        }
        Path file = functionFile(id);
        try {
            if (!Files.exists(file)) {
                Files.createDirectories(file.getParent());
                Files.writeString(file, io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicSlangDecompile.decompile(f, lib).json(), StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            say("Could not open " + id + ": " + e.getMessage(), StudioUi.BAD);
            return false;
        }
        workspace = Workspace.RENDERER;
        open(file);
        long blocks = doc.nodes.stream().filter(n -> n.type.equals(io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicSlangGraph.CODE)).count();
        say(f.label() + " (" + f.module() + "): " + doc.nodes.size() + " nodes" + (blocks == 0 ? ", all of it nodes" : ", " + blocks
            + " Slang block(s) for its if / for code") + ". Edit it, then Apply to Bedray.", StudioUi.MUTED);
        return true;
    }

    /** Throws away your edits of the open function: back to Bedray's own body. */
    void resetFunction() {
        String id = doc.bedrayFunction;
        if (id == null) return;
        try {
            Files.deleteIfExists(functionFile(id));
        } catch (Exception ignored) {
            // reopened below anyway
        }
        openFunctionGraph(id);
    }

    /** The module source with every edited function graph of that module in place of Bedray's body. */
    String overriddenModule(String module) {
        return StudioPack.overridden(bedrayLibrary(), module, doc.bedrayFunction, doc.bedrayFunction == null ? null : analysis.graph());
    }

    /** slangc checks the open function inside its module (the whole pack copy, so callers of it are checked too). */
    void checkFunction() {
        var lib = bedrayLibrary();
        var f = doc.bedrayFunction == null || lib == null ? null : lib.functions().get(doc.bedrayFunction);
        if (f == null || functionCode == null) return;
        String module = overriddenModule(f.module());
        Path slangc = io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicSlang.locate(StudioUi.settings.slangcPath);
        if (functionWorker != null) functionWorker.cancel(true);
        functionOk = null;
        functionStatus = "Checking " + f.label() + " with slangc...";
        fire();
        functionWorker = new SwingWorker<io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicSlangGraph.Check, Void>() {
            @Override protected io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicSlangGraph.Check doInBackground() {
                return io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicSlangGraph.checkModules(java.util.Map.of(f.module(), module), slangc,
                    io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicSlangGraph.packRoot());
            }

            @Override protected void done() {
                if (isCancelled()) return;
                try {
                    var c = get();
                    functionOk = c.ok();
                    functionStatus = c.ok() ? f.label() + " compiles with Bedray's pack (slangc)"
                        : "slangc: " + c.log().lines().filter(l -> l.contains("error")).findFirst().orElse(c.log().lines().findFirst().orElse("failed"));
                } catch (Exception e) {
                    functionOk = false;
                    functionStatus = "slangc: " + e.getMessage();
                }
                fire();
            }
        };
        functionWorker.execute();
    }

    // ================================================================== stage insides

    /** Opens a renderer stage's inside in a tab of its own; set by the app. */
    java.util.function.Consumer<String> openStage;

    static Path stagesDir() { return StudioSettings.file().resolveSibling("studio").resolve("stages"); }

    static Path stageFile(String type) { return stagesDir().resolve(type.replace('.', '_') + ".mosaic.json"); }

    /** The stage's current numbers: from the open renderer graph, else the saved one, else Bedray's defaults. */
    java.util.Map<String, Double> stageValues(String type) {
        java.util.Map<String, Double> out = new java.util.LinkedHashMap<>();
        List<MosaicDocument.ENode> from = null;
        if (rendererDoc() && doc.stage == null && doc.bedrayFunction == null) from = doc.nodes;
        else if (Files.exists(rendererFile())) {
            try {
                from = MosaicDocument.load(rendererFile()).nodes;
            } catch (Exception ignored) {
                // defaults below
            }
        }
        if (from != null) for (MosaicDocument.ENode n : from)
            if (n.type.equals(type)) {
                n.defaults.forEach((k, v) -> { if (v.length > 0) out.put(k, v[0]); });
                break;
            }
        return out;
    }

    /** Opens the inside of a stage: its settings as public parameters and the functions of its modules. */
    boolean openStageMap(String type) {
        var lib = bedrayLibrary();
        if (lib == null) {
            say("Bedray's Slang sources were not found: choose the game instance with the Bedray mod (Library)", StudioUi.BAD);
            return false;
        }
        java.util.Map<String, Double> values = stageValues(type);
        Path f = stageFile(type);
        try {
            Files.createDirectories(f.getParent());
            Files.writeString(f, io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicStageMap.graph(type, lib, values), StandardCharsets.UTF_8);
        } catch (Exception e) {
            say("Could not open the stage: " + e.getMessage(), StudioUi.BAD);
            return false;
        }
        workspace = Workspace.RENDERER;
        open(f);
        var st = io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicRenderer.stage(type);
        long fns = doc.nodes.stream().filter(n -> n.type.startsWith(io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicSlangGraph.FN)).count();
        say((st == null ? type : st.label()) + ": " + (st == null ? 0 : st.settings().size()) + " public parameters, "
            + io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicStageMap.modules(type, lib).size() + " modules, " + fns
            + " functions (double-click one to open its body)", StudioUi.MUTED);
        return true;
    }

    /** The parameters of an open stage inside go to the renderer graph and on to the game. */
    void syncStageSettings() {
        if (doc.stage == null) return;
        try {
            Path rf = rendererFile();
            MosaicDocument rd;
            if (Files.exists(rf)) rd = MosaicDocument.load(rf);
            else {
                Files.createDirectories(rf.getParent());
                Files.writeString(rf, io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicRenderer.defaultGraph(rendererState()), StandardCharsets.UTF_8);
                rd = MosaicDocument.load(rf);
            }
            MosaicDocument.ENode st = rd.nodes.stream().filter(n -> n.type.equals(doc.stage)).findFirst().orElse(null);
            if (st == null) {
                rendererStatus = "this stage is not in your renderer graph: add it there to send its parameters";
                fire();
                return;
            }
            boolean changed = false;
            for (MosaicDocument.ENode n : doc.nodes) {
                if (!n.type.equals(io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicSlangGraph.SETTING) || n.value == null || n.value.length == 0) continue;
                double[] cur = st.defaults.get(n.mask);
                if (cur != null && cur.length > 0 && cur[0] == n.value[0]) continue;
                st.defaults.put(n.mask, new double[] {n.value[0]});
                changed = true;
            }
            if (!changed) return;
            rd.save();
            MosaicGraph rg = MosaicGraph.analyze(rd.toJson()).graph();
            if (rg != null && rg.isRenderer()) writeRenderer(io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicRenderer.settings(rg));
        } catch (Exception e) {
            rendererStatus = "could not update the renderer graph: " + e.getMessage();
            fire();
        }
    }

    private long editedAt;
    private java.util.Set<String> editedModules = java.util.Set.of();

    /** Modules with a function you edited (read again at most every two seconds). */
    java.util.Set<String> editedModules() {
        if (System.currentTimeMillis() - editedAt < 2000) return editedModules;
        editedAt = System.currentTimeMillis();
        java.util.Set<String> out = new java.util.TreeSet<>();
        var lib = io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicSlangGraph.library();
        if (lib != null && Files.isDirectory(functionsDir())) {
            try (var files = Files.list(functionsDir())) {
                for (Path f : (Iterable<Path>) files::iterator) {
                    String name = f.getFileName().toString().replace(".mosaic.json", "");
                    for (var fn : lib.functions().values())
                        if (functionFile(fn.id()).getFileName().toString().equals(f.getFileName().toString())) {
                            out.add(fn.module());
                            break;
                        }
                }
            } catch (Exception ignored) {
                // none
            }
        }
        editedModules = out;
        return out;
    }

    /** Is a stage your version (a function of one of its modules edited) rather than Bedray's own? */
    boolean stageEdited(String type) {
        var lib = io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicSlangGraph.library();
        if (lib == null) return false;
        java.util.Set<String> edited = editedModules();
        if (edited.isEmpty()) return false;
        for (String m : io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicStageMap.modules(type, lib)) if (edited.contains(m)) return true;
        return false;
    }

    /** Writes every edited function into the game (bedray-studio/slang) and rebuilds the passes that use them. */
    void applyFunctions() {
        if (doc.dirty && doc.file != null) save(false);
        StudioPack.apply(this);
    }

    /** Builds the map of Bedray's whole Slang pack (every module, import and pass) and opens it. */
    void openPackMap() {
        StudioLibrary.SlangPack sp = StudioLibrary.bedraySlang(this);
        if (sp.sources().isEmpty()) {
            say("Bedray's Slang sources were not found: choose the game instance with the Bedray mod (Library)", StudioUi.BAD);
            return;
        }
        try {
            packSources = sp.sources();
            packOrigin = sp.origin();
            packModules = io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicPack.parse(sp.sources());
            Path f = StudioSettings.file().resolveSibling("studio").resolve("bedray-pack.mosaic.json");
            Files.createDirectories(f.getParent());
            Files.writeString(f, io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicPack.graph(packModules), StandardCharsets.UTF_8);
            workspace = Workspace.RENDERER;
            open(f);
            long entries = packModules.values().stream().filter(io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicPack.Module::entry).count();
            long covered = packModules.keySet().stream().filter(p -> io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicPack.stageOf(p) != null).count();
            say("Bedray pack: " + packModules.size() + " modules, " + entries + " passes; " + covered + " belong to a Renderer stage, "
                + (packModules.size() - covered) + " are not nodes yet (" + sp.origin() + ")", StudioUi.OK);
        } catch (Exception e) {
            say("Could not build the pack map: " + e.getMessage(), StudioUi.BAD);
        }
    }

    /** Opens (or creates, from the game's current values) the renderer graph. */
    void openRenderer() {
        try {
            Path f = rendererFile();
            if (!Files.exists(f)) {
                Files.createDirectories(f.getParent());
                Files.writeString(f, io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicRenderer.defaultGraph(rendererState()), StandardCharsets.UTF_8);
            }
            workspace = Workspace.RENDERER;
            open(f);
            say(rendererState() != null ? "Bedray renderer: the graph shows the values your game runs with" :
                "Bedray renderer: default values (the game sends its own once it runs with the studio folder)", StudioUi.OK);
        } catch (Exception e) {
            say("Could not open the renderer graph: " + e.getMessage(), StudioUi.BAD);
        }
    }

    /** Sends the graph's settings to the game, which applies them at once and saves bedray.toml. */
    void writeRenderer() { writeRenderer(rendererSettings); }

    /** Sends renderer settings to the game: Studio's numbers win over the game's menu ("priority") while they are live. */
    void writeRenderer(java.util.Map<String, String> s) {
        Path g = StudioLibrary.gameDir(this);
        if (s == null) return;
        if (g == null) {
            rendererStatus = "choose the game folder (Library or Settings) so Bedray receives the renderer graph";
            fire();
            return;
        }
        try {
            Path dir = g.resolve("bedray-studio");
            Files.createDirectories(dir);
            com.google.gson.JsonObject o = new com.google.gson.JsonObject();
            o.addProperty("apply", System.currentTimeMillis());
            o.addProperty("save", true);
            o.addProperty("priority", true);
            com.google.gson.JsonObject set = new com.google.gson.JsonObject();
            s.forEach(set::addProperty);
            o.add("settings", set);
            Path tmp = dir.resolve("renderer.json.tmp");
            Files.writeString(tmp, o.toString(), StandardCharsets.UTF_8);
            Files.move(tmp, dir.resolve("renderer.json"), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            rendererStatus = s.size() + " renderer settings sent to Bedray";
        } catch (Exception e) {
            rendererStatus = "could not send the renderer graph: " + e.getMessage();
        }
        fire();
    }

    // ================================================================== workspaces and materials

    /** Shaders: the frame's shader graph. Materials: per-texture surfaces baked into a resource pack. */
    enum Workspace { SHADERS, MATERIALS, RENDERER }

    Workspace workspace = Workspace.SHADERS;
    /** The shader graph file while another workspace is open (game folder, switching back). */
    Path shaderFile;
    StudioLibrary.Source materialSource;
    String materialEntry;
    MosaicMaterial.Image baseImage;
    MosaicMaterial.Baked baked;
    String bakeError;
    volatile String materialStatus = "";
    volatile String materialGameStatus = "";
    final java.util.Map<String, MosaicMaterial.Image> images = new java.util.HashMap<>();
    int bakeVersion;

    boolean materialDoc() {
        MosaicGraph g = analysis.graph();
        return g != null ? g.isMaterial() : doc.nodes.stream().anyMatch(n -> n.type.startsWith("mat."));
    }

    // ------------------------------------------------------------------ texture painting

    /** Paint tools of the material preview. */
    enum PaintTool { NAVIGATE, BRUSH, ERASER, FILL, PICKER }

    PaintTool paintTool = PaintTool.NAVIGATE;
    Color paintColor = new Color(0xC8643C);
    int brushSize = 1;
    /** The paint layer being edited (decoded from doc.paint); same size as the texture. */
    java.awt.image.BufferedImage paintLayer;
    private String paintDecoded;
    private String strokeBefore;
    private int strokePixels;

    private void syncPaint() {
        if (baseImage == null) {
            paintLayer = null;
            paintDecoded = null;
            return;
        }
        String p = doc.paint;
        if (paintLayer != null && java.util.Objects.equals(p, paintDecoded) && paintLayer.getWidth() == baseImage.w()) return;
        paintDecoded = p;
        paintLayer = new java.awt.image.BufferedImage(baseImage.w(), baseImage.h(), java.awt.image.BufferedImage.TYPE_INT_ARGB);
        if (p == null || p.isEmpty()) return;
        try {
            java.awt.image.BufferedImage img = ImageIO.read(new java.io.ByteArrayInputStream(java.util.Base64.getDecoder().decode(p)));
            if (img != null) paintLayer.getGraphics().drawImage(img, 0, 0, null);
        } catch (Exception ignored) {
            // a broken layer paints nothing
        }
    }

    /** The texture with the paint layer on top: what Block Texture nodes see. */
    MosaicMaterial.Image paintedBase() {
        if (baseImage == null || paintLayer == null) return baseImage;
        int[] out = baseImage.argb().clone();
        int[] layer = paintLayer.getRGB(0, 0, baseImage.w(), baseImage.h(), null, 0, baseImage.w());
        for (int i = 0; i < out.length; i++) {
            int l = layer[i], a = l >>> 24;
            if (a == 0) continue;
            if (a == 255) {
                out[i] = l;
                continue;
            }
            int b = out[i];
            int r = (((l >> 16) & 255) * a + ((b >> 16) & 255) * (255 - a)) / 255, g = (((l >> 8) & 255) * a + ((b >> 8) & 255) * (255 - a)) / 255,
                bl = ((l & 255) * a + (b & 255) * (255 - a)) / 255;
            out[i] = Math.max(a, b >>> 24) << 24 | r << 16 | g << 8 | bl;
        }
        return new MosaicMaterial.Image(baseImage.w(), baseImage.h(), out);
    }

    void beginStroke() {
        strokeBefore = doc.snapshot();
        strokePixels = 0;
    }

    /** Applies the current tool at texel (x, y) of the texture. */
    void paintAt(int x, int y) {
        syncPaint();
        if (paintLayer == null || x < 0 || y < 0 || x >= baseImage.w() || y >= baseImage.h()) return;
        MosaicMaterial.Image eff = paintedBase();
        switch (paintTool) {
            case PICKER -> {
                paintColor = new Color(eff.argb()[y * eff.w() + x], true);
                if (paintColor.getAlpha() == 0) paintColor = new Color(paintColor.getRGB() | 0xFF000000);
                fire();
                return;
            }
            case FILL -> {
                int target = eff.argb()[y * eff.w() + x];
                int frame = Math.min(eff.w(), eff.h()), fy0 = y / frame * frame;
                java.util.ArrayDeque<int[]> q = new java.util.ArrayDeque<>();
                boolean[] seen = new boolean[eff.w() * eff.h()];
                q.add(new int[] {x, y});
                while (!q.isEmpty()) {
                    int[] p = q.poll();
                    int px = p[0], py = p[1];
                    if (px < 0 || py < fy0 || px >= eff.w() || py >= fy0 + frame) continue;
                    int i = py * eff.w() + px;
                    if (seen[i] || eff.argb()[i] != target) continue;
                    seen[i] = true;
                    paintLayer.setRGB(px, py, paintColor.getRGB());
                    strokePixels++;
                    q.add(new int[] {px + 1, py});
                    q.add(new int[] {px - 1, py});
                    q.add(new int[] {px, py + 1});
                    q.add(new int[] {px, py - 1});
                }
            }
            default -> {
                int lo = -(brushSize - 1) / 2, hi = brushSize / 2;
                for (int dy = lo; dy <= hi; dy++)
                    for (int dx = lo; dx <= hi; dx++) {
                        int px = x + dx, py = y + dy;
                        if (px < 0 || py < 0 || px >= baseImage.w() || py >= baseImage.h()) continue;
                        paintLayer.setRGB(px, py, paintTool == PaintTool.ERASER ? 0 : paintColor.getRGB());
                        strokePixels++;
                    }
            }
        }
        MosaicGraph g = analysis.graph();
        if (g != null && g.isMaterial()) bake(g);
        fire();
    }

    /** Ends a stroke: the layer goes into the document as one undo step. */
    void endStroke() {
        if (strokeBefore == null || paintLayer == null) return;
        try {
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            ImageIO.write(paintLayer, "png", bytes);
            boolean empty = true;
            for (int v : paintLayer.getRGB(0, 0, paintLayer.getWidth(), paintLayer.getHeight(), null, 0, paintLayer.getWidth()))
                if (v >>> 24 != 0) {
                    empty = false;
                    break;
                }
            doc.paint = empty ? null : java.util.Base64.getEncoder().encodeToString(bytes.toByteArray());
            paintDecoded = doc.paint;
        } catch (Exception e) {
            say("Could not keep the paint: " + e.getMessage(), StudioUi.BAD);
        }
        String label = switch (paintTool) {
            case FILL -> "Fill " + strokePixels + " px";
            case ERASER -> "Erase " + strokePixels + " px";
            default -> "Paint " + strokePixels + " px";
        };
        commitGesture(strokeBefore, label);
        strokeBefore = null;
    }

    private void bake(MosaicGraph g) {
        bakeVersion++;
        syncPaint();
        if (baseImage == null) {
            baked = null;
            bakeError = "pick the texture this material is for: double-click it in the Library";
            return;
        }
        for (MosaicGraph.Node n : g.nodes())
            if (n.type().equals("mat.image") && n.mask() != null && !images.containsKey(n.mask())) loadImage(n.mask());
        try {
            baked = MosaicMaterial.bake(g, paintedBase(), images, baseImage);
            bakeError = null;
        } catch (IllegalArgumentException e) {
            baked = null;
            bakeError = e.getMessage();
        }
    }

    private void loadImage(String mask) {
        try {
            Path p = StudioLibrary.materialPack(this).resolve("assets/mosaic/textures").resolve(mask);
            java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(p.toFile());
            if (img != null) images.put(mask, StudioLibrary.toMaterial(img));
        } catch (Exception ignored) {
            // reported by the bake as a missing image
        }
    }

    /** Starts (or reopens) the material of a texture from a library source. */
    void openMaterial(StudioLibrary.Source src, String entry) {
        java.awt.image.BufferedImage img = StudioLibrary.image(src, entry);
        if (img == null) {
            say("Could not read " + entry, StudioUi.BAD);
            return;
        }
        try {
            Path pack = StudioLibrary.materialPack(this);
            StudioLibrary.ensurePack(pack);
            Path file = pack.resolve(entry.substring(0, entry.length() - 4) + ".mosaic.json");
            Files.createDirectories(file.getParent());
            boolean existed = Files.exists(file);
            String json = existed ? Files.readString(file, StandardCharsets.UTF_8) : MosaicMaterial.starter(guessRoughness(entry), guessMetal(entry));
            Files.writeString(baseFile(file), src.id() + "\n" + entry + "\n", StandardCharsets.UTF_8);
            if (doc.file != null && !materialDoc()) shaderFile = doc.file;
            setBase(src, entry, img);
            workspace = Workspace.MATERIALS;
            replace(json, file, (existed ? "Open material " : "New material ") + StudioLibrary.id(entry));
            if (!existed) save(false);
            doc.dirty = false;
            say((existed ? "Material " : "New material for ") + StudioLibrary.id(entry) + ": edit the nodes, Live writes it to "
                + StudioLibrary.MATERIAL_PACK + " and the game reloads it", StudioUi.OK);
        } catch (Exception e) {
            say("Could not start the material: " + e.getMessage(), StudioUi.BAD);
        }
    }

    /** Reopens a material file (its base texture is remembered next to it). */
    void openMaterialFile(Path file) {
        try {
            List<String> base = Files.exists(baseFile(file)) ? Files.readAllLines(baseFile(file)) : List.of();
            StudioLibrary.Source src = base.isEmpty() ? null : StudioLibrary.fromId(base.get(0));
            if (src != null && base.size() > 1) {
                java.awt.image.BufferedImage img = StudioLibrary.image(src, base.get(1));
                if (img != null) setBase(src, base.get(1), img);
            }
            workspace = Workspace.MATERIALS;
            open(file);
        } catch (Exception e) {
            say("Could not open the material: " + e.getMessage(), StudioUi.BAD);
        }
    }

    static Path baseFile(Path materialFile) {
        String n = materialFile.getFileName().toString();
        return materialFile.resolveSibling(n.substring(0, n.length() - ".mosaic.json".length()) + ".mosaic-base");
    }

    private void setBase(StudioLibrary.Source src, String entry, java.awt.image.BufferedImage img) {
        materialSource = src;
        materialEntry = entry;
        baseImage = StudioLibrary.toMaterial(img);
        images.clear();
    }

    static double guessRoughness(String entry) {
        String e = entry.toLowerCase(java.util.Locale.ROOT);
        if (e.contains("glass") || e.contains("ice")) return 0.05;
        if (e.contains("gold") || e.contains("iron") || e.contains("copper") || e.contains("netherite") || e.contains("diamond")) return 0.3;
        if (e.contains("polished") || e.contains("quartz") || e.contains("terracotta_glazed")) return 0.35;
        if (e.contains("wool") || e.contains("dirt") || e.contains("sand") || e.contains("gravel") || e.contains("leaves")) return 0.95;
        return 0.7;
    }

    static double guessMetal(String entry) {
        String e = entry.toLowerCase(java.util.Locale.ROOT);
        return e.contains("_block") && (e.contains("gold") || e.contains("iron") || e.contains("copper") || e.contains("netherite")) ? 1 : 0;
    }

    /** Puts an image from the library into the material as an Image Texture node (copied into the project). */
    void addImageNode(StudioLibrary.Source src, String entry, Point world) {
        try {
            Path pack = StudioLibrary.materialPack(this);
            StudioLibrary.ensurePack(pack);
            String name = StudioLibrary.shortName(entry) + ".png";
            String mask = "imported/" + name;
            Path target = pack.resolve("assets/mosaic/textures").resolve(mask);
            if (!(src.kind() == StudioLibrary.Kind.PROJECT && pack.resolve(entry).normalize().equals(target.normalize()))) {
                Files.createDirectories(target.getParent());
                Files.write(target, StudioLibrary.read(src, entry));
            }
            images.remove(mask);
            MosaicDocument.ENode[] made = new MosaicDocument.ENode[1];
            edit("Add image " + name, () -> {
                MosaicDocument.ENode n = doc.add("mat.image", world.x, world.y);
                n.mask = mask;
                made[0] = n;
            });
            select(made[0]);
        } catch (Exception e) {
            say("Could not import the image: " + e.getMessage(), StudioUi.BAD);
        }
    }

    /** Image Texture > Open image: copies the file into the project and points the node at it (one undo step). */
    void setImage(MosaicDocument.ENode n, java.io.File f) {
        try {
            java.awt.image.BufferedImage img = ImageIO.read(f);
            if (img == null) {
                say(f.getName() + " is not an image Java can read", StudioUi.BAD);
                return;
            }
            Path dir = StudioLibrary.materialPack(this).resolve("assets/mosaic/textures/imported");
            Files.createDirectories(dir);
            StudioLibrary.ensurePack(StudioLibrary.materialPack(this));
            String name = f.getName().replaceAll("(?i)[.](png|jpe?g|bmp|gif)$", "").toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9_.-]", "_") + ".png";
            ImageIO.write(img, "png", dir.resolve(name).toFile());
            String mask = "imported/" + name;
            images.remove(mask);
            edit("Image " + name + " for " + n.id, () -> n.mask = mask);
        } catch (Exception e) {
            say("Could not open the image: " + e.getMessage(), StudioUi.BAD);
        }
    }

    void reloadImage(MosaicDocument.ENode n) {
        if (n.mask == null) return;
        images.remove(n.mask);
        changed();
    }

    /** Imports image files from disk into the project (Library > Import). */
    void importImages(List<java.io.File> files) {
        try {
            Path dir = StudioLibrary.materialPack(this).resolve("assets/mosaic/textures/imported");
            Files.createDirectories(dir);
            StudioLibrary.ensurePack(StudioLibrary.materialPack(this));
            int n = 0;
            for (java.io.File f : files) {
                java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(f);
                if (img == null) continue;
                String name = f.getName().replaceAll("(?i)\\.(png|jpe?g|bmp|gif)$", "").toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9_.-]", "_") + ".png";
                javax.imageio.ImageIO.write(img, "png", dir.resolve(name).toFile());
                n++;
            }
            say("Imported " + n + " image(s) into Project: drag them onto a material", StudioUi.OK);
            fire();
        } catch (Exception e) {
            say("Import failed: " + e.getMessage(), StudioUi.BAD);
        }
    }

    /** Writes the baked maps next to the material and asks the game to reload resources. */
    void writeMaterial() {
        MosaicMaterial.Baked b = baked;
        Path file = doc.file;
        if (b == null || file == null) return;
        String base = file.getFileName().toString().replace(".mosaic.json", "");
        Path dir = file.getParent();
        StudioLibrary.Source src = materialSource;
        String entry = materialEntry;
        Path game = StudioLibrary.gameDir(this);
        slangWorker.execute(() -> {
            try {
                List<String> wrote = new ArrayList<>();
                png(dir.resolve(base + "_s.png"), b.w(), b.h(), b.specular());
                wrote.add("_s");
                if (b.normal() != null) {
                    png(dir.resolve(base + "_n.png"), b.w(), b.h(), b.normal());
                    wrote.add("_n");
                } else Files.deleteIfExists(dir.resolve(base + "_n.png"));
                if (b.albedo() != null) {
                    png(dir.resolve(base + ".png"), b.w(), b.h(), b.albedo());
                    wrote.add("colour");
                    if (src != null && entry != null) try {
                        Files.write(dir.resolve(base + ".png.mcmeta"), StudioLibrary.read(src, entry + ".mcmeta"));
                    } catch (Exception ignored) {
                        // not animated
                    }
                } else {
                    Files.deleteIfExists(dir.resolve(base + ".png"));
                    Files.deleteIfExists(dir.resolve(base + ".png.mcmeta"));
                }
                if (game != null) {
                    Path link = game.resolve("bedray-studio");
                    Files.createDirectories(link);
                    Path tmp = link.resolve("materials.json.tmp");
                    Files.writeString(tmp, "{\"reload\": " + System.currentTimeMillis() + ", \"pack\": \"" + StudioLibrary.MATERIAL_PACK + "\"}",
                        StandardCharsets.UTF_8);
                    Files.move(tmp, link.resolve("materials.json"), java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
                }
                materialStatus = "Baked " + b.w() + "x" + b.h() + " (" + String.join(", ", wrote) + ")"
                    + (game == null ? " - set the game folder (Library) so Minecraft picks it up" : " - Minecraft reloads it");
            } catch (Exception e) {
                materialStatus = "Could not write the material: " + e.getMessage();
            }
            javax.swing.SwingUtilities.invokeLater(this::fire);
        });
    }

    private static void png(Path p, int w, int h, int[] argb) throws java.io.IOException {
        Path tmp = p.resolveSibling(p.getFileName() + ".tmp");
        javax.imageio.ImageIO.write(StudioLibrary.fromArgb(w, h, argb), "png", tmp.toFile());
        Files.move(tmp, p, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    }

    /** After every document change. */
    void changed() {
        docVersion++;
        analyze();
        fire();
        settle.restart();
    }

    void selectionChanged() {
        selectionVersion++;
        fire();
    }

    private void settled() {
        if (activeGraph != null) activeGraph.finishGlide();
        ProjectStore.save(this, activeGraph);
        if (functionDoc()) {
            if (doc.dirty && doc.file != null) save(false);
            checkFunction();
            return;
        }
        if (doc.stage != null) {
            if (doc.dirty && doc.file != null) save(false);
            if (live) syncStageSettings();
            return;
        }
        if (rendererSettings != null && rendererDoc()) {
            if (live && doc.file != null) {
                save(false);
                writeRenderer();
            }
            return;
        }
        if (baked != null) {
            if (live && doc.file != null) {
                save(false);
                writeMaterial();
            }
            return;
        }
        if (generated == null) return;
        compileNow();
    }

    /** Multi-pass graphs (Buffer nodes): the pass plan, and after a compile the SPIR-V of every pass for the preview. */
    io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicPasses.Plan plan;
    String planError;
    volatile List<GpuPreview.PassRun> passRuns;

    void compileNow() {
        if (generated == null) {
            say("Nothing to compile: " + (planError != null ? planError : analysis.firstError()), StudioUi.BAD);
            return;
        }
        MosaicCodegen.Generated g = generated;
        if (worker != null) worker.cancel(true);
        var pl = plan;
        if (pl != null) { // every pass compiles on its own; no cache (it is keyed by one source)
            compiling = true;
            fire();
            worker = new SwingWorker<>() {
                List<GpuPreview.PassRun> runs = new ArrayList<>();

                @Override protected MosaicShaderCheck.Result doInBackground() {
                    MosaicShaderCheck.Result last = null;
                    for (var p : pl.all()) {
                        MosaicGraph pg = MosaicGraph.analyze(p.json()).graph();
                        if (pg == null) return new MosaicShaderCheck.Result(false, 0, 0,
                            List.of(new MosaicShaderCheck.Error(0, "pass " + p.name() + " is not a valid graph", null, null, 0)), "");
                        MosaicShaderCheck.Result r = MosaicShaderCheck.compile(MosaicCodegen.generate(pg, MosaicCodegen.Options.DEFAULT, false));
                        if (!r.ok()) return r;
                        runs.add(new GpuPreview.PassRun(r.spirv(), p.scale(), p.name() == null ? -1 : pl.bindings().get(p.name()),
                            pl.after().contains(p)));
                        last = r;
                    }
                    return last;
                }

                @Override protected void done() {
                    if (isCancelled()) return;
                    compiling = false;
                    try {
                        compile = get();
                    } catch (Exception e) {
                        return;
                    }
                    passRuns = compile.ok() ? List.copyOf(runs) : null;
                    if (compile.ok() && live && doc.dirty && doc.file != null) save(false);
                    fire();
                }
            };
            worker.execute();
            return;
        }
        passRuns = null;
        MosaicShaderCheck.Result hit = ProjectStore.cached(doc, g.source());
        if (hit != null) {
            compile = hit;
            compiling = false;
            if (live && doc.dirty && doc.file != null) save(false);
            fire();
            return;
        }
        compiling = true;
        fire();
        worker = new SwingWorker<>() {
            @Override protected MosaicShaderCheck.Result doInBackground() { return MosaicShaderCheck.compile(g); }

            @Override protected void done() {
                if (isCancelled()) return;
                compiling = false;
                try {
                    compile = get();
                } catch (Exception e) {
                    return;
                }
                ProjectStore.store(doc, g.source(), compile);
                if (compile.ok() && live && doc.dirty && doc.file != null) save(false);
                fire();
            }
        };
        worker.execute();
    }

    /** Problems from the analyzer plus compiler errors mapped to nodes. */
    List<MosaicGraph.Problem> problems() {
        List<MosaicGraph.Problem> out = new ArrayList<>();
        if (doc.nodes.isEmpty()) {
            out.add(new MosaicGraph.Problem(MosaicGraph.Severity.HINT, null, null, workspace == Workspace.MATERIALS
                ? "empty material: double-click a texture in the Library" : workspace == Workspace.RENDERER
                ? "empty renderer graph: wire Scene to Bedray frame (or reopen the Renderer workspace for the default graph)"
                : "empty graph: Space adds a node, Ctrl+N starts from a template",
                MosaicGraph.Fix.NONE));
            return out;
        }
        if (compile != null && !compile.ok() && generated != null)
            for (MosaicShaderCheck.Error e : compile.errors())
                out.add(new MosaicGraph.Problem(MosaicGraph.Severity.ERROR, e.node() != null ? e.node() : nodeOfFunction(e.function()),
                    null, "compiler (" + e.where() + "): " + e.message(), e.function() != null ? MosaicGraph.Fix.EDIT_CODE : MosaicGraph.Fix.NONE));
        if (planError != null)
            out.add(new MosaicGraph.Problem(MosaicGraph.Severity.ERROR, null, null, "passes: " + planError, MosaicGraph.Fix.NONE));
        if (bakeError != null && materialDoc())
            out.add(new MosaicGraph.Problem(MosaicGraph.Severity.WARNING, null, null, "material: " + bakeError, MosaicGraph.Fix.NONE));
        if (baked != null)
            for (String w : baked.warnings())
                out.add(new MosaicGraph.Problem(MosaicGraph.Severity.WARNING, null, null, "material: " + w, MosaicGraph.Fix.NONE));
        out.addAll(analysis.problems());
        return out;
    }

    String nodeOfFunction(String fn) {
        if (fn == null) return null;
        for (MosaicDocument.ENode n : doc.nodes) if (n.type.equals("fn." + fn)) return n.id;
        return null;
    }

    // ================================================================== editing

    /** Runs a mutation as one named undo step. */
    void edit(String label, Runnable mutation) {
        String before = doc.snapshot();
        mutation.run();
        if (doc.commit(before, label)) changed();
        else fire();
    }

    /** For gestures that already mutated the document (drags): records the step if anything changed. */
    void commitGesture(String before, String label) {
        if (before != null && doc.commit(before, label)) changed();
        else fire();
    }

    void undo() {
        if (doc.undo()) afterHistoryJump();
    }

    void redo() {
        if (doc.redo()) afterHistoryJump();
    }

    void jumpTo(int step) {
        doc.jumpTo(step);
        afterHistoryJump();
    }

    private void afterHistoryJump() {
        selection.clear();
        primary = null;
        selectedFrame = null;
        changed();
    }

    // ================================================================== selection

    void select(MosaicDocument.ENode n) {
        selection.clear();
        if (n != null) selection.add(n);
        primary = n;
        selectedFrame = null;
        selectionChanged();
    }

    void toggle(MosaicDocument.ENode n) {
        if (!selection.remove(n)) {
            selection.add(n);
            primary = n;
        } else if (primary == n) {
            primary = selection.isEmpty() ? null : selection.iterator().next();
        }
        selectionChanged();
    }

    void selectAll() {
        selection.clear();
        selection.addAll(doc.nodes);
        primary = doc.nodes.isEmpty() ? null : doc.nodes.get(0);
        selectionChanged();
    }

    List<MosaicDocument.ENode> ordered() {
        List<MosaicDocument.ENode> out = new ArrayList<>();
        for (MosaicDocument.ENode n : doc.nodes) if (selection.contains(n)) out.add(n);
        return out;
    }

    // ================================================================== palette items

    /** A palette entry: a built-in node type or a library function (effects, rendering, shading, own nodes). */
    record Item(String type, String label, String category, Color color, String help, MosaicFunction function) {
        @Override public String toString() { return label; }
    }

    List<Item> items() {
        List<Item> items = new ArrayList<>();
        if (functionDoc()) return functionItems();
        boolean materials = workspace == Workspace.MATERIALS, renderer = workspace == Workspace.RENDERER;
        for (MosaicCatalog.Entry e : MosaicCatalog.all()) {
            boolean stage = e.category() == MosaicCatalog.Category.RENDERER || e.category() == MosaicCatalog.Category.YOURS;
            if (renderer ? !stage : stage) continue;
            if (e.type().equals("bedray.stageMap")) continue; // written by Studio, not added by hand
            if (materials ? !MosaicGraph.MATERIAL_SAFE.contains(e.type()) : e.category() == MosaicCatalog.Category.MATERIAL) continue;
            items.add(new Item(e.type(), e.label(), e.category().label, new Color(e.category().color, true), e.help(), null));
        }
        if (materials || renderer) return items; // effects and code nodes run on the GPU, materials bake on the CPU
        for (MosaicFunction f : MosaicEffects.all())
            items.add(new Item(f.type(), f.label(), f.category(), StudioUi.categoryColor(f.category()), f.help(), f));
        for (MosaicDocument.FDef f : doc.functions)
            if (MosaicEffects.get(f.name) == null)
                items.add(new Item("fn." + f.name, f.label == null ? f.name : f.label, "My nodes", StudioUi.categoryColor("My nodes"),
                    f.signature, f.parsed()));
        return items;
    }

    private List<Item> cachedFunctionItems;

    /** In a Bedray function graph: every function, struct and constant of the pack, the body pieces and the math. */
    List<Item> functionItems() {
        if (cachedFunctionItems != null) return cachedFunctionItems;
        List<Item> items = new ArrayList<>();
        var lib = bedrayLibrary();
        Color fnColor = new Color(0x3D6FB8), structColor = new Color(0x2F8A8E), constColor = new Color(0x5A6170), body = new Color(0x7A5AB8);
        String[][] pieces = {
            {io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicSlangGraph.PARAM, "Parameter", "A parameter of the function (type and name in the inspector)."},
            {io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicSlangGraph.OUT, "Out parameter", "An out parameter: what the function hands back besides its result."},
            {io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicSlangGraph.LET, "Variable", "A named local value (type and name in the inspector)."},
            {io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicSlangGraph.LITERAL, "Value", "A Slang literal or expression: 1.0, 2u, float3(0, 1, 0), PI."},
            {io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicSlangGraph.OP, "Operator", "+ - * / % < > == && || ?: and the others (pick it in the inspector)."},
            {io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicSlangGraph.CALL, "Call", "Any Slang function: normalize, dot, lerp, float3(...), a texture's .SampleLevel."},
            {io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicSlangGraph.MEMBER, "Field / swizzle", ".xyz, .position, .color.rgb of a value."},
            {io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicSlangGraph.SET, "Set field", "A copy of a struct with one field replaced."},
            {io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicSlangGraph.CODE, "Slang block", "Hand-written Slang with the ports you declare (for loops and branches)."}};
        for (String[] p : pieces) items.add(new Item(p[0], p[1], "Function body", body, p[2], null));
        if (lib != null) {
            for (var f : lib.functions().values()) {
                if (!f.isNode()) continue;
                String folder = f.module().contains("/") ? f.module().substring(0, f.module().indexOf('/')) : "pack";
                String sig = f.returnType() + " " + f.label() + "(" + String.join(", ", f.params().stream()
                    .map(p -> (p.direction().equals("in") ? "" : p.direction() + " ") + p.type() + " " + p.name()).toList()) + ")";
                items.add(new Item(io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicSlangGraph.FN + f.id(), f.label() + "   " + f.module(),
                    "Bedray: " + folder, fnColor, sig + (f.comment() == null ? "" : "\n" + f.comment()), null));
            }
            for (var s : lib.structs().values()) {
                if (s.isInterface()) continue;
                items.add(new Item(io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicSlangGraph.MAKE + s.module() + "/" + s.name(), "Make " + s.name(),
                    "Bedray structs", structColor, s.fields().size() + " fields" + (s.comment() == null ? "" : ": " + s.comment()), null));
                items.add(new Item(io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicSlangGraph.BREAK + s.module() + "/" + s.name(), "Break " + s.name(),
                    "Bedray structs", structColor, "Its fields as outputs.", null));
            }
            for (var c : lib.constants().values())
                items.add(new Item(io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicSlangGraph.CONST + c.module() + "/" + c.name(), c.name(),
                    "Bedray constants", constColor, c.type() + " = " + c.value() + (c.comment() == null ? "" : "\n" + c.comment()), null));
        }
        for (String t : List.of("mosaic.add", "mosaic.sub", "mosaic.mul", "mosaic.div", "mosaic.min", "mosaic.max", "mosaic.mix", "mosaic.clamp",
            "mosaic.dot", "mosaic.cross", "mosaic.normalize", "mosaic.length", "mosaic.abs", "mosaic.floor", "mosaic.fract", "mosaic.sqrt",
            "mosaic.saturate", "mosaic.const", "mosaic.vec", "mosaic.swizzle", "mosaic.reroute")) {
            MosaicCatalog.Entry e = MosaicCatalog.of(t);
            items.add(new Item(t, e.label(), "Math", new Color(e.category().color, true), e.help(), null));
        }
        cachedFunctionItems = items;
        return items;
    }

    /** Adds an item at a world point (null: in view); an effect added without a point chains in before the output. */
    MosaicDocument.ENode add(Item it, Point world, Point fallback) {
        MosaicDocument.ENode[] made = new MosaicDocument.ENode[1];
        edit("Add " + it.label(), () -> {
            Point at = world != null ? world : fallback;
            if (it.function() != null) doc.ensureFunction(it.function());
            MosaicDocument.ENode n = doc.add(it.type(), at.x, at.y);
            if (it.type().equals("mosaic.const")) n.value = new double[] {1.0};
            if (it.type().equals("mosaic.swizzle")) n.mask = "xyz";
            if (it.type().equals("mosaic.vec")) n.index = 3;
            switch (it.type()) {
                case io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicSlangGraph.PARAM -> n.mask = "float3 value";
                case io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicSlangGraph.OUT -> n.mask = "float3 result";
                case io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicSlangGraph.LET -> n.mask = "float3 value";
                case io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicSlangGraph.LITERAL -> n.mask = "1.0";
                case io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicSlangGraph.OP -> n.mask = "*";
                case io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicSlangGraph.CALL -> { n.mask = "normalize"; n.index = 1; }
                case io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicSlangGraph.MEMBER -> n.mask = ".xyz";
                case io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicSlangGraph.SET -> n.mask = ".x";
                case io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicSlangGraph.CODE -> n.mask = "//@ in float3 a; inout float3 b; ret void\nb = b + a;";
                default -> { }
            }
            if (it.function() != null && world == null) chainBeforeOutput(n, it.function());
            made[0] = n;
        });
        select(made[0]);
        return made[0];
    }

    private void chainBeforeOutput(MosaicDocument.ENode n, MosaicFunction f) {
        MosaicDocument.ENode out = doc.nodes.stream().filter(x -> x.type.equals("mosaic.output")).findFirst().orElse(null);
        if (out == null || f.output() != MosaicGraph.Kind.V4) return;
        MosaicFunction.Param colorIn = f.params().stream().filter(p -> p.required() && p.kind() == MosaicGraph.Kind.V4).findFirst().orElse(null);
        String src = out.inputs.get("color");
        if (colorIn != null && src != null) n.inputs.put(colorIn.name(), src);
        for (MosaicFunction.Param p : f.params()) {
            if (p.required() && p.kind() == MosaicGraph.Kind.V2 && !n.inputs.containsKey(p.name())) {
                MosaicDocument.ENode uv = doc.nodes.stream().filter(x -> x.type.equals("mosaic.uv")).findFirst()
                    .orElseGet(() -> doc.add("mosaic.uv", n.x - 190, n.y + 100));
                n.inputs.put(p.name(), uv.id);
            }
        }
        out.inputs.put("color", n.id);
        n.x = out.x;
        n.y = out.y;
        out.x += StudioUi.settings.nodeWidth + 30;
    }

    /** Wires src.out into dst.port; refuses only wires that introduce a new error (kind clash, cycle). */
    boolean connect(MosaicDocument.ENode dst, String port, MosaicDocument.ENode src, String out) {
        if (dst == null || src == null || dst == src) return false;
        String errBefore = doc.validate();
        String previous = dst.inputs.get(port);
        String before = doc.snapshot();
        dst.inputs.put(port, out.isEmpty() ? src.id : src.id + "." + out);
        String err = doc.validate();
        if (err != null && !err.equals(errBefore) && !err.contains("is required") && !err.contains("exactly one output")) {
            if (previous == null) dst.inputs.remove(port);
            else dst.inputs.put(port, previous);
            say("Can't connect " + src.id + " -> " + dst.id + "." + port + ": " + err, StudioUi.BAD);
            return false;
        }
        commitGesture(before, "Connect " + src.id + (out.isEmpty() ? "" : "." + out) + " -> " + dst.id + "." + port);
        return true;
    }

    void disconnectSelection() {
        edit("Disconnect inputs", () -> { for (MosaicDocument.ENode n : selection) n.inputs.clear(); });
    }

    void deleteSelection() {
        if (selectedFrame != null && selection.isEmpty()) {
            MosaicDocument.Frame f = selectedFrame;
            edit("Remove frame " + f.title, () -> doc.frames.remove(f));
            selectedFrame = null;
            return;
        }
        if (selection.isEmpty()) return;
        int n = selection.size();
        edit("Delete " + (n == 1 ? primary.id : n + " nodes"), () -> doc.removeAll(new ArrayList<>(selection)));
        selection.clear();
        primary = null;
        selectionChanged();
    }

    void duplicateSelection() {
        if (selection.isEmpty()) return;
        List<MosaicDocument.ENode>[] made = new List[1];
        edit("Duplicate " + selection.size(), () -> made[0] = doc.insertCopies(MosaicDocument.copyOf(ordered()), 30, 30, true));
        selection.clear();
        selection.addAll(made[0]);
        primary = made[0].isEmpty() ? null : made[0].get(0);
        selectionChanged();
    }

    void copySelection() {
        if (selection.isEmpty()) return;
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(MosaicDocument.fragmentJson(ordered(), doc.functions)), null);
        say("Copied " + selection.size() + " node(s) as JSON", StudioUi.MUTED);
    }

    void paste(Point at) {
        try {
            String text = (String) Toolkit.getDefaultToolkit().getSystemClipboard().getData(DataFlavor.stringFlavor);
            List<MosaicDocument.ENode> nodes = MosaicDocument.readFragment(text);
            if (nodes == null) {
                say("The clipboard holds no Mosaic nodes", StudioUi.MUTED);
                return;
            }
            int minX = nodes.stream().mapToInt(n -> n.x).min().orElse(0), minY = nodes.stream().mapToInt(n -> n.y).min().orElse(0);
            List<MosaicDocument.ENode>[] made = new List[1];
            edit("Paste " + nodes.size(), () -> {
                doc.mergeFunctions(text);
                made[0] = doc.insertCopies(nodes, at.x - minX, at.y - minY, false);
            });
            selection.clear();
            selection.addAll(made[0]);
            primary = made[0].isEmpty() ? null : made[0].get(0);
            selectionChanged();
        } catch (Exception ignored) {
            say("The clipboard holds no text", StudioUi.MUTED);
        }
    }

    void arrange(java.util.function.ToIntFunction<MosaicDocument.ENode> height) {
        List<MosaicDocument.ENode> group = selection.size() >= 2 ? ordered() : new ArrayList<>(doc.nodes);
        tidy("Arrange " + group.size() + " nodes", group, () -> MosaicArrange.arrange(group, StudioUi.settings.nodeWidth, height,
            n -> MosaicGraph.portsOf(n.type, analysis.functions()).stream().map(MosaicGraph.Port::name).toList()));
    }

    /** M: mutes the selection, or unmutes it when every selected node is muted already. */
    void toggleMute() {
        List<MosaicDocument.ENode> group = ordered().stream().filter(n -> !MosaicGraph.isOutput(n.type)).toList();
        if (group.isEmpty()) {
            say("Select nodes to mute (the output cannot be muted)", StudioUi.MUTED);
            return;
        }
        boolean mute = group.stream().anyMatch(n -> !n.muted);
        edit((mute ? "Mute " : "Unmute ") + (group.size() == 1 ? group.get(0).id : group.size() + " nodes"), () -> {
            for (MosaicDocument.ENode n : group) n.muted = mute;
        });
    }

    /** Align / distribute the selection: left, top, horizontal spacing, vertical stack. */
    void align(String how, java.util.function.ToIntFunction<MosaicDocument.ENode> height) {
        if (selection.size() < 2) {
            say("Select two or more nodes to align", StudioUi.MUTED);
            return;
        }
        List<MosaicDocument.ENode> group = ordered();
        tidy(switch (how) {
            case "left" -> "Align left";
            case "top" -> "Align top";
            case "row" -> "Distribute in a row";
            default -> "Stack in a column";
        }, group, () -> {
            switch (how) {
                case "left" -> MosaicArrange.alignLeft(group);
                case "top" -> MosaicArrange.alignTop(group);
                case "row" -> {
                    MosaicArrange.alignTop(group);
                    MosaicArrange.distributeHorizontally(group, StudioUi.settings.nodeWidth);
                }
                default -> {
                    MosaicArrange.alignLeft(group);
                    MosaicArrange.distributeVertically(group, height);
                }
            }
        });
    }

    /** One undo step that moves nodes; the canvas glides them from where they were. */
    private void tidy(String label, List<MosaicDocument.ENode> group, Runnable layout) {
        java.util.Map<MosaicDocument.ENode, java.awt.Point> from = new java.util.LinkedHashMap<>();
        for (MosaicDocument.ENode n : group) from.put(n, new java.awt.Point(n.x, n.y));
        edit(label, layout);
        if (activeGraph != null) activeGraph.glide(from);
    }

    void group(java.util.function.ToIntFunction<MosaicDocument.ENode> height) {
        if (selection.isEmpty()) {
            say("Select nodes to group (Ctrl+A selects all)", StudioUi.MUTED);
            return;
        }
        int[] colors = {0x3A6EA5, 0x7A4FA8, 0x2F8F6F, 0xA5683A, 0xA53A5E, 0x5E7A2F};
        MosaicDocument.Frame[] made = new MosaicDocument.Frame[1];
        edit("Group " + selection.size() + " nodes", () -> made[0] = doc.frameAround(ordered(), "Group " + (doc.frames.size() + 1),
            colors[doc.frames.size() % colors.length], n -> StudioUi.settings.nodeWidth, height));
        selectedFrame = made[0];
        selectionChanged();
    }

    void ungroup() {
        MosaicDocument.Frame f = selectedFrame;
        if (f == null) {
            for (MosaicDocument.Frame fr : doc.frames)
                if (primary != null && fr.contains(primary.x, primary.y)) f = fr;
        }
        if (f == null) return;
        MosaicDocument.Frame target = f;
        edit("Remove frame " + f.title, () -> doc.frames.remove(target));
        selectedFrame = null;
        selectionChanged();
    }

    void rename(String text) {
        if (selectedFrame != null && selection.isEmpty()) {
            MosaicDocument.Frame f = selectedFrame;
            edit("Rename frame", () -> f.title = text);
        } else if (primary != null) {
            MosaicDocument.ENode n = primary;
            edit("Label " + n.id, () -> n.label = text == null || text.isBlank() ? null : text);
        }
    }

    void nudge(int dx, int dy) {
        if (selection.isEmpty() && selectedFrame == null) return;
        edit("Move " + (selection.isEmpty() ? "frame" : selection.size() + " node(s)"), () -> {
            for (MosaicDocument.ENode n : selection) {
                n.x += dx;
                n.y += dy;
            }
            if (selectedFrame != null) {
                selectedFrame.x += dx;
                selectedFrame.y += dy;
            }
        });
    }

    void applyCode(MosaicDocument.FDef def, String signature, String body) {
        edit("Edit code of " + def.name, () -> {
            def.signature = signature.trim();
            def.glsl = body;
            if (MosaicEffects.get(def.name) != null) def.category = "My nodes";
        });
    }

    // ================================================================== files

    void loadTemplate(MosaicTemplates.Template t) { replace(t.json(), null, "Template " + t.name()); }

    void replace(String json, Path file, String label) {
        compile = null; // the compile result belonged to the previous document
        edit(label, () -> {
            doc.nodes.clear();
            doc.functions.clear();
            doc.frames.clear();
            try {
                Path tmp = Files.createTempFile("mosaic", ".json");
                Files.writeString(tmp, json, StandardCharsets.UTF_8);
                MosaicDocument loaded = MosaicDocument.load(tmp);
                Files.deleteIfExists(tmp);
                doc.nodes.addAll(loaded.nodes);
                doc.functions.addAll(loaded.functions);
                doc.frames.addAll(loaded.frames);
                doc.paint = loaded.paint;
                doc.bedrayFunction = loaded.bedrayFunction;
                doc.stage = loaded.stage;
            } catch (Exception e) {
                say("Could not read the graph: " + e.getMessage(), StudioUi.BAD);
            }
            if (file != null) doc.file = file;
        });
        selection.clear();
        primary = null;
        selectedFrame = null;
        shots.clear();
        selectionChanged();
    }

    void open(Path p) {
        if (Files.isDirectory(p)) p = p.resolve("shaders").resolve("bedray").resolve("mosaic.json");
        try {
            String json = Files.exists(p) ? Files.readString(p, StandardCharsets.UTF_8) : "{\"nodes\": []}";
            replace(json, p, "Open " + p.getFileName());
            layoutIfUnplaced();
            doc.dirty = false;
            stampFile();
            fire();
        } catch (Exception e) {
            say("Open failed: " + e.getMessage(), StudioUi.BAD);
        }
    }

    boolean save(boolean loud) {
        if (doc.file == null) return false;
        if (activeGraph != null) activeGraph.finishGlide();
        try {
            doc.save();
            stampFile();
            shipSpirv();
            if (loud) say("Saved " + doc.file, StudioUi.OK);
            else fire();
            return true;
        } catch (Exception e) {
            say("Save failed: " + e.getMessage(), StudioUi.BAD);
            return false;
        }
    }

    /** How the game gets the graph's SPIR-V, shown in the status bar. */
    volatile String slangStatus = "";
    private final java.util.concurrent.ExecutorService slangWorker = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "mosaic-slangc");
        t.setDaemon(true);
        return t;
    });

    /**
     * Studio compiles: builds the graph's Slang with slangc and writes mosaic.spv + mosaic.spv.json next to mosaic.json,
     * so the game loads it without compiling (it checks the hash and falls back to its own compile on a mismatch).
     * The SPIR-V of every graph is cached by hash in .studio/cache, so undo / redo and layout-only saves are instant.
     */
    private void shipSpirv() {
        if (StudioUi.settings.slangCompiler != StudioSettings.SlangCompiler.STUDIO || doc.file == null) return;
        MosaicGraph g = analysis.graph();
        if (g == null || g.isMaterial() || g.isRenderer() || g.isPackMap() || g.isFunctionGraph() || doc.bedrayFunction != null) return;
        Path bedray = doc.file.getParent();
        Path cacheDir = bedray.resolve(".studio").resolve("cache");
        // a multi-pass graph ships one pair per pass (mosaic.pass.<buffer>.spv, the final image as mosaic.spv)
        java.util.List<java.util.Map.Entry<MosaicGraph, String>> jobs = new java.util.ArrayList<>();
        if (g.multiPass()) {
            try {
                for (var p : io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicPasses.plan(g).all())
                    jobs.add(java.util.Map.entry(MosaicGraph.parse(p.json()), io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicSlang.prebuiltName(p.name())));
            } catch (RuntimeException e) {
                return;
            }
        } else {
            jobs.add(java.util.Map.entry(g, io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicSlang.PREBUILT));
        }
        slangWorker.execute(() -> {
            try {
                for (var job : jobs) if (!shipOne(job.getKey(), bedray, cacheDir, job.getValue())) return;
            } catch (Exception e) {
                slangStatus = "Slang: " + e.getMessage();
                javax.swing.SwingUtilities.invokeLater(this::fire);
            }
        });
    }

    /** One SPIR-V + sidecar pair next to mosaic.json; false when slangc is missing or refused the graph (status says why). */
    private boolean shipOne(MosaicGraph g, Path bedray, Path cacheDir, String spvName) throws Exception {
        MosaicCodegen.Generated gen = MosaicCodegen.generate(g, io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicSlang.PREBUILT_OPTIONS, true);
        String hash = io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicSlang.sourceHash(gen.source());
        Path info = bedray.resolve(spvName + ".json");
        Path spv = bedray.resolve(spvName);
        if (Files.exists(info) && Files.exists(spv) && hash.equals(
            io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicSlang.sidecarHash(Files.readString(info)))) return true;
        Path cached = cacheDir.resolve(hash + ".slang.spv");
        byte[] bytes;
        long ms;
        if (Files.exists(cached)) {
            bytes = Files.readAllBytes(cached);
            ms = 0;
        } else {
            Path slangc = io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicSlang.locate(StudioUi.settings.slangcPath);
            if (slangc == null) {
                slangStatus = "Slang: slangc not found, the game compiles GLSL (Settings > Compiler)";
                javax.swing.SwingUtilities.invokeLater(this::fire);
                return false;
            }
            MosaicShaderCheck.Result r = io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicSlang.compile(gen, slangc);
            if (!r.ok()) {
                slangStatus = "Slang failed" + (spvName.equals(io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicSlang.PREBUILT) ? "" : " in " + spvName) + " (" + r.errors().get(0).where() + "): " + r.errors().get(0).message();
                javax.swing.SwingUtilities.invokeLater(this::fire);
                return false;
            }
            bytes = r.spirv();
            ms = r.micros() / 1000;
            Files.createDirectories(cacheDir);
            Files.write(cached, bytes);
        }
        // SPIR-V first, sidecar last: the game trusts the pair only once the hash matches
        Path tmp = bedray.resolve(spv.getFileName() + ".tmp");
        Files.write(tmp, bytes);
        Files.move(tmp, spv, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        Path tmpInfo = bedray.resolve(info.getFileName() + ".tmp");
        Files.writeString(tmpInfo, io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicSlang.sidecar(hash,
            new io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicSlang.Result("slangc (Mosaic Studio)", bytes.length)));
        Files.move(tmpInfo, info, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        slangStatus = "Slang: " + bytes.length / 1024 + " KB SPIR-V for the game" + (ms > 0 ? " (" + ms + " ms)" : " (cached)");
        javax.swing.SwingUtilities.invokeLater(this::fire);
        return true;
    }

    // ================================================================== game link (result frames)

    /** Folder the game writes result frames into: next to mosaic.json, in .studio/. */
    Path linkDir() { return doc.file == null ? null : doc.file.getParent().resolve(".studio"); }

    private long materialStamp = -1;

    /** Modification time of the open file as this studio last wrote or read it: anything else is an external edit. */
    private long fileStamp = -1;
    private Path stampedFile;

    void stampFile() {
        try {
            stampedFile = doc.file;
            fileStamp = doc.file != null && Files.exists(doc.file) ? Files.getLastModifiedTime(doc.file).toMillis() : -1;
        } catch (Exception e) {
            fileStamp = -1;
        }
    }

    /** An agent (mosaic command) or another editor changed the open file: take it in as one undoable step. */
    private void pollExternal() {
        try {
            if (doc.file == null || !Files.exists(doc.file)) return;
            if (!doc.file.equals(stampedFile)) {
                stampFile();
                return;
            }
            long m = Files.getLastModifiedTime(doc.file).toMillis();
            if (m == fileStamp) return;
            fileStamp = m;
            String text = Files.readString(doc.file, StandardCharsets.UTF_8);
            if (com.google.gson.JsonParser.parseString(text).equals(com.google.gson.JsonParser.parseString(doc.toJson()))) return;
            java.util.Set<String> selected = new java.util.HashSet<>();
            for (MosaicDocument.ENode n : selection) selected.add(n.id);
            replace(text, doc.file, "External edit (agent)");
            for (MosaicDocument.ENode n : doc.nodes) if (selected.contains(n.id)) selection.add(n);
            doc.dirty = false;
            say("The file changed outside the studio: taken in as one step (Ctrl+Z undoes it)", StudioUi.MUTED);
        } catch (Exception ignored) {
            // being written right now: the next poll reads it
        }
    }

    private void pollGame() {
        pollExternal();
        if (workspace == Workspace.RENDERER) try {
            Path g = StudioLibrary.gameDir(this);
            Path st = g == null ? null : g.resolve("bedray-studio/renderer-status.txt");
            if (st != null && Files.exists(st)) {
                String s = Files.readString(st, StandardCharsets.UTF_8).trim();
                if (!s.equals(rendererGameStatus)) {
                    rendererGameStatus = s;
                    fire();
                }
            }
        } catch (Exception ignored) {
            // being written
        }
        Path game = workspace == Workspace.MATERIALS ? StudioLibrary.gameDir(this) : null;
        if (game != null) try {
            Path st = game.resolve("bedray-studio").resolve("materials-status.txt");
            if (Files.exists(st) && Files.getLastModifiedTime(st).toMillis() != materialStamp) {
                materialStamp = Files.getLastModifiedTime(st).toMillis();
                materialGameStatus = Files.readString(st, StandardCharsets.UTF_8).trim();
                fire();
            }
        } catch (Exception ignored) {
            // the game may be writing it
        }
        Path dir = linkDir();
        if (dir == null) {
            gameStatus = "save the graph into a pack to connect a game";
            return;
        }
        try {
            Path after = dir.resolve("after.png"), status = dir.resolve("status.txt");
            if (Files.exists(status)) gameStatus = Files.readString(status, StandardCharsets.UTF_8).trim();
            if (!Files.exists(after)) return;
            long stamp = Files.getLastModifiedTime(after).toMillis();
            if (stamp == linkStamp) return;
            linkStamp = stamp;
            liveAfter = ImageIO.read(after.toFile());
            Path before = dir.resolve("before.png");
            liveBefore = Files.exists(before) ? ImageIO.read(before.toFile()) : null;
            if (recordDir != null) recordFrame();
            int step = doc.undoSteps().size();
            if (shots.isEmpty() || shots.get(shots.size() - 1).step() != step) {
                List<String> names = doc.undoSteps();
                shots.add(new Shot(step, step == 0 ? "Start" : names.get(step - 1), liveBefore, liveAfter, stamp));
                while (shots.size() > 60) shots.remove(0);
            } else {
                Shot last = shots.remove(shots.size() - 1);
                shots.add(new Shot(last.step(), last.label(), liveBefore, liveAfter, stamp));
            }
            fire();
        } catch (Exception ignored) {
            // the game may be writing the file right now; the next poll reads it
        }
    }

    // ------------------------------------------------------------------ recording the result

    /** What a recording keeps: the frame with the shader, without it, or both side by side. */
    enum RecordWhat { AFTER, BEFORE, SIDE }

    Path recordDir;
    RecordWhat recordWhat = RecordWhat.AFTER;
    int recordFrames;
    long recordStart;

    boolean recording() { return recordDir != null; }

    /** Starts saving every game frame; asks the game for faster, larger frames while it runs. */
    void startRecording(RecordWhat what) {
        Path dir = linkDir();
        if (dir == null) {
            say("Save the graph into a shader pack first: the game sends its frames next to it", StudioUi.MUTED);
            return;
        }
        try {
            recordWhat = what;
            recordDir = dir.resolve("recordings").resolve(java.time.LocalDateTime.now().withNano(0).toString().replace(':', '-'));
            Files.createDirectories(recordDir);
            recordFrames = 0;
            recordStart = System.currentTimeMillis();
            capture(66, 960);
            say("Recording the game's frames... (Result > Stop)", StudioUi.BAD);
        } catch (Exception e) {
            recordDir = null;
            say("Could not start recording: " + e.getMessage(), StudioUi.BAD);
        }
    }

    private void recordFrame() {
        try {
            BufferedImage img = switch (recordWhat) {
                case BEFORE -> liveBefore;
                case SIDE -> {
                    if (liveBefore == null) yield liveAfter;
                    BufferedImage s = new BufferedImage(liveBefore.getWidth() + liveAfter.getWidth(), Math.max(liveBefore.getHeight(), liveAfter.getHeight()),
                        BufferedImage.TYPE_INT_RGB);
                    var g = s.createGraphics();
                    g.drawImage(liveBefore, 0, 0, null);
                    g.drawImage(liveAfter, liveBefore.getWidth(), 0, null);
                    g.dispose();
                    yield s;
                }
                default -> liveAfter;
            };
            if (img == null) return;
            ImageIO.write(img, "png", recordDir.resolve(String.format("frame_%05d.png", ++recordFrames)).toFile());
        } catch (Exception ignored) {
            // a dropped frame
        }
    }

    /** Stops recording: writes an animated GIF (and an MP4 when ffmpeg is installed) next to the frames. */
    void stopRecording() {
        if (recordDir == null) return;
        Path dir = recordDir;
        int frames = recordFrames;
        long ms = Math.max(1, System.currentTimeMillis() - recordStart);
        recordDir = null;
        capture(250, 480);
        if (frames == 0) {
            say("No frames arrived from the game while recording", StudioUi.MUTED);
            return;
        }
        int fps = (int) Math.max(1, Math.min(30, Math.round(frames * 1000.0 / ms)));
        say("Saving " + frames + " frames...", StudioUi.MUTED);
        slangWorker.execute(() -> {
            String result;
            try {
                GifWriter.write(dir, frames, 1000 / fps, dir.resolve("recording.gif"));
                result = "Recorded " + frames + " frames (" + fps + " fps): " + dir.resolve("recording.gif");
                java.util.Optional<Path> ffmpeg = which("ffmpeg");
                if (ffmpeg.isPresent()) {
                    Process p = new ProcessBuilder(ffmpeg.get().toString(), "-y", "-loglevel", "error", "-framerate", String.valueOf(fps), "-i",
                        dir.resolve("frame_%05d.png").toString(), "-vf", "pad=ceil(iw/2)*2:ceil(ih/2)*2", "-pix_fmt", "yuv420p",
                        dir.resolve("recording.mp4").toString()).redirectErrorStream(true).start();
                    p.getInputStream().readAllBytes();
                    if (p.waitFor() == 0) result += " + recording.mp4";
                }
            } catch (Exception e) {
                result = "Frames are in " + dir + " (GIF failed: " + e.getMessage() + ")";
            }
            String r = result;
            javax.swing.SwingUtilities.invokeLater(() -> say(r, StudioUi.OK));
        });
    }

    /** Saves the frame on screen (with the shader) as a PNG in the project. */
    void saveFrame() {
        Path dir = linkDir();
        if (dir == null || liveAfter == null) {
            say("No frame from the game yet", StudioUi.MUTED);
            return;
        }
        try {
            Path shots = dir.resolve("screenshots");
            Files.createDirectories(shots);
            Path f = shots.resolve(java.time.LocalDateTime.now().withNano(0).toString().replace(':', '-') + ".png");
            ImageIO.write(liveAfter, "png", f.toFile());
            say("Saved " + f, StudioUi.OK);
        } catch (Exception e) {
            say("Could not save the frame: " + e.getMessage(), StudioUi.BAD);
        }
    }

    static java.util.Optional<Path> which(String tool) {
        String path = System.getenv("PATH");
        if (path == null) return java.util.Optional.empty();
        for (String dir : path.split(java.io.File.pathSeparator))
            for (String n : new String[] {tool, tool + ".exe"}) {
                Path p = Path.of(dir, n);
                if (Files.isRegularFile(p)) return java.util.Optional.of(p);
            }
        return java.util.Optional.empty();
    }

    /** Asks the game for result frames every {@code ms} at up to {@code width} pixels. */
    private void capture(int ms, int width) {
        Path dir = linkDir();
        if (dir == null) return;
        try {
            Path f = dir.resolve("control.json");
            com.google.gson.JsonObject o = Files.exists(f)
                ? com.google.gson.JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)).getAsJsonObject() : new com.google.gson.JsonObject();
            o.addProperty("captureMs", ms);
            o.addProperty("captureWidth", width);
            Path tmp = dir.resolve("control.json.tmp");
            Files.writeString(tmp, o.toString(), StandardCharsets.UTF_8);
            Files.move(tmp, f, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception ignored) {
            // the game keeps its default rate
        }
    }

    /** Node the game shows instead of the output (debug preview), or null. */
    String gamePreview;
    boolean gameNanCheck;

    /** Writes the studio's commands for the game (keeps keys it does not own, e.g. disabledAddons). */
    void writeControl() {
        Path dir = linkDir();
        if (dir == null) {
            say("Save the graph into a pack first: the game reads commands next to it", StudioUi.MUTED);
            return;
        }
        try {
            Files.createDirectories(dir);
            Path f = dir.resolve("control.json");
            com.google.gson.JsonObject o = Files.exists(f)
                ? com.google.gson.JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)).getAsJsonObject() : new com.google.gson.JsonObject();
            if (gamePreview == null) o.remove("previewNode");
            else o.addProperty("previewNode", gamePreview);
            o.addProperty("nanCheck", gameNanCheck);
            Path tmp = dir.resolve("control.json.tmp");
            Files.writeString(tmp, o.toString(), StandardCharsets.UTF_8);
            Files.move(tmp, f, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            say("Could not write the game commands: " + e.getMessage(), StudioUi.BAD);
        }
    }

    /** Shows one node's value in the game instead of the output (null stops). */
    void previewInGame(MosaicDocument.ENode n) {
        gamePreview = n == null ? null : n.id;
        writeControl();
        say(n == null ? "Game shows the output again" : "Game shows node " + n.id + " (stop: Node > Stop preview in game)", StudioUi.MUTED);
    }

    /** Result frame recorded for a history step, if any. */
    Shot shotFor(int step) {
        for (int i = shots.size() - 1; i >= 0; i--) if (shots.get(i).step() == step) return shots.get(i);
        return null;
    }

    void dispose() {
        linkPoll.stop();
        settle.stop();
    }

    Map<String, MosaicFunction> functions() { return doc.functionMap(); }
}
