package io.github.andrexarlay.vulkancompat.rtx.framegen;

import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import io.github.andrexarlay.vulkancompat.rt.core.BedrayCoreLayouts;
import io.github.andrexarlay.vulkancompat.rt.core.BedrayFgPrepareNative;
import io.github.andrexarlay.vulkancompat.rt.slang.SlangRuntime;
import io.github.andrexarlay.vulkancompat.rt.slang.SlangRuntimePool;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkImageCreateInfo;
import org.lwjgl.vulkan.VkImageViewCreateInfo;
import org.lwjgl.vulkan.VkMemoryAllocateInfo;
import org.lwjgl.vulkan.VkMemoryRequirements;
import org.lwjgl.vulkan.VkPhysicalDeviceMemoryProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.LongBuffer;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Frame-generation output images stay host-owned for their asynchronous vendor
 * consumers. C++ records only the producer blit/flip using immutable AOT
 * descriptors. Producer completion does not release the vendor image lease.
 */
public final class FrameGenGpu implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger("Bedray/FrameGenGpu");
    public static final int SETS = 3;
    public static final int FORMAT_DEPTH = VK10.VK_FORMAT_R32_SFLOAT;
    public static final int FORMAT_MV = VK10.VK_FORMAT_R16G16_SFLOAT;
    private static final int USAGE = VK10.VK_IMAGE_USAGE_SAMPLED_BIT | VK10.VK_IMAGE_USAGE_STORAGE_BIT
        | VK10.VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK10.VK_IMAGE_USAGE_TRANSFER_DST_BIT;

    public record Img(long image, long view, long memory, int format, int width, int height, int usage) {}

    private final VkDevice device;
    private final VkPhysicalDeviceMemoryProperties memProps;
    private final SlangRuntimePool.Lease runtimeLease;
    private final SlangRuntime runtime;
    private final BedrayFgPrepareNative nativePass;
    private final long program, sampler;
    private final Img[] hudless = new Img[SETS], depth = new Img[SETS], mv = new Img[SETS];
    // false = first-use transitions only, true = producer phase attempted.
    private final Map<Long, Boolean> recordings = new LinkedHashMap<>();
    private boolean needInitialTransition, resourcesClosed, closed;
    private long initializationRecording;
    private int hudFormat, winW, winH, renderW, renderH;

    public FrameGenGpu(VulkanDevice hostDevice) {
        runtimeLease = SlangRuntimePool.acquire(hostDevice);
        runtime = runtimeLease.runtime();
        device = hostDevice.vkDevice();
        VkPhysicalDeviceMemoryProperties properties = null;
        try {
            properties = VkPhysicalDeviceMemoryProperties.calloc();
            VK10.vkGetPhysicalDeviceMemoryProperties(device.getPhysicalDevice(), properties);
            var pass = runtime.program("fg_prepare").orElseThrow(() -> new IllegalStateException("Missing AOT fg_prepare"));
            var manifest = pass.manifest();
            if (manifest.pushConstantBytes() != 8 || manifest.bindings().size() != 4
                || !Arrays.equals(manifest.threadGroupSize(), new int[]{8,8,1}))
                throw new IllegalStateException("Invalid FG preparation manifest");
            String[] names = {"depthTexture","inMotionVectors","outDepth","outMotionVectors"};
            for (int index = 0; index < names.length; ++index) {
                var binding = manifest.binding(names[index]);
                int type = index == 0 ? BedrayCoreLayouts.DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER : BedrayCoreLayouts.DESCRIPTOR_TYPE_STORAGE_IMAGE;
                if (binding == null || binding.binding() != index || binding.descriptorType() != type)
                    throw new IllegalStateException("Invalid FG binding: " + names[index]);
            }
            program = pass.nativeHandle();
            sampler = runtime.atlasSampler(); // integer texel fetch: filtering/address mode is not consulted
            nativePass = new BedrayFgPrepareNative(runtime.library(), runtime.core(), program, sampler);
            memProps = properties;
        } catch (RuntimeException | Error failure) {
            if (properties != null) properties.free();
            runtimeLease.close();
            throw failure;
        }
    }

    public Img hudless(int set) { return hudless[set]; }
    public VulkanDevice hostDevice() { return runtime.hostDevice(); }
    public Img depth(int set) { return depth[set]; }
    public Img mv(int set) { return mv[set]; }
    public boolean matches(int format, int width, int height, int inputWidth, int inputHeight) {
        return hudless[0] != null && hudFormat == format && winW == width && winH == height && renderW == inputWidth && renderH == inputHeight;
    }
    private void requireOpen() { if (closed || resourcesClosed) throw new IllegalStateException("FG producer is closed"); }

    /** Caller also proves vendor-consumer idleness before replacing image outputs. */
    public void ensure(int format, int width, int height, int inputWidth, int inputHeight) {
        requireOpen();
        if (width <= 0 || height <= 0 || inputWidth <= 0 || inputHeight <= 0) throw new IllegalArgumentException("FG extent must be positive");
        if (matches(format,width,height,inputWidth,inputHeight)) return;
        if (!recordings.isEmpty()) throw new IllegalStateException("FG producers need completion/discard before resize");
        check(VK10.vkDeviceWaitIdle(device), "FG output replacement wait");
        Img[][] next = new Img[3][SETS];
        try {
            for (int index = 0; index < SETS; ++index) {
                next[0][index] = createImage(format,width,height);
                next[1][index] = createImage(FORMAT_DEPTH,inputWidth,inputHeight);
                next[2][index] = createImage(FORMAT_MV,inputWidth,inputHeight);
            }
        } catch (RuntimeException | Error failure) {
            for (Img[] group : next) for (Img image : group) destroyImage(image);
            throw failure;
        }
        destroyImages();
        System.arraycopy(next[0],0,hudless,0,SETS); System.arraycopy(next[1],0,depth,0,SETS); System.arraycopy(next[2],0,mv,0,SETS);
        hudFormat=format; winW=width; winH=height; renderW=inputWidth; renderH=inputHeight;
        needInitialTransition=true; initializationRecording=0;
        LOGGER.info("[VKB-FG] images created sets={} hudless={}x{} fmt={} depth/mv={}x{}",SETS,width,height,format,inputWidth,inputHeight);
    }

    private Img createImage(int format, int width, int height) {
        long image=0, memory=0, view=0;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var info=VkImageCreateInfo.calloc(stack).sType$Default().imageType(VK10.VK_IMAGE_TYPE_2D).format(format)
                .mipLevels(1).arrayLayers(1).samples(VK10.VK_SAMPLE_COUNT_1_BIT).tiling(VK10.VK_IMAGE_TILING_OPTIMAL)
                .usage(USAGE).sharingMode(VK10.VK_SHARING_MODE_EXCLUSIVE).initialLayout(VK10.VK_IMAGE_LAYOUT_UNDEFINED);
            info.extent().set(width,height,1);
            LongBuffer output=stack.mallocLong(1);
            check(VK10.vkCreateImage(device,info,null,output),"FG create image"); image=output.get(0);
            var requirements=VkMemoryRequirements.calloc(stack);
            VK10.vkGetImageMemoryRequirements(device,image,requirements);
            int memoryType=findMemoryType(requirements.memoryTypeBits(),VK10.VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
            if (memoryType < 0) throw new IllegalStateException("no device-local memory for FG outputs");
            var allocation=VkMemoryAllocateInfo.calloc(stack).sType$Default().allocationSize(requirements.size()).memoryTypeIndex(memoryType);
            check(VK10.vkAllocateMemory(device,allocation,null,output),"FG allocate memory"); memory=output.get(0);
            check(VK10.vkBindImageMemory(device,image,memory,0),"FG bind image");
            var imageView=VkImageViewCreateInfo.calloc(stack).sType$Default().image(image).viewType(VK10.VK_IMAGE_VIEW_TYPE_2D).format(format);
            imageView.subresourceRange().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
            check(VK10.vkCreateImageView(device,imageView,null,output),"FG create view"); view=output.get(0);
            return new Img(image,view,memory,format,width,height,USAGE);
        } catch (RuntimeException | Error failure) {
            if (view != 0) VK10.vkDestroyImageView(device,view,null);
            if (image != 0) VK10.vkDestroyImage(device,image,null);
            if (memory != 0) VK10.vkFreeMemory(device,memory,null);
            throw failure;
        }
    }
    private int findMemoryType(int filter, int properties) {
        for(int index=0;index<memProps.memoryTypeCount();++index)
            if((filter&(1<<index))!=0 && (memProps.memoryTypes(index).propertyFlags()&properties)==properties) return index;
        return -1;
    }
    private void destroyImage(Img image) {
        if(image==null) return;
        VK10.vkDestroyImageView(device,image.view(),null); VK10.vkDestroyImage(device,image.image(),null); VK10.vkFreeMemory(device,image.memory(),null);
    }
    private void destroyImages() {
        for(Img[] group:new Img[][]{hudless,depth,mv}) for(int index=0;index<SETS;++index) { destroyImage(group[index]); group[index]=null; }
    }
    private static void check(int result,String operation) {
        if(result!=VK10.VK_SUCCESS) throw new IllegalStateException(operation+" failed: "+result);
    }

    /** First-use transitions remain with the host output-image owner. */
    public void recordInitialTransitions(VkCommandBuffer command) {
        requireOpen();
        if(command==null || hudless[0]==null) throw new IllegalStateException("FG images/command unavailable");
        if(!needInitialTransition) return;
        if(recordings.containsKey(command.address())) throw new IllegalStateException("FG command still pending");
        recordings.put(command.address(),false); initializationRecording=command.address(); needInitialTransition=false;
        for(int index=0;index<SETS;++index) for(Img image:new Img[]{hudless[index],depth[index],mv[index]})
            io.github.andrexarlay.vulkancompat.rtx.upscaler.VkBarriers.transition(command,image.image(),VK10.VK_IMAGE_ASPECT_COLOR_BIT,
                VK10.VK_IMAGE_LAYOUT_UNDEFINED,VK10.VK_IMAGE_LAYOUT_GENERAL);
    }

    /** Native producer; caller holds the separate vendor output-image lease. */
    public void record(VkCommandBuffer command,int set,long sourceColor,long depthView,long motionView) {
        requireOpen();
        if(command==null || set<0 || set>=SETS || hudless[set]==null || needInitialTransition)
            throw new IllegalStateException("FG producer requires initialized images and a valid command/slot");
        long handle=command.address();
        BedrayFgPrepareNative.validate(handle,program,sampler,sourceColor,hudless[set].image(),depthView,motionView,
            depth[set].view(),mv[set].view(),winW,winH,renderW,renderH);
        if(Boolean.TRUE.equals(recordings.get(handle))) throw new IllegalStateException("FG producer command still pending");
        recordings.put(handle,true); // native failure can follow the emitted blit
        nativePass.record(handle,sourceColor,hudless[set].image(),depthView,motionView,depth[set].view(),mv[set].view(),winW,winH,renderW,renderH);
    }
    public void releaseRecording(long command) {
        if(!recordings.containsKey(command)) return;
        runtime.releaseRecording(command); recordings.remove(command);
        if(initializationRecording==command) initializationRecording=0;
    }
    public void discardRecording(long command) {
        if(!recordings.containsKey(command)) return;
        runtime.discardRecording(command); recordings.remove(command);
        if(initializationRecording==command) { initializationRecording=0; needInitialTransition=true; }
    }
    public boolean hasPendingRecordings() { return !recordings.isEmpty(); }
    /** Only after all producer commands completed and open recordings were closed/discarded. */
    public void releaseRecordingsAfterHostDrain() { for(long command:List.copyOf(recordings.keySet())) releaseRecording(command); }

    /** Caller also ends the asynchronous vendor consumer lifetime before close. */
    @Override public void close() {
        if(closed) return;
        if(!recordings.isEmpty()) throw new IllegalStateException("FG producers need completion/discard before close");
        if(!resourcesClosed) {
            check(VK10.vkDeviceWaitIdle(device),"FG close wait");
            destroyImages(); memProps.free(); resourcesClosed=true;
        }
        runtimeLease.close(); closed=true;
    }
}
