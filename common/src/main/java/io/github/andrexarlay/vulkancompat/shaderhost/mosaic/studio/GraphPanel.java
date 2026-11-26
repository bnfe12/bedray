package io.github.andrexarlay.vulkancompat.shaderhost.mosaic.studio;

import io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicSlangGraph;

import io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicCatalog;
import io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicDocument;
import io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicFunction;
import io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicGraph;
import io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicWorld;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.CubicCurve2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

import static io.github.andrexarlay.vulkancompat.shaderhost.mosaic.studio.StudioUi.*;

/**
 * The node canvas. Mouse: drag nodes, drag sockets to wire, drag empty space to box-select, middle/right/Alt drag to
 * pan, wheel to zoom (or, in touchpad mode, two-finger scroll pans and pinch zooms). Keyboard: every operation has
 * a key path (see {@link StudioKeys}); the keyboard node is outlined. Frames group nodes and carry a title.
 */
final class GraphPanel extends JComponent {

    static final int HEAD = 22, ROW = 18, FRAME_HEAD = 26;

    final StudioModel model;
    double panX = 60, panY = 60, zoom = 1;
    Point mouseScreen = new Point(200, 200);
    private boolean fitted, sizeChecked;

    // gestures
    private MosaicDocument.ENode dragNode;
    private final Map<MosaicDocument.ENode, Point> dragStarts = new LinkedHashMap<>();
    private MosaicDocument.Frame dragFrame, resizeFrame;
    private Point frameStart;
    private Point pressWorld, pressScreen, boxEnd;
    private boolean panning, boxing;
    private double panStartX, panStartY;
    private String gestureBefore, gestureLabel;
    private Socket wireFrom;
    private final List<Point> knife = new ArrayList<>();   // Ctrl+right drag: cut every wire the stroke crosses
    private final Runnable listener = this::repaint;

    record Socket(MosaicDocument.ENode node, String name, boolean input, Point at) {}

    GraphPanel(StudioModel model) {
        this.model = model;
        setFocusable(true);
        setOpaque(true);
        setFocusTraversalKeysEnabled(false); // Tab cycles nodes here instead of leaving the canvas
        MouseAdapter m = new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) { press(e); }
            @Override public void mouseDragged(MouseEvent e) { drag(e); }
            @Override public void mouseReleased(MouseEvent e) { release(e); }
            @Override public void mouseMoved(MouseEvent e) {
                boolean overMap = minimapRect != null && minimapRect.contains(e.getPoint());
                if (overMap != minimapHover) {
                    minimapHover = overMap;
                    repaint();
                }
                mouseScreen = e.getPoint();
                Tool t = toolAt(e.getPoint());
                if (t != hoverTool) {
                    hoverTool = t;
                    repaint();
                }
                if (t != null) {
                    setToolTipText(t.tip());
                    return;
                }
                Socket s = socketAt(e.getPoint());
                setToolTipText(s == null ? nodeTip(e.getPoint()) : socketTip(s));
            }
            @Override public void mouseEntered(MouseEvent e) { model.activeGraph = GraphPanel.this; }
            @Override public void mouseWheelMoved(MouseWheelEvent e) { wheel(e); }
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() != 2 || !SwingUtilities.isLeftMouseButton(e)) return;
                MosaicDocument.Frame f = frameTitleAt(e.getPoint());
                MosaicDocument.ENode n = nodeAt(e.getPoint());
                if (n != null && (n.type.startsWith(MosaicSlangGraph.FN) || n.type.equals(MosaicSlangGraph.CODE)) && model.openFunction != null
                    && toWorld(e.getPoint()).y >= n.y + HEAD) {
                    // into a Bedray function: its body as nodes in a tab of its own (a code block: its Slang in the inspector)
                    if (n.type.startsWith(MosaicSlangGraph.FN)) model.openFunction.accept(n.type.substring(MosaicSlangGraph.FN.length()));
                    else model.say("A code block: edit its Slang in the inspector", StudioUi.MUTED);
                } else if (n != null && n.type.equals("bedray.render") && model.openRenderer != null && toWorld(e.getPoint()).y >= n.y + HEAD) {
                    model.openRenderer.run(); // Bedray's own renderer: its stages and settings
                } else if (n != null && StudioModel.USER_STAGES.containsKey(n.type) && model.openFunction != null && toWorld(e.getPoint()).y >= n.y + HEAD) {
                    model.openFunction.accept(StudioModel.USER_STAGES.get(n.type)); // your stage: its graph
                } else if (n != null && io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicRenderer.isStage(n.type) && model.openStage != null
                    && toWorld(e.getPoint()).y >= n.y + HEAD) {
                    model.openStage.accept(n.type); // into the stage: its parameters, modules and functions
                } else if (n != null && toWorld(e.getPoint()).y < n.y + HEAD) renameDialog();
                else if (n == null && f != null) {
                    model.selectedFrame = f;
                    renameDialog();
                } else if (n == null) quickAdd(e.getPoint());
            }
        };
        addMouseListener(m);
        addMouseMotionListener(m);
        addMouseWheelListener(m);
        addFocusListener(new FocusAdapter() {
            @Override public void focusGained(FocusEvent e) {
                model.activeGraph = GraphPanel.this;
                repaint();
            }
            @Override public void focusLost(FocusEvent e) { repaint(); }
        });
        ToolTipManager.sharedInstance().registerComponent(this);
        setTransferHandler(new TransferHandler() {
            @Override public boolean canImport(TransferSupport s) {
                return s.isDataFlavorSupported(java.awt.datatransfer.DataFlavor.stringFlavor);
            }
            @Override public boolean importData(TransferSupport s) {
                try {
                    String text = (String) s.getTransferable().getTransferData(java.awt.datatransfer.DataFlavor.stringFlavor);
                    if (text.startsWith(MaterialPanels.DRAG_IMAGE)) {
                        String[] parts = text.substring(MaterialPanels.DRAG_IMAGE.length()).split("\\|", 2);
                        StudioLibrary.Source src = StudioLibrary.fromId(parts[0]);
                        if (src == null || parts.length < 2) return false;
                        Point w = toWorld(s.getDropLocation().getDropPoint());
                        if (model.workspace != StudioModel.Workspace.MATERIALS) {
                            model.say("Images go into materials: switch to Materials (or double-click the texture in the Library)", MUTED);
                            return false;
                        }
                        model.addImageNode(src, parts[1], new Point(w.x - W() / 2, w.y - HEAD / 2));
                        requestFocusInWindow();
                        return true;
                    }
                    if (!text.startsWith(NodeTree.DRAG_PREFIX)) return false;
                    String type = text.substring(NodeTree.DRAG_PREFIX.length());
                    StudioModel.Item it = model.items().stream().filter(x -> x.type().equals(type)).findFirst().orElse(null);
                    if (it == null) return false;
                    Point w = toWorld(s.getDropLocation().getDropPoint());
                    model.add(it, new Point(w.x - W() / 2, w.y - HEAD / 2), viewCenter());
                    requestFocusInWindow();
                    return true;
                } catch (Exception e) {
                    return false;
                }
            }
        });
        model.listen(listener);
        if (model.activeGraph == null) model.activeGraph = this;
        // resizing the area keeps what was in the middle in the middle; a graph that ends up out of sight is fitted again
        addComponentListener(new java.awt.event.ComponentAdapter() {
            int lastW = -1, lastH = -1;
            @Override public void componentResized(java.awt.event.ComponentEvent e) {
                int w = getWidth(), h = getHeight();
                if (lastW > 0 && w > 0 && fitted) {
                    panX += (w - lastW) / 2.0;
                    panY += (h - lastH) / 2.0;
                    if (!anyNodeVisible()) fitReadable();
                    repaint();
                }
                lastW = w;
                lastH = h;
            }
        });
    }

    private boolean anyNodeVisible() {
        for (MosaicDocument.ENode n : model.doc.nodes) {
            double x = n.x * zoom + panX, y = n.y * zoom + panY;
            if (x + W() * zoom > 0 && x < getWidth() && y + height(n) * zoom > 0 && y < getHeight()) return true;
        }
        return model.doc.nodes.isEmpty();
    }

    /** The view was restored from project metadata: do not auto-fit on the first paint. */
    void markFitted() {
        fitted = true;
        sizeChecked = false; // look again at the restored view on the next paint
    }

    void dispose() {
        model.unlisten(listener);
        if (model.activeGraph == this) model.activeGraph = null;
    }

    // ================================================================== geometry

    static int W() { return StudioUi.settings.nodeWidth; }

    Point toWorld(Point s) { return new Point((int) Math.round((s.x - panX) / zoom), (int) Math.round((s.y - panY) / zoom)); }

    Point viewCenter() {
        Point c = toWorld(new Point(getWidth() / 2, getHeight() / 3));
        return new Point(c.x - W() / 2, c.y);
    }

    Point mouseWorld() { return toWorld(mouseScreen); }

    int height(MosaicDocument.ENode n) {
        int rows = Math.max(Math.max(inputs(n).size(), outputs(n).size()), 1);
        return HEAD + rows * ROW + (paramText(n).isEmpty() ? 0 : ROW) + 8 + codeLines(n).size() * CODE_LINE + (codeLines(n).isEmpty() ? 0 : 10);
    }

    static final int CODE_LINE = 12;

    /** The first lines of a Slang block, shown on the node (the inspector edits all of it). */
    static List<String> codeLines(MosaicDocument.ENode n) {
        if (!n.type.equals(MosaicSlangGraph.CODE) || n.mask == null) return List.of();
        List<String> out = new ArrayList<>();
        for (String l : MosaicSlangGraph.codeSignature(n.mask).body().strip().split("\n")) {
            if (l.isBlank()) continue;
            if (out.size() == 7) {
                out.add("...");
                break;
            }
            out.add(l.replace("\t", "    ").stripTrailing());
        }
        return out;
    }

    List<String> inputs(MosaicDocument.ENode n) {
        if (n.type.equals("slang.module")) return List.of("imports");
        if (n.type.equals("slang.pack")) return List.of("passes");
        List<String> out = new ArrayList<>();
        if (MosaicSlangGraph.handles(n.type)) {
            for (MosaicSlangGraph.SPort p : MosaicSlangGraph.inputs(n.type, n.mask, n.index)) out.add(p.name());
            return out;
        }
        List<MosaicGraph.Port> ports = MosaicGraph.portsOf(n.type, model.functions());
        boolean compact = !n.expanded && ports.size() > compactFrom();
        for (MosaicGraph.Port p : ports)
            if (!compact || p.required() || n.inputs.containsKey(p.name()) || n.defaults.containsKey(p.name())) out.add(p.name());
        return out;
    }

    /** Nodes with more inputs than this show only the required, wired and changed ones until Ctrl+H expands them. */
    private void runAction(StudioKeys.Action a) { model.runAction.accept(a); }

    /** Settings > Editor: how many inputs a node shows before it folds the unused ones. */
    static int compactFrom() { return StudioUi.settings == null ? 6 : StudioUi.settings.compactInputs; }

    /** Inputs hidden on a compact node (edited in the inspector, shown with Ctrl+H). */
    int hiddenInputs(MosaicDocument.ENode n) {
        if (n.type.equals("slang.module") || n.type.equals("slang.pack") || MosaicSlangGraph.handles(n.type)) return 0;
        return MosaicGraph.portsOf(n.type, model.functions()).size() - inputs(n).size();
    }

    static List<String> outputs(MosaicDocument.ENode n) {
        List<String> out = new ArrayList<>();
        if (n.type.equals("mosaic.output") || n.type.equals("slang.pack") || n.type.equals("bedray.output")) return out;
        if (MosaicSlangGraph.handles(n.type)) {
            for (MosaicSlangGraph.SPort p : MosaicSlangGraph.outputs(n.type, n.mask)) out.add(p.name());
            return out;
        }
        out.add("");
        out.addAll(MosaicGraph.namedOutputs(n.type));
        return out;
    }

    Point inSocket(MosaicDocument.ENode n, int row) { return new Point(n.x, n.y + HEAD + row * ROW + ROW / 2 + 2); }

    Point outSocket(MosaicDocument.ENode n, int row) { return new Point(n.x + W(), n.y + HEAD + row * ROW + ROW / 2 + 2); }

    MosaicGraph.Kind outKind(MosaicDocument.ENode n, String out) {
        if (MosaicSlangGraph.handles(n.type)) {
            MosaicSlangGraph.SPort p = MosaicSlangGraph.output(n.type, n.mask, out);
            if (p != null) return p.kind();
        }
        if (!out.isEmpty()) return MosaicGraph.outputKind(n.type, out);
        return model.analysis == null ? null : model.analysis.kinds().get(n.id);
    }

    MosaicGraph.Kind refKind(String ref) {
        int dot = ref.indexOf('.');
        MosaicDocument.ENode src = model.doc.byId(dot > 0 ? ref.substring(0, dot) : ref);
        return src == null ? null : outKind(src, dot > 0 ? ref.substring(dot + 1) : "");
    }

    String label(MosaicDocument.ENode n) {
        if (n.label != null && !n.label.isBlank()) return n.label;
        if (MosaicFunction.isFunctionType(n.type)) {
            MosaicDocument.FDef d = model.doc.function(MosaicFunction.nameOf(n.type));
            return d != null && d.label != null ? d.label : MosaicFunction.nameOf(n.type);
        }
        if (MosaicSlangGraph.handles(n.type)) return MosaicSlangGraph.label(n.type, n.mask);
        if (io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicRenderer.isStage(n.type))
            return MosaicCatalog.of(n.type).label() + (model.stageEdited(n.type) ? "  (your version)" : "");
        if (n.type.startsWith("user.")) return MosaicCatalog.of(n.type).label();
        return MosaicCatalog.of(n.type).label();
    }

    Color headColor(MosaicDocument.ENode n) {
        if (n.type.equals("slang.module")) // pack map: green = a Renderer stage controls it, amber = not a node yet; passes brighter
            return io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicPack.stageOf(n.mask) != null
                ? (n.index == 1 ? new Color(0x3FA36B) : new Color(0x2E7A52)) : (n.index == 1 ? new Color(0xC08A2E) : new Color(0x8A6A2E));
        if (MosaicFunction.isFunctionType(n.type)) {
            MosaicDocument.FDef d = model.doc.function(MosaicFunction.nameOf(n.type));
            return categoryColor(d == null ? null : d.category);
        }
        if (MosaicSlangGraph.handles(n.type)) return bedrayColor(n);
        if (n.type.startsWith("user.")) return new Color(0x2F8F5A);
        return new Color(MosaicCatalog.of(n.type).category().color, true);
    }

    /** Bedray function graphs: pack calls blue, structs teal, constants and literals grey, variables green, code amber, ends violet. */
    static Color bedrayColor(MosaicDocument.ENode n) {
        String t = n.type;
        if (t.startsWith(MosaicSlangGraph.FN)) return new Color(0x3D6FB8);
        if (t.startsWith(MosaicSlangGraph.MAKE) || t.startsWith(MosaicSlangGraph.BREAK) || t.equals(MosaicSlangGraph.SET)
            || t.equals(MosaicSlangGraph.MEMBER)) return new Color(0x2F8A8E);
        if (t.startsWith(MosaicSlangGraph.CONST) || t.equals(MosaicSlangGraph.LITERAL)) return new Color(0x5A6170);
        return switch (t) {
            case MosaicSlangGraph.LET -> new Color(0x3F8F5A);
            case MosaicSlangGraph.CODE -> new Color(0xB07A2A);
            case MosaicSlangGraph.PARAM, MosaicSlangGraph.RESULT, MosaicSlangGraph.OUT -> new Color(0x7A5AB8);
            case MosaicSlangGraph.OP -> new Color(0x4C5A78);
            default -> new Color(0x4A6A9A);
        };
    }

    String paramText(MosaicDocument.ENode n) {
        String base = baseParamText(n);
        int hidden = base.isEmpty() ? hiddenInputs(n) : 0;
        return hidden > 0 ? "+" + hidden + " more" : base;
    }

    private String baseParamText(MosaicDocument.ENode n) {
        return switch (n.type) {
            case "mosaic.const" -> InspectorPanel.numbers(n.value);
            case "mosaic.swizzle" -> n.mask == null ? "" : "." + n.mask;
            case "mosaic.push" -> "rt.push slot " + n.index;
            case "mosaic.buffer" -> "buffer5 [" + n.index + "]";
            case "mosaic.vec" -> "vec" + (n.index == 0 ? 3 : n.index);
            case "mosaic.materialMask", "mosaic.materialDepth" -> MosaicWorld.Material.of(n.index).label;
            case "mosaic.math" -> (n.mask == null ? "add" : n.mask) + ((n.index & 1) != 0 ? "  clamp" : "");
            case "mosaic.mixColor" -> (n.mask == null ? "mix" : n.mask) + ((n.index & 1) != 0 ? "  clamp" : "");
            case "mosaic.mapRange" -> (n.mask == null ? "linear" : n.mask) + ((n.index & 1) != 0 ? "  clamp" : "");
            case "mosaic.colorRamp" -> (n.value == null ? 2 : n.value.length / 5) + " stops, " + (n.mask == null ? "linear" : n.mask);
            case "mosaic.gradient" -> n.mask == null ? "linear" : n.mask;
            case "mosaic.wave" -> n.mask == null ? "bands" : n.mask;
            case "mosaic.vectorMath" -> n.mask == null ? "add" : n.mask;
            case MosaicSlangGraph.LET, MosaicSlangGraph.PARAM, MosaicSlangGraph.OUT -> n.mask == null ? "" : n.mask.substring(0, Math.max(0, n.mask.lastIndexOf(' ')));
            case MosaicSlangGraph.RESULT -> n.mask == null ? "" : "returns " + n.mask;
            case MosaicSlangGraph.SETTING -> {
                var s = io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicRenderer.setting(n.mask);
                double v = n.value != null && n.value.length > 0 ? n.value[0] : s == null ? 0 : s.def();
                yield s == null ? n.mask : s.key() + " = " + s.format(v).replace('_', ' ');
            }
            case MosaicSlangGraph.CODE -> {
                String body = MosaicSlangGraph.codeSignature(n.mask).body().strip();
                String first = body.isEmpty() ? "" : body.lines().findFirst().orElse("");
                yield (first.length() > 34 ? first.substring(0, 33) + "..." : first) + "   (" + body.lines().count() + " lines)";
            }
            default -> {
                if (n.type.startsWith(MosaicSlangGraph.FN)) {
                    var f = MosaicSlangGraph.function(n.type);
                    yield f == null ? "missing in this Bedray" : f.module() + (f.isVoid() ? "" : "  -> " + f.returnType());
                }
                if (n.type.startsWith(MosaicSlangGraph.CONST)) {
                    var c = MosaicSlangGraph.constant(n.type);
                    yield c == null ? "" : "= " + c.value();
                }
                yield "";
            }
        };
    }

    Socket socketAt(Point screen) {
        Point w = toWorld(screen);
        double r = Math.max(7, 9 / zoom);
        List<MosaicDocument.ENode> nodes = model.doc.nodes;
        for (int i = nodes.size() - 1; i >= 0; i--) {
            MosaicDocument.ENode n = nodes.get(i);
            List<String> ins = inputs(n), outs = outputs(n);
            for (int k = 0; k < ins.size(); k++) if (inSocket(n, k).distance(w) <= r) return new Socket(n, ins.get(k), true, inSocket(n, k));
            for (int k = 0; k < outs.size(); k++) if (outSocket(n, k).distance(w) <= r) return new Socket(n, outs.get(k), false, outSocket(n, k));
        }
        return null;
    }

    MosaicDocument.ENode nodeAt(Point screen) {
        Point w = toWorld(screen);
        List<MosaicDocument.ENode> nodes = model.doc.nodes;
        for (int i = nodes.size() - 1; i >= 0; i--) {
            MosaicDocument.ENode n = nodes.get(i);
            if (w.x >= n.x && w.x < n.x + W() && w.y >= n.y && w.y < n.y + height(n)) return n;
        }
        return null;
    }

    MosaicDocument.Frame frameTitleAt(Point screen) {
        Point w = toWorld(screen);
        for (int i = model.doc.frames.size() - 1; i >= 0; i--) {
            MosaicDocument.Frame f = model.doc.frames.get(i);
            if (w.x >= f.x && w.x < f.x + f.w && w.y >= f.y && w.y < f.y + FRAME_HEAD) return f;
        }
        return null;
    }

    MosaicDocument.Frame frameCornerAt(Point screen) {
        Point w = toWorld(screen);
        for (MosaicDocument.Frame f : model.doc.frames)
            if (Math.abs(w.x - (f.x + f.w)) < 12 / zoom + 2 && Math.abs(w.y - (f.y + f.h)) < 12 / zoom + 2) return f;
        return null;
    }

    String socketTip(Socket s) {
        if (s.input) {
            String ref = s.node.inputs.get(s.name);
            MosaicGraph.Kind k = ref != null ? refKind(ref) : MosaicGraph.acceptedKind(s.node.type, s.name, model.functions());
            return s.name + ": " + kindName(k) + (ref != null ? "  <- " + ref : "  (drag to an output, or Shift+K)");
        }
        return (s.name.isEmpty() ? "out" : s.name) + ": " + kindName(outKind(s.node, s.name)) + "  (drag to an input, or K)";
    }

    String nodeTip(Point p) {
        MosaicDocument.ENode n = nodeAt(p);
        if (n == null) return null;
        List<MosaicGraph.Problem> probs = model.analysis.of(n.id);
        return probs.isEmpty() ? null : "<html>" + String.join("<br>", probs.stream().map(x -> esc(x.message())).toList()) + "</html>";
    }

    // ================================================================== mouse

    private boolean panButton(MouseEvent e) {
        return SwingUtilities.isMiddleMouseButton(e)
            || (settings.rightDragPans && SwingUtilities.isRightMouseButton(e) && nodeAt(e.getPoint()) == null)
            || (settings.altDragPans && SwingUtilities.isLeftMouseButton(e) && e.isAltDown());
    }

    // ================================================================== glide, edge pan, selection bar

    private final Map<MosaicDocument.ENode, Point> glideFrom = new LinkedHashMap<>(), glideTo = new LinkedHashMap<>();
    private long glideStart;
    private final javax.swing.Timer glideTimer = new javax.swing.Timer(15, e -> glideStep());

    /** Animates nodes from {@code from} to where they are now (they already hold the final, committed positions). */
    void glide(Map<MosaicDocument.ENode, Point> from) {
        finishGlide();
        for (var en : from.entrySet()) {
            MosaicDocument.ENode n = en.getKey();
            if (n.x == en.getValue().x && n.y == en.getValue().y) continue;
            glideTo.put(n, new Point(n.x, n.y));
            glideFrom.put(n, en.getValue());
            n.x = en.getValue().x;
            n.y = en.getValue().y;
        }
        if (glideTo.isEmpty()) return;
        glideStart = System.nanoTime();
        glideTimer.start();
    }

    private void glideStep() {
        double t = Math.min(1, (System.nanoTime() - glideStart) / 1.8e8);
        double k = 1 - Math.pow(1 - t, 3); // ease out
        for (var en : glideTo.entrySet()) {
            Point a = glideFrom.get(en.getKey()), b = en.getValue();
            en.getKey().x = (int) Math.round(a.x + (b.x - a.x) * k);
            en.getKey().y = (int) Math.round(a.y + (b.y - a.y) * k);
        }
        if (t >= 1) finishGlide();
        repaint();
    }

    /** Snaps a running glide to its end (before saving, or when the user grabs a node). */
    void finishGlide() {
        glideTimer.stop();
        for (var en : glideTo.entrySet()) {
            en.getKey().x = en.getValue().x;
            en.getKey().y = en.getValue().y;
        }
        glideTo.clear();
        glideFrom.clear();
    }

    private static final int EDGE_PAN = 44;
    private MouseEvent lastDrag;
    private Point dragOrigin;
    private final javax.swing.Timer edgeTimer = new javax.swing.Timer(15, e -> edgeStep());

    /** While dragging a node, frame, wire or box near the canvas edge, the view scrolls and the dragged thing follows. */
    private double[] edgeSpeed(Point p) {
        double vx = 0, vy = 0;
        if (p.x < EDGE_PAN) vx = -(EDGE_PAN - p.x);
        else if (p.x > getWidth() - EDGE_PAN) vx = p.x - (getWidth() - EDGE_PAN);
        if (p.y < EDGE_PAN) vy = -(EDGE_PAN - p.y);
        else if (p.y > getHeight() - EDGE_PAN) vy = p.y - (getHeight() - EDGE_PAN);
        double s = 18.0 / EDGE_PAN;
        return new double[] {Math.clamp(vx * s, -40, 40), Math.clamp(vy * s, -40, 40)};
    }

    private boolean edgeGesture() { return dragNode != null || dragFrame != null || wireFrom != null || boxing || resizeFrame != null; }

    private void edgeStep() {
        if (lastDrag == null || !edgeGesture()) {
            edgeTimer.stop();
            return;
        }
        double[] v = edgeSpeed(lastDrag.getPoint());
        if (v[0] == 0 && v[1] == 0) {
            edgeTimer.stop();
            return;
        }
        panX -= v[0];
        panY -= v[1];
        if (boxing) pressScreen = new Point((int) Math.round(pressScreen.x - v[0]), (int) Math.round(pressScreen.y - v[1]));
        drag(lastDrag);
    }

    private record Tool(Rectangle r, String label, String tip, Runnable run) {}
    private final List<Tool> tools = new ArrayList<>();

    /** The bar over a multi-selection: arrange, align, distribute, group. */
    private void selectionBar(Graphics2D g) {
        tools.clear();
        if (model.selection.size() < 2 || edgeGesture() || panning) return;
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        for (MosaicDocument.ENode n : model.selection) {
            minX = Math.min(minX, n.x);
            minY = Math.min(minY, n.y);
            maxX = Math.max(maxX, n.x + W());
        }
        String[][] items = {{"Arrange", "Tidy the selection by its wires (" + model.keys.humanFor(StudioKeys.Action.ARRANGE) + ")", "arrange"},
            {"Left", "Align left edges", "left"}, {"Top", "Align top edges", "top"}, {"Row", "Line up in a row, even spacing", "row"},
            {"Column", "Stack in a column", "column"}, {"Group", "Frame behind the selection", "group"}};
        g.setFont(UI.deriveFont(UI.getSize2D() - 0.5f));
        java.awt.FontMetrics fm = g.getFontMetrics();
        int h = 24, pad = 9, total = 6;
        for (String[] it : items) total += fm.stringWidth(it[0]) + pad * 2;
        int cx = (int) Math.round((minX + maxX) / 2.0 * zoom + panX);
        int x = Math.clamp(cx - total / 2, 4, Math.max(4, getWidth() - total - 4));
        int y = Math.clamp((int) Math.round(minY * zoom + panY) - h - 10, 4, Math.max(4, getHeight() - h - 4));
        g.setColor(new Color(0, 0, 0, 60));
        g.fillRoundRect(x + 1, y + 2, total, h, 12, 12);
        g.setColor(PANEL2);
        g.fillRoundRect(x, y, total, h, 12, 12);
        g.setColor(EDGE);
        g.drawRoundRect(x, y, total, h, 12, 12);
        int bx = x + 3;
        for (String[] it : items) {
            int bw = fm.stringWidth(it[0]) + pad * 2;
            Rectangle r = new Rectangle(bx, y + 2, bw, h - 4);
            String how = it[2];
            tools.add(new Tool(r, it[0], it[1], () -> {
                switch (how) {
                    case "arrange" -> model.arrange(this::height);
                    case "group" -> model.group(this::height);
                    default -> model.align(how, this::height);
                }
            }));
            boolean hover = mouseScreen != null && r.contains(mouseScreen);
            if (hover) {
                g.setColor(new Color(SELECT.getRed(), SELECT.getGreen(), SELECT.getBlue(), 70));
                g.fillRoundRect(r.x, r.y, r.width, r.height, 9, 9);
            }
            g.setColor(hover ? TEXT : MUTED);
            g.drawString(it[0], bx + pad, y + (h + fm.getAscent() - fm.getDescent()) / 2);
            bx += bw;
        }
    }

    private Tool hoverTool;

    private Tool toolAt(Point p) {
        for (Tool t : tools) if (t.r().contains(p)) return t;
        return null;
    }

    private void press(MouseEvent e) {
        requestFocusInWindow();
        model.activeGraph = this;
        finishGlide();
        if (minimapRect != null && minimapRect.contains(e.getPoint()) && SwingUtilities.isLeftMouseButton(e)) {
            minimapDrag = true;
            minimapJump(e.getPoint());
            return;
        }
        Tool tool = SwingUtilities.isLeftMouseButton(e) ? toolAt(e.getPoint()) : null;
        if (tool != null) {
            tool.run().run();
            repaint();
            return;
        }
        dragOrigin = e.getPoint();
        pressScreen = e.getPoint();
        pressWorld = toWorld(e.getPoint());
        if (SwingUtilities.isRightMouseButton(e) && e.isControlDown()) {
            knife.clear();
            knife.add(toWorld(e.getPoint()));
            return;
        }
        if (panButton(e)) {
            panning = true;
            panStartX = panX;
            panStartY = panY;
            return;
        }
        if (SwingUtilities.isRightMouseButton(e)) {
            MosaicDocument.ENode n = nodeAt(e.getPoint());
            if (n != null && !model.selection.contains(n)) model.select(n);
            JPopupMenu cm = contextMenu(n);
            StudioWidgets.fitToScreen(cm, this);
            cm.show(this, e.getX(), e.getY());
            return;
        }
        MosaicDocument.Frame corner = frameCornerAt(e.getPoint());
        if (corner != null) {
            resizeFrame = corner;
            frameStart = new Point(corner.w, corner.h);
            gestureBefore = model.doc.snapshot();
            gestureLabel = "Resize frame " + corner.title;
            return;
        }
        Socket s = socketAt(e.getPoint());
        if (s != null) {
            gestureBefore = model.doc.snapshot();
            if (s.input && s.node.inputs.containsKey(s.name)) {
                String ref = s.node.inputs.remove(s.name);
                int dot = ref.indexOf('.');
                MosaicDocument.ENode src = model.doc.byId(dot > 0 ? ref.substring(0, dot) : ref);
                String outName = dot > 0 ? ref.substring(dot + 1) : "";
                wireFrom = src == null ? null : new Socket(src, outName, false, outSocket(src, Math.max(0, outputs(src).indexOf(outName))));
                gestureLabel = "Disconnect " + s.node.id + "." + s.name;
            } else {
                wireFrom = s;
            }
            return;
        }
        MosaicDocument.ENode n = nodeAt(e.getPoint());
        if (n != null) {
            if (e.isShiftDown() || e.isControlDown()) {
                model.toggle(n);
                return;
            }
            if (!model.selection.contains(n)) model.select(n);
            else {
                model.primary = n;
                model.selectionChanged();
            }
            dragNode = n;
            gestureBefore = model.doc.snapshot();
            gestureLabel = "Move " + (model.selection.size() == 1 ? n.id : model.selection.size() + " nodes");
            dragStarts.clear();
            for (MosaicDocument.ENode s2 : model.selection) dragStarts.put(s2, new Point(s2.x, s2.y));
            model.doc.nodes.remove(n);
            model.doc.nodes.add(n);
            return;
        }
        MosaicDocument.Frame f = frameTitleAt(e.getPoint());
        if (f != null) {
            model.selection.clear();
            model.primary = null;
            model.selectedFrame = f;
            model.selectionChanged();
            dragFrame = f;
            frameStart = new Point(f.x, f.y);
            gestureBefore = model.doc.snapshot();
            gestureLabel = "Move frame " + f.title;
            dragStarts.clear();
            for (MosaicDocument.ENode in : model.doc.nodesIn(f)) dragStarts.put(in, new Point(in.x, in.y));
            return;
        }
        boxing = true;
        boxEnd = e.getPoint();
        if (!e.isShiftDown()) model.select(null);
    }

    private int snap(int v) {
        int step = settings.gridStep;
        return settings.snap ? Math.round(v / (float) step) * step : v;
    }

    private void drag(MouseEvent e) {
        mouseScreen = e.getPoint();
        if (minimapDrag) {
            minimapJump(e.getPoint());
            return;
        }
        Point w = toWorld(e.getPoint());
        if (!knife.isEmpty()) {
            knife.add(w);
            repaint();
            return;
        }
        if (panning) {
            panX = panStartX + e.getX() - pressScreen.x;
            panY = panStartY + e.getY() - pressScreen.y;
        } else if (dragNode != null) {
            Point base = dragStarts.get(dragNode);
            int dx = w.x - pressWorld.x, dy = w.y - pressWorld.y;
            if (settings.snap && !e.isAltDown()) {
                dx = snap(base.x + dx) - base.x;
                dy = snap(base.y + dy) - base.y;
            }
            for (var en : dragStarts.entrySet()) {
                en.getKey().x = en.getValue().x + dx;
                en.getKey().y = en.getValue().y + dy;
            }
        } else if (dragFrame != null) {
            int dx = w.x - pressWorld.x, dy = w.y - pressWorld.y;
            dragFrame.x = frameStart.x + dx;
            dragFrame.y = frameStart.y + dy;
            for (var en : dragStarts.entrySet()) {
                en.getKey().x = en.getValue().x + dx;
                en.getKey().y = en.getValue().y + dy;
            }
        } else if (resizeFrame != null) {
            resizeFrame.w = Math.max(80, frameStart.x + w.x - pressWorld.x);
            resizeFrame.h = Math.max(60, frameStart.y + w.y - pressWorld.y);
        } else if (boxing) {
            boxEnd = e.getPoint();
        }
        lastDrag = e;
        if (edgeGesture() && dragOrigin != null && dragOrigin.distance(e.getPoint()) > 8 && !edgeTimer.isRunning()) {
            double[] v = edgeSpeed(e.getPoint());
            if (v[0] != 0 || v[1] != 0) edgeTimer.start();
        }
        repaint();
    }

    private void release(MouseEvent e) {
        edgeTimer.stop();
        lastDrag = null;
        if (minimapDrag) {
            minimapDrag = false;
            return;
        }
        if (!knife.isEmpty()) {
            cutWires();
            knife.clear();
            repaint();
            return;
        }
        if (panning) {
            panning = false;
            if (pressScreen.distance(e.getPoint()) < 4 && SwingUtilities.isRightMouseButton(e)) quickAdd(e.getPoint());
            return;
        }
        if (wireFrom != null) {
            Socket target = socketAt(e.getPoint());
            MosaicDocument.ENode body = nodeAt(e.getPoint());
            Socket from = wireFrom;
            wireFrom = null;
            String before = gestureBefore;
            gestureBefore = null;
            if (target != null && target.input != from.input) {
                if (before != null && gestureLabel != null && gestureLabel.startsWith("Disconnect")) model.doc.commit(before, gestureLabel);
                if (from.input) model.connect(from.node, from.name, target.node, target.name);
                else model.connect(target.node, target.name, from.node, from.name);
            } else if (target == null && body != null && body != from.node && !from.input) {
                if (before != null && gestureLabel != null && gestureLabel.startsWith("Disconnect")) model.doc.commit(before, gestureLabel);
                for (String port : inputs(body)) if (!body.inputs.containsKey(port) && model.connect(body, port, from.node, from.name)) break;
            } else if (target == null && body == null) {
                model.commitGesture(before, gestureLabel == null ? "Edit" : gestureLabel);
                pickAndWire(e.getPoint(), from);
            } else {
                model.commitGesture(before, gestureLabel == null ? "Edit" : gestureLabel);
            }
            gestureLabel = null;
            repaint();
            return;
        }
        if (dragNode != null || dragFrame != null || resizeFrame != null) {
            dragNode = null;
            dragFrame = null;
            resizeFrame = null;
            model.commitGesture(gestureBefore, gestureLabel);
            gestureBefore = null;
            return;
        }
        if (boxing) {
            boxing = false;
            Point a = toWorld(pressScreen), b = toWorld(boxEnd);
            Rectangle r = new Rectangle(Math.min(a.x, b.x), Math.min(a.y, b.y), Math.abs(a.x - b.x), Math.abs(a.y - b.y));
            if (r.width > 3 || r.height > 3) {
                for (MosaicDocument.ENode n : model.doc.nodes)
                    if (r.intersects(new Rectangle(n.x, n.y, W(), height(n)))) model.selection.add(n);
                model.primary = model.selection.isEmpty() ? null : model.selection.iterator().next();
                model.selectionChanged();
            }
            repaint();
        }
    }

    private void wheel(MouseWheelEvent e) {
        double amount = e.getPreciseWheelRotation() * (settings.invertWheel ? -1 : 1);
        boolean zoomGesture = settings.wheel == StudioSettings.WheelMode.ZOOM ? !e.isShiftDown() : e.isControlDown();
        if (zoomGesture) {
            double f = Math.pow(1 + settings.zoomSpeed / 100.0, -amount);
            zoomAt(e.getPoint(), f);
        } else if (e.isShiftDown()) {
            panX -= amount * 40;
        } else {
            panY -= amount * 40;
        }
        repaint();
    }

    /** Removes every wire whose curve crosses the knife stroke (one undo step). */
    private void cutWires() {
        List<String[]> cuts = new ArrayList<>();
        for (MosaicDocument.ENode n : model.doc.nodes) {
            List<String> ins = inputs(n);
            for (int k = 0; k < ins.size(); k++) {
                String ref = n.inputs.get(ins.get(k));
                if (ref == null) continue;
                int dot = ref.indexOf('.');
                MosaicDocument.ENode src = model.doc.byId(dot > 0 ? ref.substring(0, dot) : ref);
                if (src == null) continue;
                String outName = dot > 0 ? ref.substring(dot + 1) : "";
                Point a = outSocket(src, Math.max(0, outputs(src).indexOf(outName))), b = inSocket(n, k);
                double bend = Math.max(40, Math.abs(b.x - a.x) * 0.5);
                double px = a.x, py = a.y;
                boolean hit = false;
                for (int i = 1; i <= 24 && !hit; i++) {
                    double t = i / 24.0, u = 1 - t;
                    double x = u * u * u * a.x + 3 * u * u * t * (a.x + bend) + 3 * u * t * t * (b.x - bend) + t * t * t * b.x;
                    double y = u * u * u * a.y + 3 * u * u * t * a.y + 3 * u * t * t * b.y + t * t * t * b.y;
                    for (int j = 1; j < knife.size() && !hit; j++)
                        hit = java.awt.geom.Line2D.linesIntersect(px, py, x, y, knife.get(j - 1).x, knife.get(j - 1).y, knife.get(j).x, knife.get(j).y);
                    px = x;
                    py = y;
                }
                if (hit) cuts.add(new String[] {n.id, ins.get(k)});
            }
        }
        if (cuts.isEmpty()) return;
        model.edit("Knife: cut " + cuts.size() + " wire(s)", () -> {
            for (String[] c : cuts) model.doc.byId(c[0]).inputs.remove(c[1]);
        });
    }

    void zoomAt(Point screen, double factor) {
        Point w = toWorld(screen);
        zoom = Math.clamp(zoom * factor, 0.08, 3.0);
        panX = screen.x - w.x * zoom;
        panY = screen.y - w.y * zoom;
        repaint();
    }

    void zoomBy(double factor) { zoomAt(new Point(getWidth() / 2, getHeight() / 2), factor); }

    void pan(int dx, int dy) {
        panX += dx;
        panY += dy;
        repaint();
    }

    void fit() {
        List<MosaicDocument.ENode> nodes = model.doc.nodes;
        if (nodes.isEmpty() || getWidth() == 0) return;
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE;
        for (MosaicDocument.ENode n : nodes) {
            minX = Math.min(minX, n.x);
            minY = Math.min(minY, n.y);
            maxX = Math.max(maxX, n.x + W());
            maxY = Math.max(maxY, n.y + height(n));
        }
        for (MosaicDocument.Frame f : model.doc.frames) {
            minX = Math.min(minX, f.x);
            minY = Math.min(minY, f.y);
            maxX = Math.max(maxX, f.x + f.w);
            maxY = Math.max(maxY, f.y + f.h);
        }
        zoom = Math.clamp(Math.min((getWidth() - 80.0) / Math.max(1, maxX - minX), (getHeight() - 80.0) / Math.max(1, maxY - minY)), 0.08, 1.4);
        panX = (getWidth() - (maxX - minX) * zoom) / 2 - minX * zoom;
        panY = (getHeight() - (maxY - minY) * zoom) / 2 - minY * zoom;
        repaint();
    }

    void center(MosaicDocument.ENode n) {
        if (n == null) return;
        panX = getWidth() / 2.0 - (n.x + W() / 2.0) * zoom;
        panY = getHeight() / 2.0 - (n.y + height(n) / 2.0) * zoom;
        repaint();
    }

    /** Keeps the keyboard node on screen after keyboard navigation. */
    /** Fit when that keeps nodes readable; a big graph instead opens at a readable zoom at its start (top left). */
    void fitReadable() {
        fit();
        if (zoom >= 0.55 || model.doc.nodes.isEmpty()) return;
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE;
        for (MosaicDocument.ENode n : model.doc.nodes) {
            minX = Math.min(minX, n.x);
            minY = Math.min(minY, n.y);
        }
        for (MosaicDocument.Frame f : model.doc.frames) {
            minX = Math.min(minX, f.x);
            minY = Math.min(minY, f.y);
        }
        zoom = 0.8;
        panX = 40 - minX * zoom;
        panY = 40 - minY * zoom;
        repaint();
    }

    void reveal(MosaicDocument.ENode n) {
        if (n == null) return;
        double sx = n.x * zoom + panX, sy = n.y * zoom + panY;
        if (sx < 20 || sy < 20 || sx + W() * zoom > getWidth() - 20 || sy + height(n) * zoom > getHeight() - 20) center(n);
        else repaint();
    }

    // ================================================================== keyboard operations

    /** Tab / Shift+Tab: next node in reading order (top-to-bottom, left-to-right). */
    void cycle(int dir) {
        List<MosaicDocument.ENode> order = new ArrayList<>(model.doc.nodes);
        if (order.isEmpty()) return;
        order.sort(Comparator.comparingInt((MosaicDocument.ENode n) -> n.x / 60).thenComparingInt(n -> n.y));
        int i = model.primary == null ? -1 : order.indexOf(model.primary);
        MosaicDocument.ENode next = order.get(Math.floorMod(i + dir, order.size()));
        model.select(next);
        reveal(next);
    }

    /** Alt+arrows: the nearest node in a direction (within a 60-degree cone), optionally added to the selection. */
    void navigate(int dx, int dy, boolean extend) {
        MosaicDocument.ENode from = model.primary;
        if (from == null) {
            cycle(1);
            return;
        }
        double fx = from.x + W() / 2.0, fy = from.y + height(from) / 2.0;
        MosaicDocument.ENode best = null;
        double bestScore = Double.MAX_VALUE;
        for (MosaicDocument.ENode n : model.doc.nodes) {
            if (n == from) continue;
            double vx = n.x + W() / 2.0 - fx, vy = n.y + height(n) / 2.0 - fy;
            double along = vx * dx + vy * dy, across = Math.abs(vx * dy - vy * dx);
            if (along <= 0 || across > along * 1.8) continue;
            double score = along + across * 2;
            if (score < bestScore) {
                bestScore = score;
                best = n;
            }
        }
        if (best == null) return;
        if (extend) {
            model.selection.add(best);
            model.primary = best;
            model.selectionChanged();
        } else model.select(best);
        reveal(best);
    }

    void moveSelection(int dx, int dy, boolean far) {
        int step = far ? settings.gridStep : 1;
        model.nudge(dx * step, dy * step);
    }

    void renameDialog() {
        String current = model.selectedFrame != null && model.selection.isEmpty() ? model.selectedFrame.title
            : model.primary != null ? (model.primary.label == null ? "" : model.primary.label) : null;
        if (current == null) {
            model.say("Select a node or a frame to name it", MUTED);
            return;
        }
        String text = JOptionPane.showInputDialog(SwingUtilities.getWindowAncestor(this),
            model.selectedFrame != null && model.selection.isEmpty() ? "Frame title:" : "Label shown on the node (empty: type name):", current);
        if (text != null) model.rename(text);
    }

    /** K: wire the selected node's output into an input picked from a searchable list. */
    void connectFrom() {
        MosaicDocument.ENode src = model.primary;
        if (src == null || outputs(src).isEmpty()) {
            model.say("Select a node with an output first (Tab / Alt+arrows)", MUTED);
            return;
        }
        List<String[]> targets = new ArrayList<>();
        for (MosaicDocument.ENode n : model.doc.nodes) {
            if (n == src) continue;
            for (String port : inputs(n))
                for (String out : outputs(src)) targets.add(new String[] {n.id, port, out});
        }
        pick(this, keyPoint(), "Wire " + src.id + " into...", targets,
            t -> t[0] + "." + t[1] + (t[2].isEmpty() ? "" : "   from ." + t[2])
                + (model.doc.byId(t[0]).inputs.containsKey(t[1]) ? "   (replaces " + model.doc.byId(t[0]).inputs.get(t[1]) + ")" : ""),
            t -> model.connect(model.doc.byId(t[0]), t[1], src, t[2]));
    }

    /** Shift+K: feed one of the selected node's inputs from an output picked from a list. */
    void connectInto() {
        MosaicDocument.ENode dst = model.primary;
        if (dst == null || inputs(dst).isEmpty()) {
            model.say("Select a node with inputs first", MUTED);
            return;
        }
        List<String[]> sources = new ArrayList<>();
        for (String port : inputs(dst))
            for (MosaicDocument.ENode n : model.doc.nodes) {
                if (n == dst) continue;
                for (String out : outputs(n)) sources.add(new String[] {port, n.id, out});
            }
        pick(this, keyPoint(), "Feed " + dst.id + " from...", sources,
            t -> t[0] + "  <-  " + t[1] + (t[2].isEmpty() ? "" : "." + t[2]),
            t -> model.connect(dst, t[0], model.doc.byId(t[1]), t[2]));
    }

    private Point keyPoint() {
        if (model.primary != null) {
            return new Point((int) ((model.primary.x + W()) * zoom + panX), (int) (model.primary.y * zoom + panY));
        }
        return new Point(getWidth() / 2, getHeight() / 3);
    }

    /** Space / double-click / right-click: the categorized node tree at the point; Enter or double-click adds there. */
    void quickAdd(Point screen) {
        Point at = screen == null ? keyPoint() : screen;
        Point world = toWorld(at);
        pickNode(at, "Add node", it -> {
            MosaicDocument.ENode n = model.add(it, new Point(world.x - W() / 2, world.y - HEAD / 2), viewCenter());
            if (screen == null && n != null) reveal(n);
        });
    }

    /** A wire dropped on empty canvas: pick a node, it is created there and wired. */
    void pickAndWire(Point screen, Socket from) {
        Point world = toWorld(screen);
        pickNode(screen, from.input ? "Feed " + from.node.id + "." + from.name + " from a new..." : "Wire into a new...", it -> {
            MosaicDocument.ENode added = model.add(it, new Point(world.x - (from.input ? W() : 0), world.y - HEAD / 2), viewCenter());
            if (added == null) return;
            if (from.input) model.connect(from.node, from.name, added, "");
            else for (String port : inputs(added)) if (model.connect(added, port, from.node, from.name)) break;
        });
    }

    /** The add menu: search field over the collapsible category tree (same as the palette). */
    void pickNode(Point at, String title, Consumer<StudioModel.Item> onPick) {
        Window win = SwingUtilities.getWindowAncestor(this);
        JWindow w = new JWindow(win);
        JPanel p = new JPanel(new BorderLayout(0, 4));
        p.setBackground(PANEL2);
        p.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(SELECT), new EmptyBorder(6, 6, 6, 6)));
        JTextField q = field("");
        NodeTree tree = new NodeTree(model::items, false);
        Runnable close = () -> {
            w.dispose();
            requestFocusInWindow();
        };
        Consumer<StudioModel.Item> done = it -> {
            close.run();
            if (it != null) onPick.accept(it);
        };
        tree.onPick(done);
        q.getDocument().addDocumentListener(onText(() -> tree.setFilter(q.getText())));
        q.addActionListener(e -> done.accept(tree.current()));
        q.addKeyListener(new KeyAdapter() {
            @Override public void keyPressed(KeyEvent e) {
                switch (e.getKeyCode()) {
                    case KeyEvent.VK_DOWN -> tree.moveSelection(1);
                    case KeyEvent.VK_UP -> tree.moveSelection(-1);
                    case KeyEvent.VK_PAGE_DOWN -> tree.moveSelection(10);
                    case KeyEvent.VK_PAGE_UP -> tree.moveSelection(-10);
                    case KeyEvent.VK_RIGHT -> {
                        if (q.getCaretPosition() == q.getText().length() && tree.getSelectedValue() instanceof NodeTree.Group g && !g.open())
                            tree.toggle(g.name());
                    }
                    case KeyEvent.VK_LEFT -> {
                        if (q.getText().isEmpty() && tree.getSelectedValue() instanceof NodeTree.Group g && g.open()) tree.toggle(g.name());
                    }
                    case KeyEvent.VK_ESCAPE -> close.run();
                    default -> { }
                }
            }
        });
        tree.addFocusListener(new FocusAdapter() {
            @Override public void focusLost(FocusEvent e) {
                if (e.getOppositeComponent() != q && !(e.getOppositeComponent() != null && SwingUtilities.isDescendingFrom(e.getOppositeComponent(), p))) w.dispose();
            }
        });
        q.addFocusListener(new FocusAdapter() {
            @Override public void focusLost(FocusEvent e) {
                if (e.getOppositeComponent() == null || !SwingUtilities.isDescendingFrom(e.getOppositeComponent(), p)) w.dispose();
            }
        });
        JLabel head = StudioUi.label(title + "   (type to search, Enter adds, Left/Right fold)", MUTED);
        head.setFont(UI_S);
        p.add(head, BorderLayout.NORTH);
        JPanel mid = new JPanel(new BorderLayout(0, 4));
        mid.setOpaque(false);
        mid.add(q, BorderLayout.NORTH);
        JScrollPane sp = scroll(tree);
        sp.setPreferredSize(new Dimension(300, 380));
        mid.add(sp, BorderLayout.CENTER);
        p.add(mid, BorderLayout.CENTER);
        w.add(p);
        w.pack();
        Point on = new Point(at);
        SwingUtilities.convertPointToScreen(on, this);
        Rectangle screen = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        on.x = Math.min(on.x, screen.x + screen.width - w.getWidth());
        on.y = Math.min(on.y, screen.y + screen.height - w.getHeight());
        w.setLocation(on);
        w.setVisible(true);
        q.requestFocusInWindow();
    }

    /** A searchable popup list at a point: type to filter, Up/Down, Enter picks, Esc closes. Keyboard and mouse. */
    static <T> void pick(Component owner, Point at, String title, List<T> items, Function<T, String> label, Consumer<T> onPick) {
        Window win = SwingUtilities.getWindowAncestor(owner);
        JWindow w = new JWindow(win);
        JPanel p = new JPanel(new BorderLayout(0, 4));
        p.setBackground(PANEL2);
        p.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(SELECT), new EmptyBorder(6, 6, 6, 6)));
        JLabel head = StudioUi.label(title, MUTED);
        JTextField q = field("");
        DefaultListModel<T> lm = new DefaultListModel<>();
        JList<T> list = new JList<>(lm);
        list.setFont(UI);
        list.setBackground(PANEL);
        list.setForeground(TEXT);
        list.setSelectionBackground(SELECT.darker());
        list.setCellRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> l, Object v, int i, boolean s, boolean f) {
                @SuppressWarnings("unchecked") String t = label.apply((T) v);
                JLabel c = (JLabel) super.getListCellRendererComponent(l, t, i, s, false);
                c.setBorder(new EmptyBorder(2, 6, 2, 6));
                return c;
            }
        });
        Runnable fill = () -> {
            lm.clear();
            String s = q.getText().trim().toLowerCase(Locale.ROOT);
            for (T it : items) if (s.isEmpty() || label.apply(it).toLowerCase(Locale.ROOT).contains(s)) lm.addElement(it);
            if (!lm.isEmpty()) list.setSelectedIndex(0);
        };
        q.getDocument().addDocumentListener(onText(fill));
        fill.run();
        Runnable done = () -> {
            T it = list.getSelectedValue();
            w.dispose();
            owner.requestFocusInWindow();
            if (it != null) onPick.accept(it);
        };
        q.addActionListener(e -> done.run());
        q.addKeyListener(new KeyAdapter() {
            @Override public void keyPressed(KeyEvent e) {
                int i = list.getSelectedIndex();
                if (e.getKeyCode() == KeyEvent.VK_DOWN) list.setSelectedIndex(Math.min(i + 1, lm.size() - 1));
                if (e.getKeyCode() == KeyEvent.VK_UP) list.setSelectedIndex(Math.max(i - 1, 0));
                if (e.getKeyCode() == KeyEvent.VK_PAGE_DOWN) list.setSelectedIndex(Math.min(i + 10, lm.size() - 1));
                if (e.getKeyCode() == KeyEvent.VK_PAGE_UP) list.setSelectedIndex(Math.max(i - 10, 0));
                if (e.getKeyCode() == KeyEvent.VK_ESCAPE) {
                    w.dispose();
                    owner.requestFocusInWindow();
                }
                list.ensureIndexIsVisible(list.getSelectedIndex());
            }
        });
        list.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) { done.run(); }
        });
        q.addFocusListener(new FocusAdapter() {
            @Override public void focusLost(FocusEvent e) {
                if (e.getOppositeComponent() != list) w.dispose();
            }
        });
        p.add(head, BorderLayout.NORTH);
        JPanel mid = new JPanel(new BorderLayout(0, 4));
        mid.setOpaque(false);
        mid.add(q, BorderLayout.NORTH);
        JScrollPane sp = scroll(list);
        sp.setPreferredSize(new Dimension(320, 300));
        mid.add(sp, BorderLayout.CENTER);
        p.add(mid, BorderLayout.CENTER);
        w.add(p);
        w.pack();
        Point on = new Point(at);
        SwingUtilities.convertPointToScreen(on, owner);
        Rectangle screen = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        on.x = Math.min(on.x, screen.x + screen.width - w.getWidth());
        on.y = Math.min(on.y, screen.y + screen.height - w.getHeight());
        w.setLocation(on);
        w.setVisible(true);
        q.requestFocusInWindow();
    }

    JPopupMenu contextMenu(MosaicDocument.ENode n) {
        JPopupMenu m = new JPopupMenu();
        StudioKeys k = model.keys;
        if (n == null) {
            item(m, "Add node...", k.humanFor(StudioKeys.Action.ADD_NODE), () -> quickAdd(mouseScreen));
            item(m, "Paste", k.humanFor(StudioKeys.Action.PASTE), () -> model.paste(mouseWorld()));
            item(m, "Arrange all", k.humanFor(StudioKeys.Action.ARRANGE), () -> model.arrange(this::height));
            item(m, "Fit view", k.humanFor(StudioKeys.Action.FIT), this::fit);
            m.addSeparator();
            item(m, "Find a node...", k.humanFor(StudioKeys.Action.FIND_NODE), () -> runAction(StudioKeys.Action.FIND_NODE));
            item(m, "Command palette...", k.humanFor(StudioKeys.Action.COMMAND_PALETTE), () -> runAction(StudioKeys.Action.COMMAND_PALETTE));
            item(m, StudioUi.settings.minimap ? "Hide the minimap" : "Show the minimap", k.humanFor(StudioKeys.Action.TOGGLE_MINIMAP),
                () -> runAction(StudioKeys.Action.TOGGLE_MINIMAP));
            return m;
        }
        item(m, "Rename / label...", k.humanFor(StudioKeys.Action.RENAME), this::renameDialog);
        item(m, n.muted ? "Unmute" : "Mute (pass the input through)", k.humanFor(StudioKeys.Action.MUTE), model::toggleMute);
        if (hiddenInputs(n) > 0 || n.expanded)
            item(m, n.expanded ? "Hide unused inputs" : "Show all inputs", k.humanFor(StudioKeys.Action.EXPAND), model::toggleExpand);
        item(m, model.gamePreview != null && model.gamePreview.equals(n.id) ? "Stop preview in game" : "Preview this node in game", "",
            () -> model.previewInGame(model.gamePreview != null && model.gamePreview.equals(n.id) ? null : n));
        item(m, "Select what feeds it", k.humanFor(StudioKeys.Action.SELECT_UPSTREAM), () -> model.selectLinked(true));
        item(m, "Select what it feeds", k.humanFor(StudioKeys.Action.SELECT_DOWNSTREAM), () -> model.selectLinked(false));
        item(m, "Wire output into...", k.humanFor(StudioKeys.Action.CONNECT_FROM), this::connectFrom);
        item(m, "Feed input from...", k.humanFor(StudioKeys.Action.CONNECT_INTO), this::connectInto);
        m.addSeparator();
        item(m, "Duplicate", k.humanFor(StudioKeys.Action.DUPLICATE), model::duplicateSelection);
        item(m, "Copy", k.humanFor(StudioKeys.Action.COPY), model::copySelection);
        item(m, "Group into frame", k.humanFor(StudioKeys.Action.GROUP), () -> model.group(this::height));
        if (!n.inputs.isEmpty()) item(m, "Disconnect inputs", k.humanFor(StudioKeys.Action.DISCONNECT), model::disconnectSelection);
        item(m, "Arrange", k.humanFor(StudioKeys.Action.ARRANGE), () -> model.arrange(this::height));
        m.addSeparator();
        item(m, "Delete", k.humanFor(StudioKeys.Action.DELETE), model::deleteSelection);
        return m;
    }

    private static void item(JPopupMenu m, String label, String keys, Runnable r) {
        JMenuItem i = new JMenuItem(label);
        if (keys != null && !keys.isEmpty()) i.setAccelerator(null);
        if (keys != null && !keys.isEmpty()) i.setText("<html>" + esc(label) + "&nbsp;&nbsp;&nbsp;<span style='color:#8B93A8'>" + esc(keys) + "</span></html>");
        i.addActionListener(e -> r.run());
        m.add(i);
    }

    // ================================================================== painting

    @Override protected void paintComponent(Graphics g0) {
        if (!fitted && getWidth() > 0) {
            fitted = true;
            fitReadable();
        }
        if (!sizeChecked && getWidth() > 0 && !model.doc.nodes.isEmpty()) {
            // a view restored from an earlier session that leaves the graph a speck in a corner: fit it once
            sizeChecked = true;
            int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE;
            for (MosaicDocument.ENode n : model.doc.nodes) {
                minX = Math.min(minX, n.x);
                minY = Math.min(minY, n.y);
                maxX = Math.max(maxX, n.x + W());
                maxY = Math.max(maxY, n.y + height(n));
            }
            boolean tiny = (maxX - minX) * zoom < getWidth() * 0.6 && (maxY - minY) * zoom < getHeight() * 0.6;
            boolean away = (minX * zoom + panX) < -10 || (maxX * zoom + panX) > getWidth() + 10 || (minY * zoom + panY) < -10
                || (maxY * zoom + panY) > getHeight() + 10; // cut by an edge
            if (tiny || away) fitReadable();
        }
        Graphics2D g = (Graphics2D) g0.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(BG);
        g.fillRect(0, 0, getWidth(), getHeight());
        grid(g);
        g.translate(panX, panY);
        g.scale(zoom, zoom);
        trackAppearances();
        for (MosaicDocument.Frame f : model.doc.frames) frame(g, f);
        g.setStroke(new BasicStroke(settings.wireWidth, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        for (MosaicDocument.ENode n : model.doc.nodes) {
            if (n.type.startsWith("slang.")) { // map nodes: every import arrives at the one socket
                for (String ref : n.inputs.values()) {
                    MosaicDocument.ENode src = model.doc.byId(ref);
                    if (src == null) continue;
                    boolean hot = model.selection.contains(n) || model.selection.contains(src);
                    wire(g, outSocket(src, 0), inSocket(n, 0), hot ? SELECT : new Color(EDGE.getRed(), EDGE.getGreen(), EDGE.getBlue(), 140));
                }
                continue;
            }
            List<String> ins = inputs(n);
            for (int k = 0; k < ins.size(); k++) {
                String ref = n.inputs.get(ins.get(k));
                if (ref == null) continue;
                int dot = ref.indexOf('.');
                MosaicDocument.ENode src = model.doc.byId(dot > 0 ? ref.substring(0, dot) : ref);
                if (src == null) continue;
                String outName = dot > 0 ? ref.substring(dot + 1) : "";
                Color c = kindColor(outKind(src, outName));
                if (model.selection.contains(n) || model.selection.contains(src)) c = c.brighter();
                wire(g, outSocket(src, Math.max(0, outputs(src).indexOf(outName))), inSocket(n, k), c);
            }
        }
        if (wireFrom != null) {
            Point mw = mouseWorld();
            if (wireFrom.input) wire(g, mw, wireFrom.at, Color.WHITE);
            else wire(g, wireFrom.at, mw, Color.WHITE);
        }
        for (MosaicDocument.ENode n : model.doc.nodes) node(g, n);
        if (knife.size() > 1) {
            g.setColor(BAD);
            g.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 10, new float[] {6, 4}, 0));
            for (int i = 1; i < knife.size(); i++) g.drawLine(knife.get(i - 1).x, knife.get(i - 1).y, knife.get(i).x, knife.get(i).y);
        }
        g.dispose();
        selectionBar((Graphics2D) g0);
        minimap((Graphics2D) g0);
        if (boxing && boxEnd != null) {
            Rectangle r = new Rectangle(Math.min(pressScreen.x, boxEnd.x), Math.min(pressScreen.y, boxEnd.y),
                Math.abs(pressScreen.x - boxEnd.x), Math.abs(pressScreen.y - boxEnd.y));
            g0.setColor(new Color(SELECT.getRed(), SELECT.getGreen(), SELECT.getBlue(), 40));
            ((Graphics2D) g0).fill(r);
            g0.setColor(SELECT);
            ((Graphics2D) g0).draw(r);
        }
        if (model.doc.nodes.isEmpty()) {
            g0.setColor(MUTED);
            g0.setFont(UI.deriveFont(UI.getSize2D() + 3));
            String t = "Empty graph: Space / double-click adds a node, Ctrl+N starts from a template";
            g0.drawString(t, (getWidth() - g0.getFontMetrics().stringWidth(t)) / 2, getHeight() / 2);
        }
        if (isFocusOwner() && settings.keyboardHints) {
            g0.setColor(new Color(SELECT.getRed(), SELECT.getGreen(), SELECT.getBlue(), 120));
            g0.drawRect(1, 1, getWidth() - 3, getHeight() - 3);
        }
    }

    private void grid(Graphics2D g) {
        if (settings.grid == StudioSettings.GridStyle.NONE) return;
        double step = settings.gridStep * zoom;
        while (step < 10) step *= 2;
        g.setColor(GRID);
        if (settings.grid == StudioSettings.GridStyle.LINES) {
            for (double x = panX % step; x < getWidth(); x += step) g.drawLine((int) x, 0, (int) x, getHeight());
            for (double y = panY % step; y < getHeight(); y += step) g.drawLine(0, (int) y, getWidth(), (int) y);
        } else {
            g.setColor(GRID.brighter());
            for (double x = panX % step; x < getWidth(); x += step)
                for (double y = panY % step; y < getHeight(); y += step) g.fillRect((int) x, (int) y, 2, 2);
        }
    }

    private void frame(Graphics2D g, MosaicDocument.Frame f) {
        Color c = new Color(f.color);
        boolean sel = f == model.selectedFrame;
        g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), 38));
        g.fill(new RoundRectangle2D.Double(f.x, f.y, f.w, f.h, 14, 14));
        g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), 150));
        g.fill(new RoundRectangle2D.Double(f.x, f.y, f.w, FRAME_HEAD, 14, 14));
        g.fillRect(f.x, f.y + FRAME_HEAD - 7, f.w, 7);
        g.setStroke(new BasicStroke(sel ? 2f : 1f));
        g.setColor(sel ? SELECT : c);
        g.draw(new RoundRectangle2D.Double(f.x, f.y, f.w, f.h, 14, 14));
        g.setFont(UI_B);
        g.setColor(Color.WHITE);
        g.drawString(clip(g, f.title == null || f.title.isEmpty() ? "(untitled)" : f.title, f.w - 16), f.x + 10, f.y + 18);
        g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), 160));
        g.fillPolygon(new int[] {f.x + f.w - 2, f.x + f.w - 2, f.x + f.w - 12}, new int[] {f.y + f.h - 12, f.y + f.h - 2, f.y + f.h - 2}, 3);
    }

    private void wire(Graphics2D g, Point a, Point b, Color c) {
        g.setColor(c);
        switch (settings.wires) {
            case STRAIGHT -> g.drawLine(a.x, a.y, b.x, b.y);
            case STEPPED -> {
                int mid = b.x < a.x + 32 ? a.x + 16 : a.x + Math.max(16, (b.x - a.x) / 2);
                Path2D.Double p = new Path2D.Double();
                p.moveTo(a.x, a.y);
                p.lineTo(mid, a.y);
                p.lineTo(mid, b.y);
                p.lineTo(b.x, b.y);
                g.draw(p);
            }
            default -> {
                double bend = Math.max(40, Math.abs(b.x - a.x) * 0.5);
                g.draw(new CubicCurve2D.Double(a.x, a.y, a.x + bend, a.y, b.x - bend, b.y, b.x, b.y));
            }
        }
    }

    // ================================================================== minimap

    private Rectangle minimapRect;
    private boolean minimapDrag, minimapHover;
    private double mmScale, mmX0, mmY0;

    /**
     * The whole graph in the bottom-right corner when it does not fit the view: nodes (selected ones in the accent colour,
     * nodes with errors in red), frames and the visible area; click or drag in it to move the view.
     */
    private void minimap(Graphics2D g0) {
        minimapRect = null;
        if (StudioUi.settings == null || !StudioUi.settings.minimap || model.doc.nodes.size() < 2) return;
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        for (MosaicDocument.ENode n : model.doc.nodes) {
            minX = Math.min(minX, n.x);
            minY = Math.min(minY, n.y);
            maxX = Math.max(maxX, n.x + W());
            maxY = Math.max(maxY, n.y + height(n));
        }
        double vx = -panX / zoom, vy = -panY / zoom, vw = getWidth() / zoom, vh = getHeight() / zoom;
        if (minX >= vx && minY >= vy && maxX <= vx + vw && maxY <= vy + vh) return; // everything is in view
        minX = Math.min(minX, vx);
        minY = Math.min(minY, vy);
        maxX = Math.max(maxX, vx + vw);
        maxY = Math.max(maxY, vy + vh);
        int mw = Math.min(StudioUi.settings.minimapWidth, getWidth() / 4), mh = Math.min(StudioUi.settings.minimapHeight, getHeight() / 4);
        if (mw < 60 || mh < 40) return;
        mmScale = Math.min((mw - 8) / (maxX - minX), (mh - 8) / (maxY - minY));
        mmX0 = minX;
        mmY0 = minY;
        Rectangle r = new Rectangle(getWidth() - mw - 10, getHeight() - mh - 10, mw, mh);
        minimapRect = r;
        Graphics2D g = (Graphics2D) g0.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        // see-through until the pointer comes to it: the nodes under it stay visible
        if (!minimapHover && !minimapDrag) g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.45f));
        g.setColor(new Color(PANEL2.getRed(), PANEL2.getGreen(), PANEL2.getBlue(), 225));
        g.fillRoundRect(r.x, r.y, r.width, r.height, 10, 10);
        g.setColor(EDGE);
        g.drawRoundRect(r.x, r.y, r.width, r.height, 10, 10);
        g.clipRect(r.x + 2, r.y + 2, r.width - 4, r.height - 4);
        java.util.Set<String> bad = new java.util.HashSet<>();
        for (MosaicGraph.Problem p : model.analysis.problems())
            if (p.severity() == MosaicGraph.Severity.ERROR && p.node() != null) bad.add(p.node());
        for (MosaicDocument.Frame f : model.doc.frames) {
            g.setColor(new Color((f.color >> 16) & 255, (f.color >> 8) & 255, f.color & 255, 70));
            g.fillRect(mmX(f.x), mmY(f.y), Math.max(2, (int) (f.w * mmScale)), Math.max(2, (int) (f.h * mmScale)));
        }
        for (MosaicDocument.ENode n : model.doc.nodes) {
            Color c = model.selection.contains(n) ? SELECT : bad.contains(n.id) ? BAD : n.muted ? FAINT : StudioWidgets.mix(MUTED, PANEL2, 0.25);
            g.setColor(c);
            g.fillRect(mmX(n.x), mmY(n.y), Math.max(2, (int) (W() * mmScale)), Math.max(2, (int) (height(n) * mmScale)));
        }
        g.setColor(SELECT);
        g.drawRect(mmX(vx), mmY(vy), Math.max(3, (int) (vw * mmScale)), Math.max(3, (int) (vh * mmScale)));
        g.dispose();
    }

    private int mmX(double wx) { return minimapRect.x + 4 + (int) Math.round((wx - mmX0) * mmScale); }

    private int mmY(double wy) { return minimapRect.y + 4 + (int) Math.round((wy - mmY0) * mmScale); }

    /** Centres the view on the world point under a minimap click. */
    private void minimapJump(Point p) {
        if (minimapRect == null || mmScale <= 0) return;
        double wx = mmX0 + (p.x - minimapRect.x - 4) / mmScale, wy = mmY0 + (p.y - minimapRect.y - 4) / mmScale;
        panX = getWidth() / 2.0 - wx * zoom;
        panY = getHeight() / 2.0 - wy * zoom;
        repaint();
    }

    /** Nodes that appeared in the last moment (added, pasted, duplicated): they scale and fade in. */
    private final Map<MosaicDocument.ENode, Long> born = new java.util.WeakHashMap<>();
    private final java.util.Set<MosaicDocument.ENode> seen = java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());
    private boolean seenReady;
    private final javax.swing.Timer appearTimer = new javax.swing.Timer(15, e -> {
        long now = System.nanoTime();
        born.values().removeIf(t -> now - t > 2.2e8);
        if (born.isEmpty()) ((javax.swing.Timer) e.getSource()).stop();
        repaint();
    });

    private int seenCount;

    /** Animates only real additions (a few new nodes, more than before), not whole-graph swaps like undo or open. */
    private void trackAppearances() {
        List<MosaicDocument.ENode> fresh = new ArrayList<>();
        for (MosaicDocument.ENode n : model.doc.nodes) if (seen.add(n)) fresh.add(n);
        int count = model.doc.nodes.size();
        if (seenReady && !fresh.isEmpty() && fresh.size() <= 12 && count > seenCount && fresh.size() == count - seenCount) {
            long now = System.nanoTime();
            for (MosaicDocument.ENode n : fresh) born.put(n, now);
            appearTimer.start();
        }
        seenCount = count;
        seenReady = true;
    }

    /** A muted node is drawn faded with a red bypass line from its first input to its output. */
    private void node(Graphics2D g, MosaicDocument.ENode n) {
        Long b = born.get(n);
        if (b != null) {
            double t = Math.min(1, (System.nanoTime() - b) / 2.2e8), k = 1 - Math.pow(1 - t, 3);
            Graphics2D a = (Graphics2D) g.create();
            double cx = n.x + W() / 2.0, cy = n.y + height(n) / 2.0, s = 0.85 + 0.15 * k;
            a.translate(cx, cy);
            a.scale(s, s);
            a.translate(-cx, -cy);
            a.setComposite(AlphaComposite.SrcOver.derive((float) k));
            nodeInner(a, n);
            a.dispose();
            return;
        }
        nodeInner(g, n);
    }

    private void nodeInner(Graphics2D g, MosaicDocument.ENode n) {
        if (!n.muted) {
            nodeBody(g, n);
            return;
        }
        Composite was = g.getComposite();
        g.setComposite(AlphaComposite.SrcOver.derive(0.4f));
        nodeBody(g, n);
        g.setComposite(was);
        Stroke s = g.getStroke();
        g.setColor(new Color(0xE0605A));
        g.setStroke(new BasicStroke(2.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        Point out = outSocket(n, 0);
        Point in = inputs(n).isEmpty() ? new Point(n.x, out.y) : inSocket(n, 0);
        g.drawLine(in.x, in.y, out.x, out.y);
        g.setFont(UI_S);
        g.drawString("muted", n.x + 6, n.y + height(n) + 13);
        g.setStroke(s);
    }

    private void nodeBody(Graphics2D g, MosaicDocument.ENode n) {
        int h = height(n), w = W();
        boolean sel = model.selection.contains(n), prim = n == model.primary;
        List<MosaicGraph.Problem> probs = model.analysis == null ? List.of() : model.analysis.of(n.id);
        boolean err = probs.stream().anyMatch(p -> p.severity() == MosaicGraph.Severity.ERROR);
        boolean warn = !err && probs.stream().anyMatch(p -> p.severity() == MosaicGraph.Severity.WARNING);
        Color head = headColor(n);
        g.setColor(new Color(0, 0, 0, 90));
        g.fill(new RoundRectangle2D.Double(n.x + 3, n.y + 4, w, h, 10, 10));
        g.setColor(NODE);
        g.fill(new RoundRectangle2D.Double(n.x, n.y, w, h, 10, 10));
        g.setColor(sel ? head.brighter() : head);
        g.fill(new RoundRectangle2D.Double(n.x, n.y, w, HEAD, 10, 10));
        g.fillRect(n.x, n.y + HEAD - 6, w, 6);
        g.setStroke(new BasicStroke(prim ? 2.2f : 1.4f));
        g.setColor(err ? BAD : warn ? WARN : prim ? Color.WHITE : sel ? SELECT : EDGE);
        g.draw(new RoundRectangle2D.Double(n.x, n.y, w, h, 10, 10));
        if (prim && isFocusOwner() && settings.keyboardHints) {
            g.setStroke(new BasicStroke(1.4f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10, new float[] {4, 3}, 0));
            g.setColor(SELECT);
            g.draw(new RoundRectangle2D.Double(n.x - 4, n.y - 4, w + 8, h + 8, 14, 14));
        }
        String category = MosaicFunction.isFunctionType(n.type)
            ? (model.doc.function(MosaicFunction.nameOf(n.type)) == null ? "My nodes" : String.valueOf(model.doc.function(MosaicFunction.nameOf(n.type)).category))
            : MosaicCatalog.of(n.type).category().label;
        Graphics2D gi = (Graphics2D) g.create();
        gi.translate(n.x + 5, n.y + 3);
        Icons.draw(gi, category, Color.WHITE, 16);
        gi.dispose();
        g.setFont(UI_B);
        g.setColor(Color.WHITE);
        String title = label(n);
        g.drawString(clip(g, title, w - 32), n.x + 26, n.y + 16);
        g.setFont(UI_S);
        List<String> ins = inputs(n), outs = outputs(n);
        Map<String, MosaicFunction> fns = model.functions();
        for (int k = 0; k < ins.size(); k++) {
            Point s = inSocket(n, k);
            String portName = ins.get(k);
            String ref = n.inputs.get(portName);
            MosaicSlangGraph.SPort sp = MosaicSlangGraph.handles(n.type) ? MosaicSlangGraph.input(n.type, n.mask, portName) : null;
            MosaicGraph.Kind kind = ref != null ? refKind(ref) : sp != null ? sp.kind() : MosaicGraph.acceptedKind(n.type, portName, fns);
            socket(g, s, kindColor(kind), ref != null);
            boolean required = sp != null ? sp.required() : MosaicGraph.portsOf(n.type, fns).stream().anyMatch(p -> p.name().equals(portName) && p.required());
            g.setColor(ref == null && required && !n.defaults.containsKey(portName) ? WARN : MUTED);
            String shown = portName;
            if (ref == null && n.defaults.containsKey(portName)) shown += "  " + InspectorPanel.numbers(n.defaults.get(portName));
            if (settings.portNames) g.drawString(clip(g, shown, w / 2 + 30), s.x + 9, s.y + 4);
        }
        for (int k = 0; k < outs.size(); k++) {
            Point s = outSocket(n, k);
            MosaicGraph.Kind kind = outKind(n, outs.get(k));
            socket(g, s, kindColor(kind), true);
            String l = outs.get(k).isEmpty() ? kindName(kind) : outs.get(k);
            g.setColor(MUTED);
            if (settings.portNames) g.drawString(l, s.x - 9 - g.getFontMetrics().stringWidth(l), s.y + 4);
        }
        List<String> code = codeLines(n);
        String param = code.isEmpty() ? paramText(n) : "";
        if (!param.isEmpty()) {
            g.setColor(TEXT);
            g.drawString(clip(g, param, w - 16), n.x + 8, n.y + h - 9);
        }
        if (!code.isEmpty() && zoom > 0.35) {
            int top = n.y + h - code.size() * CODE_LINE - 10;
            g.setColor(new Color(0x14171F));
            g.fillRoundRect(n.x + 4, top, w - 8, code.size() * CODE_LINE + 6, 6, 6);
            g.setFont(MONO.deriveFont(9.5f));
            g.setColor(new Color(0xD8C38A));
            int min = Integer.MAX_VALUE;
            for (String l : code) if (!l.equals("...")) min = Math.min(min, l.length() - l.stripLeading().length());
            for (int k = 0; k < code.size(); k++) {
                String l = code.get(k);
                if (!l.equals("...") && min != Integer.MAX_VALUE) l = l.substring(Math.min(min, l.length()));
                g.drawString(clip(g, l, w - 16), n.x + 9, top + 11 + k * CODE_LINE);
            }
            g.setFont(UI_S);
        }
        if (n.label != null && !n.label.isBlank()) {
            g.setColor(MUTED);
            g.setFont(UI_S);
            String type = MosaicFunction.isFunctionType(n.type) ? MosaicFunction.nameOf(n.type)
                : MosaicSlangGraph.handles(n.type) ? MosaicSlangGraph.label(n.type, n.mask) : MosaicCatalog.of(n.type).label();
            if (!type.equals(n.label)) g.drawString(clip(g, type, w), n.x + 2, n.y - 4);
        }
        if (n.note != null && !n.note.isBlank() && settings.showNotes && zoom > 0.3) note(g, n, w, h);
    }

    /** A developer's note under its node: up to four wrapped lines, the rest in the tooltip and the inspector. */
    private void note(Graphics2D g, MosaicDocument.ENode n, int w, int h) {
        g.setFont(UI_S.deriveFont(java.awt.Font.ITALIC));
        FontMetrics fm = g.getFontMetrics();
        int width = Math.max(w + 40, 220);
        List<String> lines = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String word : n.note.trim().split("\\s+")) {
            if (cur.length() > 0 && fm.stringWidth(cur + " " + word) > width) {
                lines.add(cur.toString());
                cur.setLength(0);
                if (lines.size() == 4) break;
            }
            if (cur.length() > 0) cur.append(' ');
            cur.append(word);
        }
        if (lines.size() < 4 && cur.length() > 0) lines.add(cur.toString());
        else if (lines.size() == 4) lines.set(3, clip(g, lines.get(3) + " " + cur, width));
        int y = n.y + h + 6;
        g.setColor(new Color(0xE8D9A8));
        g.fillRect(n.x, y, 2, lines.size() * (fm.getHeight() - 1) + 2);
        g.setColor(new Color(0xCFC7AE));
        for (String l : lines) {
            y += fm.getHeight() - 1;
            g.drawString(l, n.x + 7, y);
        }
    }

    private void socket(Graphics2D g, Point p, Color c, boolean filled) {
        g.setColor(BG.darker());
        g.fillOval(p.x - 6, p.y - 6, 12, 12);
        g.setColor(c);
        if (filled) g.fillOval(p.x - 4, p.y - 4, 8, 8);
        else {
            g.setStroke(new BasicStroke(1.6f));
            g.drawOval(p.x - 4, p.y - 4, 8, 8);
        }
    }

    static String clip(Graphics2D g, String s, int w) {
        FontMetrics fm = g.getFontMetrics();
        if (fm.stringWidth(s) <= w) return s;
        while (s.length() > 1 && fm.stringWidth(s + "...") > w) s = s.substring(0, s.length() - 1);
        return s + "...";
    }
}
