package io.github.andrexarlay.bedray.api.v1;

import org.junit.jupiter.api.Test;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import static org.junit.jupiter.api.Assertions.*;

class ComputeSceneFrameTest {
    private VoxelSceneSnapshot snapshot() {
        return new VoxelSceneSnapshot(29_999_984,64,-20_000_016,1,1,1,1,1,new int[]{1});
    }

    @Test void copiesMatrixAndSerializesExactPushLayout() {
        float[] matrix = new float[16];
        for(int i=0;i<16;i++) matrix[i]=i+0.25f;
        var frame = new ComputeSceneFrame(snapshot(),matrix,2.5f,3.5f,4.5f,0.01f,-0.02f,0.03f);
        matrix[0]=99;
        ByteBuffer bytes = ByteBuffer.allocate(112).order(ByteOrder.BIG_ENDIAN).position(4);
        frame.writeCameraTo(bytes);
        assertEquals(100,bytes.position());
        assertEquals(ByteOrder.BIG_ENDIAN,bytes.order());
        bytes.order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(0.25f,bytes.getFloat(4));
        assertEquals(15.25f,bytes.getFloat(64));
        assertEquals(2.5f,bytes.getFloat(68));
        assertEquals(4.5f,bytes.getFloat(76));
        assertEquals(0,bytes.getFloat(80));
        assertEquals(0.01f,bytes.getFloat(84));
        assertEquals(0.03f,bytes.getFloat(92));
        assertEquals(0,bytes.getFloat(96));
    }

    @Test void rejectsNonFiniteCameraOrMatrixAndShortDestination() {
        float[] matrix = new float[16];
        assertThrows(IllegalArgumentException.class,()->new ComputeSceneFrame(snapshot(),new float[15],0,0,0,0,0,0));
        assertThrows(IllegalArgumentException.class,()->new ComputeSceneFrame(snapshot(),matrix,Float.NaN,0,0,0,0,0));
        assertThrows(IllegalArgumentException.class,()->new ComputeSceneFrame(snapshot(),matrix,0,0,0,0,Float.POSITIVE_INFINITY,0));
        matrix[7]=Float.NaN;
        assertThrows(IllegalArgumentException.class,()->new ComputeSceneFrame(snapshot(),matrix,0,0,0,0,0,0));
        var frame = new ComputeSceneFrame(snapshot(),new float[16],0,0,0,0,0,0);
        ByteBuffer small = ByteBuffer.allocate(96).position(1);
        assertThrows(IllegalArgumentException.class,()->frame.writeCameraTo(small));
        assertEquals(1,small.position());
    }
}
