package io.github.andrexarlay.vulkancompat.mixin.compute;

import com.mojang.blaze3d.pipeline.RenderTarget;
import io.github.andrexarlay.vulkancompat.compute.ComputeAtlasBridge;
import io.github.andrexarlay.vulkancompat.compute.ComputeRenderBridge;
import io.github.andrexarlay.vulkancompat.shaderhost.iris.IrisRenderBridge;
import io.github.andrexarlay.vulkanbridge.api.VulkanBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Shared retirement gate before sampled world targets can be replaced. */
@Mixin(value = RenderTarget.class, remap = false)
public abstract class MixinComputeTarget {
    @Inject(method = "destroyBuffers()V", at = @At("HEAD"))
    private void bedray$retireTargetConsumers(CallbackInfo ci) {
        if (!VulkanBridge.isVulkan()) return;
        boolean compute = ComputeRenderBridge.onTargetClosing((RenderTarget)(Object)this);
        boolean iris = IrisRenderBridge.onTargetClosing((RenderTarget)(Object)this);
        if ((!compute || !iris) && !ComputeAtlasBridge.isClosing())
            throw new IllegalStateException("Cannot replace world target after renderer cleanup failure; restart the client");
    }
}
