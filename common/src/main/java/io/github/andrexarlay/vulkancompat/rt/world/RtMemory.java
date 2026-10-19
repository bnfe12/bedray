package io.github.andrexarlay.vulkancompat.rt.world;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRAccelerationStructure;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VK11;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkBufferCreateInfo;
import org.lwjgl.vulkan.VkBufferDeviceAddressInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkImageCreateInfo;
import org.lwjgl.vulkan.VkImageViewCreateInfo;
import org.lwjgl.vulkan.VkMemoryAllocateFlagsInfo;
import org.lwjgl.vulkan.VkMemoryAllocateInfo;
import org.lwjgl.vulkan.VkMemoryRequirements;
import org.lwjgl.vulkan.VkPhysicalDeviceAccelerationStructurePropertiesKHR;
import org.lwjgl.vulkan.VkPhysicalDeviceMemoryProperties;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties2;

import java.nio.LongBuffer;

/**
 * Raw Vulkan memory helper of the world ray tracing path (own buffers on the engine's VkDevice, no VMA: the engine's
 * and Sodium's buffers move/defragment and lack the device-address usage bits, see the Sodium geometry notes).
 * Every buffer is one VkBuffer + one VkDeviceMemory; callers sub-allocate with {@link RtArena}, so the number of
 * vkAllocateMemory calls stays far below maxMemoryAllocationCount.
 */
public final class RtMemory {
    /** One buffer with its memory; {@code address} is non-zero only with the device-address usage bit. */
    public static final class Buf {
        public final long handle;
        public final long memory;
        public final long size;
        public final long address;
        /** Persistently mapped pointer for host-visible buffers, else 0. */
        public final long mapped;
        public final boolean hostVisible;

        Buf(long handle, long memory, long size, long address, long mapped, boolean hostVisible) {
            this.handle = handle;
            this.memory = memory;
            this.size = size;
            this.address = address;
            this.mapped = mapped;
            this.hostVisible = hostVisible;
        }
    }

    /** A 2D colour image with its memory and one view. */
    public static final class Img {
        public final long image;
        public final long view;
        public final long memory;
        public final int width;
        public final int height;
        public final long bytes;

        Img(long image, long view, long memory, int width, int height, long bytes) {
            this.image = image;
            this.view = view;
            this.memory = memory;
            this.width = width;
            this.height = height;
            this.bytes = bytes;
        }
    }

    public final VkDevice device;
    private final VkPhysicalDeviceMemoryProperties memProps;
    /** minAccelerationStructureScratchOffsetAlignment of the physical device. */
    public final int scratchAlignment;
    /** Total bytes of all live buffers and images created through this object (reserved, not "used"). */
    private long reservedBytes;

    public RtMemory(VkDevice device) {
        this.device = device;
        this.memProps = VkPhysicalDeviceMemoryProperties.calloc();
        VK10.vkGetPhysicalDeviceMemoryProperties(device.getPhysicalDevice(), memProps);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPhysicalDeviceAccelerationStructurePropertiesKHR asProps =
                VkPhysicalDeviceAccelerationStructurePropertiesKHR.calloc(stack).sType$Default();
            VkPhysicalDeviceProperties2 props2 = VkPhysicalDeviceProperties2.calloc(stack).sType$Default().pNext(asProps.address());
            VK11.vkGetPhysicalDeviceProperties2(device.getPhysicalDevice(), props2);
            int align = asProps.minAccelerationStructureScratchOffsetAlignment();
            if (align <= 0) {
                throw new RtFailure("init", "minAccelerationStructureScratchOffsetAlignment=" + align);
            }
            this.scratchAlignment = align;
        }
    }

    public VkDevice device() {
        return device;
    }

    public long reservedBytes() {
        return reservedBytes;
    }

    private int findMemoryType(int typeBits, int required) {
        for (int i = 0; i < memProps.memoryTypeCount(); i++) {
            if ((typeBits & (1 << i)) != 0 && (memProps.memoryTypes(i).propertyFlags() & required) == required) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Creates a buffer. Host-visible buffers use HOST_VISIBLE|HOST_COHERENT memory and stay mapped; others use
     * DEVICE_LOCAL. The device-address bit adds VK_MEMORY_ALLOCATE_DEVICE_ADDRESS_BIT and queries the address.
     */
    public Buf createBuffer(String what, long size, int usage, boolean hostVisible) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkBufferCreateInfo info = VkBufferCreateInfo.calloc(stack).sType$Default()
                .size(size).usage(usage).sharingMode(VK10.VK_SHARING_MODE_EXCLUSIVE);
            LongBuffer pBuf = stack.mallocLong(1);
            RtFailure.check(VK10.vkCreateBuffer(device, info, null, pBuf), "create_buffer:" + what);
            long buffer = pBuf.get(0);

            VkMemoryRequirements reqs = VkMemoryRequirements.calloc(stack);
            VK10.vkGetBufferMemoryRequirements(device, buffer, reqs);
            int required = hostVisible
                ? VK10.VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK10.VK_MEMORY_PROPERTY_HOST_COHERENT_BIT
                : VK10.VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT;
            int type = findMemoryType(reqs.memoryTypeBits(), required);
            if (type < 0) {
                VK10.vkDestroyBuffer(device, buffer, null);
                throw new RtFailure("create_buffer:" + what, "no_memory_type(hostVisible=" + hostVisible + ")");
            }
            boolean addressable = (usage & VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT) != 0;
            VkMemoryAllocateInfo alloc = VkMemoryAllocateInfo.calloc(stack).sType$Default()
                .allocationSize(reqs.size()).memoryTypeIndex(type);
            if (addressable) {
                VkMemoryAllocateFlagsInfo flags = VkMemoryAllocateFlagsInfo.calloc(stack).sType$Default()
                    .flags(VK12.VK_MEMORY_ALLOCATE_DEVICE_ADDRESS_BIT);
                alloc.pNext(flags.address());
            }
            LongBuffer pMem = stack.mallocLong(1);
            int err = VK10.vkAllocateMemory(device, alloc, null, pMem);
            if (err != VK10.VK_SUCCESS) {
                VK10.vkDestroyBuffer(device, buffer, null);
                throw new RtFailure("allocate_memory:" + what, "VkResult=" + err + " bytes=" + reqs.size());
            }
            long memory = pMem.get(0);
            err = VK10.vkBindBufferMemory(device, buffer, memory, 0);
            if (err != VK10.VK_SUCCESS) {
                VK10.vkDestroyBuffer(device, buffer, null);
                VK10.vkFreeMemory(device, memory, null);
                throw new RtFailure("bind_buffer_memory:" + what, "VkResult=" + err);
            }
            long address = 0L;
            if (addressable) {
                VkBufferDeviceAddressInfo addr = VkBufferDeviceAddressInfo.calloc(stack).sType$Default().buffer(buffer);
                address = VK12.vkGetBufferDeviceAddress(device, addr);
                if (address == 0L) {
                    throw new RtFailure("buffer_device_address:" + what, "address=0");
                }
            }
            long mapped = 0L;
            if (hostVisible) {
                PointerBuffer pData = stack.mallocPointer(1);
                err = VK10.vkMapMemory(device, memory, 0, VK10.VK_WHOLE_SIZE, 0, pData);
                if (err != VK10.VK_SUCCESS) {
                    VK10.vkDestroyBuffer(device, buffer, null);
                    VK10.vkFreeMemory(device, memory, null);
                    throw new RtFailure("map_memory:" + what, "VkResult=" + err);
                }
                mapped = pData.get(0);
            }
            reservedBytes += reqs.size();
            return new Buf(buffer, memory, reqs.size(), address, mapped, hostVisible);
        }
    }

    public void destroy(Buf buf) {
        if (buf.mapped != 0L) {
            VK10.vkUnmapMemory(device, buf.memory);
        }
        VK10.vkDestroyBuffer(device, buf.handle, null);
        VK10.vkFreeMemory(device, buf.memory, null);
        reservedBytes -= buf.size;
    }

    /** Device-local single-mip 2D image with a colour view; the caller transitions it (it is created UNDEFINED). */
    public Img createImage(String what, int width, int height, int format, int usage) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkImageCreateInfo info = VkImageCreateInfo.calloc(stack).sType$Default()
                .imageType(VK10.VK_IMAGE_TYPE_2D).format(format)
                .mipLevels(1).arrayLayers(1).samples(VK10.VK_SAMPLE_COUNT_1_BIT)
                .tiling(VK10.VK_IMAGE_TILING_OPTIMAL).usage(usage)
                .sharingMode(VK10.VK_SHARING_MODE_EXCLUSIVE).initialLayout(VK10.VK_IMAGE_LAYOUT_UNDEFINED);
            info.extent().width(width).height(height).depth(1);
            LongBuffer pImg = stack.mallocLong(1);
            RtFailure.check(VK10.vkCreateImage(device, info, null, pImg), "create_image:" + what);
            long image = pImg.get(0);

            VkMemoryRequirements reqs = VkMemoryRequirements.calloc(stack);
            VK10.vkGetImageMemoryRequirements(device, image, reqs);
            int type = findMemoryType(reqs.memoryTypeBits(), VK10.VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
            if (type < 0) {
                VK10.vkDestroyImage(device, image, null);
                throw new RtFailure("create_image:" + what, "no_memory_type");
            }
            VkMemoryAllocateInfo alloc = VkMemoryAllocateInfo.calloc(stack).sType$Default()
                .allocationSize(reqs.size()).memoryTypeIndex(type);
            LongBuffer pMem = stack.mallocLong(1);
            int err = VK10.vkAllocateMemory(device, alloc, null, pMem);
            if (err != VK10.VK_SUCCESS) {
                VK10.vkDestroyImage(device, image, null);
                throw new RtFailure("allocate_memory:" + what, "VkResult=" + err + " bytes=" + reqs.size());
            }
            long memory = pMem.get(0);
            err = VK10.vkBindImageMemory(device, image, memory, 0);
            if (err != VK10.VK_SUCCESS) {
                VK10.vkDestroyImage(device, image, null);
                VK10.vkFreeMemory(device, memory, null);
                throw new RtFailure("bind_image_memory:" + what, "VkResult=" + err);
            }
            VkImageViewCreateInfo viewInfo = VkImageViewCreateInfo.calloc(stack).sType$Default()
                .image(image).viewType(VK10.VK_IMAGE_VIEW_TYPE_2D).format(format);
            viewInfo.subresourceRange().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                .baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
            LongBuffer pView = stack.mallocLong(1);
            err = VK10.vkCreateImageView(device, viewInfo, null, pView);
            if (err != VK10.VK_SUCCESS) {
                VK10.vkDestroyImage(device, image, null);
                VK10.vkFreeMemory(device, memory, null);
                throw new RtFailure("create_image_view:" + what, "VkResult=" + err);
            }
            reservedBytes += reqs.size();
            return new Img(image, pView.get(0), memory, width, height, reqs.size());
        }
    }

    public void destroy(Img img) {
        VK10.vkDestroyImageView(device, img.view, null);
        VK10.vkDestroyImage(device, img.image, null);
        VK10.vkFreeMemory(device, img.memory, null);
        reservedBytes -= img.bytes;
    }

    public void close() {
        memProps.free();
    }

    /** Usage of buffers whose content the BLAS build reads. */
    public static final int USAGE_AS_INPUT = KHRAccelerationStructure.VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR
        | VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT;
}
