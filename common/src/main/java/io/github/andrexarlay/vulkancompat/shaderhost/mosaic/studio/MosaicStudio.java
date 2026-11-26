package io.github.andrexarlay.vulkancompat.shaderhost.mosaic.studio;

/**
 * Mosaic Studio launcher: the node editor as its own desktop application, independent of Minecraft.
 * Run {@code java ...MosaicStudio [path/to/mosaic.json or pack folder]}. See {@link StudioApp}.
 */
public final class MosaicStudio {

    private MosaicStudio() {}

    public static void main(String[] args) throws Exception {
        // the interface scale of every window (Settings > Look > Interface scale): Java reads it once, before the first window
        try {
            int scale = StudioSettings.load().uiScale;
            if (scale > 0 && System.getProperty("sun.java2d.uiScale") == null)
                System.setProperty("sun.java2d.uiScale", String.valueOf(scale / 100.0));
        } catch (RuntimeException | LinkageError ignored) {
            // the system's scale
        }
        StudioApp.launch(args);
    }
}
