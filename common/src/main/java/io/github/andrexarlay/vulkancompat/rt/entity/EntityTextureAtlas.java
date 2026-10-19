package io.github.andrexarlay.vulkancompat.rt.entity;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * CPU side of the RT entity texture atlas: every texture an entity / block entity / item of the RT scene uses is loaded once
 * from the resource manager (the PNG of the active resource packs; for atlas sprites the sprite PNG
 * {@code <ns>:textures/<sprite name>.png}, frame 0 only) and shelf-packed into one RGBA8 (ABGR) image. The GPU copy and the
 * rectangle table are uploaded by {@code RtEntityGeometryManager} when {@link #version()} changes.
 * Slot 0 = 1x1 white (no texture / not loadable). Cleared on a resource pack change ({@link #checkReload}).
 * <p>
 * Emission per texel rides in the texel alpha: a texel with alpha &lt; {@link #ALPHA_CUTOFF_CODE} (transparent, the alpha test of
 * the shader is alpha &gt;= 0.1 = 26) keeps its alpha; every other texel gets alpha {@code 0xE0 | e5}, where {@code e5} is the 5 bit
 * emission (0..31) of the texel from the same auto-PBR rules as the block atlas ({@code MaterialRegion.emissionMask}, by the
 * sprite / texture name). The shader treats every such texel as opaque (as before: only alpha &gt;= 0.1 mattered) and reads
 * {@code (alpha - 224) / 31} as emission ({@code entityEmission} in rt_common.glsl).
 */
public final class EntityTextureAtlas {
    private static final Logger LOGGER = LoggerFactory.getLogger("VK-ENTITY-ATLAS");
    public static final EntityTextureAtlas INSTANCE = new EntityTextureAtlas();
    public static final int SIZE = 2048;
    public static final int MAX_SLOTS = 256;
    /** Alpha of an opaque texel carries the emission code in its low 5 bits (see the class comment). */
    public static final int ALPHA_EMISSION_BASE = 0xE0;
    /** Texels with an alpha below this stay as they are (the shader alpha test is alpha >= 26 / 255). */
    public static final int ALPHA_CUTOFF_CODE = 26;
    /** Texels with an alpha from this (0.95) up are opaque and carry the emission code; texels in between keep their alpha. */
    public static final int ALPHA_OPAQUE_MIN = 242;

    private final int[] pixels = new int[SIZE * SIZE];
    /** x, y, w, h per slot (texels). */
    private final int[] rects = new int[MAX_SLOTS * 4];
    /** Emissive texels per slot (emission code > 0), for a quick reject in the emitter capture. */
    private final int[] emissiveTexels = new int[MAX_SLOTS];
    private final Map<Identifier, Integer> slots = new HashMap<>();
    private final Map<Identifier, Integer> glowingFontSlots = new HashMap<>();
    private int slotCount;
    private int shelfX;
    private int shelfY;
    private int shelfH;
    private long version;
    private int failures;
    private int packsHash = Integer.MIN_VALUE;

    private EntityTextureAtlas() {
        clear();
    }

    private void clear() {
        java.util.Arrays.fill(pixels, 0);
        java.util.Arrays.fill(rects, 0);
        slots.clear();
        glowingFontSlots.clear();
        java.util.Arrays.fill(emissiveTexels, 0);
        pixels[0] = (ALPHA_EMISSION_BASE << 24) | 0x00FFFFFF;
        rects[0] = 0;
        rects[1] = 0;
        rects[2] = 1;
        rects[3] = 1;
        slotCount = 1;
        shelfX = 2;
        shelfY = 0;
        shelfH = 2;
        version++;
    }

    /** Re-bake trigger: the selected resource packs changed (texture contents of every slot may differ). Render thread. */
    public synchronized void checkReload() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.getResourcePackRepository() == null) {
            return;
        }
        // selected ids (user choice) and the pack list of the live ResourceManager (swapped when the async reload finishes)
        int h = mc.getResourcePackRepository().getSelectedIds().hashCode() * 31
            + mc.getResourceManager().listPacks().map(net.minecraft.server.packs.PackResources::packId).toList().hashCode();
        if (h != packsHash) {
            if (packsHash != Integer.MIN_VALUE) {
                LOGGER.info("[VK-ENTITY-ATLAS] resource packs changed -> atlas rebaked (slots dropped={})", slotCount - 1);
            }
            packsHash = h;
            clear();
        }
    }

    /** Slot of a texture file ({@code ns:textures/....png}); frameW/H > 0 crop to the first frame (animated sprites). */
    public synchronized int slot(Identifier texture, int frameW, int frameH) {
        if (texture == null) {
            return 0;
        }
        Integer s = slots.get(texture);
        if (s != null) {
            return s;
        }
        int slot = load(texture, frameW, frameH);
        slots.put(texture, slot);
        return slot;
    }

    /** Duplicate one bitmap font texture with alpha-only emission for outlined sign text; normal glyph slots stay non-emissive. */
    public synchronized int glowingFontSlot(Identifier texture) {
        if (texture == null) return 0;
        Integer cached = glowingFontSlots.get(texture);
        if (cached != null) return cached;
        Optional<net.minecraft.server.packs.resources.Resource> res = Minecraft.getInstance().getResourceManager().getResource(texture);
        if (res.isEmpty()) return fail(texture, "font_texture_not_found");
        try (InputStream in = res.get().open(); NativeImage img = NativeImage.read(in)) {
            int slot = packGlowingFont(texture, img);
            glowingFontSlots.put(texture, slot);
            return slot;
        } catch (Exception e) {
            LOGGER.warn("[VK-ENTITY-ATLAS] glowing font texture load failed texture={}", texture, e);
            return fail(texture, "font_texture_read_error");
        }
    }

    /** Owns the freshly loaded font image; its glyph coverage is cutout, never a glass material. */
    synchronized int packGlowingFont(Identifier texture, NativeImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        float[] mask = new float[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int pixel = image.getPixel(x, y);
                boolean glyph = (pixel >>> 24) >= 128;
                image.setPixel(x, y, (pixel & 0x00FFFFFF) | (glyph ? 0xFF000000 : 0));
                mask[y * width + x] = glyph ? 1f : 0f;
            }
        }
        return pack(texture, image, 0, 0, "font-outline-emissive", mask);
    }
    /** Capture the stitched sprite pixels, including generated paletted armor trims with no resource PNG. */
    public synchronized int spriteSlot(net.minecraft.client.renderer.texture.SpriteContents sprite) {
        if (sprite == null) return 0;
        Identifier texture = Identifier.fromNamespaceAndPath(sprite.name().getNamespace(),
                "textures/" + sprite.name().getPath() + ".png");
        Integer cached = slots.get(texture);
        if (cached != null && cached > 0) return cached;
        if (sprite instanceof io.github.andrexarlay.vulkancompat.mixin.rt.MixinSpriteContentsAccessor access) {
            NativeImage image = access.vkb$originalImage();
            if (image != null) {
                if (slotCount >= MAX_SLOTS) return fail(texture, "slots_full");
                // SpriteContents owns this image; copy the first frame without closing or modifying it.
                int slot = pack(texture, image, sprite.width(), sprite.height(), "sprite", null);
                slots.put(texture, slot);
                return slot;
            }
        }
        return spriteSlot(sprite.name(), sprite.width(), sprite.height());
    }
    /** Slot of an atlas sprite by sprite name ({@code minecraft:item/diamond_sword} -> {@code minecraft:textures/item/diamond_sword.png}). */
    public int spriteSlot(Identifier spriteName, int frameW, int frameH) {
        if (spriteName == null) {
            return 0;
        }
        return slot(Identifier.fromNamespaceAndPath(spriteName.getNamespace(), "textures/" + spriteName.getPath() + ".png"), frameW, frameH);
    }

    private int load(Identifier tex, int frameW, int frameH) {
        if (slotCount >= MAX_SLOTS) {
            return fail(tex, "slots_full");
        }
        Optional<net.minecraft.server.packs.resources.Resource> res = Minecraft.getInstance().getResourceManager().getResource(tex);
        if (res.isEmpty()) {
            // textures without a resource PNG (downloaded player skins, generated textures): a DynamicTexture keeps its pixels
            // on the CPU (javap 26.3: DynamicTexture.getPixels()) - copied from there, no GPU readback needed
            var t = Minecraft.getInstance().getTextureManager().getTexture(tex);
            if (t instanceof net.minecraft.client.renderer.texture.DynamicTexture dt && dt.getPixels() != null) {
                return pack(tex, dt.getPixels(), frameW, frameH, "dynamic", null);
            }
            // stitched atlases (signs, banners, chests, items: textures/atlas/*.png) have no PNG resource; the model UVs are
            // atlas UVs, so the whole atlas is rebuilt on the CPU from the sprites' original images at their atlas positions
            if (t instanceof net.minecraft.client.renderer.texture.TextureAtlas atlas) {
                return loadAtlas(tex, atlas);
            }
            return fail(tex, "not_found");
        }
        try (InputStream in = res.get().open(); NativeImage img = NativeImage.read(in)) {
            return pack(tex, img, frameW, frameH, "resource", null);
        } catch (Exception e) {
            LOGGER.error("[VK-ENTITY-ATLAS] load failed texture={}", tex, e);
            return fail(tex, "read_error");
        }
    }

    private int loadAtlas(Identifier tex, net.minecraft.client.renderer.texture.TextureAtlas atlas) {
        var acc = (io.github.andrexarlay.vulkancompat.mixin.rt.MixinTextureAtlasAccessor) atlas;
        int aw = acc.vkb$width();
        int ah = acc.vkb$height();
        if (aw <= 0 || ah <= 0 || aw > SIZE || ah > SIZE) {
            LOGGER.warn("[VK-ENTITY-ATLAS] atlas {} is {}x{}, does not fit the {}x{} entity atlas", tex, aw, ah, SIZE, SIZE);
            return fail(tex, "atlas_too_big");
        }
        int sprites = 0;
        int missing = 0;
        float[] mask = new float[aw * ah];
        try (NativeImage img = new NativeImage(aw, ah, true)) {
            for (var sprite : acc.vkb$sprites()) {
                NativeImage src = ((io.github.andrexarlay.vulkancompat.mixin.rt.MixinSpriteContentsAccessor) sprite.contents()).vkb$originalImage();
                if (src == null) {
                    missing++;
                    continue;
                }
                int w = Math.min(sprite.contents().width(), src.getWidth());
                int h = Math.min(sprite.contents().height(), src.getHeight());
                int x0 = sprite.getX();
                int y0 = sprite.getY();
                int[] spriteAbgr = new int[w * h];
                for (int y = 0; y < h && y0 + y < ah; y++) {
                    for (int x = 0; x < w && x0 + x < aw; x++) {
                        int px = src.getPixel(x, y);
                        img.setPixel(x0 + x, y0 + y, px);   // getPixel/setPixel share one convention (javap 26.3)
                        spriteAbgr[y * w + x] = px;
                    }
                }
                float[] spriteMask = maskOf(sprite.contents().name().getPath(), spriteAbgr, w, w, h);
                if (spriteMask != null) {
                    for (int y = 0; y < h && y0 + y < ah; y++) {
                        for (int x = 0; x < w && x0 + x < aw; x++) {
                            mask[(y0 + y) * aw + x0 + x] = spriteMask[y * w + x];
                        }
                    }
                }
                sprites++;
            }
            LOGGER.info("[VK-ENTITY-ATLAS] stitched atlas {} {}x{} sprites={} missing_images={}", tex, aw, ah, sprites, missing);
            return pack(tex, img, 0, 0, "atlas", mask);
        } catch (Exception e) {
            LOGGER.error("[VK-ENTITY-ATLAS] atlas stitch failed texture={}", tex, e);
            return fail(tex, "atlas_error");
        }
    }

    private int pack(Identifier tex, NativeImage img, int frameW, int frameH, String source, float[] explicitMask) {
        try {
            int w = frameW > 0 ? Math.min(frameW, img.getWidth()) : img.getWidth();
            int h = frameH > 0 ? Math.min(frameH, img.getHeight()) : img.getHeight();
            if (shelfX + w > SIZE) {
                shelfX = 0;
                shelfY += shelfH;
                shelfH = 0;
            }
            if (w > SIZE || shelfY + h > SIZE) {
                return fail(tex, "atlas_full");
            }
            int[] src = img.getPixelsABGR();
            int iw = img.getWidth();
            for (int y = 0; y < h; y++) {
                System.arraycopy(src, y * iw, pixels, (shelfY + y) * SIZE + shelfX, w);
            }
            // emission per texel: explicit (stitched atlas, built per sprite) or by the name of the texture
            float[] mask = explicitMask != null ? explicitMask : maskOf(spritePath(tex), src, iw, w, h);
            int maskStride = explicitMask != null ? iw : w;
            int emissive = encodeEmission(shelfX, shelfY, w, h, mask, maskStride);
            ItemPbr pbr = ItemPbr.classify(spritePath(tex));
            int roughU8 = Math.round(pbr.roughness() * 255f) & 0xFF;
            int metalU8 = Math.round(pbr.metal() * 255f) & 0xFF;
            int f0U8 = Math.round(pbr.f0() * 255f) & 0xFF;
            int flags = 1; // has PBR metadata
            int slot = slotCount++;
            emissiveTexels[slot] = emissive;
            rects[slot * 4] = shelfX;
            rects[slot * 4 + 1] = shelfY;
            rects[slot * 4 + 2] = (w & 0xFFFF) | (roughU8 << 16) | (metalU8 << 24);
            rects[slot * 4 + 3] = (h & 0xFFFF) | (f0U8 << 16) | (flags << 24);
            shelfX += w;
            shelfH = Math.max(shelfH, h);
            version++;
            LOGGER.info("[VK-ENTITY-ATLAS] slot={} texture={} source={} size={}x{} at={},{} emissive_texels={} pbr={}", slot, tex, source, w, h,
                rects[slot * 4], rects[slot * 4 + 1], emissive, pbr);
            return slot;
        } catch (RuntimeException e) {
            LOGGER.error("[VK-ENTITY-ATLAS] pack failed texture={}", tex, e);
            return fail(tex, "pack_error");
        }
    }

    /** {@code textures/item/torch.png} -> {@code item/torch} (the sprite path the auto-PBR rules of AutoPbr match). */
    static String spritePath(Identifier tex) {
        String p = tex.getPath();
        if (p.startsWith("textures/")) {
            p = p.substring("textures/".length());
        }
        if (p.endsWith(".png")) {
            p = p.substring(0, p.length() - 4);
        }
        return p;
    }

    /**
     * Emission mask (w x h, row stride w) of a region of ABGR pixels ({@code stride} ints per row) by the auto-PBR rules of the
     * sprite path; null = nothing emits.
     */
    static float[] maskOf(String spritePath, int[] abgr, int stride, int w, int h) {
        io.github.andrexarlay.vulkancompat.rt.material.AutoPbr.Material m =
            io.github.andrexarlay.vulkancompat.rt.material.AutoPbr.classify(spritePath);
        io.github.andrexarlay.vulkancompat.rt.material.AutoPbr.MixedKind mixed =
            io.github.andrexarlay.vulkancompat.rt.material.AutoPbr.mixedKind(spritePath);
        if (m.emissionMode() == io.github.andrexarlay.vulkancompat.rt.material.AutoPbr.Emission.NONE) {
            return null;
        }
        int[] argb = new int[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int c = abgr[y * stride + x];
                argb[y * w + x] = (c & 0xFF00FF00) | ((c & 0xFF) << 16) | ((c >>> 16) & 0xFF);
            }
        }
        return io.github.andrexarlay.vulkancompat.rt.material.MaterialRegion.emissionMask(m, mixed, argb, w, h);
    }

    /**
     * Rewrites the alpha of the opaque texels of the region at (x0, y0) with the emission code (class comment); returns the
     * number of emissive texels. {@code mask} null = no emission, row stride {@code maskStride}.
     */
    private int encodeEmission(int x0, int y0, int w, int h, float[] mask, int maskStride) {
        int emissive = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int i = (y0 + y) * SIZE + x0 + x;
                int px = pixels[i];
                int texelAlpha = px >>> 24;
                if (texelAlpha < ALPHA_CUTOFF_CODE) {
                    continue;
                }
                if (texelAlpha < ALPHA_OPAQUE_MIN) {
                    // translucent texel (stained glass, ice, slime): keeps its alpha (below the emission code range) and does not emit
                    pixels[i] = (px & 0x00FFFFFF) | (Math.min(texelAlpha, ALPHA_EMISSION_BASE - 1) << 24);
                    continue;
                }
                int code = mask == null ? 0 : Math.round(Math.max(0f, Math.min(1f, mask[y * maskStride + x])) * 31f);
                if (code > 0) {
                    emissive++;
                }
                pixels[i] = (px & 0x00FFFFFF) | ((ALPHA_EMISSION_BASE | code) << 24);
            }
        }
        return emissive;
    }

    /** Emission 0..1 of a texel (ABGR int of the atlas) from its alpha code; 0 for transparent texels. */
    public static float emissionOf(int abgr) {
        int a = abgr >>> 24;
        return a >= ALPHA_EMISSION_BASE ? (a & 31) / 31f : 0f;
    }

    /** True when any texel of the slot emits (quick reject for the emitter capture). */
    public synchronized boolean hasEmission(int slot) {
        return slot > 0 && slot < slotCount && emissiveTexels[slot] > 0;
    }

    /** Emission view of the rectangle of a slot, texture coordinates 0..1 over the slot (as the entity vertex UVs). */
    public record ItemPbr(float roughness, float metal, float f0) {
        public static final ItemPbr DEFAULT = new ItemPbr(0.85f, 0.0f, 0.04f);
        public static final ItemPbr IRON = new ItemPbr(0.35f, 1.0f, 0.04f);
        public static final ItemPbr GOLD = new ItemPbr(0.25f, 1.0f, 0.04f);
        public static final ItemPbr NETHERITE = new ItemPbr(0.40f, 1.0f, 0.04f);
        public static final ItemPbr COPPER = new ItemPbr(0.30f, 1.0f, 0.04f);
        public static final ItemPbr DIAMOND = new ItemPbr(0.05f, 0.0f, 0.17f); // IOR 2.42 -> f0 = 0.17

        public static ItemPbr classify(String path) {
            if (path == null) {
                return DEFAULT;
            }
            String p = path.toLowerCase(java.util.Locale.ROOT);
            int slash = p.lastIndexOf('/');
            String n = slash >= 0 ? p.substring(slash + 1) : p;

            if (n.contains("diamond")) {
                return DIAMOND;
            }
            if (n.contains("netherite")) {
                return NETHERITE;
            }
            if (n.contains("gold") || n.contains("golden")) {
                return GOLD;
            }
            if (n.contains("copper")) {
                return COPPER;
            }
            if (n.contains("iron") || n.contains("chainmail")) {
                return IRON;
            }
            return DEFAULT;
        }
    }

    /** Decoded ItemPbr of a slot (for testing / inspection). */
    public synchronized ItemPbr itemPbr(int slot) {
        if (slot <= 0 || slot >= slotCount) {
            return ItemPbr.DEFAULT;
        }
        int rz = rects[slot * 4 + 2];
        int rw = rects[slot * 4 + 3];
        if (((rw >>> 24) & 1) == 0) {
            return ItemPbr.DEFAULT;
        }
        float rough = ((rz >>> 16) & 255) / 255f;
        float metal = ((rz >>> 24) & 255) / 255f;
        float f0 = ((rw >>> 16) & 255) / 255f;
        return new ItemPbr(rough, metal, f0);
    }

    public synchronized io.github.andrexarlay.vulkancompat.rt.world.RtEmitters.TexelEmission texels(int slot) {
        final int rx = rects[slot * 4];
        final int ry = rects[slot * 4 + 1];
        final int rw = Math.max(1, rects[slot * 4 + 2] & 0xFFFF);
        final int rh = Math.max(1, rects[slot * 4 + 3] & 0xFFFF);
        return new io.github.andrexarlay.vulkancompat.rt.world.RtEmitters.TexelEmission() {
            @Override
            public int width() {
                return rw;
            }

            @Override
            public int height() {
                return rh;
            }

            @Override
            public float at(int x, int y) {
                return emissionOf(pixels[(ry + y) * SIZE + rx + x]);
            }
        };
    }

    private int fail(Identifier tex, String why) {
        if (failures++ < 32) {
            LOGGER.warn("[VK-ENTITY-ATLAS] no texture slot for {} ({}), white is used", tex, why);
        }
        return 0;
    }

    public synchronized long version() {
        return version;
    }

    public synchronized int slotCount() {
        return slotCount;
    }

    /** Copies pixels and rects (ABGR / int4) to the given mapped addresses. */
    public synchronized void copyTo(long pixelAddr, long rectAddr) {
        org.lwjgl.system.MemoryUtil.memIntBuffer(pixelAddr, pixels.length).put(pixels);
        org.lwjgl.system.MemoryUtil.memIntBuffer(rectAddr, rects.length).put(rects);
    }
}
