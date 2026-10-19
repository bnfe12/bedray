package io.github.andrexarlay.vulkancompat.fabric;

import io.github.andrexarlay.vulkanbridge.platform.BridgeBootstrap;
import net.fabricmc.api.ClientModInitializer;

/**
 * {@code client} entry point (javap: ClientModInitializer.onInitializeClient()). Starts the Bedray Core autotest harness when,
 * and only when, -Dvkbridge.autotest=&lt;scenario&gt; is set (same rule as the NeoForge entry point). Runs after preLaunch,
 * so the platform seam is installed.
 */
public final class BedrayFabricClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        BridgeBootstrap.clientInit();
    }
}
