package io.github.andrexarlay.vulkancompat.shaderhost;

import io.github.andrexarlay.vulkanbridge.platform.LogPaths;

import java.util.Objects;

/** Result of {@link ShaderHost#probe}: state plus a human-readable reason (logged, shown on the settings page later). */
@Experimental("I0")
public record ShaderHostStatus(ShaderHostKind host, ShaderHostState state, String detail) {
    public ShaderHostStatus {
        Objects.requireNonNull(host, "host");
        Objects.requireNonNull(state, "state");
        // The reason comes from outside (a pack scan, an Iris failure); this is the only place both the log line and the
        // settings page read it, so the redactor lives here rather than at every caller.
        detail = detail == null ? "" : LogPaths.show(detail);
    }

    public static ShaderHostStatus of(ShaderHostKind host, ShaderHostState state, String detail) {
        return new ShaderHostStatus(host, state, detail);
    }
}
