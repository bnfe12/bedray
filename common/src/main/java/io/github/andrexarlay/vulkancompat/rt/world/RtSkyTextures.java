package io.github.andrexarlay.vulkancompat.rt.world;

import com.mojang.blaze3d.platform.NativeImage;
import io.github.andrexarlay.vulkanbridge.platform.LogPaths;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.MoonPhase;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkDevice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Sky textures of the RT passes, read from the active resource packs (so a resource pack's sun / moon / clouds are used):
 * <ul>
 *   <li>slot 0: {@code minecraft:textures/environment/celestial/sun.png}</li>
 *   <li>slots 1..8: {@code minecraft:textures/environment/celestial/moon/<phase>.png}, slot = 1 + MoonPhase.index()</li>
 *   <li>slot 9: {@code minecraft:textures/environment/clouds.png}</li>
 * </ul>
 * Paths: the same files the 26.3 game uses. The sun and moon are sprites of the celestials atlas (SkyRenderer:
 * {@code atlas.getSprite(withDefaultNamespace("sun"))}, {@code "moon/" + phase.getSerializedName()}; sprite name -> file
 * {@code textures/environment/celestial/<name>.png}, listed in the 26.3 client jar), the clouds texture is read by
 * CloudRenderer.prepare from {@code textures/environment/clouds.png} (decompiled 26.3, local reference).
 * <p>
 * GPU layout (one host-visible UIntBuf, read through {@code StatsBuf.skyTexAddr}): header of {@link #SLOTS} x 4 uints
 * (pixel offset in uints, width, height, 1 = loaded), then the pixels as ABGR (NativeImage.getPixelsABGR, red in the low
 * byte). Reloaded when the resource pack list changes (same trigger as EntityTextureAtlas.checkReload).
 */
public final class RtSkyTextures {
    private static final Logger LOGGER = LoggerFactory.getLogger("VKB-SKY");
    public static final int SLOT_SUN = 0;
    public static final int SLOT_MOON = 1;
    public static final int SLOT_CLOUDS = 9;
    public static final int SLOT_END_SKY = 10;
    public static final int SLOT_END_FLASH = 11;
    public static final int SLOTS = 12;
    private static final int HEADER_UINTS = SLOTS * 4;

    private final RtMemory memory;
    private RtMemory.Buf buf;
    private int packsHash = Integer.MIN_VALUE;
    private int loadedMask;
    private int cloudW;
    private int cloudH;

    public RtSkyTextures(RtMemory memory) {
        this.memory = memory;
    }

    /** Device address of the texture buffer (0 before the first load). */
    public long address() {
        return buf != null ? buf.address : 0L;
    }

    /** Bit i set = slot i was loaded from a resource. */
    public int loadedMask() {
        return loadedMask;
    }

    public int cloudWidth() {
        return cloudW;
    }

    public int cloudHeight() {
        return cloudH;
    }

    /**
     * Extracts a single moon phase tile (0..7) from a 4x2 {@code moon_phases.png} sprite sheet.
     * Column = {@code phase % 4}, Row = {@code (phase / 4) % 2}.
     */
    public static int[] extractMoonPhaseTile(int[] sheetPixels, int sheetW, int sheetH, int phase) {
        int tileW = sheetW / 4;
        int tileH = sheetH / 2;
        if (tileW <= 0 || tileH <= 0 || sheetPixels == null || sheetPixels.length < sheetW * sheetH) {
            return new int[0];
        }
        int p = phase & 7;
        int col = p & 3;
        int row = (p >>> 2) & 1;
        int x0 = col * tileW;
        int y0 = row * tileH;
        int[] out = new int[tileW * tileH];
        for (int y = 0; y < tileH; y++) {
            System.arraycopy(sheetPixels, (y0 + y) * sheetW + x0, out, y * tileW, tileW);
        }
        return out;
    }

    /** Loads the textures on the first call and again when the resource packs changed. Render thread. */
    public void ensure(VkDevice device) {
        Minecraft mc = Minecraft.getInstance();
        int h = mc.getResourcePackRepository().getSelectedIds().hashCode() * 31
            + mc.getResourceManager().listPacks().map(net.minecraft.server.packs.PackResources::packId).toList().hashCode();
        if (h == packsHash && buf != null) {
            return;
        }
        packsHash = h;
        List<Identifier> ids = new ArrayList<>(SLOTS);
        ids.add(Identifier.withDefaultNamespace("textures/environment/celestial/sun.png"));
        MoonPhase[] phases = MoonPhase.values();
        Identifier[] moon = new Identifier[8];
        for (MoonPhase p : phases) {
            moon[p.index()] = Identifier.withDefaultNamespace("textures/environment/celestial/moon/" + p.getSerializedName() + ".png");
        }
        for (Identifier m : moon) {
            ids.add(m);
        }
        ids.add(Identifier.withDefaultNamespace("textures/environment/clouds.png"));
        ids.add(Identifier.withDefaultNamespace("textures/environment/end_sky.png"));
        ids.add(Identifier.withDefaultNamespace("textures/environment/celestial/end_flash.png"));

        int[] sheetPixels = null;
        int sheetW = 0;
        int sheetH = 0;
        Optional<net.minecraft.server.packs.resources.Resource> moonSheetRes =
            mc.getResourceManager().getResource(Identifier.withDefaultNamespace("textures/environment/moon_phases.png"));
        if (moonSheetRes.isPresent()) {
            try (InputStream in = moonSheetRes.get().open(); NativeImage img = NativeImage.read(in)) {
                sheetW = img.getWidth();
                sheetH = img.getHeight();
                sheetPixels = img.getPixelsABGR();
            } catch (Exception e) {
                LOGGER.warn("[VKB-SKY] moon_phases.png fallback load failed\n{}", LogPaths.stack(e));
            }
        }

        int[][] pixels = new int[SLOTS][];
        int[] w = new int[SLOTS];
        int[] hh = new int[SLOTS];
        int mask = 0;
        long total = HEADER_UINTS;
        for (int i = 0; i < SLOTS; i++) {
            Identifier id = ids.get(i);
            if (id == null) {
                continue;
            }
            Optional<net.minecraft.server.packs.resources.Resource> res = mc.getResourceManager().getResource(id);
            if (res.isEmpty() && i == SLOT_SUN) {
                res = mc.getResourceManager().getResource(Identifier.withDefaultNamespace("textures/environment/sun.png"));
            }
            if (res.isEmpty() && i == SLOT_END_FLASH) {
                res = mc.getResourceManager().getResource(Identifier.withDefaultNamespace("textures/environment/end_flash.png"));
            }
            if (res.isEmpty()) {
                if (i >= SLOT_MOON && i < SLOT_MOON + 8 && sheetPixels != null && sheetW >= 4 && sheetH >= 2) {
                    int phase = i - SLOT_MOON;
                    w[i] = sheetW / 4;
                    hh[i] = sheetH / 2;
                    pixels[i] = extractMoonPhaseTile(sheetPixels, sheetW, sheetH, phase);
                    total += (long) w[i] * hh[i];
                    mask |= 1 << i;
                    continue;
                }
                LOGGER.warn("[VKB-SKY] texture missing {} (slot {}): the shader draws a plain square / no clouds", id, i);
                continue;
            }
            try (InputStream in = res.get().open(); NativeImage img = NativeImage.read(in)) {
                w[i] = img.getWidth();
                hh[i] = img.getHeight();
                pixels[i] = img.getPixelsABGR();
                total += (long) w[i] * hh[i];
                mask |= 1 << i;
                LOGGER.info("[VKB-SKY] texture slot={} id={} size={}x{} pack={}", i, id, w[i], hh[i], res.get().sourcePackId());
            } catch (Exception e) {
                LOGGER.error("[VKB-SKY] texture load failed {} (slot {})\n{}", id, i, LogPaths.stack(e));
                pixels[i] = null;
            }
        }
        if (buf != null) {
            // rare (resource pack change): the frames in flight may still read the old buffer
            VK10.vkDeviceWaitIdle(device);
            memory.destroy(buf);
            buf = null;
        }
        buf = memory.createBuffer("rt_sky_textures", total * 4L,
            VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT, true);
        long base = buf.mapped;
        int offset = HEADER_UINTS;
        for (int i = 0; i < SLOTS; i++) {
            long hdr = base + i * 16L;
            if (pixels[i] == null) {
                MemoryUtil.memPutInt(hdr, 0);
                MemoryUtil.memPutInt(hdr + 4, 0);
                MemoryUtil.memPutInt(hdr + 8, 0);
                MemoryUtil.memPutInt(hdr + 12, 0);
                continue;
            }
            MemoryUtil.memPutInt(hdr, offset);
            MemoryUtil.memPutInt(hdr + 4, w[i]);
            MemoryUtil.memPutInt(hdr + 8, hh[i]);
            MemoryUtil.memPutInt(hdr + 12, 1);
            MemoryUtil.memIntBuffer(base + offset * 4L, pixels[i].length).put(pixels[i]);
            offset += pixels[i].length;
        }
        loadedMask = mask;
        cloudW = pixels[SLOT_CLOUDS] != null ? w[SLOT_CLOUDS] : 0;
        cloudH = pixels[SLOT_CLOUDS] != null ? hh[SLOT_CLOUDS] : 0;
        LOGGER.info("[VKB-SKY] textures uploaded loaded_mask=0x{} bytes={} clouds={}x{}", Integer.toHexString(mask), total * 4L,
            cloudW, cloudH);
    }

    public void destroy() {
        if (buf != null) {
            memory.destroy(buf);
            buf = null;
        }
        packsHash = Integer.MIN_VALUE;
    }
}
