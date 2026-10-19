package io.github.andrexarlay.vulkancompat.mixin.rt;

import com.mojang.blaze3d.vertex.PoseStack;
import io.github.andrexarlay.vulkancompat.rt.entity.EntityCaptureManager;
import net.minecraft.client.model.Model;
import net.minecraft.client.renderer.SubmitNodeCollection;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.UvMapping;
import net.minecraft.client.resources.model.geometry.ItemQuads;
import net.minecraft.world.item.ItemDisplayContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin into SubmitNodeCollection to intercept model and item submission for RT entity geometry capture.
 */
@Mixin(SubmitNodeCollection.class)
public class MixinSubmitNodeCollection {

    private static boolean isWaterMask(RenderType renderType) {
        if (renderType == null) {
            return true;
        }
        String s = renderType.toString();
        if (s.contains("water_mask") || s.contains("waterMask") || s.toLowerCase(java.util.Locale.ROOT).contains("watermask")) {
            return true;
        }
        try {
            if (renderType.equals(RenderTypes.waterMask())) {
                return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    @Inject(method = "submitModel", at = @At("HEAD"))
    private <S> void onModelSubmitted(
            Model<? super S> model, S state,
            PoseStack poseStack, RenderType renderType,
            int light, int overlay, int color,
            UvMapping uvMapping, int flags,
            CallbackInfo ci) {
        if (isWaterMask(renderType)) {
            return;
        }
        EntityCaptureManager.getInstance().captureModel(model, state, poseStack, renderType, light, overlay, color, uvMapping);
    }

    /** True while the full-light resubmit of an emissive hand item runs (it must not be captured twice). */
    private static boolean vkb$resubmit;

    @Inject(method = "submitItem", at = @At("HEAD"), cancellable = true)
    private void onItemSubmitted(
            PoseStack poseStack, ItemDisplayContext itemDisplayContext,
            int light, int overlay, int color, int[] tintColors,
            ItemQuads itemQuads, ItemStackRenderState.FoilType foilType,
            CallbackInfo ci) {
        if (vkb$resubmit) return;

        EntityCaptureManager capture = EntityCaptureManager.getInstance();
        EntityCaptureManager.HandItemSplit split = capture.captureHandItem(poseStack, itemQuads, tintColors);
        if (split != null) {
            if (!split.emissive().isEmpty()) {
                // The two subsets are disjoint: base quads retain vanilla light, while only emitter-eligible quads get
                // full light. Keeping the original foil type on both subsets applies each quad's foil exactly once.
                vkb$resubmit = true;
                try {
                    if (!split.material().isEmpty()) {
                        ((SubmitNodeCollection) (Object) this).submitItem(poseStack, itemDisplayContext, light, overlay, color,
                            tintColors, split.material(), foilType);
                    }
                    ((SubmitNodeCollection) (Object) this).submitItem(poseStack, itemDisplayContext, HAND_FULL_LIGHT, overlay, color,
                        tintColors, split.emissive(), foilType);
                } finally {
                    vkb$resubmit = false;
                }
                ci.cancel();
            }
            return;
        }
        capture.captureItem(poseStack, itemQuads, tintColors, light, overlay);
    }

    /** Packed light coordinates block 15 / sky 15 (LightCoordsUtil.FULL_BRIGHT = 0xF000F0). */
    private static final int HAND_FULL_LIGHT = 0xF000F0;

    /** Text of signs etc. (javap 26.3: submitText(PoseStack, float, float, FormattedCharSequence, boolean, Font$DisplayMode, int light, int color, int bg, int outline)). */
    @Inject(method = "submitText", at = @At("HEAD"))
    private void onTextSubmitted(PoseStack poseStack, float x, float y, net.minecraft.util.FormattedCharSequence text, boolean dropShadow,
                                 net.minecraft.client.gui.Font.DisplayMode mode, int light, int color, int background, int outline, CallbackInfo ci) {
        EntityCaptureManager.getInstance().captureText(poseStack, x, y, text, light, color, outline);
    }

    /** Capture only known emissive sprite quads for NEE; vanilla still submits and renders the particles. */
    @Inject(method = "submitQuadParticleGroup", at = @At("HEAD"))
    private void onQuadParticleGroupSubmitted(net.minecraft.client.renderer.state.level.QuadParticleRenderState state, CallbackInfo ci) {
        EntityCaptureManager.getInstance().captureParticles(state);
    }

    /** Block breaking crumbling animation quads (destroy stages 0..9) intercepted for RT BLAS/TLAS. */
    @Inject(method = "submitBreakingBlockModel", at = @At("HEAD"))
    private void onBreakingBlockModelSubmitted(PoseStack poseStack, java.util.List<net.minecraft.client.renderer.block.dispatch.BlockStateModelPart> parts,
                                               int stage, boolean isBreakingOverlay, CallbackInfo ci) {
        EntityCaptureManager.getInstance().captureBreakingBlock(poseStack, parts, stage);
    }

    /** Block models submitted outside chunk terrain (Item Frame wooden/glow frames, falling blocks, TNT, displays, etc.). */
    @Inject(method = "submitBlockModel", at = @At("HEAD"))
    private void onBlockModelSubmitted(PoseStack poseStack, RenderType renderType,
                                       java.util.List<net.minecraft.client.renderer.block.dispatch.BlockStateModelPart> parts,
                                       int[] tintLayers, int light, int overlay, int outlineColor, CallbackInfo ci) {
        if (renderType != null && renderType.isOutline()) {
            return;
        }
        EntityCaptureManager.getInstance().captureBlockModel(poseStack, renderType, parts, tintLayers, light, overlay);
    }

    /** Piston moving blocks intercepted for RT BLAS/TLAS. */
    @Inject(method = "submitMovingBlock", at = @At("HEAD"))
    private void onMovingBlockSubmitted(PoseStack poseStack, net.minecraft.client.renderer.block.MovingBlockRenderState state,
                                        int outlineColor, CallbackInfo ci) {
        EntityCaptureManager.getInstance().captureMovingBlock(poseStack, state);
    }

    /** Custom geometry (Paintings, Activated End Portals, End Gateways, Maps in Item Frames, etc.) intercepted for RT BLAS/TLAS. */
    @Inject(method = "submitCustomGeometry", at = @At("HEAD"))
    private void onCustomGeometrySubmitted(PoseStack poseStack, RenderType renderType,
                                           net.minecraft.client.renderer.SubmitNodeCollector.CustomGeometryRenderer renderer,
                                           CallbackInfo ci) {
        if (isWaterMask(renderType) || (renderType != null && renderType.isOutline())) {
            return;
        }
        EntityCaptureManager.getInstance().captureCustomGeometry(poseStack, renderType, renderer);
    }

    /** Block selection wireframe outline intercepted so targeted blocks highlight cleanly in RT mode. */
    @Inject(method = "submitShapeOutline", at = @At("HEAD"))
    private void onShapeOutlineSubmitted(PoseStack poseStack, net.minecraft.world.phys.shapes.VoxelShape shape,
                                         RenderType renderType, int color, float width, boolean isTranslucent, CallbackInfo ci) {
        EntityCaptureManager.getInstance().captureShapeOutline(poseStack, shape, color, width);
    }
}
