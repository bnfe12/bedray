package io.github.andrexarlay.vulkancompat.rt.world;

import io.github.andrexarlay.vulkancompat.rt.scene.BedraySceneLayouts;
import io.github.andrexarlay.vulkancompat.rt.scene.BedraySceneNative;
import io.github.andrexarlay.vulkancompat.rt.sodium.DecodedSection;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;

/**
 * Producer of the shader-facing {@code GeometryVertex} stream that {@code bedray_scene}
 * builds its BLAS from and {@code path_trace_scene.slang} decodes hits through.
 *
 * <p>This is the one place where the legacy Sodium capture (four planar arrays per
 * section) is repacked into the native record: position, uv/light, tint and meta,
 * 64 bytes per vertex, four vertices per quad.  The index stream is the shared quad
 * template {@code 4q+(0,1,2,2,3,0)} local to each pass range, exactly the layout the
 * scene's {@code primitiveOffset = quad_start*6*4} assumes.
 *
 * <p>Light words keep the legacy encoding: each byte is {@code level*16+8}, so the
 * shader-facing value is {@code (byte-8)/240}.  The material id is byte 2 of the
 * first light word and the vegetation tags are the bits {@link RtWorld} already
 * classified while staging the section.
 */
final class BedraySceneGeometry {
    /** Compact vertex flags of {@code GeometryVertex.meta.y}; see {@code contracts/geometry.slang}. */
    static final int VERTEX_FLAG_VEGETATION = 1;
    static final int VERTEX_FLAG_LEAF_CUBE = 2;

    private static final int VERTEX_BYTES = BedraySceneLayouts.GEOMETRY_VERTEX_BYTES;
    private static final int INDEX_BYTES = 4;
    private static final float LIGHT_SCALE = 1.0f / 240.0f;
    private static final float TINT_SCALE = 1.0f / 255.0f;

    private BedraySceneGeometry() {
    }

    /**
     * Packs one decoded section.  {@code tagged} is the per-quad array
     * {@link RtWorld} produced while staging (may be null when nothing was
     * classified).  The returned record borrows memory from {@code arena};
     * {@code bedray_scene_update} copies it before returning.
     */
    static BedraySceneNative.Section build(DecodedSection d, int[] tagged, Arena arena,
                                           long sectionId, long revision) {
        int quads = d.totalQuads();
        int[] passStart = new int[DecodedSection.PASS_COUNT];
        int[] passId = new int[DecodedSection.PASS_COUNT];
        int geometryCount = 0;
        for (int pass = 0; pass < DecodedSection.PASS_COUNT; pass++) {
            if (d.quadsInPass(pass) > 0) {
                passStart[geometryCount] = d.passQuadStart(pass);
                passId[geometryCount] = pass;
                geometryCount++;
            }
        }
        if (geometryCount == 0) {
            throw new IllegalArgumentException("section " + d.key + " has no quads");
        }

        MemorySegment vertices = arena.allocate((long) quads * 4L * VERTEX_BYTES);
        long at = 0L;
        for (int q = 0; q < quads; q++) {
            int light0 = d.light[q * 4];
            int material = (light0 >> 16) & 0xFF;
            int vertexFlags = 0;
            if (tagged != null) {
                int tag = tagged[q];
                if ((tag & RtWorld.VEGETATION_BIT) != 0) {
                    vertexFlags |= VERTEX_FLAG_VEGETATION;
                }
                if ((tag & RtWorld.LEAF_CUBE_BIT) != 0) {
                    vertexFlags |= VERTEX_FLAG_LEAF_CUBE;
                }
            }
            for (int corner = 0; corner < 4; corner++) {
                int p = q * 12 + corner * 3;
                int u = q * 8 + corner * 2;
                int light = d.light[q * 4 + corner];
                int color = d.colors[q * 4 + corner];
                vertices.set(JAVA_FLOAT, at, d.positions[p]);
                vertices.set(JAVA_FLOAT, at + 4L, d.positions[p + 1]);
                vertices.set(JAVA_FLOAT, at + 8L, d.positions[p + 2]);
                vertices.set(JAVA_FLOAT, at + 12L, 0f);
                vertices.set(JAVA_FLOAT, at + 16L, d.uvs[u]);
                vertices.set(JAVA_FLOAT, at + 20L, d.uvs[u + 1]);
                vertices.set(JAVA_FLOAT, at + 24L, lightByte(light));
                vertices.set(JAVA_FLOAT, at + 28L, lightByte(light >>> 8));
                vertices.set(JAVA_FLOAT, at + 32L, (color & 0xFF) * TINT_SCALE);
                vertices.set(JAVA_FLOAT, at + 36L, ((color >>> 8) & 0xFF) * TINT_SCALE);
                vertices.set(JAVA_FLOAT, at + 40L, ((color >>> 16) & 0xFF) * TINT_SCALE);
                vertices.set(JAVA_FLOAT, at + 44L, 0f);
                vertices.set(JAVA_INT, at + 48L, material);
                vertices.set(JAVA_INT, at + 52L, vertexFlags);
                vertices.set(JAVA_INT, at + 56L, 0);
                vertices.set(JAVA_INT, at + 60L, 0);
                at += VERTEX_BYTES;
            }
        }

        // Local per quad, exactly what the scene's BLAS build expects: its build
        // range carries the range start (primitiveOffset and firstVertex), so an
        // absolute index here would move every vertex of the range twice.
        MemorySegment indices = arena.allocate((long) quads * 6L * INDEX_BYTES);
        int index = 0;
        for (int q = 0; q < quads; q++) {
            indices.setAtIndex(JAVA_INT, index++, 0);
            indices.setAtIndex(JAVA_INT, index++, 1);
            indices.setAtIndex(JAVA_INT, index++, 2);
            indices.setAtIndex(JAVA_INT, index++, 2);
            indices.setAtIndex(JAVA_INT, index++, 3);
            indices.setAtIndex(JAVA_INT, index++, 0);
        }

        int passBits = 0;
        for (int geometry = 0; geometry < geometryCount; geometry++) {
            passBits |= passId[geometry] << (2 * geometry);
        }
        return new BedraySceneNative.Section(sectionId, revision, vertices, (long) quads * 4L,
            indices, (long) quads * 6L, BedraySceneLayouts.INDEX_UINT32,
            d.sectionX * 16, d.sectionY * 16, d.sectionZ * 16, geometryCount,
            passStart[0], passStart[1], passStart[2], passBits,
            BedraySceneLayouts.SECTION_FLAG_GEOMETRY_VERTEX);
    }

    /** {@code (byte-8)/240}, the inverse of the capture's {@code level*16+8}. */
    private static float lightByte(int raw) {
        float value = ((raw & 0xFF) - 8) * LIGHT_SCALE;
        return value <= 0f ? 0f : (value >= 1f ? 1f : value);
    }
}
