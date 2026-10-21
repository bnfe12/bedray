package io.github.andrexarlay.bedray.api.v1;

import org.junit.jupiter.api.Test;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import static org.junit.jupiter.api.Assertions.*;

class CameraSnapshotTest {
    private float[] identity() { return new float[]{1,0,0,0,0,1,0,0,0,0,1,0,0,0,0,1}; }

    @Test void immutableMatricesAndOffsetsHaveExactPushLayout() {
        float[] projection=identity(), view=identity(), bob={0.01f,-0.02f,0.03f};
        projection[0]=2; projection[5]=3; view[12]=4;
        var camera=new CameraSnapshot(29_999_984.25,64.5,-20_000_016.75,projection,view,bob);
        projection[0]=99; bob[0]=99; camera.inverseView()[12]=99;
        ByteBuffer bytes=ByteBuffer.allocate(120).order(ByteOrder.BIG_ENDIAN).position(4);
        bytes.putInt(84,0x12345678);
        camera.writePushConstants(bytes,29_999_984,64,-20_000_016);
        assertEquals(4,bytes.position()); assertEquals(ByteOrder.BIG_ENDIAN,bytes.order());
        bytes.order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(2,bytes.getFloat(4)); assertEquals(3,bytes.getFloat(24));
        assertEquals(4,bytes.getFloat(52));
        assertEquals(0.25f,bytes.getFloat(68)); assertEquals(0.5f,bytes.getFloat(72));
        assertEquals(-0.75f,bytes.getFloat(76));
        assertEquals(0.01f,bytes.getFloat(100)); assertEquals(0.03f,bytes.getFloat(108));
        assertEquals(0x78563412,bytes.getInt(84));
    }

    @Test void invalidCameraAndShortPushDestinationFailClosed() {
        assertThrows(IllegalArgumentException.class,()->new CameraSnapshot(0,Double.NaN,0,identity(),identity()));
        assertThrows(IllegalArgumentException.class,()->new CameraSnapshot(0,0,0,new float[15],identity()));
        float[] invalid=identity(); invalid[2]=Float.POSITIVE_INFINITY;
        assertThrows(IllegalArgumentException.class,()->new CameraSnapshot(0,0,0,invalid,identity()));
        assertThrows(IllegalArgumentException.class,()->new CameraSnapshot(0,0,0,identity(),identity(),new float[]{0,Float.NaN,0}));
        var camera=new CameraSnapshot(0,0,0,identity(),identity());
        ByteBuffer bytes=ByteBuffer.allocate(112).position(1);
        assertThrows(IllegalArgumentException.class,()->camera.writePushConstants(bytes,0,0,0));
        assertEquals(1,bytes.position());
    }
}
