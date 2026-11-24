package io.github.andrexarlay.vulkancompat.shaderhost.mosaic.studio;

/**
 * Mosaic Studio launcher: the node editor as its own desktop application, independent of Minecraft.
 * Run {@code java ...MosaicStudio [path/to/mosaic.json or pack folder]}. See {@link StudioApp}.
 */
public final class MosaicStudio {

    private MosaicStudio() {}

    public static void main(String[] args) throws Exception {
        StudioApp.launch(args);
    }
}
