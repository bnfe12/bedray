package io.github.andrexarlay.vulkancompat.shaderhost.iris;

import io.github.andrexarlay.vulkancompat.rt.entity.EntityGeometryFrame;
import io.github.andrexarlay.vulkancompat.rt.sodium.DecodedSection;
import io.github.andrexarlay.vulkancompat.rt.sodium.SectionGeometryRegistry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the per-frame geometry meshes the Iris forward executor draws, keyed by source ("terrain", "water", "entities").
 * Render thread only.
 *
 * <p>Terrain is cached section-local (positions in [0,16], so float precision is exact and {@code at_midBlock} is computed
 * against block-aligned coordinates) and rebuilt only when {@link SectionGeometryRegistry#version()} changes. Each frame the
 * cached sections are offset by {@code sectionOrigin - cameraPosition} and merged, producing camera-relative vertices that
 * match the Iris convention ({@code worldPos = vaPosition + cameraPosition}). Entities arrive already camera-relative from
 * the capture ({@link EntityGeometryFrame}). Solid + cutout quads feed "terrain"; translucent quads feed "water".
 */
public final class IrisFrameGeometry {

    private record CachedSection(int sx, int sy, int sz, IrisGeometrySource.Mesh terrain, IrisGeometrySource.Mesh water) {}

    private long version = -1;
    private final List<CachedSection> sections = new ArrayList<>();

    public Map<String, IrisGeometrySource.Mesh> build(double camX, double camY, double camZ, EntityGeometryFrame entityFrame) {
        long v = SectionGeometryRegistry.INSTANCE.version();
        if (v != version) {
            refresh();
            version = v;
        }
        List<IrisGeometrySource.Mesh> terrainParts = new ArrayList<>();
        List<IrisGeometrySource.Mesh> waterParts = new ArrayList<>();
        for (CachedSection s : sections) {
            float ox = (float) (s.sx * 16.0 - camX);
            float oy = (float) (s.sy * 16.0 - camY);
            float oz = (float) (s.sz * 16.0 - camZ);
            if (s.terrain != null) {
                terrainParts.add(offset(s.terrain, ox, oy, oz));
            }
            if (s.water != null) {
                waterParts.add(offset(s.water, ox, oy, oz));
            }
        }
        Map<String, IrisGeometrySource.Mesh> out = new LinkedHashMap<>();
        IrisGeometrySource.Mesh terrain = merge(terrainParts);
        if (terrain.vertices().length > 0) {
            out.put("terrain", terrain);
        }
        IrisGeometrySource.Mesh water = merge(waterParts);
        if (water.vertices().length > 0) {
            out.put("water", water);
        }
        if (entityFrame != null && entityFrame.vertexData() != null && entityFrame.indexData() != null
            && entityFrame.totalVertices() > 0 && entityFrame.totalIndices() >= 3) {
            out.put("entities", IrisGeometrySource.buildEntities(entityFrame.vertexData(), entityFrame.indexData()));
        }
        return out;
    }

    private void refresh() {
        sections.clear();
        SectionGeometryRegistry.INSTANCE.forEachSection(sec -> {
            IrisGeometrySource.Mesh terrain = merge(List.of(local(sec, DecodedSection.PASS_SOLID), local(sec, DecodedSection.PASS_CUTOUT)));
            IrisGeometrySource.Mesh water = local(sec, DecodedSection.PASS_TRANSLUCENT);
            sections.add(new CachedSection(sec.sectionX, sec.sectionY, sec.sectionZ,
                terrain.vertices().length > 0 ? terrain : null,
                water.vertices().length > 0 ? water : null));
        });
    }

    private static IrisGeometrySource.Mesh local(DecodedSection sec, int pass) {
        return IrisGeometrySource.buildTerrain(sec.positions, sec.uvs, sec.colors, sec.light, sec.normals,
            sec.passQuadStart(pass), sec.quadsInPass(pass), 0f, 0f, 0f);
    }

    /** Copies a mesh, translating only the position components (stride layout: pos3 first). {@code at_midBlock} is preserved. */
    static IrisGeometrySource.Mesh offset(IrisGeometrySource.Mesh m, float ox, float oy, float oz) {
        float[] v = m.vertices().clone();
        int stride = m.strideFloats();
        for (int i = 0; i + 2 < v.length; i += stride) {
            v[i] += ox;
            v[i + 1] += oy;
            v[i + 2] += oz;
        }
        return new IrisGeometrySource.Mesh(v, m.indices(), stride);
    }

    /** Concatenates meshes into one, rebasing each part's indices by the running vertex count. */
    static IrisGeometrySource.Mesh merge(List<IrisGeometrySource.Mesh> parts) {
        int stride = IrisGeometrySource.STRIDE_FLOATS;
        int totalV = 0;
        int totalI = 0;
        for (IrisGeometrySource.Mesh m : parts) {
            totalV += m.vertices().length;
            totalI += m.indices().length;
        }
        float[] v = new float[totalV];
        int[] idx = new int[totalI];
        int vo = 0;
        int io = 0;
        int base = 0;
        for (IrisGeometrySource.Mesh m : parts) {
            System.arraycopy(m.vertices(), 0, v, vo, m.vertices().length);
            for (int i = 0; i < m.indices().length; i++) {
                idx[io + i] = m.indices()[i] + base;
            }
            vo += m.vertices().length;
            io += m.indices().length;
            base += m.vertices().length / stride;
        }
        return new IrisGeometrySource.Mesh(v, idx, stride);
    }
}