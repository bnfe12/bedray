package io.github.andrexarlay.vulkancompat.shaderhost.mosaic.studio;

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;

import static io.github.andrexarlay.vulkancompat.shaderhost.mosaic.studio.StudioUi.*;

/**
 * Preview: the open graph run by the studio itself on the GPU over a real scene - the preview world (the blocks around
 * the player when the game opened Studio, or a built-in landscape) rendered with depth, normals, materials and light, a
 * camera you fly with the mouse (drag to look, wheel to move, W A S D / Space / Shift when the view has focus) and the
 * time of day on a slider - or over the game's last frame. Independent of the game (Result shows the game's own render).
 */
final class PreviewPanel extends Panels.Listening {

    enum Source { WORLD, GAME }

    private static GpuPreview gpu;
    private static String gpuError;

    final JPanel root = new JPanel(new BorderLayout());
    final StudioWidgets.Segmented<Source> source = new StudioWidgets.Segmented<Source>()
        .add(Source.WORLD, "World", StudioWidgets.glyph("vanilla", new Color(0x7FB069)),
            "Real blocks with depth, normals, materials and light: where you stood when the game opened Studio, or a built-in landscape."
                + " Drag to look around, wheel to move, W A S D / Space / Shift to fly.")
        .add(Source.GAME, "Game frame", StudioWidgets.glyph("frame", null), "The last frame the game sent (a picture: no depth or world data)");
    final JSlider time = new JSlider(0, 24000, 6000);
    volatile PreviewWorld.View worldView;
    private Point dragFrom;
    final JLabel info = label("", FAINT);
    final JComponent view;
    volatile BufferedImage shown;
    volatile String message = "Starting the GPU...";
    volatile boolean playing = true;
    private final JButton play;
    private final Thread worker;
    private volatile boolean alive = true;
    private long start = System.nanoTime();
    private int frame;
    private Object lastKey;

    /** How the image is shown: as it is, or a view that makes brightness, clipping or one channel readable. */
    enum Show {
        IMAGE("Image"), FALSE_COLOUR("False colour"), CLIPPING("Clipping"), LUMA("Brightness"), RED("Red"), GREEN("Green"), BLUE("Blue");
        final String label;
        Show(String l) { label = l; }
        @Override public String toString() { return label; }
    }

    /** Comparison with a kept frame (A): side by side with a movable split, or the difference made 4x brighter. */
    enum Compare {
        OFF("No compare"), SPLIT("Split: A | now"), DIFF("Difference x4");
        final String label;
        Compare(String l) { label = l; }
        @Override public String toString() { return label; }
    }

    volatile Show showMode = Show.IMAGE;
    volatile Compare compare = Compare.OFF;
    volatile BufferedImage reference;
    private double split = 0.5;
    private boolean splitDrag;
    private Point probe;
    private Rectangle imageRect;
    private PreviewWorld.View startView;
    private BufferedImage displayCache;
    private Object displayKey;
    private final JComboBox<Compare> compareBox = new JComboBox<>(Compare.values());
    private long lastLoop = System.nanoTime();

    PreviewPanel(StudioModel m) {
        super(m);
        root.setBackground(PANEL);
        JPanel bar = new JPanel(new BorderLayout(6, 0));
        bar.setBackground(PANEL2);
        bar.setBorder(new EmptyBorder(5, 6, 5, 6));
        source.select(0);
        source.onChange(s -> lastKey = null);
        bar.add(source, BorderLayout.CENTER);
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        right.setOpaque(false);
        play = button("Pause", "Animate time (time, noise, grain...) and, with a day speed in Settings > Preview, the time of day (P)", () -> { });
        play.addActionListener(e -> {
            playing = !playing;
            play.setText(playing ? "Pause" : "Play");
        });
        right.add(play);
        time.setOpaque(false);
        time.setPreferredSize(new Dimension(110, 22));
        time.setToolTipText("Time of day: 0 sunrise, 6000 noon, 12000 sunset, 18000 midnight (Minecraft ticks)");
        time.addChangeListener(e -> {
            PreviewWorld.View v = worldView;
            if (v != null) worldView = v.at((float) (Math.floorMod(time.getValue() - 6000, 24000) / 24000.0));
            lastKey = null;
        });
        JPanel row2 = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        row2.setOpaque(false);
        row2.setBorder(new EmptyBorder(4, 0, 0, 0));
        row2.add(label("Time", FAINT));
        row2.add(time);
        JComboBox<String> size = new JComboBox<>(new String[] {"320 x 180", "640 x 360", "960 x 540", "1280 x 720", "1600 x 900", "1920 x 1080",
            "2560 x 1440", "Custom..."});
        String current = StudioUi.settings.previewWidth + " x " + StudioUi.settings.previewHeight;
        if (((DefaultComboBoxModel<String>) size.getModel()).getIndexOf(current) < 0) size.insertItemAt(current, 0);
        size.setToolTipText("Preview resolution: the world and every pass of the graph run at this size");
        size.setSelectedItem(StudioUi.settings.previewWidth + " x " + StudioUi.settings.previewHeight);
        size.addActionListener(e -> {
            String pick = (String) size.getSelectedItem();
            if ("Custom...".equals(pick)) {
                String t = JOptionPane.showInputDialog(root, "Preview size (width x height, 160..3840 x 90..2160)",
                    StudioUi.settings.previewWidth + " x " + StudioUi.settings.previewHeight);
                java.util.regex.Matcher mt = t == null ? null : java.util.regex.Pattern.compile("(\\d+)\\s*[xX*, ]\\s*(\\d+)").matcher(t.trim());
                if (mt == null || !mt.matches()) {
                    size.setSelectedItem(StudioUi.settings.previewWidth + " x " + StudioUi.settings.previewHeight);
                    return;
                }
                pick = Math.clamp(Integer.parseInt(mt.group(1)), 160, 3840) + " x " + Math.clamp(Integer.parseInt(mt.group(2)), 90, 2160);
                if (((DefaultComboBoxModel<String>) size.getModel()).getIndexOf(pick) < 0) size.insertItemAt(pick, 0);
                size.setSelectedItem(pick);
                return;
            }
            String[] wh = pick.split(" x ");
            StudioUi.settings.previewWidth = Integer.parseInt(wh[0]);
            StudioUi.settings.previewHeight = Integer.parseInt(wh[1]);
            StudioUi.settings.save();
            lastKey = null;
        });
        row2.add(size);
        row2.add(button("World look...", "How the preview world draws the game's image: light map, face shading, water and glass, view distance",
            this::lookDialog));
        row2.add(button("Reload world", "Load the world again (the game saves the place you stand on each time it opens Studio)", () -> {
            worldView = null;
            lastKey = null;
        }));
        JPanel row3 = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        row3.setOpaque(false);
        row3.setBorder(new EmptyBorder(4, 0, 0, 0));
        JComboBox<Show> shows = new JComboBox<>(Show.values());
        String forcedShow = System.getProperty("mosaic.preview.show"); // verification aid
        if (forcedShow != null) {
            showMode = Show.valueOf(forcedShow);
            shows.setSelectedItem(showMode);
        }
        shows.setToolTipText("<html>Image: as the graph draws it<br>False colour: brightness from blue (dark) to red and white (bright)"
            + "<br>Clipping: red where a channel is at 255, blue where all are near 0<br>Brightness, Red, Green, Blue: one value as grey</html>");
        shows.addActionListener(e -> {
            showMode = (Show) shows.getSelectedItem();
            redraw();
        });
        row3.add(shows);
        compareBox.setToolTipText("Compare with frame A (Keep A): side by side (drag the line) or the difference");
        compareBox.addActionListener(e -> {
            compare = (Compare) compareBox.getSelectedItem();
            if (compare != Compare.OFF && reference == null) keepReference();
            redraw();
        });
        row3.add(compareBox);
        row3.add(button("Keep A", "Keep the frame shown now as A to compare against (change something, then look at Split or Difference)",
            this::keepReference));
        JButton views = button("Views", "Saved cameras: save this view, go back to one (keys 1..9), the start view (R)", () -> { });
        views.addActionListener(e -> viewsMenu().show(views, 0, views.getHeight()));
        row3.add(views);
        JButton image = button("Image", "Save the frame as PNG, copy it", () -> { });
        image.addActionListener(e -> imageMenu().show(image, 0, image.getHeight()));
        row3.add(image);
        JPanel rows = new JPanel(new GridLayout(0, 1));
        rows.setOpaque(false);
        rows.add(row2);
        rows.add(row3);
        bar.add(right, BorderLayout.EAST);
        bar.add(rows, BorderLayout.SOUTH);
        root.add(bar, BorderLayout.NORTH);
        view = new JComponent() {
            @Override protected void paintComponent(Graphics g0) {
                Graphics2D g = (Graphics2D) g0;
                g.setColor(BG);
                g.fillRect(0, 0, getWidth(), getHeight());
                BufferedImage img = shown;
                imageRect = null;
                if (img != null) {
                    double k = Math.min(getWidth() / (double) img.getWidth(), getHeight() / (double) img.getHeight());
                    int w = (int) (img.getWidth() * k), h = (int) (img.getHeight() * k);
                    int x0 = (getWidth() - w) / 2, y0 = (getHeight() - h) / 2;
                    imageRect = new Rectangle(x0, y0, w, h);
                    g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, StudioUi.settings.previewSmooth
                        ? RenderingHints.VALUE_INTERPOLATION_BILINEAR : RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
                    g.drawImage(display(img), x0, y0, w, h, null);
                    BufferedImage ref = reference;
                    if (compare == Compare.SPLIT && ref != null) {
                        int sx = x0 + (int) Math.round(w * split);
                        Shape clip = g.getClip();
                        g.clipRect(x0, y0, sx - x0, h);
                        g.drawImage(display(ref), x0, y0, w, h, null);
                        g.setClip(clip);
                        g.setColor(Color.WHITE);
                        g.fillRect(sx - 1, y0, 2, h);
                        g.setFont(UI_S);
                        badge(g, "A", x0 + 6, y0 + 6);
                        badge(g, "now", sx + 6, y0 + 6);
                    } else if (compare != Compare.OFF && ref == null) {
                        g.setFont(UI_S);
                        badge(g, "Keep A first", x0 + 6, y0 + 6);
                    }
                    Point pr = probe;
                    if (pr != null && imageRect.contains(pr)) {
                        int px = Math.clamp((int) ((pr.x - x0) / k), 0, img.getWidth() - 1), py = Math.clamp((int) ((pr.y - y0) / k), 0, img.getHeight() - 1);
                        int c = img.getRGB(px, py), r = (c >> 16) & 255, gg = (c >> 8) & 255, b = c & 255;
                        g.setFont(UI_S);
                        String t = String.format(java.util.Locale.ROOT, "%d, %d   rgb %d %d %d   %.3f %.3f %.3f   brightness %.3f", px, py, r, gg, b,
                            r / 255.0, gg / 255.0, b / 255.0, luma(c));
                        badge(g, t, Math.min(pr.x + 14, getWidth() - g.getFontMetrics().stringWidth(t) - 20), Math.max(4, pr.y - 26));
                    }
                }
                if (message != null) {
                    g.setFont(UI);
                    g.setColor(img == null ? MUTED : new Color(0, 0, 0, 170));
                    FontMetrics fm = g.getFontMetrics();
                    String t = StudioWidgets.Segmented.fit(message, fm, getWidth() - 20);
                    if (img != null) g.fillRoundRect(8, getHeight() - 30, fm.stringWidth(t) + 16, 22, 8, 8);
                    g.setColor(img == null ? MUTED : Color.WHITE);
                    g.drawString(t, img == null ? Math.max(10, (getWidth() - fm.stringWidth(t)) / 2) : 16, img == null ? getHeight() / 2 : getHeight() - 14);
                }
            }
        };
        root.add(view, BorderLayout.CENTER);
        view.setFocusable(true);
        java.awt.event.MouseAdapter fly = new java.awt.event.MouseAdapter() {
            @Override public void mousePressed(java.awt.event.MouseEvent e) {
                dragFrom = e.getPoint();
                view.requestFocusInWindow();
                Rectangle r = imageRect;
                splitDrag = compare == Compare.SPLIT && reference != null && r != null
                    && (e.isShiftDown() || Math.abs(e.getX() - (r.x + r.width * split)) < 8);
            }
            @Override public void mouseReleased(java.awt.event.MouseEvent e) { splitDrag = false; }
            @Override public void mouseDragged(java.awt.event.MouseEvent e) {
                probe = null;
                Rectangle r = imageRect;
                if (splitDrag && r != null) {
                    split = Math.clamp((e.getX() - r.x) / (double) Math.max(1, r.width), 0.0, 1.0);
                    view.repaint();
                    return;
                }
                PreviewWorld.View v = worldView;
                if (v == null || dragFrom == null) return;
                float s = (float) StudioUi.settings.previewLookSpeed, inv = StudioUi.settings.previewInvertY ? -1f : 1f;
                worldView = v.look((e.getX() - dragFrom.x) * s, (e.getY() - dragFrom.y) * s * inv);
                dragFrom = e.getPoint();
                lastKey = null;
            }
            @Override public void mouseMoved(java.awt.event.MouseEvent e) {
                probe = e.getPoint();
                view.repaint();
            }
            @Override public void mouseExited(java.awt.event.MouseEvent e) {
                probe = null;
                view.repaint();
            }
            @Override public void mouseWheelMoved(java.awt.event.MouseWheelEvent e) {
                PreviewWorld.View v = worldView;
                if (v == null) return;
                worldView = v.move(-e.getPreciseWheelRotation() * StudioUi.settings.previewWheelSpeed, 0, 0);
                lastKey = null;
            }
        };
        view.addMouseListener(fly);
        view.addMouseMotionListener(fly);
        view.addMouseWheelListener(fly);
        view.addKeyListener(new java.awt.event.KeyAdapter() {
            @Override public void keyPressed(java.awt.event.KeyEvent e) {
                PreviewWorld.View v = worldView;
                if (v == null) return;
                int code = e.getKeyCode();
                if (code >= java.awt.event.KeyEvent.VK_1 && code <= java.awt.event.KeyEvent.VK_9 && !e.isControlDown()) {
                    var names = new java.util.ArrayList<>(StudioUi.settings.previewViews.keySet());
                    int i = code - java.awt.event.KeyEvent.VK_1;
                    if (i < names.size()) goTo(StudioUi.settings.previewViews.get(names.get(i)));
                    e.consume();
                    return;
                }
                if (code == java.awt.event.KeyEvent.VK_R && startView != null) {
                    worldView = startView;
                    syncTime();
                    lastKey = null;
                    e.consume();
                    return;
                }
                if (code == java.awt.event.KeyEvent.VK_P) {
                    playing = !playing;
                    play.setText(playing ? "Pause" : "Play");
                    e.consume();
                    return;
                }
                double s = StudioUi.settings.previewFlySpeed * (e.isControlDown() ? 3.0 : 1.0);
                PreviewWorld.View n = switch (e.getKeyCode()) {
                    case java.awt.event.KeyEvent.VK_W, java.awt.event.KeyEvent.VK_UP -> v.move(s, 0, 0);
                    case java.awt.event.KeyEvent.VK_S, java.awt.event.KeyEvent.VK_DOWN -> v.move(-s, 0, 0);
                    case java.awt.event.KeyEvent.VK_A, java.awt.event.KeyEvent.VK_LEFT -> v.move(0, -s, 0);
                    case java.awt.event.KeyEvent.VK_D, java.awt.event.KeyEvent.VK_RIGHT -> v.move(0, s, 0);
                    case java.awt.event.KeyEvent.VK_SPACE -> v.move(0, 0, s);
                    case java.awt.event.KeyEvent.VK_SHIFT -> v.move(0, 0, -s);
                    default -> null;
                };
                if (n != null) {
                    worldView = n;
                    lastKey = null;
                    e.consume();
                }
            }
        });
        info.setFont(UI_S);
        info.setBorder(new EmptyBorder(3, 8, 3, 8));
        root.add(info, BorderLayout.SOUTH);
        worker = new Thread(this::loop, "mosaic-gpu-preview");
        worker.setDaemon(true);
        worker.start();
    }

    /** The time slider follows the camera's time of day (without a feedback loop: the slider sets the same time). */
    private void syncTime() {
        PreviewWorld.View v = worldView;
        if (v == null) return;
        int ticks = Math.floorMod(Math.round(v.timeOfDay() * 24000) + 6000, 24000);
        SwingUtilities.invokeLater(() -> { if (Math.abs(time.getValue() - ticks) > 1) time.setValue(ticks); });
    }

    private void redraw() { view.repaint(); }

    private static void badge(Graphics2D g, String t, int x, int y) {
        FontMetrics fm = g.getFontMetrics();
        g.setColor(new Color(0, 0, 0, 170));
        g.fillRoundRect(x, y, fm.stringWidth(t) + 12, fm.getHeight() + 4, 8, 8);
        g.setColor(Color.WHITE);
        g.drawString(t, x + 6, y + 2 + fm.getAscent());
    }

    static double luma(int c) {
        return (0.2126 * ((c >> 16) & 255) + 0.7152 * ((c >> 8) & 255) + 0.0722 * (c & 255)) / 255.0;
    }

    /** The image as the chosen view and comparison show it (cached per frame). */
    private BufferedImage display(BufferedImage img) {
        BufferedImage ref = reference;
        boolean diff = compare == Compare.DIFF && ref != null && ref.getWidth() == img.getWidth() && ref.getHeight() == img.getHeight();
        if (showMode == Show.IMAGE && !diff) return img;
        Object key = java.util.List.of(System.identityHashCode(img), showMode, diff, ref == null ? 0 : System.identityHashCode(ref));
        if (key.equals(displayKey) && displayCache != null) return displayCache;
        int w = img.getWidth(), h = img.getHeight();
        int[] px = img.getRGB(0, 0, w, h, null, 0, w);
        int[] rp = diff ? ref.getRGB(0, 0, w, h, null, 0, w) : null;
        for (int i = 0; i < px.length; i++) {
            int c = px[i];
            if (rp != null) {
                int d = rp[i];
                c = (Math.min(255, Math.abs(((c >> 16) & 255) - ((d >> 16) & 255)) * 4) << 16)
                    | (Math.min(255, Math.abs(((c >> 8) & 255) - ((d >> 8) & 255)) * 4) << 8) | Math.min(255, Math.abs((c & 255) - (d & 255)) * 4);
            }
            px[i] = 0xFF000000 | switch (showMode) {
                case IMAGE -> c;
                case FALSE_COLOUR -> falseColour(luma(c));
                case CLIPPING -> {
                    int r = (c >> 16) & 255, g = (c >> 8) & 255, b = c & 255;
                    if (Math.max(r, Math.max(g, b)) >= 250) yield 0xFF3030;
                    if (Math.max(r, Math.max(g, b)) <= 5) yield 0x3060FF;
                    int l = (int) (luma(c) * 160);
                    yield l << 16 | l << 8 | l;
                }
                case LUMA -> grey((int) Math.round(luma(c) * 255));
                case RED -> grey((c >> 16) & 255);
                case GREEN -> grey((c >> 8) & 255);
                case BLUE -> grey(c & 255);
            };
        }
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        out.setRGB(0, 0, w, h, px, 0, w);
        displayCache = out;
        displayKey = key;
        return out;
    }

    private static int grey(int v) { return v << 16 | v << 8 | v; }

    /** Brightness 0..1 as a heat ramp: dark blue, blue, green, yellow, red, white. */
    static int falseColour(double t) {
        double[][] stops = {{0, 0.02, 0.02, 0.25}, {0.2, 0.05, 0.35, 1.0}, {0.45, 0.05, 0.85, 0.35}, {0.65, 1.0, 0.9, 0.05},
            {0.85, 1.0, 0.2, 0.05}, {1.0, 1.0, 1.0, 1.0}};
        t = Math.clamp(t, 0, 1);
        for (int i = 1; i < stops.length; i++)
            if (t <= stops[i][0]) {
                double f = (t - stops[i - 1][0]) / (stops[i][0] - stops[i - 1][0]);
                int r = (int) Math.round(255 * (stops[i - 1][1] + (stops[i][1] - stops[i - 1][1]) * f));
                int g = (int) Math.round(255 * (stops[i - 1][2] + (stops[i][2] - stops[i - 1][2]) * f));
                int b = (int) Math.round(255 * (stops[i - 1][3] + (stops[i][3] - stops[i - 1][3]) * f));
                return r << 16 | g << 8 | b;
            }
        return 0xFFFFFF;
    }

    private void keepReference() {
        BufferedImage img = shown;
        if (img == null) {
            model.say("No preview frame yet", MUTED);
            return;
        }
        reference = img;
        if (compare == Compare.OFF) compareBox.setSelectedItem(Compare.SPLIT);
        model.say("Kept this frame as A: change the graph and compare (drag the line, or Shift+drag anywhere)", MUTED);
        view.repaint();
    }

    /** Saved cameras: save, go to (1..9), delete; the start view. */
    private JPopupMenu viewsMenu() {
        JPopupMenu m = new JPopupMenu();
        JMenuItem save = new JMenuItem("Save this view...");
        save.addActionListener(e -> {
            PreviewWorld.View v = worldView;
            if (v == null) return;
            String name = JOptionPane.showInputDialog(root, "Name of the view", "View " + (StudioUi.settings.previewViews.size() + 1));
            if (name == null || name.isBlank()) return;
            StudioUi.settings.previewViews.put(name.trim(), new double[] {v.x(), v.y(), v.z(), v.yaw(), v.pitch(), v.timeOfDay()});
            StudioUi.settings.save();
            model.say("Saved view " + name.trim() + " (key " + StudioUi.settings.previewViews.size() + " in the preview)", MUTED);
        });
        m.add(save);
        JMenuItem start = new JMenuItem("Start view   R");
        start.setEnabled(startView != null);
        start.addActionListener(e -> {
            worldView = startView;
            syncTime();
            lastKey = null;
        });
        m.add(start);
        if (!StudioUi.settings.previewViews.isEmpty()) {
            m.addSeparator();
            int k = 1;
            JMenu del = new JMenu("Delete");
            for (var en : StudioUi.settings.previewViews.entrySet()) {
                JMenuItem it = new JMenuItem((k <= 9 ? k + "   " : "    ") + en.getKey());
                double[] d = en.getValue();
                it.addActionListener(e -> goTo(d));
                m.add(it);
                JMenuItem x = new JMenuItem(en.getKey());
                String name = en.getKey();
                x.addActionListener(e -> {
                    StudioUi.settings.previewViews.remove(name);
                    StudioUi.settings.save();
                });
                del.add(x);
                k++;
            }
            m.addSeparator();
            m.add(del);
        }
        return m;
    }

    /** Moves the camera to a saved view (x, y, z, yaw, pitch, time of day). */
    private void goTo(double[] d) {
        PreviewWorld.View v = worldView;
        if (v == null || d == null || d.length < 6) return;
        worldView = new PreviewWorld.View(v.world(), d[0], d[1], d[2], (float) d[3], (float) d[4], (float) d[5], v.atlas(), v.graphSettings(), v.look());
        syncTime();
        lastKey = null;
    }

    private JPopupMenu imageMenu() {
        JPopupMenu m = new JPopupMenu();
        JMenuItem save = new JMenuItem("Save PNG...");
        save.addActionListener(e -> saveImage(false));
        m.add(save);
        JMenuItem saveShown = new JMenuItem("Save PNG as shown (view and compare)...");
        saveShown.addActionListener(e -> saveImage(true));
        m.add(saveShown);
        JMenuItem copy = new JMenuItem("Copy to the clipboard");
        copy.addActionListener(e -> {
            BufferedImage img = shown;
            if (img == null) return;
            copyImage(img);
            model.say("Copied the preview frame (" + img.getWidth() + "x" + img.getHeight() + ")", MUTED);
        });
        m.add(copy);
        m.addSeparator();
        JMenuItem keep = new JMenuItem("Keep as A (compare)");
        keep.addActionListener(e -> keepReference());
        m.add(keep);
        JMenuItem clear = new JMenuItem("Forget A");
        clear.setEnabled(reference != null);
        clear.addActionListener(e -> {
            reference = null;
            compareBox.setSelectedItem(Compare.OFF);
        });
        m.add(clear);
        return m;
    }

    private void saveImage(boolean asShown) {
        BufferedImage img = shown;
        if (img == null) return;
        BufferedImage out = img;
        if (asShown) {
            out = display(img);
            BufferedImage ref = reference;
            if (compare == Compare.SPLIT && ref != null) {
                out = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_RGB);
                Graphics2D g = out.createGraphics();
                g.drawImage(display(img), 0, 0, null);
                int sx = (int) Math.round(img.getWidth() * split);
                g.setClip(0, 0, sx, img.getHeight());
                g.drawImage(display(ref), 0, 0, img.getWidth(), img.getHeight(), null);
                g.dispose();
            }
        }
        JFileChooser fc = new JFileChooser();
        fc.setSelectedFile(new java.io.File("preview-" + java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".png"));
        if (fc.showSaveDialog(root) != JFileChooser.APPROVE_OPTION) return;
        try {
            java.io.File f = fc.getSelectedFile();
            if (!f.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".png")) f = new java.io.File(f.getPath() + ".png");
            ImageIO.write(out, "png", f);
            model.say("Saved " + f, OK);
        } catch (Exception ex) {
            model.say("Could not save: " + ex.getMessage(), BAD);
        }
    }

    static void copyImage(BufferedImage img) {
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new java.awt.datatransfer.Transferable() {
            @Override public java.awt.datatransfer.DataFlavor[] getTransferDataFlavors() {
                return new java.awt.datatransfer.DataFlavor[] {java.awt.datatransfer.DataFlavor.imageFlavor};
            }
            @Override public boolean isDataFlavorSupported(java.awt.datatransfer.DataFlavor f) {
                return java.awt.datatransfer.DataFlavor.imageFlavor.equals(f);
            }
            @Override public Object getTransferData(java.awt.datatransfer.DataFlavor f) throws java.awt.datatransfer.UnsupportedFlavorException {
                if (!isDataFlavorSupported(f)) throw new java.awt.datatransfer.UnsupportedFlavorException(f);
                return img;
            }
        }, null);
    }

    static BufferedImage fit(BufferedImage img, int maxW) {
        int w = Math.min(maxW, img.getWidth()), h = Math.max(1, img.getHeight() * w / img.getWidth());
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(img, 0, 0, w, h, null);
        g.dispose();
        return out;
    }

    /** The preview look (PreviewWorld.LOOK) as a dialog of numbers, saved in Studio settings. */
    private void lookDialog() {
        JPanel p = new JPanel(new GridLayout(0, 2, 6, 4));
        java.util.Map<String, JTextField> fields = new java.util.LinkedHashMap<>();
        for (var s : PreviewWorld.LOOK) {
            JLabel l = new JLabel(s.label());
            l.setToolTipText(s.help());
            double[] v = io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicSettings.value(StudioUi.settings.previewLook, s);
            JTextField f = new JTextField(InspectorPanel.numbers(v), 12);
            f.setToolTipText(s.help());
            p.add(l);
            p.add(f);
            fields.put(s.key(), f);
        }
        JScrollPane sp = new JScrollPane(p);
        sp.setPreferredSize(new Dimension(460, 420));
        Object[] options = {"Apply", "Defaults", "Cancel"};
        int r = JOptionPane.showOptionDialog(root, sp, "Preview world look", JOptionPane.DEFAULT_OPTION, JOptionPane.PLAIN_MESSAGE, null, options, options[0]);
        if (r == 1) StudioUi.settings.previewLook.clear();
        else if (r == 0) {
            for (var s : PreviewWorld.LOOK) {
                double[] v = InspectorPanel.parse(fields.get(s.key()).getText());
                if (v == null || v.length != s.size()) continue;
                if (java.util.Arrays.equals(v, s.def())) StudioUi.settings.previewLook.remove(s.key());
                else StudioUi.settings.previewLook.put(s.key(), v);
            }
        } else return;
        StudioUi.settings.save();
        lastKey = null;
    }

    private void loop() {
        while (alive) {
            try {
                if (!root.isShowing()) {
                    Thread.sleep(300);
                    continue;
                }
                var c = model.compile;
                Source s = source.getSelectedItem();
                boolean infoOn = StudioUi.settings.previewInfo;
                if (info.isVisible() != infoOn) SwingUtilities.invokeLater(() -> info.setVisible(infoOn));
                PreviewWorld.View wv = null;
                BufferedImage input = null;
                if (s == Source.WORLD) {
                    if (worldView == null) {
                        var snap = PreviewWorld.load(StudioLibrary.gameDir(model));
                        worldView = PreviewWorld.View.of(snap);
                        // verification aids: start time (ticks) and pitch of the preview camera
                        Integer forcedTime = Integer.getInteger("mosaic.preview.time");
                        String forcedPitch = System.getProperty("mosaic.preview.pitch");
                        if (forcedTime != null) worldView = worldView.at((float) (Math.floorMod(forcedTime - 6000, 24000) / 24000.0));
                        if (forcedPitch != null) worldView = worldView.look(0f, Float.parseFloat(forcedPitch) - worldView.pitch());
                        startView = worldView;
                        syncTime();
                    }
                    long now = System.nanoTime();
                    double dt = Math.min(0.5, (now - lastLoop) / 1e9);
                    int daySpeed = StudioUi.settings.previewDaySpeed;
                    if (playing && daySpeed > 0) {
                        PreviewWorld.View v0 = worldView;
                        worldView = v0.at((float) (v0.timeOfDay() + dt * daySpeed / 24000.0 - Math.floor(v0.timeOfDay() + dt * daySpeed / 24000.0)));
                        syncTime();
                    }
                    wv = worldView.with(model.doc.settings, StudioUi.settings.previewLook);
                } else input = model.liveBefore != null ? fit(model.liveBefore, 960) : null;
                if (model.rendererDoc()) {
                    show(null, "Renderer graphs set up Bedray itself: they apply in the game (Result shows its frames)");
                    Thread.sleep(400);
                    continue;
                }
                if (model.materialDoc()) {
                    show(null, "Materials preview on the Material panel");
                    Thread.sleep(400);
                    continue;
                }
                if (input == null && wv == null) {
                    show(null, "No game frame yet: use World, or run the game with this pack");
                    Thread.sleep(300);
                    continue;
                }
                if (c == null && model.generated != null && !model.compiling) {
                    SwingUtilities.invokeLater(model::compileNow); // a graph opened without edits has not been compiled yet
                    show(shown, "Compiling...");
                    Thread.sleep(250);
                    continue;
                }
                if (c == null || !c.ok() || c.spirv() == null) {
                    show(shown, model.compiling ? "Compiling..." : "The graph does not compile: see Problems");
                    Thread.sleep(250);
                    continue;
                }
                var runs = model.passRuns;
                Object key = java.util.List.of(System.identityHashCode(c), System.identityHashCode(input), s, System.identityHashCode(runs),
                    wv == null ? 0 : System.identityHashCode(worldView), lookKey());
                if (!playing && key.equals(lastKey)) {
                    Thread.sleep(120);
                    continue;
                }
                lastKey = key;
                synchronized (PreviewPanel.class) {
                    if (gpu == null && gpuError == null) {
                        try {
                            gpu = GpuPreview.create();
                        } catch (Throwable t) {
                            gpuError = "No GPU preview: " + t.getMessage();
                        }
                    }
                }
                if (gpu == null) {
                    show(null, gpuError);
                    Thread.sleep(1000);
                    continue;
                }
                long t0 = System.nanoTime();
                BufferedImage out;
                try {
                    float t = (System.nanoTime() - start) / 1e9f;
                    gpu.worldWidth = StudioUi.settings.previewWidth;
                    gpu.worldHeight = StudioUi.settings.previewHeight;
                    var list = runs != null ? runs : java.util.List.of(new GpuPreview.PassRun(c.spirv(), 1, -1));
                    out = gpu.runChain(list, input, t, frame++, extras(), wv);
                } catch (UnsupportedOperationException e) {
                    show(null, e.getMessage());
                    Thread.sleep(500);
                    continue;
                }
                long ms = (System.nanoTime() - t0) / 1_000_000;
                show(out, null);
                int passes = runs == null ? 1 : runs.size();
                String where = wv == null ? "game frame (no depth)" : PreviewWorld.fromGame(wv.world())
                    ? "world from the game" : "built-in world (open Studio from the game for yours)";
                String gpuLine = gpuProfile(gpu.lastTimings, wv != null);
                SwingUtilities.invokeLater(() -> info.setText(gpu.deviceName + "   -   " + out.getWidth() + "x" + out.getHeight() + "   -   "
                    + (passes > 1 ? passes + " passes, " : "") + ms + " ms" + gpuLine + "   -   " + where));
                lastLoop = System.nanoTime();
                // keep the studio smooth on small GPUs: after a heavy frame wait so the preview takes at most its share of the GPU,
                // and draw rarely while another window is in front
                double share = StudioUi.settings.previewGpuShare / 100.0;
                long rest = Math.max(1000 / StudioUi.settings.previewFps - ms, (long) (ms * (1 - share) / share));
                Window win = SwingUtilities.getWindowAncestor(root);
                if (StudioUi.settings.previewIdleInBackground && win != null && !win.isActive()
                    && java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().getActiveWindow() == null) rest = Math.max(rest, 2000);
                Thread.sleep(Math.max(5, rest));
            } catch (InterruptedException e) {
                return;
            } catch (Throwable t) {
                show(null, "Preview failed: " + t.getMessage());
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }
    }

    private String lookKey() {
        return io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicSettings.toJson(model.doc.settings) + "|"
            + io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicSettings.toJson(StudioUi.settings.previewLook) + "|"
            + StudioUi.settings.previewWidth + "x" + StudioUi.settings.previewHeight;
    }

    /** GPU time of the last run and its three most expensive passes (names from the pass plan), or "". */
    private String gpuProfile(double[] t, boolean world) {
        if (t == null || t.length == 0) return "";
        double total = 0;
        for (double v : t) total += v;
        var plan = model.plan;
        java.util.List<String> names = new java.util.ArrayList<>();
        names.add(world ? "world" : "input");
        if (plan != null) for (var p : plan.all()) names.add(p.name() == null ? "final" : p.name());
        else names.add("final");
        Integer[] order = new Integer[t.length];
        for (int i = 0; i < t.length; i++) order[i] = i;
        java.util.Arrays.sort(order, (a, b) -> Double.compare(t[b], t[a]));
        StringBuilder sb = new StringBuilder(String.format(java.util.Locale.ROOT, " (GPU %.1f ms:", total));
        for (int k = 0; k < Math.min(3, order.length); k++)
            sb.append(String.format(java.util.Locale.ROOT, " %s %.1f", order[k] < names.size() ? names.get(order[k]) : "pass", t[order[k]]));
        return sb.append(")").toString();
    }

    private Path extrasFrom;
    private GpuPreview.Extras extras;

    /** The pack's rt.push, buffer5.bin and texture6.png next to the graph, when it has them. */
    private GpuPreview.Extras extras() {
        Path f = model.doc.file;
        Path dir = f == null ? null : f.getParent();
        if (dir == null) return null;
        if (dir.equals(extrasFrom)) return extras;
        extrasFrom = dir;
        try {
            byte[] push = Files.exists(dir.resolve("rt.push")) ? Files.readAllBytes(dir.resolve("rt.push")) : null;
            byte[] buf = Files.exists(dir.resolve("buffer5.bin")) ? Files.readAllBytes(dir.resolve("buffer5.bin")) : null;
            BufferedImage lut = Files.exists(dir.resolve("texture6.png")) ? ImageIO.read(dir.resolve("texture6.png").toFile()) : null;
            extras = new GpuPreview.Extras(push, buf, lut);
        } catch (Exception e) {
            extras = null;
        }
        return extras;
    }

    private void show(BufferedImage img, String msg) {
        shown = img;
        message = msg;
        SwingUtilities.invokeLater(view::repaint);
    }

    @Override void refresh() { lastKey = null; }

    @Override public void dispose() {
        super.dispose();
        alive = false;
        worker.interrupt();
    }

    public JComponent component() { return root; }
}
