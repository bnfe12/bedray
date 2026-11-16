package io.github.andrexarlay.vulkanbridge.installer;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Copies the Bedray jar that is running the installer into a game's {@code mods} folder and prepares {@code bedray/natives}.
 * It never deletes or overwrites a different file: an existing file with the same name must be byte-identical, and older Bedray
 * jars are reported, not removed. The copy is verified with SHA-256 before it is moved into place.
 */
public final class JarInstaller {
    private JarInstaller() {
    }

    /** What happened; {@code messages} are shown to the player as they are. */
    public record Result(boolean ok, boolean changed, List<String> messages) {
    }

    public static Result install(Path sourceJar, Path gameDir) {
        List<String> messages = new ArrayList<>();
        if (sourceJar == null || !Files.isRegularFile(sourceJar)
            || !sourceJar.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar")) {
            messages.add("The installer must be started from the Bedray .jar file itself.");
            return new Result(false, false, messages);
        }
        if (gameDir == null || !Files.isDirectory(gameDir)) {
            messages.add("The game folder does not exist: " + gameDir);
            return new Result(false, false, messages);
        }
        Path mods = gameDir.resolve("mods");
        Path target = mods.resolve(sourceJar.getFileName().toString());
        try {
            Files.createDirectories(mods);
            String sourceHash = sha256(sourceJar);
            if (Files.exists(target)) {
                if (sha256(target).equals(sourceHash)) {
                    messages.add("Already installed: " + target.getFileName());
                    prepareNatives(gameDir, messages);
                    reportOlder(mods, target, messages);
                    return new Result(true, false, messages);
                }
                messages.add("A different file named " + target.getFileName() + " is already in the mods folder. Nothing was changed.");
                return new Result(false, false, messages);
            }
            Path temp = Files.createTempFile(mods, "bedray-install-", ".tmp");
            try {
                Files.copy(sourceJar, temp, StandardCopyOption.REPLACE_EXISTING);
                if (!sha256(temp).equals(sourceHash)) {
                    messages.add("The copy did not match the original (checksum mismatch). Nothing was installed.");
                    return new Result(false, false, messages);
                }
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
            } finally {
                Files.deleteIfExists(temp);
            }
            messages.add("Installed " + target.getFileName() + " into " + mods);
            prepareNatives(gameDir, messages);
            reportOlder(mods, target, messages);
            return new Result(true, true, messages);
        } catch (IOException | RuntimeException e) {
            messages.add("Installation failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return new Result(false, false, messages);
        }
    }

    private static void prepareNatives(Path gameDir, List<String> messages) throws IOException {
        Path natives = gameDir.resolve("bedray").resolve("natives");
        Files.createDirectories(natives);
        messages.add("Vendor DLLs go in: " + natives + " (use Bedray Settings > Download DLLs in game for the official pages).");
    }

    private static void reportOlder(Path mods, Path installed, List<String> messages) throws IOException {
        try (Stream<Path> files = Files.list(mods)) {
            files.filter(p -> !p.equals(installed))
                .filter(p -> {
                    String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
                    return n.startsWith("bedray") && n.endsWith(".jar");
                })
                .sorted()
                .forEach(p -> messages.add("Another Bedray jar is present and was left alone: " + p.getFileName()
                    + ". Remove it yourself if it is an older version."));
        }
    }

    static String sha256(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[1 << 16];
            int read;
            while ((read = in.read(buffer)) > 0) {
                digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the Java platform", e);
        }
    }
}
