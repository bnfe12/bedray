package io.github.andrexarlay.vulkancompat.rtx.upscaler;

import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

/**
 * Standard upstream contract for supplying temporal inputs (color, depth, motion vectors, camera displacement, matrices)
 * to temporal upscalers and frame generation backends.
 */
public record TemporalFrameInputs(
    long colorView,
    long depthView,
    long mvView,
    int width,
    int height,
    Vec3 camDelta,
    Matrix4f invViewProj,
    Matrix4f prevViewProj,
    boolean historyReset,
    String depthEncoding,
    String motionVectorUnits
) {
    public static TemporalFrameInputs of(
        long colorView,
        long depthView,
        long mvView,
        int width,
        int height,
        Vec3 camDelta,
        Matrix4f invViewProj,
        Matrix4f prevViewProj,
        boolean historyReset,
        String depthEncoding,
        String motionVectorUnits
    ) {
        return new TemporalFrameInputs(
            colorView, depthView, mvView, width, height,
            camDelta != null ? camDelta : Vec3.ZERO,
            invViewProj != null ? invViewProj : new Matrix4f(),
            prevViewProj != null ? prevViewProj : new Matrix4f(),
            historyReset,
            depthEncoding != null ? depthEncoding : "VIEW_Z",
            motionVectorUnits != null ? motionVectorUnits : "PIXELS"
        );
    }
}
