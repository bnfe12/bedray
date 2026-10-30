package io.github.andrexarlay.vulkancompat.rt.world;

import java.util.Locale;

/**
 * The two hardware ray tracing modes of the world renderer, mutually exclusive, both on the AOT Slang pipeline:
 * <ul>
 *   <li>{@link #RAY_TRACED}: primary rays plus ray traced effects (sun shadows, reflections, ambient light, emissive
 *       lights, one-bounce indirect light), traced as a one-indirect-bounce path in the same pass as Path Tracing;</li>
 *   <li>{@link #PATH_TRACED}: full light transport (several bounces, next event estimation of the sun, early probabilistic path termination,
 *       temporal accumulation), the same pass with the player's bounce and sample counts.</li>
 * </ul>
 * {@link #AO} and {@link #DIRECT} are debug shadings of the deleted ray pass; the Slang renderer refuses them by name
 * instead of labelling a lit image with their name.
 * The mode is stored in {@code config/bedray.toml} ({@code rt_mode}) and can be forced for a session with
 * {@code -Dvkrtx.rt.mode=off|rays|path} (autotests).
 */
public enum RtMode {
    OFF("off"),
    RAY_TRACED("rays"),
    PATH_TRACED("path"),
    AO("ao"),
    DIRECT("direct");

    private final String logName;

    RtMode(String logName) {
        this.logName = logName;
    }

    /** Short name used in logs, the toml file and {@code -Dvkrtx.rt.mode}. */
    public String logName() {
        return logName;
    }

    /** Modes offered in the public settings UI: Off and Path Tracing. The others need the experimental flag. */
    public boolean isPublic() {
        return this == OFF || this == PATH_TRACED;
    }

    public boolean isActive() {
        return this != OFF;
    }

    /** Accepts {@code off|rays|path|ao|direct|hybrid} and the enum names (case-insensitive); {@code null} for anything else. */
    public static RtMode parse(String text) {
        if (text == null) {
            return null;
        }
        String s = text.trim().toLowerCase(Locale.ROOT);
        for (RtMode m : values()) {
            if (m.logName.equals(s) || m.name().toLowerCase(Locale.ROOT).equals(s)) {
                return m;
            }
        }
        return switch (s) {
            case "hybrid", "ray", "raytracing", "ray_tracing", "rt" -> RAY_TRACED;
            case "pathtracing", "path_tracing", "pt" -> PATH_TRACED;
            case "ao", "ambient_occlusion" -> AO;
            case "direct", "direct_light" -> DIRECT;
            default -> null;
        };
    }
}
