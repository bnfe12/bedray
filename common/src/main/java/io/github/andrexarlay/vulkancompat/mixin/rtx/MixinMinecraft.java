package io.github.andrexarlay.vulkancompat.mixin.rtx;

import io.github.andrexarlay.vulkancompat.rt.keybind.VulkanRtxKeyBinds;
import net.minecraft.client.Minecraft;
import net.minecraft.client.PreferredGraphicsApi;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin into {@link Minecraft} to poll Bedray hotkeys during client keybind handling
 * and prevent Minecraft constructor from downgrading preferred graphics API on unexpected shutdown.
 */
@Mixin(value = Minecraft.class, remap = false)
public abstract class MixinMinecraft {

    @Inject(method = "handleKeybinds()V", at = @At("TAIL"))
    private void vkb$handleKeybinds(CallbackInfo ci) {
        VulkanRtxKeyBinds.handleKeybinds();
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
