package io.github.andrexarlay.vulkancompat.mixin.iris;

import net.irisshaders.iris.compat.sodium.config.ShaderPackScreenPlaceholder;
import net.irisshaders.iris.gui.screen.ShaderPackScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin into Iris's {@link ShaderPackScreenPlaceholder}.
 * <p>
 * By default, when Iris runs on Vulkan it displays a modal screen:
 * "Iris cannot run when using Vulkan. Would you like to switch to OpenGL? This will close your game."
 * This mixin intercepts its {@code init()} and {@code switchToVk()} methods:
 * 1. Immediately opens the real {@link ShaderPackScreen} with the parent screen instead of the dummy dialog.
 * 2. Prevents the "Switch" action from terminating the game or switching the user's preferred backend to OpenGL.
 */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.compat.sodium.config.ShaderPackScreenPlaceholder", remap = false)
public abstract class MixinShaderPackScreenPlaceholder {
    private static final Logger LOGGER = LoggerFactory.getLogger("VulkanCompat/IrisScreenBypass");

    @Shadow
    @Final
    private Screen parent;

    @Inject(method = "init", at = @At("HEAD"), cancellable = true)
    private void vkb$bypassPlaceholder(CallbackInfo ci) {
        ci.cancel();
        try {
            LOGGER.info("[VKB-IRIS] Intercepted Iris Vulkan placeholder: opening real ShaderPackScreen instead");
            Minecraft.getInstance().gui.setScreen(new ShaderPackScreen(this.parent));
        } catch (Throwable t) {
            LOGGER.error("[VKB-IRIS] Failed to open ShaderPackScreen: {}", t.getMessage(), t);
            Minecraft.getInstance().gui.setScreen(this.parent);
        }
    }

    @Inject(method = "switchToVk", at = @At("HEAD"), cancellable = true)
    private void vkb$preventSwitchToGl(CallbackInfo ci) {
        ci.cancel();
        LOGGER.warn("[VKB-IRIS] Suppressed Iris attempt to switch backend to OpenGL or stop game");
    }
}
