package io.github.andrexarlay.vulkancompat.mixin.debug;

import io.github.andrexarlay.vulkancompat.rtx.debug.F3Entry;
import io.github.andrexarlay.vulkancompat.rtx.debug.F3Sections;
import net.minecraft.client.gui.components.debug.DebugScreenEntries;
import net.minecraft.client.gui.components.debug.DebugScreenEntry;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Registers the Bedray sections of the F3 screen ({@link F3Sections.Section}, one entry each) in the vanilla registry. 26.3 has no public registration API:
 * {@code DebugScreenEntries} keeps {@code private static final Map<Identifier, DebugScreenEntry> ENTRIES_BY_ID} (a HashMap)
 * filled by {@code private static Identifier register(Identifier, DebugScreenEntry)} from its static initializer
 * (javap -p + vineflower of libs/minecraft-26.3-client.jar). The overlay and the F3+F6 options screen read only
 * {@code getEntry}/{@code allEntries}, so an entry added at the end of {@code <clinit>} is shown like a vanilla one.
 */
@Mixin(value = DebugScreenEntries.class, remap = false)
public abstract class MixinDebugScreenEntries {
    @Shadow
    private static Identifier register(Identifier identifier, DebugScreenEntry entry) {
        throw new AssertionError();
    }

    @Inject(method = "<clinit>", at = @At("TAIL"))
    private static void vkb$registerF3Sections(CallbackInfo ci) {
        for (F3Sections.Section section : F3Sections.ALL) {
            register(section.id(), new F3Entry(section));
        }
    }
}
