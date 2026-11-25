package io.github.andrexarlay.vulkancompat.shaderhost.mosaic.studio;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef;

import javax.swing.MenuSelectionManager;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.Frame;
import java.awt.Rectangle;
import java.awt.Window;
import java.awt.geom.AffineTransform;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Studio inside Minecraft: started by the game with {@code -Dmosaic.embed.hwnd=<game window>}, the main Studio window
 * becomes an owned window of the game's window (Win32 owner: no taskbar button, minimised and restored with the game,
 * always above it) and is kept exactly over the game's client area, so it reads as a screen of the game while every
 * Studio feature - menus, dialogs, text input - works as in the desktop app. The game sends {@code show} / {@code hide}
 * on stdin; Esc or closing the window hides Studio and answers {@code hidden} on stdout so the game closes its screen.
 * End of stdin means the game is gone: Studio saves and quits. Windows only; elsewhere Studio stays a normal window.
 */
final class StudioEmbed {

    static final long HOST = Long.getLong("mosaic.embed.hwnd", 0L);
    private static StudioApp app;
    private static boolean shown = true;
    private static Rectangle last;

    private StudioEmbed() {}

    /** user32 calls jna-platform's User32 does not map. */
    interface Extra extends com.sun.jna.win32.StdCallLibrary {
        Extra INSTANCE = Native.load("user32", Extra.class, com.sun.jna.win32.W32APIOptions.DEFAULT_OPTIONS);

        boolean ClientToScreen(WinDef.HWND hwnd, WinDef.POINT point);
    }

    static boolean active() {
        return HOST != 0L && System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).startsWith("windows");
    }

    /** Called once on the EDT after the windows opened. */
    static void attach(StudioApp a) {
        app = a;
        Window main = a.main;
        if (main instanceof Frame f) f.setExtendedState(Frame.NORMAL);
        own(main);
        new Timer(100, e -> follow()).start();
        Thread in = new Thread(StudioEmbed::listen, "mosaic-embed-stdin");
        in.setDaemon(true);
        in.start();
        follow();
        main.toFront();
        main.requestFocus();
    }

    /** Win32 owner = the game window (GWLP_HWNDPARENT); re-shown so the taskbar forgets the button. */
    private static void own(Window w) {
        try {
            boolean visible = w.isVisible();
            if (visible) w.setVisible(false);
            if (!w.isDisplayable()) w.addNotify();
            WinDef.HWND h = new WinDef.HWND(Native.getComponentPointer(w));
            User32.INSTANCE.SetWindowLongPtr(h, -8, new Pointer(HOST));
            if (visible) w.setVisible(true);
        } catch (RuntimeException | LinkageError e) {
            System.err.println("[mosaic-embed] could not attach to the game window: " + e);
        }
    }

    /** Keeps the main window over the game's client area (Win32 pixels to Java user space). */
    private static void follow() {
        if (!shown || app == null || app.main == null) return;
        try {
            WinDef.HWND host = new WinDef.HWND(new Pointer(HOST));
            if (!User32.INSTANCE.IsWindow(host)) {
                quit();
                return;
            }
            WinDef.RECT r = new WinDef.RECT();
            User32.INSTANCE.GetClientRect(host, r);
            WinDef.POINT p = new WinDef.POINT(0, 0);
            Extra.INSTANCE.ClientToScreen(host, p);
            AffineTransform t = app.main.getGraphicsConfiguration().getDefaultTransform();
            double sx = t.getScaleX(), sy = t.getScaleY();
            Rectangle want = new Rectangle((int) Math.round(p.x / sx), (int) Math.round(p.y / sy),
                (int) Math.round((r.right - r.left) / sx), (int) Math.round((r.bottom - r.top) / sy));
            if (want.width < 50 || want.height < 50) return; // minimised game: Windows hides owned windows itself
            if (!want.equals(app.main.getBounds()) || !want.equals(last)) {
                if (app.main.getExtendedState() != Frame.NORMAL) app.main.setExtendedState(Frame.NORMAL);
                app.main.setBounds(want);
                last = want;
            }
        } catch (RuntimeException | LinkageError e) {
            // the game window is being recreated (fullscreen switch): try again on the next tick
        }
    }

    /** Back to the game: every Studio window hides, the game closes its screen. */
    static void hide() {
        if (!shown || app == null) return;
        shown = false;
        MenuSelectionManager.defaultManager().clearSelectedPath();
        for (Window w : Window.getWindows()) if (w.isVisible()) w.setVisible(false);
        System.out.println("hidden");
        System.out.flush();
        try {
            User32.INSTANCE.SetForegroundWindow(new WinDef.HWND(new Pointer(HOST)));
        } catch (RuntimeException | LinkageError ignored) {
            // the game takes focus with its next click
        }
    }

    static void show() {
        if (app == null) return;
        shown = true;
        last = null;
        app.main.setVisible(true);
        follow();
        app.main.toFront();
        app.main.requestFocus();
    }

    private static void listen() {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            for (String line; (line = r.readLine()) != null; ) {
                switch (line.trim()) {
                    case "show" -> SwingUtilities.invokeLater(StudioEmbed::show);
                    case "hide" -> SwingUtilities.invokeLater(StudioEmbed::hide);
                    default -> { }
                }
            }
        } catch (Exception ignored) {
            // stdin closed
        }
        SwingUtilities.invokeLater(StudioEmbed::quit);
    }

    /** The game is gone: save like a normal close, without asking. */
    private static void quit() {
        if (app != null) app.quitNow();
        else System.exit(0);
    }
}
