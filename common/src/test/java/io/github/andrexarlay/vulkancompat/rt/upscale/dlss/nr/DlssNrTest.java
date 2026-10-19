package io.github.andrexarlay.vulkancompat.rt.upscale.dlss.nr;

import io.github.andrexarlay.vulkancompat.rtx.config.RtSettings;
import io.github.andrexarlay.vulkancompat.rtx.raytracing.VulkanCapabilityChecker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * L2 JUnit tests for DLSS 5 Neural Rendering (DLSS-NR) prototype.
 * Tests configuration parameters, clamping, fail-safe isolation fallback,
 * GPU hardware guard, and RenoDX/OptiScaler color composition.
 */
class DlssNrTest {

    private Path tempDir;

    @BeforeEach
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("dlssnr_test_");
        VulkanCapabilityChecker.setOverrideNvidiaForTesting(true);
    }

    @AfterEach
    void tearDown() {
        VulkanCapabilityChecker.setOverrideNvidiaForTesting(null);
        System.clearProperty(DlssNeuralRendering.PROP_FORCE_SUPPORTED);
        System.clearProperty(DlssNeuralRendering.PROP_DIR);
        System.clearProperty(DlssNeuralRendering.PROP_MODEL_PATH);
        if (tempDir != null) {
            try {
                Files.deleteIfExists(tempDir.resolve(DlssNeuralRendering.MODEL_DLL_NAME));
                Files.deleteIfExists(tempDir.resolve(DlssNeuralRendering.LOADER_DLL_NAME));
                Files.deleteIfExists(tempDir);
            } catch (IOException ignored) {}
        }
    }

    @Test
    @DisplayName("Verify DLSS-NR configuration defaults match OptiScaler specifications")
    void testConfigDefaults() {
        DlssNrConfig cfg = new DlssNrConfig();

        assertFalse(cfg.isEnabled(), "DLSS-NR must be disabled by default (strictly opt-in experimental pass)");
        assertEquals(1.0f, cfg.getTransferStrength(), 1e-4f, "Default transfer strength must be 1.0");
        assertEquals(1.0f, cfg.getColourStrength(), 1e-4f, "Default colour strength must be 1.0");
        assertEquals(2.0f, cfg.getMaxRatio(), 1e-4f, "Default max ratio (highlight guard) must be 2.0");
        assertEquals(1.0f, cfg.getWorkingScale(), 1e-4f, "Default working scale must be 1.0");
        assertEquals(0, cfg.getDebugView(), "Default debug view must be 0 (Off)");
        assertTrue(cfg.isBypass(), "Default config must be in bypass mode since it is disabled");
    }

    @Test
    @DisplayName("Verify DLSS-NR parameter bounds and clamping")
    void testConfigClamping() {
        DlssNrConfig cfg = new DlssNrConfig();

        // Transfer strength: [0.0 .. 2.0]
        cfg.setTransferStrength(-1.0f);
        assertEquals(0.0f, cfg.getTransferStrength(), 1e-4f);
        cfg.setTransferStrength(3.5f);
        assertEquals(2.0f, cfg.getTransferStrength(), 1e-4f);

        // Colour strength: [0.0 .. 1.0]
        cfg.setColourStrength(-0.5f);
        assertEquals(0.0f, cfg.getColourStrength(), 1e-4f);
        cfg.setColourStrength(1.8f);
        assertEquals(1.0f, cfg.getColourStrength(), 1e-4f);

        // Max ratio: [1.0 .. 10.0]
        cfg.setMaxRatio(0.2f);
        assertEquals(1.0f, cfg.getMaxRatio(), 1e-4f);
        cfg.setMaxRatio(15.0f);
        assertEquals(10.0f, cfg.getMaxRatio(), 1e-4f);

        // Working scale: [0.5 .. 1.0]
        cfg.setWorkingScale(0.1f);
        assertEquals(0.5f, cfg.getWorkingScale(), 1e-4f);
        cfg.setWorkingScale(2.5f);
        assertEquals(1.0f, cfg.getWorkingScale(), 1e-4f);

        // Debug view: [0 .. 3]
        cfg.setDebugView(-2);
        assertEquals(0, cfg.getDebugView());
        cfg.setDebugView(99);
        assertEquals(3, cfg.getDebugView());
    }

    @Test
    @DisplayName("Verify DLSS-NR config copy and reset lifecycle")
    void testConfigCopyAndReset() {
        DlssNrConfig cfg = new DlssNrConfig();
        cfg.setEnabled(true);
        cfg.setTransferStrength(1.5f);
        cfg.setColourStrength(0.7f);
        cfg.setMaxRatio(2.5f);
        cfg.setWorkingScale(0.8f);
        cfg.setDebugView(DlssNrConfig.DEBUG_VIEW_RAW);

        DlssNrConfig copy = cfg.copy();
        assertEquals(cfg, copy);

        // Mutating copy must not affect original
        copy.setTransferStrength(0.2f);
        assertNotEquals(cfg, copy);
        assertEquals(1.5f, cfg.getTransferStrength(), 1e-4f);

        // Reset restores defaults
        cfg.resetToDefaults();
        assertFalse(cfg.isEnabled());
        assertEquals(1.0f, cfg.getTransferStrength(), 1e-4f);
    }

    @Test
    @DisplayName("Verify Fail-Safe Guard: Missing DLLs result in safe fallback without crashing")
    void testSafeFallbackWhenDllMissing() throws IOException {
        DlssNrConfig cfg = new DlssNrConfig();
        cfg.setEnabled(true);

        DlssNeuralRendering nr = new DlssNeuralRendering(cfg);

        // Check libraries in isolated empty directory
        Path emptyDir = tempDir.resolve("isolated_empty");
        Files.createDirectories(emptyDir);
        DlssNeuralRendering.LibraryResolution res = DlssNeuralRendering.checkLibraries(emptyDir);
        assertFalse(res.usable(), "Empty directory must report missing libraries");
        assertNotNull(res.reason());

        // Init must safely fail with NOT_SUPPORTED_MISSING_LIBS without throwing exceptions
        DlssNeuralRendering.Status initStatus = nr.init(emptyDir, "NVIDIA GeForce RTX 5090");
        assertEquals(DlssNeuralRendering.Status.NOT_SUPPORTED_MISSING_LIBS, initStatus);
        assertFalse(nr.isInitialized(), "Should not be initialized when libraries are absent");

        // Evaluate must safely bypass and copy input to output without throwing exceptions
        float[] orig = new float[]{0.5f, 0.6f, 0.7f};
        float[] model = new float[]{0.9f, 0.9f, 0.9f};
        float[] out = new float[3];

        DlssNeuralRendering.Status evalStatus = nr.evaluate(orig, model, out);
        assertEquals(DlssNeuralRendering.Status.BYPASS, evalStatus);
        assertArrayEquals(orig, out, 1e-6f, "Bypassed evaluation must pass original pixels through untouched");

        // Release must be idempotent and safe
        assertDoesNotThrow(nr::release);
        assertDoesNotThrow(nr::close);
    }

    @Test
    @DisplayName("Verify Fail-Safe Guard: GPU architecture requirements (RTX 50 series check)")
    void testGpuSupportAndSafeFallbackOnUnsupportedGpu() throws IOException {
        // Create mock DLL files in tempDir so libraries pass
        Files.createFile(tempDir.resolve(DlssNeuralRendering.MODEL_DLL_NAME));
        Files.createFile(tempDir.resolve(DlssNeuralRendering.LOADER_DLL_NAME));

        DlssNrConfig cfg = new DlssNrConfig();
        cfg.setEnabled(true);
        DlssNeuralRendering nr = new DlssNeuralRendering(cfg);

        // 1. Non-NVIDIA / older NVIDIA GPUs must be rejected
        assertFalse(DlssNeuralRendering.isGpuSupported(null));
        assertFalse(DlssNeuralRendering.isGpuSupported("AMD Radeon RX 7900 XTX"));
        assertFalse(DlssNeuralRendering.isGpuSupported("Intel Arc A770"));
        assertFalse(DlssNeuralRendering.isGpuSupported("NVIDIA GeForce RTX 4090"), "RTX 40 series must not pass");
        assertFalse(DlssNeuralRendering.isGpuSupported("NVIDIA GeForce RTX 3080"), "RTX 30 series must not pass");
        assertFalse(DlssNeuralRendering.isGpuSupported("NVIDIA GeForce RTX 2080 Ti"), "RTX 20 series must not pass");

        // 2. RTX 50 series (Blackwell) must be recognized
        assertTrue(DlssNeuralRendering.isGpuSupported("NVIDIA GeForce RTX 5090"));
        assertTrue(DlssNeuralRendering.isGpuSupported("NVIDIA GeForce RTX 5080 Laptop GPU"));
        assertTrue(DlssNeuralRendering.isGpuSupported("NVIDIA GeForce RTX 5070"));
        assertTrue(DlssNeuralRendering.isGpuSupported("RTX 5060"));
        assertTrue(DlssNeuralRendering.isGpuSupported("NVIDIA Blackwell Graphics"));

        // 3. Init with an older GPU must return NOT_SUPPORTED_GPU_ARCH safely without throwing
        DlssNeuralRendering.Status status = nr.init(tempDir, "NVIDIA GeForce RTX 4080");
        assertEquals(DlssNeuralRendering.Status.NOT_SUPPORTED_GPU_ARCH, status);
        assertFalse(nr.isInitialized());

        // 4. Force override for testing allows initializing on test systems
        System.setProperty(DlssNeuralRendering.PROP_FORCE_SUPPORTED, "true");
        assertTrue(DlssNeuralRendering.isGpuSupported("Any GPU"));

        DlssNeuralRendering.Status overrideStatus = nr.init(tempDir, "Test Device");
        assertEquals(DlssNeuralRendering.Status.INITIALIZED, overrideStatus);
        assertTrue(nr.isInitialized());
    }

    @Test
    @DisplayName("Verify RenoDX / OptiScaler color composition: Bypass at transferStrength = 0")
    void testColorCompositionBypass() {
        DlssNrConfig cfg = new DlssNrConfig();
        cfg.setEnabled(true);
        cfg.setTransferStrength(0.0f); // True bypass

        float[] orig = new float[]{0.3f, 0.5f, 0.7f};
        float[] model = new float[]{0.9f, 0.2f, 0.1f};
        float[] out = DlssNeuralRendering.composeColor(orig, model, cfg);

        assertArrayEquals(orig, out, 1e-6f, "Transfer strength 0.0 must output exact original color");
    }

    @Test
    @DisplayName("Verify RenoDX / OptiScaler color composition: Luminance transfer and Colour strength")
    void testColorCompositionLuminanceAndColourStrength() {
        DlssNrConfig cfg = new DlssNrConfig();
        cfg.setEnabled(true);
        cfg.setTransferStrength(1.0f);
        cfg.setMaxRatio(3.0f);

        float[] orig = new float[]{0.2f, 0.4f, 0.8f};
        float[] model = new float[]{0.8f, 0.4f, 0.2f};

        // At colourStrength = 0: Hue ratios (R/G, B/G) must match the original color exactly
        cfg.setColourStrength(0.0f);
        float[] outLumOnly = DlssNeuralRendering.composeColor(orig, model, cfg);

        float origRatioRG = orig[0] / orig[1];
        float outRatioRG = outLumOnly[0] / outLumOnly[1];
        assertEquals(origRatioRG, outRatioRG, 1e-4f, "Colour strength 0 must strictly preserve original chromaticity/hue");

        // At colourStrength = 1: Model hue is present
        cfg.setColourStrength(1.0f);
        float[] outFullColour = DlssNeuralRendering.composeColor(orig, model, cfg);
        assertNotEquals(outLumOnly[0], outFullColour[0], 1e-3f, "Colour strength 1 must incorporate model hue");
    }

    @Test
    @DisplayName("Verify Highlight Guard (maxRatio) prevents excessive brightening")
    void testHighlightGuardMaxRatio() {
        DlssNrConfig cfg = new DlssNrConfig();
        cfg.setEnabled(true);
        cfg.setTransferStrength(1.0f);
        cfg.setColourStrength(0.0f);
        cfg.setMaxRatio(1.5f); // Cap to 1.5x original luminance

        float[] orig = new float[]{0.1f, 0.1f, 0.1f};
        // Model asks for 10x brightness
        float[] model = new float[]{1.0f, 1.0f, 1.0f};

        float[] out = DlssNeuralRendering.composeColor(orig, model, cfg);

        // Maximum pixel value must be bounded by orig * maxRatio = 0.1 * 1.5 = 0.15
        assertEquals(0.15f, out[0], 1e-4f, "Highlight guard must cap pixel brightness to 1.5x");
        assertEquals(0.15f, out[1], 1e-4f);
        assertEquals(0.15f, out[2], 1e-4f);
    }

    @Test
    @DisplayName("Verify Debug Views: Input, Raw Model, Difference")
    void testDebugViews() {
        DlssNrConfig cfg = new DlssNrConfig();
        cfg.setEnabled(true);

        float[] orig = new float[]{0.2f, 0.3f, 0.4f};
        float[] model = new float[]{0.5f, 0.6f, 0.7f};

        // 1. Debug view: INPUT
        cfg.setDebugView(DlssNrConfig.DEBUG_VIEW_INPUT);
        float[] outInput = DlssNeuralRendering.composeColor(orig, model, cfg);
        assertArrayEquals(orig, outInput, 1e-6f);

        // 2. Debug view: RAW
        cfg.setDebugView(DlssNrConfig.DEBUG_VIEW_RAW);
        float[] outRaw = DlssNeuralRendering.composeColor(orig, model, cfg);
        assertArrayEquals(model, outRaw, 1e-6f);

        // 3. Debug view: DIFFERENCE
        cfg.setDebugView(DlssNrConfig.DEBUG_VIEW_DIFFERENCE);
        float[] outDiff = DlssNeuralRendering.composeColor(orig, model, cfg);
        // Formula: 0.5 + 20 * (model - orig)
        assertEquals(0.5f + 20.0f * (0.5f - 0.2f), outDiff[0], 1e-4f);

        // Flat grey (0.5) when model == orig
        float[] outDiffIdentical = DlssNeuralRendering.composeColor(orig, orig, cfg);
        assertArrayEquals(new float[]{0.5f, 0.5f, 0.5f}, outDiffIdentical, 1e-6f,
            "Difference view on identical frames must return flat grey 0.5");
    }

    @Test
    @DisplayName("Verify contiguous pixel buffer composition")
    void testPixelBufferComposition() {
        DlssNrConfig cfg = new DlssNrConfig();
        cfg.setEnabled(true);
        cfg.setTransferStrength(1.0f);
        cfg.setColourStrength(1.0f);

        int pixelCount = 4;
        float[] origBuffer = new float[]{
            0.1f, 0.2f, 0.3f,
            0.4f, 0.5f, 0.6f,
            0.7f, 0.8f, 0.9f,
            0.2f, 0.4f, 0.6f
        };
        float[] modelBuffer = new float[]{
            0.3f, 0.2f, 0.1f,
            0.6f, 0.5f, 0.4f,
            0.9f, 0.8f, 0.7f,
            0.6f, 0.4f, 0.2f
        };
        float[] outBuffer = new float[pixelCount * 3];

        DlssNeuralRendering.composePixelBuffer(origBuffer, modelBuffer, outBuffer, pixelCount, cfg);

        // Check first pixel matches single composeColor
        float[] singleOut = DlssNeuralRendering.composeColor(
            new float[]{0.1f, 0.2f, 0.3f},
            new float[]{0.3f, 0.2f, 0.1f},
            cfg
        );
        assertEquals(singleOut[0], outBuffer[0], 1e-5f);
        assertEquals(singleOut[1], outBuffer[1], 1e-5f);
        assertEquals(singleOut[2], outBuffer[2], 1e-5f);
    }

    @Test
    @DisplayName("Verify RtSettings integration and DLSS-NR configuration reflection")
    void testRtSettingsIntegration() {
        RtSettings.dlssNrEnabled = true;
        RtSettings.dlssNrTransferStrength = 1.4f;
        RtSettings.dlssNrColourStrength = 0.8f;
        RtSettings.dlssNrMaxRatio = 2.2f;
        RtSettings.dlssNrWorkingScale = 0.75f;
        RtSettings.dlssNrDebugView = 2;

        assertTrue(RtSettings.dlssNr());
        assertEquals(1.4f, RtSettings.dlssNrTransferStrength(), 1e-4f);
        assertEquals(0.8f, RtSettings.dlssNrColourStrength(), 1e-4f);
        assertEquals(2.2f, RtSettings.dlssNrMaxRatio(), 1e-4f);
        assertEquals(0.75f, RtSettings.dlssNrWorkingScale(), 1e-4f);
        assertEquals(2, RtSettings.dlssNrDebugView());

        DlssNrConfig cfg = RtSettings.getDlssNrConfig();
        assertTrue(cfg.isEnabled());
        assertEquals(1.4f, cfg.getTransferStrength(), 1e-4f);
        assertEquals(0.8f, cfg.getColourStrength(), 1e-4f);
        assertEquals(2.2f, cfg.getMaxRatio(), 1e-4f);
        assertEquals(0.75f, cfg.getWorkingScale(), 1e-4f);
        assertEquals(2, cfg.getDebugView());

        // Test applyKeyValue from TOML
        RtSettings.applyKeyValue("dlss_nr_transfer_strength", "1.8");
        assertEquals(1.8f, RtSettings.dlssNrTransferStrength(), 1e-4f);

        RtSettings.applyKeyValue("dlss_nr_max_ratio", "3.5");
        assertEquals(3.5f, RtSettings.dlssNrMaxRatio(), 1e-4f);

        // Reset
        RtSettings.dlssNrEnabled = false;
        RtSettings.dlssNrTransferStrength = 1.0f;
        RtSettings.dlssNrColourStrength = 1.0f;
        RtSettings.dlssNrMaxRatio = 2.0f;
        RtSettings.dlssNrWorkingScale = 1.0f;
        RtSettings.dlssNrDebugView = 0;
    }
}
