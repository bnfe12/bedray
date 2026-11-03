package io.github.andrexarlay.vulkancompat.rt.slang;

import com.mojang.renderpearl.backend.vulkan.init.FeatureSet;
import com.mojang.renderpearl.backend.vulkan.init.VulkanFeature;
import com.mojang.renderpearl.backend.vulkan.init.VulkanPNextStruct;
import io.github.andrexarlay.vulkanbridge.core.RequirementApplier;
import io.github.andrexarlay.vulkancompat.rt.core.BedrayCoreLayouts;
import io.github.andrexarlay.vulkancompat.rtx.raytracing.RtRayPipelineDeviceRequirements;
import org.junit.jupiter.api.Test;
import org.lwjgl.vulkan.VkPhysicalDeviceRayQueryFeaturesKHR;

import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class SlangDeviceCapabilitiesTest {
    private static final int BASE_PIPELINE = BedrayCoreLayouts.CAP_RAY_QUERY
        | BedrayCoreLayouts.CAP_ACCELERATION_STRUCTURE | BedrayCoreLayouts.CAP_BUFFER_DEVICE_ADDRESS
        | BedrayCoreLayouts.CAP_SHADER_INT64 | BedrayCoreLayouts.CAP_RAY_TRACING_PIPELINE;

    @Test void absentEnabledFeaturesNeverInferHardwareSupportOrRequestedOptions() {
        assertEquals(0, SlangDeviceCapabilities.enabledFeaturesMask(null));
        assertEquals(0, SlangDeviceCapabilities.enabledFeaturesMask(new FeatureSet("none", Set.of(), Set.of())));
        var supported = RequirementApplier.toFeatureSet(RtRayPipelineDeviceRequirements.serRequirement());
        assertEquals(0, SlangDeviceCapabilities.enabledFeaturesMask(
            new FeatureSet("extensions without enabled feature bits", supported.extensions(), Set.of())));
    }

    @Test void droppedSerGroupKeepsOnlyTheOrdinaryPipelineCapabilities() {
        var enabled = RequirementApplier.toFeatureSet(RtRayPipelineDeviceRequirements.raygenRequirement());
        assertEquals(BASE_PIPELINE, SlangDeviceCapabilities.enabledFeaturesMask(enabled));
    }

    @Test void enabledNvGroupIncludesItsTypedShaderCapability() {
        var enabled = RequirementApplier.toFeatureSet(RtRayPipelineDeviceRequirements.serRequirement());
        assertEquals(BASE_PIPELINE | BedrayCoreLayouts.CAP_INVOCATION_REORDER_NV,
            SlangDeviceCapabilities.enabledFeaturesMask(enabled));
    }

    @Test void featureBitsWithoutTheirEnabledExtensionsCannotAuthorizePipelineOrNvShaders() {
        var supported = RequirementApplier.toFeatureSet(RtRayPipelineDeviceRequirements.serRequirement());
        var withoutExtensions = new FeatureSet("missing extensions", Set.of(), supported.features());
        assertEquals(BASE_PIPELINE & ~BedrayCoreLayouts.CAP_RAY_TRACING_PIPELINE,
            SlangDeviceCapabilities.enabledFeaturesMask(withoutExtensions));
        var withoutNvExtension = new FeatureSet("ordinary pipeline only",
            supported.extensions().stream().filter(name -> !name.equals("VK_NV_ray_tracing_invocation_reorder"))
                .collect(Collectors.toSet()), supported.features());
        assertEquals(BASE_PIPELINE, SlangDeviceCapabilities.enabledFeaturesMask(withoutNvExtension));
    }

    @Test void equalFeatureMemberNamesFromAnotherStructCannotImpersonateNvOrRayPipeline() {
        var differentStruct = new VulkanPNextStruct(VkPhysicalDeviceRayQueryFeaturesKHR.class);
        var enabled = new FeatureSet("different shader feature model",
            Set.of("VK_KHR_ray_tracing_pipeline", "VK_NV_ray_tracing_invocation_reorder"),
            Set.of(new VulkanFeature(differentStruct, "rayTracingPipeline", 0),
                new VulkanFeature(differentStruct, "rayTracingInvocationReorder", 0)));
        assertEquals(0, SlangDeviceCapabilities.enabledFeaturesMask(enabled));
    }
}
