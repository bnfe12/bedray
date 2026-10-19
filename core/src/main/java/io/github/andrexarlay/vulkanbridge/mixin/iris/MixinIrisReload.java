package io.github.andrexarlay.vulkanbridge.mixin.iris;

import io.github.andrexarlay.vulkanbridge.compat.iris.IrisCompat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * {@code public static void reload() throws IOException} of {@code net.irisshaders.iris.Iris} (javap -p -c, iris
 * 1.11.7+mc26.3) calls {@code getPipelineManager().destroyPipeline()}; the pipeline manager is created only by the
 * OpenGL-mode code of Iris, so on Vulkan that is a NullPointerException (ShaderPackScreen "Apply"/"Done" ->
 * IrisApiV0ConfigImpl.setShadersEnabledAndApply -> Iris.reload). On Vulkan the reload is skipped with a WARN line
 * (see IrisCompat.onReload); on OpenGL nothing changes.
 */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.Iris", remap = false)
public abstract class MixinIrisReload {
    @Inject(method = "reload()V", at = @At("HEAD"), cancellable = true, remap = false)
    private static void vkb$reloadOnVulkan(CallbackInfo ci) {
        if (IrisCompat.onReload()) {
            ci.cancel();
        }
    }
}
