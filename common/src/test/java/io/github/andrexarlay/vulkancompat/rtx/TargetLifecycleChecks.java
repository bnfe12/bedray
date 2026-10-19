package io.github.andrexarlay.vulkancompat.rtx;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.renderpearl.api.GpuFormat;

/**
 * Java-level checks (L2) of the render target lifecycle policy. No GPU: not evidence that the crash is fixed in game.
 */
public final class TargetLifecycleChecks {
    private TargetLifecycleChecks() {}

    private static void check(boolean cond, String msg) {
        if (!cond) {
            throw new AssertionError(msg);
        }
    }

    public static void runAll() {
        check(TargetLifecyclePolicy.isDedicatedWorldTargetAlwaysStorage(), "dedicated world target must always be STORAGE");
        check(TargetLifecyclePolicy.resolveStorageScope(true) == GpuFormat.RGBA8_UNORM, "world target storage scope is RGBA8_UNORM");
        check(TargetLifecyclePolicy.resolveStorageScope(false) == null, "general textures get no storage scope");
        check(!TargetLifecyclePolicy.shouldRetireTargetOnRtToggle(false, true), "RT off->on must not retire the target");
        check(!TargetLifecyclePolicy.shouldRetireTargetOnRtToggle(true, false), "RT on->off must not retire the target");

        check(!TargetLifecyclePolicy.isTargetRetirementAllowed(true, TargetLifecyclePolicy.PublicationState.NOT_PUBLISHED),
            "no retirement during the world phase");
        check(!TargetLifecyclePolicy.isTargetRetirementAllowed(false, TargetLifecyclePolicy.PublicationState.UNKNOWN),
            "UNKNOWN publication state blocks retirement (fail-closed)");
        check(!TargetLifecyclePolicy.isTargetRetirementAllowed(false, TargetLifecyclePolicy.PublicationState.PUBLISHED),
            "PUBLISHED blocks retirement");
        check(TargetLifecyclePolicy.isTargetRetirementAllowed(false, TargetLifecyclePolicy.PublicationState.NOT_PUBLISHED),
            "NOT_PUBLISHED outside the world phase allows retirement");

        Object target = new Object();
        check(TargetLifecyclePolicy.evaluatePublicationState(null, target) == TargetLifecyclePolicy.PublicationState.UNKNOWN,
            "no renderer -> UNKNOWN");
        check(TargetLifecyclePolicy.evaluatePublicationState(new Object(), target) == TargetLifecyclePolicy.PublicationState.UNKNOWN,
            "renderer without accessor (mixin not applied) -> UNKNOWN");
        check(TargetLifecyclePolicy.evaluatePublicationState(new Object(), null) == TargetLifecyclePolicy.PublicationState.NOT_PUBLISHED,
            "no target -> NOT_PUBLISHED");
        SkyRenderTargetAccessor holdsNull = () -> null;
        check(TargetLifecyclePolicy.evaluatePublicationState(holdsNull, target) == TargetLifecyclePolicy.PublicationState.NOT_PUBLISHED,
            "accessor holding another target -> NOT_PUBLISHED");

        // Resize in place keeps the identity of the object (what RenderTarget.resize does); a replaced object does not.
        Object same = target;
        check(TargetLifecyclePolicy.hasStableIdentity(target, same), "same object keeps identity");
        check(!TargetLifecyclePolicy.hasStableIdentity(target, new Object()), "replaced object loses identity");
        check(!TargetLifecyclePolicy.hasStableIdentity(null, null), "null is never a stable identity");
    }
}
