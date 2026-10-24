package io.github.andrexarlay.vulkancompat.shaderhost.iris;

import io.github.andrexarlay.vulkancompat.rtx.frame.WorldFrame;
import io.github.andrexarlay.vulkancompat.shaderhost.glsl.IrisUniforms;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Fixed std140 layout shared by all stages, written once into ordered GPU commands per frame. */
final class IrisFrameUniforms {
    private IrisFrameUniforms() {}

    static void write(ByteBuffer bytes, WorldFrame.Camera current, WorldFrame.Camera previous) {
        bytes.order(ByteOrder.nativeOrder());
        for (int i = 0; i < IrisUniforms.FRAME_BYTES; i += 4) bytes.putInt(i, 0);
        if (current == null) return; // The bridge rejects every shader reading camera fields in this case.
        current.projection().get(0, bytes);
        current.inverseProjection().get(64, bytes);
        current.modelView().get(128, bytes);
        current.inverseModelView().get(192, bytes);
        if (previous != null) {
            previous.projection().get(256, bytes);
            previous.modelView().get(320, bytes);
            position(bytes, 400, previous);
        }
        position(bytes, 384, current);
    }

    private static void position(ByteBuffer bytes, int offset, WorldFrame.Camera camera) {
        bytes.putFloat(offset, (float) camera.x()); bytes.putFloat(offset+4, (float) camera.y()); bytes.putFloat(offset+8, (float) camera.z());
    }
}
