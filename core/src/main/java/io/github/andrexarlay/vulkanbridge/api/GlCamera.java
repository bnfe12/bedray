package io.github.andrexarlay.vulkanbridge.api;

/**
 * Snapshot of {@code net.minecraft.client.Camera} taken on the render thread at the end of the frame.
 *
 * @param x            camera position (world space)
 * @param y            camera position (world space)
 * @param z            camera position (world space)
 * @param xRot         pitch in degrees (Camera.xRot())
 * @param yRot         yaw in degrees (Camera.yRot())
 * @param fov          vertical field of view in degrees (Camera.getFov())
 * @param viewRotation column-major 4x4 view rotation matrix (Camera.getViewRotationMatrix; no translation: subtract the position yourself)
 */
@Experimental
public record GlCamera(double x, double y, double z, float xRot, float yRot, float fov, float[] viewRotation) {
}
