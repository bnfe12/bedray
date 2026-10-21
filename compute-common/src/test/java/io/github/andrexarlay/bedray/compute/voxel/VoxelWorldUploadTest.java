package io.github.andrexarlay.bedray.compute.voxel;

import io.github.andrexarlay.bedray.api.v1.*;
import org.junit.jupiter.api.Test;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class VoxelWorldUploadTest {
    private CameraSnapshot camera(double x) {
        float[] identity={1,0,0,0,0,1,0,0,0,0,1,0,0,0,0,1};
        return new CameraSnapshot(x,24,24,identity,identity);
    }
    @Test void immutableTilesUpdateOnlyChangesAndEvictMissingSections() {
        var upload=new VoxelWorldUpload();
        byte[] cells=new byte[4096]; cells[0]=1; cells[4095]=2;
        var a=new SectionSnapshot(1,1,1,1,cells);
        var first=new WorldSnapshot(1,1,camera(24),List.of(a));
        var initial=upload.update(first);
        assertEquals(27,initial.size());
        ByteBuffer payload=ByteBuffer.allocate(VoxelWorldUpload.TILE_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        initial.get(13).writeTo(payload);
        assertEquals(1,payload.getInt(0)); assertEquals(2,payload.getInt(payload.capacity()-4));
        assertEquals(0,upload.update(first).size());
        cells[0]=0;
        var b=new SectionSnapshot(1,1,1,2,cells);
        var changed=upload.update(new WorldSnapshot(1,2,camera(24),List.of(b)));
        assertEquals(1,changed.size()); assertEquals(13,changed.getFirst().slot());
        assertEquals(16L+13L*16384,changed.getFirst().offset());
        var removed=upload.update(new WorldSnapshot(1,3,camera(24),List.of()));
        assertEquals(1,removed.size()); payload.clear(); removed.getFirst().writeTo(payload);
        assertEquals(VoxelWorldUpload.UNKNOWN,payload.getInt(0));
        assertEquals(27,upload.update(new WorldSnapshot(2,1,camera(24),List.of())).size());
        assertThrows(IllegalArgumentException.class,()->new VoxelWorldUpload.Tile(27,null));
    }
    @Test void shiftingWindowUpdatesMovedSlotsAndRetainsUnknownSlots() {
        var upload=new VoxelWorldUpload();
        var a=new SectionSnapshot(1,1,1,1,new byte[4096]);
        upload.update(new WorldSnapshot(1,1,camera(24),List.of(a)));
        var shifted=upload.update(new WorldSnapshot(1,1,camera(40),List.of(a)));
        assertEquals(2,shifted.size());
        assertEquals(16,upload.originX());
        assertTrue(shifted.stream().anyMatch(t->t.slot()==13&&t.section()==null));
        assertTrue(shifted.stream().anyMatch(t->t.slot()==12&&t.section()==a));
    }
}
