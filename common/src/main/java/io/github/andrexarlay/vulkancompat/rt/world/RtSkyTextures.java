package io.github.andrexarlay.vulkancompat.rt.world;

import com.mojang.blaze3d.platform.NativeImage;
import io.github.andrexarlay.vulkancompat.resources.ResourceReloadEpoch;
import io.github.andrexarlay.vulkanbridge.platform.LogPaths;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.world.level.MoonPhase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Optional;

/** Minecraft resource capture for the native sky atlas. Java holds only the packed CPU input. */
public final class RtSkyTextures {
    private static final Logger LOGGER = LoggerFactory.getLogger("VKB-SKY");
    public static final int SLOT_SUN = 0;
    public static final int SLOT_MOON = 1;
    public static final int SLOT_CLOUDS = 9;
    public static final int SLOT_END_SKY = 10;
    public static final int SLOT_END_FLASH = 11;
    public static final int SLOTS = SkyTextureAtlasData.SLOTS;

    private long loadedEpoch;
    private ByteBuffer bytes;
    private int words, loadedMask, cloudW, cloudH;

    long epoch() { return loadedEpoch; }
    ByteBuffer bytes() { return bytes; }
    public int wordCount() { return words; }
    public int loadedMask() { return loadedMask; }
    public int cloudWidth() { return cloudW; }
    public int cloudHeight() { return cloudH; }

    public void prepare() {
        long requested = ResourceReloadEpoch.current();
        if (loadedEpoch == requested) return;
        var data = loadAtlas();
        if (data.byteCount() > (256L << 20)) throw new RtFailure("sky_atlas_capture", "sky atlas exceeds 256 MiB");
        var packed = ByteBuffer.allocate(Math.toIntExact(data.byteCount())).order(ByteOrder.nativeOrder());
        data.writeTo(packed.asIntBuffer());
        bytes = packed.asReadOnlyBuffer();
        words = data.wordCount();
        loadedMask = data.loadedMask();
        cloudW = data.cloudWidth();
        cloudH = data.cloudHeight();
        loadedEpoch = requested;
        LOGGER.info("[VKB-SKY] atlas captured epoch={} loaded_mask=0x{} words={} clouds={}x{}",
            loadedEpoch, Integer.toHexString(loadedMask), words, cloudW, cloudH);
    }

    /** 4×2 legacy moon sheet fallback, preserving the public extraction helper. */
    public static int[] extractMoonPhaseTile(int[] pixels, int width, int height, int phase) {
        return SkyTextureAtlasData.moonPhaseTile(pixels, width, height, phase);
    }

    private static SkyTextureAtlasData loadAtlas() {
        Minecraft minecraft = Minecraft.getInstance();
        var resources = minecraft.getResourceManager();
        Identifier[] ids = new Identifier[SLOTS];
        ids[SLOT_SUN] = id("celestial/sun.png");
        for (MoonPhase phase : MoonPhase.values())
            ids[SLOT_MOON + phase.index()] = id("celestial/moon/" + phase.getSerializedName() + ".png");
        ids[SLOT_CLOUDS] = id("clouds.png");
        ids[SLOT_END_SKY] = id("end_sky.png");
        ids[SLOT_END_FLASH] = id("celestial/end_flash.png");
        Identifier sheetId = id("moon_phases.png");
        var sheet = read(resources.getResource(sheetId), sheetId, -1);
        SkyTextureAtlasData.Texture[] textures = new SkyTextureAtlasData.Texture[SLOTS];
        for (int slot = 0; slot < SLOTS; slot++) {
            Identifier location = ids[slot];
            if (location == null) continue;
            Optional<Resource> resource = resources.getResource(location);
            if (resource.isEmpty() && slot == SLOT_SUN) resource = resources.getResource(id("sun.png"));
            if (resource.isEmpty() && slot == SLOT_END_FLASH) resource = resources.getResource(id("end_flash.png"));
            if (resource.isEmpty() && slot >= SLOT_MOON && slot < SLOT_MOON + 8 && sheet != null) {
                int[] tile = extractMoonPhaseTile(sheet.pixels(), sheet.width(), sheet.height(), slot - SLOT_MOON);
                if (tile.length != 0) {
                    textures[slot] = new SkyTextureAtlasData.Texture(sheet.width() / 4, sheet.height() / 2, tile);
                    continue;
                }
            }
            textures[slot] = read(resource, location, slot);
            if (textures[slot] == null)
                LOGGER.warn("[VKB-SKY] texture unavailable id={} slot={}; using shader fallback", location, slot);
        }
        return SkyTextureAtlasData.pack(textures);
    }

    private static Identifier id(String name) {
        return Identifier.withDefaultNamespace("textures/environment/" + name);
    }

    private static SkyTextureAtlasData.Texture read(Optional<Resource> resource, Identifier id, int slot) {
        if (resource.isEmpty()) return null;
        try (InputStream input = resource.get().open(); NativeImage image = NativeImage.read(input)) {
            var texture = new SkyTextureAtlasData.Texture(image.getWidth(), image.getHeight(), image.getPixelsABGR());
            LOGGER.info("[VKB-SKY] texture loaded slot={} id={} extent={}x{} pack={}",
                slot, id, texture.width(), texture.height(), resource.get().sourcePackId());
            return texture;
        } catch (Exception failure) {
            LOGGER.error("[VKB-SKY] texture load failed slot={} id={} pack={}\n{}", slot, id, resource.get().sourcePackId(), LogPaths.stack(failure));
            return null;
        }
    }
}