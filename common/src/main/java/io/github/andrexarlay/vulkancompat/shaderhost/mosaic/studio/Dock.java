package io.github.andrexarlay.vulkancompat.shaderhost.mosaic.studio;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

import static io.github.andrexarlay.vulkancompat.shaderhost.mosaic.studio.StudioUi.*;

/**
 * Blender-like areas. Every window holds a tree of areas; an area shows one editor ({@link Panels.Type}) chosen in its
 * header, and can be split side by side or top/bottom, resized by its divider, maximized (Ctrl+Space), closed, or
 * popped out into its own window and docked back. Windows are independent of each other; the layout of all windows
 * is saved in the settings.
 */
final class Dock {

    private Dock() {}

    // ================================================================== area

    static final class Area extends JPanel {
        final StudioModel model;
        Panels.Type type;
        Panels.Panel panel;
        final JButton chooser = new JButton();
        final JPanel header = new JPanel(new BorderLayout());

        Area(StudioModel model, Panels.Type type) {
            super(new BorderLayout());
            this.model = model;
            setBackground(PANEL);
            setBorder(BorderFactory.createLineBorder(EDGE));
            header.setBackground(HEADER);
            header.setBorder(new EmptyBorder(1, 4, 1, 2));
            chooser.setFont(UI_B.deriveFont(UI_S.getSize2D()));
            chooser.setForeground(TEXT);
            chooser.setFocusable(false);
            chooser.setContentAreaFilled(false);
            chooser.setBorder(new EmptyBorder(2, 4, 2, 8));
            chooser.setIconTextGap(6);
            chooser.setToolTipText("Change the editor in this area (" + model.keys.humanFor(StudioKeys.Action.CYCLE_EDITOR) + ")");
            chooser.addActionListener(e -> editorMenu().show(chooser, 0, chooser.getHeight()));
            chooser.addMouseListener(new MouseAdapter() {
                @Override public void mouseEntered(MouseEvent e) { chooser.setForeground(SELECT); }
                @Override public void mouseExited(MouseEvent e) { chooser.setForeground(TEXT); }
            });
            JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
            left.setOpaque(false);
            left.add(chooser);
            JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
            buttons.setOpaque(false);
            buttons.add(iconButton("splitH", "Split side by side (" + model.keys.humanFor(StudioKeys.Action.SPLIT_RIGHT) + ")", () -> split(this, true)));
            buttons.add(iconButton("splitV", "Split top / bottom (" + model.keys.humanFor(StudioKeys.Action.SPLIT_DOWN) + ")", () -> split(this, false)));
            buttons.add(iconButton("max", "Maximize / restore (" + model.keys.humanFor(StudioKeys.Action.MAXIMIZE_AREA) + ")", () -> maximize(this)));
            buttons.add(iconButton("detach", "Pop out into a window (" + model.keys.humanFor(StudioKeys.Action.DETACH_AREA) + ")", () -> detach(this)));
            buttons.add(iconButton("close", "Close area (" + model.keys.humanFor(StudioKeys.Action.CLOSE_AREA) + ")", () -> close(this)));
            header.add(left, BorderLayout.WEST);
            header.add(buttons, BorderLayout.EAST);
            add(header, BorderLayout.NORTH);
            show(type);
        }

        void show(Panels.Type t) {
            if (panel != null) {
                remove(panel.component());
                panel.dispose();
            }
            type = t;
            panel = t.create(model);
            add(panel.component(), BorderLayout.CENTER);
            chooser.setText(t.label);
            chooser.setIcon(Icons.panel(t, 16));
            revalidate();
            repaint();
        }

        void dispose() {
            if (panel != null) panel.dispose();
        }

        /** Blender-style editor menu: icons, groups, a line of help each; the current editor is marked. */
        JPopupMenu editorMenu() {
            JPopupMenu m = new JPopupMenu();
            m.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(EDGE), new EmptyBorder(4, 0, 4, 0)));
            String group = null;
            for (Panels.Type t : Panels.Type.values()) {
                if (!t.group.equals(group)) {
                    if (group != null) m.addSeparator();
                    group = t.group;
                    JLabel h = new JLabel(group.toUpperCase(java.util.Locale.ROOT));
                    h.setFont(UI_B.deriveFont(UI_S.getSize2D() - 1));
                    h.setForeground(FAINT);
                    h.setBorder(new EmptyBorder(4, 12, 2, 12));
                    m.add(h);
                }
                JMenuItem i = new JMenuItem("<html><b>" + t.label + "</b><br><span style='color:#8B93A8'>" + t.help + "</span></html>",
                    Icons.panel(t, 22));
                i.setIconTextGap(10);
                i.setFont(UI_S);
                i.setBorder(new EmptyBorder(4, 10, 4, 18));
                if (t == type) {
                    i.setBackground(SELECT.darker().darker());
                    i.setOpaque(true);
                }
                i.addActionListener(e -> {
                    show(t);
                    panel.focus();
                });
                m.add(i);
            }
            return m;
        }
    }

    // ================================================================== tree operations

    /** Replaces a child inside its parent (a split pane or a window root) keeping the divider position. */
    static void replace(Component old, Component fresh) {
        Container parent = old.getParent();
        if (parent instanceof JSplitPane sp) {
            int loc = sp.getDividerLocation();
            if (sp.getLeftComponent() == old) sp.setLeftComponent(fresh);
            else sp.setRightComponent(fresh);
            sp.setDividerLocation(loc);
        } else if (parent != null) {
            parent.remove(old);
            parent.add(fresh, BorderLayout.CENTER);
        }
        parent.revalidate();
        parent.repaint();
    }

    static JSplitPane splitPane(boolean sideBySide, Component a, Component b, double ratio) {
        JSplitPane sp = new JSplitPane(sideBySide ? JSplitPane.HORIZONTAL_SPLIT : JSplitPane.VERTICAL_SPLIT, a, b);
        flatDivider(sp);
        sp.setResizeWeight(ratio);
        // the ratio needs a size: apply it when the pane first gets one (a tree put into a window that is already shown
        // is laid out after this call; setting it earlier would pin the divider at the edge)
        sp.addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override public void componentResized(java.awt.event.ComponentEvent e) {
                if (sp.getWidth() <= 0 || sp.getHeight() <= 0) return;
                sp.removeComponentListener(this);
                sp.setDividerLocation(ratio);
            }
        });
        SwingUtilities.invokeLater(() -> {
            if (sp.getWidth() > 0 && sp.getHeight() > 0) sp.setDividerLocation(ratio);
        });
        return sp;
    }

    static void split(Area a, boolean sideBySide) {
        Area fresh = new Area(a.model, a.type == Panels.Type.GRAPH ? Panels.Type.RESULT : Panels.Type.GRAPH);
        JPanel placeholder = new JPanel();
        replace(a, placeholder);
        replace(placeholder, splitPane(sideBySide, a, fresh, 0.5));
    }

    static void close(Area a) {
        Container parent = a.getParent();
        if (parent instanceof JSplitPane sp) {
            Component sibling = sp.getLeftComponent() == a ? sp.getRightComponent() : sp.getLeftComponent();
            sp.remove(sibling);
            replace(sp, sibling);
            a.dispose();
        } else {
            StudioWindow w = (StudioWindow) SwingUtilities.getWindowAncestor(a);
            if (w != null && !w.main) {
                a.dispose();
                w.dispose();
                StudioApp.get().windows.remove(w);
            } else {
                StudioApp.get().model.say("The last area of the main window stays open", MUTED);
            }
        }
    }

    static void detach(Area a) {
        StudioWindow from = (StudioWindow) SwingUtilities.getWindowAncestor(a);
        Container parent = a.getParent();
        Point onScreen = a.getLocationOnScreen();
        Dimension size = a.getSize();
        if (parent instanceof JSplitPane sp) {
            Component sibling = sp.getLeftComponent() == a ? sp.getRightComponent() : sp.getLeftComponent();
            sp.remove(sibling);
            sp.remove(a);
            replace(sp, sibling);
        } else if (from != null && !from.main) {
            return; // already alone in its own window
        } else {
            StudioApp.get().model.say("The last area of the main window cannot leave it", MUTED);
            return;
        }
        StudioWindow w = StudioApp.get().newWindow(false, a);
        w.setBounds(onScreen.x + 30, onScreen.y + 30, Math.max(360, size.width), Math.max(260, size.height));
        w.setVisible(true);
    }

    /** Moves everything in a detached window back into the main window, to the right of what is there. */
    static void dockBack(StudioWindow w) {
        StudioWindow main = StudioApp.get().main;
        Component content = w.root.getComponentCount() == 0 ? null : w.root.getComponent(0);
        if (content == null) return;
        w.root.remove(content);
        Component mainContent = main.root.getComponent(0);
        main.root.remove(mainContent);
        main.root.add(splitPane(true, mainContent, content, 0.72), BorderLayout.CENTER);
        main.root.revalidate();
        StudioApp.get().windows.remove(w);
        w.dispose();
    }

    static void maximize(Area a) {
        StudioWindow w = (StudioWindow) SwingUtilities.getWindowAncestor(a);
        if (w == null) return;
        if (w.maximized != null) {
            w.root.removeAll();
            Component tree = w.maximized;
            w.maximized = null;
            replace(w.maxPlaceholder, a);
            w.root.add(tree, BorderLayout.CENTER);
        } else {
            Component tree = w.root.getComponent(0);
            if (tree == a) return;
            w.maxPlaceholder = new JPanel();
            replace(a, w.maxPlaceholder);
            w.root.removeAll();
            w.maximized = tree;
            w.root.add(a, BorderLayout.CENTER);
        }
        w.root.revalidate();
        w.root.repaint();
        a.panel.focus();
    }

    static List<Area> areas(Container c) {
        List<Area> out = new ArrayList<>();
        collect(c, out);
        return out;
    }

    private static void collect(Component c, List<Area> out) {
        if (c instanceof Area a) {
            out.add(a);
            return;
        }
        if (c instanceof JSplitPane sp) {
            collect(sp.getLeftComponent(), out);
            collect(sp.getRightComponent(), out);
        } else if (c instanceof Container ct) {
            for (Component k : ct.getComponents()) collect(k, out);
        }
    }

    static Area areaOf(Component c) {
        while (c != null && !(c instanceof Area)) c = c.getParent();
        return (Area) c;
    }

    // ================================================================== layout persistence

    static JsonObject save(Component c) {
        JsonObject o = new JsonObject();
        if (c instanceof Area a) {
            o.addProperty("panel", a.type.name());
        } else if (c instanceof JSplitPane sp) {
            o.addProperty("split", sp.getOrientation() == JSplitPane.HORIZONTAL_SPLIT ? "side" : "stack");
            int total = sp.getOrientation() == JSplitPane.HORIZONTAL_SPLIT ? sp.getWidth() : sp.getHeight();
            o.addProperty("ratio", total <= 0 ? 0.5 : Math.clamp(sp.getDividerLocation() / (double) total, 0.05, 0.95));
            o.add("a", save(sp.getLeftComponent()));
            o.add("b", save(sp.getRightComponent()));
        }
        return o;
    }

    static Component load(StudioModel model, JsonObject o) {
        if (o.has("panel")) {
            Panels.Type t;
            try {
                t = Panels.Type.valueOf(o.get("panel").getAsString());
            } catch (IllegalArgumentException e) {
                t = Panels.Type.GRAPH;
            }
            return new Area(model, t);
        }
        return splitPane(o.get("split").getAsString().equals("side"), load(model, o.getAsJsonObject("a")),
            load(model, o.getAsJsonObject("b")), o.get("ratio").getAsDouble());
    }

    /** Palette | (graph over problems+history) | (result over properties). */
    static String defaultLayout() {
        return """
            {"windows": [{"main": true, "tree":
              {"split": "side", "ratio": 0.16, "a": {"split": "stack", "ratio": 0.6, "a": {"panel": "PALETTE"}, "b": {"panel": "BROWSER"}},
               "b": {"split": "side", "ratio": 0.68,
                 "a": {"split": "stack", "ratio": 0.74, "a": {"panel": "GRAPH"},
                        "b": {"split": "side", "ratio": 0.6, "a": {"panel": "PROBLEMS"}, "b": {"panel": "HISTORY"}}},
                 "b": {"split": "stack", "ratio": 0.6, "a": {"split": "stack", "ratio": 0.5, "a": {"panel": "PREVIEW"}, "b": {"panel": "RESULT"}},
                        "b": {"panel": "INSPECTOR"}}}}}]}""";
    }

    /** Materials: library on the left, graph in the middle, the lit block and properties on the right. */
    static String defaultMaterialLayout() {
        return """
            {"windows": [{"main": true, "tree":
              {"split": "side", "ratio": 0.2, "a": {"split": "stack", "ratio": 0.62, "a": {"panel": "LIBRARY"}, "b": {"panel": "PALETTE"}},
               "b": {"split": "side", "ratio": 0.64,
                 "a": {"split": "stack", "ratio": 0.74, "a": {"panel": "GRAPH"},
                        "b": {"split": "side", "ratio": 0.6, "a": {"panel": "PROBLEMS"}, "b": {"panel": "HISTORY"}}},
                 "b": {"split": "stack", "ratio": 0.55, "a": {"panel": "MATERIAL"}, "b": {"panel": "INSPECTOR"}}}}}]}""";
    }

    /** Renderer: stages on the left, the pipeline graph in the middle, the stage's settings on the right. */
    static String defaultRendererLayout() {
        return """
            {"windows": [{"main": true, "tree":
              {"split": "side", "ratio": 0.16, "a": {"panel": "PALETTE"},
               "b": {"split": "side", "ratio": 0.62,
                 "a": {"split": "stack", "ratio": 0.72, "a": {"panel": "GRAPH"},
                        "b": {"split": "side", "ratio": 0.6, "a": {"panel": "PROBLEMS"}, "b": {"panel": "HISTORY"}}},
                 "b": {"split": "stack", "ratio": 0.4, "a": {"panel": "RESULT"}, "b": {"panel": "INSPECTOR"}}}}}]}""";
    }

    static String saveAll(List<StudioWindow> windows) {
        JsonObject root = new JsonObject();
        JsonArray arr = new JsonArray();
        for (StudioWindow w : windows) {
            if (w.root.getComponentCount() == 0) continue;
            JsonObject o = new JsonObject();
            o.addProperty("main", w.main);
            Component tree = w.maximized != null ? w.maximized : w.root.getComponent(0);
            o.add("tree", save(tree));
            Rectangle b = w.getBounds();
            o.addProperty("x", b.x);
            o.addProperty("y", b.y);
            o.addProperty("w", b.width);
            o.addProperty("h", b.height);
            o.addProperty("maximized", (w.getExtendedState() & Frame.MAXIMIZED_BOTH) != 0);
            arr.add(o);
        }
        root.add("windows", arr);
        return root.toString();
    }

    static JsonArray parseWindows(String json) {
        try {
            return JsonParser.parseString(json).getAsJsonObject().getAsJsonArray("windows");
        } catch (Exception e) {
            return JsonParser.parseString(defaultLayout()).getAsJsonObject().getAsJsonArray("windows");
        }
    }

    // ================================================================== windows

    /**
     * An undecorated window with a compact title bar in the theme colors (drag to move, double-click to maximize,
     * edges to resize). The main window also has the tool bar and status line; pop-out windows have a Dock back button.
     */
    static class StudioWindow extends JFrame {
        final boolean main, dockable;
        final JPanel root = new JPanel(new BorderLayout());
        Component maximized, maxPlaceholder;
        final JLabel titleLabel = new JLabel();
        /** Menus (main window) and quick buttons live inside the title bar: one narrow strip. */
        final JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        final JPanel menuSlot = new JPanel(new BorderLayout());
        final JPanel quickSlot = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        private Point dragOffset;
        /** Holders for the window buttons: left for the macOS style, right for the Mosaic style. */
        final JPanel leftChrome = new JPanel(new BorderLayout()), rightChrome = new JPanel(new BorderLayout());

        /** (Re)creates the window buttons for the current style setting. */
        void rebuildChrome() {
            leftChrome.removeAll();
            rightChrome.removeAll();
            StudioWidgets.WindowButtons wb = new StudioWidgets.WindowButtons(this, this::toggleMaximize, this::closeWindow);
            (StudioWidgets.macStyle() ? leftChrome : rightChrome).add(wb, BorderLayout.CENTER);
            leftChrome.revalidate();
            rightChrome.revalidate();
            leftChrome.repaint();
            rightChrome.repaint();
        }

        void closeWindow() {
            if (main) StudioApp.get().requestClose();
            else if (!dockable) dispose();
            else {
                for (Area a : areas(root)) a.dispose();
                StudioApp.get().windows.remove(this);
                dispose();
            }
        }
        private BufferedImage fade;
        private float fadeAlpha;

        StudioWindow(boolean main, String title) { this(main, !main, title); }

        StudioWindow(boolean main, boolean dockable, String title) {
            super(title);
            this.main = main;
            this.dockable = dockable;
            setUndecorated(true);
            setIconImage(StudioApp.appIcon());
            JPanel frame = new JPanel(new BorderLayout());
            frame.setBackground(EDGE);
            frame.setBorder(BorderFactory.createLineBorder(EDGE, 1));
            frame.add(titleBar(title), BorderLayout.NORTH);
            root.setBackground(BG);
            frame.add(root, BorderLayout.CENTER);
            setContentPane(frame);
            Resizer.install(this);
            root.setCursor(Cursor.getDefaultCursor());
            addWindowStateListener(e -> repaint());
            addWindowFocusListener(new java.awt.event.WindowAdapter() {
                @Override public void windowGainedFocus(java.awt.event.WindowEvent e) { repaint(); }
                @Override public void windowLostFocus(java.awt.event.WindowEvent e) { repaint(); }
            });
            setGlassPane(new JComponent() {
                @Override protected void paintComponent(Graphics g) {
                    if (fade == null) return;
                    Graphics2D g2 = (Graphics2D) g.create();
                    g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, Math.clamp(fadeAlpha, 0f, 1f)));
                    g2.drawImage(fade, 0, 0, null);
                    g2.dispose();
                }
            });
        }

        private JComponent titleBar(String title) {
            JPanel bar = new JPanel(new BorderLayout());
            bar.setBackground(PANEL2);
            bar.setBorder(new EmptyBorder(0, 8, 0, 0));
            bar.setPreferredSize(new Dimension(10, Math.round(24 * settings.fontPercent / 100f)));
            titleLabel.setText(title);
            titleLabel.setFont(UI_S);
            titleLabel.setForeground(MUTED);
            left.setOpaque(false);
            JLabel logo = new JLabel(new ImageIcon(StudioApp.appIcon().getScaledInstance(14, 14, Image.SCALE_SMOOTH)));
            logo.setBorder(new EmptyBorder(0, 2, 0, 6));
            left.add(logo);
            left.add(menuSlot);
            bar.add(left, BorderLayout.WEST);
            bar.setCursor(Cursor.getDefaultCursor());
            titleLabel.setHorizontalAlignment(SwingConstants.CENTER);
            bar.add(titleLabel, BorderLayout.CENTER);
            quickSlot.setOpaque(false);
            JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
            buttons.setOpaque(false);
            buttons.add(quickSlot);
            if (dockable) buttons.add(chrome(null, "Dock back into the main window", () -> dockBack(this)));
            buttons.add(rightChrome);
            rightChrome.setOpaque(false);
            leftChrome.setOpaque(false);
            left.add(leftChrome, 0);
            rebuildChrome();
            bar.add(buttons, BorderLayout.EAST);
            MouseAdapter drag = new MouseAdapter() {
                @Override public void mousePressed(MouseEvent e) { dragOffset = e.getPoint(); }
                @Override public void mouseDragged(MouseEvent e) {
                    if ((getExtendedState() & Frame.MAXIMIZED_BOTH) != 0) {
                        // pulling a maximized window down restores it under the cursor
                        double rel = e.getX() / (double) Math.max(1, getWidth());
                        toggleMaximize();
                        dragOffset = new Point((int) (getWidth() * rel), dragOffset.y);
                    }
                    Point p = e.getLocationOnScreen();
                    setLocation(p.x - dragOffset.x, p.y - dragOffset.y);
                }
                @Override public void mouseClicked(MouseEvent e) {
                    if (e.getClickCount() == 2) toggleMaximize();
                }
                /** Windows-style snapping: drop on the top edge maximizes, on a side edge fills that half. */
                @Override public void mouseReleased(MouseEvent e) {
                    if ((getExtendedState() & Frame.MAXIMIZED_BOTH) != 0) return;
                    Point p = e.getLocationOnScreen();
                    GraphicsConfiguration gc = getGraphicsConfiguration();
                    Rectangle screen = gc.getBounds();
                    Insets in = Toolkit.getDefaultToolkit().getScreenInsets(gc);
                    Rectangle work = new Rectangle(screen.x + in.left, screen.y + in.top, screen.width - in.left - in.right,
                        screen.height - in.top - in.bottom);
                    if (p.y <= screen.y + 2) toggleMaximize();
                    else if (p.x <= screen.x + 2) setBounds(work.x, work.y, work.width / 2, work.height);
                    else if (p.x >= screen.x + screen.width - 3) setBounds(work.x + work.width / 2, work.y, work.width - work.width / 2, work.height);
                }
            };
            bar.addMouseListener(drag);
            bar.addMouseMotionListener(drag);
            left.addMouseListener(drag);
            left.addMouseMotionListener(drag);
            titleLabel.addMouseListener(drag);
            titleLabel.addMouseMotionListener(drag);
            return bar;
        }

        /** Title-bar button: a painted icon (min / max / close) or, with no icon, a "Dock back" text button. */
        private JButton chrome(String iconKind, String tip, Runnable r) {
            JButton b = iconKind == null ? new JButton("Dock back") : new JButton(new Icon() {
                public int getIconWidth() { return 12; }
                public int getIconHeight() { return 12; }
                public void paintIcon(Component c, Graphics g0, int x, int y) {
                    Graphics2D g = (Graphics2D) g0.create();
                    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    g.setColor(TEXT);
                    g.setStroke(new BasicStroke(1.2f));
                    g.translate(x, y);
                    switch (iconKind) {
                        case "min" -> g.drawLine(1, 6, 11, 6);
                        case "max" -> g.drawRect(1, 1, 10, 10);
                        default -> { g.drawLine(1, 1, 11, 11); g.drawLine(11, 1, 1, 11); }
                    }
                    g.dispose();
                }
            });
            b.setToolTipText(tip);
            b.setFont(UI_S);
            b.setForeground(TEXT);
            b.setFocusable(false);
            b.setContentAreaFilled(false);
            b.setOpaque(false);
            b.setBackground(BUTTON);
            b.setBorder(new EmptyBorder(2, 14, 2, 14));
            b.addActionListener(e -> r.run());
            if (!"close".equals(iconKind)) b.addMouseListener(new MouseAdapter() {
                @Override public void mouseEntered(MouseEvent e) { b.setOpaque(true); b.repaint(); }
                @Override public void mouseExited(MouseEvent e) { b.setOpaque(false); b.repaint(); }
            });
            return b;
        }

        void toggleMaximize() {
            if ((getExtendedState() & Frame.MAXIMIZED_BOTH) != 0) setExtendedState(Frame.NORMAL);
            else {
                setMaximizedBounds(GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds());
                setExtendedState(Frame.MAXIMIZED_BOTH);
            }
        }

        /** Captures the current look so a restyle can cross-fade from it. */
        BufferedImage snapshot() {
            if (!isShowing() || getWidth() <= 0) return null;
            BufferedImage img = new BufferedImage(getWidth(), getHeight(), BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = img.createGraphics();
            getRootPane().paint(g);
            g.dispose();
            return img;
        }

        /** Fades the captured old look out over the freshly styled window (no flash). */
        void fadeFrom(BufferedImage old) {
            if (old == null) return;
            fade = old;
            fadeAlpha = 1f;
            getGlassPane().setVisible(true);
            long start = System.nanoTime();
            Timer t = new Timer(15, null);
            t.addActionListener(e -> {
                float p = (System.nanoTime() - start) / 260_000_000f;
                fadeAlpha = 1f - (p * p * (3 - 2 * p)); // smoothstep
                getGlassPane().repaint();
                if (p >= 1f) {
                    fade = null;
                    getGlassPane().setVisible(false);
                    t.stop();
                }
            });
            t.start();
        }
    }

    /** Resizing an undecorated window from its edges and corners. */
    static final class Resizer extends MouseAdapter {
        private static final int EDGE_PX = 6;
        private final JFrame frame;
        private int edges;
        private Point start;
        private Rectangle startBounds;

        static void install(JFrame f) {
            Resizer r = new Resizer(f);
            Component c = f.getContentPane();
            c.addMouseListener(r);
            c.addMouseMotionListener(r);
            ((JComponent) c).setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(EDGE, 1), new EmptyBorder(EDGE_PX - 1, EDGE_PX - 1, EDGE_PX - 1, EDGE_PX - 1)));
        }

        private Resizer(JFrame f) { frame = f; }

        private int edgesAt(Point p) {
            int e = 0, w = frame.getContentPane().getWidth(), h = frame.getContentPane().getHeight();
            if (p.x < EDGE_PX) e |= 1;
            if (p.x >= w - EDGE_PX) e |= 2;
            if (p.y < EDGE_PX) e |= 4;
            if (p.y >= h - EDGE_PX) e |= 8;
            return e;
        }

        @Override public void mouseMoved(MouseEvent e) {
            int ed = edgesAt(e.getPoint());
            int cursor = switch (ed) {
                case 1 -> Cursor.W_RESIZE_CURSOR;
                case 2 -> Cursor.E_RESIZE_CURSOR;
                case 4 -> Cursor.N_RESIZE_CURSOR;
                case 8 -> Cursor.S_RESIZE_CURSOR;
                case 5 -> Cursor.NW_RESIZE_CURSOR;
                case 6 -> Cursor.NE_RESIZE_CURSOR;
                case 9 -> Cursor.SW_RESIZE_CURSOR;
                case 10 -> Cursor.SE_RESIZE_CURSOR;
                default -> Cursor.DEFAULT_CURSOR;
            };
            frame.getContentPane().setCursor(Cursor.getPredefinedCursor(cursor));
        }

        @Override public void mouseExited(MouseEvent e) {
            if (edges == 0) frame.getContentPane().setCursor(Cursor.getDefaultCursor());
        }

        @Override public void mouseReleased(MouseEvent e) {
            edges = 0;
            mouseMoved(e);
        }

        @Override public void mousePressed(MouseEvent e) {
            edges = (frame.getExtendedState() & Frame.MAXIMIZED_BOTH) != 0 ? 0 : edgesAt(e.getPoint());
            start = e.getLocationOnScreen();
            startBounds = frame.getBounds();
        }

        @Override public void mouseDragged(MouseEvent e) {
            if (edges == 0) return;
            Point p = e.getLocationOnScreen();
            int dx = p.x - start.x, dy = p.y - start.y;
            Rectangle b = new Rectangle(startBounds);
            if ((edges & 1) != 0) { b.x += dx; b.width -= dx; }
            if ((edges & 2) != 0) b.width += dx;
            if ((edges & 4) != 0) { b.y += dy; b.height -= dy; }
            if ((edges & 8) != 0) b.height += dy;
            if (b.width >= 320 && b.height >= 220) frame.setBounds(b);
        }
    }
}
