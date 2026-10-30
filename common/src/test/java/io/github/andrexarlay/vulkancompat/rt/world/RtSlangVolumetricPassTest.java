package io.github.andrexarlay.vulkancompat.rt.world;

import org.junit.jupiter.api.Test;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import static org.junit.jupiter.api.Assertions.*;

class RtSlangVolumetricPassTest {
    @Test void typedFrameAndPushUseOneExposureAndCurrentMinusPreviousRebase() {
        var p = new RtFrameParams();
        p.frame = 17; p.cameraDeltaX = 1; p.cameraDeltaY = 2; p.cameraDeltaZ = 3;
        p.cameraY = 80; p.flipY = true; p.key.bodyAlpha = 0.25f;
        p.exposure = 1.35f;   // not the default: the lanes must carry the frame's number, not a constant
        var frame = new RtPathTraceSlangPass.Frame();
        RtSlangFrameData.fill(frame, p, 19, 9, 0, 0);
        assertEquals(1.35f, frame.exposure.x); assertEquals(19, frame.width); assertEquals(9, frame.height);
        assertEquals(0f, frame.jitter.x); assertEquals(1f / 19, frame.jitter.z);
        var march = ByteBuffer.allocate(96).order(ByteOrder.nativeOrder());
        var apply = ByteBuffer.allocate(24).order(ByteOrder.nativeOrder());
        RtSlangVolumetricPass.fillPush(march, apply, p, 12, 0.5f, 64);
        assertEquals(1f, march.getFloat(32)); assertEquals(2f, march.getFloat(36)); assertEquals(3f, march.getFloat(40));
        assertEquals(0.75f, march.getFloat(44)); assertEquals(80f, march.getFloat(60));
        assertEquals(frame.exposure.x, march.getFloat(48)); assertEquals(march.getFloat(48), apply.getFloat(0));
        assertEquals(64f, march.getFloat(28)); assertEquals(64f, apply.getFloat(4));
        assertEquals(17, march.getInt(72)); assertEquals(17, apply.getInt(12));
        assertEquals(0, march.getInt(76)); assertEquals(1, march.getInt(80)); assertEquals(1, march.getInt(84));
        assertEquals(1, apply.getInt(8)); assertEquals(0, march.getInt(92));
    }
    @Test void sharedCameraConversionPreservesTheBobOriginWithoutChangingWorldHeight() {
        var p = new RtFrameParams();
        p.bobX = 0.1f; p.bobY = 0.2f; p.cameraY = 80;
        var frame = new RtPathTraceSlangPass.Frame();
        RtSlangFrameData.fill(frame, p, 16, 8, 3, 1);
        assertEquals(p.bobX, frame.origin.x);
        assertEquals(p.bobY, frame.origin.y);
        var march = ByteBuffer.allocate(96).order(ByteOrder.nativeOrder());
        var apply = ByteBuffer.allocate(24).order(ByteOrder.nativeOrder());
        RtSlangVolumetricPass.fillPush(march, apply, p, 12, 0.5f, 64);
        assertEquals(80f, march.getFloat(60), "raw camera height excludes bob; shader sample origin adds it once");
        assertNotEquals(0f, frame.jitter.x);
    }
}
