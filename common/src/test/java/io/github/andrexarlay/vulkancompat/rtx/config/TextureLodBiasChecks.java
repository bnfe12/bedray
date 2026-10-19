package io.github.andrexarlay.vulkancompat.rtx.config;

import io.github.andrexarlay.vulkancompat.rtx.upscaler.UpscalerBackend;
import org.lwjgl.vulkan.VK10;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Verification checks for {@link TextureLodBias} (LOD bias calculations, point sampling sharpness,
 * RCAS integration, resource pack adaptation, and config persistence).
 */
public final class TextureLodBiasChecks {
    private TextureLodBiasChecks() {}

    public static void runAll() throws Exception {
        testLog2LodBiasCalculation();
        testDimensionsLodBiasFormula();
        testPointSamplingSharpness();
        testRcasIntegration();
        testResourcePackAdaptation();
        testUpscalerModesLodBias();
        testLodBiasOffset();
        testManualModeAndSystemPropertyOverride();
        testTomlConfigurationRoundTrip();
        testMixinRegistration();
    }

    private static void check(boolean cond, String msg) {
        if (!cond) {
            throw new AssertionError(msg);
        }
    }

    private static void near(float expected, float actual, float delta, String msg) {
        if (Math.abs(expected - actual) > delta) {
            throw new AssertionError(msg + " (expected: " + expected + ", actual: " + actual + ", delta: " + delta + ")");
        }
    }

    private static void testLog2LodBiasCalculation() {
        // Native / 1.0x -> 0.0f
        near(0.0f, TextureLodBias.calculateLodBias(1.0), 1e-5f, "1.0x scale gives 0.0 LOD bias");
        near(0.0f, TextureLodBias.calculateLodBias(1.5), 1e-5f, "Super-sampling scale > 1.0 gives 0.0 bias");

        // Quality: 0.667x -> approx -0.58425f (rounds to -0.585f)
        near(-0.58425f, TextureLodBias.calculateLodBias(0.667), 1e-3f, "0.667x render scale approx -0.585");
        near(-0.58496f, TextureLodBias.calculateLodBias(1.0 / 1.5), 1e-4f, "1.5x upscaling ratio (Quality) gives -0.585");

        // Performance: 0.5x scale (2.0x upscaling): exactly log2(0.5) = -1.0f
        near(-1.0f, TextureLodBias.calculateLodBias(0.5), 1e-5f, "0.5x render scale gives -1.0f");

        // Balanced: 1.0 / 1.7 scale -> log2(1/1.7) ≈ -0.7655f
        near(-0.76553f, TextureLodBias.calculateLodBias(1.0 / 1.7), 1e-4f, "1.7x upscaling ratio (Balanced) gives -0.766");

        // Ultra Performance: 1.0 / 3.0 scale -> log2(1/3) ≈ -1.585f
        near(-1.58496f, TextureLodBias.calculateLodBias(1.0 / 3.0), 1e-4f, "3.0x upscaling ratio (Ultra Performance) gives -1.585");

        // Clamping edge cases
        near(TextureLodBias.MIN_LOD_BIAS, TextureLodBias.calculateLodBias(0.0), 1e-5f, "0.0 scale clamped to MIN_LOD_BIAS");
        near(TextureLodBias.MIN_LOD_BIAS, TextureLodBias.calculateLodBias(-0.5), 1e-5f, "Negative scale clamped to MIN_LOD_BIAS");
        near(0.0f, TextureLodBias.calculateLodBias(Double.NaN), 1e-5f, "Non-finite render scale uses neutral bias");
        near(0.0f, TextureLodBias.clamp(Float.NaN), 1e-5f, "Non-finite configured bias uses neutral bias");
    }

    private static void testDimensionsLodBiasFormula() {
        // lodBias = log2(W_render / W_display) + offset
        // Performance: 640x360 on 1280x720 display -> 640/1280 = 0.5 -> -1.0f
        near(-1.0f, TextureLodBias.calculateLodBiasForDimensions(640, 1280), 1e-5f, "640 on 1280 gives -1.0");

        // Quality: 854x480 on 1280x720 display -> 854/1280 ≈ 0.66719 -> approx -0.5838f (≈ -0.585f)
        near(-0.58385f, TextureLodBias.calculateLodBiasForDimensions(854, 1280), 1e-3f, "854 on 1280 approx -0.585");

        // Native: 1920x1080 on 1920x1080 display -> 1.0 -> 0.0f
        near(0.0f, TextureLodBias.calculateLodBiasForDimensions(1920, 1920), 1e-5f, "1920 on 1920 gives 0.0");

        // With offset: 640 on 1280 with offset -0.25f -> -1.25f
        near(-1.25f, TextureLodBias.calculateLodBiasForDimensions(640, 1280, -0.25f), 1e-5f, "640 on 1280 with offset -0.25 gives -1.25");

        // Degenerate dimensions
        near(0.0f, TextureLodBias.calculateLodBiasForDimensions(0, 1080), 1e-5f, "0 render width clamped to offset 0.0");
        near(0.0f, TextureLodBias.calculateLodBiasForDimensions(1920, 0), 1e-5f, "0 display width clamped to offset 0.0");
    }

    private static void testPointSamplingSharpness() {
        boolean saved = TextureLodBias.pointSamplingSharpness;
        try {
            TextureLodBias.setPointSamplingSharpness(false);
            check(!TextureLodBias.isPointSamplingSharpness(), "Point sampling disabled");
            check(TextureLodBias.getMagFilter(VK10.VK_FILTER_LINEAR) == VK10.VK_FILTER_LINEAR,
                "Linear filter preserved when point sampling disabled");

            TextureLodBias.setPointSamplingSharpness(true);
            check(TextureLodBias.isPointSamplingSharpness(), "Point sampling enabled");
            check(TextureLodBias.getMagFilter(VK10.VK_FILTER_LINEAR) == VK10.VK_FILTER_NEAREST,
                "Nearest filter forced when point sampling enabled");

            // System property override
            String propKey = "vkrtx.point_sampling_sharpness";
            String savedProp = System.getProperty(propKey);
            try {
                System.setProperty(propKey, "false");
                check(!TextureLodBias.isPointSamplingSharpness(), "System property false overrides point sampling");
                System.setProperty(propKey, "true");
                check(TextureLodBias.isPointSamplingSharpness(), "System property true overrides point sampling");
            } finally {
                if (savedProp != null) {
                    System.setProperty(propKey, savedProp);
                } else {
                    System.clearProperty(propKey);
                }
            }
        } finally {
            TextureLodBias.setPointSamplingSharpness(saved);
        }
    }

    private static void testRcasIntegration() {
        near(0.0f, TextureLodBias.rcasStopsForSlider(1.0f), 1e-5f, "1.0 slider gives 0.0 RCAS stops (max sharpness)");
        near(1.0f, TextureLodBias.rcasStopsForSlider(0.5f), 1e-5f, "0.5 slider gives 1.0 RCAS stop");
        near(2.0f, TextureLodBias.rcasStopsForSlider(0.0f), 1e-5f, "0.0 slider gives 2.0 RCAS stops");

        // Modulated LOD bias: with 0.0 RCAS sharpness, base LOD bias is unchanged
        float baseBias = TextureLodBias.calculateLodBias(1.0 / 1.5, 0.0f);
        near(baseBias, TextureLodBias.calculateRcasModulatedLodBias(1.0 / 1.5, 0.0f, 0.0f), 1e-5f,
            "0.0 RCAS sharpness leaves LOD bias unchanged");

        // With high RCAS sharpness (>0.5), gentle damping is applied to avoid excessive moire
        float dampedBias = TextureLodBias.calculateRcasModulatedLodBias(1.0 / 1.5, 0.0f, 1.0f);
        check(dampedBias > baseBias, "High RCAS sharpness dampens negative bias slightly (less negative)");
        near(baseBias + 0.1f, dampedBias, 1e-5f, "1.0 RCAS sharpness adds 0.1f damping: " + dampedBias);
    }

    private static void testResourcePackAdaptation() {
        // 16x16 is vanilla base: 0.0 adjustment
        near(0.0f, TextureLodBias.calculateResourcePackLodAdjustment(16), 1e-5f, "16x16 adjustment is 0.0");
        near(0.0f, TextureLodBias.calculateResourcePackLodAdjustment(8), 1e-5f, "<=16 adjustment is 0.0");

        // 32x32: log2(32/16) * 0.25 = 1 * 0.25 = +0.25f
        near(0.25f, TextureLodBias.calculateResourcePackLodAdjustment(32), 1e-5f, "32x32 adjustment is +0.25");

        // 64x64: log2(64/16) * 0.25 = 2 * 0.25 = +0.50f
        near(0.50f, TextureLodBias.calculateResourcePackLodAdjustment(64), 1e-5f, "64x64 adjustment is +0.50");

        // 128x128: log2(128/16) * 0.25 = 3 * 0.25 = +0.75f
        near(0.75f, TextureLodBias.calculateResourcePackLodAdjustment(128), 1e-5f, "128x128 adjustment is +0.75");

        // Combined formula with pack resolution
        near(-1.0f, TextureLodBias.calculateLodBiasForDimensions(640, 1280, 0.0f, 16), 1e-5f, "Vanilla pack gives -1.0");
        near(-0.25f, TextureLodBias.calculateLodBiasForDimensions(640, 1280, 0.0f, 128), 1e-5f, "128x pack softens to -0.25");
    }

    private static void testUpscalerModesLodBias() {
        // DLSS
        near(0.0f, TextureLodBias.getLodBiasFor(UpscalerBackend.DLSS, UpscalerMode.DLAA), 1e-5f, "DLSS DLAA");
        near(-0.58496f, TextureLodBias.getLodBiasFor(UpscalerBackend.DLSS, UpscalerMode.QUALITY), 1e-4f, "DLSS Quality");
        near(-0.76553f, TextureLodBias.getLodBiasFor(UpscalerBackend.DLSS, UpscalerMode.BALANCED), 1e-4f, "DLSS Balanced");
        near(-1.0f, TextureLodBias.getLodBiasFor(UpscalerBackend.DLSS, UpscalerMode.PERFORMANCE), 1e-5f, "DLSS Performance");
        near(-1.58496f, TextureLodBias.getLodBiasFor(UpscalerBackend.DLSS, UpscalerMode.ULTRA_PERFORMANCE), 1e-4f, "DLSS Ultra Performance");

        // FSR
        near(0.0f, TextureLodBias.getLodBiasFor(UpscalerBackend.FSR, UpscalerMode.NATIVE_AA), 1e-5f, "FSR Native AA");
        near(-0.58496f, TextureLodBias.getLodBiasFor(UpscalerBackend.FSR, UpscalerMode.QUALITY), 1e-4f, "FSR Quality");
        near(-0.76553f, TextureLodBias.getLodBiasFor(UpscalerBackend.FSR, UpscalerMode.BALANCED), 1e-4f, "FSR Balanced");
        near(-1.0f, TextureLodBias.getLodBiasFor(UpscalerBackend.FSR, UpscalerMode.PERFORMANCE), 1e-5f, "FSR Performance");
        near(-1.58496f, TextureLodBias.getLodBiasFor(UpscalerBackend.FSR, UpscalerMode.ULTRA_PERFORMANCE), 1e-4f, "FSR Ultra Performance");

        // XeSS
        near(-0.37851f, TextureLodBias.getLodBiasFor(UpscalerBackend.XESS, UpscalerMode.ULTRA_QUALITY_PLUS), 1e-4f, "XeSS UQ+ (1.3x)");
        near(-0.58496f, TextureLodBias.getLodBiasFor(UpscalerBackend.XESS, UpscalerMode.ULTRA_QUALITY), 1e-4f, "XeSS UQ (1.5x)");
        near(-0.76553f, TextureLodBias.getLodBiasFor(UpscalerBackend.XESS, UpscalerMode.QUALITY), 1e-4f, "XeSS Quality (1.7x)");
        near(-1.0f, TextureLodBias.getLodBiasFor(UpscalerBackend.XESS, UpscalerMode.BALANCED), 1e-5f, "XeSS Balanced (2.0x)");
        near(-1.20163f, TextureLodBias.getLodBiasFor(UpscalerBackend.XESS, UpscalerMode.PERFORMANCE), 1e-4f, "XeSS Performance (2.3x)");
        near(-1.58496f, TextureLodBias.getLodBiasFor(UpscalerBackend.XESS, UpscalerMode.ULTRA_PERFORMANCE), 1e-4f, "XeSS Ultra Performance (3.0x)");

        // FSR 1
        near(-0.37851f, TextureLodBias.getLodBiasFor(UpscalerBackend.FSR1, UpscalerMode.ULTRA_QUALITY), 1e-4f, "FSR 1 UQ (1.3x)");
        near(-0.58496f, TextureLodBias.getLodBiasFor(UpscalerBackend.FSR1, UpscalerMode.QUALITY), 1e-4f, "FSR 1 Quality (1.5x)");
        near(-0.76553f, TextureLodBias.getLodBiasFor(UpscalerBackend.FSR1, UpscalerMode.BALANCED), 1e-4f, "FSR 1 Balanced (1.7x)");
        near(-1.0f, TextureLodBias.getLodBiasFor(UpscalerBackend.FSR1, UpscalerMode.PERFORMANCE), 1e-5f, "FSR 1 Performance (2.0x)");

        // NIS custom scale uses the configured percent and has a 50% minimum.
        int savedNisPercent = VulkanRtxConfig.customPercent(UpscalerBackend.NIS);
        try {
            VulkanRtxConfig.setCustomPercent(UpscalerBackend.NIS, 50);
            near(-1.0f, TextureLodBias.getLodBiasFor(UpscalerBackend.NIS, UpscalerMode.CUSTOM), 1e-5f,
                "NIS custom 50% gives -1.0");
        } finally {
            VulkanRtxConfig.setCustomPercent(UpscalerBackend.NIS, savedNisPercent);
        }

        // Bilinear presets
        VulkanRtxConfig.QualityPreset savedPreset = VulkanRtxConfig.qualityPreset;
        try {
            VulkanRtxConfig.qualityPreset = VulkanRtxConfig.QualityPreset.NATIVE;
            near(0.0f, TextureLodBias.getLodBiasFor(UpscalerBackend.BILINEAR, null), 1e-5f, "Bilinear Native");

            VulkanRtxConfig.qualityPreset = VulkanRtxConfig.QualityPreset.PERFORMANCE;
            near(-1.0f, TextureLodBias.getLodBiasFor(UpscalerBackend.BILINEAR, null), 1e-5f, "Bilinear Performance 50%");

            VulkanRtxConfig.qualityPreset = VulkanRtxConfig.QualityPreset.BALANCED;
            near(-0.58424f, TextureLodBias.getLodBiasFor(UpscalerBackend.BILINEAR, null), 1e-3f, "Bilinear Balanced 66.7%");
        } finally {
            VulkanRtxConfig.qualityPreset = savedPreset;
        }
    }

    private static void testLodBiasOffset() {
        float base = TextureLodBias.calculateLodBias(1.0 / 1.5);
        float offset = -0.25f;
        near(base + offset, TextureLodBias.calculateLodBias(1.0 / 1.5, offset), 1e-5f, "Base with offset");

        // Clamping check on extreme offset
        near(TextureLodBias.MIN_LOD_BIAS, TextureLodBias.calculateLodBias(0.5, -10.0f), 1e-5f, "Offset clamp min");
        near(TextureLodBias.MAX_LOD_BIAS, TextureLodBias.calculateLodBias(1.0, 10.0f), 1e-5f, "Offset clamp max");
    }

    private static void testManualModeAndSystemPropertyOverride() {
        boolean savedAuto = TextureLodBias.autoTextureLodBias;
        float savedManual = TextureLodBias.manualTextureLodBias;
        try {
            TextureLodBias.autoTextureLodBias = false;
            TextureLodBias.manualTextureLodBias = -0.75f;
            near(-0.75f, TextureLodBias.getEffectiveLodBias(), 1e-5f, "Manual mode returns manualTextureLodBias");

            // System property override takes precedence over everything
            String propKey = "vkrtx.texture_lod_bias";
            String savedProp = System.getProperty(propKey);
            try {
                System.setProperty(propKey, "-1.25");
                near(-1.25f, TextureLodBias.getEffectiveLodBias(), 1e-5f, "System property override takes precedence");
            } finally {
                if (savedProp != null) {
                    System.setProperty(propKey, savedProp);
                } else {
                    System.clearProperty(propKey);
                }
            }
        } finally {
            TextureLodBias.autoTextureLodBias = savedAuto;
            TextureLodBias.manualTextureLodBias = savedManual;
        }
    }

    private static void testTomlConfigurationRoundTrip() throws Exception {
        Path tempDir = Files.createTempDirectory("vkb_lodbias_test");
        File tempConfig = tempDir.resolve("bedray.toml").toFile();
        File savedConfig = new File("config/bedray.toml");
        boolean savedAuto = VulkanRtxConfig.autoTextureLodBias;
        float savedOffset = VulkanRtxConfig.textureLodBiasOffset;
        boolean savedPoint = VulkanRtxConfig.pointSamplingSharpness;
        int savedPackRes = VulkanRtxConfig.resourcePackResolution;
        boolean savedTextureAuto = TextureLodBias.autoTextureLodBias;
        float savedTextureOffset = TextureLodBias.textureLodBiasOffset;
        float savedTextureManual = TextureLodBias.manualTextureLodBias;
        boolean savedTexturePoint = TextureLodBias.pointSamplingSharpness;
        int savedTexturePackRes = TextureLodBias.resourcePackResolution;

        try {
            VulkanRtxConfig.setConfigFileForTesting(tempConfig);
            VulkanRtxConfig.autoTextureLodBias = true;
            VulkanRtxConfig.textureLodBiasOffset = -0.3f;
            VulkanRtxConfig.pointSamplingSharpness = true;
            VulkanRtxConfig.resourcePackResolution = 64;
            VulkanRtxConfig.save();

            check(tempConfig.exists(), "Config file created");
            String content = Files.readString(tempConfig.toPath());
            check(content.contains("auto_texture_lod_bias = true"), "Saved auto_texture_lod_bias");
            check(content.contains("texture_lod_bias_offset = -0.3"), "Saved texture_lod_bias_offset");
            check(content.contains("point_sampling_sharpness = true"), "Saved point_sampling_sharpness");
            check(content.contains("resource_pack_resolution = 64"), "Saved resource_pack_resolution");

            // Reset and reload
            VulkanRtxConfig.autoTextureLodBias = false;
            VulkanRtxConfig.textureLodBiasOffset = 0.0f;
            VulkanRtxConfig.pointSamplingSharpness = false;
            VulkanRtxConfig.resourcePackResolution = 16;
            VulkanRtxConfig.load();

            check(VulkanRtxConfig.autoTextureLodBias, "Reloaded auto_texture_lod_bias");
            near(-0.3f, VulkanRtxConfig.textureLodBiasOffset, 1e-5f, "Reloaded texture_lod_bias_offset");
            check(VulkanRtxConfig.pointSamplingSharpness, "Reloaded point_sampling_sharpness");
            check(VulkanRtxConfig.resourcePackResolution == 64, "Reloaded resource_pack_resolution 64");
            check(TextureLodBias.isPointSamplingSharpness(), "TextureLodBias synced pointSamplingSharpness");
        } finally {
            VulkanRtxConfig.autoTextureLodBias = savedAuto;
            VulkanRtxConfig.textureLodBiasOffset = savedOffset;
            VulkanRtxConfig.pointSamplingSharpness = savedPoint;
            VulkanRtxConfig.resourcePackResolution = savedPackRes;
            TextureLodBias.setPointSamplingSharpness(savedPoint);
            TextureLodBias.autoTextureLodBias = savedTextureAuto;
            TextureLodBias.textureLodBiasOffset = savedTextureOffset;
            TextureLodBias.manualTextureLodBias = savedTextureManual;
            TextureLodBias.pointSamplingSharpness = savedTexturePoint;
            TextureLodBias.resourcePackResolution = savedTexturePackRes;
            VulkanRtxConfig.setConfigFileForTesting(savedConfig);
            tempConfig.delete();
            tempDir.toFile().delete();
        }
    }

    private static void testMixinRegistration() throws Exception {
        Path mixinJson = Path.of("src", "main", "resources", "mixins.bedray.json");
        if (!Files.exists(mixinJson)) {
            mixinJson = Path.of("common", "src", "main", "resources", "mixins.bedray.json");
        }
        check(Files.exists(mixinJson), "mixins.bedray.json exists");
        String content = Files.readString(mixinJson);
        check(content.contains("rtx.MixinVulkanGpuSampler"), "MixinVulkanGpuSampler registered in mixins.bedray.json");
    }
}
