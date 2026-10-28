package io.github.andrexarlay.vulkancompat.rt.core;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceMemoryProperties;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * Minecraft-side FFM boundary to the native image owner. Allocation, memory,
 * view lifetime and retirement live in C++; this class only supplies the game's
 * device information and forwards proven recording completion. It issues no
 * Vulkan allocation, destruction, submission or wait commands.
 */
public final class BedrayGpuImagesNative implements AutoCloseable {
    private static final int ABI = 1;
    private static final int ABI_INFO_BYTES = 96;
    private static final int OK = 0;

    public record Image(long generation, long serial, long image, long view,
                        int width, int height, int format, long allocationBytes) { }
    private static final class Use {
        final Image image;
        long generation, serial;
        Use(Image image) { this.image = image; }
    }
    private record Layout(long size, long alignment, int[] offsets) { }

    private final BedrayCoreNative library;
    private final MethodHandle destroy, createImage, beginUse, completeUse, retire, collect, deviceLost, releaseLost;
    private final Layout imageLayout;
    private final Map<Long, ArrayList<Use>> recordings = new LinkedHashMap<>();
    private final Map<Long, Image> images = new LinkedHashMap<>();
    private long owner;

    public BedrayGpuImagesNative(BedrayCoreNative library, VkPhysicalDevice physical, VkDevice device, long budgetBytes) {
        if (library == null || physical == null || device == null || budgetBytes <= 0) {
            throw new IllegalArgumentException("native image owner needs a device and a positive budget");
        }
        if (!device.getCapabilities().Vulkan11) {
            throw new IllegalArgumentException("native image owner requires enabled Vulkan 1.1 dedicated allocation");
        }
        this.library = library;
        MethodHandle version = function("bedray_gpu_image_abi_version", FunctionDescriptor.of(JAVA_INT));
        MethodHandle info = function("bedray_gpu_image_abi_info", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
        MethodHandle createOwner = function("bedray_gpu_image_owner_create", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
        destroy = function("bedray_gpu_image_owner_destroy", FunctionDescriptor.of(JAVA_INT, ADDRESS));
        createImage = function("bedray_gpu_image_create", FunctionDescriptor.of(JAVA_INT, ADDRESS,
            JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS));
        beginUse = function("bedray_gpu_image_use_begin", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, JAVA_LONG, ADDRESS));
        completeUse = function("bedray_gpu_image_use_complete", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, JAVA_LONG));
        retire = function("bedray_gpu_image_retire", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, JAVA_LONG));
        collect = function("bedray_gpu_image_collect", FunctionDescriptor.of(JAVA_INT, ADDRESS));
        deviceLost = function("bedray_gpu_image_device_lost", FunctionDescriptor.of(JAVA_INT, ADDRESS));
        releaseLost = function("bedray_gpu_image_release_after_device_loss", FunctionDescriptor.of(JAVA_INT, ADDRESS));

        try (Arena arena = Arena.ofConfined(); MemoryStack stack = MemoryStack.stackPush()) {
            int actualVersion = (int) version.invokeExact();
            if (actualVersion != ABI) throw new IllegalStateException("native image ABI " + actualVersion + " != " + ABI);
            MemorySegment metadata = arena.allocate(ABI_INFO_BYTES, 4);
            check("abi_info", (int) info.invokeExact(metadata, ABI_INFO_BYTES));
            require(metadata.get(JAVA_INT, 0) == ABI && metadata.get(JAVA_INT, 4) == ABI_INFO_BYTES, "image ABI info size/version");
            Layout ownerLayout = readLayout(metadata, 8, 10);
            imageLayout = readLayout(metadata, 56, 8);
            // Native natural layout is verified before any owner or Vulkan object
            // is created. Every field used below participates in the check.
            requireLayout(ownerLayout, 248, 8, new int[]{0, 4, 8, 16, 24, 32, 40, 44, 48, 176});
            requireLayout(imageLayout, 56, 8, new int[]{0, 8, 16, 24, 32, 40, 44, 48});
            // Both image and buffer owners use BedrayGpuUseToken. Validate its
            // canonical ABI metadata instead of assuming its natural layout.
            MethodHandle tokenInfo = function("bedray_gpu_abi_info", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
            MemorySegment shared = arena.allocate(104, 4);
            check("shared_token_abi", (int) tokenInfo.invokeExact(shared, 104));
            require(shared.get(JAVA_INT, 0) == 1 && shared.get(JAVA_INT, 4) == 104, "shared GPU ABI version/size");
            requireLayout(readLayout(shared, 88, 2), 16, 8, new int[]{0, 8});
            MemorySegment desc = arena.allocate(ownerLayout.size, ownerLayout.alignment);
            int[] at = ownerLayout.offsets;
            desc.set(JAVA_INT, at[0], ABI); desc.set(JAVA_INT, at[1], (int) ownerLayout.size);
            desc.set(JAVA_LONG, at[2], physical.address()); desc.set(JAVA_LONG, at[3], device.address());
            desc.set(JAVA_LONG, at[4], BedrayCoreNative.nextResourceGeneration()); desc.set(JAVA_LONG, at[5], budgetBytes);
            desc.set(JAVA_INT, at[6], 1); // enabled core-1.1 dedicated allocation
            VkPhysicalDeviceMemoryProperties properties = VkPhysicalDeviceMemoryProperties.calloc(stack);
            VK10.vkGetPhysicalDeviceMemoryProperties(physical, properties);
            desc.set(JAVA_INT, at[7], properties.memoryTypeCount());
            for (int i = 0; i < properties.memoryTypeCount(); ++i) {
                desc.set(JAVA_INT, at[8] + i * 4L, properties.memoryTypes(i).propertyFlags());
            }
            var functions = device.getCapabilities();
            long[] addresses = {functions.vkCreateImage, functions.vkDestroyImage, functions.vkGetImageMemoryRequirements,
                functions.vkAllocateMemory, functions.vkFreeMemory, functions.vkBindImageMemory,
                functions.vkCreateImageView, functions.vkDestroyImageView, physical.getCapabilities().vkGetPhysicalDeviceImageFormatProperties};
            for (int i = 0; i < addresses.length; ++i) desc.set(JAVA_LONG, at[9] + i * 8L, addresses[i]);
            MemorySegment out = arena.allocate(ADDRESS);
            check("owner_create", (int) createOwner.invokeExact(desc, out));
            owner = out.get(ADDRESS, 0).address();
            try {
                library.retainResourceOwner(owner);
            } catch (RuntimeException | Error e) {
                int ignored = (int) destroy.invokeExact(pointer());
                owner = 0;
                throw e;
            }
        } catch (Throwable t) {
            throw failure("create native image owner", t);
        }
    }

    private MethodHandle function(String name, FunctionDescriptor descriptor) {
        return library.resourceFunction(name, descriptor);
    }
    private MemorySegment pointer() {
        if (owner == 0) throw new IllegalStateException("native image owner is closed");
        return MemorySegment.ofAddress(owner);
    }
    private static Layout readLayout(MemorySegment info, long offset, int fields) {
        int[] offsets = new int[fields];
        for (int i = 0; i < fields; ++i) offsets[i] = info.get(JAVA_INT, offset + 8 + i * 4L);
        return new Layout(info.get(JAVA_INT, offset), info.get(JAVA_INT, offset + 4), offsets);
    }
    private static void requireLayout(Layout layout, int size, int alignment, int[] offsets) {
        require(layout.size == size && layout.alignment == alignment && java.util.Arrays.equals(layout.offsets, offsets),
            "native image structure layout differs from Java");
    }
    private static void require(boolean condition, String text) {
        if (!condition) throw new IllegalStateException(text);
    }
    private static void check(String operation, int status) {
        if (status != OK) throw new IllegalStateException("native images " + operation + " failed: status=" + status);
    }
    private static RuntimeException failure(String operation, Throwable t) {
        if (t instanceof RuntimeException e) return e;
        if (t instanceof Error e) throw e;
        return new IllegalStateException(operation, t);
    }

    public Image create(int width, int height, int format, int usage) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment out = arena.allocate(imageLayout.size, imageLayout.alignment);
            MemorySegment vk = arena.allocate(JAVA_INT);
            int status = (int) createImage.invokeExact(pointer(), width, height, format, usage,
                VK10.VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, 0, out, vk);
            if (status != OK) throw new IllegalStateException("native image create failed: status=" + status + " VkResult=" + vk.get(JAVA_INT, 0));
            int[] at = imageLayout.offsets;
            long generation = out.get(JAVA_LONG, at[0]), serial = out.get(JAVA_LONG, at[1]);
            try {
                Image result = new Image(generation, serial,
                    out.get(JAVA_LONG, at[2]), out.get(JAVA_LONG, at[3]), out.get(JAVA_INT, at[6]),
                    out.get(JAVA_INT, at[7]), out.get(JAVA_INT, at[5]), out.get(JAVA_LONG, at[4]));
                images.put(result.serial, result);
                return result;
            } catch (RuntimeException | Error e) {
                check("create_rollback_retire", (int) retire.invokeExact(pointer(), generation, serial));
                images.remove(serial);
                check("create_rollback_collect", (int) collect.invokeExact(pointer()));
                throw e;
            }
        } catch (Throwable t) { throw failure("create image", t); }
    }

    public void retain(long commandBuffer, Image image) {
        if (commandBuffer == 0 || image == null || !image.equals(images.get(image.serial))) {
            throw new IllegalArgumentException("recording and owned image are required");
        }
        ArrayList<Use> uses = recordings.computeIfAbsent(commandBuffer, ignored -> new ArrayList<>());
        for (Use use : uses) if (use.image.equals(image)) return;
        // Reserve Java storage before the native use so an allocation failure
        // cannot leave a native token that the host has lost track of.
        uses.ensureCapacity(uses.size() + 1);
        Use use = new Use(image);
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment out = arena.allocate(16, 8);
            check("use_begin", (int) beginUse.invokeExact(pointer(), image.generation, image.serial, out));
            use.generation = out.get(JAVA_LONG, 0);
            use.serial = out.get(JAVA_LONG, 8);
            uses.add(use);
        } catch (Throwable t) { throw failure("retain image", t); }
    }

    /** Caller has proven completion or discarded the entire unsubmitted recording. */
    public void releaseRecording(long commandBuffer) {
        List<Use> uses = recordings.get(commandBuffer);
        if (uses == null) return;
        try {
            while (!uses.isEmpty()) {
                Use use = uses.getLast();
                check("use_complete", (int) completeUse.invokeExact(pointer(), use.generation, use.serial));
                uses.removeLast();
            }
            recordings.remove(commandBuffer);
            check("collect", (int) collect.invokeExact(pointer()));
        } catch (Throwable t) { throw failure("release image recording", t); }
    }

    public void retire(Image image) {
        if (image == null) return;
        if (!image.equals(images.get(image.serial))) throw new IllegalArgumentException("image does not belong to this owner");
        try {
            check("retire", (int) retire.invokeExact(pointer(), image.generation, image.serial));
            images.remove(image.serial);
            check("collect", (int) collect.invokeExact(pointer()));
        } catch (Throwable t) { throw failure("retire image", t); }
    }

    @Override public void close() {
        if (owner == 0) return;
        if (!recordings.isEmpty()) throw new IllegalStateException("native images are still referenced by recordings");
        for (Image image : List.copyOf(images.values())) retire(image);
        try {
            long old = owner;
            check("owner_destroy", (int) destroy.invokeExact(pointer()));
            owner = 0;
            library.releaseResourceOwner(old);
        } catch (Throwable t) { throw failure("close native images", t); }
    }

    /** Only after observed device loss, quiescing all callers and forbidding future submissions. */
    public void closeAfterDeviceLoss() {
        if (owner == 0) return;
        try {
            check("device_lost", (int) deviceLost.invokeExact(pointer()));
            check("release_after_device_loss", (int) releaseLost.invokeExact(pointer()));
            long old = owner; check("owner_destroy", (int) destroy.invokeExact(pointer())); owner = 0;
            images.clear(); recordings.clear(); library.releaseResourceOwner(old);
        } catch (Throwable t) { throw failure("release lost-device images", t); }
    }
}
