package io.github.andrexarlay.vulkanbridge.mixin;

import com.mojang.renderpearl.backend.vulkan.VulkanInstance;
import io.github.andrexarlay.vulkanbridge.api.VulkanBridge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.github.andrexarlay.vulkanbridge.core.InstanceExtensionSelector;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Set;

/**
 * Raises the engine VkInstance {@code apiVersion} to the maximum {@code minInstanceApiVersion} of the registered
 * device requirements (public registry). With no such requirement nothing changes.
 * <p>
 * Signature source: javap -p -c on libs/minecraft-26.3-client.jar and on the NeoForge-patched jar
 * (26.3.0.57-beta): {@code protected VulkanInstance(int,boolean,boolean)} calls
 * {@code VkApplicationInfo.apiVersion(I)} once (getstatic VK12.VK_API_VERSION_1_2 at offset 58); decompiled copy in
 * (decompiled game classes). LWJGL's {@code new VkInstance(handle, createInfo)} derives its function capabilities
 * from that apiVersion, so the 1.3 entry points are loaded for the instance (and devices created from it).
 * <p>
 * Instance extensions (0.3.1): the constructor fills the private final {@code Set<String> enabledExtensions} (SDL surface
 * extensions, debug utils, portability) and later copies it into {@code ppEnabledExtensionNames}. We capture the result of
 * the private {@code getSupportedInstanceExtensions()} (RETURN) and, right after the static
 * {@code VulkanDebug.create(IZLjava/util/Set;Ljava/util/Set;)} call, add the requested names the loader supports (the set is
 * copied into the buffer only afterwards). Signatures: {@code javap -p -c} on libs/minecraft-26.3-client.jar
 * (field enabledExtensions, getSupportedInstanceExtensions:()Ljava/util/Set; invoked at offset 139, VulkanDebug.create at 222).
 */
@Mixin(value = VulkanInstance.class, remap = false)
public abstract class MixinVulkanInstance {
    private static final Logger LOGGER = LoggerFactory.getLogger("BedrayCore/VulkanInstance");

    @Shadow @Final private Set<String> enabledExtensions;
    @Unique private Set<String> vkb$availableExtensions;

    @Inject(method = "getSupportedInstanceExtensions", at = @At("RETURN"))
    private void vkb$captureAvailable(CallbackInfoReturnable<Set<String>> cir) {
        vkb$availableExtensions = cir.getReturnValue();
    }

    @Inject(
        method = "<init>(IZZ)V",
        at = @At(
            value = "INVOKE",
            target = "Lcom/mojang/renderpearl/backend/vulkan/VulkanDebug;create(IZLjava/util/Set;Ljava/util/Set;)Lcom/mojang/renderpearl/backend/vulkan/VulkanDebug;",
            shift = At.Shift.AFTER
        )
    )
    private void vkb$addRequestedInstanceExtensions(int debugVerbosity, boolean wantsDebugLabels, boolean validation, CallbackInfo ci) {
        if (vkb$availableExtensions == null) {
            throw new IllegalStateException("VulkanInstance hook: getSupportedInstanceExtensions() did not run before VulkanDebug.create");
        }
        InstanceExtensionSelector.apply(VulkanBridge.deviceFeatures(), vkb$availableExtensions, enabledExtensions);
    }

    @ModifyArg(
        method = "<init>(IZZ)V",
        at = @At(value = "INVOKE", target = "Lorg/lwjgl/vulkan/VkApplicationInfo;apiVersion(I)Lorg/lwjgl/vulkan/VkApplicationInfo;"),
        index = 0
    )
    private int vkb$apiVersionFromRegistry(int engineApiVersion) {
        // Late providers must have registered before the instance API version is read (they may ask for a newer one).
        VulkanBridge.deviceFeatures().runLateProviders();
        int requested = VulkanBridge.deviceFeatures().requestedInstanceApiVersion();
        if (requested <= engineApiVersion) {
            return engineApiVersion;
        }
        LOGGER.info("[VKB-CORE] instance_api_raised from=0x{} to=0x{}",
            Integer.toHexString(engineApiVersion), Integer.toHexString(requested));
        return requested;
    }
}
