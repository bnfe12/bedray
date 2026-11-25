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

    private static final ExecutorService MODELS = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "mosaic-models");
        t.setDaemon(true);
        return t;
    });

    private static final ExecutorService LOADER = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "mosaic-library");
        t.setDaemon(true);
        return t;
    });

    // ================================================================== library

    static final class LibraryPanel extends Panels.Listening {
        final JPanel root = new JPanel(new BorderLayout(0, 6));
        final JComboBox<StudioLibrary.Source> source = new JComboBox<>();
        final JComboBox<Object> instance = new JComboBox<>();
        final JLabel versionNote = label("", WARN);
        private boolean fillingInstances;
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
            source.setToolTipText("Where textures come from: Minecraft jars, resource packs and mods of the instance, this project");
            instance.setToolTipText("Game installations found in launchers (Prism, CurseForge, Modrinth, ATLauncher, GDLauncher, official)."
                + " Materials go into the chosen one's resourcepacks/.");
            instance.addActionListener(e -> {
                if (fillingInstances || !(instance.getSelectedItem() instanceof StudioLibrary.Instance i)) return;
                settings.gameDir = i.dir().toString();
                StudioApp.get().saveSettings();
                reloadSources();
            });
            JPanel pick = new JPanel(new GridLayout(0, 1, 0, 4));
            pick.setOpaque(false);
            pick.add(instance);
            pick.add(source);
            versionNote.setFont(UI_S);
            pick.add(versionNote);
            top.add(pick, BorderLayout.NORTH);
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
            buttons.add(button("Refresh", "Look for launchers, jars, packs and mods again", () -> {
                StudioLibrary.forgetInstances();
                reloadSources();
            }));
            bottom.add(buttons, BorderLayout.CENTER);
            root.add(bottom, BorderLayout.SOUTH);
            reloadSources();
        }

        void fillInstances() {
            fillingInstances = true;
            DefaultComboBoxModel<Object> im = new DefaultComboBoxModel<>();
            StudioLibrary.Instance cur = StudioLibrary.currentInstance(model);
            if (cur == null) im.addElement("Choose a game instance...");
            for (StudioLibrary.Instance i : StudioLibrary.instances()) im.addElement(i);
            if (cur != null && StudioLibrary.instances().stream().noneMatch(i -> i.dir().equals(cur.dir()))) im.insertElementAt(cur, 0);
            instance.setModel(im);
            if (cur != null) for (int i = 0; i < im.getSize(); i++)
                if (im.getElementAt(i) instanceof StudioLibrary.Instance x && x.dir().equals(cur.dir())) instance.setSelectedIndex(i);
            fillingInstances = false;
        }

        void reloadSources() {
            fillInstances();
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
            String v = s == null || s.kind() != StudioLibrary.Kind.VANILLA ? null : s.label().replaceAll("^Minecraft ([0-9.]+).*$", "$1");
            versionNote.setText(v != null && !v.equals(StudioLibrary.TARGET) && v.matches("[0-9.]+")
                ? "Bedray is built for " + StudioLibrary.TARGET + ": textures of " + v + " may differ" : "");
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
            StudioApp.get().openMaterialTab(s, entry);
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
        enum View { LIT, UV, ALBEDO, ROUGHNESS, METAL, NORMAL, EMISSION, HEIGHT }

        final JPanel root = new JPanel(new BorderLayout());
        final StudioWidgets.Segmented<View> view = new StudioWidgets.Segmented<View>()
            .add(View.LIT, "Lit", StudioWidgets.glyph("shader", new Color(0x5AA0E0)),
                "The block's real model (fences, stairs, plants...) with the material: drag to turn it, Shift+drag moves the sun")
            .add(View.UV, "UV", StudioWidgets.glyph("split", null), "UV layout: which part of the texture each face of the model uses")
            .add(View.ALBEDO, "Colour", null, "Albedo (base colour)")
            .add(View.ROUGHNESS, "Rough", null, "Roughness: black = mirror, white = matte")
            .add(View.METAL, "Metal", null, "Metal (white) or F0 of non-metals")
            .add(View.NORMAL, "Normal", null, "Normal map from the height input")
            .add(View.EMISSION, "Glow", null, "Emission")
            .add(View.HEIGHT, "Height", null, "Height (bump)");
        final JLabel status = label("", MUTED);
        double lightYaw = -0.7, lightPitch = 0.9, modelYaw = 0, zoom = 1;
        BlockModels.Model blockModel;
        final JComboBox<String> modelBox = new JComboBox<>();
        /** Minecraft: the game's own flat face shading (what players see without shaders). Bedray PBR: the baked material lit by the sun. */
        final StudioWidgets.Segmented<Boolean> light = new StudioWidgets.Segmented<Boolean>()
            .add(true, "Minecraft", StudioWidgets.glyph("vanilla", new Color(0x7FB069)), "The vanilla look: Minecraft's own face shading")
            .add(false, "Bedray PBR", StudioWidgets.glyph("shader", new Color(0x5AA0E0)), "The baked material (roughness, metal, normals) in sunlight");
        final StudioWidgets.Segmented<StudioModel.PaintTool> tools = new StudioWidgets.Segmented<StudioModel.PaintTool>()
            .add(StudioModel.PaintTool.NAVIGATE, "Turn", StudioWidgets.glyph("move", null), "Drag turns the block, Shift+drag moves the sun")
            .add(StudioModel.PaintTool.BRUSH, "Brush", StudioWidgets.glyph("brush", null), "Paint pixels on the UV view or straight on the block")
            .add(StudioModel.PaintTool.ERASER, "Erase", StudioWidgets.glyph("eraser", null), "Back to the original pixels")
            .add(StudioModel.PaintTool.FILL, "Fill", StudioWidgets.glyph("fill", null), "Fill the touching pixels of the same colour")
            .add(StudioModel.PaintTool.PICKER, "Pick", StudioWidgets.glyph("picker", null), "Take a colour from the texture");
        final JButton swatch = new JButton() {
            @Override protected void paintComponent(Graphics g0) {
                Graphics2D g = StudioWidgets.smooth(g0);
                g.setColor(MaterialPanel.this.model.paintColor);
                g.fillRoundRect(2, 2, getWidth() - 4, getHeight() - 4, 6, 6);
                g.setColor(EDGE);
                g.drawRoundRect(2, 2, getWidth() - 5, getHeight() - 5, 6, 6);
                g.dispose();
            }
        };
        boolean seams = true;
        JCheckBox seamBox;
        int[] pick;
        int pickSize, pickX, pickY;
        int uvX, uvY, uvSize, uvFrame;
        int lastTexel = -1;
        boolean fillingModels;
        String modelFor;
        final java.util.Map<String, MosaicMaterial.Image> faceTextures = new java.util.HashMap<>();
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
            modelBox.setToolTipText("Which model shows the material: every block model that uses this texture (cube, fence, stairs, slab...)");
            modelBox.addActionListener(e -> {
                if (fillingModels || modelBox.getSelectedItem() == null || model.materialSource == null) return;
                blockModel = loadModel((String) modelBox.getSelectedItem());
                cacheVersion = -1;
                root.repaint();
            });
            JPanel paintRow = new JPanel(new BorderLayout(6, 0));
            paintRow.setOpaque(false);
            paintRow.setBorder(new EmptyBorder(5, 0, 0, 0));
            tools.onChange(t -> {
                model.paintTool = t;
                root.repaint();
            });
            paintRow.add(tools, BorderLayout.CENTER);
            JPanel extra = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
            extra.setOpaque(false);
            swatch.setToolTipText("Paint colour (the picker takes it from the texture)");
            swatch.setPreferredSize(new Dimension(26, 22));
            swatch.addActionListener(e -> {
                Color c = JColorChooser.showDialog(root, "Paint colour", model.paintColor);
                if (c != null) model.paintColor = c;
                swatch.repaint();
            });
            extra.add(swatch);
            JComboBox<String> size = new JComboBox<>(new String[] {"1 px", "2 px", "3 px", "4 px"});
            size.setToolTipText("Brush size in texture pixels");
            size.addActionListener(e -> model.brushSize = size.getSelectedIndex() + 1);
            extra.add(size);
            seamBox = new JCheckBox("Seams", seams);
            seamBox.setToolTipText("Red lines where the model's faces (UV islands) meet");
            seamBox.addActionListener(e -> {
                seams = seamBox.isSelected();
                cacheVersion = -1;
                root.repaint();
            });
            extra.add(seamBox);
            extra.add(button("Clear paint", "Remove everything painted on this texture (undoable)", () -> {
                if (model.doc.paint == null) return;
                model.edit("Clear paint", () -> model.doc.paint = null);
            }));
            paintRow.add(extra, BorderLayout.SOUTH);
            JPanel modelRow = new JPanel(new BorderLayout(6, 0));
            modelRow.setOpaque(false);
            modelRow.setBorder(new EmptyBorder(5, 0, 0, 0));
            modelRow.add(label("Model", MUTED), BorderLayout.WEST);
            modelRow.add(modelBox, BorderLayout.CENTER);
            light.setPreferredSize(new Dimension(230, 24));
            light.onChange(v -> {
                cacheVersion = -1;
                root.repaint();
            });
            JButton lightMenu = button("Light: " + PreviewLight.current.name, "Light the material: presets, an HDRI of your own, sun and fill colours", null);
            lightMenu.addActionListener(e -> {
                JPopupMenu p = new JPopupMenu();
                for (PreviewLight pl : PreviewLight.PRESETS) {
                    JMenuItem it = new JMenuItem(pl.name);
                    it.addActionListener(ev -> setLight(pl, lightMenu));
                    p.add(it);
                }
                p.addSeparator();
                JMenuItem hd = new JMenuItem("Load HDRI (.hdr, .png, .jpg)...");
                hd.addActionListener(ev -> {
                    JFileChooser fc = new JFileChooser();
                    fc.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("HDRI / panorama", "hdr", "png", "jpg", "jpeg"));
                    if (fc.showOpenDialog(root) != JFileChooser.APPROVE_OPTION) return;
                    try {
                        setLight(PreviewLight.hdri(fc.getSelectedFile().toPath()), lightMenu);
                    } catch (Exception ex) {
                        model.say("Could not read the HDRI: " + ex.getMessage(), BAD);
                    }
                });
                p.add(hd);
                JMenuItem sc = new JMenuItem("Sun colour...");
                sc.addActionListener(ev -> {
                    PreviewLight cur = PreviewLight.current;
                    Color c = JColorChooser.showDialog(root, "Sun colour", new Color((float) Math.min(1, cur.sun[0]), (float) Math.min(1, cur.sun[1]),
                        (float) Math.min(1, cur.sun[2])));
                    if (c != null) setLight(cur.withSun(PreviewLight.rgb(c.getRed() / 255.0, c.getGreen() / 255.0, c.getBlue() / 255.0), cur.sunStrength), lightMenu);
                });
                p.add(sc);
                JMenu strength = new JMenu("Sun strength");
                for (double s : new double[] {0, 0.25, 0.5, 1, 1.5, 2, 3}) {
                    JMenuItem it = new JMenuItem(s == 0 ? "off" : "x " + s);
                    it.addActionListener(ev -> setLight(PreviewLight.current.withSun(PreviewLight.current.sun, s), lightMenu));
                    strength.add(it);
                }
                p.add(strength);
                JMenuItem fl = new JMenuItem("Fill light colour... (black: off)");
                fl.addActionListener(ev -> {
                    Color c = JColorChooser.showDialog(root, "Fill light", Color.BLACK);
                    if (c != null) setLight(PreviewLight.current.withFill(PreviewLight.rgb(c.getRed() / 255.0, c.getGreen() / 255.0, c.getBlue() / 255.0)), lightMenu);
                });
                p.add(fl);
                p.show(lightMenu, 0, lightMenu.getHeight());
            });
            JPanel lightRow = new JPanel(new BorderLayout(6, 0));
            lightRow.setOpaque(false);
            lightRow.setBorder(new EmptyBorder(5, 0, 0, 0));
            lightRow.add(lightMenu, BorderLayout.WEST);
            lightRow.add(light, BorderLayout.EAST);
            if (Boolean.getBoolean("mosaic.selfshot.pbr")) light.setSelectedItem(false);
            JPanel rows = new JPanel(new GridLayout(0, 1, 0, 0));
            rows.setOpaque(false);
            rows.add(modelRow);
            JPanel both = new JPanel(new BorderLayout());
            both.setOpaque(false);
            both.add(modelRow, BorderLayout.NORTH);
            both.add(lightRow, BorderLayout.CENTER);
            both.add(paintRow, BorderLayout.SOUTH);
            bar.add(both, BorderLayout.SOUTH);
            root.add(bar, BorderLayout.NORTH);
            JComponent canvas = new JComponent() {
                @Override protected void paintComponent(Graphics g0) { paintView((Graphics2D) g0, getWidth(), getHeight()); }
            };
            MouseAdapter drag = new MouseAdapter() {
                Point last;
                @Override public void mousePressed(MouseEvent e) {
                    last = e.getPoint();
                    if (painting(e)) {
                        model.beginStroke();
                        lastTexel = -1;
                        paint(e.getPoint());
                    }
                }
                @Override public void mouseReleased(MouseEvent e) {
                    if (painting(e)) model.endStroke();
                }
                @Override public void mouseDragged(MouseEvent e) {
                    if (painting(e)) {
                        if (model.paintTool != StudioModel.PaintTool.FILL) paint(e.getPoint());
                        return;
                    }
                    if (e.isShiftDown() || SwingUtilities.isRightMouseButton(e)) {
                        lightYaw += (e.getX() - last.x) * 0.012;
                        lightPitch = Math.clamp(lightPitch - (e.getY() - last.y) * 0.012, 0.05, 1.5);
                    } else modelYaw += (e.getX() - last.x) * 0.012;
                    last = e.getPoint();
                    cacheVersion = -1;
                    canvas.repaint();
                }
            };
            canvas.addMouseListener(drag);
            canvas.addMouseMotionListener(drag);
            canvas.addMouseWheelListener(e -> {
                zoom = Math.clamp(zoom * Math.pow(1.12, -e.getPreciseWheelRotation()), 0.3, 6);
                cacheVersion = -1;
                canvas.repaint();
            });
            canvas.setToolTipText("Turn: drag turns the block, Shift+drag (or right drag) moves the sun. Paint tools paint on the UV view or the block.");
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
            String key = model.materialSource == null ? null : model.materialSource.id() + "|" + model.materialEntry;
            if (key != null && !key.equals(modelFor)) {
                modelFor = key;
                faceTextures.clear();
                boolean entity = model.materialEntry.contains("/entity/");
                seams = !entity;
                if (seamBox != null) seamBox.setSelected(seams);
                zoom = 1;
                blockModel = entity ? null : BlockModels.forTexture(model.materialSource, model.materialEntry);
                cacheVersion = -1;
                StudioLibrary.Source src = model.materialSource;
                String entry = model.materialEntry;
                java.nio.file.Path game = StudioLibrary.gameDir(model);
                MODELS.execute(() -> {
                    List<String> found = entity ? BlockModels.entityModelsFor(game, entry) : BlockModels.modelsUsing(src, entry);
                    if (entity && found.isEmpty() && BlockModels.entityLayers(game).isEmpty()) {
                        // no game export yet: build the models from the client jar, then fill the list again
                        BlockModels.exportOffline(() -> SwingUtilities.invokeLater(() -> {
                            modelFor = null;
                            root.repaint();
                        }));
                        SwingUtilities.invokeLater(root::repaint);
                        return;
                    }
                    SwingUtilities.invokeLater(() -> {
                        fillingModels = true;
                        DefaultComboBoxModel<String> cm = new DefaultComboBoxModel<>();
                        for (String f : found) cm.addElement(f);
                        if (blockModel != null && !found.contains(blockModel.name())) cm.insertElementAt(blockModel.name(), 0);
                        modelBox.setModel(cm);
                        if (blockModel != null) modelBox.setSelectedItem(blockModel.name());
                        modelBox.setEnabled(cm.getSize() > 1);
                        fillingModels = false;
                        if (blockModel == null && cm.getSize() > 0) {
                            blockModel = loadModel(cm.getElementAt(0));
                            fillingModels = true;
                            modelBox.setSelectedIndex(0);
                            fillingModels = false;
                            cacheVersion = -1;
                            root.repaint();
                        }
                    });
                });
            }
            if (v == View.UV) {
                paintUv(g, w, h, b, base);
                return;
            }
            if (cache == null || cacheVersion != model.bakeVersion || cacheView != v || (v == View.LIT && (cacheW != w || cacheH != h))) {
                int size = Math.max(32, Math.min(w, h) - 16);
                boolean entityTex = model.materialEntry != null && model.materialEntry.contains("/entity/");
                boolean itemTex = model.materialEntry != null && model.materialEntry.contains("/textures/item/");
                if (v == View.LIT && blockModel == null && entityTex) {
                    paintWaiting(g, w, h);
                    return;
                }
                if (v == View.LIT && blockModel == null && itemTex) {
                    // items look like the inventory: the sprite face-on, Minecraft-flat or lit by the material
                    cache = light.getSelectedItem() ? channel(b, base, View.ALBEDO) : itemLit(b, base);
                } else
                    cache = v == View.LIT ? (blockModel != null ? renderModel(blockModel, b, base, size) : lit(b, base, size)) : channel(b, base, v);
                cacheVersion = model.bakeVersion;
                cacheView = v;
                cacheW = w;
                cacheH = h;
            }
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            int frame = Math.min(cache.getWidth(), cache.getHeight());
            boolean flatItem = v == View.LIT && blockModel == null && model.materialEntry != null && model.materialEntry.contains("/textures/item/");
            if (v == View.LIT && !flatItem) {
                pickX = (w - cache.getWidth()) / 2;
                pickY = (h - cache.getHeight()) / 2;
                g.drawImage(cache, pickX, pickY, null);
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

        /** A block model id, or "entity:<layer>" for an entity layer exported by the game. */
        BlockModels.Model loadModel(String id) {
            if (id == null || model.materialSource == null) return null;
            if (id.startsWith("entity:"))
                return BlockModels.entity(StudioLibrary.gameDir(model), id.substring("entity:".length()), StudioLibrary.id(model.materialEntry));
            return BlockModels.load(model.materialSource, id, null);
        }

        /** An entity texture whose model is not here yet: say what is happening instead of showing a wrong cube. */
        void paintWaiting(Graphics2D g, int w, int h) {
            String state = BlockModels.offlineState();
            String t = state.startsWith("working") ? "Building entity models from Minecraft (once, a few seconds)..."
                : state.startsWith("failed") || state.startsWith("no ") ? "Entity models are not available: " + state
                : "Looking for this entity's model...";
            g.setFont(UI);
            g.setColor(state.startsWith("failed") ? WARN : MUTED);
            FontMetrics fm = g.getFontMetrics();
            String fit = StudioWidgets.Segmented.fit(t, fm, w - 20);
            g.drawString(fit, Math.max(10, (w - fm.stringWidth(fit)) / 2), h / 2);
            if (state.startsWith("working")) SwingUtilities.invokeLater(() -> {
                try {
                    Thread.sleep(300);
                } catch (InterruptedException ignored) {
                    // repaint soon
                }
                root.repaint();
            });
        }

        boolean painting(MouseEvent e) {
            return model.paintTool != StudioModel.PaintTool.NAVIGATE && SwingUtilities.isLeftMouseButton(e) && !e.isShiftDown()
                && model.baked != null;
        }

        /** Screen point -> texel (UV view: the drawn texture; Lit view: the face under the cursor). */
        void paint(Point p) {
            int texel = -1;
            MosaicMaterial.Image base = model.baseImage;
            if (base == null) return;
            if (view.getSelectedItem() == View.LIT) {
                int x = p.x - pickX, y = p.y - pickY;
                if (pick != null && x >= 0 && y >= 0 && x < pickSize && y < pickSize) texel = pick[y * pickSize + x];
            } else if (uvSize > 0) {
                int x = (p.x - uvX) * uvFrame / uvSize, y = (p.y - uvY) * uvFrame / uvSize;
                if (x >= 0 && y >= 0 && x < uvFrame && y < uvFrame) texel = y * base.w() + x;
            }
            if (texel < 0 || texel == lastTexel) return;
            lastTexel = texel;
            model.paintAt(texel % base.w(), texel / base.w());
            swatch.repaint();
        }

        /** UV layout: the texture with the rectangle each model face samples, labelled by face. */
        void paintUv(Graphics2D g, int w, int h, MosaicMaterial.Baked b, MosaicMaterial.Image base) {
            BufferedImage tex = channel(b, base, View.ALBEDO);
            int frame = Math.min(tex.getWidth(), tex.getHeight());
            int size = Math.max(16, Math.min(w, h) - 30), x = (w - size) / 2, y = (h - size) / 2;
            uvX = x;
            uvY = y;
            uvSize = size;
            uvFrame = frame;
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            g.drawImage(tex, x, y, x + size, y + size, 0, 0, frame, frame, null);
            g.setColor(new Color(255, 255, 255, 40));
            for (int i = 0; i <= frame && frame <= 64; i++) {
                int p = i * size / frame;
                g.drawLine(x + p, y, x + p, y + size);
                g.drawLine(x, y + p, x + size, y + p);
            }
            if (blockModel == null) {
                g.setColor(FAINT);
                g.drawString(model.materialEntry != null && model.materialEntry.contains("/entity/")
                    ? "Entity models come from the game: start Minecraft with Bedray once (it exports them to bedray-studio/models)"
                    : "No block model uses this texture: the whole texture is shown", 10, h - 10);
                return;
            }
            String id = StudioLibrary.id(model.materialEntry);
            java.util.Map<String, Color> colors = java.util.Map.of("up", new Color(0x5AA0E0), "down", new Color(0x8A6FC8), "north", new Color(0xE0605A),
                "south", new Color(0x4FB886), "east", new Color(0xF0A830), "west", new Color(0xD06AB0));
            g.setStroke(new BasicStroke(2f));
            g.setFont(UI_S);
            int labelled = 0;
            for (BlockModels.Quad q : blockModel.quads()) {
                if (!q.texture().equals(id)) continue;
                if (q.uv4() != null) { // entity polygon: its own four UV corners
                    java.awt.geom.Path2D.Double poly = new java.awt.geom.Path2D.Double();
                    for (int k = 0; k < 4; k++) {
                        double px = x + q.uv4()[k][0] * size, py = y + q.uv4()[k][1] * size;
                        if (k == 0) poly.moveTo(px, py);
                        else poly.lineTo(px, py);
                    }
                    poly.closePath();
                    g.setColor(new Color(255, 80, 80, 40));
                    g.fill(poly);
                    g.setColor(new Color(0xFF5050));
                    g.draw(poly);
                    continue;
                }
                double u1 = Math.min(q.u1(), q.u2()), u2 = Math.max(q.u1(), q.u2()), v1 = Math.min(q.v1(), q.v2()), v2 = Math.max(q.v1(), q.v2());
                int rx = x + (int) Math.round(u1 / 16 * size), ry = y + (int) Math.round(v1 / 16 * size);
                int rw = (int) Math.round((u2 - u1) / 16 * size), rh = (int) Math.round((v2 - v1) / 16 * size);
                Color c = colors.getOrDefault(q.face(), Color.WHITE);
                g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), 50));
                g.fillRect(rx, ry, rw, rh);
                g.setColor(c);
                g.drawRect(rx, ry, rw, rh);
                if (labelled++ < 24) g.drawString(q.face(), rx + 3, ry + 13);
            }
            g.setColor(MUTED);
            g.drawString(blockModel.name() + "  -  " + blockModel.quads().size() + " faces", 10, h - 10);
        }

        /** The block model, software-rendered with the baked maps on faces that use this texture (others plain). */
        BufferedImage renderModel(BlockModels.Model m, MosaicMaterial.Baked b, MosaicMaterial.Image base, int size) {
            BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
            double[] depth = new double[size * size];
            java.util.Arrays.fill(depth, -1e9);
            int[] pk = new int[size * size];
            java.util.Arrays.fill(pk, -1);
            List<double[][]> edges = new ArrayList<>();
            double cy = Math.cos(modelYaw), sy = Math.sin(modelYaw);
            double lx = Math.cos(lightPitch) * Math.cos(lightYaw), ly = Math.sin(lightPitch), lz = Math.cos(lightPitch) * Math.sin(lightYaw);
            double c30 = Math.cos(Math.toRadians(30)), s30 = 0.5;
            // fit: project every corner at scale 1, then scale and centre the outline into the view (any model size)
            double fx0 = 1e9, fy0 = 1e9, fx1 = -1e9, fy1 = -1e9;
            for (BlockModels.Quad q : m.quads())
                for (double[] c : q.p()) {
                    double x = c[0] - 8, y = c[1] - 8, z = c[2] - 8, rx = x * cy - z * sy, rz = x * sy + z * cy;
                    double px = (rx - rz) * c30, py = (rx + rz) * s30 - y;
                    fx0 = Math.min(fx0, px); fx1 = Math.max(fx1, px); fy0 = Math.min(fy0, py); fy1 = Math.max(fy1, py);
                }
            double scale = 0.86 * size / Math.max(1e-6, Math.max(fx1 - fx0, fy1 - fy0)) * zoom;
            offX = -(fx0 + fx1) / 2 * scale;
            offY = -(fy0 + fy1) / 2 * scale;
            String id = StudioLibrary.id(model.materialEntry);
            for (BlockModels.Quad q : m.quads()) {
                double[][] p = new double[4][];
                for (int i = 0; i < 4; i++) {
                    double x = q.p()[i][0] - 8, y = q.p()[i][1] - 8, z = q.p()[i][2] - 8;
                    p[i] = new double[] {x * cy - z * sy, y, x * sy + z * cy};
                }
                double[] e1 = sub(p[1], p[0]), e3 = sub(p[3], p[0]);
                double[] n = cross(e3, e1);
                double nl = Math.sqrt(dot(n, n));
                if (nl < 1e-9) continue;
                n = new double[] {n[0] / nl, n[1] / nl, n[2] / nl};
                boolean front = dot(n, new double[] {1, 1, 1}) > 0;
                if (!front) n = new double[] {-n[0], -n[1], -n[2]}; // thin planes: face the viewer
                double[] a = proj(p[0], scale, c30, s30, size), b1 = proj(p[1], scale, c30, s30, size), b3 = proj(p[3], scale, c30, s30, size);
                double[] d1 = {b1[0] - a[0], b1[1] - a[1]}, d3 = {b3[0] - a[0], b3[1] - a[1]};
                double det = d1[0] * d3[1] - d1[1] * d3[0];
                if (Math.abs(det) < 1e-6) continue;
                double[] far = {a[0] + d1[0] + d3[0], a[1] + d1[1] + d3[1]};
                int minX = (int) Math.max(0, Math.floor(Math.min(Math.min(a[0], b1[0]), Math.min(b3[0], far[0])))),
                    maxX = (int) Math.min(size - 1, Math.ceil(Math.max(Math.max(a[0], b1[0]), Math.max(b3[0], far[0])))),
                    minY = (int) Math.max(0, Math.floor(Math.min(Math.min(a[1], b1[1]), Math.min(b3[1], far[1])))),
                    maxY = (int) Math.min(size - 1, Math.ceil(Math.max(Math.max(a[1], b1[1]), Math.max(b3[1], far[1]))));
                double[] t = norm(e1), bt = norm(e3);
                boolean mine = q.texture().equals(id);
                boolean vanilla = light.getSelectedItem();
                // Minecraft's own shading by face direction, from the model's (unturned) normal: up 1, down 0.5, N/S 0.8, E/W 0.6
                double[] n0 = cross(sub(q.p()[3], q.p()[0]), sub(q.p()[1], q.p()[0]));
                double n0l = Math.sqrt(dot(n0, n0));
                double ax = Math.abs(n0[0]) / Math.max(1e-9, n0l), ay = n0[1] / Math.max(1e-9, n0l), az = Math.abs(n0[2]) / Math.max(1e-9, n0l);
                double faceShade = Math.abs(ay) > 0.6 ? (ay > 0 ? 1.0 : 0.5) : az > ax ? 0.8 : 0.6;
                if (front && mine) {
                    double[] p2 = proj(p[2], scale, c30, s30, size);
                    edges.add(new double[][] {a, b1, p2, b3});
                }
                MosaicMaterial.Image other = mine ? null : faceTexture(q.texture());
                for (int py = minY; py <= maxY; py++)
                    for (int px = minX; px <= maxX; px++) {
                        double X = px + 0.5 - a[0], Y = py + 0.5 - a[1];
                        double s = (X * d3[1] - Y * d3[0]) / det, tt = (d1[0] * Y - d1[1] * X) / det;
                        if (s < 0 || s >= 1 || tt < 0 || tt >= 1) continue;
                        double[] P = {p[0][0] + e1[0] * s + e3[0] * tt, p[0][1] + e1[1] * s + e3[1] * tt, p[0][2] + e1[2] * s + e3[2] * tt};
                        double z = P[0] + P[1] + P[2];
                        int i = py * size + px;
                        if (z <= depth[i]) continue;
                        double[] uv = BlockModels.uvAt(q, s, tt);
                        int argb;
                        if (vanilla) argb = vanillaShade(mine ? (b.albedo() != null ? new MosaicMaterial.Image(b.w(), b.h(), b.albedo()) : base) : other,
                            uv[0] / 16, uv[1] / 16, faceShade);
                        else if (mine) argb = shade(b, base, uv[0] / 16, uv[1] / 16, n[0], n[1], n[2], t[0], t[1], t[2], bt[0], bt[1], bt[2], lx, ly, lz,
                            Math.min(b.w(), b.h()));
                        else argb = plain(other, uv[0] / 16, uv[1] / 16, n, lx, ly, lz);
                        if (argb == 0) continue;
                        depth[i] = z;
                        img.setRGB(px, py, argb);
                        if (mine) {
                            boolean strip = b.h() > b.w() && b.h() % b.w() == 0;
                            int tw = b.w(), th = strip ? b.w() : b.h();
                            int tx = Math.min(tw - 1, Math.max(0, (int) (uv[0] / 16 * tw))), ty = Math.min(th - 1, Math.max(0, (int) (uv[1] / 16 * th)));
                            pk[i] = ty * b.w() + tx;
                        } else pk[i] = -1;
                    }
            }
            pick = pk;
            pickSize = size;
            if (seams && !edges.isEmpty()) {
                Graphics2D g = StudioWidgets.smooth(img.getGraphics());
                g.setColor(new Color(255, 64, 64, 150));
                g.setStroke(new BasicStroke(1f));
                for (double[][] e : edges) {
                    java.awt.geom.Path2D.Double path = new java.awt.geom.Path2D.Double();
                    path.moveTo(e[0][0], e[0][1]);
                    for (int k = 1; k < 4; k++) path.lineTo(e[k][0], e[k][1]);
                    path.closePath();
                    g.draw(path);
                }
                g.dispose();
            }
            return img;
        }

        MosaicMaterial.Image faceTexture(String id) {
            return faceTextures.computeIfAbsent(id, k -> {
                String ns = k.substring(0, k.indexOf(':')), path = k.substring(k.indexOf(':') + 1);
                String entry = "assets/" + ns + "/textures/" + path + ".png";
                BufferedImage img = model.materialSource == null ? null : StudioLibrary.image(model.materialSource, entry);
                if (img == null) for (StudioLibrary.Source v : StudioLibrary.vanillaJars()) if ((img = StudioLibrary.image(v, entry)) != null) break;
                return img == null ? null : StudioLibrary.toMaterial(img);
            });
        }

        /** The vanilla look: the texel times the face's fixed shade, cut-out alpha. */
        static int vanillaShade(MosaicMaterial.Image tex, double u, double v, double shade) {
            if (tex == null) return 0xFF6A6A6A;
            int frame = Math.min(tex.w(), tex.h());
            int x = Math.min(tex.w() - 1, Math.max(0, (int) (u * tex.w()))), y = Math.min(frame - 1, Math.max(0, (int) (v * frame)));
            if (tex.w() == tex.h() || tex.h() % tex.w() != 0) y = Math.min(tex.h() - 1, Math.max(0, (int) (v * tex.h())));
            int c = tex.argb()[y * tex.w() + x];
            if ((c >>> 24) < 26) return 0;
            int r = (int) (((c >> 16) & 255) * shade), g = (int) (((c >> 8) & 255) * shade), b = (int) ((c & 255) * shade);
            return 0xFF000000 | r << 16 | g << 8 | b;
        }

        static int plain(MosaicMaterial.Image tex, double u, double v, double[] n, double lx, double ly, double lz) {
            if (tex == null) return 0xFF6A6A6A;
            int frame = Math.min(tex.w(), tex.h());
            int x = Math.min(frame - 1, Math.max(0, (int) (u * frame))), y = Math.min(frame - 1, Math.max(0, (int) (v * frame)));
            int c = tex.argb()[y * tex.w() + x];
            if ((c >>> 24) < 128) return 0;
            double k = 0.35 + 0.75 * Math.max(0, n[0] * lx + n[1] * ly + n[2] * lz);
            int r = (int) Math.min(255, ((c >> 16) & 255) * k), g = (int) Math.min(255, ((c >> 8) & 255) * k), bl = (int) Math.min(255, (c & 255) * k);
            return 0xFF000000 | r << 16 | g << 8 | bl;
        }

        double offX, offY;

        double[] proj(double[] p, double scale, double c30, double s30, int size) {
            return new double[] {size / 2.0 + offX + (p[0] - p[2]) * c30 * scale, size / 2.0 + offY + ((p[0] + p[2]) * s30 - p[1]) * scale};
        }

        static double[] sub(double[] a, double[] b) { return new double[] {a[0] - b[0], a[1] - b[1], a[2] - b[2]}; }
        static double dot(double[] a, double[] b) { return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]; }
        static double[] cross(double[] a, double[] b) {
            return new double[] {a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
        }
        static double[] norm(double[] a) {
            double l = Math.sqrt(dot(a, a));
            return l < 1e-12 ? a : new double[] {a[0] / l, a[1] / l, a[2] / l};
        }

        /** The item sprite face-on, lit by the sun with its baked normal, roughness and metal (Bedray PBR). */
        BufferedImage itemLit(MosaicMaterial.Baked b, MosaicMaterial.Image base) {
            int frame = Math.min(b.w(), b.h());
            int[] out = new int[b.w() * b.h()];
            double lx = Math.cos(lightPitch) * Math.cos(lightYaw), ly = Math.sin(lightPitch), lz = Math.cos(lightPitch) * Math.sin(lightYaw);
            for (int y = 0; y < b.h(); y++)
                for (int x = 0; x < b.w(); x++)
                    out[y * b.w() + x] = shade(b, base, (x + 0.5) / b.w(), (y + 0.5) / frame, 0, 0, 1, 1, 0, 0, 0, -1, 0, lx, ly, lz, frame);
            return StudioLibrary.fromArgb(b.w(), b.h(), out);
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
            // animation strips (h = n * w) use the first frame; other textures (entities: 64x32, 128x64...) map u, v to the whole image
            boolean strip = b.h() > b.w() && b.h() % b.w() == 0;
            int x = Math.min(b.w() - 1, Math.max(0, (int) (u * (strip ? frame : b.w())))), y = Math.min((strip ? frame : b.h()) - 1, Math.max(0, (int) (v * (strip ? frame : b.h()))));
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
            PreviewLight L = PreviewLight.current;
            double fx = Math.cos(L.fillPitch) * Math.cos(L.fillYaw), fy = Math.sin(L.fillPitch), fz = Math.cos(L.fillPitch) * Math.sin(L.fillYaw);
            double ndf = Math.max(0, Nx * fx + Ny * fy + Nz * fz);
            // viewer along (1, 1, 1) normalized (isometric)
            double vx = 0.577, vy = 0.577, vz = 0.577;
            double vn = vx * Nx + vy * Ny + vz * Nz;
            double rx = 2 * vn * Nx - vx, ry = 2 * vn * Ny - vy, rz = 2 * vn * Nz - vz; // reflection of the view
            double[] amb = L.env(Nx, Ny, Nz, 1.0), refl = L.env(rx, ry, rz, rough);
            double hx = lx + vx, hy = ly + vy, hz = lz + vz, hl = Math.sqrt(hx * hx + hy * hy + hz * hz);
            double ndh = Math.max(0, (Nx * hx + Ny * hy + Nz * hz) / hl);
            double a2 = Math.pow(rough, 4), d = ndh * ndh * (a2 - 1) + 1;
            double spec = a2 / (Math.PI * d * d) * ndl * 0.25;
            double[] c = {((albedo >> 16) & 255) / 255.0, ((albedo >> 8) & 255) / 255.0, (albedo & 255) / 255.0};
            int out = 0xFF000000;
            for (int k = 0; k < 3; k++) {
                double lin = Math.pow(c[k], 2.2);
                double fk = metal ? lin : f0;
                // sky light from everywhere (the preset's sky or the HDRI): diffuse ambient, and the reflection metals and smooth surfaces show
                double sunK = L.sun[k] * L.sunStrength;
                double env = refl[k] * (0.4 + 0.9 * (1 - rough)) * ao;
                double val = (metal ? 0 : lin) * (0.55 * amb[k] * ao + 0.85 * ndl * sunK + 0.85 * ndf * L.fill[k])
                    + fk * (Math.min(spec, 8) * sunK + env) + lin * emission * 2.5;
                int q = (int) Math.round(Math.pow(Math.min(1, val / (1 + val * 0.25)), 1 / 2.2) * 255);
                out |= Math.min(255, q) << (16 - k * 8);
            }
            return out;
        }

        void setLight(PreviewLight l, JButton menu) {
            PreviewLight.current = l;
            menu.setText("Light: " + l.name);
            if (light.getSelectedItem()) light.setSelectedItem(false); // the light only shows on the Bedray PBR look
            cacheVersion = -1;
            root.repaint();
        }

        public JComponent component() { return root; }
    }
}
