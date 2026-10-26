package io.github.andrexarlay.vulkancompat.rt.world;

import io.github.andrexarlay.vulkancompat.rt.sodium.RtGeometryGate;
import org.lwjgl.vulkan.VK10;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Observation of VK_ERROR_DEVICE_LOST (the driver reset the device: TDR, hang, GPU removed). The RT mod
 * records into command buffers that the bridge submits, so within the mod a loss surfaces synchronously
 * only through mod-owned swapchain calls (Fsr3FrameGen acquire/present) and through {@link RtFailure}
 * checks of explicit Vulkan results. A trip marks the session gate (RtGeometryGate.markFailed), so the RT
 * frame renderer stops recording at the next frame head and the log attributes the failure instead of
 * leaving a cascade of broken submissions.
 */
public final class RtDeviceLost {
    private static final Logger LOGGER = LoggerFactory.getLogger("VKB-RT");
    private static volatile boolean logged;

    private RtDeviceLost() {
    }

    /** One-time attribution of a device-lost VkResult; other results are ignored. */
    public static void check(int vkResult, String where) {
        if (vkResult != VK10.VK_ERROR_DEVICE_LOST || logged) {
            return;
        }
        logged = true;
        LOGGER.error("[VKB-RT] FAILED stage=device_lost result=VK_ERROR_DEVICE_LOST where={} (RT disabled for the session)", where);
        RtGeometryGate.markFailed("device_lost", where);
    }

    static void resetForTests() {
        logged = false;
    }
}
