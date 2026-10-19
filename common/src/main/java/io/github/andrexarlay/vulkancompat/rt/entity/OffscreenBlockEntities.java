package io.github.andrexarlay.vulkancompat.rt.entity;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.Model;
import net.minecraft.client.renderer.OrderedSubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Set;

/**
 * Block entities (chests, signs, banners, ...) the level did NOT submit this frame because their section is outside the view
 * (Sodium culls them with the section) are extracted and submitted again into a capture-only collector, so they are part of
 * the RT scene (reflections, shadows of off-screen objects). Nothing is drawn by the raster pass for them.
 * APIs (javap 26.3): BlockEntityRenderDispatcher.tryExtractRenderState(E, float, CrumblingOverlay, boolean),
 * submit(S, PoseStack, SubmitNodeCollector, CameraRenderState); BlockEntityRenderState.blockPos; LevelChunk.getBlockEntities().
 */
public final class OffscreenBlockEntities {
    private static final Logger LOGGER = LoggerFactory.getLogger("VK-ENTITY-CAPTURE");
    private static final int RADIUS = Integer.getInteger("vkrtx.rt.blockEntityRadius", 32);
    /** Positions submitted by the level this frame (MixinBlockEntityRenderDispatcher), cleared at beginFrame. */
    private static final Set<BlockPos> submittedThisFrame = new HashSet<>();
    private static int lastAdded;
    private static boolean loggedFailure;

    private OffscreenBlockEntities() {}

    public static synchronized void noteSubmitted(BlockPos pos) {
        if (pos != null) {
            submittedThisFrame.add(pos.immutable());
        }
    }

    public static synchronized void beginFrame() {
        submittedThisFrame.clear();
    }

    public static int lastAdded() {
        return lastAdded;
    }

    /** Called by RtFrameRenderer before the capture is closed (render thread, inside the frame). */
    public static synchronized void captureMissing(CameraRenderState camera) {
        lastAdded = 0;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || camera == null || camera.pos == null) {
            return;
        }
        BlockEntityRenderDispatcher dispatcher = mc.getBlockEntityRenderDispatcher();
        float pt = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        int cx = ((int) Math.floor(camera.pos.x)) >> 4;
        int cz = ((int) Math.floor(camera.pos.z)) >> 4;
        int rc = (RADIUS >> 4) + 1;
        double r2 = (double) RADIUS * RADIUS;
        PoseStack ps = new PoseStack();
        for (int x = cx - rc; x <= cx + rc; x++) {
            for (int z = cz - rc; z <= cz + rc; z++) {
                LevelChunk chunk = mc.level.getChunkSource().getChunk(x, z, net.minecraft.world.level.chunk.status.ChunkStatus.FULL, false);
                if (chunk == null) {
                    continue;
                }
                for (BlockEntity be : chunk.getBlockEntities().values()) {
                    BlockPos p = be.getBlockPos();
                    if (submittedThisFrame.contains(p) || p.distToCenterSqr(camera.pos.x, camera.pos.y, camera.pos.z) > r2) {
                        continue;
                    }
                    try {
                        BlockEntityRenderState state = dispatcher.tryExtractRenderState(be, pt, null, false);
                        if (state == null) {
                            continue;
                        }
                        ps.pushPose();
                        ps.translate(p.getX() - camera.pos.x, p.getY() - camera.pos.y, p.getZ() - camera.pos.z);
                        dispatcher.submit(state, ps, CaptureOnlyCollector.INSTANCE, camera);
                        ps.popPose();
                        lastAdded++;
                    } catch (RuntimeException e) {
                        if (!loggedFailure) {
                            loggedFailure = true;
                            LOGGER.error("[VK-ENTITY-CAPTURE] off-screen block entity capture failed at {}", p, e);
                        }
                    }
                }
            }
        }
    }

    /** Forwards model/item submits to the RT capture and drops everything else (nothing reaches the raster pass). */
    static final class CaptureOnlyCollector implements SubmitNodeCollector {
        static final CaptureOnlyCollector INSTANCE = new CaptureOnlyCollector();

        @Override
        public OrderedSubmitNodeCollector order(int order) {
            return this;
        }

        @Override
        public <S> void submitModel(Model<? super S> model, S state, PoseStack poseStack, RenderType renderType, int light, int overlay, int color,
                                    net.minecraft.client.renderer.texture.UvMapping uvMapping, int outlineColor) {
            EntityCaptureManager.getInstance().captureModel(model, state, poseStack, renderType, light, overlay, color, uvMapping);
        }

        @Override
        public void submitItem(PoseStack poseStack, net.minecraft.world.item.ItemDisplayContext ctx, int light, int overlay, int color, int[] tints,
                               net.minecraft.client.resources.model.geometry.ItemQuads quads,
                               net.minecraft.client.renderer.item.ItemStackRenderState.FoilType foil) {
            EntityCaptureManager.getInstance().captureItem(poseStack, quads, tints, light, overlay);
        }

        @Override public void submitShadow(PoseStack p, float r, java.util.List<net.minecraft.client.renderer.entity.state.EntityRenderState.ShadowPiece> s) { }
        @Override public void submitNameTag(PoseStack p, net.minecraft.world.phys.Vec3 v, int i, net.minecraft.network.chat.Component c, boolean b, int j, CameraRenderState cs) { }
        @Override public void submitText(PoseStack p, float x, float y, net.minecraft.util.FormattedCharSequence t, boolean s, net.minecraft.client.gui.Font.DisplayMode m, int a, int b, int c, int d) {
            EntityCaptureManager.getInstance().captureText(p, x, y, t, a, b, d);
        }
        @Override public void submitTextBackground(PoseStack p, float a, float b, float c, float d, int e, net.minecraft.client.gui.Font.DisplayMode m, int f) { }
        @Override public void submitFlame(PoseStack p, net.minecraft.client.renderer.entity.state.EntityRenderState s, org.joml.Quaternionf q) { }
        @Override public void submitLeash(PoseStack p, net.minecraft.client.renderer.entity.state.EntityRenderState.LeashState l) { }
        @Override public <S> void submitCrumblingOverlay(Model<? super S> m, S s, PoseStack p, RenderType r, int a, int b, int c,
                                                         net.minecraft.client.renderer.feature.ModelFeatureRenderer.CrumblingOverlay o) { }
        @Override public void submitMovingBlock(PoseStack p, net.minecraft.client.renderer.block.MovingBlockRenderState s, int i) { }
        @Override public void submitBlockModel(PoseStack p, RenderType r, java.util.List<net.minecraft.client.renderer.block.dispatch.BlockStateModelPart> parts, int[] t, int a, int b, int c) { }
        @Override public void submitBreakingBlockModel(PoseStack p, java.util.List<net.minecraft.client.renderer.block.dispatch.BlockStateModelPart> parts, int a, boolean b) { }
        @Override public void submitShapeOutline(PoseStack p, net.minecraft.world.phys.shapes.VoxelShape s, RenderType r, int a, float b, boolean c) { }
        @Override public void submitCustomGeometry(PoseStack p, RenderType r, SubmitNodeCollector.CustomGeometryRenderer g) { }
        @Override public void submitQuadParticleGroup(net.minecraft.client.renderer.state.level.QuadParticleRenderState s) { }
        @Override public void submitGizmoPrimitives(net.minecraft.client.renderer.gizmos.DrawableGizmoPrimitives.Group g, CameraRenderState c, boolean b) { }
    }
}
