package io.github.andrexarlay.vulkanbridge.mixin;

import io.github.andrexarlay.vulkanbridge.core.BackendGuard;
import io.github.andrexarlay.vulkanbridge.platform.Platforms;
import net.minecraft.client.Minecraft;
import net.minecraft.client.PreferredGraphicsApi;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hooks for {@link BackendGuard}. Targets ({@code javap -p -c net.minecraft.client.Minecraft}, vanilla
 * libs/minecraft-26.3-client.jar and NeoForge minecraft-client-patched-26.3.0.76): the constructor has exactly three
 * {@code OptionInstance.set(Ljava/lang/Object;)V} calls - {@code fullscreen().set(false)},
 * {@code preferredGraphicsBackend().set(PreferredGraphicsApi.DEFAULT)} (only when the last startup was unclean and the
 * stored value is VULKAN) and {@code preferredGraphicsBackend().set(PreferredGraphicsApi.OPENGL)} (unclean, stored
 * DEFAULT). Only the second one is changed, recognised by value DEFAULT while the option still holds VULKAN.
 * {@code private void onGameLoadFinished(net.minecraft.client.GameLoadCookie)} sets {@code startedCleanly = true}.
 * Ported from the earlier prototype {@code MixinOptions}, see BackendGuard for what changed and why.
 */
@Mixin(Minecraft.class)
public abstract class MixinMinecraftBackendGuard {
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("BedrayCore/Options");

    @ModifyArg(method = "<init>",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/OptionInstance;set(Ljava/lang/Object;)V"))
    private Object vkbridge$guardBackendReset(Object value) {
        if (value instanceof PreferredGraphicsApi api && api != PreferredGraphicsApi.VULKAN) {
            BackendGuard.Decision decision = BackendGuard.onUncleanStartup(
                    BackendGuard.stateFile(Platforms.get().configDir()),
                    BackendGuard.enabledFromProperties(), BackendGuard.maxKeepsFromProperties());
            if (decision != BackendGuard.Decision.KEEP_VULKAN) {
                LOGGER.info("[BedrayCore/Options] backend_guard decision={} letting vanilla reset to {}", decision, api);
                return value;
            }
            LOGGER.info("[BedrayCore/Options] backend_guard intercepted reset to {}, strictly enforcing VULKAN", api);
            return PreferredGraphicsApi.VULKAN;
        }
        return value;
    }

    @Inject(method = "onGameLoadFinished", at = @At("RETURN"))
    private void vkbridge$cleanStartup(CallbackInfo ci) {
        BackendGuard.onCleanStartup(BackendGuard.stateFile(Platforms.get().configDir()));
    }
}
