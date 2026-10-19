package io.github.andrexarlay.vulkanbridge.compat.iris;

import io.github.andrexarlay.vulkanbridge.autotest.AutotestContext;
import io.github.andrexarlay.vulkanbridge.autotest.AutotestScenario;
import net.caffeinemc.mods.sodium.client.config.ConfigManager;
import net.caffeinemc.mods.sodium.client.config.structure.ExternalPage;
import net.caffeinemc.mods.sodium.client.config.structure.ModOptions;
import net.caffeinemc.mods.sodium.client.config.structure.Page;
import net.caffeinemc.mods.sodium.client.gui.VideoSettingsScreen;
import net.irisshaders.iris.gui.screen.ShaderPackScreen;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/**
 * Autotest {@code iris_gui} (title screen, no world): drives the two real Iris entry points on Vulkan and screenshots
 * the result.
 * <ol>
 *   <li>{@code 01_title.png}: title screen.</li>
 *   <li>{@code 02_sodium_settings.png}: Sodium video settings ({@code VideoSettingsScreen.createScreen(Screen)}), page
 *       list with the Iris entry.</li>
 *   <li>Calls the consumer of the Iris "Shader Packs" {@link ExternalPage} with the Sodium screen as the page list does
 *       on click; expects {@link ShaderPackScreen}; {@code 03_shaderpacks.png}.</li>
 *   <li>{@code ShaderPackScreen.applyChanges()} (the Apply button) -> Iris.reload must be skipped, no crash;
 *       {@code 04_after_apply.png}.</li>
 *   <li>Back to the title screen, presses the Iris shader pack key ({@code KeyMapping.click(defaultKey)}); expects
 *       {@link ShaderPackScreen} again; {@code 05_key.png}.</li>
 * </ol>
 * API (javap of sodium 0.9.2 / iris 1.11.7+mc26.3 / minecraft 26.3 jars in libs/): ConfigManager.CONFIG,
 * Config.getModOptions(), ModOptions.configId()/pages(), ExternalPage.currentScreenConsumer(),
 * ShaderPackScreen.applyChanges(), KeyMapping.get(String), KeyMapping.getDefaultKey(), KeyMapping.click(Key).
 */
public final class IrisGuiScenario implements AutotestScenario {
    private static final int SETTLE = 40;

    @Override
    public String name() {
        return "iris_gui";
    }

    @Override
    public boolean needsWorld() {
        return false;
    }

    @Override
    public void run(AutotestContext ctx) {
        Minecraft mc = ctx.minecraft();
        Screen title = mc.gui.screen();
        ctx.log("event", "iris_gui_start", "irisCompatReady", String.valueOf(IrisCompat.isReady()),
                "irisVulkanMode", String.valueOf(IrisCompat.isIrisVulkanMode()));
        if (!IrisCompat.isReady()) {
            ctx.fail("IrisCompat not ready (Iris not in Vulkan mode or IrisVKOnly.run hook missing)", null);
            return;
        }
        ctx.shot("01_title.png", () -> {
            mc.gui.setScreen(VideoSettingsScreen.createScreen(title));
            ctx.waitFrames(SETTLE, () -> ctx.shot("02_sodium_settings.png", () -> openShaderPackPage(ctx, mc, title)));
        });
    }

    private void openShaderPackPage(AutotestContext ctx, Minecraft mc, Screen title) {
        Screen sodium = mc.gui.screen();
        ExternalPage page = findIrisShaderPackPage();
        if (page == null) {
            ctx.fail("Iris external page not found in Sodium ConfigManager.CONFIG", null);
            return;
        }
        ctx.log("event", "sodium_iris_page_click", "page", page.name().getString(), "parent", sodium.getClass().getName());
        page.currentScreenConsumer().accept(sodium);
        ctx.waitFrames(SETTLE, () -> {
            Screen now = mc.gui.screen();
            ctx.log("event", "screen_after_page", "class", now == null ? "null" : now.getClass().getName(),
                    "placeholderReplacements", String.valueOf(IrisCompat.placeholderReplacements()));
            if (!(now instanceof ShaderPackScreen shaderPackScreen)) {
                ctx.fail("expected ShaderPackScreen after the Sodium page, got " + (now == null ? "null" : now.getClass().getName()), null);
                return;
            }
            ctx.shot("03_shaderpacks.png", () -> {
                shaderPackScreen.applyChanges();
                int afterButton = IrisCompat.reloadsSkipped();
                if (afterButton == 0) {
                    // applyChanges() reloads only when the selection/enable state changed (empty pack list in the
                    // testbed); drive the same call it makes so the reload path is exercised.
                    boolean enabled = net.irisshaders.iris.Iris.getIrisConfig().areShadersEnabled();
                    net.irisshaders.iris.api.v0.IrisApi.getInstance().getConfig().setShadersEnabledAndApply(enabled);
                    ctx.log("event", "apply_via_irisapi", "enabled", String.valueOf(enabled));
                }
                ctx.waitFrames(SETTLE, () -> {
                    Screen afterApply = mc.gui.screen();
                    ctx.log("event", "after_apply", "class", afterApply == null ? "null" : afterApply.getClass().getName(),
                            "reloadsSkipped", String.valueOf(IrisCompat.reloadsSkipped()));
                    ctx.shot("04_after_apply.png", () -> pressKey(ctx, mc, title));
                });
            });
        });
    }

    private void pressKey(AutotestContext ctx, Minecraft mc, Screen title) {
        mc.gui.setScreen(title);
        KeyMapping key = KeyMapping.get("iris.keybind.shaderPackSelection");
        if (key == null) {
            ctx.fail("KeyMapping iris.keybind.shaderPackSelection not registered", null);
            return;
        }
        ctx.waitFrames(10, () -> {
            KeyMapping.click(key.getDefaultKey());
            ctx.log("event", "iris_key_clicked", "key", key.getDefaultKey().getName());
            ctx.waitFrames(SETTLE, () -> {
                Screen now = mc.gui.screen();
                ctx.log("event", "screen_after_key", "class", now == null ? "null" : now.getClass().getName(),
                        "placeholderReplacements", String.valueOf(IrisCompat.placeholderReplacements()));
                if (!(now instanceof ShaderPackScreen)) {
                    ctx.fail("expected ShaderPackScreen after the Iris key, got " + (now == null ? "null" : now.getClass().getName()), null);
                    return;
                }
                ctx.shot("05_key.png", () -> {
                    mc.gui.setScreen(title);
                    ctx.waitFrames(10, ctx::finish);
                });
            });
        });
    }

    private static ExternalPage findIrisShaderPackPage() {
        for (ModOptions options : ConfigManager.CONFIG.getModOptions()) {
            if (!"iris".equals(options.configId())) {
                continue;
            }
            for (Page page : options.pages()) {
                if (page instanceof ExternalPage external) {
                    return external;
                }
            }
        }
        return null;
    }
}
