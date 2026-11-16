package io.github.andrexarlay.vulkanbridge.installer;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Finds game folders a Bedray jar can be installed into. Pure file-system reads, no launcher account data is opened:
 * the official launcher folder ({@code %APPDATA%\.minecraft}) and Prism Launcher instances
 * ({@code %APPDATA%\PrismLauncher\instances\<name>\(minecraft|.minecraft)}). A folder is only listed when it already exists;
 * the installer never creates a launcher instance.
 */
public final class InstallTargets {
    private InstallTargets() {
    }

    /** A game directory and the {@code mods} folder inside it. */
    public record Target(String label, Path gameDir) {
        public Path modsDir() {
            return gameDir.resolve("mods");
        }
    }

    /** Lists targets for the given environment ({@code APPDATA}) so tests can use a temporary folder. */
    public static List<Target> detect(Map<String, String> env) {
        List<Target> out = new ArrayList<>();
        String appData = env.get("APPDATA");
        if (appData == null || appData.isBlank()) {
            return out;
        }
        Path base = Path.of(appData);
        Path official = base.resolve(".minecraft");
        if (Files.isDirectory(official)) {
            out.add(new Target("Minecraft launcher (.minecraft)", official));
        }
        Path instances = base.resolve("PrismLauncher").resolve("instances");
        if (Files.isDirectory(instances)) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(instances)) {
                List<Path> dirs = new ArrayList<>();
                for (Path p : stream) {
                    if (Files.isDirectory(p)) {
                        dirs.add(p);
                    }
                }
                dirs.sort(null);
                for (Path instance : dirs) {
                    Path game = gameDirOf(instance);
                    if (game != null) {
                        out.add(new Target("Prism: " + instance.getFileName(), game));
                    }
                }
            } catch (IOException | SecurityException ignored) {
                // an unreadable launcher folder simply lists fewer targets
            }
        }
        return out;
    }

    private static Path gameDirOf(Path instance) {
        for (String name : new String[] {"minecraft", ".minecraft"}) {
            Path candidate = instance.resolve(name);
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
        }
        return null;
    }
}
