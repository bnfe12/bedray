package io.github.andrexarlay.vulkanbridge.mixin.iris;

import com.mojang.renderpearl.backend.opengl.GlStateManager;
import io.github.andrexarlay.vulkanbridge.compat.iris.IrisCompat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * {@code OldImageButton.renderTexture(GuiGraphicsExtractor, Identifier, int, int, int, int, int, int, int, int, int)}
 * calls {@code GlStateManager._enableDepthTest()V} directly (javap -p -c, iris 1.11.7+mc26.3); same reason and rule as
 * {@link MixinIrisButtonGlState}. The button is the show/hide toggle of ShaderPackScreen.
 */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.gui.OldImageButton", remap = false)
public abstract class MixinOldImageButtonGlState {
    @Redirect(method = "renderTexture",
            at = @At(value = "INVOKE", target = "Lcom/mojang/renderpearl/backend/opengl/GlStateManager;_enableDepthTest()V", remap = false),
            remap = false)
    private void vkb$enableDepthTest() {
        if (!IrisCompat.skipGlState("OldImageButton._enableDepthTest")) {
            GlStateManager._enableDepthTest();
        }
    }
}
