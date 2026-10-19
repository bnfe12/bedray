package io.github.andrexarlay.vulkanbridge.mixin;

import io.github.andrexarlay.vulkanbridge.platform.FrameHooks;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Feeds {@link FrameHooks} on Fabric (no Fabric API). Targets (javap -p on libs/minecraft-26.3-client.jar):
 * {@code public void tick()} and {@code public void renderFrame(boolean)} of net.minecraft.client.Minecraft.
 * The tick hook fires at the start of the tick (NeoForge ClientTickEvent.Pre analogue), the frame hook at the end of
 * renderFrame (RenderFrameEvent.Post analogue). Timing parity with NeoForge is not verified without a launch.
 */
@Mixin(Minecraft.class)
public abstract class MixinMinecraftHooks {
    @Inject(method = "tick()V", at = @At("HEAD"))
    private void vkbridge$onTickStart(CallbackInfo ci) {
        FrameHooks.INSTANCE.fireClientTick();
    }

    @Inject(method = "renderFrame(Z)V", at = @At("RETURN"))
    private void vkbridge$onFrameEnd(boolean advanceGameTime, CallbackInfo ci) {
        FrameHooks.INSTANCE.firePostFrame();
    }
}
