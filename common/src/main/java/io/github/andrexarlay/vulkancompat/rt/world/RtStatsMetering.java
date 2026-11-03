package io.github.andrexarlay.vulkancompat.rt.world;

/**
 * Host mirror of the metering mask in {@code passes/frame_stats.slang}: one whole 4x4 tile in sixteen, rotated by the
 * frame index, and the edge tiles clipped to the image. The pass counts only the samples it trusts, so the pixels it
 * weighed minus that count is the share the tracer refused - the readback of
 * {@code PATH_TRACE_FLAG_INVALID_SAMPLE} the frame otherwise never reports.
 */
final class RtStatsMetering {
    /** One tile in this many is metered; the shader's own rate, pinned against its source by the test. */
    static final int TILES_PER_SAMPLE = 16;

    private RtStatsMetering() {
    }

    /** The pixels the pass weighs for a frame of this extent and rotation: 16 per metered tile, edge tiles clipped. */
    static long meteredPixels(int width, int height, int frameIndex) {
        if (width <= 0 || height <= 0) {
            return 0L;
        }
        long tilesPerRow = (width + 3L) >> 2;
        long tilesPerColumn = (height + 3L) >> 2;
        // The shader adds an unsigned word; 2^32 is divisible by 16, so the signed rotation lands on the same tiles.
        long rotation = Integer.toUnsignedLong(frameIndex);
        long metered = 0L;
        for (long ty = 0; ty < tilesPerColumn; ty++) {
            long rowPixels = Math.min(4L, height - ty * 4L);
            long first = Math.floorMod(-(ty * tilesPerRow + rotation), TILES_PER_SAMPLE);
            for (long tx = first; tx < tilesPerRow; tx += TILES_PER_SAMPLE) {
                metered += rowPixels * Math.min(4L, width - tx * 4L);
            }
        }
        return metered;
    }

    /** The F3 split of a frame, each share in percent of the metered pixels. */
    record Breakdown(double invalidPct, double truncatedPct, double overflowPct) {
    }

    /**
     * The trace pass's own block ({@link RtStatsLayout#PATH_REJECTS}), or null when it holds no measurement this host can
     * vouch for: a metered word of 0 (the native core's stand-in, or an unwritten slot), a metered word that is not this
     * mask's count (host and shader drifted), or a count above the metered pixels.
     */
    static Breakdown breakdown(int width, int height, int frameIndex, int invalid, int truncated, int overflow,
                               int meteredWord) {
        long metered = meteredPixels(width, height, frameIndex);
        long written = Integer.toUnsignedLong(meteredWord);
        if (metered == 0L || written != metered) {
            return null;
        }
        long a = Integer.toUnsignedLong(invalid);
        long b = Integer.toUnsignedLong(truncated);
        long c = Integer.toUnsignedLong(overflow);
        if (a > metered || b > metered || c > metered) {
            return null;
        }
        return new Breakdown(100.0 * a / metered, 100.0 * b / metered, 100.0 * c / metered);
    }

    /**
     * Share (percent) of the metered pixels the tracer refused, or -1 when the frame holds no measurement: nothing
     * metered, no trusted sample, or more samples than the mask can hold (the host's copy of the shader disagrees).
     */
    static double rejectedPct(int width, int height, int frameIndex, int samples) {
        long metered = meteredPixels(width, height, frameIndex);
        long trusted = Integer.toUnsignedLong(samples);
        if (metered == 0L || trusted == 0L || trusted > metered) {
            return -1.0;
        }
        return 100.0 * (metered - trusted) / metered;
    }
}