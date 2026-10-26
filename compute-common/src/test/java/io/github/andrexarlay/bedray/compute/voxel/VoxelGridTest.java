package io.github.andrexarlay.bedray.compute.voxel;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.*;

class VoxelGridTest {
    @Test void gridOwnsSnapshotAndUsesXMajorLayout() {
        VoxelGridBuilder builder = VoxelGrid.builder(2, 3, 4).set(1, 2, 3, 7);
        VoxelGrid first = builder.build();
        builder.set(1, 2, 3, 8);
        assertEquals(7, first.material(1, 2, 3));
        assertEquals(8, builder.build().material(1, 2, 3));
        assertEquals(24, first.cellCount());
        assertEquals(112, first.byteSize());
        assertEquals(0, first.material(0, 0, 0));
    }

    @Test void serializationMatchesStd430HeaderAndPreservesCallerOrder() {
        VoxelGrid grid = VoxelGrid.builder(2, 2, 2).set(1, 0, 1, 0x12345678).build();
        ByteBuffer target = ByteBuffer.allocate((int) grid.byteSize() + 8).order(ByteOrder.BIG_ENDIAN);
        target.position(4);
        grid.writeTo(target);
        assertEquals(4 + grid.byteSize(), target.position());
        assertEquals(ByteOrder.BIG_ENDIAN, target.order());
        ByteBuffer bytes = target.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(2, bytes.getInt(4));
        assertEquals(2, bytes.getInt(8));
        assertEquals(2, bytes.getInt(12));
        assertEquals(8, bytes.getInt(16));
        assertEquals(0x12345678, bytes.getInt(4 + 16 + 5 * 4));
    }

    @Test void serializationRejectsShortTargetWithoutChangingItsPosition() {
        VoxelGrid grid = VoxelGrid.builder(1, 1, 1).build();
        ByteBuffer target = ByteBuffer.allocate(19);
        assertThrows(IllegalArgumentException.class, () -> grid.writeTo(target));
        assertEquals(0, target.position());
    }

    @Test void boxUsesExclusiveMaximumAndAcceptsEmptyBox() {
        VoxelGrid grid = VoxelGrid.builder(4, 4, 4)
                .fillBox(1, 1, 1, 3, 3, 3, 2)
                .fillBox(4, 4, 4, 4, 4, 4, 3).build();
        int filled = 0;
        for (int z = 0; z < 4; z++) for (int y = 0; y < 4; y++) for (int x = 0; x < 4; x++) {
            if (grid.material(x, y, z) == 2) filled++;
        }
        assertEquals(8, filled);
        assertEquals(0, grid.material(3, 2, 2));
    }

    @Test void budgetIsCheckedBeforeAllocation() {
        assertDoesNotThrow(() -> VoxelGrid.builder(2, 2, 2, 48));
        assertThrows(IllegalArgumentException.class, () -> VoxelGrid.builder(2, 2, 2, 47));
        assertThrows(IllegalArgumentException.class, () -> VoxelGrid.builder(1, 1, 1, -1));
        assertThrows(IllegalArgumentException.class, () -> VoxelGrid.builder(Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE));
        assertThrows(IllegalArgumentException.class, () -> VoxelGrid.builder(1024, 1024, 1024, Long.MAX_VALUE));
    }

    @Test void dimensionsCoordinatesAndMaterialsAreValidated() {
        assertThrows(IllegalArgumentException.class, () -> VoxelGrid.builder(0, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> VoxelGrid.builder(1, -1, 1));
        VoxelGridBuilder builder = VoxelGrid.builder(2, 2, 2);
        assertThrows(IndexOutOfBoundsException.class, () -> builder.set(2, 0, 0, 1));
        assertThrows(IndexOutOfBoundsException.class, () -> builder.set(-1, 0, 0, 1));
        assertThrows(IndexOutOfBoundsException.class, () -> builder.fillBox(1, 0, 0, 0, 1, 1, 1));
        assertThrows(IndexOutOfBoundsException.class, () -> builder.fillBox(0, 0, 0, 3, 1, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> builder.set(0, 0, 0, -1));
        assertThrows(IllegalArgumentException.class, () -> builder.fillBox(0, 0, 0, 1, 1, 1, -1));
        assertThrows(IndexOutOfBoundsException.class, () -> builder.build().material(0, 2, 0));
    }

    @Test void demoHasDocumentedBoundsAndMaterials() {
        VoxelGrid grid = VoxelDemoScene.create();
        assertEquals(32, grid.sizeX());
        assertEquals(24, grid.sizeY());
        assertEquals(32, grid.sizeZ());
        assertEquals(1, grid.material(0, 0, 0));
        assertEquals(2, grid.material(8, 1, 8));
        assertEquals(3, grid.material(20, 14, 17));
        assertEquals(4, grid.material(18, 6, 4));
        assertEquals(0, grid.material(31, 23, 31));
    }
}
