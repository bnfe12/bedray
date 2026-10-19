package io.github.andrexarlay.vulkanbridge.neoforge.mixin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * NeoForge 26.3.0.57-beta bug workaround (known pitfall):
 * ClientHooks.initClientHooks calls net.minecraft.client.renderer.RenderPipelines.registerCustomPipelines(),
 * which is absent in the 26.3 client, so the game crashes at startup with java.lang.NoSuchMethodError.
 * <p>
 * Evidence (javap): neoforge-26.3.0.57-beta-universal.jar ClientHooks.initClientHooks has
 * {@code invokestatic RenderPipelines.registerCustomPipelines:()V}, while minecraft-client-patched-26.3.0.57-beta.jar
 * RenderPipelines has no such method. In-game proof: the earlier prototype run with this mixin disabled crashed at startup
 * (<local decompiled sources>, run 5); the run of the core+rtx jars without it crashed the same way
 * (crash-2026-10-10_22.53.24-client.txt).
 * <p>
 * NeoForge-only (target class is a NeoForge class), therefore it lives in the neoforge module, not in common.
 * Gated by {@link NeoForgeMixinPlugin}: applied only while ClientHooks calls the method AND RenderPipelines lacks it.
 * NeoForge 26.3.0.76 added the method (it posts RegisterRenderPipelinesEvent) but still calls it, so an ungated
 * redirect would silently drop that event there. {@code require = 1} stays: once the gate says "apply", the call
 * must be found.
 */
@Mixin(targets = "net.neoforged.neoforge.client.ClientHooks", remap = false)
public abstract class MixinClientHooks {
    private static final Logger VKB_LOGGER = LoggerFactory.getLogger("BedrayCore/ClientHooks");

    @Redirect(
        method = "initClientHooks",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/RenderPipelines;registerCustomPipelines()V",
            remap = false
        ),
        remap = false,
        require = 1,
        expect = 1
    )
    private static void vulkanBridge$redirectRegisterCustomPipelines() {
        VKB_LOGGER.info("[VKB-COMPAT] redirect_applied=ClientHooks.initClientHooks: skipped missing RenderPipelines.registerCustomPipelines()");
    }
}
