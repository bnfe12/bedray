package io.github.andrexarlay.vulkancompat.shaderhost.mosaic.studio;

import io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicGraph;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;

/** The studio's look: theme colors and fonts (from {@link StudioSettings}) and the themed Swing building blocks. */
final class StudioUi {

    private StudioUi() {}

    static Color BG, PANEL, PANEL2, EDGE, TEXT, MUTED, FAINT, NODE, CODE_BG, GRID, SELECT, BUTTON, HEADER;
    static final Color OK = new Color(0x4FB886), BAD = new Color(0xFF6B66), WARN = new Color(0xF0A830), LIVE = new Color(0x2E6B4F);
    static Font UI, UI_B, UI_S, MONO;
    static StudioSettings settings;

    static void apply(StudioSettings s) {
        settings = s.sanitized();
        StudioSettings.Theme t = settings.theme;
        BG = new Color(t.bg);
        PANEL = new Color(t.panel);
        PANEL2 = new Color(t.panel2);
        EDGE = new Color(t.edge);
        TEXT = new Color(t.text);
        MUTED = new Color(t.muted);
        FAINT = new Color(t.faint);
        NODE = new Color(t.node);
        CODE_BG = new Color(t.code);
        GRID = new Color(t.grid);
        SELECT = new Color(settings.accent.rgb);
        BUTTON = t.light ? new Color(0xE2E7F0) : PANEL2.brighter();
        HEADER = t.light ? new Color(0xE6EAF1) : new Color(Math.max(0, t.panel2 - 0x030303));
        float f = settings.fontPercent / 100f;
        UI = new Font("Segoe UI", Font.PLAIN, Math.round(13 * f));
        UI_B = new Font("Segoe UI", Font.BOLD, Math.round(13 * f));
        UI_S = new Font("Segoe UI", Font.PLAIN, Math.round(11.5f * f));
        MONO = new Font("Consolas", Font.PLAIN, Math.round(12 * f));
        UIManager.put("ToolTip.background", PANEL2);
        UIManager.put("ToolTip.foreground", TEXT);
        UIManager.put("ToolTip.font", UI);
        UIManager.put("ToolTip.border", BorderFactory.createLineBorder(EDGE));
        for (String k : new String[] {"PopupMenu", "MenuItem", "Menu", "CheckBoxMenuItem", "RadioButtonMenuItem"}) {
            UIManager.put(k + ".background", PANEL2);
            UIManager.put(k + ".foreground", TEXT);
            UIManager.put(k + ".font", UI);
            UIManager.put(k + ".selectionBackground", SELECT.darker());
            UIManager.put(k + ".selectionForeground", Color.WHITE);
            UIManager.put(k + ".acceleratorForeground", MUTED);
        }
        UIManager.put("PopupMenu.border", BorderFactory.createLineBorder(EDGE));
        UIManager.put("MenuBar.background", PANEL2);
        UIManager.put("MenuBar.border", BorderFactory.createEmptyBorder());
        UIManager.put("Menu.selectionBackground", SELECT.darker());
        UIManager.put("Menu.selectionForeground", Color.WHITE);
        UIManager.put("MenuItem.borderPainted", false);
        UIManager.put("MenuItem.border", BorderFactory.createEmptyBorder(4, 10, 4, 14));
        UIManager.put("Menu.border", BorderFactory.createEmptyBorder(2, 8, 2, 8));
        UIManager.put("Separator.background", PANEL2);
        UIManager.put("PopupMenuSeparator.foreground", EDGE);
        UIManager.put("PopupMenuSeparator.background", PANEL2);
        for (String k : new String[] {"Panel", "OptionPane", "CheckBox", "RadioButton", "Slider", "Viewport", "ScrollPane", "SplitPane", "TabbedPane"})
            UIManager.put(k + ".background", PANEL);
        for (String k : new String[] {"Label", "CheckBox", "RadioButton", "OptionPane.message", "TabbedPane", "TitledBorder.title"})
            UIManager.put(k + ".foreground", TEXT);
        for (String k : new String[] {"Label", "CheckBox", "RadioButton", "TabbedPane", "ComboBox", "List", "Table", "TableHeader", "Button",
            "TextField", "OptionPane.message", "OptionPane.button"})
            UIManager.put(k + ".font", UI);
        UIManager.put("ComboBox.background", PANEL2);
        UIManager.put("ComboBox.foreground", TEXT);
        UIManager.put("ComboBox.selectionBackground", SELECT.darker());
        UIManager.put("ComboBox.selectionForeground", Color.WHITE);
        UIManager.put("List.background", PANEL);
        UIManager.put("List.foreground", TEXT);
        UIManager.put("Table.background", PANEL);
        UIManager.put("Table.foreground", TEXT);
        UIManager.put("Table.gridColor", EDGE);
        UIManager.put("Table.selectionBackground", SELECT.darker());
        UIManager.put("TableHeader.background", PANEL2);
        UIManager.put("TableHeader.foreground", MUTED);
        UIManager.put("TextField.background", CODE_BG);
        UIManager.put("TextField.foreground", TEXT);
        UIManager.put("TextField.caretForeground", TEXT);
        UIManager.put("FormattedTextField.background", CODE_BG);
        UIManager.put("FormattedTextField.foreground", TEXT);
        UIManager.put("Spinner.background", PANEL2);
        UIManager.put("Button.background", BUTTON);
        UIManager.put("Button.foreground", TEXT);
        UIManager.put("Separator.foreground", EDGE);
        UIManager.put("ToolBar.background", PANEL2);
        StudioWidgets.install();
    }

    static String hex(Color c) { return String.format("#%02x%02x%02x", c.getRed(), c.getGreen(), c.getBlue()); }

    static void style(AbstractButton b) {
        b.setFocusable(false);
        b.setFont(UI);
        b.setForeground(TEXT);
        b.setBackground(BUTTON);
        b.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(EDGE), new EmptyBorder(4, 10, 4, 10)));
    }

    static JButton button(String label, String tip, Runnable r) {
        JButton b = new JButton(label);
        if (tip != null) b.setToolTipText(tip);
        style(b);
        b.addActionListener(e -> r.run());
        return b;
    }

    static JScrollPane scroll(JComponent c) {
        JScrollPane sp = new JScrollPane(c);
        sp.setBorder(null);
        sp.getVerticalScrollBar().setUnitIncrement(28);
        sp.getHorizontalScrollBar().setUnitIncrement(28);
        darken(sp);
        return sp;
    }

    static void darken(JScrollPane sp) {
        for (JScrollBar bar : new JScrollBar[] {sp.getVerticalScrollBar(), sp.getHorizontalScrollBar()}) {
            bar.setPreferredSize(bar.getOrientation() == JScrollBar.VERTICAL ? new Dimension(10, 0) : new Dimension(0, 10));
            bar.setBackground(PANEL);
            bar.setUI(new javax.swing.plaf.basic.BasicScrollBarUI() {
                @Override protected void configureScrollBarColors() {
                    thumbColor = EDGE.brighter();
                    trackColor = PANEL;
                }
                @Override protected JButton createDecreaseButton(int o) { return zero(); }
                @Override protected JButton createIncreaseButton(int o) { return zero(); }
                private JButton zero() {
                    JButton b = new JButton();
                    b.setPreferredSize(new Dimension(0, 0));
                    return b;
                }
                @Override protected void paintThumb(Graphics g, JComponent c, Rectangle r) {
                    Graphics2D g2 = (Graphics2D) g.create();
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    g2.setColor(isDragging ? SELECT : thumbColor);
                    g2.fillRoundRect(r.x + 2, r.y + 2, r.width - 4, r.height - 4, 6, 6);
                    g2.dispose();
                }
            });
        }
        sp.getViewport().setBackground(PANEL);
        JPanel corner = new JPanel();
        corner.setBackground(PANEL);
        sp.setCorner(ScrollPaneConstants.LOWER_RIGHT_CORNER, corner);
    }

    static void flatDivider(JSplitPane sp) {
        sp.setUI(new javax.swing.plaf.basic.BasicSplitPaneUI() {
            @Override public javax.swing.plaf.basic.BasicSplitPaneDivider createDefaultDivider() {
                return new javax.swing.plaf.basic.BasicSplitPaneDivider(this) {
                    @Override public void paint(Graphics g) {
                        g.setColor(BG);
                        g.fillRect(0, 0, getWidth(), getHeight());
                    }
                };
            }
        });
        sp.setBorder(null);
        sp.setDividerSize(5);
        sp.setBackground(BG);
        sp.setContinuousLayout(true);
    }

    static JLabel title(String t) {
        JLabel l = new JLabel(t);
        l.setFont(UI_B.deriveFont(UI_B.getSize2D() + 1));
        l.setForeground(TEXT);
        l.setBorder(new EmptyBorder(6, 0, 4, 0));
        l.setAlignmentX(0);
        return l;
    }

    static JLabel label(String t, Color c) {
        JLabel l = new JLabel(t);
        l.setFont(UI);
        l.setForeground(c);
        l.setAlignmentX(0);
        return l;
    }

    /** A label that wraps at {@code width} screen pixels (Swing's HTML renderer scales CSS px by ~1.3, hence the factor). */
    static JLabel wrapped(String html, Color c, int width) {
        int css = Math.max(60, Math.round(width * 0.74f * 100f / settings.fontPercent));
        return label("<html><div style='width:" + css + "px'>" + html + "</div></html>", c);
    }

    static JTextField field(String v) {
        JTextField f = new JTextField(v);
        f.setFont(MONO);
        f.setBackground(CODE_BG);
        f.setForeground(TEXT);
        f.setCaretColor(TEXT);
        f.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(EDGE), new EmptyBorder(3, 5, 3, 5)));
        f.setMaximumSize(new Dimension(Integer.MAX_VALUE, f.getPreferredSize().height + 4));
        f.setAlignmentX(0);
        return f;
    }

    static DocumentListener onText(Runnable r) {
        return new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { r.run(); }
            public void removeUpdate(DocumentEvent e) { r.run(); }
            public void changedUpdate(DocumentEvent e) { r.run(); }
        };
    }

    static Color kindColor(MosaicGraph.Kind k) {
        if (k == null) return new Color(0xC8CCD8);
        return switch (k) {
            case F -> new Color(0xA6AEBF);
            case V2 -> new Color(0x6FD2A0);
            case V3 -> new Color(0xE0B45C);
            case V4 -> new Color(0xB48CF0);
            case S -> new Color(0x4FC3C8);
        };
    }

    static String kindName(MosaicGraph.Kind k) {
        if (k == null) return "any";
        return switch (k) { case F -> "float"; case V2 -> "vec2"; case V3 -> "vec3"; case V4 -> "vec4"; case S -> "Slang value"; };
    }

    static Color categoryColor(String c) {
        return switch (c == null ? "" : c) {
            case "Effects" -> new Color(0xC06A3A);
            case "Rendering" -> new Color(0x8A5CC8);
            case "Shading" -> new Color(0x3E8EB0);
            case "Render pipeline" -> new Color(0xD08A2E);
            default -> new Color(0x4FA3A0);
        };
    }

    static String esc(String s) { return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"); }

    /** Small painted icons for area headers (no font glyph dependencies). */
    static Icon icon(String kind) {
        return new Icon() {
            public int getIconWidth() { return 14; }
            public int getIconHeight() { return 14; }
            public void paintIcon(Component c, Graphics g0, int x, int y) {
                Graphics2D g = (Graphics2D) g0.create();
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(c.isEnabled() ? MUTED : FAINT);
                g.setStroke(new BasicStroke(1.3f));
                g.translate(x, y);
                switch (kind) {
                    case "splitH" -> { g.drawRect(1, 2, 12, 10); g.drawLine(7, 2, 7, 12); }
                    case "splitV" -> { g.drawRect(1, 2, 12, 10); g.drawLine(1, 7, 13, 7); }
                    case "max" -> { g.drawRect(2, 2, 10, 10); g.drawLine(2, 4, 12, 4); }
                    case "detach" -> { g.drawRect(1, 4, 8, 8); g.drawLine(6, 1, 13, 1); g.drawLine(13, 1, 13, 8); g.drawLine(13, 1, 7, 7); }
                    case "dock" -> { g.drawRect(1, 1, 12, 12); g.fillRect(1, 8, 12, 5); }
                    case "close" -> { g.drawLine(3, 3, 11, 11); g.drawLine(11, 3, 3, 11); }
                    default -> { }
                }
                g.dispose();
            }
        };
    }

    static JButton iconButton(String kind, String tip, Runnable r) {
        JButton b = new JButton(icon(kind));
        b.setToolTipText(tip);
        b.setFocusable(false);
        b.setBorder(new EmptyBorder(2, 4, 2, 4));
        b.setContentAreaFilled(false);
        b.addActionListener(e -> r.run());
        return b;
    }
}
