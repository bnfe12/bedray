package io.github.andrexarlay.bedray.compute;

import io.github.andrexarlay.bedray.api.v1.CameraSnapshot;
import io.github.andrexarlay.bedray.api.v1.GraphicsBackendRegistry;
import io.github.andrexarlay.bedray.api.v1.SectionSnapshot;
import io.github.andrexarlay.bedray.api.v1.WorldSnapshot;
import io.github.andrexarlay.bedray.compute.voxel.VoxelDda;
import io.github.andrexarlay.bedray.compute.voxel.VoxelDemoScene;
import io.github.andrexarlay.bedray.compute.voxel.VoxelGrid;
import io.github.andrexarlay.bedray.compute.voxel.VoxelHit;
import io.github.andrexarlay.bedray.compute.voxel.VoxelGridBuilder;
import io.github.andrexarlay.bedray.compute.voxel.VoxelWorldUpload;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.vulkan.VK10.*;

/** Explicitly requested native probe: it fails rather than skipping if no suitable device exists. */
@EnabledIfSystemProperty(named = "bedray.compute.vulkanProbe", matches = "true")
@Execution(ExecutionMode.SAME_THREAD)
class VulkanComputeProbeTest {
    private static final int WIDTH = 192;
    private static final int HEIGHT = 128;
    private static final int PIXEL_BYTES = WIDTH * HEIGHT * 4;
    private static final int WORLD_SIZE = 48;
    private static final int WORLD_X = 29_999_968;
    private static final int WORLD_Y = 48;
    private static final int WORLD_Z = -20_000_032;

    @Test void computeTraversalMatchesCpuWithoutEnabledExtensions() throws Exception {
        VoxelGrid grid = VoxelDemoScene.create();
        try (Probe probe = new Probe();
             VoxelComputePipeline pipeline = new VoxelComputePipeline(probe.device, WIDTH, HEIGHT,
                     VK_FORMAT_R8G8B8A8_UNORM, grid)) {
            byte[] ids = probe.render(pipeline, true);
            int mismatches = 0;
            int unexplained = 0;
            int gpuHits = 0;
            int cpuHits = 0;
            for (int y = 0; y < HEIGHT; y++) {
                for (int x = 0; x < WIDTH; x++) {
                    int offset = (x + WIDTH * y) * 4;
                    VoxelHit hit = cpuHit(grid, x, y);
                    if (hit != null) cpuHits++;
                    if (Byte.toUnsignedInt(ids[offset + 3]) != 0) gpuHits++;
                    if (!matches(ids, offset, hit)) {
                        mismatches++;
                        // Permit a small numerical boundary discrepancy only when a nearby
                        // CPU ray actually identifies that same cell; arbitrary wrong IDs fail.
                        boolean boundary = false;
                        for (float py : new float[]{-0.001f, 0, 0.001f}) {
                            for (float px : new float[]{-0.001f, 0, 0.001f}) {
                                boundary |= matches(ids, offset, cpuHit(grid, x + px, y + py));
                            }
                        }
                        if (!boundary) unexplained++;
                    }
                }
            }
            System.out.printf("[BEDRAY-COMPUTE-PROBE] GPU=%s Vulkan=1.0 enabledInstanceExtensions=0 "
                            + "enabledDeviceExtensions=0 enabledOptionalFeatures=0 pixels=%d cpuHits=%d gpuHits=%d "
                            + "mismatches=%d unexplained=%d%n",
                    probe.deviceName, WIDTH * HEIGHT, cpuHits, gpuHits, mismatches, unexplained);
            assertTrue(gpuHits > WIDTH * HEIGHT / 10, "The diagnostic must produce substantial real geometry");
            assertEquals(0, unexplained, "GPU IDs differ beyond a numerical cell boundary");
            assertTrue(mismatches <= WIDTH * HEIGHT / 1000, "More than 0.1 percent of CPU/GPU rays disagree: " + mismatches);

            writeImage(probe.render(pipeline, false), "voxel-vulkan.png");
        }
    }

    @Test void worldCameraAndSuccessiveSceneRevisionsReachGpu() throws Exception {
        WorldFixture firstScene = worldScene(1, 40, false, null);
        WorldFixture secondScene = worldScene(2, 44, true, firstScene);
        WorldFixture thirdScene = withSingleCube(secondScene);
        GraphicsBackendRegistry.DebugView previousView = GraphicsBackendRegistry.debugView();
        GraphicsBackendRegistry.setDebugView(GraphicsBackendRegistry.DebugView.SHADED);
        try {
            try (Probe probe = new Probe();
                 VoxelComputePipeline pipeline = new VoxelComputePipeline(probe.device, WIDTH, HEIGHT,
                         VK_FORMAT_R8G8B8A8_UNORM, VoxelGrid.builder(WORLD_SIZE, WORLD_SIZE, WORLD_SIZE).build())) {
                VoxelWorldUpload uploads = new VoxelWorldUpload();
                List<VoxelWorldUpload.Tile> firstUpdates = uploads.update(firstScene.snapshot());
                assertEquals(27, firstUpdates.size(), "First residency must initialize loaded and unknown tiles");
                assertEquals(WORLD_X, uploads.originX());
                assertEquals(WORLD_Y, uploads.originY());
                assertEquals(WORLD_Z, uploads.originZ());
                byte[] first = probe.renderWorld(pipeline, true, firstScene.snapshot().camera(), uploads, firstUpdates);
                compareWorldIds(firstScene.opaque(), first, "world-revision-1", false);
                assertTrue(uploads.update(firstScene.snapshot()).isEmpty(), "Unchanged section objects must not upload again");

                List<VoxelWorldUpload.Tile> secondUpdates = uploads.update(secondScene.snapshot());
                assertTrue(!secondUpdates.isEmpty() && secondUpdates.size() <= 9,
                        "Only the far layer containing the moved wall may change");
                byte[] second = probe.renderWorld(pipeline, true, secondScene.snapshot().camera(), uploads, secondUpdates);
                compareWorldIds(secondScene.opaque(), second, "world-revision-2", false);
                int changedPixels = changedPixels(first, second);
                System.out.printf("[BEDRAY-COMPUTE-PROBE] successiveRevisions=1,2 samePipeline=true changedTiles=%d changedPixels=%d%n",
                        secondUpdates.size(), changedPixels);
                assertTrue(changedPixels > 500, "Moved/removed/reclassified wall must change the uploaded scene");

                List<VoxelWorldUpload.Tile> oneTile = uploads.update(thirdScene.snapshot());
                assertEquals(1, oneTile.size(), "A one-cell edit must update one tile, not the entire scene");
                java.nio.ByteBuffer serialized = java.nio.ByteBuffer.allocate(VoxelWorldUpload.TILE_BYTES);
                oneTile.getFirst().writeTo(serialized);
                assertEquals(16 * 1024, serialized.position());
                assertEquals(0, (oneTile.getFirst().offset() - VoxelGrid.HEADER_BYTES) % VoxelWorldUpload.TILE_BYTES);
                byte[] third = probe.renderWorld(pipeline, true, thirdScene.snapshot().camera(), uploads, oneTile);
                compareWorldIds(thirdScene.opaque(), third, "world-revision-3-one-tile", false);
                assertTrue(changedPixels(second, third) > 0, "The single restored cube must reach the GPU");
                assertTrue(uploads.update(thirdScene.snapshot()).isEmpty());
                writeImage(probe.renderWorld(pipeline, false, thirdScene.snapshot().camera(), uploads, List.of()),
                        "voxel-world-vulkan.png");

                GraphicsBackendRegistry.setDebugView(GraphicsBackendRegistry.DebugView.OCCUPANCY);
                byte[] markers = probe.renderWorld(pipeline, true, thirdScene.snapshot().camera(), uploads, List.of());
                compareWorldIds(thirdScene.markers(), markers, "world-marker-view", true);
                writeImage(probe.renderWorld(pipeline, false, thirdScene.snapshot().camera(), uploads, List.of()),
                        "voxel-world-markers-vulkan.png");
            }
        } finally {
            GraphicsBackendRegistry.setDebugView(previousView);
        }
    }

    private static void writeImage(byte[] rgba, String filename) throws Exception {
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < HEIGHT; y++) for (int x = 0; x < WIDTH; x++) {
            int at = (x + WIDTH * y) * 4;
            int argb = Byte.toUnsignedInt(rgba[at + 3]) << 24
                    | Byte.toUnsignedInt(rgba[at]) << 16
                    | Byte.toUnsignedInt(rgba[at + 1]) << 8
                    | Byte.toUnsignedInt(rgba[at + 2]);
            image.setRGB(x, y, argb);
        }
        Path output = Path.of("build", "reports", "compute", filename);
        Files.createDirectories(output.getParent());
        assertTrue(ImageIO.write(image, "png", output.toFile()), "PNG writer is unavailable");
        System.out.println("[BEDRAY-COMPUTE-PROBE] Offscreen shaded artifact=" + output.toAbsolutePath());
    }

    /**
     * Textured + raster variant on a real device: cell tint, emitter list, key light, diffuse bounce,
     * water transmission, temporal history and depth-gated composition. Correct depth is synthesized
     * from a CPU DDA oracle and a reverse-Z perspective, so replacement is exercised exactly as in game.
     * This proves dispatch and data flow offscreen, not in-game appearance.
     */
    @Test void texturedLightingHistoryAndCompositionDispatch() throws Exception {
        GraphicsBackendRegistry.DebugView previousView = GraphicsBackendRegistry.debugView();
        GraphicsBackendRegistry.setDebugView(GraphicsBackendRegistry.DebugView.SHADED);
        float aspect = (float) WIDTH / HEIGHT, near = 0.05f, far = 1000f;
        float fy = (float) (1 / Math.tan(Math.toRadians(35)));
        float a = near / (far - near), b = near * far / (far - near);
        float[] projection = {fy / aspect,0,0,0, 0,-fy,0,0, 0,0,a,-1, 0,0,b,0};
        float[] inverseProjection = new float[16];
        assertTrue(TemporalHistory.invert(projection, inverseProjection));
        float[] identity = {1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,0,1};
        double camX = 24.5, camY = 27.5, camZ = 31.5; // window sections 0..2 on every axis
        LitScene scene = litScene();
        float[] depth = new float[WIDTH * HEIGHT];
        int hits = 0;
        for (int y = 0; y < HEIGHT; y++) for (int x = 0; x < WIDTH; x++) {
            float ndcX = (x + 0.5f) / WIDTH * 2 - 1, ndcY = (y + 0.5f) / HEIGHT * 2 - 1;
            float[] p = new float[4];
            for (int row = 0; row < 4; row++)
                p[row] = inverseProjection[row] * ndcX + inverseProjection[4 + row] * ndcY
                    + inverseProjection[8 + row] * 0.5f + inverseProjection[12 + row];
            float[] ray = normalize(p[0] / p[3], p[1] / p[3], p[2] / p[3]);
            VoxelHit hit = VoxelDda.trace(scene.occupancy(), camX, camY, camZ, ray[0], ray[1], ray[2], 0, 160);
            if (hit == null) continue;
            double viewZ = hit.t() * ray[2];
            depth[x + WIDTH * y] = (float) ((a * viewZ + b) / -viewZ);
            hits++;
        }
        assertTrue(hits > WIDTH * HEIGHT / 3, "Fixture camera must see the lit scene");
        float[] light = new float[24];
        light[0] = 0.35f; light[1] = 0.85f; light[2] = 0.25f; light[3] = 1;
        light[4] = 1; light[5] = 0.95f; light[6] = 0.9f; light[7] = 1;
        light[8] = 0.25f; light[9] = 0.45f; light[10] = 1; light[11] = 1;
        light[12] = 1; light[13] = 0.7f; light[14] = 0.45f; light[15] = 1;
        light[16] = 0.01f; light[17] = 0.01f; light[18] = 0.01f; light[19] = 0.5f;
        var lighting = new io.github.andrexarlay.bedray.api.v1.LightingFrame(light);
        try (Probe probe = new Probe(); LitInputs inputs = new LitInputs(probe, depth)) {
            var atlas = new io.github.andrexarlay.bedray.api.v1.AtlasFrame(1, probe.device.address(), inputs.atlasView,
                VK_IMAGE_LAYOUT_GENERAL, VK_FORMAT_R8G8B8A8_UNORM, 16, 16,
                io.github.andrexarlay.bedray.api.v1.AtlasFrame.ColorEncoding.SRGB_IN_UNORM);
            var raster = new io.github.andrexarlay.bedray.api.v1.RasterFrame(probe.device.address(), inputs.colorView,
                inputs.depthView, VK_FORMAT_R8G8B8A8_UNORM, VK_FORMAT_D32_SFLOAT, WIDTH, HEIGHT);
            try (VoxelComputePipeline pipeline = new VoxelComputePipeline(probe.device, WIDTH, HEIGHT,
                    VK_FORMAT_R8G8B8A8_UNORM, VoxelGrid.builder(48, 48, 48).build(), atlas, raster)) {
                CameraSnapshot camera = new CameraSnapshot(camX, camY, camZ, inverseProjection, identity);
                WorldSnapshot snapshot = new WorldSnapshot(1, 1, camera, scene.sections());
                VoxelWorldUpload uploads = new VoxelWorldUpload();
                probe.lighting = lighting;
                byte[] previous = probe.renderWorld(pipeline, false, camera, uploads, uploads.update(snapshot));
                writeImage(previous, "voxel-lit-first-vulkan.png");
                assertEquals(2, uploads.emitters().count(), "Emitter cube and lava-like cell must be listed");
                int replaced = 0, keptSky = 0, magenta = 0;
                for (int i = 0; i < WIDTH * HEIGHT; i++) {
                    int r = Byte.toUnsignedInt(previous[i * 4]), g = Byte.toUnsignedInt(previous[i * 4 + 1]),
                        bl = Byte.toUnsignedInt(previous[i * 4 + 2]);
                    if (r == 255 && g == 0 && bl == 255) magenta++;
                    boolean raw = r == 0 && g == 0 && bl == 255;
                    if (depth[i] == 0 && raw) keptSky++;
                    if (depth[i] > 0 && !raw) replaced++;
                }
                assertEquals(0, magenta, "No invalid-camera pixels");
                assertEquals(WIDTH * HEIGHT - hits, keptSky, "Pixels without raster depth keep the raster color");
                assertTrue(replaced > hits * 8 / 10, "Matching depth must replace the raster surface: " + replaced + "/" + hits);
                // Static camera: history accumulates, so successive frames converge.
                double firstChange = 0, lastChange = 0;
                for (int frame = 1; frame <= 24; frame++) {
                    byte[] next = probe.renderWorld(pipeline, false, camera, uploads, uploads.update(snapshot));
                    double change = 0;
                    for (int i = 0; i < PIXEL_BYTES; i++) change += Math.abs(next[i] - previous[i]);
                    change /= hits;
                    if (frame == 1) firstChange = change;
                    if (frame == 24) lastChange = change;
                    previous = next;
                }
                System.out.printf("[BEDRAY-COMPUTE-PROBE] lit frames=25 replaced=%d/%d emitters=%d meanChange(frame1)=%.3f meanChange(frame24)=%.3f history=%s%n",
                    replaced, hits, uploads.emitters().count(), firstChange, lastChange, pipeline.historyStatus());
                assertTrue(lastChange < firstChange, "Temporal history must reduce frame-to-frame noise");
                writeImage(previous, "voxel-lit-vulkan.png");
                // Camera moved off every scene plane without new raster depth: surfaces fail the depth test and keep raster.
                CameraSnapshot moved = new CameraSnapshot(camX + 0.37, camY + 0.53, camZ - 0.41, inverseProjection, identity);
                byte[] stale = probe.renderWorld(pipeline, false, moved, uploads,
                    uploads.update(new WorldSnapshot(1, 1, moved, scene.sections())));
                int raw = 0;
                for (int i = 0; i < WIDTH * HEIGHT; i++)
                    if (stale[i * 4] == 0 && stale[i * 4 + 1] == 0 && Byte.toUnsignedInt(stale[i * 4 + 2]) == 255) raw++;
                assertTrue(raw > WIDTH * HEIGHT * 9 / 10, "Mismatched depth must preserve raster: " + raw);
            } finally {
                probe.lighting = null;
            }
        } finally {
            GraphicsBackendRegistry.setDebugView(previousView);
        }
    }

    private record LitScene(List<SectionSnapshot> sections, VoxelGrid occupancy) {}

    /** World origin 0: floor y=20 (tinted tops), sunken water pool, back wall, emitter cube, emitting unsupported cell. */
    private static LitScene litScene() {
        VoxelGridBuilder occupancy = VoxelGrid.builder(48, 48, 48);
        var face = new io.github.andrexarlay.bedray.api.v1.CubeFaceMaterial(0, 0, 1, 0, 0, 1, 0xFFFFFFFF, false);
        var top = new io.github.andrexarlay.bedray.api.v1.CubeFaceMaterial(0, 0, 1, 0, 0, 1, 0xFFFFFFFF, true);
        var cubeMaterial = new io.github.andrexarlay.bedray.api.v1.CubeMaterial(List.of(face, top, face, face, face, face));
        float[] water = {0,1,0,0,0, 0,1,1,0,1, 1,1,1,1,1, 1,1,0,1,0};
        var waterModel = new io.github.andrexarlay.bedray.api.v1.BlockModelGeometry(List.of(
            new io.github.andrexarlay.bedray.api.v1.ModelQuad(water, 0xFFFFFFFF,
                io.github.andrexarlay.bedray.api.v1.ModelQuad.TRANSLUCENT | io.github.andrexarlay.bedray.api.v1.ModelQuad.CELL_TINT
                    | io.github.andrexarlay.bedray.api.v1.ModelQuad.FLUID, 0)));
        int emitterX = 24, emitterY = 21, emitterZ = 30, lavaX = 14, lavaY = 21, lavaZ = 16;
        List<SectionSnapshot> sections = new ArrayList<>();
        for (int tz = 0; tz < 3; tz++) for (int ty = 0; ty < 3; ty++) for (int tx = 0; tx < 3; tx++) {
            byte[] cells = new byte[4096];
            short[] cubes = new short[4096], models = new short[4096], light = new short[4096];
            int[] tints = new int[4096];
            for (int z = 0; z < 16; z++) for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++) {
                int gx = tx * 16 + x, gy = ty * 16 + y, gz = tz * 16 + z, index = x + 16 * (y + 16 * z);
                boolean pool = gx >= 20 && gx < 28 && gz >= 20 && gz < 28;
                byte kind = SectionSnapshot.EMPTY;
                if (gy == 19 || gy == 20 && !pool || gz == 8 && gy > 20 && gy <= 30 && gx >= 10 && gx < 38
                        || gx == emitterX && gy == emitterY && gz == emitterZ) kind = SectionSnapshot.OPAQUE_CUBE;
                if (gy == 20 && pool) kind = SectionSnapshot.MODEL;
                if (gx == lavaX && gy == lavaY && gz == lavaZ) kind = SectionSnapshot.UNSUPPORTED;
                cells[index] = kind;
                if (kind == SectionSnapshot.OPAQUE_CUBE) { cubes[index] = 1; tints[index] = 0xFF000000 | (0x40 + gx * 3) << 16 | 0xB04000; }
                if (kind == SectionSnapshot.MODEL) { models[index] = 1; tints[index] = 0xFF3F76E4; }
                if (kind == SectionSnapshot.OPAQUE_CUBE || kind == SectionSnapshot.MODEL) occupancy.set(gx, gy, gz, 1);
                int manhattan = Math.abs(gx - emitterX) + Math.abs(gy - emitterY) + Math.abs(gz - emitterZ);
                int lava = Math.abs(gx - lavaX) + Math.abs(gy - lavaY) + Math.abs(gz - lavaZ);
                int block = Math.max(0, Math.max(15 - manhattan, 15 - lava));
                int sky = gy > 20 ? 15 : 0;
                int emission = manhattan == 0 || lava == 0 ? 15 : 0;
                light[index] = (short) (block | sky << 4 | emission << 8);
            }
            sections.add(new SectionSnapshot(tx, ty, tz, 1, cells,
                new io.github.andrexarlay.bedray.api.v1.CubeSectionMaterials(1, List.of(cubeMaterial), cubes),
                new io.github.andrexarlay.bedray.api.v1.SectionModelGeometry(1, List.of(waterModel), models),
                new io.github.andrexarlay.bedray.api.v1.SectionLightData(light),
                new io.github.andrexarlay.bedray.api.v1.SectionTintData(tints)));
        }
        return new LitScene(sections, occupancy.build());
    }

    /** Borrowed-input stand-ins: 16x16 atlas, raster color cleared to blue, raster depth from the oracle. */
    private static final class LitInputs implements AutoCloseable {
        private final Probe probe;
        long atlas, atlasMemory, atlasView, color, colorMemory, colorView, depthImage, depthMemory, depthView;
        long staging, stagingMemory;

        LitInputs(Probe probe, float[] depth) {
            this.probe = probe;
            try (MemoryStack stack = MemoryStack.stackPush()) {
                long[] a = image(16, 16, VK_FORMAT_R8G8B8A8_UNORM, VK_IMAGE_ASPECT_COLOR_BIT, stack);
                atlas = a[0]; atlasMemory = a[1]; atlasView = a[2];
                long[] c = image(WIDTH, HEIGHT, VK_FORMAT_R8G8B8A8_UNORM, VK_IMAGE_ASPECT_COLOR_BIT, stack);
                color = c[0]; colorMemory = c[1]; colorView = c[2];
                long[] d = image(WIDTH, HEIGHT, VK_FORMAT_D32_SFLOAT, VK_IMAGE_ASPECT_DEPTH_BIT, stack);
                depthImage = d[0]; depthMemory = d[1]; depthView = d[2];
                LongBuffer handle = stack.mallocLong(1);
                check(vkCreateBuffer(probe.device, VkBufferCreateInfo.calloc(stack).sType$Default().size(depth.length * 4L)
                    .usage(VK_BUFFER_USAGE_TRANSFER_SRC_BIT).sharingMode(VK_SHARING_MODE_EXCLUSIVE), null, handle), "Create depth staging");
                staging = handle.get(0);
                VkMemoryRequirements requirements = VkMemoryRequirements.calloc(stack);
                vkGetBufferMemoryRequirements(probe.device, staging, requirements);
                stagingMemory = probe.allocate(probe.device.getPhysicalDevice(), requirements,
                    VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT, stack);
                check(vkBindBufferMemory(probe.device, staging, stagingMemory, 0), "Bind depth staging");
                PointerBuffer mapped = stack.mallocPointer(1);
                check(vkMapMemory(probe.device, stagingMemory, 0, depth.length * 4L, 0, mapped), "Map depth staging");
                MemoryUtil.memFloatBuffer(mapped.get(0), depth.length).put(depth);
                vkUnmapMemory(probe.device, stagingMemory);

                check(vkResetCommandBuffer(probe.command, 0), "Reset input command buffer");
                check(vkBeginCommandBuffer(probe.command, VkCommandBufferBeginInfo.calloc(stack).sType$Default()
                    .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT)), "Begin input command buffer");
                VkImageMemoryBarrier.Buffer toTransfer = VkImageMemoryBarrier.calloc(3, stack);
                long[] images = {atlas, color, depthImage};
                for (int i = 0; i < 3; i++) {
                    toTransfer.get(i).sType$Default().image(images[i]).srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                        .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).oldLayout(VK_IMAGE_LAYOUT_UNDEFINED)
                        .newLayout(VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL).srcAccessMask(0).dstAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT);
                    toTransfer.get(i).subresourceRange().aspectMask(i == 2 ? VK_IMAGE_ASPECT_DEPTH_BIT : VK_IMAGE_ASPECT_COLOR_BIT)
                        .levelCount(1).layerCount(1);
                }
                vkCmdPipelineBarrier(probe.command, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0, null, null, toTransfer);
                VkImageSubresourceRange.Buffer range = VkImageSubresourceRange.calloc(1, stack);
                range.get(0).aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
                VkClearColorValue gray = VkClearColorValue.calloc(stack);
                gray.float32(0, 0.7f).float32(1, 0.65f).float32(2, 0.6f).float32(3, 0.8f);
                vkCmdClearColorImage(probe.command, atlas, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, gray, range);
                VkClearColorValue blue = VkClearColorValue.calloc(stack);
                blue.float32(0, 0).float32(1, 0).float32(2, 1).float32(3, 1);
                vkCmdClearColorImage(probe.command, color, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, blue, range);
                VkBufferImageCopy.Buffer copy = VkBufferImageCopy.calloc(1, stack);
                copy.get(0).imageSubresource().aspectMask(VK_IMAGE_ASPECT_DEPTH_BIT).layerCount(1);
                copy.get(0).imageExtent().set(WIDTH, HEIGHT, 1);
                vkCmdCopyBufferToImage(probe.command, staging, depthImage, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, copy);
                for (int i = 0; i < 3; i++) toTransfer.get(i).oldLayout(VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL)
                    .newLayout(VK_IMAGE_LAYOUT_GENERAL).srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT).dstAccessMask(VK_ACCESS_SHADER_READ_BIT);
                vkCmdPipelineBarrier(probe.command, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, null, null, toTransfer);
                check(vkEndCommandBuffer(probe.command), "End input command buffer");
                VkSubmitInfo.Buffer submit = VkSubmitInfo.calloc(1, stack);
                submit.get(0).sType$Default().pCommandBuffers(stack.pointers(probe.command.address()));
                check(vkQueueSubmit(probe.queue, submit, VK_NULL_HANDLE), "Submit input upload");
                check(vkQueueWaitIdle(probe.queue), "Wait for input upload");
            }
        }

        private long[] image(int width, int height, int format, int aspect, MemoryStack stack) {
            LongBuffer handle = stack.mallocLong(1);
            VkImageCreateInfo info = VkImageCreateInfo.calloc(stack).sType$Default().imageType(VK_IMAGE_TYPE_2D)
                .format(format).mipLevels(1).arrayLayers(1).samples(VK_SAMPLE_COUNT_1_BIT).tiling(VK_IMAGE_TILING_OPTIMAL)
                .usage(VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT)
                .sharingMode(VK_SHARING_MODE_EXCLUSIVE).initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);
            info.extent().set(width, height, 1);
            check(vkCreateImage(probe.device, info, null, handle), "Create probe input image");
            long image = handle.get(0);
            VkMemoryRequirements requirements = VkMemoryRequirements.calloc(stack);
            vkGetImageMemoryRequirements(probe.device, image, requirements);
            long memory = probe.allocate(probe.device.getPhysicalDevice(), requirements, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, stack);
            check(vkBindImageMemory(probe.device, image, memory, 0), "Bind probe input image");
            VkImageViewCreateInfo view = VkImageViewCreateInfo.calloc(stack).sType$Default().image(image)
                .viewType(VK_IMAGE_VIEW_TYPE_2D).format(format);
            view.subresourceRange().aspectMask(aspect).levelCount(1).layerCount(1);
            check(vkCreateImageView(probe.device, view, null, handle), "Create probe input view");
            return new long[]{image, memory, handle.get(0)};
        }

        @Override public void close() {
            if (probe.device == null) return;
            vkDeviceWaitIdle(probe.device);
            for (long view : new long[]{atlasView, colorView, depthView}) if (view != 0) vkDestroyImageView(probe.device, view, null);
            for (long image : new long[]{atlas, color, depthImage}) if (image != 0) vkDestroyImage(probe.device, image, null);
            if (staging != 0) vkDestroyBuffer(probe.device, staging, null);
            for (long memory : new long[]{atlasMemory, colorMemory, depthMemory, stagingMemory}) if (memory != 0) vkFreeMemory(probe.device, memory, null);
        }
    }

    private record WorldFixture(WorldSnapshot snapshot, VoxelGrid opaque, VoxelGrid markers) {}

    private static WorldFixture worldScene(long revision, int wallZ, boolean revised, WorldFixture previous) {
        VoxelGridBuilder opaque = VoxelGrid.builder(WORLD_SIZE, WORLD_SIZE, WORLD_SIZE);
        VoxelGridBuilder markers = VoxelGrid.builder(WORLD_SIZE, WORLD_SIZE, WORLD_SIZE);
        List<SectionSnapshot> sections = new ArrayList<>();
        for (int tz = 0; tz < 3; tz++) for (int ty = 0; ty < 3; ty++) for (int tx = 0; tx < 3; tx++) {
            // One visible corner remains unloaded. Shaded traversal omits it; occupancy view marks it.
            if (tx == 0 && ty == 0 && tz == 2) {
                markers.fillBox(0, 0, 32, 16, 16, 48, 4);
                continue;
            }
            byte[] cells = new byte[SectionSnapshot.CELL_COUNT];
            for (int z = 0; z < 16; z++) for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++) {
                int gx = tx * 16 + x, gy = ty * 16 + y, gz = tz * 16 + z;
                int kind = gz == wallZ ? SectionSnapshot.OPAQUE_CUBE : SectionSnapshot.EMPTY;
                if (gz == wallZ && gx >= 32 && gx < 40 && gy >= 20 && gy < 28) kind = SectionSnapshot.UNSUPPORTED;
                if (revised && gz == wallZ && gx >= 20 && gx < 28 && gy >= 20 && gy < 28) kind = SectionSnapshot.EMPTY;
                cells[x + 16 * (y + 16 * z)] = (byte) kind;
                opaque.set(gx, gy, gz, kind == SectionSnapshot.OPAQUE_CUBE ? 1 : 0);
                markers.set(gx, gy, gz, kind);
            }
            SectionSnapshot section = new SectionSnapshot(WORLD_X / 16 + tx, WORLD_Y / 16 + ty,
                    WORLD_Z / 16 + tz, revision, cells);
            if (previous != null) {
                for (SectionSnapshot old : previous.snapshot().sections()) {
                    if (old.x() == section.x() && old.y() == section.y() && old.z() == section.z()
                            && section.hasSameCells(old)) {
                        section = old;
                        break;
                    }
                }
            }
            sections.add(section);
        }
        float[] identity = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
        CameraSnapshot camera = new CameraSnapshot(WORLD_X + 24.0, WORLD_Y + 24.0, WORLD_Z + 24.0,
                identity, identity);
        return new WorldFixture(new WorldSnapshot(47, revision, camera, sections), opaque.build(), markers.build());
    }

    private static WorldFixture withSingleCube(WorldFixture previous) {
        VoxelGridBuilder opaque = VoxelGrid.builder(WORLD_SIZE, WORLD_SIZE, WORLD_SIZE);
        VoxelGridBuilder markers = VoxelGrid.builder(WORLD_SIZE, WORLD_SIZE, WORLD_SIZE);
        for (int z = 0; z < WORLD_SIZE; z++) for (int y = 0; y < WORLD_SIZE; y++) for (int x = 0; x < WORLD_SIZE; x++) {
            opaque.set(x, y, z, previous.opaque().material(x, y, z));
            markers.set(x, y, z, previous.markers().material(x, y, z));
        }
        opaque.set(24, 24, 44, 1);
        markers.set(24, 24, 44, 1);
        List<SectionSnapshot> sections = new ArrayList<>(previous.snapshot().sections());
        for (int i = 0; i < sections.size(); i++) {
            SectionSnapshot old = sections.get(i);
            if (old.x() == WORLD_X / 16 + 1 && old.y() == WORLD_Y / 16 + 1 && old.z() == WORLD_Z / 16 + 2) {
                byte[] cells = new byte[SectionSnapshot.CELL_COUNT];
                for (int z = 0; z < 16; z++) for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++) {
                    cells[x + 16 * (y + 16 * z)] = old.cell(x, y, z);
                }
                cells[8 + 16 * (8 + 16 * 12)] = SectionSnapshot.OPAQUE_CUBE;
                sections.set(i, new SectionSnapshot(old.x(), old.y(), old.z(), 3, cells));
                break;
            }
        }
        return new WorldFixture(new WorldSnapshot(47, 3, previous.snapshot().camera(), sections),
                opaque.build(), markers.build());
    }

    private static int changedPixels(byte[] before, byte[] after) {
        int changed = 0;
        for (int at = 0; at < before.length; at += 4) {
            if (before[at] != after[at] || before[at + 1] != after[at + 1]
                    || before[at + 2] != after[at + 2] || before[at + 3] != after[at + 3]) changed++;
        }
        return changed;
    }

    private static void compareWorldIds(VoxelGrid oracle, byte[] ids, String phase, boolean markersExpected) {
        int mismatches = 0;
        int unexplained = 0;
        int cpuHits = 0;
        int gpuHits = 0;
        int excludedHits = 0;
        int unknownHits = 0;
        for (int y = 0; y < HEIGHT; y++) for (int x = 0; x < WIDTH; x++) {
            int at = (x + WIDTH * y) * 4;
            VoxelHit expected = worldCpuHit(oracle, x, y);
            if (expected != null) cpuHits++;
            int kind = Byte.toUnsignedInt(ids[at + 3]);
            if (kind != 0) gpuHits++;
            if (kind == 2) excludedHits++;
            if (kind == 4) unknownHits++;
            if (!matches(ids, at, expected)) {
                mismatches++;
                boolean boundary = false;
                for (float py : new float[]{-0.001f, 0, 0.001f}) {
                    for (float px : new float[]{-0.001f, 0, 0.001f}) {
                        boundary |= matches(ids, at, worldCpuHit(oracle, x + px, y + py));
                    }
                }
                if (!boundary) unexplained++;
            }
        }
        System.out.printf("[BEDRAY-COMPUTE-PROBE] phase=%s pixels=%d cpuHits=%d gpuHits=%d "
                        + "excludedHits=%d unknownHits=%d mismatches=%d unexplained=%d%n",
                phase, WIDTH * HEIGHT, cpuHits, gpuHits, excludedHits, unknownHits, mismatches, unexplained);
        assertTrue(gpuHits > 500, "World camera must intersect the uploaded wall");
        if (markersExpected) {
            assertTrue(excludedHits > 0, "Occupancy view must expose unsupported markers");
            assertTrue(unknownHits > 0, "Occupancy view must expose unloaded markers");
        } else {
            assertEquals(0, excludedHits, "Shaded opaque traversal must not invent solid unsupported geometry");
            assertEquals(0, unknownHits, "Shaded opaque traversal must not invent solid unloaded geometry");
        }
        assertEquals(0, unexplained, "World GPU IDs differ beyond numerical cell boundaries");
        assertTrue(mismatches <= WIDTH * HEIGHT / 1000, "More than 0.1 percent of world rays disagree: " + mismatches);
    }

    private static VoxelHit worldCpuHit(VoxelGrid oracle, float pixelX, float pixelY) {
        float ndcX = (pixelX + 0.5f) / WIDTH * 2 - 1;
        float ndcY = (pixelY + 0.5f) / HEIGHT * 2 - 1;
        // Identity inverse view-projection and zero bob origin: (ndc.x,ndc.y,0.5,1).
        float[] ray = normalize(ndcX, ndcY, 0.5f);
        return VoxelDda.trace(oracle, 24, 24, 24, ray[0], ray[1], ray[2], 0, 160);
    }

    private static boolean matches(byte[] ids, int offset, VoxelHit hit) {
        if (hit == null) return ids[offset] == 0 && ids[offset + 1] == 0 && ids[offset + 2] == 0 && ids[offset + 3] == 0;
        return Byte.toUnsignedInt(ids[offset]) == hit.x() && Byte.toUnsignedInt(ids[offset + 1]) == hit.y()
                && Byte.toUnsignedInt(ids[offset + 2]) == hit.z() && Byte.toUnsignedInt(ids[offset + 3]) == hit.material();
    }

    private static VoxelHit cpuHit(VoxelGrid grid, float pixelX, float pixelY) {
        float u = ((pixelX + 0.5f) / WIDTH * 2 - 1) * ((float) WIDTH / HEIGHT);
        float v = (pixelY + 0.5f) / HEIGHT * 2 - 1;
        float[] forward = normalize(-22, -19, -27);
        float[] right = normalize(-forward[2], 0, forward[0]);
        float[] up = cross(right, forward);
        float[] ray = normalize(forward[0] + 0.62f * u * right[0] - 0.62f * v * up[0],
                forward[1] + 0.62f * u * right[1] - 0.62f * v * up[1],
                forward[2] + 0.62f * u * right[2] - 0.62f * v * up[2]);
        return VoxelDda.trace(grid, 38, 24, 43, ray[0], ray[1], ray[2], 0, 160);
    }

    private static float[] normalize(float x, float y, float z) {
        float inverseLength = 1 / (float) Math.sqrt(x * x + y * y + z * z);
        return new float[]{x * inverseLength, y * inverseLength, z * inverseLength};
    }

    private static float[] cross(float[] a, float[] b) {
        return new float[]{a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
    }

    private static void check(int result, String operation) {
        assertEquals(VK_SUCCESS, result, operation + " failed: VkResult=" + result);
    }

    static final class Probe implements AutoCloseable {
        VkInstance instance;
        VkDevice device;
        VkQueue queue;
        VkCommandBuffer command;
        String deviceName;
        long commandPool;
        long target;
        long targetMemory;
        long readback;
        long readbackMemory;
        boolean targetInitialized;
        io.github.andrexarlay.bedray.api.v1.LightingFrame lighting;

        Probe() {
            try {
                create();
            } catch (RuntimeException | Error failure) {
                close();
                throw failure;
            }
        }

        private void create() {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                PointerBuffer pointer = stack.mallocPointer(1);
                VkApplicationInfo application = VkApplicationInfo.calloc(stack).sType$Default()
                        .pApplicationName(stack.UTF8("Bedray Compute offscreen probe"))
                        .applicationVersion(1).pEngineName(stack.UTF8("Bedray Compute"))
                        .engineVersion(1).apiVersion(VK_API_VERSION_1_0);
                // calloc leaves enabledLayerCount and enabledExtensionCount at zero.
                VkInstanceCreateInfo info = VkInstanceCreateInfo.calloc(stack).sType$Default().pApplicationInfo(application);
                assertEquals(0, info.enabledExtensionCount());
                check(vkCreateInstance(info, null, pointer), "Create Vulkan 1.0 instance");
                instance = new VkInstance(pointer.get(0), info);
                IntBuffer count = stack.ints(0);
                check(vkEnumeratePhysicalDevices(instance, count, null), "Count physical devices");
                assertTrue(count.get(0) > 0, "No Vulkan physical devices");
                PointerBuffer physicals = stack.mallocPointer(count.get(0));
                check(vkEnumeratePhysicalDevices(instance, count, physicals), "Enumerate physical devices");
                VkPhysicalDevice physical = null;
                int family = -1;
                for (int i = 0; i < physicals.remaining() && physical == null; i++) {
                    VkPhysicalDevice candidate = new VkPhysicalDevice(physicals.get(i), instance);
                    vkGetPhysicalDeviceQueueFamilyProperties(candidate, count, null);
                    VkQueueFamilyProperties.Buffer families = VkQueueFamilyProperties.calloc(count.get(0), stack);
                    vkGetPhysicalDeviceQueueFamilyProperties(candidate, count, families);
                    for (int j = 0; j < families.capacity(); j++) {
                        int flags = VK_QUEUE_GRAPHICS_BIT | VK_QUEUE_COMPUTE_BIT;
                        if (families.get(j).queueCount() > 0 && (families.get(j).queueFlags() & flags) == flags) {
                            physical = candidate;
                            family = j;
                            break;
                        }
                    }
                }
                assertNotNull(physical, "No queue family supports both graphics and compute");
                VkPhysicalDeviceProperties properties = VkPhysicalDeviceProperties.calloc(stack);
                vkGetPhysicalDeviceProperties(physical, properties);
                deviceName = properties.deviceNameString();
                VkDeviceQueueCreateInfo.Buffer queues = VkDeviceQueueCreateInfo.calloc(1, stack);
                queues.get(0).sType$Default().queueFamilyIndex(family).pQueuePriorities(stack.floats(1));
                // No extension names, no pEnabledFeatures, and no pNext feature chain.
                VkDeviceCreateInfo deviceInfo = VkDeviceCreateInfo.calloc(stack).sType$Default().pQueueCreateInfos(queues);
                assertEquals(0, deviceInfo.enabledExtensionCount());
                assertEquals(0, deviceInfo.pNext());
                assertNull(deviceInfo.pEnabledFeatures());
                check(vkCreateDevice(physical, deviceInfo, null, pointer), "Create device without extensions");
                device = new VkDevice(pointer.get(0), physical, deviceInfo);
                vkGetDeviceQueue(device, family, 0, pointer);
                queue = new VkQueue(pointer.get(0), device);
                LongBuffer handle = stack.mallocLong(1);
                check(vkCreateCommandPool(device, VkCommandPoolCreateInfo.calloc(stack).sType$Default()
                        .queueFamilyIndex(family).flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT), null, handle), "Create command pool");
                commandPool = handle.get(0);
                check(vkAllocateCommandBuffers(device, VkCommandBufferAllocateInfo.calloc(stack).sType$Default()
                        .commandPool(commandPool).level(VK_COMMAND_BUFFER_LEVEL_PRIMARY).commandBufferCount(1), pointer), "Allocate command buffer");
                command = new VkCommandBuffer(pointer.get(0), device);

                VkImageCreateInfo imageInfo = VkImageCreateInfo.calloc(stack).sType$Default().imageType(VK_IMAGE_TYPE_2D)
                        .format(VK_FORMAT_R8G8B8A8_UNORM).mipLevels(1).arrayLayers(1).samples(VK_SAMPLE_COUNT_1_BIT)
                        .tiling(VK_IMAGE_TILING_OPTIMAL).usage(VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT)
                        .sharingMode(VK_SHARING_MODE_EXCLUSIVE).initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);
                imageInfo.extent().set(WIDTH, HEIGHT, 1);
                check(vkCreateImage(device, imageInfo, null, handle), "Create target image");
                target = handle.get(0);
                VkMemoryRequirements requirements = VkMemoryRequirements.calloc(stack);
                vkGetImageMemoryRequirements(device, target, requirements);
                targetMemory = allocate(physical, requirements, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, stack);
                check(vkBindImageMemory(device, target, targetMemory, 0), "Bind target memory");
                check(vkCreateBuffer(device, VkBufferCreateInfo.calloc(stack).sType$Default().size(PIXEL_BYTES)
                        .usage(VK_BUFFER_USAGE_TRANSFER_DST_BIT).sharingMode(VK_SHARING_MODE_EXCLUSIVE), null, handle), "Create readback buffer");
                readback = handle.get(0);
                vkGetBufferMemoryRequirements(device, readback, requirements);
                readbackMemory = allocate(physical, requirements,
                        VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT, stack);
                check(vkBindBufferMemory(device, readback, readbackMemory, 0), "Bind readback memory");
            }
        }

        long allocate(VkPhysicalDevice physical, VkMemoryRequirements requirements, int flags, MemoryStack stack) {
            VkPhysicalDeviceMemoryProperties memory = VkPhysicalDeviceMemoryProperties.calloc(stack);
            vkGetPhysicalDeviceMemoryProperties(physical, memory);
            for (int type = 0; type < memory.memoryTypeCount(); type++) {
                if ((requirements.memoryTypeBits() & (1 << type)) != 0
                        && (memory.memoryTypes(type).propertyFlags() & flags) == flags) {
                    LongBuffer handle = stack.mallocLong(1);
                    check(vkAllocateMemory(device, VkMemoryAllocateInfo.calloc(stack).sType$Default()
                            .allocationSize(requirements.size()).memoryTypeIndex(type), null, handle), "Allocate probe memory");
                    return handle.get(0);
                }
            }
            throw new AssertionError("No compatible probe memory type for flags " + flags);
        }

        byte[] render(VoxelComputePipeline pipeline, boolean hitIds) {
            return render(pipeline, hitIds, null, null, List.of());
        }

        byte[] renderWorld(VoxelComputePipeline pipeline, boolean hitIds, CameraSnapshot camera,
                           VoxelWorldUpload uploads, List<VoxelWorldUpload.Tile> updates) {
            return render(pipeline, hitIds, camera, uploads, updates);
        }

        private byte[] render(VoxelComputePipeline pipeline, boolean hitIds, CameraSnapshot camera,
                              VoxelWorldUpload uploads, List<VoxelWorldUpload.Tile> updates) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                check(vkResetCommandBuffer(command, 0), "Reset command buffer");
                check(vkBeginCommandBuffer(command, VkCommandBufferBeginInfo.calloc(stack).sType$Default()
                        .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT)), "Begin command buffer");
                if (!targetInitialized) {
                    VkImageMemoryBarrier.Buffer transition = VkImageMemoryBarrier.calloc(1, stack);
                    transition.get(0).sType$Default().image(target).srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                            .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).oldLayout(VK_IMAGE_LAYOUT_UNDEFINED)
                            .newLayout(VK_IMAGE_LAYOUT_GENERAL).srcAccessMask(0).dstAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT);
                    transition.get(0).subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
                    vkCmdPipelineBarrier(command, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
                            0, null, null, transition);
                }
                if (camera == null) pipeline.record(command, target, hitIds);
                else if (lighting != null) pipeline.recordWorld(command, target, camera, uploads, updates, lighting);
                else pipeline.recordWorld(command, target, hitIds, camera, uploads, updates);
                VkImageMemoryBarrier.Buffer ready = VkImageMemoryBarrier.calloc(1, stack);
                ready.get(0).sType$Default().image(target).srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                        .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).oldLayout(VK_IMAGE_LAYOUT_GENERAL)
                        .newLayout(VK_IMAGE_LAYOUT_GENERAL).srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT)
                        .dstAccessMask(VK_ACCESS_TRANSFER_READ_BIT);
                ready.get(0).subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
                vkCmdPipelineBarrier(command, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
                        0, null, null, ready);
                VkBufferImageCopy.Buffer copy = VkBufferImageCopy.calloc(1, stack);
                copy.get(0).imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
                copy.get(0).imageExtent().set(WIDTH, HEIGHT, 1);
                vkCmdCopyImageToBuffer(command, target, VK_IMAGE_LAYOUT_GENERAL, readback, copy);
                VkBufferMemoryBarrier.Buffer host = VkBufferMemoryBarrier.calloc(1, stack);
                host.get(0).sType$Default().buffer(readback).offset(0).size(PIXEL_BYTES)
                        .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                        .srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT).dstAccessMask(VK_ACCESS_HOST_READ_BIT);
                vkCmdPipelineBarrier(command, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_HOST_BIT,
                        0, null, host, null);
                check(vkEndCommandBuffer(command), "End command buffer");
                VkSubmitInfo.Buffer submit = VkSubmitInfo.calloc(1, stack);
                submit.get(0).sType$Default().pCommandBuffers(stack.pointers(command.address()));
                check(vkQueueSubmit(queue, submit, VK_NULL_HANDLE), "Submit compute probe");
                check(vkQueueWaitIdle(queue), "Wait for compute probe");
                targetInitialized = true;
                PointerBuffer mapped = stack.mallocPointer(1);
                check(vkMapMemory(device, readbackMemory, 0, PIXEL_BYTES, 0, mapped), "Map probe readback");
                try {
                    ByteBuffer bytes = MemoryUtil.memByteBuffer(mapped.get(0), PIXEL_BYTES);
                    byte[] result = new byte[PIXEL_BYTES];
                    bytes.get(result);
                    return result;
                } finally {
                    vkUnmapMemory(device, readbackMemory);
                }
            }
        }

        @Override public void close() {
            if (device != null) {
                vkDeviceWaitIdle(device);
                if (readback != 0) vkDestroyBuffer(device, readback, null);
                if (readbackMemory != 0) vkFreeMemory(device, readbackMemory, null);
                if (target != 0) vkDestroyImage(device, target, null);
                if (targetMemory != 0) vkFreeMemory(device, targetMemory, null);
                if (commandPool != 0) vkDestroyCommandPool(device, commandPool, null);
                vkDestroyDevice(device, null);
                device = null;
            }
            if (instance != null) {
                vkDestroyInstance(instance, null);
                instance = null;
            }
        }
    }
}
