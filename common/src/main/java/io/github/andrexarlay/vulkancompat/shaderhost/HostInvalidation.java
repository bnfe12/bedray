package io.github.andrexarlay.vulkancompat.shaderhost;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Invalidation rules for a host change (the Iris dual-path design, "Selector contract"): changing host invalidates shader modules,
 * pass graph, material bindings, temporal history and provider instances. The live device is never mutated; when the target
 * host needs device extensions the device was not created with, the switch is deferred to a restart.
 */
@Experimental("I0")
public final class HostInvalidation {

    /** Per-host resources that are dropped on a host change. Scene/BLAS/TLAS of the shared core survive (host-agnostic). */
    public enum Resource {
        SHADER_MODULES,
        PASS_GRAPH,
        MATERIAL_BINDINGS,
        TEMPORAL_HISTORY,
        PROVIDER_INSTANCES
    }

    /**
     * @param invalidate        resources to destroy before the new host is activated (empty = nothing to do)
     * @param restartRequired   true when {@code missingExtensions} is not empty: the switch is stored, applied after restart
     * @param missingExtensions extensions the target host needs that the live device lacks (sorted)
     */
    public record Plan(Set<Resource> invalidate, boolean restartRequired, Set<String> missingExtensions) {
        public Plan {
            invalidate = Collections.unmodifiableSet(invalidate.isEmpty() ? EnumSet.noneOf(Resource.class) : EnumSet.copyOf(invalidate));
            missingExtensions = Collections.unmodifiableSet(new TreeSet<>(missingExtensions));
        }

        public boolean isNoop() {
            return invalidate.isEmpty() && !restartRequired;
        }
    }

    private HostInvalidation() {}

    /**
     * @param from              currently active host (null when nothing was active yet: first activation, nothing to drop)
     * @param to                host that should become active
     * @param toExtraExtensions {@link ShaderHost#extraDeviceExtensions()} of {@code to}
     * @param liveDevice        extensions of the live device; empty before device creation (then nothing can be missing yet:
     *                          the late provider still can request them)
     */
    public static Plan plan(ShaderHostKind from, ShaderHostKind to, Set<String> toExtraExtensions, Optional<Set<String>> liveDevice) {
        Set<String> missing = new TreeSet<>();
        if (liveDevice.isPresent()) {
            for (String ext : toExtraExtensions) {
                if (!liveDevice.get().contains(ext)) {
                    missing.add(ext);
                }
            }
        }
        boolean restart = !missing.isEmpty();
        if (from == null || from == to) {
            return new Plan(EnumSet.noneOf(Resource.class), restart, missing);
        }
        return new Plan(EnumSet.allOf(Resource.class), restart, missing);
    }
}
