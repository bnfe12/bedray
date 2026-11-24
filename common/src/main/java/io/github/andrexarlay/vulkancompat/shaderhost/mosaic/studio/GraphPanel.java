package io.github.andrexarlay.vulkancompat.shaderhost.mosaic.studio;

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
    private boolean fitted;

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
                mouseScreen = e.getPoint();
                Socket s = socketAt(e.getPoint());
                setToolTipText(s == null ? nodeTip(e.getPoint()) : socketTip(s));
            }
            @Override public void mouseEntered(MouseEvent e) { model.activeGraph = GraphPanel.this; }
            @Override public void mouseWheelMoved(MouseWheelEvent e) { wheel(e); }
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() != 2 || !SwingUtilities.isLeftMouseButton(e)) return;
                MosaicDocument.Frame f = frameTitleAt(e.getPoint());
                MosaicDocument.ENode n = nodeAt(e.getPoint());
                if (n != null && toWorld(e.getPoint()).y < n.y + HEAD) renameDialog();
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
    }

    /** The view was restored from project metadata: do not auto-fit on the first paint. */
    void markFitted() { fitted = true; }

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
        return HEAD + rows * ROW + (paramText(n).isEmpty() ? 0 : ROW) + 8;
    }

    List<String> inputs(MosaicDocument.ENode n) {
        List<String> out = new ArrayList<>();
        for (MosaicGraph.Port p : MosaicGraph.portsOf(n.type, model.functions())) out.add(p.name());
        return out;
    }

    static List<String> outputs(MosaicDocument.ENode n) {
        List<String> out = new ArrayList<>();
        if (n.type.equals("mosaic.output")) return out;
        out.add("");
        out.addAll(MosaicGraph.namedOutputs(n.type));
        return out;
    }

    Point inSocket(MosaicDocument.ENode n, int row) { return new Point(n.x, n.y + HEAD + row * ROW + ROW / 2 + 2); }

    Point outSocket(MosaicDocument.ENode n, int row) { return new Point(n.x + W(), n.y + HEAD + row * ROW + ROW / 2 + 2); }

    MosaicGraph.Kind outKind(MosaicDocument.ENode n, String out) {
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
        return MosaicCatalog.of(n.type).label();
    }

    Color headColor(MosaicDocument.ENode n) {
        if (MosaicFunction.isFunctionType(n.type)) {
            MosaicDocument.FDef d = model.doc.function(MosaicFunction.nameOf(n.type));
            return categoryColor(d == null ? null : d.category);
        }
        return new Color(MosaicCatalog.of(n.type).category().color, true);
    }

    String paramText(MosaicDocument.ENode n) {
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
            default -> "";
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

    private void press(MouseEvent e) {
        requestFocusInWindow();
        model.activeGraph = this;
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
            contextMenu(n).show(this, e.getX(), e.getY());
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
        repaint();
    }

    private void release(MouseEvent e) {
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
        zoom = Math.clamp(zoom * factor, 0.25, 3.0);
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
        zoom = Math.clamp(Math.min((getWidth() - 80.0) / Math.max(1, maxX - minX), (getHeight() - 80.0) / Math.max(1, maxY - minY)), 0.25, 1.4);
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
            return m;
        }
        item(m, "Rename / label...", k.humanFor(StudioKeys.Action.RENAME), this::renameDialog);
        item(m, model.gamePreview != null && model.gamePreview.equals(n.id) ? "Stop preview in game" : "Preview this node in game", "",
            () -> model.previewInGame(model.gamePreview != null && model.gamePreview.equals(n.id) ? null : n));
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
            fit();
        }
        Graphics2D g = (Graphics2D) g0.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(BG);
        g.fillRect(0, 0, getWidth(), getHeight());
        grid(g);
        g.translate(panX, panY);
        g.scale(zoom, zoom);
        for (MosaicDocument.Frame f : model.doc.frames) frame(g, f);
        g.setStroke(new BasicStroke(settings.wireWidth, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        for (MosaicDocument.ENode n : model.doc.nodes) {
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

    private void node(Graphics2D g, MosaicDocument.ENode n) {
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
            MosaicGraph.Kind kind = ref != null ? refKind(ref) : MosaicGraph.acceptedKind(n.type, portName, fns);
            socket(g, s, kindColor(kind), ref != null);
            boolean required = MosaicGraph.portsOf(n.type, fns).stream().anyMatch(p -> p.name().equals(portName) && p.required());
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
        String param = paramText(n);
        if (!param.isEmpty()) {
            g.setColor(TEXT);
            g.drawString(clip(g, param, w - 16), n.x + 8, n.y + h - 9);
        }
        if (n.label != null && !n.label.isBlank()) {
            g.setColor(MUTED);
            g.setFont(UI_S);
            String type = MosaicFunction.isFunctionType(n.type) ? MosaicFunction.nameOf(n.type) : MosaicCatalog.of(n.type).label();
            g.drawString(clip(g, type, w), n.x + 2, n.y - 4);
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
