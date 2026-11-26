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
     * The blocks around the player (the world volume of the graph settings worldRadius / worldHeight: material classes, sky and block light), the camera and
     * the time of day, for Studio's preview world: graphs are previewed on the place the player stands in.
     */
    static void captureWorld(Minecraft mc) {
        var level = mc.level;
        var player = mc.player;
        if (level == null || player == null) return;
        try {
            int[] dims = CustomRtStage.worldDims();
            var volume = new io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicWorld.Volume(dims[0], dims[1], dims[2]);
            double x = player.getX(), y = player.getEyeY(), z = player.getZ();
            volume.update(new MosaicWorldFeed(level), x, y, z, volume.cellCount());
            float tod = (float) (Math.floorMod(level.getDefaultClockTime() - 6000L, 24000L) / 24000.0);
            java.nio.file.Path dir = java.nio.file.Files.createDirectories(mc.gameDirectory.toPath().resolve("bedray-studio"));
            // the land beyond the volume: 384 x 384 columns, top block height and class
            int fs = 384, fox = (int) Math.floor(x) - fs / 2, foz = (int) Math.floor(z) - fs / 2;
            short[] hs = new short[fs * fs], gs = new short[fs * fs];
            byte[] ms = new byte[fs * fs];
            var pos = new net.minecraft.core.BlockPos.MutableBlockPos();
            for (int j = 0; j < fs; j++)
                for (int i = 0; i < fs; i++) {
                    int bx = fox + i, bz = foz + j;
                    int top = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, bx, bz) - 1;
                    pos.set(bx, top, bz);
                    var state = level.getBlockState(pos);
                    hs[j * fs + i] = state.isAir() ? Short.MIN_VALUE : (short) Math.clamp(top, -32767, 32767);
                    ms[j * fs + i] = (byte) MosaicWorldFeed.classify(state).id();
                    gs[j * fs + i] = (short) Math.clamp(level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, bx, bz) - 1,
                        -32767, 32767);
                    // a log right under the leaves (on the ground): the column's trunk, marked in the material's high bit
                    if (gs[j * fs + i] < hs[j * fs + i]) {
                        pos.set(bx, gs[j * fs + i], bz);
                        if (level.getBlockState(pos).is(net.minecraft.tags.BlockTags.LOGS)) ms[j * fs + i] |= (byte) 0x80;
                    }
                }
            var far = new io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicWorld.Far(fs, fox, foz, hs, ms, gs);
            var snap = volume.snapshot(x, y, z, player.getYRot(), player.getXRot(), tod).withFar(far);
            // which block every cell is, so Studio's preview draws each block with its own textures
            java.util.Map<String, Integer> ids = new java.util.LinkedHashMap<>();
            ids.put("minecraft:air", 0);
            short[] idx = new short[snap.cellCount()];
            for (int ly = 0, i = 0; ly < snap.sy(); ly++)
                for (int lz = 0; lz < snap.sz(); lz++)
                    for (int lx = 0; lx < snap.sx(); lx++, i++) {
                        pos.set(snap.ox() + lx, snap.oy() + ly, snap.oz() + lz);
                        var st = level.getBlockState(pos);
                        if (st.isAir()) continue;
                        String name = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(st.getBlock()).toString();
                        Integer id = ids.get(name);
                        if (id == null && ids.size() < 65535) ids.put(name, id = ids.size());
                        idx[i] = (short) (id == null ? 0 : id.intValue());
                    }
            // the biome of every column at the camera's height (grass, leaf and water colours in Studio)
            java.util.Map<String, Integer> bids = new java.util.LinkedHashMap<>();
            short[] bcol = new short[snap.sx() * snap.sz()];
            for (int lz = 0; lz < snap.sz(); lz++)
                for (int lx = 0; lx < snap.sx(); lx++) {
                    pos.set(snap.ox() + lx, (int) Math.floor(y), snap.oz() + lz);
                    String bn = level.getBiome(pos).unwrapKey().map(k -> k.identifier().toString()).orElse("minecraft:plains");
                    Integer bid = bids.get(bn);
                    if (bid == null) bids.put(bn, bid = bids.size());
                    bcol[lz * snap.sx() + lx] = (short) bid.intValue();
                }
            byte[] bytes = new io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicWorld.BlockTable(java.util.List.copyOf(ids.keySet()), idx,
                java.util.List.copyOf(bids.keySet()), bcol).appendTo(snap.toBytes());
            java.nio.file.Files.write(dir.resolve("preview-world.bin"), bytes);
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
