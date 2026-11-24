package io.github.andrexarlay.vulkancompat.shaderhost.mosaic.studio;

import io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicMaterial;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static io.github.andrexarlay.vulkancompat.shaderhost.mosaic.studio.StudioUi.*;

/** The Library (textures from Minecraft, resource packs and the project) and the material preview. */
final class MaterialPanels {

    private MaterialPanels() {}

    static final String DRAG_IMAGE = "mosaic-image:";

    private static final ExecutorService LOADER = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "mosaic-library");
        t.setDaemon(true);
        return t;
    });

    // ================================================================== library

    static final class LibraryPanel extends Panels.Listening {
        final JPanel root = new JPanel(new BorderLayout(0, 6));
        final JComboBox<StudioLibrary.Source> source = new JComboBox<>();
        final JTextField search = field("");
        final StudioWidgets.Segmented<String> group = new StudioWidgets.Segmented<String>()
            .add("all", "All", null, "Every texture")
            .add("block", "Blocks", StudioWidgets.glyph("vanilla", new Color(0x7FB069)), "textures/block")
            .add("item", "Items", StudioWidgets.glyph("tag", new Color(0x5AA0E0)), "textures/item")
            .add("entity", "Entities", StudioWidgets.glyph("shader", new Color(0xD08A4A)), "textures/entity")
            .add("other", "Other", StudioWidgets.glyph("dot", null), "GUI, particles, environment, ...");
        final DefaultListModel<String> entries = new DefaultListModel<>();
        final JList<String> grid = new JList<>(entries);
        final JLabel footer = label("", FAINT);
        static final int THUMB = 48;
        private List<String> all = List.of();
        private int loadToken;

        LibraryPanel(StudioModel m) {
            super(m);
            root.setBackground(PANEL);
            root.setBorder(new EmptyBorder(8, 8, 8, 8));
            JPanel top = new JPanel(new BorderLayout(0, 6));
            top.setOpaque(false);
            source.addActionListener(e -> load());
            source.setToolTipText("Where textures come from: Minecraft jars, resource packs of the game folder, this project");
            top.add(source, BorderLayout.NORTH);
            search.setToolTipText("Filter by name (stone, oak, ...). Enter opens the first match as a material.");
            search.getDocument().addDocumentListener(onText(this::filter));
            search.addActionListener(e -> {
                if (!entries.isEmpty()) open(entries.get(0));
            });
            top.add(search, BorderLayout.CENTER);
            group.onChange(v -> filter());
            top.add(group, BorderLayout.SOUTH);
            root.add(top, BorderLayout.NORTH);

            grid.setLayoutOrientation(JList.HORIZONTAL_WRAP);
            grid.setVisibleRowCount(-1);
            grid.setFixedCellWidth(THUMB + 22);
            grid.setFixedCellHeight(THUMB + 26);
            grid.setBackground(PANEL);
            grid.setCellRenderer(new DefaultListCellRenderer() {
                @Override public Component getListCellRendererComponent(JList<?> l, Object v, int i, boolean sel, boolean f) {
                    JLabel c = (JLabel) super.getListCellRendererComponent(l, "", i, sel, false);
                    String entry = (String) v;
                    StudioLibrary.Source s = (StudioLibrary.Source) source.getSelectedItem();
                    Image t = s == null ? null : StudioLibrary.thumb(s, entry, THUMB);
                    if (t == null && s != null) {
                        int token = loadToken;
                        LOADER.execute(() -> {
                            if (token != loadToken) return;
                            StudioLibrary.loadThumb(s, entry, THUMB);
                            SwingUtilities.invokeLater(grid::repaint);
                        });
                    }
                    c.setIcon(t == null ? new ImageIcon(new BufferedImage(THUMB, THUMB, BufferedImage.TYPE_INT_ARGB)) : new ImageIcon(t));
                    String name = StudioLibrary.shortName(entry);
                    c.setText("<html><div style='width:" + (THUMB + 8) + "px;text-align:center'>" + esc(name.length() > 14 ? name.substring(0, 13) + "..." : name)
                        + "</div></html>");
                    c.setFont(UI_S);
                    c.setVerticalTextPosition(SwingConstants.BOTTOM);
                    c.setHorizontalTextPosition(SwingConstants.CENTER);
                    c.setHorizontalAlignment(SwingConstants.CENTER);
                    c.setForeground(sel ? TEXT : MUTED);
                    c.setBackground(sel ? StudioWidgets.mix(PANEL, SELECT, 0.35) : PANEL);
                    c.setBorder(new EmptyBorder(3, 2, 3, 2));
                    c.setToolTipText(StudioLibrary.id(entry) + "  -  double-click: material, drag: image node");
                    return c;
                }
            });
            grid.addMouseListener(new MouseAdapter() {
                @Override public void mouseClicked(MouseEvent e) {
                    if (e.getClickCount() == 2 && grid.getSelectedValue() != null) open(grid.getSelectedValue());
                }
            });
            grid.addKeyListener(new KeyAdapter() {
                @Override public void keyPressed(KeyEvent e) {
                    if (e.getKeyCode() == KeyEvent.VK_ENTER && grid.getSelectedValue() != null) open(grid.getSelectedValue());
                }
            });
            grid.setDragEnabled(true);
            grid.setTransferHandler(new TransferHandler() {
                @Override public int getSourceActions(JComponent c) { return COPY; }
                @Override protected Transferable createTransferable(JComponent c) {
                    StudioLibrary.Source s = (StudioLibrary.Source) source.getSelectedItem();
                    String v = grid.getSelectedValue();
                    return s == null || v == null ? null : new StringSelection(DRAG_IMAGE + s.id() + "|" + v);
                }
            });
            root.add(scroll(grid), BorderLayout.CENTER);

            JPanel bottom = new JPanel(new BorderLayout(6, 4));
            bottom.setOpaque(false);
            footer.setFont(UI_S);
            bottom.add(footer, BorderLayout.NORTH);
            JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
            buttons.setOpaque(false);
            buttons.add(button("Import images...", "Copy PNG/JPG files into the project to use as maps", this::importFiles));
            buttons.add(button("Game folder...", "The Minecraft folder with Bedray: materials go to its resourcepacks/", this::chooseGame));
            buttons.add(button("Refresh", "Look for jars and packs again", this::reloadSources));
            bottom.add(buttons, BorderLayout.CENTER);
            root.add(bottom, BorderLayout.SOUTH);
            reloadSources();
        }

        void reloadSources() {
            StudioLibrary.Source was = (StudioLibrary.Source) source.getSelectedItem();
            List<StudioLibrary.Source> list = StudioLibrary.sources(model);
            DefaultComboBoxModel<StudioLibrary.Source> cm = new DefaultComboBoxModel<>();
            list.forEach(cm::addElement);
            source.setModel(cm);
            int pick = list.size() > 1 ? 1 : 0; // a Minecraft jar first: the project starts empty
            for (int i = 0; i < list.size(); i++) if (was != null && list.get(i).id().equals(was.id())) pick = i;
            if (!list.isEmpty()) source.setSelectedIndex(pick);
            load();
        }

        void load() {
            StudioLibrary.Source s = (StudioLibrary.Source) source.getSelectedItem();
            int token = ++loadToken;
            all = List.of();
            entries.clear();
            if (s == null) return;
            footer.setText("Reading " + s.label() + "...");
            LOADER.execute(() -> {
                List<String> found = StudioLibrary.textures(s);
                SwingUtilities.invokeLater(() -> {
                    if (token != loadToken) return;
                    all = found;
                    filter();
                });
            });
        }

        void filter() {
            String q = search.getText().trim().toLowerCase(Locale.ROOT);
            String g = group.getSelectedItem();
            List<String> shown = new ArrayList<>();
            for (String e : all) {
                String grp = StudioLibrary.group(e);
                boolean inGroup = g.equals("all") || (g.equals("other") ? !List.of("block", "item", "entity").contains(grp) : grp.equals(g));
                if (inGroup && (q.isEmpty() || e.toLowerCase(Locale.ROOT).contains(q))) shown.add(e);
                if (shown.size() >= 4000) break;
            }
            entries.clear();
            shown.forEach(entries::addElement);
            Path game = StudioLibrary.gameDir(model);
            footer.setText(shown.size() + " of " + all.size() + " textures   -   game folder: " + (game == null ? "not set" : game.getFileName()));
            footer.setToolTipText(game == null ? "Materials are kept in ~/.bedray/studio until a game folder is set" : game.toString());
        }

        void open(String entry) {
            StudioLibrary.Source s = (StudioLibrary.Source) source.getSelectedItem();
            if (s == null) return;
            StudioApp.get().switchWorkspace(StudioModel.Workspace.MATERIALS);
            model.openMaterial(s, entry);
        }

        void importFiles() {
            JFileChooser fc = new JFileChooser();
            fc.setMultiSelectionEnabled(true);
            fc.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("Images", "png", "jpg", "jpeg", "bmp", "gif"));
            if (fc.showOpenDialog(root) != JFileChooser.APPROVE_OPTION) return;
            model.importImages(List.of(fc.getSelectedFiles()));
            reloadSources();
        }

        void chooseGame() {
            JFileChooser fc = new JFileChooser();
            fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            fc.setDialogTitle("Minecraft folder (the one with resourcepacks/ and shaderpacks/)");
            if (fc.showOpenDialog(root) != JFileChooser.APPROVE_OPTION) return;
            settings.gameDir = fc.getSelectedFile().toString();
            StudioApp.get().saveSettings();
            reloadSources();
        }

        @Override void refresh() { }

        public JComponent component() { return root; }

        public void focus() { search.requestFocusInWindow(); }
    }

    // ================================================================== material preview

    static final class MaterialPanel extends Panels.Listening {
        enum View { LIT, ALBEDO, ROUGHNESS, METAL, NORMAL, EMISSION, HEIGHT }

        final JPanel root = new JPanel(new BorderLayout());
        final StudioWidgets.Segmented<View> view = new StudioWidgets.Segmented<View>()
            .add(View.LIT, "Lit", StudioWidgets.glyph("shader", new Color(0x5AA0E0)), "The block lit by a sun you can drag")
            .add(View.ALBEDO, "Colour", null, "Albedo (base colour)")
            .add(View.ROUGHNESS, "Rough", null, "Roughness: black = mirror, white = matte")
            .add(View.METAL, "Metal", null, "Metal (white) or F0 of non-metals")
            .add(View.NORMAL, "Normal", null, "Normal map from the height input")
            .add(View.EMISSION, "Glow", null, "Emission")
            .add(View.HEIGHT, "Height", null, "Height (bump)");
        final JLabel status = label("", MUTED);
        double lightYaw = -0.7, lightPitch = 0.9;
        BufferedImage cache;
        int cacheVersion = -1, cacheW, cacheH;
        View cacheView;

        MaterialPanel(StudioModel m) {
            super(m);
            root.setBackground(PANEL);
            JPanel bar = new JPanel(new BorderLayout());
            bar.setBackground(PANEL2);
            bar.setBorder(new EmptyBorder(5, 6, 5, 6));
            view.onChange(v -> root.repaint());
            bar.add(view, BorderLayout.CENTER);
            root.add(bar, BorderLayout.NORTH);
            JComponent canvas = new JComponent() {
                @Override protected void paintComponent(Graphics g0) { paintView((Graphics2D) g0, getWidth(), getHeight()); }
            };
            MouseAdapter drag = new MouseAdapter() {
                Point last;
                @Override public void mousePressed(MouseEvent e) { last = e.getPoint(); }
                @Override public void mouseDragged(MouseEvent e) {
                    lightYaw += (e.getX() - last.x) * 0.012;
                    lightPitch = Math.clamp(lightPitch - (e.getY() - last.y) * 0.012, 0.05, 1.5);
                    last = e.getPoint();
                    cacheVersion = -1;
                    canvas.repaint();
                }
            };
            canvas.addMouseListener(drag);
            canvas.addMouseMotionListener(drag);
            canvas.setToolTipText("Drag to move the sun");
            root.add(canvas, BorderLayout.CENTER);
            status.setFont(UI_S);
            status.setBorder(new EmptyBorder(4, 8, 4, 8));
            root.add(status, BorderLayout.SOUTH);
            refresh();
        }

        @Override void refresh() {
            String s = model.materialStatus;
            if (!model.materialGameStatus.isEmpty()) s += (s.isEmpty() ? "" : "   |   ") + "game: " + model.materialGameStatus;
            if (model.baked != null && !model.baked.warnings().isEmpty()) s = model.baked.warnings().get(0) + (s.isEmpty() ? "" : "   |   " + s);
            status.setText(s.isEmpty() ? (model.materialEntry == null ? "Double-click a texture in the Library to start a material" : "") : s);
            status.setForeground(model.baked != null && !model.baked.warnings().isEmpty() ? WARN : MUTED);
            root.repaint();
        }

        void paintView(Graphics2D g, int w, int h) {
            g.setColor(BG);
            g.fillRect(0, 0, w, h);
            MosaicMaterial.Baked b = model.baked;
            MosaicMaterial.Image base = model.baseImage;
            if (b == null || base == null) {
                g.setColor(FAINT);
                g.setFont(UI);
                String t = model.bakeError != null && model.materialDoc() ? model.bakeError : "No material open";
                g.drawString(t, Math.max(8, (w - g.getFontMetrics().stringWidth(t)) / 2), h / 2);
                return;
            }
            View v = view.getSelectedItem();
            if (cache == null || cacheVersion != model.bakeVersion || cacheView != v || (v == View.LIT && (cacheW != w || cacheH != h))) {
                cache = v == View.LIT ? lit(b, base, Math.max(32, Math.min(w, h) - 16)) : channel(b, base, v);
                cacheVersion = model.bakeVersion;
                cacheView = v;
                cacheW = w;
                cacheH = h;
            }
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            int frame = Math.min(cache.getWidth(), cache.getHeight());
            if (v == View.LIT) {
                g.drawImage(cache, (w - cache.getWidth()) / 2, (h - cache.getHeight()) / 2, null);
                return;
            }
            int size = Math.max(16, Math.min(w, h) - 24);
            int x = (w - size) / 2, y = (h - size) / 2;
            for (int cy = 0; cy < size; cy += 8) for (int cx = 0; cx < size; cx += 8) {
                g.setColor(((cx + cy) / 8) % 2 == 0 ? PANEL2 : PANEL);
                g.fillRect(x + cx, y + cy, Math.min(8, size - cx), Math.min(8, size - cy));
            }
            g.drawImage(cache, x, y, x + size, y + size, 0, 0, frame, frame, null);
        }

        static BufferedImage channel(MosaicMaterial.Baked b, MosaicMaterial.Image base, View v) {
            int n = b.w() * b.h();
            int[] out = new int[n];
            for (int i = 0; i < n; i++) {
                int s = b.specular()[i], nm = b.normal() == null ? 0xFF8080FF : b.normal()[i];
                int albedo = b.albedo() != null ? b.albedo()[i] : base.argb()[i];
                out[i] = switch (v) {
                    case ALBEDO -> albedo;
                    case ROUGHNESS -> grey(255 - ((s >> 16) & 255));
                    case METAL -> grey((s >> 8) & 255);
                    case NORMAL -> 0xFF000000 | (nm & 0xFFFF00) | 0xFF;
                    case EMISSION -> (s >>> 24) == 255 ? 0xFF000000 : grey((int) Math.round((s >>> 24) / 254.0 * 255));
                    case HEIGHT -> grey(nm >>> 24);
                    default -> albedo;
                };
            }
            return StudioLibrary.fromArgb(b.w(), b.h(), out);
        }

        static int grey(int v) { return 0xFF000000 | v << 16 | v << 8 | v; }

        /** A block seen from above at an angle: three faces of a cube, lit by a sun with the baked maps (CPU). */
        BufferedImage lit(MosaicMaterial.Baked b, MosaicMaterial.Image base, int size) {
            BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
            double lx = Math.cos(lightPitch) * Math.cos(lightYaw), ly = Math.sin(lightPitch), lz = Math.cos(lightPitch) * Math.sin(lightYaw);
            int frame = Math.min(b.w(), b.h());
            double s = size / 2.2, cx = size / 2.0, cy = size / 2.0;
            // isometric axes: x to the right-down, z to the left-down, y up
            double[] ax = {Math.cos(Math.toRadians(30)) * s / 1.0, Math.sin(Math.toRadians(30)) * s};
            double[] az = {-Math.cos(Math.toRadians(30)) * s, Math.sin(Math.toRadians(30)) * s};
            double[] ay = {0, -s};
            double ox = cx - (ax[0] + az[0]) / 2, oy = cy - (ax[1] + az[1] + ay[1]) / 2 - s * 0.05;
            for (int py = 0; py < size; py++)
                for (int px = 0; px < size; px++) {
                    double X = px + 0.5 - ox, Y = py + 0.5 - oy;
                    int argb = 0;
                    // top face: origin + y, spanned by ax, az
                    double[] t = solve(ax, az, X - ay[0], Y - ay[1]);
                    if (t != null && in(t)) argb = shade(b, base, t[0], t[1], 0, 1, 0, 1, 0, 0, 0, 0, 1, lx, ly, lz, frame);
                    else {
                        // left face (facing +z): spanned by ax and -ay from origin + az
                        double[] l = solve(ax, ay, X - az[0], Y - az[1]);
                        if (l != null && in(l)) argb = shade(b, base, l[0], 1 - l[1], 0, 0, 1, 1, 0, 0, 0, -1, 0, lx, ly, lz, frame);
                        else {
                            double[] r = solve(az, ay, X - ax[0], Y - ax[1]);
                            if (r != null && in(r)) argb = shade(b, base, 1 - r[0], 1 - r[1], 1, 0, 0, 0, 0, -1, 0, -1, 0, lx, ly, lz, frame);
                        }
                    }
                    if (argb != 0) img.setRGB(px, py, argb);
                }
            return img;
        }

        static boolean in(double[] t) { return t[0] >= 0 && t[0] < 1 && t[1] >= 0 && t[1] < 1; }

        static double[] solve(double[] a, double[] b, double x, double y) {
            double det = a[0] * b[1] - a[1] * b[0];
            if (Math.abs(det) < 1e-9) return null;
            return new double[] {(x * b[1] - y * b[0]) / det, (a[0] * y - a[1] * x) / det};
        }

        /** Face normal n, tangent t (u direction), bitangent bt (v direction, image rows). */
        static int shade(MosaicMaterial.Baked b, MosaicMaterial.Image base, double u, double v, double nx, double ny, double nz,
                         double tx, double ty, double tz, double bx, double by, double bz, double lx, double ly, double lz, int frame) {
            int x = Math.min(frame - 1, (int) (u * frame)), y = Math.min(frame - 1, (int) (v * frame));
            int i = y * b.w() + x;
            int albedo = b.albedo() != null ? b.albedo()[i] : base.argb()[i];
            if ((albedo >>> 24) < 128) return 0;
            int s = b.specular()[i];
            double smooth = ((s >> 16) & 255) / 255.0, rough = Math.max(0.03, 1 - smooth);
            int g = (s >> 8) & 255;
            boolean metal = g >= 230;
            double f0 = metal ? 1 : g / 255.0;
            double emission = (s >>> 24) == 255 ? 0 : (s >>> 24) / 254.0;
            double ao = 1;
            double Nx = nx, Ny = ny, Nz = nz;
            if (b.normal() != null) {
                int nm = b.normal()[i];
                double tnx = ((nm >> 16) & 255) / 127.5 - 1, tny = -(((nm >> 8) & 255) / 127.5 - 1); // DirectX green
                double tnz = Math.sqrt(Math.max(0, 1 - tnx * tnx - tny * tny));
                ao = (nm & 255) / 255.0;
                // bitangent runs down the image; tangent-space +y points up the image
                Nx = tx * tnx - bx * tny + nx * tnz;
                Ny = ty * tnx - by * tny + ny * tnz;
                Nz = tz * tnx - bz * tny + nz * tnz;
                double len = Math.sqrt(Nx * Nx + Ny * Ny + Nz * Nz);
                Nx /= len;
                Ny /= len;
                Nz /= len;
            }
            double ndl = Math.max(0, Nx * lx + Ny * ly + Nz * lz);
            // viewer along (1, 1, 1) normalized (isometric)
            double vx = 0.577, vy = 0.577, vz = 0.577;
            double hx = lx + vx, hy = ly + vy, hz = lz + vz, hl = Math.sqrt(hx * hx + hy * hy + hz * hz);
            double ndh = Math.max(0, (Nx * hx + Ny * hy + Nz * hz) / hl);
            double a2 = Math.pow(rough, 4), d = ndh * ndh * (a2 - 1) + 1;
            double spec = a2 / (Math.PI * d * d) * ndl * 0.25;
            double[] c = {((albedo >> 16) & 255) / 255.0, ((albedo >> 8) & 255) / 255.0, (albedo & 255) / 255.0};
            int out = 0xFF000000;
            for (int k = 0; k < 3; k++) {
                double lin = Math.pow(c[k], 2.2);
                double fk = metal ? lin : f0;
                // sky light from everywhere: diffuse ambient, and a soft reflection that metals and smooth surfaces show
                double env = (0.22 + 0.5 * (1 - rough)) * ao * (0.55 + 0.45 * Math.max(0, Ny));
                double val = (metal ? 0 : lin) * (0.28 * ao + 0.85 * ndl) + fk * (Math.min(spec, 8) + env) + lin * emission * 2.5;
                int q = (int) Math.round(Math.pow(Math.min(1, val / (1 + val * 0.25)), 1 / 2.2) * 255);
                out |= Math.min(255, q) << (16 - k * 8);
            }
            return out;
        }

        public JComponent component() { return root; }
    }
}
