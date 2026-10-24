package io.github.andrexarlay.vulkancompat.mixin.rt;

import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import io.github.andrexarlay.vulkancompat.rt.entity.EntityCaptureManager;
import io.github.andrexarlay.vulkancompat.rtx.GameRendererHandAccessor;
import io.github.andrexarlay.vulkancompat.rtx.WorldTargetManager;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.Projection;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import net.minecraft.client.renderer.state.GameRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.PlayerRenderState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Marks the first-person hand pass ({@code GameRenderer.renderItemInHand(CameraRenderState, PlayerRenderState, GpuTextureView)},
 * javap 26.3). The pass runs after the RT pass of the frame and draws the hand and its item with the raster pipeline; the item
 * quads it submits are not world geometry, but their glowing pixels light the scene: {@link EntityCaptureManager} turns them
 * into emitter quads (view space) that the RT pass of the next frame uses.
 * <p>
 * The vanilla pass happens inside {@code render3dHud}, i.e. into the low-resolution world target and before
 * {@code applyPostEffects}. Its depth goes to the separate hud3d target and it has no motion vectors, so a temporal upscaler
 * reconstructs it against the world depth and smears it (owner-visible as a huge flat arm). When such an upscaler is active the
 * hand is deferred and replayed at full resolution over the upscaled image with the hud3d projection.
 */
@Mixin(value = GameRenderer.class, remap = false)
public abstract class MixinGameRendererHand implements GameRendererHandAccessor {

    @Shadow
    @Final
    private RenderTarget hud3DTarget;

    @Shadow
    @Final
    private Projection hudProjection;

    @Shadow
    @Final
    private ProjectionMatrixBuffer hud3dProjectionMatrixBuffer;

    @Shadow
    @Final
    private GameRenderState gameRenderState;

    private CameraRenderState vkb$deferredCameraState;
    private PlayerRenderState vkb$deferredPlayerState;

    @Invoker("renderItemInHand")
    protected abstract void vkb$invokeRenderItemInHand(CameraRenderState cameraState, PlayerRenderState playerState,
                                                       GpuTextureView depthTextureView);

    @Inject(method = "renderItemInHand", at = @At("HEAD"), cancellable = true)
    private void vkb$handPassBegin(CameraRenderState cameraState, PlayerRenderState playerState, GpuTextureView depthTextureView,
                                   CallbackInfo ci) {
        if (WorldTargetManager.shouldDeferHand()) {
            this.vkb$deferredCameraState = cameraState;
            this.vkb$deferredPlayerState = playerState;
            ci.cancel();
            return;
        }
        this.vkb$deferredCameraState = null;
        this.vkb$deferredPlayerState = null;
        EntityCaptureManager.getInstance().beginHandPass(cameraState.viewRotationMatrix);
    }

    @Inject(method = "renderItemInHand", at = @At("RETURN"))
    private void vkb$handPassEnd(CameraRenderState cameraState, PlayerRenderState playerState, GpuTextureView depthTextureView,
                                 CallbackInfo ci) {
        EntityCaptureManager.getInstance().endHandPass();
    }

    @Override
    public void vkb$replayDeferredHand() {
        CameraRenderState cameraState = this.vkb$deferredCameraState;
        PlayerRenderState playerState = this.vkb$deferredPlayerState;
        this.vkb$deferredCameraState = null;
        this.vkb$deferredPlayerState = null;
        if (cameraState == null || playerState == null || this.hud3DTarget == null) {
            return;
        }
        // Same setup as render3dHud: the hand is projected with the window aspect, a 0.05 near plane and no sub-pixel jitter.
        int width = this.gameRenderState.windowRenderState.width;
        int height = this.gameRenderState.windowRenderState.height;
        this.hudProjection.setupPerspective(0.05f, cameraState.depthFar, cameraState.hudFov, (float) width, (float) height);
        RenderSystem.setProjectionMatrix(this.hud3dProjectionMatrixBuffer.getBuffer(this.hudProjection), ProjectionType.PERSPECTIVE);
        RenderSystem.getDevice().createCommandEncoder().clearDepthTexture(this.hud3DTarget.getDepthTexture(), 0.0);
        this.vkb$invokeRenderItemInHand(cameraState, playerState, this.hud3DTarget.getDepthTextureView());
    }
}
