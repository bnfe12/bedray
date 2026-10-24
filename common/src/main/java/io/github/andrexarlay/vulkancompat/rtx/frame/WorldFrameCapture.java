package io.github.andrexarlay.vulkancompat.rtx.frame;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.backend.vulkan.VulkanConst;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import io.github.andrexarlay.vulkancompat.rt.world.RtFrameRenderer;
import io.github.andrexarlay.vulkancompat.rtx.WorldTargetManager;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.joml.Matrix4f;
import org.joml.Vector4f;

/** Adapter from native target/camera state into the borrowed, renderer-neutral host contract. */
public final class WorldFrameCapture {
    private WorldFrameCapture() {}

    public static WorldFrame capture(VulkanDevice device, RenderTarget target, CameraRenderState camera, WorldFrame.Renderer renderer) {
        if (target == null || target.width <= 0 || target.height <= 0 || target.getColorTexture() == null
            || target.getColorTexture().isClosed()) throw new IllegalArgumentException("World color target unavailable");
        int copies = GpuTexture.USAGE_COPY_SRC | GpuTexture.USAGE_COPY_DST;
        if ((target.getColorTexture().usage() & copies) != copies) throw new IllegalArgumentException("World color target has no copy usage");
        var stamp = WorldFrames.stamp();
        var color = new WorldFrame.Image(WorldTargetManager.extractVkImage(target),
            WorldTargetManager.extractVkImageView(target.getColorTextureView()), target.width, target.height,
            VulkanConst.toVk(target.getColorTexture().getFormat()), WorldFrame.Origin.BOTTOM_LEFT);
        WorldFrame.Camera snapshot = null;
        if (renderer != WorldFrame.Renderer.SYNTHETIC && camera != null && camera.pos != null
            && WorldTargetManager.hasCurrentLevelProjection()) {
            Matrix4f nativeProjection = renderer == WorldFrame.Renderer.HARDWARE_RT
                ? RtFrameRenderer.displayedProjection(target) : renderer == WorldFrame.Renderer.VOXEL_COMPUTE
                    ? computeProjection(camera) : WorldTargetManager.currentLevelProjection();
            if (nativeProjection != null) {
                try { snapshot = camera(camera, nativeProjection); }
                catch (IllegalArgumentException ignored) { /* Color-only post can still use this frame. */ }
            }
        }
        WorldFrame.Depth depth = null;
        if (renderer == WorldFrame.Renderer.RASTER && snapshot != null && target.getDepthTexture() != null
            && !target.getDepthTexture().isClosed() && target.getDepthTextureView() != null
            && (target.getDepthTexture().usage() & GpuTexture.USAGE_TEXTURE_BINDING) != 0) {
            long view = WorldTargetManager.extractVkImageView(target.getDepthTextureView());
            long image = WorldTargetManager.extractDepthVkImage(target);
            if (view != 0 && image != 0) depth = new WorldFrame.Depth(stamp, renderer,
                new WorldFrame.Image(image, view, target.width, target.height,
                    VulkanConst.toVk(target.getDepthTexture().getFormat()), WorldFrame.Origin.BOTTOM_LEFT),
                WorldFrame.DepthEncoding.REVERSE_DEVICE_Z);
        }
        return new WorldFrame(stamp, renderer, device, color, snapshot, depth);
    }

    private static Matrix4f computeProjection(CameraRenderState camera) {
        var backend = io.github.andrexarlay.bedray.api.v1.GraphicsBackendRegistry.backend();
        return backend != null && backend.supportsModelGeometry() ? WorldTargetManager.currentLevelProjection()
            : WorldTargetManager.levelProjectionOr(camera.projectionMatrix);
    }

    private static WorldFrame.Camera camera(CameraRenderState camera, Matrix4f full) {
        if (camera.projectionMatrix == null || camera.viewRotationMatrix == null) throw new IllegalArgumentException("Missing camera matrices");
        Matrix4f base = new Matrix4f(camera.projectionMatrix);
        Matrix4f inverseBase = new Matrix4f(base).invert();
        Vector4f nearPoint = inverseBase.transform(new Vector4f(0, 0, 1, 1));
        Vector4f farPoint = inverseBase.transform(new Vector4f(0, 0, 0, 1));
        float near = -nearPoint.z/nearPoint.w;
        float far = -farPoint.z/farPoint.w;
        if (!Float.isFinite(far)) far = camera.depthFar; // finite Iris range for an infinite native far plane
        if (!Float.isFinite(near) || near <= 0 || !Float.isFinite(far) || far <= near) throw new IllegalArgumentException("Invalid camera planes");
        Vector4f n = base.transform(new Vector4f(0, 0, -near, 1));
        Vector4f f = base.transform(new Vector4f(0, 0, -far, 1));
        float dn = n.z/n.w, df = f.z/f.w;
        float scale = 1.0f/(df-dn), bias = -dn*scale;
        if (!Float.isFinite(scale) || !Float.isFinite(bias) || scale >= 0) throw new IllegalArgumentException("Not a reverse-Z camera");
        // Convert clip Z without changing XY/W. This works for finite or infinite native reverse-Z projections.
        Matrix4f clip = new Matrix4f().m22(2*scale).m32(2*bias-1);
        Matrix4f glProjection = clip.mul(base, new Matrix4f());
        // Native GameRenderer puts bob/hurt/portal pose into projection; Iris keeps that pose in model-view.
        Matrix4f modelView = inverseBase.mul(full).mul(camera.viewRotationMatrix);
        return new WorldFrame.Camera(full, glProjection, modelView, camera.pos.x, camera.pos.y, camera.pos.z,
            near, far, scale, bias);
    }
}
