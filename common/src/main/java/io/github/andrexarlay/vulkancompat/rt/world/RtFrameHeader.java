package io.github.andrexarlay.vulkancompat.rt.world;

import io.github.andrexarlay.vulkancompat.rt.entity.EntityCaptureManager;
import org.lwjgl.system.MemoryUtil;

/**
 * Writers for the groups of the per-frame statistics slot header (the {@code hdr} address of the slot the trace reads): fog,
 * entity geometry and the camera extras. The renderer still decides the order of the frame and which slot is written; each
 * method only stores the values of one group at its {@link RtStatsLayout} offsets.
 */
final class RtFrameHeader {
    private RtFrameHeader() {
    }

    /** Distance fog and the fog mode of the slot. */
    static void writeFog(long hdr, RtFrameParams params) {
        float baseDistance = (float) Math.max(32, params.rayDistance);
        float fogDistance = baseDistance * params.fogDistanceScale;
        MemoryUtil.memPutFloat(hdr + RtStatsLayout.FOG_DENSITY, params.fogDensity);
        MemoryUtil.memPutFloat(hdr + RtStatsLayout.FOG_INSCATTERING, params.fogInScattering);
        MemoryUtil.memPutFloat(hdr + RtStatsLayout.FOG_DISTANCE, fogDistance);
        // 2 = distance fog + the former in-shader god rays, 1 = distance fog only (the light shafts come from RtAtmosphere)
        MemoryUtil.memPutInt(hdr + RtStatsLayout.FOG_MODE, params.volumetricFogEnabled
            ? (RtAtmosphereSettings.godRays() == RtAtmosphereSettings.GodRays.INLINE ? 2 : 1) : 0);
        MemoryUtil.memPutFloat(hdr + RtStatsLayout.FOG_DISTANCE_SCALE, params.fogDistanceScale);
    }

    /** Entity geometry of this frame, the local player's avatar range and the motion buffer bound to this frame. */
    static void writeEntities(long hdr, RtWorld world, long motionAddr) {
        // entity geometry of this frame (RtEntityGeometryManager ring slice; the same slice the entity BLAS was built from)
        RtEntityGeometryManager eg = RtEntityGeometryManager.INSTANCE;
        boolean entOk = world.entityPrimitiveCount() > 0;
        MemoryUtil.memPutInt(hdr + RtStatsLayout.ENTITY_TRIS, entOk ? eg.frameTriangles() : 0);
        // Triangle range of the local player's body captured for this frame: the primary rays skip it (an inside face of the
        // avatar at 0.001 from the eye is what the first-person screen would otherwise show).
        EntityCaptureManager capture =
            EntityCaptureManager.getInstance();
        MemoryUtil.memPutInt(hdr + RtStatsLayout.SELF_AVATAR_TRI_START, entOk ? capture.selfAvatarTriStart() : 0);
        MemoryUtil.memPutInt(hdr + RtStatsLayout.SELF_AVATAR_TRI_COUNT, entOk ? capture.selfAvatarTriCount() : 0);
        MemoryUtil.memPutLong(hdr + RtStatsLayout.ENT_POS_ADDR, entOk ? eg.framePosAddress() : 0L);
        MemoryUtil.memPutLong(hdr + RtStatsLayout.ENT_IDX_ADDR, entOk ? eg.frameIndexAddress() : 0L);
        MemoryUtil.memPutLong(hdr + RtStatsLayout.ENT_ATTR_ADDR, entOk ? eg.frameAttrAddress() : 0L);
        MemoryUtil.memPutLong(hdr + RtStatsLayout.ENT_PREV_ADDR, entOk ? eg.framePrevAddress() : 0L);
        MemoryUtil.memPutLong(hdr + RtStatsLayout.ENT_ATLAS_ADDR, eg.atlasAddress());
        MemoryUtil.memPutLong(hdr + RtStatsLayout.ENT_RECT_ADDR, eg.rectAddress());
        MemoryUtil.memPutLong(hdr + RtStatsLayout.MOTION_ADDR, motionAddr);
        MemoryUtil.memPutInt(hdr + RtStatsLayout.ENT_ATLAS_SIZE, eg.atlasAddress() != 0L ? io.github.andrexarlay.vulkancompat.rt.entity.EntityTextureAtlas.SIZE : 0);
    }

    /** Upscaler jitter, the camera bob origin, the raw forward vector and the baked light fill strength. */
    static void writeCamera(long hdr, RtFrameParams params, org.joml.Vector3f bobOrigin, org.joml.Matrix4f viewRotation) {
        float jitterPxX = 0.0f;
        float jitterPxY = 0.0f;
        boolean upscalerJittered = io.github.andrexarlay.vulkancompat.rtx.upscaler.CameraJitterManager.isJitterActive();
        if (upscalerJittered) {
            jitterPxX = io.github.andrexarlay.vulkancompat.rtx.upscaler.CameraJitterManager.getCurrentPixelJitterX();
            jitterPxY = io.github.andrexarlay.vulkancompat.rtx.upscaler.CameraJitterManager.getCurrentPixelJitterY();
        }
        params.upscalerJittered = upscalerJittered;
        params.upscalerJitterX = jitterPxX;
        params.upscalerJitterY = jitterPxY;
        MemoryUtil.memPutFloat(hdr + RtStatsLayout.CAMERA_JITTER, jitterPxX);
        MemoryUtil.memPutFloat(hdr + RtStatsLayout.CAMERA_JITTER + 4, jitterPxY);
        MemoryUtil.memPutFloat(hdr + RtStatsLayout.CAMERA_BOB_ORIGIN, bobOrigin.x);
        MemoryUtil.memPutFloat(hdr + RtStatsLayout.CAMERA_BOB_ORIGIN + 4, bobOrigin.y);
        MemoryUtil.memPutFloat(hdr + RtStatsLayout.CAMERA_BOB_ORIGIN + 8, bobOrigin.z);
        MemoryUtil.memPutFloat(hdr + RtStatsLayout.CAMERA_BOB_ORIGIN + 12, 0.0f);
        org.joml.Vector3f rawForward = io.github.andrexarlay.vulkancompat.rt.world.CameraBobPose.rawForward(
            viewRotation, new org.joml.Vector3f());
        MemoryUtil.memPutFloat(hdr + RtStatsLayout.CAMERA_FORWARD, rawForward.x);
        MemoryUtil.memPutFloat(hdr + RtStatsLayout.CAMERA_FORWARD + 4, rawForward.y);
        MemoryUtil.memPutFloat(hdr + RtStatsLayout.CAMERA_FORWARD + 8, rawForward.z);
        // StatsBuf.cameraForward.w: strength of the optional baked vanilla light fill (RtSettings.bakedLightFill, rays mode)
        MemoryUtil.memPutFloat(hdr + RtStatsLayout.CAMERA_FORWARD + 12, io.github.andrexarlay.vulkancompat.rtx.config.RtSettings.bakedLightFill());
    }
}
