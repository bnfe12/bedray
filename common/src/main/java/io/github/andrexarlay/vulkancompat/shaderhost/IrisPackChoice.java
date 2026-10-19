package io.github.andrexarlay.vulkancompat.shaderhost;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Properties;

/**
 * Which shaderpack the iris host should use. Source, in this order:
 * <ol>
 *   <li>{@code -Dvkrtx.shaderhost.pack=<file or folder name>} (session only, for autotests);</li>
 *   <li>{@code config/iris.properties} written by Iris itself: {@code enableShaders=true} and {@code shaderPack=<name>}
 *       (read-only; the Iris jar is not needed and not touched).</li>
 * </ol>
 */
@Experimental("I1")
public final class IrisPackChoice {
    public static final String PACK_PROPERTY = "vkrtx.shaderhost.pack";

    private IrisPackChoice() {}

    /** @return the pack name, or empty with nothing chosen / shaders disabled in Iris. */
    public static Optional<String> read(Path gameDir) throws IOException {
        String override = System.getProperty(PACK_PROPERTY);
        if (override != null && !override.isBlank()) {
            return Optional.of(override.trim());
        }
        Path file = gameDir.resolve("config").resolve("iris.properties");
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.empty();
        }
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            p.load(in); // Iris writes it with Properties.store (ISO-8859-1 + \\u escapes), so load(InputStream) is the matching reader
        }
        if (!Boolean.parseBoolean(p.getProperty("enableShaders", "false").trim())) {
            return Optional.empty();
        }
        String pack = p.getProperty("shaderPack", "").trim();
        return pack.isEmpty() ? Optional.empty() : Optional.of(pack);
    }
}
