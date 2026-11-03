package io.github.andrexarlay.vulkancompat.rt.world;

import io.github.andrexarlay.vulkanbridge.platform.LogPaths;
import com.mojang.blaze3d.platform.NativeImage;
import io.github.andrexarlay.vulkancompat.resources.ResourceReloadEpoch;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.world.level.MoonPhase;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VK12;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.util.Objects;
import java.util.Optional;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** Immutable pack sky atlas; replacement runs before command recording with explicit host completion proof. */
public final class RtSkyTextures {
    private static final Logger LOGGER = LoggerFactory.getLogger("VKB-SKY");
    public static final int SLOT_SUN = 0;
    public static final int SLOT_MOON = 1;
    public static final int SLOT_CLOUDS = 9;
    public static final int SLOT_END_SKY = 10;
    public static final int SLOT_END_FLASH = 11;
    public static final int SLOTS = SkyTextureAtlasData.SLOTS;

    interface Storage {
        RtMemory.Buf create(long bytes);
        void upload(RtMemory.Buf buffer, SkyTextureAtlasData data);
        void destroy(RtMemory.Buf buffer);
    }

    private final Storage storage;
    private final Supplier<SkyTextureAtlasData> load;
    private final LongSupplier epoch;
    private RtMemory.Buf buf;
    /** A failed rollback keeps its allocation reachable without allocating bookkeeping during OOM. */
    private RtMemory.Buf abandoned;
    private long loadedEpoch = Long.MIN_VALUE;
    private int words, loadedMask, cloudW, cloudH;

    public RtSkyTextures(RtMemory memory) {
        this(storage(Objects.requireNonNull(memory)), RtSkyTextures::loadAtlas, ResourceReloadEpoch::current);
    }

    RtSkyTextures(Storage storage, Supplier<SkyTextureAtlasData> load, LongSupplier epoch) {
        this.storage = Objects.requireNonNull(storage);
        this.load = Objects.requireNonNull(load);
        this.epoch = Objects.requireNonNull(epoch);
    }

    private static Storage storage(RtMemory memory) {
        return new Storage() {
            public RtMemory.Buf create(long bytes) {
                return memory.createBuffer("rt_sky_textures", bytes,
                    VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT, true);
            }
            public void upload(RtMemory.Buf buffer, SkyTextureAtlasData data) {
                if (buffer.mapped == 0) throw new IllegalStateException("sky atlas allocation is not mapped");
                data.writeTo(MemoryUtil.memIntBuffer(buffer.mapped, data.wordCount()));
            }
            public void destroy(RtMemory.Buf buffer) { memory.destroy(buffer); }
        };
    }

    public long address() { return buf != null ? buf.address : 0L; }
    public int wordCount() { return buf != null ? words : 0; }
    public int loadedMask() { return buf != null ? loadedMask : 0; }
    public int cloudWidth() { return buf != null ? cloudW : 0; }
    public int cloudHeight() { return buf != null ? cloudH : 0; }
    public boolean needsReload() { return buf == null || abandoned != null || loadedEpoch != epoch.getAsLong(); }

    /** Complete replacement first; prove old readers finished before retiring their BDA. Never call inside an open CB. */
    public void prepare(Runnable completionProof) {
        Objects.requireNonNull(completionProof, "sky atlas replacement needs host completion proof");
        long requestedEpoch = epoch.getAsLong();
        if (buf != null && abandoned == null && loadedEpoch == requestedEpoch) return;
        String stage = "rollback_cleanup";
        SkyTextureAtlasData data = null;
        RtMemory.Buf next = null;
        long previousAddress = address();
        try {
            collectAbandoned();
            stage = "load";
            data = Objects.requireNonNull(load.get(), "sky atlas loader returned null");
            stage = "allocate";
            next = storage.create(data.byteCount());
            if (next == null || next.address == 0 || next.size < data.byteCount())
                throw new IllegalStateException("sky atlas allocation has no BDA or complete word range");
            stage = "upload";
            storage.upload(next, data);
            if (buf != null) {
                stage = "host_drain";
                completionProof.run();
                stage = "retire_previous";
                storage.destroy(buf);
            }
            buf = next;
            next = null;
            loadedEpoch = requestedEpoch;
            words = data.wordCount();
            loadedMask = data.loadedMask();
            cloudW = data.cloudWidth();
            cloudH = data.cloudHeight();
        } catch (RuntimeException | Error failure) {
            if (next != null) {
                abandoned = next;
                try { collectAbandoned(); }
                catch (RuntimeException | Error cleanup) { failure.addSuppressed(cleanup); }
            }
            int requestedWords = data != null ? data.wordCount() : 0;
            LOGGER.error("[VKB-SKY] atlas prepare failed stage={} epoch={} loaded_epoch={} words={} previous_address=0x{} rollback_retained={}\n{}",
                stage, requestedEpoch, loadedEpoch, requestedWords, Long.toUnsignedString(previousAddress, 16), abandoned != null, LogPaths.stack(failure));
            throw new RtFailure("sky_atlas_" + stage,
                "epoch=" + requestedEpoch + " loadedEpoch=" + loadedEpoch + " words=" + requestedWords
                    + " previousAddress=0x" + Long.toUnsignedString(previousAddress, 16)
                    + " rollbackRetained=" + (abandoned != null), failure);
        }
        LOGGER.info("[VKB-SKY] atlas uploaded epoch={} loaded_mask=0x{} words={} bytes={} clouds={}x{} address=0x{}",
            loadedEpoch, Integer.toHexString(loadedMask), words, (long) words * Integer.BYTES, cloudW, cloudH,
            Long.toUnsignedString(address(), 16));
    }

    private void collectAbandoned() {
        if (abandoned == null) return;
        storage.destroy(abandoned);
        abandoned = null;
    }

    /** Caller has drained submissions before closing the world-owned atlas. Failed cleanup remains retryable. */
    public void destroy() {
        Throwable failure = null;
        try { collectAbandoned(); }
        catch (RuntimeException | Error cleanup) { failure = cleanup; }
        if (buf != null) {
            try { storage.destroy(buf); buf = null; }
            catch (RuntimeException | Error cleanup) {
                if (failure == null) failure = cleanup;
                else failure.addSuppressed(cleanup);
            }
        }
        if (failure != null)
            throw new RtFailure("sky_atlas_destroy", "loadedEpoch=" + loadedEpoch + " words=" + wordCount()
                + " address=0x" + Long.toUnsignedString(address(), 16) + " rollbackRetained=" + (abandoned != null), failure);
        loadedEpoch = Long.MIN_VALUE;
        words = loadedMask = cloudW = cloudH = 0;
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