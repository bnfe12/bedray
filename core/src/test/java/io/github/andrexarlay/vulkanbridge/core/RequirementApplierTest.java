package io.github.andrexarlay.vulkanbridge.core;

import io.github.andrexarlay.vulkanbridge.api.DeviceRequirement;
import io.github.andrexarlay.vulkanbridge.api.FeatureRequest;
import org.junit.jupiter.api.Test;
import org.lwjgl.vulkan.VkPhysicalDeviceRayQueryFeaturesKHR;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** L2: DeviceRequirement -> engine FeatureSet conversion (engine classes come from libs/minecraft-26.3-client.jar). */
class RequirementApplierTest {
    @Test
    void convertsNameExtensionsAndFeatures() {
        var r = new DeviceRequirement("t.a", Set.of("VK_KHR_ray_query"),
            List.of(FeatureRequest.of(VkPhysicalDeviceRayQueryFeaturesKHR.class, "rayQuery"),
                FeatureRequest.core("VK12", "bufferDeviceAddress")),
            0x402000, true, null);
        var fs = RequirementApplier.toFeatureSet(r);
        assertEquals("t.a", fs.name());
        assertEquals(1, fs.extensions().size());
        assertEquals(2, fs.features().size());
    }

    @Test
    void unknownStructThrowsInsteadOfBeingIgnored() {
        var r = new DeviceRequirement("t.bad", null,
            List.of(new FeatureRequest("org.lwjgl.vulkan.NoSuchStruct", "x")), 0, true, null);
        var e = assertThrows(IllegalArgumentException.class, () -> RequirementApplier.toFeatureSet(r));
        assertTrue(e.getMessage().contains("t.bad"));
    }
}
