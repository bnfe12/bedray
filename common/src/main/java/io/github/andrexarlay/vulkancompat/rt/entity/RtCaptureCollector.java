package io.github.andrexarlay.vulkancompat.rt.entity;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.Font;
import net.minecraft.client.model.Model;
import net.minecraft.client.renderer.OrderedSubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.MovingBlockRenderState;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.gizmos.DrawableGizmoPrimitives;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.QuadParticleRenderState;
import net.minecraft.client.renderer.texture.UvMapping;
import net.minecraft.client.resources.model.geometry.ItemQuads;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Quaternionf;

import java.util.List;

/**
 * A {@link SubmitNodeCollector} that draws nothing and keeps only the model submits, forwarding them to
 * {@link EntityCaptureManager}. Re-submitting an entity render state with it runs the vanilla renderer (pose, rotations,
 * model setup) without producing anything on screen, so geometry that the raster pass never draws - the local player's own
 * model under a first-person camera - still reaches the RT entity geometry and its reflections.
 * <p>
 * Every other submit (text, name tags, flames, leashes, items, shadows, particles) is dropped: those are not entity model
 * geometry, and the emitter/item capture of the raster pass already handles the ones that are.
 */
public final class RtCaptureCollector implements SubmitNodeCollector {

    public static final RtCaptureCollector INSTANCE = new RtCaptureCollector();

    private RtCaptureCollector() {
    }

    /**
     * Water mask render types draw the vanilla water surface for the game's own effects and must never become entity
     * geometry (also true for a null render type: nothing usable to sample).
     */
    public static boolean isWaterMask(RenderType renderType) {
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

    @Override
    public OrderedSubmitNodeCollector order(int order) {
        return this;
    }

    @Override
    public <S> void submitModel(Model<? super S> model, S state, PoseStack poseStack, RenderType renderType, int light,
                                int overlay, int color, UvMapping uvMapping, int flags) {
        if (isWaterMask(renderType)) {
            return;
        }
        EntityCaptureManager.getInstance().captureModel(model, state, poseStack, renderType, light, overlay, color, uvMapping);
    }

    @Override
    public void submitShadow(PoseStack poseStack, float radius, List<EntityRenderState.ShadowPiece> pieces) {
    }

    @Override
    public void submitNameTag(PoseStack poseStack, Vec3 attachment, int offsetY, Component name, boolean discrete,
                              int light, CameraRenderState cameraState) {
    }

    @Override
    public void submitText(PoseStack poseStack, float x, float y, FormattedCharSequence text, boolean dropShadow,
                           Font.DisplayMode displayMode, int light, int color, int backgroundColor, int outlineColor) {
    }

    @Override
    public void submitTextBackground(PoseStack poseStack, float x, float y, float width, float height,
                                     int backgroundColor, Font.DisplayMode displayMode, int light) {
    }

    @Override
    public void submitFlame(PoseStack poseStack, EntityRenderState state, Quaternionf rotation) {
    }

    @Override
    public void submitLeash(PoseStack poseStack, EntityRenderState.LeashState leashState) {
    }

    @Override
    public <S> void submitCrumblingOverlay(Model<? super S> model, S state, PoseStack poseStack, RenderType renderType,
                                           int light, int overlay, int color, ModelFeatureRenderer.CrumblingOverlay crumbling) {
    }

    @Override
    public void submitMovingBlock(PoseStack poseStack, MovingBlockRenderState state, int outlineColor) {
    }

    @Override
    public void submitBlockModel(PoseStack poseStack, RenderType renderType, List<BlockStateModelPart> parts,
                                 int[] tintLayers, int light, int overlay, int outlineColor) {
    }

    @Override
    public void submitBreakingBlockModel(PoseStack poseStack, List<BlockStateModelPart> parts, int stage,
                                         boolean isBreakingOverlay) {
    }

    @Override
    public void submitShapeOutline(PoseStack poseStack, VoxelShape shape, RenderType renderType, int color,
                                   float lineWidth, boolean isTranslucent) {
    }

    @Override
    public void submitItem(PoseStack poseStack, ItemDisplayContext context, int light, int overlay, int color,
                           int[] tintLayers, ItemQuads quads, ItemStackRenderState.FoilType foilType) {
    }

    @Override
    public void submitCustomGeometry(PoseStack poseStack, RenderType renderType, CustomGeometryRenderer renderer) {
    }

    @Override
    public void submitQuadParticleGroup(QuadParticleRenderState state) {
    }

    @Override
    public void submitGizmoPrimitives(DrawableGizmoPrimitives.Group group, CameraRenderState cameraState, boolean flag) {
    }
}
