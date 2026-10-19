// STUB: CPU-only matrix math, no GPU motion-vector buffer exists. Not called from the execution path (Phase 0 step 0.3); do not expose in UI.
package io.github.andrexarlay.vulkancompat.rtx.upscaler;

import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.joml.Vector4f;

/**
 * Calculates 2D screen-space motion vectors (velocity buffer) for temporal reprojection.
 * Used by DLSS, FSR, XeSS, and the NRD Denoiser to track pixel movement across consecutive frames.
 */
public final class VelocityBuffer {

    private final Matrix4f prevViewProj = new Matrix4f();
    private final Matrix4f currViewProj = new Matrix4f();
    private final Matrix4f prevView = new Matrix4f();
    private final Matrix4f currView = new Matrix4f();
    private final Matrix4f prevProj = new Matrix4f();
    private final Matrix4f currProj = new Matrix4f();

    private final Vector4f tempWorld = new Vector4f();
    private final Vector4f tempCurrClip = new Vector4f();
    private final Vector4f tempPrevClip = new Vector4f();

    private boolean firstFrame = true;
    private long frameCount = 0;

    public VelocityBuffer() {}

    /**
     * Updates view and projection matrices for the current frame.
     * Copies previous matrices to maintain temporal continuity.
     */
    public void updateMatrices(Matrix4f view, Matrix4f proj) {
        if (!firstFrame) {
            prevView.set(currView);
            prevProj.set(currProj);
            prevViewProj.set(currViewProj);
        } else {
            prevView.set(view);
            prevProj.set(proj);
            prevViewProj.set(new Matrix4f(proj).mul(view));
            firstFrame = false;
        }

        currView.set(view);
        currProj.set(proj);
        currViewProj.set(new Matrix4f(proj).mul(view));
        frameCount++;
    }

    /**
     * Calculates the 2D screen-space velocity vector for a given 3D world position.
     * Subtracts the current sub-pixel camera jitter to produce un-jittered motion vectors.
     */
    public Vector2f calculateVelocity(float worldX, float worldY, float worldZ, Vector2f ndcJitter, Vector2f outVelocity) {
        tempWorld.set(worldX, worldY, worldZ, 1.0f);

        // Project to current clip space
        currViewProj.transform(tempWorld, tempCurrClip);

        // Project to previous clip space
        prevViewProj.transform(tempWorld, tempPrevClip);

        if (Math.abs(tempCurrClip.w) < 1e-6f || Math.abs(tempPrevClip.w) < 1e-6f) {
            outVelocity.set(0.0f, 0.0f);
            return outVelocity;
        }

        // Perspective divide to NDC [-1, 1]
        float currNdcX = tempCurrClip.x / tempCurrClip.w;
        float currNdcY = tempCurrClip.y / tempCurrClip.w;

        float prevNdcX = tempPrevClip.x / tempPrevClip.w;
        float prevNdcY = tempPrevClip.y / tempPrevClip.w;

        // Screen-space velocity: delta NDC minus camera jitter
        float vx = (currNdcX - prevNdcX) - (ndcJitter != null ? ndcJitter.x : 0.0f);
        float vy = (currNdcY - prevNdcY) - (ndcJitter != null ? ndcJitter.y : 0.0f);

        outVelocity.set(vx, vy);
        return outVelocity;
    }

    public void reset() {
        firstFrame = true;
        frameCount = 0;
        prevViewProj.identity();
        currViewProj.identity();
    }

    public long getFrameCount() {
        return frameCount;
    }

    public Matrix4f getCurrViewProj() {
        return currViewProj;
    }

    public Matrix4f getCurrentViewProj() {
        return currViewProj;
    }

    public Matrix4f getPrevViewProj() {
        return prevViewProj;
    }

    public Matrix4f getPreviousViewProj() {
        return prevViewProj;
    }

    public Vector2f calculateMotionVector(float worldX, float worldY, float worldZ) {
        return calculateVelocity(worldX, worldY, worldZ, null, new Vector2f());
    }
}
