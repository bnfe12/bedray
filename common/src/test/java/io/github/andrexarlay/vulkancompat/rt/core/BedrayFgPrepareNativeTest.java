package io.github.andrexarlay.vulkancompat.rt.core;

import org.junit.jupiter.api.Test;
import java.lang.foreign.Arena;
import static java.lang.foreign.ValueLayout.*;
import static org.junit.jupiter.api.Assertions.*;

class BedrayFgPrepareNativeTest {
    @Test void packetKeepsBothResolutionsAndDistinctProducerViews() {
        try(Arena arena=Arena.ofConfined()) {
            var value=BedrayFgPrepareNative.frame(arena,11,17,19,21,22,23,24,25,26,101,51,19,9);
            assertEquals(104,value.byteSize()); assertEquals(2,value.get(JAVA_INT,0)); assertEquals(104,value.get(JAVA_INT,4));
            long[] handles={11,17,19,21,22,23,24,25,26};
            for(int i=0;i<handles.length;++i) assertEquals(handles[i],value.get(JAVA_LONG,8L+i*8L));
            assertEquals(101,value.get(JAVA_INT,80)); assertEquals(51,value.get(JAVA_INT,84));
            assertEquals(19,value.get(JAVA_INT,88)); assertEquals(9,value.get(JAVA_INT,92)); assertEquals(0,value.get(JAVA_LONG,96));
        }
    }
    @Test void invalidDimensionsAndAliasesRejectBeforeDowncall() {
        try(Arena arena=Arena.ofConfined()) {
            assertThrows(IllegalArgumentException.class,()->BedrayFgPrepareNative.frame(arena,0,17,19,21,22,23,24,25,26,101,51,19,9));
            assertThrows(IllegalArgumentException.class,()->BedrayFgPrepareNative.frame(arena,11,17,19,21,21,23,24,25,26,101,51,19,9));
            assertThrows(IllegalArgumentException.class,()->BedrayFgPrepareNative.frame(arena,11,17,19,21,22,23,24,24,26,101,51,19,9));
            assertThrows(IllegalArgumentException.class,()->BedrayFgPrepareNative.frame(arena,11,17,19,21,22,23,24,25,25,101,51,19,9));
            assertThrows(IllegalArgumentException.class,()->BedrayFgPrepareNative.frame(arena,11,17,19,21,22,23,24,25,26,0,51,19,9));
            assertThrows(IllegalArgumentException.class,()->BedrayFgPrepareNative.frame(arena,11,17,19,21,22,23,24,25,26,101,51,-1,9));
        }
    }
}
