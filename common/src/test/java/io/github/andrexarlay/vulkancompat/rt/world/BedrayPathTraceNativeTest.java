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
    @Test void motionRangeIsOneVec4PerPixelOnHeapAndNativeMemory() {
        try (Arena arena = Arena.ofConfined()) {
            for (var storage : new MemorySegment[]{arena.allocate(48, 8), MemorySegment.ofArray(new byte[48])}) {
                storage.fill((byte)0x5a);
                var out = storage.asSlice(8, 32);
                BedrayPathTraceNative.fillMotion(out, 71, 3, 5);
                assertEquals(71, out.get(JAVA_LONG_UNALIGNED, 0));
                assertEquals(0, out.get(JAVA_LONG_UNALIGNED, 8));
                assertEquals(240, out.get(JAVA_LONG_UNALIGNED, 16));
                assertEquals(0, out.get(JAVA_LONG_UNALIGNED, 24));
                for (int i = 0; i < 8; i++) {
                    assertEquals((byte)0x5a, storage.get(JAVA_BYTE, i));
                    assertEquals((byte)0x5a, storage.get(JAVA_BYTE, 40 + i));
                }
                assertThrows(IllegalArgumentException.class, () -> BedrayPathTraceNative.fillMotion(out, 0, 3, 5));
                assertThrows(IllegalArgumentException.class, () -> BedrayPathTraceNative.fillMotion(out, 71, 0, 5));
                assertThrows(ArithmeticException.class,
                    () -> BedrayPathTraceNative.fillMotion(out, 71, Integer.MAX_VALUE, Integer.MAX_VALUE));
            }
        }
    }
    private static RtPathTraceSlangPass.Dispatch dispatch(int atlas, int first, int count) {
        return new RtPathTraceSlangPass.Dispatch(3, 8, 7, 5, 6, 0.01f, 0.5f, 72f, 64f, 0.5f,
            2, 0.25f, 1f, 2f, 3f, 8, 4f, 5f, 6f, 1, 0, atlas, first, count, 1.25f, 0f, 0f, 0f);
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

    @Test void guidePacketsMapDenoiserChannelsToShaderBindingsOnHeapAndNativeMemory() {
        var guides = RtPathTraceSlangPass.Guides.of(new long[]{101, 102, 103, 104, 105, 106, 107});
        long[] bindingOrder = {101, 102, 106, 103, 104, 105, 107};
        try (Arena arena = Arena.ofConfined()) {
            for (MemorySegment storage : new MemorySegment[]{arena.allocate(80, 8), MemorySegment.ofArray(new byte[80])}) {
                storage.fill((byte)0x5a);
                var out = storage.asSlice(8, 64);
                BedrayPathTraceNative.fillGuides(out, guides);
                assertEquals(BedrayCoreLayouts.ABI_VERSION, out.get(JAVA_INT_UNALIGNED, 0));
                assertEquals(64, out.get(JAVA_INT_UNALIGNED, 4));
                for (int i = 0; i < bindingOrder.length; i++)
                    assertEquals(bindingOrder[i], out.get(JAVA_LONG_UNALIGNED, 8 + i * 8), "guide binding " + (26 + i));
                for (int i = 0; i < 8; i++) {
                    assertEquals((byte)0x5a, storage.get(JAVA_BYTE, i));
                    assertEquals((byte)0x5a, storage.get(JAVA_BYTE, 72 + i));
                }
                assertThrows(IllegalArgumentException.class, () -> BedrayPathTraceNative.fillGuides(out.asSlice(0, 63), guides));
                assertThrows(IllegalArgumentException.class, () -> BedrayPathTraceNative.fillGuides(out, null));
                var partial = RtPathTraceSlangPass.Guides.of(new long[]{101, 102, 103, 104, 105, 106, 0});
                assertThrows(IllegalArgumentException.class, () -> BedrayPathTraceNative.fillGuides(out, partial));
            }
        }
    }
}
