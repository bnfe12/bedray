package io.github.andrexarlay.vulkanbridge.mixin.iris;

import io.github.andrexarlay.vulkanbridge.compat.iris.IrisCompat;
import net.irisshaders.iris.compat.sodium.config.ShaderPackScreenPlaceholder;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Vulkan-only entry points of Iris. Signatures: {@code javap -p -c net.irisshaders.iris.IrisVKOnly} (iris
 * 1.11.7+mc26.3): {@code public static void run()} (called by the VKOnly_InitKeys mixin of Iris only when Iris runs on
 * Vulkan) and {@code public static void handleKeybinds()}, which passes {@code new ShaderPackScreenPlaceholder(null)}
 * to {@code Gui.setScreen(Lnet/minecraft/client/gui/screens/Screen;)V}.
 */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.IrisVKOnly", remap = false)
public abstract class MixinIrisVKOnly {
    @Inject(method = "run()V", at = @At("RETURN"), remap = false)
    private static void vkb$afterRun(CallbackInfo ci) {
        IrisCompat.onIrisVulkanMode();
    }

    @ModifyArg(method = "handleKeybinds()V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/Gui;setScreen(Lnet/minecraft/client/gui/screens/Screen;)V", remap = false),
            remap = false)
    private static Screen vkb$shaderPackKey(Screen screen) {
        if (screen instanceof ShaderPackScreenPlaceholder placeholder) {
            // Iris passes null as the parent here, exactly like its OpenGL key handler does for ShaderPackScreen.
            return IrisCompat.replacePlaceholder(placeholder,
                    ((ShaderPackScreenPlaceholderAccessor) placeholder).vkb$getParent(), "iris_key");
        }
        return screen;
    }
}
