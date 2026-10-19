package io.github.andrexarlay.vulkancompat.rtx.debug;

import net.minecraft.client.gui.components.debug.DebugEntryCategory;
import net.minecraft.client.gui.components.debug.DebugScreenDisplayer;
import net.minecraft.client.gui.components.debug.DebugScreenEntry;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.ChatFormatting;

/**
 * One section of the Bedray F3 screen as a vanilla debug-screen entry (registered by {@code MixinDebugScreenEntries}). The entries
 * sit in their own category "Bedray RTX" of the F3+F6 options screen, where every section can be switched between always on, only in
 * the F3 overlay and off. {@code display} hands the cached immutable list of {@link F3Sections#lines} to the overlay as one group,
 * which keeps the lines of a section together in one column (vineflower of {@code DebugScreenOverlay.extractRenderState}; groups
 * are ordered by identifier, hence the number in the path). Signatures: javap -p of {@code DebugScreenEntry},
 * {@code DebugScreenDisplayer.addToGroup(Identifier, Collection)}, record {@code DebugEntryCategory(Component, float)} in
 * libs/minecraft-26.3-client.jar. No world information is shown, so the entry is allowed with reduced debug info.
 */
public final class F3Entry implements DebugScreenEntry {
    private final F3Sections.Section section;

    public F3Entry(F3Sections.Section section) {
        this.section = section;
    }

    @Override
    public void display(DebugScreenDisplayer displayer, Level level, LevelChunk clientChunk, LevelChunk serverChunk) {
        displayer.addToGroup(section.id(), F3Sections.lines(section));
    }

    @Override
    public boolean isAllowed(boolean reducedDebugInfo) {
        return true;
    }

    @Override
    public DebugEntryCategory category() {
        return CategoryHolder.BEDRAY;
    }

    /** Created on first use so that no Component is built while DebugScreenEntries is still initialising. */
    private static final class CategoryHolder {
        static final DebugEntryCategory BEDRAY = new DebugEntryCategory(
            Component.literal("Bedray RTX").withStyle(ChatFormatting.BOLD, ChatFormatting.UNDERLINE), 3.0F);
    }
}
