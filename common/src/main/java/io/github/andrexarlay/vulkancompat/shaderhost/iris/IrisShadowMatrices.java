package io.github.andrexarlay.vulkancompat.shaderhost.iris;

import org.joml.Matrix4f;

/**
 * Builds the Iris shadow pass matrices ({@code shadowModelView} / {@code shadowProjection}) for the raster-core forward
 * executor. Pure and unit-tested; no device or Minecraft state.
 *
 * <p>Geometry fed to the shadow pass is camera-relative ({@code vaPosition = worldPos - cameraPosition}), so the light view
 * is expressed around the camera at the origin: the camera position cancels out of {@code shadowModelView}. The light sits
 * at {@code sunDir * shadowDistance} looking back at the camera, and an orthographic box of half-size {@code shadowDistance}
 * covers the shadowed region around the camera. {@code sunDir} points TOWARDS the light (see {@code RtSky.sunDirection}).
 */
public final class IrisShadowMatrices {

    public record Result(float[] modelView, float[] projection) {}

    private IrisShadowMatrices() {}

    public static Result build(float[] sunDir, float shadowDistance) {
        float d = Math.max(1f, shadowDistance);
        float lx = sunDir[0];
        float ly = sunDir[1];
        float lz = sunDir[2];
        float len = (float) Math.sqrt(lx * lx + ly * ly + lz * lz);
        if (len < 1e-6f) {
            lx = 0f; ly = 1f; lz = 0f; len = 1f;
        }
        lx /= len; ly /= len; lz /= len;

        // Up vector: avoid gimbal lock when the light is near vertical (noon / midnight).
        float upX = 0f, upY = 1f, upZ = 0f;
        if (Math.abs(ly) > 0.99f) {
            upX = 0f; upY = 0f; upZ = 1f;
        }

        Matrix4f view = new Matrix4f().lookAt(lx * d, ly * d, lz * d, 0f, 0f, 0f, upX, upY, upZ);
        // Depth range covers the box [-d,d] around the camera as seen from the light at distance d: points towards the
        // light land near depth 0, points away near 2d; the extra margin keeps tall geometry inside the frustum.
        Matrix4f proj = new Matrix4f().ortho(-d, d, -d, d, -d, 3f * d);

        float[] mv = new float[16];
        float[] pr = new float[16];
        view.get(mv);
        proj.get(pr);
        return new Result(mv, pr);
    }
}