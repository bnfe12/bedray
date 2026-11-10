package io.github.andrexarlay.vulkancompat.rt.material;

import io.github.andrexarlay.vulkanbridge.platform.LogPaths;
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
    private static final java.util.concurrent.atomic.AtomicInteger LOGGED_ERRORS =
        new java.util.concurrent.atomic.AtomicInteger();

    public record Result(int width, int height, int[] material, int[] normal, int sprites, int labS, int labN,
                         int emissiveSprites, int metalSprites, int errors, double ms) {
    }

    /** One sprite baked off the render thread into tight row-major arrays (row stride {@code w}). */
    public record SpriteBake(int x0, int y0, int w, int h, int[] material, int[] normal,
                             boolean labS, boolean labN, boolean metal, int emissive, boolean autoNormal) {
    }

    /** Sprites that got generated normals in the last bake (for the log). */
    public static volatile int autoNormalSprites;

    private MaterialAtlasBaker() {
    }

    /**
     * Bakes one sprite off the render thread: LabPBR {@code _s}/{@code _n} maps when present, else auto-PBR. Returns null
     * when the sprite rect does not fit the atlas (logged, counted as an error by the caller).
     */
    public static SpriteBake bakeSprite(TextureAtlasSprite sprite, int atlasW, int atlasH, ResourceProvider resources,
                                        OpticalMaterials optics) {
        Identifier name = sprite.contents().name();
        int w = sprite.contents().width();
        int h = sprite.contents().height();
        int x0 = Math.round(sprite.getU0() * atlasW);
        int y0 = Math.round(sprite.getV0() * atlasH);
        if (w <= 0 || h <= 0 || x0 < 0 || y0 < 0 || x0 + w > atlasW || y0 + h > atlasH) {
            if (LOGGED_ERRORS.incrementAndGet() <= MAX_LOGGED_ERRORS) {
                LOGGER.warn("[VKB-RT] materials sprite_out_of_atlas name={} rect=({},{} {}x{}) atlas={}x{}", name, x0, y0, w, h,
                    atlasW, atlasH);
            }
            return null;
        }
        AutoPbr.Material auto = AutoPbr.classify(name.getPath());
        MaterialRegion.Image spec = null;
        MaterialRegion.Image nrm = null;
        MaterialRegion.Image albedo = null;
        try {
            spec = read(resources, texture(name, "_s"));
            nrm = read(resources, texture(name, "_n"));
            // Albedo feeds per-pixel auto-PBR materials and generated normals.
            if (spec == null || nrm == null) {
                albedo = read(resources, texture(name, ""));
            }
        } catch (IOException | RuntimeException e) {
            // A broken PNG of a resource pack: this sprite keeps auto-PBR values; counted and logged (not silent).
            if (LOGGED_ERRORS.incrementAndGet() <= MAX_LOGGED_ERRORS) {
                LOGGER.warn("[VKB-RT] materials read_failed sprite={} {}", name, LogPaths.show(e));
            }
        }
        int[] material = new int[w * h];
        int[] normal = new int[w * h];
        int emissive = MaterialRegion.fill(material, normal, w, 0, 0, w, h, auto, albedo, spec, nrm, name.getPath());
        if (optics != null) optics.apply(material, name, resources);
        boolean autoNormals = false;
        if (nrm == null && albedo != null) {
            AutoNormals.fill(normal, w, 0, 0, w, h, albedo);
            autoNormals = true;
        }
        return new SpriteBake(x0, y0, w, h, material, normal, spec != null, nrm != null,
            spec == null && auto.metal(), emissive, autoNormals);
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
