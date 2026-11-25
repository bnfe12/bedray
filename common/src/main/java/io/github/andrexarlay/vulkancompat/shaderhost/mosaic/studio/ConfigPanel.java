package io.github.andrexarlay.vulkancompat.shaderhost.mosaic.studio;

import io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicGraph;
import io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicRenderer;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static io.github.andrexarlay.vulkancompat.shaderhost.mosaic.studio.StudioUi.*;

/**
 * Every public number of the renderer graph in one place: the config Bedray runs with, stage by stage, editable here
 * (the renderer graph follows), sent with priority (it wins over the game's menu), and exportable as bedray.toml lines.
 */
final class ConfigPanel extends Panels.Listening {
    final JPanel root = new JPanel(new BorderLayout(0, 6));
    final JPanel content = new JPanel();
    private String shown = "";

    ConfigPanel(StudioModel m) {
        super(m);
        root.setBackground(PANEL);
        root.setBorder(new EmptyBorder(8, 8, 8, 8));
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setBackground(PANEL);
        JScrollPane sp = scroll(content);
        sp.getVerticalScrollBar().setUnitIncrement(18);
        root.add(sp, BorderLayout.CENTER);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        buttons.setOpaque(false);
        buttons.add(button("Copy as bedray.toml", "The whole config as key = value lines", () -> {
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(toml()), null);
            model.say("Config copied: " + settings().size() + " values", OK);
        }));
        buttons.add(button("Save as...", "Writes the config to a .toml file", this::saveAs));
        buttons.add(button("Send to the game", "Applies the config now (it wins over the game's menu while Studio is live)", () -> {
            model.writeRenderer(settings());
            model.say("Config sent: " + settings().size() + " values", OK);
        }));
        root.add(buttons, BorderLayout.SOUTH);
        refresh();
    }

    /** The renderer graph the config comes from: the open one, else the saved one. */
    private List<io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicDocument.ENode> nodes() {
        if (model.rendererDoc() && model.doc.stage == null && model.doc.bedrayFunction == null && !model.packDoc()) return model.doc.nodes;
        try {
            Path f = model.rendererFile();
            if (Files.exists(f)) return io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicDocument.load(f).nodes;
        } catch (Exception ignored) {
            // nothing saved yet
        }
        return List.of();
    }

    private Map<String, String> settings() {
        try {
            var d = new io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicDocument();
            d.nodes.addAll(nodes());
            MosaicGraph g = MosaicGraph.analyze(d.toJson()).graph();
            return g != null && g.isRenderer() ? MosaicRenderer.settings(g) : Map.of();
        } catch (Exception e) {
            return Map.of();
        }
    }

    private String toml() {
        StringBuilder sb = new StringBuilder("# Bedray config written by Mosaic Studio (" + java.time.LocalDate.now() + ")\n");
        for (Map.Entry<String, String> e : settings().entrySet()) {
            String v = e.getValue();
            boolean plain = v.equals("true") || v.equals("false") || v.matches("-?\\d+(\\.\\d+)?");
            sb.append(e.getKey()).append(" = ").append(plain ? v : "\"" + v + "\"").append('\n');
        }
        return sb.toString();
    }

    private void saveAs() {
        JFileChooser fc = new JFileChooser();
        fc.setSelectedFile(new java.io.File("bedray-studio.toml"));
        if (fc.showSaveDialog(root) != JFileChooser.APPROVE_OPTION) return;
        try {
            Files.writeString(fc.getSelectedFile().toPath(), toml(), StandardCharsets.UTF_8);
            model.say("Config saved to " + fc.getSelectedFile(), OK);
        } catch (Exception e) {
            model.say("Could not save the config: " + e.getMessage(), BAD);
        }
    }

    @Override void refresh() {
        List<io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicDocument.ENode> nodes = nodes();
        StringBuilder key = new StringBuilder();
        for (var n : nodes) key.append(n.type).append(n.defaults.entrySet().stream().map(e -> e.getKey() + java.util.Arrays.toString(e.getValue())).toList());
        if (key.toString().equals(shown)) return;
        shown = key.toString();
        content.removeAll();
        Map<String, String> sent = settings();
        content.add(title("Config"));
        JLabel intro = text("Every public number of your renderer graph: " + sent.size() + " values. Studio sends them with priority: they win over"
            + " the game's menu while Live is on. Edit here or on the stages; both are the same numbers.", MUTED);
        content.add(intro);
        if (nodes.isEmpty()) {
            content.add(Box.createVerticalStrut(8));
            content.add(text("No renderer graph yet: open the Renderer workspace once.", WARN));
        }
        for (var n : nodes) {
            MosaicRenderer.Stage st = MosaicRenderer.stage(n.type);
            if (st == null || st.settings().isEmpty()) continue;
            content.add(Box.createVerticalStrut(10));
            JLabel head = new JLabel(st.label() + (model.stageEdited(n.type) ? "   (your version)" : ""));
            head.setFont(UI_B);
            head.setForeground(TEXT);
            head.setAlignmentX(0);
            content.add(head);
            for (MosaicRenderer.Setting s : st.settings()) {
                double[] cur = n.defaults.get(s.key());
                double v = cur != null && cur.length > 0 ? cur[0] : s.def();
                content.add(row(n, s, v, sent.get(s.key())));
            }
        }
        content.add(Box.createVerticalGlue());
        content.revalidate();
        content.repaint();
    }

    private JComponent row(io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicDocument.ENode n, MosaicRenderer.Setting s, double value, String sent) {
        JPanel row = new JPanel(new BorderLayout(10, 0));
        row.setOpaque(false);
        row.setAlignmentX(0);
        row.setBorder(new EmptyBorder(3, 8, 3, 0));
        JLabel name = label(s.label(), TEXT);
        name.setToolTipText("<html>" + s.key() + (s.help() == null ? "" : "<br>" + s.help()) + (sent == null ? "<br>(not sent: the stage is off)" : "") + "</html>");
        if (sent == null) name.setForeground(FAINT);
        row.add(name, BorderLayout.CENTER);
        JComponent control;
        switch (s.type()) {
            case BOOL -> control = new StudioWidgets.Toggle(value != 0, v -> set(n, s, v ? 1 : 0));
            case CHOICE -> {
                JComboBox<String> box = new JComboBox<>(s.options().stream().map(o -> o.replace('_', ' ')).toArray(String[]::new));
                box.setSelectedIndex((int) Math.max(0, Math.min(s.options().size() - 1, Math.round(value))));
                box.addActionListener(ev -> set(n, s, box.getSelectedIndex()));
                control = box;
            }
            default -> {
                JSpinner sp = new JSpinner(new SpinnerNumberModel((int) Math.round(Math.max(s.min(), Math.min(s.max(), value))), s.min(), s.max(), 1));
                sp.setPreferredSize(new Dimension(80, 24));
                sp.addChangeListener(ev -> set(n, s, ((Number) sp.getValue()).doubleValue()));
                control = sp;
            }
        }
        row.add(control, BorderLayout.EAST);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
        return row;
    }

    private void set(io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicDocument.ENode n, MosaicRenderer.Setting s, double v) {
        double[] cur = n.defaults.get(s.key());
        if (cur != null && cur.length > 0 && cur[0] == v) return;
        if (model.doc.nodes.contains(n)) {
            model.edit(s.label() + " = " + s.format(v), () -> n.defaults.put(s.key(), new double[] {v}));
            return;
        }
        // the saved renderer graph: change it there and send it
        try {
            Path f = model.rendererFile();
            var d = io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicDocument.load(f);
            for (var x : d.nodes) if (x.type.equals(n.type)) x.defaults.put(s.key(), new double[] {v});
            d.save();
            n.defaults.put(s.key(), new double[] {v});
            MosaicGraph g = MosaicGraph.analyze(d.toJson()).graph();
            if (g != null && g.isRenderer() && model.live) model.writeRenderer(MosaicRenderer.settings(g));
            model.fire();
        } catch (Exception e) {
            model.say("Could not change the renderer graph: " + e.getMessage(), BAD);
        }
    }

    /** A wrapping line of text. */
    static JLabel text(String s, Color c) {
        JLabel l = new JLabel("<html><body style='width:260px'>" + s.replace("&", "&amp;").replace("<", "&lt;") + "</body></html>");
        l.setFont(UI_S);
        l.setForeground(c);
        l.setAlignmentX(0);
        return l;
    }

    public JComponent component() { return root; }
}
