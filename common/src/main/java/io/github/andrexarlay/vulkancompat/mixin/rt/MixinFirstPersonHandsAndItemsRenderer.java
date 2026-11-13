package io.github.andrexarlay.vulkancompat.mixin.rt;

import io.github.andrexarlay.vulkancompat.rt.hand.HandRtLight;
import net.minecraft.client.renderer.FirstPersonHandsAndItemsRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Redirects the packed light of the first-person arm and its held item to {@link HandRtLight#SENTINEL_LIGHT} while the hand
 * pass is armed. javap 26.3: {@code submitHandsWithItems} reads {@code AvatarRenderState.lightCoords} at 162 and 246, each
 * straight into the last argument of {@code private void submitArmWithItem(PlayerRenderState,
 * FirstPersonHandsAndItemsRenderState, float, float, InteractionHand, float, ItemStack, float, PoseStack, SubmitNodeCollector,
 * int)}; both call sites are covered. The submit runs before prepareFrame, so this keys on {@code armed}, not on the
 * lightmap override.
 */
@Mixin(value = FirstPersonHandsAndItemsRenderer.class, remap = false)
public abstract class MixinFirstPersonHandsAndItemsRenderer {

    @ModifyArg(method = "submitHandsWithItems", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/renderer/FirstPersonHandsAndItemsRenderer;submitArmWithItem(Lnet/minecraft/client/renderer/state/level/PlayerRenderState;Lnet/minecraft/client/renderer/state/level/FirstPersonHandsAndItemsRenderState;FFLnet/minecraft/world/InteractionHand;FLnet/minecraft/world/item/ItemStack;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;I)V"),
        index = 10)
    private int vkb$handLight(int light) {
        return HandRtLight.handLight(light);
    }
}
