package io.github.andrexarlay.vulkancompat.shaderhost.mosaic.studio;

import io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicTemplates;

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicGraph;
import io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicIrisExport;

import java.util.Locale;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static io.github.andrexarlay.vulkancompat.shaderhost.mosaic.studio.StudioUi.*;

/**
 * The shader browser: every shader pack of the game folder - Mosaic graphs and Iris packs - with the last result frame as
 * a thumbnail. Open a Mosaic graph in a tab, use an Iris pack in the game, import and export zips, start a new pack.
 */
final class ShaderBrowser extends Panels.Listening {

    enum Kind { MOSAIC, IRIS, OTHER }

    record Pack(String name, Path path, Kind kind, Path graph, Path thumb) {}

    final JPanel root = new JPanel(new BorderLayout(0, 6));
    final DefaultListModel<Pack> packs = new DefaultListModel<>();
    final JList<Pack> grid = new JList<>(packs);
    final JLabel footer = label("", FAINT);
    static final int W = 150, H = 86;
    private final java.util.Map<Path, Image> thumbs = new java.util.concurrent.ConcurrentHashMap<>();

    ShaderBrowser(StudioModel m) {
        super(m);
        root.setBackground(PANEL);
        root.setBorder(new EmptyBorder(8, 8, 8, 8));
        grid.setLayoutOrientation(JList.HORIZONTAL_WRAP);
        grid.setVisibleRowCount(-1);
        grid.setFixedCellWidth(W + 16);
        grid.setFixedCellHeight(H + 44);
        grid.setBackground(PANEL);
        grid.setCellRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> l, Object v, int i, boolean sel, boolean f) {
                JLabel c = (JLabel) super.getListCellRendererComponent(l, "", i, sel, false);
                Pack p = (Pack) v;
                c.setIcon(new ImageIcon(card(p, sel)));
                c.setText("<html><div style='width:" + W + "px'><b>" + esc(p.name()) + "</b><br><span style='color:" + hex(FAINT) + "'>"
                    + (p.kind() == Kind.MOSAIC ? "Mosaic graph" : p.kind() == Kind.IRIS ? "Iris shader pack" : "Shader pack")
                    + (Files.isRegularFile(p.path()) ? " (zip)" : "") + "</span></div></html>");
                c.setVerticalTextPosition(SwingConstants.BOTTOM);
                c.setHorizontalTextPosition(SwingConstants.CENTER);
                c.setFont(UI_S);
                c.setForeground(sel ? TEXT : MUTED);
                c.setBackground(PANEL);
                c.setBorder(new EmptyBorder(4, 4, 4, 4));
                c.setToolTipText(p.path().toString());
                return c;
            }
        });
        grid.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && grid.getSelectedValue() != null) open(grid.getSelectedValue());
            }
        });
        root.add(scroll(grid), BorderLayout.CENTER);
        JPanel south = new JPanel(new BorderLayout(0, 4));
        south.setOpaque(false);
        footer.setFont(UI_S);
        south.add(footer, BorderLayout.NORTH);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        buttons.setOpaque(false);
        buttons.add(button("Open", "Mosaic graph: open in a tab. Iris pack: use it in the game", () -> {
            if (grid.getSelectedValue() != null) open(grid.getSelectedValue());
        }));
        buttons.add(button("New Mosaic pack...", "A new shader pack with a Mosaic graph from a template", this::newPack));
        buttons.add(button("Import zip...", "Copy shader pack zips (Iris or Mosaic) into the game's shaderpacks", this::importZip));
        buttons.add(button("Export zip...", "The selected pack as a zip to share or publish", this::exportZip));
        buttons.add(button("Export as Iris pack", "The selected Mosaic graph as an Iris shader pack zip (one composite pass) next to it in"
            + " shaderpacks: screen-space graphs only, nodes Iris has nothing for are named", this::exportIris));
        buttons.add(button("Folder", "Show the shaderpacks folder", () -> {
            Path d = dir();
            try {
                if (d != null) Desktop.getDesktop().open(d.toFile());
            } catch (Exception ignored) {
                // no desktop
            }
        }));
        buttons.add(button("Refresh", null, this::reload));
        south.add(buttons, BorderLayout.CENTER);
        root.add(south, BorderLayout.SOUTH);
        reload();
    }

    Path dir() {
        Path g = StudioLibrary.gameDir(model);
        return g == null ? null : g.resolve("shaderpacks");
    }

    void reload() {
        packs.clear();
        Path d = dir();
        List<Pack> found = new ArrayList<>();
        if (d != null && Files.isDirectory(d)) {
            try (Stream<Path> s = Files.list(d)) {
                s.sorted(Comparator.comparing(p -> p.getFileName().toString().toLowerCase(Locale.ROOT))).forEach(p -> {
                    Pack pk = describe(p);
                    if (pk != null) found.add(pk);
                });
            } catch (Exception ignored) {
                // unreadable folder
            }
        }
        // graphs open or used recently that live outside the game folder (examples, work folders)
        for (String s : settings.openTabs) {
            int bar = s.indexOf('|');
            if (bar < 0 || !s.startsWith("SHADERS")) continue;
            Path f = Path.of(s.substring(bar + 1));
            Path pack = f.getParent() != null && f.getParent().getParent() != null ? f.getParent().getParent().getParent() : null;
            if (pack != null && found.stream().noneMatch(x -> x.path().equals(pack))) {
                Pack pk = describe(pack);
                if (pk != null) found.add(pk);
            }
        }
        found.forEach(packs::addElement);
        footer.setText(d == null ? "Choose the game folder (Library > game instance) to see its shader packs"
            : found.size() + " packs   -   " + d);
    }

    /** What a folder or zip in shaderpacks/ is. */
    static Pack describe(Path p) {
        String name = p.getFileName().toString();
        if (Files.isDirectory(p)) {
            Path graph = p.resolve("shaders/bedray/mosaic.json");
            if (Files.exists(graph)) {
                Path studio = p.resolve("shaders/bedray/.studio");
                Path thumb = Files.exists(p.resolve("pack.png")) ? p.resolve("pack.png") : Files.exists(studio.resolve("thumbnail.png"))
                    ? studio.resolve("thumbnail.png") : Files.exists(studio.resolve("after.png")) ? studio.resolve("after.png") : null;
                return new Pack(name, p, Kind.MOSAIC, graph, thumb);
            }
            if (Files.isDirectory(p.resolve("shaders"))) return new Pack(name, p, Kind.IRIS, null, null);
            return null;
        }
        if (name.toLowerCase(Locale.ROOT).endsWith(".zip")) {
            try (ZipFile z = new ZipFile(p.toFile())) {
                boolean mosaic = z.stream().anyMatch(e -> e.getName().endsWith("shaders/bedray/mosaic.json"));
                boolean shaders = mosaic || z.stream().anyMatch(e -> e.getName().startsWith("shaders/") || e.getName().contains("/shaders/"));
                return shaders ? new Pack(name.replaceAll("(?i)[.]zip$", ""), p, mosaic ? Kind.MOSAIC : Kind.IRIS, null, null) : null;
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    private Image card(Pack p, boolean selected) {
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = StudioWidgets.smooth(img.getGraphics());
        Image t = p.thumb() == null ? null : thumbs.computeIfAbsent(p.thumb(), k -> {
            try {
                BufferedImage b = ImageIO.read(k.toFile());
                return b == null ? null : b.getScaledInstance(W, H, Image.SCALE_SMOOTH);
            } catch (Exception e) {
                return null;
            }
        });
        g.setClip(new java.awt.geom.RoundRectangle2D.Double(0, 0, W, H, 12, 12));
        if (t != null) g.drawImage(t, 0, 0, null);
        else {
            Color a = p.kind() == Kind.MOSAIC ? new Color(0x3A6EA5) : p.kind() == Kind.IRIS ? new Color(0x7A4FA8) : new Color(0x555555);
            g.setPaint(new GradientPaint(0, 0, a, W, H, StudioWidgets.mix(a, BG, 0.7)));
            g.fillRect(0, 0, W, H);
            StudioWidgets.glyph(p.kind() == Kind.MOSAIC ? "shader" : "frame", Color.WHITE).paintIcon(null, g, W / 2 - 7, H / 2 - 7);
        }
        g.setClip(null);
        g.setColor(selected ? SELECT : EDGE);
        g.setStroke(new BasicStroke(selected ? 2.5f : 1f));
        g.drawRoundRect(1, 1, W - 3, H - 3, 12, 12);
        g.dispose();
        return img;
    }

    void open(Pack p) {
        if (p.kind() == Kind.MOSAIC && p.graph() != null) {
            StudioApp.get().openShaderTab(p.graph());
            return;
        }
        if (p.kind() == Kind.MOSAIC) {
            model.say("Zipped Mosaic pack: unpack it into shaderpacks to edit it", MUTED);
            return;
        }
        // Iris pack: Bedray hosts Iris packs - select it the way Iris stores the choice
        Path g = StudioLibrary.gameDir(model);
        if (g == null) return;
        try {
            Path cfg = g.resolve("config/iris.properties");
            java.util.Properties props = new java.util.Properties();
            if (Files.exists(cfg)) try (var r = Files.newBufferedReader(cfg)) {
                props.load(r);
            }
            props.setProperty("shaderPack", p.path().getFileName().toString());
            props.setProperty("enableShaders", "true");
            Files.createDirectories(cfg.getParent());
            try (var w = Files.newBufferedWriter(cfg)) {
                props.store(w, "Iris settings (shader pack chosen in Mosaic Studio)");
            }
            model.say("The game uses " + p.name() + " the next time it loads shaders (Iris pack, hosted by Bedray)", OK);
        } catch (Exception e) {
            model.say("Could not choose the pack: " + e.getMessage(), BAD);
        }
    }

    void newPack() {
        Path d = dir();
        if (d == null) {
            model.say("Choose the game folder first (Library > game instance)", MUTED);
            return;
        }
        String name = JOptionPane.showInputDialog(root, "Name of the new shader pack:", "My Mosaic shader");
        if (name == null || name.isBlank()) return;
        name = name.trim().replaceAll("[\\\\/:*?\"<>|]", "_");
        MosaicTemplates.Template[] all = MosaicTemplates.all().toArray(MosaicTemplates.Template[]::new);
        MosaicTemplates.Template t = (MosaicTemplates.Template) JOptionPane.showInputDialog(root, "Start from:", "New Mosaic pack",
            JOptionPane.PLAIN_MESSAGE, null, all, all[0]);
        if (t == null) return;
        try {
            Path graph = d.resolve(name).resolve("shaders/bedray/mosaic.json");
            if (Files.exists(graph)) {
                model.say(name + " exists already", BAD);
                return;
            }
            Files.createDirectories(graph.getParent());
            Files.writeString(graph, t.json(), StandardCharsets.UTF_8);
            reload();
            StudioApp.get().openShaderTab(graph);
        } catch (Exception e) {
            model.say("Could not create the pack: " + e.getMessage(), BAD);
        }
    }

    void importZip() {
        Path d = dir();
        if (d == null) {
            model.say("Choose the game folder first (Library > game instance)", MUTED);
            return;
        }
        StudioChooser fc = new StudioChooser();
        fc.setMultiSelectionEnabled(true);
        fc.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("Shader pack zips", "zip"));
        if (fc.showOpenDialog(root) != JFileChooser.APPROVE_OPTION) return;
        int n = 0;
        for (java.io.File f : fc.getSelectedFiles()) {
            if (describe(f.toPath()) == null) {
                model.say(f.getName() + " has no shaders/ folder: not a shader pack", BAD);
                continue;
            }
            try {
                Files.createDirectories(d);
                Files.copy(f.toPath(), d.resolve(f.getName()), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                n++;
            } catch (Exception e) {
                model.say("Could not copy " + f.getName() + ": " + e.getMessage(), BAD);
            }
        }
        if (n > 0) model.say("Imported " + n + " pack(s)", OK);
        reload();
    }

    void exportZip() {
        Pack p = grid.getSelectedValue();
        if (p == null || !Files.isDirectory(p.path())) {
            model.say("Select a pack folder to export", MUTED);
            return;
        }
        StudioChooser fc = new StudioChooser();
        fc.setSelectedFile(new java.io.File(p.name() + ".zip"));
        if (fc.showSaveDialog(root) != JFileChooser.APPROVE_OPTION) return;
        Path out = fc.getSelectedFile().toPath();
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(out)); Stream<Path> w = Files.walk(p.path())) {
            for (Path f : (Iterable<Path>) w.filter(Files::isRegularFile)::iterator) {
                String rel = p.path().relativize(f).toString().replace('\\', '/');
                if (rel.contains("/.studio/") || rel.startsWith(".studio/")) continue; // history, cache and frames stay home
                zip.putNextEntry(new ZipEntry(rel));
                Files.copy(f, zip);
                zip.closeEntry();
            }
            model.say("Exported " + out, OK);
        } catch (Exception e) {
            model.say("Export failed: " + e.getMessage(), BAD);
        }
    }

    /** A Mosaic pack folder as an Iris pack zip in shaderpacks ({@link MosaicIrisExport}): refused nodes are listed by name. */
    void exportIris() {
        Pack p = grid.getSelectedValue();
        if (p == null || p.kind() != Kind.MOSAIC || !Files.isDirectory(p.path())) {
            model.say("Select a Mosaic pack folder to export for Iris", MUTED);
            return;
        }
        try {
            String json = Files.readString(p.path().resolve("shaders/bedray/mosaic.json"));
            MosaicGraph.Analysis a = MosaicGraph.analyze(json);
            if (a.firstError() != null) {
                model.say("Fix the graph first: " + a.firstError(), BAD);
                return;
            }
            MosaicIrisExport.Result r = MosaicIrisExport.export(a.graph(), p.name());
            if (!r.ok()) {
                model.say("Iris cannot run this graph: " + String.join("; ", r.problems()), BAD);
                return;
            }
            Path out = p.path().resolveSibling(p.name() + " (Iris).zip");
            try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(out))) {
                for (var e : new java.util.TreeMap<>(r.files()).entrySet()) {
                    zip.putNextEntry(new ZipEntry(e.getKey()));
                    zip.write(e.getValue().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    zip.closeEntry();
                }
            }
            model.say("Exported " + out.getFileName() + " for Iris", OK);
            reload();
        } catch (Exception e) {
            model.say("Iris export failed: " + e.getMessage(), BAD);
        }
    }

    @Override void refresh() { }

    public JComponent component() { return root; }
}
