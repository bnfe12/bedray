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

    private record WorldFixture(WorldSnapshot snapshot, VoxelGrid opaque, VoxelGrid markers) {}

    private static WorldFixture worldScene(long revision, int wallZ, boolean revised, WorldFixture previous) {
        VoxelGridBuilder opaque = VoxelGrid.builder(WORLD_SIZE, WORLD_SIZE, WORLD_SIZE);
        VoxelGridBuilder markers = VoxelGrid.builder(WORLD_SIZE, WORLD_SIZE, WORLD_SIZE);
        List<SectionSnapshot> sections = new ArrayList<>();
        for (int tz = 0; tz < 3; tz++) for (int ty = 0; ty < 3; ty++) for (int tx = 0; tx < 3; tx++) {
            // One visible corner remains unloaded. Shaded traversal omits it; occupancy view marks it.
            if (tx == 0 && ty == 0 && tz == 2) {
                markers.fillBox(0, 0, 32, 16, 16, 48, 3);
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
            if (kind == 3) unknownHits++;
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

    private static final class Probe implements AutoCloseable {
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

        private long allocate(VkPhysicalDevice physical, VkMemoryRequirements requirements, int flags, MemoryStack stack) {
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
