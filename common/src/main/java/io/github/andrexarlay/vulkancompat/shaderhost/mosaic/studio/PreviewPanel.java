package io.github.andrexarlay.vulkancompat.shaderhost.mosaic.studio;

import io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicDocument;
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
    private JToggleButton bypassButton, nodeButton;
    private final Thread worker;
    private volatile boolean alive = true;
    private long start = System.nanoTime();
    private int frame;
    private Object lastKey;

    /** How the image is shown: as it is, or a view that makes brightness, clipping or one channel readable. */
    enum Show {
        IMAGE("Colour"), FALSE_COLOUR("False colour"), CLIPPING("Clipping"), LUMA("Brightness"), RED("Red"), GREEN("Green"), BLUE("Blue");
        final String label;
        Show(String l) { label = l; }
        @Override public String toString() { return label; }
    }

    /** Comparison with a kept frame (A): side by side with a movable split, or the difference made 4x brighter. */
    enum Compare {
        OFF("No compare"), SPLIT("Split: A | now"), DIFF("Difference");
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
    private long atlasChecked;
    /** Node (N): the preview shows the output of the selected node instead of the graph's final picture. */
    volatile boolean nodeMode;
    private String nodeKey;
    private java.util.List<GpuPreview.PassRun> nodeRuns;
    private volatile boolean nodeCompiling;

    /** The selected node's output as a pass (one-pass graphs; compiled in the background, cached per graph and node). */
    private java.util.List<GpuPreview.PassRun> nodeRuns() {
        MosaicDocument.ENode n = model.primary;
        var g = model.analysis.graph();
        if (n == null || g == null || g.multiPass()) return null;
        String key = System.identityHashCode(g) + "|" + n.id;
        if (key.equals(nodeKey)) return nodeRuns;
        if (nodeCompiling) return nodeRuns;
        nodeCompiling = true;
        Thread t = new Thread(() -> {
            java.util.List<GpuPreview.PassRun> made = null;
            try {
                var r = io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicShaderCheck.compile(io.github.andrexarlay.vulkancompat.shaderhost.mosaic
                    .MosaicCodegen.generate(g, new io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicCodegen.Options(n.id, false, true, true), false));
                if (r.ok()) made = java.util.List.of(new GpuPreview.PassRun(r.spirv(), 1, -1));
            } catch (Exception ignored) {
                // no node picture
            }
            nodeRuns = made;
            nodeKey = key;
            nodeCompiling = false;
            lastKey = null;
        }, "mosaic-node-preview");
        t.setDaemon(true);
        t.start();
        return nodeRuns;
    }

    /** Bypass (B): the world without the graph, as the game draws it before your nodes. */
    volatile boolean bypass;
    private static java.util.List<GpuPreview.PassRun> passThrough;

    private static synchronized java.util.List<GpuPreview.PassRun> passThrough() {
        if (passThrough != null) return passThrough;
        try {
            String json = io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicTemplates.all().stream()
                .filter(t -> t.name().equals("Pass-through")).findFirst().orElseThrow().json();
            var r = io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicShaderCheck.compile(io.github.andrexarlay.vulkancompat.shaderhost.mosaic
                .MosaicCodegen.generate(io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicGraph.parse(json),
                    io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicCodegen.Options.PREVIEW, false));
            passThrough = r.ok() ? java.util.List.of(new GpuPreview.PassRun(r.spirv(), 1, -1)) : null;
        } catch (Exception e) {
            passThrough = null;
        }
        return passThrough;
    }
    /** The last moment something changed (camera, graph, look, settings): after a while the preview rests. */
    private volatile long lastActivity = System.currentTimeMillis();
    private Object lastSeenKey;

    PreviewPanel(StudioModel m) {
        super(m);
        root.setBackground(PANEL);
        JPanel bar = new JPanel(new BorderLayout(6, 0));
        bar.setBackground(PANEL2);
        bar.setBorder(new EmptyBorder(5, 6, 5, 6));
        source.select(0);
        source.onChange(s -> lastKey = null);
        // the source switch leads the one row of tools (bar.add below): a short panel keeps its height for the picture
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
        JComboBox<String> size = new JComboBox<>(new String[] {FIT, "320 x 180", "640 x 360", "960 x 540", "1280 x 720", "1600 x 900", "1920 x 1080",
            "2560 x 1440", "Custom..."});
        String current = StudioUi.settings.previewWidth + " x " + StudioUi.settings.previewHeight;
        if (((DefaultComboBoxModel<String>) size.getModel()).getIndexOf(current) < 0) size.insertItemAt(current, 0);
        size.setToolTipText("<html>Preview resolution: the world and every pass of the graph run at this size.<br>"
            + "Fit panel: the panel's own size and shape (Settings &gt; Preview: how many percent of its pixels)</html>");
        size.setSelectedItem(StudioUi.settings.previewFit ? FIT : StudioUi.settings.previewWidth + " x " + StudioUi.settings.previewHeight);
        size.addActionListener(e -> {
            String pick = (String) size.getSelectedItem();
            if (FIT.equals(pick)) {
                StudioUi.settings.previewFit = true;
                StudioUi.settings.save();
                lastKey = null;
                return;
            }
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
            StudioUi.settings.previewFit = false;
            StudioUi.settings.previewWidth = Integer.parseInt(wh[0]);
            StudioUi.settings.previewHeight = Integer.parseInt(wh[1]);
            StudioUi.settings.save();
            lastKey = null;
        });
        row2.add(size);

        JButton worldBtn = button("World", "Which world the preview shows: one of your saves, the place the game captured (J), the built-in landscape", () -> { });
        worldBtn.addActionListener(e -> {
            JPopupMenu wm = worldMenu();
            StudioWidgets.fitToScreen(wm, worldBtn);
            wm.show(worldBtn, 0, worldBtn.getHeight());
        });
        row2.add(worldBtn);

        JPanel row3 = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        row3.setOpaque(false);
        row3.setBorder(new EmptyBorder(4, 0, 0, 0));
        JComboBox<Show> shows = new JComboBox<>(Show.values());
        String forcedShow = System.getProperty("mosaic.preview.show"); // verification aid
        if (forcedShow != null) {
            showMode = Show.valueOf(forcedShow);
            shows.setSelectedItem(showMode);
        }
        shows.setToolTipText("<html>Colour: as the graph draws it<br>False colour: brightness from blue (dark) to red and white (bright)"
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
        JButton keepA = button("Keep A", "Keep the frame shown now as A to compare against (change something, then look at Split or Difference)",
            this::keepReference);
        keepA.setVisible(compare != Compare.OFF); // only while comparing
        compareBox.addActionListener(e -> {
            keepA.setVisible(compare != Compare.OFF);
            keepA.getParent().revalidate();
        });
        row3.add(keepA);
        JToggleButton bypassBtn = toggle("Bypass");
        bypassBtn.setToolTipText("The world without your graph, as the game draws it before your nodes (B). Keep A + Split compares them.");
        bypassBtn.addActionListener(e -> {
            bypass = bypassBtn.isSelected();
            lastKey = null;
        });
        this.bypassButton = bypassBtn;
        row3.add(bypassBtn);
        JToggleButton nodeBtn = toggle("Node");
        nodeBtn.setToolTipText("Show the output of the selected node instead of the final picture (N); one-pass graphs");
        nodeBtn.addActionListener(e -> {
            nodeMode = nodeBtn.isSelected();
            lastKey = null;
        });
        this.nodeButton = nodeBtn;
        row3.add(nodeBtn);
        JButton views = button("Views", "Saved cameras: save this view, go back to one (keys 1..9), the start view (R)", () -> { });
        views.addActionListener(e -> {
            JPopupMenu vm = viewsMenu();
            StudioWidgets.fitToScreen(vm, views);
            vm.show(views, 0, views.getHeight());
        });
        row3.add(views);
        JButton image = button("Export", "Save the frame as PNG, copy it, make it the project's picture", () -> { });
        image.addActionListener(e -> imageMenu().show(image, 0, image.getHeight()));
        row3.add(image);
        // one flowing row of tools: a single line on a wide panel, wrapping on a narrow one (small screens keep the picture big)
        // one row of tools under the source switch; what a narrow panel has no room for waits behind »
        StudioWidgets.OverflowBar rows = new StudioWidgets.OverflowBar(4);
        rows.setBorder(new EmptyBorder(4, 0, 0, 0));
        java.util.List<Component> order = new java.util.ArrayList<>();
        rows.tool(source);
        for (Component comp : row3.getComponents()) order.add(comp);
        for (Component comp : row2.getComponents()) order.add(comp);
        // the most used first: Bypass, Node, Views, World, size, view mode, compare..., time last
        order.sort(java.util.Comparator.comparingInt(c -> {
            String t = c instanceof AbstractButton ab ? ab.getText() : c instanceof JLabel jl ? jl.getText() : "";
            return switch (t) {
                case "Bypass" -> 0; case "Node" -> 1; case "Views" -> 2; case "World" -> 3; case "Export" -> 9; case "Time" -> 10;
                default -> c == time ? 11 : c instanceof JComboBox<?> ? 5 : 8;
            };
        }));
        for (Component comp : order) { // compact: small text and margins, so more of the picture shows
            comp.setFont(UI_S);
            if (comp instanceof AbstractButton ab) ab.setMargin(new Insets(2, 8, 2, 8));
            rows.tool(comp);
        }
        rows.setBorder(null);
        bar.add(right, BorderLayout.EAST);
        bar.add(rows, BorderLayout.CENTER);
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
                lastActivity = System.currentTimeMillis();
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
                if (code == java.awt.event.KeyEvent.VK_N && nodeButton != null) {
                    nodeButton.doClick();
                    e.consume();
                    return;
                }
                if (code == java.awt.event.KeyEvent.VK_B && bypassButton != null) {
                    bypassButton.doClick();
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
        if (Boolean.getBoolean("mosaic.selfshot.look")) { // verification aid: the World look editor open
            javax.swing.Timer lt = new javax.swing.Timer(4000, e -> lookDialog());
            lt.setRepeats(false);
            lt.start();
        }
        worker = new Thread(this::loop, "mosaic-gpu-preview");
        worker.setDaemon(true);
        worker.start();
    }

    /** A toggle in the studio's look: dark when off, the accent when on. */
    private static JToggleButton toggle(String text) {
        JToggleButton t = new JToggleButton(text);
        t.setFont(UI_S);
        t.setFocusable(false);
        t.setMargin(new Insets(2, 8, 2, 8));
        t.setContentAreaFilled(false);
        t.setOpaque(true);
        Runnable paint = () -> {
            t.setBackground(t.isSelected() ? StudioWidgets.mix(PANEL2, SELECT, 0.45) : BUTTON);
            t.setForeground(t.isSelected() ? Color.WHITE : TEXT);
            t.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(t.isSelected() ? SELECT : EDGE),
                new EmptyBorder(3, 8, 3, 8)));
        };
        paint.run();
        t.addItemListener(e -> paint.run());
        return t;
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
        StudioSettings st = StudioUi.settings;
        key = java.util.List.of(key, st.previewDiffGain, st.previewClipHigh, st.previewClipLow, st.previewFalseColourMax);
        if (key.equals(displayKey) && displayCache != null) return displayCache;
        double gain = st.previewDiffGain;
        int hi = st.previewClipHigh, lo = st.previewClipLow;
        double fcMax = st.previewFalseColourMax;
        int w = img.getWidth(), h = img.getHeight();
        int[] px = img.getRGB(0, 0, w, h, null, 0, w);
        int[] rp = diff ? ref.getRGB(0, 0, w, h, null, 0, w) : null;
        for (int i = 0; i < px.length; i++) {
            int c = px[i];
            if (rp != null) {
                int d = rp[i];
                c = ((int) Math.min(255, Math.abs(((c >> 16) & 255) - ((d >> 16) & 255)) * gain) << 16)
                    | ((int) Math.min(255, Math.abs(((c >> 8) & 255) - ((d >> 8) & 255)) * gain) << 8)
                    | (int) Math.min(255, Math.abs((c & 255) - (d & 255)) * gain);
            }
            px[i] = 0xFF000000 | switch (showMode) {
                case IMAGE -> c;
                case FALSE_COLOUR -> falseColour(luma(c) / fcMax);
                case CLIPPING -> {
                    int r = (c >> 16) & 255, g = (c >> 8) & 255, b = c & 255;
                    if (Math.max(r, Math.max(g, b)) >= hi) yield 0xFF3030;
                    if (Math.max(r, Math.max(g, b)) <= lo) yield 0x3060FF;
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

    /** Which world: your saves (newest first), another save folder, the game's capture, the built-in landscape. */
    private JPopupMenu worldMenu() {
        JPopupMenu m = new JPopupMenu();
        String cur = StudioUi.settings.previewWorld;
        java.util.function.BiConsumer<String, String> add = (label, choice) -> {
            JCheckBoxMenuItem it = new JCheckBoxMenuItem(label, choice.equals(cur));
            it.addActionListener(e -> {
                StudioUi.settings.previewWorld = choice;
                StudioUi.settings.save();
                worldView = null;
                lastKey = null;
            });
            m.add(it);
        };
        add.accept("Automatic: the game's capture, else built in", "");
        add.accept("The place the game captured (J in the game)", "game");
        add.accept("Built-in landscape", "builtin");
        var saves = SaveWorld.saves(StudioLibrary.gameDir(model));
        if (!saves.isEmpty()) {
            m.addSeparator();
            JLabel h = label("  Your worlds (around where you stand)", FAINT);
            h.setFont(UI_S);
            m.add(h);
            for (java.nio.file.Path s : saves) add.accept(s.getFileName().toString(), s.toString());
        }
        m.addSeparator();
        JMenuItem lookItem = new JMenuItem("World look (light, water, clouds, rain, models)...");
        lookItem.addActionListener(e -> lookDialog());
        m.add(lookItem);
        JMenuItem reload = new JMenuItem("Reload the world");
        reload.addActionListener(e -> {
            worldView = null;
            lastKey = null;
        });
        m.add(reload);
        m.addSeparator();
        JMenuItem other = new JMenuItem("Another save folder...");
        other.addActionListener(e -> {
            StudioChooser fc = new StudioChooser(StudioLibrary.gameDir(model) == null ? null : StudioLibrary.gameDir(model).resolve("saves").toFile());
            fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            fc.setDialogTitle("A Minecraft world folder (with level.dat)");
            if (fc.showOpenDialog(root) != JFileChooser.APPROVE_OPTION) return;
            java.nio.file.Path p = fc.getSelectedFile().toPath();
            if (!SaveWorld.isSave(p)) {
                model.say("Not a Minecraft world: " + p + " has no level.dat", WARN);
                return;
            }
            StudioUi.settings.previewWorld = p.toString();
            StudioUi.settings.save();
            worldView = null;
            lastKey = null;
        });
        m.add(other);
        return m;
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
        JMenuItem proj = new JMenuItem("Use as the project picture");
        proj.addActionListener(e -> {
            java.nio.file.Path pack = ProjectInfo.packOf(model.doc.file);
            BufferedImage img = shown;
            if (pack == null || img == null) {
                model.say("Open a project (a pack's mosaic.json) to give it a picture", WARN);
                return;
            }
            try {
                ProjectInfo.load(pack).setPicture(img);
                model.say("The project picture is now this frame (pack.png)", OK);
            } catch (Exception ex) {
                model.say("Could not save the picture: " + ex.getMessage(), BAD);
            }
        });
        m.add(proj);
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
        StudioChooser fc = new StudioChooser();
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

    /** Sections of the World look editor (key -> tab); keys not listed go to "More". */
    private static final String[][] LOOK_TABS = {
        {"Light", "lightCurve,dayRise,dayOffset,nightLevel,brightness,nightSkyColor,blockColor,blockBoost,ambient,smoothLighting,aoStrength,faceTop,faceBottom,faceX,faceZ"},
        {"Sky and weather", "vanillaSky,sunsetGlow,stars,moonSharpness,moonBrightness,clouds,cloudHeight,cloudThickness,cloudSpeed,cloudOpacity,fog,fogStart,rain,rainOpacity,rainSpeed,rainDarken,snowTemperature,snowSpeed"},
        {"Water and glass", "waterOpacity,waterReflection,glassOpacity,cutout"},
        {"World", "models,entityShadows,animate,maxDistance,farDistance,farStep,canopyDepth,trunkWidth"}};

    /** Ranges of the look numbers for their sliders. */
    private static final java.util.Map<String, double[]> LOOK_RANGES = java.util.Map.ofEntries(
        java.util.Map.entry("maxDistance", new double[] {32, 512}), java.util.Map.entry("farDistance", new double[] {0, 4000}),
        java.util.Map.entry("canopyDepth", new double[] {0, 16}), java.util.Map.entry("trunkWidth", new double[] {0, 1}),
        java.util.Map.entry("farStep", new double[] {0.002, 0.05}), java.util.Map.entry("lightCurve", new double[] {0, 8}),
        java.util.Map.entry("dayRise", new double[] {0, 6}), java.util.Map.entry("dayOffset", new double[] {0, 1.5}),
        java.util.Map.entry("nightLevel", new double[] {0, 1}), java.util.Map.entry("blockBoost", new double[] {0, 3}),
        java.util.Map.entry("ambient", new double[] {0, 0.3}), java.util.Map.entry("faceTop", new double[] {0, 1}),
        java.util.Map.entry("faceBottom", new double[] {0, 1}), java.util.Map.entry("faceX", new double[] {0, 1}),
        java.util.Map.entry("faceZ", new double[] {0, 1}), java.util.Map.entry("waterOpacity", new double[] {0, 1}),
        java.util.Map.entry("waterReflection", new double[] {0, 1}), java.util.Map.entry("glassOpacity", new double[] {0, 1}),
        java.util.Map.entry("cutout", new double[] {0, 1}), java.util.Map.entry("moonSharpness", new double[] {50, 4000}),
        java.util.Map.entry("moonBrightness", new double[] {0, 6}), java.util.Map.entry("aoStrength", new double[] {0, 1}),
        java.util.Map.entry("fogStart", new double[] {0, 1}), java.util.Map.entry("cloudHeight", new double[] {64, 320}),
        java.util.Map.entry("cloudSpeed", new double[] {0, 5}), java.util.Map.entry("entityShadows", new double[] {0, 1}), java.util.Map.entry("cloudThickness", new double[] {0, 12}), java.util.Map.entry("cloudOpacity", new double[] {0, 1}),
        java.util.Map.entry("rainOpacity", new double[] {0, 1}), java.util.Map.entry("rainSpeed", new double[] {0, 5}),
        java.util.Map.entry("rainDarken", new double[] {0, 1}), java.util.Map.entry("sunsetGlow", new double[] {0, 2}),
        java.util.Map.entry("stars", new double[] {0, 3}), java.util.Map.entry("snowTemperature", new double[] {-0.5, 1}),
        java.util.Map.entry("snowSpeed", new double[] {0, 1}), java.util.Map.entry("brightness", new double[] {0, 1}));

    /** Look numbers that are switches. */
    private static final java.util.Set<String> LOOK_SWITCHES = java.util.Set.of("smoothLighting", "models", "fog", "clouds", "animate", "vanillaSky", "brickSkip");

    private JDialog lookWindow;
    private final javax.swing.Timer lookSave = new javax.swing.Timer(600, e -> StudioUi.settings.save());

    /**
     * World look: every number of how the preview world is drawn (light map, sky and weather, water and glass, models,
     * far land) in tabs, with sliders, switches and a reset per value. Changes show in the preview at once.
     */
    private void lookDialog() {
        if (lookWindow != null && lookWindow.isDisplayable()) {
            lookWindow.toFront();
            return;
        }
        lookSave.setRepeats(false);
        JDialog d = new JDialog(SwingUtilities.getWindowAncestor(root), "World look");
        lookWindow = d;
        JTabbedPane tabs = new JTabbedPane();
        tabs.setFont(UI);
        tabs.setBackground(PANEL2);
        tabs.setForeground(TEXT);
        java.util.Set<String> placed = new java.util.HashSet<>();
        java.util.List<Runnable> refreshers = new java.util.ArrayList<>();
        for (String[] tab : LOOK_TABS) {
            JPanel col = lookColumn();
            for (String key : tab[1].split(",")) {
                var s = PreviewWorld.LOOK.stream().filter(x -> x.key().equals(key)).findFirst().orElse(null);
                if (s == null) continue;
                placed.add(key);
                refreshers.add(lookRow(col, s));
            }
            col.add(Box.createVerticalGlue());
            tabs.addTab(tab[0], lookScroll(col));
        }
        JPanel more = lookColumn();
        for (var s : PreviewWorld.LOOK) if (!placed.contains(s.key())) refreshers.add(lookRow(more, s));
        if (more.getComponentCount() > 0) {
            more.add(Box.createVerticalGlue());
            tabs.addTab("More", lookScroll(more));
        }
        JPanel foot = new JPanel(new BorderLayout());
        foot.setBackground(PANEL2);
        foot.setBorder(new EmptyBorder(8, 12, 8, 12));
        JLabel hint = label("Changes show in the preview at once and are kept in Studio settings.", FAINT);
        hint.setFont(UI_S);
        foot.add(hint, BorderLayout.WEST);
        JPanel btns = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        btns.setOpaque(false);
        btns.add(button("All to defaults", "Every look value back to the game-like default", () -> {
            StudioUi.settings.previewLook.clear();
            lookChanged();
            refreshers.forEach(Runnable::run);
        }));
        btns.add(button("Close", null, d::dispose));
        foot.add(btns, BorderLayout.EAST);
        JPanel rootP = new JPanel(new BorderLayout());
        rootP.setBackground(BG);
        rootP.add(tabs, BorderLayout.CENTER);
        rootP.add(foot, BorderLayout.SOUTH);
        d.setContentPane(rootP);
        Rectangle scr = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        d.setSize(Math.min(700, scr.width - 40), Math.min(620, scr.height - 60));
        d.setLocationRelativeTo(root);
        d.setVisible(true);
    }

    private static JPanel lookColumn() {
        JPanel col = new JPanel();
        col.setLayout(new BoxLayout(col, BoxLayout.Y_AXIS));
        col.setBackground(BG);
        col.setBorder(new EmptyBorder(10, 14, 10, 14));
        return col;
    }

    private static JScrollPane lookScroll(JPanel col) {
        JScrollPane sp = new JScrollPane(col, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        sp.setBorder(null);
        sp.getVerticalScrollBar().setUnitIncrement(24);
        darken(sp);
        return sp;
    }

    private void lookChanged() {
        lastKey = null;
        lookSave.restart();
    }

    private void lookSet(io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicSettings.Setting s, double[] v) {
        if (java.util.Arrays.equals(v, s.def())) StudioUi.settings.previewLook.remove(s.key());
        else StudioUi.settings.previewLook.put(s.key(), v);
        lookChanged();
    }

    /** One look value: label and help, then a switch, a choice, a slider with its number, or a colour; reset. Returns its refresher. */
    private Runnable lookRow(JPanel col, io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicSettings.Setting s) {
        JPanel row = new JPanel(new BorderLayout(10, 2));
        row.setOpaque(false);
        row.setBorder(new EmptyBorder(6, 0, 6, 0));
        JLabel name = label(s.label(), TEXT);
        name.setFont(UI_B);
        JLabel help = new JLabel("<html><div style='width:230px'>" + esc(s.help()) + "</div></html>");
        help.setForeground(FAINT);
        help.setFont(UI_S);
        JPanel text = new JPanel(new BorderLayout(0, 2));
        text.setOpaque(false);
        text.add(name, BorderLayout.NORTH);
        text.add(help, BorderLayout.CENTER);
        row.add(text, BorderLayout.CENTER);
        JButton reset = button("Reset", "Back to " + InspectorPanel.numbers(s.def()), () -> { });
        reset.setFont(UI_S);
        reset.setMargin(new Insets(1, 6, 1, 6));
        JPanel control = new JPanel(new BorderLayout(6, 0));
        control.setOpaque(false);
        Runnable refresh;
        double[] cur = io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicSettings.value(StudioUi.settings.previewLook, s);
        if (LOOK_SWITCHES.contains(s.key())) {
            JCheckBox box = new JCheckBox("", cur[0] > 0.5);
            box.setOpaque(false);
            box.addActionListener(e -> lookSet(s, new double[] {box.isSelected() ? 1 : 0}));
            control.add(box, BorderLayout.CENTER);
            refresh = () -> box.setSelected(io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicSettings.value(StudioUi.settings.previewLook, s)[0] > 0.5);
        } else if (s.key().equals("rain")) {
            JComboBox<String> box = new JComboBox<>(new String[] {"Never", "When the world rains", "Always"});
            box.setSelectedIndex(Math.clamp((int) Math.round(cur[0]), 0, 2));
            box.setFont(UI_S);
            box.addActionListener(e -> lookSet(s, new double[] {box.getSelectedIndex()}));
            control.add(box, BorderLayout.CENTER);
            refresh = () -> box.setSelectedIndex(Math.clamp((int) Math.round(io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicSettings.value(
                StudioUi.settings.previewLook, s)[0]), 0, 2));
        } else if (s.size() == 3) {
            JButton swatch = new JButton();
            swatch.setPreferredSize(new Dimension(70, 24));
            Runnable paint = () -> {
                double[] v = io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicSettings.value(StudioUi.settings.previewLook, s);
                swatch.setBackground(new Color((float) Math.clamp(v[0], 0, 1), (float) Math.clamp(v[1], 0, 1), (float) Math.clamp(v[2], 0, 1)));
            };
            paint.run();
            swatch.addActionListener(e -> {
                Color c = JColorChooser.showDialog(row, s.label(), swatch.getBackground());
                if (c == null) return;
                lookSet(s, new double[] {c.getRed() / 255.0, c.getGreen() / 255.0, c.getBlue() / 255.0});
                paint.run();
            });
            control.add(swatch, BorderLayout.CENTER);
            refresh = paint;
        } else {
            double[] range = LOOK_RANGES.getOrDefault(s.key(), new double[] {0, Math.max(1, s.def()[0] * 4)});
            JSlider sl = new JSlider(0, 1000, (int) Math.round(Math.clamp((cur[0] - range[0]) / (range[1] - range[0]), 0, 1) * 1000));
            sl.setOpaque(false);
            sl.setPreferredSize(new Dimension(150, 22));
            JTextField num = new JTextField(InspectorPanel.numbers(cur), 6);
            num.setFont(UI_S);
            boolean[] quiet = {false};
            sl.addChangeListener(e -> {
                if (quiet[0]) return;
                double v = range[0] + (range[1] - range[0]) * sl.getValue() / 1000.0;
                v = Double.parseDouble(String.format(java.util.Locale.ROOT, "%.4g", v));
                num.setText(InspectorPanel.numbers(new double[] {v}));
                lookSet(s, new double[] {v});
            });
            num.addActionListener(e -> {
                double[] v = InspectorPanel.parse(num.getText());
                if (v == null || v.length != 1) return;
                lookSet(s, v);
                quiet[0] = true;
                sl.setValue((int) Math.round(Math.clamp((v[0] - range[0]) / (range[1] - range[0]), 0, 1) * 1000));
                quiet[0] = false;
            });
            control.add(sl, BorderLayout.CENTER);
            control.add(num, BorderLayout.EAST);
            refresh = () -> {
                double[] v = io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicSettings.value(StudioUi.settings.previewLook, s);
                quiet[0] = true;
                sl.setValue((int) Math.round(Math.clamp((v[0] - range[0]) / (range[1] - range[0]), 0, 1) * 1000));
                num.setText(InspectorPanel.numbers(v));
                quiet[0] = false;
            };
        }
        Runnable fin = refresh;
        reset.addActionListener(e -> {
            StudioUi.settings.previewLook.remove(s.key());
            lookChanged();
            fin.run();
        });
        control.add(reset, BorderLayout.WEST);
        JPanel right = new JPanel(new BorderLayout());
        right.setOpaque(false);
        right.add(control, BorderLayout.NORTH);
        right.setPreferredSize(new Dimension(290, 30));
        right.setMinimumSize(new Dimension(290, 30));
        row.add(right, BorderLayout.EAST);
        row.setAlignmentX(0);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
        col.add(row);
        JSeparator sep = new JSeparator();
        sep.setForeground(EDGE);
        sep.setMaximumSize(new Dimension(Integer.MAX_VALUE, 1));
        col.add(sep);
        return fin;
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
                        String choice = StudioUi.settings.previewWorld;
                        if (choice != null && !choice.isBlank() && !choice.equals("builtin") && !choice.equals("game"))
                            show(shown, "Loading the world " + java.nio.file.Path.of(choice).getFileName() + "...");
                        int wr = io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicSettings.intValue(model.doc.settings, "worldRadius", 8, 256);
                        int wh = io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicSettings.intValue(model.doc.settings, "worldHeight", 16, 256);
                        SaveWorld.farSize = StudioUi.settings.previewFarSize;
                        PreviewBlocks.biomeTemperature = StudioUi.settings.previewBiomeTemperature;
                        PreviewBlocks.biomeDownfall = StudioUi.settings.previewBiomeDownfall;
                        PreviewWorld.lastError = null;
                        var snap = PreviewWorld.load(StudioLibrary.gameDir(model), choice, wr * 2, wh, wr * 2);
                        if (PreviewWorld.lastError != null) model.say(PreviewWorld.lastError, WARN);
                        worldView = PreviewWorld.View.of(snap, StudioLibrary.gameDir(model));
                        atlasChecked = System.nanoTime();
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
                    // resource packs or materials changed (Mosaic Materials, options.txt): the blocks get their new textures
                    if (System.nanoTime() - atlasChecked > 2_000_000_000L) {
                        atlasChecked = System.nanoTime();
                        PreviewWorld.View v1 = worldView;
                        var t = v1.blocks();
                        PreviewBlocks.Atlas fresh = PreviewBlocks.atlasFor(v1.world(), StudioLibrary.gameDir(model));
                        if (fresh != v1.atlas()) {
                            worldView = v1.withAtlas(fresh);
                            model.say("Preview: textures reloaded (resource packs / materials changed)", MUTED);
                        }
                    }
                    wv = worldView.with(model.doc.settings, StudioUi.settings.previewLook);
                } else input = model.liveBefore != null ? fit(model.liveBefore, 960) : null;
                if (model.rendererDoc()) {
                    show(null, "Renderer graphs set up Bedray itself: they apply in the game (Result shows its frames)");
                    Thread.sleep(400);
                    continue;
                }
                // a material: the world without a shader graph, its blocks from the resource packs (Mosaic Materials included),
                // so a saved material shows on the world (the textures reload when the pack changes)
                boolean materialWorld = model.materialDoc() && wv != null && passThrough() != null;
                if (model.materialDoc() && !materialWorld) {
                    show(null, "Materials preview on the Material panel");
                    Thread.sleep(400);
                    continue;
                }
                if (input == null && wv == null) {
                    show(null, "No game frame yet: use World, or run the game with this pack");
                    Thread.sleep(300);
                    continue;
                }
                if (!materialWorld && c == null && model.generated != null && !model.compiling) {
                    SwingUtilities.invokeLater(model::compileNow); // a graph opened without edits has not been compiled yet
                    show(shown, "Compiling...");
                    Thread.sleep(250);
                    continue;
                }
                if (!materialWorld && (c == null || !c.ok() || c.spirv() == null)) {
                    show(shown, model.compiling ? "Compiling..." : "The graph does not compile: see Problems");
                    Thread.sleep(250);
                    continue;
                }
                var runs = model.passRuns;
                Object key = java.util.List.of(System.identityHashCode(c), System.identityHashCode(input), s, System.identityHashCode(runs),
                    nodeMode ? String.valueOf(nodeKey) + (model.primary == null ? "" : model.primary.id) : "", bypass,
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
                    int[] wh = renderSize();
                    gpu.worldWidth = wh[0];
                    gpu.worldHeight = wh[1];
                    var list = (bypass || materialWorld) && passThrough() != null ? passThrough()
                        : runs != null ? runs : java.util.List.of(new GpuPreview.PassRun(c.spirv(), 1, -1));
                    if (!bypass && !materialWorld && nodeMode) {
                        var nr = nodeRuns();
                        if (nr != null) list = nr;
                    }
                    out = gpu.runChain(list, input, t, frame++, extras(), wv);
                } catch (UnsupportedOperationException e) {
                    show(null, e.getMessage());
                    Thread.sleep(500);
                    continue;
                }
                long ms = (System.nanoTime() - t0) / 1_000_000;
                show(out, null);
                int passes = runs == null ? 1 : runs.size();
                String where = wv == null ? "game frame (no depth)" : PreviewWorld.originOf(wv.world())
                    + (wv.blocks() != null ? ", block textures" : ", material textures");
                String gpuLine = gpuProfile(gpu.lastTimings, wv != null);
                String full = gpu.deviceName + "   -   " + out.getWidth() + "x" + out.getHeight() + "   -   "
                    + (passes > 1 ? passes + " passes, " : "") + ms + " ms" + gpuLine + "   -   " + where;
                String brief = out.getWidth() + " × " + out.getHeight() + "  ·  " + ms + " ms  ·  " + PreviewWorld.originOf(wv == null ? null : wv.world());
                SwingUtilities.invokeLater(() -> {
                    info.setText(StudioUi.settings.detailedStatus ? full : brief);
                    info.setToolTipText(full);
                });
                lastLoop = System.nanoTime();
                // keep the studio smooth on small GPUs: after a heavy frame wait so the preview takes at most its share of the GPU,
                // and draw rarely while another window is in front
                // idle: nothing changed for a while (no camera move, no edit) -> a slow frame rate saves the laptop's GPU
                Object activityKey = java.util.List.of(System.identityHashCode(c), System.identityHashCode(worldView), lookKey(), showMode, compare);
                if (!activityKey.equals(lastSeenKey)) {
                    lastSeenKey = activityKey;
                    lastActivity = System.currentTimeMillis();
                }
                boolean idle = StudioUi.settings.previewIdleSeconds > 0
                    && System.currentTimeMillis() - lastActivity > StudioUi.settings.previewIdleSeconds * 1000L;
                double share = StudioUi.settings.previewGpuShare / 100.0;
                long rest = Math.max(1000 / StudioUi.settings.previewFps - ms, (long) (ms * (1 - share) / share));
                Window win = SwingUtilities.getWindowAncestor(root);
                if (StudioUi.settings.previewIdleInBackground && win != null && !win.isActive()
                    && java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().getActiveWindow() == null)
                    rest = Math.max(rest, StudioUi.settings.previewBackgroundMs);
                if (idle) rest = Math.max(rest, 1000 / Math.max(1, StudioUi.settings.previewIdleFps));
                Thread.sleep(Math.max(StudioUi.settings.previewMinPauseMs, rest));
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
            + java.util.Arrays.toString(renderSize());
    }

    static final String FIT = "Fit panel";

    /** The size the preview renders at: the panel's own pixels (HiDPI included) times the scale, or the fixed size. */
    int[] renderSize() {
        StudioSettings s = StudioUi.settings;
        JComponent v = view;
        if (!s.previewFit || v == null) return new int[] {s.previewWidth, s.previewHeight};
        if (v.getWidth() < 16 || v.getHeight() < 16) return new int[] {160, 96}; // a squeezed panel: the smallest frame
        double dpi = 1;
        GraphicsConfiguration gc = v.getGraphicsConfiguration();
        if (gc != null) dpi = gc.getDefaultTransform().getScaleX();
        double k = dpi * s.previewScale / 100.0;
        double w = v.getWidth() * k, h = v.getHeight() * k;
        double over = Math.max(w, h) / s.previewMaxSide;
        if (over > 1) { w /= over; h /= over; }
        // multiples of 8 (whole work groups), so a resize by a pixel or two does not rebuild every buffer
        int iw = Math.max(160, (int) Math.round(w / 8) * 8), ih = Math.max(96, (int) Math.round(h / 8) * 8);
        return new int[] {iw, ih};
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

    /** The last frame any preview drew (File > This project > Use the preview frame as the project picture, thumbnails). */
    static volatile BufferedImage lastFrame;

    private void show(BufferedImage img, String msg) {
        if (img != null) lastFrame = img;
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
