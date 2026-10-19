package io.github.andrexarlay.vulkanbridge.api;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * A group of Vulkan device/instance requirements that is enabled together (all-or-nothing): if the physical device
 * does not support every extension and feature of the group, the engine drops the whole group and logs it.
 * Several small groups are better than one big one when parts are "nice to have".
 *
 * @param id                   unique, human-readable; it is also the name the engine logs ("Enabling optional FeatureSet [id]")
 * @param deviceExtensions     device extension names, e.g. {@code VK_KHR_ray_query}
 * @param features             feature bits to enable
 * @param minInstanceApiVersion minimum VkInstance apiVersion (e.g. {@code VK13.VK_API_VERSION_1_3}); 0 = no requirement
 * @param optional             must be {@code true} for now: required groups are not implemented (STUB, see {@link DeviceFeatureRegistry#register})
 * @param condition            evaluated once at registration; {@code false} = the requirement is skipped (e.g. a native library is missing). {@code null} = always.
 */
@Experimental
public record DeviceRequirement(
    String id,
    Set<String> deviceExtensions,
    List<FeatureRequest> features,
    int minInstanceApiVersion,
    boolean optional,
    Supplier<Boolean> condition
) {
    public DeviceRequirement {
        Objects.requireNonNull(id, "id");
        if (id.isBlank()) {
            throw new IllegalArgumentException("DeviceRequirement id must not be blank");
        }
        deviceExtensions = deviceExtensions == null ? Set.of() : Set.copyOf(deviceExtensions);
        features = features == null ? List.of() : List.copyOf(features);
        if (minInstanceApiVersion < 0) {
            throw new IllegalArgumentException("minInstanceApiVersion must be >= 0 (0 = no requirement)");
        }
        if (condition == null) {
            condition = () -> Boolean.TRUE;
        }
    }

    /** Evaluates {@link #condition}; {@code null} result counts as {@code false}. */
    public boolean conditionHolds() {
        return Boolean.TRUE.equals(condition.get());
    }
}
