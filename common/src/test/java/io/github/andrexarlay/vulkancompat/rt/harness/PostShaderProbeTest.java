package io.github.andrexarlay.vulkancompat.rt.harness;

import io.github.andrexarlay.vulkancompat.rt.world.RtMemory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.vulkan.VK10.*;

/** L2 harness smoke test: device creation + image upload/readback round-trip (no shader). Gated like rtProbe. */
class PostShaderProbeTest {
    @Test
    @EnabledIfSystemProperty(named = "bedray.rt.postProbe", matches = "true")
    void uploadReadbackRoundTrip() {
        try (PostShaderProbe probe = new PostShaderProbe()) {
            int w = 16, h = 16;
            RtMemory.Img img = probe.memory.createImage("smoke", w, h, VK_FORMAT_R8G8B8A8_UNORM,
                VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT);
            try {
                byte[] data = new byte[w * h * 4];
                for (int i = 0; i < data.length; i++) data[i] = (byte) (i * 37 + 11);
                probe.upload(img, data, 4);
                byte[] back = probe.readback(img, 4);
                assertArrayEquals(data, back, "upload/readback must round-trip exactly");
                System.out.printf("[BEDRAY-POST-PROBE] device=%s round-trip ok%n", probe.deviceName);
            } finally {
                probe.memory.destroy(img);
            }
        }
    }
}