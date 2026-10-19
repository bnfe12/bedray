package io.github.andrexarlay.vulkanbridge.api;

import java.util.Objects;

/**
 * One Vulkan device feature bit to enable: a boolean field of a feature struct, e.g. {@code rayQuery} of
 * {@code VkPhysicalDeviceRayQueryFeaturesKHR}.
 * <p>
 * {@code struct} is either an alias of a struct the engine already chains ({@code "VK10"} = VkPhysicalDeviceFeatures2,
 * {@code "VK11"}, {@code "VK12"}; use these for core features, a second separate struct for the same core feature
 * violates VUID-VkDeviceCreateInfo-pNext-02829) or a fully qualified LWJGL class name such as
 * {@code "org.lwjgl.vulkan.VkPhysicalDeviceRayQueryFeaturesKHR"}.
 */
@Experimental
public record FeatureRequest(String struct, String field) {
    public static final String VK10 = "VK10";
    public static final String VK11 = "VK11";
    public static final String VK12 = "VK12";

    public FeatureRequest {
        Objects.requireNonNull(struct, "struct");
        Objects.requireNonNull(field, "field");
        if (struct.isBlank() || field.isBlank()) {
            throw new IllegalArgumentException("FeatureRequest struct/field must not be blank");
        }
    }

    /** Feature of the engine's chained core struct ({@link #VK10}, {@link #VK11} or {@link #VK12}). */
    public static FeatureRequest core(String alias, String field) {
        return new FeatureRequest(alias, field);
    }

    /** Feature of an extension struct, e.g. {@code of(VkPhysicalDeviceRayQueryFeaturesKHR.class, "rayQuery")}. */
    public static FeatureRequest of(Class<?> structClass, String field) {
        return new FeatureRequest(structClass.getName(), field);
    }
}
