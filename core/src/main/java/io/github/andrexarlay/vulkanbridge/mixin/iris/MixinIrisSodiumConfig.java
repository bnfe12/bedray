package io.github.andrexarlay.vulkanbridge.mixin.iris;

import io.github.andrexarlay.vulkanbridge.compat.iris.IrisCompat;
import net.irisshaders.iris.compat.sodium.config.ShaderPackScreenPlaceholder;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * The "Shader Packs" page Iris registers in the Sodium video settings. Signature: {@code javap -p -c
 * net.irisshaders.iris.compat.sodium.config.IrisConfig} (iris 1.11.7+mc26.3): the page consumer is
 * {@code private static void lambda$registerConfigLate$0(boolean, Screen)}; with {@code usingVulkan} it passes
 * {@code new ShaderPackScreenPlaceholder(parent)} to {@code Gui.setScreen}. The name is synthetic: if Iris changes it
 * the injection fails loudly at class load (default require), it is not skipped silently.
 */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.compat.sodium.config.IrisConfig", remap = false)
public abstract class MixinIrisSodiumConfig {
    @ModifyArg(method = "lambda$registerConfigLate$0(ZLnet/minecraft/client/gui/screens/Screen;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/Gui;setScreen(Lnet/minecraft/client/gui/screens/Screen;)V", remap = false),
            remap = false)
    private static Screen vkb$shaderPackPage(Screen screen) {
        if (screen instanceof ShaderPackScreenPlaceholder placeholder) {
            return IrisCompat.replacePlaceholder(placeholder,
                    ((ShaderPackScreenPlaceholderAccessor) placeholder).vkb$getParent(), "sodium_page");
        }
        return screen;
    }
}
