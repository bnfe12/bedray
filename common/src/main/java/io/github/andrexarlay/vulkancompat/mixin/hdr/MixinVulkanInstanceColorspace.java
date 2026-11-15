package io.github.andrexarlay.vulkancompat.mixin.hdr;

import io.github.andrexarlay.vulkancompat.rtx.hdr.HdrProbe;
import io.github.andrexarlay.vulkancompat.rtx.hdr.HdrSettings;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Set;

/**
 * Only when HDR10 output is requested ({@code hdr_output = "HDR10"}) or {@code -Dbedray.hdr.probe=colorspace} is given: adds {@code VK_EXT_swapchain_colorspace} to the instance extensions
 * so the surface lists its HDR colour spaces. Otherwise this mixin changes nothing.
 * <p>
 * Call site checked in the decompile (scratch/decomp/out/.../VulkanInstance.java): the constructor
 * {@code VulkanInstance(int, boolean, boolean)} fills {@code Set<String> enabledExtensions} and only afterwards copies it into
 * {@code ppEnabledExtensionNames}; {@code VulkanDebug.create(IZSet;Set;)} is the last call before that copy, package-private static.
 */
@Mixin(targets = "com.mojang.renderpearl.backend.vulkan.VulkanInstance", remap = false)
public abstract class MixinVulkanInstanceColorspace {
    @Shadow
    @Final
    private Set<String> enabledExtensions;

    @Inject(method = "<init>", at = @At(value = "INVOKE", shift = At.Shift.AFTER,
        target = "Lcom/mojang/renderpearl/backend/vulkan/VulkanDebug;create(IZLjava/util/Set;Ljava/util/Set;)Lcom/mojang/renderpearl/backend/vulkan/VulkanDebug;"))
    private void vkb$hdrProbeExtension(CallbackInfo ci) {
        try {
            boolean offered = HdrProbe.instanceExtensions().contains(HdrProbe.EXT_SWAPCHAIN_COLORSPACE);
            boolean enable = offered && (HdrProbe.wantColorspaceExtension() || HdrSettings.wantsHdr10());
            if (enable) {
                this.enabledExtensions.add(HdrProbe.EXT_SWAPCHAIN_COLORSPACE);
            }
            HdrProbe.logInstance(enable, offered);
        } catch (Throwable t) {
            // The probe must never stop the engine from creating its instance.
            org.slf4j.LoggerFactory.getLogger("Bedray/HDR").error("[VKB-HDR] instance probe failed", t);
        }
    }
}
