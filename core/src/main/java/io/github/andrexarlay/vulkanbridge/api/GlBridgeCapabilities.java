package io.github.andrexarlay.vulkanbridge.api;

import java.util.Set;

/**
 * What the {@link GlBridge} can do in this game session (ADR-0006). Check this instead of comparing versions.
 *
 * @param state           {@link GlBridge#state()} when the snapshot was taken
 * @param platform        {@code os.name/os.arch} of the JVM
 * @param handleType      external memory handle type used for sharing ({@code OPAQUE_WIN32}), or {@code none} on an unsupported platform
 * @param tiers           tiers usable now: TIER_1 and TIER_2 while the state is WAITING_FOR_DEVICE or READY, otherwise empty
 * @param latencies       latency modes usable now (same rule)
 * @param phases          phases a renderer may be composited in (same rule)
 * @param deviceUuidMatch whether the GL context and the engine VkPhysicalDevice were verified to be the same GPU
 *                        (LUID, node mask, device and driver UUID); {@code null} before that check ran (state READY or FAILED)
 */
@Experimental
public record GlBridgeCapabilities(GlBridge.State state, String platform, String handleType, Set<GlTier> tiers,
                                   Set<GlRendererOptions.Latency> latencies, Set<FramePhase> phases, Boolean deviceUuidMatch) {
    public GlBridgeCapabilities {
        tiers = Set.copyOf(tiers);
        latencies = Set.copyOf(latencies);
        phases = Set.copyOf(phases);
    }

    public boolean supports(GlTier tier) {
        return tiers.contains(tier);
    }
}
