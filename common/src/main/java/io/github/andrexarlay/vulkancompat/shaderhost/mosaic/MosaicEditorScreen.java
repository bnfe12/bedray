package io.github.andrexarlay.vulkancompat.shaderhost.mosaic;

import io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicGraph.Kind;
import io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicLayout.Rect;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.mojang.blaze3d.platform.InputConstants.KEY_DOWN;
import static com.mojang.blaze3d.platform.InputConstants.KEY_UP;

/**
 * The Mosaic node editor, laid out as fixed, non-overlapping regions ({@link MosaicLayout}): a tool bar on top, the
 * node palette on the left, the canvas in the middle, the inspector on the right and a status line at the bottom.
 * Every click, scroll and key belongs to exactly one region (or to the one popup/dialog that is open), so panels
 * never fight over input and the canvas never draws under a panel.
 * <p>
 * Adding nodes: click or drag a palette entry; double-click, right-click or press Space on the canvas for a search
 * popup at the cursor; drop a dragged wire on empty canvas to add a node that is wired up immediately. Wiring: drag
 * from an output to an input (or the other way), drop a wire on a node body to use its first free fitting port,
 * right-click an input to disconnect it. Parameters edit live in the inspector. With Live on, every valid change is
 * written to the pack's mosaic.json and the running stage hot-reloads it; Preview makes the canvas see-through so the
 * world behind shows the result.
 */
public final class MosaicEditorScreen extends Screen {

    // ---------------------------------------------------------------- canvas geometry (world units, scaled by zoom)
    private static final int NODE_W = 124, HEAD_H = 14, ROW_H = 11, SOCKET = 3;
    private static final double ZOOM_MIN = 0.35, ZOOM_MAX = 2.0;

    // ---------------------------------------------------------------- colors
    private static final int COL_SCREEN = 0xFF0E1116, COL_CANVAS = 0xF0121620, COL_CANVAS_PREVIEW = 0x30121620;
    private static final int COL_GRID = 0x18304058, COL_GRID_MAJOR = 0x2A4A6080;
    private static final int COL_PANEL = 0xFA171C25, COL_PANEL_EDGE = 0xFF2B3344, COL_BAR = 0xFA1C2230;
    private static final int COL_TEXT = 0xFFE8ECF4, COL_MUTED = 0xFF8B93A8, COL_FAINT = 0xFF5D6478;
    private static final int COL_NODE = 0xF01A1F2A, COL_NODE_EDGE = 0xFF3A4258, COL_SELECT = 0xFFFFFFFF;
    private static final int COL_HOVER = 0xFF8FA3C8, COL_OK = 0xFF6FD2A0, COL_BAD = 0xFFFF7772, COL_WARN = 0xFFFFC266;
    private static final int COL_ROW_HOT = 0x2A6FA0FF, COL_CHIP = 0xFF263046, COL_CHIP_HOT = 0xFF34436A;
    private static final int COL_ANY = 0xFFC8CCD8;

    private static int kindColor(Kind k) {
        if (k == null) return COL_ANY;
        return switch (k) {
            case F -> 0xFFA6AEBF;
            case V2 -> 0xFF6FD2A0;
            case V3 -> 0xFFE0B45C;
            case V4 -> 0xFFB48CF0;
            case S -> 0xFF5CC8E0; // a Slang value of Bedray's pack (only its own type connects)
        };
    }

    private static String kindName(Kind k) {
        if (k == null) return "any";
        return switch (k) { case F -> "float"; case V2 -> "vec2"; case V3 -> "vec3"; case V4 -> "vec4"; case S -> "slang"; };
    }

    private static final Pattern NODE_IN_ERROR = Pattern.compile("node (\\w+)");

    // ---------------------------------------------------------------- state
    private final Screen parent;
    private final Supplier<Path> saveTarget;
    private final MosaicDocument doc;

    private MosaicLayout layout;
    private boolean compact;
    private boolean wantPalette = true, wantInspector = true, preview, live = true;
    private double panX, panY, zoom = 1.0;
    private boolean viewPlaced;

    private MosaicGraph graph, lastGood; // current parse (null when invalid) and the last valid one for socket kinds
    private String error, errorNode;
    private String flash;
    private int flashColor;
    private long flashUntil, lastEdit;
    private boolean closeArmed;

    private enum Drag { NONE, NODE, PAN, BOX, WIRE_FROM_OUTPUT, WIRE_FROM_INPUT, PALETTE }

    private Drag drag = Drag.NONE;
    /** The primary selection (shown in the inspector) and the whole selection it belongs to. */
    private MosaicDocument.ENode selected, dragNode;
    private final java.util.Set<MosaicDocument.ENode> selection = new java.util.LinkedHashSet<>();
    private final java.util.Map<MosaicDocument.ENode, int[]> dragStarts = new java.util.HashMap<>();
    private double pressWX, pressWY, boxX0, boxY0, boxX1, boxY1;
    private boolean boxAdditive, rightClickPending;
    private boolean codeSlang;
    private String codeCache;
    private MosaicGraph codeFor;
    private boolean codeForSlang;
    private double dragOffX, dragOffY, dragStartX, dragStartY, panStartX, panStartY;
    private boolean dragMoved;
    private MosaicDocument.ENode wireNode;   // fixed end of the wire being dragged
    private String wirePort;                 // output name (from-output) or input port (from-input)
    private String gestureBefore;            // history snapshot of a running gesture
    private String paletteDragType;
    private double mouseX, mouseY;

    private int paletteScroll;

    /** A palette group header: a category, or the Favorites / Recent groups. */
    private record Group(String id, String label, int color) {}

    private static final int COL_FAVORITE = 0xFFE8C455;

    private boolean quickOpen;
    private int quickX, quickY, quickSel;
    private double quickWorldX, quickWorldY;
    private MosaicDocument.ENode pendingFrom, pendingTo; // auto-wire the node the quick popup adds
    private String pendingFromOut, pendingToPort;

    private boolean saveDialog;
    private String paramBefore;
    private boolean syncingBoxes;

    private Button paletteButton, settingsButton, helpButton, codeButton, undoButton, redoButton, fitButton, previewButton, liveButton,
        applyButton, doneButton;
    private int titleLeft, titleRight;

    // editor settings (shortcuts, grid, favorites...) shared by every graph
    private MosaicPrefs prefs;
    private Path prefsFile;

    /** Floating windows: Keys and Settings are movable and stay open beside the canvas; Context is a menu. */
    private enum Win { NONE, KEYS, SETTINGS, CODE, CONTEXT }

    private Win win = Win.NONE;
    private int winX = Integer.MIN_VALUE, winY, winScroll;
    private boolean winDrag;
    private double winDragDX, winDragDY;
    private MosaicKeymap.Action rebinding;
    private int rebindIndex;
    private MosaicDocument.ENode contextNode;
    private int ctxX, ctxY;
    private String clipboardText;
    private Button packSave, packCancel;
    private EditBox searchBox, valueBox, maskBox, quickBox, packBox;

    /** A clickable panel element recorded while drawing; clicks and tooltips use the same rectangles. */
    private record Hot(Rect rect, Runnable action, String tooltip) {}

    private final List<Hot> hots = new ArrayList<>();

    public MosaicEditorScreen(Screen parent, Supplier<Path> saveTarget, MosaicDocument document) {
        super(Component.literal("Mosaic node editor"));
        this.parent = parent;
        this.saveTarget = saveTarget;
        this.doc = document;
        revalidate();
    }

    /** Convenience opener: loads the pack's mosaic.json or starts a minimal color pass. */
    public static MosaicEditorScreen open(Screen parent, Supplier<Path> target) {
        return new MosaicEditorScreen(parent, target, seedDocument(target));
    }

    public static MosaicDocument seedDocument(Supplier<Path> target) {
        Path p = target.get();
        if (p != null && Files.isRegularFile(p)) {
            try {
                return MosaicDocument.load(p);
            } catch (Exception e) {
                MosaicDocument broken = new MosaicDocument();
                broken.file = p;
                broken.dirty = true;
                return broken;
            }
        }
        MosaicDocument d = new MosaicDocument();
        d.file = p;
        d.dirty = p == null;
        MosaicDocument.ENode color = d.add("mosaic.sceneColor", 0, 0);
        MosaicDocument.ENode out = d.add("mosaic.output", 200, 10);
        out.inputs.put("color", color.id);
        return d;
    }

    // =================================================================== lifecycle

    @Override protected void init() {
        if (prefs == null) {
            prefsFile = MosaicPrefs.defaultFile(minecraft.gameDirectory.toPath());
            prefs = MosaicPrefs.load(prefsFile);
            wantPalette = prefs.showPalette;
            wantInspector = prefs.showInspector;
            live = prefs.liveByDefault;
            preview = prefs.previewByDefault;
        }
        layout = MosaicLayout.of(width, height, wantPalette, wantInspector);
        String search = searchBox == null ? "" : searchBox.getValue();
        String pack = packBox == null ? "" : packBox.getValue();
        String quick = quickBox == null ? "" : quickBox.getValue();

        compact = width < 560;
        int h = 16;
        paletteButton = toolButton("Nodes", compact ? 36 : 44, "Show or hide the node palette",
            MosaicKeymap.Action.TOGGLE_PALETTE, this::togglePalette);
        settingsButton = toolButton(compact ? "Opts" : "Settings", compact ? 30 : 48, "Editor settings",
            MosaicKeymap.Action.SETTINGS, () -> toggleWindow(Win.SETTINGS));
        helpButton = toolButton("?", 16, "All shortcuts, rebind keys", MosaicKeymap.Action.HELP,
            () -> toggleWindow(Win.KEYS));
        codeButton = toolButton("Code", compact ? 30 : 34, "Generated shader source", MosaicKeymap.Action.CODE,
            () -> toggleWindow(Win.CODE));
        undoButton = toolButton("Undo", compact ? 30 : 36, "Undo", MosaicKeymap.Action.UNDO, () -> undoRedo(false));
        redoButton = toolButton("Redo", compact ? 30 : 36, "Redo", MosaicKeymap.Action.REDO, () -> undoRedo(true));
        fitButton = toolButton("Fit", 26, "Show the whole graph", MosaicKeymap.Action.FIT, this::fitView);
        previewButton = toolButton("", compact ? 34 : 64, "See-through canvas: watch the world behind while editing",
            MosaicKeymap.Action.TOGGLE_PREVIEW, () -> run(MosaicKeymap.Action.TOGGLE_PREVIEW));
        liveButton = toolButton("", compact ? 32 : 54, "Live: save every valid change so the running stage hot-reloads it",
            MosaicKeymap.Action.TOGGLE_LIVE, () -> run(MosaicKeymap.Action.TOGGLE_LIVE));
        applyButton = toolButton("Apply", compact ? 36 : 44, "Save mosaic.json now", MosaicKeymap.Action.APPLY,
            this::apply);
        doneButton = toolButton("Done", compact ? 34 : 40, "Close the editor (Esc)", null, this::requestClose);
        layoutTopBar(h);

        searchBox = box(search, "Search nodes  (Ctrl+F)", 40);
        searchBox.setResponder(s -> paletteScroll = 0);
        valueBox = box("", "1..4 numbers", 80);
        valueBox.setResponder(this::liveValue);
        maskBox = box("", "xyzw / rgba", 4);
        maskBox.setResponder(this::liveMask);
        quickBox = box(quick, "Type to search...", 40);
        quickBox.setResponder(s -> quickSel = 0);
        packBox = box(pack.isEmpty() ? "mosaic-pack" : pack, "folder name", 40);
        packSave = Button.builder(Component.literal("Save"), b -> saveAsPack()).bounds(0, 0, 60, 18).build();
        packCancel = Button.builder(Component.literal("Cancel"), b -> closeSaveDialog()).bounds(0, 0, 60, 18).build();
        addWidget(packSave);
        addWidget(packCancel);

        if (!viewPlaced) {
            fitView();
            viewPlaced = true;
        }
        syncInspectorBoxes();
    }

    private Button toolButton(String label, int w, String tip, MosaicKeymap.Action shortcut, Runnable action) {
        String keys = shortcut == null ? "" : shortcutText(shortcut);
        Button b = Button.builder(Component.literal(label), btn -> action.run())
            .bounds(0, 3, w, 16).tooltip(Tooltip.create(Component.literal(keys.isEmpty() ? tip : tip + "  (" + keys + ")")))
            .build();
        addWidget(b);
        return b;
    }

    /**
     * Places the tool bar: palette / settings / help on the left, history, view and save on the right. When the
     * screen is too narrow the optional buttons (Fit, Preview, help) give way first; their keys keep working.
     */
    private void layoutTopBar(int h) {
        List<Button> left = List.of(paletteButton, settingsButton, helpButton, codeButton);
        List<Button> right = List.of(doneButton, applyButton, liveButton, previewButton, fitButton, redoButton, undoButton);
        Button[] optional = {codeButton, fitButton, previewButton, helpButton};
        for (int drop = 0; ; drop++) {
            int need = 8;
            for (Button b : left) if (b.visible) need += b.getWidth() + 2;
            for (Button b : right) if (b.visible) need += b.getWidth() + 2;
            need += 12 + 40; // group gaps and a little title room
            if (need <= width || drop == optional.length) break;
            optional[drop].visible = false;
        }
        int x = 4;
        for (Button b : left) {
            if (!b.visible) continue;
            b.setPosition(x, 3);
            x += b.getWidth() + 2;
        }
        titleLeft = x + 6;
        x = width - 4;
        for (Button b : right) {
            if (!b.visible) continue;
            x -= b.getWidth();
            b.setPosition(x, 3);
            x -= (b == applyButton || b == previewButton) ? 6 : 2;
        }
        titleRight = x - 6;
    }

    /** The shortcuts of an action as text ("Ctrl+Y / Ctrl+Shift+Z"), empty when unbound. */
    private String shortcutText(MosaicKeymap.Action a) {
        if (prefs == null) return "";
        List<String> parts = new ArrayList<>();
        for (MosaicKeymap.Combo c : prefs.keys.get(a)) parts.add(MosaicKeymap.format(c));
        return String.join(" / ", parts);
    }

    private String firstShortcut(MosaicKeymap.Action a) {
        List<MosaicKeymap.Combo> c = prefs.keys.get(a);
        return c.isEmpty() ? "" : MosaicKeymap.format(c.get(0));
    }

    private void savePrefs() {
        prefs.showPalette = wantPalette;
        prefs.showInspector = wantInspector;
        try {
            prefs.save(prefsFile);
        } catch (Exception e) {
            flash("could not save editor settings: " + e.getMessage(), COL_BAD);
        }
    }

    private EditBox box(String value, String hint, int max) {
        EditBox b = new EditBox(font, 0, 0, 10, 14, Component.literal(hint));
        b.setMaxLength(max);
        b.setHint(Component.literal(hint).withColor(COL_FAINT));
        b.setValue(value);
        b.visible = false;
        addWidget(b);
        return b;
    }

    @Override public void tick() {
        super.tick();
        // Live: write a valid graph shortly after the last edit, never in the middle of a drag or while typing
        if (live && doc.dirty && graph != null && doc.file != null && drag == Drag.NONE
            && System.currentTimeMillis() - lastEdit > 400) {
            try {
                doc.save();
            } catch (Exception e) {
                flash("live save failed: " + e.getMessage(), COL_BAD);
                live = false;
            }
        }
    }

    @Override public void onClose() {
        finishParamEdit();
        if (prefs != null) savePrefs();
        minecraft.gui.setScreen(parent);
    }

    @Override public boolean isPauseScreen() { return false; }

    /** No vanilla blur or dirt: the editor paints its own background (see-through in Preview). */
    @Override public void extractBackground(GuiGraphicsExtractor g, int mx, int my, float delta) { }

    // =================================================================== document helpers

    private void revalidate() {
        try {
            graph = MosaicGraph.parse(doc.toJson());
            lastGood = graph;
            error = null;
            errorNode = null;
        } catch (IllegalArgumentException e) {
            graph = null;
            error = String.valueOf(e.getMessage());
            Matcher m = NODE_IN_ERROR.matcher(error);
            errorNode = m.find() && doc.byId(m.group(1)) != null ? m.group(1) : null;
        }
    }

    /** Called after every document change: validation, Live timer, close guard. */
    private void changed() {
        revalidate();
        lastEdit = System.currentTimeMillis();
        closeArmed = false;
    }

    private void flash(String message, int color) {
        flash = message;
        flashColor = color;
        flashUntil = System.currentTimeMillis() + 4000;
    }

    /** Selects exactly one node (or nothing). */
    private void select(MosaicDocument.ENode n) {
        if (n == selected && selection.size() == (n == null ? 0 : 1)) return;
        finishParamEdit();
        selected = n;
        selection.clear();
        if (n != null) selection.add(n);
        syncInspectorBoxes();
    }

    /** Shift/Ctrl+click: adds a node to the selection or takes it out. */
    private void toggleSelect(MosaicDocument.ENode n) {
        finishParamEdit();
        if (selection.remove(n)) {
            if (selected == n) selected = selection.isEmpty() ? null : selection.iterator().next();
        } else {
            selection.add(n);
            selected = n;
        }
        syncInspectorBoxes();
    }

    private void selectGroup(java.util.Collection<MosaicDocument.ENode> nodes, boolean additive) {
        finishParamEdit();
        if (!additive) selection.clear();
        selection.addAll(nodes);
        selected = nodes.isEmpty() ? (selection.isEmpty() ? null : selected) : nodes.iterator().next();
        if (selected != null && !selection.contains(selected)) selected = null;
        syncInspectorBoxes();
    }

    /** The selected nodes in document (draw) order. */
    private List<MosaicDocument.ENode> group() {
        List<MosaicDocument.ENode> out = new ArrayList<>();
        for (MosaicDocument.ENode n : doc.nodes) if (selection.contains(n)) out.add(n);
        return out;
    }

    private String groupLabel() {
        int n = selection.size();
        return n == 1 && selected != null ? selected.id : n + " nodes";
    }

    private MosaicDocument.ENode addNodeAt(String type, double wx, double wy) {
        finishParamEdit();
        String before = doc.snapshot();
        MosaicDocument.ENode n = doc.add(type, (int) Math.round(wx), (int) Math.round(wy));
        if (type.equals("mosaic.const")) n.value = new double[] {1.0};
        if (type.equals("mosaic.swizzle")) n.mask = "xyz";
        doc.commit(before);
        prefs.used(type);
        changed();
        select(n);
        return n;
    }

    /** A free spot near the middle of the visible canvas, stepping down until no node sits there. */
    private double[] freeSpotInView() {
        Rect c = layout.canvas();
        double wx = toWorldX(c.x() + c.w() / 2.0) - NODE_W / 2.0, wy = toWorldY(c.y() + c.h() / 3.0);
        for (int i = 0; i < 12; i++) {
            boolean taken = false;
            for (MosaicDocument.ENode n : doc.nodes)
                if (Math.abs(n.x - wx) < NODE_W * 0.6 && Math.abs(n.y - wy) < 24) taken = true;
            if (!taken) break;
            wy += 30;
            wx += 12;
        }
        return new double[] {wx, wy};
    }

    private void deleteSelected() {
        if (selection.isEmpty()) return;
        finishParamEdit();
        String before = doc.snapshot();
        String what = groupLabel();
        doc.removeAll(group());
        doc.commit(before);
        selection.clear();
        selected = null;
        changed();
        syncInspectorBoxes();
        flash("deleted " + what + " - " + firstShortcut(MosaicKeymap.Action.UNDO) + " brings it back", COL_MUTED);
    }

    /** Duplicates the selection next to itself; wires inside the group follow, wires from outside are kept. */
    private void duplicateSelected() {
        if (selection.isEmpty()) return;
        finishParamEdit();
        String before = doc.snapshot();
        List<MosaicDocument.ENode> copies = doc.insertCopies(MosaicDocument.copyOf(group()), 20, 20, true);
        doc.commit(before);
        changed();
        selectGroup(copies, false);
    }

    /** Arrange: the selection when two or more nodes are selected, else the whole graph. */
    private void arrange() {
        boolean all = selection.size() < 2;
        List<MosaicDocument.ENode> nodes = all ? new ArrayList<>(doc.nodes) : group();
        if (nodes.isEmpty()) return;
        String before = doc.snapshot();
        MosaicArrange.arrange(nodes, NODE_W, MosaicEditorScreen::nodeHeight);
        if (doc.commit(before)) changed();
        if (all) fitView();
        flash("arranged " + (all ? "the graph" : nodes.size() + " nodes") + " by dependency", COL_MUTED);
    }

    private void align(boolean left) {
        if (selection.size() < 2) return;
        String before = doc.snapshot();
        if (left) MosaicArrange.alignLeft(group());
        else MosaicArrange.alignTop(group());
        if (doc.commit(before)) changed();
    }

    /** Arrow keys: nudge the selection by one unit, or by a grid step with Shift. */
    private void nudge(int dx, int dy, boolean big) {
        if (selection.isEmpty()) return;
        int step = big ? prefs.grid : 1;
        String before = doc.snapshot();
        for (MosaicDocument.ENode n : selection) {
            n.x += dx * step;
            n.y += dy * step;
        }
        if (doc.commit(before)) changed();
    }

    private void disconnect(MosaicDocument.ENode n, String port) {
        if (!n.inputs.containsKey(port)) return;
        String before = doc.snapshot();
        n.inputs.remove(port);
        doc.commit(before);
        changed();
    }

    /**
     * Wires src's output into dst's input. Refused wires are rolled back with the parser's reason; inside a running
     * gesture the gesture records history once at the end.
     */
    private boolean connect(MosaicDocument.ENode dst, String port, MosaicDocument.ENode src, String out, boolean quiet) {
        if (dst == null || src == null || dst == src) return false;
        String ref = out.isEmpty() ? src.id : src.id + "." + out;
        if (ref.equals(dst.inputs.get(port))) return true;
        // a socket that insists on one kind refuses another right away, with a readable reason
        Kind want = MosaicGraph.acceptedKind(dst.type, port), have = outputKind(src, out);
        if (want != null && have != null && want != have) {
            if (!quiet) flash("can't connect: " + port + " takes " + kindName(want) + ", " + ref + " is "
                + kindName(have), COL_BAD);
            return false;
        }
        String before = doc.snapshot();
        String errorBefore = doc.validate();
        String previous = dst.inputs.get(port);
        dst.inputs.put(port, ref);
        String problem = doc.validate();
        // Graphs under construction always have empty required ports somewhere: those never block wiring. Only a
        // new error that this wire introduces (kind clash, cycle) rolls it back.
        boolean causedHere = problem != null && !problem.equals(errorBefore) && !isIncomplete(problem);
        if (causedHere) {
            if (previous == null) dst.inputs.remove(port);
            else dst.inputs.put(port, previous);
            if (!quiet) flash("can't connect: " + problem, COL_BAD);
            return false;
        }
        if (gestureBefore == null) doc.commit(before);
        else doc.dirty = true;
        changed();
        return true;
    }

    /** Drops a wire onto a node body: the first free input that accepts it, else the first input that does. */
    private boolean connectToBody(MosaicDocument.ENode dst, MosaicDocument.ENode src, String out) {
        List<String> ports = inputsOf(dst);
        for (boolean freeOnly : new boolean[] {true, false})
            for (String p : ports)
                if ((!freeOnly || !dst.inputs.containsKey(p)) && connect(dst, p, src, out, true)) return true;
        flash("no input of " + dst.id + " accepts " + src.id, COL_BAD);
        return false;
    }

    private void undoRedo(boolean redo) {
        finishParamEdit();
        if (!(redo ? doc.redo() : doc.undo())) {
            flash(redo ? "nothing to redo" : "nothing to undo", COL_MUTED);
            return;
        }
        selected = null; // history replaced every node object
        selection.clear();
        changed();
        syncInspectorBoxes();
    }

    private void apply() {
        finishParamEdit();
        if (graph == null) {
            flash("not saved, the graph has an error: " + error, COL_BAD);
            return;
        }
        Path target = doc.file != null ? doc.file : saveTarget.get();
        if (target == null) {
            openSaveDialog();
            return;
        }
        try {
            doc.file = target;
            doc.save();
            closeArmed = false;
            flash("saved " + target.getFileName() + " - the running stage reloads it within 0.5 s", COL_OK);
        } catch (Exception e) {
            flash("save failed: " + e.getMessage(), COL_BAD);
        }
    }

    private void requestClose() {
        if (saveDialog) {
            closeSaveDialog();
            return;
        }
        if (doc.dirty && !closeArmed) {
            closeArmed = true;
            flash("unsaved changes: press Done / Esc again to discard them, or Apply to save", COL_WARN);
            return;
        }
        onClose();
    }

    private void togglePalette() {
        if (wantPalette && !layout.paletteShown()) wantInspector = false; // no room: the palette takes the inspector's
        else wantPalette = !wantPalette;
        rebuildWidgets();
        savePrefs();
    }

    private void toggleInspector() {
        wantInspector = !wantInspector;
        rebuildWidgets();
        savePrefs();
    }

    // ---------------------------------------------------------------- actions (keymap, buttons, menus)

    private void run(MosaicKeymap.Action a) {
        switch (a) {
            case QUICK_ADD -> {
                boolean inCanvas = layout.canvas().contains(mouseX, mouseY);
                Rect c = layout.canvas();
                openQuick(inCanvas ? (int) mouseX : c.x() + c.w() / 2, inCanvas ? (int) mouseY : c.y() + c.h() / 3);
            }
            case SEARCH -> {
                if (!layout.paletteShown()) togglePalette();
                setFocused(searchBox);
                searchBox.setFocused(true);
            }
            case UNDO -> undoRedo(false);
            case REDO -> undoRedo(true);
            case COPY -> copySelected();
            case PASTE -> paste();
            case DUPLICATE -> duplicateSelected();
            case DELETE -> deleteSelected();
            case APPLY -> apply();
            case FIT -> fitView();
            case CENTER -> {
                if (selected != null) focusNode(selected);
            }
            case SELECT_ALL -> {
                selectGroup(doc.nodes, false);
                flash(doc.nodes.size() + " nodes selected", COL_MUTED);
            }
            case ARRANGE -> arrange();
            case CODE -> toggleWindow(Win.CODE);
            case ZOOM_IN -> zoomAround(1.25);
            case ZOOM_OUT -> zoomAround(1 / 1.25);
            case ZOOM_RESET -> zoomAround(1 / zoom);
            case TOGGLE_PALETTE -> togglePalette();
            case TOGGLE_INSPECTOR -> toggleInspector();
            case TOGGLE_LIVE -> {
                live = !live;
                flash(live ? "Live on: valid changes are saved and hot-reloaded" : "Live off: use Apply to save",
                    COL_MUTED);
            }
            case TOGGLE_PREVIEW -> preview = !preview;
            case TOGGLE_SNAP -> {
                prefs.snap = !prefs.snap;
                flash(prefs.snap ? "snap to grid on (hold Alt while dragging to place freely)" : "snap to grid off",
                    COL_MUTED);
                savePrefs();
            }
            case HELP -> toggleWindow(Win.KEYS);
            case SETTINGS -> toggleWindow(Win.SETTINGS);
        }
    }

    /** Zooms around the canvas center (keyboard zoom). */
    private void zoomAround(double factor) {
        Rect c = layout.canvas();
        double cx = c.x() + c.w() / 2.0, cy = c.y() + c.h() / 2.0;
        double wx = toWorldX(cx), wy = toWorldY(cy);
        zoom = Math.clamp(zoom * factor, ZOOM_MIN, ZOOM_MAX);
        panX = cx - c.x() - wx * zoom;
        panY = cy - c.y() - wy * zoom;
    }

    /** Copies the selection as a JSON fragment to the system clipboard (pasteable in any pack or chat). */
    private void copySelected() {
        if (selection.isEmpty()) {
            flash("select nodes to copy", COL_MUTED);
            return;
        }
        String json = MosaicDocument.fragmentJson(group());
        clipboardText = json;
        try {
            minecraft.keyboardHandler.setClipboard(json);
        } catch (RuntimeException ignored) {
            // the internal copy still works
        }
        flash("copied " + groupLabel() + " - " + firstShortcut(MosaicKeymap.Action.PASTE) + " pastes at the cursor",
            COL_MUTED);
    }

    /** Pastes a fragment from the system clipboard (or the last copy) with its top-left corner at the mouse. */
    private void paste() {
        String text = null;
        try {
            text = minecraft.keyboardHandler.getClipboard();
        } catch (RuntimeException ignored) {
            // fall back to the internal copy
        }
        List<MosaicDocument.ENode> nodes = MosaicDocument.readFragment(text);
        if (nodes == null) nodes = MosaicDocument.readFragment(clipboardText);
        if (nodes == null) {
            flash("the clipboard holds no Mosaic nodes", COL_MUTED);
            return;
        }
        finishParamEdit();
        int minX = nodes.stream().mapToInt(n -> n.x).min().orElse(0);
        int minY = nodes.stream().mapToInt(n -> n.y).min().orElse(0);
        double[] spot = layout.canvas().contains(mouseX, mouseY)
            ? new double[] {toWorldX(mouseX), toWorldY(mouseY)} : freeSpotInView();
        String before = doc.snapshot();
        List<MosaicDocument.ENode> added = doc.insertCopies(nodes, (int) Math.round(spot[0]) - minX,
            (int) Math.round(spot[1]) - minY, false);
        doc.commit(before);
        changed();
        selectGroup(added, false);
        flash("pasted " + added.size() + (added.size() == 1 ? " node" : " nodes"), COL_MUTED);
    }

    // ---------------------------------------------------------------- windows

    private void toggleWindow(Win w) {
        if (win == w) {
            closeWindow();
            return;
        }
        win = w;
        winScroll = 0;
        rebinding = null;
        Rect r = windowRect();
        if (winX == Integer.MIN_VALUE) { // first open: top-right of the canvas, clear of the inspector
            Rect c = layout.canvas();
            winX = c.right() - r.w() - 8;
            winY = c.y() + 8;
        }
    }

    private void closeWindow() {
        win = Win.NONE;
        rebinding = null;
        winDrag = false;
        contextNode = null;
    }

    private Rect windowRect() {
        if (win == Win.CONTEXT) return new Rect(ctxX, ctxY, 150, contextItems().size() * 12 + 4);
        int w = switch (win) {
            case KEYS -> Math.min(300, width - 16);
            case CODE -> Math.min(460, width - 16);
            default -> Math.min(230, width - 16);
        };
        int h = switch (win) {
            case KEYS -> Math.min(300, height - 40);
            case CODE -> Math.min(340, height - 40);
            default -> Math.min(210, height - 40);
        };
        int x = winX == Integer.MIN_VALUE ? (width - w) / 2 : winX, y = winX == Integer.MIN_VALUE ? 30 : winY;
        x = Math.clamp(x, 0, Math.max(0, width - w));
        y = Math.clamp(y, MosaicLayout.TOP_H, Math.max(MosaicLayout.TOP_H, height - h));
        return new Rect(x, y, w, h);
    }

    private boolean overWindow(double mx, double my) {
        return win != Win.NONE && windowRect().contains(mx, my);
    }

    private record MenuItem(String label, String keys, Runnable action) {}

    private List<MenuItem> contextItems() {
        MosaicDocument.ENode n = contextNode;
        List<MenuItem> items = new ArrayList<>();
        if (n == null) return items;
        if (selection.size() > 1) {
            int k = selection.size();
            items.add(new MenuItem("Arrange " + k + " nodes", firstShortcut(MosaicKeymap.Action.ARRANGE), this::arrange));
            items.add(new MenuItem("Align left", "", () -> align(true)));
            items.add(new MenuItem("Align top", "", () -> align(false)));
            items.add(new MenuItem("Duplicate " + k, firstShortcut(MosaicKeymap.Action.DUPLICATE), this::duplicateSelected));
            items.add(new MenuItem("Copy " + k, firstShortcut(MosaicKeymap.Action.COPY), this::copySelected));
            items.add(new MenuItem("Delete " + k, firstShortcut(MosaicKeymap.Action.DELETE), this::deleteSelected));
            return items;
        }
        items.add(new MenuItem("Duplicate", firstShortcut(MosaicKeymap.Action.DUPLICATE), this::duplicateSelected));
        items.add(new MenuItem("Copy", firstShortcut(MosaicKeymap.Action.COPY), this::copySelected));
        if (!n.inputs.isEmpty())
            items.add(new MenuItem("Disconnect inputs", "", () -> {
                String before = doc.snapshot();
                n.inputs.clear();
                doc.commit(before);
                changed();
            }));
        items.add(new MenuItem("Center view", firstShortcut(MosaicKeymap.Action.CENTER), () -> focusNode(n)));
        boolean fav = prefs.favorites.contains(n.type);
        items.add(new MenuItem(fav ? "Remove from favorites" : "Add type to favorites", "", () -> {
            prefs.toggleFavorite(n.type);
            savePrefs();
        }));
        items.add(new MenuItem("Delete", firstShortcut(MosaicKeymap.Action.DELETE), this::deleteSelected));
        return items;
    }

    private void openContext(MosaicDocument.ENode n, int sx, int sy) {
        if (!selection.contains(n)) select(n);
        contextNode = n;
        win = Win.CONTEXT;
        ctxX = Math.clamp(sx, 0, Math.max(0, width - 152));
        ctxY = Math.clamp(sy, MosaicLayout.TOP_H, Math.max(MosaicLayout.TOP_H, height - contextItems().size() * 12 - 6));
    }

    // ---------------------------------------------------------------- save-as dialog

    private void openSaveDialog() {
        saveDialog = true;
        closeQuick();
        packBox.visible = true;
        setFocused(packBox);
        packBox.setFocused(true);
    }

    private void closeSaveDialog() {
        saveDialog = false;
        packBox.setFocused(false);
        packBox.visible = false;
        setFocused(null);
    }

    private void saveAsPack() {
        String name = packBox.getValue().trim();
        if (!name.matches("[\\w .-]+") || name.startsWith(".")) {
            flash("not a valid folder name: " + name, COL_BAD);
            return;
        }
        Path file = minecraft.gameDirectory.toPath().resolve("shaderpacks").resolve(name)
            .resolve("shaders").resolve("bedray").resolve("mosaic.json");
        try {
            doc.file = file;
            doc.save();
            closeSaveDialog();
            flash("saved shaderpacks/" + name + " - run with -Dbedray.customrt=" + name + " to see it live", COL_OK);
        } catch (Exception e) {
            doc.file = null;
            flash("save failed: " + e.getMessage(), COL_BAD);
        }
    }

    // ---------------------------------------------------------------- parameters (live)

    private void syncInspectorBoxes() {
        syncingBoxes = true;
        MosaicDocument.ENode n = selected;
        if (n != null && n.type.equals("mosaic.const")) valueBox.setValue(numbers(n.value));
        if (n != null && n.type.equals("mosaic.swizzle")) maskBox.setValue(n.mask == null ? "" : n.mask);
        valueBox.setTextColor(COL_TEXT);
        maskBox.setTextColor(COL_TEXT);
        syncingBoxes = false;
    }

    private void liveValue(String text) {
        if (syncingBoxes || selected == null || !selected.type.equals("mosaic.const")) return;
        double[] v = parseNumbers(text);
        if (v == null) {
            valueBox.setTextColor(COL_BAD);
            return;
        }
        if (paramBefore == null) paramBefore = doc.snapshot();
        double[] old = selected.value;
        selected.value = v;
        if (refusedHere(selected)) {
            selected.value = old;
            valueBox.setTextColor(COL_BAD);
            return;
        }
        valueBox.setTextColor(COL_TEXT);
        doc.dirty = true;
        changed();
    }

    private void liveMask(String text) {
        if (syncingBoxes || selected == null || !selected.type.equals("mosaic.swizzle")) return;
        String t = text.trim();
        if (t.isEmpty() || !t.chars().allMatch(c -> "xyzwrgba".indexOf(c) >= 0)) {
            maskBox.setTextColor(COL_BAD);
            return;
        }
        if (paramBefore == null) paramBefore = doc.snapshot();
        String old = selected.mask;
        selected.mask = t;
        if (refusedHere(selected)) {
            selected.mask = old;
            maskBox.setTextColor(COL_BAD);
            flash(String.valueOf(doc.validate()), COL_BAD);
            return;
        }
        maskBox.setTextColor(COL_TEXT);
        doc.dirty = true;
        changed();
    }

    /**
     * True when the document's error is about this node's own parameter. Unrelated errors elsewhere and missing
     * inputs (a graph still being built) never block editing a parameter.
     */
    private boolean refusedHere(MosaicDocument.ENode n) {
        String problem = doc.validate();
        return problem != null && !isIncomplete(problem)
            && (problem.contains("node " + n.id + ":") || problem.contains("node " + n.id + " "));
    }

    /** Errors that only say the graph is unfinished (an empty required port, no output yet). */
    private static boolean isIncomplete(String problem) {
        return problem.contains("is required") || problem.contains("exactly one output");
    }

    /** Closes a parameter edit: everything typed since it began is one undo step. */
    private void finishParamEdit() {
        if (paramBefore != null) {
            doc.commit(paramBefore);
            paramBefore = null;
        }
    }

    private void stepIndex(int delta) {
        MosaicDocument.ENode n = selected;
        if (n == null) return;
        int max = n.type.equals("mosaic.push") ? 3 : 1023;
        int next = Math.clamp(n.index + delta, 0, max);
        if (next == n.index) return;
        String before = doc.snapshot();
        n.index = next;
        doc.commit(before);
        changed();
    }

    private static double[] parseNumbers(String text) {
        String[] parts = text.trim().split("[,;\\s]+");
        if (parts.length < 1 || parts.length > 4 || parts[0].isEmpty()) return null;
        double[] v = new double[parts.length];
        try {
            for (int i = 0; i < v.length; i++) {
                v[i] = Double.parseDouble(parts[i]);
                if (!Double.isFinite(v[i])) return null;
            }
        } catch (NumberFormatException e) {
            return null;
        }
        return v;
    }

    private static String numbers(double[] value) {
        StringBuilder sb = new StringBuilder();
        for (double v : value == null ? new double[] {0} : value) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(v == Math.rint(v) && Math.abs(v) < 1e15 ? String.valueOf((long) v) : String.valueOf((float) v));
        }
        return sb.toString();
    }

    // =================================================================== canvas geometry

    private double toScreenX(double wx) { return layout.canvas().x() + panX + wx * zoom; }
    private double toScreenY(double wy) { return layout.canvas().y() + panY + wy * zoom; }
    private double toWorldX(double sx) { return (sx - layout.canvas().x() - panX) / zoom; }
    private double toWorldY(double sy) { return (sy - layout.canvas().y() - panY) / zoom; }

    private static List<String> inputsOf(MosaicDocument.ENode n) {
        List<String> out = new ArrayList<>();
        for (MosaicGraph.Port p : MosaicGraph.portsOf(n.type)) out.add(p.name());
        return out;
    }

    private static List<String> outputsOf(MosaicDocument.ENode n) {
        List<String> outs = new ArrayList<>();
        if (n.type.equals("mosaic.output")) return outs;
        outs.add("");
        if (n.type.equals("mosaic.trace")) outs.addAll(MosaicGraph.TRACE_OUTPUTS);
        return outs;
    }

    private static boolean hasParamLine(MosaicDocument.ENode n) { return !paramLine(n).isEmpty(); }

    private static int nodeHeight(MosaicDocument.ENode n) {
        int rows = Math.max(Math.max(inputsOf(n).size(), outputsOf(n).size()), 1);
        return HEAD_H + rows * ROW_H + (hasParamLine(n) ? ROW_H : 0) + 4;
    }

    private static double inputY(MosaicDocument.ENode n, int row) { return n.y + HEAD_H + row * ROW_H + ROW_H / 2.0 + 1; }

    private static double outputY(MosaicDocument.ENode n, int row) { return inputY(n, row); }

    private Kind outputKind(MosaicDocument.ENode n, String out) {
        MosaicGraph g = graph != null ? graph : lastGood;
        if (!out.isEmpty()) return MosaicGraph.traceOutputKind(out);
        if (g == null || g.node(n.id) == null || !g.node(n.id).type().equals(n.type)) return null;
        try {
            return g.kind(n.id);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private Kind refKind(String ref) {
        String[] parts = MosaicGraph.split(ref);
        MosaicDocument.ENode src = doc.byId(parts[0]);
        return src == null ? null : outputKind(src, parts[1]);
    }

    private record SocketHit(MosaicDocument.ENode node, String name, boolean input) {}

    private SocketHit socketAt(double sx, double sy) {
        double wx = toWorldX(sx), wy = toWorldY(sy), r = Math.max(5, 7 / zoom);
        for (int i = doc.nodes.size() - 1; i >= 0; i--) {
            MosaicDocument.ENode n = doc.nodes.get(i);
            List<String> ins = inputsOf(n), outs = outputsOf(n);
            for (int p = 0; p < ins.size(); p++)
                if (Math.abs(wx - n.x) <= r && Math.abs(wy - inputY(n, p)) <= r * 0.8)
                    return new SocketHit(n, ins.get(p), true);
            for (int p = 0; p < outs.size(); p++)
                if (Math.abs(wx - (n.x + NODE_W)) <= r && Math.abs(wy - outputY(n, p)) <= r * 0.8)
                    return new SocketHit(n, outs.get(p), false);
        }
        return null;
    }

    private MosaicDocument.ENode nodeAt(double sx, double sy) {
        double wx = toWorldX(sx), wy = toWorldY(sy);
        for (int i = doc.nodes.size() - 1; i >= 0; i--) {
            MosaicDocument.ENode n = doc.nodes.get(i);
            if (wx >= n.x && wx < n.x + NODE_W && wy >= n.y && wy < n.y + nodeHeight(n)) return n;
        }
        return null;
    }

    private void fitView() {
        Rect c = layout == null ? null : layout.canvas();
        if (c == null || c.w() <= 0) return;
        if (doc.nodes.isEmpty()) {
            zoom = 1;
            panX = c.w() / 2.0;
            panY = c.h() / 3.0;
            return;
        }
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE;
        for (MosaicDocument.ENode n : doc.nodes) {
            minX = Math.min(minX, n.x);
            minY = Math.min(minY, n.y);
            maxX = Math.max(maxX, n.x + NODE_W);
            maxY = Math.max(maxY, n.y + nodeHeight(n));
        }
        double bw = Math.max(1, maxX - minX), bh = Math.max(1, maxY - minY);
        zoom = Math.clamp(Math.min((c.w() - 40) / bw, (c.h() - 40) / bh), ZOOM_MIN, 1.25);
        panX = (c.w() - bw * zoom) / 2 - minX * zoom;
        panY = (c.h() - bh * zoom) / 2 - minY * zoom;
    }

    private void focusNode(MosaicDocument.ENode n) {
        Rect c = layout.canvas();
        panX = c.w() / 2.0 - (n.x + NODE_W / 2.0) * zoom;
        panY = c.h() / 2.0 - (n.y + nodeHeight(n) / 2.0) * zoom;
        if (!selection.contains(n)) select(n);
    }

    // =================================================================== quick-add popup

    private void openQuick(int sx, int sy) {
        finishParamEdit();
        Rect c = layout.canvas();
        quickOpen = true;
        quickWorldX = toWorldX(sx);
        quickWorldY = toWorldY(sy);
        quickX = Math.clamp(sx, c.x() + 2, Math.max(c.x() + 2, c.right() - 162));
        quickY = Math.clamp(sy, c.y() + 2, Math.max(c.y() + 2, c.bottom() - 150));
        quickSel = 0;
        quickBox.setValue("");
        setFocused(quickBox);
        quickBox.setFocused(true);
    }

    private void closeQuick() {
        quickOpen = false;
        pendingFrom = pendingTo = null;
        quickBox.setFocused(false);
        quickBox.visible = false;
        if (getFocused() == quickBox) setFocused(null);
    }

    private List<MosaicCatalog.Entry> quickHits() {
        List<MosaicCatalog.Entry> hits = MosaicCatalog.search(quickBox.getValue());
        if (pendingTo != null) hits = hits.stream().filter(e -> !e.type().equals("mosaic.output")).toList();
        if (pendingFrom != null) hits = hits.stream().filter(e -> !MosaicGraph.portsOf(e.type()).isEmpty()).toList();
        return hits;
    }

    private void quickAdd(MosaicCatalog.Entry e) {
        MosaicDocument.ENode from = pendingFrom, to = pendingTo;
        String fromOut = pendingFromOut, toPort = pendingToPort;
        double wx = quickWorldX, wy = quickWorldY;
        closeQuick();
        if (to != null) wx -= NODE_W; // a node feeding an input sits to its left
        MosaicDocument.ENode n = addNodeAt(e.type(), wx, wy - HEAD_H / 2.0);
        if (from != null) connectToBody(n, from, fromOut);
        if (to != null && !outputsOf(n).isEmpty()) connect(to, toPort, n, "", false);
    }

    // =================================================================== input

    private boolean anyBoxFocused() {
        return focusedBox() != null;
    }

    private EditBox focusedBox() {
        for (EditBox b : new EditBox[] {searchBox, valueBox, maskBox, quickBox, packBox})
            if (b != null && b.visible && b.isFocused()) return b;
        return null;
    }

    private void blurBoxes() {
        finishParamEdit();
        for (EditBox b : new EditBox[] {searchBox, valueBox, maskBox, quickBox, packBox}) b.setFocused(false);
        setFocused(null);
    }

    @Override public void mouseMoved(double x, double y) {
        mouseX = x;
        mouseY = y;
        super.mouseMoved(x, y);
    }

    @Override public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        double mx = event.x(), my = event.y();
        int button = event.button();
        mouseX = mx;
        mouseY = my;

        if (saveDialog) {
            Rect d = dialogRect();
            if (!d.contains(mx, my)) closeSaveDialog();
            else super.mouseClicked(event, doubleClick);
            return true;
        }
        if (rebinding != null && !(win == Win.KEYS && windowRect().contains(mx, my))) rebinding = null;
        if (win == Win.CONTEXT) {
            Rect r = windowRect();
            if (r.contains(mx, my)) runHotIn(r, mx, my);
            closeWindow();
            return true;
        }
        if (overWindow(mx, my)) {
            Rect r = windowRect();
            Rect title = new Rect(r.x(), r.y(), r.w(), 14);
            if (title.contains(mx, my)) {
                if (!runHotIn(title, mx, my)) { // the title bar moves the window
                    winDrag = true;
                    winDragDX = mx - r.x();
                    winDragDY = my - r.y();
                }
            } else {
                runHotIn(r, mx, my);
            }
            return true;
        }
        if (quickOpen) {
            if (quickRect().contains(mx, my)) {
                if (quickBox.isMouseOver(mx, my)) return super.mouseClicked(event, doubleClick);
                runHotIn(quickRect(), mx, my);
                return true;
            }
            closeQuick();
            if (!layout.canvas().contains(mx, my)) return true;
        }

        EditBox focused = focusedBox();
        if (focused != null && !focused.isMouseOver(mx, my)) blurBoxes();

        if (layout.top().contains(mx, my)) return super.mouseClicked(event, doubleClick);
        if (layout.palette().contains(mx, my)) {
            if (searchBox.isMouseOver(mx, my)) return super.mouseClicked(event, doubleClick);
            for (int i = hots.size() - 1; i >= 0; i--) {
                Hot h = hots.get(i);
                if (h.rect().contains(mx, my) && h.action() == null && h.tooltip() != null
                    && h.tooltip().startsWith("mosaic.") && button == 1) {
                    boolean fav = prefs.toggleFavorite(h.tooltip());
                    savePrefs();
                    flash((fav ? "added to" : "removed from") + " favorites: " + MosaicCatalog.of(h.tooltip()).label(),
                        COL_MUTED);
                    return true;
                }
                if (h.rect().contains(mx, my) && h.action() == null && h.tooltip() != null
                    && h.tooltip().startsWith("mosaic.")) {
                    // palette entry: press starts a drag; release on the canvas places it, release in place adds it
                    drag = Drag.PALETTE;
                    paletteDragType = h.tooltip();
                    dragStartX = mx;
                    dragStartY = my;
                    dragMoved = false;
                    return true;
                }
            }
            runHotIn(layout.palette(), mx, my);
            return true;
        }
        if (layout.inspector().contains(mx, my)) {
            if (valueBox.isMouseOver(mx, my) || maskBox.isMouseOver(mx, my)) return super.mouseClicked(event, doubleClick);
            runHotIn(layout.inspector(), mx, my);
            return true;
        }
        if (layout.status().contains(mx, my)) {
            runHotIn(layout.status(), mx, my);
            return true;
        }
        if (layout.canvas().contains(mx, my)) return canvasClicked(mx, my, button, doubleClick, event.modifiers());
        return super.mouseClicked(event, doubleClick);
    }

    /** Runs the clickable element under the mouse, considering only elements drawn inside {@code area}. */
    private boolean runHotIn(Rect area, double mx, double my) {
        for (int i = hots.size() - 1; i >= 0; i--) {
            Hot h = hots.get(i);
            if (within(h.rect(), area) && h.rect().contains(mx, my) && h.action() != null) {
                h.action().run();
                return true;
            }
        }
        return false;
    }

    private static boolean within(Rect r, Rect area) {
        return r.x() >= area.x() && r.y() >= area.y() && r.right() <= area.right() && r.bottom() <= area.bottom();
    }

    private boolean canvasClicked(double mx, double my, int button, boolean doubleClick, int mods) {
        boolean additive = (mods & (MosaicKeymap.SHIFT | MosaicKeymap.CTRL)) != 0;
        if (button == 2) {
            startPan(mx, my);
            return true;
        }
        SocketHit socket = socketAt(mx, my);
        MosaicDocument.ENode node = socket != null ? socket.node() : nodeAt(mx, my);
        if (button == 1) {
            if (socket != null && socket.input() && socket.node().inputs.containsKey(socket.name())) {
                disconnect(socket.node(), socket.name());
                flash("disconnected " + socket.node().id + "." + socket.name(), COL_MUTED);
            } else if (node != null) {
                openContext(node, (int) mx, (int) my);
            } else {
                // right button on empty canvas: drag pans, a plain click opens the add popup on release
                startPan(mx, my);
                rightClickPending = true;
            }
            return true;
        }
        if (button != 0) return true;
        if (socket != null) {
            select(socket.node());
            gestureBefore = doc.snapshot();
            if (socket.input()) {
                String ref = socket.node().inputs.get(socket.name());
                if (ref != null) {
                    // grab an existing wire by its input end: it follows the mouse from its source output
                    String[] parts = MosaicGraph.split(ref);
                    socket.node().inputs.remove(socket.name());
                    changed();
                    wireNode = doc.byId(parts[0]);
                    wirePort = parts[1];
                    drag = wireNode != null ? Drag.WIRE_FROM_OUTPUT : Drag.NONE;
                } else {
                    wireNode = socket.node();
                    wirePort = socket.name();
                    drag = Drag.WIRE_FROM_INPUT;
                }
            } else {
                wireNode = socket.node();
                wirePort = socket.name();
                drag = Drag.WIRE_FROM_OUTPUT;
            }
            return true;
        }
        if (node != null) {
            if (additive) {
                toggleSelect(node);
                return true;
            }
            if (!selection.contains(node)) select(node);
            else if (selected != node) {
                finishParamEdit();
                selected = node; // keep the group, make this one primary
                syncInspectorBoxes();
            }
            // bring the moved nodes to front so they draw above the nodes they are dragged over
            for (MosaicDocument.ENode n : group()) {
                doc.nodes.remove(n);
                doc.nodes.add(n);
            }
            dragNode = node;
            gestureBefore = doc.snapshot();
            dragStarts.clear();
            for (MosaicDocument.ENode n : selection) dragStarts.put(n, new int[] {n.x, n.y});
            pressWX = toWorldX(mx);
            pressWY = toWorldY(my);
            drag = Drag.NODE;
            return true;
        }
        if (doubleClick) {
            openQuick((int) mx, (int) my);
            return true;
        }
        if (prefs.leftDragSelects) {
            drag = Drag.BOX;
            boxX0 = boxX1 = mx;
            boxY0 = boxY1 = my;
            boxAdditive = additive;
            return true;
        }
        if (!additive) select(null);
        startPan(mx, my);
        return true;
    }

    private void startPan(double mx, double my) {
        rightClickPending = false;
        drag = Drag.PAN;
        dragStartX = mx;
        dragStartY = my;
        panStartX = panX;
        panStartY = panY;
    }

    @Override public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        double mx = event.x(), my = event.y();
        mouseX = mx;
        mouseY = my;
        if (winDrag) {
            winX = (int) (mx - winDragDX);
            winY = (int) (my - winDragDY);
            return true;
        }
        switch (drag) {
            case NODE -> {
                // the grabbed node follows the mouse (snapped); the rest of the group keeps its offsets
                int[] start = dragStarts.get(dragNode);
                double nx = start[0] + toWorldX(mx) - pressWX, ny = start[1] + toWorldY(my) - pressWY;
                boolean free = (event.modifiers() & MosaicKeymap.ALT) != 0;
                int tx = prefs.snap && !free ? prefs.snapped(nx) : (int) Math.round(nx);
                int ty = prefs.snap && !free ? prefs.snapped(ny) : (int) Math.round(ny);
                int ddx = tx - start[0], ddy = ty - start[1];
                for (var e : dragStarts.entrySet()) {
                    e.getKey().x = e.getValue()[0] + ddx;
                    e.getKey().y = e.getValue()[1] + ddy;
                }
            }
            case BOX -> {
                boxX1 = mx;
                boxY1 = my;
            }
            case PAN -> {
                panX = panStartX + (mx - dragStartX);
                panY = panStartY + (my - dragStartY);
            }
            case PALETTE -> dragMoved |= Math.abs(mx - dragStartX) + Math.abs(my - dragStartY) > 4;
            case NONE -> {
                return super.mouseDragged(event, dx, dy);
            }
            default -> { }
        }
        return true;
    }

    @Override public boolean mouseReleased(MouseButtonEvent event) {
        if (winDrag) {
            winDrag = false;
            return true;
        }
        if (drag == Drag.NONE) return super.mouseReleased(event); // edit-box selection, buttons
        double mx = event.x(), my = event.y();
        boolean onCanvas = layout.canvas().contains(mx, my) && !quickOpen;
        switch (drag) {
            case WIRE_FROM_OUTPUT -> {
                SocketHit s = onCanvas ? socketAt(mx, my) : null;
                MosaicDocument.ENode body = onCanvas ? nodeAt(mx, my) : null;
                if (s != null && s.input()) connect(s.node(), s.name(), wireNode, wirePort, false);
                else if (body != null && body != wireNode) connectToBody(body, wireNode, wirePort);
                else if (onCanvas && s == null && body == null) {
                    commitGesture();
                    openQuick((int) mx, (int) my);
                    pendingFrom = wireNode;
                    pendingFromOut = wirePort;
                }
            }
            case WIRE_FROM_INPUT -> {
                SocketHit s = onCanvas ? socketAt(mx, my) : null;
                MosaicDocument.ENode body = onCanvas ? nodeAt(mx, my) : null;
                if (s != null && !s.input()) connect(wireNode, wirePort, s.node(), s.name(), false);
                else if (body != null && body != wireNode && !outputsOf(body).isEmpty())
                    connect(wireNode, wirePort, body, "", false);
                else if (onCanvas && s == null && body == null) {
                    commitGesture();
                    openQuick((int) mx, (int) my);
                    pendingTo = wireNode;
                    pendingToPort = wirePort;
                }
            }
            case PALETTE -> {
                if (dragMoved && layout.canvas().contains(mx, my))
                    addNodeAt(paletteDragType, toWorldX(mx) - NODE_W / 2.0, toWorldY(my) - HEAD_H / 2.0);
                else if (!dragMoved) {
                    double[] spot = freeSpotInView();
                    addNodeAt(paletteDragType, spot[0], spot[1]);
                }
            }
            case BOX -> {
                boolean moved = Math.abs(boxX1 - boxX0) + Math.abs(boxY1 - boxY0) > 3;
                if (moved) {
                    double x0 = toWorldX(Math.min(boxX0, boxX1)), x1 = toWorldX(Math.max(boxX0, boxX1));
                    double y0 = toWorldY(Math.min(boxY0, boxY1)), y1 = toWorldY(Math.max(boxY0, boxY1));
                    List<MosaicDocument.ENode> hit = new ArrayList<>();
                    for (MosaicDocument.ENode n : doc.nodes)
                        if (n.x < x1 && n.x + NODE_W > x0 && n.y < y1 && n.y + nodeHeight(n) > y0) hit.add(n);
                    selectGroup(hit, boxAdditive);
                } else if (!boxAdditive) {
                    select(null);
                }
            }
            case PAN -> {
                boolean moved = Math.abs(mx - dragStartX) + Math.abs(my - dragStartY) > 4;
                if (rightClickPending && !moved && layout.canvas().contains(mx, my)) openQuick((int) mx, (int) my);
                rightClickPending = false;
            }
            default -> { }
        }
        commitGesture();
        drag = Drag.NONE;
        dragNode = null;
        dragStarts.clear();
        wireNode = null;
        wirePort = null;
        paletteDragType = null;
        return true;
    }

    private void commitGesture() {
        if (gestureBefore != null) {
            if (doc.commit(gestureBefore)) changed();
            gestureBefore = null;
        }
    }

    @Override public boolean mouseScrolled(double x, double y, double sx, double sy) {
        if (saveDialog) return true;
        if (overWindow(x, y)) {
            winScroll = Math.max(0, winScroll - (int) Math.round(sy * 20));
            return true;
        }
        if (quickOpen && quickRect().contains(x, y)) {
            int n = quickHits().size();
            if (n > 0) quickSel = Math.floorMod(quickSel - (int) Math.signum(sy), n);
            return true;
        }
        if (layout.palette().contains(x, y)) {
            paletteScroll = Math.max(0, paletteScroll - (int) Math.round(sy * 24));
            return true;
        }
        if (!layout.canvas().contains(x, y)) return true;
        double next = Math.clamp(zoom * (sy > 0 ? 1.12 : 1 / 1.12), ZOOM_MIN, ZOOM_MAX);
        double wx = toWorldX(x), wy = toWorldY(y);
        zoom = next;
        panX = x - layout.canvas().x() - wx * zoom;
        panY = y - layout.canvas().y() - wy * zoom;
        return true;
    }

    @Override public boolean keyPressed(KeyEvent event) {
        int key = event.key(), mods = event.modifiers();

        if (saveDialog) {
            if (event.isEscape()) closeSaveDialog();
            else if (event.isConfirmation()) saveAsPack();
            else super.keyPressed(event);
            return true;
        }
        if (rebinding != null) {
            captureBinding(key, mods, event.isEscape());
            return true;
        }
        if (quickOpen) {
            List<MosaicCatalog.Entry> hits = quickHits();
            if (event.isEscape()) closeQuick();
            else if (event.isConfirmation()) {
                if (!hits.isEmpty()) quickAdd(hits.get(Math.min(quickSel, hits.size() - 1)));
            } else if (key == KEY_DOWN && !hits.isEmpty()) quickSel = (quickSel + 1) % hits.size();
            else if (key == KEY_UP && !hits.isEmpty()) quickSel = Math.floorMod(quickSel - 1, hits.size());
            else super.keyPressed(event);
            return true;
        }
        if (win == Win.CONTEXT) {
            closeWindow();
            if (event.isEscape()) return true;
        }
        if (getFocused() instanceof Button) setFocused(null); // a clicked tool button must not re-fire on Enter/Space
        EditBox box = focusedBox();
        if (box != null) {
            if (event.isEscape()) {
                blurBoxes();
                return true;
            }
            if (event.isConfirmation()) {
                if (box == searchBox) {
                    List<MosaicCatalog.Entry> hits = MosaicCatalog.search(searchBox.getValue());
                    if (!hits.isEmpty()) {
                        double[] spot = freeSpotInView();
                        addNodeAt(hits.get(0).type(), spot[0], spot[1]);
                    }
                }
                blurBoxes();
                return true;
            }
            MosaicKeymap.Action a = prefs.keys.match(key, mods);
            if ((mods & MosaicKeymap.CTRL) != 0 && (a == MosaicKeymap.Action.UNDO || a == MosaicKeymap.Action.REDO
                || a == MosaicKeymap.Action.APPLY)) {
                run(a);
                return true;
            }
            return super.keyPressed(event); // typing never reaches the canvas shortcuts
        }

        MosaicKeymap.Action a = prefs.keys.match(key, mods);
        if (a != null) {
            run(a);
            return true;
        }
        if (!selection.isEmpty() && (key == 262 || key == 263 || key == 264 || key == 265) && (mods & 6) == 0) {
            boolean big = (mods & MosaicKeymap.SHIFT) != 0;
            nudge(key == 262 ? 1 : key == 263 ? -1 : 0, key == 264 ? 1 : key == 265 ? -1 : 0, big);
            return true;
        }
        if (event.isEscape()) {
            if (win != Win.NONE) closeWindow();
            else if (!selection.isEmpty()) select(null);
            else requestClose();
            return true;
        }
        return super.keyPressed(event);
    }

    /** The Keys window waits for a combo: Esc cancels, Delete/Backspace alone clears the slot. */
    private void captureBinding(int key, int mods, boolean escape) {
        if (escape) {
            rebinding = null;
            return;
        }
        if (MosaicKeymap.isModifierKey(key)) return; // wait for the real key
        MosaicKeymap.Action action = rebinding;
        rebinding = null;
        if ((key == 259 || key == 261) && (mods & 7) == 0) {
            prefs.keys.unbind(action, rebindIndex);
            flash("cleared a shortcut of " + action.label, COL_MUTED);
        } else {
            MosaicKeymap.Combo combo = new MosaicKeymap.Combo(key, mods);
            MosaicKeymap.Action stolen = prefs.keys.bind(action, rebindIndex, combo);
            flash(action.label + ": " + combo + (stolen != null ? "  (taken from " + stolen.label + ")" : ""),
                stolen != null ? COL_WARN : COL_OK);
        }
        savePrefs();
        rebuildWidgets(); // tool-bar tooltips show the new keys
    }

    @Override public boolean shouldCloseOnEsc() { return false; }

    // =================================================================== rendering

    @Override public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
        hots.clear();
        boolean modal = saveDialog;
        // panels under an open window/popup must not light up or show tooltips through it
        boolean covered = modal || overWindow(mx, my) || (quickOpen && quickRect().contains(mx, my));
        int hx = covered ? -1 : mx, hy = covered ? -1 : my;

        if (!preview) g.fill(0, 0, width, height, COL_SCREEN);
        drawCanvas(g, hx, hy);
        g.nextStratum();
        drawTopBar(g, hx, hy, delta);
        drawPalette(g, hx, hy, delta);
        drawInspector(g, hx, hy, delta);
        drawStatus(g, hx, hy);
        if (drag == Drag.PALETTE && dragMoved && paletteDragType != null) {
            MosaicCatalog.Entry e = MosaicCatalog.of(paletteDragType);
            int w = font.width(e.label()) + 12;
            g.fill(mx + 6, my + 4, mx + 6 + w, my + 18, e.category().color);
            g.text(font, e.label(), mx + 12, my + 7, COL_TEXT);
        }
        if (win == Win.KEYS || win == Win.SETTINGS || win == Win.CODE) {
            g.nextStratum();
            drawWindow(g, modal ? -1 : mx, modal ? -1 : my);
        }
        if (quickOpen) {
            g.nextStratum();
            drawQuick(g, modal ? -1 : mx, modal ? -1 : my, delta);
        }
        if (win == Win.CONTEXT) {
            g.nextStratum();
            drawContext(g, mx, my);
        }
        if (modal) {
            g.nextStratum();
            drawSaveDialog(g, mx, my, delta);
        }
        if (!modal) hoverTooltip(g, mx, my);
    }

    // ---------------------------------------------------------------- canvas

    private void drawCanvas(GuiGraphicsExtractor g, int mx, int my) {
        Rect c = layout.canvas();
        if (!c.visible()) return;
        g.fill(c.x(), c.y(), c.right(), c.bottom(), preview ? COL_CANVAS_PREVIEW : COL_CANVAS);
        g.enableScissor(c.x(), c.y(), c.right(), c.bottom());
        drawGrid(g, c);

        g.pose().pushMatrix();
        g.pose().translate((float) (c.x() + panX), (float) (c.y() + panY));
        g.pose().scale((float) zoom);

        SocketHit hoverSocket = drag == Drag.NONE && c.contains(mx, my) && !quickOpen ? socketAt(mx, my) : null;
        MosaicDocument.ENode hoverNode = drag == Drag.NONE && c.contains(mx, my) && !quickOpen ? nodeAt(mx, my) : null;

        // wires under nodes
        for (MosaicDocument.ENode n : doc.nodes) {
            List<String> ins = inputsOf(n);
            for (int row = 0; row < ins.size(); row++) {
                String ref = n.inputs.get(ins.get(row));
                if (ref == null) continue;
                String[] parts = MosaicGraph.split(ref);
                MosaicDocument.ENode src = doc.byId(parts[0]);
                if (src == null) continue;
                int outRow = Math.max(0, outputsOf(src).indexOf(parts[1]));
                int color = kindColor(outputKind(src, parts[1]));
                if (selection.contains(n) || selection.contains(src)) color = brighten(color);
                drawWire(g, src.x + NODE_W, outputY(src, outRow), n.x, inputY(n, row), color, prefs.wireThickness);
            }
        }
        // the wire being dragged
        if ((drag == Drag.WIRE_FROM_OUTPUT || drag == Drag.WIRE_FROM_INPUT) && wireNode != null) {
            double wx = toWorldX(mouseX), wy = toWorldY(mouseY);
            if (drag == Drag.WIRE_FROM_OUTPUT) {
                int row = Math.max(0, outputsOf(wireNode).indexOf(wirePort));
                drawWire(g, wireNode.x + NODE_W, outputY(wireNode, row), wx, wy, 0xFFFFFFFF, prefs.wireThickness);
            } else {
                int row = Math.max(0, inputsOf(wireNode).indexOf(wirePort));
                drawWire(g, wx, wy, wireNode.x, inputY(wireNode, row), 0xFFFFFFFF, prefs.wireThickness);
            }
        }
        for (MosaicDocument.ENode n : doc.nodes) drawNode(g, n, n == hoverNode, hoverSocket);

        g.pose().popMatrix();
        if (drag == Drag.BOX) {
            int bx0 = (int) Math.min(boxX0, boxX1), by0 = (int) Math.min(boxY0, boxY1);
            int bx1 = (int) Math.max(boxX0, boxX1), by1 = (int) Math.max(boxY0, boxY1);
            g.fill(bx0, by0, bx1, by1, 0x203C8CFF);
            g.outline(bx0, by0, Math.max(1, bx1 - bx0), Math.max(1, by1 - by0), 0xC09CC4FF);
        }
        if (doc.nodes.isEmpty())
            g.centeredText(font, font.plainSubstrByWidth("Empty graph: double-click or press Space to add a node",
                c.w() - 8), c.x() + c.w() / 2, c.y() + c.h() / 2, COL_MUTED);
        g.disableScissor();
        // zoom badge
        String z = (prefs.snap ? "snap " + prefs.grid + "   " : "") + Math.round(zoom * 100) + "%";
        g.text(font, z, c.right() - font.width(z) - 6, c.bottom() - 12, COL_FAINT);
    }

    private void drawGrid(GuiGraphicsExtractor g, Rect c) {
        double step = prefs.grid * zoom;
        while (step < 10) step *= 2;
        int ox = (int) Math.floor(panX % step), oy = (int) Math.floor(panY % step);
        int i = (int) Math.floor(-panX / step);
        for (double x = c.x() + ox; x < c.right(); x += step, i++)
            g.fill((int) x, c.y(), (int) x + 1, c.bottom(), Math.floorMod(i + 1, 4) == 0 ? COL_GRID_MAJOR : COL_GRID);
        int j = (int) Math.floor(-panY / step);
        for (double y = c.y() + oy; y < c.bottom(); y += step, j++)
            g.fill(c.x(), (int) y, c.right(), (int) y + 1, Math.floorMod(j + 1, 4) == 0 ? COL_GRID_MAJOR : COL_GRID);
    }

    private void drawNode(GuiGraphicsExtractor g, MosaicDocument.ENode n, boolean hover, SocketHit hoverSocket) {
        MosaicCatalog.Entry e = MosaicCatalog.of(n.type);
        int x = n.x, y = n.y, w = NODE_W, h = nodeHeight(n);
        boolean sel = selection.contains(n), primary = n == selected, bad = n.id.equals(errorNode);
        g.fill(x + 2, y + 2, x + w + 2, y + h + 2, 0x60000000); // shadow
        g.fill(x, y, x + w, y + h, COL_NODE);
        g.fill(x, y, x + w, y + HEAD_H, sel ? brighten(e.category().color) : e.category().color);
        int edge = bad ? COL_BAD : primary ? COL_SELECT : sel ? 0xFF9CC4FF : hover ? COL_HOVER : COL_NODE_EDGE;
        g.outline(x - (sel || bad ? 1 : 0), y - (sel || bad ? 1 : 0), w + (sel || bad ? 2 : 0), h + (sel || bad ? 2 : 0), edge);
        if (sel && bad) g.outline(x - 2, y - 2, w + 4, h + 4, COL_BAD);

        boolean small = zoom < 0.6, labels = !small && prefs.portLabels;
        g.text(font, font.plainSubstrByWidth(e.label(), w - 8), x + 4, y + 3, COL_TEXT);
        if (!small && !n.id.equalsIgnoreCase(e.shortName()) && font.width(e.label()) + font.width(n.id) + 14 < w)
            g.text(font, n.id, x + w - font.width(n.id) - 4, y + 3, 0xB0FFFFFF);

        List<String> ins = inputsOf(n), outs = outputsOf(n);
        for (int i = 0; i < ins.size(); i++) {
            String port = ins.get(i);
            int sy = (int) inputY(n, i);
            boolean wired = n.inputs.containsKey(port);
            Kind k = wired ? refKind(n.inputs.get(port)) : MosaicGraph.acceptedKind(n.type, port);
            boolean hot = hoverSocket != null && hoverSocket.input() && hoverSocket.node() == n && hoverSocket.name().equals(port);
            drawSocket(g, x, sy, kindColor(k), wired, hot);
            if (labels) {
                boolean required = MosaicGraph.portsOf(n.type).stream().anyMatch(p -> p.name().equals(port) && p.required());
                int col = !wired && required ? COL_WARN : COL_MUTED;
                g.text(font, font.plainSubstrByWidth(port, w / 2 - 8), x + 6, sy - 4, col);
            }
        }
        for (int i = 0; i < outs.size(); i++) {
            String out = outs.get(i);
            int sy = (int) outputY(n, i);
            boolean hot = hoverSocket != null && !hoverSocket.input() && hoverSocket.node() == n && hoverSocket.name().equals(out);
            drawSocket(g, x + w, sy, kindColor(outputKind(n, out)), true, hot);
            if (labels) {
                String label = out.isEmpty() ? (n.type.equals("mosaic.trace") ? "t" : kindName(outputKind(n, out))) : out;
                g.text(font, label, x + w - font.width(label) - 6, sy - 4, COL_MUTED);
            }
        }
        String param = paramLine(n);
        if (!param.isEmpty() && !small)
            g.text(font, font.plainSubstrByWidth(param, w - 10), x + 5, y + h - ROW_H - 1, COL_TEXT);
    }

    private static void drawSocket(GuiGraphicsExtractor g, int cx, int cy, int color, boolean filled, boolean hot) {
        int r = hot ? SOCKET + 1 : SOCKET;
        g.fill(cx - r - 1, cy - r - 1, cx + r + 1, cy + r + 1, 0xFF0B0D12);
        if (filled) g.fill(cx - r, cy - r, cx + r, cy + r, color);
        else g.outline(cx - r, cy - r, r * 2, r * 2, color);
    }

    private static void drawWire(GuiGraphicsExtractor g, double x0, double y0, double x1, double y1, int color, double t) {
        double bend = Math.max(24, Math.abs(x1 - x0) * 0.5);
        double len = Math.hypot(x1 - x0, y1 - y0) + bend;
        int steps = (int) Math.clamp(len / 2.5, 12, 160);
        int r = Math.max(1, (int) Math.round(t));
        for (int i = 0; i <= steps; i++) {
            double s = (double) i / steps, u = 1 - s;
            double px = u * u * u * x0 + 3 * u * u * s * (x0 + bend) + 3 * u * s * s * (x1 - bend) + s * s * s * x1;
            double py = u * u * u * y0 + 3 * u * u * s * y0 + 3 * u * s * s * y1 + s * s * s * y1;
            g.fill((int) Math.round(px) - r / 2, (int) Math.round(py) - r / 2,
                (int) Math.round(px) - r / 2 + r + 1, (int) Math.round(py) - r / 2 + r + 1, color);
        }
    }

    private static int brighten(int argb) {
        int a = argb >>> 24, r = (argb >> 16) & 255, gr = (argb >> 8) & 255, b = argb & 255;
        r = r + (255 - r) / 3;
        gr = gr + (255 - gr) / 3;
        b = b + (255 - b) / 3;
        return a << 24 | r << 16 | gr << 8 | b;
    }

    private static String paramLine(MosaicDocument.ENode n) {
        return switch (n.type) {
            case "mosaic.const" -> numbers(n.value);
            case "mosaic.swizzle" -> n.mask == null ? "(no mask)" : "." + n.mask;
            case "mosaic.push" -> "rt.push slot " + n.index;
            case "mosaic.buffer" -> "buffer5 [" + n.index + "]";
            default -> "";
        };
    }

    // ---------------------------------------------------------------- top bar

    private void drawTopBar(GuiGraphicsExtractor g, int mx, int my, float delta) {
        Rect t = layout.top();
        g.fill(t.x(), t.y(), t.right(), t.bottom(), COL_BAR);
        g.fill(t.x(), t.bottom() - 1, t.right(), t.bottom(), COL_PANEL_EDGE);

        undoButton.active = doc.canUndo();
        redoButton.active = doc.canRedo();
        applyButton.active = graph != null && (doc.dirty || doc.file == null);
        liveButton.active = doc.file != null;
        boolean liveOn = live && doc.file != null;
        liveButton.setMessage(Component.literal(compact ? "Live" : liveOn ? "Live: on" : "Live: off")
            .withColor(liveOn ? COL_OK : COL_MUTED));
        previewButton.setMessage(Component.literal(compact ? "View" : preview ? "Preview: on" : "Preview")
            .withColor(preview ? COL_OK : COL_TEXT));
        paletteButton.setMessage(Component.literal(layout.paletteShown() ? "< Nodes" : "> Nodes"));

        String name = doc.file == null ? "unsaved graph" : packLabel(doc.file);
        String title = "Mosaic  " + name;
        int left = titleLeft;
        int room = titleRight - left;
        if (room > 30) {
            String shown = font.plainSubstrByWidth(title, room - (doc.dirty ? 12 : 0));
            g.text(font, shown, left, 7, COL_TEXT);
            if (doc.dirty) g.text(font, "*", left + font.width(shown) + 3, 7, COL_WARN);
        }
        for (Button b : new Button[] {paletteButton, settingsButton, helpButton, codeButton, undoButton, redoButton, fitButton,
            previewButton, liveButton, applyButton, doneButton})
            b.extractRenderState(g, mx, my, delta);
    }

    private static String packLabel(Path file) {
        // .../shaderpacks/<pack>/shaders/bedray/mosaic.json -> <pack>
        Path p = file;
        for (int i = 0; i < 3 && p != null; i++) p = p.getParent();
        return p != null && p.getFileName() != null ? p.getFileName().toString() : file.getFileName().toString();
    }

    // ---------------------------------------------------------------- palette

    private void drawPalette(GuiGraphicsExtractor g, int mx, int my, float delta) {
        Rect p = layout.palette();
        searchBox.visible = p.visible();
        if (!p.visible()) return;
        g.fill(p.x(), p.y(), p.right(), p.bottom(), COL_PANEL);
        g.fill(p.right() - 1, p.y(), p.right(), p.bottom(), COL_PANEL_EDGE);

        searchBox.setX(p.x() + 4);
        searchBox.setY(p.y() + 4);
        searchBox.setWidth(p.w() - 9);
        searchBox.extractRenderState(g, mx, my, delta);

        int listTop = p.y() + 22, listBottom = p.bottom() - 2;
        String q = searchBox.getValue().trim();
        List<Object> rows = new ArrayList<>(); // Group headers and entries
        if (q.isEmpty()) {
            addGroup(rows, new Group("FAVORITES", "Favorites", COL_FAVORITE), prefs.favorites);
            addGroup(rows, new Group("RECENT", "Recent", 0xFF8792A8), prefs.recent);
            for (MosaicCatalog.Category cat : MosaicCatalog.Category.values()) {
                List<String> types = new ArrayList<>();
                for (MosaicCatalog.Entry e : MosaicCatalog.all()) if (e.category() == cat) types.add(e.type());
                addGroup(rows, new Group(cat.name(), cat.label, cat.color), types);
            }
        } else {
            rows.addAll(MosaicCatalog.search(q));
        }
        int rowH = 12, total = rows.size() * rowH;
        paletteScroll = Math.clamp(paletteScroll, 0, Math.max(0, total - (listBottom - listTop)));
        g.enableScissor(p.x(), listTop, p.right() - 1, listBottom);
        int y = listTop - paletteScroll;
        for (Object row : rows) {
            if (y + rowH > listTop && y < listBottom) {
                Rect r = new Rect(p.x() + 2, y, p.w() - 5, rowH);
                boolean hot = r.contains(mx, my) && my >= listTop && my < listBottom;
                if (row instanceof Group grp) {
                    boolean shut = prefs.collapsed.contains(grp.id());
                    g.fill(r.x(), r.y() + 1, r.x() + 3, r.bottom() - 1, grp.color());
                    g.text(font, (shut ? "+ " : "- ") + grp.label(), r.x() + 6, y + 2, hot ? COL_TEXT : COL_MUTED);
                    if (my >= listTop && my < listBottom)
                        hots.add(new Hot(r, () -> {
                            if (!prefs.collapsed.remove(grp.id())) prefs.collapsed.add(grp.id());
                            savePrefs();
                        }, null));
                } else if (row instanceof MosaicCatalog.Entry e) {
                    if (hot) g.fill(r.x(), r.y(), r.right(), r.bottom(), COL_ROW_HOT);
                    g.fill(r.x() + 6, y + 4, r.x() + 9, y + 7, e.category().color);
                    boolean fav = prefs.favorites.contains(e.type());
                    g.text(font, font.plainSubstrByWidth(e.label(), r.w() - 24), r.x() + 13, y + 2, COL_TEXT);
                    if (fav) g.text(font, "*", r.right() - 8, y + 2, COL_FAVORITE);
                    if (my >= listTop && my < listBottom) hots.add(new Hot(r, null, e.type()));
                }
            }
            y += rowH;
        }
        if (rows.isEmpty()) g.text(font, "no node matches", p.x() + 8, listTop + 4, COL_MUTED);
        g.disableScissor();
        if (total > listBottom - listTop) { // scroll indicator
            int trackH = listBottom - listTop, barH = Math.max(12, trackH * trackH / total);
            int barY = listTop + (trackH - barH) * paletteScroll / Math.max(1, total - trackH);
            g.fill(p.right() - 4, barY, p.right() - 2, barY + barH, 0x60FFFFFF);
        }
    }

    private static void addGroup(List<Object> rows, Group group, List<String> types, MosaicPrefs prefs) {
        if (types.isEmpty()) return;
        rows.add(group);
        if (!prefs.collapsed.contains(group.id())) for (String t : types) rows.add(MosaicCatalog.of(t));
    }

    private void addGroup(List<Object> rows, Group group, List<String> types) { addGroup(rows, group, types, prefs); }

    // ---------------------------------------------------------------- inspector

    private void drawInspector(GuiGraphicsExtractor g, int mx, int my, float delta) {
        Rect r = layout.inspector();
        MosaicDocument.ENode n = selected;
        valueBox.visible = r.visible() && n != null && n.type.equals("mosaic.const");
        maskBox.visible = r.visible() && n != null && n.type.equals("mosaic.swizzle");
        if (!r.visible()) return;
        g.fill(r.x(), r.y(), r.right(), r.bottom(), COL_PANEL);
        g.fill(r.x(), r.y(), r.x() + 1, r.bottom(), COL_PANEL_EDGE);
        g.enableScissor(r.x() + 1, r.y(), r.right(), r.bottom());
        int x = r.x() + 7, w = r.w() - 12, y = r.y() + 6;
        if (selection.size() > 1) {
            valueBox.visible = maskBox.visible = false;
            drawGroupInspector(g, x, y, w, mx, my);
        } else if (n == null) drawGraphSummary(g, x, y, w, mx, my);
        else drawNodeInspector(g, n, x, y, w, mx, my, delta);
        g.disableScissor();
    }

    private int section(GuiGraphicsExtractor g, String title, int x, int y, int w) {
        g.text(font, title, x, y, COL_FAINT);
        g.fill(x + font.width(title) + 4, y + 4, x + w, y + 5, COL_PANEL_EDGE);
        return y + 12;
    }

    private int wrapped(GuiGraphicsExtractor g, String text, int x, int y, int w, int color) {
        for (var line : font.split(Component.literal(text), w)) {
            g.text(font, line, x, y, color);
            y += 10;
        }
        return y;
    }

    private void drawNodeInspector(GuiGraphicsExtractor g, MosaicDocument.ENode n, int x, int y, int w,
                                   int mx, int my, float delta) {
        MosaicCatalog.Entry e = MosaicCatalog.of(n.type);
        g.fill(x - 3, y - 2, x - 1, y + 9, e.category().color);
        g.text(font, font.plainSubstrByWidth(e.label(), w - 4), x + 2, y, COL_TEXT);
        y += 11;
        g.text(font, font.plainSubstrByWidth(n.id + "  " + e.category().label, w), x + 2, y, COL_MUTED);
        y += 12;
        y = wrapped(g, e.help(), x, y, w, COL_MUTED) + 4;
        if (n.id.equals(errorNode)) y = wrapped(g, error, x, y, w, COL_BAD) + 4;

        // parameters
        if (valueBox.visible || maskBox.visible || n.type.equals("mosaic.push") || n.type.equals("mosaic.buffer")) {
            y = section(g, "Parameter", x, y, w);
            if (valueBox.visible || maskBox.visible) {
                EditBox b = valueBox.visible ? valueBox : maskBox;
                b.setX(x);
                b.setY(y);
                b.setWidth(w);
                b.extractRenderState(g, mx, my, delta);
                y += 17;
                g.text(font, valueBox.visible ? "edits apply as you type" : "1..4 of xyzw / rgba", x, y, COL_FAINT);
                y += 12;
            } else {
                boolean push = n.type.equals("mosaic.push");
                g.text(font, push ? "slot" : "index", x, y + 4, COL_MUTED);
                int cx = x + 36;
                chip(g, new Rect(cx, y, 16, 14), "-", mx, my, () -> stepIndex(-1), "previous");
                String v = String.valueOf(n.index);
                g.centeredText(font, v, cx + 36, y + 3, COL_TEXT);
                chip(g, new Rect(cx + 56, y, 16, 14), "+", mx, my, () -> stepIndex(1), "next");
                y += 18;
                g.text(font, push ? "vec4 from rt.push bytes " + n.index * 16 + ".." + (n.index * 16 + 15)
                    : "vec4 #" + n.index + " of buffer5.bin", x, y, COL_FAINT);
                y += 12;
            }
        }

        // inputs
        List<String> ins = inputsOf(n);
        if (!ins.isEmpty()) {
            y = section(g, "Inputs", x, y, w);
            for (String port : ins) {
                String ref = n.inputs.get(port);
                Kind accepted = MosaicGraph.acceptedKind(n.type, port);
                boolean required = MosaicGraph.portsOf(n.type).stream().anyMatch(p -> p.name().equals(port) && p.required());
                int sock = kindColor(ref != null ? refKind(ref) : accepted);
                g.fill(x, y + 2, x + 5, y + 7, sock);
                g.text(font, port, x + 8, y, COL_TEXT);
                int vx = x + 8 + Math.max(font.width(port) + 6, 30);
                if (ref != null) {
                    g.text(font, font.plainSubstrByWidth("<- " + ref, w - (vx - x) - 14), vx, y, COL_MUTED);
                    chip(g, new Rect(x + w - 11, y - 1, 11, 10), "x", mx, my, () -> disconnect(n, port),
                        "disconnect " + port);
                } else {
                    String what = (required ? "needs " : "optional, ") + kindName(accepted);
                    g.text(font, font.plainSubstrByWidth(what, w - (vx - x)), vx, y, required ? COL_WARN : COL_FAINT);
                }
                y += 11;
            }
            y += 2;
        }
        // outputs
        List<String> outs = outputsOf(n);
        if (!outs.isEmpty()) {
            y = section(g, "Output", x, y, w);
            for (String out : outs) {
                Kind k = outputKind(n, out);
                g.fill(x, y + 2, x + 5, y + 7, kindColor(k));
                String label = out.isEmpty() ? (n.type.equals("mosaic.trace") ? "t" : "out") : out;
                g.text(font, label + "  " + (k == null ? "?" : kindName(k)), x + 8, y, COL_TEXT);
                y += 11;
            }
            y += 2;
        }
        y += 2;
        int bw = (w - 4) / 2;
        chip(g, new Rect(x, y, bw, 16), "Duplicate", mx, my, this::duplicateSelected, "Ctrl+D");
        chip(g, new Rect(x + bw + 4, y, bw, 16), "Delete", mx, my, this::deleteSelected, "Del");
        y += 20;
        chip(g, new Rect(x, y, w, 14), "Center on node", mx, my, () -> focusNode(n), null);
    }

    private void drawGroupInspector(GuiGraphicsExtractor g, int x, int y, int w, int mx, int my) {
        g.text(font, selection.size() + " nodes selected", x, y, COL_TEXT);
        y += 12;
        java.util.Map<String, Integer> counts = new java.util.LinkedHashMap<>();
        for (MosaicDocument.ENode n : group()) counts.merge(MosaicCatalog.of(n.type).label(), 1, Integer::sum);
        int shown = 0;
        for (var e : counts.entrySet()) {
            if (shown++ == 6) {
                g.text(font, "...", x, y, COL_MUTED);
                y += 10;
                break;
            }
            g.text(font, font.plainSubstrByWidth(e.getValue() + " x " + e.getKey(), w), x, y, COL_MUTED);
            y += 10;
        }
        y += 4;
        y = section(g, "Layout", x, y, w);
        chip(g, new Rect(x, y, w, 14), "Arrange by dependency", mx, my, this::arrange,
            shortcutText(MosaicKeymap.Action.ARRANGE));
        y += 17;
        int bw = (w - 4) / 2;
        chip(g, new Rect(x, y, bw, 14), "Align left", mx, my, () -> align(true), null);
        chip(g, new Rect(x + bw + 4, y, bw, 14), "Align top", mx, my, () -> align(false), null);
        y += 20;
        y = section(g, "Group", x, y, w);
        chip(g, new Rect(x, y, bw, 14), "Duplicate", mx, my, this::duplicateSelected,
            shortcutText(MosaicKeymap.Action.DUPLICATE));
        chip(g, new Rect(x + bw + 4, y, bw, 14), "Copy", mx, my, this::copySelected,
            "copies JSON to the clipboard - paste in any pack");
        y += 17;
        chip(g, new Rect(x, y, w, 14), "Delete " + selection.size() + " nodes", mx, my, this::deleteSelected,
            shortcutText(MosaicKeymap.Action.DELETE));
        y += 20;
        wrapped(g, "Drag any selected node to move the group. Arrows nudge it (Shift: a grid step). Shift+click adds"
            + " or removes a node.", x, y, w, COL_FAINT);
    }

    private void drawGraphSummary(GuiGraphicsExtractor g, int x, int y, int w, int mx, int my) {
        g.text(font, "Graph", x, y, COL_TEXT);
        y += 12;
        g.text(font, doc.nodes.size() + " nodes", x, y, COL_MUTED);
        y += 11;
        if (graph != null) {
            g.text(font, "valid - ready to apply", x, y, COL_OK);
            y += 12;
        } else {
            y = wrapped(g, error, x, y, w, COL_BAD) + 2;
            if (errorNode != null) {
                MosaicDocument.ENode bad = doc.byId(errorNode);
                chip(g, new Rect(x, y, w, 14), "Show " + errorNode, mx, my, () -> focusNode(bad), null);
                y += 18;
            }
        }
        y += 4;
        y = section(g, "Add nodes", x, y, w);
        y = wrapped(g, "Click or drag from the palette. Double-click, right-click or " + firstShortcut(
            MosaicKeymap.Action.QUICK_ADD) + " on the canvas to search. Right-click a palette entry to pin it.",
            x, y, w, COL_MUTED) + 4;
        y = section(g, "Wires", x, y, w);
        y = wrapped(g, "Drag output to input (or back). Drop on a node to use its first free port, on empty space to"
            + " add a node. Right-click an input to cut.", x, y, w, COL_MUTED) + 4;
        y = section(g, "Keys", x, y, w);
        MosaicKeymap.Action[] shown = {MosaicKeymap.Action.UNDO, MosaicKeymap.Action.REDO, MosaicKeymap.Action.SELECT_ALL,
            MosaicKeymap.Action.COPY, MosaicKeymap.Action.PASTE, MosaicKeymap.Action.DUPLICATE, MosaicKeymap.Action.DELETE,
            MosaicKeymap.Action.ARRANGE, MosaicKeymap.Action.FIT, MosaicKeymap.Action.TOGGLE_SNAP,
            MosaicKeymap.Action.CODE, MosaicKeymap.Action.APPLY};
        int kw = 0;
        for (MosaicKeymap.Action a : shown) kw = Math.max(kw, font.width(firstShortcut(a)));
        kw = Math.min(kw + 6, w / 2);
        for (MosaicKeymap.Action a : shown) {
            String k = firstShortcut(a);
            if (k.isEmpty()) continue;
            g.text(font, font.plainSubstrByWidth(k, kw - 4), x, y, COL_TEXT);
            g.text(font, font.plainSubstrByWidth(a.label, w - kw), x + kw, y, COL_MUTED);
            y += 10;
        }
        chip(g, new Rect(x, y + 2, w, 14), "All shortcuts... " + firstShortcut(MosaicKeymap.Action.HELP), mx, my,
            () -> toggleWindow(Win.KEYS), null);
        y += 20;
        y += 4;
        y = section(g, "Socket colors", x, y, w);
        Kind[] kinds = {Kind.F, Kind.V2, Kind.V3, Kind.V4, null};
        int cx = x;
        for (Kind k : kinds) {
            String label = kindName(k);
            int lw = font.width(label) + 14;
            if (cx + lw > x + w) {
                cx = x;
                y += 11;
            }
            g.fill(cx, y + 2, cx + 5, y + 7, kindColor(k));
            g.text(font, label, cx + 8, y, COL_MUTED);
            cx += lw;
        }
    }

    private Rect hotClip; // while drawing a scrolled window: chips outside it are not clickable

    private void chip(GuiGraphicsExtractor g, Rect r, String label, int mx, int my, Runnable action, String tooltip) {
        if (hotClip != null && !within(r, hotClip)) {
            mx = my = -1; // drawn (clipped) but neither hot nor clickable
            action = null;
        }
        boolean hot = r.contains(mx, my);
        g.fill(r.x(), r.y(), r.right(), r.bottom(), hot ? COL_CHIP_HOT : COL_CHIP);
        g.outline(r.x(), r.y(), r.w(), r.h(), hot ? COL_HOVER : COL_PANEL_EDGE);
        g.centeredText(font, font.plainSubstrByWidth(label, r.w() - 4), r.x() + r.w() / 2, r.y() + (r.h() - 8) / 2,
            COL_TEXT);
        if (action != null || tooltip != null) hots.add(new Hot(r, action, tooltip));
    }

    // ---------------------------------------------------------------- windows

    private void drawWindow(GuiGraphicsExtractor g, int mx, int my) {
        Rect r = windowRect();
        g.fill(r.x() + 4, r.y() + 4, r.right() + 4, r.bottom() + 4, 0x70000000);
        g.fill(r.x(), r.y(), r.right(), r.bottom(), 0xFC1B2130);
        g.fill(r.x(), r.y(), r.right(), r.y() + 14, COL_BAR);
        g.outline(r.x(), r.y(), r.w(), r.h(), COL_HOVER);
        String title = switch (win) {
            case KEYS -> "Shortcuts";
            case CODE -> "Generated shader - " + (codeSlang ? "Slang" : "GLSL");
            default -> "Editor settings";
        };
        g.text(font, title, r.x() + 6, r.y() + 3, COL_TEXT);
        chip(g, new Rect(r.right() - 13, r.y() + 2, 11, 10), "x", mx, my, this::closeWindow, "close (Esc)");

        Rect body = new Rect(r.x() + 1, r.y() + 15, r.w() - 2, r.h() - 16);
        g.enableScissor(body.x(), body.y(), body.right(), body.bottom());
        hotClip = body;
        int contentH = switch (win) {
            case KEYS -> drawKeys(g, body, mx, my);
            case CODE -> drawCode(g, body, mx, my);
            default -> drawSettings(g, body, mx, my);
        };
        hotClip = null;
        g.disableScissor();
        int max = Math.max(0, contentH - body.h());
        if (winScroll > max) winScroll = max;
        if (max > 0) {
            int barH = Math.max(12, body.h() * body.h() / contentH);
            int barY = body.y() + (body.h() - barH) * winScroll / max;
            g.fill(r.right() - 4, barY, r.right() - 2, barY + barH, 0x60FFFFFF);
        }
    }

    /** The Keys window: every action with its shortcuts; click one to rebind, + to add another. */
    private int drawKeys(GuiGraphicsExtractor g, Rect body, int mx, int my) {
        int x = body.x() + 6, w = body.w() - 14, top = body.y() + 4 - winScroll, y = top;
        y = wrapped(g, "Click a shortcut and press new keys. Del clears it, Esc cancels. A key used elsewhere moves here.",
            x, y, w, COL_MUTED) + 4;
        int labelW = Math.min(w / 2, 120);
        for (MosaicKeymap.Action a : MosaicKeymap.Action.values()) {
            g.text(font, font.plainSubstrByWidth(a.label, labelW - 4), x, y + 2, COL_TEXT);
            List<MosaicKeymap.Combo> combos = prefs.keys.get(a);
            int cx = x + labelW, rowY = y;
            for (int i = 0; i <= combos.size(); i++) {
                boolean adding = i == combos.size();
                boolean waiting = rebinding == a && rebindIndex == i;
                String label = waiting ? "press keys..." : adding ? "+" : MosaicKeymap.format(combos.get(i));
                int cw = font.width(label) + 8;
                if (cx + cw > x + w && cx > x + labelW) {
                    cx = x + labelW;
                    rowY += 13;
                }
                final int slot = i;
                Rect cr = new Rect(cx, rowY, cw, 12);
                chip(g, cr, label, mx, my, () -> {
                    rebinding = a;
                    rebindIndex = slot;
                }, adding ? "add another shortcut" : "click, then press the new keys");
                if (waiting) g.outline(cr.x() - 1, cr.y() - 1, cr.w() + 2, cr.h() + 2, COL_WARN);
                cx += cw + 3;
            }
            y = rowY + 14;
        }
        y += 4;
        chip(g, new Rect(x, y, Math.min(w, 140), 14), "Reset all shortcuts", mx, my, () -> {
            prefs.keys.resetToDefaults();
            savePrefs();
            rebuildWidgets();
            flash("shortcuts reset to defaults", COL_MUTED);
        }, null);
        y += 20;
        return y - top + 4;
    }

    /** The source the graph compiles to (regenerated only when the graph changes). */
    private String generatedCode() {
        if (graph == null) return null;
        if (codeFor != graph || codeForSlang != codeSlang || codeCache == null) {
            codeCache = codeSlang ? MosaicCodegen.slang(graph) : MosaicCodegen.glsl(graph);
            codeFor = graph;
            codeForSlang = codeSlang;
        }
        return codeCache;
    }

    /** The Code window: a fixed tool row (GLSL / Slang / Copy) over the scrolling source, drawn at 80 %. */
    private int drawCode(GuiGraphicsExtractor g, Rect body, int mx, int my) {
        int x = body.x() + 6, w = body.w() - 14;
        String code = generatedCode();
        // tool row does not scroll
        Rect saved = hotClip;
        hotClip = new Rect(body.x(), body.y(), body.w(), 18);
        g.fill(body.x(), body.y(), body.right(), body.y() + 18, 0xFC1B2130);
        chip(g, new Rect(x, body.y() + 3, 44, 12), (codeSlang ? "" : "> ") + "GLSL", mx, my, () -> {
            codeSlang = false;
            winScroll = 0;
        }, "what the game compiles at runtime");
        chip(g, new Rect(x + 48, body.y() + 3, 46, 12), (codeSlang ? "> " : "") + "Slang", mx, my, () -> {
            codeSlang = true;
            winScroll = 0;
        }, "the same graph for the AOT Slang build");
        if (code != null)
            chip(g, new Rect(x + w - 50, body.y() + 3, 50, 12), "Copy", mx, my, () -> {
                minecraft.keyboardHandler.setClipboard(code);
                flash("copied the " + (codeSlang ? "Slang" : "GLSL") + " source", COL_OK);
            }, "copy the whole source");
        hotClip = saved;
        if (code == null) {
            wrapped(g, "The graph has an error, so there is no shader yet: " + error, x, body.y() + 24, w, COL_BAD);
            return body.h();
        }
        g.enableScissor(body.x(), body.y() + 18, body.right(), body.bottom());
        float scale = 0.8f;
        int lineH = 8;
        String[] lines = code.split("\n", -1);
        g.pose().pushMatrix();
        g.pose().translate(x, body.y() + 21 - winScroll);
        g.pose().scale(scale);
        int maxW = (int) (w / scale);
        int first = Math.max(0, (winScroll - 4) / lineH - 1);
        int visible = body.h() / lineH + 3;
        for (int i = first; i < Math.min(lines.length, first + visible); i++) {
            String line = lines[i].replace("\t", "    ");
            int color = line.trim().startsWith("//") ? COL_FAINT
                : line.startsWith("layout") || line.startsWith("[[") || line.startsWith("#") ? 0xFF9CC4FF : COL_TEXT;
            String num = String.valueOf(i + 1);
            g.text(font, num, 22 - font.width(num), (int) (i * lineH / scale), COL_FAINT);
            g.text(font, font.plainSubstrByWidth(line, maxW - 30), 28, (int) (i * lineH / scale), color);
        }
        g.pose().popMatrix();
        g.disableScissor();
        return lines.length * lineH + 26;
    }

    /** The Settings window: look and behaviour, saved at once. */
    private int drawSettings(GuiGraphicsExtractor g, Rect body, int mx, int my) {
        int x = body.x() + 6, w = body.w() - 14, top = body.y() + 4 - winScroll, y = top;
        y = toggleRow(g, x, y, w, "Snap nodes to the grid", prefs.snap, mx, my, () -> run(MosaicKeymap.Action.TOGGLE_SNAP));
        y = stepperRow(g, x, y, w, "Grid step", String.valueOf(prefs.grid), mx, my,
            () -> prefs.grid = Math.max(8, prefs.grid - 8), () -> prefs.grid = Math.min(64, prefs.grid + 8));
        y = toggleRow(g, x, y, w, "Port names on nodes", prefs.portLabels, mx, my,
            () -> prefs.portLabels = !prefs.portLabels);
        y = toggleRow(g, x, y, w, "Left-drag on empty canvas selects (off: pans)", prefs.leftDragSelects, mx, my,
            () -> prefs.leftDragSelects = !prefs.leftDragSelects);
        y = stepperRow(g, x, y, w, "Wire thickness", String.valueOf(prefs.wireThickness), mx, my,
            () -> prefs.wireThickness = Math.max(1, prefs.wireThickness - 1),
            () -> prefs.wireThickness = Math.min(3, prefs.wireThickness + 1));
        y = toggleRow(g, x, y, w, "Live on when the editor opens", prefs.liveByDefault, mx, my,
            () -> prefs.liveByDefault = !prefs.liveByDefault);
        y = toggleRow(g, x, y, w, "Preview on when the editor opens", prefs.previewByDefault, mx, my,
            () -> prefs.previewByDefault = !prefs.previewByDefault);
        y += 4;
        int bw = (w - 4) / 2;
        chip(g, new Rect(x, y, bw, 14), "Shortcuts...", mx, my, () -> toggleWindow(Win.KEYS), null);
        chip(g, new Rect(x + bw + 4, y, bw, 14), "Reset look", mx, my, () -> {
            prefs.resetLook();
            savePrefs();
        }, "grid, labels, wires and defaults; keys and favorites stay");
        y += 20;
        y = wrapped(g, "Saved in config/bedray/mosaic-editor.json", x, y, w, COL_FAINT);
        return y - top + 4;
    }

    private int toggleRow(GuiGraphicsExtractor g, int x, int y, int w, String label, boolean on, int mx, int my,
                          Runnable flip) {
        Rect row = new Rect(x - 2, y, w + 4, 13);
        boolean clickable = hotClip == null || within(row, hotClip);
        boolean hot = clickable && row.contains(mx, my);
        if (hot) g.fill(row.x(), row.y(), row.right(), row.bottom(), COL_ROW_HOT);
        g.outline(x, y + 2, 9, 9, hot ? COL_HOVER : COL_PANEL_EDGE);
        if (on) g.fill(x + 2, y + 4, x + 7, y + 9, COL_OK);
        g.text(font, font.plainSubstrByWidth(label, w - 14), x + 14, y + 3, COL_TEXT);
        if (clickable) hots.add(new Hot(row, () -> {
            flip.run();
            savePrefs();
        }, null));
        return y + 15;
    }

    private int stepperRow(GuiGraphicsExtractor g, int x, int y, int w, String label, String value, int mx, int my,
                           Runnable down, Runnable up) {
        g.text(font, font.plainSubstrByWidth(label, w - 62), x + 14, y + 3, COL_TEXT);
        int cx = x + w - 56;
        chip(g, new Rect(cx, y, 14, 12), "-", mx, my, () -> {
            down.run();
            savePrefs();
        }, null);
        g.centeredText(font, value, cx + 28, y + 2, COL_TEXT);
        chip(g, new Rect(cx + 42, y, 14, 12), "+", mx, my, () -> {
            up.run();
            savePrefs();
        }, null);
        return y + 15;
    }

    private void drawContext(GuiGraphicsExtractor g, int mx, int my) {
        Rect r = windowRect();
        g.fill(r.x() + 3, r.y() + 3, r.right() + 3, r.bottom() + 3, 0x80000000);
        g.fill(r.x(), r.y(), r.right(), r.bottom(), 0xFC1B2130);
        g.outline(r.x(), r.y(), r.w(), r.h(), COL_HOVER);
        int y = r.y() + 2;
        for (MenuItem item : contextItems()) {
            Rect row = new Rect(r.x() + 2, y, r.w() - 4, 12);
            if (row.contains(mx, my)) g.fill(row.x(), row.y(), row.right(), row.bottom(), COL_ROW_HOT);
            g.text(font, item.label(), row.x() + 4, y + 2, item.label().startsWith("Delete") ? COL_BAD : COL_TEXT);
            if (!item.keys().isEmpty())
                g.text(font, item.keys(), row.right() - font.width(item.keys()) - 4, y + 2, COL_FAINT);
            hots.add(new Hot(row, item.action(), null));
            y += 12;
        }
    }

    // ---------------------------------------------------------------- status line

    private void drawStatus(GuiGraphicsExtractor g, int mx, int my) {
        Rect s = layout.status();
        g.fill(s.x(), s.y(), s.right(), s.bottom(), COL_BAR);
        g.fill(s.x(), s.y(), s.right(), s.y() + 1, COL_PANEL_EDGE);
        String text;
        int color;
        if (flash != null && System.currentTimeMillis() < flashUntil) {
            text = flash;
            color = flashColor;
        } else if (graph != null) {
            text = "OK - " + doc.nodes.size() + " nodes" + (doc.file == null ? " - Apply asks where to save"
                : live ? (doc.dirty ? " - saving..." : " - live, saved") : doc.dirty ? " - unsaved" : " - saved");
            color = COL_OK;
        } else {
            text = "Error: " + error + (errorNode != null ? "  (click to show)" : "");
            color = COL_BAD;
            if (errorNode != null) {
                MosaicDocument.ENode bad = doc.byId(errorNode);
                hots.add(new Hot(new Rect(s.x(), s.y(), Math.min(s.w(), font.width(text) + 12), s.h()),
                    () -> focusNode(bad), null));
            }
        }
        String hint = hint(mx, my);
        int hintW = hint.isEmpty() ? 0 : Math.min(font.width(hint), s.w() / 2);
        g.text(font, font.plainSubstrByWidth(text, s.w() - hintW - 18), s.x() + 6, s.y() + 3, color);
        if (hintW > 0) g.text(font, font.plainSubstrByWidth(hint, hintW), s.right() - hintW - 6, s.y() + 3, COL_FAINT);
    }

    /** One line of help for whatever is under the mouse, so the next step is always on screen. */
    private String hint(int mx, int my) {
        if (drag == Drag.WIRE_FROM_OUTPUT || drag == Drag.WIRE_FROM_INPUT)
            return "drop on a socket or node to connect, on empty space to add a node";
        if (drag == Drag.PALETTE) return "drop on the canvas to place";
        if (drag == Drag.NODE) return selection.size() > 1 ? "moving " + selection.size() + " nodes" : "release to place";
        if (drag == Drag.BOX) return "release to select" + (boxAdditive ? " (adding)" : " - Shift adds to the selection");
        if (rebinding != null) return "press the new shortcut - Esc cancels - Del clears it";
        if (quickOpen) return "type to filter - Up/Down - Enter adds - Esc cancels";
        if (win == Win.CONTEXT) return "pick an action - click outside closes";
        if (overWindow(mx, my)) return "drag the title bar to move - x closes";
        if (layout.palette().contains(mx, my)) return "click to add - drag onto the canvas - right-click to pin";
        if (!layout.canvas().contains(mx, my)) return "";
        SocketHit s = socketAt(mx, my);
        if (s != null) return s.input() ? (s.node().inputs.containsKey(s.name())
            ? "drag to re-route - right-click to cut" : "drag to an output") : "drag to an input";
        if (nodeAt(mx, my) != null) return "drag to move" + (prefs.snap ? " (Alt: no snap)" : "")
            + " - Shift+click selects more - right-click for actions";
        return (prefs.leftDragSelects ? "drag to select - right/middle-drag to pan" : "drag to pan")
            + " - wheel zooms - double-click adds";
    }

    // ---------------------------------------------------------------- quick-add popup

    private Rect quickRect() { return new Rect(quickX, quickY, 160, 24 + 9 * 12 + 4); }

    private void drawQuick(GuiGraphicsExtractor g, int mx, int my, float delta) {
        Rect q = quickRect();
        g.fill(q.x() + 3, q.y() + 3, q.right() + 3, q.bottom() + 3, 0x80000000);
        g.fill(q.x(), q.y(), q.right(), q.bottom(), 0xFC1B2130);
        g.outline(q.x(), q.y(), q.w(), q.h(), COL_HOVER);
        quickBox.visible = true;
        quickBox.setX(q.x() + 4);
        quickBox.setY(q.y() + 4);
        quickBox.setWidth(q.w() - 8);
        quickBox.extractRenderState(g, mx, my, delta);
        List<MosaicCatalog.Entry> hits = quickHits();
        if (hits.isEmpty()) {
            g.text(font, "no node matches", q.x() + 8, q.y() + 26, COL_MUTED);
            return;
        }
        quickSel = Math.min(quickSel, hits.size() - 1);
        int first = Math.max(0, quickSel - 8), y = q.y() + 22;
        for (int i = first; i < Math.min(hits.size(), first + 9); i++) {
            MosaicCatalog.Entry e = hits.get(i);
            Rect r = new Rect(q.x() + 2, y, q.w() - 4, 12);
            if (i == quickSel || r.contains(mx, my)) g.fill(r.x(), r.y(), r.right(), r.bottom(), COL_ROW_HOT);
            g.fill(r.x() + 4, y + 4, r.x() + 7, y + 7, e.category().color);
            g.text(font, e.label(), r.x() + 11, y + 2, COL_TEXT);
            String cat = e.category().label;
            g.text(font, cat, r.right() - font.width(cat) - 4, y + 2, COL_FAINT);
            hots.add(new Hot(r, () -> quickAdd(e), null));
            y += 12;
        }
    }

    // ---------------------------------------------------------------- save dialog

    private Rect dialogRect() {
        int w = Math.min(280, width - 20), h = 84;
        return new Rect((width - w) / 2, (height - h) / 2, w, h);
    }

    private void drawSaveDialog(GuiGraphicsExtractor g, int mx, int my, float delta) {
        g.fill(0, 0, width, height, 0x90000000);
        Rect d = dialogRect();
        g.fill(d.x(), d.y(), d.right(), d.bottom(), 0xFC1B2130);
        g.outline(d.x(), d.y(), d.w(), d.h(), COL_HOVER);
        g.text(font, "Save as a new shader pack", d.x() + 8, d.y() + 7, COL_TEXT);
        g.text(font, font.plainSubstrByWidth("shaderpacks/<name>/shaders/bedray/mosaic.json", d.w() - 16),
            d.x() + 8, d.y() + 19, COL_MUTED);
        packBox.visible = true;
        packBox.setX(d.x() + 8);
        packBox.setY(d.y() + 32);
        packBox.setWidth(d.w() - 16);
        packBox.extractRenderState(g, mx, my, delta);
        packSave.setPosition(d.right() - 132, d.bottom() - 24);
        packCancel.setPosition(d.right() - 68, d.bottom() - 24);
        packSave.extractRenderState(g, mx, my, delta);
        packCancel.extractRenderState(g, mx, my, delta);
    }

    // ---------------------------------------------------------------- tooltips

    private void hoverTooltip(GuiGraphicsExtractor g, int mx, int my) {
        if (drag != Drag.NONE || rebinding != null) return;
        Rect top = win == Win.CONTEXT || overWindow(mx, my) ? windowRect()
            : quickOpen && quickRect().contains(mx, my) ? quickRect() : null;
        for (int i = hots.size() - 1; i >= 0; i--) {
            Hot h = hots.get(i);
            if (top != null && !within(h.rect(), top)) continue;
            if (h.rect().contains(mx, my) && h.tooltip() != null) {
                String tip = h.tooltip();
                if (tip.startsWith("mosaic.")) {
                    MosaicCatalog.Entry e = MosaicCatalog.of(tip);
                    tip = e.label() + "\n" + e.help();
                }
                g.setComponentTooltipForNextFrame(font, tip.lines().map(l -> (Component) Component.literal(l)).toList(),
                    mx, my);
                return;
            }
        }
        if (!layout.canvas().contains(mx, my) || quickOpen || top != null) return;
        SocketHit s = socketAt(mx, my);
        if (s != null) {
            String text;
            if (s.input()) {
                String ref = s.node().inputs.get(s.name());
                Kind k = ref != null ? refKind(ref) : MosaicGraph.acceptedKind(s.node().type, s.name());
                text = s.name() + ": " + kindName(k) + (ref != null ? " <- " + ref : " (not connected)");
            } else {
                text = (s.name().isEmpty() ? "out" : s.name()) + ": " + kindName(outputKind(s.node(), s.name()));
            }
            g.setTooltipForNextFrame(font, Component.literal(text), mx, my);
        }
    }
}
