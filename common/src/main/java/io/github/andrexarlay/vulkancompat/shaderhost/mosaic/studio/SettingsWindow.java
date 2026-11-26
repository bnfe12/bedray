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

    /** What the settings change: the editor, or the start screen before any editor exists. */
    interface Host {
        StudioSettings settings();
        void restyle(StudioSettings next);
        void saveSettings();
        Window owner();
        StudioKeys keys();
        void capturingKeys(boolean on);
        void say(String text, Color c);
        void resetLayout();
        void runGame();
    }

    private final Host app;
    private StudioSettings draft;
    private final Timer apply;
    private int tab;

    SettingsWindow(StudioApp studio) { this(studio.settingsHost()); }

    SettingsWindow(Host app) {
        super(false, false, "Mosaic Studio - settings");
        this.app = app;
        apply = new Timer(160, e -> {
            app.restyle(draft.copy());
            app.settings().save();
        });
        apply.setRepeats(false);
        Rectangle scr = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        setSize(Math.min(820, scr.width - 40), Math.min(640, scr.height - 30));
        setLocationRelativeTo(app.owner());
        build();
    }

    /** Opens the page with this title (screenshot aid, links from other panels). */
    void showPage(String title) {
        for (int i = 0; i < pages.size(); i++) if (pages.get(i).title().equalsIgnoreCase(title)) tab = i;
        build();
    }

    void restyle() {
        BufferedImageHolder.fade(this, this::build);
    }

    /** Re-creates this window's content in the current look (the window itself stays). */
    private void build() {
        draft = app.settings().copy();
        root.removeAll();
        root.setBackground(BG);
        pages.clear();
        page("Look", "Theme, colours, nodes and wires", "shader", new Color(0x5AA0E0), this::look);
        page("Input", "Mouse, touchpad and keyboard", "move", new Color(0x4FB886), this::input);
        pages.add(new Page("Keys", "Every shortcut, rebindable", StudioWidgets.glyph("tag", new Color(0xB07CD8)), keys(), List.of("keys", "shortcut")));
        page("Editor", "Nodes, minimap, compiling, files", "shader", new Color(0x5E9EC8), this::editor);
        page("Preview", "Camera, frame rate, day cycle, image", "vanilla", new Color(0x7FB069), this::preview);
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
        /** Follows the width of the window (long rows wrap or shrink instead of scrolling sideways). */
        final JPanel panel = new JPanel() {
            @Override public Dimension getPreferredSize() {
                Dimension d = super.getPreferredSize();
                Container vp = getParent();
                return vp instanceof JViewport v && v.getWidth() > 0 ? new Dimension(v.getWidth(), d.height) : d;
            }
        };
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
                JLabel h = StudioUi.wrapped(esc(help), FAINT, 280); // long help wraps instead of widening the page
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

    private JComponent slider(int min, int max, int value, IntConsumer on) { return slider(min, max, value, on, String::valueOf); }

    /** A slider with its value shown beside it (fmt turns the slider's integer into the number the user thinks in). */
    private JComponent slider(int min, int max, int value, IntConsumer on, java.util.function.IntFunction<String> fmt) {
        JSlider s = new JSlider(min, max, Math.clamp(value, min, max));
        s.setBackground(PANEL);
        JLabel v = StudioUi.label(fmt.apply(s.getValue()), MUTED);
        v.setFont(UI_S);
        v.setHorizontalAlignment(SwingConstants.RIGHT);
        v.setPreferredSize(new Dimension(44, v.getPreferredSize().height));
        s.addChangeListener(e -> {
            on.accept(s.getValue());
            v.setText(fmt.apply(s.getValue()));
            if (!s.getValueIsAdjusting()) changed();
        });
        JPanel p = new JPanel(new BorderLayout(6, 0));
        p.setOpaque(false);
        p.add(s, BorderLayout.CENTER);
        p.add(v, BorderLayout.EAST);
        p.setToolTipText(min + " .. " + max);
        return p;
    }

    private static String tenth(int v) { return String.format(java.util.Locale.ROOT, "%.1f", v / 10.0); }

    private static String hundredth(int v) { return String.format(java.util.Locale.ROOT, "%.2f", v / 100.0); }

    /** A row with a sliding switch (the whole row is clickable). */
    private JComponent check(String label, boolean value, Consumer<Boolean> on) {
        StudioWidgets.Toggle t = new StudioWidgets.Toggle(value, v -> {
            on.accept(v);
            changed();
        });
        JPanel r = new JPanel(new BorderLayout(16, 0));
        r.setOpaque(false);
        JLabel l = StudioUi.wrapped(esc(label), TEXT, 420);
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
        f.row("Wire thickness", null, slider(10, 50, Math.round(draft.wireWidth * 10), v -> draft.wireWidth = v / 10f, SettingsWindow::tenth));
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

    private void editor(Form f) {
        f.section("Nodes");
        f.row("Inputs shown before folding", "A node with more inputs shows only the wired, required and changed ones (Ctrl+H shows all)",
            slider(2, 24, draft.compactInputs, v -> draft.compactInputs = v));
        f.wide(check("Minimap in the corner of the canvas when the graph does not fit the view", draft.minimap, v -> draft.minimap = v));
        f.row("Minimap width", "Pixels at most (and a third of the canvas)", slider(80, 600, draft.minimapWidth, v -> draft.minimapWidth = v));
        f.row("Minimap height", null, slider(50, 400, draft.minimapHeight, v -> draft.minimapHeight = v));
        f.wide(check("Fit the view to the graph when a graph or template is opened", draft.fitOnOpen, v -> draft.fitOnOpen = v));
        f.wide(check("Hints in the panels and a second line under each Scene row (tooltips tell either way)", draft.showHints, v -> draft.showHints = v));
        f.wide(check("Detailed status bar and preview footer (node folding, GPU time per pass, the device)", draft.detailedStatus, v -> draft.detailedStatus = v));
        f.section("Projects");
        f.wide(check("Start on the projects screen (when Studio opens without a file)", draft.showStartScreen, v -> draft.showStartScreen = v));
        JTextField author = field(draft.projectAuthor);
        author.getDocument().addDocumentListener(onText(() -> {
            draft.projectAuthor = author.getText().trim();
            changed();
        }));
        f.row("Author", "Written into the card of new projects (and their README / licence)", author);
        JTextField pdir = field(draft.projectsDir);
        pdir.setToolTipText(ProjectInfo.projectsHome().toString());
        pdir.getDocument().addDocumentListener(onText(() -> {
            draft.projectsDir = pdir.getText().trim();
            changed();
        }));
        f.row("Projects folder", "Where new projects go when the game folder is unknown (empty: ~/.bedray/projects)", pdir);
        f.row("Studio memory", "Most memory the Studio the game opens (J) may take; the game runs beside it (applies next start)",
            slider(384, 4096, draft.studioMemoryMb, v -> draft.studioMemoryMb = v, v -> v + " MB"));
        f.section("Compiling");
        f.row("Compile after an edit (ms)", "Pause after the last change before the graph is compiled, saved and sent to the game",
            slider(100, 3000, draft.compileDelayMs, v -> draft.compileDelayMs = v));
        f.section("Files");
        f.row("Recent files kept", null, slider(1, 50, draft.recentMax, v -> draft.recentMax = v));
        f.row("Palette history kept", "Commands remembered by Ctrl+Shift+P (0: none)", slider(0, 100, draft.paletteRecentMax, v -> draft.paletteRecentMax = v));
        f.row("Recent files", draft.recentFiles.size() + " in File > Open recent and the command palette", button("Clear", null, () -> {
            draft.recentFiles.clear();
            app.settings().recentFiles.clear();
            changed();
        }));
        f.row("Command palette history", "Commands used last come first in Ctrl+Shift+P", button("Clear", null, () -> {
            draft.paletteRecent.clear();
            app.settings().paletteRecent.clear();
            changed();
        }));
        f.wide(wrapped("Ctrl+Shift+P (or F3) opens the command palette: every command, menu item, template, node type and file. Ctrl+F finds a"
            + " node of the graph, L / Shift+L select what feeds a node / what it feeds, F8 walks the problems.", MUTED, 440));
    }

    private void preview(Form f) {
        f.section("Camera");
        f.row("Look speed", "Degrees the camera turns per pixel of mouse drag", slider(5, 200, (int) Math.round(draft.previewLookSpeed * 100),
            v -> draft.previewLookSpeed = v / 100.0, SettingsWindow::hundredth));
        f.row("Fly speed", "Blocks per key press, W A S D / Space / Shift (Ctrl: 3 times)", slider(1, 80,
            (int) Math.round(draft.previewFlySpeed * 10), v -> draft.previewFlySpeed = v / 10.0, SettingsWindow::tenth));
        f.row("Wheel speed", "Blocks per wheel step", slider(1, 160, (int) Math.round(draft.previewWheelSpeed * 10),
            v -> draft.previewWheelSpeed = v / 10.0, SettingsWindow::tenth));
        f.wide(check("Invert up / down when dragging", draft.previewInvertY, v -> draft.previewInvertY = v));
        f.section("Frames");
        f.row("Frames per second at most", "The GPU may give fewer; lower saves power", slider(2, 120, draft.previewFps, v -> draft.previewFps = v));
        f.row("GPU share", "Most of the GPU's time the preview may take; lower keeps the studio smooth on laptops",
            slider(10, 100, draft.previewGpuShare, v -> draft.previewGpuShare = v, v -> v + " %"));
        f.wide(check("Draw rarely while the studio is in the background", draft.previewIdleInBackground, v -> draft.previewIdleInBackground = v));
        f.row("Background frame interval", "How often the preview draws while another window is in front",
            slider(250, 30000, draft.previewBackgroundMs, v -> draft.previewBackgroundMs = v, v -> String.format(java.util.Locale.ROOT, "%.1f s", v / 1000.0)));
        f.row("Rest after", "Seconds without a change (camera, graph, look) after which the preview slows down (0: never)",
            slider(0, 120, draft.previewIdleSeconds, v -> draft.previewIdleSeconds = v, v -> v == 0 ? "never" : v + " s"));
        f.row("Resting frame rate", "Frames per second while resting (time, clouds and water still move, slowly)",
            slider(1, 30, draft.previewIdleFps, v -> draft.previewIdleFps = v, v -> v + " fps"));
        f.row("Shortest pause between frames", "Even on a fast GPU", slider(0, 1000, draft.previewMinPauseMs, v -> draft.previewMinPauseMs = v,
            v -> v + " ms"));
        f.section("Views and compare");
        f.row("Difference gain", "Compare > Difference: how many times the difference is brightened",
            slider(10, 640, (int) Math.round(draft.previewDiffGain * 10), v -> draft.previewDiffGain = v / 10.0, v -> "x" + tenth(v)));
        f.row("Clipping: bright", "View > Clipping marks a channel at or above this red (0..255)", slider(1, 255, draft.previewClipHigh,
            v -> draft.previewClipHigh = v));
        f.row("Clipping: dark", "View > Clipping marks pixels with every channel at or below this blue", slider(0, 254, draft.previewClipLow,
            v -> draft.previewClipLow = v));
        f.row("False colour: white at", "View > False colour: the brightness drawn as white (lower spreads dark scenes)",
            slider(5, 100, (int) Math.round(draft.previewFalseColourMax * 100), v -> draft.previewFalseColourMax = v / 100.0, SettingsWindow::hundredth));
        f.row("Day speed", "Minecraft ticks per second while the preview plays (0: time stands still, 20: the game's speed)",
            slider(0, 2400, draft.previewDaySpeed, v -> draft.previewDaySpeed = v));
        f.wide(check("Smooth scaling (off: exact pixels when the image is enlarged)", draft.previewSmooth, v -> draft.previewSmooth = v));
        f.wide(check("Info line: device, size, GPU time of the passes", draft.previewInfo, v -> draft.previewInfo = v));
        f.section("World");
        f.row("Far land from a save", "Columns per side read around the camera beyond the volume (0: none; more is slower to load)",
            slider(0, 1024, draft.previewFarSize, v -> draft.previewFarSize = v));
        f.row("Biome temperature", "Grass and leaf colour of the preview world (the game's colour map; plains 0.8)",
            slider(-100, 200, (int) Math.round(draft.previewBiomeTemperature * 100), v -> draft.previewBiomeTemperature = v / 100.0, SettingsWindow::hundredth));
        f.row("Biome downfall", "Plains 0.4, jungle 0.9, desert 0", slider(0, 100, (int) Math.round(draft.previewBiomeDownfall * 100),
            v -> draft.previewBiomeDownfall = v / 100.0, SettingsWindow::hundredth));
        f.section("Saved views");
        f.row("Saved cameras", draft.previewViews.size() + " (Preview > Views; keys 1..9 in the preview)", button("Delete all", null, () -> {
            draft.previewViews.clear();
            app.settings().previewViews.clear();
            changed();
        }));
        f.section("Reset");
        f.wide(button("Reset preview settings to defaults", null, () -> {
            StudioSettings d = new StudioSettings();
            draft.previewFps = d.previewFps;
            draft.previewGpuShare = d.previewGpuShare;
            draft.previewIdleInBackground = d.previewIdleInBackground;
            draft.previewBackgroundMs = d.previewBackgroundMs;
            draft.previewIdleSeconds = d.previewIdleSeconds;
            draft.previewIdleFps = d.previewIdleFps;
            draft.previewMinPauseMs = d.previewMinPauseMs;
            draft.previewDiffGain = d.previewDiffGain;
            draft.previewClipHigh = d.previewClipHigh;
            draft.previewClipLow = d.previewClipLow;
            draft.previewFalseColourMax = d.previewFalseColourMax;
            draft.previewLookSpeed = d.previewLookSpeed;
            draft.previewFlySpeed = d.previewFlySpeed;
            draft.previewWheelSpeed = d.previewWheelSpeed;
            draft.previewInvertY = d.previewInvertY;
            draft.previewSmooth = d.previewSmooth;
            draft.previewDaySpeed = d.previewDaySpeed;
            draft.previewInfo = d.previewInfo;
            changed();
        }));
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
                String keys = String.join(", ", app.keys().get(a).stream().map(StudioKeys::human).toList());
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
            app.capturingKeys(true);
            list.requestFocusInWindow();
            list.addKeyListener(new KeyAdapter() {
                @Override public void keyPressed(KeyEvent ev) {
                    int code = ev.getKeyCode();
                    if (code == KeyEvent.VK_SHIFT || code == KeyEvent.VK_CONTROL || code == KeyEvent.VK_ALT || code == KeyEvent.VK_META) return;
                    list.removeKeyListener(this);
                    app.capturingKeys(false);
                    ev.consume();
                    if (code == KeyEvent.VK_ESCAPE) {
                        capture.setText("Cancelled.");
                        return;
                    }
                    KeyStroke k = StudioApp.layoutIndependent(ev);
                    StudioKeys.Action stolen = app.keys().bind(a, Integer.MAX_VALUE, k);
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
            while (!app.keys().get(a).isEmpty()) app.keys().unbind(a, 0);
            app.saveSettings();
            list.repaint();
        }));
        buttons.add(button("Reset all", "Every action back to its default keys", () -> {
            app.keys().reset();
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
        f.wide(check("Area buttons (split, maximize, pop out, close) only on the area under the pointer", draft.hoverAreaButtons, v -> draft.hoverAreaButtons = v));
        f.wide(button("Save the current layout now", null, () -> {
            app.saveSettings();
            app.say("Layout saved", OK);
        }));
        f.wide(button("Reset to the default layout", null, this::resetLayout));
    }

    void resetLayout() { app.resetLayout(); }

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
                app.say("Created " + bin.resolve("mosaic.cmd") + " (add " + bin + " to PATH)", OK);
            } catch (Exception ex) {
                app.say("Could not create the command: " + ex.getMessage(), BAD);
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
        JTextField cmd = field(app.settings().gameCommand == null ? "" : app.settings().gameCommand);
        cmd.addActionListener(e -> {
            app.settings().gameCommand = cmd.getText().trim();
            app.saveSettings();
            app.say("Game command saved", OK);
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
