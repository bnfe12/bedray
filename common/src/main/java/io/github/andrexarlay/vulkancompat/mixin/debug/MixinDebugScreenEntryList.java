package io.github.andrexarlay.vulkancompat.mixin.debug;

import io.github.andrexarlay.vulkancompat.rtx.debug.F3Sections;
import net.minecraft.client.gui.components.debug.DebugScreenEntryList;
import net.minecraft.client.gui.components.debug.DebugScreenEntryStatus;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;

/**
 * Makes the default-on Bedray sections visible in the F3 overlay by default (the others stay off until the player enables them in the F3+F6 screen). The built-in profiles are immutable
 * {@code Map.of(..)} (vineflower of {@code DebugScreenEntries.<clinit>}) and every status source (profile, saved custom map)
 * goes through {@code private void resetStatuses(Map)} (javap -p of DebugScreenEntryList). {@code putIfAbsent} keeps a
 * choice the user saved in debug-profile.json (for example NEVER); unknown ids default to NEVER otherwise
 * ({@code getStatus} -> getOrDefault NEVER).
 */
@Mixin(value = DebugScreenEntryList.class, remap = false)
public abstract class MixinDebugScreenEntryList {
    @Shadow
    @Final
    private Map<Identifier, DebugScreenEntryStatus> allStatuses;

    @Inject(method = "resetStatuses(Ljava/util/Map;)V", at = @At("TAIL"))
    private void vkb$defaultInOverlay(Map<Identifier, DebugScreenEntryStatus> newEntries, CallbackInfo ci) {
        for (F3Sections.Section section : F3Sections.ALL) {
            if (section.defaultOn()) {
                this.allStatuses.putIfAbsent(section.id(), DebugScreenEntryStatus.IN_OVERLAY);
            }
        }
    }
}
