package io.github.andrexarlay.vulkancompat.rt.world;

import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RtOutputResolveTest {
    private static RtPathTraceSlangPass.Frame frame() {
        var frame = new RtPathTraceSlangPass.Frame();
        frame.forward.set(0.1f, 0.2f, 0.3f, 0.4f);
        frame.right.set(0.5f, 0.6f, 0.7f, 0.8f);
        frame.up.set(0.9f, 1.0f, 1.1f, 1.2f);
        frame.jitter.set(0.25f, -0.5f, 0f, 0f);
        return frame;
    }

    @Test
    void directPushCarriesCameraBasisJitterAndOutputExtent() {
        ByteBuffer push = MemoryUtil.memAlloc(RtOutputResolve.PUSH_BYTES).order(ByteOrder.nativeOrder());
        try {
            RtOutputResolve.writePush(push, frame(), 1280, 720, true);

            assertEquals(0.1f, push.getFloat(0));
            assertEquals(0.4f, push.getFloat(12));
            assertEquals(0.5f, push.getFloat(16));
            assertEquals(0.8f, push.getFloat(28));
            assertEquals(0.9f, push.getFloat(32));
            assertEquals(1.2f, push.getFloat(44));
            assertEquals(0.25f, push.getFloat(48));
            assertEquals(-0.5f, push.getFloat(52));
            assertEquals(1280, push.getInt(56));
            assertEquals(720, push.getInt(60));
            assertEquals(1, push.getInt(64));
        } finally {
            MemoryUtil.memFree(push);
        }
    }

    @Test
    void heapBufferIsRejectedBeforeJomlCanWriteThroughUnsafe() {
        ByteBuffer heap = ByteBuffer.allocate(RtOutputResolve.PUSH_BYTES).order(ByteOrder.nativeOrder());
        assertThrows(IllegalArgumentException.class,
            () -> RtOutputResolve.writePush(heap, frame(), 1, 1, false));
    }

    @Test
    void shortDirectBufferIsRejected() {
        ByteBuffer shortBuffer = MemoryUtil.memAlloc(RtOutputResolve.PUSH_BYTES - 1).order(ByteOrder.nativeOrder());
        try {
            assertThrows(IllegalArgumentException.class,
                () -> RtOutputResolve.writePush(shortBuffer, frame(), 1, 1, false));
        } finally {
            MemoryUtil.memFree(shortBuffer);
        }
    }
}
