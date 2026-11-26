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
    private final Thread worker;
    private volatile boolean alive = true;
    private long start = System.nanoTime();
    private int frame;
    private Object lastKey;

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
        JButton play = button("Pause", "Animate time (time, noise, grain...)", () -> { });
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
        JComboBox<String> size = new JComboBox<>(new String[] {"640 x 360", "960 x 540", "1280 x 720", "1920 x 1080"});
        size.setToolTipText("Preview resolution: the world and every pass of the graph run at this size");
        size.setSelectedItem(StudioUi.settings.previewWidth + " x " + StudioUi.settings.previewHeight);
        size.addActionListener(e -> {
            String[] wh = ((String) size.getSelectedItem()).split(" x ");
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
        bar.add(right, BorderLayout.EAST);
        bar.add(row2, BorderLayout.SOUTH);
        root.add(bar, BorderLayout.NORTH);
        view = new JComponent() {
            @Override protected void paintComponent(Graphics g0) {
                Graphics2D g = (Graphics2D) g0;
                g.setColor(BG);
                g.fillRect(0, 0, getWidth(), getHeight());
                BufferedImage img = shown;
                if (img != null) {
                    double k = Math.min(getWidth() / (double) img.getWidth(), getHeight() / (double) img.getHeight());
                    int w = (int) (img.getWidth() * k), h = (int) (img.getHeight() * k);
                    g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                    g.drawImage(img, (getWidth() - w) / 2, (getHeight() - h) / 2, w, h, null);
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
            }
            @Override public void mouseDragged(java.awt.event.MouseEvent e) {
                PreviewWorld.View v = worldView;
                if (v == null || dragFrom == null) return;
                worldView = v.look((e.getX() - dragFrom.x) * 0.3f, (e.getY() - dragFrom.y) * 0.3f);
                dragFrom = e.getPoint();
                lastKey = null;
            }
            @Override public void mouseWheelMoved(java.awt.event.MouseWheelEvent e) {
                PreviewWorld.View v = worldView;
                if (v == null) return;
                worldView = v.move(-e.getPreciseWheelRotation() * 1.5, 0, 0);
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
                double s = e.isControlDown() ? 2.0 : 0.6;
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
                        int ticks = Math.floorMod(Math.round(worldView.timeOfDay() * 24000) + 6000, 24000);
                        SwingUtilities.invokeLater(() -> time.setValue(ticks));
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
                Thread.sleep(Math.max(15, 50 - ms));
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
