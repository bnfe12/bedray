package io.github.andrexarlay.vulkancompat.rt.world;

import org.junit.jupiter.api.Test;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class RtSkyTexturesLifetimeTest {
    private static SkyTextureAtlasData data() {
        var textures = new SkyTextureAtlasData.Texture[12];
        textures[0] = new SkyTextureAtlasData.Texture(2, 1, new int[]{0xff332211, 0x88776655});
        textures[9] = new SkyTextureAtlasData.Texture(1, 1, new int[]{0xaabbccdd});
        return SkyTextureAtlasData.pack(textures);
    }

    private static final class Storage implements RtSkyTextures.Storage {
        long serial;
        boolean failUpload;
        final Set<Long> live = new HashSet<>(), failDestroy = new HashSet<>();
        final List<String> events = new ArrayList<>();
        public RtMemory.Buf create(long bytes) {
            long id = ++serial; live.add(id); events.add("create:" + id);
            return new RtMemory.Buf(id, 0, bytes, id * 4096, 0, true);
        }
        public void upload(RtMemory.Buf buffer, SkyTextureAtlasData atlas) {
            events.add("upload:" + buffer.handle);
            if (failUpload) throw new IllegalStateException("injected upload");
            atlas.writeTo(IntBuffer.allocate(atlas.wordCount()));
        }
        public void destroy(RtMemory.Buf buffer) {
            assertNotNull(buffer);
            events.add("destroy:" + buffer.handle);
            if (failDestroy.contains(buffer.handle)) throw new IllegalStateException("injected destroy " + buffer.handle);
            assertTrue(live.remove(buffer.handle), "free each allocation once");
        }
    }

    @Test void firstLoadDoesNotDrainAndStableEpochDoesNotLoadOrAllocate() {
        var storage = new Storage(); var epoch = new AtomicLong(1); var loads = new AtomicInteger();
        var atlas = new RtSkyTextures(storage, () -> { loads.incrementAndGet(); return data(); }, epoch::get);
        atlas.prepare(() -> fail("no previous readers on the first generation"));
        assertEquals(51, atlas.wordCount());
        assertEquals(1 | (1 << 9), atlas.loadedMask());
        assertEquals(1, atlas.cloudWidth()); assertEquals(1, atlas.cloudHeight());
        atlas.prepare(() -> fail("unchanged generation"));
        assertEquals(1, loads.get()); assertEquals(1, storage.serial);
        atlas.destroy(); atlas.destroy();
        assertEquals(0, atlas.wordCount()); assertTrue(storage.live.isEmpty());
    }

    @Test void samePackReloadUploadsThenProvesOldReadersBeforeRetirement() {
        var storage = new Storage(); var epoch = new AtomicLong(1);
        var atlas = new RtSkyTextures(storage, RtSkyTexturesLifetimeTest::data, epoch::get);
        atlas.prepare(() -> {}); long oldAddress = atlas.address();
        storage.events.clear(); epoch.incrementAndGet();
        assertTrue(atlas.needsReload());
        atlas.prepare(() -> { assertEquals(oldAddress, atlas.address()); storage.events.add("proof"); });
        assertEquals(List.of("create:2", "upload:2", "proof", "destroy:1"), storage.events);
        assertNotEquals(oldAddress, atlas.address());
        assertFalse(atlas.needsReload()); assertEquals(1, storage.live.size());
        atlas.destroy();
    }

    @Test void failedProofPreservesOldAtlasAndEpochForRetry() {
        var storage = new Storage(); var epoch = new AtomicLong(1);
        var atlas = new RtSkyTextures(storage, RtSkyTexturesLifetimeTest::data, epoch::get);
        atlas.prepare(() -> {}); long oldAddress = atlas.address(); epoch.incrementAndGet();
        var original = new IllegalStateException("host fence timeout");
        var failure = assertThrows(RtFailure.class, () -> atlas.prepare(() -> { throw original; }));
        assertSame(original, failure.getCause()); assertEquals("sky_atlas_host_drain", failure.stage());
        assertEquals(oldAddress, atlas.address()); assertEquals(1, storage.live.size());
        assertTrue(atlas.needsReload());
        atlas.prepare(() -> {}); assertFalse(atlas.needsReload());
        atlas.destroy(); assertTrue(storage.live.isEmpty());
    }

    @Test void failedUploadNeverDrainsOrPublishesTheReplacement() {
        var storage = new Storage(); var epoch = new AtomicLong(1);
        var atlas = new RtSkyTextures(storage, RtSkyTexturesLifetimeTest::data, epoch::get);
        atlas.prepare(() -> {}); long oldAddress = atlas.address(); epoch.incrementAndGet(); storage.failUpload = true;
        assertThrows(RtFailure.class, () -> atlas.prepare(() -> fail("failed upload needs no host proof")));
        assertEquals(oldAddress, atlas.address()); assertEquals(1, storage.live.size());
        storage.failUpload = false; atlas.destroy(); assertTrue(storage.live.isEmpty());
    }

    @Test void failedRollbackRemainsReachableAndBlocksAnotherAllocationUntilCleanup() {
        var storage = new Storage(); var epoch = new AtomicLong(1);
        var atlas = new RtSkyTextures(storage, RtSkyTexturesLifetimeTest::data, epoch::get);
        atlas.prepare(() -> {}); epoch.incrementAndGet(); storage.failUpload = true; storage.failDestroy.add(2L);
        var failure = assertThrows(RtFailure.class, () -> atlas.prepare(() -> {}));
        assertEquals(1, failure.getCause().getSuppressed().length);
        assertEquals(2, storage.live.size());
        assertThrows(RtFailure.class, () -> atlas.prepare(() -> {}));
        assertEquals(2, storage.serial, "retry must collect the prior abandoned allocation first");
        storage.failDestroy.clear(); storage.failUpload = false;
        atlas.prepare(() -> {}); assertEquals(1, storage.live.size());
        atlas.destroy(); assertTrue(storage.live.isEmpty());
    }

    @Test void layoutPreservesAbgrAndRejectsInvalidDimensionsBeforePacking() {
        var atlas = data(); var words = IntBuffer.allocate(atlas.wordCount()); atlas.writeTo(words);
        assertEquals(48, words.get(0)); assertEquals(2, words.get(1)); assertEquals(1, words.get(2));
        assertEquals(1, words.get(3)); assertEquals(0xff332211, words.get(48)); assertEquals(0x88776655, words.get(49));
        assertEquals(50, words.get(9 * 4)); assertEquals(0xaabbccdd, words.get(50));
        assertEquals(0, words.get(4 + 3));
        var bad = new SkyTextureAtlasData.Texture[12];
        bad[0] = new SkyTextureAtlasData.Texture(Integer.MAX_VALUE, 2, new int[]{1});
        assertThrows(IllegalArgumentException.class, () -> SkyTextureAtlasData.pack(bad));
        assertThrows(IllegalArgumentException.class, () -> atlas.writeTo(IntBuffer.allocate(48)));
    }
}
