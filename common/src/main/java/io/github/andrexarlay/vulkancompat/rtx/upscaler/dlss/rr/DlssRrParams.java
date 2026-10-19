package io.github.andrexarlay.vulkancompat.rtx.upscaler.dlss.rr;

import io.github.andrexarlay.vulkancompat.rtx.upscaler.dlss.DlssQuality;

/**
 * Parameters and buffer descriptors for NVIDIA DLSS Ray Reconstruction (DLSS-D / DLSS 3.5+).
 * Marked [DLSS-RR].
 * <p>
 * Ray Reconstruction replaces standard denoisers (such as NRD) with an AI-driven reconstruction
 * model trained to upscale and denoise ray-traced lighting signals simultaneously.
 * API signatures and constants are verified against NVIDIA DLSS SDK v3.7 / v3.5:
 * <ul>
 *   <li>nvsdk_ngx_defs_dlssd.h</li>
 *   <li>nvsdk_ngx_helpers_dlssd_vk.h</li>
 *   <li>nvsdk_ngx_params_dlssd.h</li>
 * </ul>
 */
public final class DlssRrParams {
    private DlssRrParams() {}

    /**
     * Roughness mode:
     * 0 = separate roughness texture (unpacked),
     * 1 = roughness packed in normals.w (RGBA16F where xyz are normals in [-1.0, 1.0], w is roughness in [0.0, 1.0]).
     */
    public static final int ROUGHNESS_UNPACKED = 0;
    public static final int ROUGHNESS_PACKED = 1;

    /** Depth type: 0 = linear depth, 1 = hardware depth (typical for reversed-Z depth buffer). */
    public static final int DEPTH_LINEAR = 0;
    public static final int DEPTH_HW = 1;

    /**
     * DLSS Ray Reconstruction feature flags (mapped to VKB_DLSS_FLAG_*).
     */
    public static final int FLAG_MV_LOW_RES = 1;
    public static final int FLAG_IS_HDR = 1 << 1;
    public static final int FLAG_DEPTH_INVERTED = 1 << 2;
    public static final int FLAG_AUTO_EXPOSURE = 1 << 3;
    public static final int FLAG_MV_JITTERED = 1 << 4;
    public static final int FLAG_SHARPEN = 1 << 5;
    public static final int FLAG_ALPHA_UPSCALING = 1 << 6;

    /**
     * Ray Reconstruction render preset hints (matching NVSDK_NGX_RayReconstruction_Hint_Render_Preset).
     */
    public enum Preset {
        DEFAULT(0),
        D(4), // Transformer model
        E(5), // Latest transformer model (supports DoF guide)
        F(6); // Default model RR2

        private final int nativeValue;

        Preset(int nativeValue) {
            this.nativeValue = nativeValue;
        }

        public int nativeValue() {
            return nativeValue;
        }
    }

    /**
     * Descriptor for a Vulkan image resource passed to DLSS-RR.
     * Matches layout of {@code VkbDlssImage} in {@code vkb_dlss.h} (56 bytes).
     */
    public record Image(
        long image,
        long view,
        int format,
        int width,
        int height,
        int aspectMask,
        boolean readWrite
    ) {
        public static final Image NONE = new Image(0L, 0L, 0, 0, 0, 0, false);

        public boolean isValid() {
            return view != 0L;
        }
    }

    /**
     * Parameters for creating a Ray Reconstruction feature handle.
     * Matches layout of {@code VkbDlssRrCreateInfo} in {@code vkb_dlss.h} (48 bytes).
     */
    public record CreateInfo(
        int renderWidth,
        int renderHeight,
        int outputWidth,
        int outputHeight,
        DlssQuality quality,
        int flags,
        boolean enableOutputSubrects,
        int roughnessMode,
        int depthType
    ) {
        public CreateInfo {
            if (renderWidth <= 0 || renderHeight <= 0) {
                throw new IllegalArgumentException("[DLSS-RR] render resolution must be positive: " + renderWidth + "x" + renderHeight);
            }
            if (outputWidth <= 0 || outputHeight <= 0) {
                throw new IllegalArgumentException("[DLSS-RR] output resolution must be positive: " + outputWidth + "x" + outputHeight);
            }
            if (quality == null) {
                throw new IllegalArgumentException("[DLSS-RR] quality cannot be null");
            }
        }
    }

    /**
     * Parameters and image bindings for evaluating a single frame of DLSS Ray Reconstruction.
     * Matches layout of {@code VkbDlssRrEvalInfo} in {@code vkb_dlss.h} (968 bytes).
     */
    public record EvalInfo(
        boolean reset,
        // Core DLSS buffers
        Image color,
        Image output,
        Image depth,
        Image motionVectors,
        Image exposure,
        // Ray Reconstruction G-Buffer inputs
        Image diffuseAlbedo,
        Image specularAlbedo,
        Image normals,
        Image roughness,
        // Hit distance & auxiliary RT inputs
        Image diffuseHitDistance,
        Image specularHitDistance,
        Image rayTracingHitDistance,
        Image transparencyMask,
        Image disocclusionMask,
        // Scalars
        float jitterX,
        float jitterY,
        float mvScaleX,
        float mvScaleY,
        float sharpness,
        float preExposure,
        float exposureScale,
        float frameTimeDeltaMs,
        int renderSubrectWidth,
        int renderSubrectHeight,
        // Optional transform matrices (16 floats each, row/column major as per camera convention)
        float[] worldToViewMatrix,
        float[] viewToClipMatrix
    ) {
        public EvalInfo {
            if (color == null || !color.isValid()) {
                throw new IllegalArgumentException("[DLSS-RR] color image must be valid");
            }
            if (output == null || !output.isValid()) {
                throw new IllegalArgumentException("[DLSS-RR] output image must be valid");
            }
            if (!output.readWrite()) {
                throw new IllegalArgumentException("[DLSS-RR] output image must have readWrite=true (storage usage required)");
            }
            if (depth == null || !depth.isValid()) {
                throw new IllegalArgumentException("[DLSS-RR] depth image must be valid");
            }
            if (motionVectors == null || !motionVectors.isValid()) {
                throw new IllegalArgumentException("[DLSS-RR] motion vectors image must be valid");
            }
            if (diffuseAlbedo == null || !diffuseAlbedo.isValid()) {
                throw new IllegalArgumentException("[DLSS-RR] diffuse albedo image must be valid");
            }
            if (specularAlbedo == null || !specularAlbedo.isValid()) {
                throw new IllegalArgumentException("[DLSS-RR] specular albedo image must be valid");
            }
            if (normals == null || !normals.isValid()) {
                throw new IllegalArgumentException("[DLSS-RR] normals image must be valid");
            }
            if (exposure == null) exposure = Image.NONE;
            if (roughness == null) roughness = Image.NONE;
            if (diffuseHitDistance == null) diffuseHitDistance = Image.NONE;
            if (specularHitDistance == null) specularHitDistance = Image.NONE;
            if (rayTracingHitDistance == null) rayTracingHitDistance = Image.NONE;
            if (transparencyMask == null) transparencyMask = Image.NONE;
            if (disocclusionMask == null) disocclusionMask = Image.NONE;
            if (worldToViewMatrix != null && worldToViewMatrix.length != 16) {
                throw new IllegalArgumentException("[DLSS-RR] worldToViewMatrix must be 16 floats");
            }
            if (viewToClipMatrix != null && viewToClipMatrix.length != 16) {
                throw new IllegalArgumentException("[DLSS-RR] viewToClipMatrix must be 16 floats");
            }
        }
    }
}
