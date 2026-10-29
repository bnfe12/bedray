package io.github.andrexarlay.vulkancompat.rt.world;

import io.github.andrexarlay.vulkancompat.rt.core.BedrayCoreLayouts;
import org.junit.jupiter.api.Test;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import static java.lang.foreign.ValueLayout.*;
import static org.junit.jupiter.api.Assertions.*;

class BedrayPathTraceNativeTest {
    private static RtPathTraceSlangPass.Dispatch dispatch(int atlas, int first, int count) {
        return new RtPathTraceSlangPass.Dispatch(3, 8, 7, 5, 6, 0.01f, 0.5f, 72f, 64f, 0.5f,
            2, 0.25f, 1f, 2f, 3f, 8, 4f, 5f, 6f, 1, 0, atlas, first, count, 1.25f);
    }

    @Test void heapAndNativePacketsPreserveTheCanonicalPushAndBothRangeGuards() {
        var dispatch = dispatch(0, 0, 0);
        ByteBuffer expected = ByteBuffer.allocate(112).order(ByteOrder.nativeOrder());
        dispatch.writeTo(expected);
        try (Arena arena = Arena.ofConfined()) {
            for (MemorySegment storage : new MemorySegment[]{arena.allocate(128, 8), MemorySegment.ofArray(new byte[128])}) {
                storage.fill((byte)0x5a);
                var out = storage.asSlice(8, 112);
                BedrayPathTraceNative.fillPath(out, dispatch, RtPathTraceSlangPass.Entities.NONE);
                for (int i = 0; i < 112; i++) assertEquals(expected.get(i), out.get(JAVA_BYTE, i), "path byte " + i);
                assertEquals(6, out.get(JAVA_INT_UNALIGNED, BedrayCoreLayouts.PTP_FLAGS));
                assertEquals(Float.floatToRawIntBits(1.25f), out.get(JAVA_INT_UNALIGNED, BedrayCoreLayouts.PTP_ENTITY_STATE + 12));
                for (int i = 0; i < 8; i++) {
                    assertEquals((byte)0x5a, storage.get(JAVA_BYTE, i));
                    assertEquals((byte)0x5a, storage.get(JAVA_BYTE, 120 + i));
                }
            }
        }
    }

    @Test void entityMetadataFollowsBoundRangesWithoutDroppingTheAutoNormalLane() {
        try (Arena arena = Arena.ofConfined()) {
            var out = arena.allocate(BedrayCoreLayouts.PT_PATH);
            BedrayPathTraceNative.fillPath(out, dispatch(256, 10, 20), RtPathTraceSlangPass.Entities.NONE);
            assertEquals(0, out.get(JAVA_INT, BedrayCoreLayouts.PTP_ENTITY_STATE));
            assertEquals(0, out.get(JAVA_INT, BedrayCoreLayouts.PTP_ENTITY_STATE + 4));
            assertEquals(0, out.get(JAVA_INT, BedrayCoreLayouts.PTP_ENTITY_STATE + 8));
            assertEquals(1.25f, out.get(JAVA_FLOAT, BedrayCoreLayouts.PTP_ENTITY_STATE + 12));
            assertThrows(IllegalArgumentException.class,
                () -> BedrayPathTraceNative.fillPath(out.asSlice(0, 111), dispatch(0, 0, 0), RtPathTraceSlangPass.Entities.NONE));
        }
    }
}
