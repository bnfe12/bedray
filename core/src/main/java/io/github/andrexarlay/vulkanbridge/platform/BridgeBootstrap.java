package io.github.andrexarlay.vulkanbridge.platform;

import io.github.andrexarlay.vulkanbridge.autotest.AutotestHarness;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * All startup logic of the core. A loader module's entry points only call these two methods:
 * {@link #earlyInit} from the earliest point (Fabric preLaunch / NeoForge mod construction) and
 * {@link #clientInit} from the client initialiser (may be the same point on loaders that have only one).
 */
public final class BridgeBootstrap {
    private static final Logger LOGGER = LoggerFactory.getLogger("BedrayCore/Bootstrap");

    private BridgeBootstrap() {}

    /** Installs the loader seam, logs it and fires the "before VkDevice" hooks. Touches no Minecraft class. */
    public static void earlyInit(LoaderAccess access) {
        Platforms.install(access);
        LoaderAccess loader = Platforms.get();
        LogPaths.setGameDir(loader.gameDir());
        LOGGER.info("[VKB-CORE] loader={} loaderVersion={} gameDir={} configDir={} dev={}",
                loader.loaderName(), loader.loaderVersion(), LogPaths.show(loader.gameDir()), LogPaths.show(loader.configDir()), loader.isDevEnvironment());
        LOGGER.info("[VKB-CORE] bedray_core_init nativesDir={} legacyFallbacks=read-only", LogPaths.show(io.github.andrexarlay.vulkanbridge.api.VulkanBridge.libraries().nativesDirectory()));
        prepareNatives();
        // Also constructs the GL bridge, which registers its BEFORE_GUI/FRAME_END frame-phase listeners (no Minecraft class is loaded).
        io.github.andrexarlay.vulkanbridge.glbridge.GlBridgeImpl.INSTANCE.earlyInit(
                io.github.andrexarlay.vulkanbridge.api.VulkanBridge.deviceFeatures());
        EarlyHooks.INSTANCE.fire();
    }

    /**
     * Creates bedray/natives (+ streamline/ and README.txt) and extracts the DLLs bundled in the jar (AMD FidelityFX, Intel XeSS).
     * A failure is logged with its cause and the game goes on: the folders are a convenience, a missing library is reported later by
     * the loader that needs it.
     */
    private static void prepareNatives() {
        java.nio.file.Path dir = io.github.andrexarlay.vulkanbridge.api.VulkanBridge.libraries().nativesDirectory();
        try {
            var r = io.github.andrexarlay.vulkanbridge.core.NativeFolders.ensureLayout(dir);
            LOGGER.info("[VKB-NATIVES] layout dir={} created={} readme_written={}", LogPaths.show(dir), r.createdDirs(), r.readmeWritten());
        } catch (java.io.IOException e) {
            LOGGER.warn("[VKB-NATIVES] cannot prepare the natives folders under {}", LogPaths.show(dir), e);
        }
        io.github.andrexarlay.vulkanbridge.core.NativeBundle.extractAll(dir);
    }

    /** Starts the autotest harness when, and only when, -Dvkbridge.autotest=&lt;scenario&gt; is set. */
    public static void clientInit() {
        String scenario = System.getProperty(AutotestHarness.PROPERTY);
        io.github.andrexarlay.vulkanbridge.autotest.AutotestScenarios.register(new io.github.andrexarlay.vulkanbridge.glbridge.GlBridgeScenario());
        io.github.andrexarlay.vulkanbridge.autotest.AutotestScenarios.register(new io.github.andrexarlay.vulkanbridge.glbridge.GlBridgeFbo0Scenario());
        io.github.andrexarlay.vulkanbridge.autotest.AutotestScenarios.register(new io.github.andrexarlay.vulkanbridge.glbridge.GlBridgeResizeScenario());
        io.github.andrexarlay.vulkanbridge.autotest.AutotestScenarios.register(new io.github.andrexarlay.vulkanbridge.glbridge.GlBridgePerfScenario());
        io.github.andrexarlay.vulkanbridge.autotest.AutotestScenarios.register(new io.github.andrexarlay.vulkanbridge.glbridge.GlBridgeAlphaScenario());
        io.github.andrexarlay.vulkanbridge.autotest.AutotestScenarios.register(new io.github.andrexarlay.vulkanbridge.glbridge.GlBridgeModScenario());
        io.github.andrexarlay.vulkanbridge.autotest.AutotestScenarios.register(new io.github.andrexarlay.vulkanbridge.glbridge.GlBridgeTwoScenario());
        io.github.andrexarlay.vulkanbridge.autotest.AutotestScenarios.register(new io.github.andrexarlay.vulkanbridge.autotest.TestWorldScenario());
        if (Platforms.get().isModLoaded("iris") && Platforms.get().isModLoaded("sodium")) {
            // The scenario class refers to Iris/Sodium types only inside method bodies; registered only when both exist.
            io.github.andrexarlay.vulkanbridge.autotest.AutotestScenarios.register(new io.github.andrexarlay.vulkanbridge.compat.iris.IrisGuiScenario());
        }
        if (scenario != null && !scenario.isBlank()) {
            AutotestHarness.install(scenario.trim());
        }
    }
}
