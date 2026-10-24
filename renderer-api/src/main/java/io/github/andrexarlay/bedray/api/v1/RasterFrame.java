package io.github.andrexarlay.bedray.api.v1;

/** Borrowed current world raster inputs before compute replacement; valid only during render().
 * Both sampled views stay GENERAL, same device/extent/pixel origin as the output target.
 * Color is RGBA8/BGRA8 UNORM with sRGB-encoded texels, or sRGB with hardware decoding.
 * Depth is D32_FLOAT, reverse device Z (clear zero), from the same bobbed camera frame.
 * Depth cannot identify translucent particles that do not write depth. Keeping this record does
 * not extend view lifetime: the host retires consumers before target destruction/replacement.
 */
public record RasterFrame(long deviceAddress, long colorView, long depthView, int colorFormat,
                          int depthFormat, int width, int height) {
    public RasterFrame {
        if (deviceAddress == 0 || colorView == 0 || depthView == 0 || width <= 0 || height <= 0)
            throw new IllegalArgumentException("Invalid borrowed raster frame");
    }
}
