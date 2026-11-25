package io.github.andrexarlay.vulkancompat.shaderhost.mosaic.studio;

import javax.swing.Icon;
import java.awt.*;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;

/** Painted category icons (no font glyphs): palette, menus and node headers use the same set. */
final class Icons {

    private Icons() {}

    static Icon category(String category, Color color, int size) {
        return new Icon() {
            public int getIconWidth() { return size; }
            public int getIconHeight() { return size; }
            public void paintIcon(Component c, Graphics g0, int x, int y) {
                Graphics2D g = (Graphics2D) g0.create();
                g.translate(x, y);
                draw(g, category, color, size);
                g.dispose();
            }
        };
    }

    /** Icon of an editor type (area headers and the editor menu). */
    static Icon panel(Panels.Type type, int size) {
        return new Icon() {
            public int getIconWidth() { return size; }
            public int getIconHeight() { return size; }
            public void paintIcon(Component c, Graphics g0, int x, int y) {
                Graphics2D g = (Graphics2D) g0.create();
                g.translate(x, y);
                drawPanel(g, type, size);
                g.dispose();
            }
        };
    }

    static Color panelColor(Panels.Type t) {
        return switch (t) {
            case GRAPH -> new Color(0x4A76B8);
            case PALETTE -> new Color(0x2F8F7C);
            case INSPECTOR -> new Color(0x8A6FC8);
            case RESULT -> new Color(0xC06A3A);
            case HISTORY -> new Color(0x6C8AA8);
            case PROBLEMS -> new Color(0xC0504D);
            case CODE -> new Color(0x5E9E4E);
            case OUTLINE -> new Color(0x7A8794);
            case GAME -> new Color(0x3E9E8A);
            case ADDONS -> new Color(0xB08A3E);
            case KEYS -> new Color(0x8792A8);
            case LIBRARY -> new Color(0x3E8FB0);
            case MATERIAL -> new Color(0xB86A3A);
            case BROWSER -> new Color(0x5A7AC8);
            case PREVIEW -> new Color(0x3AA0A0);
            case CONFIG -> new Color(0x8A9CF0);
            case GUIDE -> new Color(0xD0A04A);
        };
    }

    static void drawPanel(Graphics2D g, Panels.Type t, int s) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        Color c = panelColor(t);
        g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), 80));
        g.fillRoundRect(0, 0, s, s, s / 3, s / 3);
        g.setColor(c.brighter());
        g.setStroke(new BasicStroke(Math.max(1.2f, s / 11f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        float a = s * 0.22f, b = s * 0.78f, m = s * 0.5f, r = s * 0.14f;
        switch (t) {
            case GRAPH -> {
                g.fill(new Ellipse2D.Float(a - r / 2, a, r * 1.6f, r * 1.6f));
                g.fill(new Ellipse2D.Float(b - r, m - r / 2, r * 1.6f, r * 1.6f));
                g.fill(new Ellipse2D.Float(a - r / 2, b - r * 1.2f, r * 1.6f, r * 1.6f));
                g.draw(new java.awt.geom.Line2D.Float(a + r, a + r, b - r, m));
                g.draw(new java.awt.geom.Line2D.Float(a + r, b - r / 2, b - r, m + r / 2));
            }
            case PALETTE -> {
                for (int i = 0; i < 3; i++) {
                    float yy = a + i * (b - a) / 2.2f;
                    g.fill(new Ellipse2D.Float(a - 1, yy - 1, 3, 3));
                    g.draw(new java.awt.geom.Line2D.Float(a + 4, yy + 0.5f, b, yy + 0.5f));
                }
            }
            case INSPECTOR -> {
                g.draw(new java.awt.geom.Line2D.Float(a, a + 1, b, a + 1));
                g.draw(new java.awt.geom.Line2D.Float(a, m, b, m));
                g.draw(new java.awt.geom.Line2D.Float(a, b - 1, b, b - 1));
                g.fill(new Ellipse2D.Float(m + 1, a - 1, 4, 4));
                g.fill(new Ellipse2D.Float(a + 1, m - 2, 4, 4));
                g.fill(new Ellipse2D.Float(m + 3, b - 3, 4, 4));
            }
            case RESULT -> {
                g.drawRoundRect((int) a - 1, (int) a, (int) (b - a) + 2, (int) (b - a), 3, 3);
                Path2D p = new Path2D.Float();
                p.moveTo(a, b - 1);
                p.lineTo(m - 1, m);
                p.lineTo(m + 2, m + 3);
                p.lineTo(b, a + 3);
                g.draw(p);
            }
            case HISTORY -> {
                g.draw(new Ellipse2D.Float(a - 1, a - 1, b - a + 2, b - a + 2));
                g.draw(new java.awt.geom.Line2D.Float(m, m, m, a + 2));
                g.draw(new java.awt.geom.Line2D.Float(m, m, b - 2, m + 2));
            }
            case PROBLEMS -> {
                Path2D p = new Path2D.Float();
                p.moveTo(m, a - 1);
                p.lineTo(b + 1, b);
                p.lineTo(a - 1, b);
                p.closePath();
                g.draw(p);
                g.draw(new java.awt.geom.Line2D.Float(m, m - 2, m, m + 2));
                g.fill(new Ellipse2D.Float(m - 1, b - 4, 2.5f, 2.5f));
            }
            case CODE -> {
                g.draw(new java.awt.geom.Line2D.Float(m - 3, a, a, m));
                g.draw(new java.awt.geom.Line2D.Float(a, m, m - 3, b));
                g.draw(new java.awt.geom.Line2D.Float(m + 3, a, b, m));
                g.draw(new java.awt.geom.Line2D.Float(b, m, m + 3, b));
            }
            case OUTLINE -> {
                for (int i = 0; i < 3; i++) {
                    float yy = a + i * (b - a) / 2.2f, xx = a + (i == 0 ? 0 : 3);
                    g.draw(new java.awt.geom.Line2D.Float(xx, yy, b, yy));
                }
            }
            case GAME -> {
                g.drawRoundRect((int) a - 1, (int) (a + 2), (int) (b - a) + 2, (int) (b - a) - 3, 4, 4);
                g.draw(new java.awt.geom.Line2D.Float(a + 2, m + 1, a + 6, m + 1));
                g.draw(new java.awt.geom.Line2D.Float(a + 4, m - 1, a + 4, m + 3));
                g.fill(new Ellipse2D.Float(b - 5, m - 1, 2.5f, 2.5f));
            }
            case ADDONS -> {
                g.drawRect((int) a, (int) a, (int) (m - a) - 1, (int) (m - a) - 1);
                g.drawRect((int) m + 1, (int) a, (int) (m - a) - 1, (int) (m - a) - 1);
                g.drawRect((int) a, (int) m + 1, (int) (m - a) - 1, (int) (m - a) - 1);
                g.draw(new java.awt.geom.Line2D.Float(m + 1 + (m - a) / 2, m + 1, m + 1 + (m - a) / 2, b));
                g.draw(new java.awt.geom.Line2D.Float(m + 1, m + 1 + (b - m) / 2, b, m + 1 + (b - m) / 2));
            }
            case KEYS -> {
                g.drawRoundRect((int) a - 2, (int) (a + 2), (int) (b - a) + 4, (int) (b - a) - 3, 3, 3);
                for (int i = 0; i < 3; i++) g.fill(new java.awt.geom.Rectangle2D.Float(a + i * 3.5f, m - 2, 2, 2));
                g.draw(new java.awt.geom.Line2D.Float(a + 1, b - 4, b - 1, b - 4));
            }
            case LIBRARY -> { // a stack of pictures
                g.drawRoundRect((int) a + 2, (int) a, (int) (b - a) - 2, (int) (b - a) - 4, 3, 3);
                g.drawRoundRect((int) a, (int) a + 3, (int) (b - a) - 2, (int) (b - a) - 3, 3, 3);
                g.draw(new java.awt.geom.Line2D.Float(a + 2, b - 3, m, m + 1));
                g.draw(new java.awt.geom.Line2D.Float(m, m + 1, b - 4, b - 3));
            }
            case PREVIEW -> { // an eye
                g.drawOval((int) a, (int) (m - (b - a) / 4), (int) (b - a), (int) ((b - a) / 2));
                g.fill(new java.awt.geom.Ellipse2D.Float(m - 2.5f, m - 2.5f, 5, 5));
            }
            case BROWSER -> { // a grid of cards
                float h = (b - a) / 2f - 1;
                g.drawRoundRect((int) a, (int) a, (int) h, (int) h, 2, 2);
                g.drawRoundRect((int) (a + h + 2), (int) a, (int) h, (int) h, 2, 2);
                g.drawRoundRect((int) a, (int) (a + h + 2), (int) h, (int) h, 2, 2);
                g.drawRoundRect((int) (a + h + 2), (int) (a + h + 2), (int) h, (int) h, 2, 2);
            }
            case MATERIAL -> { // a lit sphere
                g.drawOval((int) a, (int) a, (int) (b - a), (int) (b - a));
                g.fill(new java.awt.geom.Ellipse2D.Float(a + (b - a) * 0.25f, a + (b - a) * 0.2f, (b - a) * 0.25f, (b - a) * 0.25f));
            }
        }
    }

    /** Draws a category glyph into a size x size box at the origin. */
    static void draw(Graphics2D g, String category, Color color, int s) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), 70));
        g.fillRoundRect(0, 0, s, s, s / 3, s / 3);
        g.setColor(color.brighter());
        g.setStroke(new BasicStroke(Math.max(1.2f, s / 10f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        float a = s * 0.22f, b = s * 0.78f, m = s * 0.5f;
        switch (category == null ? "" : category) {
            case "Output" -> {
                g.draw(new Ellipse2D.Float(a, a, b - a, b - a));
                g.fill(new Ellipse2D.Float(m - s * 0.1f, m - s * 0.1f, s * 0.2f, s * 0.2f));
            }
            case "Inputs" -> {
                g.draw(new java.awt.geom.Line2D.Float(a, m, b, m));
                g.draw(new java.awt.geom.Line2D.Float(m, a + 1, b, m));
                g.draw(new java.awt.geom.Line2D.Float(m, b - 1, b, m));
            }
            case "Scene" -> {
                g.drawRect((int) a, (int) a, (int) (b - a), (int) (b - a));
                Path2D p = new Path2D.Float();
                p.moveTo(a, b);
                p.lineTo(m - 1, m);
                p.lineTo(m + 2, m + 3);
                p.lineTo(b, a + 3);
                g.draw(p);
            }
            case "Ray tracing" -> {
                g.draw(new java.awt.geom.Line2D.Float(a, b, b, a));
                g.fill(new Ellipse2D.Float(b - s * 0.14f, a - s * 0.06f, s * 0.2f, s * 0.2f));
                g.draw(new java.awt.geom.Line2D.Float(a, a + 2, a + s * 0.25f, a + 2));
            }
            case "World & materials" -> {
                Path2D p = new Path2D.Float();
                p.moveTo(m, a);
                p.lineTo(b, a + s * 0.15f);
                p.lineTo(b, b - s * 0.1f);
                p.lineTo(m, b + s * 0.05f);
                p.lineTo(a, b - s * 0.1f);
                p.lineTo(a, a + s * 0.15f);
                p.closePath();
                g.draw(p);
                g.draw(new java.awt.geom.Line2D.Float(m, a + s * 0.3f, m, b + s * 0.05f));
            }
            case "Values" -> {
                g.draw(new java.awt.geom.Line2D.Float(a + 2, a, a, b));
                g.draw(new java.awt.geom.Line2D.Float(b, a, b - 2, b));
                g.draw(new java.awt.geom.Line2D.Float(a - 1, m - 2, b + 1, m - 2));
                g.draw(new java.awt.geom.Line2D.Float(a - 1, m + 3, b + 1, m + 3));
            }
            case "Math" -> {
                g.draw(new java.awt.geom.Line2D.Float(a, m, b, m));
                g.draw(new java.awt.geom.Line2D.Float(m, a, m, b));
            }
            case "Vector" -> {
                g.draw(new java.awt.geom.Line2D.Float(a, b, b, a));
                g.draw(new java.awt.geom.Line2D.Float(b, a, b - s * 0.25f, a));
                g.draw(new java.awt.geom.Line2D.Float(b, a, b, a + s * 0.25f));
            }
            case "Compare" -> {
                g.draw(new java.awt.geom.Line2D.Float(m - 1, a, a, m));
                g.draw(new java.awt.geom.Line2D.Float(a, m, m - 1, b));
                g.draw(new java.awt.geom.Line2D.Float(m + 1, a, b, m));
                g.draw(new java.awt.geom.Line2D.Float(b, m, m + 1, b));
            }
            case "Effects" -> {
                for (int i = 0; i < 4; i++) {
                    double t = i * Math.PI / 4;
                    g.draw(new java.awt.geom.Line2D.Double(m + Math.cos(t) * s * 0.3, m + Math.sin(t) * s * 0.3,
                        m - Math.cos(t) * s * 0.3, m - Math.sin(t) * s * 0.3));
                }
            }
            case "Rendering" -> {
                g.draw(new Ellipse2D.Float(m - s * 0.16f, m - s * 0.16f, s * 0.32f, s * 0.32f));
                for (int i = 0; i < 8; i++) {
                    double t = i * Math.PI / 4;
                    g.draw(new java.awt.geom.Line2D.Double(m + Math.cos(t) * s * 0.26, m + Math.sin(t) * s * 0.26,
                        m + Math.cos(t) * s * 0.36, m + Math.sin(t) * s * 0.36));
                }
            }
            case "Converter" -> {
                g.draw(new java.awt.geom.Line2D.Float(a, m - 3, b, m - 3));
                g.draw(new java.awt.geom.Line2D.Float(b, m - 3, b - 3, m - 6));
                g.draw(new java.awt.geom.Line2D.Float(b, m + 3, a, m + 3));
                g.draw(new java.awt.geom.Line2D.Float(a, m + 3, a + 3, m + 6));
            }
            case "Color" -> {
                g.draw(new Ellipse2D.Float(a, a + 1, s * 0.34f, s * 0.34f));
                g.draw(new Ellipse2D.Float(m - 1, a + 1, s * 0.34f, s * 0.34f));
                g.draw(new Ellipse2D.Float(a + s * 0.16f, m - 1, s * 0.34f, s * 0.34f));
            }
            case "Textures" -> {
                int q = Math.max(2, s / 4);
                for (int yy = 0; yy < 2; yy++)
                    for (int xx = 0; xx < 2; xx++)
                        if ((xx + yy) % 2 == 0) g.fillRect((int) a + xx * q, (int) a + yy * q, q, q);
                g.drawRect((int) a, (int) a, 2 * q, 2 * q);
            }
            case "Shading" -> {
                g.draw(new Ellipse2D.Float(a, a, b - a, b - a));
                g.fill(new Ellipse2D.Float(a + s * 0.12f, a + s * 0.1f, s * 0.16f, s * 0.16f));
            }
            default -> {
                g.draw(new Ellipse2D.Float(m - s * 0.13f, a, s * 0.26f, s * 0.26f));
                Path2D p = new Path2D.Float();
                p.moveTo(a, b);
                p.quadTo(m, m - s * 0.05f, b, b);
                g.draw(p);
            }
        }
    }
}
