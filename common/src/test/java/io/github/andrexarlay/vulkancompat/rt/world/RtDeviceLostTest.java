package io.github.andrexarlay.vulkancompat.rt.world;

import io.github.andrexarlay.vulkancompat.rt.sodium.RtGeometryGate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.vulkan.VK10;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Device-lost observation (RtDeviceLost) must mark the session gate exactly once with an attributable
 * result, ignore other VkResults, and RtFailure.check must name VK_ERROR_DEVICE_LOST instead of a bare
 * result number (the failure class of the 2026-10 audit: a driver reset used to leave no attribution).
 */
public class RtDeviceLostTest {

    @AfterEach
    public void restore() {
        RtGeometryGate.clearFailure();
        RtDeviceLost.resetForTests();
    }

    @Test
    public void deviceLostMarksGateOnce() {
        RtGeometryGate.clearFailure();
        RtDeviceLost.check(VK10.VK_ERROR_DEVICE_LOST, "fg_present");
        assertNotNull(RtGeometryGate.failure(), "device lost must disable RT for the session");
        assertTrue(RtGeometryGate.failure().contains("stage=device_lost"), RtGeometryGate.failure());
        assertTrue(RtGeometryGate.failure().contains("fg_present"), RtGeometryGate.failure());
        RtDeviceLost.check(VK10.VK_ERROR_DEVICE_LOST, "fg_present_again");
        assertTrue(RtGeometryGate.failure().contains("fg_present"), "first observation wins, no repeat log");
    }

    @Test
    public void otherResultsAreIgnored() {
        RtGeometryGate.clearFailure();
        RtDeviceLost.check(VK10.VK_SUCCESS, "ok");
        RtDeviceLost.check(org.lwjgl.vulkan.KHRSwapchain.VK_ERROR_OUT_OF_DATE_KHR, "out_of_date");
        RtDeviceLost.check(org.lwjgl.vulkan.KHRSwapchain.VK_SUBOPTIMAL_KHR, "suboptimal");
        assertNull(RtGeometryGate.failure(), "only VK_ERROR_DEVICE_LOST trips the gate");
    }

    @Test
    public void rtFailureCheckNamesDeviceLost() {
        RtFailure lost = assertThrows(RtFailure.class, () -> RtFailure.check(VK10.VK_ERROR_DEVICE_LOST, "end_command_buffer"));
        assertEquals("VK_ERROR_DEVICE_LOST", lost.result());
        assertEquals("end_command_buffer", lost.stage());
        RtFailure other = assertThrows(RtFailure.class, () -> RtFailure.check(-13, "stage_x"));
        assertEquals("VkResult=-13", other.result());
    }
}
