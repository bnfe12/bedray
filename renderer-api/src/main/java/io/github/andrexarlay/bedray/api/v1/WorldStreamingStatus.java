package io.github.andrexarlay.bedray.api.v1;

/** Provider-reported coverage. Residency and budgets are diagnostics, never a gameplay verification claim. */
public record WorldStreamingStatus(boolean available, int requestedRenderDistanceChunks,
        int effectiveRenderDistanceChunks, int residentSections, int queuedSections,
        long usedGpuBytes, long budgetGpuBytes, long uploadedBytes,
        int discoveredSections, int emptySections, boolean memoryLimited, String reason) {
    public WorldStreamingStatus {
        if (requestedRenderDistanceChunks < 0 || effectiveRenderDistanceChunks < 0 || residentSections < 0
                || queuedSections < 0 || usedGpuBytes < 0 || budgetGpuBytes < 0 || uploadedBytes < 0
                || discoveredSections < 0 || emptySections < 0 || reason == null)
            throw new IllegalArgumentException("Invalid world streaming status");
    }
    public static WorldStreamingStatus unavailable() {
        return new WorldStreamingStatus(false, 0, 0, 0, 0, 0, 0, 0, 0, 0, false,
            "Provider does not support world streaming");
    }
}
