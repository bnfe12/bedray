package io.github.andrexarlay.vulkancompat.sodium;

import io.github.andrexarlay.vulkancompat.rtx.config.RtSettings;
import io.github.andrexarlay.vulkancompat.rtx.config.VulkanRtxConfig;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Collapsible option groups of our Sodium page (backlog item 19). Sodium 0.9.2 has no collapsible groups: its
 * {@code OptionListWidget.renderAllPages} adds one row per option of every group, and {@code setControlHiddenWhenDisabled}
 * only hides the control, the row stays (decompiled Sodium 0.9.2, local reference,
 * OptionListWidget / ControlElement). The mixins {@code mixin.sodium.MixinOptionListWidget} and
 * {@code MixinOptionListHeaderWidget} use this registry: a collapsed group renders only its header ("▶ name - N settings,
 * click to expand"), a click on the header toggles it, saves the state to bedray.toml and rebuilds the list on the next
 * frame (scroll position kept). The search results list is not affected (it shows every matching option).
 * <p>
 * Groups are identified by the translation key of their name (all our group names are
 * {@code Component.translatableWithFallback}), never by the visible text.
 */
public final class CollapsibleGroups {
    private static final Logger LOGGER = LoggerFactory.getLogger("Bedray/SodiumPage");

    public static final String RT_ADVANCED = "bedray.group.rt_advanced";
    public static final String RT_EXPERT = "bedray.group.rt_expert";
    public static final String ADV_LOOK = "bedray.group.adv_look";
    public static final String ADV_IMAGE = "bedray.group.adv_image";
    public static final String ADV_FG = "bedray.group.adv_fg";
    public static final String ADV_DIAG = "bedray.group.adv_diag";
    public static final String ADV_RAYS = "bedray.group.adv_rays";
    public static final String[] ALL_KEYS = {RT_ADVANCED, ADV_LOOK, ADV_IMAGE, ADV_FG, ADV_DIAG, ADV_RAYS, RT_EXPERT};
    private static final java.util.Set<String> OPEN = new java.util.HashSet<>();

    private record Entry(Supplier<Boolean> expanded, Consumer<Boolean> setExpanded) {
    }

    private static final Map<String, Entry> GROUPS = new LinkedHashMap<>();
    /** Header text as rendered (without Sodium's bold prefix) -> group key; refilled on every list build. */
    private static final Map<String, String> HEADER_TEXT_TO_KEY = new LinkedHashMap<>();
    /** Last rendered header rectangle per key (screen GUI coordinates, scroll applied): x, y, width, height. Autotest only. */
    private static final Map<String, int[]> HEADER_RECTS = new LinkedHashMap<>();
    private static volatile boolean rebuildRequested;

    static {
        GROUPS.put(RT_ADVANCED, new Entry(() -> RtSettings.showAdvanced, v -> RtSettings.showAdvanced = v));
        GROUPS.put(RT_EXPERT, new Entry(() -> RtSettings.showExpert, v -> RtSettings.showExpert = v));
        for (String k : new String[] {ADV_LOOK, ADV_IMAGE, ADV_FG, ADV_DIAG, ADV_RAYS}) {
            GROUPS.put(k, new Entry(() -> OPEN.contains(k), v -> {
                if (v) {
                    OPEN.add(k);
                } else {
                    OPEN.remove(k);
                }
            }));
        }
    }

    private CollapsibleGroups() {
    }

    /** Translation key of a group name, or null when the name is not translatable (Sodium's own and other mods' groups). */
    public static String keyOf(Component name) {
        if (name != null && name.getContents() instanceof TranslatableContents tc && GROUPS.containsKey(tc.getKey())) {
            return tc.getKey();
        }
        return null;
    }

    public static boolean isCollapsible(String key) {
        return key != null && GROUPS.containsKey(key);
    }

    public static boolean isExpanded(String key) {
        Entry e = GROUPS.get(key);
        return e == null || e.expanded().get();
    }

    /** Header text: "▼ name" when expanded, "▶ name - N settings (click to expand)" when collapsed. Remembered for clicks. */
    public static Component decorate(String key, Component name, int optionCount) {
        boolean expanded = isExpanded(key);
        Component text = expanded
            ? Component.empty().append("▼ ").append(name).append("  ").append(Component.translatableWithFallback(
                "bedray.section.expanded", "(click to collapse)"))
            : Component.empty().append("▶ ").append(name).append("  ").append(Component.translatableWithFallback(
                "bedray.section.collapsed", "%s settings hidden (click to expand)", optionCount));
        HEADER_TEXT_TO_KEY.put(text.getString(), key);
        return text;
    }

    /** Group key for a rendered header title (Sodium prefixes the title with a formatting code), or null. */
    public static String keyForHeaderTitle(String title) {
        if (title == null) {
            return null;
        }
        String plain = title.startsWith("§") && title.length() >= 2 ? title.substring(2) : title;
        return HEADER_TEXT_TO_KEY.get(plain);
    }

    /** Toggles a group, saves the toml and asks the open option list to rebuild on its next frame. */
    public static void toggle(String key, String source) {
        Entry e = GROUPS.get(key);
        if (e == null) {
            return;
        }
        boolean now = !e.expanded().get();
        e.setExpanded().accept(now);
        VulkanRtxConfig.save();
        rebuildRequested = true;
        LOGGER.info("[VKB-AUTOTEST] ui_section key={} expanded={} source={}", key, now, source);
    }

    /** True once after a toggle (consumed by the option list mixin). */
    public static boolean consumeRebuildRequest() {
        boolean r = rebuildRequested;
        rebuildRequested = false;
        return r;
    }

    public static void recordHeaderRect(String key, int x, int y, int w, int h) {
        HEADER_RECTS.put(key, new int[]{x, y, w, h});
    }

    /** Last rendered header rectangle of a group (x, y, w, h), or null when it was not rendered. */
    public static int[] headerRect(String key) {
        return HEADER_RECTS.get(key);
    }

    /** Called before a list build: rectangles of the old widgets are stale. */
    public static void clearRenderedState() {
        HEADER_RECTS.clear();
        HEADER_TEXT_TO_KEY.clear();
    }
}
