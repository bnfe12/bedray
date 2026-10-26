package io.github.andrexarlay.vulkancompat.rtx.frame;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;

import java.util.Objects;

/**
 * Immutable CPU metadata for one completed world image on the host queue.
 * Image handles are borrowed in GENERAL layout and valid only for this current frame's host-owned
 * recording/lifetime scope. Consumers must neither destroy them nor submit work on another queue.
 * Keeping this object does not extend a borrowed image's lifetime. This model owns no GPU resources
 * and has no Vulkan or Minecraft dependency.
 */
public final class WorldFrame {
    public enum Renderer { RASTER, HARDWARE_RT, VOXEL_COMPUTE, SYNTHETIC }
    /** LINEAR_VIEW_Z is positive -view.z; RAY_DISTANCE is world-unit distance on unjittered pixel-center rays. */
    public enum DepthEncoding { REVERSE_DEVICE_Z, OPENGL_WINDOW_Z, LINEAR_VIEW_Z, RAY_DISTANCE }
    public enum Origin { BOTTOM_LEFT, TOP_LEFT }

    /** World lifetime and host frame sequence, independent of a provider's resource/material revisions. */
    public record Stamp(long worldEpoch, long frameNumber) {
        public Stamp {
            if (worldEpoch <= 0 || frameNumber <= 0)
                throw new IllegalArgumentException("World/frame stamps must be positive");
        }
    }

    /** Native handles are opaque bit patterns: any nonzero signed long is valid metadata. */
    public record Image(long image, long view, int width, int height, int format, Origin origin) {
        public Image {
            Objects.requireNonNull(origin, "origin");
            if (image == 0 || view == 0 || width <= 0 || height <= 0 || format <= 0)
                throw new IllegalArgumentException("Image requires nonzero handles, positive extent and format");
        }
    }

    /** A sampled GENERAL-layout guide explicitly identifies its producer, frame and numeric depth convention. */
    public record Depth(Stamp stamp, Renderer renderer, Image image, DepthEncoding encoding) {
        public Depth {
            Objects.requireNonNull(stamp, "stamp");
            Objects.requireNonNull(renderer, "renderer");
            Objects.requireNonNull(image, "image");
            Objects.requireNonNull(encoding, "encoding");
        }
    }

    private final Stamp stamp;
    private final Renderer renderer;
    private final Object deviceIdentity;
    private final Image color;
    private final Camera camera;
    private final Depth depth;

    public WorldFrame(Stamp stamp, Renderer renderer, Object deviceIdentity, Image color, Camera camera, Depth depth) {
        this.stamp = Objects.requireNonNull(stamp, "stamp");
        this.renderer = Objects.requireNonNull(renderer, "renderer");
        this.deviceIdentity = Objects.requireNonNull(deviceIdentity, "deviceIdentity");
        this.color = Objects.requireNonNull(color, "color");
        this.camera = camera;
        if (depth != null && (!stamp.equals(depth.stamp()) || renderer != depth.renderer()
            || color.width() != depth.image().width() || color.height() != depth.image().height()
            || color.origin() != depth.image().origin()))
            throw new IllegalArgumentException("Depth must match the color frame, producer, extent and origin");
        this.depth = depth;
    }

    public Stamp stamp() { return stamp; }
    public Renderer renderer() { return renderer; }
    /** Opaque host identity; consumers compare it by reference, without taking ownership. */
    public Object deviceIdentity() { return deviceIdentity; }
    public Image color() { return color; }
    public Camera camera() { return camera; }
    public Depth depth() { return depth; }

    /**
     * A defensive camera snapshot. Native projection is the actual reverse-Z world projection,
     * including bob; GL projection is the base projection; model-view is bob times view rotation.
     * The double world origin is kept separate from these camera-relative matrices.
     * {@code glWindowDepth = hardwareDepth * depthScale + depthBias} specifies the host's depth map.
     */
    public static final class Camera {
        private final Matrix4f nativeProjection, inverseNativeProjection;
        private final Matrix4f projection, inverseProjection;
        private final Matrix4f modelView, inverseModelView;
        private final double x, y, z;
        private final float near, far, depthScale, depthBias;

        public Camera(Matrix4fc nativeProjection, Matrix4fc projection, Matrix4fc modelView,
                      double x, double y, double z, float near, float far, float depthScale, float depthBias) {
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z))
                throw new IllegalArgumentException("Camera origin must be finite");
            if (!Float.isFinite(near) || !Float.isFinite(far) || near <= 0 || far <= near)
                throw new IllegalArgumentException("Camera requires finite ordered near/far planes");
            if (!Float.isFinite(depthScale) || !Float.isFinite(depthBias))
                throw new IllegalArgumentException("Depth mapping must be finite");
            this.nativeProjection = matrix(nativeProjection, "native projection");
            this.inverseNativeProjection = inverse(this.nativeProjection, "native projection");
            this.projection = matrix(projection, "GL projection");
            this.inverseProjection = inverse(this.projection, "GL projection");
            this.modelView = matrix(modelView, "model-view");
            this.inverseModelView = inverse(this.modelView, "model-view");
            this.x = x;
            this.y = y;
            this.z = z;
            this.near = near;
            this.far = far;
            this.depthScale = depthScale;
            this.depthBias = depthBias;
        }

        private static Matrix4f matrix(Matrix4fc source, String name) {
            Matrix4f copy = new Matrix4f(Objects.requireNonNull(source, name));
            if (!copy.isFinite()) throw new IllegalArgumentException("Nonfinite " + name);
            return copy;
        }

        private static Matrix4f inverse(Matrix4f matrix, String name) {
            float determinant = matrix.determinant();
            if (!Float.isFinite(determinant) || determinant == 0)
                throw new IllegalArgumentException("Noninvertible " + name);
            Matrix4f inverse = new Matrix4f(matrix).invert();
            if (!inverse.isFinite()) throw new IllegalArgumentException("Nonfinite inverse " + name);
            return inverse;
        }

        public Matrix4f nativeProjection() { return new Matrix4f(nativeProjection); }
        public Matrix4f inverseNativeProjection() { return new Matrix4f(inverseNativeProjection); }
        public Matrix4f projection() { return new Matrix4f(projection); }
        public Matrix4f inverseProjection() { return new Matrix4f(inverseProjection); }
        public Matrix4f modelView() { return new Matrix4f(modelView); }
        public Matrix4f inverseModelView() { return new Matrix4f(inverseModelView); }
        public double x() { return x; }
        public double y() { return y; }
        public double z() { return z; }
        public float near() { return near; }
        public float far() { return far; }
        public float depthScale() { return depthScale; }
        public float depthBias() { return depthBias; }
    }
}
