package io.github.andrexarlay.vulkancompat.mixin.rt;

import com.mojang.renderpearl.api.textures.GpuTextureView;
import io.github.andrexarlay.vulkancompat.rt.entity.EntityCaptureManager;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.PlayerRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Marks the first-person hand pass ({@code GameRenderer.renderItemInHand(CameraRenderState, PlayerRenderState, GpuTextureView)},
 * javap 26.3). The pass runs after the RT pass of the frame and draws the hand and its item with the raster pipeline; the item
 * quads it submits are not world geometry, but their glowing pixels light the scene: {@link EntityCaptureManager} turns them
 * into emitter quads (view space) that the RT pass of the next frame uses.
 */
@Mixin(value = GameRenderer.class, remap = false)
public abstract class MixinGameRendererHand {
    @Inject(method = "renderItemInHand", at = @At("HEAD"))
    private void vkb$handPassBegin(CameraRenderState cameraState, PlayerRenderState playerState, GpuTextureView depthTextureView,
                                   CallbackInfo ci) {
        EntityCaptureManager.getInstance().beginHandPass(cameraState.viewRotationMatrix);
    }

    @Inject(method = "renderItemInHand", at = @At("RETURN"))
    private void vkb$handPassEnd(CameraRenderState cameraState, PlayerRenderState playerState, GpuTextureView depthTextureView,
                                 CallbackInfo ci) {
        EntityCaptureManager.getInstance().endHandPass();
    }
}
