package io.github.andrexarlay.vulkancompat.sodium;

import java.util.List;

/**
 * Factory looks shipped with Bedray, loadable from "My presets" alongside the player's own files. A built-in preset is the
 * same values-only text a saved preset holds ({@code key = value} lines), so it loads through the identical
 * {@link SettingsDraft#importText} path and every value is range checked. Built-ins are read-only: they cannot be deleted
 * or overwritten, and a player preset of the same name stays a separate, deletable file.
 */
public final class BuiltinPresets {
    /** A named factory look. {@code text} is preset TOML (values only). */
    public record Preset(String name, String text) {
    }

    private static final List<Preset> ALL = List.of(
        new Preset("Vibrant", """
            # Bedray built-in preset: Vibrant
            # Richer saturation and contrast (vibrant_visuals) paired with the cinematic ACES tone curve.
            vibrant_visuals = true
            tonemapper = "ACES"
            """)
    );

    private BuiltinPresets() {
    }

    public static List<Preset> all() {
        return ALL;
    }

    /** The built-in with this name (case-insensitive), or null when the name is a player preset only. */
    public static Preset find(String name) {
        if (name == null) {
            return null;
        }
        for (Preset p : ALL) {
            if (p.name().equalsIgnoreCase(name.trim())) {
                return p;
            }
        }
        return null;
    }

    public static boolean isBuiltin(String name) {
        return find(name) != null;
    }
}