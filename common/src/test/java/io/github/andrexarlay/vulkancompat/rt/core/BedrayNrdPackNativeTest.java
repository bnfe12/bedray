package io.github.andrexarlay.vulkancompat.rt.core;

import org.junit.jupiter.api.Test;
import java.lang.foreign.Arena;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import static java.lang.foreign.ValueLayout.*;
import static org.junit.jupiter.api.Assertions.*;

class BedrayNrdPackNativeTest {
    private static long[] views() { long[] result = new long[14]; for (int i=0; i<14; ++i) result[i] = 21+i; return result; }
    private static BedrayNrdPackNative.Settings settings() {
        return new BedrayNrdPackNative.Settings(2,1,0,3,3,.1f,20,100,1.1f,4096);
    }

    @Test void coarsePacketMatchesEveryNativeFieldAndCopiesViewIdentities() {
        try (Arena arena = Arena.ofConfined()) {
            long[] views = views();
            var result = BedrayNrdPackNative.frame(arena,11,17,views,0,19,9,settings());
            assertEquals(200,result.byteSize()); assertEquals(2,result.get(JAVA_INT,0)); assertEquals(200,result.get(JAVA_INT,4));
            assertEquals(11,result.get(JAVA_LONG,8)); assertEquals(17,result.get(JAVA_LONG,16));
            for(int i=0; i<14; ++i) assertEquals(21+i,result.get(JAVA_LONG,24L+i*8L));
            views[0]=99; assertEquals(21,result.get(JAVA_LONG,24));
            assertEquals(0,result.get(JAVA_INT,136)); assertEquals(19,result.get(JAVA_INT,140)); assertEquals(9,result.get(JAVA_INT,144));
            assertEquals(2,result.get(JAVA_INT,148)); assertEquals(1,result.get(JAVA_INT,152)); assertEquals(0,result.get(JAVA_INT,156));
            assertEquals(3,result.get(JAVA_INT,160)); assertEquals(3,result.get(JAVA_FLOAT,164)); assertEquals(.1f,result.get(JAVA_FLOAT,168));
            assertEquals(20,result.get(JAVA_FLOAT,172)); assertEquals(100,result.get(JAVA_FLOAT,176)); assertEquals(1.1f,result.get(JAVA_FLOAT,180));
            assertEquals(4096,result.get(JAVA_LONG,184)); assertEquals(0,result.get(JAVA_LONG,192));
        }
    }

    @Test void invalidRangesAndSettingsAreRejectedBeforeDowncall() {
        try(Arena arena=Arena.ofConfined()) {
            assertThrows(IllegalArgumentException.class,()->BedrayNrdPackNative.frame(arena,0,17,views(),0,19,9,settings()));
            assertThrows(IllegalArgumentException.class,()->BedrayNrdPackNative.frame(arena,11,17,views(),2,19,9,settings()));
            assertThrows(IllegalArgumentException.class,()->BedrayNrdPackNative.frame(arena,11,17,views(),0,Integer.MAX_VALUE,9,settings()));
            assertThrows(IllegalArgumentException.class,()->new BedrayNrdPackNative.Settings(0,1,0,0,3,.1f,20,100,1,0));
            assertThrows(IllegalArgumentException.class,()->new BedrayNrdPackNative.Settings(2,1,2,0,3,.1f,20,100,1,0));
            assertThrows(IllegalArgumentException.class,()->new BedrayNrdPackNative.Settings(2,1,0,0,3,.1f,20,Float.NaN,1,0));
            assertThrows(IllegalArgumentException.class,()->new BedrayNrdPackNative.Settings(2,1,0,0,3,.1f,20,100,1,1));
            var overflow=new BedrayNrdPackNative.Settings(2,1,0,0,3,.1f,20,100,1,-16);
            assertThrows(IllegalArgumentException.class,()->BedrayNrdPackNative.frame(arena,11,17,views(),0,19,9,overflow));
        }
    }

    @Test void compatibilityInputKeepsLegacyOffsetsAndExtentAgreement() {
        var input=ByteBuffer.allocate(56).order(ByteOrder.nativeOrder());
        input.putInt(0,2).putInt(4,1).putInt(8,0).putInt(12,3);
        input.putFloat(16,3).putFloat(20,.1f).putFloat(24,20).putFloat(28,100);
        input.putInt(32,19).putInt(36,9).putFloat(40,1.1f).putLong(48,4096);
        assertEquals(settings(),BedrayNrdPackNative.Settings.fromPush(input,19,9));
        assertThrows(IllegalArgumentException.class,()->BedrayNrdPackNative.Settings.fromPush(input,20,9));
    }
}
