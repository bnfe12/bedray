package io.github.andrexarlay.vulkancompat.shaderhost.customrt;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.Writer;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Mosaic Studio inside the game: the very same app as the desktop Studio, started from Bedray's jar with the game's
 * Java and libraries (gson, LWJGL with shaderc, JNA). On Windows it gets the game window's handle and lives over the
 * game's client area as an owned window (StudioEmbed), so it is a screen of the game; elsewhere it is a normal window.
 * One Studio process stays alive for the session: J shows it again instantly, Esc hides it.
 */
public final class StudioLauncher {
    private static final Logger LOGGER = LoggerFactory.getLogger("VulkanRtx/MosaicStudio");
    public static final String MAIN = "io.github.andrexarlay.vulkancompat.shaderhost.mosaic.studio.MosaicStudio";
    private static Process studio;
    private static Writer control;
    private static volatile Runnable onHidden = () -> { };

    private StudioLauncher() {}

    public enum Result { STARTED, SHOWN, FAILED }

    public static synchronized boolean running() {
        return studio != null && studio.isAlive();
    }

    /** What the game does when Studio went back to the game (Esc / close inside Studio). */
    public static void onHidden(Runnable r) { onHidden = r; }

    /**
     * Shows Studio over the game: starts it on {@code mosaicJson} (null: Studio's start page) or shows the running one.
     * {@code hostWindow} is the game's native window handle (0: a normal window). Never throws.
     */
    public static synchronized Result open(Path mosaicJson, Path gameDir, long hostWindow) {
        if (running()) {
            send("show");
            return Result.SHOWN;
        }
        try {
            List<String> args = arguments(System.getProperty("java.class.path", ""), ownJar(),
                System.getProperty("java.library.path"), System.getProperty("org.lwjgl.librarypath"), hostWindow, mosaicJson);
            Path dir = Files.createDirectories(gameDir.resolve("bedray-studio"));
            Path argFile = dir.resolve("studio.args");
            // an @argfile: the game's classpath is longer than a Windows command line may be
            Files.writeString(argFile, String.join("\n", quoted(args)) + "\n", StandardCharsets.UTF_8);
            Process p = new ProcessBuilder(javaExecutable(), "@" + argFile.toAbsolutePath())
                .directory(gameDir.toFile()).redirectErrorStream(true).start();
            studio = p;
            control = new java.io.OutputStreamWriter(p.getOutputStream(), StandardCharsets.UTF_8);
            Path log = dir.resolve("studio.log");
            Files.writeString(log, "", StandardCharsets.UTF_8);
            Thread reader = new Thread(() -> pump(p, log), "mosaic-studio-output");
            reader.setDaemon(true);
            reader.start();
            LOGGER.info("[VKB-MOSAIC] Mosaic Studio started (pid {}, in the game window: {}) on {}", p.pid(), hostWindow != 0, mosaicJson);
            return Result.STARTED;
        } catch (Exception | LinkageError e) {
            LOGGER.warn("[VKB-MOSAIC] Mosaic Studio could not start: {}", e.toString());
            return Result.FAILED;
        }
    }

    /** Hides Studio (the game's screen was closed from the game side). */
    public static synchronized void hide() {
        if (running()) send("hide");
    }

    private static void send(String line) {
        try {
            control.write(line + "\n");
            control.flush();
        } catch (Exception e) {
            LOGGER.warn("[VKB-MOSAIC] Studio control failed: {}", e.toString());
        }
    }

    /** Studio's output into bedray-studio/studio.log; "hidden" tells the game Studio went back to the game. */
    private static void pump(Process p, Path log) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            for (String line; (line = r.readLine()) != null; ) {
                if (line.equals("hidden")) {
                    onHidden.run();
                    continue;
                }
                Files.writeString(log, line + System.lineSeparator(), StandardCharsets.UTF_8, StandardOpenOption.APPEND);
            }
        } catch (Exception ignored) {
            // Studio ended
        }
        onHidden.run();
    }

    /** The JVM arguments of the Studio process (without the java executable). */
    static List<String> arguments(String gameClasspath, Path ownJar, String libraryPath, String lwjglPath, long hostWindow,
                                  Path mosaicJson) {
        Set<String> cp = new LinkedHashSet<>();
        if (ownJar != null) cp.add(ownJar.toAbsolutePath().toString());
        for (String e : gameClasspath.split(File.pathSeparator)) if (!e.isBlank()) cp.add(e);
        List<String> out = new ArrayList<>();
        out.add("--enable-native-access=ALL-UNNAMED");
        out.add("-Xmx2g");
        if (hostWindow != 0) out.add("-Dmosaic.embed.hwnd=" + hostWindow);
        if (libraryPath != null && !libraryPath.isBlank()) out.add("-Djava.library.path=" + libraryPath);
        if (lwjglPath != null && !lwjglPath.isBlank()) out.add("-Dorg.lwjgl.librarypath=" + lwjglPath);
        out.add("-cp");
        out.add(String.join(File.pathSeparator, cp));
        out.add(MAIN);
        if (mosaicJson != null) out.add(mosaicJson.toAbsolutePath().toString());
        return out;
    }

    /** Java @argfile quoting: every argument in double quotes, backslashes doubled. */
    static List<String> quoted(List<String> args) {
        List<String> q = new ArrayList<>();
        for (String a : args) q.add('"' + a.replace("\\", "\\\\").replace("\"", "\\\"") + '"');
        return q;
    }

    private static String javaExecutable() {
        String cmd = ProcessHandle.current().info().command().orElse(null);
        if (cmd != null && Files.isRegularFile(Path.of(cmd))) return cmd;
        Path home = Path.of(System.getProperty("java.home"), "bin");
        Path javaw = home.resolve("javaw.exe");
        return Files.isRegularFile(javaw) ? javaw.toString() : home.resolve("java").toString();
    }

    /** The jar (or class folder) Bedray's classes come from. */
    private static Path ownJar() {
        try {
            var src = StudioLauncher.class.getProtectionDomain().getCodeSource();
            return src == null ? null : Path.of(src.getLocation().toURI());
        } catch (URISyntaxException | IllegalArgumentException | SecurityException e) {
            return null;
        }
    }
}
