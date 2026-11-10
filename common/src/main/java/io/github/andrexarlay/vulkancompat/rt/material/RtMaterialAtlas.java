package io.github.andrexarlay.vulkancompat.rt.material;

import io.github.andrexarlay.vulkancompat.rt.scene.BedraySceneNative.MaterialViews;
import io.github.andrexarlay.vulkancompat.resources.ResourceReloadEpoch;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceProvider;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.locks.LockSupport;

/**
 * Captures and bakes Minecraft material sprites. C++ owns both atlas images and the
 * staging for their uploads; the frame holds only their borrowed image views.
 */
public final class RtMaterialAtlas {
    private static final Logger LOGGER = LoggerFactory.getLogger("VKB-RT");
    /** Upload at most this many texels per frame (a single sprite always goes through, however large). */
    private static final long UPLOAD_TEXEL_BUDGET = 1L << 19;

    @FunctionalInterface
    public interface Uploader {
        MaterialViews upload(int width, int height, int count, MemorySegment regions, MemorySegment data, long planeBytes);
    }

    private long materialView, normalView;
    private int width, height;
    private long captureEpoch;
    private OpticalMaterials optics = new OpticalMaterials();
    private static final java.util.concurrent.atomic.AtomicLong OPTICAL_REVISIONS = new java.util.concurrent.atomic.AtomicLong();
    private int publishedOpticalCount;
    private long opticalRevision;
    private java.nio.ByteBuffer opticalBytes;

    public long opticalRevision() { return opticalRevision; }
    public java.nio.ByteBuffer opticalBytes() { return opticalBytes; }

    private void refreshOpticalPalette() {
        var profiles = optics.snapshot();
        if (profiles.size() == publishedOpticalCount) return;
        var bytes = java.nio.ByteBuffer.allocate(OpticalMaterials.BYTES).order(java.nio.ByteOrder.nativeOrder());
        for (int i = 1; i < profiles.size(); i++) {
            bytes.asFloatBuffer().position(i * OpticalMaterials.FLOATS).put(profiles.get(i).data());
        }
        opticalBytes = bytes.asReadOnlyBuffer();
        opticalRevision = OPTICAL_REVISIONS.incrementAndGet();
        publishedOpticalCount = profiles.size();
    }
    public int opticalProfileCount() { return optics.snapshot().size() - 1; }
    private Object bakedAtlas;
    private int bakedW = -1;
    private int bakedH = -1;
    private MaterialAtlasBaker.Result last;

    /** CPU-side atlas: the source of truth for uploads; the live arrays are shared with the emitters via {@link #last}. */
    private int[] cpuMaterial;
    /**
     * Bumped by every write into the CPU material mirror (allocation, default fill, each landed sprite batch). The mirror is
     * filled in place while a bake runs, so a reader that caches anything derived from it (the emitter extraction's
     * {@code EmissiveTexelIndex}) must key on this version, not on the array identity.
     */
    private static final java.util.concurrent.atomic.AtomicLong CPU_MATERIAL_VERSION = new java.util.concurrent.atomic.AtomicLong();

    /** Version of the CPU material mirror's contents (see {@code CPU_MATERIAL_VERSION}). */
    public static long cpuMaterialVersion() {
        return CPU_MATERIAL_VERSION.get();
    }
    private int[] cpuNormal;

    private record Completed(MaterialAtlasBaker.SpriteBake bake, Epoch owner) {
    }

    private static final class Epoch {
        final TextureAtlas atlas;
        final int width;
        final int height;
        final ResourceProvider resources;
        final OpticalMaterials optics;
        final ArrayDeque<TextureAtlasSprite> queue = new ArrayDeque<>();
        final long startNs = System.nanoTime();
        // worker-thread only (the render thread reads them after the volatile `finished` write)
        int labS;
        int labN;
        int emissive;
        int metal;
        int errors;
        int autoN;
        volatile boolean cancelled;
        volatile boolean finished;

        Epoch(TextureAtlas atlas, int width, int height, ResourceProvider resources, OpticalMaterials optics) {
            this.atlas = atlas;
            this.width = width;
            this.height = height;
            this.resources = resources;
            this.optics = optics;
        }
    }

    private Epoch epoch;
    private volatile Epoch pendingEpoch;
    private volatile boolean shutdown;
    private Thread worker;
    private final ConcurrentLinkedQueue<Completed> completed = new ConcurrentLinkedQueue<>();
    public long materialView() { return materialView; }
    public long normalView() { return normalView; }

    public MaterialAtlasBaker.Result lastBake() {
        return last;
    }

    /**
     * Starts a new bake after a resource reload, and gives the native frame its finished sprite regions before tracing.
     */
    public boolean ensure(Uploader uploader, TextureAtlas atlas, int width, int height, ResourceProvider resources) {
        long revision = ResourceReloadEpoch.current();
        if (captureEpoch != revision) {
            if (epoch != null) epoch.cancelled = true;
            epoch = null;
            bakedAtlas = null;
            // The atlas dimensions can stay fixed while every sprite moves or changes.
            // Reinitialize both planes together before sampling any rect of the new game atlas.
        }
        if (materialView == 0 || this.width != width || this.height != height || captureEpoch != revision) {
            if (epoch != null) epoch.cancelled = true;
            epoch = null;
            last = null;
            initialize(uploader, width, height);
            captureEpoch = revision;
        }
        if (epoch == null) {
            if (atlas == bakedAtlas && width == bakedW && height == bakedH) {
                return false;
            }
            startEpoch(atlas, width, height, resources);
        } else if (epoch.atlas != atlas || epoch.width != width || epoch.height != height) {
            epoch.cancelled = true;
            startEpoch(atlas, width, height, resources);
        }
        return drainAndUpload(uploader);
    }

    private void initialize(Uploader uploader, int width, int height) {
        io.github.andrexarlay.vulkancompat.rt.sodium.GlassFaceFilter.setLookup(null);
        optics = new OpticalMaterials();
        publishedOpticalCount = 0;
        refreshOpticalPalette();
        cpuMaterial = new int[Math.multiplyExact(width, height)];
        Arrays.fill(cpuMaterial, LabPbr.materialFromAuto(AutoPbr.DEFAULT, 0f));
        CPU_MATERIAL_VERSION.incrementAndGet();
        cpuNormal = new int[cpuMaterial.length];
        Arrays.fill(cpuNormal, LabPbr.FLAT_NORMAL);
        bakedAtlas = null;
        bakedW = width;
        bakedH = height;
        try (Arena arena = Arena.ofConfined()) {
            long planeBytes = (long) cpuMaterial.length * Integer.BYTES;
            var regions = arena.allocate(24, 8);
            setRegion(regions, 0, 0, 0, 0, width, height);
            var pixels = arena.allocate(planeBytes * 2, 4);
            MemorySegment.copy(cpuMaterial, 0, pixels, JAVA_INT, 0, cpuMaterial.length);
            MemorySegment.copy(cpuNormal, 0, pixels, JAVA_INT, planeBytes, cpuNormal.length);
            setViews(uploader.upload(width, height, 1, regions, pixels, planeBytes));
        }
        this.width = width;
        this.height = height;
    }

    private void startEpoch(TextureAtlas atlas, int width, int height, ResourceProvider resources) {
        Map<Identifier, TextureAtlasSprite> sprites = ((AtlasSpritesAccess) atlas).vkb$texturesByName();
        if (sprites == null || sprites.isEmpty()) {
            LOGGER.warn("[VKB-RT] material bake skipped: atlas sprite map unavailable yet");
            return;
        }
        if (cpuMaterial == null || cpuMaterial.length != width * height) {
            cpuMaterial = new int[width * height];
            Arrays.fill(cpuMaterial, LabPbr.materialFromAuto(AutoPbr.DEFAULT, 0f));
            CPU_MATERIAL_VERSION.incrementAndGet();
            cpuNormal = new int[width * height];
            Arrays.fill(cpuNormal, LabPbr.FLAT_NORMAL);
        }
        Epoch e = new Epoch(atlas, width, height, resources, optics);
        synchronized (e.queue) {
            e.queue.addAll(sprites.values());
        }
        epoch = e;
        // a live Result from the first epoch frame: the emitters read the same arrays and refresh once at the end
        last = new MaterialAtlasBaker.Result(width, height, cpuMaterial, cpuNormal, sprites.size(), 0, 0, 0, 0, 0, 0.0);
        pendingEpoch = e;
        if (worker == null) {
            worker = new Thread(this::workerLoop, "VKB-RT-MaterialBake");
            worker.setDaemon(true);
            worker.start();
        }
        LockSupport.unpark(worker);
    }

    private void workerLoop() {
        while (!shutdown) {
            Epoch e = pendingEpoch;
            if (e == null || e.finished) {
                LockSupport.parkNanos(200_000);
                continue;
            }
            pendingEpoch = null;
            TextureAtlasSprite s;
            while (!e.cancelled && !shutdown && (s = e.queue.poll()) != null) {
                MaterialAtlasBaker.SpriteBake b = MaterialAtlasBaker.bakeSprite(s, e.width, e.height, e.resources, e.optics);
                if (b == null) {
                    e.errors++;
                    continue;
                }
                if (b.labS()) {
                    e.labS++;
                }
                if (b.labN()) {
                    e.labN++;
                }
                if (b.metal()) {
                    e.metal++;
                }
                if (b.emissive() > 0) {
                    e.emissive++;
                }
                if (b.autoNormal()) {
                    e.autoN++;
                }
                completed.add(new Completed(b, e));
            }
            e.finished = true;
        }
    }

    private boolean drainAndUpload(Uploader uploader) {
        Epoch e = epoch;
        if (e == null) {
            return false;
        }
        ArrayList<MaterialAtlasBaker.SpriteBake> batch = new ArrayList<>();
        long texels = 0;
        Completed c;
        while ((c = completed.poll()) != null) {
            if (c.owner() != e) {
                continue; // a superseded epoch: drop its work
            }
            batch.add(c.bake());
            texels += (long) c.bake().w() * c.bake().h();
            if (texels >= UPLOAD_TEXEL_BUDGET) {
                break;
            }
        }
        if (e.finished && completed.isEmpty()) {
            finishEpoch(e);
        }
        if (batch.isEmpty()) {
            return false;
        }
        mirrorToCpu(batch, e.width, e.height);
        refreshOpticalPalette();
        int[] lookup = cpuMaterial;
        OpticalMaterials palette = optics;
        io.github.andrexarlay.vulkancompat.rt.sodium.GlassFaceFilter.setLookup((u, v) -> {
            if (!io.github.andrexarlay.vulkancompat.rtx.config.RtSettings.cullSharedGlass()) return 0;
            int x = Math.max(0, Math.min(e.width - 1, (int) (u * e.width)));
            int y = Math.max(0, Math.min(e.height - 1, (int) (v * e.height)));
            int id = OpticalMaterials.profileId(lookup[y * e.width + x]);
            return palette.cullsGlass(id) ? id : 0;
        });
        uploadSprites(uploader, batch);
        return true;
    }

    private void finishEpoch(Epoch e) {
        double ms = (System.nanoTime() - e.startNs) / 1.0e6;
        last = new MaterialAtlasBaker.Result(e.width, e.height, cpuMaterial, cpuNormal, last != null ? last.sprites() : 0,
            e.labS, e.labN, e.emissive, e.metal, e.errors, ms);
        bakedAtlas = e.atlas;
        bakedW = e.width;
        bakedH = e.height;
        epoch = null;
        LOGGER.info(String.format(Locale.ROOT,
            "[VKB-RT] materials baked atlas=%dx%d sprites=%d labpbr_s=%d labpbr_n=%d auto_normals=%d emissive_sprites=%d metal_sprites=%d errors=%d ms=%.1f (progressive)",
            e.width, e.height, last.sprites(), e.labS, e.labN, e.autoN, e.emissive, e.metal, e.errors, ms));
    }

    /** Copies the baked sprites into the CPU material mirror that the emitter scanner reads: the live
     * {@link MaterialAtlasBaker.Result} wraps this same array, so without this the scan sees the zero-emission defaults. */
    private void mirrorToCpu(ArrayList<MaterialAtlasBaker.SpriteBake> batch, int atlasW, int atlasH) {
        if (cpuMaterial == null || cpuMaterial.length != atlasW * atlasH) {
            return;
        }
        for (MaterialAtlasBaker.SpriteBake b : batch) {
            mirrorSprite(cpuMaterial, atlasW, b);
        }
        CPU_MATERIAL_VERSION.incrementAndGet();
    }

    static void mirrorSprite(int[] cpuMaterial, int atlasW, MaterialAtlasBaker.SpriteBake b) {
        int[] m = b.material();
        for (int y = 0; y < b.h(); y++) {
            System.arraycopy(m, y * b.w(), cpuMaterial, (b.y0() + y) * atlasW + b.x0(), b.w());
        }
    }

    private void uploadSprites(Uploader uploader, ArrayList<MaterialAtlasBaker.SpriteBake> batch) {
        long planeBytes = 0;
        for (var sprite : batch) planeBytes += (long) sprite.w() * sprite.h() * Integer.BYTES;
        try (Arena arena = Arena.ofConfined()) {
            var regions = arena.allocate(24L * batch.size(), 8);
            var pixels = arena.allocate(planeBytes * 2, 4);
            long offset = 0;
            for (int i = 0; i < batch.size(); i++) {
                var sprite = batch.get(i);
                int texels = Math.multiplyExact(sprite.w(), sprite.h());
                setRegion(regions, i, offset, sprite.x0(), sprite.y0(), sprite.w(), sprite.h());
                MemorySegment.copy(sprite.material(), 0, pixels, JAVA_INT, offset, texels);
                MemorySegment.copy(sprite.normal(), 0, pixels, JAVA_INT, planeBytes + offset, texels);
                offset += (long) texels * Integer.BYTES;
            }
            setViews(uploader.upload(width, height, batch.size(), regions, pixels, planeBytes));
        }
    }

    private void setViews(MaterialViews views) {
        materialView = views.material();
        normalView = views.normal();
    }

    private static void setRegion(MemorySegment regions, int i, long offset, int x, int y, int width, int height) {
        long base = 24L * i;
        regions.set(JAVA_LONG, base, offset);
        regions.set(JAVA_INT, base + 8, x);
        regions.set(JAVA_INT, base + 12, y);
        regions.set(JAVA_INT, base + 16, width);
        regions.set(JAVA_INT, base + 20, height);
    }

    public synchronized void destroy() {
        io.github.andrexarlay.vulkancompat.rt.sodium.GlassFaceFilter.setLookup(null);
        shutdown = true;
        if (epoch != null) {
            epoch.cancelled = true;
        }
        if (worker != null) {
            LockSupport.unpark(worker);
        }
        completed.clear();
        materialView = normalView = 0;
        cpuMaterial = null;
        cpuNormal = null;
        bakedAtlas = null;
    }
}
