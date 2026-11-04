package io.github.andrexarlay.vulkancompat.rtx.framegen;

import io.github.andrexarlay.vulkancompat.rtx.frame.ReconstructionCamera;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.fsr.FsrCamera;
import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.joml.Vector3f;

/** CPU-only packing of Streamline's common camera contract, shared by RR, SR and frame generation. */
final class StreamlineCameraConstants {
    record Packed(float[] matrices, float[] values, int[] flags) {}

    private StreamlineCameraConstants() {}

    static Packed pack(ReconstructionCamera camera, ReconstructionCamera previous,
                       int renderWidth, int renderHeight, int outputWidth, int outputHeight) {
        if (renderWidth <= 0 || renderHeight <= 0 || outputWidth <= 0 || outputHeight <= 0)
            throw new IllegalArgumentException("invalid Streamline viewport extent");
        boolean reset = camera.reset() || previous == null || camera.teleportedFrom(previous);
        Matrix4f projection = camera.projection();
        Matrix4f clipToPrevious = camera.clipToPrevious(reset ? camera : previous);
        // JOML column-major bytes also encode the transposed matrix in SL's row-major, row-vector convention.
        float[] matrices = new float[64];
        projection.get(matrices, 0);
        new Matrix4f(projection).invert().get(matrices, 16);
        clipToPrevious.get(matrices, 32);
        new Matrix4f(clipToPrevious).invert().get(matrices, 48);

        float[] values = new float[21];
        values[0] = camera.jitterX();
        values[1] = camera.jitterY();
        values[2] = 1.0f / renderWidth;
        values[3] = 1.0f / renderHeight;
        Vector3d eye = camera.eye();
        values[4] = (float) eye.x;
        values[5] = (float) eye.y;
        values[6] = (float) eye.z;
        put(values, 7, camera.up());
        put(values, 10, camera.right());
        put(values, 13, camera.forward());
        FsrCamera lens = FsrCamera.fromProjection(projection);
        values[16] = lens.near();
        values[17] = lens.far();
        values[18] = lens.fovY();
        values[19] = (float) outputWidth / outputHeight;
        values[20] = Float.MAX_VALUE; // sl::INVALID_FLOAT; camera motion is already in the motion vectors.
        int[] flags = {1 /* reverse hardware Z */, 1 /* camera motion included */, 0 /* 2D */,
            reset ? 1 : 0, 0 /* unjittered motion */, 0 /* not dilated */};
        return new Packed(matrices, values, flags);
    }

    private static void put(float[] values, int offset, Vector3f vector) {
        values[offset] = vector.x;
        values[offset + 1] = vector.y;
        values[offset + 2] = vector.z;
    }
}
