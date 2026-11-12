package io.github.andrexarlay.vulkancompat.rt.world;

import io.github.andrexarlay.vulkancompat.rt.denoise.StabilityProbe;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.lwjgl.system.MemoryUtil;

/**
 * Temporal camera history of the accumulation: the previous full view-projection and camera origin, and the per-frame
 * delta the primary rays reproject with. The current frame's unjittered projection is supplied by the renderer; this
 * owner keeps the prior pose so a moved camera invalidates history exactly as the distance/normal test expects.
 */
final class RtCameraHistory {
    private static final int STATS_STRIDE = RtStatsLayout.SLOT_STRIDE;

    private final org.joml.Matrix4f curViewProj = new org.joml.Matrix4f();
    private final org.joml.Matrix4f prevViewProj = new org.joml.Matrix4f();
    private double prevCamX;
    private double prevCamY;
    private double prevCamZ;
    private boolean havePrevCamera;
    private float dx;
    private float dy;
    private float dz;

    org.joml.Matrix4f curViewProj() { return curViewProj; }
    float dx() { return dx; }
    float dy() { return dy; }
    float dz() { return dz; }

    /**
     * Writes the previous projection and the camera delta into the stats slot and the frame params. Hit positions include
     * the current bob origin, while camDelta remains the raw camera-position delta; the prior full projection already
     * carries the previous bob origin.
     */
    void writeTemporal(long hdr, RtFrameParams params, CameraRenderState camera, org.joml.Matrix4f frameProjection) {
        curViewProj.set(frameProjection).mul(camera.viewRotationMatrix);
        if (!havePrevCamera) {
            prevViewProj.set(curViewProj);
            prevCamX = camera.pos.x;
            prevCamY = camera.pos.y;
            prevCamZ = camera.pos.z;
            havePrevCamera = true;
        }
        prevViewProj.get(RtStatsLayout.PREV_VIEW_PROJ, MemoryUtil.memByteBuffer(hdr, STATS_STRIDE));
        dx = (float) (camera.pos.x - prevCamX);
        dy = (float) (camera.pos.y - prevCamY);
        dz = (float) (camera.pos.z - prevCamZ);
        boolean camMoved = Math.abs(dx) > 1.0e-7f || Math.abs(dy) > 1.0e-7f || Math.abs(dz) > 1.0e-7f
            || !prevViewProj.equals(curViewProj, 1.0e-7f);
        MemoryUtil.memPutFloat(hdr + RtStatsLayout.CAM_DELTA, dx);
        MemoryUtil.memPutFloat(hdr + RtStatsLayout.CAM_DELTA + 4, dy);
        MemoryUtil.memPutFloat(hdr + RtStatsLayout.CAM_DELTA + 8, dz);
        MemoryUtil.memPutFloat(hdr + RtStatsLayout.CAM_DELTA + 12, camMoved ? 1.0f : 0.0f);
        params.prevViewProj.set(prevViewProj);
        params.cameraDeltaX = dx;
        params.cameraDeltaY = dy;
        params.cameraDeltaZ = dz;
        params.cameraStatic = !camMoved;
    }

    /** The prior projection the stability probe reprojects against, captured before this frame's pose becomes the prior. */
    org.joml.Matrix4f probePrev() {
        return StabilityProbe.recording() ? new org.joml.Matrix4f(prevViewProj) : null;
    }

    void commit(CameraRenderState camera) {
        prevViewProj.set(curViewProj);
        prevCamX = camera.pos.x;
        prevCamY = camera.pos.y;
        prevCamZ = camera.pos.z;
    }
}