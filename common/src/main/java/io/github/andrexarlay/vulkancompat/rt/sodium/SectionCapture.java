package io.github.andrexarlay.vulkancompat.rt.sodium;

import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.BuilderTaskOutput;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildOutput;
import net.caffeinemc.mods.sodium.client.render.chunk.data.BuiltSectionMeshParts;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.caffeinemc.mods.sodium.client.util.NativeBuffer;
import net.minecraft.core.SectionPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.Collection;

/**
 * Glue between the Sodium mixins (H1, H3, H4) and the Sodium-independent decoder/registry. All methods return
 * immediately when {@link RtGeometryGate#enabled()} is false.
 *
 * <p>The decoder reads the pass NativeBuffers directly on the Sodium worker thread: they are freed only by
 * {@code ChunkBuildOutput.destroy()}, which runs on the render thread after the output has been returned from
 * {@code ChunkBuilderMeshingTask.execute} (RenderSectionManager.processChunkBuilds), so no native copy is needed.
 * The decoded result lives on the Java heap.
 */
public final class SectionCapture {
    private static final Logger LOGGER = LoggerFactory.getLogger("VKB-RT");
    private static final int MAX_LOGGED_FAILURES = 10;

    private SectionCapture() {
    }

    /** H1, Sodium worker thread: decode the finished build output and attach the result to it. */
    public static void captureOnWorker(ChunkBuildOutput output) {
        if (output == null || !RtGeometryGate.enabled()) {
            return;
        }
        RenderSection section = output.section;
        int sx = section.getChunkX();
        int sy = section.getChunkY();
        int sz = section.getChunkZ();
        long t0 = io.github.andrexarlay.vulkancompat.rt.world.RtTimings.start();
        try {
            ByteBuffer[] buffers = new ByteBuffer[DecodedSection.PASS_COUNT];
            for (int p = 0; p < DefaultTerrainRenderPasses.ALL.length; p++) {
                TerrainRenderPass pass = DefaultTerrainRenderPasses.ALL[p];
                BuiltSectionMeshParts mesh = output.getMesh(pass);
                if (mesh != null) {
                    NativeBuffer data = mesh.getVertexData();
                    buffers[DefaultTerrainRenderPasses.getPassIndex(pass)] = data.getDirectBuffer();
                }
            }
            DecodedSection decoded = SodiumMeshDecoder.decode(SectionPos.asLong(sx, sy, sz), sx, sy, sz, buffers);
            ((RtGeometryHolder) (Object) output).vkb$setGeometry(decoded);
            if (t0 != 0L) {
                long bytes = 0;
                for (ByteBuffer b : buffers) {
                    bytes += b == null ? 0 : b.remaining();
                }
                io.github.andrexarlay.vulkancompat.rt.world.RtTimings.decode(System.nanoTime() - t0, bytes);
            }
        } catch (RuntimeException e) {
            long n = SectionGeometryRegistry.INSTANCE.recordFailure();
            if (n <= MAX_LOGGED_FAILURES) {
                LOGGER.error("[VKB-RT] decode_failed section=({},{},{}) failed_total={}", sx, sy, sz, n, e);
            }
        }
    }

    /** H3, render thread, before Sodium destroys the results: hand the decoded sections of accepted outputs to the registry. */
    public static void acceptOnRenderThread(Collection<BuilderTaskOutput> results) {
        if (!RtGeometryGate.enabled()) {
            return;
        }
        SectionGeometryRegistry registry = SectionGeometryRegistry.INSTANCE;
        for (BuilderTaskOutput result : results) {
            if (!(result instanceof ChunkBuildOutput output)) {
                continue; // ChunkSortOutput: only translucent indices change, geometry stays
            }
            RenderSection section = output.section;
            if (section.isDisposed()) {
                continue;
            }
            long key = SectionPos.asLong(section.getChunkX(), section.getChunkY(), section.getChunkZ());
            DecodedSection decoded = ((RtGeometryHolder) (Object) output).vkb$getGeometry();
            if (decoded == null) {
                // No decoded data (decoding failed, already counted, or built before the feature was enabled):
                // a stale entry would describe geometry Sodium has replaced, so drop it.
                registry.remove(key);
            } else {
                registry.submit(decoded);
            }
        }
    }

    /** H4, render thread: the section is deleted. */
    public static void onSectionDeleted(RenderSection section) {
        SectionGeometryRegistry registry = SectionGeometryRegistry.INSTANCE;
        if (!RtGeometryGate.enabled() && registry.sectionCount() == 0) {
            return;
        }
        registry.remove(SectionPos.asLong(section.getChunkX(), section.getChunkY(), section.getChunkZ()));
    }
}
