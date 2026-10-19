package io.github.andrexarlay.vulkanbridge.compat.iris;

import io.github.andrexarlay.vulkanbridge.core.VulkanBackendDetector;
import io.github.andrexarlay.vulkanbridge.mixin.iris.IrisAccessor;
import io.github.andrexarlay.vulkanbridge.platform.Platforms;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.UpdateChecker;
import net.irisshaders.iris.config.IrisConfig;
import net.irisshaders.iris.gui.screen.ShaderPackScreen;
import net.irisshaders.iris.platform.IrisPlatformHelpers;
import net.minecraft.client.gui.screens.Screen;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Iris GUI compatibility on the native Vulkan backend (Iris 1.11.7+mc26.3).
 * <p>
 * Facts (javap / decompilation of iris-neoforge-1.11.7+mc26.3, <local decompiled sources>):
 * <ul>
 *   <li>{@code IrisMixinPlugin.usingVulkan} is read from options.txt; when true every non-"VKOnly" Iris mixin is
 *       skipped, so {@code Iris.onEarlyInitialize()} never runs: {@code Iris.irisConfig} and
 *       {@code Iris.updateChecker} stay null and {@code new ShaderPackScreen(..)} throws a NullPointerException.</li>
 *   <li>Instead Iris runs {@code IrisVKOnly.run()} (registers the shader pack key) and shows
 *       {@code ShaderPackScreenPlaceholder}: "Iris cannot run when using Vulkan ... switch to OpenGL", whose
 *       "Switch" button sets the backend to OpenGL and stops the game.</li>
 * </ul>
 * What the core does (and nothing more):
 * <ol>
 *   <li>{@link #onIrisVulkanMode()} (from {@code IrisVKOnly.run} RETURN) creates the GL-free part of the state that
 *       {@code Iris.onEarlyInitialize()} would create: {@code IrisConfig} (same files, same {@code initialize()}),
 *       {@code UpdateChecker} (no network check) and the version string; and the shaderpacks directory.</li>
 *   <li>{@link #replacePlaceholder} swaps the placeholder for the real {@link ShaderPackScreen} at the two places
 *       Iris opens it (Sodium "Shader Packs" page, Iris key).</li>
 *   <li>{@link #onReload} skips {@code Iris.reload()} (GL pipeline creation) because the Iris pipeline does not exist
 *       on Vulkan; the selection itself is already saved to iris.properties by {@code ShaderPackScreen}.</li>
 *   <li>{@link #skipGlState(String)} lets the Iris widgets skip their direct {@code GlStateManager} calls on Vulkan (there
 *       is no GL context on the render thread; GUI blending comes from the GUI render pipelines).</li>
 * </ol>
 * STUB: Iris shader packs are NOT rendered on Vulkan. The screen selects and stores a pack; a consumer mod (for
 * example vulkan-rtx) must read the selection. Pack option menus need {@code Iris.getCurrentPack()}, which stays empty.
 */
public final class IrisCompat {
    private static final Logger LOGGER = LoggerFactory.getLogger("BedrayCore/IrisCompat");
    private static final String TAG = "[BedrayCore/IrisCompat]";

    private static volatile boolean vulkanMode;
    private static volatile boolean ready;
    private static volatile boolean glStateSkipLogged;
    private static volatile int placeholderReplacements;
    private static volatile int reloadsSkipped;

    private IrisCompat() {}

    /** True when Iris runs in its Vulkan-only mode and the core initialised the state the screens need. */
    public static boolean isReady() {
        return ready;
    }

    /** True once Iris entered its Vulkan-only mode ({@code IrisVKOnly.run()} ran). */
    public static boolean isIrisVulkanMode() {
        return vulkanMode;
    }

    public static int placeholderReplacements() {
        return placeholderReplacements;
    }

    public static int reloadsSkipped() {
        return reloadsSkipped;
    }

    /** Called from {@code IrisVKOnly.run()} RETURN, i.e. only when Iris itself decided it runs on Vulkan. */
    public static void onIrisVulkanMode() {
        vulkanMode = true;
        IrisPlatformHelpers helpers = IrisPlatformHelpers.getInstance();
        String version = helpers.getVersion();
        IrisAccessor.vkb$setIrisVersion(version);

        Path shaderpacks = Iris.getShaderpacksDirectory();
        try {
            Files.createDirectories(shaderpacks);
        } catch (IOException e) {
            // Same handling as Iris.onEarlyInitialize: the screen still opens, the list is just empty.
            LOGGER.warn("{} event=shaderpacks_dir_failed path={}", TAG, shaderpacks, e);
        }

        if (Iris.getIrisConfig() == null) {
            Path configDir = helpers.getConfigDir();
            IrisConfig config = new IrisConfig(configDir.resolve("iris.properties"), configDir.resolve("iris-excluded.json"));
            try {
                config.initialize();
            } catch (IOException e) {
                // Same handling as Iris.onEarlyInitialize ("default values will be used instead").
                LOGGER.error("{} event=iris_config_init_failed defaults=true", TAG, e);
            }
            IrisAccessor.vkb$setIrisConfig(config);
        }
        if (Iris.getUpdateChecker() == null) {
            // No checkForUpdates(): no network request from the core; getUpdateMessage() is then empty.
            IrisAccessor.vkb$setUpdateChecker(new UpdateChecker(version));
        }
        ready = true;

        IrisConfig config = Iris.getIrisConfig();
        boolean rtxLoaded = Platforms.get().isModLoaded("vulkan_rtx");
        LOGGER.info("{} event=init irisVersion={} irisVulkanMode=true shadersEnabled={} pack={} shaderpacksDir={} vulkanRtxLoaded={}",
                TAG, version, config.areShadersEnabled(), config.getShaderPackName().orElse("<none>"), shaderpacks, rtxLoaded);
        if (rtxLoaded) {
            LOGGER.warn("{} event=possible_duplicate vulkan_rtx is loaded; its own Iris GUI mixins (mixin/iris/*) target the same Iris methods and must be removed there",
                    TAG);
        }
    }

    /**
     * Returns the screen Iris should open instead of its "switch to OpenGL" placeholder. Without the state from
     * {@link #onIrisVulkanMode()} the placeholder is kept (and an error logged): ShaderPackScreen would throw.
     */
    public static Screen replacePlaceholder(Screen placeholder, Screen parent, String source) {
        if (!ready) {
            LOGGER.error("{} event=placeholder_kept source={} reason=iris_state_not_initialised (IrisVKOnly.run hook did not run)",
                    TAG, source);
            return placeholder;
        }
        placeholderReplacements++;
        LOGGER.info("{} event=placeholder_replaced source={} parent={} count={} dialog_suppressed=true",
                TAG, source, parent == null ? "null" : parent.getClass().getName(), placeholderReplacements);
        return new ShaderPackScreen(parent);
    }

    /** {@code Iris.reload()} HEAD. Returns true when the call must be cancelled (Iris on Vulkan has no pipeline). */
    public static boolean onReload() {
        if (!vulkanMode) {
            return false;
        }
        reloadsSkipped++;
        IrisConfig config = Iris.getIrisConfig();
        LOGGER.warn("{} event=reload_skipped reason=iris_pipeline_unavailable_on_vulkan shadersEnabled={} pack={} count={} (selection is saved to iris.properties; nothing is rendered by Iris)",
                TAG, config == null ? "null" : config.areShadersEnabled(),
                config == null ? "null" : config.getShaderPackName().orElse("<none>"), reloadsSkipped);
        return true;
    }

    /**
     * True when the Iris widgets must skip their direct GlStateManager calls: the engine backend is resolved and is
     * Vulkan (no GL context on the render thread). On OpenGL the calls go through unchanged.
     */
    public static boolean skipGlState(String site) {
        boolean skip = VulkanBackendDetector.isBackendResolved() && VulkanBackendDetector.isVulkanActive();
        if (skip && !glStateSkipLogged) {
            glStateSkipLogged = true;
            LOGGER.info("{} event=gl_state_call_skipped site={} reason=no_gl_context_on_vulkan (logged once)", TAG, site);
        }
        return skip;
    }
}
