package io.github.andrexarlay.vulkancompat.sodium;

import io.github.andrexarlay.vulkancompat.rtx.VulkanRtxManager;
import io.github.andrexarlay.vulkancompat.rtx.config.VulkanRtxConfig;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.xess.XessRuntime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

/**
 * User presets of the Bedray settings: {@code <game dir>/bedray/presets/<name>.toml}. A preset is the text that
 * {@link VulkanRtxConfig#writeAll} produces for bedray.toml (setting values only, no paths), minus the keys in {@link #SKIPPED}.
 * Loading feeds the lines to {@link VulkanRtxConfig#applyKeyValue}, the same parser as the config file, so every value is
 * range checked and an unavailable upscaler falls back as at game start.
 */
public final class PresetStore {
    private static final Logger LOGGER = LoggerFactory.getLogger("Bedray/Presets");
    /** UI state and developer switches are not part of a look. */
    private static final Set<String> SKIPPED = Set.of("ui_expert", "experimental_rays", "rt_show_advanced", "rt_show_expert",
        "enable_hardware_ray_tracing");

    private PresetStore() {
    }

    public static Path dir() {
        return XessRuntime.resolveGameDir().resolve("bedray").resolve("presets");
    }

    /** Letters, digits, space, '-' and '_' ; 1..32 chars. Returns null when nothing usable is left. */
    public static String sanitize(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim().replaceAll("[^\\p{L}\\p{N} _-]", "");
        if (s.length() > 32) {
            s = s.substring(0, 32).trim();
        }
        return s.isEmpty() ? null : s;
    }

    public static List<String> list() {
        List<String> out = new ArrayList<>();
        try (Stream<Path> s = Files.isDirectory(dir()) ? Files.list(dir()) : Stream.<Path>empty()) {
            s.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".toml"))
                .map(n -> n.substring(0, n.length() - 5)).sorted(String.CASE_INSENSITIVE_ORDER).forEach(out::add);
        } catch (IOException e) {
            LOGGER.warn("cannot list presets: {}", e.getMessage());
        }
        return out;
    }

    /** The current settings as preset text. */
    public static String snapshot() {
        StringWriter w = new StringWriter();
        try {
            VulkanRtxConfig.writeAll(w);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        StringBuilder sb = new StringBuilder("# Bedray settings preset (values only)\n");
        for (String line : w.toString().split("\r?\n")) {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("#")) {
                continue;
            }
            if (t.startsWith("[")) {
                sb.append(t).append('\n');
                continue;
            }
            int eq = t.indexOf('=');
            if (eq > 0 && !SKIPPED.contains(t.substring(0, eq).trim())) {
                sb.append(t).append('\n');
            }
        }
        return sb.toString();
    }

    public static boolean save(String name, String text) {
        String n = sanitize(name);
        if (n == null) {
            return false;
        }
        try {
            Files.createDirectories(dir());
            Files.writeString(dir().resolve(n + ".toml"), text, StandardCharsets.UTF_8);
            LOGGER.info("[VKB-AUTOTEST] ui_preset saved name={} bytes={}", n, text.length());
            return true;
        } catch (IOException e) {
            LOGGER.warn("cannot save preset {}: {}", n, e.getMessage());
            return false;
        }
    }

    public static boolean saveCurrent(String name) {
        return save(name, snapshot());
    }

    public static boolean delete(String name) {
        String n = sanitize(name);
        try {
            return n != null && Files.deleteIfExists(dir().resolve(n + ".toml"));
        } catch (IOException e) {
            return false;
        }
    }

    public static String read(String name) {
        String n = sanitize(name);
        try {
            return n == null ? null : Files.readString(dir().resolve(n + ".toml"), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    /** Applies preset text. Returns the number of settings applied, or -1 when the text has no setting at all. */
    public static int apply(String text) {
        int n = 0;
        for (String line : text.split("\r?\n")) {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("#") || t.startsWith("[")) {
                continue;
            }
            int eq = t.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String key = t.substring(0, eq).trim();
            if (SKIPPED.contains(key)) {
                continue;
            }
            VulkanRtxConfig.applyKeyValue(key, t.substring(eq + 1).trim().replace("\"", ""));
            n++;
        }
        if (n == 0) {
            return -1;
        }
        SettingsPolicy.enforce();
        VulkanRtxConfig.save();
        VulkanRtxManager.applyResolutionScalingNow();
        VulkanSodiumConfig.requestValueRefresh();
        VulkanSodiumConfig.afterPresetApplied();
        LOGGER.info("[VKB-AUTOTEST] ui_preset applied settings={}", n);
        return n;
    }

    public static String normalizeName(String n) {
        return n == null ? "" : n.toLowerCase(Locale.ROOT);
    }
}
