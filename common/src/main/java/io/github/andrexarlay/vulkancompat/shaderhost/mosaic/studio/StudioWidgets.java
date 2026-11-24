package io.github.andrexarlay.vulkancompat.shaderhost.mosaic.studio;

import javax.swing.*;
import javax.swing.plaf.ComponentUI;
import javax.swing.plaf.basic.BasicComboBoxUI;
import javax.swing.plaf.basic.BasicComboPopup;
import javax.swing.plaf.basic.ComboPopup;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static io.github.andrexarlay.vulkancompat.shaderhost.mosaic.studio.StudioUi.*;

/** Studio's own controls, painted in the theme colours so they look the same on Windows, macOS and Linux. */
final class StudioWidgets {

    private StudioWidgets() {}

    static Graphics2D smooth(Graphics g0) {
        Graphics2D g = (Graphics2D) g0.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        return g;
    }

    static Color mix(Color a, Color b, double t) {
        return new Color((int) (a.getRed() + (b.getRed() - a.getRed()) * t), (int) (a.getGreen() + (b.getGreen() - a.getGreen()) * t),
            (int) (a.getBlue() + (b.getBlue() - a.getBlue()) * t));
    }

    // ------------------------------------------------------------------ check box

    /** Rounded box; checked = accent fill with a white tick; hover brightens the outline. */
    static final class CheckIcon implements Icon {
        public int getIconWidth() { return 16; }
        public int getIconHeight() { return 16; }

        public void paintIcon(Component c, Graphics g0, int x, int y) {
            AbstractButton b = (AbstractButton) c;
            ButtonModel m = b.getModel();
            Graphics2D g = smooth(g0);
            g.translate(x + 1, y + 1);
            boolean on = m.isSelected(), hover = m.isRollover(), enabled = b.isEnabled();
            RoundRectangle2D box = new RoundRectangle2D.Double(0.5, 0.5, 13, 13, 5, 5);
            if (on) {
                g.setColor(enabled ? (hover ? SELECT.brighter() : SELECT) : FAINT);
                g.fill(box);
                Path2D tick = new Path2D.Double();
                tick.moveTo(3.4, 7.2);
                tick.lineTo(5.9, 9.8);
                tick.lineTo(10.8, 4.4);
                g.setColor(Color.WHITE);
                g.setStroke(new BasicStroke(1.9f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.draw(tick);
            } else {
                g.setColor(CODE_BG);
                g.fill(box);
                g.setColor(hover && enabled ? SELECT : mix(EDGE, TEXT, 0.25));
                g.setStroke(new BasicStroke(1.2f));
                g.draw(box);
            }
            g.dispose();
        }
    }

    static void styleCheck(AbstractButton b) {
        b.setIcon(new CheckIcon());
        b.setSelectedIcon(null);
        b.setRolloverEnabled(true);
        b.setOpaque(false);
        b.setFocusPainted(false);
        b.setForeground(TEXT);
        b.setIconTextGap(8);
        b.setBorder(BorderFactory.createEmptyBorder(3, 2, 3, 2));
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
    }

    // ------------------------------------------------------------------ combo box

    /** Flat combo: rounded field, thin outline, a chevron instead of the platform arrow button. */
    public static final class Combo extends BasicComboBoxUI {
        public static ComponentUI createUI(JComponent c) { return new Combo(); }

        @Override protected void installDefaults() {
            super.installDefaults();
            comboBox.setBorder(BorderFactory.createEmptyBorder(3, 8, 3, 4));
            comboBox.setOpaque(false);
            comboBox.setBackground(PANEL2);
            comboBox.setForeground(TEXT);
        }

        @Override protected JButton createArrowButton() {
            JButton b = new JButton() {
                @Override protected void paintComponent(Graphics g0) {
                    Graphics2D g = smooth(g0);
                    int cx = getWidth() / 2, cy = getHeight() / 2;
                    g.setColor(comboBox.isEnabled() ? MUTED : FAINT);
                    g.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    g.drawPolyline(new int[] {cx - 4, cx, cx + 4}, new int[] {cy - 2, cy + 2, cy - 2}, 3);
                    g.dispose();
                }
            };
            b.setBorder(BorderFactory.createEmptyBorder());
            b.setContentAreaFilled(false);
            b.setFocusable(false);
            b.setPreferredSize(new Dimension(18, 18));
            return b;
        }

        @Override public void paint(Graphics g0, JComponent c) {
            Graphics2D g = smooth(g0);
            boolean hover = c.getMousePosition() != null;
            g.setColor(hover ? mix(PANEL2, TEXT, 0.06) : PANEL2);
            g.fillRoundRect(0, 0, c.getWidth() - 1, c.getHeight() - 1, 8, 8);
            g.setColor(comboBox.hasFocus() || isPopupVisible(comboBox) ? SELECT : hover ? mix(EDGE, TEXT, 0.3) : EDGE);
            g.drawRoundRect(0, 0, c.getWidth() - 1, c.getHeight() - 1, 8, 8);
            g.dispose();
            super.paint(g0, c);
        }

        @Override public void paintCurrentValueBackground(Graphics g, Rectangle bounds, boolean hasFocus) { /* painted in paint() */ }

        @Override public void paintCurrentValue(Graphics g, Rectangle bounds, boolean hasFocus) {
            ListCellRenderer<Object> r = comboBox.getRenderer();
            Component c = r.getListCellRendererComponent(listBox, comboBox.getSelectedItem(), -1, false, false);
            c.setFont(comboBox.getFont());
            c.setForeground(comboBox.isEnabled() ? TEXT : FAINT);
            c.setBackground(PANEL2);
            if (c instanceof JComponent jc) jc.setOpaque(false);
            currentValuePane.paintComponent(g, c, comboBox, bounds.x, bounds.y, bounds.width, bounds.height, false);
        }

        @Override protected ComboPopup createPopup() {
            BasicComboPopup p = new BasicComboPopup(comboBox);
            p.setBorder(BorderFactory.createLineBorder(EDGE));
            return p;
        }

        @Override protected void installListeners() {
            super.installListeners();
            comboBox.addMouseListener(new MouseAdapter() {
                @Override public void mouseEntered(MouseEvent e) { comboBox.repaint(); }
                @Override public void mouseExited(MouseEvent e) { comboBox.repaint(); }
            });
        }
    }

    static void install() {
        UIManager.put("CheckBox.icon", new CheckIcon());
        UIManager.put("CheckBoxMenuItem.checkIcon", new CheckIcon());
        UIManager.put("CheckBox.background", PANEL);
        UIManager.put("CheckBox.foreground", TEXT);
        UIManager.put("CheckBox.font", UI);
        UIManager.put("CheckBox.focus", new Color(0, 0, 0, 0));
        UIManager.put("ComboBoxUI", Combo.class.getName());
        UIManager.put(Combo.class.getName(), Combo.class); // the UI class itself, found without a class loader lookup
    }

    // ------------------------------------------------------------------ segmented control

    /** One row of pill segments (icon + label, icons only when narrow), the selected one filled with the accent. */
    static final class Segmented<T> extends JComponent {
        record Item<T>(T value, String label, Icon icon, String tip) {}

        final List<Item<T>> items = new ArrayList<>();
        int selected, hover = -1;
        final List<Consumer<T>> listeners = new ArrayList<>();
        private int[] edges = new int[0];

        Segmented() {
            setFont(UI_S);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            MouseAdapter m = new MouseAdapter() {
                @Override public void mouseMoved(MouseEvent e) {
                    int h = at(e.getX());
                    if (h != hover) {
                        hover = h;
                        setToolTipText(h < 0 ? null : items.get(h).tip());
                        repaint();
                    }
                }
                @Override public void mouseExited(MouseEvent e) {
                    hover = -1;
                    repaint();
                }
                @Override public void mousePressed(MouseEvent e) {
                    int h = at(e.getX());
                    if (h >= 0) setSelectedIndex(h);
                }
            };
            addMouseListener(m);
            addMouseMotionListener(m);
        }

        Segmented<T> add(T value, String label, Icon icon, String tip) {
            items.add(new Item<>(value, label, icon, tip));
            return this;
        }

        T getSelectedItem() { return items.get(selected).value(); }

        void setSelectedIndex(int i) {
            if (i < 0 || i >= items.size()) return;
            boolean changed = i != selected;
            selected = i;
            if (changed) animate();
            else repaint();
            for (Consumer<T> l : listeners) l.accept(getSelectedItem());
        }

        /** Shows {@code i} as selected without telling listeners (the app already switched). */
        void select(int i) {
            if (i < 0 || i >= items.size() || i == selected) return;
            selected = i;
            animate();
        }

        // the selection pill slides from the old segment to the new one
        private double pillX = -1, pillW, fromX, fromW, toX, toW;
        private long animStart;
        private final javax.swing.Timer anim = new javax.swing.Timer(15, e -> {
            double t = Math.min(1, (System.nanoTime() - animStart) / 1.6e8), k = 1 - Math.pow(1 - t, 3);
            pillX = fromX + (toX - fromX) * k;
            pillW = fromW + (toW - fromW) * k;
            repaint();
            if (t >= 1) ((javax.swing.Timer) e.getSource()).stop();
        });

        private void animate() {
            fromX = pillX;
            fromW = pillW;
            animStart = System.nanoTime();
            if (pillX < 0) repaint();
            else anim.restart();
        }

        void setSelectedItem(T v) {
            for (int i = 0; i < items.size(); i++) if (items.get(i).value().equals(v)) setSelectedIndex(i);
        }

        void onChange(Consumer<T> l) { listeners.add(l); }

        private int at(int x) {
            for (int i = 0; i + 1 < edges.length; i++) if (x >= edges[i] && x < edges[i + 1]) return i;
            return -1;
        }

        private boolean compact() { return getWidth() < fullWidth(); }

        private int fullWidth() {
            FontMetrics fm = getFontMetrics(getFont());
            int w = 2;
            for (Item<T> it : items) w += 12 + (it.icon() != null ? it.icon().getIconWidth() + 5 : 0) + fm.stringWidth(it.label()) + 12;
            return w;
        }

        @Override public Dimension getPreferredSize() { return new Dimension(fullWidth(), 24); }

        static String fit(String s, FontMetrics fm, int w) {
            if (fm.stringWidth(s) <= w) return s;
            for (int n = s.length() - 1; n > 0; n--) if (fm.stringWidth(s.substring(0, n) + ".") <= w) return s.substring(0, n) + ".";
            return "";
        }
        @Override public Dimension getMinimumSize() { return new Dimension(items.size() * 30, 24); }

        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = smooth(g0);
            int h = getHeight() - 2;
            boolean compact = compact();
            FontMetrics fm = g.getFontMetrics(getFont());
            g.setFont(getFont());
            edges = new int[items.size() + 1];
            double each = (getWidth() - 2) / (double) items.size();
            int x = 1;
            int[] widths = new int[items.size()];
            int natural = fullWidth() - 2;
            for (int i = 0; i < items.size(); i++) {
                Item<T> it = items.get(i);
                int nat = 24 + (it.icon() != null ? it.icon().getIconWidth() + 5 : 0) + fm.stringWidth(it.label());
                widths[i] = compact ? (int) Math.round(each) : (int) Math.round(nat + (getWidth() - 2 - natural) / (double) items.size());
            }
            g.setColor(CODE_BG);
            g.fillRoundRect(0, 0, getWidth() - 1, h + 1, 10, 10);
            g.setColor(EDGE);
            g.drawRoundRect(0, 0, getWidth() - 1, h + 1, 10, 10);
            double drawnX = pillX;
            if (pillX >= 0) {
                g.setColor(mix(PANEL2, SELECT, 0.55));
                g.fill(new RoundRectangle2D.Double(pillX, 2, pillW, h - 2, 8, 8));
            }
            for (int i = 0; i < items.size(); i++) {
                edges[i] = x;
                int w = i == items.size() - 1 ? getWidth() - 1 - x : widths[i];
                Item<T> it = items.get(i);
                if (i == selected) {
                    toX = x + 1;
                    toW = w - 2;
                    if (pillX < 0 || !anim.isRunning()) {
                        pillX = toX;
                        pillW = toW;
                    }
                } else if (i == hover) {
                    g.setColor(mix(CODE_BG, TEXT, 0.08));
                    g.fillRoundRect(x + 1, 2, w - 2, h - 2, 8, 8);
                }
                boolean text = !compact || it.icon() == null; // no icon: the label stays, even when narrow
                String label = text && compact ? fit(it.label(), fm, w - 6) : it.label();
                int content = (it.icon() != null ? it.icon().getIconWidth() : 0) + (text ? (it.icon() != null ? 5 : 0) + fm.stringWidth(label) : 0);
                int cx = x + (w - content) / 2;
                if (it.icon() != null) {
                    it.icon().paintIcon(this, g, cx, (h + 2 - it.icon().getIconHeight()) / 2);
                    cx += it.icon().getIconWidth() + 5;
                }
                if (text) {
                    g.setColor(i == selected ? Color.WHITE : i == hover ? TEXT : MUTED);
                    g.drawString(label, cx, (h + 2 + fm.getAscent() - fm.getDescent()) / 2);
                }
                x += w;
            }
            edges[items.size()] = getWidth();
            if (!anim.isRunning() && Math.abs(drawnX - pillX) > 0.5) repaint(); // first paint or a resize
            g.dispose();
        }
    }

    // ------------------------------------------------------------------ window buttons

    /** Which window buttons to draw: the Mosaic set on the right, or macOS-style traffic lights on the left. */
    static boolean macStyle() {
        StudioSettings.WindowButtons w = settings.windowButtons;
        if (w == null || w == StudioSettings.WindowButtons.AUTO) return System.getProperty("os.name", "").toLowerCase().contains("mac");
        return w == StudioSettings.WindowButtons.MAC;
    }

    /** Minimize / maximize (restore) / close, painted by the studio. */
    static final class WindowButtons extends JComponent {
        private final JFrame frame;
        private final Runnable close, toggle;
        private int hover = -1, pressed = -1;
        private final boolean mac;

        WindowButtons(JFrame frame, Runnable toggle, Runnable close) {
            this.frame = frame;
            this.toggle = toggle;
            this.close = close;
            this.mac = macStyle();
            MouseAdapter m = new MouseAdapter() {
                @Override public void mouseMoved(MouseEvent e) { setHover(at(e.getPoint())); }
                @Override public void mouseExited(MouseEvent e) { setHover(-1); }
                @Override public void mousePressed(MouseEvent e) {
                    pressed = at(e.getPoint());
                    repaint();
                }
                @Override public void mouseReleased(MouseEvent e) {
                    int a = at(e.getPoint());
                    int p = pressed;
                    pressed = -1;
                    repaint();
                    if (a != p || a < 0) return;
                    switch (a) {
                        case 0 -> (mac ? close : (Runnable) () -> frame.setState(Frame.ICONIFIED)).run();
                        case 1 -> (mac ? (Runnable) () -> frame.setState(Frame.ICONIFIED) : toggle).run();
                        default -> (mac ? toggle : close).run();
                    }
                }
            };
            addMouseListener(m);
            addMouseMotionListener(m);
        }

        private void setHover(int h) {
            if (h == hover) return;
            hover = h;
            setToolTipText(h < 0 ? null : mac ? new String[] {"Close", "Minimize", "Maximize / restore"}[h]
                : new String[] {"Minimize", "Maximize / restore", "Close"}[h]);
            repaint();
        }

        private int slot() { return mac ? 20 : 40; }

        private int at(Point p) {
            int i = (p.x - (mac ? 8 : 0)) / slot();
            return p.x < (mac ? 8 : 0) || i > 2 ? -1 : i;
        }

        @Override public Dimension getPreferredSize() { return new Dimension(mac ? 8 + slot() * 3 + 6 : slot() * 3, 24); }

        private boolean maximized() { return (frame.getExtendedState() & Frame.MAXIMIZED_BOTH) != 0; }

        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = smooth(g0);
            int h = getHeight();
            if (mac) {
                Color[] fill = {new Color(0xFF5F57), new Color(0xFEBC2E), new Color(0x28C840)};
                boolean groupHover = hover >= 0;
                for (int i = 0; i < 3; i++) {
                    int cx = 8 + i * slot() + slot() / 2, cy = h / 2;
                    g.setColor(frame.isActive() || groupHover ? fill[i] : mix(PANEL2, TEXT, 0.25));
                    g.fillOval(cx - 6, cy - 6, 12, 12);
                    g.setColor(new Color(0, 0, 0, 40));
                    g.drawOval(cx - 6, cy - 6, 12, 12);
                    if (!groupHover) continue;
                    g.setColor(new Color(0, 0, 0, pressed == i ? 200 : 150));
                    g.setStroke(new BasicStroke(1.3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    switch (i) {
                        case 0 -> {
                            g.drawLine(cx - 3, cy - 3, cx + 3, cy + 3);
                            g.drawLine(cx + 3, cy - 3, cx - 3, cy + 3);
                        }
                        case 1 -> g.drawLine(cx - 3, cy, cx + 3, cy);
                        default -> {
                            g.fillPolygon(new int[] {cx - 3, cx + 1, cx - 3}, new int[] {cy - 3, cy - 3, cy + 1}, 3);
                            g.fillPolygon(new int[] {cx + 3, cx - 1, cx + 3}, new int[] {cy + 3, cy + 3, cy - 1}, 3);
                        }
                    }
                }
                g.dispose();
                return;
            }
            for (int i = 0; i < 3; i++) {
                int x = i * slot(), cx = x + slot() / 2, cy = h / 2;
                boolean isClose = i == 2;
                if (hover == i) {
                    Color bg = isClose ? new Color(0xE0443A) : mix(PANEL2, TEXT, pressed == i ? 0.18 : 0.10);
                    g.setColor(bg);
                    g.fillRoundRect(x + 4, 3, slot() - 8, h - 6, 8, 8);
                }
                g.setColor(hover == i && isClose ? Color.WHITE : hover == i ? TEXT : mix(MUTED, TEXT, 0.3));
                g.setStroke(new BasicStroke(1.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                switch (i) {
                    case 0 -> g.drawLine(cx - 5, cy, cx + 5, cy);
                    case 1 -> {
                        if (maximized()) {
                            g.drawRoundRect(cx - 5, cy - 2, 7, 7, 3, 3);
                            g.drawPolyline(new int[] {cx - 2, cx - 2, cx + 5, cx + 5}, new int[] {cy - 2, cy - 5, cy - 5, cy + 2}, 4);
                        } else {
                            g.drawRoundRect(cx - 5, cy - 5, 10, 10, 4, 4);
                        }
                    }
                    default -> {
                        g.drawLine(cx - 4, cy - 4, cx + 4, cy + 4);
                        g.drawLine(cx + 4, cy - 4, cx - 4, cy + 4);
                    }
                }
            }
            g.dispose();
        }
    }

    // ------------------------------------------------------------------ small glyphs (result views, history steps)

    /** A 14 px line icon; {@code color} null paints in the text colour of the moment. */
    static Icon glyph(String kind, Color color) {
        return new Icon() {
            public int getIconWidth() { return 14; }
            public int getIconHeight() { return 14; }
            public void paintIcon(Component c, Graphics g0, int x, int y) {
                Graphics2D g = smooth(g0);
                g.translate(x, y);
                Color col = color != null ? color : TEXT;
                g.setColor(col);
                g.setStroke(new BasicStroke(1.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                switch (kind) {
                    case "shader" -> { // shaded sphere with a highlight
                        g.setPaint(new GradientPaint(3, 3, mix(col, Color.WHITE, 0.55), 12, 12, mix(col, Color.BLACK, 0.45)));
                        g.fillOval(1, 1, 12, 12);
                        g.setColor(new Color(255, 255, 255, 200));
                        g.fillOval(4, 3, 3, 3);
                    }
                    case "vanilla" -> { // a block
                        g.drawPolygon(new int[] {7, 13, 7, 1}, new int[] {1, 4, 7, 4}, 4);
                        g.drawLine(1, 4, 1, 10);
                        g.drawLine(13, 4, 13, 10);
                        g.drawLine(7, 7, 7, 13);
                        g.drawLine(1, 10, 7, 13);
                        g.drawLine(13, 10, 7, 13);
                    }
                    case "split" -> {
                        g.drawRoundRect(1, 2, 12, 10, 3, 3);
                        g.drawLine(7, 0, 7, 14);
                        g.fillRect(2, 3, 5, 9);
                    }
                    case "side" -> {
                        g.drawRoundRect(0, 3, 6, 8, 2, 2);
                        g.drawRoundRect(8, 3, 6, 8, 2, 2);
                    }
                    case "history" -> {
                        g.drawOval(1, 1, 12, 12);
                        g.drawLine(7, 7, 7, 4);
                        g.drawLine(7, 7, 10, 8);
                    }
                    case "move" -> {
                        g.drawLine(7, 1, 7, 13);
                        g.drawLine(1, 7, 13, 7);
                        g.drawPolyline(new int[] {5, 7, 9}, new int[] {3, 1, 3}, 3);
                        g.drawPolyline(new int[] {5, 7, 9}, new int[] {11, 13, 11}, 3);
                        g.drawPolyline(new int[] {3, 1, 3}, new int[] {5, 7, 9}, 3);
                        g.drawPolyline(new int[] {11, 13, 11}, new int[] {5, 7, 9}, 3);
                    }
                    case "arrange" -> {
                        g.drawRoundRect(1, 1, 4, 4, 2, 2);
                        g.drawRoundRect(1, 9, 4, 4, 2, 2);
                        g.drawRoundRect(9, 5, 4, 4, 2, 2);
                        g.drawLine(5, 3, 9, 7);
                        g.drawLine(5, 11, 9, 7);
                    }
                    case "add" -> {
                        g.drawLine(7, 2, 7, 12);
                        g.drawLine(2, 7, 12, 7);
                    }
                    case "delete" -> {
                        g.drawLine(2, 3, 12, 3);
                        g.drawLine(5, 3, 5, 1);
                        g.drawLine(5, 1, 9, 1);
                        g.drawLine(9, 1, 9, 3);
                        g.drawPolyline(new int[] {3, 4, 10, 11}, new int[] {3, 13, 13, 3}, 4);
                    }
                    case "wire" -> {
                        g.fillOval(0, 9, 4, 4);
                        g.fillOval(10, 1, 4, 4);
                        g.draw(new java.awt.geom.CubicCurve2D.Double(2, 11, 8, 11, 6, 3, 12, 3));
                    }
                    case "cut" -> {
                        g.drawOval(1, 8, 4, 4);
                        g.drawOval(9, 8, 4, 4);
                        g.drawLine(4, 8, 10, 1);
                        g.drawLine(10, 8, 4, 1);
                    }
                    case "tag" -> {
                        g.drawPolygon(new int[] {1, 8, 13, 6}, new int[] {1, 1, 7, 13}, 4);
                        g.fillOval(4, 4, 2, 2);
                    }
                    case "frame" -> {
                        g.drawRoundRect(1, 1, 12, 12, 3, 3);
                        g.fillRoundRect(1, 1, 12, 4, 3, 3);
                    }
                    case "value" -> {
                        g.drawLine(1, 7, 13, 7);
                        g.fillOval(6, 4, 6, 6);
                    }
                    case "code" -> {
                        g.drawPolyline(new int[] {5, 1, 5}, new int[] {3, 7, 11}, 3);
                        g.drawPolyline(new int[] {9, 13, 9}, new int[] {3, 7, 11}, 3);
                    }
                    case "copy" -> {
                        g.drawRoundRect(1, 4, 8, 9, 2, 2);
                        g.drawPolyline(new int[] {5, 5, 13, 13, 10}, new int[] {4, 1, 1, 10, 10}, 5);
                    }
                    case "brush" -> {
                        g.drawLine(12, 2, 6, 8);
                        g.fillOval(1, 8, 6, 5);
                    }
                    case "eraser" -> {
                        g.drawPolygon(new int[] {1, 7, 13, 7}, new int[] {9, 3, 9, 13}, 4);
                        g.drawLine(4, 6, 10, 12);
                    }
                    case "fill" -> {
                        g.drawPolygon(new int[] {2, 7, 12, 7}, new int[] {7, 2, 7, 12}, 4);
                        g.fillOval(10, 10, 4, 4);
                    }
                    case "picker" -> {
                        g.drawLine(2, 12, 9, 5);
                        g.fillOval(8, 1, 5, 5);
                    }
                    case "start" -> {
                        g.drawLine(3, 1, 3, 13);
                        g.fillPolygon(new int[] {3, 12, 3}, new int[] {1, 4, 8}, 3);
                    }
                    default -> g.fillOval(4, 4, 6, 6);
                }
                g.dispose();
            }
        };
    }

    /** Icon kind and colour for a history step label ("Move 3 nodes", "Connect a -> b", ...). */
    static Icon historyIcon(String label) {
        String l = label == null ? "" : label.toLowerCase(java.util.Locale.ROOT);
        String kind;
        Color c;
        if (l.equals("start")) { kind = "start"; c = new Color(0x7A8794); }
        else if (l.startsWith("arrange") || l.startsWith("align") || l.startsWith("distribute") || l.startsWith("stack")) { kind = "arrange"; c = new Color(0x4A9FD8); }
        else if (l.startsWith("move") || l.startsWith("resize")) { kind = "move"; c = new Color(0x6C8AA8); }
        else if (l.startsWith("add") || l.startsWith("insert") || l.startsWith("template") || l.startsWith("new")) { kind = "add"; c = new Color(0x4FB886); }
        else if (l.startsWith("paste") || l.startsWith("duplicate")) { kind = "copy"; c = new Color(0x4FB886); }
        else if (l.startsWith("delete") || l.startsWith("remove")) { kind = "delete"; c = new Color(0xE0605A); }
        else if (l.startsWith("knife")) { kind = "cut"; c = new Color(0xE0605A); }
        else if (l.startsWith("connect") || l.startsWith("disconnect") || l.startsWith("wire")) { kind = "wire"; c = new Color(0xC79A3A); }
        else if (l.startsWith("label") || l.startsWith("rename")) { kind = "tag"; c = new Color(0xB07CD8); }
        else if (l.startsWith("group") || l.contains("frame")) { kind = "frame"; c = new Color(0x8A6FC8); }
        else if (l.contains("code")) { kind = "code"; c = new Color(0x5E9E4E); }
        else if (l.startsWith("set") || l.startsWith("reset") || l.startsWith("clamp") || l.startsWith("swizzle") || l.contains("ramp")
            || l.startsWith("material") || l.startsWith("mode")) { kind = "value"; c = new Color(0xD08A4A); }
        else { kind = "dot"; c = new Color(0x8792A8); }
        return glyph(kind, c);
    }

    /** Two icons side by side (history: action icon, then the result thumbnail). */
    static Icon pair(Icon a, Icon b, int gap) {
        if (b == null) return a;
        return new Icon() {
            public int getIconWidth() { return a.getIconWidth() + gap + b.getIconWidth(); }
            public int getIconHeight() { return Math.max(a.getIconHeight(), b.getIconHeight()); }
            public void paintIcon(Component c, Graphics g, int x, int y) {
                a.paintIcon(c, g, x, y + (getIconHeight() - a.getIconHeight()) / 2);
                b.paintIcon(c, g, x + a.getIconWidth() + gap, y + (getIconHeight() - b.getIconHeight()) / 2);
            }
        };
    }
}
