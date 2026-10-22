package io.github.andrexarlay.vulkancompat.mixin.compute;

import io.github.andrexarlay.vulkancompat.compute.ComputeAtlasBridge;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.CompletableFuture;

/** Gates the optional compute material capture across the complete resource reload. */
@Mixin(value = Minecraft.class, remap = false)
public abstract class MixinComputeResources {
    @Inject(method = "reloadResourcePacks(ZLnet/minecraft/client/GameLoadCookie;)Ljava/util/concurrent/CompletableFuture;",
        at = @At("HEAD"))
    private void bedray$beginMaterialReload(CallbackInfoReturnable<CompletableFuture<Void>> ci) {
        ComputeAtlasBridge.onReloadStarted();
    }

    @Inject(method = "onResourceLoadFinished(Lnet/minecraft/client/GameLoadCookie;)V", at = @At("TAIL"))
    private void bedray$finishMaterialReload(CallbackInfo ci) {
        ComputeAtlasBridge.onReloadFinished();
    }

    @Inject(method = "close()V", at = @At("HEAD"))
    private void bedray$beginClientShutdown(CallbackInfo ci) {
        ComputeAtlasBridge.onShutdownStarted();
    }
}
