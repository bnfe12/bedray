package io.github.andrexarlay.vulkancompat.mixin.framegen;

import io.github.andrexarlay.vulkancompat.rtx.framegen.StreamlineBootstrap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Calls {@code slInit} (NVIDIA Streamline) before the engine touches Vulkan, only when {@code -Dvkrtx.framegen=dlss} was given.
 * <p>
 * Signature checked with javap on {@code libs/minecraft-26.3-client.jar}:
 * {@code com.mojang.renderpearl.backend.vulkan.VulkanBackend#loadLibrary() throws BackendCreationException} (public).
 * It is the first Vulkan-related call of both {@code checkBackendAvailable()} and {@code createDevice}; LWJGL's VK.create()
 * (NativeLibrariesBootstrap.tryLoadingVulkan) has only loaded the library by then, no instance exists.
 */
@Mixin(targets = "com.mojang.renderpearl.backend.vulkan.VulkanBackend", remap = false)
public abstract class MixinVulkanBackendStreamline {
    @Inject(method = "loadLibrary", at = @At("HEAD"))
    private void vkb$streamlineInit(CallbackInfo ci) {
        StreamlineBootstrap.initEarly();
    }
}
