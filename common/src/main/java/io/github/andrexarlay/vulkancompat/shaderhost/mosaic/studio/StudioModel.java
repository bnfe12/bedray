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
        if (g != null && g.isMaterial()) {
            generated = null;
            bake(g);
        } else {
            baked = null;
            bakeError = null;
            generated = analysis.ok() ? MosaicCodegen.generate(g, MosaicCodegen.Options.DEFAULT, false) : null;
        }
    }

    // ================================================================== workspaces and materials

    /** Shaders: the frame's shader graph. Materials: per-texture surfaces baked into a resource pack. */
    enum Workspace { SHADERS, MATERIALS }

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

    private void bake(MosaicGraph g) {
        bakeVersion++;
        if (baseImage == null) {
            baked = null;
            bakeError = "pick the texture this material is for: double-click it in the Library";
            return;
        }
        for (MosaicGraph.Node n : g.nodes())
            if (n.type().equals("mat.image") && n.mask() != null && !images.containsKey(n.mask())) loadImage(n.mask());
        try {
            baked = MosaicMaterial.bake(g, baseImage, images);
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

    void compileNow() {
        if (generated == null) {
            say("Nothing to compile: " + analysis.firstError(), StudioUi.BAD);
            return;
        }
        MosaicCodegen.Generated g = generated;
        if (worker != null) worker.cancel(true);
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
        if (compile != null && !compile.ok() && generated != null)
            for (MosaicShaderCheck.Error e : compile.errors())
                out.add(new MosaicGraph.Problem(MosaicGraph.Severity.ERROR, e.node() != null ? e.node() : nodeOfFunction(e.function()),
                    null, "compiler (" + e.where() + "): " + e.message(), e.function() != null ? MosaicGraph.Fix.EDIT_CODE : MosaicGraph.Fix.NONE));
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
        boolean materials = workspace == Workspace.MATERIALS;
        for (MosaicCatalog.Entry e : MosaicCatalog.all()) {
            if (materials ? !MosaicGraph.MATERIAL_SAFE.contains(e.type()) : e.category() == MosaicCatalog.Category.MATERIAL) continue;
            items.add(new Item(e.type(), e.label(), e.category().label, new Color(e.category().color, true), e.help(), null));
        }
        if (materials) return items; // effects and code nodes run on the GPU, materials bake on the CPU
        for (MosaicFunction f : MosaicEffects.all())
            items.add(new Item(f.type(), f.label(), f.category(), StudioUi.categoryColor(f.category()), f.help(), f));
        for (MosaicDocument.FDef f : doc.functions)
            if (MosaicEffects.get(f.name) == null)
                items.add(new Item("fn." + f.name, f.label == null ? f.name : f.label, "My nodes", StudioUi.categoryColor("My nodes"),
                    f.signature, f.parsed()));
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
            doc.dirty = false;
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
        if (g == null || g.isMaterial()) return;
        Path bedray = doc.file.getParent();
        Path cacheDir = bedray.resolve(".studio").resolve("cache");
        slangWorker.execute(() -> {
            try {
                MosaicCodegen.Generated gen = MosaicCodegen.generate(g, io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicSlang.PREBUILT_OPTIONS, true);
                String hash = io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicSlang.sourceHash(gen.source());
                Path info = bedray.resolve(io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicSlang.PREBUILT_INFO);
                Path spv = bedray.resolve(io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicSlang.PREBUILT);
                if (Files.exists(info) && Files.exists(spv) && hash.equals(
                    io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicSlang.sidecarHash(Files.readString(info)))) return;
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
                        return;
                    }
                    MosaicShaderCheck.Result r = io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicSlang.compile(gen, slangc);
                    if (!r.ok()) {
                        slangStatus = "Slang failed (" + r.errors().get(0).where() + "): " + r.errors().get(0).message();
                        javax.swing.SwingUtilities.invokeLater(this::fire);
                        return;
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
            } catch (Exception e) {
                slangStatus = "Slang: " + e.getMessage();
                javax.swing.SwingUtilities.invokeLater(this::fire);
            }
        });
    }

    // ================================================================== game link (result frames)

    /** Folder the game writes result frames into: next to mosaic.json, in .studio/. */
    Path linkDir() { return doc.file == null ? null : doc.file.getParent().resolve(".studio"); }

    private long materialStamp = -1;

    private void pollGame() {
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
