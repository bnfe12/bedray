package io.github.andrexarlay.vulkancompat.rt.world;

import io.github.andrexarlay.vulkanbridge.platform.Platforms;
import org.lwjgl.BufferUtils;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRRayTracingPipeline;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkAllocationCallbacks;
import org.lwjgl.vulkan.VkComputePipelineCreateInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkGraphicsPipelineCreateInfo;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;
import org.lwjgl.vulkan.VkPipelineCacheCreateInfo;
import org.lwjgl.vulkan.VkRayTracingPipelineCreateInfoKHR;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.function.LongFunction;

/** One device/driver-specific Vulkan pipeline cache shared by Bedray's pipeline builders. */
public final class RtVulkanPipelineCache {
    private static final Logger LOGGER = LoggerFactory.getLogger("Bedray/PipelineCache");
    private static final PipelineCacheDevices<VkDevice> ENTRIES = new PipelineCacheDevices<>();

    private RtVulkanPipelineCache() {
    }

    public static int createCompute(VkDevice device, VkComputePipelineCreateInfo.Buffer info,
                                    VkAllocationCallbacks allocator, LongBuffer pipelines) {
        return withCache(device, cache -> VK10.vkCreateComputePipelines(device, cache, info, allocator, pipelines));
    }

    public static int createGraphics(VkDevice device, VkGraphicsPipelineCreateInfo.Buffer info,
                                     VkAllocationCallbacks allocator, LongBuffer pipelines) {
        return withCache(device, cache -> VK10.vkCreateGraphicsPipelines(device, cache, info, allocator, pipelines));
    }

    /** A nonzero preferredCache remains externally synchronized by its own caller. */
    public static int createRayTracing(VkDevice device, long deferredOperation, long preferredCache,
                                       VkRayTracingPipelineCreateInfoKHR.Buffer info,
                                       VkAllocationCallbacks allocator, LongBuffer pipelines) {
        return withCache(device, cache -> KHRRayTracingPipeline.vkCreateRayTracingPipelinesKHR(device, deferredOperation,
            preferredCache != 0L ? preferredCache : cache, info, allocator, pipelines));
    }

    /** Borrows one cache under the same lock as Java builders, serialization and destruction. Never retain its handle. */
    public static <T> T withCache(VkDevice device, LongFunction<T> operation) {
        return ENTRIES.forUse(device, device.address(), owner -> create(device, owner)).use(operation);
    }

    /** Called at the head of VulkanDevice.close(), while the VkDevice is still valid. */
    public static void closeDevice(VkDevice device) {
        if (device == null) return;
        PipelineCacheOwner owner = ENTRIES.forClose(device, device.address());
        try {
            owner.close(handle -> {
                if (handle == VK10.VK_NULL_HANDLE) return;
                if (owner.file() != null) persist(device, handle, owner.file());
                VK10.vkDestroyPipelineCache(device, handle, null);
            });
        } catch (RuntimeException | Error failure) {
            LOGGER.error("[VKB-PIPELINE-CACHE] stage=close device=0x{} retained=true", Long.toHexString(device.address()), failure);
            throw failure;
        }
    }

    private static void create(VkDevice device, PipelineCacheOwner owner) {
        PipelineCacheIdentity identity;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPhysicalDeviceProperties properties = VkPhysicalDeviceProperties.calloc(stack);
            VK10.vkGetPhysicalDeviceProperties(device.getPhysicalDevice(), properties);
            byte[] uuid = new byte[VK10.VK_UUID_SIZE];
            properties.pipelineCacheUUID().get(0, uuid);
            identity = new PipelineCacheIdentity(properties.vendorID(), properties.deviceID(), properties.driverVersion(), uuid);
        }
        Path file = cacheFile(identity);
        owner.file(file);
        ByteBuffer initial = file == null ? null : load(file, identity);
        boolean loaded = initial != null;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer out = stack.callocLong(1);
            VkPipelineCacheCreateInfo info = VkPipelineCacheCreateInfo.calloc(stack).sType$Default();
            if (initial != null) info.pInitialData(initial);
            int result = VK10.vkCreatePipelineCache(device, info, null, out);
            if (result == VK10.VK_SUCCESS) owner.initialized(out.get(0));
            if (result == VK10.VK_ERROR_DEVICE_LOST)
                throw new RtFailure("pipeline_cache_create", "device lost; device=0x" + Long.toHexString(device.address()));
            if (result != VK10.VK_SUCCESS && initial != null) {
                LOGGER.warn("[VKB-PIPELINE-CACHE] stage=create source={} VkResult={} retry=empty", file, result);
                info = VkPipelineCacheCreateInfo.calloc(stack).sType$Default();
                out.put(0, VK10.VK_NULL_HANDLE);
                result = VK10.vkCreatePipelineCache(device, info, null, out);
                if (result == VK10.VK_SUCCESS) owner.initialized(out.get(0));
                loaded = false;
            }
            if (result == VK10.VK_ERROR_DEVICE_LOST)
                throw new RtFailure("pipeline_cache_create", "device lost during empty retry");
            if (result != VK10.VK_SUCCESS)
                LOGGER.warn("[VKB-PIPELINE-CACHE] stage=create VkResult={} mode=uncached device=0x{}", result, Long.toHexString(device.address()));
            else
                LOGGER.info("[VKB-PIPELINE-CACHE] stage=create source={} file={} device=0x{}",
                    loaded ? "disk" : "empty", file != null ? file : "memory-only", Long.toHexString(device.address()));
        }
    }

    private static Path cacheFile(PipelineCacheIdentity identity) {
        try {
            return Platforms.get().configDir().resolve("bedray").resolve("cache").resolve("vulkan").resolve(identity.filename());
        } catch (RuntimeException failure) {
            LOGGER.warn("[VKB-PIPELINE-CACHE] stage=locate mode=memory-only", failure);
            return null;
        }
    }

    private static ByteBuffer load(Path file, PipelineCacheIdentity identity) {
        try {
            long size = Files.size(file);
            if (size < PipelineCacheData.ENVELOPE_BYTES + PipelineCacheIdentity.HEADER_BYTES
                || size > (long) PipelineCacheIdentity.MAX_BYTES + PipelineCacheData.ENVELOPE_BYTES) {
                LOGGER.warn("[VKB-PIPELINE-CACHE] stage=load file={} reason=envelope_size bytes={}", file, size);
                return null;
            }
            byte[] bytes;
            try (var input = Files.newInputStream(file)) { bytes = input.readNBytes((int) size + 1); }
            if (bytes.length != size) {
                LOGGER.warn("[VKB-PIPELINE-CACHE] stage=load file={} reason=changed_size", file);
                return null;
            }
            ByteBuffer payload = PipelineCacheData.decode(bytes, identity);
            ByteBuffer data = BufferUtils.createByteBuffer(payload.remaining());
            data.put(payload).flip();
            return data;
        } catch (java.nio.file.NoSuchFileException absent) {
            return null;
        } catch (IOException | RuntimeException failure) {
            LOGGER.warn("[VKB-PIPELINE-CACHE] stage=load file={} mode=empty reason={}", file, failure.getMessage(), failure);
            return null;
        }
    }

    private static void persist(VkDevice device, long handle, Path file) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer size = stack.mallocPointer(1); // size_t
            int result = VK10.vkGetPipelineCacheData(device, handle, size, null);
            if (result != VK10.VK_SUCCESS && result != VK10.VK_INCOMPLETE) {
                LOGGER.warn("[Bedray] could not read Vulkan pipeline cache (error {})", result);
                return;
            }
            long capacity = size.get(0);
            for (int attempt = 0; attempt < 4 && capacity > 0 && capacity <= PipelineCacheIdentity.MAX_BYTES; attempt++) {
                ByteBuffer data = BufferUtils.createByteBuffer((int) capacity);
                size.put(0, capacity);
                result = VK10.vkGetPipelineCacheData(device, handle, size, data);
                long written = size.get(0);
                if (result == VK10.VK_INCOMPLETE) {
                    capacity = Math.max(capacity * 2, written + 1);
                    continue;
                }
                if (result != VK10.VK_SUCCESS || written < 0 || written > data.capacity()) {
                    LOGGER.warn("[Bedray] could not serialize Vulkan pipeline cache (error {})", result);
                    return;
                }
                data.limit((int) written);
                if (writeAtomically(file, data))
                    LOGGER.info("[VKB-PIPELINE-CACHE] stage=save file={} bytes={}", file, written);
                return;
            }
            if (capacity > 0) {
                LOGGER.warn("[Bedray] Vulkan pipeline cache did not fit the serialization limit; skipped saving");
            }
        } catch (RuntimeException e) {
            LOGGER.warn("[Bedray] could not save Vulkan pipeline cache: {}", e.toString());
        }
    }

    private static boolean writeAtomically(Path target, ByteBuffer data) {
        Path temporary = null;
        try {
            Files.createDirectories(target.getParent());
            temporary = Files.createTempFile(target.getParent(), target.getFileName().toString(), ".tmp");
            byte[] bytes = PipelineCacheData.encode(data);
            Files.write(temporary, bytes);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("[VKB-PIPELINE-CACHE] stage=save file={} retained=previous_file", target, e);
            return false;
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                }
            }
        }
    }

}
