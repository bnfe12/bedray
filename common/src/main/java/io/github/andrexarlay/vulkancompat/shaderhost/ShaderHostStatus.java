package io.github.andrexarlay.vulkancompat.shaderhost;

import java.util.Objects;

/** Result of {@link ShaderHost#probe}: state plus a human-readable reason (logged, shown on the settings page later). */
@Experimental("I0")
public record ShaderHostStatus(ShaderHostKind host, ShaderHostState state, String detail) {
    public ShaderHostStatus {
        Objects.requireNonNull(host, "host");
        Objects.requireNonNull(state, "state");
        detail = detail == null ? "" : detail;
    }

    public static ShaderHostStatus of(ShaderHostKind host, ShaderHostState state, String detail) {
        return new ShaderHostStatus(host, state, detail);
    }
}
