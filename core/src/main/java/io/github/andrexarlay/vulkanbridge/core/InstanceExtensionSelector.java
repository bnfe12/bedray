package io.github.andrexarlay.vulkanbridge.core;

import io.github.andrexarlay.vulkanbridge.api.DeviceFeatureRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.TreeSet;

/**
 * Decides which requested VkInstance extensions are added to the engine's create info. Pure logic (no engine classes),
 * called by {@code MixinVulkanInstance}. A requested name the loader does not report is NOT enabled and is logged.
 */
public final class InstanceExtensionSelector {
    private static final Logger LOGGER = LoggerFactory.getLogger("BedrayCore/InstanceExt");

    private InstanceExtensionSelector() {}

    /**
     * Adds every requested extension that is in {@code available} and not yet in {@code engineEnabled} to
     * {@code engineEnabled}, records the result in the registry and logs. {@code engineEnabled} is mutated.
     *
     * @return the names the core added (not including names the engine had already enabled)
     */
    public static Set<String> apply(DeviceFeatureRegistry registry, Set<String> available, Set<String> engineEnabled) {
        registry.sealInstanceExtensions();
        Set<String> requested = registry.requestedInstanceExtensions();
        Set<String> added = new TreeSet<>();
        Set<String> alreadyByEngine = new TreeSet<>();
        for (String name : requested) {
            if (engineEnabled.contains(name)) {
                alreadyByEngine.add(name);
            } else if (available.contains(name)) {
                engineEnabled.add(name);
                added.add(name);
            } else {
                LOGGER.warn("{} instance_extension_unavailable name={} (not reported by vkEnumerateInstanceExtensionProperties, not enabled)",
                    BridgeCore.LOG_PREFIX, name);
            }
        }
        Set<String> enabled = new TreeSet<>(added);
        enabled.addAll(alreadyByEngine);
        registry.recordEnabledInstanceExtensions(enabled);
        if (!requested.isEmpty()) {
            LOGGER.info("{} instance_extensions_enabled added={} already_enabled_by_engine={} requested={}",
                BridgeCore.LOG_PREFIX, added, alreadyByEngine, requested);
        }
        return added;
    }
}
