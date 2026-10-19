package io.github.andrexarlay.vulkancompat.mixin.iris;

import io.github.andrexarlay.vulkancompat.mixin.VulkanRtxMixinPlugin;
import net.irisshaders.iris.compat.sodium.config.ShaderPackScreenPlaceholder;
import net.irisshaders.iris.gui.screen.ShaderPackScreen;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IrisMixinTest {

    @Test
    void testIrisClassesAvailableAndRecognized() {
        assertNotNull(ShaderPackScreenPlaceholder.class);
        assertNotNull(ShaderPackScreen.class);

        VulkanRtxMixinPlugin plugin = new VulkanRtxMixinPlugin();
        boolean applies = plugin.shouldApplyMixin(
            ShaderPackScreenPlaceholder.class.getName(),
            MixinShaderPackScreenPlaceholder.class.getName()
        );
        assertTrue(applies, "MixinShaderPackScreenPlaceholder must be applied when Iris is on classpath");
    }
}
