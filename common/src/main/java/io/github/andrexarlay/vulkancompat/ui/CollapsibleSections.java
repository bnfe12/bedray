package io.github.andrexarlay.vulkancompat.ui;

import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Option groups of the Vulkan page that the user can fold (backlog C19: "RT: Advanced" and "RT: Expert" were too long).
 * Sodium 0.9.2 has no collapsible groups in its Config API (javap of libs/sodium-core.jar: OptionGroupBuilder has only
 * setName/addOption), so the page registers the group name here and the mixins in {@code mixin.sodium.ui} do the rest:
 * <ul>
 *   <li>{@code MixinOptionListWidgetCollapse}: while a section is collapsed, {@code OptionListWidget.renderAllPages} sees an
 *       empty option list for it (the search still finds every option: renderFilteredOptions is not touched); the header
 *       title gets an arrow;</li>
 *   <li>{@code MixinOptionListHeaderWidget}: a left click on the group header toggles the section; the list is rebuilt on the
 *       next frame ({@link #consumeRebuildRequest()}, not inside the click dispatch that iterates the children).</li>
 * </ul>
 * The state lives for the game session; every section starts collapsed.
 */
public final class CollapsibleSections {
    private static final Logger LOGGER = LoggerFactory.getLogger("VulkanRtx/SettingsUi");

    /** Arrow prefixes of the header title (U+25B6 / U+25BC, both in the vanilla font). */
    public static final String COLLAPSED_ARROW = "▶ ";
    public static final String EXPANDED_ARROW = "▼ ";

    private static final Map<String, Section> SECTIONS = new LinkedHashMap<>();
    private static boolean rebuildRequested;

    private CollapsibleSections() {}

    private static final class Section {
        final String key;
        final Component name;
        boolean expanded;

        Section(String key, Component name) {
            this.key = key;
            this.name = name;
        }
    }

    /**
     * Marks a group name as collapsible and returns the same Component for {@code OptionGroupBuilder.setName}. Sodium keeps
     * the Component it was given ({@code OptionGroup.name()}), so the mixins find the group by identity. Called again when
     * the page is rebuilt: the expanded state of the key is kept.
     */
    public static synchronized Component register(String key, Component name) {
        Section old = SECTIONS.get(key);
        Section s = new Section(key, name);
        s.expanded = old != null && old.expanded;
        SECTIONS.put(key, s);
        return name;
    }

    private static Section byName(Component name) {
        if (name == null) {
            return null;
        }
        for (Section s : SECTIONS.values()) {
            if (s.name == name) {
                return s;
            }
        }
        return null;
    }

    private static Section byTitle(String title) {
        if (title == null) {
            return null;
        }
        for (Section s : SECTIONS.values()) {
            String plain = s.name.getString();
            if (title.endsWith(COLLAPSED_ARROW + plain) || title.endsWith(EXPANDED_ARROW + plain)) {
                return s;
            }
        }
        return null;
    }

    /** True when the group with this name is registered and folded. */
    public static synchronized boolean isCollapsed(Component groupName) {
        Section s = byName(groupName);
        return s != null && !s.expanded;
    }

    /** Header title of a group: the arrow plus the name for a collapsible group, the plain name otherwise. */
    public static synchronized String decorateTitle(String plainTitle) {
        for (Section s : SECTIONS.values()) {
            if (s.name.getString().equals(plainTitle)) {
                return (s.expanded ? EXPANDED_ARROW : COLLAPSED_ARROW) + plainTitle;
            }
        }
        return plainTitle;
    }

    /** True when a rendered header title belongs to a collapsible group. */
    public static synchronized boolean isCollapsibleTitle(String title) {
        return byTitle(title) != null;
    }

    /** Toggles the group of a rendered header title; returns false when the title is not a collapsible group. */
    public static synchronized boolean toggleByTitle(String title) {
        Section s = byTitle(title);
        if (s == null) {
            return false;
        }
        setExpanded(s.key, !s.expanded);
        return true;
    }

    /** Sets the state of a section by key (also used by the settings_ui autotest); requests a list rebuild. */
    public static synchronized void setExpanded(String key, boolean expanded) {
        Section s = SECTIONS.get(key);
        if (s == null) {
            throw new IllegalArgumentException("unknown collapsible section " + key + ", known: " + SECTIONS.keySet());
        }
        s.expanded = expanded;
        rebuildRequested = true;
        LOGGER.info("[VKB-AUTOTEST] ui_section key={} expanded={}", key, expanded);
    }

    public static synchronized boolean isExpanded(String key) {
        Section s = SECTIONS.get(key);
        return s != null && s.expanded;
    }

    /** Returns and clears the pending rebuild request (read once per frame by the settings screen mixin). */
    public static synchronized boolean consumeRebuildRequest() {
        boolean r = rebuildRequested;
        rebuildRequested = false;
        return r;
    }
}
