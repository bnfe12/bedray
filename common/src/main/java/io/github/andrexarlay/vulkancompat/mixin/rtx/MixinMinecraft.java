package io.github.andrexarlay.vulkancompat.mixin.rtx;

import io.github.andrexarlay.vulkancompat.rt.keybind.VulkanRtxKeyBinds;
import io.github.andrexarlay.vulkancompat.shaderhost.iris.IrisFrameHook;
import io.github.andrexarlay.vulkancompat.resources.ResourceReloadEpoch;
import net.minecraft.client.Minecraft;
import net.minecraft.client.PreferredGraphicsApi;
import net.minecraft.client.multiplayer.ClientLevel;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Mixin into {@link Minecraft} to poll Bedray hotkeys during client keybind handling
 * and prevent Minecraft constructor from downgrading preferred graphics API on unexpected shutdown.
 * Also feeds the I3.5 iris-host teardown: {@code setLevel} (world unload/change) and {@code reloadResourcePacks}
 * (F3+T) both run on the render thread, so the executor's GPU objects are destroyed while the device is alive.
 * Signatures (javap -p on libs/minecraft-26.3-client.jar): {@code public void setLevel(ClientLevel)},
 * {@code public CompletableFuture<Void> reloadResourcePacks()}.
 */
@Mixin(value = Minecraft.class, remap = false)
public abstract class MixinMinecraft {

    @Inject(method = "onResourceLoadFinished(Lnet/minecraft/client/GameLoadCookie;)V", at = @At("TAIL"))
    private void vkb$resourceReloadCompleted(CallbackInfo ci) {
        long epoch = ResourceReloadEpoch.completed();
        LoggerFactory.getLogger("VKB-RESOURCES").info("[VKB-RESOURCES] reload completed epoch={}", epoch);
    }

    @Inject(method = "handleKeybinds()V", at = @At("TAIL"))
    private void vkb$handleKeybinds(CallbackInfo ci) {
        VulkanRtxKeyBinds.handleKeybinds();
    }

    @Inject(method = "setLevel(Lnet/minecraft/client/multiplayer/ClientLevel;)V", at = @At("HEAD"))
    private void vkb$irisHostWorldChange(ClientLevel level, CallbackInfo ci) {
        IrisFrameHook.teardown(level == null ? "world_unload" : "world_change");
    }

    @Inject(method = "reloadResourcePacks()Ljava/util/concurrent/CompletableFuture;", at = @At("HEAD"))
    private void vkb$irisHostResourceReload(CallbackInfoReturnable<java.util.concurrent.CompletableFuture<Void>> cir) {
        IrisFrameHook.teardown("resource_reload");
    }

    @ModifyArg(method = "<init>",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/OptionInstance;set(Ljava/lang/Object;)V"))
    private Object vkb$guardBackendInConstructor(Object value) {
        if (value instanceof PreferredGraphicsApi api && api != PreferredGraphicsApi.VULKAN) {
            LoggerFactory.getLogger("Bedray/BackendGuard")
                .info("[VKB-GUARD] Intercepted Minecraft.<init> reset to {}, keeping VULKAN", api);
            return PreferredGraphicsApi.VULKAN;
        }
        return value;
    }
}