package io.github.andrexarlay.vulkancompat.shaderhost.mosaic.studio;

import io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicTemplates;

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

import static io.github.andrexarlay.vulkancompat.shaderhost.mosaic.studio.StudioUi.*;

/**
 * The start screen: Home (actions, recent projects, templates), Projects (every project with search, sorting and
 * filters), Templates (by category, with pictures rendered by the studio) and Learn. Cards show the project's picture
 * (or its graph rendered over the built-in world), name, description and chips (GitHub, version, last change); the dots
 * on a card open its menu (picture, card, GitHub, folder). {@code onOpen} gets the graph file to open, or null for an
 * empty untitled graph.
 */
final class StartScreen extends JFrame {

    private final Consumer<Path> onOpen;
    private final boolean quitOnClose;
    private boolean done;
    private List<ProjectInfo> projects = List.of();
    private final CardLayout pages = new CardLayout();
    private final JPanel content = new JPanel(pages);
    private final Map<String, Nav> navs = new LinkedHashMap<>();
    private final JPanel homeRecent = grid(), projectsGrid = grid();
    private final JPanel templatesBox = new JPanel();
    private final Hint search = new Hint("Search projects");
    private final JComboBox<String> sort = new JComboBox<>(new String[] {"Last changed", "Name"});
    private String filter = "All";

    static final int CW = 232, CH = 130;

    /** Set when the user picks Continue: the editor restores the tabs of the last session. */
    static boolean continueSession;

    StartScreen(Consumer<Path> onOpen) { this(onOpen, true); }

    StartScreen(Consumer<Path> onOpen, boolean quitOnClose) {
        super("Mosaic Studio");
        this.onOpen = onOpen;
        this.quitOnClose = quitOnClose;
        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowClosed(java.awt.event.WindowEvent e) {
                GraphThumbs.release();
                if (!done && quitOnClose) System.exit(0); // closing the start screen quits, like closing Studio
            }
        });
        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(BG);
        root.add(rail(), BorderLayout.WEST);
        content.setBackground(BG);
        content.add(page(homePage()), "Home");
        content.add(page(projectsPage()), "Projects");
        content.add(page(templatesPage()), "Templates");
        content.add(page(learnPage()), "Learn");
        root.add(content, BorderLayout.CENTER);
        setContentPane(root);
        Rectangle scr = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        setSize(Math.min(1180, scr.width - 40), Math.min(760, scr.height - 30));
        setMinimumSize(new Dimension(760, 480));
        setLocationRelativeTo(null);
        reload();
        show("Home");
        keys(root);
    }

    /** Keyboard: Ctrl+N new project, Ctrl+O open, Ctrl+F search projects, Ctrl+1..4 the pages. */
    private void keys(JComponent root) {
        InputMap im = root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
        ActionMap am = root.getActionMap();
        Object[][] binds = {{"ctrl N", (Runnable) this::newProject}, {"ctrl O", (Runnable) this::openOther},
            {"ctrl F", (Runnable) () -> {
                show("Projects");
                search.requestFocusInWindow();
            }},
            {"ctrl 1", (Runnable) () -> show("Home")}, {"ctrl 2", (Runnable) () -> show("Projects")},
            {"ctrl 3", (Runnable) () -> show("Templates")}, {"ctrl 4", (Runnable) () -> show("Learn")}};
        for (Object[] bnd : binds) {
            String k = (String) bnd[0];
            Runnable run = (Runnable) bnd[1];
            im.put(KeyStroke.getKeyStroke(k), k);
            am.put(k, new AbstractAction() {
                @Override public void actionPerformed(java.awt.event.ActionEvent e) { run.run(); }
            });
        }
    }

    // ------------------------------------------------------------------ frame

    private Path gameDir() {
        String g = StudioUi.settings.gameDir;
        return g != null && !g.isBlank() && Files.isDirectory(Path.of(g)) ? Path.of(g) : null;
    }

    /** Where new and cloned projects go: the game's shaderpacks/ when the game folder is known, else the projects folder. */
    private Path home() {
        Path g = gameDir();
        return g != null ? g.resolve("shaderpacks") : ProjectInfo.projectsHome();
    }

    void show(String name) {
        pages.show(content, name);
        navs.forEach((k, n) -> n.setSelected(k.equals(name)));
    }

    private JComponent rail() {
        JPanel p = new JPanel() {
            @Override protected void paintComponent(Graphics g0) {
                Graphics2D g = StudioWidgets.smooth(g0);
                g.setColor(PANEL);
                g.fillRect(0, 0, getWidth(), getHeight());
                g.setColor(EDGE);
                g.drawLine(getWidth() - 1, 0, getWidth() - 1, getHeight());
                g.dispose();
            }
        };
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBorder(new EmptyBorder(22, 16, 16, 16));
        p.setPreferredSize(new Dimension(232, 10));
        JPanel brand = new JPanel(new BorderLayout(12, 0));
        brand.setOpaque(false);
        brand.add(new Logo(40), BorderLayout.WEST);
        JPanel names = new JPanel(new GridLayout(2, 1));
        names.setOpaque(false);
        JLabel t = label("Mosaic Studio", TEXT);
        t.setFont(UI_B.deriveFont(UI_B.getSize2D() + 3));
        JLabel v = label("for Bedray", FAINT);
        v.setFont(UI_S);
        names.add(t);
        names.add(v);
        brand.add(names, BorderLayout.CENTER);
        brand.setAlignmentX(0);
        brand.setMaximumSize(new Dimension(Integer.MAX_VALUE, 44));
        p.add(brand);
        p.add(Box.createVerticalStrut(26));
        for (String[] n : new String[][] {{"Home", "home"}, {"Projects", "grid"}, {"Templates", "template"}, {"Learn", "book"}}) {
            Nav nav = new Nav(n[0], n[1], () -> show(n[0]));
            navs.put(n[0], nav);
            p.add(nav);
            p.add(Box.createVerticalStrut(4));
        }
        p.add(Box.createVerticalGlue());
        // the game folder: where projects are found and new ones go
        JPanel game = new JPanel(new BorderLayout(0, 6)) {
            @Override protected void paintComponent(Graphics g0) {
                Graphics2D g = StudioWidgets.smooth(g0);
                g.setColor(PANEL2);
                g.fill(new RoundRectangle2D.Double(0, 0, getWidth() - 1, getHeight() - 1, 12, 12));
                g.dispose();
            }
        };
        game.setOpaque(false);
        game.setBorder(new EmptyBorder(10, 12, 10, 12));
        JLabel gl = label("GAME FOLDER", FAINT);
        gl.setFont(UI_S.deriveFont(Font.BOLD));
        game.add(gl, BorderLayout.NORTH);
        Path gd = gameDir();
        JLabel gv = label(gd == null ? "Not chosen" : shortPath(gd), gd == null ? WARN : MUTED);
        gv.setFont(UI_S);
        gv.setToolTipText(gd == null ? "Projects then go to " + ProjectInfo.projectsHome() : gd.toString());
        game.add(gv, BorderLayout.CENTER);
        JButton change = button(gd == null ? "Choose..." : "Change...", "The Minecraft folder with Bedray (its shaderpacks/ hold your projects)", () -> {
            JFileChooser fc = new JFileChooser(gd == null ? null : gd.toFile());
            fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            fc.setDialogTitle("The Minecraft folder (with mods/, shaderpacks/, saves/)");
            if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
            StudioUi.settings.gameDir = fc.getSelectedFile().toString();
            StudioUi.settings.save();
            gv.setText(shortPath(fc.getSelectedFile().toPath()));
            gv.setForeground(MUTED);
            reload();
        });
        change.setFont(UI_S);
        game.add(change, BorderLayout.SOUTH);
        game.setAlignmentX(0);
        game.setMaximumSize(new Dimension(Integer.MAX_VALUE, 96));
        p.add(game);
        p.add(Box.createVerticalStrut(10));
        JCheckBox showOnStart = new JCheckBox("Show this screen on start", StudioUi.settings.showStartScreen);
        showOnStart.setOpaque(false);
        showOnStart.setForeground(FAINT);
        showOnStart.setFont(UI_S);
        showOnStart.setFocusable(false);
        showOnStart.setAlignmentX(0);
        showOnStart.addActionListener(e -> {
            StudioUi.settings.showStartScreen = showOnStart.isSelected();
            StudioUi.settings.save();
        });
        p.add(showOnStart);
        return p;
    }

    private static String shortPath(Path p) {
        String s = p.toString();
        return s.length() <= 30 ? s : "..." + s.substring(s.length() - 28);
    }

    private JScrollPane page(JComponent body) {
        JPanel holder = new ScrollColumn();
        holder.setLayout(new BorderLayout());
        holder.setBackground(BG);
        holder.add(body, BorderLayout.NORTH);
        JScrollPane sp = new JScrollPane(holder, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        sp.setBorder(null);
        sp.getViewport().setBackground(BG);
        sp.getVerticalScrollBar().setUnitIncrement(32);
        darken(sp);
        sp.getViewport().setBackground(BG);
        return sp;
    }

    private static JPanel grid() {
        JPanel g = new JPanel(new WrapLayout(FlowLayout.LEFT, 16, 16));
        g.setOpaque(false);
        return g;
    }

    private static JComponent column() {
        JPanel c = new JPanel();
        c.setLayout(new BoxLayout(c, BoxLayout.Y_AXIS));
        c.setOpaque(false);
        c.setBorder(new EmptyBorder(26, 16, 26, 28)); // grids add their own 16 on the left
        return c;
    }

    private static JComponent heading(String title, String sub) {
        JPanel h = new JPanel(new GridLayout(sub == null ? 1 : 2, 1, 0, 2));
        h.setOpaque(false);
        JLabel t = label(title, TEXT);
        t.setFont(UI_B.deriveFont(UI_B.getSize2D() + 12));
        h.add(t);
        if (sub != null) {
            JLabel s = label(sub, MUTED);
            h.add(s);
        }
        h.setBorder(new EmptyBorder(0, 16, 0, 0));
        h.setAlignmentX(0);
        h.setMaximumSize(new Dimension(Integer.MAX_VALUE, h.getPreferredSize().height));
        return h;
    }

    private static JComponent section(String title, String link, Runnable onLink) {
        JPanel s = new JPanel(new BorderLayout());
        s.setOpaque(false);
        s.setBorder(new EmptyBorder(26, 16, 2, 0));
        JLabel t = label(title.toUpperCase(Locale.ROOT), FAINT);
        t.setFont(UI_S.deriveFont(Font.BOLD, UI_S.getSize2D() + 0.5f));
        s.add(t, BorderLayout.WEST);
        if (link != null) {
            JLabel l = label(link + "  >", SELECT);
            l.setFont(UI_S);
            l.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            l.addMouseListener(new MouseAdapter() {
                @Override public void mouseClicked(MouseEvent e) { onLink.run(); }
            });
            s.add(l, BorderLayout.EAST);
        }
        s.setAlignmentX(0);
        s.setMaximumSize(new Dimension(Integer.MAX_VALUE, s.getPreferredSize().height));
        return s;
    }

    // ------------------------------------------------------------------ pages

    private JComponent homePage() {
        JComponent c = column();
        int hour = java.time.LocalTime.now().getHour();
        String hello = hour < 5 ? "Good night" : hour < 12 ? "Good morning" : hour < 18 ? "Good afternoon" : "Good evening";
        c.add(heading(hello, "Shaders, renderers and materials for Bedray - pick up a project or start a new one."));
        c.add(Box.createVerticalStrut(20));
        boolean session = !StudioUi.settings.openTabs.isEmpty();
        JPanel actions = new JPanel(new GridLayout(1, session ? 5 : 4, 14, 0));
        if (session)
            actions.add(new Action("Continue", "Last session (" + StudioUi.settings.openTabs.size() + " tabs)", "page", new Color(0xE0605A), () -> {
                continueSession = true;
                finish(null);
            }));
        actions.setOpaque(false);
        actions.add(new Action("New project", "From a template  Ctrl+N", "plus", new Color(0x4F8BF0), this::newProject));
        actions.add(new Action("Open", "Project or graph  Ctrl+O", "folder", new Color(0xD69A3C), this::openOther));
        actions.add(new Action("Clone", "From GitHub", "branch", new Color(0x8B6FD8), this::cloneProject));
        actions.add(new Action("Empty graph", "Save it later", "page", new Color(0x3FAE86), () -> finish(null)));
        actions.setBorder(new EmptyBorder(0, 16, 0, 0));
        actions.setAlignmentX(0);
        actions.setMaximumSize(new Dimension(Integer.MAX_VALUE, 84));
        actions.setPreferredSize(new Dimension(600, 84));
        c.add(actions);
        c.add(section("Recent projects", "All projects", () -> show("Projects")));
        homeRecent.setAlignmentX(0);
        c.add(homeRecent);
        c.add(section("Start from a template", "All templates", () -> show("Templates")));
        JPanel tg = grid();
        List<MosaicTemplates.Template> ts = MosaicTemplates.all();
        String[] picks = {"Minecraft by parts", "Renderer from scratch (voxel GI)", "Cinematic", "Realistic water", "Bloom chain (mip)", "Dreamy glow"};
        for (String name : picks)
            ts.stream().filter(t -> t.name().equals(name)).findFirst().ifPresent(t -> tg.add(templateCard(t)));
        tg.setAlignmentX(0);
        c.add(tg);
        return c;
    }

    private JComponent projectsPage() {
        JComponent c = column();
        c.add(heading("Projects", "Shader packs with a Mosaic graph: the game's, your projects folder and the recent ones."));
        c.add(Box.createVerticalStrut(18));
        JPanel bar = new JPanel(new BorderLayout(12, 0));
        bar.setOpaque(false);
        search.setPreferredSize(new Dimension(280, search.getPreferredSize().height + 8));
        search.getDocument().addDocumentListener(onText(this::fillProjects));
        bar.add(search, BorderLayout.WEST);
        JPanel chips = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        chips.setOpaque(false);
        List<Chip> all = new ArrayList<>();
        for (String f : new String[] {"All", "Game packs", "My projects", "GitHub"}) {
            Chip ch = new Chip(f, f.equals(filter));
            ch.addMouseListener(new MouseAdapter() {
                @Override public void mouseClicked(MouseEvent e) {
                    filter = f;
                    for (Chip x : all) x.on = x.text.equals(f);
                    chips.repaint();
                    fillProjects();
                }
            });
            all.add(ch);
            chips.add(ch);
        }
        bar.add(chips, BorderLayout.CENTER);
        sort.setFont(UI_S);
        sort.setPreferredSize(new Dimension(150, search.getPreferredSize().height));
        bar.setBorder(new EmptyBorder(0, 16, 0, 0));
        sort.addActionListener(e -> fillProjects());
        bar.add(sort, BorderLayout.EAST);
        bar.setAlignmentX(0);
        bar.setMaximumSize(new Dimension(Integer.MAX_VALUE, bar.getPreferredSize().height));
        c.add(bar);
        c.add(Box.createVerticalStrut(6));
        projectsGrid.setAlignmentX(0);
        c.add(projectsGrid);
        return c;
    }

    private JComponent templatesPage() {
        JComponent c = column();
        c.add(heading("Templates", "Ready graphs to start from. The pictures are rendered by the studio over the built-in world."));
        templatesBox.setLayout(new BoxLayout(templatesBox, BoxLayout.Y_AXIS));
        templatesBox.setOpaque(false);
        templatesBox.setAlignmentX(0);
        Map<String, JPanel> byCat = new LinkedHashMap<>();
        for (MosaicTemplates.Template t : MosaicTemplates.all()) {
            JPanel g = byCat.computeIfAbsent(t.category(), k -> {
                templatesBox.add(section(k, null, null));
                JPanel gg = grid();
                gg.setAlignmentX(0);
                templatesBox.add(gg);
                return gg;
            });
            g.add(templateCard(t));
        }
        c.add(templatesBox);
        return c;
    }

    private JComponent learnPage() {
        JComponent c = column();
        c.add(heading("Learn", "The ideas behind Mosaic Studio in a minute each."));
        String[][] tips = {
            {"The Scene panel", "Your graph like Blender's outliner: groups, the parts of Minecraft's picture (sky, water, leaves...), passes and the output. Right click a part for ready looks."},
            {"Minecraft parts", "Every part of the picture is one node: give it a look with tint, brightness and contrast, or wire your own shader into its look input."},
            {"Preview", "The graph over a real world: one of your saves, the place the game captured, or the built-in landscape. View: false colour and clipping; Keep A to compare."},
            {"Every number is yours", "Graph settings (click empty canvas) hold the numbers every node builds on: sky, rays, world volume, material colours. Presets save and share them."},
            {"Keys", "Ctrl+Shift+P: every command. Ctrl+F: find a node. L / Shift+L: what feeds a node, what it feeds. F8: the next problem. Space: add a node."},
            {"Projects and GitHub", "A project is a shader pack folder with a card and a picture. Prepare for GitHub writes README, licence and a release workflow; Publish creates the repository."},
            {"The game", "Save inside <game>/shaderpacks/<pack>/shaders/bedray/mosaic.json with Live on: the game reloads every change. Result shows the game's own frame."},
            {"Agents", "Everything is also a command: mosaic help. An AI agent can build, render, lint and publish projects once Settings > Agent allows it."}};
        JPanel g = grid();
        for (String[] t : tips) g.add(new TipCard(t[0], t[1]));
        g.setAlignmentX(0);
        c.add(Box.createVerticalStrut(10));
        c.add(g);
        return c;
    }

    void reload() {
        projects = ProjectInfo.known(gameDir());
        homeRecent.removeAll();
        int n = 0;
        for (ProjectInfo p : projects) {
            if (n++ >= 6) break;
            homeRecent.add(projectCard(p));
        }
        if (projects.isEmpty()) homeRecent.add(empty("No projects yet", "New project makes one from a template; or choose the game folder to see its shader packs."));
        homeRecent.revalidate();
        homeRecent.repaint();
        fillProjects();
    }

    private void fillProjects() {
        projectsGrid.removeAll();
        String q = search.getText().trim().toLowerCase(Locale.ROOT);
        Path shaderpacks = gameDir() == null ? null : gameDir().resolve("shaderpacks").toAbsolutePath().normalize();
        List<ProjectInfo> list = new ArrayList<>();
        for (ProjectInfo p : projects) {
            String text = (p.name + " " + p.description + " " + String.join(" ", p.tags)).toLowerCase(Locale.ROOT);
            if (!q.isEmpty() && !text.contains(q)) continue;
            boolean inGame = shaderpacks != null && p.dir.toAbsolutePath().normalize().startsWith(shaderpacks);
            if (filter.equals("Game packs") && !inGame) continue;
            if (filter.equals("My projects") && inGame) continue;
            if (filter.equals("GitHub") && p.github.isBlank()) continue;
            list.add(p);
        }
        if ("Name".equals(sort.getSelectedItem())) list.sort((a, b) -> a.name.compareToIgnoreCase(b.name));
        for (ProjectInfo p : list) projectsGrid.add(projectCard(p));
        if (list.isEmpty()) projectsGrid.add(empty(projects.isEmpty() ? "No projects yet" : "Nothing matches",
            projects.isEmpty() ? "New project makes one from a template." : "Try another word or filter."));
        projectsGrid.revalidate();
        projectsGrid.repaint();
    }

    private static JComponent empty(String title, String text) {
        JPanel p = new JPanel(new GridLayout(2, 1, 0, 4));
        p.setOpaque(false);
        p.setBorder(new EmptyBorder(30, 4, 30, 4));
        JLabel t = label(title, MUTED);
        t.setFont(UI_B);
        p.add(t);
        p.add(label(text, FAINT));
        return p;
    }

    // ------------------------------------------------------------------ cards

    private Card projectCard(ProjectInfo p) {
        List<String> chips = new ArrayList<>();
        if (!p.github.isBlank()) chips.add("GitHub");
        if (!p.version.isBlank()) chips.add("v" + p.version);
        chips.add(ago(ProjectInfo.mtime(ProjectInfo.graphOf(p.dir))));
        Card c = new Card(p.name, p.description.isBlank() ? "No description yet" : p.description, chips, p.description.isBlank());
        BufferedImage pic = null;
        try {
            Path f = p.picture();
            if (f != null) pic = ImageIO.read(f.toFile());
        } catch (Exception ignored) {
            // no picture
        }
        if (pic != null) c.image = pic;
        else {
            String json = GraphThumbs.graphOf(p);
            if (json != null) c.image = GraphThumbs.get(json, () -> {
                c.image = GraphThumbs.get(json, null);
                c.repaint();
            });
        }
        c.setToolTipText(p.dir.toString());
        c.onOpen = () -> finish(ProjectInfo.graphOf(p.dir));
        c.menu = () -> cardMenu(p);
        return c;
    }

    private Card templateCard(MosaicTemplates.Template t) {
        Card c = new Card(t.name(), t.description(), List.of(t.category()), false);
        c.image = GraphThumbs.get(t.json(), () -> {
            c.image = GraphThumbs.get(t.json(), null);
            c.repaint();
        });
        c.onOpen = () -> {
            Path made = newProjectDialog(this, home(), t.name());
            if (made != null) finish(ProjectInfo.graphOf(made));
        };
        c.menu = () -> {
            JPopupMenu m = new JPopupMenu();
            item(m, "New project from this template...", c.onOpen);
            item(m, "Open as an untitled graph", () -> {
                try {
                    Path tmp = Files.createTempFile("mosaic-template", ".json");
                    Files.writeString(tmp, t.json());
                    finish(tmp);
                } catch (Exception ex) {
                    JOptionPane.showMessageDialog(this, ex.getMessage());
                }
            });
            return m;
        };
        return c;
    }

    static String ago(long millis) {
        if (millis <= 0) return "";
        long s = (System.currentTimeMillis() - millis) / 1000;
        if (s < 90) return "just now";
        if (s < 3600) return s / 60 + " min ago";
        if (s < 86400) return s / 3600 + " h ago";
        if (s < 86400 * 30) return s / 86400 + " d ago";
        return java.time.LocalDate.ofEpochDay(millis / 86400000L).toString();
    }

    /** A card: picture (or a coloured mosaic with the initial), title, two lines of text, chips; the dots open its menu. */
    final class Card extends JComponent {
        final String title, text;
        final List<String> chips;
        final boolean faintText;
        BufferedImage image;
        Runnable onOpen;
        java.util.function.Supplier<JPopupMenu> menu;
        boolean hover, hoverDots;

        Card(String title, String text, List<String> chips, boolean faintText) {
            this.title = title;
            this.text = text;
            this.chips = chips;
            this.faintText = faintText;
            setPreferredSize(new Dimension(CW + 12, CH + 104));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            MouseAdapter m = new MouseAdapter() {
                @Override public void mouseEntered(MouseEvent e) { hover = true; repaint(); }
                @Override public void mouseExited(MouseEvent e) { hover = hoverDots = false; repaint(); }
                @Override public void mouseMoved(MouseEvent e) {
                    boolean d = dots().contains(e.getPoint());
                    if (d != hoverDots) {
                        hoverDots = d;
                        repaint();
                    }
                }
                @Override public void mouseClicked(MouseEvent e) {
                    if ((SwingUtilities.isRightMouseButton(e) || dots().contains(e.getPoint())) && menu != null) {
                        JPopupMenu pm = menu.get();
                        StudioWidgets.fitToScreen(pm, Card.this);
                        pm.show(Card.this, e.getX(), e.getY());
                    } else if (SwingUtilities.isLeftMouseButton(e) && onOpen != null) onOpen.run();
                }
            };
            addMouseListener(m);
            addMouseMotionListener(m);
        }

        private Rectangle dots() { return new Rectangle(6 + CW - 38, 6 + 8, 30, 30); }

        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = StudioWidgets.smooth(g0);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            int x = 6, y = hover ? 3 : 6, w = CW, h = CH + 92;
            // shadow
            for (int i = 0; i < 6; i++) {
                g.setColor(new Color(0, 0, 0, hover ? 14 : 8));
                g.fill(new RoundRectangle2D.Double(x - i + 1, y - i + 4, w + i * 2 - 2, h + i * 2 - 2, 18 + i, 18 + i));
            }
            Shape card = new RoundRectangle2D.Double(x, y, w, h, 16, 16);
            g.setColor(hover ? StudioWidgets.mix(PANEL, SELECT, 0.06) : PANEL);
            g.fill(card);
            // picture
            Shape clip = g.getClip();
            g.clip(card);
            g.clipRect(x, y, w, CH);
            if (image != null) {
                double k = Math.max(w / (double) image.getWidth(), CH / (double) image.getHeight());
                int iw = (int) Math.ceil(image.getWidth() * k), ih = (int) Math.ceil(image.getHeight() * k);
                g.drawImage(image, x + (w - iw) / 2, y + (CH - ih) / 2, iw, ih, null);
            } else mosaic(g, x, y, w, CH, title);
            g.setPaint(new GradientPaint(0, y + CH - 40, new Color(0, 0, 0, 0), 0, y + CH, new Color(0, 0, 0, 70)));
            g.fillRect(x, y + CH - 40, w, 40);
            g.setClip(clip);
            g.setColor(hover ? SELECT : EDGE);
            g.setStroke(new BasicStroke(hover ? 1.6f : 1f));
            g.draw(card);
            // the menu dots
            if (hover && menu != null) {
                Rectangle d = dots();
                d.y = y + 8;
                g.setColor(new Color(0, 0, 0, hoverDots ? 170 : 120));
                g.fillOval(d.x, d.y, d.width, d.height);
                g.setColor(Color.WHITE);
                for (int i = -1; i <= 1; i++) g.fillOval(d.x + d.width / 2 - 2 + i * 6, d.y + d.height / 2 - 2, 4, 4);
            }
            // text
            int tx = x + 14, ty = y + CH + 22;
            g.setFont(UI_B);
            g.setColor(TEXT);
            g.drawString(fit(title, g.getFontMetrics(), w - 28), tx, ty);
            g.setFont(UI_S);
            g.setColor(faintText ? FAINT : MUTED);
            FontMetrics fm = g.getFontMetrics();
            List<String> lines = wrap(text, fm, w - 28, 2);
            for (int i = 0; i < lines.size(); i++) g.drawString(lines.get(i), tx, ty + 20 + i * (fm.getHeight()));
            // chips
            int cx = tx, cy = y + h - 26;
            g.setFont(UI_S.deriveFont(UI_S.getSize2D() - 0.5f));
            fm = g.getFontMetrics();
            for (String c : chips) {
                if (c.isEmpty()) continue;
                int cw = fm.stringWidth(c) + 14;
                if (cx + cw > x + w - 10) break;
                boolean gh = c.equals("GitHub");
                g.setColor(gh ? StudioWidgets.mix(PANEL, new Color(0x8B6FD8), 0.35) : PANEL2);
                g.fill(new RoundRectangle2D.Double(cx, cy, cw, 18, 9, 9));
                g.setColor(gh ? new Color(0xD8CCFF) : MUTED);
                g.drawString(c, cx + 7, cy + 13);
                cx += cw + 6;
            }
            g.dispose();
        }
    }

    /** A gradient with a tile mosaic and the initial: the face of a card without a picture. */
    static void mosaic(Graphics2D g, int x, int y, int w, int h, String name) {
        int hsh = name.hashCode();
        float hue = (hsh & 255) / 255f;
        Color a = Color.getHSBColor(hue, 0.5f, 0.5f), b = Color.getHSBColor(hue + 0.12f, 0.55f, 0.28f);
        g.setPaint(new GradientPaint(x, y, a, x + w, y + h, b));
        g.fillRect(x, y, w, h);
        java.util.Random r = new java.util.Random(hsh);
        int s = 22;
        for (int yy = 0; yy < h; yy += s)
            for (int xx = 0; xx < w; xx += s) {
                g.setColor(new Color(255, 255, 255, r.nextInt(18)));
                g.fillRoundRect(x + xx + 2, y + yy + 2, s - 4, s - 4, 6, 6);
            }
        String ini = name.isEmpty() ? "?" : name.substring(0, 1).toUpperCase(Locale.ROOT);
        g.setFont(UI_B.deriveFont(UI_B.getSize2D() + 26));
        FontMetrics fm = g.getFontMetrics();
        g.setColor(new Color(255, 255, 255, 215));
        g.drawString(ini, x + (w - fm.stringWidth(ini)) / 2, y + (h + fm.getAscent() - fm.getDescent()) / 2);
    }

    static String fit(String s, FontMetrics fm, int w) {
        if (fm.stringWidth(s) <= w) return s;
        while (s.length() > 1 && fm.stringWidth(s + "...") > w) s = s.substring(0, s.length() - 1);
        return s + "...";
    }

    static List<String> wrap(String s, FontMetrics fm, int w, int maxLines) {
        List<String> out = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        List<String> words = new ArrayList<>();
        for (String word : s.split("\\s+")) {
            while (fm.stringWidth(word) > w && word.length() > 4) { // a path or a long name: cut it
                int cut = word.length() - 1;
                while (cut > 1 && fm.stringWidth(word.substring(0, cut)) > w) cut--;
                words.add(word.substring(0, cut));
                word = word.substring(cut);
            }
            words.add(word);
        }
        for (String word : words) {
            String next = line.length() == 0 ? word : line + " " + word;
            if (fm.stringWidth(next) <= w) {
                line.setLength(0);
                line.append(next);
                continue;
            }
            if (line.length() > 0) out.add(line.toString());
            line.setLength(0);
            line.append(word);
            if (out.size() == maxLines) break;
        }
        if (out.size() < maxLines && line.length() > 0) out.add(line.toString());
        if (out.size() > maxLines) out = out.subList(0, maxLines);
        if (out.size() == maxLines && fm.stringWidth(String.join(" ", out)) < fm.stringWidth(s) - 4) {
            String last = out.get(maxLines - 1);
            out.set(maxLines - 1, fit(last + " ...", fm, w).replace(" ......", "..."));
        }
        return out;
    }

    /** A big action of the home page: a coloured round icon, a title and a line. */
    final class Action extends JComponent {
        final String title, text, icon;
        final Color color;
        boolean hover;

        Action(String title, String text, String icon, Color color, Runnable run) {
            this.title = title;
            this.text = text;
            this.icon = icon;
            this.color = color;
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setToolTipText(text);
            addMouseListener(new MouseAdapter() {
                @Override public void mouseEntered(MouseEvent e) { hover = true; repaint(); }
                @Override public void mouseExited(MouseEvent e) { hover = false; repaint(); }
                @Override public void mouseClicked(MouseEvent e) { run.run(); }
            });
        }

        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = StudioWidgets.smooth(g0);
            int w = getWidth() - 1, h = getHeight() - 1;
            Shape r = new RoundRectangle2D.Double(0, 0, w, h, 16, 16);
            g.setColor(hover ? StudioWidgets.mix(PANEL, color, 0.16) : PANEL);
            g.fill(r);
            g.setColor(hover ? color : EDGE);
            g.draw(r);
            int d = 42, ix = 16, iy = (h - d) / 2;
            g.setColor(StudioWidgets.mix(PANEL, color, 0.3));
            g.fillOval(ix, iy, d, d);
            g.setColor(color.brighter());
            g.setStroke(new BasicStroke(2.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            icon(g, icon, ix + d / 2, iy + d / 2);
            int tx = ix + d + 14;
            g.setFont(UI_B);
            g.setColor(TEXT);
            FontMetrics fm = g.getFontMetrics();
            g.drawString(fit(title, fm, w - tx - 10), tx, h / 2 - 3);
            g.setFont(UI_S);
            g.setColor(MUTED);
            g.drawString(fit(text, g.getFontMetrics(), w - tx - 10), tx, h / 2 + 15);
            g.dispose();
        }
    }

    /** Small line icons drawn around (cx, cy). */
    static void icon(Graphics2D g, String kind, int cx, int cy) {
        switch (kind) {
            case "plus" -> {
                g.drawLine(cx - 8, cy, cx + 8, cy);
                g.drawLine(cx, cy - 8, cx, cy + 8);
            }
            case "folder" -> {
                g.drawRoundRect(cx - 10, cy - 6, 20, 14, 3, 3);
                g.drawLine(cx - 10, cy - 6, cx - 7, cy - 9);
                g.drawLine(cx - 7, cy - 9, cx - 1, cy - 9);
                g.drawLine(cx - 1, cy - 9, cx + 1, cy - 6);
            }
            case "branch" -> {
                g.drawOval(cx - 8, cy - 10, 5, 5);
                g.drawOval(cx - 8, cy + 5, 5, 5);
                g.drawOval(cx + 4, cy - 6, 5, 5);
                g.drawLine(cx - 6, cy - 5, cx - 6, cy + 5);
                g.drawArc(cx - 6, cy - 3, 12, 10, 0, 90);
            }
            case "page" -> {
                g.drawRoundRect(cx - 7, cy - 9, 14, 18, 3, 3);
                g.drawLine(cx - 3, cy - 3, cx + 3, cy - 3);
                g.drawLine(cx - 3, cy + 1, cx + 3, cy + 1);
            }
            case "home" -> {
                g.drawLine(cx - 7, cy, cx, cy - 7);
                g.drawLine(cx, cy - 7, cx + 7, cy);
                g.drawRect(cx - 5, cy, 10, 7);
            }
            case "grid" -> {
                for (int i = 0; i < 2; i++) for (int j = 0; j < 2; j++) g.drawRoundRect(cx - 7 + i * 8, cy - 7 + j * 8, 6, 6, 2, 2);
            }
            case "template" -> {
                g.drawRoundRect(cx - 8, cy - 7, 16, 14, 3, 3);
                g.drawLine(cx - 8, cy - 2, cx + 8, cy - 2);
                g.drawLine(cx - 2, cy - 2, cx - 2, cy + 7);
            }
            case "book" -> {
                g.drawLine(cx, cy - 6, cx, cy + 7);
                g.drawArc(cx - 9, cy - 8, 9, 4, 0, 180);
                g.drawLine(cx - 9, cy - 6, cx - 9, cy + 6);
                g.drawLine(cx - 9, cy + 6, cx, cy + 7);
                g.drawArc(cx, cy - 8, 9, 4, 0, 180);
                g.drawLine(cx + 9, cy - 6, cx + 9, cy + 6);
                g.drawLine(cx + 9, cy + 6, cx, cy + 7);
            }
            default -> { }
        }
    }

    /** A navigation item of the rail. */
    final class Nav extends JComponent {
        final String text, icon;
        boolean selected, hover;

        Nav(String text, String icon, Runnable run) {
            this.text = text;
            this.icon = icon;
            setAlignmentX(0);
            setMaximumSize(new Dimension(Integer.MAX_VALUE, 38));
            setPreferredSize(new Dimension(200, 38));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            addMouseListener(new MouseAdapter() {
                @Override public void mouseEntered(MouseEvent e) { hover = true; repaint(); }
                @Override public void mouseExited(MouseEvent e) { hover = false; repaint(); }
                @Override public void mouseClicked(MouseEvent e) { run.run(); }
            });
        }

        void setSelected(boolean s) {
            selected = s;
            repaint();
        }

        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = StudioWidgets.smooth(g0);
            if (selected || hover) {
                g.setColor(selected ? StudioWidgets.mix(PANEL, SELECT, 0.22) : PANEL2);
                g.fill(new RoundRectangle2D.Double(0, 0, getWidth() - 1, getHeight() - 1, 10, 10));
            }
            if (selected) {
                g.setColor(SELECT);
                g.fillRoundRect(0, 9, 3, getHeight() - 18, 3, 3);
            }
            g.setColor(selected ? SELECT : MUTED);
            g.setStroke(new BasicStroke(1.7f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            icon(g, icon, 22, getHeight() / 2);
            g.setFont(selected ? UI_B : UI);
            g.setColor(selected ? TEXT : MUTED);
            g.drawString(text, 44, getHeight() / 2 + g.getFontMetrics().getAscent() / 2 - 2);
            g.dispose();
        }
    }

    /** The Mosaic mark: four tilted tiles in the accent's family. */
    static final class Logo extends JComponent {
        final int s;

        Logo(int s) {
            this.s = s;
            setPreferredSize(new Dimension(s, s));
        }

        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = StudioWidgets.smooth(g0);
            Color[] c = {new Color(0x4F8BF0), new Color(0x8B6FD8), new Color(0x3FAE86), new Color(0xE0A040)};
            int t = s / 2 - 2;
            g.rotate(Math.toRadians(-8), s / 2.0, s / 2.0);
            for (int i = 0; i < 4; i++) {
                g.setColor(c[i]);
                g.fill(new RoundRectangle2D.Double(1 + (i % 2) * (t + 2), 1 + (i / 2) * (t + 2), t, t, 7, 7));
            }
            g.dispose();
        }
    }

    /** A filter chip. */
    static final class Chip extends JComponent {
        final String text;
        boolean on;

        Chip(String text, boolean on) {
            this.text = text;
            this.on = on;
            setFont(UI_S);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            FontMetrics fm = getFontMetrics(UI_S);
            setPreferredSize(new Dimension(fm.stringWidth(text) + 24, 28));
        }

        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = StudioWidgets.smooth(g0);
            Shape r = new RoundRectangle2D.Double(0, 1, getWidth() - 1, getHeight() - 3, getHeight() - 3, getHeight() - 3);
            g.setColor(on ? StudioWidgets.mix(PANEL, SELECT, 0.3) : PANEL);
            g.fill(r);
            g.setColor(on ? SELECT : EDGE);
            g.draw(r);
            g.setFont(UI_S);
            g.setColor(on ? TEXT : MUTED);
            FontMetrics fm = g.getFontMetrics();
            g.drawString(text, (getWidth() - fm.stringWidth(text)) / 2, getHeight() / 2 + fm.getAscent() / 2 - 2);
            g.dispose();
        }
    }

    /** A tip of the Learn page. */
    static final class TipCard extends JComponent {
        final String title, text;

        TipCard(String title, String text) {
            this.title = title;
            this.text = text;
            setPreferredSize(new Dimension(CW * 2 / 2 + 70, 128));
        }

        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = StudioWidgets.smooth(g0);
            Shape r = new RoundRectangle2D.Double(0, 0, getWidth() - 1, getHeight() - 1, 14, 14);
            g.setColor(PANEL);
            g.fill(r);
            g.setColor(EDGE);
            g.draw(r);
            g.setColor(SELECT);
            g.fillRoundRect(14, 16, 4, 18, 4, 4);
            g.setFont(UI_B);
            g.setColor(TEXT);
            g.drawString(title, 26, 30);
            g.setFont(UI_S);
            g.setColor(MUTED);
            FontMetrics fm = g.getFontMetrics();
            List<String> lines = wrap(text, fm, getWidth() - 40, 4);
            for (int i = 0; i < lines.size(); i++) g.drawString(lines.get(i), 26, 54 + i * fm.getHeight());
            g.dispose();
        }
    }

    /** A text field with a grey hint while it is empty. */
    static final class Hint extends JTextField {
        final String hint;

        Hint(String hint) {
            this.hint = hint;
            setFont(UI);
            setBackground(PANEL);
            setForeground(TEXT);
            setCaretColor(TEXT);
            setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(EDGE), new EmptyBorder(4, 10, 4, 10)));
        }

        @Override protected void paintComponent(Graphics g0) {
            super.paintComponent(g0);
            if (!getText().isEmpty()) return;
            Graphics2D g = StudioWidgets.smooth(g0);
            g.setColor(FAINT);
            g.setFont(getFont());
            Insets in = getInsets();
            g.drawString(hint, in.left, (getHeight() + g.getFontMetrics().getAscent()) / 2 - 2);
            g.dispose();
        }
    }

    /** A column that follows the width of its scroll pane. */
    static final class ScrollColumn extends JPanel implements Scrollable {
        @Override public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
        @Override public int getScrollableUnitIncrement(Rectangle r, int o, int d) { return 32; }
        @Override public int getScrollableBlockIncrement(Rectangle r, int o, int d) { return Math.max(32, r.height - 60); }
        @Override public boolean getScrollableTracksViewportWidth() { return true; }
        @Override public boolean getScrollableTracksViewportHeight() {
            return getParent() instanceof JViewport v && v.getHeight() > getPreferredSize().height; // short pages fill the window
        }
    }

    // ------------------------------------------------------------------ actions

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
        item(m, "Edit the card (name, description, author, version)...", () -> {
            if (editCard(this, p)) reload();
        });
        item(m, "Rename...", () -> {
            String n = JOptionPane.showInputDialog(this, "New name", p.name);
            if (n == null || n.isBlank()) return;
            try {
                p.name = n.trim();
                p.save();
                Path target = p.dir.resolveSibling(n.trim().replaceAll("[\\\\/:*?\"<>|]+", "_"));
                if (!Files.exists(target)) Files.move(p.dir, target);
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(this, "Could not rename: " + ex.getMessage());
            }
            reload();
        });
        item(m, "Duplicate...", () -> {
            String n = JOptionPane.showInputDialog(this, "Name of the copy", p.name + " copy");
            if (n == null || n.isBlank()) return;
            Path target = p.dir.resolveSibling(n.trim().replaceAll("[\\\\/:*?\"<>|]+", "_"));
            if (Files.exists(target)) {
                JOptionPane.showMessageDialog(this, "There is already a folder " + target.getFileName());
                return;
            }
            try (var walk = Files.walk(p.dir)) {
                for (Path f : walk.toList()) {
                    Path rel = p.dir.relativize(f);
                    if (rel.toString().startsWith(".git")) continue; // a copy is a new project, not the same repository
                    Path to = target.resolve(rel.toString());
                    if (Files.isDirectory(f)) Files.createDirectories(to);
                    else Files.copy(f, to);
                }
                ProjectInfo copy = ProjectInfo.load(target);
                copy.name = n.trim();
                copy.github = "";
                copy.created = java.time.LocalDate.now().toString();
                copy.save();
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(this, "Could not copy: " + ex.getMessage());
            }
            reload();
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

    static Path newProjectDialog(Component owner, Path home) { return newProjectDialog(owner, home, "Minecraft by parts"); }

    /** New project: a template gallery on the left, the card on the right; returns the folder or null. */
    static Path newProjectDialog(Component owner, Path home, String template) {
        Window w = owner == null ? null : owner instanceof Window ow ? ow : SwingUtilities.getWindowAncestor(owner);
        JDialog d = new JDialog(w, "New project", Dialog.ModalityType.APPLICATION_MODAL);
        List<MosaicTemplates.Template> ts = MosaicTemplates.all();
        DefaultListModel<MosaicTemplates.Template> lm = new DefaultListModel<>();
        ts.forEach(lm::addElement);
        JList<MosaicTemplates.Template> list = new JList<>(lm);
        list.setBackground(PANEL);
        list.setFixedCellHeight(74);
        list.setCellRenderer((l, t, i, sel, foc) -> new JComponent() {
            @Override protected void paintComponent(Graphics g0) {
                Graphics2D g = StudioWidgets.smooth(g0);
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g.setColor(sel ? StudioWidgets.mix(PANEL, SELECT, 0.25) : PANEL);
                g.fillRect(0, 0, getWidth(), getHeight());
                BufferedImage img = GraphThumbs.get(t.json(), list::repaint);
                Shape thumb = new RoundRectangle2D.Double(10, 9, 98, 56, 8, 8);
                Shape clip = g.getClip();
                g.clip(thumb);
                if (img != null) g.drawImage(img, 10, 9, 98, 56, null);
                else mosaic(g, 10, 9, 98, 56, t.name());
                g.setClip(clip);
                g.setFont(UI_B);
                g.setColor(TEXT);
                g.drawString(fit(t.name(), g.getFontMetrics(), getWidth() - 132), 120, 30);
                g.setFont(UI_S);
                g.setColor(sel ? TEXT : FAINT);
                g.drawString(t.category(), 120, 50);
                g.dispose();
            }
        });
        for (int i = 0; i < ts.size(); i++) if (ts.get(i).name().equals(template)) list.setSelectedIndex(i);
        if (list.getSelectedIndex() < 0) list.setSelectedIndex(0);
        JScrollPane lsp = new JScrollPane(list);
        lsp.setBorder(BorderFactory.createMatteBorder(0, 0, 0, 1, EDGE));
        lsp.setPreferredSize(new Dimension(320, 460));
        lsp.getVerticalScrollBar().setUnitIncrement(24);
        darken(lsp);

        JPanel form = new JPanel();
        form.setLayout(new BoxLayout(form, BoxLayout.Y_AXIS));
        form.setBackground(BG);
        form.setBorder(new EmptyBorder(18, 22, 18, 22));
        JLabel tdesc = new JLabel();
        tdesc.setForeground(MUTED);
        tdesc.setFont(UI_S);
        Runnable showDesc = () -> {
            var t = list.getSelectedValue();
            tdesc.setText(t == null ? "" : "<html><div style='width:340px'>" + esc(t.description()) + "</div></html>");
        };
        list.addListSelectionListener(e -> showDesc.run());
        showDesc.run();
        JLabel title = label("New project", TEXT);
        title.setFont(UI_B.deriveFont(UI_B.getSize2D() + 8));
        title.setAlignmentX(0);
        form.add(title);
        tdesc.setAlignmentX(0);
        form.add(Box.createVerticalStrut(4));
        form.add(tdesc);
        form.add(Box.createVerticalStrut(14));
        Hint name = new Hint("My shaders");
        Hint desc = new Hint("What it looks like, in a sentence");
        Hint folder = new Hint(home.toString());
        folder.setText(home.toString());
        Hint pic = new Hint("Optional: a picture for the card (or later from the preview)");
        JComboBox<String> lic = new JComboBox<>(ProjectGit.LICENSES);
        JCheckBox github = new JCheckBox("Prepare for GitHub (README, release workflow, .gitignore)", true);
        github.setOpaque(false);
        github.setForeground(TEXT);
        field(form, "Name", name, null);
        field(form, "Description", desc, null);
        field(form, "Folder", folder, () -> {
            JFileChooser fc = new JFileChooser(folder.getText());
            fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            if (fc.showOpenDialog(d) == JFileChooser.APPROVE_OPTION) folder.setText(fc.getSelectedFile().toString());
        });
        field(form, "Picture", pic, () -> {
            JFileChooser fc = new JFileChooser();
            fc.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("Pictures", "png", "jpg", "jpeg"));
            if (fc.showOpenDialog(d) == JFileChooser.APPROVE_OPTION) pic.setText(fc.getSelectedFile().toString());
        });
        field(form, "Licence", lic, null);
        github.setAlignmentX(0);
        form.add(github);
        form.add(Box.createVerticalGlue());
        Path[] made = new Path[1];
        JButton cancel = button("Cancel", null, d::dispose);
        JButton create = button("Create project", "Make the folder with the graph and its card", () -> {
            String n = name.getText().trim().isEmpty() ? "My shaders" : name.getText().trim();
            Path dir = Path.of(folder.getText().trim()).resolve(n.replaceAll("[\\\\/:*?\"<>|]+", "_"));
            try {
                var t = list.getSelectedValue();
                ProjectInfo info = ProjectInfo.create(dir, n, desc.getText().trim(), t.json());
                info.author = StudioUi.settings.projectAuthor;
                info.save();
                if (!pic.getText().isBlank()) {
                    BufferedImage img = ImageIO.read(Path.of(pic.getText().trim()).toFile());
                    if (img != null) info.setPicture(img);
                } else {
                    BufferedImage thumb = GraphThumbs.get(t.json(), null);
                    if (thumb != null) info.setPicture(thumb); // the template's picture until you choose one
                }
                if (github.isSelected()) ProjectGit.prepare(info, (String) lic.getSelectedItem(), false);
                made[0] = dir;
                d.dispose();
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(d, "Could not create the project: " + ex.getMessage());
            }
        });
        create.setBackground(SELECT.darker());
        create.setForeground(Color.WHITE);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        buttons.setOpaque(false);
        buttons.add(cancel);
        buttons.add(create);
        buttons.setAlignmentX(0);
        buttons.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
        form.add(buttons);
        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(BG);
        root.add(lsp, BorderLayout.WEST);
        root.add(form, BorderLayout.CENTER);
        d.setContentPane(root);
        d.getRootPane().setDefaultButton(create);
        d.setSize(900, 560);
        d.setLocationRelativeTo(owner);
        d.setVisible(true);
        return made[0];
    }

    private static void field(JPanel form, String label, JComponent input, Runnable browse) {
        JLabel l = StudioUi.label(label, MUTED);
        l.setFont(UI_S.deriveFont(Font.BOLD));
        l.setAlignmentX(0);
        form.add(l);
        form.add(Box.createVerticalStrut(4));
        JComponent row = input;
        if (browse != null) {
            JPanel r = new JPanel(new BorderLayout(6, 0));
            r.setOpaque(false);
            r.add(input, BorderLayout.CENTER);
            r.add(button("Browse...", null, browse), BorderLayout.EAST);
            row = r;
        }
        row.setAlignmentX(0);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, Math.max(32, input.getPreferredSize().height + 6)));
        form.add(row);
        form.add(Box.createVerticalStrut(12));
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
        p.tags = new ArrayList<>();
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
                int width = target.getWidth();
                if (width <= 0 && target.getParent() != null) width = target.getParent().getWidth();
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
