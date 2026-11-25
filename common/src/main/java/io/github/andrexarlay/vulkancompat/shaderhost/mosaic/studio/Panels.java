package io.github.andrexarlay.vulkancompat.shaderhost.mosaic.studio;

import io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicDocument;
import io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicGraph;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Locale;

import static io.github.andrexarlay.vulkancompat.shaderhost.mosaic.studio.StudioUi.*;

/** The studio's editors. Any of them can live in any area of any window (see {@link Dock}). */
final class Panels {

    private Panels() {}

    /** One editor instance in an area. */
    interface Panel {
        JComponent component();

        default void dispose() { }

        /** Gives the keyboard focus to the panel's main control (F6 cycling). */
        default void focus() { component().requestFocusInWindow(); }
    }

    enum Type {
        GRAPH("Node graph", "Main", "Build the shader from nodes"),
        RESULT("Result", "Main", "The real render from the game, before / after / compare"),
        HISTORY("History", "Main", "Every change by name; click one to go back"),
        PALETTE("Nodes", "Data", "All nodes by category; drag onto the graph"),
        INSPECTOR("Properties", "Data", "Values, modes and code of the selection"),
        OUTLINE("Node list", "Data", "Every node of the graph as a list"),
        PROBLEMS("Problems", "Diagnostics", "Errors and warnings with quick fixes"),
        CODE("Generated code", "Diagnostics", "The shader this graph compiles to"),
        KEYS("Keys", "Diagnostics", "All shortcuts and mouse gestures"),
        GAME("Game link", "Game", "Pack file, game status, Live save, run the game"),
        ADDONS("Add-ons", "Game", "Node libraries and the game's add-ons"),
        LIBRARY("Library", "Data", "Textures from Minecraft, resource packs and the project; drag or double-click"),
        MATERIAL("Material", "Main", "The material on a lit block and each baked map"),
        BROWSER("Shader browser", "Data", "Shader packs of the game: Mosaic graphs and Iris packs, import / export"),
        PREVIEW("Preview", "Main", "The shader run by the studio on the GPU over a game frame, a test scene or a picture"),
        CONFIG("Config", "Game", "Every public number of the renderer graph in one place: edit, copy, save, send with priority"),
        GUIDE("Guide", "Diagnostics", "How the editor works, from the first node to a Bedray pass rewritten in nodes");

        final String label, group, help;

        Type(String label, String group, String help) {
            this.label = label;
            this.group = group;
            this.help = help;
        }

        @Override public String toString() { return label; }

        Panel create(StudioModel m) {
            return switch (this) {
                case GRAPH -> {
                    GraphPanel g = new GraphPanel(m);
                    yield new Panel() {
                        public JComponent component() { return g; }
                        public void dispose() { g.dispose(); }
                    };
                }
                case PALETTE -> new PalettePanel(m);
                case INSPECTOR -> new InspectorPanel(m);
                case RESULT -> new ResultPanel(m);
                case HISTORY -> new HistoryPanel(m);
                case PROBLEMS -> new ProblemsPanel(m);
                case CODE -> new CodePanel(m);
                case OUTLINE -> new OutlinePanel(m);
                case GAME -> new GamePanel(m);
                case ADDONS -> new AddonsPanel(m);
                case KEYS -> new KeysPanel(m);
                case LIBRARY -> new MaterialPanels.LibraryPanel(m);
                case MATERIAL -> new MaterialPanels.MaterialPanel(m);
                case BROWSER -> new ShaderBrowser(m);
                case PREVIEW -> new PreviewPanel(m);
                case CONFIG -> new ConfigPanel(m);
                case GUIDE -> new GuidePanel(m);
            };
        }
    }

    /** Base: listens to the model while alive. */
    abstract static class Listening implements Panel { // package: ConfigPanel extends it
        final StudioModel model;
        private final Runnable listener = this::refresh;

        Listening(StudioModel model) {
            this.model = model;
            model.listen(listener);
        }

        abstract void refresh();

        @Override public void dispose() { model.unlisten(listener); }
    }

    // ================================================================== palette

    static final class PalettePanel extends Listening {
        final JPanel root = new JPanel(new BorderLayout(0, 6));
        final JTextField search = field("");
        final NodeTree tree;
        int functionsSeen = -1, hiddenSeen = -1;

        PalettePanel(StudioModel m) {
            super(m);
            tree = new NodeTree(m::items, true);
            root.setBackground(PANEL);
            root.setBorder(new EmptyBorder(8, 8, 8, 8));
            search.setToolTipText("Filter nodes. Up/Down move, Enter adds at the view center, Left/Right fold groups.");
            search.getDocument().addDocumentListener(onText(() -> tree.setFilter(search.getText())));
            search.addActionListener(e -> add(tree.current()));
            search.addKeyListener(new KeyAdapter() {
                @Override public void keyPressed(KeyEvent e) {
                    if (e.getKeyCode() == KeyEvent.VK_DOWN) tree.moveSelection(1);
                    if (e.getKeyCode() == KeyEvent.VK_UP) tree.moveSelection(-1);
                }
            });
            tree.onPick(this::add);
            JLabel hint = label("Drag a node onto the graph", FAINT);
            hint.setFont(UI_S);
            JPanel top = new JPanel(new BorderLayout(0, 4));
            top.setOpaque(false);
            top.add(search, BorderLayout.NORTH);
            top.add(hint, BorderLayout.SOUTH);
            root.add(top, BorderLayout.NORTH);
            root.add(scroll(tree), BorderLayout.CENTER);
        }

        void add(StudioModel.Item it) {
            if (it == null) return;
            GraphPanel g = model.activeGraph;
            MosaicDocument.ENode n = model.add(it, null, g == null ? new Point(0, 0) : g.viewCenter());
            if (g != null) g.reveal(n);
        }

        @Override void refresh() {
            int hidden = settings.hiddenLibraries.hashCode();
            int fs = model.doc.functions.size() * 2 + model.workspace.ordinal() * 7 + (model.functionDoc() ? 1000 : 0);
            if (fs != functionsSeen || hidden != hiddenSeen) {
                functionsSeen = fs;
                hiddenSeen = hidden;
                tree.rebuild();
            }
        }

        public JComponent component() { return root; }

        public void focus() { search.requestFocusInWindow(); }
    }

    // ================================================================== problems

    static final class ProblemsPanel extends Listening {
        final DefaultListModel<MosaicGraph.Problem> lm = new DefaultListModel<>();
        final JList<MosaicGraph.Problem> list = new JList<>(lm);
        final JPanel root = new JPanel(new BorderLayout());
        final JCheckBox hints = new JCheckBox("Show hints", false);

        ProblemsPanel(StudioModel m) {
            super(m);
            list.setBackground(PANEL);
            list.setCellRenderer(new DefaultListCellRenderer() {
                @Override public Component getListCellRendererComponent(JList<?> l, Object v, int i, boolean s, boolean f) {
                    JLabel c = (JLabel) super.getListCellRendererComponent(l, v, i, s, false);
                    MosaicGraph.Problem p = (MosaicGraph.Problem) v;
                    String sev = switch (p.severity()) { case ERROR -> "ERROR"; case WARNING -> "WARNING"; case HINT -> "hint"; };
                    c.setText("  " + sev + "    " + p.message());
                    c.setToolTipText(p.message());
                    c.setForeground(p.severity() == MosaicGraph.Severity.ERROR ? BAD : p.severity() == MosaicGraph.Severity.WARNING ? WARN : MUTED);
                    c.setBackground(s ? SELECT.darker().darker() : PANEL);
                    c.setBorder(new EmptyBorder(3, 4, 3, 4));
                    return c;
                }
            });
            list.setToolTipText("Click: show the node. Double-click or Enter: quick fix.");
            list.addMouseListener(new MouseAdapter() {
                @Override public void mouseClicked(MouseEvent e) {
                    show(list.getSelectedValue());
                    if (e.getClickCount() == 2) fix(list.getSelectedValue());
                }
            });
            list.addKeyListener(new KeyAdapter() {
                @Override public void keyPressed(KeyEvent e) {
                    if (e.getKeyCode() == KeyEvent.VK_ENTER) fix(list.getSelectedValue());
                }
            });
            list.addListSelectionListener(e -> {
                if (!e.getValueIsAdjusting() && list.isFocusOwner()) show(list.getSelectedValue());
            });
            hints.setOpaque(false);
            hints.setForeground(MUTED);
            hints.setFont(UI_S);
            hints.setFocusable(false);
            hints.addActionListener(e -> refresh());
            JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
            bar.setBackground(PANEL2);
            bar.add(hints);
            root.add(bar, BorderLayout.NORTH);
            root.add(scroll(list), BorderLayout.CENTER);
            refresh();
        }

        void show(MosaicGraph.Problem p) {
            if (p == null || p.node() == null) return;
            MosaicDocument.ENode n = model.doc.byId(p.node());
            if (n == null) return;
            model.select(n);
            if (model.activeGraph != null) model.activeGraph.center(n);
        }

        void fix(MosaicGraph.Problem p) {
            if (p == null) return;
            MosaicDocument.ENode n = p.node() == null ? null : model.doc.byId(p.node());
            GraphPanel g = model.activeGraph;
            switch (p.fix()) {
                case DELETE_NODE -> {
                    if (n != null) {
                        model.select(n);
                        model.deleteSelection();
                    }
                }
                case ADD_OUTPUT -> {
                    MosaicDocument.ENode last = model.doc.nodes.isEmpty() ? null : model.doc.nodes.get(model.doc.nodes.size() - 1);
                    model.edit("Add output", () -> {
                        MosaicDocument.ENode out = model.doc.add("mosaic.output", last == null ? 0 : last.x + 220, last == null ? 0 : last.y);
                        if (last != null) out.inputs.put("color", last.id);
                    });
                }
                case CONNECT -> {
                    if (n != null && g != null) {
                        model.select(n);
                        g.connectInto();
                    }
                }
                default -> show(p);
            }
        }

        @Override void refresh() {
            List<MosaicGraph.Problem> ps = model.problems().stream()
                .filter(p -> hints.isSelected() || p.severity() != MosaicGraph.Severity.HINT).toList();
            if (ps.isEmpty() && !model.problems().isEmpty() && !hints.isSelected()) {
                long n = model.problems().size();
                hints.setText("Show hints (" + n + ")");
            } else if (!hints.isSelected()) hints.setText("Show hints");
            if (ps.size() == lm.size()) {
                boolean same = true;
                for (int i = 0; i < ps.size() && same; i++) same = ps.get(i).equals(lm.get(i));
                if (same) return;
            }
            lm.clear();
            ps.forEach(lm::addElement);
        }

        public JComponent component() { return root; }

        public void focus() {
            list.requestFocusInWindow();
            if (list.getSelectedIndex() < 0 && !lm.isEmpty()) list.setSelectedIndex(0);
        }
    }

    // ================================================================== generated code

    static final class CodePanel extends Listening {
        final JTextArea text = new JTextArea();
        final JScrollPane root;
        String shown = "";

        CodePanel(StudioModel m) {
            super(m);
            text.setEditable(false);
            text.setFont(MONO);
            text.setBackground(CODE_BG);
            text.setForeground(TEXT);
            text.setBorder(new EmptyBorder(6, 8, 6, 8));
            root = scroll(text);
            refresh();
        }

        @Override void refresh() {
            String src = model.generated == null ? "// no shader: " + model.analysis.firstError() : model.generated.source();
            if (src.equals(shown)) return;
            shown = src;
            String[] lines = src.split("\n", -1);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < lines.length; i++) sb.append(String.format("%4d  ", i + 1)).append(lines[i]).append('\n');
            int caret = text.getCaretPosition();
            text.setText(sb.toString());
            text.setCaretPosition(Math.min(caret, text.getDocument().getLength()));
        }

        public JComponent component() { return root; }

        public void focus() { text.requestFocusInWindow(); }
    }

    // ================================================================== outline

    static final class OutlinePanel extends Listening {
        final DefaultListModel<MosaicDocument.ENode> lm = new DefaultListModel<>();
        final JList<MosaicDocument.ENode> list = new JList<>(lm);
        final JScrollPane root;
        boolean syncing;

        OutlinePanel(StudioModel m) {
            super(m);
            list.setBackground(PANEL);
            list.setCellRenderer(new DefaultListCellRenderer() {
                @Override public Component getListCellRendererComponent(JList<?> l, Object v, int i, boolean s, boolean f) {
                    JLabel c = (JLabel) super.getListCellRendererComponent(l, v, i, s, false);
                    MosaicDocument.ENode n = (MosaicDocument.ENode) v;
                    boolean bad = !model.analysis.of(n.id).isEmpty();
                    c.setText("  " + n.id + (n.label != null ? "  \"" + n.label + "\"" : "") + "    " + n.type.replace("mosaic.", ""));
                    c.setForeground(bad ? WARN : TEXT);
                    c.setBackground(s ? SELECT.darker().darker() : PANEL);
                    return c;
                }
            });
            list.addListSelectionListener(e -> {
                if (syncing || e.getValueIsAdjusting()) return;
                List<MosaicDocument.ENode> sel = list.getSelectedValuesList();
                model.selection.clear();
                model.selection.addAll(sel);
                model.primary = sel.isEmpty() ? null : sel.get(0);
                model.selectionChanged();
                if (model.primary != null && model.activeGraph != null) model.activeGraph.reveal(model.primary);
            });
            root = scroll(list);
            refresh();
        }

        @Override void refresh() {
            syncing = true;
            if (lm.size() != model.doc.nodes.size() || !java.util.stream.IntStream.range(0, lm.size()).allMatch(i -> lm.get(i) == model.doc.nodes.get(i))) {
                lm.clear();
                model.doc.nodes.forEach(lm::addElement);
            }
            int[] idx = model.doc.nodes.stream().filter(model.selection::contains).mapToInt(model.doc.nodes::indexOf).toArray();
            list.setSelectedIndices(idx);
            list.repaint();
            syncing = false;
        }

        public JComponent component() { return root; }

        public void focus() { list.requestFocusInWindow(); }
    }

    // ================================================================== history

    static final class HistoryPanel extends Listening {
        final DefaultListModel<Integer> lm = new DefaultListModel<>();
        final JList<Integer> list = new JList<>(lm);
        final JScrollPane root;
        boolean syncing;

        HistoryPanel(StudioModel m) {
            super(m);
            list.setBackground(PANEL);
            list.setCellRenderer(new DefaultListCellRenderer() {
                @Override public Component getListCellRendererComponent(JList<?> l, Object v, int i, boolean s, boolean f) {
                    JLabel c = (JLabel) super.getListCellRendererComponent(l, v, i, s, false);
                    int step = (Integer) v;
                    List<String> done = model.doc.undoSteps(), redo = model.doc.redoSteps();
                    int current = done.size();
                    int r = step - current - 1;
                    String name = step == 0 ? "Start" : step <= current ? (step - 1 < done.size() ? done.get(step - 1) : "")
                        : r < redo.size() ? redo.get(r) : "";
                    String num = "<span style='color:" + hex(FAINT) + "'>" + step + "</span>&nbsp;&nbsp;";
                    c.setText("<html>" + num + esc(name) + "</html>");
                    c.setForeground(step > current ? FAINT : step == current ? TEXT : MUTED);
                    c.setBackground(s ? StudioWidgets.mix(PANEL, SELECT, 0.35) : PANEL);
                    StudioModel.Shot shot = model.shotFor(step);
                    Icon action = StudioWidgets.historyIcon(name);
                    c.setIcon(StudioWidgets.pair(action, shot == null ? null : new ImageIcon(thumb(shot.after(), 64, 36)), 8));
                    c.setIconTextGap(9);
                    // accent bar on the current step, dimmed icons for steps that can only be redone
                    c.setBorder(BorderFactory.createCompoundBorder(
                        BorderFactory.createMatteBorder(0, 3, 0, 0, step == current ? SELECT : (s ? StudioWidgets.mix(PANEL, SELECT, 0.35) : PANEL)),
                        new EmptyBorder(4, 8, 4, 6)));
                    c.setEnabled(step <= current);
                    if (step > current) c.setDisabledIcon(StudioWidgets.pair(StudioWidgets.glyph("dot", FAINT), null, 0));
                    return c;
                }
            });
            list.setToolTipText("Click (or Enter) a step to go back or forward to it; greyed steps can be redone.");
            list.addListSelectionListener(e -> {
                if (syncing || e.getValueIsAdjusting() || list.getSelectedValue() == null) return;
                if (list.getSelectedValue() != model.doc.undoSteps().size()) model.jumpTo(list.getSelectedValue());
            });
            root = scroll(list);
            refresh();
        }

        @Override void refresh() {
            syncing = true;
            int total = model.doc.undoSteps().size() + model.doc.redoSteps().size() + 1;
            if (lm.size() != total) {
                lm.clear();
                for (int i = 0; i < total; i++) lm.addElement(i);
            }
            list.setSelectedIndex(model.doc.undoSteps().size());
            list.ensureIndexIsVisible(model.doc.undoSteps().size());
            list.repaint();
            syncing = false;
        }

        public JComponent component() { return root; }

        public void focus() { list.requestFocusInWindow(); }
    }

    static Image thumb(BufferedImage img, int w, int h) {
        if (img == null) return new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        return img.getScaledInstance(w, h, Image.SCALE_SMOOTH);
    }

    // ================================================================== result

    /** The game's real render of this graph: after / before / split / side-by-side / against any earlier result. */
    static final class ResultPanel extends Listening {
        enum Mode { AFTER("With shader"), BEFORE("Minecraft (no shader)"), SPLIT("Split slider"), SIDE("Side by side"), HISTORY("Compare with earlier");
            final String label;
            Mode(String l) { label = l; }
            @Override public String toString() { return label; }
        }

        final JPanel root = new JPanel(new BorderLayout());
        final StudioWidgets.Segmented<Mode> mode = new StudioWidgets.Segmented<Mode>()
            .add(Mode.AFTER, "Shader", StudioWidgets.glyph("shader", new Color(0x5AA0E0)), "The game's frame with this graph (1)")
            .add(Mode.BEFORE, "Vanilla", StudioWidgets.glyph("vanilla", new Color(0x7FB069)), "The same frame without the shader (2)")
            .add(Mode.SPLIT, "Split", StudioWidgets.glyph("split", null), "Drag the line to compare before / after (3, Left / Right)")
            .add(Mode.SIDE, "Side", StudioWidgets.glyph("side", null), "Before and after next to each other (4)")
            .add(Mode.HISTORY, "Earlier", StudioWidgets.glyph("history", null), "Compare with a result from an earlier step (5)");
        final DefaultListModel<StudioModel.Shot> shots = new DefaultListModel<>();
        final JList<StudioModel.Shot> strip = new JList<>(shots);
        double split = 0.5;
        final JComponent view;

        ResultPanel(StudioModel m) {
            super(m);
            root.setBackground(PANEL);
            JPanel bar = new JPanel(new BorderLayout());
            bar.setBackground(PANEL2);
            bar.setBorder(new EmptyBorder(5, 6, 5, 6));
            mode.onChange(v -> root.repaint());
            bar.add(mode, BorderLayout.CENTER);
            JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
            actions.setOpaque(false);
            JButton rec = button("Record", "Save every game frame while it runs; Stop writes a GIF (and an MP4 when ffmpeg is installed)", () -> { });
            rec.addActionListener(e -> {
                if (model.recording()) model.stopRecording();
                else {
                    StudioModel.RecordWhat what = switch (mode.getSelectedItem()) {
                        case BEFORE -> StudioModel.RecordWhat.BEFORE;
                        case SPLIT, SIDE -> StudioModel.RecordWhat.SIDE;
                        default -> StudioModel.RecordWhat.AFTER;
                    };
                    model.startRecording(what);
                }
                rec.setText(model.recording() ? "Stop" : "Record");
                rec.setForeground(model.recording() ? BAD : TEXT);
            });
            actions.add(rec);
            actions.add(button("Save frame", "The frame with the shader as a PNG in the project's .studio/screenshots", model::saveFrame));
            bar.add(actions, BorderLayout.EAST);
            root.add(bar, BorderLayout.NORTH);
            view = new JComponent() {
                @Override protected void paintComponent(Graphics g0) { paintResult((Graphics2D) g0, getWidth(), getHeight()); }
            };
            view.setFocusable(true);
            view.addMouseMotionListener(new MouseMotionAdapter() {
                @Override public void mouseDragged(MouseEvent e) {
                    split = Math.clamp(e.getX() / (double) Math.max(1, view.getWidth()), 0, 1);
                    view.repaint();
                }
            });
            view.addMouseListener(new MouseAdapter() {
                @Override public void mousePressed(MouseEvent e) {
                    view.requestFocusInWindow();
                    split = Math.clamp(e.getX() / (double) Math.max(1, view.getWidth()), 0, 1);
                    view.repaint();
                }
            });
            view.addKeyListener(new KeyAdapter() {
                @Override public void keyPressed(KeyEvent e) {
                    switch (e.getKeyCode()) {
                        case KeyEvent.VK_LEFT -> split = Math.max(0, split - 0.05);
                        case KeyEvent.VK_RIGHT -> split = Math.min(1, split + 0.05);
                        case KeyEvent.VK_1, KeyEvent.VK_2, KeyEvent.VK_3, KeyEvent.VK_4, KeyEvent.VK_5 -> mode.setSelectedIndex(e.getKeyCode() - KeyEvent.VK_1);
                        default -> { return; }
                    }
                    e.consume();
                    view.repaint();
                }
            });
            root.add(view, BorderLayout.CENTER);
            strip.setLayoutOrientation(JList.HORIZONTAL_WRAP);
            strip.setVisibleRowCount(1);
            strip.setBackground(PANEL2);
            strip.setCellRenderer(new DefaultListCellRenderer() {
                @Override public Component getListCellRendererComponent(JList<?> l, Object v, int i, boolean s, boolean f) {
                    JLabel c = (JLabel) super.getListCellRendererComponent(l, "", i, s, false);
                    StudioModel.Shot shot = (StudioModel.Shot) v;
                    c.setIcon(new ImageIcon(thumb(shot.after(), 96, 54)));
                    c.setText("<html><span style='font-size:9px'>" + shot.step() + " " + esc(shot.label()) + "</span></html>");
                    c.setVerticalTextPosition(SwingConstants.BOTTOM);
                    c.setHorizontalTextPosition(SwingConstants.CENTER);
                    c.setForeground(MUTED);
                    c.setBackground(s ? SELECT.darker().darker() : PANEL2);
                    c.setBorder(new EmptyBorder(4, 4, 4, 4));
                    return c;
                }
            });
            strip.addListSelectionListener(e -> {
                if (!e.getValueIsAdjusting() && strip.getSelectedValue() != null) {
                    mode.setSelectedItem(Mode.HISTORY);
                    view.repaint();
                }
            });
            strip.addMouseListener(new MouseAdapter() {
                @Override public void mouseClicked(MouseEvent e) {
                    if (e.getClickCount() == 2 && strip.getSelectedValue() != null) model.jumpTo(strip.getSelectedValue().step());
                }
            });
            JScrollPane sp = scroll(strip);
            sp.setPreferredSize(new Dimension(100, 92));
            root.add(sp, BorderLayout.SOUTH);
            refresh();
        }

        void paintResult(Graphics2D g, int w, int h) {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setColor(BG);
            g.fillRect(0, 0, w, h);
            BufferedImage after = model.liveAfter, before = model.liveBefore;
            if (after == null) {
                g.setColor(MUTED);
                g.setFont(UI);
                String[] paragraphs = {"No frame from the game yet.",
                    "1. Save this graph into a folder shader pack: <minecraft>/shaderpacks/<name>/shaders/bedray/mosaic.json",
                    "2. Start Minecraft with Bedray and -Dbedray.customrt=<name>",
                    "3. The game sends the real render here a few times per second, before and after your shader.",
                    "Game: " + model.gameStatus};
                FontMetrics fm = g.getFontMetrics();
                int y = 24, maxW = Math.max(80, w - 32);
                for (String para : paragraphs) {
                    StringBuilder line = new StringBuilder();
                    for (String word : para.split(" ")) {
                        if (fm.stringWidth(line + word) > maxW && line.length() > 0) {
                            g.drawString(line.toString(), 16, y);
                            y += fm.getHeight();
                            line.setLength(0);
                        }
                        line.append(word).append(' ');
                    }
                    g.drawString(line.toString(), 16, y);
                    y += fm.getHeight() + 6;
                }
                return;
            }
            Mode md = mode.getSelectedItem();
            StudioModel.Shot other = strip.getSelectedValue();
            switch (md) {
                case AFTER -> draw(g, after, 0, 0, w, h);
                case BEFORE -> draw(g, before != null ? before : after, 0, 0, w, h);
                case SIDE -> {
                    draw(g, before != null ? before : after, 0, 0, w / 2 - 2, h);
                    draw(g, after, w / 2 + 2, 0, w - w / 2 - 2, h);
                    caption(g, "Minecraft", 8, 18);
                    caption(g, "Shader", w / 2 + 10, 18);
                }
                case SPLIT, HISTORY -> {
                    BufferedImage left = md == Mode.SPLIT ? (before != null ? before : after) : (other != null ? other.after() : after);
                    Rectangle r = fit(after, w, h);
                    g.drawImage(after, r.x, r.y, r.width, r.height, null);
                    int cut = r.x + (int) (r.width * split);
                    Shape clip = g.getClip();
                    g.clipRect(r.x, r.y, cut - r.x, r.height);
                    g.drawImage(left, r.x, r.y, r.width, r.height, null);
                    g.setClip(clip);
                    g.setColor(SELECT);
                    g.fillRect(cut - 1, r.y, 2, r.height);
                    caption(g, md == Mode.SPLIT ? "Minecraft" : "Earlier: " + (other == null ? "-" : other.step() + " " + other.label()), r.x + 8, r.y + 18);
                    caption(g, "Now", r.x + r.width - 50, r.y + 18);
                }
            }
        }

        static Rectangle fit(BufferedImage img, int w, int h) {
            double s = Math.min(w / (double) img.getWidth(), h / (double) img.getHeight());
            int iw = (int) (img.getWidth() * s), ih = (int) (img.getHeight() * s);
            return new Rectangle((w - iw) / 2, (h - ih) / 2, iw, ih);
        }

        static void draw(Graphics2D g, BufferedImage img, int x, int y, int w, int h) {
            Rectangle r = fit(img, w, h);
            g.drawImage(img, x + r.x, y + r.y, r.width, r.height, null);
        }

        static void caption(Graphics2D g, String t, int x, int y) {
            g.setFont(UI_S);
            g.setColor(new Color(0, 0, 0, 150));
            g.fillRoundRect(x - 4, y - 13, g.getFontMetrics().stringWidth(t) + 8, 18, 6, 6);
            g.setColor(Color.WHITE);
            g.drawString(t, x, y);
        }

        @Override void refresh() {
            if (shots.size() != model.shots.size() || (!shots.isEmpty() && shots.lastElement() != model.shots.get(model.shots.size() - 1))) {
                StudioModel.Shot sel = strip.getSelectedValue();
                shots.clear();
                model.shots.forEach(shots::addElement);
                if (sel != null) strip.setSelectedValue(sel, false);
            }
            view.repaint();
        }

        public JComponent component() { return root; }

        public void focus() { view.requestFocusInWindow(); }
    }

    // ================================================================== game link

    static final class GamePanel extends Listening {
        final JTextArea text = new JTextArea();
        final JScrollPane root;

        GamePanel(StudioModel m) {
            super(m);
            text.setEditable(false);
            text.setFont(UI);
            text.setBackground(PANEL);
            text.setForeground(MUTED);
            text.setLineWrap(true);
            text.setWrapStyleWord(true);
            text.setBorder(new EmptyBorder(10, 12, 10, 12));
            root = scroll(text);
            refresh();
        }

        @Override void refresh() {
            String t = "The game is the real-render viewport; the Result area shows its frames.\n\n"
                + "Pack file: " + (model.doc.file == null ? "not saved yet (Ctrl+S)" : model.doc.file) + "\n"
                + "Link folder: " + (model.linkDir() == null ? "-" : model.linkDir()) + "\n"
                + "Game: " + model.gameStatus + "\n"
                + "Live save: " + (model.live ? "on - every valid change is saved, the game reloads it within 0.5 s" : "off (Ctrl+S saves)") + "\n"
                + "Slang compiler: " + settings.slangCompiler + "\n\n"
                + "Start Minecraft with Bedray and -Dbedray.customrt=<pack folder name>. Graphs without ray nodes run with every "
                + "renderer (raster, voxel, hardware RT); ray nodes need hardware RT.";
            if (!t.equals(text.getText())) text.setText(t);
        }

        public JComponent component() { return root; }
    }

    // ================================================================== add-ons

    /**
     * Node libraries of the studio (shown or hidden in the palette) and the game's add-ons as the game reports them in
     * {@code .studio/addons.json}; toggling a game add-on writes {@code .studio/control.json}, which the game reads.
     */
    static final class AddonsPanel extends Listening {
        final JPanel content = new JPanel();
        final JScrollPane root;
        long addonsStamp = -1;
        String shown = "";

        AddonsPanel(StudioModel m) {
            super(m);
            content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
            content.setBackground(PANEL);
            content.setBorder(new EmptyBorder(10, 12, 10, 12));
            root = scroll(content);
            refresh();
        }

        record GameAddon(String id, String name, String version, String kind, boolean enabled) {}

        java.util.List<GameAddon> gameAddons() {
            java.util.List<GameAddon> out = new java.util.ArrayList<>();
            java.nio.file.Path dir = model.linkDir();
            if (dir == null) return out;
            try {
                java.nio.file.Path f = dir.resolve("addons.json");
                if (!java.nio.file.Files.exists(f)) return out;
                var arr = com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(f)).getAsJsonArray();
                java.util.Set<String> off = disabledGameAddons();
                for (var el : arr) {
                    var o = el.getAsJsonObject();
                    String id = o.get("id").getAsString();
                    out.add(new GameAddon(id, o.has("name") ? o.get("name").getAsString() : id,
                        o.has("version") ? o.get("version").getAsString() : "", o.has("kind") ? o.get("kind").getAsString() : "",
                        !off.contains(id)));
                }
            } catch (Exception ignored) {
                // unreadable while the game writes it
            }
            return out;
        }

        java.util.Set<String> disabledGameAddons() {
            java.util.Set<String> off = new java.util.LinkedHashSet<>();
            try {
                java.nio.file.Path f = model.linkDir().resolve("control.json");
                if (java.nio.file.Files.exists(f)) {
                    var o = com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(f)).getAsJsonObject();
                    if (o.has("disabledAddons")) for (var e : o.getAsJsonArray("disabledAddons")) off.add(e.getAsString());
                }
            } catch (Exception ignored) {
                // no control file yet
            }
            return off;
        }

        void setGameAddon(String id, boolean on) {
            try {
                java.util.Set<String> off = disabledGameAddons();
                if (on) off.remove(id);
                else off.add(id);
                java.nio.file.Path cf = model.linkDir().resolve("control.json");
                com.google.gson.JsonObject o = java.nio.file.Files.exists(cf)
                    ? com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(cf)).getAsJsonObject() : new com.google.gson.JsonObject();
                com.google.gson.JsonArray a = new com.google.gson.JsonArray();
                off.forEach(a::add);
                o.add("disabledAddons", a);
                java.nio.file.Files.createDirectories(model.linkDir());
                java.nio.file.Files.writeString(model.linkDir().resolve("control.json"), o.toString());
                model.say((on ? "Enabled " : "Disabled ") + id + " in the game", MUTED);
            } catch (Exception e) {
                model.say("Could not write the control file: " + e.getMessage(), BAD);
            }
        }

        @Override void refresh() {
            java.util.List<GameAddon> game = gameAddons();
            String key = settings.hiddenLibraries + "|" + game + "|" + model.doc.functions.size();
            if (key.equals(shown)) return;
            shown = key;
            content.removeAll();
            content.add(title("Node libraries"));
            content.add(label("Hidden libraries stay usable in graphs; they only leave the palette.", FAINT));
            java.util.Map<String, Integer> counts = new java.util.LinkedHashMap<>();
            for (StudioModel.Item it : model.items()) counts.merge(it.category(), 1, Integer::sum);
            for (var e : counts.entrySet()) {
                JCheckBox cb = new JCheckBox(e.getKey() + "   (" + e.getValue() + ")", !settings.hiddenLibraries.contains(e.getKey()));
                cb.setOpaque(false);
                cb.setForeground(TEXT);
                cb.setFont(UI);
                cb.setAlignmentX(0);
                cb.addActionListener(ev -> {
                    if (cb.isSelected()) settings.hiddenLibraries.remove(e.getKey());
                    else settings.hiddenLibraries.add(e.getKey());
                    settings.save();
                    model.fire();
                });
                content.add(cb);
            }
            content.add(Box.createVerticalStrut(12));
            content.add(title("Game add-ons"));
            if (game.isEmpty()) {
                content.add(label("<html>No add-on list from the game yet. A game running this pack reports its Bedray add-ons "
                    + "(light providers, compute services, renderer extensions) here, and you can switch them on and off.</html>", MUTED));
            }
            for (GameAddon a : game) {
                JCheckBox cb = new JCheckBox(a.name() + "  " + a.version() + "   " + a.kind(), a.enabled());
                cb.setOpaque(false);
                cb.setForeground(TEXT);
                cb.setFont(UI);
                cb.setAlignmentX(0);
                cb.addActionListener(ev -> setGameAddon(a.id(), cb.isSelected()));
                content.add(cb);
            }
            content.add(Box.createVerticalGlue());
            content.revalidate();
            content.repaint();
        }

        public JComponent component() { return root; }
    }

    // ================================================================== keys reference

    static final class KeysPanel extends Listening {
        final JPanel root = new JPanel(new BorderLayout(0, 6));
        final JTextField search = field("");
        final JTextArea text = new JTextArea();

        KeysPanel(StudioModel m) {
            super(m);
            root.setBackground(PANEL);
            root.setBorder(new EmptyBorder(8, 8, 8, 8));
            text.setEditable(false);
            text.setFont(MONO);
            text.setBackground(PANEL);
            text.setForeground(TEXT);
            search.getDocument().addDocumentListener(onText(this::refresh));
            root.add(search, BorderLayout.NORTH);
            root.add(scroll(text), BorderLayout.CENTER);
            refresh();
        }

        @Override void refresh() {
            String q = search.getText().trim().toLowerCase(Locale.ROOT);
            StringBuilder sb = new StringBuilder();
            String group = "";
            for (StudioKeys.Action a : StudioKeys.Action.values()) {
                String keys = String.join(", ", model.keys.get(a).stream().map(StudioKeys::human).toList());
                if (!q.isEmpty() && !(a.label.toLowerCase(Locale.ROOT).contains(q) || keys.toLowerCase(Locale.ROOT).contains(q))) continue;
                if (!a.group.equals(group)) {
                    group = a.group;
                    sb.append('\n').append(group.toUpperCase(Locale.ROOT)).append('\n');
                }
                sb.append(String.format("  %-22s %s%n", keys.isEmpty() ? "-" : keys, a.label));
            }
            sb.append("\nMOUSE / TOUCHPAD\n  drag node / socket       move / wire\n  drag empty space         box select (Shift adds)\n"
                + "  middle, right or Alt+drag  pan\n  wheel                    zoom (mouse mode) / pan (touchpad mode)\n"
                + "  Ctrl+wheel / pinch       zoom\n  double-click empty       add node\n  double-click node title  rename\n"
                + "  drag frame title / corner  move frame with its nodes / resize\n");
            text.setText(sb.toString());
            text.setCaretPosition(0);
        }

        public JComponent component() { return root; }

        public void focus() { search.requestFocusInWindow(); }
    }
}
