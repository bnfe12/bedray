package io.github.andrexarlay.vulkanbridge.mixin;

import com.mojang.renderpearl.api.device.GpuBackend;
import io.github.andrexarlay.vulkanbridge.core.VulkanBackendDetector;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Locale;

/**
 * Reports the real GpuBackend at window creation to {@link VulkanBackendDetector}. From then on the backend is
 * resolved for good and {@code VulkanBridge.isVulkan()} needs no reflection.
 * <p>
 * Signature source: {@code javap -p -cp libs/minecraft-26.3-client.jar com.mojang.blaze3d.platform.Window}:
 * {@code private long createWindow(com.mojang.renderpearl.api.device.GpuBackend, int, int, java.lang.String)};
 * ported from MixinWindow of the earlier prototype (it ran in game there).
 */
@Mixin(targets = "com.mojang.blaze3d.platform.Window", remap = false)
public abstract class MixinWindow {
    @Inject(method = "createWindow", at = @At("HEAD"))
    private void vkb$onCreateWindow(GpuBackend backend, int width, int height, String title, CallbackInfoReturnable<Long> cir) {
        // A null backend would be an engine bug; do not guess "OpenGL" from it.
        if (backend == null) {
            throw new IllegalStateException("Window.createWindow called with a null GpuBackend");
        }
        boolean isVulkan = backend.getClass().getName().toLowerCase(Locale.ROOT).contains("vulkan");
        VulkanBackendDetector.onBackendResolved(isVulkan);
        if (!isVulkan) {
            throw new IllegalStateException(
                "\n==========================================================\n" +
                "[VKB-FATAL] FATAL: Minecraft attempted to run on OpenGL (" + backend.getClass().getName() + ")!\n" +
                "Bedray strictly require the Vulkan Graphics Backend.\n" +
                "Running on OpenGL is strictly disallowed.\n" +
                "Ensure 'preferredGraphicsBackend: \"vulkan\"' in options.txt.\n" +
                "==========================================================\n"
            );
        }
    }
}
