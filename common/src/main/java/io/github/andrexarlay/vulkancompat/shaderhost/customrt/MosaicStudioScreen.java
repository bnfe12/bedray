package io.github.andrexarlay.vulkancompat.shaderhost.customrt;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * The game's side of Mosaic Studio: while it is open the game is in a menu (cursor free, no player input) and Studio
 * covers the game window (StudioLauncher / StudioEmbed). The game keeps rendering, so Studio's Result and live edits
 * show the running frame. Closing it from the game hides Studio; Esc in Studio closes it.
 */
public final class MosaicStudioScreen extends Screen {

    public MosaicStudioScreen() {
        super(Component.translatableWithFallback("bedray.studio.title", "Mosaic Studio"));
    }

    /** Opens Studio on the active folder pack (or Studio's start page) and this screen behind it. */
    public static StudioLauncher.Result open() {
        Minecraft mc = Minecraft.getInstance();
        captureWorld(mc);
        java.nio.file.Path graph = CustomRtStage.editableMosaicPath();
        StudioLauncher.onHidden(() -> mc.execute(() -> {
            if (mc.gui.screen() instanceof MosaicStudioScreen) mc.gui.setScreen(null);
        }));
        StudioLauncher.Result r = StudioLauncher.open(graph != null && java.nio.file.Files.isRegularFile(graph) ? graph : null,
            mc.gameDirectory.toPath(), gameWindow(mc));
        if (r != StudioLauncher.Result.FAILED) mc.gui.setScreen(new MosaicStudioScreen());
        return r;
    }

    /**
     * The blocks around the player (the 96 x 48 x 96 world volume: material classes, sky and block light), the camera and
     * the time of day, for Studio's preview world: graphs are previewed on the place the player stands in.
     */
    static void captureWorld(Minecraft mc) {
        var level = mc.level;
        var player = mc.player;
        if (level == null || player == null) return;
        try {
            var volume = new io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicWorld.Volume();
            double x = player.getX(), y = player.getEyeY(), z = player.getZ();
            volume.update(new MosaicWorldFeed(level), x, y, z, io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicWorld.CELLS);
            float tod = (float) (Math.floorMod(level.getDefaultClockTime() - 6000L, 24000L) / 24000.0);
            java.nio.file.Path dir = java.nio.file.Files.createDirectories(mc.gameDirectory.toPath().resolve("bedray-studio"));
            java.nio.file.Files.write(dir.resolve("preview-world.bin"),
                volume.snapshot(x, y, z, player.getYRot(), player.getXRot(), tod).toBytes());
        } catch (java.io.IOException | RuntimeException e) {
            org.slf4j.LoggerFactory.getLogger("bedray").warn("[mosaic-studio] could not save the preview world: {}", e.toString());
        }
    }

    /** The game window's native handle (Win32 HWND through SDL), 0 when there is none. */
    static long gameWindow(Minecraft mc) {
        if (!System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).startsWith("windows")) return 0L;
        try {
            int props = org.lwjgl.sdl.SDLVideo.SDL_GetWindowProperties(mc.getWindow().handle());
            return org.lwjgl.sdl.SDLProperties.SDL_GetPointerProperty(props, org.lwjgl.sdl.SDLVideo.SDL_PROP_WINDOW_WIN32_HWND_POINTER, 0L);
        } catch (RuntimeException | LinkageError e) {
            return 0L;
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false; // the frame keeps running: Studio shows and edits it live
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        super.extractRenderState(g, mouseX, mouseY, delta);
        // only visible for a moment while Studio starts, or where Studio is a window of its own
        g.text(font, title, (width - font.width(title)) / 2, height / 2 - 12, 0xFFFFFFFF);
        Component hint = Component.translatableWithFallback("bedray.studio.hint", StudioLauncher.running()
            ? "Esc in Studio returns to the game" : "Starting Mosaic Studio...");
        g.text(font, hint, (width - font.width(hint)) / 2, height / 2 + 2, 0xFFA0A8B8);
    }

    @Override
    public void removed() {
        StudioLauncher.hide();
        super.removed();
    }
}
