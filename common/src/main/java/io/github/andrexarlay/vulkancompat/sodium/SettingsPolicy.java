package io.github.andrexarlay.vulkancompat.sodium;

import io.github.andrexarlay.vulkancompat.rtx.config.RtSettings;
import io.github.andrexarlay.vulkancompat.rtx.config.TextureLodBias;
import io.github.andrexarlay.vulkancompat.rtx.config.VulkanRtxConfig;

/**
 * Options that have no meaningful "off" are not shown (docs/SETTINGS_AUDIT.md, decision FORCED-ON). This keeps them on after
 * a config load, a preset load or a reset. The Expert UI ({@code -Dvkrtx.ui.expert=true}) shows them again and then the
 * stored value is respected. Autotests that need another value set the field directly at runtime (this runs on load only).
 */
public final class SettingsPolicy {
    private SettingsPolicy() {
    }

    public static void enforce() {
        if (Boolean.getBoolean("vkrtx.ui.expert") || VulkanRtxConfig.uiExpert) {
            return;
        }
        RtSettings.pathAccumulate = true;
        RtSettings.rtDistanceAuto = true;
        VulkanRtxConfig.autoTextureLodBias = true;
        TextureLodBias.autoTextureLodBias = true;
    }
}
