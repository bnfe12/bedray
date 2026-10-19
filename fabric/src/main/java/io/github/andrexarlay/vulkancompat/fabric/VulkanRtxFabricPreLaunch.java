package io.github.andrexarlay.vulkancompat.fabric;

import io.github.andrexarlay.vulkanbridge.fabric.FabricAccess;
import io.github.andrexarlay.vulkanbridge.platform.BridgeBootstrap;
import io.github.andrexarlay.vulkancompat.RtxBootstrap;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;

/**
 * Fabric entry point of the Bedray mod: installs the loader seam of the bundled Bedray Core ({@link FabricAccess}), then makes
 * one call into the loader-neutral {@link RtxBootstrap}. {@code preLaunch} (javap on fabric-loader-0.19.5.jar:
 * {@code PreLaunchEntrypoint.onPreLaunch()}) runs before any Minecraft class is loaded, so the late device-requirement
 * providers are queued before the engine creates its VkInstance/VkDevice. The Core seam is installed first, in the same call.
 * The Sodium page is registered by Sodium itself through the {@code sodium:config_api_user} entry point in fabric.mod.json.
 */
public final class VulkanRtxFabricPreLaunch implements PreLaunchEntrypoint {
    @Override
    public void onPreLaunch() {
        BridgeBootstrap.earlyInit(new FabricAccess());
        RtxBootstrap.earlyInit();
    }
}
