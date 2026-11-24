package io.github.andrexarlay.vulkancompat.shaderhost.mosaic.studio;

import io.github.andrexarlay.vulkancompat.shaderhost.glsl.SpirvReflection;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;

import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
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

    private Prepared prepared;

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

    private final class Prepared {
        int spirvHash, w, h;
        long module, setLayout, pipelineLayout, pipeline, pool, set, sampler;
        Buf ubo, upload, readback;
        Img input, output, depth, one;
        final List<Buf> buffers = new ArrayList<>();
        boolean push;

        void destroy() {
            vkDeviceWaitIdle(device);
            if (pipeline != 0) vkDestroyPipeline(device, pipeline, null);
            if (pipelineLayout != 0) vkDestroyPipelineLayout(device, pipelineLayout, null);
            if (pool != 0) vkDestroyDescriptorPool(device, pool, null);
            if (setLayout != 0) vkDestroyDescriptorSetLayout(device, setLayout, null);
            if (module != 0) vkDestroyShaderModule(device, module, null);
            if (sampler != 0) vkDestroySampler(device, sampler, null);
            for (Buf b : new Buf[] {ubo, upload, readback}) free(b);
            for (Buf b : buffers) free(b);
            for (Img i : new Img[] {input, output, depth, one}) free(i);
        }
    }

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

    /**
     * Runs {@code spirv} over {@code input} and returns the output (clamped to 0..1 for display). Throws when the shader
     * needs something the studio cannot give (ray tracing).
     */
    synchronized BufferedImage run(byte[] spirv, BufferedImage input, float time, int frame, Extras extras) {
        int w = input.getWidth(), h = input.getHeight();
        int hash = Arrays.hashCode(spirv);
        if (prepared == null || prepared.spirvHash != hash || prepared.w != w || prepared.h != h) {
            if (prepared != null) prepared.destroy();
            prepared = null;
            prepared = prepare(spirv, hash, w, h, extras);
        }
        Prepared p = prepared;
        writeFrame(p.ubo.mapped(), w, h, time, frame);
        int[] px = input.getRGB(0, 0, w, h, null, 0, w);
        for (int y = 0; y < h; y++) // bottom-up like the game's world target (GL origin)
            for (int x = 0; x < w; x++) {
                int c = px[(h - 1 - y) * w + x];
                MemoryUtil.memPutInt(p.upload.mapped() + ((long) y * w + x) * 4, (c & 0xFF00FF00) | (c >> 16 & 0xFF) | (c & 0xFF) << 16);
            }
        try (MemoryStack s = MemoryStack.stackPush()) {
            vkResetCommandBuffer(cmd, 0);
            vkBeginCommandBuffer(cmd, VkCommandBufferBeginInfo.calloc(s).sType$Default().flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT));
            barrier(s, p.input.image(), VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
            copyIn(s, p.upload, p.input);
            barrier(s, p.input.image(), VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            barrier(s, p.output.image(), VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_GENERAL);
            vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, p.pipeline);
            vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, p.pipelineLayout, 0, s.longs(p.set), null);
            if (p.push) {
                ByteBuffer pb = s.calloc(128);
                if (extras != null && extras.push() != null) pb.put(0, extras.push(), 0, Math.min(128, extras.push().length));
                vkCmdPushConstants(cmd, p.pipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT, 0, pb);
            }
            vkCmdDispatch(cmd, (w + 7) / 8, (h + 7) / 8, 1);
            barrier(s, p.output.image(), VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
            VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, s);
            region.get(0).imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
            region.get(0).imageExtent().set(w, h, 1);
            vkCmdCopyImageToBuffer(cmd, p.output.image(), VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, p.readback.handle(), region);
            vkEndCommandBuffer(cmd);
            vkResetFences(device, fence);
            check(vkQueueSubmit(queue, VkSubmitInfo.calloc(s).sType$Default().pCommandBuffers(s.pointers(cmd)), fence), "submit");
            check(vkWaitForFences(device, fence, true, 5_000_000_000L), "wait");
        }
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        int[] row = new int[w];
        for (int y = 0; y < h; y++) {
            long base = p.readback.mapped() + (long) (h - 1 - y) * w * 8;
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

    private Prepared prepare(byte[] spirv, int hash, int w, int h, Extras extras) {
        ByteBuffer code = MemoryUtil.memAlloc(spirv.length).order(ByteOrder.LITTLE_ENDIAN);
        code.put(spirv).flip();
        SpirvReflection.Module module = SpirvReflection.reflect(code.duplicate().order(ByteOrder.LITTLE_ENDIAN));
        for (SpirvReflection.Binding b : module.bindings())
            if (b.kind() == SpirvReflection.DescriptorKind.ACCELERATION_STRUCTURE) {
                MemoryUtil.memFree(code);
                throw new UnsupportedOperationException("this graph traces rays: its preview needs the game's scene (see Result)");
            }
        Prepared p = new Prepared();
        p.spirvHash = hash;
        p.w = w;
        p.h = h;
        p.push = module.pushConstants();
        try (MemoryStack s = MemoryStack.stackPush()) {
            LongBuffer lp = s.mallocLong(1);
            check(vkCreateShaderModule(device, VkShaderModuleCreateInfo.calloc(s).sType$Default().pCode(code), null, lp), "shader module");
            p.module = lp.get(0);
            MemoryUtil.memFree(code);
            p.ubo = buffer(320, VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT);
            p.upload = buffer((long) w * h * 4, VK_BUFFER_USAGE_TRANSFER_SRC_BIT);
            p.readback = buffer((long) w * h * 8, VK_BUFFER_USAGE_TRANSFER_DST_BIT);
            p.input = image(w, h, VK_FORMAT_R8G8B8A8_UNORM, VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT);
            p.output = image(w, h, VK_FORMAT_R16G16B16A16_SFLOAT, VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT);
            p.depth = image(1, 1, VK_FORMAT_R32_SFLOAT, VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT);
            fill(p.depth, new int[] {Float.floatToIntBits(1.0e6f)}, 4); // "the ray hit nothing": sky everywhere
            BufferedImage lut = extras == null ? null : extras.lut();
            p.one = lut == null ? image(1, 1, VK_FORMAT_R8G8B8A8_UNORM, VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT)
                : image(lut.getWidth(), lut.getHeight(), VK_FORMAT_R8G8B8A8_UNORM, VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT);
            if (lut == null) fill(p.one, new int[] {0xFFFFFFFF}, 4);
            else {
                int[] px = lut.getRGB(0, 0, lut.getWidth(), lut.getHeight(), null, 0, lut.getWidth());
                for (int i = 0; i < px.length; i++) px[i] = (px[i] & 0xFF00FF00) | (px[i] >> 16 & 0xFF) | (px[i] & 0xFF) << 16;
                fill(p.one, px, 4);
            }
            check(vkCreateSampler(device, VkSamplerCreateInfo.calloc(s).sType$Default().magFilter(VK_FILTER_LINEAR).minFilter(VK_FILTER_LINEAR)
                .addressModeU(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE).addressModeV(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                .addressModeW(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE).maxLod(0), null, lp), "sampler");
            p.sampler = lp.get(0);

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
                        Buf buf = b.binding() == 1 ? p.ubo : extraBuffer(p, b, extras);
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
                        Img img = b.binding() == 2 ? p.input : b.binding() == 4 ? p.depth : p.one;
                        VkDescriptorImageInfo.Buffer ii = VkDescriptorImageInfo.calloc(1, s);
                        ii.get(0).sampler(p.sampler).imageView(img.view()).imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
                        wr.get(0).pImageInfo(ii);
                    }
                }
                vkUpdateDescriptorSets(device, wr, null);
            }
        }
        return p;
    }

    /** User buffer (binding 5: buffer5.bin) and the world volume (15: empty world, sky light everywhere), else zeros. */
    private Buf extraBuffer(Prepared p, SpirvReflection.Binding b, Extras extras) {
        Buf buf;
        if (b.binding() == 15) {
            int cells = 48 * 32 * 48;
            buf = buffer(48 + cells * 2L, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT);
            MemoryUtil.memPutInt(buf.mapped() + 32, 48);
            MemoryUtil.memPutInt(buf.mapped() + 36, 32);
            MemoryUtil.memPutInt(buf.mapped() + 40, 48);
            for (int i = 0; i < cells; i++) MemoryUtil.memPutShort(buf.mapped() + 48 + i * 2L, (short) (15 << 12)); // air, full sky light
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

    /** The 320-byte frame block: a camera at the origin looking along -Z, 70 degrees, noon. */
    private static void writeFrame(long at, int w, int h, float time, int frame) {
        org.joml.Matrix4f proj = new org.joml.Matrix4f().perspective((float) Math.toRadians(70), w / (float) h, 0.05f, 1024f);
        org.joml.Matrix4f view = new org.joml.Matrix4f();
        ByteBuffer b = MemoryUtil.memByteBuffer(at, 320).order(ByteOrder.LITTLE_ENDIAN);
        proj.get(0, b);
        new org.joml.Matrix4f(proj).invert().get(64, b);
        view.get(128, b);
        new org.joml.Matrix4f(view).invert().get(192, b);
        b.putFloat(256, 0f).putFloat(260, 64f).putFloat(264, 0f).putFloat(268, 0f);
        b.putFloat(272, w).putFloat(276, h).putFloat(280, 1f / w).putFloat(284, 1f / h);
        b.putFloat(288, time % 3600f).putFloat(292, 1f / 30f).putFloat(296, frame).putFloat(300, 0f);
        b.putFloat(304, 0.05f).putFloat(308, 1024f).putFloat(312, 0f).putFloat(316, 0f);
    }

    @Override public synchronized void close() {
        if (prepared != null) prepared.destroy();
        prepared = null;
        vkDestroyFence(device, fence, null);
        vkDestroyCommandPool(device, commandPool, null);
        vkDestroyDevice(device, null);
        vkDestroyInstance(instance, null);
    }
}
