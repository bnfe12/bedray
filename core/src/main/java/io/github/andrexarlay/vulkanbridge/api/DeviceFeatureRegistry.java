package io.github.andrexarlay.vulkanbridge.api;

import java.util.List;

/**
 * Collects Vulkan device/instance requirements BEFORE the game creates its VkInstance/VkDevice.
 * Obtain via {@link VulkanBridge#deviceFeatures()}. Thread-safe.
 * <p>
 * Lifecycle: mods register in their constructor (or any time before the device is created); the core freezes the
 * registry when the engine asks for its optional feature sets. After that {@link #register} throws.
 * Requirements that cannot be built in the constructor go through {@link #addLateProvider}.
 */
@Experimental
public interface DeviceFeatureRegistry {
    /**
     * Adds a requirement. The {@code condition} is evaluated now; a requirement whose condition is {@code false}
     * is logged and not added.
     *
     * @throws IllegalStateException    when the registry is frozen (the device is already being created) or the id is taken
     * @throws IllegalArgumentException for {@code optional == false}: STUB, required groups are not implemented
     *                                  (the engine hook only covers optional feature sets)
     */
    void register(DeviceRequirement requirement);

    /** Active (condition held) requirements in registration order. Immutable copy. */
    List<DeviceRequirement> snapshot();

    /**
     * Asks for VkInstance extensions (since API 0.3.1). The engine creates a Vulkan 1.2 instance with only the SDL surface
     * extensions (and debug-utils when needed); a middleware such as NGX may demand more
     * ({@code VK_KHR_get_physical_device_properties2}, ...). the core adds every requested name that the loader reports in
     * {@code vkEnumerateInstanceExtensionProperties}; a name that is not available is logged
     * ({@code instance_extension_unavailable}) and NOT enabled (no fake enabling). Which names were actually enabled is
     * answered by {@link #enabledInstanceExtensions()}.
     *
     * @param id unique non-blank name used in logs
     * @throws IllegalStateException when the instance is already created (extensions sealed) or the id is taken
     */
    void requestInstanceExtensions(String id, java.util.Set<String> extensions);

    /** Union of all requested instance extension names (what was asked, not what was enabled). Immutable copy. */
    java.util.Set<String> requestedInstanceExtensions();

    /**
     * Instance extensions the core really added to the VkInstance create info. Empty until the instance is created.
     * Immutable copy.
     */
    java.util.Set<String> enabledInstanceExtensions();

    /** Called by the instance hook: closes {@link #requestInstanceExtensions} (the instance is being created). Idempotent. */
    void sealInstanceExtensions();

    /** Records which requested instance extensions were enabled; called by the instance hook, mods do not call it. */
    void recordEnabledInstanceExtensions(java.util.Set<String> enabled);

    /** Maximum {@code minInstanceApiVersion} of the active requirements, 0 when none asks for one. */
    int requestedInstanceApiVersion();

    /**
     * Registers a provider that is run once, before the registry is frozen and before the instance API version and the
     * requirements are read by the engine hooks. For requirements that cannot be built in the mod constructor.
     *
     * @param id unique non-blank name used in logs and in the error message when the provider throws
     * @throws IllegalStateException when late providers already ran (too late) or the id is taken
     */
    void addLateProvider(String id, LateRequirementProvider provider);

    /**
     * Runs the late providers, once. Idempotent and thread-safe (a second caller waits until the first has finished).
     * Called by the core hooks; mods normally do not call it. A throwing provider aborts with
     * {@link IllegalStateException}; the providers are then considered done (no retry).
     */
    void runLateProviders();

    /** Runs {@link #runLateProviders()}, then closes registration. Idempotent; logs once. */
    void freeze();

    boolean isFrozen();
}
