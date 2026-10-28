package io.github.andrexarlay.vulkancompat.rt.core;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceMemoryProperties;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/** FFM-only boundary to C++ buffer allocation and GPU-use retirement. */
public final class BedrayGpuBuffersNative implements AutoCloseable {
    public record Buffer(long generation, long serial, long handle, long size, long deviceAddress, int memoryFlags) { }
    private static final class Use {
        Buffer buffer;
        long generation, serial;
        Use(Buffer buffer) { this.buffer = buffer; }
    }
    private final BedrayCoreNative library;
    private final MethodHandle destroy, create, write, upload, retireUploads, beginUse, completeUse, retire, collect,
        deviceLost, releaseLost;
    private final Map<Long, Buffer> buffers = new LinkedHashMap<>();
    private final Map<Long, ArrayList<Use>> recordings = new LinkedHashMap<>();
    private long owner;

    public BedrayGpuBuffersNative(BedrayCoreNative library, VkPhysicalDevice physical, VkDevice device, long budget) {
        if (library == null || physical == null || device == null || budget <= 0 || !device.getCapabilities().Vulkan11)
            throw new IllegalArgumentException("native buffers require an enabled Vulkan 1.1 device and a budget");
        this.library = library;
        MethodHandle version = function("bedray_gpu_abi_version", FunctionDescriptor.of(JAVA_INT));
        MethodHandle info = function("bedray_gpu_abi_info", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
        MethodHandle createOwner = function("bedray_gpu_owner_create", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
        destroy = function("bedray_gpu_owner_destroy", FunctionDescriptor.of(JAVA_INT, ADDRESS));
        create = function("bedray_gpu_buffer_create", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG,
            JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS));
        write = function("bedray_gpu_buffer_write", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, JAVA_LONG,
            JAVA_LONG, ADDRESS, JAVA_LONG, ADDRESS));
        beginUse = function("bedray_gpu_use_begin", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, JAVA_LONG, ADDRESS));
        completeUse = function("bedray_gpu_use_complete", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, JAVA_LONG));
        retire = function("bedray_gpu_buffer_retire", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, JAVA_LONG));
        collect = function("bedray_gpu_collect", FunctionDescriptor.of(JAVA_INT, ADDRESS));
        upload = function("bedray_gpu_buffer_upload", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT,
            ADDRESS, JAVA_LONG, ADDRESS, ADDRESS, ADDRESS));
        retireUploads = function("bedray_gpu_uploads_retire", FunctionDescriptor.of(JAVA_INT, ADDRESS));
        deviceLost = function("bedray_gpu_device_lost", FunctionDescriptor.of(JAVA_INT, ADDRESS));
        releaseLost = function("bedray_gpu_release_after_device_loss", FunctionDescriptor.of(JAVA_INT, ADDRESS));
        try (Arena arena = Arena.ofConfined(); MemoryStack stack = MemoryStack.stackPush()) {
            if ((int) version.invokeExact() != 1) throw new IllegalStateException("unsupported native buffer ABI");
            MemorySegment metadata = arena.allocate(104, 4);
            check("abi_info", (int) info.invokeExact(metadata, 104));
            int[] expected = {1, 104, 264, 8, 0, 4, 8, 16, 24, 32, 40, 44, 48, 176,
                48, 8, 0, 8, 16, 24, 32, 40, 16, 8, 0, 8};
            for (int i = 0; i < expected.length; ++i) {
                if (metadata.get(JAVA_INT, i * 4L) != expected[i])
                    throw new IllegalStateException("native buffer ABI layout differs at metadata field " + i);
            }
            MemorySegment desc = arena.allocate(264, 8);
            desc.set(JAVA_INT, 0, 1); desc.set(JAVA_INT, 4, 264);
            desc.set(JAVA_LONG, 8, device.address()); desc.set(JAVA_LONG, 16, BedrayCoreNative.nextResourceGeneration());
            desc.set(JAVA_LONG, 24, budget);
            VkPhysicalDeviceProperties properties = VkPhysicalDeviceProperties.calloc(stack);
            VK10.vkGetPhysicalDeviceProperties(physical, properties);
            desc.set(JAVA_LONG, 32, properties.limits().nonCoherentAtomSize());
            desc.set(JAVA_INT, 40, 1); // enabled dedicated allocation; no device-address buffers here
            VkPhysicalDeviceMemoryProperties memory = VkPhysicalDeviceMemoryProperties.calloc(stack);
            VK10.vkGetPhysicalDeviceMemoryProperties(physical, memory);
            desc.set(JAVA_INT, 44, memory.memoryTypeCount());
            for (int i = 0; i < memory.memoryTypeCount(); ++i) desc.set(JAVA_INT, 48 + i * 4L, memory.memoryTypes(i).propertyFlags());
            var f = device.getCapabilities();
            long[] functions = {f.vkCreateBuffer, f.vkDestroyBuffer, f.vkGetBufferMemoryRequirements, f.vkAllocateMemory,
                f.vkFreeMemory, f.vkBindBufferMemory, f.vkMapMemory, f.vkUnmapMemory, f.vkFlushMappedMemoryRanges,
                f.vkInvalidateMappedMemoryRanges, 0};
            for (int i = 0; i < functions.length; ++i) desc.set(JAVA_LONG, 176 + i * 8L, functions[i]);
            MemorySegment out = arena.allocate(ADDRESS);
            check("owner_create", (int) createOwner.invokeExact(desc, out));
            owner = out.get(ADDRESS, 0).address();
            try { library.retainResourceOwner(owner); }
            catch (RuntimeException | Error e) {
                int ignored = (int) destroy.invokeExact(pointer()); owner = 0; throw e;
            }
        } catch (Throwable t) { throw failure("create native buffers", t); }
    }

    private MethodHandle function(String name, FunctionDescriptor descriptor) { return library.resourceFunction(name, descriptor); }
    private MemorySegment pointer() {
        if (owner == 0) throw new IllegalStateException("native buffer owner is closed");
        return MemorySegment.ofAddress(owner);
    }
    private static void check(String operation, int status) {
        if (status != 0) throw new IllegalStateException("native buffers " + operation + " failed: status=" + status);
    }
    private static RuntimeException failure(String operation, Throwable t) {
        if (t instanceof RuntimeException e) return e;
        if (t instanceof Error e) throw e;
        return new IllegalStateException(operation, t);
    }

    /** Creates a persistently mapped host-visible buffer; native code handles coherency. */
    public Buffer create(long size, int usage) {
        return create(size, usage, true);
    }

    public Buffer createDeviceLocal(long size, int usage) {
        return create(size, usage, false);
    }

    private Buffer create(long size, int usage, boolean mapped) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment out = arena.allocate(48, 8), vk = arena.allocate(JAVA_INT);
            int required = mapped ? VK10.VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT : VK10.VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT;
            int preferred = mapped ? VK10.VK_MEMORY_PROPERTY_HOST_COHERENT_BIT : VK10.VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT;
            int status = (int) create.invokeExact(pointer(), size, usage, required, preferred, mapped ? 1 : 0, out, vk);
            if (status != 0) throw new IllegalStateException("native buffer create failed: status=" + status + " VkResult=" + vk.get(JAVA_INT, 0));
            long generation = out.get(JAVA_LONG, 0), serial = out.get(JAVA_LONG, 8);
            try {
                Buffer result = readBuffer(out);
                buffers.put(result.serial, result); return result;
            } catch (RuntimeException | Error e) {
                check("create_rollback_retire", (int) retire.invokeExact(pointer(), generation, serial));
                buffers.remove(serial);
                check("create_rollback_collect", (int) collect.invokeExact(pointer()));
                throw e;
            }
        } catch (Throwable t) { throw failure("create buffer", t); }
    }

    private static Buffer readBuffer(MemorySegment out) {
        return new Buffer(out.get(JAVA_LONG, 0), out.get(JAVA_LONG, 8), out.get(JAVA_LONG, 16),
            out.get(JAVA_LONG, 24), out.get(JAVA_LONG, 32), out.get(JAVA_INT, 40));
    }

    /** Native pool upload, immutable until this recording completes or is discarded. */
    public Buffer upload(long commandBuffer, int usage, ByteBuffer data) {
        if (commandBuffer == 0 || data == null || !data.isDirect() || !data.hasRemaining())
            throw new IllegalArgumentException("recording and nonempty direct upload are required");
        ArrayList<Use> uses = recordings.computeIfAbsent(commandBuffer, ignored -> new ArrayList<>());
        uses.ensureCapacity(uses.size() + 1);
        Use use = new Use(null);
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment out = arena.allocate(48, 8), token = arena.allocate(16, 8), vk = arena.allocate(JAVA_INT);
            MemorySegment source = MemorySegment.ofBuffer(data);
            int status = (int) upload.invokeExact(pointer(), usage, source, source.byteSize(), out, token, vk);
            if (status != 0) throw new IllegalStateException("native upload failed: status=" + status + " VkResult=" + vk.get(JAVA_INT, 0));
            use.generation = token.get(JAVA_LONG, 0); use.serial = token.get(JAVA_LONG, 8);
            // Track the token before allocating the return record. Even an OOME
            // leaves a recoverable pinned use on the recording's discard path.
            uses.add(use);
            use.buffer = readBuffer(out);
            return use.buffer;
        } catch (Throwable t) { throw failure("upload buffer", t); }
    }

    public void write(Buffer buffer, long offset, ByteBuffer data) {
        if (buffer == null || !buffer.equals(buffers.get(buffer.serial)) || data == null || !data.isDirect() || !data.hasRemaining())
            throw new IllegalArgumentException("an owned buffer and nonempty direct data are required");
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment vk = arena.allocate(JAVA_INT);
            MemorySegment source = MemorySegment.ofBuffer(data);
            int status = (int) write.invokeExact(pointer(), buffer.generation, buffer.serial, offset, source, source.byteSize(), vk);
            if (status != 0) throw new IllegalStateException("native buffer write failed: status=" + status + " VkResult=" + vk.get(JAVA_INT, 0));
        } catch (Throwable t) { throw failure("write buffer", t); }
    }

    public void retain(long commandBuffer, Buffer buffer) {
        if (commandBuffer == 0 || buffer == null || !buffer.equals(buffers.get(buffer.serial)))
            throw new IllegalArgumentException("recording and owned buffer are required");
        ArrayList<Use> uses = recordings.computeIfAbsent(commandBuffer, ignored -> new ArrayList<>());
        for (Use use : uses) if (buffer.equals(use.buffer)) return;
        uses.ensureCapacity(uses.size() + 1);
        Use use = new Use(buffer);
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment out = arena.allocate(16, 8);
            check("use_begin", (int) beginUse.invokeExact(pointer(), buffer.generation, buffer.serial, out));
            use.generation = out.get(JAVA_LONG, 0); use.serial = out.get(JAVA_LONG, 8); uses.add(use);
        } catch (Throwable t) { throw failure("retain buffer", t); }
    }

    /** Caller proved the entire recording completed or was discarded before submission. */
    public void releaseRecording(long commandBuffer) {
        ArrayList<Use> uses = recordings.get(commandBuffer);
        if (uses == null) return;
        try {
            while (!uses.isEmpty()) {
                Use use = uses.getLast();
                check("use_complete", (int) completeUse.invokeExact(pointer(), use.generation, use.serial));
                uses.removeLast();
            }
            recordings.remove(commandBuffer); check("collect", (int) collect.invokeExact(pointer()));
        } catch (Throwable t) { throw failure("release buffer recording", t); }
    }

    public void retire(Buffer buffer) {
        if (buffer == null) return;
        if (!buffer.equals(buffers.get(buffer.serial))) throw new IllegalArgumentException("buffer does not belong to this owner");
        try {
            check("retire", (int) retire.invokeExact(pointer(), buffer.generation, buffer.serial));
            buffers.remove(buffer.serial); check("collect", (int) collect.invokeExact(pointer()));
        } catch (Throwable t) { throw failure("retire buffer", t); }
    }

    @Override public void close() {
        if (owner == 0) return;
        if (!recordings.isEmpty()) throw new IllegalStateException("native buffers still have recorded uses");
        for (Buffer buffer : List.copyOf(buffers.values())) retire(buffer);
        try {
            check("uploads_retire", (int) retireUploads.invokeExact(pointer()));
            check("collect", (int) collect.invokeExact(pointer()));
            long old = owner; check("owner_destroy", (int) destroy.invokeExact(pointer())); owner = 0;
            library.releaseResourceOwner(old);
        } catch (Throwable t) { throw failure("close native buffers", t); }
    }

    /** Only after observed device loss, quiescing all callers and forbidding future submissions. */
    public void closeAfterDeviceLoss() {
        if (owner == 0) return;
        try {
            check("device_lost", (int) deviceLost.invokeExact(pointer()));
            check("release_after_device_loss", (int) releaseLost.invokeExact(pointer()));
            long old = owner; check("owner_destroy", (int) destroy.invokeExact(pointer())); owner = 0;
            buffers.clear(); recordings.clear(); library.releaseResourceOwner(old);
        } catch (Throwable t) { throw failure("release lost-device buffers", t); }
    }
}
