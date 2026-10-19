package io.github.andrexarlay.vulkancompat.mixin.rt;

import com.mojang.blaze3d.vertex.PoseStack;
import io.github.andrexarlay.vulkancompat.rt.entity.EntityCaptureManager;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin into EntityRenderDispatcher to intercept entity rendering for geometry capture.
 */
@Mixin(EntityRenderDispatcher.class)
public class MixinEntityRenderDispatcher {

    @Inject(method = "submit", at = @At("HEAD"))
    private <S extends EntityRenderState> void onEntitySubmitStart(
            S renderState, CameraRenderState cameraRenderState,
            double x, double y, double z,
            PoseStack poseStack, SubmitNodeCollector collector,
            CallbackInfo ci) {
        if (renderState == null) {
            return;
        }
        int entityId = System.identityHashCode(renderState);
        String type = renderState.entityType != null ? renderState.entityType.toString() : "minecraft:unknown";
        Matrix4f transform = poseStack != null ? poseStack.last().pose() : new Matrix4f().identity();
        int flags = 0;
        if (renderState instanceof net.minecraft.client.renderer.entity.state.LivingEntityRenderState living) {
            flags |= io.github.andrexarlay.vulkancompat.rt.entity.EntityGeometryFrame.CapturedEntity.FLAG_LIVING;
            if (living.hasRedOverlay) {
                flags |= 0x40;
            }
        }
        if (renderState.appearsGlowing()) {
            flags |= 0x10;
        }
        if (renderState.displayFireAnimation) {
            flags |= 0x20;
        }
        EntityCaptureManager.getInstance().beginEntity(entityId, type, transform, flags);
    }

    @Inject(method = "submit", at = @At("RETURN"))
    private <S extends EntityRenderState> void onEntitySubmitEnd(
            S renderState, CameraRenderState cameraRenderState,
            double x, double y, double z,
            PoseStack poseStack, SubmitNodeCollector collector,
            CallbackInfo ci) {
        EntityCaptureManager.getInstance().endEntity();
    }

    /**
     * Off-screen entities for ray tracing: with RT on, an entity that the view frustum culled but lies within
     * {@code vkrtx.rt.entityRadius} blocks (default 32) of the camera is still extracted/submitted, so it is in the RT scene
     * (reflections, shadows of objects outside the view). The raster pass draws it too (clipped, off-screen).
     * Signature (javap 26.3): {@code public <E extends Entity> boolean shouldRender(E, Frustum, double, double, double, float)}.
     */
    @org.spongepowered.asm.mixin.injection.Inject(method = "shouldRender", at = @At("RETURN"), cancellable = true)
    private <E extends net.minecraft.world.entity.Entity> void vkb$keepOffscreenForRt(E entity, net.minecraft.client.renderer.culling.Frustum frustum,
            double camX, double camY, double camZ, float partialTick,
            org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ() || !io.github.andrexarlay.vulkancompat.rt.sodium.RtGeometryGate.enabled()) {
            return;
        }
        double r = RT_ENTITY_RADIUS;
        if (entity.distanceToSqr(camX, camY, camZ) <= r * r) {
            cir.setReturnValue(true);
        }
    }

    private static final double RT_ENTITY_RADIUS = Double.parseDouble(System.getProperty("vkrtx.rt.entityRadius", "32"));
}
