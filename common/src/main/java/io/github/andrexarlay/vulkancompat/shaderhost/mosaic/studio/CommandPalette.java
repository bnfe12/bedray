package io.github.andrexarlay.vulkancompat.shaderhost.mosaic.studio;

import io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicDocument;
import io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicTemplates;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static io.github.andrexarlay.vulkancompat.shaderhost.mosaic.studio.StudioUi.*;

/**
 * The command palette (Ctrl+Shift+P / F3) and node finder (Ctrl+F): one search over every command of the studio - every
 * key action, every item of the menu bar (picked up by itself, so new menu items need no extra code), the templates,
 * the node types to add, the nodes of the open graph and the recent files. Words match in any order; letters typed in
 * order match the initials ("tm" finds Toggle minimap). Commands used last come first.
 */
final class CommandPalette {

    /** Client property of a menu item: its plain label (menu items may carry HTML with their keys). */
    static final String LABEL = "mosaic.palette.label";

    record Entry(String label, String group, String keys, Runnable run) {
        String text() { return (label + " " + group).toLowerCase(Locale.ROOT); }
    }

    private CommandPalette() {}

    /** The palette shown last (the studio's screenshot aid captures it). */
    static JDialog last;

    static void open(StudioApp app, boolean nodesOnly) { open(app, nodesOnly, ""); }

    static void open(StudioApp app, boolean nodesOnly, String query) {
        List<Entry> all = nodesOnly ? nodes(app) : entries(app);
        Window owner = app.main;
        JDialog w = new JDialog(owner);
        w.setUndecorated(true);
        w.setModal(false);
        JPanel p = new JPanel(new BorderLayout(0, 6));
        p.setBackground(PANEL2);
        p.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(SELECT), new EmptyBorder(8, 8, 8, 8)));
        JLabel head = label(nodesOnly ? "Find a node: name, type or id  (Enter selects and centres it)"
            : "Command palette: commands, menus, templates, nodes to add, nodes of this graph, recent files", MUTED);
        head.setFont(UI_S);
        JTextField q = field(query);
        DefaultListModel<Entry> lm = new DefaultListModel<>();
        JList<Entry> list = new JList<>(lm);
        list.setBackground(PANEL);
        list.setForeground(TEXT);
        list.setSelectionBackground(StudioWidgets.mix(PANEL, SELECT, 0.35));
        list.setCellRenderer(new ListCellRenderer<>() {
            final JPanel row = new JPanel(new BorderLayout(12, 0));
            final JLabel left = new JLabel(), right = new JLabel();
            {
                row.setBorder(new EmptyBorder(3, 8, 3, 8));
                row.add(left, BorderLayout.CENTER);
                row.add(right, BorderLayout.EAST);
            }
            @Override public Component getListCellRendererComponent(JList<? extends Entry> l, Entry e, int i, boolean s, boolean f) {
                row.setBackground(s ? l.getSelectionBackground() : PANEL);
                left.setFont(UI);
                left.setForeground(TEXT);
                left.setText(e.label());
                right.setFont(UI_S);
                right.setForeground(FAINT);
                right.setText(e.keys().isEmpty() ? e.group() : e.group() + "   " + e.keys());
                return row;
            }
        });
        Runnable fill = () -> {
            lm.clear();
            for (Entry e : filter(all, q.getText(), app.settings.paletteRecent)) lm.addElement(e);
            if (!lm.isEmpty()) list.setSelectedIndex(0);
        };
        q.getDocument().addDocumentListener(onText(fill));
        fill.run();
        Runnable done = () -> {
            Entry e = list.getSelectedValue();
            w.dispose();
            if (e == null) return;
            if (!nodesOnly) {
                app.settings.paletteRecent.remove(e.label());
                app.settings.paletteRecent.add(0, e.label());
                while (app.settings.paletteRecent.size() > app.settings.paletteRecentMax) app.settings.paletteRecent.remove(app.settings.paletteRecent.size() - 1);
                app.saveSettings();
            }
            SwingUtilities.invokeLater(e.run());
        };
        q.addActionListener(ev -> done.run());
        q.addKeyListener(new KeyAdapter() {
            @Override public void keyPressed(KeyEvent e) {
                int i = list.getSelectedIndex(), n = lm.size();
                switch (e.getKeyCode()) {
                    case KeyEvent.VK_DOWN -> list.setSelectedIndex(Math.min(i + 1, n - 1));
                    case KeyEvent.VK_UP -> list.setSelectedIndex(Math.max(i - 1, 0));
                    case KeyEvent.VK_PAGE_DOWN -> list.setSelectedIndex(Math.min(i + 10, n - 1));
                    case KeyEvent.VK_PAGE_UP -> list.setSelectedIndex(Math.max(i - 10, 0));
                    case KeyEvent.VK_ESCAPE -> w.dispose();
                    default -> { return; }
                }
                list.ensureIndexIsVisible(list.getSelectedIndex());
                e.consume();
            }
        });
        list.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) { if (e.getClickCount() == 2) done.run(); }
        });
        w.addWindowFocusListener(new java.awt.event.WindowAdapter() {
            @Override public void windowLostFocus(java.awt.event.WindowEvent e) { w.dispose(); }
        });
        JScrollPane sp = new JScrollPane(list);
        sp.setBorder(BorderFactory.createLineBorder(EDGE));
        sp.setPreferredSize(new Dimension(640, 380));
        JPanel top = new JPanel(new BorderLayout(0, 4));
        top.setOpaque(false);
        top.add(head, BorderLayout.NORTH);
        top.add(q, BorderLayout.CENTER);
        p.add(top, BorderLayout.NORTH);
        p.add(sp, BorderLayout.CENTER);
        JLabel foot = label(all.size() + " entries   -   Enter runs, Esc closes, arrows choose", FAINT);
        foot.setFont(UI_S);
        p.add(foot, BorderLayout.SOUTH);
        w.setContentPane(p);
        w.pack();
        if (owner != null) {
            Rectangle b = owner.getBounds();
            w.setLocation(b.x + (b.width - w.getWidth()) / 2, b.y + Math.max(40, b.height / 8));
        }
        w.setVisible(true);
        last = w;
        q.requestFocusInWindow();
    }

    /** Entries ranked for the query: every word must match; recent commands first when the query is empty. */
    static List<Entry> filter(List<Entry> all, String query, List<String> recent) {
        String q = query.trim().toLowerCase(Locale.ROOT);
        List<Entry> out = new ArrayList<>();
        if (q.isEmpty()) {
            for (String r : recent) for (Entry e : all) if (e.label().equals(r) && !out.contains(e)) out.add(e);
            for (Entry e : all) if (!out.contains(e)) out.add(e);
            return out;
        }
        String[] words = q.split("\\s+");
        record Scored(Entry e, int score) {}
        List<Scored> s = new ArrayList<>();
        for (Entry e : all) {
            int sc = score(e, words, q);
            if (sc > 0) s.add(new Scored(e, sc + (recent.contains(e.label()) ? 40 - recent.indexOf(e.label()) : 0)));
        }
        s.sort((a, b) -> Integer.compare(b.score(), a.score()));
        for (Scored x : s) out.add(x.e());
        return out;
    }

    static int score(Entry e, String[] words, String whole) {
        String label = e.label().toLowerCase(Locale.ROOT), text = e.text();
        int sc = 0;
        boolean all = true;
        for (String w : words) {
            int at = text.indexOf(w);
            if (at < 0) {
                all = false;
                break;
            }
            sc += at == 0 ? 30 : label.contains(" " + w) || label.startsWith(w) ? 20 : at < label.length() ? 10 : 4;
        }
        if (all) return sc + (label.startsWith(whole) ? 50 : 0) + Math.max(0, 20 - label.length() / 4);
        // initials: "tm" -> "Toggle minimap"
        StringBuilder ini = new StringBuilder();
        for (String part : label.split("[^a-z0-9]+")) if (!part.isEmpty()) ini.append(part.charAt(0));
        String compact = whole.replace(" ", "");
        return compact.length() >= 2 && ini.indexOf(compact) >= 0 ? 15 : 0;
    }

    static List<Entry> entries(StudioApp app) {
        List<Entry> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (StudioKeys.Action a : StudioKeys.Action.values()) {
            if (a == StudioKeys.Action.COMMAND_PALETTE || a.group.equals("Move") || a.name().startsWith("NAV_") || a.name().startsWith("EXTEND_")
                || a.name().startsWith("PAN_")) continue;
            out.add(new Entry(a.label, a.group, app.model.keys.humanFor(a), () -> app.run(a)));
            seen.add(a.label);
        }
        if (app.menuBar != null)
            for (int i = 0; i < app.menuBar.getMenuCount(); i++) {
                JMenu m = app.menuBar.getMenu(i);
                if (m != null) menuItems(m, m.getText(), out, seen);
            }
        for (MosaicTemplates.Template t : MosaicTemplates.all())
            out.add(new Entry("New from template: " + t.name(), "Template - " + t.category(), "", () -> {
                app.model.loadTemplate(t);
                GraphPanel g = app.graph();
                if (g != null && app.settings.fitOnOpen) SwingUtilities.invokeLater(g::fit);
            }));
        for (String s : app.settings.recentFiles) {
            Path p = Path.of(s);
            if (Files.exists(p)) out.add(new Entry("Open recent: " + StudioApp.recentLabel(p), "File", "", () -> app.openFile(p)));
        }
        out.addAll(nodes(app));
        for (StudioModel.Item it : app.model.items())
            out.add(new Entry("Add node: " + it.label(), "Add - " + it.category(), "", () -> {
                GraphPanel g = app.graph();
                Point at = g == null ? new Point(0, 0) : g.viewCenter();
                app.model.add(it, at, at);
            }));
        return out;
    }

    private static void menuItems(JMenu m, String path, List<Entry> out, Set<String> seen) {
        // menus filled on opening (Open recent) are listed by the palette itself
        for (Component c : m.getMenuComponents()) {
            if (c instanceof JMenu sub) {
                menuItems(sub, path + " > " + sub.getText(), out, seen);
                continue;
            }
            if (!(c instanceof JMenuItem it) || !it.isEnabled()) continue;
            Object l = it.getClientProperty(LABEL);
            String label = l instanceof String s ? s : it.getText();
            if (label == null || label.isBlank() || label.startsWith("<html") || !seen.add(path + label)) continue;
            if (seen.contains(label)) continue; // a key action, listed with its keys already
            out.add(new Entry(label, path, "", it::doClick));
        }
    }

    /** The nodes of the open graph: Enter selects one and centres the view on it. */
    static List<Entry> nodes(StudioApp app) {
        List<Entry> out = new ArrayList<>();
        for (MosaicDocument.ENode n : app.model.doc.nodes) {
            String name = n.label != null ? n.label : n.id;
            out.add(new Entry("Go to node: " + name + (n.mask != null && n.type.equals("mosaic.pass") ? "  [buffer " + n.mask + "]" : ""),
                n.type + "  #" + n.id, "", () -> {
                    app.model.select(n);
                    GraphPanel g = app.graph();
                    if (g != null) {
                        g.center(n);
                        g.requestFocusInWindow();
                    }
                }));
        }
        return out;
    }
}
