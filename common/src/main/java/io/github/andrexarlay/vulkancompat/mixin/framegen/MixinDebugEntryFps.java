package io.github.andrexarlay.vulkancompat.mixin.framegen;

import io.github.andrexarlay.vulkancompat.rtx.debug.F3Fps;
import net.minecraft.client.gui.components.debug.DebugEntryFps;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Main F3 FPS line. Minecraft prints {@code Minecraft.getFps()}, which counts only the frames the game renders, so with frame
 * generation the stock line shows the real rate and hides the generated frames. While the frame counters of the runtime show
 * generation, the leading "N fps" becomes "real + gen = shown fps (Nx)" (real white, generated and shown aqua), see {@link F3Fps}.
 * Target: the single {@code addPriorityLine(String)} call of {@code DebugEntryFps.display} (vineflower of the 26.3 client jar,
 * decompiled DebugEntryFps). Without generation the line is returned unchanged.
 */
@Mixin(value = DebugEntryFps.class, remap = false)
public abstract class MixinDebugEntryFps {
    @ModifyArg(
        method = "display",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/components/debug/DebugScreenDisplayer;addPriorityLine(Ljava/lang/String;)V"),
        index = 0
    )
    private String vkb$realAndGeneratedFps(String line) {
        return F3Fps.decorate(line);
    }
}
