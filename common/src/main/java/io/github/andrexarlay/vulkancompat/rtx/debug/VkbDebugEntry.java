package io.github.andrexarlay.vulkancompat.rtx.debug;

import net.minecraft.resources.Identifier;

/**
 * Id of the main Bedray F3 section, kept for the keybind and the Sodium option that switch "the Bedray block" on and off
 * (they toggle this one entry; the other sections are switched in the F3+F6 screen). The entry itself is {@link F3Entry}.
 */
public final class VkbDebugEntry {
    public static final Identifier ID = F3Sections.Section.OVERVIEW.id();

    private VkbDebugEntry() {}
}
