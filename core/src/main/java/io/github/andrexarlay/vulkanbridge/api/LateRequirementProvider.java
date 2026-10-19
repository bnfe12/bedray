package io.github.andrexarlay.vulkanbridge.api;

/**
 * Callback that registers device requirements late: the core calls it once, on the thread that first needs the
 * requirement list (the VkInstance creation hook or the optional-feature-set hook, whichever comes first), BEFORE the
 * registry is frozen and before any requirement is applied to the engine.
 * <p>
 * Use it when a requirement cannot be built in the mod constructor, for example because it needs the game directory
 * (not available in a constructor, known pitfall) or a value that is only known after all mods are constructed.
 * Register through {@link DeviceFeatureRegistry#addLateProvider(String, LateRequirementProvider)}.
 * <p>
 * The callback must only call {@link DeviceFeatureRegistry#register}; it must not touch the Vulkan device (it does not
 * exist yet) and must not call {@code Minecraft.getInstance()} (the Minecraft instance may not exist yet either).
 * An exception thrown here is NOT swallowed: it is rethrown as {@link IllegalStateException} naming the provider id.
 */
@Experimental
@FunctionalInterface
public interface LateRequirementProvider {
    void provide(DeviceFeatureRegistry registry);
}
