package io.github.andrexarlay.vulkancompat.shaderhost.mosaic.studio;

import io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicCatalog;
import io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicDocument;
import io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicFunction;
import io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicGraph;
import io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicMaterial;
import io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicRenderer;
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
        if (n == null && model.functionDoc()) {
            functionPanel();
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
        if (MosaicRenderer.isStage(n.type)) {
            stageEditor(n);
            return;
        }
        if (n.type.equals("user.post")) {
            boolean edited = java.nio.file.Files.exists(StudioModel.functionFile(StudioModel.POST_HOOK));
            content.add(Box.createVerticalStrut(6));
            content.add(text("Your own stage: name it with the label below. Its graph is Bedray's post hook (postStudio): the display colour,"
                + " uv, pixel and frame in, your colour out. It runs in Bedray's frame after the tone curve and Bedray's looks"
                + " (path tracer display, ray-effects tone mapping and the denoiser's compose).", MUTED));
            content.add(text(edited ? "Its graph is edited: Apply to Bedray sends it." : "Untouched: it passes the colour through.", edited ? OK : FAINT));
            content.add(button("Open its graph", "Build the stage from nodes (double-click the node does the same)", () -> {
                if (model.openFunction != null) model.openFunction.accept(StudioModel.POST_HOOK);
            }));
            content.add(button("Apply to Bedray", "Rebuilds the passes that run the hook with your graph", model::applyFunctions));
            content.add(section("Label"));
            JTextField label = field(n.label == null ? "" : n.label);
            label.addActionListener(ev -> model.rename(label.getText()));
            content.add(label);
            return;
        }
        if (n.type.equals("slang.module")) {
            moduleInfo(n);
            return;
        }
        if (io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicSlangGraph.handles(n.type)) {
            bedrayNode(n);
            return;
        }
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

    /** Image Texture: the picture, open a file (like Blender's Open), or drag one in from the Library. */
    private void imageEditor(MosaicDocument.ENode n) {
        content.add(section("Image"));
        MosaicMaterial.Image img = n.mask == null ? null : model.images.get(n.mask);
        if (img != null) content.add(picture(img));
        JLabel name = label(n.mask == null ? "No image yet" : n.mask + (img == null ? "  (missing)" : "  " + img.w() + "x" + img.h()), img == null ? WARN : MUTED);
        name.setAlignmentX(0);
        content.add(name);
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 4));
        row.setOpaque(false);
        row.setAlignmentX(0);
        row.add(button("Open image...", "Choose a PNG / JPG: it is copied into the project", () -> {
            JFileChooser fc = new JFileChooser();
            fc.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("Images", "png", "jpg", "jpeg", "bmp", "gif"));
            if (fc.showOpenDialog(content) == JFileChooser.APPROVE_OPTION) model.setImage(n, fc.getSelectedFile());
        }));
        if (n.mask != null) row.add(button("Reload", "Read the file again (after editing it elsewhere)", () -> model.reloadImage(n)));
        content.add(row);
        JLabel hint = label("Or drag a texture from the Library onto the graph", FAINT);
        hint.setFont(UI_S);
        hint.setAlignmentX(0);
        content.add(hint);
    }

    /** Block Texture: which texture of which source the material belongs to. */
    private void textureInfo() {
        content.add(section("Texture"));
        if (model.baseImage == null) {
            content.add(label("Pick a texture in the Library", WARN));
            return;
        }
        content.add(picture(model.paintedBase()));
        JLabel l = label(StudioLibrary.id(model.materialEntry) + "  " + model.baseImage.w() + "x" + model.baseImage.h()
            + (model.materialSource == null ? "" : "  -  " + model.materialSource.label()), MUTED);
        l.setAlignmentX(0);
        content.add(l);
    }

    private JComponent picture(MosaicMaterial.Image img) {
        int frame = Math.min(img.w(), img.h());
        java.awt.image.BufferedImage b = StudioLibrary.fromArgb(img.w(), img.h(), img.argb());
        JComponent c = new JComponent() {
            @Override protected void paintComponent(Graphics g0) {
                Graphics2D g = (Graphics2D) g0;
                int s = Math.min(getWidth(), 160);
                for (int y = 0; y < s; y += 8) for (int x = 0; x < s; x += 8) {
                    g.setColor(((x + y) / 8) % 2 == 0 ? PANEL2 : PANEL);
                    g.fillRect(x, y, 8, 8);
                }
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
                g.drawImage(b, 0, 0, s, s, 0, 0, frame, frame, null);
            }
        };
        c.setPreferredSize(new Dimension(160, 160));
        c.setMaximumSize(new Dimension(160, 160));
        c.setAlignmentX(0);
        return c;
    }

    /**
     * A Bedray renderer stage: every setting of the real renderer, as a switch, a slider with its number or a list, with
     * the text players see in Bedray's settings. A change is one undo step; Live sends it to the game at once.
     */
    private void stageEditor(MosaicDocument.ENode n) {
        MosaicRenderer.Stage stage = MosaicRenderer.stage(n.type);
        boolean wired = model.analysis.reachable().contains(n.id);
        content.add(Box.createVerticalStrut(6));
        content.add(text(wired ? "On: wired into the Bedray frame." : "Off: wire it into the Bedray frame to turn it on.", wired ? OK : WARN));
        if (!stage.enableKeys().isEmpty())
            content.add(text("Switch: " + String.join(", ", stage.enableKeys()) + " (follows the wire)", FAINT));
        boolean edited = model.stageEdited(n.type);
        content.add(text(edited ? "Your version: functions of this stage were edited (Apply to Bedray sends them)."
            : "Bedray's own: every function inside is Bedray's.", edited ? WARN : MUTED));
        content.add(button("Open inside: settings, modules, functions", "The stage as nodes: its public parameters and every function"
            + " of its modules, each one openable as nodes (double-click the stage does the same)", () -> {
                if (model.openStage != null) model.openStage.accept(n.type);
            }));
        content.add(section("Settings"));
        for (MosaicRenderer.Setting s : stage.settings()) {
            if (stage.enableKeys().contains(s.key()) && s.type() == MosaicRenderer.Type.BOOL) continue; // the wire is the switch
            double[] cur = n.defaults.get(s.key());
            double value = cur != null && cur.length > 0 ? cur[0] : s.def();
            settingRow(s, value, v -> set(n, s, v));
        }
        content.add(Box.createVerticalStrut(8));
        content.add(button("Defaults of this stage", "Every setting of this stage back to Bedray's default", () ->
            model.edit("Defaults of " + stage.label(), () -> stage.settings().forEach(s -> n.defaults.remove(s.key())))));
    }

    /** One setting: a toggle, a slider with its number, or a choice; with its help below. */
    private void settingRow(MosaicRenderer.Setting s, double value, java.util.function.DoubleConsumer onChange) {
        {
            JPanel row = new JPanel(new BorderLayout(10, 2));
            row.setOpaque(false);
            row.setAlignmentX(0);
            row.setBorder(new EmptyBorder(6, 0, 2, 0));
            JLabel name = label(s.label(), TEXT);
            name.setToolTipText(s.key());
            row.add(name, BorderLayout.CENTER);
            JComponent control;
            switch (s.type()) {
                case BOOL -> control = new StudioWidgets.Toggle(value != 0, v -> onChange.accept(v ? 1 : 0));
                case CHOICE -> {
                    JComboBox<String> box = new JComboBox<>(s.options().stream().map(o -> o.replace('_', ' ')).toArray(String[]::new));
                    box.setSelectedIndex((int) Math.max(0, Math.min(s.options().size() - 1, Math.round(value))));
                    box.addActionListener(ev -> onChange.accept(box.getSelectedIndex()));
                    control = box;
                }
                default -> {
                    JPanel p = new JPanel(new BorderLayout(6, 0));
                    p.setOpaque(false);
                    JSlider slider = new JSlider(s.min(), s.max(), (int) Math.round(Math.max(s.min(), Math.min(s.max(), value))));
                    JLabel num = label(Integer.toString(slider.getValue()), MUTED);
                    num.setPreferredSize(new Dimension(38, 18));
                    slider.addChangeListener(ev -> {
                        num.setText(Integer.toString(slider.getValue()));
                        if (!slider.getValueIsAdjusting()) onChange.accept(slider.getValue());
                    });
                    slider.setPreferredSize(new Dimension(Math.max(110, width - 190), 22));
                    p.add(slider, BorderLayout.CENTER);
                    p.add(num, BorderLayout.EAST);
                    control = p;
                }
            }
            row.add(control, BorderLayout.EAST);
            row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
            content.add(row);
            if (s.help() != null) {
                JLabel h = text(s.help(), FAINT);
                h.setFont(UI_S);
                h.setMaximumSize(new Dimension(Integer.MAX_VALUE, h.getPreferredSize().height));
                content.add(h);
            }
        }
    }

    /** A module of the pack map: what it is, what it declares, who controls it, its source. */
    // ------------------------------------------------------------------ Bedray function graphs

    /** The open function: what it is, whether slangc takes it, and applying it to Bedray. */
    private void functionPanel() {
        var lib = model.bedrayLibrary();
        var f = lib == null ? null : lib.functions().get(model.doc.bedrayFunction);
        content.add(title(f == null ? model.doc.bedrayFunction : f.label()));
        if (f == null) {
            content.add(text("This function is not in the Bedray pack Studio found.", BAD));
            return;
        }
        content.add(text(f.module() + ".slang" + (f.owner() != null ? "   method of " + f.owner() + (f.mutating() ? " (mutating)" : "") : ""), MUTED));
        content.add(Box.createVerticalStrut(4));
        content.add(code(signature(f)));
        if (f.comment() != null) {
            content.add(section("Developer's note"));
            content.add(text(f.comment(), TEXT));
        }
        content.add(section("Slang"));
        Boolean ok = model.functionOk;
        content.add(text(model.functionStatus.isEmpty() ? "Edit the graph: slangc checks it inside its module after every change."
            : model.functionStatus, ok == null ? MUTED : ok ? OK : BAD));
        long blocks = model.doc.nodes.stream().filter(x -> x.type.equals(io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicSlangGraph.CODE)).count();
        content.add(text(model.doc.nodes.size() + " nodes" + (blocks == 0 ? ", the whole body as nodes." : ", " + blocks
            + " Slang block(s): loops and branches stay code you can edit (select one)."), FAINT));
        if (!f.calls().isEmpty()) {
            content.add(section("Calls"));
            content.add(text(String.join(", ", f.calls()), MUTED));
        }
        content.add(Box.createVerticalStrut(8));
        content.add(button("Apply to Bedray", "Writes your edited functions into the game's bedray-studio/slang and rebuilds the passes that use them",
            model::applyFunctions));
        content.add(button("Show the Slang this graph writes", "The function body generated from the nodes", () -> showText(
            model.functionCode == null ? "The graph has errors: " + model.analysis.firstError() : model.functionCode.helpers() + signature(f) + " {\n"
                + model.functionCode.body() + "}", f.label() + " (from the graph)")));
        content.add(button("Show Bedray's source", "The original function in its module", () -> showText(
            lib.sources().getOrDefault(f.module() + ".slang", "").substring(f.start(), f.end()), f.label() + " (Bedray)")));
        content.add(button("Remove Studio passes from the game", "Bedray runs its own passes again (your function graphs stay)",
            () -> StudioPack.clear(model)));
        content.add(button("Reset to Bedray's", "Throws away your edits of this function", () -> {
            if (JOptionPane.showConfirmDialog(content, "Throw away your edits of " + f.label() + "?", "Reset", JOptionPane.OK_CANCEL_OPTION)
                == JOptionPane.OK_OPTION) model.resetFunction();
        }));
    }

    static String signature(io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicSlangLib.Function f) {
        return f.returnType() + " " + f.label() + "(" + String.join(", ", f.params().stream()
            .map(p -> (p.direction().equals("in") ? "" : p.direction() + " ") + p.type() + " " + p.name()).toList()) + ")";
    }

    private JComponent code(String s) {
        JTextArea a = new JTextArea(s);
        a.setFont(MONO);
        a.setEditable(false);
        a.setLineWrap(true);
        a.setWrapStyleWord(true);
        a.setBackground(CODE_BG);
        a.setForeground(TEXT);
        a.setBorder(new EmptyBorder(4, 6, 4, 6));
        a.setAlignmentX(0);
        return a;
    }

    private void showText(String s, String title) {
        JTextArea area = new JTextArea(s, 30, 100);
        area.setFont(MONO);
        area.setEditable(false);
        area.setBackground(CODE_BG);
        area.setForeground(TEXT);
        area.setCaretPosition(0);
        JOptionPane.showMessageDialog(content, scroll(area), title, JOptionPane.PLAIN_MESSAGE);
    }

    /** A node of a function graph: a pack call, a struct, a constant, a variable, an operator, a call, a code block. */
    private void bedrayNode(MosaicDocument.ENode n) {
        String t = n.type;
        var G = io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicSlangGraph.class;
        for (MosaicGraph.Problem p : model.analysis.of(n.id))
            content.add(text(p.message(), p.severity() == MosaicGraph.Severity.ERROR ? BAD : WARN));
        if (n.note != null && !n.note.isBlank()) {
            content.add(section("Developer's note"));
            content.add(text(n.note, TEXT));
        }
        if (t.equals(io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicSlangGraph.SETTING)) {
            MosaicRenderer.Setting s = MosaicRenderer.setting(n.mask);
            if (s == null) {
                content.add(text("No setting " + n.mask + " in this Bedray", BAD));
                return;
            }
            content.add(text("Public parameter " + s.key() + ": Studio's value wins over the game's menu while the renderer graph is live.", MUTED));
            double v = n.value != null && n.value.length > 0 ? n.value[0] : s.def();
            settingRow(s, v, nv -> {
                if (n.value != null && n.value.length > 0 && n.value[0] == nv) return;
                model.edit(s.label() + " = " + s.format(nv), () -> n.value = new double[] {nv});
            });
            content.add(button("Bedray's default (" + s.format(s.def()) + ")", "Back to the default", () ->
                model.edit(s.label() + " default", () -> n.value = new double[] {s.def()})));
            return;
        }
        var f = io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicSlangGraph.function(t);
        if (f != null) {
            content.add(text(f.module() + ".slang", MUTED));
            content.add(code(signature(f)));
            if (f.comment() != null) content.add(text(f.comment(), TEXT));
            content.add(Box.createVerticalStrut(6));
            content.add(button("Open as nodes", "Its body in a tab of its own (double-click the node does the same)",
                () -> { if (model.openFunction != null) model.openFunction.accept(f.id()); }));
        } else if (t.equals(io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicSlangGraph.CODE)) {
            var sig = io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicSlangGraph.codeSignature(n.mask);
            content.add(section("Ports"));
            StringBuilder ports = new StringBuilder();
            for (String[] p : sig.params()) ports.append(p[0]).append(' ').append(p[1]).append(' ').append(p[2]).append("; ");
            ports.append("ret ").append(sig.ret());
            JTextField head = field(ports.toString());
            head.setToolTipText("in / inout ports and the return type, separated by ';'. Enter applies.");
            content.add(head);
            content.add(section("Slang"));
            JTextArea body = new JTextArea(sig.body(), Math.min(24, Math.max(6, (int) sig.body().lines().count() + 1)), 40);
            body.setFont(MONO);
            body.setBackground(CODE_BG);
            body.setForeground(TEXT);
            body.setCaretColor(TEXT);
            body.setTabSize(4);
            JScrollPane sp = scroll(body);
            sp.setAlignmentX(0);
            sp.setMaximumSize(new Dimension(Integer.MAX_VALUE, 420));
            content.add(sp);
            Runnable apply = () -> {
                String mask = "//@ " + head.getText().trim() + "\n" + body.getText();
                if (!mask.equals(n.mask)) model.edit("Edit Slang block", () -> n.mask = mask);
            };
            head.addActionListener(e -> apply.run());
            content.add(button("Apply the block", "Takes the ports and the code (Ctrl+Enter in the code does the same)", apply));
            body.getInputMap().put(KeyStroke.getKeyStroke("ctrl ENTER"), "applyBlock");
            body.getActionMap().put("applyBlock", new AbstractAction() {
                @Override public void actionPerformed(java.awt.event.ActionEvent e) { apply.run(); }
            });
            content.add(text("The block runs as written; its in ports are read, its inout ports come back changed. A block that"
                + " returns ends the function with its value.", FAINT));
        } else if (t.startsWith(io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicSlangGraph.CONST)) {
            var c = io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicSlangGraph.constant(t);
            if (c != null) {
                content.add(code(c.type() + " " + c.name() + " = " + c.value()));
                content.add(text(c.module() + ".slang" + (c.comment() == null ? "" : "   " + c.comment()), MUTED));
            }
        } else if (t.startsWith(io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicSlangGraph.MAKE)
            || t.startsWith(io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicSlangGraph.BREAK)) {
            var s = io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicSlangGraph.struct(t);
            if (s != null) {
                if (s.comment() != null) content.add(text(s.comment(), TEXT));
                content.add(section("Fields"));
                StringBuilder sb = new StringBuilder();
                for (var fl : s.fields()) sb.append(fl.type()).append(' ').append(fl.name()).append('\n');
                content.add(code(sb.toString().strip()));
            }
        } else {
            String what = switch (t) {
                case "slang.param" -> "Type and name of the parameter, e.g. float3 direction";
                case "slang.out" -> "Type and name of the out parameter, e.g. float3 weight";
                case "slang.let" -> "Type and name of the variable, e.g. float3 color (auto: Slang infers it)";
                case "slang.result" -> "The return type (void: no value)";
                case "slang.literal" -> "A Slang value or expression: 1.0, 2u, float3(0, 1, 0), PI, gFrame.time";
                case "slang.op" -> "The operator: + - * / % < > <= >= == != && || & | ^ << >> ?: [] (unary: -u !u ~u)";
                case "slang.call" -> "The function: normalize, lerp, float3; .Name calls a method of the self input; (float) casts";
                case "slang.member" -> "The field or swizzle, with its dot: .xyz, .position";
                case "slang.set" -> "The field to replace, with its dot: .position";
                default -> "Slang";
            };
            content.add(section("Slang"));
            JTextField m = field(n.mask == null ? "" : n.mask);
            m.setFont(MONO);
            m.setToolTipText(what + ". Enter applies.");
            m.addActionListener(e -> {
                String v = m.getText().trim();
                if (!v.equals(n.mask)) model.edit("Edit " + v, () -> n.mask = v);
            });
            content.add(m);
            content.add(text(what, FAINT));
            if (t.equals("slang.call")) {
                content.add(section("Arguments"));
                JSpinner args = new JSpinner(new SpinnerNumberModel(Math.max(0, n.index), 0, 12, 1));
                args.setMaximumSize(new Dimension(90, 28));
                args.setAlignmentX(0);
                args.addChangeListener(e -> {
                    int v = (Integer) args.getValue();
                    if (v != n.index) model.edit("Arguments " + v, () -> n.index = v);
                });
                content.add(args);
            }
        }
        content.add(section("Label"));
        JTextField label = field(n.label == null ? "" : n.label);
        label.addActionListener(ev -> model.rename(label.getText()));
        content.add(label);
        content.add(section("Note"));
        JTextArea note = new JTextArea(n.note == null ? "" : n.note, 3, 30);
        note.setLineWrap(true);
        note.setWrapStyleWord(true);
        note.setFont(UI);
        note.setBackground(CODE_BG);
        note.setForeground(TEXT);
        note.setCaretColor(TEXT);
        JScrollPane nsp = scroll(note);
        nsp.setAlignmentX(0);
        nsp.setMaximumSize(new Dimension(Integer.MAX_VALUE, 90));
        content.add(nsp);
        content.add(button("Save note", "Your comment on this node, shown under it", () -> {
            String v = note.getText().strip();
            model.edit("Note", () -> n.note = v.isEmpty() ? null : v);
        }));
    }

    private void moduleInfo(MosaicDocument.ENode n) {
        var m = model.packModules.get(n.mask);
        String stage = io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicPack.stageOf(n.mask);
        content.add(Box.createVerticalStrut(6));
        content.add(text(n.mask, TEXT));
        content.add(text(stage != null ? "Controlled by the Renderer stage " + MosaicRenderer.stage(stage).label() + " (its settings)"
            : "Not a node yet: nothing in the Renderer controls this module", stage != null ? OK : WARN));
        if (m == null) {
            content.add(text("Reopen the pack map to read this module.", MUTED));
            return;
        }
        content.add(text((m.entry() ? "Pass entry point (Bedray dispatches it)   " : "Library module   ") + m.lines() + " lines   " + m.imports().size()
            + " imports", MUTED));
        int usedBy = 0;
        for (var other : model.packModules.values()) if (other.imports().contains(m.path())) usedBy++;
        content.add(text("Used by " + usedBy + " module(s)", MUTED));
        if (!m.structs().isEmpty()) {
            content.add(section("Types"));
            content.add(text(String.join(", ", m.structs()), MUTED));
        }
        if (!m.functions().isEmpty()) {
            content.add(section("Functions"));
            content.add(text(String.join(", ", m.functions()), MUTED));
        }
        content.add(section("Imports"));
        content.add(text(m.imports().isEmpty() ? "none" : String.join("\n", m.imports()), FAINT));
        content.add(Box.createVerticalStrut(6));
        content.add(button("Show source", "The module's Slang code (read only here)", () -> {
            String src = model.packSources.getOrDefault(n.mask, "");
            JTextArea area = new JTextArea(src, 30, 100);
            area.setFont(MONO);
            area.setEditable(false);
            area.setBackground(CODE_BG);
            area.setForeground(TEXT);
            area.setCaretPosition(0);
            JOptionPane.showMessageDialog(content, scroll(area), n.mask, JOptionPane.PLAIN_MESSAGE);
        }));
    }

    private void set(MosaicDocument.ENode n, MosaicRenderer.Setting s, double v) {
        double[] cur = n.defaults.get(s.key());
        if (cur != null && cur.length > 0 && cur[0] == v) return;
        if (cur == null && v == s.def()) return;
        model.edit(s.label() + " = " + s.format(v), () -> n.defaults.put(s.key(), new double[] {v}));
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
            case "mosaic.vectorMath" -> modeCombo(n, "Operation", MosaicGraph.VECTOR_OPS, "add");
            case "mosaic.colorRamp" -> {
                modeCombo(n, "Interpolation", MosaicGraph.RAMP_MODES, "linear");
                rampEditor(n);
            }
            case "mat.image" -> imageEditor(n);
            case "mat.texture" -> textureInfo();
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
