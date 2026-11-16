package io.github.andrexarlay.vulkancompat.rtx.hdr;

import io.github.andrexarlay.vulkanbridge.api.DeviceFeatureRegistry;
import io.github.andrexarlay.vulkanbridge.api.DeviceRequirement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Set;

/**
 * Optional device group {@code VK_EXT_hdr_metadata}: lets Bedray tell the display the mastering primaries and the peak
 * brightness of the HDR10 swapchain. Registered only while HDR10 output is requested; the engine silently drops an
 * optional group the device does not support, and HDR output then runs without metadata (the display uses its own limits).
 */
public final class HdrDeviceRequirements {
    private static final Logger LOGGER = LoggerFactory.getLogger("Bedray/HDR");

    public static final String ID = "VKB HDR metadata";

    private HdrDeviceRequirements() {}

    public static void register(DeviceFeatureRegistry registry) {
        if (!HdrSettings.wantsHdr10() || HdrOutput.blockedByFrameGeneration()) {
            return;
        }
        registry.register(new DeviceRequirement(ID, Set.of(HdrProbe.EXT_HDR_METADATA), List.of(), 0, true, null));
        LOGGER.info("[VKB-HDR] device group registered: {} ({})", ID, HdrProbe.EXT_HDR_METADATA);
    }
}
