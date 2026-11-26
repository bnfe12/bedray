package io.github.andrexarlay.vulkancompat.shaderhost.mosaic.studio;

import io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicTemplates;

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

import static io.github.andrexarlay.vulkancompat.shaderhost.mosaic.studio.StudioUi.*;

/**
 * The start screen: Studio opens on your projects instead of the whole editor - cards with the picture, name and
 * description of every project (the game's shader packs with a Mosaic graph, your projects folder, recent ones), a
 * search, New project (from a template, with a picture and a description, ready for GitHub), Open, Clone from GitHub,
 * and per project: set the picture, edit the card, prepare / publish / update on GitHub, show the folder.
 * {@code onOpen} gets the graph file to open, or null for an empty untitled graph.
 */
final class StartScreen extends JFrame {

    private final Consumer<Path> onOpen;
    private final boolean quitOnClose;
    private final JPanel grid = new JPanel(new WrapLayout(FlowLayout.LEFT, 14, 14));
    private final JTextField search = field("");
    private List<ProjectInfo> projects = List.of();
    private boolean done;

    static final int CW = 260, CH = 146;

    StartScreen(Consumer<Path> onOpen) { this(onOpen, true); }

    StartScreen(Consumer<Path> onOpen, boolean quitOnClose) {
        super("Mosaic Studio");
        this.onOpen = onOpen;
        this.quitOnClose = quitOnClose;
        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowClosed(java.awt.event.WindowEvent e) {
                if (!done && quitOnClose) System.exit(0); // closing the start screen quits, like closing Studio
            }
        });
        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(BG);
        root.add(side(), BorderLayout.WEST);
        JPanel main = new JPanel(new BorderLayout(0, 10));
        main.setBackground(BG);
        main.setBorder(new EmptyBorder(18, 18, 12, 18));
        JPanel head = new JPanel(new BorderLayout(10, 0));
        head.setOpaque(false);
        JLabel t = label("Projects", TEXT);
        t.setFont(UI_B.deriveFont(UI_B.getSize2D() + 8));
        head.add(t, BorderLayout.WEST);
        search.setToolTipText("Find a project by name, description or tag");
        search.setPreferredSize(new Dimension(260, search.getPreferredSize().height + 6));
        search.getDocument().addDocumentListener(onText(this::fill));
        head.add(search, BorderLayout.EAST);
        main.add(head, BorderLayout.NORTH);
        grid.setBackground(BG);
        JScrollPane sp = scroll(grid);
        sp.setBorder(null);
        sp.getViewport().setBackground(BG);
        main.add(sp, BorderLayout.CENTER);
        JCheckBox show = new JCheckBox("Show this screen when Studio starts", StudioUi.settings.showStartScreen);
        show.setOpaque(false);
        show.setForeground(MUTED);
        show.setFont(UI_S);
        show.addActionListener(e -> {
            StudioUi.settings.showStartScreen = show.isSelected();
            StudioUi.settings.save();
        });
        main.add(show, BorderLayout.SOUTH);
        root.add(main, BorderLayout.CENTER);
        setContentPane(root);
        Rectangle scr = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        setSize(Math.min(1100, scr.width - 40), Math.min(700, scr.height - 40));
        setLocationRelativeTo(null);
        reload();
    }

    private Path gameDir() {
        String g = StudioUi.settings.gameDir;
        return g != null && !g.isBlank() && Files.isDirectory(Path.of(g)) ? Path.of(g) : null;
    }

    /** Where new and cloned projects go: the game's shaderpacks/ when the game folder is known, else the projects folder. */
    private Path home() {
        Path g = gameDir();
        return g != null ? g.resolve("shaderpacks") : ProjectInfo.projectsHome();
    }

    private JComponent side() {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBackground(PANEL);
        p.setBorder(new EmptyBorder(20, 16, 16, 16));
        p.setPreferredSize(new Dimension(230, 10));
        JLabel logo = label("Mosaic Studio", TEXT);
        logo.setFont(UI_B.deriveFont(UI_B.getSize2D() + 6));
        logo.setAlignmentX(0);
        p.add(logo);
        JLabel sub = new JLabel("<html>Shaders, renderers and<br>materials for Bedray</html>");
        sub.setForeground(FAINT);
        sub.setFont(UI_S);
        sub.setAlignmentX(0);
        p.add(sub);
        p.add(Box.createVerticalStrut(18));
        for (Object[] b : new Object[][] {
            {"New project...", "A shader pack folder from a template, with a picture and a description", (Runnable) this::newProject},
            {"Open...", "A project folder, a pack or a mosaic.json", (Runnable) this::openOther},
            {"Clone from GitHub...", "A Mosaic project from a GitHub repository into your projects", (Runnable) this::cloneProject},
            {"Empty graph", "Studio without a project (save it later)", (Runnable) () -> finish(null)}}) {
            JButton btn = button((String) b[0], (String) b[1], (Runnable) b[2]);
            btn.setAlignmentX(0);
            btn.setMaximumSize(new Dimension(Integer.MAX_VALUE, btn.getPreferredSize().height + 6));
            p.add(btn);
            p.add(Box.createVerticalStrut(6));
        }
        p.add(Box.createVerticalGlue());
        JLabel where = new JLabel("New projects go to " + home().getFileName());
        where.setToolTipText(home().toString());
        where.setForeground(FAINT);
        where.setFont(UI_S);
        where.setAlignmentX(0);
        p.add(where);
        return p;
    }

    void reload() {
        projects = ProjectInfo.known(gameDir());
        fill();
    }

    private void fill() {
        grid.removeAll();
        String q = search.getText().trim().toLowerCase(Locale.ROOT);
        int shown = 0;
        for (ProjectInfo p : projects) {
            String text = (p.name + " " + p.description + " " + String.join(" ", p.tags)).toLowerCase(Locale.ROOT);
            if (!q.isEmpty() && !text.contains(q)) continue;
            grid.add(card(p));
            shown++;
        }
        if (shown == 0) {
            JLabel none = label(projects.isEmpty() ? "No projects yet: New project... makes one (or choose the game folder in Settings > Game)"
                : "No project matches", MUTED);
            grid.add(none);
        }
        grid.revalidate();
        grid.repaint();
    }

    private JComponent card(ProjectInfo p) {
        BufferedImage pic = null;
        try {
            Path f = p.picture();
            if (f != null) pic = ImageIO.read(f.toFile());
        } catch (Exception ignored) {
            // no picture
        }
        BufferedImage img = pic;
        JPanel c = new JPanel(new BorderLayout()) {
            boolean hover;
            {
                addMouseListener(new MouseAdapter() {
                    @Override public void mouseEntered(MouseEvent e) { hover = true; repaint(); }
                    @Override public void mouseExited(MouseEvent e) { hover = false; repaint(); }
                });
            }
            @Override protected void paintComponent(Graphics g0) {
                Graphics2D g = StudioWidgets.smooth(g0);
                g.setColor(hover ? StudioWidgets.mix(PANEL, SELECT, 0.18) : PANEL);
                g.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 14, 14);
                g.setColor(hover ? SELECT : EDGE);
                g.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 14, 14);
                Shape clip = g.getClip();
                g.clip(new java.awt.geom.RoundRectangle2D.Double(1, 1, CW - 2, CH, 12, 12));
                if (img != null) {
                    double k = Math.max(CW / (double) img.getWidth(), CH / (double) img.getHeight());
                    int w = (int) (img.getWidth() * k), h = (int) (img.getHeight() * k);
                    g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                    g.drawImage(img, (CW - w) / 2, (CH - h) / 2, w, h, null);
                } else {
                    int hsh = p.name.hashCode();
                    Color a = Color.getHSBColor((hsh & 255) / 255f, 0.45f, 0.45f), b = Color.getHSBColor(((hsh >> 8) & 255) / 255f, 0.5f, 0.25f);
                    g.setPaint(new GradientPaint(0, 0, a, CW, CH, b));
                    g.fillRect(0, 0, CW, CH);
                    g.setColor(new Color(255, 255, 255, 200));
                    g.setFont(UI_B.deriveFont(UI_B.getSize2D() + 18));
                    String ini = p.name.isEmpty() ? "?" : p.name.substring(0, 1).toUpperCase(Locale.ROOT);
                    g.drawString(ini, (CW - g.getFontMetrics().stringWidth(ini)) / 2, CH / 2 + g.getFontMetrics().getAscent() / 3);
                }
                g.setClip(clip);
                g.dispose();
            }
        };
        c.setOpaque(false);
        c.setPreferredSize(new Dimension(CW, CH + 92));
        c.setToolTipText(p.dir.toString());
        JPanel text = new JPanel();
        text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));
        text.setOpaque(false);
        text.setBorder(new EmptyBorder(CH + 8, 10, 8, 10));
        JLabel n = label(p.name, TEXT);
        n.setFont(UI_B);
        text.add(n);
        String desc = p.description.isBlank() ? "No description yet (right click > Edit card)" : p.description;
        JTextArea d = new JTextArea(desc.length() > 96 ? desc.substring(0, 93) + "..." : desc, 2, 10);
        d.setLineWrap(true);
        d.setWrapStyleWord(true);
        d.setEditable(false);
        d.setFocusable(false);
        d.setOpaque(false);
        d.setBorder(null);
        d.setForeground(p.description.isBlank() ? FAINT : MUTED);
        d.setFont(UI_S);
        d.setAlignmentX(0);
        d.setMaximumSize(new Dimension(CW - 20, 40));
        d.setPreferredSize(new Dimension(CW - 20, 34));
        n.setAlignmentX(0);
        text.add(d);
        String meta = (p.github.isBlank() ? "" : "GitHub  ") + (p.author.isBlank() ? "" : p.author + "  ") + (p.version.isBlank() ? "" : "v" + p.version);
        if (!meta.isBlank()) {
            JLabel m = label(meta.trim(), FAINT);
            m.setFont(UI_S);
            text.add(m);
        }
        c.add(text, BorderLayout.CENTER);
        MouseAdapter click = new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (SwingUtilities.isRightMouseButton(e)) {
                    JPopupMenu m = cardMenu(p);
                    StudioWidgets.fitToScreen(m, c);
                    m.show(e.getComponent(), e.getX(), e.getY());
                } else if (e.getClickCount() >= 1) finish(ProjectInfo.graphOf(p.dir));
            }
        };
        c.addMouseListener(click);
        text.addMouseListener(click);
        d.addMouseListener(click);
        c.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return c;
    }

    private JPopupMenu cardMenu(ProjectInfo p) {
        JPopupMenu m = new JPopupMenu();
        item(m, "Open", () -> finish(ProjectInfo.graphOf(p.dir)));
        item(m, "Set the picture...", () -> {
            JFileChooser fc = new JFileChooser();
            fc.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("Pictures", "png", "jpg", "jpeg"));
            if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
            try {
                BufferedImage img = ImageIO.read(fc.getSelectedFile());
                if (img != null) p.setPicture(img);
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(this, "Could not use that picture: " + ex.getMessage());
            }
            reload();
        });
        item(m, "Edit card (name, description, author, version)...", () -> {
            if (editCard(this, p)) reload();
        });
        m.addSeparator();
        item(m, "Prepare for GitHub (README, workflow, licence)...", () -> {
            prepareDialog(this, p);
            reload();
        });
        item(m, "Publish to GitHub...", () -> publishDialog(this, p, this::reload));
        item(m, "Update from GitHub (pull, description)", () -> runLogged(this, "Update from GitHub", out -> ProjectGit.refresh(p, out), this::reload));
        m.addSeparator();
        item(m, "Show the folder", () -> {
            try {
                Desktop.getDesktop().open(p.dir.toFile());
            } catch (Exception ignored) {
                // no desktop
            }
        });
        return m;
    }

    private static void item(JPopupMenu m, String label, Runnable r) {
        JMenuItem i = new JMenuItem(label);
        i.addActionListener(e -> r.run());
        m.add(i);
    }

    private void finish(Path graph) {
        done = true;
        dispose();
        onOpen.accept(graph);
    }

    private void openOther() {
        JFileChooser fc = new JFileChooser(home().toFile());
        fc.setFileSelectionMode(JFileChooser.FILES_AND_DIRECTORIES);
        if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        Path p = fc.getSelectedFile().toPath();
        finish(Files.isDirectory(p) ? ProjectInfo.graphOf(p) : p);
    }

    private void newProject() {
        Path made = newProjectDialog(this, home());
        if (made != null) finish(ProjectInfo.graphOf(made));
    }

    private void cloneProject() {
        String addr = JOptionPane.showInputDialog(this, "GitHub repository (owner/name or its address)", "");
        if (addr == null || addr.isBlank()) return;
        Path[] made = new Path[1];
        runLogged(this, "Clone from GitHub", out -> made[0] = ProjectGit.cloneInto(addr, home(), out).dir, () -> {
            reload();
            if (made[0] != null && ProjectInfo.isProject(made[0])) finish(ProjectInfo.graphOf(made[0]));
        });
    }

    // ------------------------------------------------------------------ dialogs (also used from the editor: File > Project)

    /** New project: name, description, template, picture, GitHub files; returns the folder or null. */
    static Path newProjectDialog(Component owner, Path home) {
        JTextField name = new JTextField("My shaders", 24);
        JTextArea desc = new JTextArea(3, 24);
        desc.setLineWrap(true);
        desc.setWrapStyleWord(true);
        List<MosaicTemplates.Template> ts = MosaicTemplates.all();
        JComboBox<String> tpl = new JComboBox<>(ts.stream().map(t -> t.name() + "  (" + t.category() + ")").toArray(String[]::new));
        for (int i = 0; i < ts.size(); i++) if (ts.get(i).name().equals("Minecraft by parts")) tpl.setSelectedIndex(i);
        JTextField folder = new JTextField(home.toString(), 24);
        JTextField pic = new JTextField("", 24);
        pic.setToolTipText("A picture for the project card and pack.png (optional; Preview > Image > Use as the project picture later)");
        JCheckBox github = new JCheckBox("Prepare for GitHub (README, release workflow, .gitignore)", true);
        JComboBox<String> lic = new JComboBox<>(ProjectGit.LICENSES);
        JPanel p = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(3, 3, 3, 3);
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.HORIZONTAL;
        Object[][] rows = {{"Name", name}, {"Description", new JScrollPane(desc)}, {"Start from", tpl}, {"Folder", folder}, {"Picture", pic},
            {"", github}, {"Licence", lic}};
        for (int i = 0; i < rows.length; i++) {
            c.gridx = 0;
            c.gridy = i;
            c.weightx = 0;
            p.add(new JLabel((String) rows[i][0]), c);
            c.gridx = 1;
            c.weightx = 1;
            p.add((Component) rows[i][1], c);
        }
        if (JOptionPane.showConfirmDialog(owner, p, "New project", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION)
            return null;
        String n = name.getText().trim();
        if (n.isEmpty()) return null;
        Path dir = Path.of(folder.getText().trim()).resolve(n.replaceAll("[\\\\/:*?\"<>|]+", "_"));
        try {
            ProjectInfo info = ProjectInfo.create(dir, n, desc.getText().trim(), ts.get(tpl.getSelectedIndex()).json());
            info.author = StudioUi.settings.projectAuthor;
            info.save();
            if (!pic.getText().isBlank()) {
                BufferedImage img = ImageIO.read(Path.of(pic.getText().trim()).toFile());
                if (img != null) info.setPicture(img);
            }
            if (github.isSelected()) ProjectGit.prepare(info, (String) lic.getSelectedItem(), false);
            return dir;
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(owner, "Could not create the project: " + ex.getMessage());
            return null;
        }
    }

    static boolean editCard(Component owner, ProjectInfo p) {
        JTextField name = new JTextField(p.name, 24), author = new JTextField(p.author, 24), version = new JTextField(p.version, 10),
            github = new JTextField(p.github, 24), tags = new JTextField(String.join(", ", p.tags), 24);
        JTextArea desc = new JTextArea(p.description, 4, 24);
        desc.setLineWrap(true);
        desc.setWrapStyleWord(true);
        JPanel f = new JPanel(new GridLayout(0, 2, 4, 4));
        for (Object[] r : new Object[][] {{"Name", name}, {"Description", new JScrollPane(desc)}, {"Author", author}, {"Version", version},
            {"GitHub", github}, {"Tags", tags}}) {
            f.add(new JLabel((String) r[0]));
            f.add((Component) r[1]);
        }
        if (JOptionPane.showConfirmDialog(owner, f, "Project card", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION)
            return false;
        p.name = name.getText().trim();
        p.description = desc.getText().trim();
        p.author = author.getText().trim();
        p.version = version.getText().trim();
        p.github = github.getText().trim();
        p.tags = new java.util.ArrayList<>();
        for (String t : tags.getText().split(",")) if (!t.isBlank()) p.tags.add(t.trim());
        try {
            p.save();
            return true;
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(owner, "Could not save the card: " + ex.getMessage());
            return false;
        }
    }

    static void prepareDialog(Component owner, ProjectInfo p) {
        JComboBox<String> lic = new JComboBox<>(ProjectGit.LICENSES);
        if (!p.license.isBlank()) lic.setSelectedItem(p.license);
        JCheckBox over = new JCheckBox("Write README and LICENSE again (they exist already)", false);
        JPanel f = new JPanel(new GridLayout(0, 1, 4, 4));
        f.add(new JLabel("Writes README.md (picture, description, install), .gitignore, .github/workflows/release.yml"));
        f.add(new JLabel("(a version tag such as v1.0.0 makes a release with the pack zip) and the licence you choose."));
        f.add(lic);
        f.add(over);
        if (JOptionPane.showConfirmDialog(owner, f, "Prepare for GitHub", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return;
        try {
            List<Path> w = ProjectGit.prepare(p, (String) lic.getSelectedItem(), over.isSelected());
            JOptionPane.showMessageDialog(owner, "Written:\n" + String.join("\n", w.stream().map(x -> p.dir.relativize(x).toString()).toList()));
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(owner, "Could not write the files: " + ex.getMessage());
        }
    }

    static void publishDialog(Component owner, ProjectInfo p, Runnable after) {
        Object[] opts = {"Private", "Public", "Cancel"};
        int r = JOptionPane.showOptionDialog(owner, "<html>Creates a new repository <b>" + esc(p.name) + "</b> on your GitHub account with the GitHub CLI (gh)"
                + "<br>and pushes this project to it (git init, commit, gh repo create --push).<br>Needs git and gh (gh auth login once).</html>",
            "Publish to GitHub", JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE, null, opts, opts[0]);
        if (r != 0 && r != 1) return;
        boolean pub = r == 1;
        runLogged(owner, "Publish to GitHub", out -> {
            if (!Files.exists(p.dir.resolve("README.md"))) ProjectGit.prepare(p, null, false);
            ProjectGit.publish(p, pub, out);
        }, after);
    }

    interface Job { void run(Consumer<String> out) throws Exception; }

    /** Runs a job off the UI thread with its log in a window; {@code after} runs on the UI thread when it ends. */
    static void runLogged(Component owner, String title, Job job, Runnable after) {
        JDialog d = new JDialog(SwingUtilities.getWindowAncestor(owner), title);
        JTextArea log = new JTextArea(16, 70);
        log.setEditable(false);
        log.setFont(MONO);
        d.add(new JScrollPane(log));
        d.pack();
        d.setLocationRelativeTo(owner);
        d.setVisible(true);
        Consumer<String> out = s -> SwingUtilities.invokeLater(() -> {
            log.append(s + "\n");
            log.setCaretPosition(log.getDocument().getLength());
        });
        Thread t = new Thread(() -> {
            try {
                job.run(out);
                out.accept("done");
            } catch (Exception e) {
                out.accept("failed: " + e.getMessage());
            }
            if (after != null) SwingUtilities.invokeLater(after);
        }, "mosaic-project-job");
        t.setDaemon(true);
        t.start();
    }

    /** FlowLayout that wraps inside a scroll pane (cards flow onto new rows). */
    static final class WrapLayout extends FlowLayout {
        WrapLayout(int align, int h, int v) { super(align, h, v); }

        @Override public Dimension preferredLayoutSize(Container target) { return size(target); }

        @Override public Dimension minimumLayoutSize(Container target) { return size(target); }

        private Dimension size(Container target) {
            synchronized (target.getTreeLock()) {
                int width = target.getParent() == null ? target.getWidth() : target.getParent().getWidth();
                if (width <= 0) width = Integer.MAX_VALUE;
                Insets in = target.getInsets();
                int max = width - in.left - in.right - getHgap() * 2, x = 0, y = in.top + getVgap(), row = 0;
                for (Component c : target.getComponents()) {
                    if (!c.isVisible()) continue;
                    Dimension d = c.getPreferredSize();
                    if (x > 0 && x + d.width > max) {
                        y += row + getVgap();
                        x = 0;
                        row = 0;
                    }
                    x += d.width + getHgap();
                    row = Math.max(row, d.height);
                }
                return new Dimension(width == Integer.MAX_VALUE ? x : width, y + row + getVgap() + in.bottom);
            }
        }
    }
}
