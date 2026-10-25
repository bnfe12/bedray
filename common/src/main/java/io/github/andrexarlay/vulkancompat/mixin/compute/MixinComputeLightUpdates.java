package io.github.andrexarlay.vulkancompat.mixin.compute;

import io.github.andrexarlay.vulkancompat.compute.ComputeRenderBridge;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.LightLayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Propagated light changes also matter in air sections that have no Sodium geometry to upload. */
@Mixin(value = ClientChunkCache.class, remap = false)
public abstract class MixinComputeLightUpdates {
    @Inject(method = "onLightUpdate(Lnet/minecraft/world/level/LightLayer;Lnet/minecraft/core/SectionPos;)V",
        at = @At("TAIL"))
    private void bedray$lightChanged(LightLayer layer, SectionPos section, CallbackInfo ci) {
        ComputeRenderBridge.sectionLightingChanged((ClientChunkCache)(Object)this, section.x(), section.y(), section.z());
    }
}
