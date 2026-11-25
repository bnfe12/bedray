package io.github.andrexarlay.vulkancompat.shaderhost.mosaic.studio;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static io.github.andrexarlay.vulkancompat.shaderhost.mosaic.studio.StudioUi.*;

/** Photoshop-like document tabs under the title bar: one shader graph, a block texture, another texture... each in its own tab. */
final class DocTabs extends JComponent {

    /** One open document: its kind (which workspace shows it), file, undo history, material base and graph view. */
    static final class Tab {
        StudioModel.Workspace kind;
        Path file;
        List<List<String>> history;
        String srcId, entry;
        double[] view;
        long used = System.nanoTime();

        Tab(StudioModel.Workspace kind, Path file) {
            this.kind = kind;
            this.file = file;
        }
    }

    private final StudioApp app;
    private final List<Rectangle> boxes = new ArrayList<>(), closes = new ArrayList<>();
    private Rectangle plus = new Rectangle();
    private int hover = -1, hoverClose = -1;
    private boolean hoverPlus;

    DocTabs(StudioApp app) {
        this.app = app;
        setFont(UI_S);
        MouseAdapter m = new MouseAdapter() {
            @Override public void mouseMoved(MouseEvent e) {
                int h = -1, hc = -1;
                for (int i = 0; i < boxes.size(); i++) if (boxes.get(i).contains(e.getPoint())) h = i;
                for (int i = 0; i < closes.size(); i++) if (closes.get(i).contains(e.getPoint())) hc = i;
                boolean hp = plus.contains(e.getPoint());
                if (h != hover || hc != hoverClose || hp != hoverPlus) {
                    hover = h;
                    hoverClose = hc;
                    hoverPlus = hp;
                    setToolTipText(h >= 0 && h < app.tabs.size() ? tip(app.tabs.get(h)) : hp ? "New tab" : null);
                    repaint();
                }
            }
            @Override public void mouseExited(MouseEvent e) {
                hover = hoverClose = -1;
                hoverPlus = false;
                repaint();
            }
            @Override public void mousePressed(MouseEvent e) {
                if (plus.contains(e.getPoint())) {
                    newMenu().show(DocTabs.this, plus.x, plus.y + plus.height);
                    return;
                }
                for (int i = 0; i < boxes.size() && i < app.tabs.size(); i++) {
                    if (!boxes.get(i).contains(e.getPoint())) continue;
                    DocTabs.Tab t = app.tabs.get(i);
                    if (SwingUtilities.isMiddleMouseButton(e) || closes.get(i).contains(e.getPoint())) app.closeTab(t);
                    else app.activate(t);
                    return;
                }
            }
        };
        addMouseListener(m);
        addMouseMotionListener(m);
    }

    private JPopupMenu newMenu() {
        JPopupMenu p = new JPopupMenu();
        JMenuItem s = new JMenuItem("New shader graph", StudioWidgets.glyph("shader", new Color(0x5AA0E0)));
        s.addActionListener(e -> app.newTab(StudioModel.Workspace.SHADERS));
        JMenuItem m = new JMenuItem("New material (pick a texture in the Library)", StudioWidgets.glyph("vanilla", new Color(0xD08A4A)));
        m.addActionListener(e -> app.newTab(StudioModel.Workspace.MATERIALS));
        JMenuItem r = new JMenuItem("Bedray renderer graph", StudioWidgets.glyph("chip", new Color(0x8A9CF0)));
        r.addActionListener(e -> app.switchWorkspace(StudioModel.Workspace.RENDERER));
        JMenuItem k = new JMenuItem("Bedray pack map (every Slang module)", StudioWidgets.glyph("frame", new Color(0x8A9CF0)));
        k.addActionListener(e -> app.openPackMapTab());
        JMenuItem fn = new JMenuItem("Bedray function as nodes...", StudioWidgets.glyph("chip", new Color(0x3D6FB8)));
        fn.addActionListener(e -> app.pickFunction(this, new java.awt.Point(Math.max(0, getWidth() - 420), getHeight())));
        p.add(s);
        p.add(m);
        p.add(r);
        p.add(k);
        p.add(fn);
        return p;
    }

    String title(Tab t) {
        Path f = t == app.active ? app.model.doc.file : t.file;
        if (t.kind == StudioModel.Workspace.RENDERER) {
            if (f != null && f.getParent() != null && f.getParent().equals(StudioModel.functionsDir())) {
                String id = f.getFileName().toString().replace(".mosaic.json", "");
                String name = id.replaceAll("\\(.*", "");
                return "ƒ " + name.substring(name.lastIndexOf('_') + 1);
            }
            return f != null && f.getFileName().toString().startsWith("bedray-pack") ? "Bedray pack (map)" : "Bedray renderer";
        }
        if (f == null) return t.kind == StudioModel.Workspace.MATERIALS ? "New material" : "Untitled graph";
        String n = f.getFileName().toString();
        if (t.kind == StudioModel.Workspace.MATERIALS) return n.replace(".mosaic.json", "");
        return n.equals("mosaic.json") ? StudioApp.packName(f) : n;
    }

    String tip(Tab t) {
        Path f = t == app.active ? app.model.doc.file : t.file;
        return (t.kind == StudioModel.Workspace.MATERIALS ? "Material" : "Shader graph") + (f == null ? " (not saved)" : ": " + f);
    }

    @Override public Dimension getPreferredSize() { return new Dimension(100, 28); }

    @Override protected void paintComponent(Graphics g0) {
        Graphics2D g = StudioWidgets.smooth(g0);
        int h = getHeight();
        g.setColor(StudioWidgets.mix(PANEL2, BG, 0.5));
        g.fillRect(0, 0, getWidth(), h);
        g.setColor(EDGE);
        g.drawLine(0, h - 1, getWidth(), h - 1);
        g.setFont(getFont());
        FontMetrics fm = g.getFontMetrics();
        boxes.clear();
        closes.clear();
        int x = 6;
        for (int i = 0; i < app.tabs.size(); i++) {
            Tab t = app.tabs.get(i);
            boolean active = t == app.active, dirty = active && app.model.doc.dirty;
            String title = (dirty ? "• " : "") + title(t);
            int w = Math.min(220, 14 + 14 + 6 + fm.stringWidth(title) + 22);
            Rectangle r = new Rectangle(x, 3, w, h - 3);
            boxes.add(r);
            if (active) {
                g.setColor(BG);
                g.fillRoundRect(r.x, r.y, r.width, r.height + 8, 10, 10);
                g.setColor(SELECT);
                g.fillRoundRect(r.x + 6, r.y, r.width - 12, 2, 2, 2);
            } else if (i == hover) {
                g.setColor(StudioWidgets.mix(PANEL2, TEXT, 0.06));
                g.fillRoundRect(r.x, r.y + 2, r.width, r.height + 8, 10, 10);
            }
            Icon icon = t.kind == StudioModel.Workspace.RENDERER ? StudioWidgets.glyph("chip", new Color(0x8A9CF0))
                : StudioWidgets.glyph(t.kind == StudioModel.Workspace.MATERIALS ? "vanilla" : "shader",
                t.kind == StudioModel.Workspace.MATERIALS ? new Color(0xD08A4A) : new Color(0x5AA0E0));
            icon.paintIcon(this, g, r.x + 9, r.y + (r.height - 14) / 2);
            g.setColor(active ? TEXT : MUTED);
            String shown = StudioWidgets.Segmented.fit(title, fm, r.width - 14 - 14 - 6 - 22);
            g.drawString(shown, r.x + 28, r.y + (r.height + fm.getAscent() - fm.getDescent()) / 2);
            Rectangle c = new Rectangle(r.x + r.width - 20, r.y + (r.height - 14) / 2, 14, 14);
            closes.add(c);
            if (active || i == hover) {
                if (i == hoverClose) {
                    g.setColor(StudioWidgets.mix(PANEL2, BAD, 0.6));
                    g.fillRoundRect(c.x, c.y, c.width, c.height, 6, 6);
                }
                g.setColor(i == hoverClose ? Color.WHITE : MUTED);
                g.setStroke(new BasicStroke(1.3f));
                g.drawLine(c.x + 4, c.y + 4, c.x + 10, c.y + 10);
                g.drawLine(c.x + 10, c.y + 4, c.x + 4, c.y + 10);
            }
            x += w + 2;
        }
        plus = new Rectangle(x + 4, 5, 22, h - 9);
        if (hoverPlus) {
            g.setColor(StudioWidgets.mix(PANEL2, TEXT, 0.1));
            g.fillRoundRect(plus.x, plus.y, plus.width, plus.height, 8, 8);
        }
        g.setColor(hoverPlus ? TEXT : MUTED);
        g.setStroke(new BasicStroke(1.5f));
        int cx = plus.x + plus.width / 2, cy = plus.y + plus.height / 2;
        g.drawLine(cx - 5, cy, cx + 5, cy);
        g.drawLine(cx, cy - 5, cx, cy + 5);
        g.dispose();
    }
}
