package io.github.andrexarlay.vulkancompat.mixin.rt;

import com.mojang.blaze3d.vertex.PoseStack;
import io.github.andrexarlay.vulkancompat.rt.entity.EntityCaptureManager;
import io.github.andrexarlay.vulkancompat.rt.entity.EntityGeometryFrame;
import io.github.andrexarlay.vulkancompat.rt.entity.OffscreenBlockEntities;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Groups the submits of one block entity into one captured RT entity (stable type/position for motion matching) and records
 * which positions the level submitted this frame (the rest are added off-screen by OffscreenBlockEntities).
 * Signature (javap 26.3): {@code public <S extends BlockEntityRenderState> void submit(S, PoseStack, SubmitNodeCollector, CameraRenderState)}.
 */
@Mixin(value = BlockEntityRenderDispatcher.class, remap = false)
public class MixinBlockEntityRenderDispatcher {
    @Inject(method = "submit", at = @At("HEAD"))
    private <S extends BlockEntityRenderState> void vkb$beginBlockEntity(S state, PoseStack poseStack, SubmitNodeCollector collector,
                                                                          CameraRenderState camera, CallbackInfo ci) {
        if (state == null || poseStack == null) {
            return;
        }
        OffscreenBlockEntities.noteSubmitted(state.blockPos);
        String type = state.blockEntityType != null ? String.valueOf(net.minecraft.core.registries.BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(state.blockEntityType)) : "block_entity";
        EntityCaptureManager.getInstance().beginEntity(state.blockPos != null ? state.blockPos.hashCode() : -1, "be:" + type,
            poseStack.last().pose(), EntityGeometryFrame.CapturedEntity.FLAG_BLOCK_ENTITY);
    }

    @Inject(method = "submit", at = @At("RETURN"))
    private <S extends BlockEntityRenderState> void vkb$endBlockEntity(S state, PoseStack poseStack, SubmitNodeCollector collector,
                                                                        CameraRenderState camera, CallbackInfo ci) {
        EntityCaptureManager.getInstance().endEntity();
    }
}
