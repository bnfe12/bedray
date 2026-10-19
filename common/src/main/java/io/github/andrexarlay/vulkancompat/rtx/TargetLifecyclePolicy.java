package io.github.andrexarlay.vulkancompat.rtx;

import com.mojang.renderpearl.api.GpuFormat;

/**
 * Pure Java lifecycle and usage policy for dedicated render targets (L2 testable without GPU).
 *
 * <p>Enforces stable Java object identity for {@code worldTarget}:
 * <ul>
 *   <li>Dedicated {@code worldTarget} is ALWAYS created with {@code STORAGE} usage flag, ensuring
 *       it can be read/written by both rasterization and compute passes (Hardware Ray Tracing).</li>
 *   <li>Toggling Hardware RT (ON &harr; OFF) NEVER destroys the published {@code worldTarget} or
 *       replaces its Java instance, preventing dangling/stale target references in external
 *       consumers such as {@code SkyRenderer} or Sodium.</li>
 *   <li>Scoped storage usage is strictly restricted to dedicated targets (RGBA8_UNORM for world
 *       target and XeSS output; RG16_FLOAT for motion vectors) and never leaks to general engine textures.</li>
 *   <li>Target destruction is forbidden during an active world render phase.</li>
 * </ul>
 */
public final class TargetLifecyclePolicy {

    private TargetLifecyclePolicy() {
    }

    /**
     * Determines whether the dedicated world target should be retired/destroyed when the
     * Hardware Ray Tracing toggle changes.
     *
     * <p>Always returns {@code false} because {@code worldTarget} is allocated with STORAGE usage
     * from inception, maintaining stable object identity across RT reconfigurations.</p>
     */
    public static boolean shouldRetireTargetOnRtToggle(boolean currentHasStorage, boolean newWantStorage) {
        return false;
    }

    /**
     * Returns true if dedicated world targets must always request STORAGE usage.
     */
    public static boolean isDedicatedWorldTargetAlwaysStorage() {
        return true;
    }

    /**
     * Resolves the required storage format for a target creation/resize scope.
     *
     * @param isDedicatedWorldTarget true if the target being allocated is the isolated world target
     * @return {@link GpuFormat#RGBA8_UNORM} for dedicated world targets, or null for general textures
     */
    public static GpuFormat resolveStorageScope(boolean isDedicatedWorldTarget) {
        return isDedicatedWorldTarget ? GpuFormat.RGBA8_UNORM : null;
    }

    /**
     * Publication state of dedicated render targets across external renderers (e.g. SkyRenderer).
     */
    public enum PublicationState {
        PUBLISHED,
        NOT_PUBLISHED,
        UNKNOWN
    }

    /**
     * Checks whether destroying/reallocating render targets is permitted in the current phase.
     * Targets must never be destroyed while an in-flight world render pass is referencing them.
     */
    public static boolean isTargetRetirementAllowed(boolean inWorldPhase) {
        return !inWorldPhase;
    }

    /**
     * Checks whether destroying/reallocating render targets is permitted given world phase and publication state.
     *
     * <p>Retirement is strictly disallowed if:
     * <ul>
     *   <li>Currently in an active world render phase ({@code inWorldPhase == true}).</li>
     *   <li>An external renderer is known to hold the target ({@link PublicationState#PUBLISHED}).</li>
     *   <li>The publication state cannot be determined ({@link PublicationState#UNKNOWN}, such as when the accessor
     *       mixin did not apply). Unknown state blocks destruction fail-safe.</li>
     * </ul>
     * Only {@link PublicationState#NOT_PUBLISHED} outside the world render phase permits target destruction.</p>
     */
    public static boolean isTargetRetirementAllowed(boolean inWorldPhase, PublicationState publicationState) {
        if (inWorldPhase) {
            return false;
        }
        return publicationState == PublicationState.NOT_PUBLISHED;
    }

    /**
     * Evaluates the publication state of a target with respect to a renderer.
     * If the renderer does not implement {@link SkyRenderTargetAccessor} (i.e. mixin did not apply)
     * or cannot be determined, the state is {@link PublicationState#UNKNOWN}, safely blocking retirement.
     */
    public static PublicationState evaluatePublicationState(Object renderer, Object target) {
        if (target == null) {
            return PublicationState.NOT_PUBLISHED;
        }
        if (renderer == null) {
            return PublicationState.UNKNOWN;
        }
        if (renderer instanceof SkyRenderTargetAccessor acc) {
            Object captured = acc.vkb$getRenderTarget();
            return (captured == target) ? PublicationState.PUBLISHED : PublicationState.NOT_PUBLISHED;
        }
        return PublicationState.UNKNOWN;
    }

    /**
     * Verifies whether two target references maintain identity.
     */
    public static boolean hasStableIdentity(Object originalTarget, Object currentTarget) {
        return originalTarget != null && originalTarget == currentTarget;
    }
}
