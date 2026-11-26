package io.github.andrexarlay.vulkancompat.shaderhost.mosaic.studio;

import io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicDocument;
import io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicEffects;
import io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicFunction;
import io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicGraph;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.github.andrexarlay.vulkancompat.shaderhost.mosaic.studio.StudioUi.*;

/**
 * Scene: the graph as Blender's outliner shows a scene - what each part of the picture is made of. Groups are the
 * graph's frames (like collections); inside them the parts of Minecraft's picture (Sky, Water, Lava, Leaves, ...), the
 * effects, the passes (buffers), Bedray and the output, each with one line saying what it does. Click: select and show
 * it; the dot: on / off (mute); double click: rename. "Add part" puts a Minecraft part into the chain before the output.
 */
final class ScenePanel extends Panels.Listening {

    private final JPanel root = new JPanel(new BorderLayout());
    private final DefaultMutableTreeNode top = new DefaultMutableTreeNode("Scene");
    private final DefaultTreeModel treeModel = new DefaultTreeModel(top);
    private final JTree tree = new JTree(treeModel);
    private String shownKey = "";

    /** One row: a node (or a group = frame) with its role. */
    private record Row(MosaicDocument.ENode node, MosaicDocument.Frame frame, String title, String role, Color color) {
        @Override public String toString() { return title; }
    }

    ScenePanel(StudioModel m) {
        super(m);
        root.setBackground(PANEL);
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        bar.setBackground(PANEL2);
        JButton add = button("Add part", "Put a part of Minecraft's picture (water, sky, leaves...) into the chain before the output", () -> { });
        add.addActionListener(e -> {
            JPopupMenu pm = addMenu();
            StudioWidgets.fitToScreen(pm, add);
            pm.show(add, 0, add.getHeight());
        });
        bar.add(add);
        bar.add(button("Group", "Put the selected nodes into a group (a frame), like a collection", () -> model.runAction.accept(StudioKeys.Action.GROUP)));
        bar.add(button("By parts", "Start from the template Minecraft by parts: every part of the picture as one node", () -> {
            var t = io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicTemplates.all().stream()
                .filter(x -> x.name().equals("Minecraft by parts")).findFirst().orElse(null);
            if (t != null) model.loadTemplate(t);
        }));
        root.add(bar, BorderLayout.NORTH);
        tree.setRootVisible(false);
        tree.setShowsRootHandles(true);
        tree.setBackground(PANEL);
        tree.setRowHeight(0);
        tree.setCellRenderer(new DefaultTreeCellRenderer() {
            @Override public Component getTreeCellRendererComponent(JTree t, Object v, boolean sel, boolean exp, boolean leaf, int row, boolean focus) {
                JLabel l = (JLabel) super.getTreeCellRendererComponent(t, v, sel, exp, leaf, row, false);
                Object u = ((DefaultMutableTreeNode) v).getUserObject();
                l.setOpaque(true);
                l.setBackground(sel ? StudioWidgets.mix(PANEL, SELECT, 0.35) : PANEL);
                l.setBorder(new EmptyBorder(2, 2, 2, 6));
                l.setFont(UI);
                if (u instanceof Row r) {
                    boolean muted = r.node() != null && r.node().muted;
                    String dot = r.node() == null ? "" : muted ? "○ " : "● ";
                    int w = Math.max(160, t.getWidth() - 60);
                    l.setText("<html><div style='width:" + w + "px'><span style='color:" + hex(muted ? FAINT : r.color()) + "'>" + dot + "</span>"
                        + "<b>" + esc(r.title()) + "</b>" + (r.role().isEmpty() ? "" : "<br><span style='color:" + hex(FAINT) + "'>"
                        + esc(r.role()) + "</span>") + "</div></html>");
                    l.setForeground(muted ? FAINT : TEXT);
                } else {
                    l.setText(String.valueOf(u));
                    l.setForeground(MUTED);
                    l.setFont(UI_B);
                }
                l.setIcon(null);
                return l;
            }
        });
        tree.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                TreePath p = tree.getPathForLocation(e.getX(), e.getY());
                if (p == null) return;
                Object u = ((DefaultMutableTreeNode) p.getLastPathComponent()).getUserObject();
                if (!(u instanceof Row r)) return;
                Rectangle b = tree.getPathBounds(p);
                boolean onDot = b != null && e.getX() < b.x + 18 && r.node() != null;
                if (onDot) {
                    model.select(r.node());
                    model.toggleMute();
                    return;
                }
                if (r.node() != null) {
                    model.select(r.node());
                    if (model.activeGraph != null) model.activeGraph.center(r.node());
                    if (e.getClickCount() == 2 && model.activeGraph != null) model.activeGraph.renameDialog();
                } else if (r.frame() != null) {
                    model.select(null);
                    model.selectedFrame = r.frame();
                    model.selection.addAll(model.doc.nodesIn(r.frame()));
                    model.selectionChanged();
                    if (e.getClickCount() == 2 && model.activeGraph != null) model.activeGraph.renameDialog();
                }
            }
        });
        root.add(scroll(tree), BorderLayout.CENTER);
        JLabel hint = label("Click: show it. The dot: on / off. Double click: rename.", FAINT);
        hint.setFont(UI_S);
        hint.setBorder(new EmptyBorder(3, 8, 3, 8));
        root.add(hint, BorderLayout.SOUTH);
        refresh();
    }

    private static String hex(Color c) { return String.format("#%02X%02X%02X", c.getRed(), c.getGreen(), c.getBlue()); }

    private JPopupMenu addMenu() {
        JPopupMenu m = new JPopupMenu();
        for (MosaicEffects.Part p : MosaicEffects.PARTS) {
            JMenuItem it = new JMenuItem("<html><b>" + esc(p.label()) + "</b><br><span style='color:#8B93A8'>" + esc(p.help()) + "</span></html>");
            it.addActionListener(e -> {
                StudioModel.Item item = model.items().stream().filter(x -> x.type().equals("fn." + p.name())).findFirst().orElse(null);
                if (item != null) model.add(item, null, model.activeGraph == null ? new Point(0, 0) : model.activeGraph.viewCenter());
            });
            m.add(it);
        }
        return m;
    }

    /** What a node does, in one line. */
    private String role(MosaicDocument.ENode n) {
        if (MosaicGraph.isOutput(n.type)) return "the final picture of the frame: everything wired into it is what you see";
        if (n.type.equals("mosaic.pass")) return "pass: draws into buffer " + n.mask + " at 1/" + Math.max(1, n.index) + " size";
        if (n.type.equals("mosaic.sceneColor")) return "the picture Minecraft drew, before your nodes";
        if (n.type.equals("bedray.render")) return "Bedray's own renderer (double click in the graph: its stages)";
        if (MosaicFunction.isFunctionType(n.type)) {
            String name = MosaicFunction.nameOf(n.type);
            MosaicEffects.Part part = MosaicEffects.part(name);
            if (part != null) return part.help();
            MosaicFunction f = MosaicEffects.get(name);
            MosaicDocument.FDef d = model.doc.function(name);
            String help = f != null ? f.help() : d != null ? d.help : null;
            if (help != null) return help.length() > 120 ? help.substring(0, 117) + "..." : help;
        }
        var e = io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicCatalog.of(n.type);
        return e == null || e.help() == null ? "" : e.help();
    }

    private String title(MosaicDocument.ENode n) {
        if (n.label != null) return n.label;
        if (MosaicFunction.isFunctionType(n.type)) {
            String name = MosaicFunction.nameOf(n.type);
            MosaicEffects.Part part = MosaicEffects.part(name);
            if (part != null) return part.label() + (name.endsWith("Mask") ? " (mask)" : "");
            MosaicFunction f = MosaicEffects.get(name);
            if (f != null) return f.label();
        }
        var e = io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicCatalog.of(n.type);
        return e == null ? n.id : e.label() + (n.type.equals("mosaic.pass") && n.mask != null ? " " + n.mask : "");
    }

    /** Which list a node belongs in; small nodes (math, values) are not listed: they belong to what they feed. */
    private String kind(MosaicDocument.ENode n) {
        if (MosaicGraph.isOutput(n.type)) return "Output";
        if (MosaicFunction.isFunctionType(n.type) && MosaicEffects.part(MosaicFunction.nameOf(n.type)) != null) return "Minecraft parts";
        if (n.type.equals("mosaic.pass")) return "Passes (buffers)";
        if (n.type.startsWith("bedray.")) return "Bedray";
        if (n.type.equals("mosaic.sceneColor")) return "Inputs";
        if (MosaicFunction.isFunctionType(n.type)) return "Effects and lights";
        return null;
    }

    @Override void refresh() {
        StringBuilder k = new StringBuilder();
        for (MosaicDocument.ENode n : model.doc.nodes) k.append(n.id).append(n.type).append(n.label).append(n.muted).append(n.mask).append('|');
        for (MosaicDocument.Frame f : model.doc.frames) k.append(f.title).append(model.doc.nodesIn(f).size()).append('|');
        if (k.toString().equals(shownKey)) return;
        shownKey = k.toString();
        top.removeAllChildren();
        java.util.Set<MosaicDocument.ENode> grouped = new java.util.HashSet<>();
        for (MosaicDocument.Frame f : model.doc.frames) {
            DefaultMutableTreeNode g = new DefaultMutableTreeNode(new Row(null, f, f.title == null ? "Group" : f.title,
                model.doc.nodesIn(f).size() + " nodes", new Color(f.color)));
            for (MosaicDocument.ENode n : model.doc.nodesIn(f)) {
                if (kind(n) == null) continue;
                g.add(new DefaultMutableTreeNode(new Row(n, null, title(n), role(n), colorOf(n))));
                grouped.add(n);
            }
            top.add(g);
        }
        Map<String, DefaultMutableTreeNode> kinds = new LinkedHashMap<>();
        for (String order : List.of("Inputs", "Minecraft parts", "Effects and lights", "Passes (buffers)", "Bedray", "Output"))
            kinds.put(order, new DefaultMutableTreeNode(order));
        for (MosaicDocument.ENode n : model.doc.nodes) {
            String kd = kind(n);
            if (grouped.contains(n) || kd == null) continue;
            kinds.get(kd).add(new DefaultMutableTreeNode(new Row(n, null, title(n), role(n), colorOf(n))));
        }
        for (DefaultMutableTreeNode kn : kinds.values()) if (kn.getChildCount() > 0) top.add(kn);
        treeModel.reload();
        for (int i = 0; i < tree.getRowCount(); i++) tree.expandRow(i);
    }

    private Color colorOf(MosaicDocument.ENode n) {
        if (MosaicGraph.isOutput(n.type)) return new Color(0xE0605A);
        if (MosaicFunction.isFunctionType(n.type) && MosaicEffects.part(MosaicFunction.nameOf(n.type)) != null) return new Color(0x4FB886);
        if (n.type.equals("mosaic.pass")) return new Color(0x5AA0E0);
        return new Color(0xC8A050);
    }

    public JComponent component() { return root; }
}
