package io.github.andrexarlay.vulkanbridge.core;

import com.mojang.renderpearl.backend.vulkan.VulkanFeatureSets;
import com.mojang.renderpearl.backend.vulkan.init.FeatureSet;
import com.mojang.renderpearl.backend.vulkan.init.VulkanFeature;
import com.mojang.renderpearl.backend.vulkan.init.VulkanPNextStruct;
import io.github.andrexarlay.vulkanbridge.api.DeviceRequirement;
import io.github.andrexarlay.vulkanbridge.api.FeatureRequest;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Turns {@link DeviceRequirement}s into the engine {@link FeatureSet}s. Same engine classes the old hard-coded mixin used:
 * FeatureSet(String, Set, Set), VulkanFeature(VulkanPNextStruct, String), VulkanPNextStruct(Class),
 * VulkanFeatureSets.VK10/VK11/VK12_FEATURES_STRUCT (decompiled copy of the game classes).
 */
public final class RequirementApplier {
    private RequirementApplier() {}

    public static Set<FeatureSet> toFeatureSets(Collection<DeviceRequirement> requirements) {
        Set<FeatureSet> sets = new LinkedHashSet<>();
        for (DeviceRequirement r : requirements) {
            sets.add(toFeatureSet(r));
        }
        return sets;
    }

    public static FeatureSet toFeatureSet(DeviceRequirement r) {
        Map<String, VulkanPNextStruct> structs = new HashMap<>();
        Set<VulkanFeature> features = new LinkedHashSet<>();
        for (FeatureRequest f : r.features()) {
            VulkanPNextStruct struct = structs.computeIfAbsent(f.struct(), s -> resolveStruct(r.id(), s));
            features.add(new VulkanFeature(struct, f.field()));
        }
        return new FeatureSet(r.id(), r.deviceExtensions(), features);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    static VulkanPNextStruct resolveStruct(String requirementId, String struct) {
        switch (struct) {
            case FeatureRequest.VK10:
                return VulkanFeatureSets.VK10_FEATURES_STRUCT;
            case FeatureRequest.VK11:
                return VulkanFeatureSets.VK11_FEATURES_STRUCT;
            case FeatureRequest.VK12:
                return VulkanFeatureSets.VK12_FEATURES_STRUCT;
            default:
                break;
        }
        Class<?> cls;
        try {
            cls = Class.forName(struct, false, RequirementApplier.class.getClassLoader());
        } catch (ClassNotFoundException e) {
            throw new IllegalArgumentException("DeviceRequirement '" + requirementId + "': unknown feature struct '" + struct
                + "' (use VK10/VK11/VK12 or a fully qualified org.lwjgl.vulkan.* class)", e);
        }
        return new VulkanPNextStruct((Class) cls);
    }
}
