package io.github.andrexarlay.vulkancompat.rt.world;

import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import com.mojang.renderpearl.backend.vulkan.VulkanUtils;
import com.mojang.renderpearl.backend.vulkan.checkpoints.CheckpointExtension;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;

/**
 * Diagnostic GPU checkpoints around each RT compute stage.
 * <p>
 * renderpearl only records checkpoints at render-pass boundaries, so a DEVICE_LOST inside the RT compute is reported with the
 * last render-pass label ("Bedray RT particles") instead of the failing dispatch. This owner records a checkpoint before every
 * compute stage through a storage registered in the device's {@link CheckpointExtension}; renderpearl's existing
 * {@code retrieveCheckpoints(true)} then resolves the markers in the crash report. renderpearl writes them either as NV
 * diagnostic checkpoints or as {@code VK_AMD_buffer_marker} stores, depending on what the device enables; either way they are
 * marker writes that do not change the rendered image.
 * <p>
 * The storage uses a private high id space so only this storage resolves its markers (renderpearl's per-encoder storages count
 * from zero); the id base is set reflectively and falls back to zero when the field is unreachable. A checkpoint never breaks the
 * frame: every entry point swallows its own failures.
 */
public final class RtComputeCheckpoints {
    private static final Logger LOGGER = LoggerFactory.getLogger("VKB-RT");
    private static final int FRAMES = 4;
    private static final int ID_BASE = 0x42440000;

    private static long deviceAddr;
    private static CheckpointExtension.CheckpointStorage storage;
    private static boolean disabled;

    private RtComputeCheckpoints() {
    }

    private static CheckpointExtension.CheckpointStorage storage(VulkanDevice device) {
        if (disabled || device == null) {
            return null;
        }
        long addr = device.vkDevice().address();
        if (storage == null || addr != deviceAddr) {
            try {
                storage = device.checkpointExtension().createStorage(device, device.graphicsQueue(), FRAMES);
                deviceAddr = addr;
                bumpIdBase(storage);
            } catch (RuntimeException | LinkageError e) {
                disabled = true;
                storage = null;
                LOGGER.warn("[VKB-RT] compute checkpoints unavailable: {}", e.toString());
            }
        }
        return storage;
    }

    private static void bumpIdBase(CheckpointExtension.CheckpointStorage s) {
        try {
            for (Class<?> c = s.getClass(); c != null; c = c.getSuperclass()) {
                try {
                    Field f = c.getDeclaredField("nextCheckpointId");
                    f.setAccessible(true);
                    f.setInt(s, ID_BASE);
                    return;
                } catch (NoSuchFieldException e) {
                    // walk up to AbstractCheckpointStorage
                }
            }
        } catch (RuntimeException | LinkageError | ReflectiveOperationException ignored) {
            // base stays zero: labels still resolve, only the id space is shared
        }
    }

    /** Marks the start of a compute stage on the GPU timeline; the last reached marker names the failing stage. */
    public static void mark(VulkanDevice device, VkCommandBuffer cmd, String stage) {
        CheckpointExtension.CheckpointStorage s = storage(device);
        if (s == null || cmd == null) {
            return;
        }
        try {
            s.recordCheckpoint(cmd, CheckpointExtension.CheckpointType.END_RENDER_PASS, () -> "Bedray RT " + stage);
        } catch (RuntimeException | LinkageError ignored) {
            // a diagnostic marker must never break the frame
        }
    }

    /** Formats the reached GPU checkpoints exactly like renderpearl's crash report; empty when unavailable. */
    public static String readback(VulkanDevice device) {
        if (device == null) {
            return "";
        }
        try {
            return VulkanUtils.formatCheckpoints(device.checkpointExtension().retrieveCheckpoints(true));
        } catch (RuntimeException | LinkageError e) {
            return "";
        }
    }
}