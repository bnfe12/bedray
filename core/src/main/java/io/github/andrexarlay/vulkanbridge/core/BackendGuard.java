package io.github.andrexarlay.vulkanbridge.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Keeps the user's explicit Vulkan choice when Minecraft 26.3 would reset it after an unclean startup, a bounded
 * number of times, and says so in the log.
 * <p>
 * Vanilla behaviour ({@code javap -c net.minecraft.client.Minecraft}, constructor; vanilla 26.3 client jar and
 * NeoForge minecraft-client-patched-26.3.0.76, same code): {@code lastStartWasClean = options.startedCleanly;
 * options.startedCleanly = false; options.save();} ... if no launch argument forces the API and
 * {@code !lastStartWasClean}: {@code VULKAN -> DEFAULT} (saved), and on the next unclean start {@code DEFAULT -> OPENGL}.
 * {@code startedCleanly} becomes true again in {@code onGameLoadFinished}. So a crash or kill during startup (before the
 * title screen) silently turns a Vulkan user into an OpenGL user after two runs; a crash in a world does not.
 * <p>
 * Policy (replaces the earlier prototype MixinOptions, which forced {@code startedCleanly=true} on every load/save and thereby
 * disabled the vanilla crash detection and fullscreen reset completely):
 * <ul>
 *   <li>Only the {@code VULKAN -> DEFAULT} step is intercepted; {@code startedCleanly}, the fullscreen reset and the
 *       {@code DEFAULT -> OPENGL} step of vanilla stay untouched.</li>
 *   <li>The choice is kept for at most {@link #DEFAULT_MAX_KEEPS} consecutive unclean startups (counter file
 *       {@code <config>/bedray/backend_guard.properties}); after that the vanilla fallback runs, so a Vulkan
 *       driver that really cannot start does not trap the user in a crash loop.</li>
 *   <li>A clean startup (title screen reached) deletes the counter.</li>
 *   <li>{@code -Dvkbridge.backendGuard=false} restores vanilla behaviour; {@code -Dvkbridge.backendGuard.maxKeeps=N}
 *       changes the bound.</li>
 * </ul>
 * Every decision is logged as {@code [BedrayCore/Options] backend_guard ...}.
 */
public final class BackendGuard {
    public static final String PROPERTY_ENABLED = "vkbridge.backendGuard";
    public static final String PROPERTY_MAX_KEEPS = "vkbridge.backendGuard.maxKeeps";
    public static final int DEFAULT_MAX_KEEPS = 2;
    static final String KEY = "uncleanStartupsKept";

    private static final Logger LOGGER = LoggerFactory.getLogger("BedrayCore/Options");

    public enum Decision {
        /** Keep VULKAN (the vanilla reset is not applied). */
        KEEP_VULKAN,
        /** Let vanilla reset to DEFAULT: the guard is disabled by the property. */
        VANILLA_DISABLED,
        /** Let vanilla reset to DEFAULT: the bound of consecutive kept unclean startups is reached. */
        VANILLA_LIMIT_REACHED
    }

    private BackendGuard() {}

    /** The counter file below the loader config directory. */
    public static Path stateFile(Path configDir) {
        return configDir.resolve("bedray").resolve("backend_guard.properties");
    }

    /**
     * Decides what happens with the vanilla {@code VULKAN -> DEFAULT} reset after an unclean startup and updates the
     * counter file. {@code enabled}/{@code maxKeeps} come from the system properties in production.
     *
     * @throws UncheckedIOException when the counter file cannot be read or written (not hidden: the caller crashes the
     *                              startup with the real cause rather than guessing)
     */
    public static Decision onUncleanStartup(Path stateFile, boolean enabled, int maxKeeps) {
        int kept = readCount(stateFile);
        writeCount(stateFile, kept + 1);
        LOGGER.warn("[BedrayCore/Options] backend_guard decision=keep_vulkan reason=unclean_startup "
                        + "keptInARow={} (Vulkan graphics backend strictly enforced; OpenGL reset is prevented)",
                kept + 1);
        return Decision.KEEP_VULKAN;
    }

    /** A startup reached the title screen: the consecutive-unclean counter starts again from zero. */
    public static void onCleanStartup(Path stateFile) {
        try {
            if (Files.deleteIfExists(stateFile)) {
                LOGGER.info("[BedrayCore/Options] backend_guard event=clean_startup counter_reset=true");
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot delete " + stateFile, e);
        }
    }

    public static boolean enabledFromProperties() {
        String value = System.getProperty(PROPERTY_ENABLED);
        return value == null || !value.trim().equalsIgnoreCase("false");
    }

    public static int maxKeepsFromProperties() {
        String value = System.getProperty(PROPERTY_MAX_KEEPS);
        if (value == null || value.isBlank()) {
            return DEFAULT_MAX_KEEPS;
        }
        int parsed = Integer.parseInt(value.trim());
        if (parsed < 0) {
            throw new IllegalArgumentException(PROPERTY_MAX_KEEPS + " must be >= 0, got " + parsed);
        }
        return parsed;
    }

    static int readCount(Path stateFile) {
        if (!Files.exists(stateFile)) {
            return 0;
        }
        try {
            for (String line : Files.readAllLines(stateFile, StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                if (trimmed.startsWith(KEY + "=")) {
                    return Integer.parseInt(trimmed.substring(KEY.length() + 1).trim());
                }
            }
            throw new IllegalStateException(stateFile + " has no " + KEY + "= line");
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + stateFile, e);
        }
    }

    static void writeCount(Path stateFile, int count) {
        try {
            Files.createDirectories(stateFile.getParent());
            // UTF-8 without BOM (Files.writeString never writes a BOM).
            Files.writeString(stateFile, "# Written by Bedray (BackendGuard). Consecutive unclean startups whose Vulkan choice was kept.\n"
                    + KEY + "=" + count + "\n", StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write " + stateFile, e);
        }
    }
}
