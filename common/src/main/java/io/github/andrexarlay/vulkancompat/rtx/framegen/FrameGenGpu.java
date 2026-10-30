package io.github.andrexarlay.vulkancompat.rtx.framegen;

import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import io.github.andrexarlay.vulkancompat.rt.core.BedrayCoreLayouts;
import io.github.andrexarlay.vulkancompat.rt.core.BedrayCoreNative;
import io.github.andrexarlay.vulkancompat.rt.slang.SlangPassManifest;
import io.github.andrexarlay.vulkancompat.rt.slang.SlangProgram;
import io.github.andrexarlay.vulkancompat.rt.slang.SlangRuntime;
import io.github.andrexarlay.vulkancompat.rt.slang.SlangRuntimePool;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkImageCreateInfo;
import org.lwjgl.vulkan.VkMemoryAllocateInfo;
import org.lwjgl.vulkan.VkMemoryRequirements;
import org.lwjgl.vulkan.VkPhysicalDeviceMemoryProperties;
import org.lwjgl.vulkan.VkSamplerCreateInfo;
import org.lwjgl.vulkan.VkImageViewCreateInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.LongBuffer;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * GPU side of the frame generation inputs, all plain Vulkan on the engine device (the engine keeps its own images in
 * GENERAL layout and so do these):
 * <ul>
 * <li>a ring of {@link #SETS} sets {hudless colour (window size, back buffer format), flipped depth (R32F), flipped motion
 *     vectors (RG16F)}; a ring because Streamline reads the tagged images asynchronously after {@code vkQueuePresentKHR}
 *     (ProgrammingGuideDLSS_G.md 5.2: tags valid until present), while the next frame already writes the next set;</li>
 * <li>the AOT Slang pass {@code fg_prepare} recorded by the native core through
 *     {@code bedray_core_render_fg_prepare}: the core owns the flipped blit of the engine main target into the hudless
 *     image, the depth/motion flip dispatch, its descriptors and the barriers between them.</li>
 * </ul>
 * The core pins a descriptor pool per recording, so every recorded command buffer is handed back exactly once - released
 * when the encoder's fence proved the submission retired, discarded when the recording was abandoned unsubmitted.
 * Render thread only.
 */
public final class FrameGenGpu implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger("Bedray/FrameGenGpu");
    public static final int SETS = 3;
    public static final int FORMAT_DEPTH = VK10.VK_FORMAT_R32_SFLOAT;
    public static final int FORMAT_MV = VK10.VK_FORMAT_R16G16_SFLOAT;
    /** The AOT artifact the native producer validates before it records: 4 bindings, uint2 push, 8x8x1 threads. */
    static final String PASS_NAME = "fg_prepare";
    static final int PUSH_BYTES = 8;
    static final int[] THREADS = {8, 8, 1};
    private static final int USAGE = VK10.VK_IMAGE_USAGE_SAMPLED_BIT | VK10.VK_IMAGE_USAGE_STORAGE_BIT
        | VK10.VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK10.VK_IMAGE_USAGE_TRANSFER_DST_BIT;

    /** One image with its view and memory. */
    public record Img(long image, long view, long memory, int format, int width, int height, int usage) {}

    private final VkDevice device;
    private final VkPhysicalDeviceMemoryProperties memProps;
    private final SlangRuntimePool.Lease runtimeLease;
    private final BedrayCoreNative library;
    private final long core;
    private final SlangProgram program;
    private final Set<Long> recordings = new LinkedHashSet<>();

    private final Img[] hudless = new Img[SETS];
    private final Img[] depth = new Img[SETS];
    private final Img[] mv = new Img[SETS];
    private boolean needInitialTransition;

    private long sampler;

    private int hudFormat, winW, winH, renderW, renderH;

    public FrameGenGpu(VulkanDevice device) {
        this.device = device.vkDevice();
        this.memProps = VkPhysicalDeviceMemoryProperties.calloc();
        VK10.vkGetPhysicalDeviceMemoryProperties(this.device.getPhysicalDevice(), memProps);
        SlangRuntimePool.Lease lease = SlangRuntimePool.acquire(device);
        try {
            SlangRuntime runtime = lease.runtime();
            this.library = runtime.library();
            this.core = runtime.core();
            if (!library.hasFgPrepare()) {
                throw new IllegalStateException("the loaded bedray_core has no frame-generation producer export");
            }
            this.program = runtime.program(PASS_NAME)
                .orElseThrow(() -> new IllegalStateException("missing Slang pass '" + PASS_NAME + "'"));
            requireManifest(program.manifest());
            this.runtimeLease = lease;
        } catch (RuntimeException | Error failure) {
            lease.close();
            memProps.free();
            throw failure;
        }
        createSampler();
    }

    /** The program contract the C++ pass re-checks; a one-sided shader edit dies here, not as a wrong dispatch. */
    static void requireManifest(SlangPassManifest manifest) {
        if (manifest.pushConstantBytes() != PUSH_BYTES) {
            throw new IllegalStateException(manifest.name() + " pushes " + manifest.pushConstantBytes()
                + " B, the producer contract is " + PUSH_BYTES + " B");
        }
        if (manifest.bindings().size() != 4) {
            throw new IllegalStateException(manifest.name() + " declares " + manifest.bindings().size()
                + " binding(s), the producer contract is 4");
        }
        if (!Arrays.equals(manifest.threadGroupSize(), THREADS)) {
            throw new IllegalStateException(manifest.name() + " runs " + Arrays.toString(manifest.threadGroupSize())
                + ", the producer grid is " + Arrays.toString(THREADS));
        }
        boolean[] seen = new boolean[4];
        for (SlangPassManifest.Binding binding : manifest.bindings()) {
            int slot = binding.binding();
            if (slot < 0 || slot >= seen.length || seen[slot]) {
                throw new IllegalStateException(manifest.name() + ": unexpected binding " + slot);
            }
            seen[slot] = true;
            int want = slot == 0 ? BedrayCoreLayouts.DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER
                : BedrayCoreLayouts.DESCRIPTOR_TYPE_STORAGE_IMAGE;
            if (binding.descriptorType() != want) {
                throw new IllegalStateException(manifest.name() + ": binding " + slot + " is "
                    + BedrayCoreLayouts.descriptorTypeName(binding.descriptorType()) + ", the producer supplies "
                    + BedrayCoreLayouts.descriptorTypeName(want));
            }
        }
    }

    public Img hudless(int set) {
        return hudless[set];
    }

    public Img depth(int set) {
        return depth[set];
    }

    public Img mv(int set) {
        return mv[set];
    }

    public boolean matches(int hudFormat, int winW, int winH, int renderW, int renderH) {
        return hudless[0] != null && this.hudFormat == hudFormat && this.winW == winW && this.winH == winH
            && this.renderW == renderW && this.renderH == renderH;
    }

    /** (Re)creates the image sets. The caller guarantees the GPU is idle w.r.t. the old images (it waits for device idle). */
    public void ensure(int hudFormat, int winW, int winH, int renderW, int renderH) {
        if (matches(hudFormat, winW, winH, renderW, renderH)) {
            return;
        }
        VK10.vkDeviceWaitIdle(device);
        destroyImages();
        for (int i = 0; i < SETS; i++) {
            hudless[i] = createImage(hudFormat, winW, winH);
            depth[i] = createImage(FORMAT_DEPTH, renderW, renderH);
            mv[i] = createImage(FORMAT_MV, renderW, renderH);
        }
        this.hudFormat = hudFormat;
        this.winW = winW;
        this.winH = winH;
        this.renderW = renderW;
        this.renderH = renderH;
        this.needInitialTransition = true;
        LOGGER.info("[VKB-FG] images created sets={} hudless={}x{} fmt={} depth/mv={}x{}", SETS, winW, winH, hudFormat, renderW, renderH);
    }

    private Img createImage(int format, int w, int h) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkImageCreateInfo ci = VkImageCreateInfo.calloc(stack).sType$Default()
                .imageType(VK10.VK_IMAGE_TYPE_2D).format(format)
                .mipLevels(1).arrayLayers(1).samples(VK10.VK_SAMPLE_COUNT_1_BIT)
                .tiling(VK10.VK_IMAGE_TILING_OPTIMAL).usage(USAGE)
                .sharingMode(VK10.VK_SHARING_MODE_EXCLUSIVE).initialLayout(VK10.VK_IMAGE_LAYOUT_UNDEFINED);
            ci.extent().set(w, h, 1);
            LongBuffer p = stack.mallocLong(1);
            check(VK10.vkCreateImage(device, ci, null, p), "vkCreateImage");
            long image = p.get(0);
            VkMemoryRequirements req = VkMemoryRequirements.calloc(stack);
            VK10.vkGetImageMemoryRequirements(device, image, req);
            int type = findMemoryType(req.memoryTypeBits(), VK10.VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
            if (type < 0) {
                throw new IllegalStateException("no device local memory type for the frame generation images");
            }
            VkMemoryAllocateInfo ai = VkMemoryAllocateInfo.calloc(stack).sType$Default()
                .allocationSize(req.size()).memoryTypeIndex(type);
            check(VK10.vkAllocateMemory(device, ai, null, p), "vkAllocateMemory");
            long mem = p.get(0);
            check(VK10.vkBindImageMemory(device, image, mem, 0), "vkBindImageMemory");
            VkImageViewCreateInfo vi = VkImageViewCreateInfo.calloc(stack).sType$Default()
                .image(image).viewType(VK10.VK_IMAGE_VIEW_TYPE_2D).format(format);
            vi.subresourceRange().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
            check(VK10.vkCreateImageView(device, vi, null, p), "vkCreateImageView");
            return new Img(image, p.get(0), mem, format, w, h, USAGE);
        }
    }

    private void destroyImages() {
        for (int i = 0; i < SETS; i++) {
            for (Img[] arr : new Img[][] {hudless, depth, mv}) {
                Img im = arr[i];
                if (im != null) {
                    VK10.vkDestroyImageView(device, im.view(), null);
                    VK10.vkDestroyImage(device, im.image(), null);
                    VK10.vkFreeMemory(device, im.memory(), null);
                    arr[i] = null;
                }
            }
        }
    }

    private int findMemoryType(int filter, int props) {
        for (int i = 0; i < memProps.memoryTypeCount(); i++) {
            if ((filter & (1 << i)) != 0 && (memProps.memoryTypes(i).propertyFlags() & props) == props) {
                return i;
            }
        }
        return -1;
    }

    private void createSampler() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkSamplerCreateInfo si = VkSamplerCreateInfo.calloc(stack).sType$Default()
                .magFilter(VK10.VK_FILTER_NEAREST).minFilter(VK10.VK_FILTER_NEAREST)
                .mipmapMode(VK10.VK_SAMPLER_MIPMAP_MODE_NEAREST)
                .addressModeU(VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                .addressModeV(VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                .addressModeW(VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE);
            LongBuffer p = stack.mallocLong(1);
            check(VK10.vkCreateSampler(device, si, null, p), "vkCreateSampler");
            sampler = p.get(0);
        }
    }

    private static void check(int r, String what) {
        if (r != VK10.VK_SUCCESS) {
            throw new IllegalStateException(what + " failed: " + r);
        }
    }

    /** First use: UNDEFINED -> GENERAL for every image (they stay in GENERAL like all engine textures). */
    public void recordInitialTransitions(VkCommandBuffer cmd) {
        if (!needInitialTransition) {
            return;
        }
        needInitialTransition = false;
        for (int i = 0; i < SETS; i++) {
            for (Img im : new Img[] {hudless[i], depth[i], mv[i]}) {
                io.github.andrexarlay.vulkancompat.rtx.upscaler.VkBarriers.transition(cmd, im.image(),
                    VK10.VK_IMAGE_ASPECT_COLOR_BIT, VK10.VK_IMAGE_LAYOUT_UNDEFINED, VK10.VK_IMAGE_LAYOUT_GENERAL);
            }
        }
    }

    /**
     * Records for one frame: vertically flipped copy of the engine main target colour into {@code hudless(set)} and the
     * flip pass depth/motion vectors -> {@code depth(set)}/{@code mv(set)}. All images GENERAL; the core records its own
     * barriers. Returns the action that releases the core's recording, to be run once the encoder's fence proves the
     * submission retired.
     *
     * @param mainColorImage engine main target colour (world + hand, no GUI yet), window size, RGBA8
     * @param depthView      depth aspect view of the world depth copy (render size)
     * @param mvView         view of the engine motion vector image (render size, RG16F)
     */
    public Runnable record(VkCommandBuffer cmd, int set, long mainColorImage, long depthView, long mvView) {
        Img h = hudless[set];
        Img d = depth[set];
        Img m = mv[set];
        long command = cmd.address();
        // Registered before the core sees it: a failure inside the native recording leaves the whole command buffer
        // to be discarded by the caller, and discard must find the command here to recycle the pool.
        recordings.add(command);
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment desc = arena.allocate(BedrayCoreLayouts.FG_PREPARE_DESC);
            desc.set(ValueLayout.JAVA_INT, BedrayCoreLayouts.FGP_ABI_VERSION, BedrayCoreLayouts.ABI_VERSION);
            desc.set(ValueLayout.JAVA_INT, BedrayCoreLayouts.FGP_STRUCT_SIZE,
                (int) BedrayCoreLayouts.FG_PREPARE_DESC.byteSize());
            desc.set(ValueLayout.JAVA_LONG, BedrayCoreLayouts.FGP_COMMAND_BUFFER, command);
            desc.set(ValueLayout.JAVA_LONG, BedrayCoreLayouts.FGP_PROGRAM, program.nativeHandle());
            desc.set(ValueLayout.JAVA_LONG, BedrayCoreLayouts.FGP_SAMPLER, sampler);
            desc.set(ValueLayout.JAVA_LONG, BedrayCoreLayouts.FGP_SOURCE_COLOR_IMAGE, mainColorImage);
            desc.set(ValueLayout.JAVA_LONG, BedrayCoreLayouts.FGP_HUDLESS_IMAGE, h.image());
            desc.set(ValueLayout.JAVA_LONG, BedrayCoreLayouts.FGP_DEPTH_VIEW, depthView);
            desc.set(ValueLayout.JAVA_LONG, BedrayCoreLayouts.FGP_INPUT_MOTION_VIEW, mvView);
            desc.set(ValueLayout.JAVA_LONG, BedrayCoreLayouts.FGP_OUTPUT_DEPTH_VIEW, d.view());
            desc.set(ValueLayout.JAVA_LONG, BedrayCoreLayouts.FGP_OUTPUT_MOTION_VIEW, m.view());
            desc.set(ValueLayout.JAVA_INT, BedrayCoreLayouts.FGP_WINDOW_WIDTH, h.width());
            desc.set(ValueLayout.JAVA_INT, BedrayCoreLayouts.FGP_WINDOW_HEIGHT, h.height());
            desc.set(ValueLayout.JAVA_INT, BedrayCoreLayouts.FGP_RENDER_WIDTH, d.width());
            desc.set(ValueLayout.JAVA_INT, BedrayCoreLayouts.FGP_RENDER_HEIGHT, d.height());
            desc.set(ValueLayout.JAVA_LONG, BedrayCoreLayouts.FGP_RESERVED, 0L);
            library.renderFgPrepare(core, desc);
        }
        return () -> releaseRecording(command);
    }

    /** Recycles the pinned descriptors once the submission is proven retired. Idempotent. */
    public void releaseRecording(long command) {
        if (recordings.remove(command)) {
            library.releaseRecording(core, command);
        }
    }

    /** Invalidates a recording that was never submitted, so its descriptor pool is not pinned for the session. */
    public void discardRecording(long command) {
        if (recordings.remove(command)) {
            library.discardRecording(core, command);
        }
    }

    @Override
    public void close() {
        VK10.vkDeviceWaitIdle(device);
        for (long command : Set.copyOf(recordings)) {
            releaseRecording(command);
        }
        destroyImages();
        if (sampler != 0L) VK10.vkDestroySampler(device, sampler, null);
        sampler = 0L;
        runtimeLease.close();
        memProps.free();
    }
}
