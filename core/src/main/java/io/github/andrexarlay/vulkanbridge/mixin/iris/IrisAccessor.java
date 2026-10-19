package io.github.andrexarlay.vulkanbridge.mixin.iris;

import net.irisshaders.iris.UpdateChecker;
import net.irisshaders.iris.config.IrisConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Static setters for the Iris state that {@code Iris.onEarlyInitialize()} creates and that the Vulkan-only mode of
 * Iris never creates. Field signatures: {@code javap -p net.irisshaders.iris.Iris} (iris 1.11.7+mc26.3):
 * {@code private static IrisConfig irisConfig; private static UpdateChecker updateChecker; private static String IRIS_VERSION}.
 * Used only by {@link io.github.andrexarlay.vulkanbridge.compat.iris.IrisCompat}.
 */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.Iris", remap = false)
public interface IrisAccessor {
    @Accessor(value = "irisConfig", remap = false)
    static void vkb$setIrisConfig(IrisConfig config) {
        throw new AssertionError("mixin accessor not applied");
    }

    @Accessor(value = "updateChecker", remap = false)
    static void vkb$setUpdateChecker(UpdateChecker checker) {
        throw new AssertionError("mixin accessor not applied");
    }

    @Accessor(value = "IRIS_VERSION", remap = false)
    static void vkb$setIrisVersion(String version) {
        throw new AssertionError("mixin accessor not applied");
    }
}
