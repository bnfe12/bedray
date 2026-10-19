package io.github.andrexarlay.vulkancompat.shaderhost.pack;

/**
 * Hard limits for reading an untrusted shaderpack. Sizes are counted on the bytes actually decompressed, never on the size a zip
 * entry declares (zip bombs lie about it).
 *
 * @param maxEntries     max regular files in a pack
 * @param maxEntryBytes  max decompressed bytes of one file
 * @param maxTotalBytes  max decompressed bytes read from one pack in total
 * @param maxPathLength  max characters of a normalized entry path
 * @param maxDepth       max directory depth of an entry path
 */
public record ScanLimits(int maxEntries, long maxEntryBytes, long maxTotalBytes, int maxPathLength, int maxDepth) {
    /** Complementary r5.9.3: 468 entries, ~2.6 MB unpacked; Dirt RT: 181 entries. Limits leave two orders of magnitude. */
    public static final ScanLimits DEFAULT = new ScanLimits(50_000, 32L << 20, 512L << 20, 512, 32);

    public static ScanLimits defaults() {
        return DEFAULT;
    }

    public ScanLimits {
        if (maxEntries <= 0 || maxEntryBytes <= 0 || maxTotalBytes <= 0 || maxPathLength <= 0 || maxDepth <= 0) {
            throw new IllegalArgumentException("limits must be positive");
        }
    }
}
