package io.github.andrexarlay.vulkancompat.rt.slang;

import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import com.mojang.renderpearl.backend.vulkan.init.FeatureSet;
import com.mojang.renderpearl.backend.vulkan.init.VulkanFeature;
import io.github.andrexarlay.vulkancompat.mixin.rt.MixinVulkanDeviceFeaturesAccessor;
import io.github.andrexarlay.vulkancompat.rt.core.BedrayCoreLayouts;

import java.util.Map;

/**
 * Capability mask of the features the logical device was really created with, read from
 * {@code VulkanDevice.enabledFeatures}. A physical-device support query is never substituted: the core and the AOT
 * manifests must not be told a feature is on when the game did not enable it.
 */
public final class SlangDeviceCapabilities {
    /** Vulkan feature member name -> core capability bit. Only features with a direct one-to-one meaning are mapped. */
    static final Map<String, Integer> FEATURE_BITS = Map.of(
        "rayQuery", BedrayCoreLayouts.CAP_RAY_QUERY,
        "accelerationStructure", BedrayCoreLayouts.CAP_ACCELERATION_STRUCTURE,
        "bufferDeviceAddress", BedrayCoreLayouts.CAP_BUFFER_DEVICE_ADDRESS,
        "synchronization2", BedrayCoreLayouts.CAP_SYNCHRONIZATION2,
        "timelineSemaphore", BedrayCoreLayouts.CAP_TIMELINE_SEMAPHORE,
        "dynamicRendering", BedrayCoreLayouts.CAP_DYNAMIC_RENDERING,
        "micromap", BedrayCoreLayouts.CAP_OPACITY_MICROMAP,
        "shaderInt64", BedrayCoreLayouts.CAP_SHADER_INT64);

    private SlangDeviceCapabilities() {
    }

    /** Bits of every mapped feature present in the device's enabled feature set; 0 when the set is unreadable. */
    public static int enabledMask(VulkanDevice device) {
        // Through Object: the accessor interface is added by the mixin at runtime, javac cannot see it on VulkanDevice.
        if (!((Object) device instanceof MixinVulkanDeviceFeaturesAccessor accessor)) {
            return 0;
        }
        FeatureSet enabled = accessor.vkb$enabledFeatures();
        if (enabled == null) {
            return 0;
        }
        int mask = 0;
        for (VulkanFeature feature : enabled.features()) {
            mask |= FEATURE_BITS.getOrDefault(feature.name(), 0);
        }
        return mask;
    }
}
