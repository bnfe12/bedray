package io.github.andrexarlay.vulkancompat.mixin.rtx;

import io.github.andrexarlay.vulkancompat.rt.keybind.VulkanRtxKeyBinds;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.client.PreferredGraphicsApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin into {@link Options} to register the Bedray key mappings in Minecraft's options system
 * and guard preferredGraphicsBackend from falling back to OpenGL/Default.
 */
@Mixin(value = Options.class, remap = false)
public abstract class MixinOptions {

    private static final Logger VKB_LOGGER = LoggerFactory.getLogger("Bedray/BackendGuard");

    @Shadow
    @Final
    @Mutable
    public KeyMapping[] keyMappings;

    @Shadow
    public abstract OptionInstance<PreferredGraphicsApi> preferredGraphicsBackend();

    @Shadow
    public abstract void save();

    @Inject(method = "<init>", at = @At("RETURN"))
    private void vkb$registerKeyMappings(CallbackInfo ci) {
        this.keyMappings = VulkanRtxKeyBinds.registerKeys(this.keyMappings);
    }

    @Inject(method = "load", at = @At("RETURN"))
    private void vkb$enforceVulkanOnLoad(CallbackInfo ci) {
        if (this.preferredGraphicsBackend().get() != PreferredGraphicsApi.VULKAN) {
            VKB_LOGGER.info("[VKB-GUARD] preferredGraphicsBackend was {}, enforcing VULKAN", this.preferredGraphicsBackend().get());
            this.preferredGraphicsBackend().set(PreferredGraphicsApi.VULKAN);
            this.save();
        }
    }

    @Inject(method = "save", at = @At("HEAD"))
    private void vkb$preventResetOnSave(CallbackInfo ci) {
        if (this.preferredGraphicsBackend().get() != PreferredGraphicsApi.VULKAN) {
            VKB_LOGGER.info("[VKB-GUARD] Preventing options reset to non-Vulkan backend: forcing VULKAN");
            this.preferredGraphicsBackend().set(PreferredGraphicsApi.VULKAN);
        }
    }
}
