package io.github.andrexarlay.vulkancompat.rtx.upscaler;

import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import static org.junit.jupiter.api.Assertions.*;

class VelocityUniformsTest {
    private static Matrix4f matrix(ByteBuffer data, int offset) {
        return new Matrix4f(data.slice(offset, 64).order(ByteOrder.nativeOrder()).asFloatBuffer());
    }
    @Test void preservesColumnMajorMatricesBobDeltaDepthJitterAndRawBdaBits() {
        ByteBuffer data = ByteBuffer.allocateDirect(384).order(ByteOrder.nativeOrder()).position(8).limit(376);
        var current = new Matrix4f().translation(1, 2, 3).rotateXYZ(0.2f, 0.3f, 0.4f);
        var previous = new Matrix4f().translation(-2, 4, -6);
        var entity = new Matrix4f().translation(7, 8, 9);
        long entityMotion = 0x7fc00000deadbee0L, mask = 0x100000004L, previousDepth = 0x200000008L;
        VelocityPipeline.fillUniforms(data, current, previous, new Vec3(3, -4, 5), new Vector3f(0.1f, -0.2f, 0.3f),
            1920, 1080, entity, null, 419, true, VelocityPipeline.DEPTH_LINEAR_VIEW_Z,
            entityMotion, mask, previousDepth, 0.25f, -0.375f, -0.125f, 0.5f);
        assertEquals(8, data.position()); assertEquals(376, data.limit());
        ByteBuffer payload = data.slice().order(ByteOrder.nativeOrder());
        assertEquals(current, matrix(payload, 0)); assertEquals(previous, matrix(payload, 64));
        assertEquals(entity, matrix(payload, 176)); assertEquals(entity, matrix(payload, 240));
        assertEquals(3f, payload.getFloat(128)); assertEquals(-4f, payload.getFloat(132)); assertEquals(5f, payload.getFloat(136));
        assertEquals(1f, payload.getFloat(140)); assertEquals(0.1f, payload.getFloat(144));
        assertEquals(-0.2f, payload.getFloat(148)); assertEquals(0.3f, payload.getFloat(152));
        assertEquals(1920f, payload.getFloat(160)); assertEquals(1080f, payload.getFloat(164));
        assertEquals(1f / 1920, payload.getFloat(168)); assertEquals(1f / 1080, payload.getFloat(172));
        assertEquals(1f, payload.getFloat(304)); assertEquals(419f, payload.getFloat(308));
        assertEquals(1f, payload.getFloat(312)); assertEquals(16f, payload.getFloat(316));
        assertEquals(entityMotion, payload.getLong(320)); assertEquals(mask, payload.getLong(328));
        assertEquals(previousDepth, payload.getLong(336)); assertEquals(0, payload.getLong(344));
        assertEquals(0.25f, payload.getFloat(352)); assertEquals(-0.375f, payload.getFloat(356));
        assertEquals(-0.125f, payload.getFloat(360)); assertEquals(0.5f, payload.getFloat(364));
    }
    @Test void disabledOptionalInputsStayZeroAndAllDepthConventionsRemainAvailable() {
        for (int convention : new int[]{0, 1, 2}) {
            var data = ByteBuffer.allocateDirect(368).order(ByteOrder.nativeOrder());
            for (int i = 0; i < data.capacity(); i++) data.put(i, (byte)0xff);
            VelocityPipeline.fillUniforms(data, null, null, null, new Vector3f(), 8, 9, null, null, 0, false,
                convention, 0, 0, 0, 0, 0, 0, 0);
            assertEquals(new Matrix4f(), matrix(data, 0));
            assertEquals(new Matrix4f(), matrix(data, 64));
            assertEquals(new Matrix4f(), matrix(data, 176));
            assertEquals(new Matrix4f(), matrix(data, 240));
            assertEquals((float)convention, data.getFloat(140));
            assertEquals(0f, data.getFloat(304));
            for (int offset = 320; offset < 368; offset += 8) assertEquals(0, data.getLong(offset));
        }
    }
}
