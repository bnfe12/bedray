package io.github.andrexarlay.vulkancompat.rt.material;

import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;

/**
 * CPU bake of the RT material atlas and normal atlas for the block atlas (same size and layout). Per sprite: LabPBR
 * {@code <texture>_s.png} / {@code <texture>_n.png} from the active resource packs when present, else auto-PBR
 * ({@link AutoPbr}). Images are read with {@code ImageIO} from {@code ResourceProvider.getResource} (javap: returns
 * {@code Optional<Resource>}; {@code Resource.open()} returns an InputStream). Sprite placement: {@code getU0()/getV0()}
 * times the atlas size, {@code contents().width()/height()} (javap of TextureAtlasSprite / SpriteContents).
 */
public final class MaterialAtlasBaker {
    private static final Logger LOGGER = LoggerFactory.getLogger("VKB-RT");
    private static final int MAX_LOGGED_ERRORS = 5;

    public record Result(int width, int height, int[] material, int[] normal, int sprites, int labS, int labN,
                         int emissiveSprites, int metalSprites, int errors, double ms) {
    }

    /** Sprites that got generated normals in the last bake (for the log). */
    public static volatile int autoNormalSprites;

    private MaterialAtlasBaker() {
    }

    public static Result bake(TextureAtlas atlas, int width, int height, ResourceProvider resources) {
        long t0 = System.nanoTime();
        Map<Identifier, TextureAtlasSprite> sprites = ((AtlasSpritesAccess) atlas).vkb$texturesByName();
        if (sprites == null) {
            throw new IllegalStateException("TextureAtlas.texturesByName is null (atlas not uploaded yet)");
        }
        int[] material = new int[width * height];
        int[] normal = new int[width * height];
        Arrays.fill(material, LabPbr.materialFromAuto(AutoPbr.DEFAULT, 0f));
        Arrays.fill(normal, LabPbr.FLAT_NORMAL);
        java.util.concurrent.atomic.AtomicInteger labSA = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger labNA = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger emissiveA = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger metalA = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger errorsA = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger autoN = new java.util.concurrent.atomic.AtomicInteger();
        // sprites occupy disjoint atlas rectangles: the per-sprite work runs in parallel (PNG decoding dominates)
        new java.util.ArrayList<>(sprites.values()).parallelStream().forEach(sprite -> {
            Identifier name = sprite.contents().name();
            int w = sprite.contents().width();
            int h = sprite.contents().height();
            int x0 = Math.round(sprite.getU0() * width);
            int y0 = Math.round(sprite.getV0() * height);
            if (w <= 0 || h <= 0 || x0 < 0 || y0 < 0 || x0 + w > width || y0 + h > height) {
                if (errorsA.incrementAndGet() <= MAX_LOGGED_ERRORS) {
                    LOGGER.warn("[VKB-RT] materials sprite_out_of_atlas name={} rect=({},{} {}x{}) atlas={}x{}", name, x0, y0, w, h,
                        width, height);
                }
                return;
            }
            AutoPbr.Material auto = AutoPbr.classify(name.getPath());
            MaterialRegion.Image spec = null;
            MaterialRegion.Image nrm = null;
            MaterialRegion.Image albedo = null;
            try {
                spec = read(resources, texture(name, "_s"));
                nrm = read(resources, texture(name, "_n"));
                // albedo: for auto-PBR per-pixel materials (mixed blocks, ores, roughness, bright emission) and auto-normals
                if (spec == null || nrm == null) {
                    albedo = read(resources, texture(name, ""));
                }
            } catch (IOException | RuntimeException e) {
                // A broken PNG of a resource pack: this sprite keeps auto-PBR values; counted and logged (not silent).
                if (errorsA.incrementAndGet() <= MAX_LOGGED_ERRORS) {
                    LOGGER.warn("[VKB-RT] materials read_failed sprite={} {}", name, e.toString());
                }
            }
            if (spec != null) {
                labSA.incrementAndGet();
            }
            if (nrm != null) {
                labNA.incrementAndGet();
            }
            if (spec == null && auto.metal()) {
                metalA.incrementAndGet();
            }
            int emissive = MaterialRegion.fill(material, normal, width, x0, y0, w, h, auto, albedo, spec, nrm, name.getPath());
            if (nrm == null && albedo != null) {
                AutoNormals.fill(normal, width, x0, y0, w, h, albedo);
                autoN.incrementAndGet();
            }
            if (emissive > 0) {
                emissiveA.incrementAndGet();
            }
        });
        int labS = labSA.get();
        int labN = labNA.get();
        int emissiveSprites = emissiveA.get();
        int metalSprites = metalA.get();
        int errors = errorsA.get();
        autoNormalSprites = autoN.get();
        double ms = (System.nanoTime() - t0) / 1.0e6;
        return new Result(width, height, material, normal, sprites.size(), labS, labN, emissiveSprites, metalSprites, errors, ms);
    }

    /** {@code minecraft:block/stone} + "_s" -> {@code minecraft:textures/block/stone_s.png}. */
    public static Identifier texture(Identifier sprite, String suffix) {
        return sprite.withPath("textures/" + sprite.getPath() + suffix + ".png");
    }

    private static MaterialRegion.Image read(ResourceProvider resources, Identifier id) throws IOException {
        Optional<Resource> res = resources.getResource(id);
        if (res.isEmpty()) {
            return null;
        }
        try (InputStream in = res.get().open()) {
            BufferedImage img = ImageIO.read(in);
            if (img == null) {
                throw new IOException("ImageIO could not decode " + id);
            }
            int w = img.getWidth();
            int h = img.getHeight();
            return new MaterialRegion.Image(w, h, img.getRGB(0, 0, w, h, null, 0, w));
        }
    }
}
