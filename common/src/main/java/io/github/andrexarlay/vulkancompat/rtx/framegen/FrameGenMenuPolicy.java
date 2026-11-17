package io.github.andrexarlay.vulkancompat.rtx.framegen;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/**
 * When frame generation must be off because a full-screen menu is open. NVIDIA's DLSS-G programming guide (6.4) says generation
 * should be disabled in any full-screen menu such as the settings or pause menus: the generated frames would otherwise be
 * interpolated from a back buffer that is mostly user interface. Only the world and hand are in the HUD-less buffer.
 *
 * <p>A screen counts as a full-screen menu when the game treats it as a pause screen ({@link Screen#isPauseScreen()}: the pause menu,
 * the options and video settings, Bedray's and Sodium's settings screens) and it is not part of the in-game interface
 * ({@link Screen#isInGameUi()}: inventory and container screens). Chat is not a pause screen and does not suppress generation.
 */
public final class FrameGenMenuPolicy {
    private FrameGenMenuPolicy() {
    }

    /** The decision itself, separated from the game classes so it can be tested. */
    public static boolean suppresses(boolean screenOpen, boolean pauseScreen, boolean inGameUi) {
        return screenOpen && pauseScreen && !inGameUi;
    }

    /** True while a full-screen menu is open on the client. Render thread. */
    public static boolean fullscreenMenuOpen() {
        Minecraft mc = Minecraft.getInstance();
        Screen screen = mc == null || mc.gui == null ? null : mc.gui.screen();
        return screen != null && suppresses(true, screen.isPauseScreen(), screen.isInGameUi());
    }
}
