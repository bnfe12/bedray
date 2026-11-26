package io.github.andrexarlay.vulkancompat.shaderhost.mosaic.studio;

import io.github.andrexarlay.vulkancompat.shaderhost.glsl.SpirvReflection;
import io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicCodegen;
import io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicShaderCheck;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;

import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.lwjgl.vulkan.VK10.*;

/**
 * Runs a Mosaic compute shader inside the studio on the GPU, with the same set-0 contract as the game's custom RT stage:
 * frame UBO (1), scene colour (2) from an input picture, output rgba16f (3), depth (4, "sky everywhere"), user buffer (5),
 * LUT (6), world volume (15, empty) and the push block. Graphs that trace rays need the game's scene and are refused.
 * One device for the studio's lifetime; pipeline and images are rebuilt only when the shader or the size changes.
 */
final class GpuPreview implements AutoCloseable {

    private final VkInstance instance;
    private final VkPhysicalDevice physical;
    private final VkDevice device;
    private final VkQueue queue;
    private final int family;
    private final long commandPool, fence;
    private final VkCommandBuffer cmd;
    final String deviceName;
    /** Nanoseconds per timestamp tick (0: the queue writes no timestamps). */
    private float timestampPeriod;
    /** GPU time of the last run in ms: [0] the world G-buffer, then every pass in run order. */
    volatile double[] lastTimings;


    private GpuPreview(VkInstance instance, VkPhysicalDevice physical, VkDevice device, int family, String name) {
        this.instance = instance;
        this.physical = physical;
        this.device = device;
        this.family = family;
        this.deviceName = name;
        try (MemoryStack s = MemoryStack.stackPush()) {
            PointerBuffer pq = s.mallocPointer(1);
            vkGetDeviceQueue(device, family, 0, pq);
            queue = new VkQueue(pq.get(0), device);
            // timestamps for per-pass GPU timings (mosaic render --profile), when the queue family writes them
            VkPhysicalDeviceProperties props = VkPhysicalDeviceProperties.calloc(s);
            vkGetPhysicalDeviceProperties(physical, props);
            IntBuffer nq = s.mallocInt(1);
            vkGetPhysicalDeviceQueueFamilyProperties(physical, nq, null);
            VkQueueFamilyProperties.Buffer qf = VkQueueFamilyProperties.calloc(nq.get(0), s);
            vkGetPhysicalDeviceQueueFamilyProperties(physical, nq, qf);
            timestampPeriod = qf.get(family).timestampValidBits() > 0 ? props.limits().timestampPeriod() : 0f;
            LongBuffer lp = s.mallocLong(1);
            check(vkCreateCommandPool(device, VkCommandPoolCreateInfo.calloc(s).sType$Default()
                .flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT).queueFamilyIndex(family), null, lp), "command pool");
            commandPool = lp.get(0);
            PointerBuffer pc = s.mallocPointer(1);
            check(vkAllocateCommandBuffers(device, VkCommandBufferAllocateInfo.calloc(s).sType$Default().commandPool(commandPool)
                .level(VK_COMMAND_BUFFER_LEVEL_PRIMARY).commandBufferCount(1), pc), "command buffer");
            cmd = new VkCommandBuffer(pc.get(0), device);
            check(vkCreateFence(device, VkFenceCreateInfo.calloc(s).sType$Default(), null, lp), "fence");
            fence = lp.get(0);
        }
    }

    /** A Vulkan device with a compute queue, or an exception saying why there is none. */
    static GpuPreview create() {
        try (MemoryStack s = MemoryStack.stackPush()) {
            VkApplicationInfo app = VkApplicationInfo.calloc(s).sType$Default().pApplicationName(s.UTF8("Mosaic Studio preview"))
                .apiVersion(VK.getInstanceVersionSupported() >= VK12.VK_API_VERSION_1_2 ? VK12.VK_API_VERSION_1_2 : VK_API_VERSION_1_0);
            PointerBuffer pi = s.mallocPointer(1);
            check(vkCreateInstance(VkInstanceCreateInfo.calloc(s).sType$Default().pApplicationInfo(app), null, pi), "instance");
            VkInstance inst = new VkInstance(pi.get(0), VkInstanceCreateInfo.calloc(s).sType$Default().pApplicationInfo(app));
            int[] n = new int[1];
            vkEnumeratePhysicalDevices(inst, n, null);
            if (n[0] == 0) throw new IllegalStateException("no Vulkan device");
            PointerBuffer devs = s.mallocPointer(n[0]);
            vkEnumeratePhysicalDevices(inst, n, devs);
            VkPhysicalDevice best = null;
            int bestScore = -1, bestFamily = -1;
            String bestName = "";
            for (int i = 0; i < n[0]; i++) {
                VkPhysicalDevice pd = new VkPhysicalDevice(devs.get(i), inst);
                VkPhysicalDeviceProperties props = VkPhysicalDeviceProperties.calloc(s);
                vkGetPhysicalDeviceProperties(pd, props);
                int[] qn = new int[1];
                vkGetPhysicalDeviceQueueFamilyProperties(pd, qn, null);
                VkQueueFamilyProperties.Buffer qs = VkQueueFamilyProperties.calloc(qn[0], s);
                vkGetPhysicalDeviceQueueFamilyProperties(pd, qn, qs);
                int fam = -1;
                for (int q = 0; q < qn[0]; q++) if ((qs.get(q).queueFlags() & VK_QUEUE_COMPUTE_BIT) != 0) { fam = q; break; }
                if (fam < 0) continue;
                int score = switch (props.deviceType()) {
                    case VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU -> 3;
                    case VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU -> 2;
                    default -> 1;
                };
                if (score > bestScore) {
                    bestScore = score;
                    best = pd;
                    bestFamily = fam;
                    bestName = props.deviceNameString();
                }
            }
            if (best == null) throw new IllegalStateException("no Vulkan device with compute");
            VkDeviceQueueCreateInfo.Buffer qci = VkDeviceQueueCreateInfo.calloc(1, s);
            qci.get(0).sType$Default().queueFamilyIndex(bestFamily).pQueuePriorities(s.floats(1f));
            PointerBuffer pd = s.mallocPointer(1);
            check(vkCreateDevice(best, VkDeviceCreateInfo.calloc(s).sType$Default().pQueueCreateInfos(qci), null, pd), "device");
            return new GpuPreview(inst, best, new VkDevice(pd.get(0), best, VkDeviceCreateInfo.calloc(s).sType$Default().pQueueCreateInfos(qci)),
                bestFamily, bestName);
        }
    }

    private static void check(int r, String what) {
        if (r != VK_SUCCESS) throw new IllegalStateException("Vulkan " + what + " failed (" + r + ")");
    }

    // ------------------------------------------------------------------ resources

    private record Buf(long handle, long memory, long size, long mapped) {}

    private record Img(long image, long memory, long view, int w, int h, int format) {}

    /** One compute pass of a chain: SPIR-V, size divisor, the buffer binding it writes (-1 for the final image). */
    record PassRun(byte[] spirv, int scale, int binding, boolean after) {
        PassRun(byte[] spirv, int scale, int binding) { this(spirv, scale, binding, false); }
    }

    private final class Prepared {
        long module, setLayout, pipelineLayout, pipeline, pool, set;
        Img output;
        /** Layout the output is in between runs (buffers keep their content from run to run: history passes). */
        int layout = VK_IMAGE_LAYOUT_UNDEFINED;
        boolean fin;
        final List<Buf> buffers = new ArrayList<>();
        boolean push;

        void destroy() {
            if (pipeline != 0) vkDestroyPipeline(device, pipeline, null);
            if (pipelineLayout != 0) vkDestroyPipelineLayout(device, pipelineLayout, null);
            if (pool != 0) vkDestroyDescriptorPool(device, pool, null);
            if (setLayout != 0) vkDestroyDescriptorSetLayout(device, setLayout, null);
            if (module != 0) vkDestroyShaderModule(device, module, null);
            for (Buf b : buffers) free(b);
            free(output);
        }
    }

    /** Everything one chain of passes needs at one size; rebuilt when the shaders or the size change. */
    private final class Chain {
        int key, w, h;
        long sampler;
        Buf ubo, upload, readback;
        Img input, depth, one, history;
        final List<Prepared> passes = new ArrayList<>();
        boolean historyFresh = true;
        long nearest;
        /** The previous run's camera (BedrayPrevFrame, binding 17), shared by every pass and rewritten each run. */
        Buf prev;
        ByteBuffer lastCamera;
        // preview world: G-buffer pass (scene colour, ray distance) over the world volume
        boolean world;
        Buf worldBuf, gbWorldBuf;
        /** The world volume as the G-buffer reads it: cells with flags (0x80: model or entity quads here, 0x40: a block drawn by its model). */
        io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicWorld.Snapshot gbWorld;
        Img scene, dist, atlas;
        Buf farBuf;
        Buf blockIds, blockInfo, meshBuf, quadBuf, biomeBuf;
        /** Far shadows in the preview: the far land as the height field of binding 18 (MosaicHeight), following the camera. */
        Buf heightBuf;
        io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicHeight.Field heightField;
        long gbModule, gbSetLayout, gbLayout, gbPipeline, gbPool, gbSet;
        boolean gbFresh = true;
        /** GPU timestamps: start, after the G-buffer, after every pass (0 when the queue has no timestamps). */
        long queryPool;
        int queries;
        PreviewWorld.View lastView;

        void destroy() {
            vkDeviceWaitIdle(device);
            for (Prepared p : passes) p.destroy();
            if (sampler != 0) vkDestroySampler(device, sampler, null);
            if (nearest != 0) vkDestroySampler(device, nearest, null);
            if (queryPool != 0) vkDestroyQueryPool(device, queryPool, null);
            if (gbPipeline != 0) vkDestroyPipeline(device, gbPipeline, null);
            if (gbLayout != 0) vkDestroyPipelineLayout(device, gbLayout, null);
            if (gbPool != 0) vkDestroyDescriptorPool(device, gbPool, null);
            if (gbSetLayout != 0) vkDestroyDescriptorSetLayout(device, gbSetLayout, null);
            if (gbModule != 0) vkDestroyShaderModule(device, gbModule, null);
            if (lastCamera != null) MemoryUtil.memFree(lastCamera);
            for (Buf b : new Buf[] {ubo, upload, readback, prev, worldBuf, gbWorldBuf, farBuf, heightBuf, blockIds, blockInfo, meshBuf, quadBuf, biomeBuf}) free(b);
            for (Img i : new Img[] {input, depth, one, history, scene, dist, atlas}) free(i);
        }
    }

    private Chain chain;

    private int memoryType(int bits, int flags) {
        try (MemoryStack s = MemoryStack.stackPush()) {
            VkPhysicalDeviceMemoryProperties mp = VkPhysicalDeviceMemoryProperties.calloc(s);
            vkGetPhysicalDeviceMemoryProperties(physical, mp);
            for (int i = 0; i < mp.memoryTypeCount(); i++)
                if ((bits & (1 << i)) != 0 && (mp.memoryTypes(i).propertyFlags() & flags) == flags) return i;
        }
        throw new IllegalStateException("no suitable GPU memory");
    }

    private Buf buffer(long size, int usage) {
        try (MemoryStack s = MemoryStack.stackPush()) {
            LongBuffer lp = s.mallocLong(1);
            check(vkCreateBuffer(device, VkBufferCreateInfo.calloc(s).sType$Default().size(size).usage(usage)
                .sharingMode(VK_SHARING_MODE_EXCLUSIVE), null, lp), "buffer");
            long b = lp.get(0);
            VkMemoryRequirements req = VkMemoryRequirements.calloc(s);
            vkGetBufferMemoryRequirements(device, b, req);
            check(vkAllocateMemory(device, VkMemoryAllocateInfo.calloc(s).sType$Default().allocationSize(req.size())
                .memoryTypeIndex(memoryType(req.memoryTypeBits(), VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT)), null, lp), "memory");
            long m = lp.get(0);
            vkBindBufferMemory(device, b, m, 0);
            PointerBuffer pp = s.mallocPointer(1);
            vkMapMemory(device, m, 0, size, 0, pp);
            MemoryUtil.memSet(pp.get(0), 0, size);
            return new Buf(b, m, size, pp.get(0));
        }
    }

    private Img image(int w, int h, int format, int usage) {
        try (MemoryStack s = MemoryStack.stackPush()) {
            LongBuffer lp = s.mallocLong(1);
            VkImageCreateInfo ci = VkImageCreateInfo.calloc(s).sType$Default().imageType(VK_IMAGE_TYPE_2D).format(format)
                .mipLevels(1).arrayLayers(1).samples(VK_SAMPLE_COUNT_1_BIT).tiling(VK_IMAGE_TILING_OPTIMAL).usage(usage)
                .sharingMode(VK_SHARING_MODE_EXCLUSIVE).initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);
            ci.extent().set(w, h, 1);
            check(vkCreateImage(device, ci, null, lp), "image");
            long img = lp.get(0);
            VkMemoryRequirements req = VkMemoryRequirements.calloc(s);
            vkGetImageMemoryRequirements(device, img, req);
            check(vkAllocateMemory(device, VkMemoryAllocateInfo.calloc(s).sType$Default().allocationSize(req.size())
                .memoryTypeIndex(memoryType(req.memoryTypeBits(), VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT)), null, lp), "image memory");
            long mem = lp.get(0);
            vkBindImageMemory(device, img, mem, 0);
            VkImageViewCreateInfo vi = VkImageViewCreateInfo.calloc(s).sType$Default().image(img).viewType(VK_IMAGE_VIEW_TYPE_2D).format(format);
            vi.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
            check(vkCreateImageView(device, vi, null, lp), "image view");
            return new Img(img, mem, lp.get(0), w, h, format);
        }
    }

    private void free(Buf b) {
        if (b == null) return;
        vkUnmapMemory(device, b.memory());
        vkDestroyBuffer(device, b.handle(), null);
        vkFreeMemory(device, b.memory(), null);
    }

    private void free(Img i) {
        if (i == null) return;
        vkDestroyImageView(device, i.view(), null);
        vkDestroyImage(device, i.image(), null);
        vkFreeMemory(device, i.memory(), null);
    }

    // ------------------------------------------------------------------ run

    /** Extra inputs of a run: the pack's rt.push bytes, buffer5.bin and texture6.png, when there are any. */
    record Extras(byte[] push, byte[] buffer5, BufferedImage lut) {}

    /** A single-pass graph. */
    synchronized BufferedImage run(byte[] spirv, BufferedImage input, float time, int frame, Extras extras) {
        return runChain(List.of(new PassRun(spirv, 1, -1)), input, time, frame, extras);
    }

    /**
     * Runs the passes in order over {@code input} and returns the final image (clamped to 0..1 for display). Buffer passes
     * write their own rgba16f image at 1/scale size, later passes sample it at its binding; the final image is kept as
     * the previous frame (binding 19) of the next run. Throws when a shader needs the game's scene (ray tracing).
     */
    synchronized BufferedImage runChain(List<PassRun> runs, BufferedImage input, float time, int frame, Extras extras) {
        return runChain(runs, input, time, frame, extras, null);
    }

    /** Preview world size (Studio settings previewWidth / previewHeight): the G-buffer and every pass run at this size. */
    volatile int worldWidth = 640, worldHeight = 360;

    /**
     * With a {@code view}, the frame comes from the preview world instead of a picture: a G-buffer pass renders its blocks
     * (textures, the game's light map) into the scene colour (binding 2) and the ray distance (4), the world volume is
     * binding 15 and the camera moves between runs (binding 17 holds the previous one), so the graph sees a real scene.
     */
    synchronized BufferedImage runChain(List<PassRun> runs, BufferedImage input, float time, int frame, Extras extras, PreviewWorld.View view) {
        int w = view != null ? worldWidth : input.getWidth(), h = view != null ? worldHeight : input.getHeight();
        int key = java.util.Objects.hash(view != null, view == null ? 0 : gbufferSource(view).hashCode(), view == null ? 0 : view.world().bytes(), w, h,
            view == null ? 0 : System.identityHashCode(view.atlas()), view == null ? 0 : System.identityHashCode(view.world()), runs.stream().map(r -> Arrays.hashCode(r.spirv()) * 31 + r.scale() * 7 + r.binding() + (r.after() ? 1 << 20 : 0)).toList());
        if (chain == null || chain.key != key) {
            if (chain != null) chain.destroy();
            chain = null;
            chain = build(runs, key, w, h, extras, view);
        }
        Chain c = chain;
        writeFrame(c.ubo.mapped(), w, h, time, frame, view);
        // last run's camera for reprojection (Accumulate, TAA), this run's for the next
        ByteBuffer cam = MemoryUtil.memByteBuffer(c.ubo.mapped(), 320);
        if (c.lastCamera == null) {
            c.lastCamera = MemoryUtil.memAlloc(144);
            copyCamera(cam, c.lastCamera);
        }
        MemoryUtil.memCopy(MemoryUtil.memAddress(c.lastCamera), c.prev.mapped(), 144);
        copyCamera(cam, c.lastCamera);
        if (view != null) {
            var far = view.world().far();
            if (far != null) { // the far land doubles as the height field of far shadows (binding 18)
                boolean newWorld = c.lastView == null || c.lastView.world() != view.world();
                io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicHeight.Sampler hs = (hx, hz) -> {
                    int i = hx - far.ox(), j = hz - far.oz();
                    if (i < 0 || j < 0 || i >= far.size() || j >= far.size()) return Short.MIN_VALUE;
                    short top = far.heights()[j * far.size() + i];
                    return top == Short.MIN_VALUE ? Short.MIN_VALUE : top + 1;
                };
                int ox0 = c.heightField.originX(), oz0 = c.heightField.originZ();
                c.heightField.update(hs, view.x(), view.z(), 0);
                ByteBuffer hb = MemoryUtil.memByteBuffer(c.heightBuf.mapped(), (int) c.heightBuf.size());
                if (newWorld || c.heightField.originX() != ox0 || c.heightField.originZ() != oz0) {
                    c.heightField.update(hs, view.x(), view.z(), io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicHeight.COLUMNS);
                    c.heightField.writeColumns(hb, io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicHeight.HEADER_BYTES);
                }
                c.heightField.writeHeader(hb, view.x(), view.y(), view.z());
            }
            if (far != null)
                far.writeBuffer(MemoryUtil.memByteBuffer(c.farBuf.mapped(), (int) c.farBuf.size()), view.x(), view.y(), view.z(),
                    c.lastView == null || c.lastView.world() != view.world());
            if (c.lastView == null || c.lastView.world() != view.world()) {
                view.world().writeBuffer(MemoryUtil.memByteBuffer(c.worldBuf.mapped(), (int) c.worldBuf.size()), view.x(), view.y(), view.z());
                c.gbWorld.writeBuffer(MemoryUtil.memByteBuffer(c.gbWorldBuf.mapped(), (int) c.gbWorldBuf.size()), view.x(), view.y(), view.z());
            } else { // the camera moved: only the camera-relative header changes
                int fx = (int) Math.floor(view.x()), fy = (int) Math.floor(view.y()), fz = (int) Math.floor(view.z());
                for (Buf wb0 : new Buf[] {c.worldBuf, c.gbWorldBuf}) {
                    ByteBuffer hb = MemoryUtil.memByteBuffer(wb0.mapped(), 48).order(ByteOrder.LITTLE_ENDIAN);
                    hb.putInt(0, view.world().ox() - fx).putInt(4, view.world().oy() - fy).putInt(8, view.world().oz() - fz);
                    hb.putFloat(16, (float) (view.x() - fx)).putFloat(20, (float) (view.y() - fy)).putFloat(24, (float) (view.z() - fz));
                }
            }
            c.lastView = view;
        } else {
            int[] px = input.getRGB(0, 0, w, h, null, 0, w);
            for (int y = 0; y < h; y++) // bottom-up like the game's world target (GL origin)
                for (int x = 0; x < w; x++) {
                    int col = px[(h - 1 - y) * w + x];
                    MemoryUtil.memPutInt(c.upload.mapped() + ((long) y * w + x) * 4, (col & 0xFF00FF00) | (col >> 16 & 0xFF) | (col & 0xFF) << 16);
                }
        }
        try (MemoryStack s = MemoryStack.stackPush()) {
            vkResetCommandBuffer(cmd, 0);
            vkBeginCommandBuffer(cmd, VkCommandBufferBeginInfo.calloc(s).sType$Default().flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT));
            if (c.queryPool != 0) {
                vkCmdResetQueryPool(cmd, c.queryPool, 0, c.queries);
                vkCmdWriteTimestamp(cmd, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, c.queryPool, 0);
            }
            if (c.world) {
                int from = c.gbFresh ? VK_IMAGE_LAYOUT_UNDEFINED : VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
                barrier(s, c.scene.image(), from, VK_IMAGE_LAYOUT_GENERAL);
                barrier(s, c.dist.image(), from, VK_IMAGE_LAYOUT_GENERAL);
                c.gbFresh = false;
                vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, c.gbPipeline);
                vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, c.gbLayout, 0, s.longs(c.gbSet), null);
                vkCmdDispatch(cmd, (w + 7) / 8, (h + 7) / 8, 1);
                barrier(s, c.scene.image(), VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
                barrier(s, c.dist.image(), VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
                if (c.queryPool != 0) vkCmdWriteTimestamp(cmd, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, c.queryPool, 1);
            } else {
                barrier(s, c.input.image(), VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
                copyIn(s, c.upload, c.input);
                barrier(s, c.input.image(), VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            }
            if (c.historyFresh) {
                barrier(s, c.history.image(), VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
                VkClearColorValue zero = VkClearColorValue.calloc(s);
                VkImageSubresourceRange.Buffer range = VkImageSubresourceRange.calloc(1, s);
                range.get(0).aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
                vkCmdClearColorImage(cmd, c.history.image(), VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, zero, range);
                barrier(s, c.history.image(), VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
                c.historyFresh = false;
            }
            for (Prepared p : c.passes) {
                if (p.layout != VK_IMAGE_LAYOUT_UNDEFINED) continue;
                // first run: every buffer starts black (a history buffer is read before its pass writes it)
                barrier(s, p.output.image(), VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
                VkImageSubresourceRange.Buffer r0 = VkImageSubresourceRange.calloc(1, s);
                r0.get(0).aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
                vkCmdClearColorImage(cmd, p.output.image(), VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VkClearColorValue.calloc(s), r0);
                barrier(s, p.output.image(), VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
                p.layout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
            }
            for (int i = 0; i < c.passes.size(); i++) {
                Prepared p = c.passes.get(i);
                barrier(s, p.output.image(), p.layout, VK_IMAGE_LAYOUT_GENERAL);
                vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, p.pipeline);
                vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, p.pipelineLayout, 0, s.longs(p.set), null);
                if (p.push) {
                    ByteBuffer pb = s.calloc(128);
                    if (extras != null && extras.push() != null) pb.put(0, extras.push(), 0, Math.min(128, extras.push().length));
                    vkCmdPushConstants(cmd, p.pipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT, 0, pb);
                }
                vkCmdDispatch(cmd, (p.output.w() + 7) / 8, (p.output.h() + 7) / 8, 1);
                p.layout = p.fin ? VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL : VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
                barrier(s, p.output.image(), VK_IMAGE_LAYOUT_GENERAL, p.layout);
                if (c.queryPool != 0) vkCmdWriteTimestamp(cmd, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, c.queryPool, i + 2);
            }
            Img fin = c.passes.stream().filter(q -> q.fin).findFirst().orElse(c.passes.get(c.passes.size() - 1)).output;
            VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, s);
            region.get(0).imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
            region.get(0).imageExtent().set(w, h, 1);
            vkCmdCopyImageToBuffer(cmd, fin.image(), VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, c.readback.handle(), region);
            // the final image becomes the previous frame of the next run
            barrier(s, c.history.image(), VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
            VkImageCopy.Buffer ic = VkImageCopy.calloc(1, s);
            ic.get(0).srcSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
            ic.get(0).dstSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
            ic.get(0).extent().set(w, h, 1);
            vkCmdCopyImage(cmd, fin.image(), VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, c.history.image(), VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, ic);
            barrier(s, c.history.image(), VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            vkEndCommandBuffer(cmd);
            vkResetFences(device, fence);
            check(vkQueueSubmit(queue, VkSubmitInfo.calloc(s).sType$Default().pCommandBuffers(s.pointers(cmd)), fence), "submit");
            check(vkWaitForFences(device, fence, true, 5_000_000_000L), "wait");
            if (c.queryPool != 0) {
                LongBuffer ts = s.mallocLong(c.queries);
                if (vkGetQueryPoolResults(device, c.queryPool, 0, c.queries, ts, 8, VK_QUERY_RESULT_64_BIT | VK_QUERY_RESULT_WAIT_BIT) == VK_SUCCESS) {
                    double[] ms = new double[c.queries - 1];
                    long t0 = ts.get(0), prev = t0;
                    if (!c.world) ts.put(1, t0); // no G-buffer pass: its slot holds the start
                    for (int q = 1; q < c.queries; q++) {
                        long t = ts.get(q);
                        if (q == 1 && !c.world) t = t0;
                        ms[q - 1] = Math.max(0, t - prev) * timestampPeriod / 1.0e6;
                        prev = t;
                    }
                    lastTimings = ms;
                }
            }
        }
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        int[] row = new int[w];
        for (int y = 0; y < h; y++) {
            long base = c.readback.mapped() + (long) (h - 1 - y) * w * 8;
            for (int x = 0; x < w; x++) {
                long o = base + x * 8L;
                int r = c8(Float.float16ToFloat(MemoryUtil.memGetShort(o))), g = c8(Float.float16ToFloat(MemoryUtil.memGetShort(o + 2))),
                    b = c8(Float.float16ToFloat(MemoryUtil.memGetShort(o + 4)));
                row[x] = r << 16 | g << 8 | b;
            }
            out.setRGB(0, y, w, 1, row, 0, w);
        }
        return out;
    }

    private static int c8(float v) {
        if (Float.isNaN(v)) return 255;
        return (int) (Math.max(0, Math.min(1, v)) * 255 + 0.5f);
    }

    private void barrier(MemoryStack s, long image, int from, int to) {
        VkImageMemoryBarrier.Buffer b = VkImageMemoryBarrier.calloc(1, s);
        b.get(0).sType$Default().oldLayout(from).newLayout(to).srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
            .image(image).srcAccessMask(VK_ACCESS_MEMORY_WRITE_BIT).dstAccessMask(VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT);
        b.get(0).subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
        vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, 0, null, null, b);
    }

    private void copyIn(MemoryStack s, Buf from, Img to) {
        VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, s);
        region.get(0).imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
        region.get(0).imageExtent().set(to.w(), to.h(), 1);
        vkCmdCopyBufferToImage(cmd, from.handle(), to.image(), VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, region);
    }

    /** One-time upload of a small image (depth, LUT, dummies) before the first frame. */
    private void fill(Img img, int[] rgbaOrFloatBits, int bytesPerPixel) {
        Buf staging = buffer((long) img.w() * img.h() * bytesPerPixel, VK_BUFFER_USAGE_TRANSFER_SRC_BIT);
        for (int i = 0; i < rgbaOrFloatBits.length; i++) MemoryUtil.memPutInt(staging.mapped() + i * 4L, rgbaOrFloatBits[i]);
        try (MemoryStack s = MemoryStack.stackPush()) {
            vkResetCommandBuffer(cmd, 0);
            vkBeginCommandBuffer(cmd, VkCommandBufferBeginInfo.calloc(s).sType$Default().flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT));
            barrier(s, img.image(), VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
            copyIn(s, staging, img);
            barrier(s, img.image(), VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            vkEndCommandBuffer(cmd);
            vkResetFences(device, fence);
            check(vkQueueSubmit(queue, VkSubmitInfo.calloc(s).sType$Default().pCommandBuffers(s.pointers(cmd)), fence), "submit");
            check(vkWaitForFences(device, fence, true, 5_000_000_000L), "wait");
        }
        free(staging);
    }

    private static void copyCamera(ByteBuffer frameBlock, ByteBuffer into) {
        MemoryUtil.memCopy(MemoryUtil.memAddress(frameBlock), MemoryUtil.memAddress(into), 64);             // projection
        MemoryUtil.memCopy(MemoryUtil.memAddress(frameBlock) + 128, MemoryUtil.memAddress(into) + 64, 64);   // model-view
        MemoryUtil.memCopy(MemoryUtil.memAddress(frameBlock) + 256, MemoryUtil.memAddress(into) + 128, 16);  // camera position
    }

    private static final java.util.Map<String, byte[]> GBUFFER_SPIRV = new java.util.HashMap<>();

    /** The G-buffer shader with the graph's settings (sky model) and the preview look as constants. */
    static String gbufferSource(PreviewWorld.View view) {
        boolean blocks = view.blocks() != null, mesh = view.atlas().hasMesh();
        return GBUFFER_GLSL.replace("%CONSTANTS%", io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicSettings.constants(view.graphSettings(), false)
            + PreviewWorld.lookConstants(view.look()) + "const bool HAS_BLOCKS = " + blocks + ";\nconst bool HAS_MESH = " + mesh + ";\nconst bool HAS_BIOMES = " + view.atlas().hasBiomes() + ";\n" + cloudConstants(view.atlas().cloudRect())
            + rainConstants(view.atlas().rain(), PreviewWorld.weatherOf(view.world()))
            + skyConstants(view.atlas())
            + "const bool HAS_SNOW_TEX = " + (view.atlas().snow() != null) + ";\nconst vec4 SNOW_RECT = " + (view.atlas().snow() == null ? "vec4(0.0)"
            : "vec4(" + view.atlas().snow()[0] + ".0, " + view.atlas().snow()[1] + ".0, " + view.atlas().snow()[2] + ".0, " + view.atlas().snow()[3] + ".0)") + ";\n");
    }

    /** The snapshot with the G-buffer's flags in the spare material bits: 0x80 quads in this cell, 0x40 a block drawn by its model. */
    private static io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicWorld.Snapshot flagged(PreviewWorld.View view) {
        var s = view.world();
        int[] mesh = view.atlas().mesh();
        var table = view.blocks();
        int[] info = view.atlas().info();
        if (mesh == null || !view.atlas().hasMesh()) return s;
        short[] cells = s.cells().clone();
        for (int i = 0; i < cells.length; i++) {
            int f = 0;
            if (i < mesh.length && (mesh[i] & 0xFF) != 0) f |= 0x80;
            if (table != null && i < table.index().length) {
                int b = table.index()[i] & 0xFFFF;
                if (b != 0 && b * 4 + 3 < info.length && (info[b * 4 + 3] & 2) != 0) f |= 0x40;
            }
            cells[i] = (short) (cells[i] | f);
        }
        return new io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicWorld.Snapshot(s.sx(), s.sy(), s.sz(), s.ox(), s.oy(), s.oz(), cells,
            s.camX(), s.camY(), s.camZ(), s.yaw(), s.pitch(), s.timeOfDay(), s.far());
    }

    private static String rect(String name, int[] r) {
        return "const bool HAS_" + name + " = " + (r != null) + ";\nconst vec4 " + name + "_RECT = "
            + (r == null ? "vec4(0.0)" : "vec4(" + r[0] + ".0, " + r[1] + ".0, " + r[2] + ".0, " + r[3] + ".0)") + ";\n";
    }

    private static String rgb(int c) {
        return "vec3(" + ((c >> 16) & 255) / 255.0 + ", " + ((c >> 8) & 255) / 255.0 + ", " + (c & 255) / 255.0 + ")";
    }

    /** The game's sky: the camera biome's sky and fog colour, the sun and moon textures. */
    private static String skyConstants(PreviewBlocks.Atlas a) {
        return rect("SUN", a.sun()) + rect("MOON", a.moon()) + "const vec3 VANILLA_SKY = " + rgb(a.skyColor()) + ";\nconst vec3 VANILLA_FOG = "
            + rgb(a.fogColor()) + ";\n";
    }

    private static String rainConstants(int[] r, float[] w) {
        String rect = r == null ? "vec4(0.0)" : "vec4(" + r[0] + ".0, " + r[1] + ".0, " + r[2] + ".0, " + r[3] + ".0)";
        return "const bool HAS_RAIN_TEX = " + (r != null) + ";\nconst vec4 RAIN_RECT = " + rect + ";\nconst float WORLD_RAIN = " + w[0] + ";\n";
    }

    private static String cloudConstants(int[] r) {
        return r == null ? "const bool HAS_CLOUDS = false;\nconst vec4 CLOUD_RECT = vec4(0.0);\n"
            : "const bool HAS_CLOUDS = true;\nconst vec4 CLOUD_RECT = vec4(" + r[0] + ".0, " + r[1] + ".0, " + r[2] + ".0, " + r[3] + ".0);\n";
    }

    private static synchronized byte[] gbufferSpirv(String source) {
        byte[] cached = GBUFFER_SPIRV.get(source);
        if (cached != null) return cached;
        MosaicShaderCheck.Result r = MosaicShaderCheck.compile(new MosaicCodegen.Generated(source, List.of(), null));
        if (!r.ok()) throw new IllegalStateException("preview world shader: " + r.errors());
        if (GBUFFER_SPIRV.size() > 16) GBUFFER_SPIRV.clear();
        GBUFFER_SPIRV.put(source, r.spirv());
        return r.spirv();
    }

    /** The world's G-buffer pass: bindings 1 frame, 15 world volume, 6 block atlas, 3 scene colour out, 7 ray distance out. */
    private void prepareGbuffer(Chain c, PreviewWorld.View view) {
        byte[] spirv = gbufferSpirv(gbufferSource(view));
        int[] atlas = view.atlas().argb();
        c.atlas = image(view.atlas().w(), view.atlas().h(), VK_FORMAT_R8G8B8A8_UNORM, VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT);
        // which block every cell is (16 bits per cell) and the tiles of every block (top, side, bottom, flags)
        var table = view.blocks();
        short[] ids = table == null ? new short[2] : table.index();
        c.blockIds = buffer(Math.max(16, ((ids.length + 1) / 2) * 4L), VK_BUFFER_USAGE_STORAGE_BUFFER_BIT);
        ByteBuffer ib = MemoryUtil.memByteBuffer(c.blockIds.mapped(), (int) c.blockIds.size()).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < ids.length; i++) ib.putShort(i * 2, ids[i]);
        int[] info = view.atlas().info();
        c.blockInfo = buffer(Math.max(16, info.length * 4L), VK_BUFFER_USAGE_STORAGE_BUFFER_BIT);
        ByteBuffer nb = MemoryUtil.memByteBuffer(c.blockInfo.mapped(), (int) c.blockInfo.size()).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < info.length; i++) nb.putInt(i * 4, info[i]);
        // the quads of blocks drawn by their models and of entities, listed per cell
        int[] mesh = view.atlas().mesh();
        float[] quads = view.atlas().quads();
        c.meshBuf = buffer(Math.max(16, mesh == null ? 16 : mesh.length * 4L), VK_BUFFER_USAGE_STORAGE_BUFFER_BIT);
        if (mesh != null) {
            ByteBuffer mb = MemoryUtil.memByteBuffer(c.meshBuf.mapped(), (int) c.meshBuf.size()).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < mesh.length; i++) mb.putInt(i * 4, mesh[i]);
        }
        c.quadBuf = buffer(Math.max(16, quads == null ? 16 : quads.length * 4L), VK_BUFFER_USAGE_STORAGE_BUFFER_BIT);
        if (quads != null) {
            ByteBuffer qb = MemoryUtil.memByteBuffer(c.quadBuf.mapped(), (int) c.quadBuf.size()).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < quads.length; i++) qb.putFloat(i * 4, quads[i]);
        }
        int[] biomeCols = view.atlas().biome();
        c.biomeBuf = buffer(Math.max(16, biomeCols == null ? 16 : biomeCols.length * 4L), VK_BUFFER_USAGE_STORAGE_BUFFER_BIT);
        if (biomeCols != null) {
            ByteBuffer bb = MemoryUtil.memByteBuffer(c.biomeBuf.mapped(), (int) c.biomeBuf.size()).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < biomeCols.length; i++) bb.putInt(i * 4, biomeCols[i]);
        }
        int[] rgba = new int[atlas.length];
        for (int i = 0; i < atlas.length; i++) rgba[i] = (atlas[i] & 0xFF00FF00) | (atlas[i] >> 16 & 0xFF) | (atlas[i] & 0xFF) << 16;
        fill(c.atlas, rgba, 4);
        try (MemoryStack s = MemoryStack.stackPush()) {
            LongBuffer lp = s.mallocLong(1);
            ByteBuffer code = s.malloc(spirv.length);
            code.put(spirv).flip();
            check(vkCreateShaderModule(device, VkShaderModuleCreateInfo.calloc(s).sType$Default().pCode(code), null, lp), "world shader module");
            c.gbModule = lp.get(0);
            int[][] binds = {{1, VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER}, {15, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER}, {8, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER},
                {6, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER}, {3, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE}, {7, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE},
                {9, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER}, {10, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER}, {11, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER},
                {12, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER}, {13, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER}};
            VkDescriptorSetLayoutBinding.Buffer lb = VkDescriptorSetLayoutBinding.calloc(binds.length, s);
            for (int i = 0; i < binds.length; i++)
                lb.get(i).binding(binds[i][0]).descriptorType(binds[i][1]).descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            check(vkCreateDescriptorSetLayout(device, VkDescriptorSetLayoutCreateInfo.calloc(s).sType$Default().pBindings(lb), null, lp), "world set layout");
            c.gbSetLayout = lp.get(0);
            check(vkCreatePipelineLayout(device, VkPipelineLayoutCreateInfo.calloc(s).sType$Default().pSetLayouts(s.longs(c.gbSetLayout)), null, lp),
                "world pipeline layout");
            c.gbLayout = lp.get(0);
            VkComputePipelineCreateInfo.Buffer cpi = VkComputePipelineCreateInfo.calloc(1, s);
            cpi.get(0).sType$Default().layout(c.gbLayout).stage().sType$Default().stage(VK_SHADER_STAGE_COMPUTE_BIT).module(c.gbModule).pName(s.UTF8("main"));
            check(vkCreateComputePipelines(device, VK_NULL_HANDLE, cpi, null, lp), "world pipeline");
            c.gbPipeline = lp.get(0);
            VkDescriptorPoolSize.Buffer sizes = VkDescriptorPoolSize.calloc(4, s);
            sizes.get(0).type(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).descriptorCount(1);
            sizes.get(1).type(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(7);
            sizes.get(2).type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1);
            sizes.get(3).type(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(2);
            check(vkCreateDescriptorPool(device, VkDescriptorPoolCreateInfo.calloc(s).sType$Default().maxSets(1).pPoolSizes(sizes), null, lp), "world pool");
            c.gbPool = lp.get(0);
            check(vkAllocateDescriptorSets(device, VkDescriptorSetAllocateInfo.calloc(s).sType$Default().descriptorPool(c.gbPool)
                .pSetLayouts(s.longs(c.gbSetLayout)), lp), "world set");
            c.gbSet = lp.get(0);
            VkWriteDescriptorSet.Buffer wr = VkWriteDescriptorSet.calloc(11, s);
            VkDescriptorBufferInfo.Buffer bib = VkDescriptorBufferInfo.calloc(1, s);
            bib.get(0).buffer(c.biomeBuf.handle()).offset(0).range(c.biomeBuf.size());
            VkDescriptorBufferInfo.Buffer meb = VkDescriptorBufferInfo.calloc(1, s);
            meb.get(0).buffer(c.meshBuf.handle()).offset(0).range(c.meshBuf.size());
            VkDescriptorBufferInfo.Buffer qub = VkDescriptorBufferInfo.calloc(1, s);
            qub.get(0).buffer(c.quadBuf.handle()).offset(0).range(c.quadBuf.size());
            VkDescriptorBufferInfo.Buffer idb = VkDescriptorBufferInfo.calloc(1, s);
            idb.get(0).buffer(c.blockIds.handle()).offset(0).range(c.blockIds.size());
            VkDescriptorBufferInfo.Buffer inb = VkDescriptorBufferInfo.calloc(1, s);
            inb.get(0).buffer(c.blockInfo.handle()).offset(0).range(c.blockInfo.size());
            VkDescriptorBufferInfo.Buffer fb = VkDescriptorBufferInfo.calloc(1, s);
            fb.get(0).buffer(c.farBuf.handle()).offset(0).range(c.farBuf.size());
            VkDescriptorBufferInfo.Buffer ub = VkDescriptorBufferInfo.calloc(1, s);
            ub.get(0).buffer(c.ubo.handle()).offset(0).range(c.ubo.size());
            VkDescriptorBufferInfo.Buffer wb = VkDescriptorBufferInfo.calloc(1, s);
            wb.get(0).buffer(c.gbWorldBuf.handle()).offset(0).range(c.gbWorldBuf.size());
            VkDescriptorImageInfo.Buffer at = VkDescriptorImageInfo.calloc(1, s);
            at.get(0).sampler(c.nearest).imageView(c.atlas.view()).imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            VkDescriptorImageInfo.Buffer so = VkDescriptorImageInfo.calloc(1, s);
            so.get(0).imageView(c.scene.view()).imageLayout(VK_IMAGE_LAYOUT_GENERAL);
            VkDescriptorImageInfo.Buffer dout = VkDescriptorImageInfo.calloc(1, s);
            dout.get(0).imageView(c.dist.view()).imageLayout(VK_IMAGE_LAYOUT_GENERAL);
            wr.get(0).sType$Default().dstSet(c.gbSet).dstBinding(1).descriptorCount(1).descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).pBufferInfo(ub);
            wr.get(1).sType$Default().dstSet(c.gbSet).dstBinding(15).descriptorCount(1).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).pBufferInfo(wb);
            wr.get(2).sType$Default().dstSet(c.gbSet).dstBinding(6).descriptorCount(1).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).pImageInfo(at);
            wr.get(3).sType$Default().dstSet(c.gbSet).dstBinding(3).descriptorCount(1).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).pImageInfo(so);
            wr.get(4).sType$Default().dstSet(c.gbSet).dstBinding(7).descriptorCount(1).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).pImageInfo(dout);
            wr.get(5).sType$Default().dstSet(c.gbSet).dstBinding(8).descriptorCount(1).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).pBufferInfo(fb);
            wr.get(6).sType$Default().dstSet(c.gbSet).dstBinding(9).descriptorCount(1).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).pBufferInfo(idb);
            wr.get(7).sType$Default().dstSet(c.gbSet).dstBinding(10).descriptorCount(1).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).pBufferInfo(inb);
            wr.get(8).sType$Default().dstSet(c.gbSet).dstBinding(11).descriptorCount(1).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).pBufferInfo(meb);
            wr.get(9).sType$Default().dstSet(c.gbSet).dstBinding(12).descriptorCount(1).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).pBufferInfo(qub);
            wr.get(10).sType$Default().dstSet(c.gbSet).dstBinding(13).descriptorCount(1).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).pBufferInfo(bib);
            vkUpdateDescriptorSets(device, wr, null);
        }
    }

    private Chain build(List<PassRun> runs, int key, int w, int h, Extras extras, PreviewWorld.View view) {
        Chain c = new Chain();
        c.key = key;
        c.w = w;
        c.h = h;
        try (MemoryStack s = MemoryStack.stackPush()) {
            LongBuffer lp = s.mallocLong(1);
            c.ubo = buffer(320, VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT);
            c.upload = buffer((long) w * h * 4, VK_BUFFER_USAGE_TRANSFER_SRC_BIT);
            c.readback = buffer((long) w * h * 8, VK_BUFFER_USAGE_TRANSFER_DST_BIT);
            c.input = image(w, h, VK_FORMAT_R8G8B8A8_UNORM, VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT);
            c.history = image(w, h, VK_FORMAT_R16G16B16A16_SFLOAT, VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT);
            c.depth = image(1, 1, VK_FORMAT_R32_SFLOAT, VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT);
            fill(c.depth, new int[] {Float.floatToIntBits(1.0e6f)}, 4); // "the ray hit nothing": sky everywhere
            BufferedImage lut = extras == null ? null : extras.lut();
            c.one = lut == null ? image(1, 1, VK_FORMAT_R8G8B8A8_UNORM, VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT)
                : image(lut.getWidth(), lut.getHeight(), VK_FORMAT_R8G8B8A8_UNORM, VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT);
            if (lut == null) fill(c.one, new int[] {0xFFFFFFFF}, 4);
            else {
                int[] px = lut.getRGB(0, 0, lut.getWidth(), lut.getHeight(), null, 0, lut.getWidth());
                for (int i = 0; i < px.length; i++) px[i] = (px[i] & 0xFF00FF00) | (px[i] >> 16 & 0xFF) | (px[i] & 0xFF) << 16;
                fill(c.one, px, 4);
            }
            check(vkCreateSampler(device, VkSamplerCreateInfo.calloc(s).sType$Default().magFilter(VK_FILTER_LINEAR).minFilter(VK_FILTER_LINEAR)
                .addressModeU(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE).addressModeV(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                .addressModeW(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE).maxLod(0), null, lp), "sampler");
            c.sampler = lp.get(0);
            // the scene colour and the ray distance are read unfiltered, as the game binds them
            check(vkCreateSampler(device, VkSamplerCreateInfo.calloc(s).sType$Default().magFilter(VK_FILTER_NEAREST).minFilter(VK_FILTER_NEAREST)
                .addressModeU(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE).addressModeV(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                .addressModeW(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE).maxLod(0), null, lp), "nearest sampler");
            c.nearest = lp.get(0);
            c.prev = buffer(144, VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT);
            if (view != null) {
                c.world = true;
                c.worldBuf = buffer(view.world().bytes(), VK_BUFFER_USAGE_STORAGE_BUFFER_BIT);
                c.gbWorldBuf = buffer(view.world().bytes(), VK_BUFFER_USAGE_STORAGE_BUFFER_BIT);
                c.gbWorld = flagged(view);
                c.farBuf = buffer(view.world().far() == null ? 64 : view.world().far().bytes(), VK_BUFFER_USAGE_STORAGE_BUFFER_BIT);
                c.heightBuf = buffer(io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicHeight.BYTES, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT);
                c.heightField = new io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicHeight.Field();
                c.scene = image(w, h, VK_FORMAT_R16G16B16A16_SFLOAT, VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT);
                c.dist = image(w, h, VK_FORMAT_R32_SFLOAT, VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT);
            }
        }
        if (view != null) prepareGbuffer(c, view);
        // outputs first, so every pass can bind the buffers of the passes before it
        java.util.Map<Integer, Img> buffers = new java.util.HashMap<>();
        for (PassRun r : runs) {
            Prepared p = new Prepared();
            boolean last = r.binding() < 0;
            int pw = last ? w : Math.max(1, w / r.scale()), ph = last ? h : Math.max(1, h / r.scale());
            p.output = image(pw, ph, VK_FORMAT_R16G16B16A16_SFLOAT, VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT
                | VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT);
            p.fin = last;
            if (!last) buffers.put(r.binding(), p.output);
            c.passes.add(p);
        }
        for (int i = 0; i < runs.size(); i++) preparePass(c, c.passes.get(i), runs.get(i).spirv(), buffers, extras);
        if (timestampPeriod > 0) try (MemoryStack s = MemoryStack.stackPush()) {
            LongBuffer lp = s.mallocLong(1);
            c.queries = runs.size() + 2;
            if (vkCreateQueryPool(device, VkQueryPoolCreateInfo.calloc(s).sType$Default().queryType(VK_QUERY_TYPE_TIMESTAMP).queryCount(c.queries),
                    null, lp) == VK_SUCCESS) c.queryPool = lp.get(0);
        }
        return c;
    }

    private void preparePass(Chain c, Prepared p, byte[] spirv, java.util.Map<Integer, Img> buffers, Extras extras) {
        ByteBuffer code = MemoryUtil.memAlloc(spirv.length).order(ByteOrder.LITTLE_ENDIAN);
        code.put(spirv).flip();
        SpirvReflection.Module module = SpirvReflection.reflect(code.duplicate().order(ByteOrder.LITTLE_ENDIAN));
        for (SpirvReflection.Binding b : module.bindings())
            if (b.kind() == SpirvReflection.DescriptorKind.ACCELERATION_STRUCTURE) {
                MemoryUtil.memFree(code);
                throw new UnsupportedOperationException("this graph traces rays: its preview needs the game's scene (see Result)");
            }
        p.push = module.pushConstants();
        try (MemoryStack s = MemoryStack.stackPush()) {
            LongBuffer lp = s.mallocLong(1);
            check(vkCreateShaderModule(device, VkShaderModuleCreateInfo.calloc(s).sType$Default().pCode(code), null, lp), "shader module");
            p.module = lp.get(0);
            MemoryUtil.memFree(code);
            List<SpirvReflection.Binding> binds = module.bindings().stream().filter(b -> b.set() == 0).toList();
            VkDescriptorSetLayoutBinding.Buffer lb = VkDescriptorSetLayoutBinding.calloc(binds.size(), s);
            int ub = 0, sb = 0, cis = 0, si = 0;
            for (int i = 0; i < binds.size(); i++) {
                SpirvReflection.Binding b = binds.get(i);
                int type = vkType(b.kind());
                lb.get(i).binding(b.binding()).descriptorType(type).descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
                switch (type) {
                    case VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER -> ub++;
                    case VK_DESCRIPTOR_TYPE_STORAGE_BUFFER -> sb++;
                    case VK_DESCRIPTOR_TYPE_STORAGE_IMAGE -> si++;
                    default -> cis++;
                }
            }
            check(vkCreateDescriptorSetLayout(device, VkDescriptorSetLayoutCreateInfo.calloc(s).sType$Default().pBindings(lb), null, lp), "set layout");
            p.setLayout = lp.get(0);
            VkPipelineLayoutCreateInfo plci = VkPipelineLayoutCreateInfo.calloc(s).sType$Default().pSetLayouts(s.longs(p.setLayout));
            if (p.push) {
                VkPushConstantRange.Buffer pr = VkPushConstantRange.calloc(1, s);
                pr.get(0).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT).offset(0).size(128);
                plci.pPushConstantRanges(pr);
            }
            check(vkCreatePipelineLayout(device, plci, null, lp), "pipeline layout");
            p.pipelineLayout = lp.get(0);
            VkComputePipelineCreateInfo.Buffer cpi = VkComputePipelineCreateInfo.calloc(1, s);
            cpi.get(0).sType$Default().layout(p.pipelineLayout).stage().sType$Default().stage(VK_SHADER_STAGE_COMPUTE_BIT).module(p.module).pName(s.UTF8("main"));
            check(vkCreateComputePipelines(device, VK_NULL_HANDLE, cpi, null, lp), "pipeline (the shader may need a feature this GPU lacks)");
            p.pipeline = lp.get(0);
            VkDescriptorPoolSize.Buffer sizes = VkDescriptorPoolSize.calloc(4, s);
            sizes.get(0).type(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).descriptorCount(Math.max(1, ub));
            sizes.get(1).type(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(Math.max(1, sb));
            sizes.get(2).type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(Math.max(1, cis));
            sizes.get(3).type(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(Math.max(1, si));
            check(vkCreateDescriptorPool(device, VkDescriptorPoolCreateInfo.calloc(s).sType$Default().maxSets(1).pPoolSizes(sizes), null, lp), "pool");
            p.pool = lp.get(0);
            check(vkAllocateDescriptorSets(device, VkDescriptorSetAllocateInfo.calloc(s).sType$Default().descriptorPool(p.pool)
                .pSetLayouts(s.longs(p.setLayout)), lp), "descriptor set");
            p.set = lp.get(0);
            for (SpirvReflection.Binding b : binds) {
                VkWriteDescriptorSet.Buffer wr = VkWriteDescriptorSet.calloc(1, s);
                wr.get(0).sType$Default().dstSet(p.set).dstBinding(b.binding()).descriptorCount(1).descriptorType(vkType(b.kind()));
                switch (vkType(b.kind())) {
                    case VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER -> {
                        Buf buf = b.binding() == 1 ? c.ubo : b.binding() == 17 ? c.prev : b.binding() == 15 && c.world ? c.worldBuf
                            : b.binding() == io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicHeight.BINDING && c.world ? c.heightBuf
                            : extraBuffer(c, p, b, extras);
                        VkDescriptorBufferInfo.Buffer bi = VkDescriptorBufferInfo.calloc(1, s);
                        bi.get(0).buffer(buf.handle()).offset(0).range(buf.size());
                        wr.get(0).pBufferInfo(bi);
                    }
                    case VK_DESCRIPTOR_TYPE_STORAGE_IMAGE -> {
                        VkDescriptorImageInfo.Buffer ii = VkDescriptorImageInfo.calloc(1, s);
                        ii.get(0).imageView(p.output.view()).imageLayout(VK_IMAGE_LAYOUT_GENERAL);
                        wr.get(0).pImageInfo(ii);
                    }
                    default -> {
                        Img img = b.binding() == 2 ? (c.world ? c.scene : c.input) : b.binding() == 4 ? (c.world ? c.dist : c.depth)
                            : b.binding() == 19 ? c.history : buffers.getOrDefault(b.binding(), c.one);
                        VkDescriptorImageInfo.Buffer ii = VkDescriptorImageInfo.calloc(1, s);
                        ii.get(0).sampler(b.binding() == 2 || b.binding() == 4 ? c.nearest : c.sampler).imageView(img.view())
                            .imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
                        wr.get(0).pImageInfo(ii);
                    }
                }
                vkUpdateDescriptorSets(device, wr, null);
            }
        }
    }

    /** User buffer (binding 5: buffer5.bin) and the world volume (15: empty world, sky light everywhere), else zeros. */
    private Buf extraBuffer(Chain c, Prepared p, SpirvReflection.Binding b, Extras extras) {
        Buf buf;
        if (b.binding() == 15) {
            // a game frame has no world: an empty volume of the default size, sky light everywhere
            int sx = io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicWorld.DEFAULT_SX, sy = io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicWorld.DEFAULT_SY,
                sz = io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicWorld.DEFAULT_SZ, cells = sx * sy * sz;
            buf = buffer(48 + cells * 2L, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT);
            MemoryUtil.memPutInt(buf.mapped() + 32, sx);
            MemoryUtil.memPutInt(buf.mapped() + 36, sy);
            MemoryUtil.memPutInt(buf.mapped() + 40, sz);
            for (int i = 0; i < cells; i++) MemoryUtil.memPutShort(buf.mapped() + 48 + i * 2L, (short) (15 << 12)); // air, full sky light
        } else if (b.binding() == io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicHeight.BINDING) { // height field: none in the preview (no far shadows)
            buf = buffer(io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicHeight.BYTES, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT);
            MemoryUtil.memSet(buf.mapped(), 0, io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicHeight.HEADER_BYTES);
        } else if (b.binding() == 17) { // previous camera: the preview camera stands still
            buf = buffer(144, VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT);
            long tmp = MemoryUtil.nmemCalloc(1, 320);
            try {
                writeFrame(tmp, Math.max(1, c.w), Math.max(1, c.h), 0f, 0);
                MemoryUtil.memCopy(tmp, buf.mapped(), 64);              // projection
                MemoryUtil.memCopy(tmp + 128, buf.mapped() + 64, 64);   // model-view
                MemoryUtil.memCopy(tmp + 256, buf.mapped() + 128, 16);  // camera position
            } finally {
                MemoryUtil.nmemFree(tmp);
            }
        } else if (b.binding() == 16) { // weather and sky: a clear day
            buf = buffer(64, VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT);
            float[] v = {0f, 0f, 0.8f, 0f, 0.47f, 0.65f, 1f, 1f, 0.75f, 0.85f, 1f, 1f, 0f, 0f, 0f, 0f};
            for (int i = 0; i < v.length; i++) MemoryUtil.memPutFloat(buf.mapped() + i * 4L, v[i]);
        } else {
            byte[] data = b.binding() == 5 && extras != null ? extras.buffer5() : null;
            buf = buffer(Math.max(65536, data == null ? 0 : data.length), b.kind() == SpirvReflection.DescriptorKind.UNIFORM_BUFFER
                ? VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT : VK_BUFFER_USAGE_STORAGE_BUFFER_BIT);
            if (data != null) MemoryUtil.memByteBuffer(buf.mapped(), data.length).put(data);
        }
        p.buffers.add(buf);
        return buf;
    }

    private static int vkType(SpirvReflection.DescriptorKind k) {
        return switch (k) {
            case UNIFORM_BUFFER -> VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER;
            case STORAGE_BUFFER -> VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
            case STORAGE_IMAGE -> VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
            default -> VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
        };
    }

    private static void writeFrame(long at, int w, int h, float time, int frame) { writeFrame(at, w, h, time, frame, null); }

    /**
     * The 320-byte frame block. Without a view: a camera at the origin looking along -Z, 70 degrees, noon, no depth.
     * With one: the game's camera (rotation-only model-view, yaw + 180 about Y after pitch about X, positions camera
     * relative), its world position, the time of day and depth available.
     */
    private static void writeFrame(long at, int w, int h, float time, int frame, PreviewWorld.View v) {
        org.joml.Matrix4f proj = new org.joml.Matrix4f().perspective((float) Math.toRadians(70), w / (float) h, 0.05f, 1024f);
        org.joml.Matrix4f view = v == null ? new org.joml.Matrix4f()
            : new org.joml.Matrix4f().rotateX((float) Math.toRadians(v.pitch())).rotateY((float) Math.toRadians(v.yaw() + 180f));
        ByteBuffer b = MemoryUtil.memByteBuffer(at, 320).order(ByteOrder.LITTLE_ENDIAN);
        proj.get(0, b);
        new org.joml.Matrix4f(proj).invert().get(64, b);
        view.get(128, b);
        new org.joml.Matrix4f(view).invert().get(192, b);
        if (v == null) b.putFloat(256, 0f).putFloat(260, 64f).putFloat(264, 0f).putFloat(268, 0f);
        else b.putFloat(256, (float) v.x()).putFloat(260, (float) v.y()).putFloat(264, (float) v.z()).putFloat(268, 0f);
        b.putFloat(272, w).putFloat(276, h).putFloat(280, 1f / w).putFloat(284, 1f / h);
        b.putFloat(288, time % 3600f).putFloat(292, 1f / 30f).putFloat(296, frame).putFloat(300, v == null ? 0f : v.timeOfDay());
        b.putFloat(304, 0.05f).putFloat(308, 1024f).putFloat(312, v == null ? 0f : 1f).putFloat(316, 0f);
    }

    /** The preview world's G-buffer: voxel rays through the world volume, block textures, the game's light map. */
    static final String GBUFFER_GLSL = """
        #version 460
        layout(local_size_x = 8, local_size_y = 8, local_size_z = 1) in;
        layout(std140, set = 0, binding = 1) uniform BedrayFrame {
            mat4 projection; mat4 projectionInverse; mat4 modelView; mat4 modelViewInverse;
            vec4 cameraPosition; vec4 viewSize; vec4 timing; vec4 depthInfo;
        } frame;
        layout(std430, set = 0, binding = 15) readonly buffer BedrayWorld { ivec4 originRel; vec4 camFrac; ivec4 size; uint cells[]; } world;
        layout(set = 0, binding = 6) uniform sampler2D atlas;
        layout(set = 0, binding = 3, rgba16f) uniform writeonly image2D sceneOut;
        layout(set = 0, binding = 7, r32f) uniform writeonly image2D distOut;
        layout(std430, set = 0, binding = 8) readonly buffer FarField { ivec4 head; ivec4 camY; uint cells[]; } farField;
        layout(std430, set = 0, binding = 9) readonly buffer BlockIds { uint ids[]; } blockIds;
        layout(std430, set = 0, binding = 10) readonly buffer BlockInfo { uvec4 info[]; } blockInfo;
        layout(std430, set = 0, binding = 11) readonly buffer Mesh { uint v[]; } mesh;
        layout(std430, set = 0, binding = 12) readonly buffer Quads { vec4 q[]; } quads;
        layout(std430, set = 0, binding = 13) readonly buffer Biomes { uvec4 c[]; } biomes;
        %CONSTANTS%
        bool inside(ivec3 c) {
            ivec3 p = c - world.originRel.xyz;
            return all(greaterThanEqual(p, ivec3(0))) && all(lessThan(p, world.size.xyz));
        }
        uint cellAt(ivec3 c) {
            ivec3 p = c - world.originRel.xyz;
            if (any(lessThan(p, ivec3(0))) || any(greaterThanEqual(p, world.size.xyz))) return 0xF000u;
            int i = (p.y * world.size.z + p.z) * world.size.x + p.x;
            return (world.cells[i >> 1] >> uint((i & 1) * 16)) & 0xFFFFu;
        }
        vec3 sunDir() { float a = frame.timing.w * 6.2831853; return normalize(vec3(-sin(a), cos(a), ms_sunTilt)); }
        float starHash(vec3 p) { return fract(sin(dot(p, vec3(12.9898, 78.233, 37.719))) * 43758.5453); }
        // a celestial square (the game's sun / moon quads): its texture where the direction falls inside it
        vec4 celestial(vec3 dir, vec3 c, float halfSize, vec4 rect) {
            float k = dot(dir, c);
            if (k <= 0.0) return vec4(0.0);
            vec3 right = normalize(cross(c, abs(c.y) > 0.99 ? vec3(1.0, 0.0, 0.0) : vec3(0.0, 0.0, 1.0)));
            vec3 up = cross(right, c);
            vec2 uv = vec2(dot(dir, right), dot(dir, up)) / k / halfSize * 0.5 + 0.5;
            if (any(lessThan(uv, vec2(0.0))) || any(greaterThan(uv, vec2(1.0)))) return vec4(0.0);
            return textureLod(atlas, (rect.xy + uv * rect.zw) / vec2(textureSize(atlas, 0)), 0.0);
        }
        // the game's sky: the biome's sky colour overhead, its fog colour at the horizon, a sunrise / sunset glow,
        // the sun and moon squares and stars, all dimmed by the time of day
        vec3 vanillaSky(vec3 dir) {
            vec3 sun = sunDir();
            float day = clamp(sun.y * 2.0 + 0.5, 0.0, 1.0);
            vec3 top = VANILLA_SKY * day, fog = VANILLA_FOG * (day * 0.94 + 0.06);
            vec3 c = mix(fog, top, smoothstep(-0.05, 0.45, dir.y));
            // sunrise / sunset: orange towards the sun while it is near the horizon
            float low = clamp(1.0 - abs(sun.y) / 0.4, 0.0, 1.0);
            vec2 sh = normalize(sun.xz + vec2(1e-5)), dh = normalize(dir.xz + vec2(1e-5));
            float toward = pow(max(dot(sh, dh), 0.0), 3.0) * clamp(1.0 - dir.y * 2.5, 0.0, 1.0);
            c = mix(c, vec3(1.0, 0.52, 0.24) * max(day, 0.3), low * toward * pw_sunsetGlow);
            if (day < 0.6) { // stars
                vec3 cell = floor(dir * 220.0);
                float st = starHash(cell);
                if (st > 0.9975 && dir.y > 0.0) c += vec3((st - 0.9975) * 400.0 * (0.6 - day) * pw_stars);
            }
            if (HAS_SUN) { vec4 s = celestial(dir, sun, 0.15, SUN_RECT); c += s.rgb * s.a * 1.2; }
            if (HAS_MOON) { vec4 m = celestial(dir, -sun, 0.10, MOON_RECT); c += m.rgb * m.a * (1.0 - day * 0.8); }
            return c;
        }
        // the graph's own sky model (graph settings) plus a moon
        vec3 sky(vec3 dir) {
            if (pw_vanillaSky > 0.5) return vanillaSky(dir);
            vec3 sun = sunDir();
            float day = clamp(sun.y * ms_skyDayRise + ms_skyDayOffset, ms_skyNight, 1.0);
            vec3 c = mix(ms_skyHorizon, ms_skyZenith, clamp(dir.y, 0.0, 1.0)) * day;
            c += ms_sunDiscColor * pow(max(dot(dir, sun), 0.0), ms_sunDiscSharpness) * ms_sunDiscBrightness * day;
            c += vec3(0.8, 0.85, 1.0) * pow(max(dot(dir, -sun), 0.0), pw_moonSharpness) * pw_moonBrightness * (1.0 - day);
            return c;
        }
        vec3 viewDir(vec2 uv) {
            vec4 v = frame.projectionInverse * vec4(uv * 2.0 - 1.0, 1.0, 1.0);
            return normalize((frame.modelViewInverse * vec4(normalize(v.xyz / v.w), 0.0)).xyz);
        }
        int tileOf(uint m, vec3 n) {
            if (m == 4u) return n.y > 0.5 ? 15 : (n.y < -0.5 ? 16 : 4);
            if (m == 7u && abs(n.y) > 0.5) return 17;
            return int(m);
        }
        // the block's own textures (top, side, bottom) when the world names its blocks, else the material class tile
        int tileFor(ivec3 c, uint m, vec3 n) {
            if (HAS_BLOCKS) {
                ivec3 p = c - world.originRel.xyz;
                int i = (p.y * world.size.z + p.z) * world.size.x + p.x;
                uint b = (blockIds.ids[i >> 1] >> uint((i & 1) * 16)) & 0xFFFFu;
                if (b != 0u) {
                    uvec4 inf = blockInfo.info[b];
                    if ((inf.w & 1u) != 0u) {
                        uint v = n.y > 0.5 ? inf.x : (n.y < -0.5 ? inf.z : inf.y);
                        int tile = int(v & 0xFFFFFu);
                        uint frames = (v >> 20) & 255u, ft = max((v >> 28) & 15u, 1u);
                        if (frames > 1u && pw_animate > 0.5) tile += int(uint(floor(frame.timing.x * 20.0 / float(ft))) % frames);
                        return tile;
                    }
                }
            }
            return tileOf(m, n);
        }
        vec4 texel(int tile, vec3 q, vec3 n) {
            vec2 f = abs(n.x) > 0.5 ? vec2(q.z * -n.x, q.y) : abs(n.y) > 0.5 ? vec2(q.x, q.z) : vec2(q.x * n.z, q.y);
            f = fract(f);
            ivec2 asz = textureSize(atlas, 0);
            int cols = asz.x / 16, rows = asz.y / 16;
            vec2 base = vec2(float(tile % cols), float(tile / cols));
            return textureLod(atlas, (base + clamp(f, vec2(0.002), vec2(0.998))) / vec2(float(cols), float(rows)), 0.0);
        }
        // the game's light map: sky light by the time of day, coloured block light, per-face shading (all from the look)
        float curve(float l) { return l / ((1.0 + pw_lightCurve) - pw_lightCurve * l); }
        float g_day = 1.0; // daylight of this frame, set once at the start of main()
        vec3 lightOf(uint front) {
            float s = float((front >> 12) & 15u) / 15.0, b = float((front >> 8) & 15u) / 15.0;
            return min(max(vec3(curve(s) * g_day), pw_blockColor * curve(b) * pw_blockBoost) + pw_ambient, vec3(1.0));
        }
        float faceShade(vec3 n) {
            float ax = abs(n.x), ay = abs(n.y), az = abs(n.z);
            return ay >= ax && ay >= az ? (n.y > 0.0 ? pw_faceTop : pw_faceBottom) : (ax >= az ? pw_faceX : pw_faceZ);
        }
        vec3 light(uint front, vec3 n) { return lightOf(front) * faceShade(n); }
        bool solidCell(ivec3 c) {
            uint m = cellAt(c) & 0x3Fu;
            return !(m == 0u || m == 1u || m == 2u || m == 5u || m == 6u || m == 10u || m == 11u || m == 13u);
        }
        // smooth lighting: the light of the four cells around each corner of the face, blended across it, with ambient occlusion
        bool solidOf(uint c) {
            uint m = c & 0x3Fu;
            return !(m == 0u || m == 1u || m == 2u || m == 5u || m == 6u || m == 10u || m == 11u || m == 13u || (c & 0x40u) != 0u);
        }
        vec3 smoothLight(ivec3 front, vec3 n, vec3 q) {
            if (pw_smoothLighting < 0.5) return light(cellAt(front), n);
            ivec3 tu = abs(n.x) > 0.5 ? ivec3(0, 0, 1) : ivec3(1, 0, 0);
            ivec3 tv = abs(n.y) > 0.5 ? ivec3(0, 0, 1) : ivec3(0, 1, 0);
            vec3 fq = fract(q);
            vec2 f = vec2(dot(fq, vec3(tu)), dot(fq, vec3(tv)));
            // the 3 x 3 cells in front of the face, read once
            uint nb[9];
            for (int j = 0; j < 3; j++) for (int i = 0; i < 3; i++) nb[j * 3 + i] = cellAt(front + tu * (i - 1) + tv * (j - 1));
            vec3 l0 = lightOf(nb[4]);
            vec3 corner[4];
            for (int k = 0; k < 4; k++) {
                int su = (k & 1) == 0 ? 0 : 2, sv = (k & 2) == 0 ? 0 : 2;
                uint ca = nb[3 + su], cb = nb[sv * 3 + 1], cd = nb[sv * 3 + su];
                bool sa = solidOf(ca), sb = solidOf(cb), sd = sa && sb ? true : solidOf(cd);
                vec3 sum = l0;
                float cnt = 1.0;
                if (!sa) { sum += lightOf(ca); cnt += 1.0; }
                if (!sb) { sum += lightOf(cb); cnt += 1.0; }
                if (!sd) { sum += lightOf(cd); cnt += 1.0; }
                float occ = (sa ? 1.0 : 0.0) + (sb ? 1.0 : 0.0) + (sd ? 1.0 : 0.0);
                corner[k] = sum / cnt * (1.0 - occ * pw_aoStrength / 3.0);
            }
            vec3 l = mix(mix(corner[0], corner[1], f.x), mix(corner[2], corner[3], f.x), f.y);
            return l * faceShade(n);
        }
        // the biome colour of a column: kind 1 grass, 2 foliage, 3 water
        vec3 biomeTint(ivec3 cell, int kind) {
            if (!HAS_BIOMES || kind == 0) return vec3(1.0);
            ivec3 p = clamp(cell - world.originRel.xyz, ivec3(0), world.size.xyz - 1);
            uvec4 b = biomes.c[p.z * world.size.x + p.x];
            uint rgb = kind == 1 ? b.x : kind == 2 ? b.y : b.z;
            return vec3(float((rgb >> 16) & 255u), float((rgb >> 8) & 255u), float(rgb & 255u)) / 255.0;
        }
        // the quads of models (stairs, torches, flowers...) and entities listed in a cell: the nearest opaque texel between tIn and tOut
        bool meshHit(ivec3 cell, vec3 o, vec3 d, float tIn, float tOut, out float tHit, out vec3 nHit, out vec4 col) {
            tHit = 1e30;
            if (!HAS_MESH || pw_models < 0.5) return false;
            ivec3 p = cell - world.originRel.xyz;
            int ci = (p.y * world.size.z + p.z) * world.size.x + p.x;
            uint mc = mesh.v[ci];
            if ((mc & 255u) == 0u) return false;
            int cells = world.size.x * world.size.y * world.size.z;
            vec3 org = vec3(world.originRel.xyz);
            vec2 asz = vec2(textureSize(atlas, 0));
            bool hit = false;
            for (uint k = 0u; k < (mc & 255u); k++) {
                int qi = int(mesh.v[cells + int((mc >> 8) & 0x3FFFFFu) + int(k)]) * 5;
                vec4 a = quads.q[qi], b = quads.q[qi + 1], c2 = quads.q[qi + 2], uvq = quads.q[qi + 3], e = quads.q[qi + 4];
                vec3 p0 = a.xyz + org, e1 = b.xyz, e2 = c2.xyz;
                vec3 nq = cross(e1, e2);
                float den = dot(d, nq);
                if (abs(den) < 1e-9) continue;
                float t = dot(p0 - o, nq) / den;
                if (t < tIn - 1e-4 || t > tOut + 1e-4 || t >= tHit || t <= 0.0) continue;
                vec3 h = o + d * t - p0;
                float d11 = dot(e1, e1), d12 = dot(e1, e2), d22 = dot(e2, e2), h1 = dot(h, e1), h2 = dot(h, e2), det = d11 * d22 - d12 * d12;
                if (abs(det) < 1e-12) continue;
                float s = (d22 * h1 - d12 * h2) / det, w = (d11 * h2 - d12 * h1) / det;
                if (s < 0.0 || s > 1.0 || w < 0.0 || w > 1.0) continue;
                vec2 uv = clamp(uvq.xy + uvq.zw * s + e.xy * w, vec2(0.0005), vec2(0.9995));
                vec2 px = vec2(a.w, b.w) + uv * vec2(c2.w, e.z);
                vec4 tx = textureLod(atlas, px / asz, 0.0);
                if (tx.a < pw_cutout) continue;
                tHit = t;
                nHit = normalize(nq) * (den > 0.0 ? -1.0 : 1.0);
                int kind = e.w == 2.0 ? 1 : e.w == 4.0 ? 2 : 0;
                col = vec4(tx.rgb * biomeTint(cell, kind), tx.a);
                hit = true;
            }
            return hit;
        }
        // the colour a cube face takes from its biome (block flags 4 grass, 8 foliage, 16 water, 32 top face only)
        vec3 cubeTint(ivec3 c, vec3 n) {
            if (!HAS_BIOMES || !HAS_BLOCKS) return vec3(1.0);
            ivec3 p = c - world.originRel.xyz;
            int i = (p.y * world.size.z + p.z) * world.size.x + p.x;
            uint b = (blockIds.ids[i >> 1] >> uint((i & 1) * 16)) & 0xFFFFu;
            if (b == 0u) return vec3(1.0);
            uint f = blockInfo.info[b].w;
            if ((f & 32u) != 0u && n.y < 0.5) return vec3(1.0);
            return biomeTint(c, (f & 4u) != 0u ? 1 : (f & 8u) != 0u ? 2 : (f & 16u) != 0u ? 3 : 0);
        }
        bool modelCell(ivec3 c) {
            if (!HAS_BLOCKS || !HAS_MESH || pw_models < 0.5) return false;
            ivec3 p = c - world.originRel.xyz;
            int i = (p.y * world.size.z + p.z) * world.size.x + p.x;
            uint b = (blockIds.ids[i >> 1] >> uint((i & 1) * 16)) & 0xFFFFu;
            return b != 0u && (blockInfo.info[b].w & 2u) != 0u;
        }
        void main() {
            ivec2 px = ivec2(gl_GlobalInvocationID.xy);
            ivec2 size = imageSize(sceneOut);
            if (px.x >= size.x || px.y >= size.y) return;
            g_day = clamp(sunDir().y * pw_dayRise + pw_dayOffset, pw_nightLevel, 1.0);
            vec2 uv = (vec2(px) + 0.5) / vec2(size);
            vec3 d = viewDir(uv);
            vec3 o = world.camFrac.xyz;
            ivec3 cell = ivec3(floor(o));
            ivec3 stp = ivec3(sign(d));
            vec3 delta = vec3(abs(d.x) > 1e-6 ? 1.0 / abs(d.x) : 1e30, abs(d.y) > 1e-6 ? 1.0 / abs(d.y) : 1e30, abs(d.z) > 1e-6 ? 1.0 / abs(d.z) : 1e30);
            vec3 f = o - vec3(cell);
            vec3 nextT = vec3(d.x > 0.0 ? 1.0 - f.x : f.x, d.y > 0.0 ? 1.0 - f.y : f.y, d.z > 0.0 ? 1.0 - f.z : f.z) * delta;
            vec3 acc = vec3(0.0);
            float trans = 1.0, depth = 1.0e6;
            uint prev = cellAt(cell) & 0x3Fu;
            bool entered = inside(cell);
            float tExit = 0.0;
            for (int i = 0; i < 4096; i++) {
                float t;
                vec3 n;
                ivec3 back = cell;
                if (nextT.x <= nextT.y && nextT.x <= nextT.z) { t = nextT.x; cell.x += stp.x; nextT.x += delta.x; n = vec3(-float(stp.x), 0.0, 0.0); }
                else if (nextT.y <= nextT.z) { t = nextT.y; cell.y += stp.y; nextT.y += delta.y; n = vec3(0.0, -float(stp.y), 0.0); }
                else { t = nextT.z; cell.z += stp.z; nextT.z += delta.z; n = vec3(0.0, 0.0, -float(stp.z)); }
                if (t > pw_maxDistance) break;
                bool in_ = inside(cell);
                if (!in_) { if (entered) { tExit = t; break; } else continue; }
                entered = true;
                uint c = cellAt(cell), m = c & 0x3Fu;
                // models (stairs, slabs, torches, plants...) and entities: their quads inside this cell
                float tOut = min(nextT.x, min(nextT.y, nextT.z));
                float tm; vec3 nm; vec4 cm;
                if ((c & 0x80u) != 0u && meshHit(cell, o, d, t, tOut, tm, nm, cm)) {
                    ivec3 lc = ivec3(floor(o + d * tm + nm * 0.02));
                    uint l0 = cellAt(cell), l1 = cellAt(lc), l2 = cellAt(cell + ivec3(0, 1, 0));
                    uint sky = max(max((l0 >> 12) & 15u, (l1 >> 12) & 15u), (l2 >> 12) & 15u);
                    uint blk = max(max((l0 >> 8) & 15u, (l1 >> 8) & 15u), (l2 >> 8) & 15u);
                    vec3 col = m == 13u ? cm.rgb : cm.rgb * lightOf((sky << 12) | (blk << 8)) * faceShade(nm);
                    depth = min(depth, tm);
                    acc += trans * col;
                    trans = 0.0;
                    break;
                }
                if (m == 0u || ((c & 0x40u) != 0u && HAS_MESH && pw_models > 0.5)) { prev = m == 0u ? 0u : prev; continue; }
                uint front = cellAt(back);
                vec3 q = o + d * t;
                vec4 tx = texel(tileFor(cell, m, n), q, n);
                tx.rgb *= cubeTint(cell, n);
                if (m == 1u) {
                    if (prev == 1u) continue;
                    prev = 1u;
                    depth = min(depth, t);
                    vec3 w = tx.rgb * light(front, n) + sky(reflect(d, n)) * pw_waterReflection;
                    acc += trans * pw_waterOpacity * w;
                    trans *= 1.0 - pw_waterOpacity;
                    continue;
                }
                prev = m;
                if ((m == 5u || m == 6u || m == 11u) && tx.a < pw_cutout) continue; // cutout leaves, plants, glass
                vec3 col = (m == 2u || m == 13u) ? tx.rgb : tx.rgb * smoothLight(back, n, q);
                depth = min(depth, t);
                if (m == 11u || m == 10u) {
                    acc += trans * pw_glassOpacity * col;
                    trans *= 1.0 - pw_glassOpacity;
                    continue;
                }
                acc += trans * col;
                trans = 0.0;
                break;
            }
            // beyond the volume: the far field (top block height and class of every column) marched with growing steps
            if (trans > 0.0 && farField.head.w == 1) {
                float t = max(tExit, 0.5), lastY = 1e9;
                int fsz = farField.head.z;
                float maxTop = float(farField.camY.y - farField.camY.x) + 1.0;
                for (int i = 0; i < 1024; i++) {
                    if (t > pw_farDistance) break;
                    vec3 q = o + d * t;
                    if (q.y > maxTop && d.y >= 0.0) break; // above every hill and still rising: only sky ahead
                    ivec2 c = ivec2(floor(q.xz)) - farField.head.xy;
                    if (c.x < 0 || c.y < 0 || c.x >= fsz || c.y >= fsz) break;
                    uint fc = farField.cells[c.y * fsz + c.x];
                    int hAbs = int(fc & 0xFFFFu) - 32768;
                    if (hAbs > -32000) {
                        float top = float(hAbs - farField.camY.x) + 1.0;
                        float canopy = float((fc >> 24) & 0xFFu);
                        uint m = (fc >> 16) & 0x7Fu;
                        bool trunk = ((fc >> 16) & 0x80u) != 0u;
                        // a column under leaves: the canopy (at most pw_canopyDepth blocks) over open air over the ground
                        float ground = top - canopy;
                        bool inCanopy = canopy > 0.0 && q.y < top && q.y >= top - min(canopy, pw_canopyDepth);
                        bool inGround = q.y < (canopy > 0.0 ? ground : top);
                        // the trunk: a log column from the ground up to the canopy, narrower than the block (pw_trunkWidth)
                        vec2 fq = fract(q.xz) - 0.5;
                        bool inTrunk = trunk && canopy > 0.0 && q.y >= ground && q.y < top && max(abs(fq.x), abs(fq.y)) < pw_trunkWidth * 0.5;
                        if (inCanopy || inGround || inTrunk) {
                            float surf = inCanopy ? top : (canopy > 0.0 ? ground : top);
                            if (!inCanopy && !inTrunk && canopy > 0.0) m = 4u; // the ground under trees
                            if (inTrunk && !inCanopy) m = 7u;
                            vec3 n = lastY >= surf ? vec3(0.0, 1.0, 0.0)
                                : (abs(d.x) > abs(d.z) ? vec3(-sign(d.x), 0.0, 0.0) : vec3(0.0, 0.0, -sign(d.z)));
                            vec3 qs = n.y > 0.5 ? vec3(q.x, surf, q.z) : q;
                            vec4 tx = texel(tileOf(m, n), qs, n);
                            vec3 col = (m == 2u || m == 13u) ? tx.rgb : tx.rgb * light(0xF000u, n);
                            if (m == 1u) col = col * pw_waterOpacity + sky(reflect(d, n)) * pw_waterReflection;
                            acc += trans * col;
                            depth = min(depth, t);
                            trans = 0.0;
                            break;
                        }
                        lastY = q.y;
                    }
                    t += max(0.35, t * pw_farStep);
                }
            }
            vec3 skyCol = sky(d);
            // the game's flat cloud layer (clouds.png, 12 blocks per texel) where the ray reaches it before anything else
            if (HAS_CLOUDS && pw_clouds > 0.5 && abs(d.y) > 1e-4) {
                float camY = frame.cameraPosition.y;
                float tc = (pw_cloudHeight - camY) / d.y;
                if (tc > 0.0 && tc < depth && tc < pw_farDistance) {
                    vec3 wp = vec3(frame.cameraPosition.x, camY, frame.cameraPosition.z) + d * tc;
                    vec2 cuv = fract(vec2(wp.x + frame.timing.x * pw_cloudSpeed, wp.z) / (12.0 * CLOUD_RECT.zw));
                    vec4 cl = textureLod(atlas, (CLOUD_RECT.xy + cuv * CLOUD_RECT.zw) / vec2(textureSize(atlas, 0)), 0.0);
                    if (cl.a > 0.5) {
                        float day = clamp(sunDir().y * pw_dayRise + pw_dayOffset, pw_nightLevel, 1.0);
                        float fade = 1.0 - smoothstep(pw_farDistance * 0.5, pw_farDistance, tc);
                        skyCol = mix(skyCol, vec3(day) * (d.y < 0.0 ? 0.75 : 1.0), pw_cloudOpacity * fade);
                    }
                }
            }
            float rain = pw_rain > 1.5 ? 1.0 : pw_rain > 0.5 ? WORLD_RAIN : 0.0;
            skyCol *= 1.0 - rain * pw_rainDarken * 2.0;
            if (trans > 0.0) acc += trans * skyCol;
            acc *= 1.0 - rain * pw_rainDarken;
            // the game's rain: its texture on cylinders around the camera, falling
            // cold biome at the camera: snow (slow, white) instead of rain, like the game
            bool snowing = false;
            if (HAS_BIOMES && HAS_SNOW_TEX) {
                ivec3 cp = clamp(ivec3(floor(o)) - world.originRel.xyz, ivec3(0), world.size.xyz - 1);
                snowing = float(int(biomes.c[cp.z * world.size.x + cp.x].w) - 10000) / 1000.0 < pw_snowTemperature;
            }
            if (rain > 0.0 && (HAS_RAIN_TEX || snowing)) {
                vec2 dh = d.xz;
                float dl = length(dh);
                for (int k = 0; k < 4 && dl > 1e-4; k++) {
                    float rad = 2.0 + float(k) * 2.5;
                    float tr = rad / dl;
                    if (tr >= depth) break;
                    vec3 hp = d * tr;
                    float ang = atan(hp.z, hp.x);
                    // one quarter of the texture's width per block of arc and per 4 blocks of height, like the game's rain quads
                    float speed = snowing ? pw_rainSpeed * pw_snowSpeed : pw_rainSpeed;
                    vec2 ruv = vec2(fract(ang * rad * 0.25 + float(k) * 0.37 + (snowing ? sin(frame.timing.x * 0.7 + float(k)) * 0.05 : 0.0)),
                        fract((hp.y + frame.cameraPosition.y) * 0.25 + frame.timing.x * speed + float(k) * 0.21));
                    vec4 rect = snowing ? SNOW_RECT : RAIN_RECT;
                    vec4 rt = textureLod(atlas, (rect.xy + ruv * rect.zw) / vec2(textureSize(atlas, 0)), 0.0);
                    acc = mix(acc, snowing ? rt.rgb : rt.rgb * vec3(0.8, 0.85, 0.95), rt.a * pw_rainOpacity * rain);
                }
            }
            // the game's distance fog towards the sky colour at the horizon
            if (pw_fog > 0.5 && depth < 1.0e5) {
                float end = pw_maxDistance;
                float fogT = smoothstep(end * pw_fogStart, end, depth);
                acc = mix(acc, sky(normalize(vec3(d.x, 0.0, d.z) + vec3(0.0, 0.05, 0.0))), fogT);
            }
            imageStore(sceneOut, px, vec4(acc, 1.0));
            imageStore(distOut, px, vec4(depth));
        }
        """;

    @Override public synchronized void close() {
        if (chain != null) chain.destroy();
        chain = null;
        vkDestroyFence(device, fence, null);
        vkDestroyCommandPool(device, commandPool, null);
        vkDestroyDevice(device, null);
        vkDestroyInstance(instance, null);
    }
}
