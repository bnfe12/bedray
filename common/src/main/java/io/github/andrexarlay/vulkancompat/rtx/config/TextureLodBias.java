package io.github.andrexarlay.vulkancompat.rtx.config;

import io.github.andrexarlay.vulkancompat.rtx.upscaler.spatial.SpatialUpscaler;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.TemporalUpscaler;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.UpscalerBackend;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.dlss.DlssRuntime;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.fsr.FsrQuality;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.fsr.FsrRuntime;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.spatial.Fsr1Constants;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.xess.XessRuntime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Texture Mipmap LOD bias calculations and point sampling sharpness for texture samplers under super resolution / upscaling
 * (DLSS, FSR, XeSS, FSR1, NIS, Bilinear).
 * <p>
 * Standard SDK specification (NVIDIA DLSS, AMD FSR, Intel XeSS):
 * {@code lodBias = log2(renderWidth / displayWidth) + offset = log2(renderScale) + offset}
 * Examples:
 * - Quality (0.667x): log2(0.667) ≈ -0.585
 * - Performance (0.500x): log2(0.500) = -1.0
 * - Ultra Performance (0.333x): log2(0.333) ≈ -1.585
 * - Balanced (0.588x): log2(1.0 / 1.7) ≈ -0.7655
 * - Native / DLAA (1.000x): log2(1.0) = 0.0 (+ offset)
 * <p>
 * Without negative LOD bias, texture samplers select higher (blurrier) mip levels at reduced resolution,
 * causing DLSS/FSR/XeSS output to appear blurry ("мыло") on pixel-art and voxel textures.
 * <p>
 * Additional features:
 * - {@link #pointSamplingSharpness}: preserves crisp 16x16 voxel pixels (point sampling magnification).
 * - Resource pack resolution adaptation: adapts bias based on pack resolution (16x, 32x, 64x, 128x, ...).
 * - Integration with Contrast-Adaptive Sharpening (RCAS / CAS).
 */
public final class TextureLodBias {
    private static final Logger LOGGER = LoggerFactory.getLogger("Bedray/TextureLodBias");

    private static final double LN_2 = Math.log(2.0);

    /** Safe clamp range for Vulkan sampler mipLodBias. */
    public static final float MIN_LOD_BIAS = -4.0f;
    public static final float MAX_LOD_BIAS = 2.0f;

    /** Base resource pack texture resolution in vanilla Minecraft (16x16). */
    public static final int VANILLA_PACK_RESOLUTION = 16;

    public static boolean autoTextureLodBias = true;
    public static float textureLodBiasOffset = 0.0f;
    public static float manualTextureLodBias = 0.0f;
    public static boolean pointSamplingSharpness = false;
    public static int resourcePackResolution = VANILLA_PACK_RESOLUTION;

    private TextureLodBias() {}

    /**
     * Calculates the standard Mipmap LOD bias for a given render resolution scale.
     * Formula: {@code log2(renderScale)}
     * Clamped to [MIN_LOD_BIAS, 0.0f]. For scales >= 1.0 (native or super-sampling), returns 0.0f.
     */
    public static float calculateLodBias(double renderScale) {
        if (!Double.isFinite(renderScale)) {
            return 0.0f;
        }
        if (renderScale >= 1.0) {
            return 0.0f;
        }
        if (renderScale <= 0.0) {
            return MIN_LOD_BIAS;
        }
        double bias = Math.log(renderScale) / LN_2;
        return (float) Math.max(MIN_LOD_BIAS, Math.min(0.0, bias));
    }

    /**
     * Calculates LOD bias with an additional user offset (e.g. for extra sharpness in pixel-art games).
     * Formula: {@code log2(renderScale) + offset}
     * Clamped to [MIN_LOD_BIAS, MAX_LOD_BIAS].
     */
    public static float calculateLodBias(double renderScale, float offset) {
        float base = calculateLodBias(renderScale);
        return clamp(base + offset);
    }

    /**
     * Calculates LOD bias from render dimensions:
     * {@code lodBias = log2(renderWidth / displayWidth) + offset}
     */
    public static float calculateLodBiasForDimensions(double renderWidth, double displayWidth, float offset) {
        if (displayWidth <= 0.0 || renderWidth <= 0.0) {
            return clamp(offset);
        }
        double scale = renderWidth / displayWidth;
        return calculateLodBias(scale, offset);
    }

    public static float calculateLodBiasForDimensions(double renderWidth, double displayWidth) {
        return calculateLodBiasForDimensions(renderWidth, displayWidth, 0.0f);
    }

    /**
     * Calculates LOD bias with resource pack texture resolution adaptation.
     * Higher resolution resource packs (e.g. 128x128) contain finer texels and more mip levels,
     * so an adjustment is made to optimize detail without high-frequency aliasing.
     */
    public static float calculateLodBiasForDimensions(double renderWidth, double displayWidth, float offset, int textureResolution) {
        float bias = calculateLodBiasForDimensions(renderWidth, displayWidth, offset);
        float packAdjustment = calculateResourcePackLodAdjustment(textureResolution);
        return clamp(bias + packAdjustment);
    }

    /**
     * Computes the resource pack resolution adjustment.
     * Vanilla 16x16 is the baseline (0.0 adjustment).
     * For 128x128 textures: ratio = 128 / 16 = 8 = 2^3 -> log2(8) * 0.25 = +0.75f softening.
     */
    public static float calculateResourcePackLodAdjustment(int textureResolution) {
        if (textureResolution <= VANILLA_PACK_RESOLUTION) {
            return 0.0f;
        }
        double ratio = (double) textureResolution / VANILLA_PACK_RESOLUTION;
        return (float) (Math.log(ratio) / LN_2 * 0.25f);
    }

    /**
     * Integrates negative LOD bias with Robust Contrast Adaptive Sharpening (RCAS / CAS).
     * When RCAS is active with high sharpness, negative LOD bias and edge contrast sharpening work in synergy.
     * To prevent high-frequency ringing / shimmering on fine textures when both are near maximum,
     * a gentle damping factor is computed.
     *
     * @param renderScale   current render scale (e.g. 0.667 for Quality)
     * @param offset        user LOD bias offset
     * @param rcasSharpness RCAS sharpness slider value in [0.0, 1.0]
     * @return harmonized LOD bias
     */
    public static float calculateRcasModulatedLodBias(double renderScale, float offset, float rcasSharpness) {
        float baseBias = calculateLodBias(renderScale, offset);
        if (rcasSharpness <= 0.0f) {
            return baseBias;
        }
        float clampedSharpness = Math.max(0.0f, Math.min(1.0f, rcasSharpness));
        float damping = (float) Math.max(0.0, (clampedSharpness - 0.5f) * 0.2f);
        return clamp(baseBias + damping);
    }

    /**
     * Converts an RCAS sharpness slider value [0.0, 1.0] to RCAS stops.
     * 1.0 -> 0.0 stops (maximum sharpening), 0.5 -> 1.0 stop, 0.0 -> 2.0 stops (off/minimal).
     */
    public static float rcasStopsForSlider(float sharpness) {
        return Fsr1Constants.stopsForSlider(sharpness);
    }

    /**
     * Returns true if RCAS sharpening is currently active in the configured upscaler.
     */
    public static boolean isRcasActive() {
        return getEffectiveRcasSharpness() > 0.0f;
    }

    /**
     * Resolves the current effective RCAS sharpness setting based on the active upscaler.
     */
    public static float getEffectiveRcasSharpness() {
        UpscalerBackend backend = VulkanRtxConfig.activeBackend;
        if (backend == UpscalerBackend.FSR1) {
            return VulkanRtxConfig.fsr1Sharpness;
        }
        if (backend == UpscalerBackend.FSR) {
            return VulkanRtxConfig.fsrSharpness;
        }
        String prop = System.getProperty("vkrtx.rcas_sharpness");
        if (prop != null) {
            try {
                return Float.parseFloat(prop.trim());
            } catch (NumberFormatException ignored) {}
        }
        return 0.0f;
    }

    /**
     * Whether point sampling sharpness is currently enabled.
     * Forces NEAREST magnification filter for textures so that 16x16 Minecraft voxel pixels
     * stay pixel-perfect and sharp instead of blurred.
     */
    public static boolean isPointSamplingSharpness() {
        String prop = System.getProperty("vkrtx.point_sampling_sharpness");
        if (prop == null) {
            prop = System.getProperty("vkrtx.pointSamplingSharpness");
        }
        if (prop != null) {
            return Boolean.parseBoolean(prop.trim());
        }
        return pointSamplingSharpness;
    }

    public static void setPointSamplingSharpness(boolean enabled) {
        pointSamplingSharpness = enabled;
        VulkanRtxConfig.pointSamplingSharpness = enabled;
    }

    /**
     * Returns the magnification filter to use for Vulkan samplers.
     * If {@link #isPointSamplingSharpness()} is active, returns VK_FILTER_NEAREST (0).
     */
    public static int getMagFilter(int originalMagFilter) {
        if (isPointSamplingSharpness()) {
            return org.lwjgl.vulkan.VK10.VK_FILTER_NEAREST;
        }
        return originalMagFilter;
    }

    /**
     * Computes the theoretical render scale (renderRes / outputRes) for the given upscaler backend and mode.
     */
    public static double getRenderScaleFor(UpscalerBackend backend, UpscalerMode mode) {
        if (backend == null || backend == UpscalerBackend.BILINEAR) {
            return VulkanRtxConfig.qualityPreset.getScaleFactor();
        }
        if (mode == UpscalerMode.CUSTOM) {
            return VulkanRtxConfig.customPercent(backend) / 100.0;
        }
        return switch (backend) {
            case DLSS -> {
                if (mode == null) yield 1.0;
                yield switch (mode) {
                    case DLAA -> 1.0;
                    case QUALITY -> 1.0 / 1.5;
                    case BALANCED -> 1.0 / 1.7;
                    case PERFORMANCE -> 1.0 / 2.0;
                    case ULTRA_PERFORMANCE -> 1.0 / 3.0;
                    case CUSTOM -> VulkanRtxConfig.customPercent(UpscalerBackend.DLSS) / 100.0;
                    default -> 1.0;
                };
            }
            case FSR -> {
                if (mode == null) yield 1.0;
                try {
                    yield 1.0 / FsrQuality.fromMode(mode).ratio();
                } catch (IllegalArgumentException e) {
                    yield 1.0;
                }
            }
            case XESS -> {
                if (mode == null) yield 1.0;
                try {
                    yield UpscalerModes.scale(UpscalerBackend.XESS, mode);
                } catch (IllegalArgumentException e) {
                    yield 1.0;
                }
            }
            case FSR1 -> {
                if (mode == null) yield 1.0;
                try {
                    yield UpscalerModes.scale(UpscalerBackend.FSR1, mode);
                } catch (IllegalArgumentException e) {
                    yield 1.0;
                }
            }
            case NIS -> VulkanRtxConfig.customPercent(UpscalerBackend.NIS) / 100.0;
            case BILINEAR -> VulkanRtxConfig.qualityPreset.getScaleFactor();
        };
    }

    /**
     * Computes the theoretical LOD bias for the given upscaler backend and mode.
     */
    public static float getLodBiasFor(UpscalerBackend backend, UpscalerMode mode) {
        double scale = getRenderScaleFor(backend, mode);
        return calculateLodBias(scale);
    }

    /**
     * Gets the effective Mipmap LOD bias to apply to texture samplers, taking into account
     * active runtime scaling, configurations, offsets, RCAS integration, and system property overrides.
     */
    public static float getEffectiveLodBias() {
        String propOverride = System.getProperty("vkrtx.texture_lod_bias");
        if (propOverride == null) {
            propOverride = System.getProperty("vkrtx.textureLodBias");
        }
        if (propOverride != null) {
            try {
                return clamp(Float.parseFloat(propOverride.trim()));
            } catch (NumberFormatException e) {
                LOGGER.warn("Invalid -Dvkrtx.texture_lod_bias='{}', ignoring", propOverride);
            }
        }

        String autoOverride = System.getProperty("vkrtx.auto_lod_bias");
        boolean auto = (autoOverride != null) ? Boolean.parseBoolean(autoOverride) : autoTextureLodBias;

        if (!auto) {
            return clamp(manualTextureLodBias);
        }

        double activeScale = getActiveRenderScale();
        float rcasSharp = getEffectiveRcasSharpness();
        float bias = calculateRcasModulatedLodBias(activeScale, textureLodBiasOffset, rcasSharp);

        if (resourcePackResolution > VANILLA_PACK_RESOLUTION) {
            bias = clamp(bias + calculateResourcePackLodAdjustment(resourcePackResolution));
        }

        return bias;
    }

    /**
     * Resolves the current active render scale, preferring live runtime metrics if active.
     */
    public static double getActiveRenderScale() {
        if (SpatialUpscaler.isSelected()) {
            return SpatialUpscaler.renderScale();
        }
        if (TemporalUpscaler.isActive()) {
            double s = TemporalUpscaler.scale();
            if (s > 0.0) {
                return 1.0 / s;
            }
        }

        UpscalerBackend backend = VulkanRtxConfig.activeBackend;
        if (backend == null) {
            return 1.0;
        }

        if (backend == UpscalerBackend.DLSS) {
            if (DlssRuntime.isActive()) {
                double dlssScale = DlssRuntime.scale();
                if (dlssScale > 0.0) {
                    return 1.0 / dlssScale;
                }
            }
            return getRenderScaleFor(UpscalerBackend.DLSS, VulkanRtxConfig.dlssMode);
        }

        if (backend == UpscalerBackend.FSR) {
            if (FsrRuntime.isActive()) {
                double fsrScale = FsrRuntime.scale();
                if (fsrScale > 0.0) {
                    return 1.0 / fsrScale;
                }
            }
            return getRenderScaleFor(UpscalerBackend.FSR, VulkanRtxConfig.fsrMode);
        }

        if (backend == UpscalerBackend.XESS) {
            if (XessRuntime.isActive()) {
                double xessScale = XessRuntime.scale();
                if (xessScale > 0.0) {
                    return 1.0 / xessScale;
                }
            }
            return getRenderScaleFor(UpscalerBackend.XESS, VulkanRtxConfig.xessMode);
        }

        if (backend == UpscalerBackend.FSR1) {
            return getRenderScaleFor(UpscalerBackend.FSR1, VulkanRtxConfig.fsr1Mode);
        }

        if (backend == UpscalerBackend.NIS) {
            return getRenderScaleFor(UpscalerBackend.NIS, UpscalerMode.CUSTOM);
        }

        if (backend == UpscalerBackend.BILINEAR) {
            return VulkanRtxConfig.qualityPreset.getScaleFactor();
        }

        return 1.0;
    }

    public static float clamp(float val) {
        if (!Float.isFinite(val)) {
            return 0.0f;
        }
        return Math.max(MIN_LOD_BIAS, Math.min(MAX_LOD_BIAS, val));
    }
}
