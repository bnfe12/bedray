package io.github.andrexarlay.vulkancompat.compute;

import io.github.andrexarlay.bedray.api.v1.AtlasFrame;
import io.github.andrexarlay.bedray.api.v1.CubeFaceMaterial;
import io.github.andrexarlay.bedray.api.v1.CubeMaterial;
import io.github.andrexarlay.bedray.api.v1.CubeSectionMaterials;
import io.github.andrexarlay.bedray.api.v1.SectionSnapshot;
import io.github.andrexarlay.bedray.api.v1.SectionLightData;
import io.github.andrexarlay.bedray.api.v1.SectionTintData;
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
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.LightLayer;
import org.joml.Vector3fc;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Render-thread extraction of exact full cubes and optional bounded baked models/light.
 * Only immutable data leaves this class. Unsupported geometry is never replaced with guessed cubes.
 */
final class ComputeBlockMaterials {
    private static final int WHITE = 0xFFFFFFFF;
    private static final float POSITION_EPSILON = 0.00001f;
    private static final float UV_EPSILON = 0.000001f;
    private static final int MAX_VARIANTS = 1024;
    private static final Geometry UNSUPPORTED = new Geometry(new FaceGeometry[0]);
    private static final Direction[] DIRECTIONS = {Direction.DOWN, Direction.UP, Direction.NORTH,
        Direction.SOUTH, Direction.WEST, Direction.EAST};

    private record SectionKey(int x, int y, int z) {}
    private record FaceGeometry(CubeFaceMaterial uv, int tintIndex) {}
    private record Geometry(FaceGeometry[] faces) {}

    private static final class SectionGeometry {
        final BlockState[] states = new BlockState[SectionSnapshot.CELL_COUNT];
        final Geometry[] geometry = new Geometry[SectionSnapshot.CELL_COUNT];
        Map<AppearanceKey, PaletteEntry> appearances = Map.of();
        final ComputeModelMaterials.SectionCache models = new ComputeModelMaterials.SectionCache();
    }

    /** The reusable lookup is never inserted. Stored keys own their six colors plus the cell-tint face mask. */
    private static final class AppearanceKey {
        Geometry geometry;
        final int[] colors;
        private int hash;
        AppearanceKey(int[] colors) { this.colors = colors; }
        void setGeometry(Geometry geometry) {
            this.geometry = geometry;
            hash = 31 * System.identityHashCode(geometry) + Arrays.hashCode(colors);
        }
        AppearanceKey copy() {
            AppearanceKey key = new AppearanceKey(colors.clone());
            key.setGeometry(geometry);
            return key;
        }
        @Override public int hashCode() { return hash; }
        @Override public boolean equals(Object other) {
            return this == other || other instanceof AppearanceKey that
                && geometry == that.geometry && Arrays.equals(colors, that.colors);
        }
    }
    private record PaletteEntry(AppearanceKey key, CubeMaterial material, int reference) {}

    private final Map<SectionKey, SectionGeometry> sections = new LinkedHashMap<>(64, 0.75f, true);
    private final Map<List<BlockStateModelPart>, Geometry> variants = new LinkedHashMap<>(128, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<List<BlockStateModelPart>, Geometry> eldest) {
            return size() > MAX_VARIANTS;
        }
    };
    private final List<BlockStateModelPart> parts = new ArrayList<>();
    private final RandomSource random = RandomSource.create(0);
    private final BlockPos.MutableBlockPos position = new BlockPos.MutableBlockPos();
    // Six face colors followed by the mask of faces that take the per-cell tint (API minor 5).
    private final int[] tintColors = new int[CubeMaterial.FACE_COUNT + 1];
    private final AppearanceKey lookup = new AppearanceKey(tintColors);
    private final ComputeModelMaterials modelMaterials = new ComputeModelMaterials();
    private Minecraft minecraft;
    private ClientLevel level;
    private BlockStateModelSet models;
    private long generation;
    private boolean sessionChanged;
    private boolean cellTint;
    private int overflowCells;

    void clear() {
        sections.clear(); variants.clear(); parts.clear(); modelMaterials.clear();
        minecraft = null; level = null; models = null; generation = 0;
        sessionChanged = false;
    }

    /** A same-generation model replacement is not safe to combine with old atlas UVs. */
    String prepare(Minecraft minecraft, ClientLevel level, AtlasFrame atlas) {
        if (minecraft.getModelManager() == null || minecraft.getBlockColors() == null)
            return "Waiting for block model and tint services";
        BlockStateModelSet current = minecraft.getModelManager().getBlockStateModelSet();
        if (current == null) return "Waiting for baked block models";
        if (this.level != level || generation != atlas.generation()) {
            clear();
            this.minecraft = minecraft; this.level = level; this.models = current;
            generation = atlas.generation();
            modelMaterials.prepare(minecraft, level, current, generation);
            sessionChanged = true;
        } else if (models != current) {
            return "Waiting for matching block model and atlas generation";
        }
        return null;
    }

    /** Cell tint changes every appearance key, so cached sections and variants are discarded. */
    void setCellTint(boolean enabled) {
        if (cellTint == enabled) return;
        cellTint = enabled;
        sections.clear();
        sessionChanged = true;
    }

    /** Cubes that did not fit the cube palette in the most recent captured section (diagnostics only). */
    int lastOverflowCells() { return overflowCells; }

    boolean consumeSessionChange() {
        boolean changed = sessionChanged;
        sessionChanged = false;
        return changed;
    }

    void retainWindow(int x, int y, int z) {
        sections.keySet().removeIf(key -> Math.abs((long)key.x - x) > 1
            || Math.abs((long)key.y - y) > 1 || Math.abs((long)key.z - z) > 1
            || !loaded(key.x,key.y,key.z));
    }

    private boolean loaded(int x, int y, int z) {
        return y >= level.getMinSectionY() && y <= level.getMaxSectionY()
            && level.getChunkSource().getChunk(x,z,ChunkStatus.FULL,false) != null;
    }

    SectionSnapshot captureSection(int x, int y, int z, long revision) {
        return captureSection(x, y, z, revision, true);
    }

    SectionSnapshot captureSection(int x, int y, int z, long revision, boolean extended) {
        return captureSection(x, y, z, revision, extended, false);
    }

    SectionSnapshot captureSection(int x, int y, int z, long revision, boolean extended, boolean streaming) {
        if (!loaded(x,y,z)) return null;
        var chunk = level.getChunkSource().getChunk(x,z,ChunkStatus.FULL,false);
        if (chunk == null) return null;
        var section = chunk.getSection(level.getSectionIndexFromSectionY(y));
        SectionKey key = new SectionKey(x,y,z);
        SectionGeometry cached = sections.computeIfAbsent(key, ignored -> new SectionGeometry());
        if (!streaming && sections.size() > 27) throw new IllegalStateException("Material section window exceeded 27 residents");
        byte[] cells = new byte[SectionSnapshot.CELL_COUNT];
        short[] references = new short[SectionSnapshot.CELL_COUNT];
        List<CubeMaterial> palette = new ArrayList<>();
        Map<AppearanceKey, PaletteEntry> appearances = new LinkedHashMap<>();
        int[] cellTints = extended && cellTint ? new int[SectionSnapshot.CELL_COUNT] : null;
        ComputeModelMaterials.SectionCapture modelCapture = extended ? modelMaterials.begin(cached.models, cellTints) : null;
        overflowCells = 0;
        short[] light = extended ? new short[SectionSnapshot.CELL_COUNT] : null;
        var blockLight = extended ? level.getLightEngine().getLayerListener(LightLayer.BLOCK) : null;
        var skyLight = extended ? level.getLightEngine().getLayerListener(LightLayer.SKY) : null;
        int originX = Math.multiplyExact(x,16), originY = Math.multiplyExact(y,16), originZ = Math.multiplyExact(z,16);
        for (int bz=0; bz<16; bz++) for (int by=0; by<16; by++) for (int bx=0; bx<16; bx++) {
            int index = bx + 16 * (by + 16 * bz);
            BlockState state = section.getBlockState(bx,by,bz);
            position.set(originX+bx,originY+by,originZ+bz);
            if (extended) {
                // Listener lookup preserves implicit skylight values when no explicit nibble layer exists.
                int block = Math.clamp(blockLight.getLightValue(position), 0, 15);
                int sky = Math.clamp(skyLight.getLightValue(position), 0, 15);
                int emission = Math.clamp(state.getLightEmission(), 0, 15);
                light[index] = (short)(block | (sky << 4) | (emission << 8));
            }
            if (cached.states[index] != state) {
                cached.states[index] = state;
                cached.geometry[index] = eligibleState(state) ? geometry(state) : UNSUPPORTED;
            }
            if (state.isAir() && state.getFluidState().isEmpty()) continue;
            Geometry geometry = cached.geometry[index];
            if (geometry == UNSUPPORTED) {
                cells[index] = extended ? modelCapture.captureCell(state, position, index) : SectionSnapshot.UNSUPPORTED;
                continue;
            }
            cells[index] = SectionSnapshot.OPAQUE_CUBE;
            tint(state,geometry);
            int cellColor = cellTints == null ? 0 : moveTintToCell();
            lookup.setGeometry(geometry);
            PaletteEntry entry = appearances.get(lookup);
            if (entry == null && palette.size() < CubeSectionMaterials.MAX_MATERIALS) {
                PaletteEntry previous = cached.appearances.get(lookup);
                AppearanceKey appearance = previous == null ? lookup.copy() : previous.key;
                CubeMaterial material = previous == null ? material(geometry) : previous.material;
                entry = new PaletteEntry(appearance,material,palette.size()+1);
                appearances.put(appearance,entry);
                palette.add(material);
            }
            if (entry != null) {
                references[index] = (short)entry.reference;
                if (cellColor != 0) cellTints[index] = cellColor;
            } else {
                // A full palette must not publish an untextured cube. The model path carries the same baked
                // quads in its own bounded palette; when that is also full the cell stays explicit unsupported.
                overflowCells++;
                cells[index] = extended ? modelCapture.captureCell(state, position, index) : SectionSnapshot.UNSUPPORTED;
            }
        }
        cached.appearances = appearances;
        CubeSectionMaterials materialData = new CubeSectionMaterials(generation,palette,references);
        SectionSnapshot result = new SectionSnapshot(x,y,z,revision,cells,materialData,
            extended ? modelCapture.finish() : null, extended ? new SectionLightData(light) : null,
            cellTints == null ? null : new SectionTintData(cellTints));
        if (streaming) {
            // Large per-cell state/template arrays are a hot extraction cache, not a second world copy.
            // Bound both entry count and conservative packed model/reference payload estimates.
            long bytes = 0;
            var iterator = sections.entrySet().iterator();
            while (iterator.hasNext()) {
                var entry = iterator.next();
                var value = entry.getValue();
                bytes += 65536L;
                for (var model : value.models.models.keySet()) bytes += (long)model.quads().size() * 192;
            }
            iterator = sections.entrySet().iterator();
            while ((sections.size() > 64 || bytes > 16L * 1024 * 1024) && iterator.hasNext()) {
                var value = iterator.next().getValue();
                bytes -= 65536L;
                for (var model : value.models.models.keySet()) bytes -= (long)model.quads().size() * 192;
                iterator.remove();
            }
        }
        return result;
    }

    private static boolean eligibleState(BlockState state) {
        // Cached state/shape properties only: no world-aware collision or neighbor callbacks.
        return !state.isAir() && !state.getBlock().hasDynamicShape() && !state.hasOffsetFunction()
            && !state.liquid() && state.getFluidState().isEmpty() && state.getRenderShape() == RenderShape.MODEL
            && state.canOcclude() && state.isSolidRender() && Block.isShapeFullBlock(state.getOcclusionShape());
    }

    private Geometry geometry(BlockState state) {
        parts.clear();
        random.setSeed(state.getSeed(position));
        var model = models.get(state);
        if (model == null || model == models.missingModel()) return UNSUPPORTED;
        model.collectParts(random,parts);
        for (BlockStateModelPart part : parts) if (part == null) return UNSUPPORTED;
        Geometry cached = variants.get(parts);
        if (cached != null) return cached;
        Geometry geometry = extractGeometry();
        variants.put(List.copyOf(parts),geometry);
        return geometry;
    }

    private Geometry extractGeometry() {
        FaceGeometry[] faces = new FaceGeometry[CubeMaterial.FACE_COUNT];
        int count = 0;
        for (BlockStateModelPart part : parts) {
            if (part == null) return UNSUPPORTED;
            for (int group=0; group<=DIRECTIONS.length; group++) {
                List<BakedQuad> quads = part.getQuads(group == DIRECTIONS.length ? null : DIRECTIONS[group]);
                if (quads == null) return UNSUPPORTED;
                for (BakedQuad quad : quads) {
                    if (quad == null || quad.direction() == null || ++count > CubeMaterial.FACE_COUNT) return UNSUPPORTED;
                    int face = faceIndex(quad.direction());
                    if (faces[face] != null) return UNSUPPORTED;
                    FaceGeometry extracted = face(quad,face);
                    if (extracted == null) return UNSUPPORTED;
                    faces[face] = extracted;
                }
            }
        }
        return count == CubeMaterial.FACE_COUNT ? new Geometry(faces) : UNSUPPORTED;
    }

    private static int faceIndex(Direction direction) {
        return switch (direction) {
            case DOWN -> 0; case UP -> 1; case NORTH -> 2;
            case SOUTH -> 3; case WEST -> 4; case EAST -> 5;
        };
    }

    private static FaceGeometry face(BakedQuad quad, int face) {
        var info = quad.materialInfo();
        if (info == null || info.layer() != ChunkSectionLayer.SOLID) return null;
        var sprite = info.sprite();
        if (sprite == null || !TextureAtlas.LOCATION_BLOCKS.equals(sprite.atlasLocation())
            || !sprite.transparency().isOpaque()) return null;
        float minU = sprite.getU0(), maxU = sprite.getU1(), minV = sprite.getV0(), maxV = sprite.getV1();
        if (!Float.isFinite(minU) || !Float.isFinite(maxU) || !Float.isFinite(minV) || !Float.isFinite(maxV)
            || minU < 0 || maxU > 1 || minV < 0 || maxV > 1 || minU >= maxU || minV >= maxV) return null;
        float[] u = new float[4], v = new float[4];
        int[] corners = new int[4];
        int mask = 0;
        for (int i=0; i<4; i++) {
            Vector3fc p = quad.position(i);
            if (p == null || !Float.isFinite(p.x()) || !Float.isFinite(p.y()) || !Float.isFinite(p.z())) return null;
            float plane = face < 2 ? p.y() : face < 4 ? p.z() : p.x();
            if (Math.abs(plane - (face % 2)) > POSITION_EPSILON) return null;
            int localU = corner(face < 4 ? p.x() : p.z());
            int localV = corner(face < 2 ? p.z() : p.y());
            if (localU < 0 || localV < 0) return null;
            int index = localU + 2 * localV;
            if ((mask & (1 << index)) != 0) return null;
            mask |= 1 << index;
            corners[i] = index;
            long packed = quad.packedUV(i);
            float qu = UVPair.unpackU(packed), qv = UVPair.unpackV(packed);
            if (!Float.isFinite(qu) || !Float.isFinite(qv) || qu < minU-UV_EPSILON || qu > maxU+UV_EPSILON
                || qv < minV-UV_EPSILON || qv > maxV+UV_EPSILON) return null;
            u[index] = Math.clamp(qu,minU,maxU); v[index] = Math.clamp(qv,minV,maxV);
        }
        if (mask != 15) return null;
        for (int i=0; i<4; i++) {
            int difference = corners[i] ^ corners[(i+1)%4];
            if (difference != 1 && difference != 2) return null;
        }
        Vector3fc p0 = quad.position(0), p1 = quad.position(1), p2 = quad.position(2);
        float ax = p1.x()-p0.x(), ay = p1.y()-p0.y(), az = p1.z()-p0.z();
        float bx = p2.x()-p0.x(), by = p2.y()-p0.y(), bz = p2.z()-p0.z();
        float normal = face < 2 ? az*bx-ax*bz : face < 4 ? ax*by-ay*bx : ay*bz-az*by;
        if (normal * (face % 2 == 0 ? -1f : 1f) < 0.5f) return null;
        float duU = u[1]-u[0], duV = v[1]-v[0], dvU = u[2]-u[0], dvV = v[2]-v[0];
        if (Math.abs(u[3]-(u[0]+duU+dvU)) > UV_EPSILON
            || Math.abs(v[3]-(v[0]+duV+dvV)) > UV_EPSILON) return null;
        try {
            return new FaceGeometry(new CubeFaceMaterial(u[0],v[0],duU,duV,dvU,dvV,WHITE),info.tintIndex());
        } catch (IllegalArgumentException invalidUv) {
            return null;
        }
    }

    private static int corner(float value) {
        if (Math.abs(value) <= POSITION_EPSILON) return 0;
        return Math.abs(value-1f) <= POSITION_EPSILON ? 1 : -1;
    }

    private void tint(BlockState state, Geometry geometry) {
        tintedGeometry = geometry;
        tintColors[CubeMaterial.FACE_COUNT] = 0;
        for (int i=0; i<CubeMaterial.FACE_COUNT; i++) {
            int tintIndex = geometry.faces[i].tintIndex;
            if (tintIndex < 0) {
                tintColors[i] = WHITE;
                continue;
            }
            var sources = minecraft.getBlockColors().getTintSources(state);
            var source = sources == null || tintIndex >= sources.size() ? null : sources.get(tintIndex);
            // Uniform game block tint; Sodium's smooth per-vertex biome blend is outside this slice.
            tintColors[i] = source == null ? WHITE : source.colorInWorld(state,level,position) | 0xFF000000;
        }
    }

    /** When every tinted face resolved to one color, that color moves to the cell and the faces become
     * white with the cell-tint bit. Faces with different tint sources keep their baked colors. */
    private int moveTintToCell() {
        int color = 0, mask = 0;
        for (int i=0; i<CubeMaterial.FACE_COUNT; i++) {
            if (lookupTintIndex(i) < 0) continue;
            if (color == 0) color = tintColors[i];
            else if (color != tintColors[i]) return 0;
            mask |= 1 << i;
        }
        if (mask == 0) return 0;
        for (int i=0; i<CubeMaterial.FACE_COUNT; i++) if ((mask & (1 << i)) != 0) tintColors[i] = WHITE;
        tintColors[CubeMaterial.FACE_COUNT] = mask;
        return color;
    }

    private Geometry tintedGeometry;
    private int lookupTintIndex(int face) { return tintedGeometry.faces[face].tintIndex; }

    private CubeMaterial material(Geometry geometry) {
        List<CubeFaceMaterial> faces = new ArrayList<>(CubeMaterial.FACE_COUNT);
        int mask = tintColors[CubeMaterial.FACE_COUNT];
        for (int i=0; i<CubeMaterial.FACE_COUNT; i++) {
            CubeFaceMaterial uv = geometry.faces[i].uv;
            boolean cell = (mask & (1 << i)) != 0;
            faces.add(tintColors[i] == WHITE && !cell ? uv : new CubeFaceMaterial(uv.u(),uv.v(),uv.duU(),uv.duV(),
                uv.dvU(),uv.dvV(),tintColors[i],cell));
        }
        return new CubeMaterial(faces);
    }
}
