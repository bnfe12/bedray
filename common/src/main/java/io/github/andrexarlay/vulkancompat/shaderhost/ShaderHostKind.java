package io.github.andrexarlay.vulkancompat.shaderhost;

import java.util.Locale;

/** The two shader-host paths of the Iris dual-path design; toml value of {@code shader_host}. */
@Experimental("I0")
public enum ShaderHostKind {
    /** Built-in Vulkan-native RT renderer (rt/world). Always the baseline and the fallback. */
    NATIVE,
    /** Compatibility host for existing Iris/OptiFine-style shaderpacks (stages I1..I6; no executor before I3). */
    IRIS;

    public String configName() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Parses {@code native} / {@code iris} (case-insensitive, surrounding quotes and spaces ignored); null when unknown. */
    public static ShaderHostKind parse(String value) {
        if (value == null) {
            return null;
        }
        String v = value.trim().replace("\"", "").toLowerCase(Locale.ROOT);
        return switch (v) {
            case "native" -> NATIVE;
            case "iris" -> IRIS;
            default -> null;
        };
    }
}
