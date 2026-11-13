package io.github.andrexarlay.vulkancompat.rtx.raytracing;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.lwjgl.vulkan.VK10.VK_MEMORY_HEAP_DEVICE_LOCAL_BIT;

/** The auto-policy's memory input is the sum of DEVICE_LOCAL heaps; host-visible heaps must not count toward video memory. */
class GiDeviceProbeTest {

    @Test
    void sumsOnlyDeviceLocalHeaps() {
        long[] sizes = {8L << 30, 16L << 30};
        int[] flags = {VK_MEMORY_HEAP_DEVICE_LOCAL_BIT, 0};
        assertEquals(8L << 30, GiDeviceProbe.deviceLocalBytes(sizes, flags));
    }

    @Test
    void multipleDeviceLocalHeapsAddUp() {
        long[] sizes = {4L << 30, 4L << 30, 8L << 30};
        int[] flags = {VK_MEMORY_HEAP_DEVICE_LOCAL_BIT, VK_MEMORY_HEAP_DEVICE_LOCAL_BIT, 0};
        assertEquals(8L << 30, GiDeviceProbe.deviceLocalBytes(sizes, flags));
    }

    @Test
    void noDeviceLocalHeapIsZero() {
        assertEquals(0, GiDeviceProbe.deviceLocalBytes(new long[] {16L << 30}, new int[] {0}));
        assertEquals(0, GiDeviceProbe.deviceLocalBytes(new long[0], new int[0]));
    }
}