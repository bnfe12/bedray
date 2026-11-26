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

    /** Always as wide as the panel: rows shrink and wrap instead of running past the right edge. */
    private final JPanel content = new ScrollableColumn();

    private static final class ScrollableColumn extends JPanel implements Scrollable {
        @Override public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
        @Override public int getScrollableUnitIncrement(Rectangle r, int o, int d) { return 28; }
        @Override public int getScrollableBlockIncrement(Rectangle r, int o, int d) { return Math.max(28, r.height - 40); }
        @Override public boolean getScrollableTracksViewportWidth() { return true; }
        @Override public boolean getScrollableTracksViewportHeight() { return false; }
    }
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
        root.getVerticalScrollBar().setUnitIncrement(28);
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
            if (!model.rendererDoc() && !model.materialDoc()) graphSettings();
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
        if (fn) partLooks(n);
        if (MosaicRenderer.isStage(n.type)) {
            stageEditor(n);
            return;
        }
        if (StudioModel.USER_STAGES.containsKey(n.type)) {
            String hook = StudioModel.USER_STAGES.get(n.type);
            boolean edited = java.nio.file.Files.exists(StudioModel.functionFile(hook));
            content.add(Box.createVerticalStrut(6));
            content.add(text("Your own stage: name it with the label below. Its graph is the hook " + hook.substring(hook.lastIndexOf('/') + 1)
                + " of Bedray's pack (identity in Bedray's own build): " + switch (n.type) {
                    case "user.hdr" -> "scene-linear colour after exposure in, your colour out, before the tone curve.";
                    case "user.light" -> "every direct-light sample of the path tracer (sun, moon, block emitters): radiance in and out, with the direction to the light, world position and light kind (0 block, 1 sun, 2 moon).";
                    case "user.skyLight" -> "the sky radiance a path collects when it escapes: radiance in and out, with the direction and the bounce (0 = the camera sees the sky).";
                    case "user.medium" -> "every segment a path flies: inScatter (added) and transmittance (multiplied) in and out, with the camera-relative start, direction, length in blocks and inWater.";
                    case "user.emission" -> "the emission a path sees on glowing surfaces: emission in and out, with world position and bounce (0 = camera).";
                    case "user.bounce" -> "the path weight after each BSDF sample: weight in and out, with lobe (1 diffuse, 2 specular, 4 transmission), new direction, normal and depth.";
                    case "user.roulette" -> "Russian roulette: the survival probability in and out (kept in 0.001..1), with depth and throughput.";
                    case "user.pathOutput" -> "the radiance of one path before the firefly clamp and the denoiser: radiance in and out, with depth and surface hits.";
                    case "user.material" -> "every surface the path tracer reaches: albedo, roughness, metallic and emission in and out (inout), with its world position, normal and material id - it changes how light bounces, not just the picture.";
                    case "user.sky" -> "the direction and Bedray's sky colour in, the sky an eye or a reflection sees out (lighting keeps Bedray's dome).";
                    default -> "the display colour after the tone curve and Bedray's looks in, your colour out.";
                } + " It runs in Bedray's frame once you Apply to Bedray.", MUTED));
            content.add(text(edited ? "Its graph is edited: Apply to Bedray sends it." : "Untouched: it passes the colour through.", edited ? OK : FAINT));
            content.add(button("Open its graph", "Build the stage from nodes (double-click the node does the same)", () -> {
                if (model.openFunction != null) model.openFunction.accept(hook);
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

    /** Buffer (pass): its name, read by Read buffer / Blur nodes, and its size as a fraction of the screen. */
    private void bufferEditor(MosaicDocument.ENode n) {
        content.add(section("Buffer name"));
        JTextField f = field(n.mask == null ? "" : n.mask);
        f.setToolTipText("One word (letters, digits, _), e.g. bloom; Enter applies. Read buffer and Blur nodes pick it by this name.");
        f.addActionListener(ev -> {
            String name = f.getText().trim();
            if (!name.matches("[A-Za-z]\\w{0,23}")) {
                f.setForeground(BAD);
                return;
            }
            String old = n.mask;
            model.edit("Buffer " + n.id + " named " + name, () -> {
                n.mask = name;
                // readers of the old name follow the rename
                if (old != null) for (MosaicDocument.ENode r : model.doc.nodes)
                    if (MosaicGraph.readsBuffer(r.type) && old.equals(r.mask)) r.mask = name;
            });
        });
        content.add(f);
        content.add(section("Size"));
        java.util.List<String> sizes = java.util.List.of("full", "1/2", "1/4", "1/8");
        int[] scale = {1, 2, 4, 8};
        JComboBox<String> box = new JComboBox<>(sizes.toArray(new String[0]));
        int cur = Math.max(0, java.util.Arrays.stream(scale).boxed().toList().indexOf(Math.max(1, n.index)));
        box.setSelectedIndex(cur);
        box.addActionListener(ev -> {
            int s = scale[box.getSelectedIndex()];
            model.edit("Size of " + n.id + ": " + box.getSelectedItem(), () -> n.index = s);
        });
        box.setAlignmentX(0);
        box.setMaximumSize(new Dimension(Integer.MAX_VALUE, box.getPreferredSize().height));
        content.add(box);
        content.add(text("Smaller buffers are cheaper and blur wider: chain 1/2, 1/4, 1/8 for bloom.", FAINT));
    }

    /** Repeat: which function of this graph is the loop body, and how many times it runs. */
    private void repeatEditor(MosaicDocument.ENode n) {
        content.add(section("Loop body"));
        java.util.List<String> fns = new java.util.ArrayList<>(model.doc.functions.stream().map(f -> f.name).toList());
        if (fns.isEmpty()) {
            content.add(text("Add a function vec4 name(vec4 acc, float i) first (Functions), then pick it here.", WARN));
        } else {
            if (n.mask != null && !fns.contains(n.mask)) fns.add(0, n.mask);
            JComboBox<String> box = new JComboBox<>(fns.toArray(new String[0]));
            box.setSelectedItem(n.mask == null ? fns.get(0) : n.mask);
            box.addActionListener(ev -> model.edit("Loop body of " + n.id + ": " + box.getSelectedItem(), () -> n.mask = (String) box.getSelectedItem()));
            box.setAlignmentX(0);
            box.setMaximumSize(new Dimension(Integer.MAX_VALUE, box.getPreferredSize().height));
            content.add(box);
        }
        content.add(section("Times"));
        JSpinner s = new JSpinner(new SpinnerNumberModel(Math.clamp(n.index, 1, 64), 1, 64, 1));
        s.addChangeListener(ev -> model.edit("Repeat " + n.id + " x" + s.getValue(), () -> n.index = (Integer) s.getValue()));
        s.setAlignmentX(0);
        s.setMaximumSize(new Dimension(Integer.MAX_VALUE, s.getPreferredSize().height));
        content.add(s);
        content.add(text("Each run gets the last result as acc and its number as i (0, 1, 2...).", FAINT));
    }

    /**
     * Graph settings (MosaicSettings): the numbers every node builds on - sky model, sun path, ray lengths, march limits,
     * material colours - edited per graph and saved with it. A value equal to the default is not written.
     */
    private void graphSettings() {
        content.add(section("Graph settings"));
        content.add(text("Numbers every node builds on (sky, sun path, ray lengths, material colours). Saved with this graph; Enter applies,"
            + " the slider applies on release, Reset puts one value back. A dot marks a changed value.", FAINT));
        java.util.List<Object[]> rows = new java.util.ArrayList<>(); // {lower-case text, components...}
        JTextField filter = field("");
        filter.setToolTipText("Filter the graph settings by name, group or help");
        filter.setAlignmentX(0);
        filter.setMaximumSize(new Dimension(Integer.MAX_VALUE, filter.getPreferredSize().height));
        filter.getDocument().addDocumentListener(onText(() -> {
            String q = filter.getText().trim().toLowerCase(java.util.Locale.ROOT);
            for (Object[] r : rows) {
                boolean show = q.isEmpty() || ((String) r[0]).contains(q);
                for (int k = 1; k < r.length; k++) ((Component) r[k]).setVisible(show);
            }
            content.revalidate();
            content.repaint();
        }));
        content.add(filter);
        content.add(Box.createVerticalStrut(4));
        presetsRow();
        String group = null;
        for (io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicSettings.Setting s : io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicSettings.ALL) {
            if (!s.group().equals(group)) {
                group = s.group();
                Component gap = Box.createVerticalStrut(8);
                content.add(gap);
                JLabel g = text(group, TEXT);
                g.setFont(UI_B);
                content.add(g);
                rows.add(new Object[] {group.toLowerCase(java.util.Locale.ROOT), gap, g});
            }
            double[] cur = io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicSettings.value(model.doc.settings, s);
            if (s.size() == 45) {
                var mats = io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicWorld.Material.values();
                for (int k = 0; k < 15; k++) {
                    final int at = k;
                    double[] def = {s.def()[k * 3], s.def()[k * 3 + 1], s.def()[k * 3 + 2]};
                    rows.add(settingRow(s, mats[k].label, new double[] {cur[k * 3], cur[k * 3 + 1], cur[k * 3 + 2]}, def, v -> {
                        double[] all = io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicSettings.value(model.doc.settings, s).clone();
                        System.arraycopy(v, 0, all, at * 3, 3);
                        return all;
                    }, 3));
                }
            } else rows.add(settingRow(s, s.label(), cur, s.def(), v -> v, s.size()));
        }
        content.add(Box.createVerticalStrut(6));
        content.add(button("Reset graph settings", "Every graph setting back to its default", () -> {
            model.edit("Reset graph settings", () -> model.doc.settings.clear());
            rebuild();
        }));
    }

    /** A Minecraft part node: its ready looks as buttons, and back to unchanged. */
    private void partLooks(MosaicDocument.ENode n) {
        var looks = io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicEffects.looksOf(MosaicFunction.nameOf(n.type));
        if (looks.isEmpty()) return;
        content.add(section("Look"));
        JPanel row = new JPanel(new GridLayout(0, 2, 4, 4));
        row.setOpaque(false);
        row.setAlignmentX(0);
        for (var l : looks)
            row.add(button(l.label(), "Sets " + String.join(", ", l.values().keySet()), () -> {
                model.edit(n.id + ": " + l.label(), () -> {
                    for (String k : java.util.List.of("tint", "brightness", "saturation", "contrast")) n.defaults.remove(k);
                    l.values().forEach((k, v) -> n.defaults.put(k, v.clone()));
                });
                rebuild();
            }));
        row.add(button("Unchanged", "Every look value back to its default", () -> {
            model.edit(n.id + ": unchanged", () -> {
                for (String k : java.util.List.of("tint", "brightness", "saturation", "contrast", "contrastPivot", "strength", "lookAmount")) n.defaults.remove(k);
            });
            rebuild();
        }));
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
        content.add(row);
    }

    /** Presets (built in and your own, ~/.bedray/studio/setting-presets), copy / paste as JSON. */
    private void presetsRow() {
        java.util.List<io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicSettings.Preset> all = new java.util.ArrayList<>(io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicSettings.PRESETS);
        all.addAll(userPresets());
        JComboBox<String> box = new JComboBox<>();
        box.addItem("Apply a preset...");
        for (var p : all) box.addItem(p.name() + (p.help().isEmpty() ? "" : "  -  " + p.help()));
        box.setToolTipText("Sets the values of the preset; the others stay as they are (Undo takes it back)");
        box.addActionListener(e -> {
            int i = box.getSelectedIndex() - 1;
            if (i < 0) return;
            var p = all.get(i);
            model.edit("Preset " + p.name(), () -> p.values().forEach((k, v) -> {
                var s = io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicSettings.of(k);
                if (s == null || v.length != s.size()) return;
                if (java.util.Arrays.equals(v, s.def())) model.doc.settings.remove(k);
                else model.doc.settings.put(k, v.clone());
            }));
            rebuild();
        });
        box.setAlignmentX(0);
        box.setMaximumSize(new Dimension(Integer.MAX_VALUE, box.getPreferredSize().height));
        content.add(box);
        JPanel row = new JPanel(new GridLayout(0, 3, 4, 4));
        row.setOpaque(false);
        row.setAlignmentX(0);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 80));
        row.add(button("Save preset...", "Save the changed values of this graph as a preset of your own", () -> {
            if (model.doc.settings.isEmpty()) {
                model.say("No changed graph settings to save", MUTED);
                return;
            }
            String name = JOptionPane.showInputDialog(content, "Preset name", "My settings");
            if (name == null || name.isBlank()) return;
            try {
                java.nio.file.Path dir = presetDir();
                java.nio.file.Files.createDirectories(dir);
                com.google.gson.JsonObject o = new com.google.gson.JsonObject();
                o.addProperty("name", name.trim());
                o.add("settings", io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicSettings.toJson(model.doc.settings));
                java.nio.file.Files.writeString(dir.resolve(name.trim().replaceAll("[^A-Za-z0-9 _-]", "_") + ".json"),
                    new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(o));
                model.say("Saved preset " + name.trim() + " in " + dir, OK);
                rebuild();
            } catch (Exception ex) {
                model.say("Could not save the preset: " + ex.getMessage(), BAD);
            }
        }));
        row.add(button("Copy", "Copy the changed graph settings as JSON", () -> {
            String json = io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicSettings.toJson(model.doc.settings).toString();
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new java.awt.datatransfer.StringSelection(json), null);
            model.say("Copied " + model.doc.settings.size() + " graph setting(s)", MUTED);
        }));
        row.add(button("Paste", "Set graph settings from JSON on the clipboard ({key: [numbers]})", () -> {
            try {
                String text = (String) Toolkit.getDefaultToolkit().getSystemClipboard().getData(java.awt.datatransfer.DataFlavor.stringFlavor);
                java.util.List<String> bad = new java.util.ArrayList<>();
                var values = io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicSettings.read(com.google.gson.JsonParser.parseString(text), bad::add);
                if (values.isEmpty()) {
                    model.say("No graph settings on the clipboard" + (bad.isEmpty() ? "" : ": " + bad.get(0)), WARN);
                    return;
                }
                model.edit("Paste " + values.size() + " graph setting(s)", () -> model.doc.settings.putAll(values));
                if (!bad.isEmpty()) model.say("Pasted " + values.size() + "; skipped: " + String.join("; ", bad), WARN);
                rebuild();
            } catch (Exception ex) {
                model.say("No graph settings on the clipboard", WARN);
            }
        }));
        content.add(Box.createVerticalStrut(4));
        content.add(row);
    }

    static java.nio.file.Path presetDir() { return StudioSettings.file().resolveSibling("studio").resolve("setting-presets"); }

    static java.util.List<io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicSettings.Preset> userPresets() {
        java.util.List<io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicSettings.Preset> out = new java.util.ArrayList<>();
        try (var files = java.nio.file.Files.list(presetDir())) {
            for (java.nio.file.Path f : files.sorted().toList()) {
                if (!f.toString().endsWith(".json")) continue;
                try {
                    var o = com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(f)).getAsJsonObject();
                    var values = io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicSettings.read(o.get("settings"), m -> { });
                    String name = o.has("name") ? o.get("name").getAsString() : f.getFileName().toString().replaceAll("[.]json$", "");
                    if (!values.isEmpty()) out.add(new io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicSettings.Preset(name, "yours", values));
                } catch (Exception ignored) {
                    // a broken preset file is skipped
                }
            }
        } catch (Exception ignored) {
            // no presets yet
        }
        return out;
    }

    /** Builds the panel again on the next turn (after an edit the shown fields do not follow by themselves). */
    private void rebuild() {
        SwingUtilities.invokeLater(() -> {
            shownKey = "";
            refresh();
        });
    }

    /** One setting: label (dot when changed), value field, reset arrow and, for a single number with a range, a slider. */
    private Object[] settingRow(io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicSettings.Setting s, String label, double[] shown, double[] def,
                                java.util.function.UnaryOperator<double[]> into, int size) {
        boolean changed = !java.util.Arrays.equals(shown, def);
        JLabel l = text((changed ? "• " : "") + label, changed ? TEXT : MUTED);
        l.setToolTipText(s.help() + "  Default: " + numbers(def));
        JTextField f = field(numbers(shown));
        f.setToolTipText(s.help() + (size == 3 ? "  (three numbers: r, g, b or x, y, z)" : "") + "  Default: " + numbers(def));
        java.util.function.Consumer<double[]> commit = v -> {
            double[] all = into.apply(v);
            model.edit(s.label() + ": " + numbers(v), () -> {
                if (java.util.Arrays.equals(all, s.def())) model.doc.settings.remove(s.key());
                else model.doc.settings.put(s.key(), all);
            });
        };
        f.addActionListener(ev -> {
            double[] v = parse(f.getText());
            if (v == null || v.length != size) {
                f.setForeground(BAD);
                return;
            }
            f.setForeground(TEXT);
            commit.accept(v);
        });
        JButton reset = new JButton("reset");
        reset.setFont(UI_S);
        reset.setFocusable(false);
        reset.setMargin(new Insets(0, 6, 0, 6));
        reset.setToolTipText("Back to the default: " + numbers(def));
        reset.setEnabled(changed);
        reset.addActionListener(ev -> {
            commit.accept(def.clone());
            rebuild();
        });
        JPanel line = new JPanel(new BorderLayout(4, 0));
        line.setOpaque(false);
        line.add(f, BorderLayout.CENTER);
        line.add(reset, BorderLayout.EAST);
        line.setAlignmentX(0);
        line.setMaximumSize(new Dimension(Integer.MAX_VALUE, f.getPreferredSize().height));
        content.add(l);
        content.add(line);
        String words = (label + " " + s.key() + " " + s.group() + " " + s.help()).toLowerCase(java.util.Locale.ROOT);
        double[] range = io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicSettings.range(s.key());
        if (size == 1 && range != null) {
            boolean log = range[0] > 0 && range[1] / range[0] >= 100;
            java.util.function.DoubleUnaryOperator toV = t -> log ? range[0] * Math.pow(range[1] / range[0], t) : range[0] + (range[1] - range[0]) * t;
            java.util.function.DoubleUnaryOperator toT = v -> log ? Math.log(Math.max(range[0], v) / range[0]) / Math.log(range[1] / range[0])
                : (v - range[0]) / (range[1] - range[0]);
            JSlider sl = new JSlider(0, 1000, (int) Math.round(Math.clamp(toT.applyAsDouble(shown[0]), 0, 1) * 1000));
            sl.setOpaque(false);
            sl.setFocusable(false);
            sl.setToolTipText("Range " + numbers(range) + (log ? " (logarithmic)" : ""));
            sl.addChangeListener(ev -> {
                double v = toV.applyAsDouble(sl.getValue() / 1000.0);
                v = s.integer() ? Math.round(v) : Double.parseDouble(String.format(java.util.Locale.ROOT, "%.4g", v));
                f.setText(numbers(new double[] {v}));
                if (!sl.getValueIsAdjusting()) commit.accept(new double[] {v});
            });
            sl.setAlignmentX(0);
            sl.setMaximumSize(new Dimension(Integer.MAX_VALUE, sl.getPreferredSize().height));
            content.add(sl);
            return new Object[] {words, l, line, sl};
        }
        return new Object[] {words, l, line};
    }

    /** Read buffer / Blur buffer / Kawase blur: which Buffer of this graph they read. */
    private void bufferChoice(MosaicDocument.ENode n) {
        content.add(section("Buffer"));
        java.util.List<String> names = new java.util.ArrayList<>();
        for (MosaicDocument.ENode w : model.doc.nodes)
            if (w.type.equals("mosaic.pass") && w.mask != null && !names.contains(w.mask)) names.add(w.mask);
        String sceneChoice = "(scene colour)";
        boolean taa = n.type.equals("mosaic.taa");
        if (taa) names.add(0, sceneChoice);
        if (names.isEmpty()) {
            content.add(text("No Buffer node in this graph yet: add Buffer (pass) and name it.", WARN));
            return;
        }
        if (n.mask != null && !names.contains(n.mask)) names.add(0, n.mask);
        JComboBox<String> box = new JComboBox<>(names.toArray(new String[0]));
        box.setSelectedItem(n.mask == null ? names.get(0) : n.mask);
        box.addActionListener(ev -> model.edit("Buffer of " + n.id + ": " + box.getSelectedItem(), () -> {
            String v = (String) box.getSelectedItem();
            n.mask = taa && sceneChoice.equals(v) ? null : v;
        }));
        box.setAlignmentX(0);
        box.setMaximumSize(new Dimension(Integer.MAX_VALUE, box.getPreferredSize().height));
        content.add(box);
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
            case "mosaic.pass" -> bufferEditor(n);
            case "mosaic.bufferIn", "mosaic.blur", "mosaic.kawase", "mosaic.taa", "mosaic.bufferPrev", "mosaic.accumulate", "mosaic.atrous", "mosaic.average", "mosaic.ssr", "mosaic.upsample" -> bufferChoice(n);
            case "mosaic.repeat" -> repeatEditor(n);
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
