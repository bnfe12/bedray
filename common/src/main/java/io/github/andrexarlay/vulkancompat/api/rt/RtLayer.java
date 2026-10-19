package io.github.andrexarlay.vulkancompat.api.rt;

/**
 * Ray-tracing material and pass classification for dynamic and modded geometry submitted through {@link RtSceneApi}.
 */
public enum RtLayer {
    /** Opaque geometry (casts opaque shadows, no alpha test). */
    SOLID,
    /** Alpha-tested cutout geometry (foliage, grates, model cutouts with alpha >= 0.1). */
    CUTOUT,
    /** Translucent dielectric geometry (stained glass, water, tinted surfaces). */
    TRANSLUCENT,
    /** Self-luminous emissive geometry contributing to direct glow and emissive lighting. */
    EMISSIVE
}
