package io.github.andrexarlay.vulkancompat.compute;

import io.github.andrexarlay.bedray.api.v1.BlockModelGeometry;
import io.github.andrexarlay.bedray.api.v1.ModelQuad;
import io.github.andrexarlay.bedray.api.v1.SectionModelGeometry;
import io.github.andrexarlay.bedray.api.v1.SectionSnapshot;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Exact bounded baked models. Unsupported inputs remain explicit rather than becoming cubes. */
final class ComputeModelMaterials {
    private static final int WHITE = 0xFFFFFFFF;
    private static final int MAX_VARIANTS = 1024;
    private static final float EPSILON = 0.00001f;
    private static final Direction[] DIRECTIONS = {Direction.DOWN, Direction.UP, Direction.NORTH,
        Direction.SOUTH, Direction.WEST, Direction.EAST};
    private static final Template UNSUPPORTED = new Template(List.of());
    private static final Template EMPTY = new Template(List.of());

    private record QuadTemplate(float[] vertices, int tintIndex, int flags, int emission, int group) {}
    private record Template(List<QuadTemplate> quads) {}

    static final class SectionCache {
        final BlockState[] states = new BlockState[SectionSnapshot.CELL_COUNT];
        final Template[] templates = new Template[SectionSnapshot.CELL_COUNT];
        Map<AppearanceKey, BlockModelGeometry> appearances = Map.of();
        Map<BlockModelGeometry, BlockModelGeometry> models = Map.of();
    }

    /** Mutable lookup keys are never inserted into a cache. */
    private static final class AppearanceKey {
        Template template;
        int visible, emission, hash;
        int[] colors;
        AppearanceKey(int[] colors) { this.colors = colors; }
        void set(Template template, int visible, int emission) {
            this.template = template; this.visible = visible; this.emission = emission;
            hash = 31 * (31 * (31 * System.identityHashCode(template) + visible) + emission) + Arrays.hashCode(colors);
        }
        AppearanceKey copy() {
            AppearanceKey copy = new AppearanceKey(colors.clone());
            copy.set(template, visible, emission);
            return copy;
        }
        @Override public int hashCode() { return hash; }
        @Override public boolean equals(Object other) {
            return this == other || other instanceof AppearanceKey that && template == that.template
                && visible == that.visible && emission == that.emission && Arrays.equals(colors, that.colors);
        }
    }

    private final Map<List<BlockStateModelPart>, Template> variants = new LinkedHashMap<>(128, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<List<BlockStateModelPart>, Template> eldest) {
            return size() > MAX_VARIANTS;
        }
    };
    private final List<BlockStateModelPart> parts = new ArrayList<>();
    private final RandomSource random = RandomSource.create(0);
    // Per-quad baked colors; the final slot records whether the tinted quads moved to the cell tint.
    private final int[] colors = new int[BlockModelGeometry.MAX_QUADS + 1];
    private final boolean[] cellQuads = new boolean[BlockModelGeometry.MAX_QUADS];
    private final AppearanceKey lookup = new AppearanceKey(colors);
    private final BlockPos.MutableBlockPos neighborPosition = new BlockPos.MutableBlockPos();
    private Minecraft minecraft;
    private ClientLevel level;
    private BlockStateModelSet models;
    private long generation;
    private ComputeFluidModels fluids;

    void prepare(Minecraft minecraft, ClientLevel level, BlockStateModelSet models, long generation) {
        this.minecraft = minecraft; this.level = level; this.models = models; this.generation = generation;
        fluids = new ComputeFluidModels(minecraft.getModelManager().getFluidStateModelSet());
    }

    void clear() {
        variants.clear(); parts.clear(); minecraft = null; level = null; models = null; fluids = null;
        generation = 0;
    }

    /** {@code cellTints} is null unless the provider accepts API minor 5 cell tint; written for MODEL cells only. */
    SectionCapture begin(SectionCache cache, int[] cellTints) { return new SectionCapture(cache, cellTints); }

    final class SectionCapture {
        private final SectionCache cache;
        private final short[] references = new short[SectionSnapshot.CELL_COUNT];
        private final List<BlockModelGeometry> palette = new ArrayList<>();
        private final Map<BlockModelGeometry, Integer> indices = new IdentityHashMap<>();
        private final Map<BlockModelGeometry, BlockModelGeometry> canonical = new LinkedHashMap<>();
        private final Map<AppearanceKey, BlockModelGeometry> appearances = new LinkedHashMap<>();
        private final int[] cellTints;

        private SectionCapture(SectionCache cache, int[] cellTints) { this.cache = cache; this.cellTints = cellTints; }

        byte captureCell(BlockState state, BlockPos position, int index) {
            Template template = cache.templates[index];
            if (cache.states[index] != state || template == null) {
                template = template(state, position);
                cache.states[index] = state;
                cache.templates[index] = template;
            }
            if (template == UNSUPPORTED) return SectionSnapshot.UNSUPPORTED;
            boolean fluid = !state.getFluidState().isEmpty();
            if (fluid && !fluidNeighborsLoaded(position)) return SectionSnapshot.UNSUPPORTED;
            int visible = visibility(state, position, template);
            Arrays.fill(colors, WHITE);
            Arrays.fill(cellQuads, false);
            var tintSources = minecraft.getBlockColors().getTintSources(state);
            int cellColor = 0;
            boolean conflict = false;
            for (int i = 0; i < template.quads.size(); i++) {
                int tint = template.quads.get(i).tintIndex;
                if (tint >= 0 && tintSources != null && tint < tintSources.size() && tintSources.get(tint) != null) {
                    colors[i] = tintSources.get(tint).colorInWorld(state, level, position) | 0xFF000000;
                    cellQuads[i] = true;
                    if (cellColor == 0) cellColor = colors[i];
                    else if (cellColor != colors[i]) conflict = true;
                }
            }
            // One cell color can replace the per-position biome tint only when all tinted quads agree.
            boolean useCell = cellTints != null && cellColor != 0 && !conflict;
            if (useCell) {
                for (int i = 0; i < template.quads.size(); i++) if (cellQuads[i]) colors[i] = WHITE;
            } else {
                Arrays.fill(cellQuads, false);
                cellColor = 0;
            }
            colors[BlockModelGeometry.MAX_QUADS] = useCell ? 1 : 0;
            int fluidTint = fluid ? fluids.tint(level, position, state) : WHITE;
            boolean fluidCell = fluid && cellTints != null && (cellColor == 0 || cellColor == fluidTint);
            if (fluidCell) cellColor = fluidTint;
            int emission = Math.clamp(state.getLightEmission(), 0, 15);
            lookup.set(template, visible, emission);
            BlockModelGeometry appearance = fluid ? null : appearances.get(lookup);
            if (!fluid && appearance == null) appearance = cache.appearances.get(lookup);
            boolean newAppearance = appearance == null;
            if (newAppearance) {
                // Preserve known cached appearances at capacity, but do not allocate/tessellate thousands
                // of unseen candidates that cannot enter this section's bounded palette.
                if (palette.size() >= SectionModelGeometry.MAX_MODELS) return SectionSnapshot.UNSUPPORTED;
                List<ModelQuad> quads = new ArrayList<>(template.quads.size() + (fluid ? 12 : 0));
                for (int i = 0; i < template.quads.size(); i++) {
                    QuadTemplate quad = template.quads.get(i);
                    if (quad.group < 6 && (visible & (1 << quad.group)) == 0) continue;
                    quads.add(new ModelQuad(quad.vertices, colors[i], quad.flags | (cellQuads[i] ? ModelQuad.CELL_TINT : 0),
                        Math.max(quad.emission, emission)));
                }
                if (fluid && !fluids.append(level, position, state, quads, fluidCell ? WHITE : fluidTint,
                    fluidCell ? ModelQuad.CELL_TINT : 0)) return SectionSnapshot.UNSUPPORTED;
                if (quads.isEmpty()) return SectionSnapshot.EMPTY;
                if (quads.size() > BlockModelGeometry.MAX_QUADS) return SectionSnapshot.UNSUPPORTED;
                appearance = new BlockModelGeometry(quads);
                BlockModelGeometry previous = canonical.get(appearance);
                if (previous == null) previous = cache.models.get(appearance);
                if (previous != null) appearance = previous;
            }
            Integer reference = indices.get(appearance);
            if (reference == null) {
                if (palette.size() >= SectionModelGeometry.MAX_MODELS) return SectionSnapshot.UNSUPPORTED;
                reference = palette.size() + 1;
                indices.put(appearance, reference);
                palette.add(appearance);
                canonical.put(appearance, appearance);
            }
            if (!fluid && appearances.size() < SectionModelGeometry.MAX_MODELS && !appearances.containsKey(lookup))
                appearances.put(lookup.copy(), appearance);
            references[index] = reference.shortValue();
            if (cellTints != null) cellTints[index] = cellColor;
            return SectionSnapshot.MODEL;
        }

        SectionModelGeometry finish() {
            cache.appearances = appearances;
            cache.models = canonical;
            return new SectionModelGeometry(generation, palette, references);
        }
    }

    private Template template(BlockState state, BlockPos position) {
        if (state.hasOffsetFunction()) return UNSUPPORTED;
        if (state.getRenderShape() != RenderShape.MODEL) return state.getFluidState().isEmpty() ? UNSUPPORTED : EMPTY;
        var model = models.get(state);
        if (model == null || model == models.missingModel()) return UNSUPPORTED;
        parts.clear();
        random.setSeed(state.getSeed(position));
        model.collectParts(random, parts);
        for (BlockStateModelPart part : parts) if (part == null) return UNSUPPORTED;
        Template cached = variants.get(parts);
        if (cached != null) return cached;
        List<QuadTemplate> quads = new ArrayList<>();
        boolean valid = true;
        outer: for (BlockStateModelPart part : parts) for (int group = 0; group <= 6; group++) {
            var groupQuads = part.getQuads(group == 6 ? null : DIRECTIONS[group]);
            if (groupQuads == null) { valid = false; break outer; }
            for (BakedQuad quad : groupQuads) {
                if (quad == null || quads.size() == BlockModelGeometry.MAX_QUADS) { valid = false; break outer; }
                QuadTemplate extracted = extract(quad, group);
                if (extracted == null) { valid = false; break outer; }
                quads.add(extracted);
            }
        }
        Template result = valid ? quads.isEmpty() ? EMPTY : new Template(List.copyOf(quads)) : UNSUPPORTED;
        variants.put(List.copyOf(parts), result);
        return result;
    }

    private static QuadTemplate extract(BakedQuad quad, int group) {
        var material = quad.materialInfo();
        if (material == null || material.layer() == null || material.sprite() == null
            || !TextureAtlas.LOCATION_BLOCKS.equals(material.sprite().atlasLocation())) return null;
        var sprite = material.sprite();
        float minU = sprite.getU0(), maxU = sprite.getU1(), minV = sprite.getV0(), maxV = sprite.getV1();
        if (!Float.isFinite(minU) || !Float.isFinite(maxU) || !Float.isFinite(minV) || !Float.isFinite(maxV)
            || minU < 0 || maxU > 1 || minV < 0 || maxV > 1 || minU >= maxU || minV >= maxV) return null;
        int flags = material.layer() == ChunkSectionLayer.TRANSLUCENT ? ModelQuad.TRANSLUCENT
            : material.layer() == ChunkSectionLayer.CUTOUT || !sprite.transparency().isOpaque() ? ModelQuad.CUTOUT : 0;
        float[] vertices = new float[20];
        for (int i = 0; i < 4; i++) {
            var point = quad.position(i);
            if (point == null || !unit(point.x()) || !unit(point.y()) || !unit(point.z())) return null;
            long uv = quad.packedUV(i);
            float u = UVPair.unpackU(uv), v = UVPair.unpackV(uv);
            if (!Float.isFinite(u) || !Float.isFinite(v) || u < minU-EPSILON || u > maxU+EPSILON
                || v < minV-EPSILON || v > maxV+EPSILON) return null;
            vertices[i*5] = Math.clamp(point.x(), 0f, 1f);
            vertices[i*5+1] = Math.clamp(point.y(), 0f, 1f);
            vertices[i*5+2] = Math.clamp(point.z(), 0f, 1f);
            vertices[i*5+3] = Math.clamp(u, minU, maxU);
            vertices[i*5+4] = Math.clamp(v, minV, maxV);
        }
        return new QuadTemplate(vertices, material.tintIndex(), flags, Math.clamp(material.lightEmission(), 0, 15), group);
    }

    private static boolean unit(float value) { return Float.isFinite(value) && value >= -EPSILON && value <= 1f+EPSILON; }

    private int visibility(BlockState state, BlockPos position, Template template) {
        int groups = 0;
        for (QuadTemplate quad : template.quads) if (quad.group < 6) groups |= 1 << quad.group;
        int visible = groups;
        for (int group = 0; group < 6; group++) {
            if ((groups & (1 << group)) == 0) continue;
            Direction direction = DIRECTIONS[group];
            neighborPosition.set(position.getX()+direction.getStepX(), position.getY()+direction.getStepY(),
                position.getZ()+direction.getStepZ());
            var chunk = level.getChunkSource().getChunk(neighborPosition.getX() >> 4,
                neighborPosition.getZ() >> 4, ChunkStatus.FULL, false);
            if (chunk == null) continue;
            BlockState neighbor = neighborPosition.getY() < level.getMinY() || neighborPosition.getY() > level.getMaxY()
                ? Blocks.AIR.defaultBlockState() : chunk.getBlockState(neighborPosition);
            if (!Block.shouldRenderFace(state, neighbor, direction)) visible &= ~(1 << group);
        }
        return visible;
    }

    private boolean fluidNeighborsLoaded(BlockPos position) {
        int minX = (position.getX()-1) >> 4, maxX = (position.getX()+1) >> 4;
        int minZ = (position.getZ()-1) >> 4, maxZ = (position.getZ()+1) >> 4;
        for (int z = minZ; z <= maxZ; z++) for (int x = minX; x <= maxX; x++)
            if (level.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false) == null) return false;
        return true;
    }
}
