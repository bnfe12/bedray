package io.github.andrexarlay.vulkancompat.rt.world;

import org.junit.jupiter.api.Test;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import static org.junit.jupiter.api.Assertions.*;

class RrHardwareDepthTest {
    @Test void packetUsesTheActualTraceBasisJitterAndFullProjectionRows() {
        var f = new RtPathTraceSlangPass.Frame();
        f.forward.set(1,2,3,4); f.right.set(5,6,7,8); f.up.set(9,10,11,12);
        f.origin.set(13,14,15,16); f.jitter.set(0.125f,-0.375f,1f/1920,1f/1080);
        f.width=1920; f.height=1080;
        f.viewProjection.set(1,2,3,4, 5,6,7,8, 9,10,11,12, 13,14,15,16);
        var b=ByteBuffer.allocateDirect(128).order(ByteOrder.nativeOrder());
        RrHardwareDepth.writePush(b,f);
        for(int i=0;i<16;i++) assertEquals(i+1f,b.getFloat(i*4));
        for(int i=0;i<4;i++) {
            assertEquals(3f+4*i,b.getFloat(64+i*4));
            assertEquals(4f+4*i,b.getFloat(80+i*4));
        }
        assertEquals(0.125f,b.getFloat(96)); assertEquals(-0.375f,b.getFloat(100));
        assertEquals(1920,b.getInt(112)); assertEquals(1080,b.getInt(116)); assertEquals(0L,b.getLong(120));
        assertThrows(IllegalArgumentException.class,()->RrHardwareDepth.writePush(ByteBuffer.allocate(128),f));
    }
}
