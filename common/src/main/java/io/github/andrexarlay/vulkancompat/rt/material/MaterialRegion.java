package io.github.andrexarlay.vulkancompat.rt.material;

/**
 * Fills the region of one sprite in the material and normal atlases (pure Java, unit-tested). Source images are given as
 * ARGB int arrays ({@code 0xAARRGGBB}, as {@code BufferedImage.getRGB}) with their width; only the first animation frame
 * (the top {@code h * scale} rows) is used. A {@code _s}/{@code _n} map may have a different resolution than the albedo:
 * it is sampled nearest at the matching relative position.
 */
public final class MaterialRegion {
    private MaterialRegion() {
    }

    static float saturation(int argb) {
        float r = ((argb >>> 16) & 255) / 255f;
        float g = ((argb >>> 8) & 255) / 255f;
        float b = (argb & 255) / 255f;
        float max = Math.max(r, Math.max(g, b));
        float min = Math.min(r, Math.min(g, b));
        return max > 0f ? (max - min) / max : 0f;
    }

    /** Mean luminance of the 3x3 neighbourhood, coordinates clamped inside the sprite. */
    static float localMean(float[] lum, int w, int h, int x, int y) {
        float sum = 0f;
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                int cx = Math.max(0, Math.min(w - 1, x + dx));
                int cy = Math.max(0, Math.min(h - 1, y + dy));
                sum += lum[cy * w + cx];
            }
        }
        return sum / 9f;
    }

    static float luminance(int argb) {
        return (0.2126f * ((argb >>> 16) & 255) + 0.7152f * ((argb >>> 8) & 255) + 0.0722f * (argb & 255)) / 255f;
    }

    /** One decoded source image (first frame or whole strip). */
    public record Image(int width, int height, int[] argb) {
        int sample(int x, int y, int regionW, int regionH) {
            // nearest sample at the same relative position; the frame height is regionH scaled like the width
            float scale = width / (float) regionW;
            int sx = Math.min(width - 1, (int) ((x + 0.5f) * scale));
            int sy = Math.min(height - 1, (int) ((y + 0.5f) * scale));
            return argb[sy * width + sx];
        }
    }

    /**
     * Writes {@code w x h} texels at {@code (x0, y0)} of the atlases (row stride {@code atlasW}).
     * {@code specular}/{@code normal}: LabPBR maps or null; {@code albedo}: needed only for {@link AutoPbr.Emission#BRIGHT}.
     * Returns the number of emissive texels written.
     */
    /**
     * Switches of the generated ("auto") materials, RtSettings Expert level: {@code pbr} off = flat defaults for textures
     * without resource-pack maps; per-pixel roughness from the texture (base of the category, darker / high-contrast
     * texels rougher, scaled by {@code contrast}); per-pixel metal for metal blocks and the mineral pixels of ores;
     * emission masks. Changing any of them re-bakes the atlas.
     */
    public record AutoOptions(boolean pbr, boolean roughness, boolean metallic, boolean emission, float contrast) {
        public static final AutoOptions DEFAULT = new AutoOptions(true, true, true, true, 1f);
    }

    /**
     * Emission 0..1 per texel of an auto-PBR sprite from its albedo ({@code argb}: {@code 0xAARRGGBB}, {@code w x h}), by the same
     * rules as {@link #fill} writes into the material atlas (WHOLE: relative luminance mask with a floor of 15 %; BRIGHT: per
     * pixel value / flame rule; mixed blocks: lantern glass, lit lamp, bulb). Used for the entity atlas (items, mobs), where
     * the block atlas material map does not apply. Returns null when the sprite has no emission.
     */
    public static float[] emissionMask(AutoPbr.Material auto, AutoPbr.MixedKind mixed, int[] argb, int w, int h) {
        if (auto.emission() <= 0f && auto.emissionMode() == AutoPbr.Emission.NONE) {
            return null;
        }
        float[] mask = new float[w * h];
        float lumLo = 0f;
        float lumHi = 1f;
        boolean whole = mixed == AutoPbr.MixedKind.NONE && auto.emissionMode() == AutoPbr.Emission.WHOLE;
        if (whole) {
            double sum = 0;
            float max = 0f;
            int cnt = 0;
            for (int i = 0; i < w * h; i++) {
                if (((argb[i] >>> 24) & 255) < 128) {
                    continue;
                }
                float l = luminance(argb[i]);
                sum += l;
                max = Math.max(max, l);
                cnt++;
            }
            float mean = cnt > 0 ? (float) (sum / cnt) : 0f;
            lumLo = mean * 0.85f;
            lumHi = Math.max(max, lumLo + 0.05f);
        }
        boolean any = false;
        for (int i = 0; i < w * h; i++) {
            int c = argb[i];
            int a = (c >>> 24) & 255;
            float e = 0f;
            if (mixed != AutoPbr.MixedKind.NONE && auto.emission() > 0f) {
                e = AutoPbr.isEmissiveTexel(c, mixed) ? 1.0f : 0f;
            } else if (auto.emissionMode() == AutoPbr.Emission.WHOLE) {
                e = a < 128 ? 0f : auto.emission() * (0.15f + 0.85f * AutoPbr.smoothstep(lumLo, lumHi, luminance(c)));
            } else if (auto.emissionMode() == AutoPbr.Emission.BRIGHT) {
                if (AutoPbr.RULE_FLAME.equals(auto.rule())) {
                    e = auto.emission() * AutoPbr.flameEmission((c >>> 16) & 255, (c >>> 8) & 255, c & 255, a);
                } else if (AutoPbr.RULE_REDSTONE.equals(auto.rule())) {
                    e = a < 128 ? 0f : auto.emission();
                } else if (AutoPbr.RULE_CANDLE.equals(auto.rule())) {
                    e = auto.emission() * AutoPbr.candleEmission((c >>> 16) & 255, (c >>> 8) & 255, c & 255, a);
                } else if (AutoPbr.RULE_ANCHOR.equals(auto.rule())) {
                    e = auto.emission() * AutoPbr.anchorEmission((c >>> 16) & 255, (c >>> 8) & 255, c & 255, a);
                } else if (AutoPbr.RULE_END_ROD.equals(auto.rule())) {
                    e = auto.emission() * AutoPbr.endRodEmission((c >>> 16) & 255, (c >>> 8) & 255, c & 255, a);
                } else if (AutoPbr.RULE_MAGMA.equals(auto.rule())) {
                    e = auto.emission() * AutoPbr.magmaEmission((c >>> 16) & 255, (c >>> 8) & 255, c & 255, a);
                } else if (AutoPbr.RULE_ENCHANTING_TABLE.equals(auto.rule())) {
                    e = AutoPbr.enchantingTableEmission((c >>> 16) & 255, (c >>> 8) & 255, c & 255, a);
                } else {
                    e = auto.emission() * AutoPbr.brightPixelEmission((c >>> 16) & 255, (c >>> 8) & 255, c & 255, a);
                }
            }
            mask[i] = e;
            any |= e > 0f;
        }
        return any ? mask : null;
    }

    public static int fill(int[] material, int[] normals, int atlasW, int x0, int y0, int w, int h,
                           AutoPbr.Material auto, Image albedo, Image specular, Image normal) {
        return fill(material, normals, atlasW, x0, y0, w, h, auto, albedo, specular, normal, AutoOptions.DEFAULT, "");
    }

    public static int fill(int[] material, int[] normals, int atlasW, int x0, int y0, int w, int h,
                           AutoPbr.Material auto, Image albedo, Image specular, Image normal, String spritePath) {
        return fill(material, normals, atlasW, x0, y0, w, h, auto, albedo, specular, normal, AutoOptions.DEFAULT, spritePath);
    }

    static boolean isTranslucentOrGlass(AutoPbr.Material auto, String spritePath) {
        if (auto != null && ("glass".equals(auto.rule()) || "water".equals(auto.rule()) || "ice".equals(auto.rule()))) {
            return true;
        }
        if (spritePath != null) {
            String sp = spritePath.toLowerCase(java.util.Locale.ROOT);
            // "ice" only as the whole name: blue_ice / packed_ice are opaque blocks (and "ice" is a substring of other names)
            String sn = sp.substring(sp.lastIndexOf('/') + 1);
            return sp.contains("glass") || sp.contains("water") || sn.equals("ice") || sn.startsWith("frosted_ice");
        }
        return false;
    }

    public static int fill(int[] material, int[] normals, int atlasW, int x0, int y0, int w, int h,
                           AutoPbr.Material autoIn, Image albedo, Image specular, Image normal, AutoOptions opts, String spritePath) {
        AutoPbr.Material auto = opts.pbr() ? autoIn : AutoPbr.DEFAULT;
        if (!opts.emission() && auto.emissionMode() != AutoPbr.Emission.NONE) {
            auto = new AutoPbr.Material(auto.roughness(), auto.f0(), auto.metal(), 0f, AutoPbr.Emission.NONE, auto.rule());
        }
        boolean translucentGlass = isTranslucentOrGlass(auto, spritePath);
        AutoPbr.OreKind ore = opts.pbr() ? AutoPbr.oreKind(spritePath) : AutoPbr.OreKind.NONE;
        AutoPbr.MixedKind mixed = opts.pbr() ? AutoPbr.mixedKind(spritePath) : AutoPbr.MixedKind.NONE;
        // per-pixel statistics of the albedo: mean luminance and the 3x3 local deviation (clamped inside the sprite)
        float[] lum = null;
        float meanLum = 0f;
        boolean perPixel = specular == null && albedo != null && opts.pbr() && (opts.roughness() || opts.metallic());
        if (perPixel) {
            lum = new float[w * h];
            double sum = 0;
            int cnt = 0;
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int c = albedo.sample(x, y, w, h);
                    lum[y * w + x] = luminance(c);
                    if (((c >>> 24) & 255) >= 128) {
                        sum += lum[y * w + x];
                        cnt++;
                    }
                }
            }
            meanLum = cnt > 0 ? (float) (sum / cnt) : 0f;
        }
        int emissive = 0;
        // WHOLE emitters (glowstone, sea lantern, lava, ...): the brighter texels glow, the dark ones much less
        // (relative to the sprite: from 0.85 x mean luminance up to the brightest texel), with a floor of 15%.
        float lumLo = 0f;
        float lumHi = 1f;
        boolean wholeMask = specular == null && albedo != null && auto.emissionMode() == AutoPbr.Emission.WHOLE;
        if (wholeMask) {
            double sum = 0;
            float max = 0f;
            int cnt = 0;
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int c = albedo.sample(x, y, w, h);
                    if (((c >>> 24) & 255) < 128) {
                        continue;
                    }
                    float l = luminance(c);
                    sum += l;
                    max = Math.max(max, l);
                    cnt++;
                }
            }
            float mean = cnt > 0 ? (float) (sum / cnt) : 0f;
            lumLo = mean * 0.85f;
            lumHi = Math.max(max, lumLo + 0.05f);
        }
        for (int y = 0; y < h; y++) {
            int row = (y0 + y) * atlasW + x0;
            for (int x = 0; x < w; x++) {
                int c = albedo != null ? albedo.sample(x, y, w, h) : 0;
                int a = (c >>> 24) & 255;
                boolean isCutoutHole = albedo != null && a < 128 && !translucentGlass
                    && !AutoPbr.RULE_GLASS.equals(auto.rule()) && !AutoPbr.RULE_WATER.equals(auto.rule())
                    && !AutoPbr.RULE_ICE.equals(auto.rule());
                int mat;
                if (isCutoutHole) {
                    // Alpha holes must not reflect or emit, including when a resource pack supplies an _s map.
                    mat = LabPbr.pack(255, 0, 0, LabPbr.SOURCE_AUTO);
                } else if (specular != null) {
                    int s = specular.sample(x, y, w, h);
                    mat = LabPbr.materialFromSpecular((s >>> 16) & 255, (s >>> 8) & 255, s & 255, (s >>> 24) & 255);
                } else {
                    float e = 0f;
                    boolean isEmissive = auto.emission() > 0f && AutoPbr.isEmissiveTexel(c, mixed);

                    if (mixed != AutoPbr.MixedKind.NONE && auto.emission() > 0f) {
                        if (isEmissive) {
                            e = 1.0f;
                        }
                    } else if (auto.emissionMode() == AutoPbr.Emission.WHOLE) {
                        e = auto.emission();
                        if (wholeMask) {
                            e = a < 128 ? 0f
                                : auto.emission() * (0.15f + 0.85f * AutoPbr.smoothstep(lumLo, lumHi, luminance(c)));
                        }
                    } else if (auto.emissionMode() == AutoPbr.Emission.BRIGHT && albedo != null) {
                        if (AutoPbr.RULE_FLAME.equals(auto.rule())) {
                            e = auto.emission() * AutoPbr.flameEmission((c >>> 16) & 255, (c >>> 8) & 255, c & 255, a);
                        } else if (AutoPbr.RULE_REDSTONE.equals(auto.rule())) {
                            e = a < 128 ? 0f : auto.emission();
                        } else if (AutoPbr.RULE_CANDLE.equals(auto.rule())) {
                            e = auto.emission() * AutoPbr.candleEmission((c >>> 16) & 255, (c >>> 8) & 255, c & 255, a);
                        } else if (AutoPbr.RULE_ANCHOR.equals(auto.rule())) {
                            e = auto.emission() * AutoPbr.anchorEmission((c >>> 16) & 255, (c >>> 8) & 255, c & 255, a);
                        } else if (AutoPbr.RULE_END_ROD.equals(auto.rule())) {
                            e = auto.emission() * AutoPbr.endRodEmission((c >>> 16) & 255, (c >>> 8) & 255, c & 255, a);
                        } else if (AutoPbr.RULE_MAGMA.equals(auto.rule())) {
                            e = auto.emission() * AutoPbr.magmaEmission((c >>> 16) & 255, (c >>> 8) & 255, c & 255, a);
                        } else if (AutoPbr.RULE_ENCHANTING_TABLE.equals(auto.rule())) {
                            e = AutoPbr.enchantingTableEmission((c >>> 16) & 255, (c >>> 8) & 255, c & 255, a);
                        } else {
                            e = auto.emission() * AutoPbr.brightPixelEmission((c >>> 16) & 255, (c >>> 8) & 255, c & 255, a);
                        }
                    }
                    mat = LabPbr.materialFromAuto(auto, e);
                    if (perPixel) {
                            float l = lum[y * w + x];
                            float dev = Math.abs(l - localMean(lum, w, h, x, y));
                            float rough = auto.roughness();
                            boolean metal = auto.metal();
                            float f0 = auto.f0();
                            float sat = saturation(c);

                            if (AutoPbr.RULE_WATER.equals(auto.rule()) || AutoPbr.RULE_ICE.equals(auto.rule())) {
                                // water (rough 0.02, f0 0.02) and ice keep their own constants: the shader tells water, ice and glass
                                // apart by f0 (rt_common.glsl fetchSurface); the shared glass branch below made all three f0 0.04,
                                // so water was shaded as a glass sheet
                                rough = auto.roughness();
                                f0 = auto.f0();
                                metal = false;
                            } else if (translucentGlass) {
                                rough = 0.02f;
                                f0 = 0.04f;
                                metal = false;
                            } else if (AutoPbr.RULE_END_ROD.equals(auto.rule())) {
                                if (e > 0f) {
                                    rough = 0.10f;
                                    f0 = 0.04f;
                                } else {
                                    rough = 0.85f;
                                    f0 = 0.04f;
                                }
                            } else if (AutoPbr.RULE_MAGMA.equals(auto.rule())) {
                                if (e > 0f) {
                                    rough = 0.15f;
                                    f0 = 0.04f;
                                } else {
                                    rough = 0.85f;
                                    f0 = 0.04f;
                                }
                            } else if (AutoPbr.RULE_ENCHANTING_TABLE.equals(auto.rule())) {
                                int cr = (c >>> 16) & 255;
                                int cg = (c >>> 8) & 255;
                                int cb = c & 255;
                                if (cb >= 170 && cg >= 200) {
                                    rough = 0.08f;
                                    f0 = 0.17f; // diamond IOR 2.42
                                } else if (cr < 60 && cg < 40 && cb < 90) {
                                    rough = 0.15f;
                                    f0 = 0.05f; // obsidian base
                                } else {
                                    rough = 0.85f;
                                    f0 = 0.04f;
                                }
                            } else if (AutoPbr.RULE_REDSTONE.equals(auto.rule())) {
                                rough = 0.9f;
                                f0 = 0.04f;
                            } else if (AutoPbr.RULE_GLASS.equals(auto.rule())) {
                                rough = 0.02f;
                                f0 = 0.04f;
                            } else if (AutoPbr.RULE_WATER.equals(auto.rule())) {
                                rough = 0.02f;
                                f0 = 0.02f;
                            } else if (ore != AutoPbr.OreKind.NONE && opts.metallic()) {
                                boolean mineral;
                                if (ore == AutoPbr.OreKind.COAL) {
                                    mineral = l < meanLum - 0.15f;
                                } else if (spritePath != null && spritePath.toLowerCase(java.util.Locale.ROOT).contains("quartz")) {
                                    mineral = l > 0.65f;
                                } else {
                                    mineral = sat > 0.15f;
                                }
                                if (mineral) {
                                    metal = ore == AutoPbr.OreKind.METAL;
                                    rough = metal ? 0.35f : 0.08f;
                                    f0 = metal ? f0 : 0.05f;
                                } else {
                                    metal = false;
                                    rough = auto.roughness();
                                    f0 = auto.f0();
                                }
                            }

                            if (mixed != AutoPbr.MixedKind.NONE) {
                                if (e > 0f) {
                                    metal = false;
                                    rough = 0.5f;
                                    f0 = 0.04f;
                                } else if (opts.metallic() && AutoPbr.isMetalTexel(c, mixed)) {
                                    metal = true;
                                    rough = 0.35f;
                                } else {
                                    metal = false;
                                    rough = (mixed == AutoPbr.MixedKind.COPPER_BULB || mixed == AutoPbr.MixedKind.REDSTONE_LAMP) ? 0.45f
                                          : (mixed == AutoPbr.MixedKind.CHEST ? 0.85f : 0.7f);
                                    f0 = 0.04f;
                                }
                            }

                            if (opts.roughness() && !translucentGlass && !AutoPbr.RULE_END_ROD.equals(auto.rule()) && !AutoPbr.RULE_MAGMA.equals(auto.rule())
                                && !AutoPbr.RULE_ENCHANTING_TABLE.equals(auto.rule()) && !AutoPbr.RULE_GLASS.equals(auto.rule())
                                && !AutoPbr.RULE_WATER.equals(auto.rule())) {
                                rough = AutoPbr.pixelRoughness(rough, l, meanLum, dev, opts.contrast());
                            }
                            int g = metal ? LabPbr.METAL_G : Math.min(229, Math.round(LabPbr.clamp01(f0) * 255f));
                            mat = LabPbr.pack(Math.round(LabPbr.clamp01(rough) * 255f), g, Math.round(LabPbr.clamp01(e) * 255f), (mat >>> 24) & 255);
                    }
                }
                if (AutoPbr.RULE_REDSTONE.equals(auto.rule())) {
                    mat = LabPbr.withSourceFlag(mat, LabPbr.SOURCE_REDSTONE);
                }
                if (normal != null) {
                    // alpha bit 1 cleared = LabPBR _n present (auto-normal strength does not apply), see LabPbr
                    mat = (mat & 0x00FFFFFF) | ((((mat >>> 24) & 255) & ~2) << 24);
                }
                if (((mat >>> 16) & 255) > 0) {
                    emissive++;
                }
                material[row + x] = mat;
                if (normal != null) {
                    int n = normal.sample(x, y, w, h);
                    normals[row + x] = LabPbr.pack((n >>> 16) & 255, (n >>> 8) & 255, n & 255, (n >>> 24) & 255);
                } else {
                    normals[row + x] = LabPbr.FLAT_NORMAL;
                }
            }
        }
        return emissive;
    }
}
