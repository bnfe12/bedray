package io.github.andrexarlay.vulkancompat.mixin.iris;

import net.irisshaders.iris.IrisVKOnly;
import net.irisshaders.iris.gui.screen.ShaderPackScreen;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin into Iris's {@link IrisVKOnly}.
 * Intercepts Iris's keybind handler so pressing the shaderpack selection hotkey
 * opens the real {@link ShaderPackScreen} instead of {@code ShaderPackScreenPlaceholder(null)}.
 */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.IrisVKOnly", remap = false)
public abstract class MixinIrisVKOnly {
    private static final Logger LOGGER = LoggerFactory.getLogger("VulkanCompat/IrisKeyBypass");

    @Shadow
    private static KeyMapping shaderpackScreenKeybind;

    @Inject(method = "handleKeybinds", at = @At("HEAD"), cancellable = true)
    private static void vkb$handleShaderPackKey(CallbackInfo ci) {
        ci.cancel();
        if (shaderpackScreenKeybind != null && shaderpackScreenKeybind.consumeClick()) {
            Minecraft mc = Minecraft.getInstance();
            try {
                LOGGER.info("[VKB-IRIS] Opening ShaderPackScreen via Iris keybind");
                mc.gui.setScreen(new ShaderPackScreen(mc.gui.screen()));
            } catch (Throwable t) {
                LOGGER.error("[VKB-IRIS] Failed to open ShaderPackScreen via keybind: {}", t.getMessage(), t);
            }
        }
    }
}
