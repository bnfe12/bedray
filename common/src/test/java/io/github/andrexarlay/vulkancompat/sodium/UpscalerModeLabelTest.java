package io.github.andrexarlay.vulkancompat.sodium;

import io.github.andrexarlay.vulkancompat.rtx.config.UpscalerMode;
import io.github.andrexarlay.vulkancompat.rtx.config.UpscalerModes;
import io.github.andrexarlay.vulkancompat.rtx.config.VulkanRtxConfig;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.UpscalerBackend;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sodium's cycling control asks the element name of EVERY allowed value to size the widget
 * ({@code CyclingControl$CyclingControlElement.getContentWidth} -> {@code truncateLabelToFit} ->
 * {@code EnumOption.getElementName}), not only of the selected one. Any value without a label therefore throws inside
 * screen rendering: the 2026-10-23 crash report (crash-2026-10-23_00.46.00-client.txt) is this path with FSR/XeSS
 * offering CUSTOM, which has no table ratio. The log line of the same option's binding called the table ratio too,
 * and that one additionally refuses every DLSS mode (the render size is queried from NGX at runtime).
 */
public class UpscalerModeLabelTest {

    @Test
    public void everyOfferedModeHasALabel() {
        for (UpscalerBackend backend : UpscalerBackend.values()) {
            for (UpscalerMode mode : UpscalerModes.modesFor(backend)) {
                assertDoesNotThrow(() -> VulkanSodiumConfig.modeName(backend, mode),
                    "the video settings screen renders the name of every allowed value: " + backend + " " + mode);
            }
        }
    }

    @Test
    public void customModeShowsTheUserPercent() {
        VulkanRtxConfig.setCustomPercent(UpscalerBackend.FSR, 67);
        String suffix = VulkanSodiumConfig.ratioSuffix(UpscalerBackend.FSR, UpscalerMode.CUSTOM);
        assertTrue(suffix.contains("67.0%"), "the render scale of CUSTOM is the user's percent, got " + suffix);
        assertTrue(suffix.contains("1.5x"), "67% is a ratio of 100/67 = 1.49, got " + suffix);
    }

    @Test
    public void bindingLogValueNeverThrows() {
        for (UpscalerBackend backend : UpscalerBackend.values()) {
            for (UpscalerMode mode : UpscalerModes.modesFor(backend)) {
                assertDoesNotThrow(() -> VulkanSodiumConfig.qualityRatioForLog(backend, mode),
                    "selecting " + mode + " on " + backend + " must not crash the binding");
            }
        }
        assertEquals("runtime-ngx", VulkanSodiumConfig.qualityRatioForLog(UpscalerBackend.DLSS, UpscalerMode.QUALITY),
            "DLSS has no table ratio: the render size is queried from NGX");
    }
}
