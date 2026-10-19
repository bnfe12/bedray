package io.github.andrexarlay.vulkanbridge.mixin.iris;

import com.mojang.renderpearl.backend.opengl.GlStateManager;
import io.github.andrexarlay.vulkanbridge.compat.iris.IrisCompat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * {@code IrisButton.extractContents(GuiGraphicsExtractor, int, int, float)} calls {@code GlStateManager._enableBlend(I)V}
 * and {@code GlStateManager._enableDepthTest()V} directly (javap -p -c, iris 1.11.7+mc26.3). {@code _enableBlend}
 * reaches {@code GL33C.glEnablei} (javap of minecraft-26.3-client.jar), which needs a current GL context; on the
 * Vulkan backend the render thread has none. The widget draw itself goes through the GUI render pipelines, which carry
 * their own blend state, so on Vulkan the GL calls are skipped. On OpenGL the original call is made.
 * IrisButton is used for Done/Apply/Cancel/Open folder/Shader options of ShaderPackScreen.
 */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.gui.element.screen.IrisButton", remap = false)
public abstract class MixinIrisButtonGlState {
    @Redirect(method = "extractContents",
            at = @At(value = "INVOKE", target = "Lcom/mojang/renderpearl/backend/opengl/GlStateManager;_enableBlend(I)V", remap = false),
            remap = false)
    private void vkb$enableBlend(int index) {
        if (!IrisCompat.skipGlState("IrisButton._enableBlend")) {
            GlStateManager._enableBlend(index);
        }
    }

    @Redirect(method = "extractContents",
            at = @At(value = "INVOKE", target = "Lcom/mojang/renderpearl/backend/opengl/GlStateManager;_enableDepthTest()V", remap = false),
            remap = false)
    private void vkb$enableDepthTest() {
        if (!IrisCompat.skipGlState("IrisButton._enableDepthTest")) {
            GlStateManager._enableDepthTest();
        }
    }
}
