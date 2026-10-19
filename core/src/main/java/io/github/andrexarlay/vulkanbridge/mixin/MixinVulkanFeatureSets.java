package io.github.andrexarlay.vulkanbridge.mixin;

import com.mojang.renderpearl.backend.vulkan.VulkanFeatureSets;
import com.mojang.renderpearl.backend.vulkan.init.FeatureSet;
import io.github.andrexarlay.vulkanbridge.api.DeviceFeatureRegistry;
import io.github.andrexarlay.vulkanbridge.api.DeviceRequirement;
import io.github.andrexarlay.vulkanbridge.api.VulkanBridge;
import io.github.andrexarlay.vulkanbridge.core.RequirementApplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Mixin into {@link VulkanFeatureSets}: feeds every requirement of the public {@link DeviceFeatureRegistry}
 * (requirements of every mod) into the engine optional feature set discovery.
 * <p>
 * Without this injection RenderPearl creates the VkDevice with vanilla extensions only, leaving
 * {@code vkGetAccelerationStructureBuildSizesKHR} and ray query function pointers null in LWJGL's device capabilities.
 */
@Mixin(value = VulkanFeatureSets.class, remap = false)
public abstract class MixinVulkanFeatureSets {
    private static final Logger LOGGER = LoggerFactory.getLogger("BedrayCore/FeatureSets");

    /**
     * The ONLY injection into {@code optionalFeatureSets}: adds one engine FeatureSet per registry requirement and
     * freezes the registry. Requirements of other mods (including the RTX mod) come through the public registry.
     */
    @Inject(method = "optionalFeatureSets", at = @At("RETURN"), cancellable = true)
    private static void vkb$injectOptionalFeatureSets(CallbackInfoReturnable<Set<FeatureSet>> cir) {
        DeviceFeatureRegistry registry = VulkanBridge.deviceFeatures();
        registry.freeze(); // runs late providers first, then closes registration
        List<DeviceRequirement> requirements = registry.snapshot();
        LOGGER.info("[VKB-CORE] featuresets_hook entered requirements={}", requirements.size());

        Set<FeatureSet> updated = new HashSet<>(cir.getReturnValue());
        // Not swallowed: a failure here is a code/LWJGL mismatch, not a hardware condition (RequirementApplier throws with the requirement id).
        updated.addAll(RequirementApplier.toFeatureSets(requirements));
        cir.setReturnValue(Set.copyOf(updated));
    }
}
