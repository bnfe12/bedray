package io.github.andrexarlay.vulkancompat;

import io.github.andrexarlay.vulkancompat.rtx.VulkanRtxManager;
import io.github.andrexarlay.vulkancompat.rtx.WorldTargetManager;
import io.github.andrexarlay.vulkancompat.rtx.config.VulkanRtxConfig;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.HaltonJitter;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.UpscalerBackend;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.UpscalerLibraryLoader;
import com.mojang.renderpearl.backend.vulkan.VulkanFeatureSets;
import com.mojang.renderpearl.backend.vulkan.init.VulkanFeature;
import com.mojang.renderpearl.backend.vulkan.init.VulkanPNextStruct;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.xess.XessConfig;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.xess.XessLayouts;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.xess.XessNative;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.xess.XessNativeException;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.xess.XessQuality;
import org.joml.Vector2f;

/**
 * Java-level checks only (L2 at best). These do NOT touch the GPU and are NOT evidence that anything works in game.
 * Tests of removed/STUB layers (upscalers, ray tracing, NRD, Nvngx loader, Iris compat, Sodium decoder, etc.)
 * were deleted in Phase 0 step 0.3 because they only verified synthetic counters and handles.
 */
public class ShimTestRunner {

    public static void main(String[] args) {
        System.out.println("=== Java-level checks (not GPU, not in-game evidence) ===");
        int passed = 0;
        int failed = 0;
        String[] names = {
            "testHaltonJitter",
            "testConfigDefaults",
            "testTargetResolutionMath",
            "testVulkanCapabilityChecker",
            "testRtExperimentEngine",
            "testWorldTargetManager",
            "testHandDeferral",
            "testUpscalerJitterSource",
            "testUpscalerBackendsImplementedFlags",
            "testUpscalerSelection",
            "testDlss",
            "testDlssRr",
            "testFsr",
            "testPresetsToScale",
            "testConfigStubValuesFallBackToBilinear",
            "testLoaderDoesNotTreatDx12AsFsr",
            "testFeatureSetStructs",
            "testXessStructLayoutSizes",
            "testXessQualityParse",
            "testXessFeatureStructs",
            "testXessMissingDllIsReported",
            "testSodiumMeshDecoder",
            "testRtWorldLogic",
            "testNrdDenoiser",
            "testJitterPhaseCount",
            "testVelocityShaderCompiles",
            "testRtShaderSourcesCompile",
            "testMixinDisableCsv",
            "testRtxDeviceRequirements",
            "testLateProvidersAndScenarios",
            "testTargetLifecycle",
            "testUpscalerRuntimeSwitch",
            "testRtActivation",
            "testTextureLodBias",
            "testMotionVectorStability",
            "testSpatialUpscalers"
        };
        Runnable[] runs = {
            wrap(ShimTestRunner::testHaltonJitter),
            wrap(ShimTestRunner::testConfigDefaults),
            wrap(ShimTestRunner::testTargetResolutionMath),
            wrap(ShimTestRunner::testVulkanCapabilityChecker),
            wrap(ShimTestRunner::testRtExperimentEngine),
            wrap(ShimTestRunner::testWorldTargetManager),
            wrap(ShimTestRunner::testHandDeferral),
            wrap(ShimTestRunner::testUpscalerJitterSource),
            wrap(ShimTestRunner::testUpscalerBackendsImplementedFlags),
            wrap(io.github.andrexarlay.vulkancompat.rtx.config.UpscalerSelectionChecks::runAll),
            wrap(io.github.andrexarlay.vulkancompat.rtx.upscaler.dlss.DlssChecks::runAll),
            wrap(io.github.andrexarlay.vulkancompat.rtx.upscaler.dlss.rr.DlssRrChecks::runAll),
            wrap(io.github.andrexarlay.vulkancompat.rtx.upscaler.fsr.FsrChecks::runAll),
            wrap(ShimTestRunner::testPresetsToScale),
            wrap(ShimTestRunner::testConfigStubValuesFallBackToBilinear),
            wrap(ShimTestRunner::testLoaderDoesNotTreatDx12AsFsr),
            wrap(ShimTestRunner::testFeatureSetStructs),
            wrap(ShimTestRunner::testXessStructLayoutSizes),
            wrap(ShimTestRunner::testXessQualityParse),
            wrap(ShimTestRunner::testXessFeatureStructs),
            wrap(ShimTestRunner::testXessMissingDllIsReported),
            wrap(io.github.andrexarlay.vulkancompat.rt.sodium.SodiumMeshDecoderChecks::runAll),
            wrap(io.github.andrexarlay.vulkancompat.rt.world.RtWorldChecks::runAll),
            wrap(io.github.andrexarlay.vulkancompat.rt.denoise.NrdChecks::runAll),
            wrap(ShimTestRunner::testJitterPhaseCount),
            wrap(ShimTestRunner::testVelocityShaderCompiles),
            wrap(ShimTestRunner::testRtShaderSourcesCompile),
            wrap(ShimTestRunner::testMixinDisableCsv),
            wrap(ShimTestRunner::testRtxDeviceRequirements),
            wrap(ShimTestRunner::testLateProvidersAndScenarios),
            wrap(io.github.andrexarlay.vulkancompat.rtx.TargetLifecycleChecks::runAll),
            wrap(io.github.andrexarlay.vulkancompat.rtx.upscaler.UpscalerSwitchChecks::runAll),
            wrap(io.github.andrexarlay.vulkancompat.rtx.raytracing.RtActivationChecks::runAll),
            wrap(io.github.andrexarlay.vulkancompat.rtx.config.TextureLodBiasChecks::runAll),
            wrap(ShimTestRunner::testMotionVectorStability),
            wrap(io.github.andrexarlay.vulkancompat.rtx.upscaler.spatial.SpatialChecks::runAll)
        };
        if (names.length != runs.length) {
            throw new IllegalStateException("names.length (" + names.length + ") != runs.length (" + runs.length + ")");
        }
        for (int i = 0; i < names.length; i++) {
            try {
                runs[i].run();
                passed++;
                System.out.println("  [PASS] " + names[i]);
            } catch (Throwable t) {
                failed++;
                System.err.println("  [FAIL] " + names[i] + ": " + t.getMessage());
                t.printStackTrace();
            }
        }
        System.out.printf(" Results: %d passed, %d failed%n", passed, failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    private interface ThrowingRunnable { void run() throws Exception; }

    private static Runnable wrap(ThrowingRunnable r) {
        return () -> {
            try { r.run(); } catch (RuntimeException | Error e) { throw e; } catch (Exception e) { throw new RuntimeException(e); }
        };
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void assertEquals(Object expected, Object actual, String message) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError(message + " (expected: " + expected + ", actual: " + actual + ")");
        }
    }

    private static void assertEquals(float expected, float actual, float delta, String message) {
        if (Math.abs(expected - actual) > delta) {
            throw new AssertionError(message + " (expected: " + expected + ", actual: " + actual + ", delta: " + delta + ")");
        }
    }

    private static void assertEquals(long expected, long actual, String message) {
        if (expected != actual) {
            throw new AssertionError(message + " (expected: " + expected + ", actual: " + actual + ")");
        }
    }

    private static void assertEquals(int expected, int actual, String message) {
        if (expected != actual) {
            throw new AssertionError(message + " (expected: " + expected + ", actual: " + actual + ")");
        }
    }

    private static void testHaltonJitter() {
        HaltonJitter jitter = new HaltonJitter(16);
        assertEquals(16, jitter.getPhaseCount(), "Halton phase count");

        for (int i = 0; i < 32; i++) {
            jitter.next(1920, 1080);
            Vector2f subpixel = jitter.getCurrentJitter();
            Vector2f ndc = jitter.getCurrentNdcJitter();

            assertTrue(subpixel != null, "Subpixel jitter must not be null");
            assertTrue(ndc != null, "NDC jitter must not be null");
            assertTrue(subpixel.x >= -0.5f && subpixel.x <= 0.5f, "Jitter X within bounds [-0.5, 0.5]");
            assertTrue(subpixel.y >= -0.5f && subpixel.y <= 0.5f, "Jitter Y within bounds [-0.5, 0.5]");
            assertTrue(Math.abs(ndc.x) <= 2.0f / 1920.0f, "NDC jitter X properly scaled");
            assertTrue(Math.abs(ndc.y) <= 2.0f / 1080.0f, "NDC jitter Y properly scaled");
        }
    }
    private static void testConfigDefaults() {
        // Compile-time/default state of the config must be the safe one: no scaling, no upscaler.
        assertTrue(VulkanRtxConfig.UpscalerType.OFF.isImplemented(), "OFF is implemented");
        assertTrue(VulkanRtxConfig.UpscalerType.BILINEAR.isImplemented(), "BILINEAR is implemented");
        assertTrue(!VulkanRtxConfig.UpscalerType.DLSS_3_7.isImplemented(), "DLSS is a STUB");
        assertTrue(!VulkanRtxConfig.UpscalerType.FSR_3_1.isImplemented(), "FSR is a STUB");
        assertTrue(!VulkanRtxConfig.UpscalerType.XESS_1_3.isImplemented(), "XeSS is a STUB");

        // Verify discrete scaling presets (Block A)
        assertEquals(1.0f, VulkanRtxConfig.QualityPreset.NATIVE.getScaleFactor(), 1e-6f, "NATIVE scale");
        assertEquals(0.770f, VulkanRtxConfig.QualityPreset.QUALITY.getScaleFactor(), 1e-6f, "QUALITY scale");
        assertEquals(0.667f, VulkanRtxConfig.QualityPreset.BALANCED.getScaleFactor(), 1e-6f, "BALANCED scale");
        assertEquals(0.500f, VulkanRtxConfig.QualityPreset.PERFORMANCE.getScaleFactor(), 1e-6f, "PERFORMANCE scale");
        assertEquals(0.333f, VulkanRtxConfig.QualityPreset.ULTRA_PERFORMANCE.getScaleFactor(), 1e-6f, "ULTRA_PERFORMANCE scale");

        // Custom preset uses customScalePercent
        int oldCustom = VulkanRtxConfig.customScalePercent;
        try {
            VulkanRtxConfig.customScalePercent = 65;
            assertEquals(0.65f, VulkanRtxConfig.QualityPreset.CUSTOM.getScaleFactor(), 1e-6f, "CUSTOM scale 65%");
        } finally {
            VulkanRtxConfig.customScalePercent = oldCustom;
        }

        // Hardware ray tracing default (Block B)
        assertTrue(!VulkanRtxConfig.enableHardwareRayTracing, "Hardware ray tracing must default to false");
    }

    private static void testTargetResolutionMath() {
        VulkanRtxConfig.UpscalerType oldU = VulkanRtxConfig.upscalerType;
        VulkanRtxConfig.QualityPreset oldQ = VulkanRtxConfig.qualityPreset;
        try {
            VulkanRtxConfig.upscalerType = VulkanRtxConfig.UpscalerType.OFF;
            VulkanRtxConfig.qualityPreset = VulkanRtxConfig.QualityPreset.QUALITY;
            // CRASH PROTECTION: isScalingEnabled() must return false to avoid the scissor-bounds crash
            assertTrue(!VulkanRtxConfig.isScalingEnabled(), "OFF must not scale");
            assertEquals(2560, VulkanRtxManager.getTargetRenderWidth(2560), "OFF width");
            assertEquals(1440, VulkanRtxManager.getTargetRenderHeight(1440), "OFF height");

            VulkanRtxConfig.upscalerType = VulkanRtxConfig.UpscalerType.BILINEAR;
            VulkanRtxConfig.qualityPreset = VulkanRtxConfig.QualityPreset.NATIVE;
            assertTrue(!VulkanRtxConfig.isScalingEnabled(), "100% must not scale");
            assertEquals(2560, VulkanRtxManager.getTargetRenderWidth(2560), "100% width");

            VulkanRtxConfig.qualityPreset = VulkanRtxConfig.QualityPreset.QUALITY;
            // Safe mode: isScalingEnabled() is forced to false until 3D target is separated
            assertTrue(!VulkanRtxConfig.isScalingEnabled(), "isScalingEnabled must be false until separate 3D target (crash protection)");
            assertTrue(VulkanRtxConfig.isScalingConfigured(), "isScalingConfigured must be true when BILINEAR + QUALITY");
            assertEquals(2560, VulkanRtxManager.getTargetRenderWidth(2560), "Target width remains native to protect GUI");
            assertEquals(1440, VulkanRtxManager.getTargetRenderHeight(1440), "Target height remains native to protect GUI");

            // Math verification via calculateScaledWidth/Height
            assertEquals(1972, VulkanRtxManager.calculateScaledWidth(2560, VulkanRtxConfig.QualityPreset.QUALITY.getScaleFactor()), "77% width");
            assertEquals(1110, VulkanRtxManager.calculateScaledHeight(1440, VulkanRtxConfig.QualityPreset.QUALITY.getScaleFactor()), "77% height");

            assertEquals(1708, VulkanRtxManager.calculateScaledWidth(2560, VulkanRtxConfig.QualityPreset.BALANCED.getScaleFactor()), "66.7% width");
            assertEquals(960, VulkanRtxManager.calculateScaledHeight(1440, VulkanRtxConfig.QualityPreset.BALANCED.getScaleFactor()), "66.7% height");

            assertEquals(1280, VulkanRtxManager.calculateScaledWidth(2560, VulkanRtxConfig.QualityPreset.PERFORMANCE.getScaleFactor()), "50% width");
            assertEquals(720, VulkanRtxManager.calculateScaledHeight(1440, VulkanRtxConfig.QualityPreset.PERFORMANCE.getScaleFactor()), "50% height");

            assertEquals(852, VulkanRtxManager.calculateScaledWidth(2560, VulkanRtxConfig.QualityPreset.ULTRA_PERFORMANCE.getScaleFactor()), "33.3% width");
            assertEquals(480, VulkanRtxManager.calculateScaledHeight(1440, VulkanRtxConfig.QualityPreset.ULTRA_PERFORMANCE.getScaleFactor()), "33.3% height");
        } finally {
            VulkanRtxConfig.upscalerType = oldU;
            VulkanRtxConfig.qualityPreset = oldQ;
        }
    }

    private static void testVulkanCapabilityChecker() {
        io.github.andrexarlay.vulkancompat.rtx.raytracing.VulkanCapabilityChecker.reset();

        // 1. Verify required extensions list
        assertTrue(io.github.andrexarlay.vulkancompat.rtx.raytracing.VulkanCapabilityChecker.REQUIRED_RT_EXTENSIONS.contains(
            io.github.andrexarlay.vulkancompat.rtx.raytracing.VulkanCapabilityChecker.EXT_ACCELERATION_STRUCTURE), "Requires acceleration structure");
        assertTrue(io.github.andrexarlay.vulkancompat.rtx.raytracing.VulkanCapabilityChecker.REQUIRED_RT_EXTENSIONS.contains(
            io.github.andrexarlay.vulkancompat.rtx.raytracing.VulkanCapabilityChecker.EXT_RAY_QUERY), "Requires ray query");
        assertTrue(io.github.andrexarlay.vulkancompat.rtx.raytracing.VulkanCapabilityChecker.REQUIRED_RT_EXTENSIONS.contains(
            io.github.andrexarlay.vulkancompat.rtx.raytracing.VulkanCapabilityChecker.EXT_BUFFER_DEVICE_ADDRESS), "Requires buffer device address");
        assertTrue(io.github.andrexarlay.vulkancompat.rtx.raytracing.VulkanCapabilityChecker.REQUIRED_RT_EXTENSIONS.contains(
            io.github.andrexarlay.vulkancompat.rtx.raytracing.VulkanCapabilityChecker.EXT_DEFERRED_HOST_OPERATIONS), "Requires deferred host operations");

        // 2. Test missing extensions scenario
        io.github.andrexarlay.vulkanbridge.core.VulkanBackendDetector.setVulkanActive(true);
        io.github.andrexarlay.vulkancompat.rtx.raytracing.VulkanCapabilityChecker.setCachedDeviceExtensionsForTesting(
            java.util.Set.of("VK_KHR_swapchain", "VK_KHR_acceleration_structure")
        );
        assertTrue(!io.github.andrexarlay.vulkancompat.rtx.raytracing.VulkanCapabilityChecker.isHardwareRayTracingSupported(), "Must not support RT when ray query is missing");
        java.util.Set<String> missing = io.github.andrexarlay.vulkancompat.rtx.raytracing.VulkanCapabilityChecker.getMissingExtensions();
        assertTrue(missing.contains("VK_KHR_ray_query"), "Missing set must contain ray query");
        assertTrue(!io.github.andrexarlay.vulkancompat.rtx.raytracing.VulkanCapabilityChecker.hasExtension("VK_KHR_ray_query"), "hasExtension must return false for missing extension");

        // 3. Test all extensions present scenario
        io.github.andrexarlay.vulkancompat.rtx.raytracing.VulkanCapabilityChecker.setCachedDeviceExtensionsForTesting(java.util.Set.of(
            "VK_KHR_acceleration_structure",
            "VK_KHR_ray_query",
            "VK_KHR_buffer_device_address",
            "VK_KHR_deferred_host_operations"
        ));
        assertTrue(io.github.andrexarlay.vulkancompat.rtx.raytracing.VulkanCapabilityChecker.isHardwareRayTracingSupported(), "Must support RT when all 4 extensions present");
        assertTrue(io.github.andrexarlay.vulkancompat.rtx.raytracing.VulkanCapabilityChecker.getMissingExtensions().isEmpty(), "No missing extensions");

        // 4. Test explicit override
        io.github.andrexarlay.vulkancompat.rtx.raytracing.VulkanCapabilityChecker.setOverrideForTesting(false);
        assertTrue(!io.github.andrexarlay.vulkancompat.rtx.raytracing.VulkanCapabilityChecker.isHardwareRayTracingSupported(), "Override false must disable support");
        io.github.andrexarlay.vulkancompat.rtx.raytracing.VulkanCapabilityChecker.setOverrideForTesting(true);
        assertTrue(io.github.andrexarlay.vulkancompat.rtx.raytracing.VulkanCapabilityChecker.isHardwareRayTracingSupported(), "Override true must enable support");

        // 5. Opacity micromap probe: extension gate is headless-testable; device feature path must not throw without a device
        io.github.andrexarlay.vulkancompat.rtx.raytracing.VulkanCapabilityChecker.reset();
        io.github.andrexarlay.vulkanbridge.core.VulkanBackendDetector.setVulkanActive(true);
        io.github.andrexarlay.vulkancompat.rtx.raytracing.VulkanCapabilityChecker.setCachedDeviceExtensionsForTesting(java.util.Set.of(
            "VK_KHR_acceleration_structure", "VK_KHR_ray_query", "VK_KHR_buffer_device_address", "VK_KHR_deferred_host_operations"
        ));
        assertTrue(!io.github.andrexarlay.vulkancompat.rtx.raytracing.VulkanCapabilityChecker.isOpacityMicromapSupported(), "OMM probe must be false when the extension is absent");
        io.github.andrexarlay.vulkancompat.rtx.raytracing.VulkanCapabilityChecker.setCachedDeviceExtensionsForTesting(java.util.Set.of(
            "VK_KHR_acceleration_structure", "VK_KHR_ray_query", "VK_KHR_buffer_device_address", "VK_KHR_deferred_host_operations",
            io.github.andrexarlay.vulkancompat.rtx.raytracing.VulkanCapabilityChecker.EXT_OPACITY_MICROMAP
        ));
        assertTrue(!io.github.andrexarlay.vulkancompat.rtx.raytracing.VulkanCapabilityChecker.isOpacityMicromapSupported(), "OMM probe must be false headless (no physical device resolvable)");
        io.github.andrexarlay.vulkancompat.rtx.raytracing.VulkanCapabilityChecker.setOverrideOpacityMicromapForTesting(true);
        assertTrue(io.github.andrexarlay.vulkancompat.rtx.raytracing.VulkanCapabilityChecker.isOpacityMicromapSupported(), "OMM override true must win");
        io.github.andrexarlay.vulkancompat.rtx.raytracing.VulkanCapabilityChecker.setOverrideOpacityMicromapForTesting(false);
        assertTrue(!io.github.andrexarlay.vulkancompat.rtx.raytracing.VulkanCapabilityChecker.isOpacityMicromapSupported(), "OMM override false must win");
        io.github.andrexarlay.vulkancompat.rtx.raytracing.VulkanCapabilityChecker.setOverrideOpacityMicromapForTesting(null);

        io.github.andrexarlay.vulkancompat.rtx.raytracing.VulkanCapabilityChecker.reset();
    }

    private static void testRtExperimentEngine() {
        io.github.andrexarlay.vulkancompat.rtx.raytracing.DefaultRtExperimentEngine engine =
            io.github.andrexarlay.vulkancompat.rtx.raytracing.DefaultRtExperimentEngine.getInstance();
        assertTrue(engine != null, "DefaultRtExperimentEngine instance must exist");
        assertTrue(io.github.andrexarlay.vulkancompat.rtx.raytracing.VulkanRtExperimentEngine.getInstance() != null, "VulkanRtExperimentEngine instance must exist");
        assertTrue(engine == io.github.andrexarlay.vulkancompat.rtx.raytracing.VulkanRtExperimentEngine.getInstance(), "Must be same singleton instance");

        // 1. By default, isEnabled() is false
        VulkanRtxConfig.enableHardwareRayTracing = false;
        io.github.andrexarlay.vulkancompat.rtx.raytracing.VulkanCapabilityChecker.setOverrideForTesting(false);
        assertTrue(!engine.isEnabled(), "Engine must be disabled when config flag is false");

        // 2. Safe no-ops when disabled
        engine.init();
        assertTrue(!engine.isInitialized(), "init must no-op when disabled");
        engine.buildTestStructure();
        engine.dispatchRayQuery();
        engine.dispatchRayQuery((com.mojang.blaze3d.pipeline.RenderTarget) null);
        engine.cleanup();
        assertTrue(!engine.isInitialized(), "cleanup must no-op when not initialized");

        // 3. When enabled via config and capability
        try {
            io.github.andrexarlay.vulkancompat.rtx.raytracing.VulkanCapabilityChecker.setOverrideForTesting(true);
            VulkanRtxConfig.enableHardwareRayTracing = true;
            assertTrue(engine.isEnabled(), "Engine must be enabled when config=true and capability=true");

            engine.init();
            assertTrue(engine.isInitialized(), "Engine must initialize when enabled");
            engine.buildTestStructure();
            engine.dispatchRayQuery();
            engine.dispatchRayQuery((com.mojang.blaze3d.pipeline.RenderTarget) null);
            engine.cleanup();
            assertTrue(!engine.isInitialized(), "Engine must be reset after cleanup");
        } finally {
            VulkanRtxConfig.enableHardwareRayTracing = false;
            io.github.andrexarlay.vulkancompat.rtx.raytracing.VulkanCapabilityChecker.reset();
        }
    }

    private static void testWorldTargetManager() throws Exception {
        VulkanRtxConfig.UpscalerType oldU = VulkanRtxConfig.upscalerType;
        VulkanRtxConfig.QualityPreset oldQ = VulkanRtxConfig.qualityPreset;
        boolean oldRt = VulkanRtxConfig.enableHardwareRayTracing;
        try {
            io.github.andrexarlay.vulkanbridge.core.VulkanBackendDetector.setVulkanActive(true);
            VulkanRtxConfig.enableHardwareRayTracing = false;
            VulkanRtxConfig.upscalerType = VulkanRtxConfig.UpscalerType.OFF;
            VulkanRtxConfig.qualityPreset = VulkanRtxConfig.QualityPreset.QUALITY;
            assertTrue(!WorldTargetManager.isScalingActive(), "OFF must not be scaling active");
            assertTrue(!WorldTargetManager.isWorldPhaseActive(), "OFF must not be world phase active");

            VulkanRtxConfig.upscalerType = VulkanRtxConfig.UpscalerType.BILINEAR;
            VulkanRtxConfig.qualityPreset = VulkanRtxConfig.QualityPreset.NATIVE;
            assertTrue(!WorldTargetManager.isScalingActive(), "100% must not be scaling active");

            VulkanRtxConfig.qualityPreset = VulkanRtxConfig.QualityPreset.BALANCED;
            assertTrue(WorldTargetManager.isScalingActive(), "BILINEAR + BALANCED must be scaling active");
            assertTrue(WorldTargetManager.isWorldPhaseActive(), "BILINEAR + BALANCED must be world phase active");
            assertEquals(1708, WorldTargetManager.getTargetWidth(2560), "BALANCED width 66.7%");
            assertEquals(960, WorldTargetManager.getTargetHeight(1440), "BALANCED height 66.7%");

            // A RUNNING Hardware RT (gate on) activates world phase isolation even at 100% Native resolution. The config flag
            // alone does not: the gate is decided per frame (RtActivation) and stays off while RT is blocked or failed.
            VulkanRtxConfig.upscalerType = VulkanRtxConfig.UpscalerType.OFF;
            VulkanRtxConfig.qualityPreset = VulkanRtxConfig.QualityPreset.NATIVE;
            VulkanRtxConfig.enableHardwareRayTracing = true;
            assertTrue(!WorldTargetManager.isScalingActive(), "RT config on but gate off (blocked/failed) must not isolate");
            io.github.andrexarlay.vulkancompat.rt.sodium.RtGeometryGate.setForTests(true);
            assertTrue(WorldTargetManager.isScalingActive(), "Hardware RT (gate on) must activate scaling/isolation");
            assertTrue(WorldTargetManager.isWorldPhaseActive(), "Hardware RT (gate on) must activate world phase active");
            io.github.andrexarlay.vulkancompat.rt.sodium.RtGeometryGate.setForTests(false);
            VulkanRtxConfig.enableHardwareRayTracing = false;

            assertTrue(!WorldTargetManager.isInWorldPhase(), "Initially not in world phase");

            // Verify GameRendererTargetAccessor interface methods
            Class<?> accessorClass = Class.forName("io.github.andrexarlay.vulkancompat.rtx.GameRendererTargetAccessor");
            assertTrue(accessorClass != null, "GameRendererTargetAccessor must exist");
            assertTrue(accessorClass.getMethod("vkb$getMainRenderTarget") != null, "vkb$getMainRenderTarget exists");
            assertTrue(accessorClass.getMethod("vkb$setMainRenderTarget", com.mojang.blaze3d.pipeline.RenderTarget.class) != null, "vkb$setMainRenderTarget exists");
            assertTrue(accessorClass.getMethod("vkb$getHud3DTarget") != null, "vkb$getHud3DTarget exists");
            assertTrue(accessorClass.getMethod("vkb$setHud3DTarget", com.mojang.blaze3d.pipeline.RenderTarget.class) != null, "vkb$setHud3DTarget exists");
        } finally {
            VulkanRtxConfig.upscalerType = oldU;
            VulkanRtxConfig.qualityPreset = oldQ;
            VulkanRtxConfig.enableHardwareRayTracing = oldRt;
            io.github.andrexarlay.vulkanbridge.core.VulkanBackendDetector.reset();
        }
    }

    /**
     * The first-person hand is rendered without motion vectors and with its own depth buffer, so it must be pulled out of
     * the world phase exactly when a temporal upscaler is active, then replayed at full resolution after the upscale.
     */
    private static void testHandDeferral() throws Exception {
        assertTrue(WorldTargetManager.handMustBeDeferred(true, true), "hand must be deferred inside the world phase with a temporal upscaler");
        assertTrue(!WorldTargetManager.handMustBeDeferred(true, false), "hand must stay in the world phase without a temporal upscaler");
        assertTrue(!WorldTargetManager.handMustBeDeferred(false, true), "hand must never be deferred outside the world phase");
        assertTrue(!WorldTargetManager.handMustBeDeferred(false, false), "hand must never be deferred outside the world phase");
        assertTrue(!WorldTargetManager.shouldDeferHand(), "default state must not defer the hand");

        Class<?> accessor = Class.forName("io.github.andrexarlay.vulkancompat.rtx.GameRendererHandAccessor");
        assertTrue(accessor.getMethod("vkb$replayDeferredHand") != null, "vkb$replayDeferredHand exists");

        Class<?> mixin = Class.forName("io.github.andrexarlay.vulkancompat.mixin.rt.MixinGameRendererHand");
        assertTrue(accessor.isAssignableFrom(mixin), "MixinGameRendererHand must implement the replay accessor");
        assertTrue(mixin.getDeclaredMethod("vkb$invokeRenderItemInHand",
            net.minecraft.client.renderer.state.level.CameraRenderState.class,
            net.minecraft.client.renderer.state.level.PlayerRenderState.class,
            com.mojang.renderpearl.api.textures.GpuTextureView.class) != null,
            "the mixin must invoke the original renderItemInHand for the replay");
    }

    /**
     * DLSS-SR must not apply the camera jitter to colour that a same-resolution denoiser has already resolved: the
     * jitter would be applied twice and the whole frame would crawl.
     */
    private static void testUpscalerJitterSource() throws Exception {
        assertEquals(0.3f, WorldTargetManager.upscalerJitterComponent(false, 0.3f), 1e-6f,
            "unresolved colour keeps the camera jitter for DLSS-SR");
        assertEquals(0.0f, WorldTargetManager.upscalerJitterComponent(true, 0.3f), 1e-6f,
            "denoiser-resolved colour reaches DLSS-SR with zero jitter");

        io.github.andrexarlay.vulkancompat.rt.world.RtFrameRenderer.resetFrameColorTemporallyResolved();
        assertTrue(!io.github.andrexarlay.vulkancompat.rt.world.RtFrameRenderer.frameColorTemporallyResolved(),
            "a fresh frame starts unresolved");

        String world = readSource("src/main/java/io/github/andrexarlay/vulkancompat/rtx/WorldTargetManager.java");
        assertTrue(world.contains("resetFrameColorTemporallyResolved();"),
            "beginWorldPhase must clear the resolved-colour flag every frame");
        assertTrue(world.contains("upscalerJitterComponent(colorResolved,"),
            "recordXess must route the SR jitter through the resolved-colour rule");
        assertTrue(!world.contains("srJitterX = CameraJitterManager.getCurrentPixelJitterX()"),
            "recordXess must not bypass the rule with the raw camera jitter");

        String renderer = readSource("src/main/java/io/github/andrexarlay/vulkancompat/rt/world/RtFrameRenderer.java");
        int marks = 0;
        for (int i = renderer.indexOf("frameColorTemporallyResolved = true;"); i >= 0;
             i = renderer.indexOf("frameColorTemporallyResolved = true;", i + 1)) {
            marks++;
        }
        assertTrue(marks >= 2, "both the NRD and the DLSS-RR record paths must mark the colour resolved (found " + marks + ")");
    }

    /** Reads a source file from either the repository root or the common module, whichever the working directory is. */
    private static String readSource(String repoRelative) throws Exception {
        java.nio.file.Path relative = java.nio.file.Path.of(repoRelative);
        java.nio.file.Path path = java.nio.file.Files.isRegularFile(relative)
            ? relative : java.nio.file.Path.of("common").resolve(relative);
        return java.nio.file.Files.readString(path, java.nio.charset.StandardCharsets.UTF_8);
    }

    /** BILINEAR and XeSS are implemented; DLSS/FSR need their Core-resolved libraries and must not be reported as ready without them. */
    private static void testUpscalerBackendsImplementedFlags() {
        assertTrue(UpscalerBackend.BILINEAR.isImplemented(), "BILINEAR is implemented");
        // DLSS has real code, but "implemented for use" also needs the libraries and an NVIDIA GPU: not the case outside the game
        assertTrue(!UpscalerBackend.DLSS.isImplemented(), "DLSS without libraries from Bedray Core is not usable (see DlssChecks for the positive case)");
        assertTrue(!UpscalerBackend.FSR.isImplemented(), "FSR without the official library resolved by Bedray Core is not usable (see FsrChecks for the positive case)");
        assertTrue(UpscalerBackend.XESS.isImplemented(), "XeSS has a real dispatch (xess=dispatch ok in latest.log)");
        assertTrue(UpscalerLibraryLoader.isBackendAvailable(UpscalerBackend.BILINEAR), "BILINEAR is built in");
    }

    /** Presets map to the documented scale factors, and the derived upscaler type follows the scale. */
    private static void testPresetsToScale() {
        ConfigSnapshot snap = new ConfigSnapshot();
        try {
            VulkanRtxConfig.QualityPreset[] presets = {
                VulkanRtxConfig.QualityPreset.ULTRA_PERFORMANCE,
                VulkanRtxConfig.QualityPreset.PERFORMANCE,
                VulkanRtxConfig.QualityPreset.BALANCED,
                VulkanRtxConfig.QualityPreset.QUALITY,
                VulkanRtxConfig.QualityPreset.NATIVE
            };
            float[] expected = {0.333f, 0.500f, 0.667f, 0.770f, 1.000f};
            for (int i = 0; i < presets.length; i++) {
                VulkanRtxConfig.qualityPreset = presets[i];
                assertEquals(expected[i], VulkanRtxConfig.getEffectiveScaleFactor(), 1e-6f, "scale of " + presets[i]);
                VulkanRtxConfig.syncUpscalerTypeWithPreset();
                VulkanRtxConfig.UpscalerType wanted = expected[i] < 1.0f
                    ? VulkanRtxConfig.UpscalerType.BILINEAR : VulkanRtxConfig.UpscalerType.OFF;
                assertEquals(wanted, VulkanRtxConfig.upscalerType, "derived upscaler type of " + presets[i]);
            }
            VulkanRtxConfig.qualityPreset = VulkanRtxConfig.QualityPreset.CUSTOM;
            VulkanRtxConfig.customScalePercent = 40;
            assertEquals(0.40f, VulkanRtxConfig.getEffectiveScaleFactor(), 1e-6f, "CUSTOM 40%");
            VulkanRtxConfig.syncUpscalerTypeWithPreset();
            assertEquals(VulkanRtxConfig.UpscalerType.BILINEAR, VulkanRtxConfig.upscalerType, "CUSTOM 40% scales");
            VulkanRtxConfig.customScalePercent = 100;
            VulkanRtxConfig.syncUpscalerTypeWithPreset();
            assertEquals(VulkanRtxConfig.UpscalerType.OFF, VulkanRtxConfig.upscalerType, "CUSTOM 100% does not scale");
        } finally {
            snap.restore();
        }
    }

    /** A toml with STUB values must load as BILINEAR; STUB keys are not written back. */
    private static void testConfigStubValuesFallBackToBilinear() throws Exception {
        ConfigSnapshot snap = new ConfigSnapshot();
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("vkb_cfg_test");
        java.io.File cfg = dir.resolve("bedray.toml").toFile();
        try {
            VulkanRtxConfig.setConfigFileForTesting(cfg);
            for (String stub : new String[] {"DLSS", "FSR", "dlss", "nonsense"}) {
                VulkanRtxConfig.activeBackend = UpscalerBackend.BILINEAR;
                VulkanRtxConfig.applyKeyValue("active_backend", stub);
                assertEquals(UpscalerBackend.BILINEAR, VulkanRtxConfig.activeBackend, "active_backend=" + stub + " must become BILINEAR");
            }
            VulkanRtxConfig.applyKeyValue("dlss_frame_gen_multiplier", "X4");
            VulkanRtxConfig.applyKeyValue("enable_dlss_frame_gen", "true");
            VulkanRtxConfig.applyKeyValue("enable_dlss_ray_reconstruction", "true");
            assertEquals(VulkanRtxConfig.FrameGenMultiplier.OFF, VulkanRtxConfig.dlssFrameGenMultiplier, "FG multiplier stays OFF");
            assertTrue(!VulkanRtxConfig.enableDlssFrameGen, "DLSS-FG stays false");
            assertTrue(!VulkanRtxConfig.enableDlssRayReconstruction, "DLSS-RR stays false");

            // Full file round trip with a legacy config that contains STUB values
            java.nio.file.Files.writeString(cfg.toPath(),
                "[super_resolution]\n"
                + "active_backend = \"FSR\"\n"
                + "upscaler_type = \"FSR_3_1\"\n"
                + "quality_preset = \"BALANCED\"\n"
                + "custom_scale = 70\n"
                + "dlss_frame_gen_multiplier = \"X2\"\n"
                + "enable_dlss_frame_gen = true\n"
                + "enable_dlss_ray_reconstruction = true\n"
                + "[ray_tracing]\n"
                + "enable_hardware_ray_tracing = false\n",
                java.nio.charset.StandardCharsets.UTF_8);
            VulkanRtxConfig.load();
            assertEquals(UpscalerBackend.BILINEAR, VulkanRtxConfig.activeBackend, "FSR without a usable official library must load as BILINEAR");
            assertEquals(VulkanRtxConfig.QualityPreset.BALANCED, VulkanRtxConfig.qualityPreset, "preset loaded");
            assertEquals(VulkanRtxConfig.UpscalerType.BILINEAR, VulkanRtxConfig.upscalerType, "scaling preset derives BILINEAR");
            assertTrue(!VulkanRtxConfig.enableDlssFrameGen && !VulkanRtxConfig.enableDlssRayReconstruction, "STUB flags ignored");

            VulkanRtxConfig.save();
            String written = java.nio.file.Files.readString(cfg.toPath(), java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(!written.contains("enable_dlss_frame_gen") && !written.contains("dlss_frame_gen_multiplier")
                && !written.contains("ray_reconstruction") && !written.contains("upscaler_type"),
                "decorative STUB keys must not be written");
            assertTrue(written.contains("active_backend = \"BILINEAR\""), "BILINEAR written");
            assertTrue(!written.startsWith("﻿"), "no BOM");
        } finally {
            snap.restore();
            VulkanRtxConfig.setConfigFileForTesting(new java.io.File("config/bedray.toml"));
            try {
                java.nio.file.Files.deleteIfExists(cfg.toPath());
                java.nio.file.Files.deleteIfExists(dir);
            } catch (java.io.IOException e) {
                System.err.println("temp cleanup failed: " + e.getMessage());
            }
        }
    }

    /** FSR counts only for the Vulkan build of FidelityFX; DX12 DLLs must not make it "available". */
    private static void testLoaderDoesNotTreatDx12AsFsr() throws Exception {
        assertTrue(!UpscalerLibraryLoader.isFsrVulkanLibraryName("amd_fidelityfx_upscaler_dx12.dll"), "dx12 upscaler is not FSR-Vulkan");
        assertTrue(!UpscalerLibraryLoader.isFsrVulkanLibraryName("amd_fidelityfx_upscaler_dx12_v4.0.2.dll"), "dx12 versioned is not FSR-Vulkan");
        assertTrue(!UpscalerLibraryLoader.isFsrVulkanLibraryName("amd_fidelityfx_framegeneration_dx12.dll"), "dx12 framegen is not FSR-Vulkan");
        assertTrue(!UpscalerLibraryLoader.isFsrVulkanLibraryName("amd_fidelityfx_upscaler.dll"), "unknown API build is not accepted");
        assertTrue(!UpscalerLibraryLoader.isFsrVulkanLibraryName("nvngx_dlss.dll"), "other vendor DLL");
        assertTrue(UpscalerLibraryLoader.isFsrVulkanLibraryName("amd_fidelityfx_vk.dll"), "amd_fidelityfx_vk.dll is FSR-Vulkan");
        assertTrue(UpscalerLibraryLoader.isFsrVulkanLibraryName("AMD_FidelityFX_VK.DLL"), "case-insensitive");
        assertTrue(UpscalerLibraryLoader.isFsrVulkanLibraryName("ffx_fsr_vk.dll"), "ffx_*vk*.dll is FSR-Vulkan");

        java.nio.file.Path tempDir = java.nio.file.Files.createTempDirectory("vulkan_test_natives");
        try {
            UpscalerLibraryLoader.setCustomNativesDirectoryForTesting(tempDir);
            assertTrue(!UpscalerLibraryLoader.isBackendAvailable(UpscalerBackend.FSR), "FSR unavailable in empty folder");

            java.nio.file.Files.createFile(tempDir.resolve("amd_fidelityfx_upscaler_dx12.dll"));
            java.nio.file.Files.createFile(tempDir.resolve("amd_fidelityfx_framegeneration_dx12.dll"));
            UpscalerLibraryLoader.scanAvailableBackends();
            assertTrue(!UpscalerLibraryLoader.isBackendAvailable(UpscalerBackend.FSR), "DX12 DLLs must not make FSR available");

            java.nio.file.Files.createFile(tempDir.resolve("amd_fidelityfx_vk.dll"));
            UpscalerLibraryLoader.scanAvailableBackends();
            assertTrue(UpscalerLibraryLoader.isBackendAvailable(UpscalerBackend.FSR), "Vulkan FidelityFX DLL name is detected (presence only; usability is FsrLibrary: Core resolve + official SHA-256)");

            java.nio.file.Files.createFile(tempDir.resolve("libxess.dll"));
            UpscalerLibraryLoader.scanAvailableBackends();
            assertTrue(UpscalerLibraryLoader.isBackendAvailable(UpscalerBackend.XESS), "libxess.dll detected (presence only)");

            // DLSS libraries are resolved only by Bedray Core's NativeLibraries: a stray nvngx_dlss.dll in a scanned folder is not enough
            java.nio.file.Files.createFile(tempDir.resolve("nvngx_dlss.dll"));
            UpscalerLibraryLoader.scanAvailableBackends();
            assertTrue(!UpscalerLibraryLoader.isBackendAvailable(UpscalerBackend.DLSS), "DLSS needs Bedray Core-resolved libraries (nvngx_dlss.dll + vkb_dlss.dll)");
        } finally {
            io.github.andrexarlay.vulkancompat.rtx.raytracing.VulkanCapabilityChecker.setOverrideNvidiaForTesting(null);
            try (java.util.stream.Stream<java.nio.file.Path> walk = java.nio.file.Files.walk(tempDir)) {
                walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                    try {
                        java.nio.file.Files.deleteIfExists(p);
                    } catch (java.io.IOException e) {
                        System.err.println("temp cleanup failed: " + e.getMessage());
                    }
                });
            }
            UpscalerLibraryLoader.resetForTesting();
        }
    }

    /** Saves and restores the static config fields touched by the tests above. */
    private static final class ConfigSnapshot {
        private final VulkanRtxConfig.QualityPreset preset = VulkanRtxConfig.qualityPreset;
        private final int custom = VulkanRtxConfig.customScalePercent;
        private final VulkanRtxConfig.UpscalerType type = VulkanRtxConfig.upscalerType;
        private final UpscalerBackend backend = VulkanRtxConfig.activeBackend;

        void restore() {
            VulkanRtxConfig.qualityPreset = preset;
            VulkanRtxConfig.customScalePercent = custom;
            VulkanRtxConfig.upscalerType = type;
            VulkanRtxConfig.activeBackend = backend;
        }
    }

    // ---- Intel XeSS (X0/X1): Java-level checks only (no DLL, no GPU) ----

    private static void testXessStructLayoutSizes() {
        // Sizes computed from xess.h / xess_vk.h (pack 8); the class initializer also throws on a mismatch.
        XessLayouts.verify();
        assertEquals(8L, XessLayouts.XESS_2D.byteSize(), "xess_2d_t");
        assertEquals(8L, XessLayouts.XESS_VERSION.byteSize(), "xess_version_t");
        assertEquals(24L, XessLayouts.XESS_PROPERTIES.byteSize(), "xess_properties_t");
        assertEquals(48L, XessLayouts.XESS_VK_IMAGE_VIEW_INFO.byteSize(), "xess_vk_image_view_info");
        assertEquals(64L, XessLayouts.XESS_VK_INIT_PARAMS.byteSize(), "xess_vk_init_params_t");
        assertEquals(360L, XessLayouts.XESS_VK_EXECUTE_PARAMS.byteSize(), "xess_vk_execute_params_t");
        assertEquals(8L, XessLayouts.INIT_OFF_QUALITY, "init qualitySetting offset");
        assertEquals(12L, XessLayouts.INIT_OFF_FLAGS, "init initFlags offset");
        assertEquals(56L, XessLayouts.INIT_OFF_PIPELINE_CACHE, "init pipelineCache offset");
    }

    private static void testXessQualityParse() {
        assertEquals(100, XessQuality.ULTRA_PERFORMANCE.nativeValue(), "ULTRA_PERFORMANCE");
        assertEquals(103, XessQuality.QUALITY.nativeValue(), "QUALITY");
        assertEquals(105, XessQuality.ULTRA_QUALITY_PLUS.nativeValue(), "ULTRA_QUALITY_PLUS");
        assertEquals(106, XessQuality.AA.nativeValue(), "AA");
        assertTrue(XessQuality.parse("ultra-quality") == XessQuality.ULTRA_QUALITY, "case/dash tolerant parse");
        assertTrue(XessQuality.parse(" Balanced ") == XessQuality.BALANCED, "trim");
        boolean threw = false;
        try {
            XessQuality.parse("ULTRA_MEGA");
        } catch (IllegalArgumentException expected) {
            threw = true;
        }
        assertTrue(threw, "unknown mode must throw, not map to a default");
    }

    private static void testXessFeatureStructs() {
        // The exact struct/field pairs used by MixinVulkanFeatureSets for the XeSS sets must be accepted by the engine's VulkanFeature.
        VulkanPNextStruct mutable = new VulkanPNextStruct(org.lwjgl.vulkan.VkPhysicalDeviceMutableDescriptorTypeFeaturesEXT.class);
        VulkanPNextStruct dot = new VulkanPNextStruct(org.lwjgl.vulkan.VkPhysicalDeviceShaderIntegerDotProductFeatures.class);
        assertTrue(new VulkanFeature(mutable, "mutableDescriptorType") != null, "mutableDescriptorType");
        assertTrue(new VulkanFeature(dot, "shaderIntegerDotProduct") != null, "shaderIntegerDotProduct");
        assertTrue(new VulkanFeature(VulkanFeatureSets.VK10_FEATURES_STRUCT, "shaderStorageImageWriteWithoutFormat") != null,
            "shaderStorageImageWriteWithoutFormat");
        assertTrue(new VulkanFeature(VulkanFeatureSets.VK10_FEATURES_STRUCT, "shaderStorageImageExtendedFormats") != null,
            "shaderStorageImageExtendedFormats");
        assertTrue(new VulkanFeature(VulkanFeatureSets.VK12_FEATURES_STRUCT, "shaderInt8") != null, "shaderInt8");
        assertTrue(new VulkanFeature(VulkanFeatureSets.VK12_FEATURES_STRUCT, "scalarBlockLayout") != null, "scalarBlockLayout");
    }

    private static void testXessMissingDllIsReported() throws Exception {
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("vkb-xess-test");
        try {
            assertTrue(!XessConfig.libraryExists(dir), "no DLL in an empty game dir");
            assertTrue(XessConfig.libraryPath(dir).endsWith(java.nio.file.Path.of("bedray", "natives", "libxess.dll")),
                "explicit path <gameDir>/bedray/natives/libxess.dll");
            boolean threw = false;
            try {
                XessNative.load(XessConfig.libraryPath(dir));
            } catch (XessNativeException expected) {
                threw = expected.getMessage().contains("not found");
            }
            assertTrue(threw, "missing DLL must raise XessNativeException(not found), not be ignored");
            // a file that is not a DLL: loading must fail with the cause attached (no crash, no silent success)
            java.nio.file.Path fake = dir.resolve("libxess.dll");
            java.nio.file.Files.writeString(fake, "not a dll");
            Throwable cause = null;
            try {
                XessNative.load(fake);
            } catch (XessNativeException expected) {
                cause = expected.getCause();
            }
            assertTrue(cause != null, "invalid DLL must raise XessNativeException with a cause");
        } finally {
            java.nio.file.Files.deleteIfExists(dir.resolve("libxess.dll"));
            java.nio.file.Files.deleteIfExists(dir);
        }
    }

    private static void testMixinDisableCsv() {
        java.util.Set<String> p = io.github.andrexarlay.vulkancompat.mixin.VulkanRtxMixinPlugin.parseDisabledMixins(
            " MixinSodiumGlContextInfo , sodium.MixinSodiumGLDrawBatch.class,,x.y.MixinOptions ");
        assertEquals(3, p.size(), "csv entries");
        assertTrue(p.contains("MixinSodiumGlContextInfo") && p.contains("MixinSodiumGLDrawBatch") && p.contains("MixinOptions"), "names normalised: " + p);
        assertTrue(io.github.andrexarlay.vulkancompat.mixin.VulkanRtxMixinPlugin.parseDisabledMixins(null).isEmpty(), "null -> empty");
        assertTrue(io.github.andrexarlay.vulkancompat.mixin.VulkanRtxMixinPlugin.parseDisabledMixins(" , ").isEmpty(), "blank -> empty");
    }

    private static void testJitterPhaseCount() {
        // XeSS guide: Halton length >= 8 * scale^2, never below 16 (ULTRA_QUALITY 1.5 -> 18, ULTRA_PERFORMANCE 3.0 -> 72)
        assertEquals(16, io.github.andrexarlay.vulkancompat.rtx.upscaler.CameraJitterManager.phaseCountFor(1.0), "AA");
        assertEquals(16, io.github.andrexarlay.vulkancompat.rtx.upscaler.CameraJitterManager.phaseCountFor(1.3), "UQ+");
        assertEquals(18, io.github.andrexarlay.vulkancompat.rtx.upscaler.CameraJitterManager.phaseCountFor(1.5), "UQ");
        assertEquals(18, io.github.andrexarlay.vulkancompat.rtx.upscaler.CameraJitterManager.phaseCountFor(1280.0 / 854.0), "UQ 1280/854");
        assertEquals(32, io.github.andrexarlay.vulkancompat.rtx.upscaler.CameraJitterManager.phaseCountFor(2.0), "B");
        assertEquals(72, io.github.andrexarlay.vulkancompat.rtx.upscaler.CameraJitterManager.phaseCountFor(3.0), "UP");
    }

    private static void testVelocityShaderCompiles() {
        java.nio.ByteBuffer spv = io.github.andrexarlay.vulkancompat.rtx.upscaler.VelocityPipeline.compileShaderToSpirv();
        assertTrue(spv != null && spv.remaining() > 0, "velocity_reproject.comp must compile to SPIR-V");
    }

    /**
     * Compiles the RT shaders in every define combination the pipelines instantiate. A GLSL error in rt_common.glsl
     * surfaces here instead of disabling ray tracing at runtime.
     */
    private static void testRtShaderSourcesCompile() {
        String gbuffer = io.github.andrexarlay.vulkancompat.rt.world.RtPipeline.GBUFFER_DEFINE;
        String accum = io.github.andrexarlay.vulkancompat.rt.world.RtPathTracerPass.ACCUM_DEFINE;
        assertSpirv("/shaders/rt_rays.comp", "rt_rays.comp", "", "ray mode");
        assertSpirv("/shaders/rt_rays.comp", "rt_rays.comp+gbuffer", gbuffer, "ray mode with the denoiser G-buffer");
        assertSpirv("/shaders/rt_path.comp", "rt_path.comp", accum, "path mode");
        assertSpirv("/shaders/rt_path.comp", "rt_path.comp+gbuffer", gbuffer + accum, "path mode with the denoiser G-buffer");
        assertSpirv("/shaders/rt_atrous.comp", "rt_atrous.comp", accum, "path-mode atrous filter");
    }

    private static void assertSpirv(String path, String name, String defines, String what) {
        java.nio.ByteBuffer spv = io.github.andrexarlay.vulkancompat.rt.world.RtShaderCompiler.compileResource(path, name, defines);
        assertTrue(spv != null && spv.remaining() > 0, what + " must compile to SPIR-V");
    }

    private static void testFeatureSetStructs() {
        try {
            com.mojang.renderpearl.backend.vulkan.init.VulkanPNextStruct asStruct =
                new com.mojang.renderpearl.backend.vulkan.init.VulkanPNextStruct(org.lwjgl.vulkan.VkPhysicalDeviceAccelerationStructureFeaturesKHR.class);
            com.mojang.renderpearl.backend.vulkan.init.VulkanPNextStruct rqStruct =
                new com.mojang.renderpearl.backend.vulkan.init.VulkanPNextStruct(org.lwjgl.vulkan.VkPhysicalDeviceRayQueryFeaturesKHR.class);
            com.mojang.renderpearl.backend.vulkan.init.VulkanPNextStruct bdaStruct =
                new com.mojang.renderpearl.backend.vulkan.init.VulkanPNextStruct(org.lwjgl.vulkan.VkPhysicalDeviceBufferDeviceAddressFeaturesKHR.class);

            com.mojang.renderpearl.backend.vulkan.init.VulkanFeature f1 =
                new com.mojang.renderpearl.backend.vulkan.init.VulkanFeature(asStruct, "accelerationStructure");
            com.mojang.renderpearl.backend.vulkan.init.VulkanFeature f2 =
                new com.mojang.renderpearl.backend.vulkan.init.VulkanFeature(rqStruct, "rayQuery");
            com.mojang.renderpearl.backend.vulkan.init.VulkanFeature f3 =
                new com.mojang.renderpearl.backend.vulkan.init.VulkanFeature(bdaStruct, "bufferDeviceAddress");

            com.mojang.renderpearl.backend.vulkan.init.FeatureSet rtFeatureSet =
                new com.mojang.renderpearl.backend.vulkan.init.FeatureSet(
                    "Hardware Ray Tracing",
                    java.util.Set.of(
                        "VK_KHR_acceleration_structure",
                        "VK_KHR_ray_query",
                        "VK_KHR_buffer_device_address",
                        "VK_KHR_deferred_host_operations"
                    ),
                    java.util.Set.of(f1, f2, f3)
                );
            assertTrue(rtFeatureSet != null, "rtFeatureSet must not be null");
            System.out.println("FeatureSet created: " + rtFeatureSet);

            java.nio.ByteBuffer spv = io.github.andrexarlay.vulkancompat.rt.poc.RtTestPipeline.obtainSpirv();
            assertTrue(spv != null && spv.remaining() > 0, "SPIR-V bytecode must be available");
            System.out.println("SPIR-V obtained: " + spv.remaining() + " bytes");
        } catch (Throwable t) {
            throw new RuntimeException("FeatureSet creation failed: " + t.getMessage(), t);
        }
    }

    /** Our own requirements convert into engine FeatureSets through Bedray Core applier (Core-side registry logic is tested in module core). */
    private static void testRtxDeviceRequirements() {
        io.github.andrexarlay.vulkanbridge.api.DeviceRequirement rtReq = io.github.andrexarlay.vulkancompat.rtx.raytracing.RtDeviceRequirements.requirement();
        com.mojang.renderpearl.backend.vulkan.init.FeatureSet rt = io.github.andrexarlay.vulkanbridge.core.RequirementApplier.toFeatureSet(rtReq);
        assertEquals("Hardware Ray Tracing", rt.name(), "RT set name");
        assertEquals(4, rt.extensions().size(), "RT extensions");
        assertEquals(3, rt.features().size(), "RT features");
        var xessBase = io.github.andrexarlay.vulkancompat.rtx.upscaler.xess.XessDeviceRequirements.base(() -> Boolean.TRUE);
        assertEquals(0x403000, xessBase.minInstanceApiVersion(), "XeSS asks for Vulkan 1.3");
        assertEquals(3, io.github.andrexarlay.vulkanbridge.core.RequirementApplier.toFeatureSet(xessBase).features().size(), "XeSS base features");
        assertEquals(3, io.github.andrexarlay.vulkanbridge.core.RequirementApplier
            .toFeatureSet(io.github.andrexarlay.vulkancompat.rtx.upscaler.xess.XessDeviceRequirements.perf(() -> Boolean.TRUE)).extensions().size(), "XeSS perf extensions");
        // registering in a fresh (non-global) registry: both of our requirements are accepted by Bedray Core registry implementation
        io.github.andrexarlay.vulkanbridge.core.DeviceFeatureRegistryImpl reg = new io.github.andrexarlay.vulkanbridge.core.DeviceFeatureRegistryImpl();
        io.github.andrexarlay.vulkancompat.rtx.raytracing.RtDeviceRequirements.register(reg);
        assertEquals(1, reg.snapshot().size(), "RT requirement registered");
        assertEquals(io.github.andrexarlay.vulkancompat.rtx.raytracing.RtDeviceRequirements.ID, reg.snapshot().get(0).id(), "RT requirement id");
    }

    private static void testLateProvidersAndScenarios() {
        // late providers: RT is registered when Bedray Core runs them; XeSS registers nothing without -Dvkbridge.xess (requestedQuality() == null)
        io.github.andrexarlay.vulkanbridge.core.DeviceFeatureRegistryImpl reg = new io.github.andrexarlay.vulkanbridge.core.DeviceFeatureRegistryImpl();
        reg.addLateProvider(RtxBootstrap.RT_PROVIDER_ID, io.github.andrexarlay.vulkancompat.rtx.raytracing.RtDeviceRequirements::register);
        reg.addLateProvider(RtxBootstrap.XESS_PROVIDER_ID, r -> io.github.andrexarlay.vulkancompat.rtx.upscaler.xess.XessDeviceRequirements
            .register(r, () -> java.nio.file.Path.of(".").toAbsolutePath().normalize()));
        assertEquals(0, reg.snapshot().size(), "nothing registered before the providers run");
        reg.runLateProviders();
        if (io.github.andrexarlay.vulkancompat.rtx.upscaler.xess.XessConfig.requestedQuality() == null) {
            assertEquals(1, reg.snapshot().size(), "only RT is registered when XeSS was not requested");
        }
        assertEquals("Hardware Ray Tracing", reg.snapshot().get(0).id(), "RT requirement id from late provider");
        // autotest scenarios of this mod: names are valid and not built in, registration works and a duplicate is rejected
        io.github.andrexarlay.vulkanbridge.autotest.AutotestScenarios.clearForTests();
        io.github.andrexarlay.vulkancompat.autotest.RtxAutotest.registerAll();
        assertTrue(io.github.andrexarlay.vulkanbridge.autotest.AutotestScenarios.isKnown("settings"), "settings registered");
        assertTrue(io.github.andrexarlay.vulkanbridge.autotest.AutotestScenarios.isKnown("settings_ui"), "settings_ui registered");
        assertTrue(io.github.andrexarlay.vulkanbridge.autotest.AutotestScenarios.isKnown("sun"), "sun registered");
        assertTrue(io.github.andrexarlay.vulkanbridge.autotest.AutotestScenarios.isKnown("upscaler_switch"), "upscaler_switch registered");
        assertTrue(!io.github.andrexarlay.vulkanbridge.autotest.AutotestScenarios.find("settings").needsWorld(), "settings runs on the title screen");
        assertTrue(!io.github.andrexarlay.vulkanbridge.autotest.AutotestScenarios.find("settings_ui").needsWorld(), "settings_ui runs on the title screen");
        assertTrue(io.github.andrexarlay.vulkanbridge.autotest.AutotestScenarios.find("sun").needsWorld(), "sun needs a world");
        boolean duplicateRejected = false;
        try {
            io.github.andrexarlay.vulkancompat.autotest.RtxAutotest.registerAll();
        } catch (IllegalStateException expected) {
            duplicateRejected = true;
        }
        assertTrue(duplicateRejected, "duplicate registration rejected");
        io.github.andrexarlay.vulkanbridge.autotest.AutotestScenarios.clearForTests();
    }

    private static void testMotionVectorStability() throws Exception {
        io.github.andrexarlay.vulkancompat.rtx.upscaler.MotionVectorStabilityTest t =
            new io.github.andrexarlay.vulkancompat.rtx.upscaler.MotionVectorStabilityTest();
        t.testMotionVectorReprojectionMath();
        t.testCpuReferenceReprojectionStaticPoint();
        t.testCpuReferenceCameraShiftRight();
        t.testCpuReferenceCameraRotation();
        t.testCpuReferenceHistoryReset();
        t.testCpuReferenceMotionVectorWithCameraJitter();
        t.testCpuReferenceJitteredReprojectionStaysStatic();
        t.testVelocityShaderCarriesJitter();
    }
}
