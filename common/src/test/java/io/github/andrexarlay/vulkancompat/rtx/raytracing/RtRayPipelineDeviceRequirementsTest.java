package io.github.andrexarlay.vulkancompat.rtx.raytracing;

import io.github.andrexarlay.vulkanbridge.api.DeviceRequirement;
import io.github.andrexarlay.vulkanbridge.api.FeatureRequest;
import io.github.andrexarlay.vulkanbridge.core.DeviceFeatureRegistryImpl;
import io.github.andrexarlay.vulkanbridge.core.RequirementApplier;
import org.junit.jupiter.api.Test;
import org.lwjgl.vulkan.VkPhysicalDeviceRayTracingInvocationReorderFeaturesNV;

import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class RtRayPipelineDeviceRequirementsTest {
    @Test void defaultRunDoesNotRequestEitherExperimentalDeviceGroup() {
        var registry = new DeviceFeatureRegistryImpl();
        RtRayPipelineDeviceRequirements.register(registry, false, false);
        assertTrue(registry.snapshot().isEmpty());
    }

    @Test void raygenRequestKeepsSerOutOfTheDevice() {
        var registry = new DeviceFeatureRegistryImpl();
        RtRayPipelineDeviceRequirements.register(registry, true, false);
        assertEquals(1, registry.snapshot().size());
        assertEquals(RtRayPipelineDeviceRequirements.RAYGEN_ID, registry.snapshot().getFirst().id());
        assertFalse(registry.snapshot().getFirst().deviceExtensions().contains("VK_NV_ray_tracing_invocation_reorder"));
    }

    @Test void serAlsoRequestsAnIndependentOrdinaryRayPipelineFallback() {
        var registry = new DeviceFeatureRegistryImpl();
        RtRayPipelineDeviceRequirements.register(registry, false, true);
        assertEquals(2, registry.snapshot().size());
        var ordinary = registry.snapshot().getFirst();
        var ser = registry.snapshot().getLast();
        assertEquals(RtRayPipelineDeviceRequirements.RAYGEN_ID, ordinary.id());
        assertEquals(RtRayPipelineDeviceRequirements.SER_ID, ser.id());
        assertTrue(ordinary.optional());
        assertTrue(ser.optional());
        assertTrue(ser.deviceExtensions().containsAll(ordinary.deviceExtensions()));
        assertTrue(ser.features().containsAll(ordinary.features()));
        assertEquals(ordinary.features().size() + 1, ser.features().size());
    }

    @Test void everyOptionalGroupCarriesItsOwnRequiredFeatureAndExtensionChain() {
        for (DeviceRequirement group : new DeviceRequirement[]{
            RtRayPipelineDeviceRequirements.raygenRequirement(), RtRayPipelineDeviceRequirements.serRequirement()}) {
            assertTrue(group.deviceExtensions().containsAll(Set.of("VK_KHR_ray_tracing_pipeline",
                "VK_KHR_acceleration_structure", "VK_KHR_ray_query", "VK_KHR_buffer_device_address",
                "VK_KHR_deferred_host_operations")));
            assertTrue(group.features().stream().map(FeatureRequest::field).collect(Collectors.toSet()).containsAll(
                Set.of("rayTracingPipeline", "accelerationStructure", "rayQuery", "bufferDeviceAddress", "shaderInt64")));
            assertTrue(group.features().contains(FeatureRequest.core(FeatureRequest.VK12, "bufferDeviceAddress")));
            assertTrue(group.features().contains(FeatureRequest.core(FeatureRequest.VK10, "shaderInt64")));
            // Resolves every actual LWJGL struct/member through the same adapter as host device creation.
            var hostGroup = RequirementApplier.toFeatureSet(group);
            assertEquals(group.deviceExtensions(), hostGroup.extensions());
            assertEquals(group.features().size(), hostGroup.features().size());
        }
        assertTrue(RtRayPipelineDeviceRequirements.serRequirement().features().contains(FeatureRequest.of(
            VkPhysicalDeviceRayTracingInvocationReorderFeaturesNV.class, "rayTracingInvocationReorder")));
    }
}
