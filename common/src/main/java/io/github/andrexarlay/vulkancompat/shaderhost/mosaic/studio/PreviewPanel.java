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
 * Preview: the shader of the open graph run by the studio itself on the GPU, over the game's last frame, a built-in test
 * scene or any picture, animated. Independent of the game (Result shows the game's own render).
 */
final class PreviewPanel extends Panels.Listening {

    enum Source { GAME, SCENE, IMAGE }

    private static GpuPreview gpu;
    private static String gpuError;

    final JPanel root = new JPanel(new BorderLayout());
    final StudioWidgets.Segmented<Source> source = new StudioWidgets.Segmented<Source>()
        .add(Source.GAME, "Game frame", StudioWidgets.glyph("vanilla", new Color(0x7FB069)), "The last frame the game sent (before the shader)")
        .add(Source.SCENE, "Test scene", StudioWidgets.glyph("shader", new Color(0x5AA0E0)), "A built-in scene: sky, sun, water, blocks")
        .add(Source.IMAGE, "Image", StudioWidgets.glyph("frame", null), "Any picture or screenshot");
    final JLabel info = label("", FAINT);
    final JComponent view;
    volatile BufferedImage shown;
    volatile String message = "Starting the GPU...";
    BufferedImage picked;
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
        source.select(m.liveBefore != null ? 0 : 1);
        source.onChange(s -> {
            if (s == Source.IMAGE && picked == null) pickImage();
            lastKey = null;
        });
        bar.add(source, BorderLayout.CENTER);
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        right.setOpaque(false);
        JButton play = button("Pause", "Animate time (time, noise, grain...)", () -> { });
        play.addActionListener(e -> {
            playing = !playing;
            play.setText(playing ? "Pause" : "Play");
        });
        right.add(play);
        right.add(button("Image...", "Preview over a picture of your own", this::pickImage));
        bar.add(right, BorderLayout.EAST);
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
        info.setFont(UI_S);
        info.setBorder(new EmptyBorder(3, 8, 3, 8));
        root.add(info, BorderLayout.SOUTH);
        worker = new Thread(this::loop, "mosaic-gpu-preview");
        worker.setDaemon(true);
        worker.start();
    }

    private void pickImage() {
        JFileChooser fc = new JFileChooser();
        fc.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("Images", "png", "jpg", "jpeg", "bmp"));
        if (fc.showOpenDialog(root) != JFileChooser.APPROVE_OPTION) return;
        try {
            BufferedImage img = ImageIO.read(fc.getSelectedFile());
            if (img != null) {
                picked = fit(img, 960);
                source.select(2);
                lastKey = null;
            }
        } catch (Exception e) {
            model.say("Could not read the picture: " + e.getMessage(), BAD);
        }
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

    private static BufferedImage scene;

    /** A small daylight scene: sky with sun, hills, water, grass and a few blocks. */
    static synchronized BufferedImage testScene() {
        if (scene != null) return scene;
        int w = 640, h = 360;
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setPaint(new GradientPaint(0, 0, new Color(0x4F86D9), 0, h * 0.6f, new Color(0xCFE3FF)));
        g.fillRect(0, 0, w, h);
        g.setPaint(new RadialGradientPaint(new Point(470, 70), 90, new float[] {0f, 0.2f, 1f},
            new Color[] {new Color(255, 250, 220), new Color(255, 236, 180, 200), new Color(255, 236, 180, 0)}));
        g.fillOval(380, -20, 180, 180);
        g.setColor(new Color(0x7E9CB8));
        g.fillPolygon(new int[] {0, 120, 260, 400, 520, 640, 640, 0}, new int[] {210, 160, 195, 150, 185, 165, 230, 230}, 8);
        g.setColor(new Color(0x3F6FA8));
        g.fillRect(0, 225, w, 40);
        g.setColor(new Color(255, 255, 255, 70));
        for (int i = 0; i < 40; i++) g.fillRect((i * 97) % w, 230 + (i * 13) % 30, 18, 2);
        g.setColor(new Color(0x5D8C3A));
        g.fillRect(0, 262, w, h - 262);
        g.setColor(new Color(0x4E7A30));
        for (int x = 0; x < w; x += 16) for (int y = 262; y < h; y += 16) if (((x + y) / 16) % 3 == 0) g.fillRect(x, y, 16, 16);
        int[][] blocks = {{90, 214, 0x8A6A4A}, {106, 214, 0x8A6A4A}, {98, 198, 0x6B8F3A}, {420, 230, 0x9A9A9A}, {436, 230, 0x9A9A9A}, {436, 214, 0xE8C14A}};
        for (int[] b : blocks) {
            g.setColor(new Color(b[2]));
            g.fillRect(b[0], b[1] + 32, 16, 16);
            g.setColor(new Color(b[2]).darker());
            g.drawRect(b[0], b[1] + 32, 15, 15);
        }
        g.dispose();
        scene = img;
        return img;
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
                BufferedImage input = switch (s) {
                    case GAME -> model.liveBefore != null ? fit(model.liveBefore, 960) : null;
                    case IMAGE -> picked;
                    default -> testScene();
                };
                if (model.materialDoc()) {
                    show(null, "Materials preview on the Material panel");
                    Thread.sleep(400);
                    continue;
                }
                if (input == null) {
                    show(null, s == Source.GAME ? "No game frame yet: use Test scene, or run the game with this pack" : "Choose an image");
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
                Object key = java.util.List.of(System.identityHashCode(c), System.identityHashCode(input), s);
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
                    out = gpu.run(c.spirv(), input, (System.nanoTime() - start) / 1e9f, frame++, extras());
                } catch (UnsupportedOperationException e) {
                    show(null, e.getMessage());
                    Thread.sleep(500);
                    continue;
                }
                long ms = (System.nanoTime() - t0) / 1_000_000;
                show(out, null);
                SwingUtilities.invokeLater(() -> info.setText(gpu.deviceName + "   -   " + out.getWidth() + "x" + out.getHeight() + "   -   " + ms + " ms"));
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
