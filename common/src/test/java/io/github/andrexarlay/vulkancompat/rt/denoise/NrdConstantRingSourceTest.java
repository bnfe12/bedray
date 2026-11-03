package io.github.andrexarlay.vulkancompat.rt.denoise;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P0-7: the NRD constant block used slot 0 on every dispatch call. A frame N+1
 * {@code vkCmdUpdateBuffer} could overwrite the constants that frame N's
 * in-flight command buffer was still reading, which is a device-lost/TDR
 * pattern rather than a visible glitch.
 * <p>
 * The bridge cannot be built here (NRD and Vulkan headers are external), so
 * this is the executable contract that pins the fix: the buffer is sized for
 * {@code frames_in_flight} rings, the upload base follows the same ring as the
 * per-frame descriptor pool, and the old slot-0 write cannot come back.
 */
class NrdConstantRingSourceTest {
    private static final Path SOURCE = Path.of("../native/nrd_bridge/src/vkb_nrd.cpp");
    private static final Path HEADER = Path.of("../native/nrd_bridge/include/vkb_nrd.h");

    @Test
    void constantBlocksFollowTheFramesInFlightRing() throws IOException {
        String source = Files.readString(SOURCE);

        assertTrue(source.contains("bci.size = d->cbStride * d->cbSlots * d->frames;"),
            "the buffer needs one group of dispatch slots per frame in flight");
        assertTrue(source.contains(
                "const uint64_t cbSlotBase = static_cast<uint64_t>(d->ring) * d->cbSlots;"),
            "the upload base must be the current ring group, the same index the descriptor pool uses");
        assertTrue(source.contains("const uint64_t slot = cbSlotBase + nextSlot++;"),
            "each dispatch writes its own slot inside the frame's group");
        assertFalse(source.contains("d->cbStride * nextSlot++"),
            "starting every dispatch call at slot 0 is the race this test exists for");

        int base = source.indexOf("const uint64_t cbSlotBase");
        int advance = source.indexOf("d->ring = (d->ring + 1) % d->frames;");
        assertTrue(base >= 0 && advance > base,
            "the ring advances after the upload; taking the base first keeps upload and pool in lockstep");

        assertTrue(source.contains("if (dispatchN > d->cbSlots)"),
            "the per-frame dispatch count must stay inside one slot group");
        assertTrue(Files.readString(HEADER).contains("descriptor-pool and constant-buffer ring size"),
            "the public create-info comment names both rings the host must keep in flight");
    }
}
