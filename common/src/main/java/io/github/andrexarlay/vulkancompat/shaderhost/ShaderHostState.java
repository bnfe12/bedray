package io.github.andrexarlay.vulkancompat.shaderhost;

/**
 * Capability result of a shader host, produced before renderer activation (the Iris dual-path design, "Selector contract").
 * Only {@link #READY} may be activated.
 */
@Experimental("I0")
public enum ShaderHostState {
    /** Host can be activated now. */
    READY,
    /** Host input missing: for iris, no shaderpack selected / the selected pack is not in shaderpacks/. */
    NOT_INSTALLED,
    /** Pack found and read, but it needs features this host cannot execute yet (or the pack was rejected as unsafe). */
    UNSUPPORTED_PACK_FEATURES,
    /** Pack sources failed to preprocess/compile (stage I2+). */
    COMPILE_FAILED,
    /** The pack needs device extensions/features the live VkDevice does not have (a restart cannot fix the GPU). */
    DEVICE_UNSUPPORTED,
    /** The host failed at runtime in this session and stays off until restart; native is used instead. */
    DISABLED_FOR_SESSION;

    public boolean isUsable() {
        return this == READY;
    }
}
