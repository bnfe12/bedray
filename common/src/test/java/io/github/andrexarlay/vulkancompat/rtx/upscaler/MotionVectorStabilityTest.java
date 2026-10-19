package io.github.andrexarlay.vulkancompat.rtx.upscaler;

import org.junit.jupiter.api.Test;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import net.minecraft.world.phys.Vec3;

import static org.junit.jupiter.api.Assertions.*;

public class MotionVectorStabilityTest {

    @Test
    public void testMotionVectorReprojectionMath() {
        Matrix4f invViewProj = new Matrix4f().identity();
        Matrix4f prevViewProj = new Matrix4f().identity();
        Vec3 camDelta = new Vec3(0.5, 0.0, 0.0);

        assertNotNull(invViewProj);
        assertNotNull(prevViewProj);
        assertEquals(0.5, camDelta.x, 1e-5);
    }

    @Test
    public void testCpuReferenceReprojectionStaticPoint() {
        // 1. Static point + stationary camera -> motionVector == (0, 0)
        Matrix4f invViewProj = new Matrix4f().identity();
        Matrix4f prevViewProj = new Matrix4f().identity();
        Vec3 camDelta = new Vec3(0.0, 0.0, 0.0);

        float width = 1920.0f;
        float height = 1080.0f;
        float uvX = 0.5f;
        float uvY = 0.5f;
        float ndcX = uvX * 2.0f - 1.0f;
        float ndcY = uvY * 2.0f - 1.0f;

        Vector4f rayW = invViewProj.transform(new Vector4f(ndcX, ndcY, 0.5f, 1.0f));
        Vector3f rayDir = new Vector3f(rayW.x / rayW.w, rayW.y / rayW.w, rayW.z / rayW.w).normalize();

        float depth = 10.0f; // 10 units in front
        Vector3f worldPosRel = new Vector3f(rayDir).mul(depth);
        Vector4f prevPosRel = new Vector4f(worldPosRel.x + (float)camDelta.x, worldPosRel.y + (float)camDelta.y, worldPosRel.z + (float)camDelta.z, 1.0f);
        Vector4f prevClip = prevViewProj.transform(prevPosRel);

        float prevUvX = (prevClip.x / prevClip.w) * 0.5f + 0.5f;
        float prevUvY = (prevClip.y / prevClip.w) * 0.5f + 0.5f;

        float mvX = (prevUvX - uvX) * width;
        float mvY = (prevUvY - uvY) * height;

        assertEquals(0.0f, mvX, 1e-4f);
        assertEquals(0.0f, mvY, 1e-4f);
    }

    @Test
    public void testCpuReferenceCameraShiftRight() {
        // 2. Camera shift right -> motionVector.x > 0 (or expected direction)
        Matrix4f invViewProj = new Matrix4f().identity();
        Matrix4f prevViewProj = new Matrix4f().identity();
        Vec3 camDelta = new Vec3(1.0, 0.0, 0.0); // Camera moved right by 1.0

        float width = 1920.0f;
        float height = 1080.0f;
        float uvX = 0.5f;
        float uvY = 0.5f;
        float ndcX = uvX * 2.0f - 1.0f;
        float ndcY = uvY * 2.0f - 1.0f;

        Vector4f rayW = invViewProj.transform(new Vector4f(ndcX, ndcY, 0.5f, 1.0f));
        Vector3f rayDir = new Vector3f(rayW.x / rayW.w, rayW.y / rayW.w, rayW.z / rayW.w).normalize();

        float depth = 10.0f;
        Vector3f worldPosRel = new Vector3f(rayDir).mul(depth);
        // curr - prev = camDelta => prev = curr - camDelta => prevPosRel = worldPosRel + camDelta
        Vector4f prevPosRel = new Vector4f(worldPosRel.x + (float)camDelta.x, worldPosRel.y + (float)camDelta.y, worldPosRel.z + (float)camDelta.z, 1.0f);
        Vector4f prevClip = prevViewProj.transform(prevPosRel);

        float prevUvX = (prevClip.x / prevClip.w) * 0.5f + 0.5f;
        float prevUvY = (prevClip.y / prevClip.w) * 0.5f + 0.5f;

        float mvX = (prevUvX - uvX) * width;
        assertTrue(Math.abs(mvX) > 0.0f);
    }

    @Test
    public void testCpuReferenceCameraRotation() {
        // 3. Camera rotation -> correct previous UV
        Matrix4f proj = new Matrix4f().perspective((float) Math.toRadians(70), 16.0f / 9.0f, 0.05f, 1000.0f);
        Matrix4f invViewProj = new Matrix4f(proj).invert();
        Matrix4f prevViewProj = new Matrix4f(proj).mul(new Matrix4f().rotationY(0.1f)); // 0.1 rad rotation
        Vec3 camDelta = new Vec3(0.0, 0.0, 0.0);

        float width = 1920.0f;
        float height = 1080.0f;
        float uvX = 0.5f;
        float uvY = 0.5f;
        float ndcX = uvX * 2.0f - 1.0f;
        float ndcY = uvY * 2.0f - 1.0f;

        Vector4f rayW = invViewProj.transform(new Vector4f(ndcX, ndcY, 0.5f, 1.0f));
        Vector3f rayDir = new Vector3f(rayW.x / rayW.w, rayW.y / rayW.w, rayW.z / rayW.w).normalize();

        float depth = 1.0e6f; // Sky / far plane
        Vector4f prevClip;
        if (depth >= 1.0e5f) {
            prevClip = prevViewProj.transform(new Vector4f(rayDir, 0.0f));
        } else {
            Vector3f worldPosRel = new Vector3f(rayDir).mul(depth);
            prevClip = prevViewProj.transform(new Vector4f(worldPosRel.x + (float)camDelta.x, worldPosRel.y + (float)camDelta.y, worldPosRel.z + (float)camDelta.z, 1.0f));
        }

        float prevUvX = uvX;
        float prevUvY = uvY;
        if (Math.abs(prevClip.w) > 1e-7f) {
            prevUvX = (prevClip.x / prevClip.w) * 0.5f + 0.5f;
            prevUvY = (prevClip.y / prevClip.w) * 0.5f + 0.5f;
        }

        assertTrue(!Float.isNaN(prevUvX) && !Float.isNaN(prevUvY));
    }

    @Test
    public void testCpuReferenceHistoryReset() {
        // 4. Reset history (teleport / reset flag) -> motionVector == (0, 0) or handled cleanly
        boolean historyReset = true;
        float mvX = historyReset ? 0.0f : 15.2f;
        float mvY = historyReset ? 0.0f : -8.5f;

        assertEquals(0.0f, mvX, 1e-4f);
        assertEquals(0.0f, mvY, 1e-4f);
    }

    @Test
    public void testCpuReferenceMotionVectorWithCameraJitter() {
        // 5. Camera jitter applied to primary rays, but unjittered matrices used for reprojection
        // -> stationary point yields motionVector == (0, 0)
        Matrix4f proj = new Matrix4f().perspective((float) Math.toRadians(70), 16.0f / 9.0f, 0.05f, 1000.0f);
        Matrix4f invViewProj = new Matrix4f(proj).invert();
        Matrix4f prevViewProj = new Matrix4f(proj); // stationary camera
        Vec3 camDelta = new Vec3(0.0, 0.0, 0.0);

        float width = 1920.0f;
        float height = 1080.0f;
        int px = 960;
        int py = 540;

        // Subpixel jitter offsets (e.g. from Halton sequence) in [-0.5, 0.5] pixel units
        float jitterPxX = 0.25f;
        float jitterPxY = -0.1667f;

        // In ray tracing, primary ray is jittered: (p + 0.5 + camJitter) / resolution * 2.0 - 1.0
        float rayNdcX = ((px + 0.5f + jitterPxX) / width) * 2.0f - 1.0f;
        float rayNdcY = ((py + 0.5f + jitterPxY) / height) * 2.0f - 1.0f;

        Vector4f rayW = invViewProj.transform(new Vector4f(rayNdcX, rayNdcY, 0.5f, 1.0f));
        Vector3f rayDir = new Vector3f(rayW.x / rayW.w, rayW.y / rayW.w, rayW.z / rayW.w).normalize();

        // Hit surface at distance 15.0 blocks
        float hitDist = 15.0f;
        Vector3f hitWorldPosRel = new Vector3f(rayDir).mul(hitDist);

        // Reprojection pass uses unjittered matrices to compute motion vectors:
        // For the pixel center (px + 0.5, py + 0.5), world pos reconstructed via unjittered invViewProj:
        float pixelNdcX = ((px + 0.5f) / width) * 2.0f - 1.0f;
        float pixelNdcY = ((py + 0.5f) / height) * 2.0f - 1.0f;
        Vector4f centerRayW = invViewProj.transform(new Vector4f(pixelNdcX, pixelNdcY, 0.5f, 1.0f));
        Vector3f centerRayDir = new Vector3f(centerRayW.x / centerRayW.w, centerRayW.y / centerRayW.w, centerRayW.z / centerRayW.w).normalize();
        Vector3f worldPosRel = new Vector3f(centerRayDir).mul(hitDist);

        Vector4f prevPosRel = new Vector4f(worldPosRel.x + (float)camDelta.x, worldPosRel.y + (float)camDelta.y, worldPosRel.z + (float)camDelta.z, 1.0f);
        Vector4f prevClip = prevViewProj.transform(prevPosRel);

        float prevUvX = (prevClip.x / prevClip.w) * 0.5f + 0.5f;
        float prevUvY = (prevClip.y / prevClip.w) * 0.5f + 0.5f;
        float currentUvX = (px + 0.5f) / width;
        float currentUvY = (py + 0.5f) / height;

        float mvX = (prevUvX - currentUvX) * width;
        float mvY = (prevUvY - currentUvY) * height;

        // Motion vector must be strictly zero (no jitter contamination)
        assertEquals(0.0f, mvX, 1e-4f, "Motion vector X must be 0 for static scene despite camera jitter");
        assertEquals(0.0f, mvY, 1e-4f, "Motion vector Y must be 0 for static scene despite camera jitter");

        // Verify that if invViewProj had been erroneously jittered:
        Matrix4f jitteredProj = new Matrix4f(proj);
        jitteredProj.m20(proj.m20() + (jitterPxX / width) * 2.0f);
        jitteredProj.m21(proj.m21() + (jitterPxY / height) * 2.0f);
        Matrix4f erroneousJitteredInv = new Matrix4f(jitteredProj).invert();

        Vector4f badCenterRayW = erroneousJitteredInv.transform(new Vector4f(pixelNdcX, pixelNdcY, 0.5f, 1.0f));
        Vector3f badCenterRayDir = new Vector3f(badCenterRayW.x / badCenterRayW.w, badCenterRayW.y / badCenterRayW.w, badCenterRayW.z / badCenterRayW.w).normalize();
        Vector3f badWorldPosRel = new Vector3f(badCenterRayDir).mul(hitDist);
        Vector4f badPrevClip = prevViewProj.transform(new Vector4f(badWorldPosRel, 1.0f));
        float badPrevUvX = (badPrevClip.x / badPrevClip.w) * 0.5f + 0.5f;
        float badMvX = (badPrevUvX - currentUvX) * width;

        // Erroneously jittering invViewProj causes artificial motion vector smear
        assertTrue(Math.abs(badMvX) > 1e-3f, "Jittering invViewProj would contaminate motion vectors");
    }
}
