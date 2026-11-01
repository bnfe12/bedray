package io.github.andrexarlay.vulkancompat.rt.world;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Private versioned display-sky packet carried by the existing 576-byte frame slot at binding 37. */
public final class RtSkyDisplayLayout {
    public static final int PACKET_BYTES = 576;
    /** Unused std430 padding before the previous projection, which starts at byte 80. */
    public static final int ATLAS_WORD_COUNT = 72;
    /** Sixteen bytes vacated by the deleted ray renderer's two reservoir addresses. */
    public static final int COLOR_DAY = RtStatsLayout.SKY_DISPLAY_COLOR_RG;
    public static final int FLAGS = 376;
    public static final int VERSION_MASK = 0xff000000;
    public static final int VERSION = 0x01000000;
    public static final int TONEMAPPED = 1 << 10;
    public static final int ATLAS_HEADER_WORDS = 12 * 4;

    private RtSkyDisplayLayout() {}

    /** Absolute writes preserve the caller's position, limit and byte order. The remaining sky fields were written by RtSkyState. */
    public static void write(ByteBuffer slot, float[] rawSkyColor, float day, boolean tonemapped, int atlasWordCount) {
        if (slot == null || slot.capacity() < PACKET_BYTES || slot.isReadOnly())
            throw new IllegalArgumentException("display sky needs a writable whole frame slot");
        if (rawSkyColor == null || rawSkyColor.length < 3 || !Float.isFinite(rawSkyColor[0])
            || !Float.isFinite(rawSkyColor[1]) || !Float.isFinite(rawSkyColor[2]) || !Float.isFinite(day))
            throw new IllegalArgumentException("display sky colour and day factor must be finite");
        if (atlasWordCount != 0 && atlasWordCount < ATLAS_HEADER_WORDS)
            throw new IllegalArgumentException("display sky atlas is shorter than its header");
        ByteBuffer packet = slot.duplicate().clear().order(ByteOrder.nativeOrder());
        packet.putInt(ATLAS_WORD_COUNT, atlasWordCount);
        packet.putFloat(COLOR_DAY, rawSkyColor[0]).putFloat(COLOR_DAY + 4, rawSkyColor[1])
            .putFloat(COLOR_DAY + 8, rawSkyColor[2]).putFloat(COLOR_DAY + 12, day);
        int flags = packet.getInt(FLAGS) & ~(VERSION_MASK | TONEMAPPED);
        packet.putInt(FLAGS, flags | VERSION | (tonemapped ? TONEMAPPED : 0));
    }
}
