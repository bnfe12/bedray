package io.github.andrexarlay.vulkancompat.rt.world;

import io.github.andrexarlay.vulkancompat.rtx.config.RtSettings;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

/** Shared typed-frame preparation; host camera/lighting state has one conversion for all native passes. */
final class RtSlangFrameData {
    private RtSlangFrameData() {}
    static void fill(RtPathTraceSlangPass.Frame frame, RtFrameParams p, int width, int height, int sampleIndex, int accumFrames) {
        float yUp = p.flipY ? 1f : -1f; // matrix-space NDC y of the top edge (Slang ndc.y = -1)
        // The world, the projection and the TLAS are anchored at the raw camera position; the engine view adds the
        // view-bob eye offset.  Remove it before deriving the basis and keep it as the ray origin, exactly like the
        // legacy cameraOrigin()/cameraRay() pair, or walking shifts the whole world against the eye.
        Vector3f bob = new Vector3f(p.bobX, p.bobY, p.bobZ);
        Vector3f centre = unproject(p.invViewProj, 0f, 0f).sub(bob);
        Vector3f rightEdge = unproject(p.invViewProj, 1f, 0f).sub(bob);
        Vector3f bottomEdge = unproject(p.invViewProj, 0f, -yUp).sub(bob);
        Vector3f rightVec = new Vector3f(rightEdge).sub(centre);
        Vector3f downVec = new Vector3f(bottomEdge).sub(centre);
        float centreLen = Math.max(centre.length(), 1.0e-6f);
        float rightLen = Math.max(rightVec.length(), 1.0e-6f);
        float downLen = Math.max(downVec.length(), 1.0e-6f);
        Vector3f forward = new Vector3f(centre).div(centreLen);
        Vector3f right = new Vector3f(rightVec).div(rightLen);
        Vector3f up = new Vector3f(downVec).div(-downLen);

        frame.invViewProjection.set(p.invViewProj);
        frame.viewProjection.set(p.invViewProj).invert();
        frame.prevViewProjection.set(p.prevViewProj);
        frame.forward.set(forward.x, forward.y, forward.z, downLen / centreLen);
        frame.right.set(right.x, right.y, right.z, rightLen / downLen);
        frame.up.set(up.x, up.y, up.z, 0f);
        frame.origin.set(bob.x, bob.y, bob.z, p.frame / 20f);
        // Sub-pixel jitter per frame so the running mean also antialiases; zw is the pixel size the shader uses for NDC.
        float jx = accumFrames == 0 ? 0f : halton(sampleIndex + 1, 2) - 0.5f;
        float jy = accumFrames == 0 ? 0f : halton(sampleIndex + 1, 3) - 0.5f;
        frame.jitter.set(jx, jy, 1f / width, 1f / height);
        frame.width = width;
        frame.height = height;
        frame.displayWidth = width;
        frame.displayHeight = height;

        float[] dir = p.key.dir;
        float[] color = p.key.color;
        float sunRadius = Math.max(RtSettings.sunRadiusRad(), 1.0e-4f);
        frame.sunDirection.set(dir[0], dir[1], dir[2], sunRadius);
        frame.sunColor.set(color[0], color[1], color[2], 1f);
        frame.moonDirection.set(-dir[0], -dir[1], -dir[2], sunRadius);
        frame.moonColor.set(0f, 0f, 0f, 0f);

        // SkyRenderState.skyColor is the colour vanilla draws (display space); the legacy sky converts it to radiance with
        // tonemapInverse before shading.  The display pass tone-maps this frame again, so writing the raw colour here would
        // tone-map the sky twice (gain 1.2 + shoulder on an already display-referred value) and wash it out.
        float[] sky = p.skyColor;
        float[] zenith = RtTonemap.invert(sky[0], sky[1], sky[2]);
        float[] horizon = RtTonemap.invert(sky[0] * 0.7f + 0.3f, sky[1] * 0.7f + 0.3f, sky[2] * 0.7f + 0.3f);
        frame.zenithColor.set(zenith[0], zenith[1], zenith[2], 1f);
        frame.horizonColor.set(horizon[0], horizon[1], horizon[2], 1f);
        frame.fogColor.set(frame.horizonColor);
        frame.fog.set(0f, 0f, 0f, 0f);
        frame.cloud.set(0f, 0f, 0f, 0f);

        frame.exposure.set(1f, 0f, 0f, 0f);
        frame.autoExposure = 0;
        frame.tonemapOperator = 0;
        frame.bloomEnabled = 0;
        frame.frameIndex = p.frame;
        frame.sampleIndex = sampleIndex;
        frame.samplesPerFrame = 1;
        frame.flags = 0;
    }

    private static Vector3f unproject(Matrix4f invViewProj, float x, float y) {
        Vector4f v = new Vector4f(x, y, 0.5f, 1f).mul(invViewProj);
        float w = Math.abs(v.w) < 1.0e-12f ? 1.0e-12f : v.w;
        return new Vector3f(v.x / w, v.y / w, v.z / w);
    }

    private static float halton(int index, int base) {
        float f = 1f;
        float r = 0f;
        int i = index;
        while (i > 0) {
            f /= base;
            r += f * (i % base);
            i /= base;
        }
        return r;
    }

}
