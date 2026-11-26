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

    /** Flat slider: thin rounded track filled with the accent, round knob that grows on hover. */
    public static final class Slider extends javax.swing.plaf.basic.BasicSliderUI {
        private boolean hover;

        public static ComponentUI createUI(JComponent c) { return new Slider((JSlider) c); }

        Slider(JSlider s) {
            super(s);
            s.setOpaque(false);
            s.addMouseListener(new MouseAdapter() {
                @Override public void mouseEntered(MouseEvent e) { hover = true; s.repaint(); }
                @Override public void mouseExited(MouseEvent e) { hover = false; s.repaint(); }
            });
        }

        @Override protected Dimension getThumbSize() { return new Dimension(16, 16); }

        @Override public void paintFocus(Graphics g) { }

        @Override public void paintTrack(Graphics g0) {
            Graphics2D g = smooth(g0);
            Rectangle t = trackRect;
            int cy = t.y + t.height / 2, x = thumbRect.x + thumbRect.width / 2;
            g.setColor(mix(CODE_BG, EDGE, 0.8));
            g.fillRoundRect(t.x, cy - 2, t.width, 4, 4, 4);
            g.setColor(slider.isEnabled() ? SELECT : FAINT);
            g.fillRoundRect(t.x, cy - 2, Math.max(0, x - t.x), 4, 4, 4);
            g.dispose();
        }

        @Override public void paintThumb(Graphics g0) {
            Graphics2D g = smooth(g0);
            Rectangle r = thumbRect;
            int d = hover || isDragging() ? 16 : 13, cx = r.x + r.width / 2, cy = r.y + r.height / 2;
            g.setColor(new Color(0, 0, 0, 70));
            g.fillOval(cx - d / 2, cy - d / 2 + 1, d, d);
            g.setColor(Color.WHITE);
            g.fillOval(cx - d / 2, cy - d / 2, d, d);
            g.setColor(SELECT);
            g.setStroke(new BasicStroke(2f));
            g.drawOval(cx - d / 2 + 1, cy - d / 2 + 1, d - 2, d - 2);
            g.dispose();
        }
    }

    static void install() {
        UIManager.put("SliderUI", Slider.class.getName());
        UIManager.put(Slider.class.getName(), Slider.class);
        UIManager.put("CheckBox.icon", new CheckIcon());
        UIManager.put("CheckBoxMenuItem.checkIcon", new CheckIcon());
        UIManager.put("CheckBox.background", PANEL);
        UIManager.put("CheckBox.foreground", TEXT);
        UIManager.put("CheckBox.font", UI);
        UIManager.put("CheckBox.focus", new Color(0, 0, 0, 0));
        UIManager.put("ComboBoxUI", Combo.class.getName());
        UIManager.put(Combo.class.getName(), Combo.class); // the UI class itself, found without a class loader lookup
    }

    // ------------------------------------------------------------------ toast

    /** A short message that slides up from the bottom-right of a window and fades away. */
    static void toast(JFrame frame, String text, Color accent) {
        if (frame == null || !frame.isShowing() || text == null || text.isBlank()) return;
        JLayeredPane layers = frame.getLayeredPane();
        for (Component c : layers.getComponentsInLayer(JLayeredPane.POPUP_LAYER)) if (c instanceof Toast t) t.retire();
        Toast t = new Toast(text.length() > 140 ? text.substring(0, 137) + "..." : text, accent);
        layers.add(t, JLayeredPane.POPUP_LAYER);
        t.start(layers);
    }

    static final class Toast extends JComponent {
        private final String text;
        private final Color accent;
        private long born;
        private float alpha;
        private int targetY;
        private final javax.swing.Timer tick = new javax.swing.Timer(15, null);
        private boolean retiring;

        Toast(String text, Color accent) {
            this.text = text;
            this.accent = accent;
            setFont(UI);
        }

        void start(JLayeredPane layers) {
            FontMetrics fm = getFontMetrics(getFont());
            int w = Math.min(layers.getWidth() - 40, fm.stringWidth(text) + 44), h = 34;
            targetY = layers.getHeight() - h - 40;
            setBounds(layers.getWidth() - w - 18, targetY + 24, w, h);
            born = System.currentTimeMillis();
            tick.addActionListener(e -> step(layers));
            tick.start();
        }

        void retire() {
            retiring = true;
            born = Math.min(born, System.currentTimeMillis() - 2600);
        }

        private void step(JLayeredPane layers) {
            long age = System.currentTimeMillis() - born;
            if (age < 220) {
                double k = 1 - Math.pow(1 - age / 220.0, 3);
                alpha = (float) k;
                setLocation(getX(), (int) Math.round(targetY + 24 * (1 - k)));
            } else if (age > 2800) {
                alpha = Math.max(0, 1 - (age - 2800) / 300f);
                if (alpha <= 0) {
                    tick.stop();
                    layers.remove(this);
                    layers.repaint();
                    return;
                }
            } else {
                alpha = 1;
                setLocation(getX(), targetY);
            }
            repaint();
        }

        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = smooth(g0);
            g.setComposite(AlphaComposite.SrcOver.derive(Math.clamp(alpha, 0f, 1f)));
            g.setColor(new Color(0, 0, 0, 90));
            g.fillRoundRect(2, 3, getWidth() - 3, getHeight() - 3, 12, 12);
            g.setColor(PANEL2);
            g.fillRoundRect(0, 0, getWidth() - 2, getHeight() - 3, 12, 12);
            g.setColor(EDGE);
            g.drawRoundRect(0, 0, getWidth() - 3, getHeight() - 4, 12, 12);
            g.setColor(accent);
            g.fillRoundRect(10, (getHeight() - 3) / 2 - 4, 8, 8, 8, 8);
            g.setColor(TEXT);
            g.setFont(getFont());
            FontMetrics fm = g.getFontMetrics();
            g.drawString(Segmented.fit(text, fm, getWidth() - 40), 26, (getHeight() - 3 + fm.getAscent() - fm.getDescent()) / 2);
            g.dispose();
        }
    }

    // ------------------------------------------------------------------ toggle switch

    /** An on/off switch whose knob slides (and colour fades) in 150 ms. */
    static final class Toggle extends JComponent {
        private boolean on;
        private double pos;
        private final Consumer<Boolean> changed;
        private final javax.swing.Timer anim = new javax.swing.Timer(15, null);

        Toggle(boolean on, Consumer<Boolean> changed) {
            this.on = on;
            this.pos = on ? 1 : 0;
            this.changed = changed;
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setFocusable(true);
            anim.addActionListener(e -> {
                double target = this.on ? 1 : 0;
                pos += (target - pos) * 0.35;
                if (Math.abs(target - pos) < 0.02) {
                    pos = target;
                    anim.stop();
                }
                repaint();
            });
            addMouseListener(new MouseAdapter() {
                @Override public void mousePressed(MouseEvent e) { flip(); }
            });
            addKeyListener(new java.awt.event.KeyAdapter() {
                @Override public void keyPressed(java.awt.event.KeyEvent e) {
                    if (e.getKeyCode() == java.awt.event.KeyEvent.VK_SPACE || e.getKeyCode() == java.awt.event.KeyEvent.VK_ENTER) flip();
                }
            });
        }

        void flip() {
            on = !on;
            anim.start();
            changed.accept(on);
        }

        @Override public Dimension getPreferredSize() { return new Dimension(38, 22); }

        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = smooth(g0);
            int w = 36, h = 20, y = (getHeight() - h) / 2;
            g.setColor(mix(mix(CODE_BG, EDGE, 0.6), SELECT, pos));
            g.fillRoundRect(0, y, w, h, h, h);
            int knob = h - 6, x = (int) Math.round(3 + (w - knob - 6) * pos);
            g.setColor(Color.WHITE);
            g.fillOval(x, y + 3, knob, knob);
            if (hasFocus()) {
                g.setColor(SELECT);
                g.drawRoundRect(-1 + 1, y - 1 + 1, w - 2, h, h, h);
            }
            g.dispose();
        }
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
                    case "chip" -> { // a processor: the renderer
                        g.drawRoundRect(3, 3, 8, 8, 2, 2);
                        for (int k = 4; k <= 10; k += 3) {
                            g.drawLine(k, 0, k, 2);
                            g.drawLine(k, 12, k, 14);
                            g.drawLine(0, k, 2, k);
                            g.drawLine(12, k, 14, k);
                        }
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

    /**
     * Makes a popup menu fit the screen it opens on (small screens such as 1366 x 720): items with a line of help become
     * one line (the help moves to the tooltip), and a menu still taller than the screen is laid out in columns.
     */
    /**
     * One row of tools that never wraps and never gets in the picture's way: what does not fit goes behind a » button
     * (the tools themselves move into its menu while it is open). Any panel width works, down to the » alone.
     */
    static final class OverflowBar extends JPanel {
        private final JButton more = new JButton("»");
        private final java.util.List<Component> tools = new java.util.ArrayList<>();
        private final java.util.List<Component> hidden = new java.util.ArrayList<>();
        private final int gap;

        OverflowBar(int gap) {
            this.gap = gap;
            setOpaque(false);
            setLayout(null);
            more.setFocusable(false);
            more.setMargin(new Insets(2, 8, 2, 8));
            more.setToolTipText("More tools (the panel is narrow)");
            more.addActionListener(e -> openMore());
            super.add(more);
        }

        void tool(Component c) {
            tools.add(c);
            super.add(c);
        }

        @Override public Dimension getPreferredSize() {
            int w = 0, h = more.getPreferredSize().height;
            for (Component c : tools) if (c.isVisible() || hidden.contains(c)) {
                Dimension d = c.getPreferredSize();
                w += d.width + gap;
                h = Math.max(h, d.height);
            }
            Insets in = getInsets();
            return new Dimension(w + in.left + in.right, h + in.top + in.bottom);
        }

        @Override public Dimension getMinimumSize() {
            Dimension p = getPreferredSize();
            return new Dimension(more.getPreferredSize().width, p.height);
        }

        @Override public void doLayout() {
            Insets in = getInsets();
            int x = in.left, h = getHeight() - in.top - in.bottom, limit = getWidth() - in.right;
            int moreW = more.getPreferredSize().width;
            // does everything fit without the » button?
            int all = 0;
            for (Component c : tools) if (shown(c)) all += c.getPreferredSize().width + gap;
            boolean overflow = in.left + all - gap > limit;
            hidden.clear();
            boolean first = true;
            for (Component c : tools) {
                if (!shown(c)) continue;
                Dimension d = c.getPreferredSize();
                int room = overflow ? limit - moreW - gap : limit;
                if (first && x + d.width > room) d = new Dimension(Math.max(40, room - x), d.height); // the lead tool shrinks instead
                first = false;
                if (hidden.isEmpty() && x + d.width <= room) {
                    c.setBounds(x, in.top + (h - d.height) / 2, d.width, d.height);
                    x += d.width + gap;
                } else {
                    hidden.add(c);
                    c.setBounds(0, 0, 0, 0);
                }
            }
            Dimension md = more.getPreferredSize();
            more.setVisible(!hidden.isEmpty());
            more.setBounds(Math.max(in.left, limit - md.width), in.top + (h - md.height) / 2, md.width, md.height);
        }

        private boolean shown(Component c) {
            return c.isVisible() || hidden.contains(c);
        }

        private void openMore() {
            if (hidden.isEmpty()) return;
            java.util.List<Component> moved = new java.util.ArrayList<>(hidden);
            JPanel box = new JPanel(new GridBagLayout());
            box.setOpaque(false);
            box.setBorder(new javax.swing.border.EmptyBorder(6, 6, 6, 6));
            GridBagConstraints gc = new GridBagConstraints();
            gc.gridx = 0;
            gc.fill = GridBagConstraints.HORIZONTAL;
            gc.insets = new Insets(2, 0, 2, 0);
            for (Component c : moved) {
                remove(c);
                c.setVisible(true);
                box.add(c, gc);
            }
            JPopupMenu m = new JPopupMenu();
            m.add(box);
            m.addPopupMenuListener(new javax.swing.event.PopupMenuListener() {
                @Override public void popupMenuWillBecomeVisible(javax.swing.event.PopupMenuEvent e) { }
                @Override public void popupMenuCanceled(javax.swing.event.PopupMenuEvent e) { }
                @Override public void popupMenuWillBecomeInvisible(javax.swing.event.PopupMenuEvent e) {
                    // the tools go back into the bar, in their order
                    for (Component c : moved) box.remove(c);
                    OverflowBar.this.removeAll();
                    OverflowBar.super.add(more);
                    for (Component c : tools) OverflowBar.super.add(c);
                    hidden.clear();
                    revalidate();
                    repaint();
                }
            });
            fitToScreen(m, more);
            m.show(more, more.getWidth() - box.getPreferredSize().width - 12, more.getHeight());
        }
    }

    static void fitToScreen(JPopupMenu m, Component invoker) {
        GraphicsConfiguration gc = invoker == null ? null : invoker.getGraphicsConfiguration();
        Rectangle screen = gc == null ? GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds() : gc.getBounds();
        Insets in = gc == null ? new Insets(0, 0, 0, 0) : Toolkit.getDefaultToolkit().getScreenInsets(gc);
        int avail = screen.height - in.top - in.bottom - 40;
        if (m.getPreferredSize().height <= avail) return;
        for (Component c : m.getComponents())
            if (c instanceof JMenuItem it && it.getText() != null && it.getText().startsWith("<html>") && it.getText().contains("<br>")) {
                String t = it.getText();
                String head = t.replaceAll("(?s)^<html>(.*?)<br>.*$", "$1").replaceAll("<[^>]+>", "");
                String help = t.replaceAll("(?s)^.*?<br>(.*)$", "$1").replaceAll("<[^>]+>", "").trim();
                it.setText(head);
                if (!help.isEmpty()) it.setToolTipText(help);
                it.setBorder(BorderFactory.createEmptyBorder(3, 10, 3, 18));
            }
        if (m.getPreferredSize().height <= avail) return;
        java.util.List<Component> cs = new java.util.ArrayList<>();
        for (Component c : m.getComponents()) if (!(c instanceof JSeparator)) cs.add(c);
        int rowH = 1;
        for (Component c : cs) rowH = Math.max(rowH, c.getPreferredSize().height);
        int rows = Math.max(1, avail / rowH), cols = (cs.size() + rows - 1) / rows;
        rows = (cs.size() + cols - 1) / cols;
        m.removeAll();
        m.setLayout(new GridLayout(rows, cols, 8, 0));
        for (int r = 0; r < rows; r++)
            for (int c = 0; c < cols; c++) {
                int i = c * rows + r; // column by column, as the list reads
                m.add(i < cs.size() ? cs.get(i) : new JLabel());
            }
    }

    /** {@link #fitToScreen} for a menu of the menu bar, applied each time it opens. */
    static void fitOnOpen(JMenu menu) {
        menu.addMenuListener(new javax.swing.event.MenuListener() {
            @Override public void menuSelected(javax.swing.event.MenuEvent e) { fitToScreen(menu.getPopupMenu(), menu); }
            @Override public void menuDeselected(javax.swing.event.MenuEvent e) { }
            @Override public void menuCanceled(javax.swing.event.MenuEvent e) { }
        });
    }
}
