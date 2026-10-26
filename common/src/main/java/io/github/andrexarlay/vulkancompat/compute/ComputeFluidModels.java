package io.github.andrexarlay.vulkancompat.compute;

import com.mojang.blaze3d.vertex.VertexConsumer;
import io.github.andrexarlay.bedray.api.v1.BlockModelGeometry;
import io.github.andrexarlay.bedray.api.v1.ModelQuad;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.block.FluidModel;
import net.minecraft.client.renderer.block.FluidRenderer;
import net.minecraft.client.renderer.block.FluidStateModelSet;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

/** Reuses vanilla surface heights, flow UVs and waterlogged visibility, without its baked vertex lighting. */
final class ComputeFluidModels {
    private final FluidStateModelSet models;
    private final FluidRenderer renderer;
    private final Collector collector = new Collector();

    ComputeFluidModels(FluidStateModelSet models) {
        this.models = models;
        this.renderer = models == null ? null : new FluidRenderer(models);
    }

    /** Resolved opaque game tint of the fluid surface, white when the fluid model has no tint source. */
    int tint(ClientLevel level, BlockPos position, BlockState state) {
        FluidModel model = models == null ? null : models.get(state.getFluidState());
        return model == null || model.tintSource() == null ? 0xFFFFFFFF
            : model.tintSource().colorInWorld(state, level, position) | 0xFF000000;
    }

    /** The caller must establish that every surrounding chunk read by vanilla tessellation is loaded.
     * {@code tint} is baked into every quad; {@code extraFlags} may add ModelQuad.CELL_TINT. */
    boolean append(ClientLevel level, BlockPos position, BlockState state, List<ModelQuad> quads, int tint, int extraFlags) {
        if (renderer == null) return false;
        FluidModel model = models.get(state.getFluidState());
        if (model == null || !blockAtlas(model.stillMaterial()) || !blockAtlas(model.flowingMaterial())
            || model.overlayMaterial() != null && !blockAtlas(model.overlayMaterial())) return false;
        collector.begin(position, quads, tint, Math.clamp(state.getLightEmission(), 0, 15), extraFlags);
        try {
            renderer.tesselate(level, position, collector, state, state.getFluidState());
            return collector.finish();
        } finally {
            collector.release();
        }
    }

    private static boolean blockAtlas(Material.Baked material) {
        return material != null && material.sprite() != null
            && TextureAtlas.LOCATION_BLOCKS.equals(material.sprite().atlasLocation());
    }

    private static final class Collector implements FluidRenderer.Output, VertexConsumer {
        private final float[] vertices = new float[20];
        private List<ModelQuad> quads;
        private int originX, originY, originZ, tint, emission, flags, extraFlags, count;
        private boolean failed;

        void begin(BlockPos position, List<ModelQuad> quads, int tint, int emission, int extraFlags) {
            originX = position.getX() & 15; originY = position.getY() & 15; originZ = position.getZ() & 15;
            this.quads = quads; this.tint = tint; this.emission = emission; this.extraFlags = extraFlags;
            count = 0; flags = 0; failed = false;
        }

        @Override public VertexConsumer getBuilder(ChunkSectionLayer layer) {
            if (count != 0) flush();
            if (layer == null) failed = true;
            flags = ModelQuad.FLUID | (layer == ChunkSectionLayer.TRANSLUCENT ? ModelQuad.TRANSLUCENT
                : layer == ChunkSectionLayer.CUTOUT ? ModelQuad.CUTOUT : 0);
            return this;
        }

        @Override public VertexConsumer addVertex(float x, float y, float z) {
            if (count == 4) flush();
            if (failed) return this;
            if (quads.size() >= BlockModelGeometry.MAX_QUADS) { failed = true; return this; }
            vertices[count*5] = x-originX;
            vertices[count*5+1] = y-originY;
            vertices[count*5+2] = z-originZ;
            // Each vertex must receive its own UV before the next quad can be accepted.
            vertices[count*5+3] = Float.NaN; vertices[count*5+4] = Float.NaN;
            count++;
            return this;
        }

        @Override public VertexConsumer setUv(float u, float v) {
            if (count == 0 || failed) return this;
            vertices[(count-1)*5+3] = u; vertices[(count-1)*5+4] = v;
            return this;
        }

        private void flush() {
            if (count == 0 || failed) return;
            if (count != 4 || quads.size() >= BlockModelGeometry.MAX_QUADS) { failed = true; return; }
            for (int i = 0; i < vertices.length; i++) {
                float value = vertices[i];
                if (!Float.isFinite(value) || value < -0.00001f || value > 1.00001f) { failed = true; return; }
                vertices[i] = Math.clamp(value, 0f, 1f);
            }
            quads.add(new ModelQuad(vertices, tint, flags | extraFlags, emission));
            count = 0;
        }

        boolean finish() { flush(); return !failed; }
        void release() { quads = null; count = 0; }

        // Vanilla colors contain cardinal lighting; the model's independent tint is the albedo input.
        @Override public VertexConsumer setColor(int red, int green, int blue, int alpha) { return this; }
        @Override public VertexConsumer setColor(int color) { return this; }
        @Override public VertexConsumer setUv1(int u, int v) { return this; }
        @Override public VertexConsumer setUv2(int u, int v) { return this; }
        @Override public VertexConsumer setUv3(float u, float v) { return this; }
        @Override public VertexConsumer setNormal(float x, float y, float z) { return this; }
        @Override public VertexConsumer setLineWidth(float width) { return this; }
    }
}
