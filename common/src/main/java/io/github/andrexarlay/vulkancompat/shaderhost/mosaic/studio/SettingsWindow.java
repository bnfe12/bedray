package io.github.andrexarlay.vulkancompat.shaderhost.mosaic.studio;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

import static io.github.andrexarlay.vulkancompat.shaderhost.mosaic.studio.StudioUi.*;

/** Mosaic Studio settings: look, input, keys, layout and compiler. Look changes apply at once with a cross-fade. */
final class SettingsWindow extends Dock.StudioWindow {

    private final StudioApp app;
    private StudioSettings draft;
    private final Timer apply;
    private int tab;

    SettingsWindow(StudioApp app) {
        super(false, false, "Mosaic Studio - settings");
        this.app = app;
        apply = new Timer(160, e -> {
            app.restyle(draft.copy());
            app.settings.save();
        });
        apply.setRepeats(false);
        setSize(820, 640);
        setLocationRelativeTo(app.main);
        build();
    }

    void restyle() {
        BufferedImageHolder.fade(this, this::build);
    }

    /** Re-creates this window's content in the current look (the window itself stays). */
    private void build() {
        draft = app.settings.copy();
        root.removeAll();
        root.setBackground(BG);
        pages.clear();
        page("Look", "Theme, colours, nodes and wires", "shader", new Color(0x5AA0E0), this::look);
        page("Input", "Mouse, touchpad and keyboard", "move", new Color(0x4FB886), this::input);
        pages.add(new Page("Keys", "Every shortcut, rebindable", StudioWidgets.glyph("tag", new Color(0xB07CD8)), keys(), List.of("keys", "shortcut")));
        page("Layout", "Areas and windows", "frame", new Color(0x8A6FC8), this::layout);
        page("Compiler", "Slang and SPIR-V", "code", new Color(0x5E9E4E), this::compiler);
        page("Game", "Running Minecraft from the studio", "vanilla", new Color(0xD08A4A), this::game);
        page("Agent", "What an AI agent may do through the mosaic command", "code", new Color(0xE0605A), this::agent);

        CardLayout cards = new CardLayout();
        JPanel content = new JPanel(cards);
        content.setBackground(BG);
        for (Page p : pages) {
            JPanel holder = new JPanel(new BorderLayout());
            holder.setBackground(BG);
            JPanel head = new JPanel(new GridLayout(0, 1));
            head.setOpaque(false);
            head.setBorder(new EmptyBorder(16, 22, 8, 22));
            JLabel t = new JLabel(p.title());
            t.setFont(UI_B.deriveFont(UI_B.getSize2D() + 6));
            t.setForeground(TEXT);
            head.add(t);
            head.add(label(p.subtitle(), MUTED));
            holder.add(head, BorderLayout.NORTH);
            holder.add(p.body(), BorderLayout.CENTER);
            content.add(holder, p.title());
        }

        DefaultListModel<Page> lm = new DefaultListModel<>();
        pages.forEach(lm::addElement);
        JList<Page> side = new JList<>(lm);
        side.setBackground(PANEL);
        side.setFixedCellHeight(36);
        side.setCellRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> l, Object v, int i, boolean s, boolean f) {
                JLabel c = (JLabel) super.getListCellRendererComponent(l, ((Page) v).title(), i, s, false);
                c.setIcon(((Page) v).icon());
                c.setIconTextGap(10);
                c.setFont(UI);
                c.setForeground(s ? TEXT : MUTED);
                c.setBackground(s ? StudioWidgets.mix(PANEL, SELECT, 0.3) : PANEL);
                c.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0, 3, 0, 0, s ? SELECT : PANEL),
                    new EmptyBorder(0, 12, 0, 8)));
                return c;
            }
        });
        side.addListSelectionListener(e -> {
            if (side.getSelectedValue() == null) return;
            tab = pages.indexOf(side.getSelectedValue());
            cards.show(content, side.getSelectedValue().title());
        });
        JTextField search = field("");
        search.setToolTipText("Find a setting");
        search.getDocument().addDocumentListener(onText(() -> {
            String q = search.getText().trim().toLowerCase(java.util.Locale.ROOT);
            lm.clear();
            for (Page p : pages) {
                boolean any = q.isEmpty() || p.title().toLowerCase(java.util.Locale.ROOT).contains(q);
                for (Row r : p.rows()) {
                    boolean m = q.isEmpty() || r.text().contains(q);
                    r.comp().setVisible(m || p.title().toLowerCase(java.util.Locale.ROOT).contains(q));
                    any |= m;
                }
                if (any) lm.addElement(p);
            }
            if (!lm.isEmpty()) side.setSelectedIndex(0);
            content.revalidate();
            content.repaint();
        }));
        JPanel left = new JPanel(new BorderLayout(0, 8));
        left.setBackground(PANEL);
        left.setBorder(new EmptyBorder(12, 10, 12, 0));
        left.setPreferredSize(new Dimension(190, 10));
        JPanel searchBox = new JPanel(new BorderLayout());
        searchBox.setOpaque(false);
        searchBox.setBorder(new EmptyBorder(0, 0, 0, 10));
        searchBox.add(search);
        left.add(searchBox, BorderLayout.NORTH);
        left.add(side, BorderLayout.CENTER);
        JLabel where = label("Saved automatically", FAINT);
        where.setFont(UI_S);
        where.setToolTipText(StudioSettings.file().toString());
        left.add(where, BorderLayout.SOUTH);

        root.add(left, BorderLayout.WEST);
        root.add(content, BorderLayout.CENTER);
        side.setSelectedIndex(Math.min(tab, pages.size() - 1));
        root.revalidate();
        root.repaint();
    }

    /** A settings page: sidebar entry, header, body; rows are kept for search. */
    private record Page(String title, String subtitle, Icon icon, JComponent body, List<String> words) {
        List<Row> rows() { return rowsOf.getOrDefault(title, List.of()); }
        @Override public String toString() { return title; }
    }

    private record Row(String text, JComponent comp) {}

    private final List<Page> pages = new java.util.ArrayList<>();
    private static final java.util.Map<String, List<Row>> rowsOf = new java.util.HashMap<>();

    private void page(String title, String subtitle, String glyph, Color color, Consumer<Form> fill) {
        Form f = new Form(title);
        fill.accept(f);
        f.end();
        rowsOf.put(title, f.rows);
        pages.add(new Page(title, subtitle, StudioWidgets.glyph(glyph, color), scroll(f.panel), List.of()));
    }

    /** Cards of rows: a title and a grey line of explanation on the left, the control on the right. */
    private final class Form {
        final JPanel panel = new JPanel();
        final List<Row> rows = new java.util.ArrayList<>();
        JPanel card;
        final String page;

        Form(String page) {
            this.page = page;
            panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
            panel.setBackground(BG);
            panel.setBorder(new EmptyBorder(4, 18, 18, 18));
        }

        /** Starts a card with a small heading. */
        void section(String title) {
            JLabel h = label(title.toUpperCase(java.util.Locale.ROOT), FAINT);
            h.setFont(UI_S.deriveFont(Font.BOLD));
            h.setBorder(new EmptyBorder(14, 6, 6, 0));
            h.setAlignmentX(0);
            panel.add(h);
            card = new JPanel() {
                @Override protected void paintComponent(Graphics g0) {
                    Graphics2D g = StudioWidgets.smooth(g0);
                    g.setColor(PANEL);
                    g.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 12, 12);
                    g.setColor(EDGE);
                    g.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 12, 12);
                    g.dispose();
                }
            };
            card.setOpaque(false);
            card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
            card.setBorder(new EmptyBorder(4, 0, 4, 0));
            card.setAlignmentX(0);
            panel.add(card);
        }

        private JPanel card() {
            if (card == null) section(page);
            return card;
        }

        void row(String label, JComponent field) { row(label, null, field); }

        void row(String label, String help, JComponent field) {
            JPanel r = new JPanel(new BorderLayout(16, 0));
            r.setOpaque(false);
            r.setBorder(new EmptyBorder(9, 14, 9, 14));
            JPanel text = new JPanel(new GridLayout(0, 1, 0, 1));
            text.setOpaque(false);
            text.add(StudioUi.label(label, TEXT));
            if (help != null) {
                JLabel h = StudioUi.label(help, FAINT);
                h.setFont(UI_S);
                text.add(h);
            }
            r.add(text, BorderLayout.CENTER);
            JPanel right = new JPanel(new BorderLayout());
            right.setOpaque(false);
            field.setPreferredSize(new Dimension(Math.max(170, Math.min(240, field.getPreferredSize().width)), field.getPreferredSize().height));
            right.add(field, BorderLayout.CENTER);
            r.add(right, BorderLayout.EAST);
            add(r, label + " " + (help == null ? "" : help));
        }

        void wide(JComponent comp) {
            JPanel r = new JPanel(new BorderLayout());
            r.setOpaque(false);
            r.setBorder(new EmptyBorder(8, 14, 8, 14));
            r.add(comp, BorderLayout.CENTER);
            String text = comp instanceof AbstractButton b ? b.getText() : comp instanceof JLabel l ? l.getText() : comp.getToolTipText();
            add(r, text == null ? "" : text);
        }

        private void add(JComponent r, String text) {
            JPanel c = card();
            if (c.getComponentCount() > 0) {
                JSeparator s = new JSeparator();
                s.setForeground(StudioWidgets.mix(PANEL, EDGE, 0.7));
                s.setMaximumSize(new Dimension(Integer.MAX_VALUE, 1));
                c.add(s);
            }
            r.setAlignmentX(0);
            r.setMaximumSize(new Dimension(Integer.MAX_VALUE, r.getPreferredSize().height + 4));
            c.add(r);
            rows.add(new Row(text.toLowerCase(java.util.Locale.ROOT), r));
            c.setMaximumSize(new Dimension(Integer.MAX_VALUE, c.getPreferredSize().height + 8));
        }

        void end() { panel.add(Box.createVerticalGlue()); }
    }

    private void changed() { apply.restart(); }

    private <T> JComboBox<T> combo(T[] values, T selected, Consumer<T> on) {
        JComboBox<T> box = new JComboBox<>(values);
        box.setSelectedItem(selected);
        box.addActionListener(e -> {
            on.accept(box.getItemAt(box.getSelectedIndex()));
            changed();
        });
        return box;
    }

    private JSlider slider(int min, int max, int value, IntConsumer on) {
        JSlider s = new JSlider(min, max, value);
        s.setBackground(PANEL);
        s.addChangeListener(e -> {
            on.accept(s.getValue());
            if (!s.getValueIsAdjusting()) changed();
        });
        return s;
    }

    /** A row with a sliding switch (the whole row is clickable). */
    private JComponent check(String label, boolean value, Consumer<Boolean> on) {
        StudioWidgets.Toggle t = new StudioWidgets.Toggle(value, v -> {
            on.accept(v);
            changed();
        });
        JPanel r = new JPanel(new BorderLayout(16, 0));
        r.setOpaque(false);
        JLabel l = StudioUi.label(label, TEXT);
        r.add(l, BorderLayout.CENTER);
        r.add(t, BorderLayout.EAST);
        r.setToolTipText(label);
        l.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent e) { t.flip(); }
        });
        return r;
    }

    private void look(Form f) {
        f.section("Appearance");
        f.row("Theme", "Colours of the whole studio; OLED black turns pixels off", combo(StudioSettings.Theme.values(), draft.theme, v -> draft.theme = v));
        f.row("Accent", "Selection, focus and highlights", combo(StudioSettings.Accent.values(), draft.accent, v -> draft.accent = v));
        f.row("Window buttons", "Mosaic set on the right or traffic lights on the left", combo(StudioSettings.WindowButtons.values(), draft.windowButtons, v -> draft.windowButtons = v));
        f.row("Text size", "Scales every font of the studio", slider(80, 160, draft.fontPercent, v -> draft.fontPercent = v));
        f.section("Nodes and wires");
        f.row("Wires", "Curved, straight or angled", combo(StudioSettings.WireStyle.values(), draft.wires, v -> draft.wires = v));
        f.row("Wire thickness", null, slider(10, 50, Math.round(draft.wireWidth * 10), v -> draft.wireWidth = v / 10f));
        f.row("Node width", null, slider(130, 260, draft.nodeWidth, v -> draft.nodeWidth = v));
        f.wide(check("Port names on nodes", draft.portNames, v -> draft.portNames = v));
        f.section("Canvas");
        f.row("Grid", null, combo(StudioSettings.GridStyle.values(), draft.grid, v -> draft.grid = v));
        f.row("Grid step", "Pixels between grid lines", slider(8, 64, draft.gridStep, v -> draft.gridStep = v));
        f.wide(check("Snap nodes to the grid (hold Alt to place freely)", draft.snap, v -> draft.snap = v));
        f.wide(check("Live save on when the studio starts", draft.liveByDefault, v -> draft.liveByDefault = v));
        f.section("Reset");
        f.wide(button("Reset look to defaults", null, () -> {
            StudioSettings d = new StudioSettings();
            draft.theme = d.theme;
            draft.accent = d.accent;
            draft.fontPercent = d.fontPercent;
            draft.wires = d.wires;
            draft.wireWidth = d.wireWidth;
            draft.nodeWidth = d.nodeWidth;
            draft.grid = d.grid;
            draft.gridStep = d.gridStep;
            draft.snap = d.snap;
            draft.portNames = d.portNames;
            changed();
        }));
    }

    private void input(Form f) {
        f.section("Mouse and touchpad");
        f.row("Scrolling", "Wheel zooms or pans the graph", combo(StudioSettings.WheelMode.values(), draft.wheel, v -> draft.wheel = v));
        f.row("Zoom speed", "Percent per wheel step", slider(3, 40, draft.zoomSpeed, v -> draft.zoomSpeed = v));
        f.wide(check("Invert wheel / scroll direction", draft.invertWheel, v -> draft.invertWheel = v));
        f.wide(check("Alt + left drag pans (touchpads without a middle button)", draft.altDragPans, v -> draft.altDragPans = v));
        f.wide(check("Right drag on empty canvas pans (a short right click opens the add menu)", draft.rightDragPans, v -> draft.rightDragPans = v));
        f.section("Keyboard");
        f.wide(check("Keyboard hints: outline the keyboard node and the focused area", draft.keyboardHints, v -> draft.keyboardHints = v));
        f.wide(wrapped("Keyboard only: Tab / Alt+arrows walk the nodes, arrows move them, Space adds, K / Shift+K wire, F2 renames, "
            + "Enter edits properties, F6 switches areas, Ctrl+Space maximizes one. Everything is rebindable in Keys.", MUTED, 440));
    }

    private JComponent keys() {
        JPanel p = new JPanel(new BorderLayout(0, 6));
        p.setBackground(PANEL);
        p.setBorder(new EmptyBorder(10, 12, 10, 12));
        DefaultListModel<StudioKeys.Action> lm = new DefaultListModel<>();
        for (StudioKeys.Action a : StudioKeys.Action.values()) lm.addElement(a);
        JList<StudioKeys.Action> list = new JList<>(lm);
        list.setBackground(PANEL);
        list.setCellRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> l, Object v, int i, boolean s, boolean f) {
                JLabel c = (JLabel) super.getListCellRendererComponent(l, v, i, s, false);
                StudioKeys.Action a = (StudioKeys.Action) v;
                String keys = String.join(", ", app.model.keys.get(a).stream().map(StudioKeys::human).toList());
                c.setText("<html><span style='color:#8B93A8'>" + esc(a.group) + "</span>&nbsp;&nbsp;" + esc(a.label)
                    + "&nbsp;&nbsp;&nbsp;<b>" + esc(keys.isEmpty() ? "-" : keys) + "</b></html>");
                c.setBackground(s ? SELECT.darker().darker() : PANEL);
                c.setForeground(TEXT);
                c.setBorder(new EmptyBorder(3, 4, 3, 4));
                return c;
            }
        });
        p.add(scroll(list), BorderLayout.CENTER);
        JLabel capture = label("Select an action, press Record, then press the new keys (Esc cancels).", MUTED);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        buttons.setOpaque(false);
        JButton record = button("Record", "Add a key for the selected action", null);
        record.addActionListener(e -> {
            StudioKeys.Action a = list.getSelectedValue();
            if (a == null) return;
            capture.setText("Press the new keys for \"" + a.label + "\"...");
            app.capturingKeys = true;
            list.requestFocusInWindow();
            list.addKeyListener(new KeyAdapter() {
                @Override public void keyPressed(KeyEvent ev) {
                    int code = ev.getKeyCode();
                    if (code == KeyEvent.VK_SHIFT || code == KeyEvent.VK_CONTROL || code == KeyEvent.VK_ALT || code == KeyEvent.VK_META) return;
                    list.removeKeyListener(this);
                    app.capturingKeys = false;
                    ev.consume();
                    if (code == KeyEvent.VK_ESCAPE) {
                        capture.setText("Cancelled.");
                        return;
                    }
                    KeyStroke k = StudioApp.layoutIndependent(ev);
                    StudioKeys.Action stolen = app.model.keys.bind(a, Integer.MAX_VALUE, k);
                    capture.setText(StudioKeys.human(k) + " -> " + a.label + (stolen != null ? "   (taken from " + stolen.label + ")" : ""));
                    app.saveSettings();
                    list.repaint();
                }
            });
        });
        buttons.add(record);
        buttons.add(button("Clear keys", "Remove every key of the selected action", () -> {
            StudioKeys.Action a = list.getSelectedValue();
            if (a == null) return;
            while (!app.model.keys.get(a).isEmpty()) app.model.keys.unbind(a, 0);
            app.saveSettings();
            list.repaint();
        }));
        buttons.add(button("Reset all", "Every action back to its default keys", () -> {
            app.model.keys.reset();
            app.saveSettings();
            list.repaint();
        }));
        JPanel south = new JPanel(new BorderLayout(0, 6));
        south.setOpaque(false);
        south.add(buttons, BorderLayout.NORTH);
        south.add(capture, BorderLayout.SOUTH);
        p.add(south, BorderLayout.SOUTH);
        return p;
    }

    private void layout(Form f) {
        f.wide(wrapped("Areas: change an area's editor in its header, split it side by side or top/bottom, maximize it, pop it "
            + "out into its own window or close it. Pop-out windows have a Dock back button. The layout of every window is saved "
            + "when the studio closes.", MUTED, 440));
        f.wide(button("Save the current layout now", null, () -> {
            app.saveSettings();
            app.model.say("Layout saved", OK);
        }));
        f.wide(button("Reset to the default layout", null, this::resetLayout));
    }

    void resetLayout() {
        {
            for (Dock.StudioWindow w : new java.util.ArrayList<>(app.windows)) if (!w.main) {
                app.windows.remove(w);
                w.dispose();
            }
            app.settings.layout = Dock.defaultLayout();
            StudioSettings s = app.settings.copy();
            s.layout = Dock.defaultLayout();
            app.windows.removeIf(w -> !w.main);
            app.main.root.removeAll();
            app.main.root.add(Dock.load(app.model, Dock.parseWindows(Dock.defaultLayout()).get(0).getAsJsonObject().getAsJsonObject("tree")),
                BorderLayout.CENTER);
            app.main.root.revalidate();
            app.main.root.repaint();
            app.saveSettings();
        }
    }

    /** Quiet JVM: the shader compiler's native access is expected. */
    static final String JVM_FLAGS = "--enable-native-access=ALL-UNNAMED -XX:+IgnoreUnrecognizedVMOptions --sun-misc-unsafe-memory-access=allow";

    private void agent(Form f) {
        StudioSettings.Agent a = draft.agent;
        f.section("Access");
        f.wide(check("Let AI agents use Mosaic Studio (the mosaic command)", a.enabled, v -> a.enabled = v));
        f.wide(check("Edit shader graphs (add, connect, set, delete nodes)", a.editGraphs, v -> a.editGraphs = v));
        f.wide(check("Edit materials and bake textures", a.editMaterials, v -> a.editMaterials = v));
        f.wide(check("Change the studio's design and settings (theme, colours, sizes...)", a.editDesign, v -> a.editDesign = v));
        f.wide(check("Compile shaders to check them", a.compile, v -> a.compile = v));
        f.wide(wrapped("Every change an agent makes appears in the studio as an undoable step (\"External edit\"). Agents cannot change "
            + "these switches. Every call is written to " + esc(AgentCli.logFile().toString()) + ".", MUTED, 440));
        f.section("Command");
        f.wide(wrapped("Agents (and you) run: <b>mosaic help</b>, <b>mosaic types</b>, <b>mosaic info graph.json</b>, "
            + "<b>mosaic add graph.json noise n1</b>, <b>mosaic connect graph.json n1 out.color</b>, <b>mosaic compile graph.json</b>, "
            + "<b>mosaic set-setting theme OLED</b>... Add --json for machine-readable output.", TEXT, 440));
        f.wide(button("Create the mosaic command", "Writes mosaic.cmd and mosaic (shell) into ~/.bedray/bin for this studio", () -> {
            try {
                java.nio.file.Path bin = StudioSettings.file().resolveSibling("bin");
                java.nio.file.Files.createDirectories(bin);
                String javaExe = java.nio.file.Path.of(System.getProperty("java.home"), "bin", "java").toString();
                String cp = System.getProperty("java.class.path");
                java.nio.file.Files.writeString(bin.resolve("mosaic.cmd"), "@echo off\r\n\"" + javaExe + "\" " + JVM_FLAGS + " -cp \"" + cp + "\" "
                    + AgentCli.class.getName() + " %*\r\n");
                java.nio.file.Path sh = bin.resolve("mosaic");
                java.nio.file.Files.writeString(sh, "#!/bin/sh\nexec \"" + javaExe + "\" " + JVM_FLAGS + " -cp \"" + cp + "\" " + AgentCli.class.getName() + " \"$@\"\n");
                sh.toFile().setExecutable(true);
                app.model.say("Created " + bin.resolve("mosaic.cmd") + " (add " + bin + " to PATH)", OK);
            } catch (Exception ex) {
                app.model.say("Could not create the command: " + ex.getMessage(), BAD);
            }
        }));
        f.section("Recent agent activity");
        String recent;
        try {
            java.util.List<String> lines = java.nio.file.Files.exists(AgentCli.logFile()) ? java.nio.file.Files.readAllLines(AgentCli.logFile()) : java.util.List.of();
            recent = lines.isEmpty() ? "Nothing yet." : String.join("<br>", lines.subList(Math.max(0, lines.size() - 10), lines.size()).stream().map(StudioUi::esc).toList());
        } catch (Exception ex) {
            recent = "The log cannot be read.";
        }
        f.wide(wrapped(recent, MUTED, 440));
    }

    private void compiler(Form f) {
        f.row("Slang compiles", combo(StudioSettings.SlangCompiler.values(), draft.slangCompiler, v -> draft.slangCompiler = v));
        JTextField slangc = field(draft.slangcPath == null ? "" : draft.slangcPath);
        slangc.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            void sync() { draft.slangcPath = slangc.getText().trim(); }
            @Override public void insertUpdate(javax.swing.event.DocumentEvent e) { sync(); }
            @Override public void removeUpdate(javax.swing.event.DocumentEvent e) { sync(); }
            @Override public void changedUpdate(javax.swing.event.DocumentEvent e) { sync(); }
        });
        slangc.setToolTipText("Empty: found automatically. Now: " + java.util.Objects.toString(
            io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicSlang.locate(draft.slangcPath), "not found"));
        f.row("slangc path", slangc);
        f.wide(wrapped("Studio: the studio compiles Slang node code and hands the game ready SPIR-V; the mod stays small and "
            + "live editing works for everyone using the studio.<br><br>In game: the optional Slang compiler add-on (about 30 MB "
            + "on Windows, separate builds for Linux and macOS) compiles inside Minecraft, so graphs can change without the studio."
            + "<br><br>Iris shader packs are GLSL by nature and keep the GLSL path.", MUTED, 440));
    }

    private void game(Form f) {
        JTextField cmd = field(app.settings.gameCommand == null ? "" : app.settings.gameCommand);
        cmd.addActionListener(e -> {
            app.settings.gameCommand = cmd.getText().trim();
            app.saveSettings();
            app.model.say("Game command saved", OK);
        });
        f.row("Start command", cmd);
        java.nio.file.Path prism = StudioApp.findPrism();
        f.wide(wrapped("Run game starts this command. Example with Prism Launcher: \"" + (prism == null ? "...\\prismlauncher.exe" : prism)
            + "\" --launch \"MyInstance\". Give that instance the JVM argument -Dbedray.customrt=&lt;pack folder name&gt; so it loads this "
            + "graph's pack; Live save then shows every change in the game within half a second. Enter saves the command.", MUTED, 440));
        f.wide(button("Run game now", null, app::runGame));
    }

    /** Cross-fade helper for this window's own rebuild. */
    static final class BufferedImageHolder {
        static void fade(Dock.StudioWindow w, Runnable rebuild) {
            java.awt.image.BufferedImage old = w.snapshot();
            rebuild.run();
            w.fadeFrom(old);
        }
    }
}
