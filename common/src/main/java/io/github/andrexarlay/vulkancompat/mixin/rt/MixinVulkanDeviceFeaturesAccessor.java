package io.github.andrexarlay.vulkancompat.mixin.rt;

import com.mojang.renderpearl.backend.vulkan.init.FeatureSet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * {@code VulkanDevice.enabledFeatures} (javap -p, 26.3 client jar: private final FeatureSet enabledFeatures): the feature
 * set the logical device was actually created with. This, not a physical-device support query, is what the Bedray
 * core may be told is enabled.
 */
@Mixin(targets = "com.mojang.renderpearl.backend.vulkan.VulkanDevice", remap = false)
public interface MixinVulkanDeviceFeaturesAccessor {
    @Accessor("enabledFeatures")
    FeatureSet vkb$enabledFeatures();
}
