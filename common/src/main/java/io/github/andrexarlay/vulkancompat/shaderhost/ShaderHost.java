package io.github.andrexarlay.vulkancompat.shaderhost;

import java.util.Set;

/**
 * One shader-host path (ADR-0004 ShaderHostProvider, internal). A host owns only source discovery, compilation, pass
 * declarations, uniform/material semantics and host-specific feature translation. It never creates or mutates the VkDevice and
 * never calls Bedray Core internals; the shared RT core (rt/world) owns scene, BLAS/TLAS, dispatch, history and images.
 */
@Experimental("I0")
public interface ShaderHost {
    ShaderHostKind kind();

    /** Capability result before activation. Must not throw for bad user input (bad pack = a state, not an exception). */
    ShaderHostStatus probe(ShaderHostContext ctx);

    /**
     * Device extensions this host needs IN ADDITION to what the shared RT core registers (RtDeviceRequirements).
     * Valid after {@link #probe}. Used by {@link HostInvalidation} to decide whether a switch needs a restart.
     */
    Set<String> extraDeviceExtensions();
}
