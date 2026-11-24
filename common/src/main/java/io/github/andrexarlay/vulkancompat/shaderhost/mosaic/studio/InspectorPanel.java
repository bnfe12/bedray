package io.github.andrexarlay.vulkancompat.shaderhost.mosaic.studio;

import io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicCatalog;
import io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicDocument;
import io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicFunction;
import io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicGraph;
import io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicWorld;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.Map;

import static io.github.andrexarlay.vulkancompat.shaderhost.mosaic.studio.StudioUi.*;

/**
 * Properties of the selection. Text wraps to the panel's real width, so it reads well in a narrow side area or a wide
 * pop-out window. It is rebuilt only when the selection or the selected node changes, never while you type in it.
 */
final class InspectorPanel extends Panels.Listening {

    private final JPanel content = new JPanel();
    private final JScrollPane root;
    private String shownKey = "";
    private int width = 220;

    InspectorPanel(StudioModel m) {
        super(m);
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setBackground(PANEL);
        content.setBorder(new EmptyBorder(10, 12, 10, 12));
        root = new JScrollPane(content, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        root.setBorder(null);
        root.getVerticalScrollBar().setUnitIncrement(16);
        darken(root);
        root.getViewport().addComponentListener(new ComponentAdapter() {
            @Override public void componentResized(ComponentEvent e) {
                int w = Math.max(120, root.getViewport().getWidth() - 48);
                if (Math.abs(w - width) > 8) {
                    width = w;
                    shownKey = "";
                    refresh();
                }
            }
        });
        refresh();
    }

    public JComponent component() { return root; }

    public void focus() {
        for (Component c : content.getComponents())
            if (c instanceof JTextField || c instanceof JComboBox || c instanceof JSpinner || c instanceof JScrollPane) {
                Component target = c instanceof JScrollPane sp ? sp.getViewport().getView() : c;
                target.requestFocusInWindow();
                return;
            }
        content.requestFocusInWindow();
    }

    /** Rebuild only when what is shown would change, and never under the user's typing. */
    @Override void refresh() {
        MosaicDocument.ENode n = model.primary;
        String key = model.selection.size() + "|" + System.identityHashCode(n) + "|" + System.identityHashCode(model.selectedFrame) + "|"
            + (n == null ? "" : n.type + n.inputs + java.util.Arrays.toString(n.value) + n.mask + n.index + n.label + defaultsKey(n))
            + "|" + model.analysis.problems().size() + "|" + width + "|" + model.doc.functions.size() + "|" + model.doc.nodes.size()
            + "|" + model.doc.frames.size() + "|" + System.identityHashCode(model.doc.file);
        if (key.equals(shownKey)) return;
        Component focus = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
        if (focus != null && SwingUtilities.isDescendingFrom(focus, content) && key.startsWith(shownKey.split("\\|")[0])) {
            if (shownKey.contains("|" + System.identityHashCode(n) + "|")) return; // same node: keep the field being edited
        }
        shownKey = key;
        content.removeAll();
        build(n);
        content.add(Box.createVerticalGlue());
        content.revalidate();
        content.repaint();
    }

    private JLabel text(String s, Color c) { return wrapped(esc(s), c, width); }

    private static String defaultsKey(MosaicDocument.ENode n) {
        StringBuilder sb = new StringBuilder();
        for (var e : n.defaults.entrySet()) sb.append(e.getKey()).append(java.util.Arrays.toString(e.getValue()));
        return sb.toString();
    }

    private void build(MosaicDocument.ENode n) {
        if (model.selection.size() > 1) {
            content.add(title(model.selection.size() + " nodes selected"));
            content.add(action("Arrange selection", StudioKeys.Action.ARRANGE, () -> model.arrange(height())));
            content.add(action("Group into frame", StudioKeys.Action.GROUP, () -> model.group(height())));
            content.add(action("Duplicate", StudioKeys.Action.DUPLICATE, model::duplicateSelection));
            content.add(action("Delete", StudioKeys.Action.DELETE, model::deleteSelection));
            return;
        }
        if (n == null && model.selectedFrame != null) {
            MosaicDocument.Frame f = model.selectedFrame;
            content.add(title("Frame"));
            JTextField t = field(f.title);
            t.addActionListener(e -> model.rename(t.getText()));
            content.add(t);
            content.add(text(model.doc.nodesIn(f).size() + " nodes inside. Drag the title to move them together, the corner to resize.", MUTED));
            JPanel colors = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 4));
            colors.setOpaque(false);
            colors.setAlignmentX(0);
            for (int c : new int[] {0x3A6EA5, 0x7A4FA8, 0x2F8F6F, 0xA5683A, 0xA53A5E, 0x5E7A2F, 0x555555}) {
                JButton b = new JButton();
                b.setPreferredSize(new Dimension(22, 22));
                b.setBackground(new Color(c));
                b.setBorder(BorderFactory.createLineBorder(c == f.color ? Color.WHITE : EDGE, 2));
                b.setFocusable(false);
                b.addActionListener(e -> model.edit("Frame color", () -> f.color = c));
                colors.add(b);
            }
            content.add(colors);
            content.add(button("Any color...", "Pick any frame color", () -> {
                Color c = JColorChooser.showDialog(content, "Frame color", new Color(f.color));
                if (c != null) model.edit("Frame color", () -> f.color = c.getRGB() & 0xFFFFFF);
            }));
            content.add(action("Remove frame (keeps nodes)", StudioKeys.Action.UNGROUP, model::ungroup));
            return;
        }
        if (n == null) {
            content.add(title("Graph"));
            content.add(text(model.doc.nodes.size() + " nodes, " + model.doc.functions.size() + " node functions, "
                + model.doc.frames.size() + " frames.", MUTED));
            content.add(Box.createVerticalStrut(8));
            content.add(text("Click a node (or Tab / Alt+arrows) to edit it. Enter focuses this panel.", MUTED));
            content.add(Box.createVerticalStrut(8));
            content.add(text("Socket colors: grey float, green vec2, amber vec3, violet vec4, white several.", FAINT));
            return;
        }
        boolean fn = MosaicFunction.isFunctionType(n.type);
        MosaicDocument.FDef def = fn ? model.doc.function(MosaicFunction.nameOf(n.type)) : null;
        MosaicCatalog.Entry e = MosaicCatalog.of(n.type);
        String typeLabel = fn ? (def != null && def.label != null ? def.label : n.type) : e.label();
        content.add(title(n.label != null ? n.label : typeLabel));
        content.add(text(n.id + "   " + (fn ? "node code" : e.category().label) + (n.label != null ? "   (" + typeLabel + ")" : ""), MUTED));
        content.add(Box.createVerticalStrut(4));
        content.add(text(fn ? (def == null || def.help == null ? "" : def.help) : e.help(), MUTED));
        for (MosaicGraph.Problem p : model.analysis.of(n.id))
            content.add(text(p.message(), p.severity() == MosaicGraph.Severity.ERROR ? BAD : WARN));
        content.add(section("Label"));
        JTextField label = field(n.label == null ? "" : n.label);
        label.setToolTipText("Shown on the node instead of its type; empty restores the type name. Enter applies.");
        label.addActionListener(ev -> model.rename(label.getText()));
        content.add(label);
        params(n);
        if (def != null) code(def);
        content.add(section("Inputs"));
        Map<String, MosaicFunction> fns = model.functions();
        for (MosaicGraph.Port port : MosaicGraph.portsOf(n.type, fns)) inputRow(n, port, fns);
        content.add(action("Feed an input from...", StudioKeys.Action.CONNECT_INTO, () -> {
            if (model.activeGraph != null) model.activeGraph.connectInto();
        }));
        content.add(action("Wire the output into...", StudioKeys.Action.CONNECT_FROM, () -> {
            if (model.activeGraph != null) model.activeGraph.connectFrom();
        }));
        content.add(Box.createVerticalStrut(6));
        content.add(action("Duplicate", StudioKeys.Action.DUPLICATE, model::duplicateSelection));
        content.add(action("Delete", StudioKeys.Action.DELETE, model::deleteSelection));
    }

    private java.util.function.ToIntFunction<MosaicDocument.ENode> height() {
        GraphPanel g = model.activeGraph;
        return g == null ? x -> 60 : g::height;
    }

    private JLabel section(String s) {
        JLabel l = title(s);
        l.setFont(UI_B);
        l.setForeground(MUTED);
        l.setBorder(new EmptyBorder(12, 0, 4, 0));
        return l;
    }

    private JButton action(String label, StudioKeys.Action a, Runnable r) {
        String keys = model.keys.humanFor(a);
        JButton b = button(label, keys.isEmpty() ? null : label + "  (" + keys + ")", r);
        b.setAlignmentX(0);
        b.setMaximumSize(new Dimension(Integer.MAX_VALUE, b.getPreferredSize().height + 2));
        b.setHorizontalAlignment(SwingConstants.LEFT);
        JPanel spacer = new JPanel();
        return b;
    }

    /**
     * One input, Blender-style: wired shows its source; unwired shows its value as a number (Enter applies) with a
     * slider for floats; a wire connected later overrides it, and the value comes back when the wire goes.
     */
    private void inputRow(MosaicDocument.ENode n, MosaicGraph.Port port, Map<String, MosaicFunction> fns) {
        String ref = n.inputs.get(port.name());
        MosaicGraph.Kind accepted = MosaicGraph.acceptedKind(n.type, port.name(), fns);
        if (ref != null) {
            content.add(text(port.name() + "   <- " + ref, TEXT));
            return;
        }
        double[] builtin = io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicEval.portDefault(n.type, port.name());
        if (MosaicFunction.isFunctionType(n.type)) {
            MosaicFunction f = fns.get(MosaicFunction.nameOf(n.type));
            if (f != null) for (MosaicFunction.Param p : f.params()) if (p.name().equals(port.name()) && p.defaultValue() != null) builtin = p.defaultValue();
        }
        double[] current = n.defaults.containsKey(port.name()) ? n.defaults.get(port.name()) : builtin;
        if (current == null && !port.required() && (port.name().equals("vector") || port.name().equals("uv") || port.name().equals("origin")
            || port.name().equals("direction"))) {
            content.add(text(port.name() + "   automatic (" + (port.name().equals("vector") ? "screen coordinates" : "per pixel") + ")", FAINT));
            return;
        }
        int size = accepted != null ? accepted.size() : current != null ? current.length : 1;
        double[] shown = current != null ? current : new double[size];
        JLabel name = text(port.name() + (port.required() && current == null ? "   (needs a value or a wire)" : ""),
            port.required() && current == null ? WARN : MUTED);
        content.add(name);
        JTextField f = field(numbers(shown));
        f.setToolTipText("Inline value: 1 to " + Math.max(1, size) + " numbers, Enter applies. Connect a wire to drive it with a map.");
        f.addActionListener(ev -> {
            double[] v = parse(f.getText());
            if (v == null) {
                f.setForeground(BAD);
                return;
            }
            model.edit("Set " + n.id + "." + port.name(), () -> n.defaults.put(port.name(), v));
        });
        content.add(f);
        if (size == 1) {
            double v0 = shown[0];
            double max = Math.max(1, Math.abs(v0) * 2), min = v0 < 0 ? -max : 0;
            JSlider s = new JSlider(0, 1000, (int) Math.round((v0 - min) / (max - min) * 1000));
            s.setBackground(PANEL);
            s.setAlignmentX(0);
            s.setMaximumSize(new Dimension(Integer.MAX_VALUE, s.getPreferredSize().height));
            String[] before = {null};
            s.addChangeListener(ev -> {
                double v = min + (max - min) * s.getValue() / 1000.0;
                v = Math.round(v * 1000) / 1000.0;
                if (before[0] == null) before[0] = model.doc.snapshot();
                n.defaults.put(port.name(), new double[] {v});
                f.setText(numbers(new double[] {v}));
                model.doc.dirty = true;
                if (!s.getValueIsAdjusting()) {
                    model.commitGesture(before[0], "Set " + n.id + "." + port.name());
                    before[0] = null;
                } else model.changed();
            });
            content.add(s);
        }
        if (n.defaults.containsKey(port.name())) {
            JButton reset = button("Reset " + port.name(), "Back to the node's default", () -> model.edit("Reset " + n.id + "." + port.name(),
                () -> n.defaults.remove(port.name())));
            reset.setAlignmentX(0);
            content.add(reset);
        }
    }

    private void modeCombo(MosaicDocument.ENode n, String title, java.util.List<String> modes, String fallback) {
        content.add(section(title));
        JComboBox<String> box = new JComboBox<>(modes.toArray(new String[0]));
        box.setSelectedItem(n.mask == null ? fallback : n.mask);
        box.addActionListener(ev -> model.edit(title + " of " + n.id + ": " + box.getSelectedItem(), () -> n.mask = (String) box.getSelectedItem()));
        box.setAlignmentX(0);
        box.setMaximumSize(new Dimension(Integer.MAX_VALUE, box.getPreferredSize().height));
        content.add(box);
    }

    private void clampBox(MosaicDocument.ENode n) {
        JCheckBox cb = new JCheckBox("Clamp result to 0..1", (n.index & 1) != 0);
        cb.setOpaque(false);
        cb.setForeground(TEXT);
        cb.setAlignmentX(0);
        cb.addActionListener(ev -> model.edit("Clamp " + n.id, () -> n.index = cb.isSelected() ? n.index | 1 : n.index & ~1));
        content.add(cb);
    }

    private void rampEditor(MosaicDocument.ENode n) {
        content.add(section("Color stops"));
        double[] s = n.value == null || n.value.length < 5 ? new double[] {0, 0, 0, 0, 1, 1, 1, 1, 1, 1} : n.value;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i + 4 < s.length; i += 5)
            sb.append(numbers(new double[] {s[i]})).append(" : ").append(numbers(new double[] {s[i + 1], s[i + 2], s[i + 3], s[i + 4]})).append('\n');
        JTextArea area = new JTextArea(sb.toString(), Math.max(2, s.length / 5), 18);
        area.setFont(MONO);
        area.setBackground(CODE_BG);
        area.setForeground(TEXT);
        area.setCaretColor(TEXT);
        JScrollPane sp = scroll(area);
        sp.setAlignmentX(0);
        sp.setMaximumSize(new Dimension(Integer.MAX_VALUE, 200));
        content.add(sp);
        content.add(text("One stop per line: position : r, g, b, a (positions 0..1, ascending, up to 16 stops).", FAINT));
        content.add(button("Apply stops", null, () -> {
            java.util.List<Double> vals = new java.util.ArrayList<>();
            for (String line : area.getText().split("\n")) {
                if (line.isBlank()) continue;
                String[] parts = line.split(":");
                double[] pos = parts.length == 2 ? parse(parts[0]) : null, col = parts.length == 2 ? parse(parts[1]) : null;
                if (pos == null || col == null) {
                    model.say("Each stop needs 'position : r, g, b, a'", BAD);
                    return;
                }
                vals.add(pos[0]);
                for (int k = 0; k < 4; k++) vals.add(k < col.length ? col[k] : (k == 3 ? 1 : col[col.length - 1]));
            }
            double[] v = vals.stream().mapToDouble(Double::doubleValue).toArray();
            model.edit("Color ramp of " + n.id, () -> n.value = v);
        }));
    }

    private void params(MosaicDocument.ENode n) {
        switch (n.type) {
            case "mosaic.math" -> {
                modeCombo(n, "Operation", MosaicGraph.MATH_OPS, "add");
                clampBox(n);
            }
            case "mosaic.mixColor" -> {
                modeCombo(n, "Blend", MosaicGraph.BLEND_MODES, "mix");
                clampBox(n);
            }
            case "mosaic.mapRange" -> {
                modeCombo(n, "Interpolation", java.util.List.of("linear", "smooth"), "linear");
                clampBox(n);
            }
            case "mosaic.colorRamp" -> {
                modeCombo(n, "Interpolation", MosaicGraph.RAMP_MODES, "linear");
                rampEditor(n);
            }
            case "mosaic.gradient" -> modeCombo(n, "Type", MosaicGraph.GRADIENT_MODES, "linear");
            case "mosaic.wave" -> modeCombo(n, "Type", MosaicGraph.WAVE_MODES, "bands");
            default -> { }
        }
        switch (n.type) {
            case "mosaic.const" -> {
                content.add(section("Value"));
                JTextField f = field(numbers(n.value));
                f.setToolTipText("1 to 4 numbers separated by commas; Enter applies");
                f.addActionListener(ev -> {
                    double[] v = parse(f.getText());
                    if (v == null) {
                        f.setForeground(BAD);
                        return;
                    }
                    model.edit("Set " + n.id + " = " + f.getText().trim(), () -> n.value = v);
                });
                content.add(f);
            }
            case "mosaic.swizzle" -> {
                content.add(section("Components"));
                JTextField f = field(n.mask == null ? "" : n.mask);
                f.setToolTipText("1 to 4 of xyzw or rgba; Enter applies");
                f.addActionListener(ev -> model.edit("Swizzle " + n.id + " ." + f.getText().trim(), () -> n.mask = f.getText().trim()));
                content.add(f);
            }
            case "mosaic.materialMask", "mosaic.materialDepth" -> {
                content.add(section("Material"));
                JComboBox<String> box = new JComboBox<>();
                for (MosaicWorld.Material mat : MosaicWorld.Material.values()) box.addItem(mat.id() + "  " + mat.label);
                box.setSelectedIndex(Math.clamp(n.index, 0, MosaicWorld.Material.values().length - 1));
                box.addActionListener(ev -> model.edit("Material of " + n.id, () -> n.index = box.getSelectedIndex()));
                box.setAlignmentX(0);
                box.setMaximumSize(new Dimension(Integer.MAX_VALUE, box.getPreferredSize().height));
                content.add(box);
            }
            case "mosaic.push", "mosaic.buffer", "mosaic.vec" -> {
                int max = switch (n.type) { case "mosaic.push" -> 3; case "mosaic.buffer" -> 1023; default -> 4; };
                int min = n.type.equals("mosaic.vec") ? 2 : 0;
                content.add(section(n.type.equals("mosaic.vec") ? "Size" : "Slot"));
                JSpinner s = new JSpinner(new SpinnerNumberModel(Math.clamp(n.index, min, max), min, max, 1));
                s.addChangeListener(ev -> model.edit("Set " + n.id + " " + s.getValue(), () -> n.index = (Integer) s.getValue()));
                s.setAlignmentX(0);
                s.setMaximumSize(new Dimension(Integer.MAX_VALUE, s.getPreferredSize().height));
                content.add(s);
            }
            default -> { }
        }
    }

    private void code(MosaicDocument.FDef def) {
        content.add(section("Signature"));
        JTextField sig = field(def.signature);
        content.add(sig);
        content.add(section("Code"));
        JTextArea body = new JTextArea(def.glsl, 10, 20);
        body.setFont(MONO);
        body.setBackground(CODE_BG);
        body.setForeground(TEXT);
        body.setCaretColor(TEXT);
        body.setTabSize(4);
        JScrollPane sp = scroll(body);
        sp.setAlignmentX(0);
        sp.setPreferredSize(new Dimension(width, 200));
        sp.setMaximumSize(new Dimension(Integer.MAX_VALUE, 360));
        content.add(sp);
        content.add(text("Ctrl+Enter applies. Helpers: mosaicScene(uv), mosaicDepth(uv), mosaicNormal(uv), mosaicHitPos(uv), "
            + "mosaicTrace(o,d,tMax), mosaicShadow(o,d), mosaicVoxelTrace(...), mosaicMaterialAt(p), mosaicSkyLightAt(p), "
            + "mosaicSky(dir), mosaicSunDir(), frame.timing.x", FAINT));
        Runnable apply = () -> model.applyCode(def, sig.getText(), body.getText());
        content.add(button("Apply code", "Ctrl+Enter", apply));
        body.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.CTRL_DOWN_MASK), "apply");
        body.getActionMap().put("apply", new AbstractAction() {
            public void actionPerformed(java.awt.event.ActionEvent e) { apply.run(); }
        });
        sig.addActionListener(e -> apply.run());
    }

    static String numbers(double[] v) {
        if (v == null) return "0";
        StringBuilder sb = new StringBuilder();
        for (double d : v) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(d == Math.rint(d) && Math.abs(d) < 1e15 ? String.valueOf((long) d) : String.valueOf(d));
        }
        return sb.toString();
    }

    static double[] parse(String t) {
        String[] parts = t.trim().split("[,;\\s]+");
        if (parts.length < 1 || parts.length > 4 || parts[0].isEmpty()) return null;
        double[] v = new double[parts.length];
        try {
            for (int i = 0; i < v.length; i++) v[i] = Double.parseDouble(parts[i]);
        } catch (NumberFormatException e) {
            return null;
        }
        return v;
    }
}
