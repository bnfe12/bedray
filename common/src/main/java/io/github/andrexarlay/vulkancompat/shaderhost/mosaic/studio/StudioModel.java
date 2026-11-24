package io.github.andrexarlay.vulkancompat.shaderhost.mosaic.studio;

import io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicArrange;
import io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicCatalog;
import io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicCodegen;
import io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicDocument;
import io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicEffects;
import io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicFunction;
import io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicGraph;
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
        generated = analysis.ok() ? MosaicCodegen.generate(analysis.graph(), MosaicCodegen.Options.DEFAULT, false) : null;
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
        for (MosaicCatalog.Entry e : MosaicCatalog.all())
            items.add(new Item(e.type(), e.label(), e.category().label, new Color(e.category().color, true), e.help(), null));
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
        if (g == null) return;
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

    private void pollGame() {
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
