package io.github.andrexarlay.bedray.api.v1;

import org.junit.jupiter.api.Test;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import static org.junit.jupiter.api.Assertions.*;

class VoxelSceneSnapshotTest {
    @Test void immutableCellsHaveDistinctEmptyExcludedAndUnknownCounts() {
        int[] cells = {0,1,2,3};
        var snapshot = new VoxelSceneSnapshot(-16,64,32,2,1,2,7,9,cells);
        cells[1] = 0;
        assertEquals(1,snapshot.material(1,0,0));
        assertEquals(1,snapshot.emptyCount());
        assertEquals(1,snapshot.fullCubeCount());
        assertEquals(1,snapshot.excludedCount());
        assertEquals(1,snapshot.unknownCount());
        assertEquals(2,snapshot.material(0,0,1));
        assertEquals(3,snapshot.material(1,0,1));
        assertEquals(7,snapshot.worldEpoch());
        assertEquals(9,snapshot.revision());
    }

    @Test void halfOpenWorldBoundsUseWideArithmetic() {
        var snapshot = new VoxelSceneSnapshot(Integer.MAX_VALUE,0,-16,2,1,2,1,1,new int[4]);
        assertTrue(snapshot.contains((double)Integer.MAX_VALUE+1,0,-15));
        assertFalse(snapshot.contains((double)Integer.MAX_VALUE+2,0,-15));
        assertFalse(snapshot.contains(Integer.MAX_VALUE,1,-15));
        assertFalse(snapshot.contains(Double.NaN,0,-15));
        assertThrows(IndexOutOfBoundsException.class,()->snapshot.material(2,0,0));
    }

    @Test void serializationMatchesHeaderAndPreservesCallerOrderAndPosition() {
        var snapshot = new VoxelSceneSnapshot(0,0,0,2,1,1,1,1,new int[]{2,3});
        ByteBuffer bytes = ByteBuffer.allocate(40).order(ByteOrder.BIG_ENDIAN);
        bytes.position(4);
        snapshot.writeTo(bytes);
        assertEquals(28,bytes.position());
        assertEquals(ByteOrder.BIG_ENDIAN,bytes.order());
        bytes.order(ByteOrder.LITTLE_ENDIAN).position(4);
        assertArrayEquals(new int[]{2,1,1,2,2,3},new int[]{bytes.getInt(),bytes.getInt(),bytes.getInt(),bytes.getInt(),bytes.getInt(),bytes.getInt()});
    }

    @Test void rejectsBadSizeBudgetKindsAndRevisionsBeforeCopying() {
        assertThrows(IllegalArgumentException.class,()->new VoxelSceneSnapshot(0,0,0,0,1,1,1,1,new int[0]));
        assertThrows(IllegalArgumentException.class,()->new VoxelSceneSnapshot(0,0,0,Integer.MAX_VALUE,Integer.MAX_VALUE,Integer.MAX_VALUE,1,1,new int[1]));
        assertThrows(IllegalArgumentException.class,()->new VoxelSceneSnapshot(0,0,0,2,1,1,1,1,new int[1]));
        assertThrows(IllegalArgumentException.class,()->new VoxelSceneSnapshot(0,0,0,1,1,1,1,1,new int[1],19));
        assertThrows(IllegalArgumentException.class,()->new VoxelSceneSnapshot(0,0,0,1,1,1,1,1,new int[]{4}));
        assertThrows(IllegalArgumentException.class,()->new VoxelSceneSnapshot(0,0,0,1,1,1,-1,1,new int[1]));
        var snapshot = new VoxelSceneSnapshot(0,0,0,1,1,1,1,1,new int[1]);
        ByteBuffer small = ByteBuffer.allocate(20).position(1);
        assertThrows(IllegalArgumentException.class,()->snapshot.writeTo(small));
        assertEquals(1,small.position());
    }
}
