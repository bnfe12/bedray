package io.github.andrexarlay.vulkancompat.rt.core;

import org.junit.jupiter.api.Test;
import java.lang.foreign.Arena;
import java.nio.ByteBuffer;
import static java.lang.foreign.ValueLayout.*;
import static org.junit.jupiter.api.Assertions.*;

class BedraySpatialNativeTest {
    @Test void framePacksEveryOffsetAndCopiesHeapAndDirectSlicesWithoutMovingThem() {
        var first = ByteBuffer.allocate(96).position(8).limit(88); first.put(8,(byte)71);
        var second = ByteBuffer.allocateDirect(48).position(8).limit(40); second.put(8,(byte)72);
        try (Arena arena = Arena.ofConfined()) {
            var frame = BedraySpatialNative.frame(arena,11,new long[]{1,2,3},21,22,31,32,33,19,13,35,25,
                BedraySpatialNative.FSR1,BedraySpatialNative.SHARPEN,first,second);
            assertEquals(136,frame.byteSize()); assertEquals(2,frame.get(JAVA_INT,0)); assertEquals(136,frame.get(JAVA_INT,4));
            assertEquals(11,frame.get(JAVA_LONG,8));
            for (int i = 0; i < 3; i++) assertEquals(i+1,frame.get(JAVA_LONG,16L+i*8L));
            assertEquals(21,frame.get(JAVA_LONG,40)); assertEquals(22,frame.get(JAVA_LONG,48));
            assertEquals(31,frame.get(JAVA_LONG,56)); assertEquals(32,frame.get(JAVA_LONG,64)); assertEquals(33,frame.get(JAVA_LONG,72));
            assertEquals(19,frame.get(JAVA_INT,80)); assertEquals(13,frame.get(JAVA_INT,84));
            assertEquals(35,frame.get(JAVA_INT,88)); assertEquals(25,frame.get(JAVA_INT,92));
            assertEquals(0,frame.get(JAVA_INT,96)); assertEquals(1,frame.get(JAVA_INT,100));
            assertEquals(80,frame.get(JAVA_INT,112)); assertEquals(32,frame.get(JAVA_INT,116)); assertEquals(0,frame.get(JAVA_LONG,128));
            first.put(8,(byte)0); second.put(8,(byte)0);
            assertEquals(71,frame.get(ADDRESS,104).reinterpret(80).get(JAVA_BYTE,0));
            assertEquals(72,frame.get(ADDRESS,120).reinterpret(32).get(JAVA_BYTE,0));
            assertEquals(8,first.position()); assertEquals(8,second.position());
        }
    }
    @Test void unsharpenedEasuAndNisNeedOnlyTheirActiveProgramAndPush() {
        try (Arena arena = Arena.ofConfined()) {
            var easu = BedraySpatialNative.frame(arena,11,new long[]{1,0,0},21,0,31,0,33,19,13,35,25,0,0,ByteBuffer.allocate(80),null);
            assertEquals(0,easu.get(JAVA_INT,116)); assertEquals(0,easu.get(ADDRESS,120).address());
            var nis = BedraySpatialNative.frame(arena,11,new long[]{0,0,3},21,0,31,0,33,19,13,35,25,1,0,ByteBuffer.allocate(112),null);
            assertEquals(112,nis.get(JAVA_INT,112)); assertEquals(1,nis.get(JAVA_INT,96));
            assertThrows(IllegalArgumentException.class,() -> BedraySpatialNative.frame(arena,11,new long[]{0,0,3},21,0,31,0,33,17,13,35,25,1,0,ByteBuffer.allocate(112),null));
            assertThrows(IllegalArgumentException.class,() -> BedraySpatialNative.frame(arena,11,new long[]{1,2,3},21,22,31,33,33,19,13,35,25,0,1,ByteBuffer.allocate(80),ByteBuffer.allocate(32)));
            assertThrows(IllegalArgumentException.class,() -> BedraySpatialNative.frame(arena,11,new long[]{1,2,3},21,22,31,32,33,19,13,35,25,0,0,ByteBuffer.allocate(79),null));
            assertThrows(IllegalArgumentException.class,() -> BedraySpatialNative.frame(arena,11,new long[]{1,2,3},21,22,31,32,33,19,13,35,25,1,1,ByteBuffer.allocate(112),ByteBuffer.allocate(32)));
        }
    }
}
