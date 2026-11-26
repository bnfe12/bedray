package io.github.andrexarlay.vulkancompat.shaderhost.mosaic.studio;

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static io.github.andrexarlay.vulkancompat.shaderhost.mosaic.studio.StudioUi.*;

/**
 * The studio's own file and folder picker, in the studio's look: places on the left (home, desktop, documents, downloads,
 * the game folder, the projects folder, drives), the folder's contents with picture thumbnails, a picture preview, a path
 * you can type, a filter, back / up and a new folder. Same calls as JFileChooser, so every picker of the studio uses it.
 */
final class StudioChooser {

    private static Path lastDir;
    private Path start;
    private int mode = JFileChooser.FILES_ONLY;
    private String[] exts;
    private String filterName;
    private String title;
    private boolean multi;
    private File selected;
    private File[] selectedAll = new File[0];
    private String suggestedName = "";

    StudioChooser() { }

    StudioChooser(File dir) { if (dir != null) start = dir.toPath(); }

    StudioChooser(String dir) { if (dir != null && !dir.isBlank()) start = Path.of(dir); }

    void setFileSelectionMode(int m) { mode = m; }

    void setFileFilter(FileNameExtensionFilter f) {
        exts = f == null ? null : f.getExtensions();
        filterName = f == null ? null : f.getDescription();
    }

    void setDialogTitle(String t) { title = t; }

    void setMultiSelectionEnabled(boolean b) { multi = b; }

    void setSelectedFile(File f) {
        if (f == null) return;
        if (f.getParentFile() != null && (start == null)) start = f.getParentFile().toPath();
        suggestedName = f.getName();
    }

    File getSelectedFile() { return selected; }

    File[] getSelectedFiles() { return selectedAll.length == 0 && selected != null ? new File[] {selected} : selectedAll; }

    int showOpenDialog(Component owner) { return show(owner, false); }

    int showSaveDialog(Component owner) { return show(owner, true); }

    // ------------------------------------------------------------------ the dialog

    private int show(Component owner, boolean save) {
        boolean folders = mode == JFileChooser.DIRECTORIES_ONLY;
        Window w = owner == null ? null : owner instanceof Window ww ? ww : SwingUtilities.getWindowAncestor(owner);
        JDialog d = new JDialog(w, title != null ? title : save ? "Save" : folders ? "Choose a folder" : "Open", Dialog.ModalityType.APPLICATION_MODAL);
        Rectangle scr = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        d.setSize(Math.min(900, scr.width - 60), Math.min(580, scr.height - 60));
        d.setLocationRelativeTo(w);
        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(PANEL);
        d.setContentPane(root);

        Path[] cur = {startDir()};
        List<Path> back = new ArrayList<>();
        DefaultListModel<Path> items = new DefaultListModel<>();
        JList<Path> list = new JList<>(items);
        JTextField path = field("");
        JTextField filter = field("");
        filter.setToolTipText("Filter by name");
        JTextField name = field(suggestedName);
        JLabel status = label(" ", FAINT);
        status.setFont(UI_S);
        PicturePreview preview = new PicturePreview();
        Runnable[] fill = new Runnable[1];
        java.util.function.Consumer<Path> go = p -> {
            if (p == null || !Files.isDirectory(p)) return;
            if (!p.equals(cur[0])) back.add(cur[0]);
            cur[0] = p;
            fill[0].run();
        };
        fill[0] = () -> {
            items.clear();
            path.setText(cur[0].toString());
            path.setCaretPosition(path.getText().length()); // the end of a long path shows: the folder you are in
            String q = filter.getText().trim().toLowerCase(Locale.ROOT);
            List<Path> dirs = new ArrayList<>(), files = new ArrayList<>();
            try (var s = Files.list(cur[0])) {
                for (Path p : (Iterable<Path>) s::iterator) {
                    String n = p.getFileName().toString();
                    if (n.startsWith(".") || n.startsWith("$")) continue;
                    try { if (Files.isHidden(p)) continue; } catch (Exception ignored) { }
                    if (!q.isEmpty() && !n.toLowerCase(Locale.ROOT).contains(q)) continue;
                    if (Files.isDirectory(p)) dirs.add(p);
                    else if (!folders && accepts(p)) files.add(p);
                }
            } catch (Exception e) {
                status.setText("Cannot read this folder");
            }
            java.util.Comparator<Path> byName = java.util.Comparator.comparing(p -> p.getFileName().toString().toLowerCase(Locale.ROOT));
            dirs.sort(byName);
            files.sort(byName);
            dirs.forEach(items::addElement);
            files.forEach(items::addElement);
            status.setText(dirs.size() + " folder" + (dirs.size() == 1 ? "" : "s") + (folders ? "" : ", " + files.size() + " file" + (files.size() == 1 ? "" : "s"))
                + (filterName != null && !folders ? "   ·   " + filterName : ""));
            preview.show(null);
        };

        // top: back, up, the path, the filter
        JPanel top = new JPanel(new BorderLayout(6, 0));
        top.setBackground(PANEL2);
        top.setBorder(new EmptyBorder(8, 10, 8, 10));
        JPanel nav = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        nav.setOpaque(false);
        JButton backB = iconButton("back", "Back", () -> {
            if (back.isEmpty()) return;
            cur[0] = back.remove(back.size() - 1);
            fill[0].run();
        });
        JButton upB = iconButton("up", "Up one folder", () -> go.accept(cur[0].getParent()));
        nav.add(backB);
        nav.add(upB);
        top.add(nav, BorderLayout.WEST);
        path.addActionListener(e -> {
            Path p = Path.of(path.getText().trim());
            if (Files.isDirectory(p)) go.accept(p);
            else if (!folders && Files.isRegularFile(p)) { selected = p.toFile(); d.dispose(); }
        });
        top.add(path, BorderLayout.CENTER);
        filter.setPreferredSize(new Dimension(160, path.getPreferredSize().height));
        filter.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e) { fill[0].run(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { fill[0].run(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { fill[0].run(); }
        });
        JPanel filterBox = new JPanel(new BorderLayout(4, 0));
        filterBox.setOpaque(false);
        JLabel fl = label("Filter", FAINT);
        fl.setFont(UI_S);
        filterBox.add(fl, BorderLayout.WEST);
        filterBox.add(filter, BorderLayout.CENTER);
        top.add(filterBox, BorderLayout.EAST);
        root.add(top, BorderLayout.NORTH);

        // left: places
        DefaultListModel<Object[]> places = new DefaultListModel<>();
        for (Object[] pl : places()) places.addElement(pl);
        JList<Object[]> placeList = new JList<>(places);
        placeList.setBackground(PANEL2);
        placeList.setFixedCellHeight(30);
        placeList.setCellRenderer((l, v, i, sel, foc) -> {
            JLabel c = new JLabel(v[1] == null ? ((String) v[0]).toUpperCase(Locale.ROOT) : (String) v[0], v[1] == null ? null : glyph((String) v[2]), SwingConstants.LEFT);
            c.setIconTextGap(8);
            c.setOpaque(true);
            c.setFont(UI);
            c.setBorder(new EmptyBorder(0, 12, 0, 8));
            boolean here = cur[0].equals(v[1]);
            c.setBackground(sel || here ? StudioWidgets.mix(PANEL2, SELECT, 0.3) : PANEL2);
            c.setForeground(v[1] == null ? FAINT : TEXT);
            if (v[1] == null) c.setFont(UI_S);
            return c;
        });
        placeList.addListSelectionListener(e -> {
            Object[] v = placeList.getSelectedValue();
            if (!e.getValueIsAdjusting() && v != null && v[1] != null) go.accept((Path) v[1]);
            else if (v != null && v[1] == null) placeList.clearSelection(); // a heading: not a place
        });
        JScrollPane ps = scroll(placeList);
        ps.setPreferredSize(new Dimension(190, 10));
        ps.setBorder(BorderFactory.createMatteBorder(0, 0, 0, 1, EDGE));
        root.add(ps, BorderLayout.WEST);

        // centre: the folder's contents
        Map<Path, ImageIcon> thumbs = new HashMap<>();
        list.setBackground(PANEL);
        list.setFixedCellHeight(34);
        list.setSelectionMode(multi ? ListSelectionModel.MULTIPLE_INTERVAL_SELECTION : ListSelectionModel.SINGLE_SELECTION);
        list.setCellRenderer((l, p, i, sel, foc) -> {
            boolean dir = Files.isDirectory(p);
            JPanel row = new JPanel(new BorderLayout(10, 0));
            row.setOpaque(true);
            row.setBackground(sel ? StudioWidgets.mix(PANEL, SELECT, 0.35) : i % 2 == 0 ? PANEL : StudioWidgets.mix(PANEL, PANEL2, 0.4));
            row.setBorder(new EmptyBorder(0, 12, 0, 12));
            Icon ic = dir ? glyph("folder") : isPicture(p) ? thumbs.computeIfAbsent(p, StudioChooser::thumb) : glyph("file");
            JLabel n = new JLabel(p.getFileName().toString(), ic, SwingConstants.LEFT);
            n.setIconTextGap(10);
            n.setFont(UI);
            n.setForeground(TEXT);
            row.add(n, BorderLayout.CENTER);
            JLabel meta = new JLabel(meta(p, dir));
            meta.setFont(UI_S);
            meta.setForeground(FAINT);
            row.add(meta, BorderLayout.EAST);
            return row;
        });
        list.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() != 2) return;
                Path p = list.getSelectedValue();
                if (p == null) return;
                if (Files.isDirectory(p)) go.accept(p);
                else { selected = p.toFile(); selectedAll = new File[] {selected}; d.dispose(); }
            }
        });
        list.addListSelectionListener(e -> {
            Path p = list.getSelectedValue();
            preview.show(p != null && isPicture(p) ? p : null);
            if (save && p != null && !Files.isDirectory(p)) name.setText(p.getFileName().toString());
        });
        list.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_BACK_SPACE, 0), "up");
        list.getActionMap().put("up", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) { go.accept(cur[0].getParent()); }
        });
        list.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "open");
        list.getActionMap().put("open", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) {
                Path p = list.getSelectedValue();
                if (p != null && Files.isDirectory(p)) go.accept(p);
                else if (p != null) { selected = p.toFile(); d.dispose(); }
            }
        });
        JPanel centre = new JPanel(new BorderLayout());
        centre.setOpaque(false);
        centre.add(scroll(list), BorderLayout.CENTER);
        if (!folders && exts != null && java.util.Arrays.stream(exts).anyMatch(x -> x.equals("png") || x.equals("jpg"))) {
            preview.setPreferredSize(new Dimension(230, 10));
            centre.add(preview, BorderLayout.EAST);
        }
        root.add(centre, BorderLayout.CENTER);

        // bottom: new folder, the name to save as, the buttons
        JPanel bottom = new JPanel(new BorderLayout(10, 0));
        bottom.setBackground(PANEL2);
        bottom.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, EDGE), new EmptyBorder(8, 10, 8, 10)));
        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        left.setOpaque(false);
        left.add(button("New folder", "Make a folder here", () -> {
            String n = JOptionPane.showInputDialog(d, "Name of the new folder", "New folder");
            if (n == null || n.isBlank()) return;
            try {
                Path made = Files.createDirectories(cur[0].resolve(n.trim()));
                fill[0].run();
                list.setSelectedValue(made, true);
            } catch (Exception ex) {
                status.setText("Could not make the folder: " + ex.getMessage());
            }
        }));
        left.add(status);
        bottom.add(left, BorderLayout.WEST);
        if (save) {
            JPanel nm = new JPanel(new BorderLayout(6, 0));
            nm.setOpaque(false);
            nm.add(label("Name", MUTED), BorderLayout.WEST);
            nm.add(name, BorderLayout.CENTER);
            bottom.add(nm, BorderLayout.CENTER);
        }
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        right.setOpaque(false);
        JButton cancel = button("Cancel", null, d::dispose);
        JButton ok = button(save ? "Save" : folders ? "Select folder" : "Open", null, () -> {
            if (save) {
                String n = name.getText().trim();
                if (n.isEmpty()) return;
                selected = cur[0].resolve(n).toFile();
            } else if (folders) {
                Path p = list.getSelectedValue();
                selected = (p != null && Files.isDirectory(p) ? p : cur[0]).toFile();
            } else {
                List<Path> sel = list.getSelectedValuesList();
                List<File> fs = new ArrayList<>();
                for (Path p : sel) if (Files.isRegularFile(p)) fs.add(p.toFile());
                if (fs.isEmpty()) {
                    Path p = list.getSelectedValue();
                    if (p != null && Files.isDirectory(p) && mode == JFileChooser.FILES_AND_DIRECTORIES) fs.add(p.toFile());
                    else if (p != null && Files.isDirectory(p)) { go.accept(p); return; }
                    else return;
                }
                selected = fs.get(0);
                selectedAll = fs.toArray(new File[0]);
            }
            d.dispose();
        });
        ok.setBackground(SELECT.darker());
        ok.setForeground(Color.WHITE);
        right.add(cancel);
        right.add(ok);
        bottom.add(right, BorderLayout.EAST);
        root.add(bottom, BorderLayout.SOUTH);
        d.getRootPane().setDefaultButton(ok);
        d.getRootPane().registerKeyboardAction(e -> d.dispose(), KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);

        fill[0].run();
        d.setVisible(true); // modal: returns when closed
        if (selected != null) {
            lastDir = selected.isDirectory() && folders ? selected.toPath().getParent() : cur[0];
            return JFileChooser.APPROVE_OPTION;
        }
        return JFileChooser.CANCEL_OPTION;
    }

    private Path startDir() {
        for (Path p : new Path[] {start, lastDir, home()}) if (p != null && Files.isDirectory(p)) return p.toAbsolutePath();
        if (start != null && start.getParent() != null && Files.isDirectory(start.getParent())) return start.getParent();
        return home();
    }

    private static Path home() { return Path.of(System.getProperty("user.home")); }

    private boolean accepts(Path p) {
        if (exts == null || exts.length == 0) return true;
        String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
        for (String e : exts) if (n.endsWith("." + e.toLowerCase(Locale.ROOT))) return true;
        return false;
    }

    /** Places: name, folder (null for a heading), icon kind. */
    private static List<Object[]> places() {
        List<Object[]> out = new ArrayList<>();
        Path h = home();
        out.add(new Object[] {"Home", h, "home"});
        for (String[] s : new String[][] {{"Desktop", "Desktop"}, {"Documents", "Documents"}, {"Downloads", "Downloads"}, {"Pictures", "Pictures"}}) {
            Path p = h.resolve(s[1]);
            if (!Files.isDirectory(p)) p = h.resolve("OneDrive").resolve(s[1]);
            if (Files.isDirectory(p)) out.add(new Object[] {s[0], p, "folder"});
        }
        out.add(new Object[] {"Minecraft", null, ""});
        try {
            Path gd = StudioUi.settings == null || StudioUi.settings.gameDir == null || StudioUi.settings.gameDir.isBlank() ? null : Path.of(StudioUi.settings.gameDir);
            if (gd != null && Files.isDirectory(gd)) {
                out.add(new Object[] {"Game folder", gd, "game"});
                for (String sub : new String[] {"shaderpacks", "resourcepacks", "saves"})
                    if (Files.isDirectory(gd.resolve(sub))) out.add(new Object[] {sub, gd.resolve(sub), "folder"});
            }
        } catch (Exception ignored) {
            // no game folder
        }
        Path projects = ProjectInfo.projectsHome();
        if (projects != null && Files.isDirectory(projects)) out.add(new Object[] {"Projects", projects, "star"});
        out.add(new Object[] {"Drives", null, ""});
        for (File r : File.listRoots()) out.add(new Object[] {r.getPath(), r.toPath(), "drive"});
        return out;
    }

    private static boolean isPicture(Path p) {
        String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
        return n.endsWith(".png") || n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".gif") || n.endsWith(".bmp");
    }

    private static String meta(Path p, boolean dir) {
        try {
            String when = java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy").withLocale(Locale.ENGLISH)
                .format(Files.getLastModifiedTime(p).toInstant().atZone(java.time.ZoneId.systemDefault()));
            if (dir) return when;
            long b = Files.size(p);
            String size = b < 1024 ? b + " B" : b < 1 << 20 ? (b >> 10) + " KB" : String.format(Locale.ROOT, "%.1f MB", b / 1048576.0);
            return size + "    " + when;
        } catch (Exception e) {
            return "";
        }
    }

    private static ImageIcon thumb(Path p) {
        try {
            if (Files.size(p) > 16 << 20) return (ImageIcon) null;
            BufferedImage img = ImageIO.read(p.toFile());
            if (img == null) return null;
            int s = 24;
            double k = Math.min(s / (double) img.getWidth(), s / (double) img.getHeight());
            int w = Math.max(1, (int) (img.getWidth() * k)), h = Math.max(1, (int) (img.getHeight() * k));
            BufferedImage t = new BufferedImage(s, s, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = t.createGraphics();
            boolean pixelArt = img.getWidth() <= 64;
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, pixelArt ? RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR : RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(img, (s - w) / 2, (s - h) / 2, w, h, null);
            g.dispose();
            return new ImageIcon(t);
        } catch (Exception e) {
            return null;
        }
    }

    /** Small painted icons in the theme's colours. */
    static Icon glyph(String kind) {
        return new Icon() {
            @Override public int getIconWidth() { return 24; }
            @Override public int getIconHeight() { return 24; }
            @Override public void paintIcon(Component c, Graphics g0, int x, int y) {
                Graphics2D g = (Graphics2D) g0.create();
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.translate(x, y);
                switch (kind) {
                    case "folder", "star", "game" -> {
                        Color f = kind.equals("game") ? new Color(0x7FB069) : kind.equals("star") ? new Color(0x8B6FD8) : new Color(0xD69A3C);
                        g.setColor(f.darker());
                        g.fillRoundRect(3, 6, 9, 5, 3, 3);
                        g.setColor(f);
                        g.fillRoundRect(3, 8, 18, 12, 4, 4);
                    }
                    case "home" -> {
                        g.setColor(new Color(0x4F8BF0));
                        g.fillPolygon(new int[] {12, 3, 21}, new int[] {4, 12, 12}, 3);
                        g.fillRect(6, 12, 12, 8);
                    }
                    case "drive" -> {
                        g.setColor(MUTED);
                        g.fillRoundRect(3, 8, 18, 9, 4, 4);
                        g.setColor(new Color(0x3FAE86));
                        g.fillOval(16, 11, 3, 3);
                    }
                    default -> {
                        g.setColor(MUTED);
                        g.fillPolygon(new int[] {6, 14, 18, 18, 6}, new int[] {3, 3, 7, 21, 21}, 5);
                        g.setColor(PANEL2);
                        g.fillPolygon(new int[] {14, 18, 14}, new int[] {3, 7, 7}, 3);
                    }
                }
                g.dispose();
            }
        };
    }

    private static JButton iconButton(String kind, String tip, Runnable r) {
        JButton b = button("", tip, r);
        b.setIcon(new Icon() {
            @Override public int getIconWidth() { return 16; }
            @Override public int getIconHeight() { return 16; }
            @Override public void paintIcon(Component c, Graphics g0, int x, int y) {
                Graphics2D g = (Graphics2D) g0.create();
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.setColor(TEXT);
                g.translate(x, y);
                if (kind.equals("back")) { g.drawLine(10, 3, 5, 8); g.drawLine(5, 8, 10, 13); }
                else { g.drawLine(3, 10, 8, 5); g.drawLine(8, 5, 13, 10); }
                g.dispose();
            }
        });
        b.setMargin(new Insets(4, 6, 4, 6));
        return b;
    }

    /** The picked picture, big, with its size. */
    private static final class PicturePreview extends JComponent {
        private BufferedImage img;
        private String info = "";

        void show(Path p) {
            img = null;
            info = "";
            if (p != null) try {
                img = ImageIO.read(p.toFile());
                if (img != null) info = img.getWidth() + " × " + img.getHeight();
            } catch (Exception ignored) {
                // not a picture after all
            }
            repaint();
        }

        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0;
            g.setColor(PANEL2);
            g.fillRect(0, 0, getWidth(), getHeight());
            g.setColor(EDGE);
            g.drawLine(0, 0, 0, getHeight());
            g.setFont(UI_S);
            g.setColor(FAINT);
            if (img == null) {
                g.drawString("Pick a picture to see it", 16, 28);
                return;
            }
            int pad = 14, w = getWidth() - pad * 2;
            double k = Math.min(w / (double) img.getWidth(), (getHeight() - 60) / (double) img.getHeight());
            int iw = (int) (img.getWidth() * k), ih = (int) (img.getHeight() * k);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, img.getWidth() <= 64 ? RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR
                : RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(img, pad + (w - iw) / 2, pad, iw, ih, null);
            g.drawString(info, pad, pad + ih + 20);
        }
    }
}
