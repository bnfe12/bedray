package io.github.andrexarlay.vulkancompat.shaderhost.mosaic.studio;

import io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicDocument;
import io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicGraph;
import io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicTemplates;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.text.JTextComponent;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static io.github.andrexarlay.vulkancompat.shaderhost.mosaic.studio.StudioUi.*;

/**
 * Mosaic Studio: windows, areas, the global keyboard, commands and restyling. One {@link StudioModel} is shared by
 * every window, so pop-out areas are as live as docked ones.
 */
final class StudioApp {

    private static StudioApp instance;

    static StudioApp get() { return instance; }

    final StudioModel model;
    StudioSettings settings;
    final List<Dock.StudioWindow> windows = new ArrayList<>();
    Dock.StudioWindow main;
    Dock.Area lastArea;
    SettingsWindow settingsWindow;
    private JLabel statusLeft, statusMid, statusRight;
    private JButton liveButton;
    private static Image icon;
    boolean capturingKeys;

    StudioApp(MosaicDocument doc, StudioSettings settings) {
        instance = this;
        this.settings = settings;
        StudioUi.apply(settings);
        this.model = new StudioModel(doc, settings);
        KeyboardFocusManager kfm = KeyboardFocusManager.getCurrentKeyboardFocusManager();
        kfm.addPropertyChangeListener("focusOwner", e -> {
            if (e.getNewValue() instanceof Component c) {
                Dock.Area a = Dock.areaOf(c);
                if (a != null) lastArea = a;
                if (c instanceof GraphPanel g) model.activeGraph = g;
            }
        });
        kfm.addKeyEventDispatcher(this::dispatch);
        model.openFunction = this::openFunctionTab;
        model.openStage = this::openStageTab;
        model.openRenderer = () -> switchWorkspace(StudioModel.Workspace.RENDERER);
        model.runAction = this::run;
        model.listen(this::updateStatus);
        initTabs();
        try {
            StudioSettings.ownWrite = Files.exists(StudioSettings.file()) ? Files.getLastModifiedTime(StudioSettings.file()).toMillis() : -1;
        } catch (Exception ignored) {
            // no settings file yet
        }
        Timer external = new Timer(1000, e -> applyExternalSettings());
        external.start();
    }

    static Image appIcon() {
        if (icon == null) {
            BufferedImage img = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = img.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int[] colors = {0x4A76B8, 0x2F8F7C, 0xC06A3A, 0x8A5CC8};
            for (int i = 0; i < 4; i++) {
                g.setColor(new Color(colors[i]));
                g.fillRoundRect(2 + (i % 2) * 15, 2 + (i / 2) * 15, 13, 13, 5, 5);
            }
            g.dispose();
            icon = img;
        }
        return icon;
    }

    // ================================================================== windows

    void open() {
        String layout = settings.layout == null ? Dock.defaultLayout() : settings.layout;
        Rectangle screen = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        for (var el : Dock.parseWindows(layout)) {
            var o = el.getAsJsonObject();
            boolean isMain = o.has("main") && o.get("main").getAsBoolean();
            if (isMain && main != null) continue;
            Dock.StudioWindow w = newWindow(isMain, Dock.load(model, o.getAsJsonObject("tree")));
            if (o.has("w")) w.setBounds(o.get("x").getAsInt(), o.get("y").getAsInt(), o.get("w").getAsInt(), o.get("h").getAsInt());
            else w.setBounds(screen);
            w.setVisible(true);
            if (!o.has("w") || (o.has("maximized") && o.get("maximized").getAsBoolean())) w.toggleMaximize();
        }
        if (main == null) {
            Dock.StudioWindow w = newWindow(true, Dock.load(model,
                Dock.parseWindows(Dock.defaultLayout()).get(0).getAsJsonObject().getAsJsonObject("tree")));
            w.setBounds(screen);
            w.setVisible(true);
            w.toggleMaximize();
        }
        main.toFront();
        ProjectStore.load(model, graph());
        updateStatus();
        String shot = System.getProperty("mosaic.selfshot");
        String shotMaterial = System.getProperty("mosaic.selfshot.material"); // e.g. block/copper_block: verification aid
        String shotWorkspace = System.getProperty("mosaic.selfshot.workspace"); // e.g. RENDERER: verification aid
        if (shot != null && shotWorkspace != null) {
            Timer w = new Timer(1200, e -> {
                if (shotWorkspace.equals("PACK")) openPackMapTab();
                else switchWorkspace(StudioModel.Workspace.valueOf(shotWorkspace));
                String sel = System.getProperty("mosaic.selfshot.select");
                MosaicDocument.ENode n = sel == null ? null : model.doc.byId(sel);
                if (n != null) model.select(n);
                if (model.rendererDoc()) model.writeRenderer();
                if (Boolean.getBoolean("mosaic.selfshot.dump")) {
                    Timer d = new Timer(1500, e2 -> System.out.println("LAYOUT " + Dock.saveAll(windows)));
                    d.setRepeats(false);
                    d.start();
                }
            });
            w.setRepeats(false);
            w.start();
        }
        if (shot != null && shotMaterial != null) {
            Timer m = new Timer(1200, e -> {
                StudioLibrary.vanillaJars().stream().findFirst().ifPresent(src ->
                    openMaterialTab(src, "assets/minecraft/textures/" + shotMaterial + ".png"));
                if (Boolean.getBoolean("mosaic.selfshot.paint") && model.baseImage != null) { // a diagonal stroke
                    model.paintTool = StudioModel.PaintTool.BRUSH;
                    model.paintColor = new Color(0x30C0FF);
                    model.beginStroke();
                    for (int i = 0; i < model.baseImage.w(); i++) model.paintAt(i, i);
                    model.endStroke();
                    model.paintTool = StudioModel.PaintTool.NAVIGATE;
                }
                if (Boolean.getBoolean("mosaic.selfshot.back")) {
                    Timer back = new Timer(800, e2 -> switchWorkspace(StudioModel.Workspace.SHADERS));
                    back.setRepeats(false);
                    back.start();
                }
            });
            m.setRepeats(false);
            m.start();
        }
        if (shot != null && Boolean.getBoolean("mosaic.selfshot.renderer")) { // a renderer of your own: verification aid
            Timer rT = new Timer(1200, e -> newRendererTab(true));
            rT.setRepeats(false);
            rT.start();
        }
        String shotPanel = System.getProperty("mosaic.selfshot.panel"); // e.g. GUIDE: verification aid
        if (shot != null && shotPanel != null) {
            Timer pT = new Timer(1500, e -> popOut(Panels.Type.valueOf(shotPanel)));
            pT.setRepeats(false);
            pT.start();
        }
        String shotStage = System.getProperty("mosaic.selfshot.stage"); // e.g. bedray.sky: verification aid
        if (shot != null && shotStage != null) {
            Timer stT = new Timer(1200, e -> openStageTab(shotStage));
            stT.setRepeats(false);
            stT.start();
        }
        String shotFunction = System.getProperty("mosaic.selfshot.function"); // e.g. light/sun/sunSolidAngle: verification aid
        if (shot != null && shotFunction != null) {
            Timer fnT = new Timer(1200, e -> {
                openFunctionTab(shotFunction);
                String sel = System.getProperty("mosaic.selfshot.select");
                MosaicDocument.ENode n = sel == null ? null : model.doc.byId(sel);
                if (n != null) model.select(n);
                if (Boolean.getBoolean("mosaic.selfshot.apply")) {
                    model.doc.dirty = true;
                    model.save(false);
                    model.applyFunctions();
                }
            });
            fnT.setRepeats(false);
            fnT.start();
        }
        if (shot != null && Boolean.getBoolean("mosaic.selfshot.settings")) {
            Timer s = new Timer(1000, e -> {
                settingsWindow = new SettingsWindow(this);
                String page = System.getProperty("mosaic.selfshot.settingsPage");
                if (page != null) settingsWindow.showPage(page);
                settingsWindow.setVisible(true);
            });
            s.setRepeats(false);
            s.start();
        }
        String shotStart = System.getProperty("mosaic.selfshot.start"); // a start-screen page, "settings" opens settings from it: verification aid
        if (shot != null && shotStart != null) {
            Timer st = new Timer(1500, e -> {
                StartScreen ss = new StartScreen(p -> { }, false);
                ss.setVisible(true);
                if (shotStart.equals("settings")) ss.openSettings();
            });
            st.setRepeats(false);
            st.start();
        }
        String shotPalette = System.getProperty("mosaic.selfshot.palette"); // a query: verification aid
        if (shot != null && shotPalette != null) {
            Timer pa = new Timer(2200, e -> CommandPalette.open(this, false, shotPalette));
            pa.setRepeats(false);
            pa.start();
        }
        if (shot != null) {
            Timer t = new Timer(Integer.getInteger("mosaic.selfshot.delay", 3500), e -> {
                try {
                    int dn = 0;
                    for (Window dw : Window.getWindows())
                        if (dw instanceof JDialog jd && jd.isShowing() && jd != CommandPalette.last) {
                            java.awt.image.BufferedImage di = new java.awt.image.BufferedImage(jd.getWidth(), jd.getHeight(), java.awt.image.BufferedImage.TYPE_INT_RGB);
                            java.awt.Graphics2D dg = di.createGraphics();
                            jd.getContentPane().paint(dg);
                            dg.dispose();
                            javax.imageio.ImageIO.write(di, "png", new java.io.File(shot.replace(".png", "-dialog" + dn++ + ".png")));
                        }
                    JDialog pal = CommandPalette.last;
                    if (pal != null && pal.isShowing()) {
                        java.awt.image.BufferedImage pi = new java.awt.image.BufferedImage(pal.getWidth(), pal.getHeight(),
                            java.awt.image.BufferedImage.TYPE_INT_RGB);
                        java.awt.Graphics2D pg = pi.createGraphics();
                        pal.getContentPane().paint(pg);
                        pg.dispose();
                        javax.imageio.ImageIO.write(pi, "png", new java.io.File(shot.replace(".png", "-palette.png")));
                    }
                    int on = 0;
                    for (Window ow : Window.getWindows()) // the start screen and settings opened from it
                        if (ow.isShowing() && (ow instanceof StartScreen || (ow instanceof SettingsWindow sw && sw != settingsWindow))) {
                            java.awt.image.BufferedImage oi = new java.awt.image.BufferedImage(ow.getWidth(), ow.getHeight(), java.awt.image.BufferedImage.TYPE_INT_RGB);
                            java.awt.Graphics2D og = oi.createGraphics();
                            ow.paint(og);
                            og.dispose();
                            javax.imageio.ImageIO.write(oi, "png", new java.io.File(shot.replace(".png", "-win" + on++ + ".png")));
                        }
                    if (settingsWindow != null && settingsWindow.isShowing()) {
                        java.awt.image.BufferedImage si = settingsWindow.snapshot();
                        if (si != null) javax.imageio.ImageIO.write(si, "png", new java.io.File(shot.replace(".png", "-settings.png")));
                    }
                    for (int i = 0; i < windows.size(); i++) {
                        java.awt.image.BufferedImage img = windows.get(i).snapshot();
                        if (img != null) javax.imageio.ImageIO.write(img, "png", new java.io.File(shot.replace(".png", "-" + i + ".png")));
                    }
                } catch (Exception ignored) {
                    // verification aid only
                }
            });
            t.setRepeats(false);
            t.start();
        }
    }

    Dock.StudioWindow newWindow(boolean isMain, Component tree) {
        Dock.StudioWindow w = new Dock.StudioWindow(isMain, isMain ? "Mosaic Studio" : "Mosaic Studio - area");
        w.root.add(tree, BorderLayout.CENTER);
        if (isMain) {
            main = w;
            JPanel frame = (JPanel) w.getContentPane();
            JPanel body = new JPanel(new BorderLayout());
            body.setBackground(BG);
            frame.remove(w.root);
            w.menuSlot.setOpaque(false);
            w.menuSlot.add(menuBar = menus(), BorderLayout.CENTER);
            JPanel tabs = new JPanel(new BorderLayout());
            tabs.setOpaque(false);
            tabs.setBorder(new EmptyBorder(2, 10, 2, 4));
            workspaceTabs.selected = model.workspace.ordinal();
            workspaceTabs.listeners.clear();
            workspaceTabs.onChange(this::switchWorkspace);
            tabs.add(workspaceTabs, BorderLayout.CENTER);
            w.menuSlot.add(tabs, BorderLayout.EAST);
            w.quickSlot.add(quick());
            tabStrip = new DocTabs(this);
            model.listen(tabStrip::repaint);
            body.add(tabStrip, BorderLayout.NORTH);
            body.add(w.root, BorderLayout.CENTER);
            body.add(statusBar(), BorderLayout.SOUTH);
            frame.add(body, BorderLayout.CENTER);
            w.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
            w.addWindowListener(new java.awt.event.WindowAdapter() {
                @Override public void windowClosing(java.awt.event.WindowEvent e) { requestClose(); }
            });
        }
        windows.add(w);
        return w;
    }

    // ================================================================== menus (inside the narrow title bar)

    private JMenuBar menus() {
        JMenuBar bar = new JMenuBar();
        bar.setOpaque(false);
        bar.setBorder(null);
        StudioKeys.Action[] file = {StudioKeys.Action.NEW_TEMPLATE, StudioKeys.Action.OPEN, StudioKeys.Action.SAVE};
        JMenu f = menu("File");
        for (StudioKeys.Action a : file) f.add(item(a.label, a));
        JMenu recent = menu("Open recent");
        recent.addMenuListener(new javax.swing.event.MenuListener() {
            @Override public void menuSelected(javax.swing.event.MenuEvent e) { fillRecent(recent); }
            @Override public void menuDeselected(javax.swing.event.MenuEvent e) { }
            @Override public void menuCanceled(javax.swing.event.MenuEvent e) { }
        });
        f.add(recent);
        f.add(item("Save as...", () -> {
            Path keep = model.doc.file;
            model.doc.file = null;
            run(StudioKeys.Action.SAVE);
            if (model.doc.file == null) model.doc.file = keep;
        }));
        f.add(item("Projects (start screen)...", () -> new StartScreen(p -> { if (p != null) openFile(p); }, false).setVisible(true)));
        f.add(item("New project...", () -> {
            Path dir = StartScreen.newProjectDialog(main, projectHome());
            if (dir != null) openFile(ProjectInfo.graphOf(dir));
        }));
        JMenu project = menu("This project");
        project.add(item("Project card (name, description, picture)...", () -> withProject(p -> StartScreen.editCard(main, p))));
        project.add(item("Use the preview frame as the project picture", () -> withProject(p -> {
            java.awt.image.BufferedImage img = PreviewPanel.lastFrame;
            if (img == null) {
                model.say("No preview frame yet: open the Preview first", WARN);
                return;
            }
            try {
                p.setPicture(img);
                model.say("The project picture is pack.png in " + p.dir, OK);
            } catch (Exception e) {
                model.say("Could not save the picture: " + e.getMessage(), BAD);
            }
        })));
        project.addSeparator();
        project.add(item("Prepare for GitHub...", () -> withProject(p -> StartScreen.prepareDialog(main, p))));
        project.add(item("Publish to GitHub...", () -> withProject(p -> StartScreen.publishDialog(main, p, null))));
        project.add(item("Update from GitHub", () -> withProject(p -> StartScreen.runLogged(main, "Update from GitHub", out -> ProjectGit.refresh(p, out),
            () -> openFile(ProjectInfo.graphOf(p.dir))))));
        f.add(project);
        f.add(item("Export generated shader...", this::exportCode));
        f.add(item("Run Minecraft with this pack", this::runGame));
        f.addSeparator();
        f.add(item(StudioKeys.Action.TOGGLE_LIVE.label, StudioKeys.Action.TOGGLE_LIVE));
        f.add(item(StudioKeys.Action.COMPILE.label, StudioKeys.Action.COMPILE));
        f.addSeparator();
        f.add(item("Settings...", StudioKeys.Action.SETTINGS));
        f.addSeparator();
        f.add(item("Exit", this::requestClose));
        bar.add(f);

        JMenu e = menu("Edit");
        for (StudioKeys.Action a : new StudioKeys.Action[] {StudioKeys.Action.UNDO, StudioKeys.Action.REDO, null, StudioKeys.Action.COPY,
            StudioKeys.Action.PASTE, StudioKeys.Action.DUPLICATE, StudioKeys.Action.DELETE, null, StudioKeys.Action.SELECT_ALL,
            StudioKeys.Action.DESELECT, StudioKeys.Action.SELECT_UPSTREAM, StudioKeys.Action.SELECT_DOWNSTREAM, null,
            StudioKeys.Action.FIND_NODE, StudioKeys.Action.COMMAND_PALETTE, StudioKeys.Action.RENAME})
            if (a == null) e.addSeparator();
            else e.add(item(a.label, a));
        bar.add(e);

        JMenu n = menu("Node");
        n.add(item("Preview selected node in game", () -> model.previewInGame(model.primary)));
        n.add(item("Stop preview in game", () -> model.previewInGame(null)));
        n.add(item("Toggle NaN / Inf highlight in game", () -> {
            model.gameNanCheck = !model.gameNanCheck;
            model.writeControl();
            model.say("NaN / Inf highlight in game " + (model.gameNanCheck ? "on (magenta)" : "off"), MUTED);
        }));
        n.addSeparator();
        for (StudioKeys.Action a : new StudioKeys.Action[] {StudioKeys.Action.ADD_NODE, null, StudioKeys.Action.CONNECT_FROM,
            StudioKeys.Action.CONNECT_INTO, StudioKeys.Action.DISCONNECT, null, StudioKeys.Action.GROUP, StudioKeys.Action.UNGROUP,
            StudioKeys.Action.ARRANGE, null, StudioKeys.Action.NEXT_PROBLEM, StudioKeys.Action.PREV_PROBLEM, StudioKeys.Action.EXPAND,
            StudioKeys.Action.MUTE})
            if (a == null) n.addSeparator();
            else n.add(item(a.label, a));
        bar.add(n);

        JMenu v = menu("View");
        for (StudioKeys.Action a : new StudioKeys.Action[] {StudioKeys.Action.FIT, StudioKeys.Action.CENTER, StudioKeys.Action.ZOOM_IN,
            StudioKeys.Action.ZOOM_OUT, StudioKeys.Action.ZOOM_RESET, StudioKeys.Action.TOGGLE_MINIMAP}) v.add(item(a.label, a));
        v.addSeparator();
        JMenu themes = menu("Theme");
        for (StudioSettings.Theme t : StudioSettings.Theme.values()) themes.add(item(t.label, () -> {
            StudioSettings s = settings.copy();
            s.theme = t;
            restyle(s);
            saveSettings();
        }));
        v.add(themes);
        JMenu accents = menu("Accent color");
        for (StudioSettings.Accent ac : StudioSettings.Accent.values()) accents.add(item(ac.label, () -> {
            StudioSettings s = settings.copy();
            s.accent = ac;
            restyle(s);
            saveSettings();
        }));
        v.add(accents);
        JMenu size = menu("Text size");
        for (int pct : new int[] {85, 100, 115, 130, 150}) size.add(item(pct + "%", () -> {
            StudioSettings s = settings.copy();
            s.fontPercent = pct;
            restyle(s);
            saveSettings();
        }));
        v.add(size);
        JMenu wires = menu("Wires");
        for (StudioSettings.WireStyle ws : StudioSettings.WireStyle.values()) wires.add(item(ws.label, () -> {
            StudioSettings s = settings.copy();
            s.wires = ws;
            restyle(s);
            saveSettings();
        }));
        v.add(wires);
        v.add(item("Toggle snap to grid", () -> {
            StudioSettings s = settings.copy();
            s.snap = !s.snap;
            restyle(s);
            saveSettings();
        }));
        bar.add(v);

        JMenu w = menu("Window");
        JMenu open = menu("Open in a new window");
        for (Panels.Type t : Panels.Type.values()) {
            JMenuItem it = item(t.label, () -> popOut(t));
            it.setIcon(Icons.panel(t, 16));
            open.add(it);
        }
        w.add(open);
        w.addSeparator();
        for (StudioKeys.Action a : new StudioKeys.Action[] {StudioKeys.Action.SPLIT_RIGHT, StudioKeys.Action.SPLIT_DOWN,
            StudioKeys.Action.MAXIMIZE_AREA, StudioKeys.Action.DETACH_AREA, StudioKeys.Action.CLOSE_AREA, StudioKeys.Action.CYCLE_EDITOR,
            StudioKeys.Action.NEXT_AREA}) w.add(item(a.label, a));
        w.addSeparator();
        w.add(item("Reset layout", () -> {
            if (settingsWindow == null || !settingsWindow.isDisplayable()) settingsWindow = new SettingsWindow(this);
            settingsWindow.resetLayout();
        }));
        w.add(item("Window style and settings...", StudioKeys.Action.SETTINGS));
        bar.add(w);

        JMenu h = menu("Help");
        h.add(item("Guide", () -> popOut(Panels.Type.GUIDE)));
        h.add(item("Config (all public numbers)", () -> popOut(Panels.Type.CONFIG)));
        h.add(item("All keys", StudioKeys.Action.HELP));
        h.add(item("Game link", () -> popOut(Panels.Type.GAME)));
        bar.add(h);
        return bar;
    }

    /** File > Open recent: the last files, newest first; missing ones are dropped. */
    private void fillRecent(JMenu recent) {
        recent.removeAll();
        settings.recentFiles.removeIf(s -> !java.nio.file.Files.exists(Path.of(s)));
        for (String s : settings.recentFiles) {
            Path p = Path.of(s);
            JMenuItem it = item(recentLabel(p), () -> openFile(p));
            it.setToolTipText(s);
            recent.add(it);
        }
        if (settings.recentFiles.isEmpty()) {
            JMenuItem none = item("(none yet)", () -> { });
            none.setEnabled(false);
            recent.add(none);
        } else {
            recent.addSeparator();
            recent.add(item("Clear the list", () -> {
                settings.recentFiles.clear();
                saveSettings();
            }));
        }
    }

    /** "mosaic.json - my pack" for files inside a pack, the file name otherwise. */
    static String recentLabel(Path p) {
        String name = p.getFileName().toString();
        Path dir = p.getParent();
        if (dir != null && dir.getFileName() != null && dir.getFileName().toString().equals("bedray") && dir.getParent() != null
            && dir.getParent().getParent() != null && dir.getParent().getParent().getFileName() != null)
            return name + "  -  " + dir.getParent().getParent().getFileName();
        return name + (dir != null && dir.getFileName() != null ? "  -  " + dir.getFileName() : "");
    }

    /** The project the open graph belongs to (its pack folder), or a message. */
    private void withProject(java.util.function.Consumer<ProjectInfo> job) {
        Path pack = ProjectInfo.packOf(model.doc.file);
        if (pack == null) {
            model.say("This graph is not in a project folder (<pack>/shaders/bedray/mosaic.json): File > New project... makes one", WARN);
            return;
        }
        job.accept(ProjectInfo.load(pack));
    }

    private Path projectHome() {
        Path g = StudioLibrary.gameDir(model);
        return g != null ? g.resolve("shaderpacks") : ProjectInfo.projectsHome();
    }

    void openFile(Path p) {
        model.open(p);
        GraphPanel gg = graph();
        if (gg != null && settings.fitOnOpen) SwingUtilities.invokeLater(gg::fit);
    }

    private JMenu menu(String title) {
        JMenu m = new JMenu(title);
        StudioWidgets.fitOnOpen(m);
        m.setFont(UI_S);
        m.setForeground(TEXT);
        m.setBorder(new EmptyBorder(2, 8, 2, 8));
        m.getPopupMenu().setBorder(BorderFactory.createLineBorder(EDGE));
        return m;
    }

    private JMenuItem item(String label, StudioKeys.Action a) {
        String keys = model.keys.humanFor(a);
        JMenuItem i = item(label, () -> run(a));
        if (!keys.isEmpty()) i.setText("<html>" + esc(label) + "&nbsp;&nbsp;&nbsp;<span style='color:#8B93A8'>" + esc(keys) + "</span></html>");
        return i;
    }

    private JMenuItem item(String label, Runnable r) {
        JMenuItem i = new JMenuItem(label);
        i.setFont(UI_S);
        i.putClientProperty(CommandPalette.LABEL, label);
        i.addActionListener(e -> r.run());
        return i;
    }

    /** The few always-visible controls: Live and the result view. */
    // ================================================================== workspaces

    final StudioWidgets.Segmented<StudioModel.Workspace> workspaceTabs = new StudioWidgets.Segmented<StudioModel.Workspace>()
        .add(StudioModel.Workspace.SHADERS, "Render graph", StudioWidgets.glyph("shader", new Color(0x5AA0E0)),
            "The frame as nodes: a renderer of your own (G-buffer, lights, GI, denoise, tone map) or Bedray render as one node, then your passes")
        .add(StudioModel.Workspace.MATERIALS, "Materials", StudioWidgets.glyph("vanilla", new Color(0xD08A4A)),
            "Materials: the surface of each block and item texture (PBR), baked into a resource pack")
        .add(StudioModel.Workspace.RENDERER, "Bedray stages", StudioWidgets.glyph("chip", new Color(0x8A9CF0)),
            "Ready-made: the inside of the Bedray render node - path tracer, ReSTIR DI / PT, cascades, fog, denoiser, exposure and their settings, applied to the game live");
    private boolean switching, keepLayout;

    /** The layout of the open workspace, as saved (or its default). */
    String currentLayout() {
        return switch (model.workspace) {
            case MATERIALS -> settings.materialLayout != null ? settings.materialLayout : Dock.defaultMaterialLayout();
            case RENDERER -> settings.rendererLayout != null ? settings.rendererLayout : Dock.defaultRendererLayout();
            default -> settings.layout != null ? settings.layout : Dock.defaultLayout();
        };
    }

    /** Remembers the arrangement of the open workspace. */
    void storeLayout() {
        if (windows.isEmpty() || keepLayout) return;
        switch (model.workspace) {
            case MATERIALS -> settings.materialLayout = Dock.saveAll(windows);
            case RENDERER -> settings.rendererLayout = Dock.saveAll(windows);
            default -> settings.layout = Dock.saveAll(windows);
        }
    }

    /** Per workspace while another one is open: its area tree, its pop-out windows, its file and undo history. */
    private final java.util.Map<StudioModel.Workspace, Component> parkedTrees = new java.util.EnumMap<>(StudioModel.Workspace.class);
    private final java.util.Map<StudioModel.Workspace, List<Dock.StudioWindow>> parkedWindows = new java.util.EnumMap<>(StudioModel.Workspace.class);
    private final java.util.Map<StudioModel.Workspace, List<List<String>>> parkedHistory = new java.util.EnumMap<>(StudioModel.Workspace.class);
    private final java.util.Map<StudioModel.Workspace, Path> parkedFile = new java.util.EnumMap<>(StudioModel.Workspace.class);

    // ================================================================== document tabs

    final List<DocTabs.Tab> tabs = new ArrayList<>();
    DocTabs.Tab active;
    DocTabs tabStrip;

    /** The first tab is the document the studio started with; tabs of the last session follow (loaded when opened). */
    /** False when the start screen opened one project: only it, not the tabs of the last session. */
    static boolean restoreSession = true;

    void initTabs() {
        active = new DocTabs.Tab(model.workspace, model.doc.file);
        tabs.add(active);
        if (!restoreSession) return;
        for (String s : settings.openTabs) {
            int bar = s.indexOf('|');
            if (bar < 0) continue;
            try {
                StudioModel.Workspace kind = StudioModel.Workspace.valueOf(s.substring(0, bar));
                Path f = Path.of(s.substring(bar + 1));
                if (Files.exists(f) && tabs.stream().noneMatch(t -> f.equals(t.file))) tabs.add(new DocTabs.Tab(kind, f));
            } catch (Exception ignored) {
                // a tab of a removed file
            }
        }
    }

    private void storeTab(DocTabs.Tab t) {
        t.file = model.doc.file;
        t.history = model.doc.historyState();
        if (t.kind == StudioModel.Workspace.MATERIALS) {
            t.srcId = model.materialSource == null ? null : model.materialSource.id();
            t.entry = model.materialEntry;
            if (t.file != null) settings.lastMaterial = t.file.toString();
        } else if (t.file != null) {
            settings.lastShader = t.file.toString();
            model.shaderFile = t.file;
        }
        GraphPanel g = model.activeGraph;
        if (g != null) t.view = new double[] {g.panX, g.panY, g.zoom};
    }

    private void loadTab(DocTabs.Tab t) {
        model.workspace = t.kind;
        if (t.kind == StudioModel.Workspace.RENDERER && t.file != null && t.file.getFileName().toString().startsWith("bedray-pack")) {
            model.openPackMap();
            t.file = model.doc.file;
        } else if (t.kind == StudioModel.Workspace.RENDERER && (t.file == null || !Files.exists(t.file))) {
            model.openRenderer();
            t.file = model.doc.file;
        } else if (t.file != null && Files.exists(t.file)) {
            if (t.kind == StudioModel.Workspace.MATERIALS) model.openMaterialFile(t.file);
            else model.open(t.file);
        } else {
            model.replace("{\"nodes\": []}", null, t.kind == StudioModel.Workspace.MATERIALS ? "New material" : "New graph");
            model.doc.file = null;
            model.doc.dirty = false;
            if (t.kind == StudioModel.Workspace.MATERIALS)
                model.say("New material: double-click a block, item or entity texture in the Library", OK);
        }
        if (t.history != null && t.history.size() == 4) model.doc.restoreHistory(t.history.get(0), t.history.get(1), t.history.get(2), t.history.get(3));
        else {
            model.doc.resetHistory();
            ProjectStore.load(model, null);
        }
        GraphPanel g = model.activeGraph;
        if (g != null && t.view != null) {
            g.panX = t.view[0];
            g.panY = t.view[1];
            g.zoom = t.view[2];
            g.markFitted();
            g.repaint();
        }
        t.used = System.nanoTime();
    }

    /** Shows a tab: parks the visible workspace when the kind changes, swaps document, history and view, cross-fades. */
    void activate(DocTabs.Tab t) {
        if (switching || t == active || main == null) return;
        switching = true;
        try {
            BufferedImage before = main.snapshot();
            if (model.doc.dirty && model.doc.file != null) model.save(false);
            if (active != null && tabs.contains(active)) storeTab(active);
            if (t.kind != model.workspace) swapAreas(t.kind);
            active = t;
            loadTab(t);
            model.selectionChanged();
            model.fire();
            workspaceTabs.select(t.kind.ordinal());
            if (tabStrip != null) tabStrip.repaint();
            main.fadeFrom(before);
            keepLayout = true;
            saveSettings();
            keepLayout = false;
        } finally {
            switching = false;
        }
    }

    /** Parks the open workspace's areas and pop-out windows and brings (or builds) the other kind's. */
    private void swapAreas(StudioModel.Workspace ws) {
        StudioModel.Workspace from = model.workspace;
        if (main.maximized != null && main.root.getComponentCount() > 0 && main.root.getComponent(0) instanceof Dock.Area a) Dock.maximize(a);
        storeLayout();
        Component tree = main.root.getComponentCount() > 0 ? main.root.getComponent(0) : null;
        if (tree != null) parkedTrees.put(from, tree);
        List<Dock.StudioWindow> extras = new ArrayList<>();
        for (Dock.StudioWindow w : windows) if (!w.main) extras.add(w);
        for (Dock.StudioWindow w : extras) w.setVisible(false);
        windows.removeAll(extras);
        parkedWindows.put(from, extras);
        model.workspace = ws;
        Component next = parkedTrees.remove(ws);
        if (next == null) {
            var specs = Dock.parseWindows(currentLayout());
            next = Dock.load(model, specs.get(0).getAsJsonObject().getAsJsonObject("tree"));
            for (int i = 1; i < specs.size(); i++) {
                var o = specs.get(i).getAsJsonObject();
                Dock.StudioWindow w = newWindow(false, Dock.load(model, o.getAsJsonObject("tree")));
                if (o.has("w")) w.setBounds(o.get("x").getAsInt(), o.get("y").getAsInt(), o.get("w").getAsInt(), o.get("h").getAsInt());
                w.setVisible(true);
            }
        } else for (Dock.StudioWindow w : parkedWindows.getOrDefault(ws, List.of())) {
            windows.add(w);
            w.setVisible(true);
        }
        parkedWindows.remove(ws);
        main.root.removeAll();
        main.root.add(next, BorderLayout.CENTER);
        main.root.revalidate();
        main.root.repaint();
        GraphPanel g = findGraph(next);
        if (g != null) model.activeGraph = g;
    }

    /** The Shaders / Materials switch: goes to the most recent tab of that kind (or opens one). */
    void switchWorkspace(StudioModel.Workspace ws) {
        if (switching || main == null) return;
        if (active != null && active.kind == ws) {
            workspaceTabs.select(ws.ordinal());
            return;
        }
        DocTabs.Tab t = tabs.stream().filter(x -> x.kind == ws).max(java.util.Comparator.comparingLong(x -> x.used)).orElse(null);
        if (t == null && ws == StudioModel.Workspace.RENDERER) {
            t = new DocTabs.Tab(ws, null);
            tabs.add(t);
        }
        if (t == null) {
            String last = ws == StudioModel.Workspace.MATERIALS ? settings.lastMaterial : settings.lastShader;
            Path f = last == null || last.isBlank() ? null : Path.of(last);
            t = new DocTabs.Tab(ws, f != null && Files.exists(f) ? f : null);
            tabs.add(t);
        }
        activate(t);
    }

    /** A new shader tab holding a whole renderer of your own (Renderer from scratch template), ready to edit. */
    void newRendererTab(boolean gi) {
        newTab(StudioModel.Workspace.SHADERS);
        String name = gi ? "Renderer from scratch (voxel GI)" : "Renderer from scratch (fast)";
        MosaicTemplates.all().stream().filter(t -> t.name().equals(name)).findFirst().ifPresent(model::loadTemplate);
    }

    void newTab(StudioModel.Workspace kind) {
        DocTabs.Tab t = new DocTabs.Tab(kind, null);
        tabs.add(t);
        activate(t);
    }

    /** Opens a texture's material in its own tab (or the tab that already has it). */
    void openMaterialTab(StudioLibrary.Source src, String entry) {
        Path file = StudioLibrary.materialPack(model).resolve(entry.substring(0, entry.length() - 4) + ".mosaic.json");
        for (DocTabs.Tab t : tabs) {
            Path f = t == active ? model.doc.file : t.file;
            if (file.equals(f)) {
                activate(t);
                return;
            }
        }
        DocTabs.Tab t = active != null && active.kind == StudioModel.Workspace.MATERIALS && model.doc.file == null && model.doc.nodes.isEmpty()
            ? active : null; // reuse an empty "New material" tab
        if (t == null) {
            t = new DocTabs.Tab(StudioModel.Workspace.MATERIALS, null);
            tabs.add(t);
            activate(t);
        }
        model.openMaterial(src, entry);
        t.file = model.doc.file;
        if (tabStrip != null) tabStrip.repaint();
    }

    /** A function of Bedray's pack as nodes, in its own tab (or the tab that has it). */
    void openFunctionTab(String id) {
        Path file = StudioModel.functionFile(id);
        for (DocTabs.Tab t : tabs) {
            Path f = t == active ? model.doc.file : t.file;
            if (file.equals(f)) {
                activate(t);
                return;
            }
        }
        DocTabs.Tab t = new DocTabs.Tab(StudioModel.Workspace.RENDERER, null);
        tabs.add(t);
        activate(t);
        if (!model.openFunctionGraph(id)) return;
        t.file = model.doc.file;
        if (tabStrip != null) tabStrip.repaint();
        Timer fit = new Timer(350, e -> {
            GraphPanel g = main == null ? null : findGraph(main.root);
            if (g != null) g.fitReadable();
        });
        fit.setRepeats(false);
        fit.start();
    }

    /** A renderer stage's inside in its own tab (or the tab that has it). */
    void openStageTab(String type) {
        Path file = StudioModel.stageFile(type);
        for (DocTabs.Tab t : tabs) {
            Path f = t == active ? model.doc.file : t.file;
            if (file.equals(f)) {
                activate(t);
                return;
            }
        }
        DocTabs.Tab t = new DocTabs.Tab(StudioModel.Workspace.RENDERER, null);
        tabs.add(t);
        activate(t);
        if (!model.openStageMap(type)) return;
        t.file = model.doc.file;
        if (tabStrip != null) tabStrip.repaint();
        Timer fit = new Timer(350, e -> {
            GraphPanel g = main == null ? null : findGraph(main.root);
            if (g != null) g.fitReadable();
        });
        fit.setRepeats(false);
        fit.start();
    }

    /** Pick any function of the pack (searchable) and open it as nodes. */
    void pickFunction(Component owner, Point at) {
        var lib = model.bedrayLibrary();
        if (lib == null) {
            model.say("Bedray's Slang sources were not found: choose the game instance with the Bedray mod (Library)", StudioUi.BAD);
            return;
        }
        java.util.List<io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicSlangLib.Function> fns = lib.functions().values().stream()
            .filter(io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicSlangLib.Function::isNode).toList();
        GraphPanel.pick(owner, at, "Open a Bedray function as nodes (" + fns.size() + ")", fns,
            f -> f.label() + "   " + f.module() + (java.nio.file.Files.exists(StudioModel.functionFile(f.id())) ? "   (edited)" : ""),
            f -> openFunctionTab(f.id()));
    }

    /** The map of Bedray's Slang pack in its own tab (rebuilt from the sources each time). */
    void openPackMapTab() {
        DocTabs.Tab t = new DocTabs.Tab(StudioModel.Workspace.RENDERER, null);
        tabs.add(t);
        activate(t);
        model.openPackMap();
        t.file = model.doc.file;
        if (tabStrip != null) tabStrip.repaint();
        Timer fit = new Timer(350, e -> { // after the swapped areas are laid out
            GraphPanel g = main == null ? null : findGraph(main.root);
            if (g != null) g.fit();
        });
        fit.setRepeats(false);
        fit.start();
    }

    /** Opens a shader graph in its own tab (or the tab that has it). */
    void openShaderTab(Path graph) {
        for (DocTabs.Tab t : tabs) {
            Path f = t == active ? model.doc.file : t.file;
            if (graph.equals(f)) {
                activate(t);
                return;
            }
        }
        DocTabs.Tab t = new DocTabs.Tab(StudioModel.Workspace.SHADERS, graph);
        tabs.add(t);
        activate(t);
    }

    void closeTab(DocTabs.Tab t) {
        if (t == active && model.doc.dirty && model.doc.file != null) model.save(false);
        int i = tabs.indexOf(t);
        tabs.remove(t);
        if (tabs.isEmpty()) tabs.add(new DocTabs.Tab(StudioModel.Workspace.SHADERS,
            settings.lastShader == null || settings.lastShader.isBlank() ? null : Path.of(settings.lastShader)));
        if (t == active) activate(tabs.get(Math.max(0, Math.min(i, tabs.size()) - 1)));
        if (tabStrip != null) tabStrip.repaint();
        saveSettings();
    }

    static GraphPanel findGraph(Component c) {
        if (c instanceof GraphPanel g) return g;
        if (c instanceof Container k) for (Component child : k.getComponents()) {
            GraphPanel g = findGraph(child);
            if (g != null) return g;
        }
        return null;
    }

    /** Theme changes rebuild the visible workspace; parked ones are rebuilt from their layout when opened. */
    private void dropParked() {
        for (Component t : parkedTrees.values()) if (t instanceof Container c) for (Dock.Area a : Dock.areas(c)) a.dispose();
        parkedTrees.clear();
        for (List<Dock.StudioWindow> ws : parkedWindows.values()) for (Dock.StudioWindow w : ws) {
            for (Dock.Area a : Dock.areas(w.root)) a.dispose();
            w.dispose();
        }
        parkedWindows.clear();
    }

    private JComponent quick() {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        p.setOpaque(false);
        liveButton = chip("", "Save every valid change so a game running this pack reloads it (" + model.keys.humanFor(StudioKeys.Action.TOGGLE_LIVE) + ")",
            () -> run(StudioKeys.Action.TOGGLE_LIVE));
        p.add(liveButton);
        p.add(chip("Game view", "The real render from the running game: before / after / compare", () -> popOut(Panels.Type.RESULT)));
        JButton run = chip("Run game", "Start Minecraft with Bedray to see this graph for real (command in Settings > Game)", this::runGame);
        Color accent = SELECT.darker(); // the main action: filled with the accent, readable white text
        run.setBackground(accent);
        run.setForeground(Color.WHITE);
        run.setFont(UI_B.deriveFont(UI_S.getSize2D()));
        run.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(accent), new EmptyBorder(0, 12, 0, 12)));
        p.add(run);
        updateLive();
        return p;
    }

    private JButton chip(String label, String tip, Runnable r) {
        JButton b = button(label, tip, r);
        b.setFont(UI_S);
        b.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(EDGE), new EmptyBorder(0, 8, 0, 8)));
        return b;
    }

    /** A Prism Launcher next to the usual install places, or null. */
    static Path findPrism() {
        String local = System.getenv("LOCALAPPDATA"), pf = System.getenv("ProgramFiles");
        for (String base : new String[] {local == null ? null : local + "/Programs/PrismLauncher", pf == null ? null : pf + "/PrismLauncher"}) {
            if (base == null) continue;
            Path p = Path.of(base, "prismlauncher.exe");
            if (Files.exists(p)) return p;
        }
        return null;
    }

    /** Starts the game command from the settings (asks for it the first time). */
    void runGame() {
        String cmd = settings.gameCommand;
        if (cmd == null || cmd.isBlank()) {
            Path prism = findPrism();
            String suggestion = prism == null ? "" : "\"" + prism + "\" --launch \"<instance name>\"";
            cmd = JOptionPane.showInputDialog(main, "<html>Command that starts Minecraft with Bedray.<br>"
                + "The instance needs the JVM argument <b>-Dbedray.customrt=&lt;pack folder&gt;</b> for this graph's pack.<br>"
                + (prism != null ? "Prism Launcher found: replace &lt;instance name&gt;." : "Prism Launcher was not found; any launcher command works.")
                + "</html>", suggestion);
            if (cmd == null || cmd.isBlank()) return;
            settings.gameCommand = cmd.trim();
            saveSettings();
        }
        try {
            boolean windows = System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win");
            ProcessBuilder pb = windows ? new ProcessBuilder("cmd", "/c", settings.gameCommand) : new ProcessBuilder("sh", "-c", settings.gameCommand);
            pb.redirectErrorStream(true);
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            pb.start();
            model.say("Starting the game: " + settings.gameCommand + "   (the Result area shows its frames once the pack is live)", OK);
        } catch (Exception ex) {
            model.say("Could not start the game: " + ex.getMessage(), BAD);
        }
    }

    private void exportCode() {
        if (model.generated == null) {
            model.say("Nothing to export: " + model.analysis.firstError(), BAD);
            return;
        }
        JFileChooser fc = new JFileChooser();
        fc.setSelectedFile(new java.io.File("mosaic.export.comp"));
        if (fc.showSaveDialog(main) != JFileChooser.APPROVE_OPTION) return;
        try {
            if (model.generated != null) Files.writeString(fc.getSelectedFile().toPath(), model.generated.source());
            model.say("Exported " + fc.getSelectedFile(), OK);
        } catch (Exception ex) {
            model.say("Export failed: " + ex.getMessage(), BAD);
        }
    }

    private void updateLive() {
        if (liveButton == null) return;
        // a status dot, not a big green block: green while every valid change goes to the game
        liveButton.setText("<html><span style='color:" + String.format("#%06X", (model.live ? new Color(0x4CC38A) : FAINT).getRGB() & 0xFFFFFF)
            + "'>●</span>&nbsp; Live</html>");
        liveButton.setBackground(BUTTON);
        liveButton.setForeground(model.live ? TEXT : MUTED);
    }

    private JComponent statusBar() {
        JPanel p = new JPanel(new BorderLayout(12, 0));
        p.setBackground(PANEL2);
        p.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, EDGE), new EmptyBorder(2, 10, 2, 10)));
        statusLeft = label(" ", MUTED);
        statusMid = label(" ", MUTED);
        statusRight = label(" ", FAINT);
        for (JLabel l : new JLabel[] {statusLeft, statusMid, statusRight}) l.setFont(UI_S);
        statusLeft.setMinimumSize(new Dimension(50, 10));
        p.add(statusLeft, BorderLayout.CENTER);
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 12, 0));
        right.setOpaque(false);
        right.add(statusMid);
        right.add(statusRight);
        p.add(right, BorderLayout.EAST);
        return p;
    }

    private void updateStatus() {
        if (statusLeft == null) return;
        MosaicGraph.Analysis a = model.analysis;
        if (!model.message.isEmpty()) {
            statusLeft.setText(model.message);
            statusLeft.setForeground(model.messageColor);
            if (model.messageColor == OK || model.messageColor == BAD) StudioWidgets.toast(main, model.message, model.messageColor);
            model.message = "";
        } else if (model.doc.stage != null) {
            var st = io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicRenderer.stage(model.doc.stage);
            statusLeft.setText((st == null ? model.doc.stage : st.label()) + " inside   " + model.doc.nodes.stream()
                .filter(n -> n.type.startsWith(io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicSlangGraph.FN)).count()
                + " functions, " + model.doc.nodes.stream().filter(n -> n.type.equals(io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicSlangGraph.SETTING)).count()
                + " public parameters" + (model.stageEdited(model.doc.stage) ? "   -   your version" : "   -   Bedray's own"));
            statusLeft.setForeground(OK);
        } else if (model.functionDoc()) {
            long blocks = model.doc.nodes.stream().filter(n -> n.type.equals(io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicSlangGraph.CODE)).count();
            String head = "Bedray function " + model.doc.bedrayFunction + "   " + model.doc.nodes.size() + " nodes"
                + (blocks > 0 ? ", " + blocks + " Slang block(s)" : "") + (model.doc.dirty ? "   - unsaved" : "");
            if (!a.ok()) {
                statusLeft.setText(head + "   -   " + a.errors().size() + " error(s): " + a.firstError());
                statusLeft.setForeground(BAD);
            } else {
                statusLeft.setText(head + (model.functionStatus.isEmpty() ? "" : "   -   " + model.functionStatus));
                statusLeft.setForeground(Boolean.FALSE.equals(model.functionOk) ? BAD : OK);
            }
        } else if (a.ok() && model.packDoc()) {
            long passes = model.packModules.values().stream().filter(m -> m.entry()).count();
            long covered = model.packModules.keySet().stream().filter(p -> io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicPack.stageOf(p) != null).count();
            statusLeft.setText("Bedray pack map   " + model.packModules.size() + " modules, " + passes + " passes   -   " + covered
                + " controlled by Renderer stages, " + (model.packModules.size() - covered) + " not nodes yet");
            statusLeft.setForeground(OK);
        } else if (a.ok() && model.rendererDoc()) {
            statusLeft.setText("Bedray renderer   " + model.doc.nodes.size() + " stages   -   " + (model.rendererSettings == null ? 0 : model.rendererSettings.size())
                + " settings" + (model.doc.dirty ? "   - unsaved" : ""));
            statusLeft.setForeground(OK);
        } else if (a.ok() && model.generated == null && model.materialDoc()) {
            var b = model.baked;
            statusLeft.setText("Material   " + model.doc.nodes.size() + " nodes" + (b == null ? "   - " + model.bakeError
                : "   baked " + b.w() + "x" + b.h() + (b.albedo() != null ? ", new colours" : "") + (b.normal() != null ? ", normal + height" : "")
                + (b.warnings().isEmpty() ? "" : "   - " + b.warnings().size() + " warning(s)")) + (model.doc.dirty ? "   - unsaved" : ""));
            statusLeft.setForeground(b == null || !b.warnings().isEmpty() ? WARN : OK);
        } else if (a.ok() && model.generated != null) {
            var s = model.generated.stats();
            String full = "Valid   " + model.doc.nodes.size() + " nodes -> " + s.emitted() + " in the shader   (" + s.unused() + " unused, "
                + s.folded() + " folded, " + s.merged() + " merged)   per pixel: " + s.cost().rays() + " rays, " + s.cost().samples() + " samples";
            statusLeft.setText((settings.detailedStatus ? full : "Valid  ·  " + model.doc.nodes.size() + " nodes") + (model.doc.dirty ? "  ·  unsaved" : ""));
            statusLeft.setForeground(OK);
        } else {
            statusLeft.setText(a.errors().size() + " error(s): " + a.firstError());
            statusLeft.setForeground(BAD);
        }
        statusLeft.setToolTipText(model.generated != null ? "<html>" + model.doc.nodes.size() + " nodes -> " + model.generated.stats().emitted()
            + " in the shader (" + model.generated.stats().unused() + " unused, " + model.generated.stats().folded() + " folded, "
            + model.generated.stats().merged() + " merged)<br>per pixel: " + model.generated.stats().cost().rays() + " rays, "
            + model.generated.stats().cost().samples() + " samples</html>" : statusLeft.getText());
        if (model.compiling) {
            statusMid.setText("compiling...");
            statusMid.setForeground(MUTED);
        } else if (model.workspace == StudioModel.Workspace.RENDERER) {
            String s = model.rendererStatus + (model.rendererGameStatus.isEmpty() ? "" : "   |   game: " + model.rendererGameStatus);
            statusMid.setText(s);
            statusMid.setForeground(s.contains("could not") || s.contains("choose") ? WARN : OK);
        } else if (model.workspace == StudioModel.Workspace.MATERIALS || model.materialDoc()) {
            statusMid.setText(model.materialStatus);
            statusMid.setForeground(model.materialStatus.startsWith("Could not") ? BAD : OK);
        } else if (model.compile != null && model.generated != null) {
            statusMid.setText(model.compile.ok() ? (settings.detailedStatus ? "compiles: " + model.compile.spirvBytes() + " B SPIR-V, " + model.compile.micros() / 1000 + " ms"
                : "Compiled  ·  " + Math.max(1, model.compile.spirvBytes() / 1024) + " KB") : "Compile error: " + model.compile.errors().get(0).where());
            statusMid.setToolTipText(model.compile.ok() ? model.compile.spirvBytes() + " B SPIR-V, compiled in " + model.compile.micros() / 1000 + " ms" : null);
            statusMid.setForeground(model.compile.ok() ? OK : BAD);
        }
        boolean gameIdle = model.gameStatus == null || model.gameStatus.startsWith("save the graph");
        statusRight.setText(settings.detailedStatus ? (model.slangStatus.isEmpty() ? "" : model.slangStatus + "    ") + "game: " + model.gameStatus + "    F1 keys"
            : gameIdle ? "" : "Game: " + model.gameStatus);
        statusRight.setToolTipText("<html>Game: " + model.gameStatus + (model.slangStatus.isEmpty() ? "" : "<br>" + model.slangStatus) + "<br>F1: keys</html>");
        String t = (model.doc.dirty ? "• " : "") + (model.doc.file == null ? "untitled" : packName(model.doc.file) + "  /  " + model.doc.file.getFileName());
        if (main != null && !main.titleLabel.getText().equals(t)) {
            main.titleLabel.setText(t);
            main.titleLabel.setToolTipText(model.doc.file == null ? "Not saved yet" : model.doc.file.toString());
        }
        updateLive();
    }

    // ================================================================== keyboard

    /** Keys that still work while typing in a text field. */
    private static final Set<StudioKeys.Action> WHILE_TYPING = EnumSet.of(StudioKeys.Action.SAVE, StudioKeys.Action.UNDO,
        StudioKeys.Action.REDO, StudioKeys.Action.NEXT_AREA,
        StudioKeys.Action.PREV_AREA, StudioKeys.Action.MAXIMIZE_AREA, StudioKeys.Action.HELP, StudioKeys.Action.SETTINGS,
        StudioKeys.Action.COMPILE, StudioKeys.Action.TOGGLE_LIVE);
    /** Keys a focused list, box or result view uses for itself (arrows, Enter, Tab, Space). */
    private static final Set<StudioKeys.Action> LIST_KEYS = EnumSet.of(StudioKeys.Action.MOVE_UP, StudioKeys.Action.MOVE_DOWN,
        StudioKeys.Action.MOVE_LEFT, StudioKeys.Action.MOVE_RIGHT, StudioKeys.Action.EDIT_PROPERTIES, StudioKeys.Action.NEXT_NODE,
        StudioKeys.Action.PREV_NODE, StudioKeys.Action.ADD_NODE);

    private boolean dispatch(KeyEvent e) {
        if (e.getID() != KeyEvent.KEY_PRESSED || capturingKeys) return false;
        // inside the game, Esc goes back to the game like any game screen (not while a menu, popup or text edit is open)
        if (embedded() && e.getKeyCode() == KeyEvent.VK_ESCAPE && e.getModifiersEx() == 0
            && MenuSelectionManager.defaultManager().getSelectedPath().length == 0
            && !(e.getComponent() instanceof JTextComponent tc && tc.isEditable())
            && SwingUtilities.getWindowAncestor(e.getComponent()) == main) {
            StudioEmbed.hide();
            return true;
        }
        Window w = e.getComponent() instanceof Window win ? win : SwingUtilities.getWindowAncestor(e.getComponent());
        if (!(w instanceof Dock.StudioWindow sw) || !windows.contains(sw)) return false;
        StudioKeys.Action a = model.keys.match(layoutIndependent(e));
        if (a == null) return false;
        Component f = e.getComponent();
        boolean typing = f instanceof JTextComponent tc && tc.isEditable();
        if (typing && !WHILE_TYPING.contains(a)) return false;
        Dock.Area area = Dock.areaOf(f);
        boolean ownsKeys = f instanceof JList || f instanceof JComboBox || f instanceof JSpinner || f instanceof JTable
            || f instanceof AbstractButton || (area != null && area.type == Panels.Type.RESULT && !(f instanceof GraphPanel));
        if (ownsKeys && LIST_KEYS.contains(a)) return false;
        run(a);
        return true;
    }

    /** Russian (JCUKEN) letters on the same physical keys as QWERTY, so shortcuts work in either layout. */
    private static final String CYRILLIC = "йцукенгшщзхъфывапролджэячсмитьбю", LATIN = "qwertyuiop[]asdfghjkl;'zxcvbnm,.";

    /** The pressed key as a layout-independent stroke: a Cyrillic letter maps back to its QWERTY key. */
    static KeyStroke layoutIndependent(KeyEvent e) {
        int code = e.getKeyCode();
        int ext = e.getExtendedKeyCode();
        if (code == KeyEvent.VK_UNDEFINED || ext >= 0x01000000) {
            char c = Character.toLowerCase((char) (ext >= 0x01000000 ? ext - 0x01000000 : e.getKeyChar()));
            int i = CYRILLIC.indexOf(c);
            if (i >= 0) code = KeyEvent.getExtendedKeyCodeForChar(LATIN.charAt(i));
        }
        return KeyStroke.getKeyStroke(code, e.getModifiersEx());
    }

    GraphPanel graph() {
        if (model.activeGraph != null && model.activeGraph.isShowing()) return model.activeGraph;
        for (Dock.StudioWindow w : windows)
            for (Dock.Area a : Dock.areas(w.root)) if (a.panel.component() instanceof GraphPanel g && g.isShowing()) return model.activeGraph = g;
        return null;
    }

    Dock.Area currentArea() {
        if (lastArea != null && lastArea.isShowing()) return lastArea;
        GraphPanel g = graph();
        return g == null ? null : Dock.areaOf(g);
    }

    List<Dock.Area> allAreas() {
        List<Dock.Area> out = new ArrayList<>();
        for (Dock.StudioWindow w : windows) out.addAll(Dock.areas(w.root));
        return out;
    }

    void focusArea(int dir) {
        List<Dock.Area> all = allAreas();
        if (all.isEmpty()) return;
        int i = all.indexOf(currentArea());
        Dock.Area next = all.get(Math.floorMod(i + dir, all.size()));
        lastArea = next;
        Window win = SwingUtilities.getWindowAncestor(next);
        if (win != null) win.toFront();
        next.panel.focus();
        next.setBorder(BorderFactory.createLineBorder(SELECT));
        Timer t = new Timer(600, ev -> next.setBorder(BorderFactory.createLineBorder(EDGE)));
        t.setRepeats(false);
        t.start();
    }

    void run(StudioKeys.Action a) {
        GraphPanel g = graph();
        java.util.function.ToIntFunction<MosaicDocument.ENode> h = g == null ? n -> 60 : g::height;
        Dock.Area area = currentArea();
        switch (a) {
            case ADD_NODE -> { if (g != null) g.quickAdd(g.getMousePosition()); }
            case DELETE -> model.deleteSelection();
            case DUPLICATE -> model.duplicateSelection();
            case COPY -> model.copySelection();
            case PASTE -> model.paste(g == null ? new Point(0, 0) : g.getMousePosition() != null ? g.mouseWorld() : g.viewCenter());
            case RENAME -> { if (g != null) g.renameDialog(); }
            case GROUP -> model.group(h);
            case UNGROUP -> model.ungroup();
            case MUTE -> model.toggleMute();
            case EXPAND -> model.toggleExpand();
            case ARRANGE -> {
                model.arrange(h);
                if (g != null && model.selection.size() < 2) g.fit();
            }
            case CONNECT_FROM -> { if (g != null) g.connectFrom(); }
            case CONNECT_INTO -> { if (g != null) g.connectInto(); }
            case DISCONNECT -> model.disconnectSelection();
            case SELECT_ALL -> model.selectAll();
            case DESELECT -> model.select(null);
            case NEXT_NODE -> { if (g != null) g.cycle(1); }
            case PREV_NODE -> { if (g != null) g.cycle(-1); }
            case NAV_LEFT -> { if (g != null) g.navigate(-1, 0, false); }
            case NAV_RIGHT -> { if (g != null) g.navigate(1, 0, false); }
            case NAV_UP -> { if (g != null) g.navigate(0, -1, false); }
            case NAV_DOWN -> { if (g != null) g.navigate(0, 1, false); }
            case EXTEND_LEFT -> { if (g != null) g.navigate(-1, 0, true); }
            case EXTEND_RIGHT -> { if (g != null) g.navigate(1, 0, true); }
            case MOVE_LEFT -> { if (g != null) g.moveSelection(-1, 0, false); }
            case MOVE_RIGHT -> { if (g != null) g.moveSelection(1, 0, false); }
            case MOVE_UP -> { if (g != null) g.moveSelection(0, -1, false); }
            case MOVE_DOWN -> { if (g != null) g.moveSelection(0, 1, false); }
            case MOVE_LEFT_FAR -> { if (g != null) g.moveSelection(-1, 0, true); }
            case MOVE_RIGHT_FAR -> { if (g != null) g.moveSelection(1, 0, true); }
            case MOVE_UP_FAR -> { if (g != null) g.moveSelection(0, -1, true); }
            case MOVE_DOWN_FAR -> { if (g != null) g.moveSelection(0, 1, true); }
            case EDIT_PROPERTIES -> allAreas().stream().filter(x -> x.type == Panels.Type.INSPECTOR).findFirst().ifPresentOrElse(
                x -> x.panel.focus(), () -> popOut(Panels.Type.INSPECTOR));
            case FIT -> { if (g != null) g.fit(); }
            case CENTER -> { if (g != null) g.center(model.primary); }
            case ZOOM_IN -> { if (g != null) g.zoomBy(1.2); }
            case ZOOM_OUT -> { if (g != null) g.zoomBy(1 / 1.2); }
            case ZOOM_RESET -> { if (g != null) g.zoomBy(1 / g.zoom); }
            case PAN_LEFT -> { if (g != null) g.pan(60, 0); }
            case PAN_RIGHT -> { if (g != null) g.pan(-60, 0); }
            case PAN_UP -> { if (g != null) g.pan(0, 60); }
            case PAN_DOWN -> { if (g != null) g.pan(0, -60); }
            case NEXT_AREA -> focusArea(1);
            case PREV_AREA -> focusArea(-1);
            case MAXIMIZE_AREA -> { if (area != null) Dock.maximize(area); }
            case SPLIT_RIGHT -> { if (area != null) Dock.split(area, true); }
            case SPLIT_DOWN -> { if (area != null) Dock.split(area, false); }
            case CLOSE_AREA -> { if (area != null) Dock.close(area); }
            case DETACH_AREA -> { if (area != null) Dock.detach(area); }
            case CYCLE_EDITOR -> {
                if (area != null) {
                    Panels.Type[] all = Panels.Type.values();
                    area.show(all[(area.type.ordinal() + 1) % all.length]);
                    area.panel.focus();
                }
            }
            case NEW_TEMPLATE -> {
                Component owner = g != null ? g : main.root;
                GraphPanel.pick(owner, new Point(40, 40), "New from template (replaces the graph; Undo brings it back)", MosaicTemplates.all(),
                    t -> t.name() + "   " + t.category() + "  -  " + t.description(), t -> {
                        model.loadTemplate(t);
                        GraphPanel gg = graph();
                        if (gg != null) SwingUtilities.invokeLater(gg::fit);
                    });
            }
            case OPEN -> {
                JFileChooser fc = new JFileChooser(model.doc.file == null ? null : model.doc.file.getParent().toFile());
                fc.setFileSelectionMode(JFileChooser.FILES_AND_DIRECTORIES);
                if (fc.showOpenDialog(main) == JFileChooser.APPROVE_OPTION) {
                    model.open(fc.getSelectedFile().toPath());
                    GraphPanel gg = graph();
                    if (gg != null) SwingUtilities.invokeLater(gg::fit);
                }
            }
            case SAVE -> {
                if (model.doc.file == null) {
                    JFileChooser fc = new JFileChooser();
                    fc.setDialogTitle("Save mosaic.json (inside <pack>/shaders/bedray/ to use it in the game)");
                    fc.setSelectedFile(new java.io.File("mosaic.json"));
                    if (fc.showSaveDialog(main) != JFileChooser.APPROVE_OPTION) return;
                    model.doc.file = fc.getSelectedFile().toPath();
                }
                model.save(true);
            }
            case UNDO -> {
                java.util.List<String> steps = model.doc.undoSteps();
                if (steps.isEmpty()) model.say("Nothing to undo", MUTED);
                else {
                    String name = steps.get(steps.size() - 1);
                    model.undo();
                    model.say("Undone: " + name + "   (" + model.keys.humanFor(StudioKeys.Action.REDO) + " redoes)", MUTED);
                }
            }
            case REDO -> {
                java.util.List<String> steps = model.doc.redoSteps();
                if (steps.isEmpty()) model.say("Nothing to redo", MUTED);
                else {
                    model.redo();
                    model.say("Redone: " + steps.get(0), MUTED);
                }
            }
            case TOGGLE_LIVE -> {
                model.live = !model.live;
                model.say(model.live ? "Live on: valid changes are saved and the game reloads them" : "Live off: Ctrl+S saves", MUTED);
            }
            case COMPILE -> model.compileNow();
            case SETTINGS -> {
                if (settingsWindow == null || !settingsWindow.isDisplayable()) settingsWindow = new SettingsWindow(this);
                settingsWindow.setVisible(true);
                settingsWindow.toFront();
            }
            case HELP -> popOut(Panels.Type.KEYS);
            case COMMAND_PALETTE -> CommandPalette.open(this, false);
            case FIND_NODE -> CommandPalette.open(this, true);
            case SELECT_UPSTREAM -> model.selectLinked(true);
            case SELECT_DOWNSTREAM -> model.selectLinked(false);
            case NEXT_PROBLEM -> goToProblem(1);
            case PREV_PROBLEM -> goToProblem(-1);
            case TOGGLE_MINIMAP -> {
                settings.minimap = !settings.minimap;
                saveSettings();
                if (g != null) g.repaint();
                model.say("Minimap " + (settings.minimap ? "on" : "off"), MUTED);
            }
        }
    }

    private int problemAt = -1;

    /** Walks the problems that sit on a node: selects it, centres the view on it and shows the message. */
    void goToProblem(int dir) {
        java.util.List<MosaicGraph.Problem> ps = model.problems().stream()
            .filter(p -> p.node() != null && model.doc.byId(p.node()) != null && p.severity() != MosaicGraph.Severity.HINT).toList();
        if (ps.isEmpty()) {
            model.say("No errors or warnings on nodes", OK);
            return;
        }
        problemAt = Math.floorMod(problemAt + dir, ps.size());
        MosaicGraph.Problem p = ps.get(problemAt);
        MosaicDocument.ENode n = model.doc.byId(p.node());
        model.select(n);
        GraphPanel g = graph();
        if (g != null) g.center(n);
        model.say((problemAt + 1) + " / " + ps.size() + "  " + p.message(), p.severity() == MosaicGraph.Severity.ERROR ? BAD : WARN);
    }

    /** The settings window's view of the editor. */
    SettingsWindow.Host settingsHost() {
        return new SettingsWindow.Host() {
            @Override public StudioSettings settings() { return settings; }
            @Override public void restyle(StudioSettings next) { StudioApp.this.restyle(next); }
            @Override public void saveSettings() { StudioApp.this.saveSettings(); }
            @Override public Window owner() { return main; }
            @Override public StudioKeys keys() { return model.keys; }
            @Override public void capturingKeys(boolean on) { capturingKeys = on; }
            @Override public void say(String text, Color c) { model.say(text, c); }
            @Override public void runGame() { StudioApp.this.runGame(); }
            @Override public void resetLayout() {
                for (Dock.StudioWindow w : new java.util.ArrayList<>(windows)) if (!w.main) {
                    windows.remove(w);
                    w.dispose();
                }
                settings.layout = Dock.defaultLayout();
                windows.removeIf(w -> !w.main);
                main.root.removeAll();
                main.root.add(Dock.load(model, Dock.parseWindows(Dock.defaultLayout()).get(0).getAsJsonObject().getAsJsonObject("tree")),
                    BorderLayout.CENTER);
                main.root.revalidate();
                main.root.repaint();
                saveSettings();
            }
        };
    }

    /** The menu bar of the main window (the command palette lists every item of it). */
    JMenuBar menuBar;

    void popOut(Panels.Type type) {
        for (Dock.StudioWindow w : windows)
            for (Dock.Area a : Dock.areas(w.root))
                if (a.type == type && !w.main) {
                    w.toFront();
                    a.panel.focus();
                    return;
                }
        Dock.StudioWindow w = newWindow(false, new Dock.Area(model, type));
        w.setSize(type == Panels.Type.RESULT ? 900 : 560, type == Panels.Type.RESULT ? 600 : 640);
        w.setLocationRelativeTo(main);
        w.setVisible(true);
    }

    // ================================================================== restyle and exit

    /** Applies new settings: every window is rebuilt with the new look and cross-fades from its old picture. */
    void restyle(StudioSettings next) {
        storeLayout();
        dropParked();
        next.layout = settings.layout;
        next.materialLayout = settings.materialLayout;
        next.keys = model.keys.toText();
        next.hiddenLibraries = settings.hiddenLibraries;
        next.collapsed = settings.collapsed;
        next.recentFiles = settings.recentFiles;      // kept up to date outside the settings window
        next.paletteRecent = settings.paletteRecent;
        next.previewViews = settings.previewViews;
        next.previewWorld = settings.previewWorld;
        next.previewLook = settings.previewLook;
        next.layoutVersion = settings.layoutVersion;
        next.previewWidth = settings.previewWidth;    // the preview's size list changes these
        next.previewHeight = settings.previewHeight;
        settings = next;
        List<BufferedImage> pictures = new ArrayList<>();
        List<Rectangle> bounds = new ArrayList<>();
        List<Boolean> maxed = new ArrayList<>();
        List<Dock.StudioWindow> old = new ArrayList<>(windows);
        for (Dock.StudioWindow w : old) {
            pictures.add(w.snapshot());
            bounds.add(w.getBounds());
            maxed.add((w.getExtendedState() & Frame.MAXIMIZED_BOTH) != 0);
        }
        StudioUi.apply(settings);
        model.keys = new StudioKeys(settings.keys);
        windows.clear();
        main = null;
        var specs = Dock.parseWindows(currentLayout());
        for (int i = 0; i < specs.size() && i < old.size(); i++) {
            var o = specs.get(i).getAsJsonObject();
            Dock.StudioWindow w = newWindow(o.get("main").getAsBoolean(), Dock.load(model, o.getAsJsonObject("tree")));
            w.setBounds(bounds.get(i));
            if (maxed.get(i)) {
                w.setMaximizedBounds(GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds());
                w.setExtendedState(Frame.MAXIMIZED_BOTH);
            }
            w.fadeFrom(pictures.get(i));
            w.setVisible(true);
        }
        for (Dock.StudioWindow w : old) {
            for (Dock.Area a : Dock.areas(w.root)) a.dispose();
            w.dispose();
        }
        if (settingsWindow != null && settingsWindow.isDisplayable()) {
            settingsWindow.restyle();
            settingsWindow.toFront();
        }
        updateStatus();
    }

    /** Settings changed by the mosaic command (an agent changing the design): apply them like the settings window does. */
    private void applyExternalSettings() {
        try {
            Path f = StudioSettings.file();
            if (!Files.exists(f)) return;
            long m = Files.getLastModifiedTime(f).toMillis();
            if (m == StudioSettings.ownWrite) return;
            StudioSettings.ownWrite = m;
            StudioSettings next = StudioSettings.load();
            restyle(next);
            model.say("Settings changed outside the studio (agent): applied", MUTED);
        } catch (Exception ignored) {
            // being written
        }
    }

    void saveSettings() {
        storeLayout();
        settings.openTabs = new ArrayList<>();
        for (DocTabs.Tab t : tabs) {
            Path f = t == active ? model.doc.file : t.file;
            if (f != null) settings.openTabs.add(t.kind + "|" + f);
        }
        settings.keys = model.keys.toText();
        settings.save();
    }

    void requestClose() {
        if (embedded()) { // inside the game: closing goes back to the game, Studio stays ready
            StudioEmbed.hide();
            return;
        }
        if (model.doc.dirty && model.doc.file != null) {
            int r = JOptionPane.showConfirmDialog(main, "Save changes to " + model.doc.file.getFileName() + "?", "Mosaic Studio",
                JOptionPane.YES_NO_CANCEL_OPTION);
            if (r == JOptionPane.CANCEL_OPTION || r == JOptionPane.CLOSED_OPTION) return;
            if (r == JOptionPane.YES_OPTION) model.save(false);
        }
        quitNow();
    }

    /** Started by the game to live inside its window (StudioEmbed; checked here so JNA loads only then). */
    static boolean embedded() {
        return Long.getLong("mosaic.embed.hwnd", 0L) != 0L
            && System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).startsWith("windows");
    }

    /** Saves settings and the project (and an open graph with Live save) and exits without asking. */
    void quitNow() {
        if (model.doc.dirty && model.doc.file != null) model.save(false);
        saveSettings();
        ProjectStore.save(model, graph());
        model.dispose();
        for (Dock.StudioWindow w : new ArrayList<>(windows)) w.dispose();
        System.exit(0);
    }

    // ================================================================== entry

    /** The shader pack folder a mosaic.json belongs to (<pack>/shaders/bedray/mosaic.json), else its folder. */
    static String packName(Path file) {
        Path p = file.toAbsolutePath().getParent();
        if (p != null && p.getFileName() != null && p.getFileName().toString().equals("bedray") && p.getParent() != null
            && p.getParent().getFileName().toString().equals("shaders") && p.getParent().getParent() != null)
            return p.getParent().getParent().getFileName().toString();
        return p == null || p.getFileName() == null ? "" : p.getFileName().toString();
    }

    static void launch(String[] args) throws Exception {
        System.setProperty("awt.useSystemAAFontSettings", "on");
        UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName());
        StudioSettings s = StudioSettings.load();
        StudioUi.apply(s);
        if (args.length == 0 && s.showStartScreen && !embedded() && System.getProperty("mosaic.selfshot") == null) {
            SwingUtilities.invokeLater(() -> new StartScreen(graph -> {
                try {
                    restoreSession = StartScreen.continueSession; // a chosen project opens alone; Continue brings the last session back
                    if (StartScreen.continueSession && graph == null && !s.openTabs.isEmpty()) {
                        String first = s.openTabs.get(0);
                        graph = Path.of(first.substring(first.indexOf('|') + 1));
                    }
                    start(graph == null ? new String[0] : new String[] {graph.toString()}, s);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }).setVisible(true));
            return;
        }
        start(args, s);
    }

    /** Opens the editor on a graph (a file, a pack folder) or, without one, on an untitled graph by parts. */
    private static void start(String[] args, StudioSettings s) throws Exception {
        MosaicDocument doc;
        if (args.length > 0) {
            Path p = Path.of(args[0]);
            if (Files.isDirectory(p)) p = p.resolve("shaders").resolve("bedray").resolve("mosaic.json");
            doc = Files.exists(p) ? MosaicDocument.load(p) : new MosaicDocument();
            doc.file = p;
        } else {
            Path tmp = Files.createTempFile("mosaic-start", ".json");
            Files.writeString(tmp, MosaicTemplates.all().stream().filter(t -> t.name().equals("Minecraft by parts")).findFirst().orElseThrow().json());
            doc = MosaicDocument.load(tmp);
            doc.file = null;
            doc.dirty = false;
        }
        MosaicDocument start = doc;
        SwingUtilities.invokeLater(() -> {
            StudioApp app = new StudioApp(start, s);
            app.open();
            if (embedded()) StudioEmbed.attach(app);
        });
    }
}
