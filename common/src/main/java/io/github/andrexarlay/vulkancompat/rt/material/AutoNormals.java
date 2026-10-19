package io.github.andrexarlay.vulkancompat.rt.material;

/**
 * Normal maps generated from the albedo of vanilla textures that have no LabPBR {@code _n} ("auto-normals"); pure Java,
 * unit-tested. Height = luminance of the texel (transparent texels: the lowest height), normal = Sobel gradient of the height
 * with sample coordinates clamped INSIDE the sprite (never a texel of a neighbouring atlas sprite), {@code n = normalize(-dh/dx
 * * K, -dh/dy * K, 1)} with {@link #BASE_STRENGTH}. Encoded like a LabPBR {@code _n} texel (DirectX: green points down = +y
 * of the image), blue = 255 (no AO), alpha = height. The user strength scales xy in the shader (no re-bake needed).
 */
public final class AutoNormals {
    /** Gradient scale of the bake (strength 1.0 in the settings). */
    public static final float BASE_STRENGTH = 1.4f;

    private AutoNormals() {
    }

    /** Fills {@code w x h} normal texels at {@code (x0, y0)} of {@code normals} (row stride {@code atlasW}) from {@code albedo}. */
    public static void fill(int[] normals, int atlasW, int x0, int y0, int w, int h, MaterialRegion.Image albedo) {
        float[] height = new float[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int c = albedo.sample(x, y, w, h);
                height[y * w + x] = heightOf((c >>> 16) & 255, (c >>> 8) & 255, c & 255, (c >>> 24) & 255);
            }
        }
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int c = albedo.sample(x, y, w, h);
                if (((c >>> 24) & 255) < 128) {
                    normals[(y0 + y) * atlasW + x0 + x] = LabPbr.FLAT_NORMAL;
                    continue;
                }
                // Sobel, clamped to the sprite
                float tl = at(height, w, h, x - 1, y - 1);
                float t = at(height, w, h, x, y - 1);
                float tr = at(height, w, h, x + 1, y - 1);
                float l = at(height, w, h, x - 1, y);
                float r = at(height, w, h, x + 1, y);
                float bl = at(height, w, h, x - 1, y + 1);
                float b = at(height, w, h, x, y + 1);
                float br = at(height, w, h, x + 1, y + 1);
                float dx = ((tr + 2f * r + br) - (tl + 2f * l + bl)) / 8f;
                float dy = ((bl + 2f * b + br) - (tl + 2f * t + tr)) / 8f;   // +y = down in the image
                float grad = (float) Math.sqrt(dx * dx + dy * dy);
                float deadzone = grad <= 0.012f ? 0f : Math.min(1f, (grad - 0.012f) / 0.028f);
                float scale = BASE_STRENGTH * deadzone;
                float nx = -dx * scale;
                float ny = -dy * scale;
                float len = (float) Math.sqrt(nx * nx + ny * ny + 1f);
                nx /= len;
                ny /= len;
                int rr = Math.round((nx * 0.5f + 0.5f) * 255f);
                int gg = Math.round((ny * 0.5f + 0.5f) * 255f);
                int hh = Math.max(1, Math.round(height[y * w + x] * 255f));
                normals[(y0 + y) * atlasW + x0 + x] = LabPbr.pack(rr, gg, 255, hh);
            }
        }
    }

    /** Height 0..1 from an sRGB texel: luminance; transparent texels are the lowest point. */
    public static float heightOf(int r, int g, int b, int a) {
        if (a < 128) {
            return 0f;
        }
        return (0.2126f * r + 0.7152f * g + 0.0722f * b) / 255f;
    }

    private static float at(float[] height, int w, int h, int x, int y) {
        int cx = Math.max(0, Math.min(w - 1, x));
        int cy = Math.max(0, Math.min(h - 1, y));
        return height[cy * w + cx];
    }
}
