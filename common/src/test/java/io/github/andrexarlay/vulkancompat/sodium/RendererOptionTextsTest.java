package io.github.andrexarlay.vulkancompat.sodium;

import io.github.andrexarlay.bedray.api.v1.GraphicsBackendRegistry;
import io.github.andrexarlay.vulkancompat.rtx.config.VulkanRtxConfig;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * The renderer and compute-scene rows label EVERY offered enum value: Sodium's cycling control asks the element name of
 * each allowed value to size the widget, so a value without a label throws inside screen rendering (the crash class
 * {@link UpscalerModeLabelTest} pins for the upscaler modes). {@link RendererOptionTexts} owns these labels and the
 * runtime status line; they are pure functions of the selection/scene and the backend registry and hold no page state.
 * The labels are compared as {@link Component} values (key + fallback), so the assertions do not depend on a loaded
 * language.
 */
public class RendererOptionTextsTest {

    @Test
    public void everySceneModeHasADistinctLabel() {
        GraphicsBackendRegistry.SceneMode[] scenes = GraphicsBackendRegistry.SceneMode.values();
        Component[] labels = new Component[scenes.length];
        for (int i = 0; i < scenes.length; i++) {
            final GraphicsBackendRegistry.SceneMode scene = scenes[i];
            labels[i] = assertDoesNotThrow(() -> RendererOptionTexts.computeSceneName(scene),
                "scene " + scene + " must have a label");
            assertNotNull(labels[i]);
        }
        for (int i = 0; i < labels.length; i++) {
            for (int j = i + 1; j < labels.length; j++) {
                assertNotEquals(labels[i], labels[j], scenes[i] + " and " + scenes[j] + " must show different labels");
            }
        }
    }

    @Test
    public void hardwareRendererLabelIsTheHardwareText() {
        assertEquals(Component.translatableWithFallback("bedray.renderer.hardware", "Hardware RT / Raster"),
            RendererOptionTexts.rendererName(VulkanRtxConfig.RendererSelection.HARDWARE_RT));
    }

    @Test
    public void everyRendererSelectionHasALabel() {
        for (VulkanRtxConfig.RendererSelection sel : VulkanRtxConfig.RendererSelection.values()) {
            assertNotNull(assertDoesNotThrow(() -> RendererOptionTexts.rendererName(sel),
                "renderer " + sel + " must have a label"));
        }
    }

    @Test
    public void rendererStatusResolvesWithoutThrowing() {
        assertNotNull(assertDoesNotThrow(RendererOptionTexts::rendererStatus,
            "the status line is read while the page builds and by BedrayConfigScreen"));
    }
}