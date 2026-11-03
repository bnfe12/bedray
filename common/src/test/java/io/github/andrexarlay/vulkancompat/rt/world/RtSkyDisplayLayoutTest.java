package io.github.andrexarlay.vulkancompat.rt.world;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RtSkyDisplayLayoutTest {
    private static final Path SHADERS = Path.of("src/main/resources/shaders/slang");

    @Test void writesOnlyItsPrivateWordsAndPreservesTheCallersBufferState() {
        ByteBuffer slot = ByteBuffer.allocate(RtSkyDisplayLayout.PACKET_BYTES).order(ByteOrder.nativeOrder());
        for (int i = 0; i < slot.capacity(); ++i) slot.put(i, (byte) 0x5a);
        int flags = 0xab400000 | 5 | 16 | (2 << 8);
        slot.putInt(RtSkyDisplayLayout.FLAGS, flags);
        byte[] before = slot.array().clone();
        slot.position(7).limit(12).order(ByteOrder.BIG_ENDIAN);
        RtSkyDisplayLayout.write(slot, new float[] {0.2f, 0.4f, 0.8f}, 0.6f, true, 1024);
        assertEquals(7, slot.position());
        assertEquals(12, slot.limit());
        assertEquals(ByteOrder.BIG_ENDIAN, slot.order());
        ByteBuffer result = slot.duplicate().clear().order(ByteOrder.nativeOrder());
        assertEquals(1024, result.getInt(RtSkyDisplayLayout.ATLAS_WORD_COUNT));
        assertEquals(0.2f, result.getFloat(RtSkyDisplayLayout.COLOR_DAY));
        assertEquals(0.4f, result.getFloat(RtSkyDisplayLayout.COLOR_DAY + 4));
        assertEquals(0.8f, result.getFloat(RtSkyDisplayLayout.COLOR_DAY + 8));
        assertEquals(0.6f, result.getFloat(RtSkyDisplayLayout.COLOR_DAY + 12));
        assertEquals((flags & ~RtSkyDisplayLayout.VERSION_MASK) | RtSkyDisplayLayout.VERSION | RtSkyDisplayLayout.TONEMAPPED,
            result.getInt(RtSkyDisplayLayout.FLAGS));
        for (int i = 0; i < before.length; ++i) {
            if (inside(i, RtSkyDisplayLayout.ATLAS_WORD_COUNT, 4) || inside(i, RtSkyDisplayLayout.COLOR_DAY, 16)
                || inside(i, RtSkyDisplayLayout.FLAGS, 4)) continue;
            assertEquals(before[i], result.get(i), "unrelated frame byte " + i);
        }
        RtSkyDisplayLayout.write(slot, new float[] {0, 0, 0}, 0, false, 0);
        assertEquals(0, result.getInt(RtSkyDisplayLayout.FLAGS) & RtSkyDisplayLayout.TONEMAPPED);
    }

    @Test void rejectsMalformedPacketsBeforeAnyWrite() {
        ByteBuffer slot = ByteBuffer.allocate(RtSkyDisplayLayout.PACKET_BYTES);
        byte[] before = slot.array().clone();
        for (int words : new int[] {-1, 1, RtSkyDisplayLayout.ATLAS_HEADER_WORDS - 1})
            assertThrows(IllegalArgumentException.class, () -> RtSkyDisplayLayout.write(slot, new float[] {1, 1, 1}, 1, true, words));
        assertThrows(IllegalArgumentException.class, () -> RtSkyDisplayLayout.write(slot, null, 1, true, 48));
        assertThrows(IllegalArgumentException.class, () -> RtSkyDisplayLayout.write(slot, new float[] {Float.NaN, 1, 1}, 1, true, 48));
        assertThrows(IllegalArgumentException.class, () -> RtSkyDisplayLayout.write(slot, new float[] {1, 1, 1}, Float.POSITIVE_INFINITY, true, 48));
        assertThrows(IllegalArgumentException.class, () -> RtSkyDisplayLayout.write(ByteBuffer.allocate(575), new float[] {1, 1, 1}, 1, true, 48));
        assertThrows(IllegalArgumentException.class, () -> RtSkyDisplayLayout.write(slot.asReadOnlyBuffer(), new float[] {1, 1, 1}, 1, true, 48));
        assertArrayEquals(before, slot.array());
    }

    @Test void theNamedShaderPacketMatchesTheHostWithoutGrowingEitherPublicBlock() throws Exception {
        String packet = Files.readString(SHADERS.resolve("contracts/sky_display.slang"));
        Map<String, Integer> constants = Map.of(
            "SKY_DISPLAY_PACKET_BYTES", RtSkyDisplayLayout.PACKET_BYTES,
            "SKY_DISPLAY_ATLAS_WORD_COUNT_BYTE", RtSkyDisplayLayout.ATLAS_WORD_COUNT,
            "SKY_DISPLAY_COLOR_DAY_BYTE", RtSkyDisplayLayout.COLOR_DAY,
            "SKY_DISPLAY_FLAGS_BYTE", RtSkyDisplayLayout.FLAGS);
        constants.forEach((name, value) -> assertTrue(packet.contains(name + " = " + value + "u;"), name));
        assertEquals(RtStatsLayout.SLOT_STRIDE, RtSkyDisplayLayout.PACKET_BYTES);
        assertEquals(RtStatsLayout.SKY_TEX_FLAGS, RtSkyDisplayLayout.FLAGS);
        assertEquals(608, RtPathTraceSlangPass.FRAME_CONSTANTS_BYTES);
        assertEquals(112, RtPathTraceSlangPass.PUSH_BYTES);
        assertTrue(packet.contains("SKY_DISPLAY_VERSION = 0x01000000u;"));
        assertTrue(packet.contains("SKY_DISPLAY_TONEMAPPED = 1u << 10u;"));
        assertTrue(packet.indexOf("words < SKY_DISPLAY_PACKET_BYTES / 4u") < packet.indexOf("sky.skyTexFlags = slot["));
        assertTrue(packet.contains("sky.textureAddress == 0u"));
        assertTrue(packet.contains("sky.textureAddress > ~uint64_t(0) - uint64_t(sky.textureWords) * 4u"));
        String atlas = Files.readString(SHADERS.resolve("light/sky_textures.slang"));
        assertTrue(atlas.contains("slot >= SKY_TEXTURE_SLOTS"));
        assertTrue(atlas.contains("offset < SKY_TEXTURE_HEADER_WORDS || offset > sky.textureWords"));
        assertTrue(atlas.contains("width > (sky.textureWords - offset) / height"),
            "division after checking height avoids an overflowing width*height capacity test");
        assertTrue(atlas.contains("any(pixel >= header.yz)"));
    }

    private static boolean inside(int value, int start, int bytes) { return value >= start && value < start + bytes; }
}
