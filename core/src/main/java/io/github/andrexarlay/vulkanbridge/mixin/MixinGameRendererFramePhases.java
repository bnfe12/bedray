package io.github.andrexarlay.vulkanbridge.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import io.github.andrexarlay.vulkanbridge.api.FramePhase;
import io.github.andrexarlay.vulkanbridge.core.FramePhasesImpl;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * the core's frame hooks (ADR-0003): dispatches {@link FramePhase}s through {@link FramePhasesImpl}. Injection points are the ones
 * the RTX mod's MixinGameRenderer and the former MixinGameRendererGlBridge use. Signatures, javap -p / -c on
 * libs/minecraft-26.3-client.jar (26.3, unobfuscated):
 * {@code public void render()}; in it {@code invokevirtual renderLevel:()V}, {@code invokevirtual applyPostEffects:()V},
 * {@code invokevirtual net/minecraft/client/gui/render/GuiRenderer.render:()V};
 * {@code private void render3dHud(CameraRenderState, PlayerRenderState, OptionsRenderState, boolean)} (called from renderLevel);
 * {@code public void renderLevel()} with {@code invokevirtual ProjectionMatrixBuffer.getBuffer:(Lorg/joml/Matrix4f;)Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;};
 * {@code public void resize(int, int)} (recreates mainRenderTarget and hud3DTarget, decompiled GameRenderer.resize);
 * {@code public RenderTarget mainRenderTarget()}; {@code RenderSystem.getDevice()}; {@code Minecraft.getDeltaTracker()}.
 */
@Mixin(GameRenderer.class)
public abstract class MixinGameRendererFramePhases {
    @Inject(method = "render()V", at = @At("HEAD"))
    private void vkbridge$frameBegin(CallbackInfo ci) {
        RenderTarget main = ((GameRenderer) (Object) this).mainRenderTarget();
        float pt = Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(false);
        FramePhasesImpl.INSTANCE.beginFrame(RenderSystem.getDevice(), main.width, main.height, pt);
    }

    @Inject(method = "render()V", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;renderLevel()V"))
    private void vkbridge$beforeLevel(CallbackInfo ci) {
        FramePhasesImpl.INSTANCE.fire(FramePhase.BEFORE_LEVEL);
    }

    @Inject(method = "render()V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;applyPostEffects()V", shift = At.Shift.AFTER))
    private void vkbridge$afterLevelPost(CallbackInfo ci) {
        FramePhasesImpl.INSTANCE.fire(FramePhase.AFTER_LEVEL_POST);
    }

    @Inject(method = "render3dHud(Lnet/minecraft/client/renderer/state/level/CameraRenderState;Lnet/minecraft/client/renderer/state/level/PlayerRenderState;Lnet/minecraft/client/renderer/state/OptionsRenderState;Z)V",
        at = @At("HEAD"))
    private void vkbridge$beforeHud3d(CallbackInfo ci) {
        FramePhasesImpl.INSTANCE.fire(FramePhase.BEFORE_HUD3D);
    }

    @Inject(method = "render()V", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/render/GuiRenderer;render()V"))
    private void vkbridge$beforeGui(CallbackInfo ci) {
        FramePhasesImpl.INSTANCE.fire(FramePhase.BEFORE_GUI);
    }

    @Inject(method = "render()V", at = @At("RETURN"))
    private void vkbridge$frameEnd(CallbackInfo ci) {
        FramePhasesImpl.INSTANCE.fire(FramePhase.FRAME_END);
    }

    @Inject(method = "resize(II)V", at = @At("RETURN"))
    private void vkbridge$mainTargetResized(int width, int height, CallbackInfo ci) {
        FramePhasesImpl.INSTANCE.onMainTargetResized(width, height);
    }

    @ModifyArg(method = "renderLevel()V",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/ProjectionMatrixBuffer;getBuffer(Lorg/joml/Matrix4f;)Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;"),
        index = 0)
    private Matrix4f vkbridge$levelProjection(Matrix4f projection) {
        return FramePhasesImpl.INSTANCE.applyProjection(projection);
    }
}
