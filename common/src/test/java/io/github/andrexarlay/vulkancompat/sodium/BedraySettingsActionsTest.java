package io.github.andrexarlay.vulkancompat.sodium;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The settings-mutation actions carved out of {@link VulkanSodiumConfig}: the reset/apply/mode bindings, the screen-config
 * apply, the value-refresh flag and the engine mode-change hook now live in {@link BedraySettingsActions}. The page keeps
 * the UI construction (the option/button builders, the {@code custom}/{@code customPath} value wrappers, the debug-view
 * enum) and delegates every action, so a later edit cannot quietly pull the mutation logic back into the monolith, nor
 * split the refresh state across two owners. The builders still request a refresh through the facade delegate.
 */
class BedraySettingsActionsTest {

    private static final Path JAVA = Path.of("src/main/java/io/github/andrexarlay/vulkancompat/sodium");
    private static final Path OWNER = JAVA.resolve("BedraySettingsActions.java");
    private static final Path FACADE = JAVA.resolve("VulkanSodiumConfig.java");

    @Test
    void theOwnerHoldsTheSettingsActions() throws IOException {
        assertTrue(Files.exists(OWNER), "the settings-actions owner exists");
        String owner = read(OWNER);
        assertTrue(owner.contains("static void resetOptions(List<Identifier> ids, String why)"),
            "the list reset moved into the owner");
        assertTrue(owner.contains("o.resetToDefault();"), "the per-option reset is the owner's");
        assertTrue(owner.contains("static void afterPresetApplied()"), "the preset hook moved into the owner");
        assertTrue(owner.contains("static void applyPathPresetFromUi(RtSettings.PathPreset val)"),
            "the PT-quality binding moved into the owner");
        assertTrue(owner.contains("static void applyScreenConfig("), "the screen-config apply moved into the owner");
        assertTrue(owner.contains("static void resetAllToDefaultsAndReopen(net.minecraft.client.gui.screens.Screen current)"),
            "the full reset moved into the owner");
        assertTrue(owner.contains("VulkanRtxConfig.activeBackend = UpscalerBackend.BILINEAR;"),
            "the upscaler reset is the owner's");
        assertTrue(owner.contains("static void applyRtModeFromUi(RtMode val)"),
            "the tracing-mode binding moved into the owner");
        assertTrue(owner.contains("static void applyRendererFromUi(VulkanRtxConfig.RendererSelection renderer)"),
            "the renderer binding moved into the owner");
        assertTrue(owner.contains("static void applyComputeSceneFromUi(GraphicsBackendRegistry.SceneMode scene)"),
            "the compute-scene binding moved into the owner");
        assertTrue(owner.contains("static void resetAndReopen(RtSettings.Level level, net.minecraft.client.gui.screens.Screen current)"),
            "the level reset moved into the owner");
        assertTrue(owner.contains("[VKB-AUTOTEST] ui_reset_all_defaults applied rt_preset={} {}"),
            "the reset log moved with the action");
    }

    @Test
    void theFacadeDelegatesTheActions() throws IOException {
        String facade = read(FACADE);
        assertTrue(facade.contains("BedraySettingsActions.resetOptions(ids, why);"),
            "the list reset delegates to the owner");
        assertTrue(facade.contains("BedraySettingsActions.afterPresetApplied();"),
            "the preset hook delegates to the owner");
        assertTrue(facade.contains("BedraySettingsActions.applyPathPresetFromUi(val);"),
            "the PT-quality binding delegates to the owner");
        assertTrue(facade.contains("BedraySettingsActions.applyScreenConfig(config, imported);"),
            "the screen-config apply delegates to the owner");
        assertTrue(facade.contains("BedraySettingsActions.requestValueRefresh();"),
            "the refresh request delegates to the owner");
        assertTrue(facade.contains("return BedraySettingsActions.consumeValueRefreshRequest();"),
            "the refresh consume delegates to the owner");
        assertTrue(facade.contains("BedraySettingsActions.resetAllToDefaultsAndReopen(current);"),
            "the full reset delegates to the owner");
        assertTrue(facade.contains("BedraySettingsActions.applyRtModeFromUi(val);"),
            "the tracing-mode binding delegates to the owner");
        assertTrue(facade.contains("BedraySettingsActions.applyRendererFromUi(renderer);"),
            "the renderer binding delegates to the owner");
        assertTrue(facade.contains("BedraySettingsActions.applyComputeSceneFromUi(scene);"),
            "the compute-scene binding delegates to the owner");
        assertTrue(facade.contains("BedraySettingsActions.resetAndReopen(level, current);"),
            "the level reset delegates to the owner");
        assertFalse(facade.contains("VulkanRtxConfig.activeBackend = UpscalerBackend.BILINEAR;"),
            "the upscaler reset moved to the owner");
        assertFalse(facade.contains("o.resetToDefault();"), "the per-option reset moved to the owner");
        assertFalse(facade.contains("[VKB-AUTOTEST] ui_reset_all_defaults applied rt_preset={} {}"),
            "the reset log moved to the owner");
    }

    @Test
    void theModeChangeHookAndRefreshStateAreTheOwners() throws IOException {
        assertTrue(Files.exists(OWNER), "the settings-actions owner exists");
        String owner = read(OWNER);
        String facade = read(FACADE);
        assertTrue(owner.contains("private static void afterModeChange(RtMode before, RtMode after, String reason)"),
            "the mode-change hook moved into the owner with all its callers");
        assertTrue(owner.contains("RtGeometryGate.clearFailure();"), "the failure-clear is the owner's");
        assertTrue(owner.contains("private static volatile boolean valueRefreshRequested;"),
            "the refresh flag is the owner's");
        assertTrue(owner.contains("private static boolean applyingScreenConfig;"), "the apply guard is the owner's");
        assertTrue(owner.contains("if (!applyingScreenConfig) valueRefreshRequested = true;"),
            "the refresh request reads the owner's own state");
        assertTrue(owner.contains("boolean r = valueRefreshRequested;"),
            "the refresh consume reads the owner's own state");
        assertFalse(facade.contains("afterModeChange"),
            "the mode-change hook left the facade entirely: every caller moved with it");
        assertFalse(facade.contains("private static volatile boolean valueRefreshRequested;"),
            "the refresh flag left the facade");
        assertFalse(facade.contains("private static boolean applyingScreenConfig;"),
            "the apply guard left the facade");
    }

    @Test
    void theUiBuildersStayInTheFacade() throws IOException {
        String facade = read(FACADE);
        assertTrue(facade.contains("private static <T> Consumer<T> custom(Consumer<T> set, Supplier<T> get)"),
            "the page-value builder stays in the facade");
        assertTrue(facade.contains("private static <T> Consumer<T> customPath(Consumer<T> set, Supplier<T> get)"),
            "the PT-value builder stays in the facade");
        assertTrue(facade.contains("public enum RtDebugView"), "the debug-view enum stays in the facade");
        assertTrue(facade.contains("private static OptionBuilder resetListButton("),
            "the reset-list button builder stays in the facade");
        assertTrue(facade.contains("resetAllDefaultsButton("), "the reset-all button builder stays in the facade");
        assertTrue(facade.contains("requestValueRefresh();"),
            "the builders still request a refresh through the facade delegate");
    }

    @Test
    void theRefreshFlagRoundTripsThroughTheFacade() {
        VulkanSodiumConfig.consumeValueRefreshRequest();
        assertFalse(VulkanSodiumConfig.consumeValueRefreshRequest(), "no refresh is pending after a consume");
        VulkanSodiumConfig.requestValueRefresh();
        assertTrue(VulkanSodiumConfig.consumeValueRefreshRequest(), "the facade request reaches the owner's flag");
        assertFalse(VulkanSodiumConfig.consumeValueRefreshRequest(), "the consume clears the owner's flag");
    }

    private static String read(Path path) throws IOException {
        return Files.readString(path, StandardCharsets.UTF_8).replace("\r\n", "\n");
    }
}