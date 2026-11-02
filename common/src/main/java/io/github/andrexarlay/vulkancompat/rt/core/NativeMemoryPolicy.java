package io.github.andrexarlay.vulkancompat.rt.core;

/** Native renderer budget, separate from host/driver usage and from individual owner limits. */
public record NativeMemoryPolicy(long limitBytes, long blockBytes) {
    private static final long MIB = 1L << 20;
    public NativeMemoryPolicy {
        if (limitBytes < 64 * MIB || limitBytes > 65536 * MIB || blockBytes < MIB || blockBytes > 256 * MIB
            || (blockBytes & (blockBytes - 1)) != 0 || blockBytes > limitBytes)
            throw new IllegalArgumentException("native memory requires 64..65536 MiB budget and power-of-two 1..256 MiB blocks within it");
    }
    public static NativeMemoryPolicy defaults(long deviceLocalBytes) {
        long mib = deviceLocalBytes > 0 ? Math.clamp(deviceLocalBytes / 3 / MIB, 128, 3072) : 256;
        return new NativeMemoryPolicy(mib * MIB, 32 * MIB);
    }
    public static NativeMemoryPolicy configured(long deviceLocalBytes) {
        var defaults = defaults(deviceLocalBytes);
        long limit = Long.parseLong(System.getProperty("bedray.rt.nativeMemoryMiB", Long.toString(defaults.limitBytes / MIB)));
        long block = Long.parseLong(System.getProperty("bedray.rt.memoryBlockMiB", "32"));
        if (limit < 64 || limit > 65536 || block < 1 || block > 256)
            throw new IllegalArgumentException("invalid bedray.rt.nativeMemoryMiB/memoryBlockMiB");
        return new NativeMemoryPolicy(limit * MIB, block * MIB);
    }
}
