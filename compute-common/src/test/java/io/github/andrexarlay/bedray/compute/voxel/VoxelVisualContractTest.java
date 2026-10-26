package io.github.andrexarlay.bedray.compute.voxel;

import io.github.andrexarlay.bedray.api.v1.*;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** CPU contracts of the alpha.8 visual pass: cell tint ABI, emitter list and history math. */
class VoxelVisualContractTest {
    private static final float[] IDENTITY = {1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,0,1};

    private static CubeMaterial cube(boolean cellTint) {
        CubeFaceMaterial face = new CubeFaceMaterial(0, 0, 0.5f, 0, 0, 0.5f, 0xFFFFFFFF, cellTint);
        return new CubeMaterial(List.of(face, face, face, face, face, face));
    }

    @Test void cellTintRequiresMatchingOccupancyAndValue() {
        byte[] cells = new byte[4096]; cells[0] = SectionSnapshot.OPAQUE_CUBE;
        short[] references = new short[4096]; references[0] = 1;
        var materials = new CubeSectionMaterials(1, List.of(cube(true)), references);
        assertTrue(materials.usesCellTint(1));
        assertFalse(materials.usesCellTint(0));
        int[] tints = new int[4096];
        assertThrows(IllegalArgumentException.class, () -> new SectionSnapshot(0,0,0,1,cells,materials,null,null,new SectionTintData(tints)));
        tints[0] = 0xFF336699;
        var section = new SectionSnapshot(0,0,0,1,cells,materials,null,null,new SectionTintData(tints));
        assertEquals(0xFF336699, section.tints().argb(0));
        int[] stray = tints.clone(); stray[1] = 0xFF000000;
        assertThrows(IllegalArgumentException.class, () -> new SectionSnapshot(0,0,0,1,cells,materials,null,null,new SectionTintData(stray)));
        assertThrows(IllegalArgumentException.class, () -> new SectionTintData(new int[]{1}));
        int[] translucent = new int[4096]; translucent[0] = 0x80FFFFFF;
        assertThrows(IllegalArgumentException.class, () -> new SectionTintData(translucent));
        // Content equality includes the tint, so a biome color change produces a new section revision.
        int[] other = tints.clone(); other[0] = 0xFF336698;
        assertFalse(section.hasSameContent(new SectionSnapshot(0,0,0,1,cells,materials,null,null,new SectionTintData(other))));
    }

    @Test void legacyFaceConstructorHasNoCellTintAndQuadAcceptsFlag() {
        assertFalse(new CubeFaceMaterial(0,0,0.5f,0,0,0.5f,0xFFFFFFFF).cellTint());
        float[] vertices = new float[20];
        assertEquals(ModelQuad.CELL_TINT, new ModelQuad(vertices, 0xFFFFFFFF, ModelQuad.CELL_TINT, 0).flags());
        assertThrows(IllegalArgumentException.class, () -> new ModelQuad(vertices, 0xFFFFFFFF, 16, 0));
    }

    @Test void tintAndFaceFlagUploadLayout() {
        byte[] cells = new byte[4096]; cells[5] = SectionSnapshot.OPAQUE_CUBE;
        short[] references = new short[4096]; references[5] = 1;
        int[] tints = new int[4096]; tints[5] = 0xFF102030;
        var section = new SectionSnapshot(0,0,0,1,cells,new CubeSectionMaterials(1,List.of(cube(true)),references),
            null,null,new SectionTintData(tints));
        var tile = new VoxelWorldUpload.Tile(13, section);
        assertEquals(VoxelGrid.HEADER_BYTES + VoxelWorldUpload.TINT_BYTES + 13L * VoxelWorldUpload.TILE_BYTES, tile.tintOffset());
        ByteBuffer buffer = ByteBuffer.allocate(CubeSectionMaterials.SECTION_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        tile.writeTintsTo(buffer);
        assertEquals(0xFF102030, buffer.getInt(5 * 4));
        buffer.clear(); tile.writeMaterialsTo(buffer);
        assertEquals(1, buffer.getInt(28), "face flags word carries the cell-tint bit");
        buffer.clear(); new VoxelWorldUpload.Tile(13, null).writeTintsTo(buffer);
        assertEquals(0, buffer.getInt(5 * 4));
    }

    @Test void emittersUseEqualAreaSpheresAndKeepNearest() {
        byte[] cells = new byte[4096]; cells[0] = SectionSnapshot.OPAQUE_CUBE;
        short[] light = new short[4096];
        light[0] = (short) (15 << 8);        // cube emitter at section corner
        light[3822] = (short) (7 << 8);      // emitter without geometry, local (14,14,14)
        var section = new SectionSnapshot(1,1,1,1,cells,null,null,new SectionLightData(light));
        float[] cube = VoxelEmitters.sphere(section, 0);
        assertEquals(Math.sqrt(6 / (4 * Math.PI)), cube[3], 1e-6, "unit cube surface area");
        assertEquals(0.5f, VoxelEmitters.sphere(section, 3822)[3], 1e-6);
        var upload = new VoxelWorldUpload();
        float[] identity = IDENTITY;
        upload.update(new WorldSnapshot(1,1,new CameraSnapshot(24,24,24,identity,identity),List.of(section)));
        assertTrue(upload.consumeEmittersChanged());
        assertEquals(2, upload.emitters().count());
        ByteBuffer data = ByteBuffer.allocate(VoxelEmitters.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        upload.emitters().writeTo(data);
        // The window center is (24,24,24); cell (30,30,30) of the center slot is nearer than (16,16,16).
        assertEquals(30.5f, data.getFloat(0), 1e-6);
        assertEquals(7f, data.getFloat(16), 1e-6);
        assertEquals(15f, data.getFloat(32 + 16), 1e-6);
    }

    @Test void historyInverseRecoversForwardMatrix() {
        float[] m = {2,0,0,0, 0,3,0,0, 0,0,-1,-1, 1,2,-0.5f,0};
        float[] inverse = new float[16];
        assertTrue(io.github.andrexarlay.bedray.compute.TemporalHistoryAccess.invert(m, inverse));
        for (int row = 0; row < 4; row++) for (int col = 0; col < 4; col++) {
            float sum = 0;
            for (int k = 0; k < 4; k++) sum += m[k * 4 + row] * inverse[col * 4 + k];
            assertEquals(row == col ? 1 : 0, sum, 1e-5);
        }
        assertFalse(io.github.andrexarlay.bedray.compute.TemporalHistoryAccess.invert(new float[16], inverse));
    }
}
