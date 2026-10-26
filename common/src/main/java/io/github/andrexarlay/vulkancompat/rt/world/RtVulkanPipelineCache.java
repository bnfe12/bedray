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
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** One device/driver-specific Vulkan pipeline cache shared by Bedray's pipeline builders. */
public final class RtVulkanPipelineCache {
    private static final Logger LOGGER = LoggerFactory.getLogger("Bedray/PipelineCache");
    private static final Map<Long, Entry> ENTRIES = new ConcurrentHashMap<>();

    private RtVulkanPipelineCache() {
    }

    public static int createCompute(VkDevice device, VkComputePipelineCreateInfo.Buffer info,
                                    VkAllocationCallbacks allocator, LongBuffer pipelines) {
        Entry entry = entry(device);
        synchronized (entry.lock) {
            return VK10.vkCreateComputePipelines(device, entry.handle, info, allocator, pipelines);
        }
    }

    public static int createGraphics(VkDevice device, VkGraphicsPipelineCreateInfo.Buffer info,
                                     VkAllocationCallbacks allocator, LongBuffer pipelines) {
        Entry entry = entry(device);
        synchronized (entry.lock) {
            return VK10.vkCreateGraphicsPipelines(device, entry.handle, info, allocator, pipelines);
        }
    }

    public static int createRayTracing(VkDevice device, long deferredOperation, long preferredCache,
                                       VkRayTracingPipelineCreateInfoKHR.Buffer info,
                                       VkAllocationCallbacks allocator, LongBuffer pipelines) {
        Entry entry = entry(device);
        long cache = preferredCache != VK10.VK_NULL_HANDLE ? preferredCache : entry.handle;
        synchronized (entry.lock) {
            return KHRRayTracingPipeline.vkCreateRayTracingPipelinesKHR(device, deferredOperation, cache, info, allocator, pipelines);
        }
    }

    /** Called at the head of VulkanDevice.close(), while the VkDevice is still valid. */
    public static void closeDevice(VkDevice device) {
        if (device == null) {
            return;
        }
        Entry entry = ENTRIES.remove(device.address());
        if (entry == null || entry.handle == VK10.VK_NULL_HANDLE) {
            return;
        }
        synchronized (entry.lock) {
            if (entry.file != null) {
                persist(device, entry);
            }
            VK10.vkDestroyPipelineCache(device, entry.handle, null);
        }
    }

    private static Entry entry(VkDevice device) {
        return ENTRIES.computeIfAbsent(device.address(), ignored -> create(device));
    }

    private static Entry create(VkDevice device) {
        Path file = cacheFile(device);
        ByteBuffer initial = file == null ? null : load(file);
        long handle = VK10.VK_NULL_HANDLE;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer out = stack.mallocLong(1);
            VkPipelineCacheCreateInfo info = VkPipelineCacheCreateInfo.calloc(stack).sType$Default();
            if (initial != null) {
                info.pInitialData(initial);
            }
            int result = VK10.vkCreatePipelineCache(device, info, null, out);
            // Vulkan has no "invalid cache data" result: incompatible data must be ignored by the driver. A failure with
            // initial data therefore means corrupt data or a driver that rejects it; retry once with an empty cache.
            if (result != VK10.VK_SUCCESS && initial != null) {
                LOGGER.warn("[Bedray] ignored stored pipeline cache {} (Vulkan error {})", file.getFileName(), result);
                info = VkPipelineCacheCreateInfo.calloc(stack).sType$Default();
                out.put(0, VK10.VK_NULL_HANDLE);
                result = VK10.vkCreatePipelineCache(device, info, null, out);
            }
            if (result == VK10.VK_SUCCESS) {
                handle = out.get(0);
            } else {
                LOGGER.warn("[Bedray] Vulkan pipeline cache unavailable (error {}); continuing without the cache", result);
            }
        } catch (RuntimeException e) {
            LOGGER.warn("[Bedray] could not create Vulkan pipeline cache; continuing without it: {}",
                io.github.andrexarlay.vulkanbridge.platform.LogPaths.show(e.toString()));
        }
        if (handle != VK10.VK_NULL_HANDLE) {
            LOGGER.info("[Bedray] pipeline cache={} source={}", initial == null ? "empty" : "loaded", file == null ? "memory-only" : file.getFileName());
        }
        return new Entry(handle, file);
    }

    private static Path cacheFile(VkDevice device) {
        Path configDir;
        try {
            configDir = Platforms.get().configDir();
        } catch (RuntimeException e) {
            return null;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPhysicalDeviceProperties properties = VkPhysicalDeviceProperties.calloc(stack);
            VK10.vkGetPhysicalDeviceProperties(device.getPhysicalDevice(), properties);
            ByteBuffer uuid = properties.pipelineCacheUUID();
            byte[] uuidBytes = new byte[VK10.VK_UUID_SIZE];
            uuid.get(0, uuidBytes);
            String key = String.format(java.util.Locale.ROOT, "v%08x-d%08x-r%08x-%s",
                properties.vendorID(), properties.deviceID(), properties.driverVersion(), HexFormat.of().formatHex(uuidBytes));
            return configDir.resolve("bedray").resolve("cache").resolve("vulkan").resolve(key + ".bin");
        } catch (RuntimeException e) {
            LOGGER.warn("[Bedray] could not identify the Vulkan device for pipeline caching: {}",
                io.github.andrexarlay.vulkanbridge.platform.LogPaths.show(e.toString()));
            return null;
        }
    }

    private static ByteBuffer load(Path file) {
        try {
            long size = Files.size(file);
            if (size <= 0 || size > 256L * 1024L * 1024L) {
                return null;
            }
            byte[] bytes = Files.readAllBytes(file);
            ByteBuffer data = BufferUtils.createByteBuffer(bytes.length);
            data.put(bytes).flip();
            return data;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static void persist(VkDevice device, Entry entry) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer size = stack.mallocPointer(1); // size_t
            int result = VK10.vkGetPipelineCacheData(device, entry.handle, size, null);
            if (result != VK10.VK_SUCCESS && result != VK10.VK_INCOMPLETE) {
                LOGGER.warn("[Bedray] could not read Vulkan pipeline cache (error {})", result);
                return;
            }
            long capacity = size.get(0);
            for (int attempt = 0; attempt < 4 && capacity > 0 && capacity <= 256L * 1024L * 1024L; attempt++) {
                ByteBuffer data = BufferUtils.createByteBuffer((int) capacity);
                size.put(0, capacity);
                result = VK10.vkGetPipelineCacheData(device, entry.handle, size, data);
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
                writeAtomically(entry.file, data);
                LOGGER.info("[Bedray] pipeline cache saved: {} bytes", written);
                return;
            }
            if (capacity > 0) {
                LOGGER.warn("[Bedray] Vulkan pipeline cache did not fit the serialization limit; skipped saving");
            }
        } catch (RuntimeException e) {
            LOGGER.warn("[Bedray] could not save Vulkan pipeline cache: {}",
                io.github.andrexarlay.vulkanbridge.platform.LogPaths.show(e.toString()));
        }
    }

    private static void writeAtomically(Path target, ByteBuffer data) {
        Path temporary = null;
        try {
            Files.createDirectories(target.getParent());
            temporary = Files.createTempFile(target.getParent(), target.getFileName().toString(), ".tmp");
            ByteBuffer copy = data.duplicate();
            byte[] bytes = new byte[copy.remaining()];
            copy.get(bytes);
            Files.write(temporary, bytes);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("[Bedray] could not write Vulkan pipeline cache: {}",
                io.github.andrexarlay.vulkanbridge.platform.LogPaths.show(e.toString()));
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                }
            }
        }
    }

    private static final class Entry {
        private final Object lock = new Object();
        private final long handle;
        private final Path file;

        private Entry(long handle, Path file) {
            this.handle = handle;
            this.file = file;
        }
    }
}
