package io.github.andrexarlay.vulkancompat.shaderhost.mosaic.studio;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
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
        setSize(560, 600);
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
        root.setBackground(PANEL);
        JTabbedPane tabs = new JTabbedPane();
        tabs.setFont(UI);
        tabs.addTab("Look", page(this::look));
        tabs.addTab("Input", page(this::input));
        tabs.addTab("Keys", keys());
        tabs.addTab("Layout", page(this::layout));
        tabs.addTab("Compiler", page(this::compiler));
        tabs.addTab("Game", page(this::game));
        tabs.setSelectedIndex(Math.min(tab, tabs.getTabCount() - 1));
        tabs.addChangeListener(e -> tab = tabs.getSelectedIndex());
        root.add(tabs, BorderLayout.CENTER);
        JPanel bottom = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 6));
        bottom.setBackground(PANEL2);
        bottom.add(label("Saved automatically to " + StudioSettings.file(), FAINT));
        bottom.add(button("Close", null, this::dispose));
        root.add(bottom, BorderLayout.SOUTH);
        root.revalidate();
        root.repaint();
    }

    private JComponent page(Consumer<Form> fill) {
        Form f = new Form();
        fill.accept(f);
        return scroll(f.panel);
    }

    /** A two-column form. */
    private final class Form {
        final JPanel panel = new JPanel(new GridBagLayout());
        final GridBagConstraints c = new GridBagConstraints();

        Form() {
            panel.setBackground(PANEL);
            panel.setBorder(new EmptyBorder(12, 14, 12, 14));
            c.gridy = 0;
            c.anchor = GridBagConstraints.WEST;
            c.insets = new Insets(5, 4, 5, 10);
            c.fill = GridBagConstraints.HORIZONTAL;
        }

        void row(String label, JComponent field) {
            c.gridx = 0;
            c.gridwidth = 1;
            c.weightx = 0;
            panel.add(StudioUi.label(label, TEXT), c);
            c.gridx = 1;
            c.weightx = 1;
            panel.add(field, c);
            c.gridy++;
        }

        void wide(JComponent comp) {
            c.gridx = 0;
            c.gridwidth = 2;
            c.weightx = 1;
            panel.add(comp, c);
            c.gridy++;
            c.gridwidth = 1;
        }

        void end() {
            c.gridx = 0;
            c.gridwidth = 2;
            c.weighty = 1;
            panel.add(Box.createVerticalGlue(), c);
        }
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

    private JCheckBox check(String label, boolean value, Consumer<Boolean> on) {
        JCheckBox cb = new JCheckBox(label, value);
        cb.setOpaque(false);
        cb.setForeground(TEXT);
        cb.addActionListener(e -> {
            on.accept(cb.isSelected());
            changed();
        });
        return cb;
    }

    private void look(Form f) {
        f.row("Theme", combo(StudioSettings.Theme.values(), draft.theme, v -> draft.theme = v));
        f.row("Accent", combo(StudioSettings.Accent.values(), draft.accent, v -> draft.accent = v));
        f.row("Text size", slider(80, 160, draft.fontPercent, v -> draft.fontPercent = v));
        f.row("Wires", combo(StudioSettings.WireStyle.values(), draft.wires, v -> draft.wires = v));
        f.row("Wire thickness", slider(10, 50, Math.round(draft.wireWidth * 10), v -> draft.wireWidth = v / 10f));
        f.row("Node width", slider(130, 260, draft.nodeWidth, v -> draft.nodeWidth = v));
        f.row("Grid", combo(StudioSettings.GridStyle.values(), draft.grid, v -> draft.grid = v));
        f.row("Grid step", slider(8, 64, draft.gridStep, v -> draft.gridStep = v));
        f.wide(check("Snap nodes to the grid (hold Alt to place freely)", draft.snap, v -> draft.snap = v));
        f.wide(check("Port names on nodes", draft.portNames, v -> draft.portNames = v));
        f.wide(check("Live save on when the studio starts", draft.liveByDefault, v -> draft.liveByDefault = v));
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
        f.end();
    }

    private void input(Form f) {
        f.row("Scrolling", combo(StudioSettings.WheelMode.values(), draft.wheel, v -> draft.wheel = v));
        f.row("Zoom speed", slider(3, 40, draft.zoomSpeed, v -> draft.zoomSpeed = v));
        f.wide(check("Invert wheel / scroll direction", draft.invertWheel, v -> draft.invertWheel = v));
        f.wide(check("Alt + left drag pans (touchpads without a middle button)", draft.altDragPans, v -> draft.altDragPans = v));
        f.wide(check("Right drag on empty canvas pans (a short right click opens the add menu)", draft.rightDragPans, v -> draft.rightDragPans = v));
        f.wide(check("Keyboard hints: outline the keyboard node and the focused area", draft.keyboardHints, v -> draft.keyboardHints = v));
        f.wide(wrapped("Keyboard only: Tab / Alt+arrows walk the nodes, arrows move them, Space adds, K / Shift+K wire, F2 renames, "
            + "Enter edits properties, F6 switches areas, Ctrl+Space maximizes one. Everything is rebindable in Keys.", MUTED, 440));
        f.end();
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
        f.end();
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

    private void compiler(Form f) {
        f.row("Slang compiles", combo(StudioSettings.SlangCompiler.values(), draft.slangCompiler, v -> draft.slangCompiler = v));
        f.wide(wrapped("Studio: the studio compiles Slang node code and hands the game ready SPIR-V; the mod stays small and "
            + "live editing works for everyone using the studio.<br><br>In game: the optional Slang compiler add-on (about 30 MB "
            + "on Windows, separate builds for Linux and macOS) compiles inside Minecraft, so graphs can change without the studio."
            + "<br><br>Iris shader packs are GLSL by nature and keep the GLSL path.", MUTED, 440));
        f.end();
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
        f.end();
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
