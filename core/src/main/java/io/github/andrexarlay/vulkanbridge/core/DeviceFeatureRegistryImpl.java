package io.github.andrexarlay.vulkanbridge.core;

import io.github.andrexarlay.vulkanbridge.api.DeviceFeatureRegistry;
import io.github.andrexarlay.vulkanbridge.api.DeviceRequirement;
import io.github.andrexarlay.vulkanbridge.api.LateRequirementProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Thread-safe registry implementation. Public only so unit tests can create isolated instances. */
public final class DeviceFeatureRegistryImpl implements DeviceFeatureRegistry {
    private static final Logger LOGGER = LoggerFactory.getLogger("BedrayCore/Registry");

    private final Object lock = new Object();
    private final Map<String, DeviceRequirement> active = new LinkedHashMap<>();
    private final List<String> skipped = new ArrayList<>();
    private boolean frozen;

    private final Object runMonitor = new Object();
    private final Map<String, LateRequirementProvider> lateProviders = new LinkedHashMap<>();
    private boolean lateProvidersRan;

    private final Map<String, java.util.Set<String>> instanceExtRequests = new LinkedHashMap<>();
    private java.util.Set<String> enabledInstanceExt = java.util.Set.of();
    private boolean instanceExtSealed;

    @Override
    public void register(DeviceRequirement requirement) {
        if (!requirement.optional()) {
            // STUB: required groups need a second hook (VulkanFeatureSets.requiredFeatureSets); only optional sets are wired.
            throw new IllegalArgumentException("DeviceRequirement '" + requirement.id()
                + "': optional=false is not implemented yet (STUB). Register it as optional=true.");
        }
        boolean holds = requirement.conditionHolds();
        synchronized (lock) {
            if (frozen) {
                throw new IllegalStateException("Cannot register DeviceRequirement '" + requirement.id()
                    + "': the Vulkan device is already being created and the registry is frozen. Register from your mod constructor"
                    + " (before the render thread creates the device).");
            }
            if (active.containsKey(requirement.id()) || skipped.contains(requirement.id())) {
                throw new IllegalStateException("DeviceRequirement id '" + requirement.id() + "' is already registered");
            }
            if (!holds) {
                skipped.add(requirement.id());
                LOGGER.info("{} requirement_skipped id={} reason=condition_false", BridgeCore.LOG_PREFIX, requirement.id());
                return;
            }
            active.put(requirement.id(), requirement);
        }
        LOGGER.info("{} requirement_registered id={} ext={} features={} instApi=0x{}", BridgeCore.LOG_PREFIX, requirement.id(),
            requirement.deviceExtensions().size(), requirement.features().size(), Integer.toHexString(requirement.minInstanceApiVersion()));
    }

    @Override
    public void requestInstanceExtensions(String id, java.util.Set<String> extensions) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("instance extension request id must be non-blank");
        }
        if (extensions == null || extensions.isEmpty()) {
            throw new IllegalArgumentException("instance extension request '" + id + "' has no extensions");
        }
        for (String e : extensions) {
            if (e == null || !e.startsWith("VK_")) {
                throw new IllegalArgumentException("instance extension request '" + id + "': invalid extension name '" + e + "'");
            }
        }
        synchronized (lock) {
            if (instanceExtSealed) {
                throw new IllegalStateException("Cannot request instance extensions '" + id
                    + "': the VkInstance is already being created. Register from a late provider or earlier.");
            }
            if (instanceExtRequests.putIfAbsent(id, java.util.Set.copyOf(extensions)) != null) {
                throw new IllegalStateException("Instance extension request id '" + id + "' is already registered");
            }
        }
        LOGGER.info("{} instance_extensions_requested id={} names={}", BridgeCore.LOG_PREFIX, id, new java.util.TreeSet<>(extensions));
    }

    @Override
    public java.util.Set<String> requestedInstanceExtensions() {
        synchronized (lock) {
            java.util.Set<String> all = new java.util.TreeSet<>();
            instanceExtRequests.values().forEach(all::addAll);
            return java.util.Collections.unmodifiableSet(all);
        }
    }

    @Override
    public java.util.Set<String> enabledInstanceExtensions() {
        synchronized (lock) {
            return enabledInstanceExt;
        }
    }

    @Override
    public void sealInstanceExtensions() {
        synchronized (lock) {
            instanceExtSealed = true;
        }
    }

    @Override
    public void recordEnabledInstanceExtensions(java.util.Set<String> enabled) {
        synchronized (lock) {
            enabledInstanceExt = java.util.Collections.unmodifiableSet(new java.util.TreeSet<>(enabled));
        }
    }

    @Override
    public void addLateProvider(String id, LateRequirementProvider provider) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("late provider id must be non-blank");
        }
        if (provider == null) {
            throw new IllegalArgumentException("late provider '" + id + "' is null");
        }
        synchronized (lock) {
            if (lateProvidersRan) {
                throw new IllegalStateException("Cannot add late provider '" + id
                    + "': late providers already ran (the Vulkan device is being created). Register earlier.");
            }
            if (lateProviders.putIfAbsent(id, provider) != null) {
                throw new IllegalStateException("Late provider id '" + id + "' is already registered");
            }
        }
        LOGGER.info("{} late_provider_added id={}", BridgeCore.LOG_PREFIX, id);
    }

    @Override
    public void runLateProviders() {
        synchronized (runMonitor) {
            List<Map.Entry<String, LateRequirementProvider>> toRun;
            synchronized (lock) {
                if (lateProvidersRan) {
                    return;
                }
                lateProvidersRan = true;
                toRun = new ArrayList<>(lateProviders.entrySet());
            }
            for (Map.Entry<String, LateRequirementProvider> e : toRun) {
                LOGGER.info("{} late_provider_run id={}", BridgeCore.LOG_PREFIX, e.getKey());
                try {
                    e.getValue().provide(this);
                } catch (RuntimeException ex) {
                    throw new IllegalStateException("Late requirement provider '" + e.getKey() + "' failed: " + ex, ex);
                }
            }
        }
    }

    @Override
    public List<DeviceRequirement> snapshot() {
        synchronized (lock) {
            return List.copyOf(active.values());
        }
    }

    @Override
    public int requestedInstanceApiVersion() {
        int max = 0;
        for (DeviceRequirement r : snapshot()) {
            max = Math.max(max, r.minInstanceApiVersion());
        }
        return max;
    }

    @Override
    public void freeze() {
        runLateProviders();
        int count;
        synchronized (lock) {
            if (frozen) {
                return;
            }
            frozen = true;
            count = active.size();
        }
        LOGGER.info("{} registry_frozen requirements={} instance_api=0x{}", BridgeCore.LOG_PREFIX, count,
            Integer.toHexString(requestedInstanceApiVersion()));
    }

    @Override
    public boolean isFrozen() {
        synchronized (lock) {
            return frozen;
        }
    }
}
