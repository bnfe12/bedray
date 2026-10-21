package io.github.andrexarlay.bedray.compute;

import io.github.andrexarlay.bedray.api.v1.ComputeSceneFrame;
import io.github.andrexarlay.bedray.api.v1.VoxelSceneSnapshot;
import io.github.andrexarlay.bedray.compute.voxel.VoxelDda;
import io.github.andrexarlay.bedray.compute.voxel.VoxelDemoScene;
import io.github.andrexarlay.bedray.compute.voxel.VoxelGrid;
import io.github.andrexarlay.bedray.compute.voxel.VoxelHit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
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

import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.vulkan.VK10.*;

/** Explicitly requested native probe: it fails rather than skipping if no suitable device exists. */
@EnabledIfSystemProperty(named = "bedray.compute.vulkanProbe", matches = "true")
class VulkanComputeProbeTest {
    private static final int WIDTH = 192;
    private static final int HEIGHT = 128;
    private static final int PIXEL_BYTES = WIDTH * HEIGHT * 4;

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

            byte[] rgba = probe.render(pipeline, false);
            BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < HEIGHT; y++) for (int x = 0; x < WIDTH; x++) {
                int at = (x + WIDTH * y) * 4;
                int argb = Byte.toUnsignedInt(rgba[at + 3]) << 24
                        | Byte.toUnsignedInt(rgba[at]) << 16
                        | Byte.toUnsignedInt(rgba[at + 1]) << 8
                        | Byte.toUnsignedInt(rgba[at + 2]);
                image.setRGB(x, y, argb);
            }
            Path output = Path.of("build", "reports", "compute", "voxel-vulkan.png");
            Files.createDirectories(output.getParent());
            assertTrue(ImageIO.write(image, "png", output.toFile()), "PNG writer is unavailable");
            System.out.println("[BEDRAY-COMPUTE-PROBE] Offscreen shaded artifact=" + output.toAbsolutePath());
        }
    }

    @Test void worldCameraAndSuccessiveSceneRevisionsReachGpu() {
        VoxelSceneSnapshot firstSnapshot = worldSnapshot(1, 16, false);
        VoxelSceneSnapshot secondSnapshot = worldSnapshot(2, 20, true);
        float[] identity = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
        ComputeSceneFrame firstFrame = new ComputeSceneFrame(firstSnapshot, identity,
                16, 12, -10, 0, 0, 0);
        ComputeSceneFrame secondFrame = new ComputeSceneFrame(secondSnapshot, identity,
                16, 12, -10, 0, 0, 0);
        try (Probe probe = new Probe();
             VoxelComputePipeline pipeline = new VoxelComputePipeline(probe.device, WIDTH, HEIGHT,
                     VK_FORMAT_R8G8B8A8_UNORM, firstSnapshot)) {
            byte[] first = probe.render(pipeline, true, firstFrame);
            compareWorldIds(firstSnapshot, first, "world-revision-1");
            byte[] second = probe.render(pipeline, true, secondFrame);
            compareWorldIds(secondSnapshot, second, "world-revision-2");
            int changedPixels = 0;
            for (int at = 0; at < first.length; at += 4) {
                if (first[at] != second[at] || first[at + 1] != second[at + 1]
                        || first[at + 2] != second[at + 2] || first[at + 3] != second[at + 3]) changedPixels++;
            }
            System.out.printf("[BEDRAY-COMPUTE-PROBE] successiveRevisions=1,2 samePipeline=true changedPixels=%d%n", changedPixels);
            assertTrue(changedPixels > 500, "Moved/removed/reclassified wall must change the uploaded scene");
        }
    }

    private static VoxelSceneSnapshot worldSnapshot(long revision, int wallZ, boolean revised) {
        int[] cells = new int[32 * 24 * 32];
        for (int y = 0; y < 24; y++) for (int x = 0; x < 32; x++) {
            int kind = 1;
            if (x >= 8 && x < 12 && y >= 8 && y < 12) kind = revised ? 3 : 2;
            if (x >= 20 && x < 24 && y >= 10 && y < 14) kind = revised ? 2 : 3;
            if (revised && x >= 12 && x < 20 && y >= 8 && y < 16) kind = 0;
            cells[x + 32 * (y + 24 * wallZ)] = kind;
        }
        // Large world coordinates exercise the API's local-grid convention. The shader sees
        // local coordinates only; neither the camera nor the grid is narrowed before rebasing.
        return new VoxelSceneSnapshot(29_999_984, 64, -20_000_016, 32, 24, 32, 47L, revision, cells);
    }

    private static void compareWorldIds(VoxelSceneSnapshot snapshot, byte[] ids, String phase) {
        int mismatches = 0;
        int unexplained = 0;
        int cpuHits = 0;
        int gpuHits = 0;
        int excludedHits = 0;
        int unknownHits = 0;
        for (int y = 0; y < HEIGHT; y++) for (int x = 0; x < WIDTH; x++) {
            int at = (x + WIDTH * y) * 4;
            VoxelHit expected = worldCpuHit(snapshot, x, y);
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
                        boundary |= matches(ids, at, worldCpuHit(snapshot, x + px, y + py));
                    }
                }
                if (!boundary) unexplained++;
            }
        }
        System.out.printf("[BEDRAY-COMPUTE-PROBE] phase=%s pixels=%d cpuHits=%d gpuHits=%d "
                        + "excludedHits=%d unknownHits=%d mismatches=%d unexplained=%d%n",
                phase, WIDTH * HEIGHT, cpuHits, gpuHits, excludedHits, unknownHits, mismatches, unexplained);
        assertTrue(gpuHits > 500, "World camera must intersect the uploaded wall");
        assertTrue(excludedHits > 0, "Excluded cells must remain diagnostic markers rather than empty air");
        assertTrue(unknownHits > 0, "Unknown cells must remain diagnostic markers rather than empty air");
        assertEquals(0, unexplained, "World GPU IDs differ beyond numerical cell boundaries");
        assertTrue(mismatches <= WIDTH * HEIGHT / 1000, "More than 0.1 percent of world rays disagree: " + mismatches);
    }

    private static VoxelHit worldCpuHit(VoxelSceneSnapshot snapshot, float pixelX, float pixelY) {
        float ndcX = (pixelX + 0.5f) / WIDTH * 2 - 1;
        float ndcY = (pixelY + 0.5f) / HEIGHT * 2 - 1;
        // Identity inverse view-projection and zero bob origin: (ndc.x,ndc.y,0.5,1).
        float[] ray = normalize(ndcX, ndcY, 0.5f);
        return VoxelDda.trace(snapshot, 16, 12, -10, ray[0], ray[1], ray[2], 0, 160);
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
            return render(pipeline, hitIds, null);
        }

        byte[] render(VoxelComputePipeline pipeline, boolean hitIds, ComputeSceneFrame scene) {
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
                pipeline.record(command, target, hitIds, scene);
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
